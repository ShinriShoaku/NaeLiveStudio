package ame.project.nlstudio.OBS

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Utility to optimize background videos for NL Studio.
 * Removes audio and resizes/crops to match target resolution.
 */
@OptIn(UnstableApi::class)
object VideoOptimizer {
    private const val TAG = "VideoOptimizer"

    interface Listener {
        fun onSuccess(outputUri: Uri)
        fun onError(e: Exception)
    }

    /** Satu baris data di manifest.json: video sumber apa yang sudah di-crop/optimize jadi file cache mana. */
    data class CachedVideoEntry(
        val sourceUri: String,
        val outputFile: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val sceneName: String?,
        val updatedAt: Long,
        val codec: String = "h264"
    )

    /**
     * Tangga resolusi standar (short-side, dalam px), dari besar ke kecil.
     * Dipakai untuk menentukan "satu tingkat di bawah" resolusi canvas.
     */
    private val RESOLUTION_TIERS = intArrayOf(2160, 1440, 1080, 720, 480, 360, 240)

    /**
     * @param canvasW lebar canvas/output composisi (mis. 1920)
     * @param canvasH tinggi canvas/output composisi (mis. 1080)
     * Menghitung resolusi video hasil optimize: SATU TINGKAT DI BAWAH resolusi canvas,
     * dengan aspect ratio yang identik dengan canvas (supaya crop-to-fit pas & saat di-scale up
     * lagi oleh GPU compositor pas render, tidak ada distorsi/pemotongan ganda).
     * Contoh: canvas 1920x1080 (tier 1080p) -> hasil 1280x720 (tier 720p).
     *         canvas 720x1520  (tier 720p)  -> hasil ~480x1013 (tier 480p).
     * Kalau canvas sudah di tier paling rendah (240p short side), tidak diturunkan lagi.
     */
    private fun computeDownscaledResolution(canvasW: Int, canvasH: Int): Pair<Int, Int> {
        val shortSide = min(canvasW, canvasH)
        val currentTierIndex = RESOLUTION_TIERS.indexOfFirst { it <= shortSide }
            .let { if (it == -1) RESOLUTION_TIERS.lastIndex else it }
        val nextTierIndex = (currentTierIndex + 1).coerceAtMost(RESOLUTION_TIERS.lastIndex)
        val nextTierShort = RESOLUTION_TIERS[nextTierIndex]

        if (nextTierShort >= shortSide) {
            // Sudah di tier paling kecil, tidak ada lagi yang bisa diturunkan.
            return Pair(canvasW, canvasH)
        }

        val factor = nextTierShort.toFloat() / shortSide.toFloat()
        val newW = max(2, (canvasW * factor).toInt())
        val newH = max(2, (canvasH * factor).toInt())
        return Pair(newW, newH)
    }

    // Batas bawah/atas short-side yang masuk akal untuk custom scale. Ini jaga-jaga kalau
    // scaleSetting yang masuk ternyata bukan format "angka+p" yang diharapkan (mis. "4K" akan
    // ke-parse jadi cuma angka "4" oleh filter{isDigit()} di bawah - tanpa batas ini hasilnya
    // video 2x2 piksel, bukan error yang kelihatan).
    private const val MIN_CUSTOM_SHORT_SIDE = 160
    private const val MAX_CUSTOM_SHORT_SIDE = 4320 // 8K, jauh di atas kebutuhan background video

    /**
     * Menghitung resolusi berdasarkan pilihan user (misal 720p).
     * Jika resolusi pilihan lebih besar atau sama dengan canvas, fallback ke "Default" (satu tingkat di bawah canvas).
     */
    private fun computeCustomResolution(canvasW: Int, canvasH: Int, scaleSetting: String): Pair<Int, Int> {
        val requestedShortSide = try {
            scaleSetting.filter { it.isDigit() }.toInt()
        } catch (_: Exception) {
            Log.w(TAG, "scaleSetting '$scaleSetting' tidak mengandung angka, fallback ke Default")
            return computeDownscaledResolution(canvasW, canvasH)
        }

        if (requestedShortSide !in MIN_CUSTOM_SHORT_SIDE..MAX_CUSTOM_SHORT_SIDE) {
            Log.w(TAG, "scaleSetting '$scaleSetting' -> $requestedShortSide di luar rentang wajar " +
                    "($MIN_CUSTOM_SHORT_SIDE-$MAX_CUSTOM_SHORT_SIDE), fallback ke Default")
            return computeDownscaledResolution(canvasW, canvasH)
        }

        val canvasShortSide = min(canvasW, canvasH)

        // Fallback: tidak boleh melebihi atau sama dengan resolusi canvas.
        // Jika user set canvas 720p dan pilih scale 720p, tetap turunkan (Default behavior).
        if (requestedShortSide >= canvasShortSide) {
            Log.d(TAG, "scaleSetting '$scaleSetting' ($requestedShortSide) >= canvas short side " +
                    "($canvasShortSide), pakai Default (satu tingkat di bawah canvas) sebagai gantinya")
            return computeDownscaledResolution(canvasW, canvasH)
        }

        val factor = requestedShortSide.toFloat() / canvasShortSide.toFloat()
        val newW = max(2, (canvasW * factor).toInt())
        val newH = max(2, (canvasH * factor).toInt())
        return Pair(newW, newH)
    }

    /**
     * Cek apakah device punya encoder hardware yang support HEVC (H.265).
     */
    private val isHevcSupported: Boolean by lazy {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal cek dukungan HEVC encoder, fallback ke H.264: ${e.message}")
            false
        }
    }

    /**
     * Optimizes a background video file:
     * 1. Removes audio.
     * 2. Menurunkan resolusi sesuai [scaleSetting]:
     *    - "Default": Satu tingkat di bawah canvas (targetW/targetH) - lihat [computeDownscaledResolution].
     *    - "1080p", "720p", dsb: Custom resolusi, tapi tidak akan melebihi resolusi canvas (fallback).
     * 3. Otomatis pakai encoder HEVC (H.265) kalau device support.
     * 4. Saves to a cache file.
     * 5. Mencatat hasilnya ke manifest.json.
     *
     * @param targetW/targetH ukuran CANVAS/output composisi.
     * @param scaleSetting pilihan skala: "Default", "1080p", "720p", "420p", "320p".
     */
    fun optimize(
        context: Context,
        inputUri: Uri,
        targetW: Int,
        targetH: Int,
        listener: Listener,
        sceneName: String? = null,
        scaleSetting: String = "Default"
    ) {
        optimizeInternal(context, inputUri, targetW, targetH, listener, sceneName, forceH264 = false, scaleSetting = scaleSetting)
    }

    /**
     * @param targetW/targetH ukuran CANVAS/output composisi.
     */
    private fun optimizeInternal(
        context: Context,
        inputUri: Uri,
        targetW: Int,
        targetH: Int,
        listener: Listener,
        sceneName: String?,
        forceH264: Boolean,
        scaleSetting: String = "Default"
    ) {
        // Use cache directory for optimized videos so they can be cleared by OS if needed
        val outputDir = File(context.cacheDir, "optimized_videos")
        if (!outputDir.exists()) outputDir.mkdirs()

        // Resolusi encode sebenarnya: sesuai setting atau default (satu tingkat di bawah canvas).
        val (downscaledW, downscaledH) = if (scaleSetting == "Default" || scaleSetting.isEmpty()) {
            computeDownscaledResolution(targetW, targetH)
        } else {
            computeCustomResolution(targetW, targetH, scaleSetting)
        }

        // Media3 Transformer requirements: dimensions must be even for most encoders
        val evenW = max(2, if (downscaledW % 2 == 0) downscaledW else downscaledW - 1)
        val evenH = max(2, if (downscaledH % 2 == 0) downscaledH else downscaledH - 1)

        val useHevc = !forceH264 && isHevcSupported
        val codecTag = if (useHevc) "h265" else "h264"

        // Generate a unique filename based on URI, resolusi hasil-encode, dan codec
        val hash = inputUri.toString().hashCode()
        val outputFileName = "opt_${hash}_${evenW}x${evenH}_$codecTag.mp4"
        val outputFile = File(outputDir, outputFileName)

        // If optimized version already exists, return it immediately
        if (outputFile.exists() && outputFile.length() > 0) {
            Log.d(TAG, "Optimized file already exists: ${outputFile.absolutePath}")
            recordManifestEntry(context, inputUri, outputFile, evenW, evenH, sceneName, codecTag)
            listener.onSuccess(Uri.fromFile(outputFile))
            return
        }

        Log.d(TAG, "Starting optimization: $inputUri -> canvas ${targetW}x$targetH, encode ${evenW}x$evenH ($codecTag)")

        val mediaItem = MediaItem.fromUri(inputUri)

        // LAYOUT_SCALE_TO_FIT_WITH_CROP performs a Center-Crop to fill the target resolution.
        // Karena evenW/evenH punya aspect ratio yang sama dengan canvas (lihat
        // computeDownscaledResolution), crop di sini SAMA PERSIS dengan crop yang akan terlihat
        // di canvas nanti - hanya resolusinya lebih kecil.
        val videoEffects = listOf<Effect>(
            Presentation.createForWidthAndHeight(
                evenW,
                evenH,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
        )

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        // Wrap the edited item in a Sequence and then a Composition using Builders to avoid deprecation
        val sequence = EditedMediaItemSequence.Builder(editedMediaItem).build()
        val composition = Composition.Builder(sequence).build()

        // Resource optimization: Control bitrate to save disk space and reduce decoding load
        val targetBitrate = calculateTargetBitrate(evenW, evenH, useHevc)
        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(targetBitrate)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .build()

        val transformer = Transformer.Builder(context)
            .setEncoderFactory(encoderFactory)
            .setVideoMimeType(if (useHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.d(TAG, "Optimization completed: ${outputFile.absolutePath}")
                    recordManifestEntry(context, inputUri, outputFile, evenW, evenH, sceneName, codecTag)
                    listener.onSuccess(Uri.fromFile(outputFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (outputFile.exists()) outputFile.delete()
                    if (useHevc) {
                        // Encoder HEVC ternyata gagal di tengah proses (device "bilang" support
                        // tapi rewel) - otomatis coba ulang pakai H.264 supaya user tidak lihat error.
                        Log.w(TAG, "Optimisasi HEVC gagal, retry pakai H.264", exportException)
                        optimizeInternal(context, inputUri, targetW, targetH, listener, sceneName, forceH264 = true, scaleSetting = scaleSetting)
                    } else {
                        Log.e(TAG, "Optimization failed", exportException)
                        listener.onError(exportException)
                    }
                }
            })
            .build()

        try {
            // Start the transformation process
            transformer.start(composition, outputFile.absolutePath)
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            if (useHevc) {
                Log.w(TAG, "Gagal start transformer HEVC, retry pakai H.264", e)
                optimizeInternal(context, inputUri, targetW, targetH, listener, sceneName, forceH264 = true, scaleSetting = scaleSetting)
            } else {
                Log.e(TAG, "Failed to start transformer", e)
                listener.onError(e)
            }
        }
    }

    /**
     * Calculates a reasonable bitrate (bps) for the given resolution.
     * Balances quality and file size for background video use cases.
     * @param isHevc kalau true, bitrate diturunkan lagi (~38%) karena HEVC jauh lebih efisien
     * dibanding H.264 di kualitas visual yang sama.
     */
    private fun calculateTargetBitrate(width: Int, height: Int, isHevc: Boolean): Int {
        val pixels = width * height
        val baseBitrate = when {
            pixels <= 640 * 480 -> 1_500_000 // 1.5 Mbps
            pixels <= 1280 * 720 -> 3_000_000 // 3.0 Mbps
            pixels <= 1920 * 1080 -> 6_000_000 // 6.0 Mbps
            else -> 10_000_000 // 10.0 Mbps
        }
        return if (isHevc) (baseBitrate * 0.62).toInt() else baseBitrate
    }

    // ===================== MANIFEST (JSON) - catatan video yang sudah di-cache =====================
    // Manifest ini adalah satu file JSON (optimized_videos/manifest.json) yang mendata SETIAP
    // video hasil optimize(): dari URI asli mana, jadi file cache mana, resolusi berapa, untuk
    // scene apa. Berguna untuk: (1) debugging/monitoring berapa banyak & seberapa besar video
    // yang sudah di-cache, (2) basis untuk fitur "Cache Manager"/cleanup di UI kalau dibutuhkan,
    // (3) menghapus cache yang sudah tidak dipakai saat scene dihapus (lihat deleteCachedOutputFile).

    private fun manifestFile(context: Context): File =
        File(File(context.cacheDir, "optimized_videos"), "manifest.json")

    private fun readManifest(context: Context): JSONArray {
        val file = manifestFile(context)
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Manifest cache video korup, reset baru: ${e.message}")
            JSONArray()
        }
    }

    private fun writeManifest(context: Context, array: JSONArray) {
        try {
            val file = manifestFile(context)
            if (!file.parentFile!!.exists()) file.parentFile!!.mkdirs()
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menulis manifest cache video", e)
        }
    }

    @Synchronized
    private fun recordManifestEntry(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        width: Int,
        height: Int,
        sceneName: String?,
        codec: String = "h264"
    ) {
        try {
            val array = readManifest(context)
            // Buang entry lama yang mengarah ke output file yang sama biar tidak dobel.
            val filtered = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optString("outputFile") != outputFile.absolutePath) {
                    filtered.put(obj)
                }
            }
            val entry = JSONObject().apply {
                put("sourceUri", sourceUri.toString())
                put("outputFile", outputFile.absolutePath)
                put("width", width)
                put("height", height)
                put("sizeBytes", outputFile.length())
                put("sceneName", sceneName)
                put("updatedAt", System.currentTimeMillis())
                put("codec", codec)
            }
            filtered.put(entry)
            writeManifest(context, filtered)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mencatat manifest cache video", e)
        }
    }

    /**
     * Ambil semua data video yang pernah di-cache/optimize (isi manifest.json), diurutkan dari
     * yang paling baru diperbarui. Bisa dipakai untuk menampilkan "Cache Manager" di UI, atau
     * sekadar debugging berapa banyak ruang yang dipakai video cache.
     */
    @Synchronized
    fun getCachedVideoManifest(context: Context): List<CachedVideoEntry> {
        val array = readManifest(context)
        val result = mutableListOf<CachedVideoEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            result.add(
                CachedVideoEntry(
                    sourceUri = obj.optString("sourceUri"),
                    outputFile = obj.optString("outputFile"),
                    width = obj.optInt("width"),
                    height = obj.optInt("height"),
                    sizeBytes = obj.optLong("sizeBytes"),
                    sceneName = if (obj.isNull("sceneName")) null else obj.optString("sceneName"),
                    updatedAt = obj.optLong("updatedAt"),
                    codec = if (obj.has("codec")) obj.optString("codec") else "h264"
                )
            )
        }
        return result.sortedByDescending { it.updatedAt }
    }

    /**
     * Hapus 1 file cache video hasil optimize (dan entry manifest-nya) berdasarkan output URI-nya
     * (yaitu Uri yang tersimpan sebagai backgroundUri scene, format file://...). Panggil ini saat
     * scene dihapus atau background video-nya diganti, supaya cache lama tidak numpuk percuma.
     */
    @Synchronized
    fun deleteCachedOutputFile(context: Context, outputUriString: String) {
        try {
            val path = Uri.parse(outputUriString).path ?: return
            val target = File(path)
            if (target.exists()) target.delete()

            val array = readManifest(context)
            val kept = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optString("outputFile") != target.absolutePath) kept.put(obj)
            }
            writeManifest(context, kept)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menghapus cache video: $outputUriString", e)
        }
    }

    /**
     * Helper untuk mendapatkan URI asli dari video yang sudah di-optimize.
     * Mencari di manifest berdasarkan path file output.
     */
    fun getOriginalUri(context: Context, optimizedUri: String): Uri? {
        val path = try { Uri.parse(optimizedUri).path ?: "" } catch (_: Exception) { "" }
        if (path.isEmpty()) return null
        return getCachedVideoManifest(context).find { it.outputFile == path }?.let { Uri.parse(it.sourceUri) }
    }

    /**
     * Mengecek apakah video tertentu perlu di-optimize ulang berdasarkan setting saat ini.
     * Berguna untuk menentukan apakah perlu menampilkan progress dialog ke user saat Record/Live.
     *
     * @param originalUri URI video asli (sebelum di-optimize).
     * @param currentOptimizedUri URI video yang saat ini tersimpan di scene (bisa original, bisa optimized).
     * @param targetW/targetH Ukuran Canvas saat ini.
     * @param scaleSetting Setting skala video background (misal "Default", "720p").
     * @return true jika video perlu di-scale ulang (karena setting berubah atau file hilang).
     */
    fun isOptimizationRequired(
        context: Context,
        originalUri: Uri,
        currentOptimizedUri: String?,
        targetW: Int,
        targetH: Int,
        scaleSetting: String = "Default"
    ): Boolean {
        // 1. Jika belum ada video ter-optimize sama sekali, atau URI-nya masih URI asli
        if (currentOptimizedUri.isNullOrEmpty()) return true
        
        // Jika URI saat ini sama dengan URI asli, berarti belum di-optimize
        if (originalUri.toString() == currentOptimizedUri) return true

        // 2. Jika URI-nya tidak berada di folder cache "optimized_videos", anggap belum di-optimize
        if (!currentOptimizedUri.contains("optimized_videos")) return true

        // 3. Hitung resolusi yang seharusnya (target) sesuai setting sekarang (termasuk fallback logic)
        val (downscaledW, downscaledH) = if (scaleSetting == "Default" || scaleSetting.isEmpty()) {
            computeDownscaledResolution(targetW, targetH)
        } else {
            computeCustomResolution(targetW, targetH, scaleSetting)
        }
        val evenW = max(2, if (downscaledW % 2 == 0) downscaledW else downscaledW - 1)
        val evenH = max(2, if (downscaledH % 2 == 0) downscaledH else downscaledH - 1)
        
        // 4. Cek di manifest apakah file tersebut benar punya resolusi sesuai target
        val manifest = getCachedVideoManifest(context)
        val currentPath = try { Uri.parse(currentOptimizedUri).path ?: "" } catch (_: Exception) { "" }
        val entry = manifest.find { it.outputFile == currentPath }
        
        if (entry != null) {
            // Jika resolusi di manifest berbeda dengan target sekarang, berarti setting berubah (Misal dari 720p ke 480p)
            if (entry.width != evenW || entry.height != evenH) {
                Log.d(TAG, "Re-optimization required: Current ${entry.width}x${entry.height} != Target ${evenW}x${evenH}")
                return true
            }
            
            // Cek codec juga (siapa tau device baru support HEVC atau sebaliknya)
            val useHevc = isHevcSupported
            val expectedCodec = if (useHevc) "h265" else "h264"
            if (entry.codec != expectedCodec) return true
        } else {
            // Jika tidak ada di manifest, kita verifikasi fisik filenya saja
            if (currentPath.isEmpty() || !File(currentPath).exists()) return true
            
            // Jika file ada tapi tidak ada di manifest, kita tidak bisa menjamin resolusinya pas, 
            // jadi lebih aman kita kembalikan true supaya di-optimize ulang secara valid.
            return true
        }
        
        // 5. Terakhir, pastikan filenya masih ada secara fisik
        return !File(currentPath).exists()
    }
}
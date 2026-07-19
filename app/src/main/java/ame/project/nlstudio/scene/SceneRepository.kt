package ame.project.nlstudio.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Nyimpen daftar scene ke SharedPreferences.
 * Fix: Default rootWidth/Height sekarang otomatis mengikuti resolusi native perangkat.
 */
class SceneRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val displayMetrics = context.resources.displayMetrics

    fun loadScenes(): MutableList<Scene> {
        val raw = prefs.getString(KEY_SCENES, null) ?: return defaultScenes()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Scene>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val layers = mutableListOf<SceneLayer>()
                val layerArr = o.optJSONArray("layers")
                if (layerArr != null) {
                    for (j in 0 until layerArr.length()) {
                        val lo = layerArr.getJSONObject(j)
                        layers.add(
                            SceneLayer(
                                id = lo.getString("id"),
                                type = LayerType.valueOf(lo.optString("type", "IMAGE")),
                                uri = lo.getString("uri"),
                                x = lo.getDouble("x").toFloat(),
                                y = lo.getDouble("y").toFloat(),
                                w = lo.getDouble("w").toFloat(),
                                h = lo.getDouble("h").toFloat(),
                                zIndex = lo.optInt("zIndex", 0)
                            )
                        )
                    }
                }
                list.add(
                    Scene(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        backgroundType = BackgroundType.valueOf(o.getString("backgroundType")),
                        backgroundUri = o.optStringOrNull("backgroundUri"),
                        layers = layers,
                        thumbnailPath = o.optStringOrNull("thumbnailPath"),
                        rootWidth = o.optInt("rootWidth", displayMetrics.widthPixels),
                        rootHeight = o.optInt("rootHeight", displayMetrics.heightPixels)
                    )
                )
            }
            if (list.none { it.backgroundType == BackgroundType.SCREEN }) {
                list.add(0, defaultScreenScene())
            }
            list
        } catch (e: Exception) {
            defaultScenes()
        }
    }

    fun saveScenes(scenes: List<Scene>) {
        val arr = JSONArray()
        scenes.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            o.put("backgroundType", s.backgroundType.name)
            o.put("backgroundUri", s.backgroundUri ?: JSONObject.NULL)
            o.put("thumbnailPath", s.thumbnailPath ?: JSONObject.NULL)
            o.put("rootWidth", s.rootWidth)
            o.put("rootHeight", s.rootHeight)
            val layerArr = JSONArray()
            s.layers.forEach { l ->
                val lo = JSONObject()
                lo.put("id", l.id)
                lo.put("type", l.type.name)
                lo.put("uri", l.uri)
                lo.put("x", l.x)
                lo.put("y", l.y)
                lo.put("w", l.w)
                lo.put("h", l.h)
                lo.put("zIndex", l.zIndex)
                layerArr.put(lo)
            }
            o.put("layers", layerArr)
            arr.put(o)
        }
        prefs.edit().putString(KEY_SCENES, arr.toString()).apply()
    }

    fun defaultScreenScene() = Scene(
        id = ID_SCREEN, 
        name = "Layar HP", 
        backgroundType = BackgroundType.SCREEN,
        rootWidth = displayMetrics.widthPixels,
        rootHeight = displayMetrics.heightPixels
    )

    private fun defaultScenes(): MutableList<Scene> = mutableListOf(defaultScreenScene())

    fun buildOrUpdateScene(
        existingId: String?,
        name: String,
        backgroundType: BackgroundType,
        backgroundUri: String?,
        layers: List<SceneLayer>,
        rootWidth: Int? = null,
        rootHeight: Int? = null
    ): Scene {
        val thumb = renderThumbnail(backgroundType, backgroundUri, layers)
        return Scene(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            backgroundType = backgroundType,
            backgroundUri = backgroundUri,
            layers = layers.map { it.copy() }.toMutableList(),
            thumbnailPath = thumb,
            rootWidth = rootWidth ?: displayMetrics.widthPixels,
            rootHeight = rootHeight ?: displayMetrics.heightPixels
        )
    }

    fun deleteThumbnail(scene: Scene) {
        val path = scene.thumbnailPath ?: return
        try {
            File(path).delete()
        } catch (ignored: Exception) {
        }
    }

    fun toJson(scene: Scene): String {
        val o = JSONObject()
        o.put("backgroundType", scene.backgroundType.name)
        o.put("backgroundUri", scene.backgroundUri ?: JSONObject.NULL)
        o.put("rootWidth", scene.rootWidth)
        o.put("rootHeight", scene.rootHeight)
        val layerArr = JSONArray()
        scene.layers.forEach { l ->
            val lo = JSONObject()
            lo.put("uri", l.uri)
            lo.put("type", l.type.name)
            lo.put("x", l.x)
            lo.put("y", l.y)
            lo.put("w", l.w)
            lo.put("h", l.h)
            lo.put("zIndex", l.zIndex)
            layerArr.put(lo)
        }
        o.put("layers", layerArr)
        return o.toString()
    }

    fun loadLayerPreviewBitmap(layer: SceneLayer): Bitmap? {
        return when (layer.type) {
            LayerType.VIDEO -> extractVideoFrame(Uri.parse(layer.uri))
            LayerType.SCREEN -> renderScreenPlaceholder()
            LayerType.VOICE_ANIM -> loadVoiceAnimPreview(layer)
            LayerType.TIKTOK_CHAT -> renderTikTokChatPlaceholder()
            LayerType.TIKTOK_GIFT -> renderTikTokGiftPlaceholder()
            LayerType.TIKTOK_JOIN -> renderTikTokJoinPlaceholder()
            LayerType.MUSIC_CURRENT -> renderMusicCurrentPlaceholder()
            LayerType.MUSIC_QUEUE -> renderMusicQueuePlaceholder()
            else -> loadPreviewBitmap(Uri.parse(layer.uri))
        }
    }

    private fun renderTikTokJoinPlaceholder(): Bitmap {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#B0000000"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8A6")
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("JOIN!", 80f, 40f, paint)
        paint.color = Color.CYAN
        paint.textSize = 10f
        canvas.drawText("User Bergabung", 80f, 65f, paint)
        return bmp
    }

    private fun renderTikTokGiftPlaceholder(): Bitmap {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#B0000000"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8A6")
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("GIFT!", 80f, 40f, paint)
        paint.color = Color.YELLOW
        paint.textSize = 10f
        canvas.drawText("User mengirim Mawar x10", 80f, 65f, paint)
        return bmp
    }

    private fun renderTikTokChatPlaceholder(): Bitmap {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#B0000000"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8A6")
            textSize = 14f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("TikTok Chat", 10f, 25f, paint)
        paint.color = Color.WHITE
        paint.textSize = 10f
        canvas.drawText("User: Halo!", 10f, 45f, paint)
        canvas.drawText("User2: Keren!", 10f, 65f, paint)
        return bmp
    }

    private fun renderMusicCurrentPlaceholder(): Bitmap {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#B0000000"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8A6")
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("MUSIC PLAYER", 80f, 35f, paint)
        paint.color = Color.WHITE
        paint.textSize = 10f
        canvas.drawText("Song Title - Artist", 80f, 60f, paint)
        return bmp
    }

    private fun renderMusicQueuePlaceholder(): Bitmap {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#B0000000"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8A6")
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("MUSIC QUEUE", 80f, 35f, paint)
        paint.color = Color.GRAY
        paint.textSize = 9f
        canvas.drawText("1. Song A - User X", 80f, 55f, paint)
        canvas.drawText("2. Song B - User Y", 80f, 75f, paint)
        return bmp
    }

    private fun loadVoiceAnimPreview(layer: SceneLayer): Bitmap? {
        val prefs = context.getSharedPreferences("voice_anim_prefs", Context.MODE_PRIVATE)
        val configJson = prefs.getString("default_config", null)
        val config = VoiceAnimConfig.fromJson(configJson)
        val firstImage = config.items.firstOrNull { it.imageUri.isNotEmpty() }?.imageUri
        return if (firstImage != null) loadPreviewBitmap(Uri.parse(firstImage)) 
               else renderTextToBitmap("VOICE ANIM")
    }

    private fun renderScreenPlaceholder(): Bitmap {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("LAYAR HP", 80f, 55f, paint)
        return bmp
    }

    fun loadPreviewBitmap(uri: Uri): Bitmap? {
        if (uri.scheme == "text") {
            val text = uri.schemeSpecificPart ?: ""
            return renderTextToBitmap(text)
        }
        return loadBitmapPreserveAspect(uri, PREVIEW_MAX_DIM)
    }

    private fun renderTextToBitmap(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            style = Paint.Style.FILL
            textAlign = Paint.Align.LEFT
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)

        val width = (bounds.width() + 40).coerceAtLeast(100)
        val height = (bounds.height() + 40).coerceAtLeast(100)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint().apply { color = Color.parseColor("#80000000") }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        canvas.drawText(text, 20f, height / 2f + bounds.height() / 2f - 5f, paint)
        return bitmap
    }

    fun extractVideoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun renderThumbnail(backgroundType: BackgroundType, backgroundUri: String?, layers: List<SceneLayer>): String? {
        val w = THUMB_W
        val h = THUMB_H
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val bg: Bitmap? = when (backgroundType) {
            BackgroundType.IMAGE -> backgroundUri?.let { loadPreviewBitmap(Uri.parse(it)) }
            BackgroundType.VIDEO -> backgroundUri?.let { extractVideoFrame(Uri.parse(it)) }
            else -> null
        }
        if (bg != null) {
            val dst = fillRect(bg.width, bg.height, w, h)
            canvas.drawBitmap(bg, null, dst, null)
        }

        layers.sortedBy { it.zIndex }.forEach { layer ->
            val bmp = loadLayerPreviewBitmap(layer) ?: return@forEach
            val dst = RectF(layer.x * w, layer.y * h, (layer.x + layer.w) * w, (layer.y + layer.h) * h)
            canvas.drawBitmap(bmp, null, dst, null)
        }

        return saveThumbnailBitmap(out)
    }

    private fun fillRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val scale = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = Math.round(srcW * scale)
        val h = Math.round(srcH * scale)
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun loadBitmapPreserveAspect(uri: Uri, maxDim: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return null
                val scale = minOf(1f, maxDim.toFloat() / maxOf(original.width, original.height))
                if (scale >= 1f) original
                else Bitmap.createScaledBitmap(original, Math.round(original.width * scale), Math.round(original.height * scale), true)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveThumbnailBitmap(bitmap: Bitmap): String? {
        return try {
            val dir = File(context.cacheDir, "scene_thumbs").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key, "")
        return v.ifEmpty { null }
    }

    companion object {
        private const val PREFS_NAME = "nlstudio_scenes"
        private const val KEY_SCENES = "scenes_json"
        private const val THUMB_W = 180
        private const val THUMB_H = 320
        private const val PREVIEW_MAX_DIM = 480
        const val ID_SCREEN = "screen_default"
    }
}

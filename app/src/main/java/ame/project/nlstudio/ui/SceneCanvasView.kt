package ame.project.nlstudio.ui

import ame.project.nlstudio.R
import ame.project.nlstudio.scene.LayerType
import ame.project.nlstudio.scene.SceneLayer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Kanvas editor scene ala OBS:
 * - Background nutup penuh area kanvas: gambar polos/statis lewat ImageView (setBackgroundBitmap),
 *   ATAU video yang beneran JALAN (motion, bukan cuma 1 frame) lewat TextureView+MediaPlayer
 *   (setBackgroundVideo/playVideo/pauseVideo/seekVideoTo).
 * - Tiap layer (gambar/icon/teks/video) ditampilin sebagai kotak yang bisa di-DRAG dan di-RESIZE,
 *   dan bisa DI-TAP buat dipilih (chip yang lagi kepilih dikasih highlight border hijau).
 * - Posisi & ukuran layer disimpan dalam rasio 0..1 terhadap ukuran kanvas.
 */
class SceneCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val backgroundImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val backgroundVideoView = TextureView(context)
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var pendingVideoUri: Uri? = null
    private var videoLoop = true
    private var videoAutoPlay = true
    private var videoNaturalW = 0
    private var videoNaturalH = 0

    // FIX: Rasio aspek target (rootWidth / rootHeight) agar editor sinkron dengan hasil live.
    private var targetAspectRatio: Float = 0f

    /** Dipanggil tiap kali user selesai drag/resize sebuah layer (posisi barunya sudah ke-update di object SceneLayer-nya langsung). */
    var onLayerUpdated: ((SceneLayer) -> Unit)? = null

    /** Dipanggil pas user tap sebuah layer, dipakai buat nge-highlight & ngaktifin tombol "Hapus layer ini". */
    var onLayerSelected: ((SceneLayer) -> Unit)? = null

    /** Dipanggil sekali durasi video background diketahui (buat isi max SeekBar di panel editor). */
    var onVideoDurationReady: ((Int) -> Unit)? = null

    /** Dipanggil berkala selama video jalan (buat update posisi SeekBar di panel editor). */
    var onVideoProgress: ((Int) -> Unit)? = null

    private var selectedLayerId: String? = null

    private val voiceAnimViews = mutableMapOf<String, ImageView>()
    private var currentLayers: List<SceneLayer> = emptyList()

    private val minLayerPx get() = (48 * resources.displayMetrics.density).toInt()

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null) {
                try {
                    if (mp.isPlaying) onVideoProgress?.invoke(mp.currentPosition)
                } catch (ignored: Exception) {
                }
            }
            progressHandler.postDelayed(this, 250)
        }
    }

    init {
        // Gunakan border hijau agar user tahu batas kanvas yang sebenarnya
        setBackgroundResource(R.drawable.bg_canvas_border)
        addView(backgroundImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        backgroundVideoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                videoSurface = Surface(st)
                pendingVideoUri?.let { startPlayback(it) }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                applyVideoTransform()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                videoSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        progressHandler.post(progressTicker)
    }

    /** 
     * Ngatur rasio aspek kanvas agar sesuai dengan resolusi output (Portrait/Landscape).
     * View akan menyesuaikan ukurannya di dalam parent tanpa merusak proporsi.
     */
    fun setTargetAspectRatio(ratio: Float) {
        if (targetAspectRatio != ratio) {
            targetAspectRatio = ratio
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (targetAspectRatio > 0) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
            val maxHeight = MeasureSpec.getSize(heightMeasureSpec)

            var finalWidth = maxWidth
            var finalHeight = maxHeight

            val currentRatio = maxWidth.toFloat() / maxHeight
            if (currentRatio > targetAspectRatio) {
                // Parent terlalu lebar -> kecilkan lebar view
                finalWidth = (maxHeight * targetAspectRatio).toInt()
            } else {
                // Parent terlalu tinggi -> kecilkan tinggi view
                finalHeight = (maxWidth / targetAspectRatio).toInt()
            }

            super.onMeasure(
                MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressHandler.removeCallbacks(progressTicker)
        releasePlayer()
    }

    // ---------------------------------------------------------------------
    // Background: gambar statis / warna
    // ---------------------------------------------------------------------

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        showImageBackground()
        backgroundImageView.setImageBitmap(bitmap)
    }

    // ---------------------------------------------------------------------
    // Background: video yang beneran main (motion), bukan cuma 1 frame
    // ---------------------------------------------------------------------

    /** Pasang & mulai muterin video background. Panggil dengan uri=null buat matiin mode video. */
    fun setBackgroundVideo(uri: Uri?, loop: Boolean = true, autoPlay: Boolean = true) {
        if (uri == null) {
            showImageBackground()
            backgroundImageView.setImageDrawable(null)
            return
        }
        videoLoop = loop
        videoAutoPlay = autoPlay
        showVideoBackground()
        if (uri == pendingVideoUri && mediaPlayer != null) {
            // uri sama & player masih hidup -> cuma update flag loop/autoplay, gak perlu restart
            mediaPlayer?.isLooping = loop
            return
        }
        pendingVideoUri = uri
        if (videoSurface != null) startPlayback(uri)
        // kalau surface belum siap, onSurfaceTextureAvailable yang bakal mulai playback-nya
    }

    fun playVideo() {
        try {
            mediaPlayer?.start()
        } catch (ignored: Exception) {
        }
    }

    fun pauseVideo() {
        try {
            mediaPlayer?.pause()
        } catch (ignored: Exception) {
        }
    }

    fun isVideoPlaying(): Boolean = try {
        mediaPlayer?.isPlaying ?: false
    } catch (e: Exception) {
        false
    }

    fun seekVideoTo(ms: Int) {
        try {
            mediaPlayer?.seekTo(ms)
        } catch (ignored: Exception) {
        }
    }

    fun getVideoDuration(): Int = try {
        mediaPlayer?.duration ?: 0
    } catch (e: Exception) {
        0
    }

    fun setVideoLooping(loop: Boolean) {
        videoLoop = loop
        try {
            mediaPlayer?.isLooping = loop
        } catch (ignored: Exception) {
        }
    }

    private fun showImageBackground() {
        pendingVideoUri = null
        releasePlayer()
        if (backgroundVideoView.parent != null) removeView(backgroundVideoView)
        if (backgroundImageView.parent == null) addView(backgroundImageView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showVideoBackground() {
        if (backgroundImageView.parent != null) removeView(backgroundImageView)
        if (backgroundVideoView.parent == null) addView(backgroundVideoView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun startPlayback(uri: Uri) {
        releasePlayer()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(context, uri)
            mp.setSurface(videoSurface)
            mp.isLooping = videoLoop
            mp.setVolume(0f, 0f) // audio video preview TIDAK dipakai, sama kayak jalur live (audio dari AudioMixSource)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoNaturalW = w
                videoNaturalH = h
                applyVideoTransform()
            }
            mp.setOnPreparedListener {
                applyVideoTransform()
                onVideoDurationReady?.invoke(it.duration)
                if (videoAutoPlay) it.start()
            }
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (ignored: Exception) {
        }
    }

    /** Center-crop manual buat TextureView (biar konsisten sama fillRect di CompositeSceneVideoSource, gak ada bar hitam). */
    private fun applyVideoTransform() {
        val viewW = backgroundVideoView.width.toFloat()
        val viewH = backgroundVideoView.height.toFloat()
        if (viewW <= 0 || viewH <= 0 || videoNaturalW <= 0 || videoNaturalH <= 0) return

        val scale = maxOf(viewW / videoNaturalW, viewH / videoNaturalH)
        val scaledW = videoNaturalW * scale
        val scaledH = videoNaturalH * scale

        val matrix = Matrix()
        matrix.setScale(scaledW / viewW, scaledH / viewH, viewW / 2f, viewH / 2f)
        backgroundVideoView.setTransform(matrix)
    }

    private fun releasePlayer() {
        val mp = mediaPlayer ?: return
        mediaPlayer = null
        try {
            mp.setOnPreparedListener(null)
            mp.setOnVideoSizeChangedListener(null)
            mp.setOnErrorListener(null)
            mp.stop()
        } catch (ignored: Exception) {
        }
        try {
            mp.release()
        } catch (ignored: Exception) {
        }
    }

    // ---------------------------------------------------------------------
    // Layers (chip overlay: bisa di-drag, di-resize, di-tap buat dipilih)
    // ---------------------------------------------------------------------

    fun setVoiceAnimLevel(level: Float, sceneRepository: ame.project.nlstudio.scene.SceneRepository) {
        val prefs = context.getSharedPreferences("voice_anim_prefs", Context.MODE_PRIVATE)
        val configJson = prefs.getString("default_config", null)
        val config = ame.project.nlstudio.scene.VoiceAnimConfig.fromJson(configJson)
        
        val item = config.items.filter { it.imageUri.isNotEmpty() }
            .sortedByDescending { it.threshold }
            .firstOrNull { level >= it.threshold } ?: return

        // Normalize level based on threshold start and end from config
        val range = (config.effectThresholdEnd - config.effectThresholdStart).coerceAtLeast(0.01f)
        val normalizedLevel = ((level - config.effectThresholdStart) / range).coerceIn(0f, 1f)

        // Brightness effect using dynamic minBrightness from config
        val brightness = config.minBrightness + (normalizedLevel * (1f - config.minBrightness))
        val matrix = android.graphics.ColorMatrix().apply {
            setScale(brightness, brightness, brightness, 1f)
        }
        val filter = android.graphics.ColorMatrixColorFilter(matrix)

        // Scale effect using dynamic scaleIntensity from config
        val baseScale = 1.0f - (config.scaleIntensity / 2f)
        val scale = baseScale + (normalizedLevel * config.scaleIntensity)

        voiceAnimViews.forEach { (_, iv) ->
            iv.setImageBitmap(sceneRepository.loadPreviewBitmap(android.net.Uri.parse(item.imageUri)))
            iv.colorFilter = filter
            iv.scaleX = scale
            iv.scaleY = scale
        }
    }

    /** Highlight chip yang lagi dipilih tanpa perlu rebuild semua chip dari nol. */
    fun setSelectedLayer(layerId: String?) {
        selectedLayerId = layerId
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.tag is String) {
                applySelectionStyle(child, child.tag == layerId)
            }
        }
    }

    /** Gambar ulang semua layer chip di atas background sesuai data terbaru, TANPA nyentuh view background
     *  (biar video background yang lagi main gak ke-restart tiap kali ada layer ditambah/digeser). */
    fun setLayers(layers: List<SceneLayer>, bitmapLoader: (SceneLayer) -> Bitmap?) {
        currentLayers = layers
        voiceAnimViews.clear()
        val chipsToRemove = mutableListOf<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== backgroundImageView && child !== backgroundVideoView) chipsToRemove.add(child)
        }
        chipsToRemove.forEach { removeView(it) }

        layers.sortedBy { it.zIndex }.forEach { layer ->
            val chip = buildLayerChip(layer, bitmapLoader(layer))
            if (layer.type == LayerType.VOICE_ANIM) {
                val contentIv = chip.findViewById<ImageView>(R.id.layer_content_iv)
                if (contentIv != null) voiceAnimViews[layer.id] = contentIv
            }
            addView(chip)
        }
    }

    private fun buildLayerChip(layer: SceneLayer, bitmap: Bitmap?): View {
        val chip = FrameLayout(context)
        chip.tag = layer.id

        val content = ImageView(context).apply {
            id = R.id.layer_content_iv
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
        }
        chip.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Badge kecil pojok kiri-atas nunjukin tipe layer (video/teks), biar jelas apa isinya
        // meskipun belum di-tap.
        val badgeIcon = when (layer.type) {
            LayerType.VIDEO -> android.R.drawable.ic_media_play
            LayerType.TEXT -> android.R.drawable.ic_menu_edit
            LayerType.VOICE_ANIM -> android.R.drawable.ic_btn_speak_now
            else -> null
        }
        if (badgeIcon != null) {
            val badgeSizePx = (16 * resources.displayMetrics.density).toInt()
            val badge = ImageView(context).apply {
                setImageResource(badgeIcon)
                setBackgroundColor(Color.parseColor("#B0000000"))
                setColorFilter(Color.parseColor("#7FD8A6"))
                setPadding(2, 2, 2, 2)
            }
            chip.addView(badge, LayoutParams(badgeSizePx, badgeSizePx, Gravity.TOP or Gravity.START))
        }

        applyChipLayoutParams(chip, layer)
        applySelectionStyle(chip, layer.id == selectedLayerId)

        val handleSizePx = (18 * resources.displayMetrics.density).toInt()
        val handle = View(context).apply { setBackgroundColor(Color.parseColor("#7FD8A6")) }
        chip.addView(handle, LayoutParams(handleSizePx, handleSizePx, Gravity.BOTTOM or Gravity.END))

        setupDragTouch(chip, layer)
        setupResizeTouch(handle, chip, layer)
        chip.setOnClickListener {
            setSelectedLayer(layer.id)
            onLayerSelected?.invoke(layer)
        }

        return chip
    }

    private fun applySelectionStyle(chip: View, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_layer_border_selected else R.drawable.bg_layer_border)
    }

    private fun applyChipLayoutParams(chip: View, layer: SceneLayer) {
        val w = width
        val h = height
        if (w == 0 || h == 0) {
            // ukuran kanvas belum diketahui (belum selesai layout) -> coba lagi setelah layout siap
            post { if (isAttachedToWindow) applyChipLayoutParams(chip, layer) }
            return
        }
        val lp = LayoutParams(
            (layer.w * w).toInt().coerceAtLeast(minLayerPx),
            (layer.h * h).toInt().coerceAtLeast(minLayerPx)
        )
        lp.leftMargin = (layer.x * w).toInt().coerceIn(0, w)
        lp.topMargin = (layer.y * h).toInt().coerceIn(0, h)
        chip.layoutParams = lp
    }

    private fun setupDragTouch(chip: View, layer: SceneLayer) {
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var moved = false

        chip.setOnTouchListener { v, event ->
            val lp = v.layoutParams as LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
                    lp.leftMargin = (startLeft + dx).coerceIn(0, (width - lp.width).coerceAtLeast(0))
                    lp.topMargin = (startTop + dy).coerceIn(0, (height - lp.height).coerceAtLeast(0))
                    v.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (width > 0 && height > 0 && moved) {
                        layer.x = lp.leftMargin.toFloat() / width
                        layer.y = lp.topMargin.toFloat() / height
                        onLayerUpdated?.invoke(layer)
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupResizeTouch(handle: View, chip: View, layer: SceneLayer) {
        var downRawX = 0f
        var downRawY = 0f
        var startW = 0
        var startH = 0

        handle.setOnTouchListener { _, event ->
            val lp = chip.layoutParams as LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startW = lp.width
                    startH = lp.height
                    setSelectedLayer(layer.id)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    var newW = (startW + dx).coerceAtLeast(minLayerPx)
                    var newH = (startH + dy).coerceAtLeast(minLayerPx)
                    newW = newW.coerceAtMost((width - lp.leftMargin).coerceAtLeast(minLayerPx))
                    newH = newH.coerceAtMost((height - lp.topMargin).coerceAtLeast(minLayerPx))
                    lp.width = newW
                    lp.height = newH
                    chip.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (width > 0 && height > 0) {
                        layer.w = lp.width.toFloat() / width
                        layer.h = lp.height.toFloat() / height
                        onLayerUpdated?.invoke(layer)
                    }
                    true
                }
                else -> false
            }
        }
    }
}
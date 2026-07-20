package ame.project.nlstudio

import ame.project.nlstudio.OBS.AudioLevelBus
import ame.project.nlstudio.OBS.StreamService
import ame.project.nlstudio.scene.BackgroundType
import ame.project.nlstudio.scene.EditorLayerAdapter
import ame.project.nlstudio.scene.LayerType
import ame.project.nlstudio.scene.Scene
import ame.project.nlstudio.scene.SceneAdapter
import ame.project.nlstudio.scene.SceneLayer
import ame.project.nlstudio.scene.SceneRepository
import ame.project.nlstudio.ui.SceneCanvasView
import ame.project.nlstudio.ui.VuMeterView
import ame.project.nlsdk.KanaeActivity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ame.project.nlstudio.OBS.VideoCacheManager
import ame.project.nlstudio.OBS.VideoDiskCacheManager
import ame.project.nlstudio.OBS.VideoOptimizer
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.UUID

/**
 * === LOGGING TAGS UNTUK DEBUG RESOLUSI ===
 * [RES-MAIN] = Resolusi di MainActivity
 * [RES-SVC]  = Resolusi di StreamService
 * [RES-COMP] = Resolusi di CompositeSceneVideoSource
 * [SCENE]    = Scene switching & state
 * [AUDIO]    = Audio mixer state
 */

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RES-MAIN"

        // FIX STARTUP JANK: jeda tambahan setelah frame pertama ter-render sebelum mulai
        // prefetch video di background, supaya tidak berebut CPU/GPU dengan initial layout &
        // rendering (lihat prefetchAllVideoScenes()).
        private const val PREFETCH_START_DELAY_MS = 500L
    }

    private lateinit var tvStatus: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    // Top Bar UI
    private lateinit var btnSettings: android.widget.ImageButton
    private lateinit var btnVoiceAnim: android.widget.ImageButton
    private lateinit var btnKanae: android.widget.ImageButton
    private lateinit var btnQuickLive: android.widget.ImageButton
    private lateinit var btnSceneManager: android.widget.ImageButton
    private lateinit var tvSceneNameTitle: TextView

    // Settings (in Dialog)
    private lateinit var dialogSettingsView: View
    private lateinit var spinnerPlatform: Spinner
    private lateinit var etServerUrl: EditText
    private lateinit var etStreamKey: EditText
    private lateinit var tvFullUrlPreview: TextView
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var spinnerEncoderType: Spinner
    private lateinit var etVideoBitrate: EditText
    private lateinit var etAudioBitrate: EditText
    private lateinit var spinnerVideoBgScale: Spinner

    // Audio mixer UI
    private lateinit var spinnerAudioSource: Spinner
    private lateinit var seekMicVolume: SeekBar
    private lateinit var seekSystemVolume: SeekBar
    private lateinit var tvMicVolumeValue: TextView
    private lateinit var tvSystemVolumeValue: TextView
    private lateinit var spinnerGameAudioApp: Spinner
    private lateinit var vuMicLevel: VuMeterView
    private lateinit var vuSystemLevel: VuMeterView
    private var installedApps: List<AppEntry> = emptyList()

    // Scene Manager (editor kanvas ala OBS)
    private lateinit var dialogSceneManagerView: View
    private lateinit var sceneRepository: SceneRepository
    private lateinit var sceneAdapter: SceneAdapter
    private lateinit var sceneCanvasView: SceneCanvasView
    private lateinit var etNewSceneName: EditText
    // Diisi oleh applyGameCenteredPreset() supaya rootWidth/rootHeight yang dipakai saat SAVE
    // persis sama dengan yang dipakai saat menghitung rasio posisi layer SCREEN (dipaksa Portrait).
    // Tanpa ini, saveCurrentEditingScene() re-read displayMetrics sendiri di waktu yang beda,
    // dan kalau saat itu metrics kebaca Landscape (mis. race saat MediaProjection start),
    // hasilnya rootWidth/rootHeight jadi Landscape padahal rasio layer dihitung utk Portrait
    // -> live jadi kanvas landscape dgn screen record kecil di tengah.
    private var editingForcedRootResolution: Pair<Int, Int>? = null
    private var rootLayoutUserTouched = false

    // Main Preview Tool Menu
    private lateinit var btnAddImage: android.widget.ImageButton
    private lateinit var btnAddText: android.widget.ImageButton
    private lateinit var btnAddVoiceAnim: android.widget.ImageButton
    private lateinit var btnAddTikTokChat: android.widget.ImageButton
    private lateinit var btnAddTikTokGift: android.widget.ImageButton
    private lateinit var btnAddTikTokJoin: android.widget.ImageButton
    private lateinit var btnAddMusicCurrent: android.widget.ImageButton
    private lateinit var btnAddMusicQueue: android.widget.ImageButton
    private lateinit var btnBgPicker: android.widget.ImageButton
    private lateinit var btnSaveSceneMain: Button
    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout

    // Editor Tool Tabs
    private var tabEditorTools: com.google.android.material.tabs.TabLayout? = null
    private var layoutToolsGeneral: View? = null
    private var layoutToolsTikTok: View? = null

    // Layer strip (klik utk pilih layer, X utk hapus - lihat EditorLayerAdapter)
    private var rvLayerStrip: RecyclerView? = null
    private var tvLayerCount: TextView? = null
    private var tvNoLayersHint: TextView? = null
    private var editorLayerAdapter: EditorLayerAdapter? = null

    // Video Control UI (Panel Editor)
    private var layoutVideoControls: View? = null
    private var btnPlayVideo: android.widget.ImageButton? = null
    private var seekVideoProgress: SeekBar? = null
    private var cbLoopVideo: android.widget.CheckBox? = null
    private var isUserSeekingVideo = false

    private var scenes: MutableList<Scene> = mutableListOf()
    private var activeSceneId: String = SceneRepository.ID_SCREEN

    // State scene yang lagi diedit di kanvas (belum tentu sama dengan yang lagi live)
    private var editingSceneId: String? = null
    private var editingBackgroundType: BackgroundType = BackgroundType.COLOR
    private var editingBackgroundUri: String? = null
    private var editingLayers: MutableList<SceneLayer> = mutableListOf()
    private var selectedLayerId: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == StreamService.ACTION_STATUS_BROADCAST) {
                val msg = intent.getStringExtra(StreamService.EXTRA_STATUS_MSG)
                tvStatus.text = msg ?: "Status: idle"
            }
        }
    }

    private lateinit var serverPresets: Array<String>

    // Index pilihan "Tangkap audio dari" di spinnerAudioSource:
    // 0 = Internal+Mic (bawaan lib), 1 = Internal Only, 2 = Mic Only, 3 = Muted,
    // 4 = Mixer Manual (pakai AudioMixSource dengan slider volume)
    private val AUDIO_MODE_MANUAL_MIXER = 4

    private var pendingAction: String = StreamService.ACTION_START

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                if (pendingAction == StreamService.ACTION_TEST_RECORD) {
                    startTestRecordService(result.resultCode, result.data!!)
                } else {
                    startStreamService(result.resultCode, result.data!!)
                }
            } else {
                Toast.makeText(this, "Izin screen capture ditolak", Toast.LENGTH_SHORT).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Semua izin dibutuhkan untuk live streaming", Toast.LENGTH_LONG).show()
            }
        }

    /**
     * Uri hasil GetContent() cuma valid izin bacanya SELAMA task ini hidup - begitu app di-kill &
     * scene disimpan dibuka lagi lain waktu, contentResolver.openInputStream() bakal throw
     * SecurityException dan kanvas jadi BLANK. takePersistableUriPermission() bikin izin itu
     * nempel permanen, tapi cuma jalan kalau intent-nya ACTION_OPEN_DOCUMENT (makanya semua
     * picker di bawah pakai OpenDocument(), bukan GetContent()).
     */
    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: SecurityException) {
            // sebagian provider gak support persist (mis. beberapa cloud provider) - gapapa,
            // masih bisa dipakai selama task ini hidup, cuma gak survive restart app
        }
    }

    // Pilih gambar buat BACKGROUND scene yang lagi diedit
    private val pickBackgroundImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadPermission(uri)
                editingBackgroundType = BackgroundType.IMAGE
                editingBackgroundUri = uri.toString()
                refreshCanvasBackground()
            }
        }

    // Pilih video buat BACKGROUND scene yang lagi diedit.
    // PENTING: video mentah yang dipilih user TIDAK langsung dipakai apa adanya - selalu
    // di-crop & dikompres dulu lewat VideoOptimizer (lihat optimizeAndSetBackgroundVideo) supaya
    // resolusinya pas dengan kanvas dan bitrate-nya kecil. Hasil optimize itulah yang jadi
    // editingBackgroundUri, bukan file aslinya.
    private val pickBackgroundVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadPermission(uri)
                optimizeAndSetBackgroundVideo(uri)
            }
        }

    /**
     * Crop + kompres video background yang baru dipilih user (via VideoOptimizer) sebelum
     * dipakai sebagai backgroundUri scene. Kalau video yang sama & resolusi target yang sama
     * sudah pernah diproses sebelumnya, VideoOptimizer langsung pakai file cache yang ada
     * (lihat VideoOptimizer.optimize) jadi prosesnya instan di kunjungan berikutnya.
     */
    private fun optimizeAndSetBackgroundVideo(sourceUri: Uri) {
        val (targetW, targetH) = getTargetResolution()

        val dialogView = layoutInflater.inflate(R.layout.dialog_loading, null)
        dialogView.findViewById<TextView>(R.id.tvLoadingText)?.text =
            "Mengoptimalkan video background..."
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Memproses Video")
            .setView(dialogView)
            .setCancelable(false)
            .show()

        Log.d(TAG, "optimizeAndSetBackgroundVideo: source=$sourceUri target=${targetW}x$targetH")

        val prefs = getSharedPreferences("stream_settings", Context.MODE_PRIVATE)
        val bgScale = prefs.getString("video_bg_scale", "Default (Optimize Auto)") ?: "Default (Optimize Auto)"

        VideoOptimizer.optimize(
            context = this,
            inputUri = sourceUri,
            targetW = targetW,
            targetH = targetH,
            scaleSetting = bgScale,
            sceneName = etNewSceneName.text?.toString()?.trim()?.ifEmpty { null },
            listener = object : VideoOptimizer.Listener {
                override fun onSuccess(outputUri: Uri) {
                    runOnUiThread {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        Log.d(TAG, "optimizeAndSetBackgroundVideo: sukses -> $outputUri")

                        // Video lama (kalau ada & berupa hasil cache) sudah tidak dipakai, hapus
                        // supaya cache tidak menumpuk percuma tiap kali user ganti background.
                        editingBackgroundUri
                            ?.takeIf { it.startsWith("file://") }
                            ?.let { VideoOptimizer.deleteCachedOutputFile(this@MainActivity, it) }

                        editingBackgroundType = BackgroundType.VIDEO
                        editingBackgroundUri = outputUri.toString()
                        refreshCanvasBackground()
                    }
                }

                override fun onError(e: Exception) {
                    Log.e(TAG, "optimizeAndSetBackgroundVideo: gagal", e)
                    runOnUiThread {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        Toast.makeText(
                            this@MainActivity,
                            "Gagal memproses video background: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    // Pilih gambar buat LAYER baru di scene yang lagi diedit
    private val pickLayerImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadPermission(uri)
                addLayer(LayerType.IMAGE, uri.toString(), w = 0.35f, h = 0.35f)
            }
        }

    // Pilih video buat LAYER (overlay) baru di scene yang lagi diedit
    private val pickLayerVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadPermission(uri)
                addLayer(LayerType.VIDEO, uri.toString(), w = 0.5f, h = 0.35f)
            }
        }

    /** Tambah 1 layer baru ke scene yang lagi diedit & refresh kanvas. */
    private fun addLayer(type: LayerType, uri: String, w: Float, h: Float) {
        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = type,
            uri = uri,
            x = 0.3f, y = 0.3f, w = w, h = h,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
        applyEditorChangesToLiveStream()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Obtain the FirebaseAnalytics instance.
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.APP_OPEN, null)

        sceneRepository = SceneRepository(this)
        scenes = sceneRepository.loadScenes()

        // Initialize Video Disk Cache (Media3)
        VideoDiskCacheManager.getInstance(this)

        Log.d(TAG, "=== onCreate ===")
        Log.d(TAG, "DisplayMetrics: ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
        Log.d(TAG, "Orientation: ${resources.configuration.orientation}")
        Log.d(TAG, "Loaded scenes: ${scenes.size}")
        scenes.forEach { scene ->
            Log.d(TAG, "  Scene[${scene.id}]: ${scene.name} | bg=${scene.backgroundType} | root=${scene.rootWidth}x${scene.rootHeight} | layers=${scene.layers.size}")
        }

        // Initialize adapter early to avoid UninitializedPropertyAccessException
        sceneAdapter = SceneAdapter(
            onSceneClick = { scene -> switchToScene(scene) },
            onSceneDelete = { scene -> confirmDeleteScene(scene) }
        )

        // Pre-inflate settings dialog view to keep references
        dialogSettingsView = layoutInflater.inflate(R.layout.dialog_settings, null)

        spinnerPlatform = dialogSettingsView.findViewById(R.id.spinnerPlatform)
        etServerUrl = dialogSettingsView.findViewById(R.id.etServerUrl)
        etStreamKey = dialogSettingsView.findViewById(R.id.etStreamKey)
        tvFullUrlPreview = dialogSettingsView.findViewById(R.id.tvFullUrlPreview)
        spinnerResolution = dialogSettingsView.findViewById(R.id.spinnerResolution)
        spinnerFps = dialogSettingsView.findViewById(R.id.spinnerFps)
        spinnerEncoderType = dialogSettingsView.findViewById(R.id.spinnerEncoderType)
        etVideoBitrate = dialogSettingsView.findViewById(R.id.etVideoBitrate)
        etAudioBitrate = dialogSettingsView.findViewById(R.id.etAudioBitrate)
        spinnerVideoBgScale = dialogSettingsView.findViewById(R.id.spinnerVideoBgScale)
        spinnerGameAudioApp = dialogSettingsView.findViewById(R.id.spinnerGameAudioApp)

        etVideoBitrate.setText("2500")
        etAudioBitrate.setText("128")

        // Pre-inflate scene manager dialog view to keep references (sama pola dgn dialogSettingsView)
        dialogSceneManagerView = layoutInflater.inflate(R.layout.dialog_scene_manager, null)
        val rvDialog = dialogSceneManagerView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSceneList)
        rvDialog.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvDialog.adapter = sceneAdapter
        etNewSceneName = dialogSceneManagerView.findViewById(R.id.etNewSceneName)

        tvStatus = findViewById(R.id.tvStatus)

        btnSettings = findViewById(R.id.btnSettings)
        btnVoiceAnim = findViewById(R.id.btnVoiceAnim)
        btnKanae = findViewById(R.id.btnKanae)
        btnQuickLive = findViewById(R.id.btnQuickLive)
        btnSceneManager = findViewById(R.id.btnSceneManager)
        tvSceneNameTitle = findViewById(R.id.tvSceneNameTitle)

        // Main preview scene tools
        sceneCanvasView = findViewById(R.id.sceneCanvasView)
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        serverPresets = resources.getStringArray(R.array.platform_server_urls)

        setupPlatformSpinner()
        setupUrlWatchers()
        setupGameAppSpinner()
        setupBottomPager()
        setupActionButtons()
        loadSettings()

        // Restore state if available
        if (savedInstanceState != null) {
            activeSceneId = savedInstanceState.getString("activeSceneId", SceneRepository.ID_SCREEN)
            editingSceneId = savedInstanceState.getString("editingSceneId")
        }

        // Load initial scene to canvas
        val initialScene = scenes.find { it.id == activeSceneId } ?: scenes.firstOrNull()
        initialScene?.let { switchToScene(it, updateService = savedInstanceState == null) }

        // Force UI update for initial scene even if panel not bound yet
        refreshCanvasBackground()
        refreshCanvasLayers()

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnVoiceAnim.setOnClickListener {
            // Open Voice Animation Settings Activity
            val intent = Intent(this, VoiceAnimSettingsActivity::class.java)
            startActivity(intent)
        }
        btnKanae.setOnClickListener {
            val intent = Intent(this, KanaeActivity::class.java)
            startActivity(intent)
        }
        btnQuickLive.setOnClickListener { findViewById<Button>(R.id.btnStart).performClick() }
        btnSceneManager.setOnClickListener { showSceneManagerDialog() }

        // VU meter Mic & Sistem: AudioMixSource ngirim level dari thread capture-nya sendiri,
        // makanya di-lempar ke main thread dulu sebelum nyentuh View.
        AudioLevelBus.registerListener(audioLevelListener)
    }

    private val audioLevelListener = AudioLevelBus.Listener { mic, system ->
        runOnUiThread {
            if (::vuMicLevel.isInitialized) vuMicLevel.setLevel(mic)
            if (::vuSystemLevel.isInitialized) vuSystemLevel.setLevel(system)
            sceneCanvasView.setVoiceAnimLevel(mic, sceneRepository)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(StreamService.ACTION_STATUS_BROADCAST)

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioLevelBus.unregisterListener(audioLevelListener)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("stream_settings", Context.MODE_PRIVATE)
        val bgScale = prefs.getString("video_bg_scale", "Default (Optimize Auto)") ?: "Default (Optimize Auto)"
        
        // Find index of bgScale in spinner
        val adapter = spinnerVideoBgScale.adapter
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == bgScale) {
                spinnerVideoBgScale.setSelection(i)
                break
            }
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("stream_settings", Context.MODE_PRIVATE)
        val bgScale = spinnerVideoBgScale.selectedItem?.toString() ?: "Default (Optimize Auto)"
        prefs.edit().putString("video_bg_scale", bgScale).apply()
    }

    private fun showSettingsDialog() {
        // Ensure the view is removed from its parent if it was already shown
        (dialogSettingsView.parent as? android.view.ViewGroup)?.removeView(dialogSettingsView)

        firebaseAnalytics.logEvent("settings_open", null)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Settings Connection & Quality")
            .setView(dialogSettingsView)
            .setPositiveButton("OK") { _, _ ->
                saveSettings()
                refreshCanvasAspectRatio()
                refreshCanvasLayers()
            }
            .show()
    }

    private fun setupBottomPager() {
        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = 3
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val layoutRes = when(viewType) {
                    0 -> R.layout.panel_audio
                    1 -> R.layout.panel_editor
                    else -> R.layout.panel_scenes
                }
                val view = layoutInflater.inflate(layoutRes, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val root = holder.itemView
                when(position) {
                    0 -> setupAudioPanel(root)
                    1 -> setupEditorPanel(root)
                    2 -> setupScenesPanel(root)
                }
            }
        }
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 2

        com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when(position) {
                0 -> "AUDIO"
                1 -> "EDITOR"
                else -> "SCENES"
            }
        }.attach()
    }

    private fun setupAudioPanel(root: View) {
        spinnerAudioSource = root.findViewById(R.id.spinnerAudioSource)
        seekMicVolume = root.findViewById(R.id.seekMicVolume)
        seekSystemVolume = root.findViewById(R.id.seekSystemVolume)
        tvMicVolumeValue = root.findViewById(R.id.tvMicVolumeValue)
        tvSystemVolumeValue = root.findViewById(R.id.tvSystemVolumeValue)
        vuMicLevel = root.findViewById(R.id.vuMicLevel)
        vuSystemLevel = root.findViewById(R.id.vuSystemLevel)

        setupVolumeSliders()
    }

    private fun setupEditorPanel(root: View) {
        btnAddImage = root.findViewById(R.id.btnAddImage)
        btnAddText = root.findViewById(R.id.btnAddText)
        btnAddVoiceAnim = root.findViewById(R.id.btnAddVoiceAnim)
        btnAddTikTokChat = root.findViewById(R.id.btnAddTikTokChat)
        btnAddTikTokGift = root.findViewById(R.id.btnAddTikTokGift)
        btnAddTikTokJoin = root.findViewById(R.id.btnAddTikTokJoin)
        btnAddMusicCurrent = root.findViewById(R.id.btnAddMusicCurrent)
        btnAddMusicQueue = root.findViewById(R.id.btnAddMusicQueue)
        btnBgPicker = root.findViewById(R.id.btnBgPicker)
        btnSaveSceneMain = root.findViewById(R.id.btnSaveScene)

        tabEditorTools = root.findViewById(R.id.tabEditorTools)
        layoutToolsGeneral = root.findViewById(R.id.layoutToolsGeneral)
        layoutToolsTikTok = root.findViewById(R.id.layoutToolsTikTok)

        rvLayerStrip = root.findViewById(R.id.rvLayerStrip)
        tvLayerCount = root.findViewById(R.id.tvLayerCount)
        tvNoLayersHint = root.findViewById(R.id.tvNoLayersHint)

        layoutVideoControls = root.findViewById(R.id.layoutVideoControls)
        btnPlayVideo = root.findViewById(R.id.btnPlayVideo)
        seekVideoProgress = root.findViewById(R.id.seekVideoProgress)
        cbLoopVideo = root.findViewById(R.id.cbLoopVideo)

        setupEditorTabs()

        editorLayerAdapter = EditorLayerAdapter(
            bitmapLoader = { layer -> sceneRepository.loadLayerPreviewBitmap(layer) },
            onLayerClick = { layer -> selectLayer(layer.id) },
            onLayerDelete = { layer -> deleteLayer(layer.id) }
        )
        rvLayerStrip?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvLayerStrip?.adapter = editorLayerAdapter

        setupMainSceneTools()
        updateVideoControlsVisibility()
        refreshCanvasLayers()
    }

    private fun setupScenesPanel(root: View) {
        val rv: androidx.recyclerview.widget.RecyclerView = root.findViewById(R.id.rvSceneListMain)
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = sceneAdapter
        refreshSceneList()
    }

    private fun updateVideoControlsVisibility() {
        val isVideo = editingBackgroundType == BackgroundType.VIDEO
        layoutVideoControls?.visibility = if (isVideo) View.VISIBLE else View.GONE
    }

    private fun setupMainSceneTools() {
        btnAddImage.setOnClickListener { showAddLayerOptions() }
        btnAddText.setOnClickListener { showAddTextDialog() }
        btnAddVoiceAnim.setOnClickListener { showAddVoiceAnimOptions() }
        btnAddTikTokChat.setOnClickListener { addTikTokChatLayer() }
        btnAddTikTokGift.setOnClickListener { addTikTokGiftLayer() }
        btnAddTikTokJoin.setOnClickListener { addTikTokJoinLayer() }
        btnAddMusicCurrent.setOnClickListener { addMusicCurrentLayer() }
        btnAddMusicQueue.setOnClickListener { addMusicQueueLayer() }
        btnBgPicker.setOnClickListener { showBackgroundPickerOptions() }
        btnSaveSceneMain.setOnClickListener { showSaveSceneDialog() }

        btnPlayVideo?.setOnClickListener {
            if (sceneCanvasView.isVideoPlaying()) {
                sceneCanvasView.pauseVideo()
                btnPlayVideo?.setImageResource(android.R.drawable.ic_media_play)
            } else {
                sceneCanvasView.playVideo()
                btnPlayVideo?.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        cbLoopVideo?.setOnCheckedChangeListener { _, isChecked ->
            sceneCanvasView.setVideoLooping(isChecked)
        }

        seekVideoProgress?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) sceneCanvasView.seekVideoTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeekingVideo = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isUserSeekingVideo = false }
        })

        sceneCanvasView.onVideoDurationReady = { duration ->
            runOnUiThread { seekVideoProgress?.max = duration.coerceAtLeast(1) }
        }
        sceneCanvasView.onVideoProgress = { position ->
            runOnUiThread {
                if (!isUserSeekingVideo) seekVideoProgress?.progress = position
                btnPlayVideo?.setImageResource(
                    if (sceneCanvasView.isVideoPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
            }
        }

        sceneCanvasView.onLayerSelected = { layer -> selectLayer(layer.id) }

        // Setup onLayerUpdated to sync changes back to editingLayers
        sceneCanvasView.onLayerUpdated = { layer ->
            val idx = editingLayers.indexOfFirst { it.id == layer.id }
            if (idx >= 0) {
                editingLayers[idx] = layer
                applyEditorChangesToLiveStream()
            }
        }
    }

    /** Menerapkan perubahan yang sedang dibuat di editor ke live stream secara real-time
     *  jika scene yang sedang diedit adalah scene yang sedang aktif tampil. */
    private fun applyEditorChangesToLiveStream() {
        if (!StreamService.isServiceRunning) return

        // Hanya update jika kita sedang mengedit scene yang saat ini ditampilkan live
        if (editingSceneId != activeSceneId) return

        val (targetW, targetH) = getTargetResolution()
        val tempScene = Scene(
            id = editingSceneId ?: "temp",
            name = etNewSceneName.text.toString(),
            backgroundType = editingBackgroundType,
            backgroundUri = editingBackgroundUri,
            layers = editingLayers,
            rootWidth = targetW,
            rootHeight = targetH
        )
        sendSceneSwitch(StreamService.SCENE_COMPOSITE, sceneRepository.toJson(tempScene))
    }

    /** Pilih layer ini di mana pun asalnya (tap di kanvas ATAU tap di layer strip) - keduanya disinkronkan. */
    private fun selectLayer(layerId: String?) {
        selectedLayerId = layerId
        sceneCanvasView.setSelectedLayer(layerId)
        editorLayerAdapter?.submitList(editingLayers, layerId)
    }

    private fun deleteLayer(layerId: String) {
        editingLayers.removeAll { it.id == layerId }
        if (selectedLayerId == layerId) selectedLayerId = null
        refreshCanvasLayers()
        applyEditorChangesToLiveStream()
    }

    private fun showAddVoiceAnimOptions() {
        // Just add a Voice Animation layer using the current default config
        val prefs = getSharedPreferences("voice_anim_prefs", Context.MODE_PRIVATE)
        val configJson = prefs.getString("default_config", "") ?: ""

        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.VOICE_ANIM,
            uri = "voiceanim:default",
            x = 0.3f, y = 0.3f, w = 0.35f, h = 0.35f,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
        Toast.makeText(this, "Voice Animation ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun showAddLayerOptions() {
        val options = arrayOf("Gambar", "Video", "Layar HP (PiP)")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tambah Layer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickLayerImageLauncher.launch(arrayOf("image/*"))
                    1 -> pickLayerVideoLauncher.launch(arrayOf("video/*"))
                    2 -> {
                        val metrics = resources.displayMetrics
                        val screenRatio = metrics.widthPixels.toFloat() / metrics.heightPixels
                        val (canvasW, canvasH) = getTargetResolution()
                        val canvasRatio = canvasW.toFloat() / canvasH

                        // Default lebar 0.4, tinggi menyesuaikan rasio layar HP agar tidak gepeng
                        val w = 0.4f
                        val h = w * (canvasRatio / screenRatio)
                        addLayer(LayerType.SCREEN, "screen://main", w = w, h = h)
                    }
                }
            }
            .show()
    }

    private fun showAddTextDialog() {
        val et = EditText(this)
        et.hint = "Masukkan teks di sini"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tambah Layer Teks")
            .setView(et)
            .setPositiveButton("Tambah") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isNotEmpty()) {
                    addTextLayer(text)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun addTikTokChatLayer() {
        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.TIKTOK_CHAT,
            uri = "tiktok:chat",
            x = 0.1f, y = 0.7f, w = 0.8f, h = 0.25f,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
        Toast.makeText(this, "TikTok Chat ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun addTikTokGiftLayer() {
        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.TIKTOK_GIFT,
            uri = "tiktok:gift",
            x = 0.1f, y = 0.1f, w = 0.8f, h = 0.25f,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
        Toast.makeText(this, "TikTok Gift ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun addTikTokJoinLayer() {
        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.TIKTOK_JOIN,
            uri = "tiktok:join",
            x = 0.1f, y = 0.4f, w = 0.8f, h = 0.2f,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
        Toast.makeText(this, "TikTok Join ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun addMusicCurrentLayer() {
        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.MUSIC_CURRENT,
            uri = "music:current",
            x = 0.1f, y = 0.1f, w = 0.8f, h = 0.2f,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
        Toast.makeText(this, "Music Current ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun addMusicQueueLayer() {
        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.MUSIC_QUEUE,
            uri = "music:queue",
            x = 0.1f, y = 0.4f, w = 0.8f, h = 0.5f,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
        Toast.makeText(this, "Music Queue ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun setupEditorTabs() {
        tabEditorTools?.apply {
            removeAllTabs()
            addTab(newTab().setText("UMUM"))
            addTab(newTab().setText("TIKTOK"))

            addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    when(tab?.position) {
                        0 -> {
                            layoutToolsGeneral?.visibility = View.VISIBLE
                            layoutToolsTikTok?.visibility = View.GONE
                        }
                        1 -> {
                            layoutToolsGeneral?.visibility = View.GONE
                            layoutToolsTikTok?.visibility = View.VISIBLE
                        }
                    }
                }
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            })
        }
    }
    private fun addTextLayer(text: String) {
        val nextZ = (editingLayers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val newLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.TEXT,
            uri = "text:$text", // Gunakan format text:teks_disini tanpa //
            x = 0.3f, y = 0.3f, w = 0.4f, h = 0.15f,
            zIndex = nextZ
        )
        editingLayers.add(newLayer)
        refreshCanvasLayers()
    }

    private fun showBackgroundPickerOptions() {
        val options = arrayOf("Warna Polos (Hitam)", "Pilih Gambar", "Pilih Video")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ganti Background")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        editingBackgroundType = BackgroundType.COLOR
                        editingBackgroundUri = null
                        refreshCanvasBackground()
                    }
                    1 -> pickBackgroundImageLauncher.launch(arrayOf("image/*"))
                    2 -> pickBackgroundVideoLauncher.launch(arrayOf("video/*"))
                }
            }
            .show()
    }

    private fun showSaveSceneDialog() {
        val et = EditText(this)
        et.setText(etNewSceneName.text)
        et.hint = "Nama Scene"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Simpan Scene")
            .setView(et)
            .setPositiveButton("Simpan") { _, _ ->
                etNewSceneName.setText(et.text)
                saveCurrentEditingScene()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun saveCurrentEditingScene() {
        val name = etNewSceneName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Isi nama scene dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val safeExistingId = if (editingSceneId == SceneRepository.ID_SCREEN) null else editingSceneId

        val metrics = resources.displayMetrics
        // Resolusi root otomatis diambil dari Setting Quality, menyesuaikan orientasi scene yang sedang diedit
        val (targetW, targetH) = getTargetResolution()
        val forced = editingForcedRootResolution
        val (rootW, rootH) = if (forced != null) {
            val forcedIsPortrait = forced.second > forced.first
            val targetIsPortrait = targetH > targetW
            if (forcedIsPortrait == targetIsPortrait) targetW to targetH else targetH to targetW
        } else {
            targetW to targetH
        }

        Log.d(TAG, "=== saveCurrentEditingScene ===")
        Log.d(TAG, "  Name=$name, existingId=$safeExistingId")
        Log.d(TAG, "  editingForcedRootResolution=$editingForcedRootResolution")
        Log.d(TAG, "  displayMetrics=${metrics.widthPixels}x${metrics.heightPixels}")
        Log.d(TAG, "  FINAL rootW=$rootW, rootH=$rootH")
        Log.d(TAG, "  editingBackgroundType=$editingBackgroundType")
        Log.d(TAG, "  editingLayers count=${editingLayers.size}")
        editingLayers.forEach { layer ->
            Log.d(TAG, "    Layer[${layer.id}]: type=${layer.type} | x=${layer.x} y=${layer.y} w=${layer.w} h=${layer.h} z=${layer.zIndex}")
        }

        val scene = sceneRepository.buildOrUpdateScene(
            existingId = safeExistingId,
            name = name,
            backgroundType = editingBackgroundType,
            backgroundUri = editingBackgroundUri,
            layers = editingLayers,
            rootWidth = rootW,
            rootHeight = rootH
        )
        val idx = scenes.indexOfFirst { it.id == scene.id }
        if (idx >= 0) {
            sceneRepository.deleteThumbnail(scenes[idx])
            scenes[idx] = scene
        } else {
            scenes.add(scene)
        }
        sceneRepository.saveScenes(scenes)
        editingSceneId = scene.id
        refreshSceneList()
        switchToScene(scene)

        // Prefetch video background di background setelah simpan (tanpa dialog) agar siap nanti
        if (scene.backgroundType == BackgroundType.VIDEO && scene.backgroundUri != null) {
            VideoCacheManager.getInstance().prefetch(this, Uri.parse(scene.backgroundUri),
                scene.rootWidth, scene.rootHeight, 30, 250L * 1024 * 1024, null)
        }

        Toast.makeText(this, "Scene \"${scene.name}\" disimpan", Toast.LENGTH_SHORT).show()
    }

    /** Reset kanvas editor ke kondisi kosong buat mulai bikin scene baru dari nol. */
    private fun resetEditingScene() {
        Log.d(TAG, "=== resetEditingScene ===")
        editingSceneId = null
        editingBackgroundType = BackgroundType.COLOR
        editingBackgroundUri = null
        editingLayers = mutableListOf()
        etNewSceneName.setText("")
        editingForcedRootResolution = null
        selectLayer(null)
        refreshCanvasAspectRatio()
        refreshCanvasBackground()
        refreshCanvasLayers()
    }

    /** Load scene yang udah tersimpan ke kanvas editor, biar bisa diedit lagi (posisi layer, background, dll). */
    private fun loadSceneIntoEditor(scene: Scene) {
        Log.d(TAG, "=== loadSceneIntoEditor ===")
        Log.d(TAG, "  Scene: ${scene.name} | id=${scene.id}")
        Log.d(TAG, "  root=${scene.rootWidth}x${scene.rootHeight}")
        Log.d(TAG, "  bgType=${scene.backgroundType} | bgUri=${scene.backgroundUri}")
        Log.d(TAG, "  layers=${scene.layers.size}")
        scene.layers.forEach { layer ->
            Log.d(TAG, "    Layer: type=${layer.type} | x=${layer.x} y=${layer.y} w=${layer.w} h=${layer.h}")
        }

        editingSceneId = scene.id
        editingBackgroundType = scene.backgroundType
        editingBackgroundUri = scene.backgroundUri
        editingLayers = scene.layers.map { it.copy() }.toMutableList() // copy biar gak ubah data tersimpan sblm disave ulang
        etNewSceneName.setText(scene.name)
        editingForcedRootResolution = scene.rootWidth to scene.rootHeight

        selectLayer(null)
        refreshCanvasAspectRatio()
        refreshCanvasBackground()
        refreshCanvasLayers()
    }

    private fun applyPortraitPreset(position: String) { // "center", "top", "bottom"
        val (targetW, targetH) = getTargetResolution()
        val rootW = minOf(targetW, targetH)
        val rootH = maxOf(targetW, targetH)

        Log.d(TAG, "=== applyPortraitPreset position=$position ===")
        editingForcedRootResolution = rootW to rootH
        rootLayoutUserTouched = false

        if (editingBackgroundType == BackgroundType.SCREEN) {
            editingBackgroundType = BackgroundType.COLOR
            editingBackgroundUri = null
            refreshCanvasBackground()
        }

        editingLayers.clear()
        // Gunakan rasio 16:9 untuk area game (umum di portrait)
        val gameHeight = rootW * 9f / 16f
        val hRatio = gameHeight / rootH

        val yRatio = when(position) {
            "top" -> 0f
            "bottom" -> (rootH - gameHeight) / rootH
            else -> ((rootH - gameHeight) / 2f) / rootH // center
        }

        val gameLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.SCREEN,
            uri = "screen://main",
            x = 0f,
            y = yRatio,
            w = 1.0f,
            h = hRatio,
            zIndex = 0
        )
        editingLayers.add(gameLayer)

        val label = when(position) {
            "top" -> "Game Atas"
            "bottom" -> "Game Bawah"
            else -> "Game Tengah"
        }
        etNewSceneName.setText("Preset $label")

        refreshCanvasAspectRatio()
        refreshCanvasLayers()
    }

    private fun applyLandscapeFullPreset() {
        val (targetW, targetH) = getTargetResolution()
        val rootW = maxOf(targetW, targetH)
        val rootH = minOf(targetW, targetH)

        Log.d(TAG, "=== applyLandscapeFullPreset ===")
        editingForcedRootResolution = rootW to rootH
        rootLayoutUserTouched = false

        if (editingBackgroundType == BackgroundType.SCREEN) {
            editingBackgroundType = BackgroundType.COLOR
            editingBackgroundUri = null
            refreshCanvasBackground()
        }

        editingLayers.clear()
        val gameLayer = SceneLayer(
            id = UUID.randomUUID().toString(),
            type = LayerType.SCREEN,
            uri = "screen://main",
            x = 0f,
            y = 0f,
            w = 1.0f,
            h = 1.0f,
            zIndex = 0
        )
        editingLayers.add(gameLayer)

        etNewSceneName.setText("Preset Landscape Full")

        refreshCanvasAspectRatio()
        refreshCanvasLayers()
    }

    private fun applyGameCenteredPreset() {
        applyPortraitPreset("center")
    }

    private fun refreshCanvasBackground() {
        val isEmptyHint = findViewById<TextView>(R.id.tvEmptyHint)

        updateVideoControlsVisibility()

        if (editingBackgroundType == BackgroundType.SCREEN) {
            isEmptyHint?.text = "PREVIEW LAYAR HP AKTIF\n(Akan muncul saat Live/Record)"
            isEmptyHint?.visibility = View.VISIBLE
            sceneCanvasView.setBackgroundBitmap(null)
            applyEditorChangesToLiveStream()
            return
        }

        if (editingBackgroundType == BackgroundType.VIDEO && editingBackgroundUri != null) {
            val uri = Uri.parse(editingBackgroundUri)
            val loop = cbLoopVideo?.isChecked ?: true

            // Jika sedang LIVE/Record, jangan putar otomatis di editor saat pindah scene biar CPU enteng.
            // User masih tetap bisa klik tombol Play secara manual kalau mau cek.
            val autoPlay = !StreamService.isServiceRunning

            sceneCanvasView.setBackgroundVideo(uri, loop = loop, autoPlay = autoPlay)

            isEmptyHint?.visibility = View.GONE
            if (autoPlay) {
                btnPlayVideo?.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                btnPlayVideo?.setImageResource(android.R.drawable.ic_media_play)
            }
            applyEditorChangesToLiveStream()
            return
        }

        val bmp = when (editingBackgroundType) {
            BackgroundType.IMAGE -> editingBackgroundUri?.let { sceneRepository.loadPreviewBitmap(Uri.parse(it)) }
            else -> null
        }

        if (bmp == null && editingLayers.isEmpty()) {
            isEmptyHint?.text = "SCENE KOSONG\nTap ikon di kanan untuk menambah konten"
            isEmptyHint?.visibility = View.VISIBLE
        } else {
            isEmptyHint?.visibility = View.GONE
        }

        sceneCanvasView.setBackgroundBitmap(bmp)
        applyEditorChangesToLiveStream()
    }

    private fun refreshCanvasLayers() {
        val isEmptyHint = findViewById<TextView>(R.id.tvEmptyHint)
        if (editingLayers.isEmpty() && editingBackgroundType != BackgroundType.IMAGE && editingBackgroundType != BackgroundType.VIDEO) {
            isEmptyHint?.visibility = View.VISIBLE
        } else {
            isEmptyHint?.visibility = View.GONE
        }

        sceneCanvasView.setLayers(editingLayers) { layer -> sceneRepository.loadLayerPreviewBitmap(layer) }
        sceneCanvasView.setSelectedLayer(selectedLayerId)

        editorLayerAdapter?.submitList(editingLayers, selectedLayerId)
        tvLayerCount?.text = "${editingLayers.size} layer"
        tvNoLayersHint?.visibility = if (editingLayers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshCanvasAspectRatio() {
        val (targetW, targetH) = getTargetResolution()
        val forced = editingForcedRootResolution
        val (rootW, rootH) = if (forced != null) {
            val forcedIsPortrait = forced.second > forced.first
            val targetIsPortrait = targetH > targetW
            if (forcedIsPortrait == targetIsPortrait) targetW to targetH else targetH to targetW
        } else {
            targetW to targetH
        }
        val ratio = rootW.toFloat() / rootH
        Log.d(TAG, "refreshCanvasAspectRatio: root=${rootW}x${rootH} ratio=$ratio")
        sceneCanvasView.setTargetAspectRatio(ratio)
    }

    private fun confirmDeleteScene(scene: Scene) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hapus scene?")
            .setMessage("Scene \"${scene.name}\" akan dihapus dari daftar.")
            .setPositiveButton("Hapus") { _, _ ->
                scenes.removeAll { it.id == scene.id }
                sceneRepository.deleteThumbnail(scene)
                // Kalau backgroundnya video hasil optimize (file cache lokal), hapus juga filenya
                // dari cache biar tidak numpuk percuma.
                scene.backgroundUri
                    ?.takeIf { scene.backgroundType == BackgroundType.VIDEO && it.startsWith("file://") }
                    ?.let { VideoOptimizer.deleteCachedOutputFile(this, it) }
                sceneRepository.saveScenes(scenes)
                if (activeSceneId == scene.id) {
                    // scene yg lagi aktif dihapus -> balik ke Layar HP biar gak nunjuk ke scene kosong
                    switchToScene(scenes.first { it.backgroundType == BackgroundType.SCREEN })
                }
                if (editingSceneId == scene.id) resetEditingScene()
                refreshSceneList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshSceneList() {
        sceneAdapter.submitList(scenes, activeSceneId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("activeSceneId", activeSceneId)
        outState.putString("editingSceneId", editingSceneId)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")
        // Refresh canvas without sending switch command to service (to avoid live glitch)
        val currentScene = scenes.find { it.id == activeSceneId }
        currentScene?.let { refreshCanvasForScene(it) }
    }

    private fun refreshCanvasForScene(scene: Scene) {
        if (scene.backgroundType != BackgroundType.SCREEN) {
            loadSceneIntoEditor(scene)
        }
        refreshCanvasBackground()
        refreshCanvasLayers()
    }

    /** Pindah ke scene ini sekarang: update judul di top bar, dan kirim ke StreamService kalau lagi live. */
    private fun switchToScene(scene: Scene, updateService: Boolean = true) {
        Log.d(TAG, "=== switchToScene === updateService=$updateService")
        Log.d(TAG, "  Scene: ${scene.name} | id=${scene.id}")

        val params = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, scene.id)
            putString(FirebaseAnalytics.Param.ITEM_NAME, scene.name)
            putString(FirebaseAnalytics.Param.CONTENT_TYPE, scene.backgroundType.name)
        }
        firebaseAnalytics.logEvent("scene_switch", params)

        activeSceneId = scene.id
        tvSceneNameTitle.text = scene.name
        refreshSceneList()

        loadSceneIntoEditor(scene)

        if (!updateService) return

        if (scene.backgroundType == BackgroundType.SCREEN) {
            sendSceneSwitch(StreamService.SCENE_SCREEN, sceneJson = null, scene = scene)
        } else {
            sendSceneSwitch(StreamService.SCENE_COMPOSITE, sceneJson = sceneRepository.toJson(scene), scene = scene)
        }
    }

    private fun showSceneManagerDialog() {
        (dialogSceneManagerView.parent as? android.view.ViewGroup)?.removeView(dialogSceneManagerView)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Scene Manager")
            .setView(dialogSceneManagerView)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun setupPlatformSpinner() {
        spinnerPlatform.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val preset = serverPresets.getOrElse(position) { "" }
                etServerUrl.setText(preset)
                updateUrlPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupUrlWatchers() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateUrlPreview() }
        }
        etServerUrl.addTextChangedListener(watcher)
        etStreamKey.addTextChangedListener(watcher)
    }

    /** Slider volume mic & audio sistem/game. Kalau live sedang jalan, kirim update real-time ke service. */
    private fun setupVolumeSliders() {
        seekMicVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMicVolumeValue.text = "$progress%"
                sendAudioGainUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekSystemVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSystemVolumeValue.text = "$progress%"
                sendAudioGainUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private data class AppEntry(val label: String, val packageName: String, val uid: Int)

    /** Isi dropdown "Tangkap audio dari" dengan daftar aplikasi yang terpasang (buat audio game spesifik). */
    private fun setupGameAppSpinner() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // sembunyikan app sistem biar list rapi
            .map {
                AppEntry(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    uid = it.uid
                )
            }
            .sortedBy { it.label.lowercase() }

        installedApps = listOf(AppEntry("Semua Audio Sistem", "", -1)) + apps

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            installedApps.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGameAudioApp.adapter = adapter
    }

    /** Kirim perintah ganti scene ke StreamService yang lagi jalan (aman dipanggil walau service belum start). */
    private fun sendSceneSwitch(sceneType: String, sceneJson: String?, scene: Scene? = null) {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_SWITCH_SCENE
            putExtra(StreamService.EXTRA_SCENE_TYPE, sceneType)
            if (sceneJson != null) putExtra(StreamService.EXTRA_SCENE_JSON, sceneJson)
            grantScenePermissions(this, scene)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            // service belum jalan (belum live) -> scene akan dipakai begitu live dimulai, gapapa
        }
    }

    private fun grantScenePermissions(intent: Intent, scene: Scene?) {
        if (scene == null) return
        val uris = mutableSetOf<Uri>()
        if (scene.backgroundType == BackgroundType.VIDEO || scene.backgroundType == BackgroundType.IMAGE) {
            scene.backgroundUri?.let { if (it.startsWith("content://")) uris.add(Uri.parse(it)) }
        }
        scene.layers.forEach { layer ->
            if (layer.uri.startsWith("content://")) {
                uris.add(Uri.parse(layer.uri))
            }
        }

        if (uris.isNotEmpty()) {
            val firstUri = uris.first()
            val clipData = android.content.ClipData.newRawUri("Scene URIs", firstUri)
            uris.drop(1).forEach { uri ->
                clipData.addItem(android.content.ClipData.Item(uri))
            }
            intent.clipData = clipData
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Kirim gain mic/sistem terbaru ke service yang lagi live, biar berubah real-time tanpa restart stream. */
    private fun sendAudioGainUpdate() {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_UPDATE_AUDIO_GAIN
            putExtra(StreamService.EXTRA_MIC_GAIN, seekMicVolume.progress / 100f)
            putExtra(StreamService.EXTRA_SYSTEM_GAIN, seekSystemVolume.progress / 100f)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            // service belum jalan, gapapa - nilai slider akan dipakai saat live dimulai
        }
    }

    private fun setupActionButtons() {
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val url = buildFullUrl()
            if (url.isEmpty()) {
                showSettingsDialog()
                Toast.makeText(this, "Isi Server URL dan Stream Key dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            firebaseAnalytics.logEvent("stream_start_clicked", null)
            pendingAction = StreamService.ACTION_START
            checkPermissionsThenStart()
        }

        findViewById<Button>(R.id.btnTestRecord)?.setOnClickListener {
            val encoderLabel = spinnerEncoderType.selectedItem.toString()
            Toast.makeText(this, "Merekam 30 detik pakai encoder: $encoderLabel", Toast.LENGTH_SHORT).show()
            firebaseAnalytics.logEvent("record_start_clicked", null)
            pendingAction = StreamService.ACTION_TEST_RECORD
            checkPermissionsThenStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            firebaseAnalytics.logEvent("stream_stop_clicked", null)
            stopService(Intent(this, StreamService::class.java))
            tvStatus.text = "Status: stopped"
            // Setelah stop, preview video di editor bisa jalan lagi
            StreamService.isServiceRunning = false
            refreshCanvasBackground()
        }

        // Setup Scene Manager Dialog buttons (if it was inflated)
        dialogSceneManagerView.findViewById<Button>(R.id.btnNewScene)?.setOnClickListener {
            resetEditingScene()
            Toast.makeText(this, "Siap membuat scene baru", Toast.LENGTH_SHORT).show()
        }
        dialogSceneManagerView.findViewById<Button>(R.id.btnSaveScene)?.setOnClickListener {
            saveCurrentEditingScene()
        }


        dialogSceneManagerView.findViewById<Button>(R.id.btnAddLayer)?.setOnClickListener {
            pickLayerImageLauncher.launch(arrayOf("image/*"))
        }
        dialogSceneManagerView.findViewById<Button>(R.id.btnDeleteSelectedLayer)?.setOnClickListener {
            val id = selectedLayerId ?: return@setOnClickListener
            deleteLayer(id)
            it.isEnabled = false
            it.alpha = 0.4f
        }

        // New Presets Click Listeners
        dialogSceneManagerView.findViewById<View>(R.id.presetPortraitCenter)?.setOnClickListener {
            applyPortraitPreset("center")
        }
        dialogSceneManagerView.findViewById<View>(R.id.presetPortraitTop)?.setOnClickListener {
            applyPortraitPreset("top")
        }
        dialogSceneManagerView.findViewById<View>(R.id.presetPortraitBottom)?.setOnClickListener {
            applyPortraitPreset("bottom")
        }
        dialogSceneManagerView.findViewById<View>(R.id.presetLandscapeFull)?.setOnClickListener {
            applyLandscapeFullPreset()
        }

        findViewById<View>(R.id.fabZoomIn)?.setOnClickListener { sceneCanvasView.zoomIn() }
        findViewById<View>(R.id.fabZoomOut)?.setOnClickListener { sceneCanvasView.zoomOut() }
        findViewById<View>(R.id.btnMoveUp)?.setOnClickListener { sceneCanvasView.moveUp() }
        findViewById<View>(R.id.btnMoveDown)?.setOnClickListener { sceneCanvasView.moveDown() }
        findViewById<View>(R.id.btnMoveLeft)?.setOnClickListener { sceneCanvasView.moveLeft() }
        findViewById<View>(R.id.btnMoveRight)?.setOnClickListener { sceneCanvasView.moveRight() }
        findViewById<View>(R.id.btnMoveReset)?.setOnClickListener { sceneCanvasView.resetMove() }
    }

    private fun buildFullUrl(): String {
        val server = etServerUrl.text.toString().trim()
        val key = etStreamKey.text.toString().trim()
        if (server.isEmpty()) return ""
        if (key.isEmpty()) return server
        return if (server.endsWith("/")) server + key else "$server/$key"
    }

    private fun updateUrlPreview() {
        val url = buildFullUrl()
        tvFullUrlPreview.text = if (url.isEmpty()) "-" else maskKeyForDisplay(url)
    }

    private fun maskKeyForDisplay(url: String): String {
        val key = etStreamKey.text.toString().trim()
        if (key.length <= 4) return url
        val visible = key.take(4)
        return url.replace(key, "$visible****")
    }

    private fun checkPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            checkAndOptimizeAllVideos {
                startPreCachingAllNecessary {
                    requestScreenCapture()
                }
            }
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Mengecek semua scene dan melakukan re-scale video background jika resolusi target berubah.
     * Ini dipanggil sesaat sebelum Live/Record dimulai untuk memastikan efisiensi decoding.
     */
    private fun checkAndOptimizeAllVideos(onComplete: () -> Unit) {
        val (targetW, targetH) = getTargetResolution()
        val prefs = getSharedPreferences("stream_settings", Context.MODE_PRIVATE)
        val bgScale = prefs.getString("video_bg_scale", "Default (Optimize Auto)") ?: "Default (Optimize Auto)"

        val scenesToOptimize = scenes.filter { scene ->
            if (scene.backgroundType != BackgroundType.VIDEO || scene.backgroundUri.isNullOrEmpty()) return@filter false

            val originalUri = VideoOptimizer.getOriginalUri(this, scene.backgroundUri!!)
                ?: Uri.parse(scene.backgroundUri)

            VideoOptimizer.isOptimizationRequired(this, originalUri, scene.backgroundUri, targetW, targetH, bgScale)
        }

        if (scenesToOptimize.isEmpty()) {
            onComplete()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_loading, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvLoadingText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Optimasi Video Background")
            .setMessage("Resolusi target berubah, menyesuaikan video di setiap scene...")
            .setView(dialogView)
            .setCancelable(false)
            .show()

        var currentIdx = 0

        fun optimizeNext() {
            if (currentIdx >= scenesToOptimize.size) {
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    onComplete()
                }
                return
            }

            val scene = scenesToOptimize[currentIdx]
            val originalUri = VideoOptimizer.getOriginalUri(this, scene.backgroundUri!!)
                ?: Uri.parse(scene.backgroundUri)

            runOnUiThread {
                tvProgress.text = "Mengoptimalkan (${scene.name}) - ${currentIdx + 1}/${scenesToOptimize.size}"
                progressBar.progress = (currentIdx * 100 / scenesToOptimize.size)
            }

            VideoOptimizer.optimize(
                context = this,
                inputUri = originalUri,
                targetW = targetW,
                targetH = targetH,
                scaleSetting = bgScale,
                sceneName = scene.name,
                listener = object : VideoOptimizer.Listener {
                    override fun onSuccess(outputUri: Uri) {
                        // Hapus file cache lama
                        val oldUri = scene.backgroundUri
                        
                        // Update URI di scene
                        scene.backgroundUri = outputUri.toString()
                        sceneRepository.saveScenes(scenes) // Simpan semua ke disk

                        // Jika ini scene yang sedang aktif di editor, update state-nya
                        if (scene.id == activeSceneId) {
                            editingBackgroundUri = outputUri.toString()
                            runOnUiThread { refreshCanvasBackground() }
                        }
                        
                        // Hapus file fisik lama JIKA itu file cache
                        oldUri?.takeIf { it.startsWith("file://") && it != outputUri.toString() }?.let {
                            VideoOptimizer.deleteCachedOutputFile(this@MainActivity, it)
                        }

                        currentIdx++
                        optimizeNext()
                    }

                    override fun onError(e: Exception) {
                        Log.e(TAG, "Gagal optimasi otomatis scene ${scene.name}", e)
                        currentIdx++
                        optimizeNext()
                    }
                }
            )
        }

        optimizeNext()
    }

    /**
     * Prefetch SEMUA video background scene ke cache, supaya siap saat scene itu dipilih nanti
     * (lihat VideoCacheManager). PENTING - dulu semua scene ditembak PARALEL sekaligus di sini,
     * yang di device kelas bawah (mis. MediaTek MT6765/PowerVR) menyebabkan:
     *   - N buah HandlerThread + EGL context + MediaCodec dibuat SERENTAK -> GPU driver PowerVR
     *     kewalahan (PVRSRVBridgeCall error), hardware AVC decoder (c2.mtk.avc.decoder) yang
     *     cuma sanggup 1(-2) instance bersamaan jadi flush/discard-loop ratusan kali sampai nyerah.
     *   - Kontensi CPU dari semburan thread itu menyekik main thread -> "Skipped N frames" /
     *     "Davey!" durasi >1 detik pas startup.
     *
     * Fix: (1) ditunda dikit sampai frame pertama selesai render (view.post + delay kecil),
     * dan (2) di-SERIALKAN - satu video di-cache sampai tuntas (pakai onComplete callback yang
     * sudah ada di VideoCacheManager) baru lanjut ke video berikutnya, jadi cuma ada SATU decoder
     * hardware yang aktif dalam satu waktu, bukan N sekaligus. Scene yang SEDANG aktif/dibuka di
     * editor dilewati dulu (kalau video) karena sceneCanvasView kemungkinan sudah memutar videonya
     * sendiri secara live untuk preview - tidak perlu decode dua kali secara bersamaan.
     */
    private fun prefetchAllVideoScenes() {
        val videoScenes = scenes.filter {
            it.backgroundType == BackgroundType.VIDEO && it.backgroundUri != null && it.id != activeSceneId
        }
        if (videoScenes.isEmpty()) return

        // FIX KONTENSI DECODER TAMBAHAN: kalau scene yang lagi aktif/dibuka di editor itu SENDIRI
        // video, SceneCanvasView.setBackgroundVideo() sudah bikin MediaPlayer-nya sendiri buat
        // preview (autoplay, lihat refreshCanvasBackground()) - itu decoder hardware KETIGA
        // (di luar VideoTextureDecoder yang dipakai VideoCacheManager & StreamService) yang bisa
        // rebutan slot codec yang sama. Kasih jeda ekstra di kasus ini biar MediaPlayer preview-nya
        // "settle" (selesai prepare + mulai main) dulu, baru prefetch video scene LAIN mulai jalan -
        // supaya tidak ada 2 decoder video nyalain bersamaan pas boot.
        val activeScene = scenes.find { it.id == activeSceneId }
        val extraDelayMs = if (activeScene?.backgroundType == BackgroundType.VIDEO) 1500L else 0L

        // Tunggu minimal satu siklus layout/draw pertama kelar, ditambah jeda kecil, sebelum
        // mulai kerja background - supaya tidak numpuk di frame yang sama dengan initial render.
        sceneCanvasView.post {
            Handler(Looper.getMainLooper()).postDelayed({
                prefetchVideoScenesSequentially(videoScenes, 0)
            }, PREFETCH_START_DELAY_MS + extraDelayMs)
        }
    }

    private fun prefetchVideoScenesSequentially(videoScenes: List<Scene>, index: Int) {
        if (index >= videoScenes.size) {
            Log.d(TAG, "prefetchVideoScenesSequentially: selesai (${videoScenes.size} scene diproses)")
            return
        }

        val scene = videoScenes[index]
        val uri = Uri.parse(scene.backgroundUri)

        if (VideoCacheManager.getInstance().isCached(uri)) {
            // Sudah ke-cache sebelumnya (mis. URI yang sama dipakai >1 scene) - lewati.
            prefetchVideoScenesSequentially(videoScenes, index + 1)
            return
        }

        Log.d(TAG, "prefetchVideoScenesSequentially: mulai scene='${scene.name}' (${index + 1}/${videoScenes.size})")

        VideoCacheManager.getInstance().prefetch(
            this, uri, scene.rootWidth, scene.rootHeight, 30, 250L * 1024 * 1024,
            object : VideoCacheManager.ProgressListener {
                override fun onProgress(frames: Int) {}
                override fun onComplete(fullyCached: Boolean) {
                    // onComplete bisa dipanggil dari thread decoder internal, bukan main thread -
                    // lompat balik ke main thread cuma untuk lanjut ke item berikutnya (murah/aman).
                    Handler(Looper.getMainLooper()).post {
                        prefetchVideoScenesSequentially(videoScenes, index + 1)
                    }
                }
            }
        )
    }

    private fun startPreCachingAllNecessary(onComplete: () -> Unit) {
        val scenesToCache = scenes.filter {
            it.backgroundType == BackgroundType.VIDEO &&
                    it.backgroundUri != null &&
                    !VideoCacheManager.getInstance().isCached(Uri.parse(it.backgroundUri))
        }

        if (scenesToCache.isEmpty()) {
            onComplete()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_loading, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvLoadingText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Menyiapkan Semua Scene Video")
            .setView(dialogView)
            .setCancelable(false)
            .show()

        var currentIdx = 0

        fun cacheNext() {
            if (currentIdx >= scenesToCache.size) {
                runOnUiThread {
                    dialog.dismiss()
                    onComplete()
                }
                return
            }

            val scene = scenesToCache[currentIdx]
            val p = collectStreamParams()
            val sceneName = scene.name

            runOnUiThread {
                tvProgress.text = "Caching scene ($sceneName) - ${currentIdx + 1}/${scenesToCache.size}"
                progressBar.progress = (currentIdx * 100 / scenesToCache.size)
                tvStatus.text = "Caching: $sceneName..."
            }

            VideoCacheManager.getInstance().prefetch(this, Uri.parse(scene.backgroundUri!!),
                p.width, p.height, p.fps, 250L * 1024 * 1024,
                object : VideoCacheManager.ProgressListener {
                    override fun onProgress(frames: Int) {
                        runOnUiThread {
                            // Progres internal per video (0-100% dari alokasi index ini)
                            val internalProgress = (frames * 100 / 300).coerceAtMost(100)
                            val totalProgress = (currentIdx * 100 + internalProgress) / scenesToCache.size
                            progressBar.progress = totalProgress
                        }
                    }

                    override fun onComplete(fullyCached: Boolean) {
                        currentIdx++
                        cacheNext()
                    }
                })
        }

        cacheNext()
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun getTargetResolution(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val selectedRes = if (::spinnerResolution.isInitialized) spinnerResolution.selectedItem?.toString() ?: "Native" else "Native"

        if (selectedRes.contains("Native")) {
            return metrics.widthPixels to metrics.heightPixels
        } else {
            val resString = selectedRes.split(" ")[0]
            val parts = resString.split("x")
            var w = parts.getOrNull(0)?.toIntOrNull() ?: 720
            var h = parts.getOrNull(1)?.toIntOrNull() ?: 1280

            // Match screen orientation (if screen is portrait, make res portrait)
            val screenIsPortrait = metrics.heightPixels > metrics.widthPixels
            val resIsPortrait = h > w
            if (screenIsPortrait != resIsPortrait) {
                val temp = w
                w = h
                h = temp
            }
            return w to h
        }
    }

    private fun collectStreamParams(): StreamParams {
        val (targetW, targetH) = getTargetResolution()
        var width = targetW
        var height = targetH

        Log.d(TAG, "=== collectStreamParams ===")
        Log.d(TAG, "  targetRes from settings=${width}x${height}")
        Log.d(TAG, "  activeSceneId=$activeSceneId")

        // FIX: Sinkronisasi Orientasi. Jika scene aktif adalah Portrait (tinggi > lebar),
        // pastikan encoder juga Portrait meskipun HP sedang dipegang Landscape (miring).
        val activeScene = scenes.find { it.id == activeSceneId }
        if (activeScene != null) {
            val sceneIsPortrait = activeScene.rootHeight > activeScene.rootWidth
            val encoderIsPortrait = height > width
            Log.d(TAG, "  Scene root=${activeScene.rootWidth}x${activeScene.rootHeight}")
            if (sceneIsPortrait != encoderIsPortrait) {
                Log.w(TAG, "  Swapping dimensions to match scene orientation")
                val temp = width
                width = height
                height = temp
            }
        }

        // PENTING: Lebar dan Tinggi harus angka genap
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        val fpsString = spinnerFps.selectedItem.toString().split(" ")[0]
        val fps = fpsString.toIntOrNull() ?: 30

        val vBitrateKbps = etVideoBitrate.text.toString().toIntOrNull() ?: 2500
        val vBitrate = vBitrateKbps * 1024

        val aBitrateKbps = etAudioBitrate.text.toString().toIntOrNull() ?: 128
        val aBitrate = aBitrateKbps * 1024

        val audioSourceIndex = spinnerAudioSource.selectedItemPosition
        val encoderType = spinnerEncoderType.selectedItemPosition

        val selectedApp = installedApps.getOrNull(spinnerGameAudioApp.selectedItemPosition)

        Log.d(TAG, "  FINAL collectStreamParams: ${width}x${height} @${fps}fps | vBitrate=${vBitrateKbps}kbps | aBitrate=${aBitrateKbps}kbps")
        Log.d(TAG, "  audioSourceIndex=$audioSourceIndex | encoderType=$encoderType | gameUid=${selectedApp?.uid ?: -1}")

        return StreamParams(
            width, height, fps, vBitrate, aBitrate, audioSourceIndex, encoderType,
            micGain = seekMicVolume.progress / 100f,
            systemGain = seekSystemVolume.progress / 100f,
            gameUid = selectedApp?.uid ?: -1
        )
    }

    private fun startStreamService(resultCode: Int, data: Intent) {
        val url = buildFullUrl()
        val p = collectStreamParams()

        val activeScene = scenes.find { it.id == activeSceneId } ?: scenes.firstOrNull()

        Log.d(TAG, "=== startStreamService ===")
        Log.d(TAG, "  URL=$url")
        Log.d(TAG, "  StreamParams: ${p.width}x${p.height} @${p.fps}fps")
        Log.d(TAG, "  activeScene=${activeScene?.name} | id=${activeScene?.id}")
        Log.d(TAG, "  activeScene root=${activeScene?.rootWidth}x${activeScene?.rootHeight}")
        Log.d(TAG, "  activeScene bgType=${activeScene?.backgroundType}")

        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_DATA, data)
            putExtra(StreamService.EXTRA_RTMP_URL, url)
            putExtra(StreamService.EXTRA_WIDTH, p.width)
            putExtra(StreamService.EXTRA_HEIGHT, p.height)
            putExtra(StreamService.EXTRA_FPS, p.fps)
            putExtra(StreamService.EXTRA_BITRATE, p.vBitrate)
            putExtra("audioBitrate", p.aBitrate)
            putExtra("audioSourceIndex", p.audioSourceIndex)
            putExtra(StreamService.EXTRA_ENCODER_TYPE, p.encoderType)
            putExtra(StreamService.EXTRA_MIC_GAIN, p.micGain)
            putExtra(StreamService.EXTRA_SYSTEM_GAIN, p.systemGain)
            putExtra(StreamService.EXTRA_GAME_UID, p.gameUid)

            // Sertakan info scene yang sedang aktif saat mulai live
            if (activeScene != null) {
                if (activeScene.backgroundType == BackgroundType.SCREEN) {
                    putExtra(StreamService.EXTRA_SCENE_TYPE, StreamService.SCENE_SCREEN)
                } else {
                    putExtra(StreamService.EXTRA_SCENE_TYPE, StreamService.SCENE_COMPOSITE)
                    val sceneJson = sceneRepository.toJson(activeScene)
                    putExtra(StreamService.EXTRA_SCENE_JSON, sceneJson)
                    Log.d(TAG, "  Sending SCENE_COMPOSITE with JSON length=${sceneJson.length}")
                }
            }
            grantScenePermissions(this, activeScene)
        }

        val params = Bundle().apply {
            putInt(FirebaseAnalytics.Param.VALUE, p.width)
            putInt("height", p.height)
            putInt("fps", p.fps)
            putInt(FirebaseAnalytics.Param.LEVEL, p.vBitrate)
        }
        firebaseAnalytics.logEvent("stream_started", params)

        // FIX "video tidak bergerak" saat live/test record: startForegroundService() TIDAK
        // menjamin StreamService.onCreate() (yang set isServiceRunning=true) sudah jalan begitu
        // baris berikutnya dieksekusi - keduanya sama2 di main thread, jadi onCreate() service
        // baru bisa jalan SETELAH method ini selesai return ke looper. Kalau refreshCanvasBackground()
        // dipanggil SETELAH startForegroundService() (urutan lama), isServiceRunning masih kebaca
        // false sesaat, autoPlay preview video di editor jadi tetap true, dan preview itu terus
        // decode video BERBARENGAN dengan decoder video yang dipakai live/test-record -> di HW
        // decoder yang cuma sanggup 1-2 instance bersamaan (lihat komentar prefetchAllVideoScenes),
        // salah satu decoder jadi freeze/stuck. Fix: set flag & hentikan preview video editor DULU,
        // baru start service-nya.
        StreamService.isServiceRunning = true
        sceneCanvasView.pauseVideo()
        refreshCanvasBackground()

        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "Status: live streaming..."
    }

    private fun startTestRecordService(resultCode: Int, data: Intent) {
        val p = collectStreamParams()
        val activeScene = scenes.find { it.id == activeSceneId } ?: scenes.firstOrNull()

        Log.d(TAG, "=== startTestRecordService ===")
        Log.d(TAG, "  StreamParams: ${p.width}x${p.height} @${p.fps}fps")

        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_TEST_RECORD
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_DATA, data)
            putExtra(StreamService.EXTRA_WIDTH, p.width)
            putExtra(StreamService.EXTRA_HEIGHT, p.height)
            putExtra(StreamService.EXTRA_FPS, p.fps)
            putExtra(StreamService.EXTRA_BITRATE, p.vBitrate)
            putExtra("audioBitrate", p.aBitrate)
            putExtra("audioSourceIndex", p.audioSourceIndex)
            putExtra(StreamService.EXTRA_ENCODER_TYPE, p.encoderType)
            putExtra(StreamService.EXTRA_TEST_DURATION_MS, 30000L)

            if (activeScene != null) {
                if (activeScene.backgroundType == BackgroundType.SCREEN) {
                    putExtra(StreamService.EXTRA_SCENE_TYPE, StreamService.SCENE_SCREEN)
                } else {
                    putExtra(StreamService.EXTRA_SCENE_TYPE, StreamService.SCENE_COMPOSITE)
                    putExtra(StreamService.EXTRA_SCENE_JSON, sceneRepository.toJson(activeScene))
                }
            }
            grantScenePermissions(this, activeScene)
        }

        // Sama seperti startStreamService(): hentikan preview video editor & set flag SEBELUM
        // startForegroundService(), supaya tidak ada 2 video decoder hardware jalan bersamaan
        // (preview editor + decoder punya CompositeSceneVideoSource utk test record) pas start.
        StreamService.isServiceRunning = true
        sceneCanvasView.pauseVideo()
        refreshCanvasBackground()

        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "Status: test recording..."
    }

    private data class StreamParams(
        val width: Int,
        val height: Int,
        val fps: Int,
        val vBitrate: Int,
        val aBitrate: Int,
        val audioSourceIndex: Int,
        val encoderType: Int,
        val micGain: Float,
        val systemGain: Float,
        val gameUid: Int
    )
}
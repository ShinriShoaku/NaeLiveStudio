package ame.project.nlstudio

import ame.project.nlstudio.OBS.AudioLevelBus
import ame.project.nlstudio.scene.SceneRepository
import ame.project.nlstudio.scene.VoiceAnimConfig
import ame.project.nlstudio.scene.VoiceAnimItem
import ame.project.nlstudio.ui.VuMeterView
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class VoiceAnimSettingsActivity : AppCompatActivity() {

    private lateinit var rvItems: RecyclerView
    private lateinit var adapter: VoiceAnimAdapter
    private lateinit var config: VoiceAnimConfig
    private lateinit var ivPreview: ImageView
    private lateinit var vuMeter: VuMeterView
    private lateinit var sceneRepository: SceneRepository
    private lateinit var btnTestMic: android.widget.Button
    private lateinit var seekMinBrightness: SeekBar
    private lateinit var seekScaleIntensity: SeekBar
    private lateinit var seekEffectThresholdStart: SeekBar
    private lateinit var seekEffectThresholdEnd: SeekBar
    private lateinit var tvMinBrightnessLabel: TextView
    private lateinit var tvScaleIntensityLabel: TextView
    private lateinit var tvEffectThresholdStartLabel: TextView
    private lateinit var tvEffectThresholdEndLabel: TextView

    private var isTestingMic = false
    private var testAudioRecord: android.media.AudioRecord? = null
    private var testThread: Thread? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val size = getFileSize(it)
            if (size > 1024 * 1024) { // 1MB
                android.widget.Toast.makeText(this, "Ukuran gambar maksimal 1MB (Sekarang: ${size / 1024}KB)", android.widget.Toast.LENGTH_LONG).show()
                return@let
            }
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            adapter.updateImage(it.toString())
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_anim_settings)

        rvItems = findViewById(R.id.rvVoiceAnimItems)
        ivPreview = findViewById(R.id.ivPreview)
        vuMeter = findViewById(R.id.vuMeter)
        btnTestMic = findViewById(R.id.btnTestMic)
        seekMinBrightness = findViewById(R.id.seekMinBrightness)
        seekScaleIntensity = findViewById(R.id.seekScaleIntensity)
        seekEffectThresholdStart = findViewById(R.id.seekEffectThresholdStart)
        seekEffectThresholdEnd = findViewById(R.id.seekEffectThresholdEnd)
        tvMinBrightnessLabel = findViewById(R.id.tvMinBrightnessLabel)
        tvScaleIntensityLabel = findViewById(R.id.tvScaleIntensityLabel)
        tvEffectThresholdStartLabel = findViewById(R.id.tvEffectThresholdStartLabel)
        tvEffectThresholdEndLabel = findViewById(R.id.tvEffectThresholdEndLabel)
        sceneRepository = SceneRepository(this)

        val prefs = getSharedPreferences("voice_anim_prefs", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("default_config", null)
        config = VoiceAnimConfig.fromJson(savedJson)

        if (config.items.isEmpty()) {
            config.items.add(VoiceAnimItem(0.0f, ""))
            config.items.add(VoiceAnimItem(0.2f, ""))
        }

        adapter = VoiceAnimAdapter(config.items) { position ->
            adapter.currentPickingPosition = position
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        rvItems.layoutManager = LinearLayoutManager(this)
        rvItems.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            prefs.edit().putString("default_config", config.toJson()).apply()
            finish()
        }
        findViewById<View>(R.id.btnAddItem).setOnClickListener {
            config.items.add(VoiceAnimItem(0.8f, ""))
            config.items.sortWith(compareBy { it.threshold })
            adapter.notifyDataSetChanged()
        }

        setupEffectControls()

        btnTestMic.setOnClickListener {
            if (isTestingMic) {
                stopMicTest()
            } else {
                startMicTest()
            }
        }

        AudioLevelBus.registerListener(audioLevelListener)
    }

    private fun setupEffectControls() {
        seekMinBrightness.progress = (config.minBrightness * 100).toInt()
        tvMinBrightnessLabel.text = "Kecerahan saat Hening: ${seekMinBrightness.progress}%"
        
        seekScaleIntensity.progress = (config.scaleIntensity * 100).toInt()
        tvScaleIntensityLabel.text = "Intensitas Pop (Goncangan): ${seekScaleIntensity.progress}%"

        seekEffectThresholdStart.progress = (config.effectThresholdStart * 100).toInt()
        tvEffectThresholdStartLabel.text = "Mulai Reaksi (Volume %): ${seekEffectThresholdStart.progress}%"

        seekEffectThresholdEnd.progress = (config.effectThresholdEnd * 100).toInt()
        tvEffectThresholdEndLabel.text = "Maksimal Reaksi (Volume %): ${seekEffectThresholdEnd.progress}%"

        seekMinBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    config.minBrightness = progress / 100f
                    tvMinBrightnessLabel.text = "Kecerahan saat Hening: $progress%"
                    updatePreview(0f) // Show silent preview
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekScaleIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    config.scaleIntensity = progress / 100f
                    tvScaleIntensityLabel.text = "Intensitas Pop (Goncangan): $progress%"
                    updatePreview(1f) // Show max scale preview
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekEffectThresholdStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    config.effectThresholdStart = progress / 100f
                    tvEffectThresholdStartLabel.text = "Mulai Reaksi (Volume %): $progress%"
                    if (config.effectThresholdStart > config.effectThresholdEnd) {
                        seekEffectThresholdEnd.progress = progress
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekEffectThresholdEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    config.effectThresholdEnd = progress / 100f
                    tvEffectThresholdEndLabel.text = "Maksimal Reaksi (Volume %): $progress%"
                    if (config.effectThresholdEnd < config.effectThresholdStart) {
                        seekEffectThresholdStart.progress = progress
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    @SuppressLint("MissingPermission")
    private fun startMicTest() {
        isTestingMic = true
        btnTestMic.text = "STOP TEST MIC"
        btnTestMic.setTextColor(android.graphics.Color.RED)

        val sampleRate = 44100
        val bufferSize = android.media.AudioRecord.getMinBufferSize(
            sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        
        try {
            testAudioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            testAudioRecord?.startRecording()
            
            testThread = Thread {
                val buffer = ShortArray(bufferSize)
                while (isTestingMic) {
                    val read = testAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = Math.sqrt(sum / read)
                        val normalized = (rms / 32768.0).toFloat() * 5.0f // Boost for visualization
                        val level = normalized.coerceIn(0f, 1f)
                        
                        runOnUiThread {
                            vuMeter.setLevel(level)
                            updatePreview(level)
                        }
                    }
                }
            }
            testThread?.start()
        } catch (e: Exception) {
            stopMicTest()
            android.widget.Toast.makeText(this, "Gagal akses mic: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMicTest() {
        isTestingMic = false
        btnTestMic.text = "MULAI TEST MIC"
        btnTestMic.setTextColor(android.graphics.Color.parseColor("#7FD8A6"))
        
        testAudioRecord?.stop()
        testAudioRecord?.release()
        testAudioRecord = null
        testThread = null
    }

    private val audioLevelListener = AudioLevelBus.Listener { mic, _, _ ->
        if (!isTestingMic) {
            runOnUiThread {
                vuMeter.setLevel(mic)
                updatePreview(mic)
            }
        }
    }

    private fun updatePreview(level: Float) {
        val item = config.items.filter { it.imageUri.isNotEmpty() }
            .sortedByDescending { it.threshold }
            .firstOrNull { level >= it.threshold }
        
        if (item != null) {
            ivPreview.setImageBitmap(sceneRepository.loadPreviewBitmap(Uri.parse(item.imageUri)))
            
            // Normalize level based on threshold start and end
            val range = (config.effectThresholdEnd - config.effectThresholdStart).coerceAtLeast(0.01f)
            val normalizedLevel = ((level - config.effectThresholdStart) / range).coerceIn(0f, 1f)

            // Brightness effect using dynamic minBrightness
            val brightness = config.minBrightness + (normalizedLevel * (1f - config.minBrightness))
            val matrix = android.graphics.ColorMatrix().apply {
                setScale(brightness, brightness, brightness, 1f)
            }
            ivPreview.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            
            // Scale effect using dynamic scaleIntensity
            val baseScale = 1.0f - (config.scaleIntensity / 2f)
            val scale = baseScale + (normalizedLevel * config.scaleIntensity)
            ivPreview.scaleX = scale
            ivPreview.scaleY = scale
        } else {
            ivPreview.setImageResource(android.R.drawable.ic_menu_gallery)
            ivPreview.colorFilter = null
            ivPreview.scaleX = 1.0f
            ivPreview.scaleY = 1.0f
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMicTest()
        AudioLevelBus.unregisterListener(audioLevelListener)
    }

    inner class VoiceAnimAdapter(
        private val items: MutableList<VoiceAnimItem>,
        private val onPickImage: (Int) -> Unit
    ) : RecyclerView.Adapter<VoiceAnimAdapter.ViewHolder>() {

        var currentPickingPosition: Int = -1

        fun updateImage(uri: String) {
            if (currentPickingPosition >= 0) {
                items[currentPickingPosition].imageUri = uri
                notifyItemChanged(currentPickingPosition)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_voice_anim, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pos = holder.bindingAdapterPosition
            val item = items[pos]
            holder.tvLabel.text = "Threshold: ${(item.threshold * 100).toInt()}%"
            holder.seekBar.progress = (item.threshold * 100).toInt()
            
            if (item.imageUri.isNotEmpty()) {
                holder.ivThumb.setImageBitmap(sceneRepository.loadPreviewBitmap(Uri.parse(item.imageUri)))
            } else {
                holder.ivThumb.setImageDrawable(null)
            }

            holder.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        item.threshold = progress / 100f
                        holder.tvLabel.text = "Threshold: $progress%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            holder.btnPick.setOnClickListener { onPickImage(pos) }
            holder.btnRemove.setOnClickListener {
                items.removeAt(pos)
                notifyDataSetChanged()
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvLabel: TextView = v.findViewById(R.id.tvThresholdLabel)
            val seekBar: SeekBar = v.findViewById(R.id.seekThreshold)
            val ivThumb: ImageView = v.findViewById(R.id.ivItemThumb)
            val btnPick: View = v.findViewById(R.id.btnPickImage)
            val btnRemove: View = v.findViewById(R.id.btnRemoveItem)
        }
    }
}

package ame.project.nlsdk


import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class KanaeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KanaeSDK"
    }

    private var kanaeService: IKanaeService? = null
    private var isBound = false

    private lateinit var ivThumbnail: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvLyrics: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSkip: ImageButton
    private lateinit var btnStop: ImageButton

    private lateinit var etRequest: EditText
    private lateinit var btnRequest: Button
    private lateinit var etTikTokUser: EditText
    private lateinit var btnTikTokConnect: Button
    private lateinit var tvTikTokStatus: TextView
    private lateinit var tvChat: TextView
    private lateinit var sbVolume: android.widget.SeekBar

    private val callback = object : IKanaeCallback.Stub() {
        override fun onTrackChanged(title: String?, artist: String?, duration: String?, thumbnail: String?) {
            runOnUiThread {
                tvTitle.text = title ?: "No Title"
                tvArtist.text = artist ?: "No Artist"
            }
        }

        override fun onLyricsChanged(lyrics: String?) {
            runOnUiThread {
                tvLyrics.text = lyrics ?: ""
            }
        }

        override fun onQueueChanged(queueJson: String?) {
            Log.d(TAG, "Queue updated")
        }

        override fun onPlaybackStatusChanged(isPlaying: Boolean, position: Long, duration: Long) {
            runOnUiThread {
                btnPlayPause.setImageResource(
                    if (isPlaying) android.R.drawable.ic_media_pause 
                    else android.R.drawable.ic_media_play,
                )
            }
        }

        override fun onChatMessage(user: String?, message: String?) {
            runOnUiThread {
                val currentText = tvChat.text.toString()
                val newChat = "$user: $message\n"
                tvChat.text = (newChat + currentText).take(1000)
            }
        }

        override fun onGiftMessage(user: String?, gift: String?, giftUrl: String?, count: Int) {
            runOnUiThread {
                val currentText = tvChat.text.toString()
                val newGift = "[GIFT] $user mengirim $gift x$count\n"
                tvChat.text = (newGift + currentText).take(1000)
            }
        }

        override fun onTikTokStatus(connected: Boolean, username: String?) {
            runOnUiThread {
                if (connected) {
                    tvTikTokStatus.text = "TikTok: Connected (@$username)"
                    tvTikTokStatus.setTextColor(Color.GREEN)
                    btnTikTokConnect.text = "DISCONNECT"
                } else {
                    tvTikTokStatus.text = "TikTok: Disconnected"
                    tvTikTokStatus.setTextColor(Color.RED)
                    btnTikTokConnect.text = "CONNECT"
                }
            }
        }

        override fun onUserJoined(user: String?, profileUrl: String?) {
            addLog("[JOIN] $user bergabung")
        }

        override fun onUserLiked(user: String?, profileUrl: String?, count: Int) {
            addLog("[LIKE] $user menyukai x$count")
        }

        override fun onUserFollowed(user: String?, profileUrl: String?) {
            addLog("[FOLLOW] $user mengikuti")
        }

        override fun onUserShared(user: String?, profileUrl: String?) {
            addLog("[SHARE] $user membagikan")
        }
    }

    private fun addLog(message: String) {
        runOnUiThread {
            val currentText = tvChat.text.toString()
            tvChat.text = (message + "\n" + currentText).take(1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            kanaeService = IKanaeService.Stub.asInterface(service)
            isBound = true
            try {
                kanaeService?.registerCallback(callback)
                updateUI()
            } catch (e: Exception) {
                Log.e(TAG, "Error registering callback", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            kanaeService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kanae)

        ivThumbnail = findViewById(R.id.ivThumbnail)
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvLyrics = findViewById(R.id.tvLyrics)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSkip = findViewById(R.id.btnSkip)
        btnStop = findViewById(R.id.btnStop)

        etRequest = findViewById(R.id.etRequest)
        btnRequest = findViewById(R.id.btnRequest)
        etTikTokUser = findViewById(R.id.etTikTokUser)
        btnTikTokConnect = findViewById(R.id.btnTikTokConnect)
        tvTikTokStatus = findViewById(R.id.tvTikTokStatus)
        tvChat = findViewById(R.id.tvChat)
        sbVolume = findViewById(R.id.sbVolume)

        sbVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    try {
                        kanaeService?.setVolume(progress / 100f)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        btnPlayPause.setOnClickListener {
            try { kanaeService?.playPause() } catch (e: Exception) { e.printStackTrace() }
        }

        btnSkip.setOnClickListener {
            try { kanaeService?.skip() } catch (e: Exception) { e.printStackTrace() }
        }

        btnStop.setOnClickListener {
            try { kanaeService?.stop() } catch (e: Exception) { e.printStackTrace() }
        }

        btnRequest.setOnClickListener {
            val query = etRequest.text.toString()
            if (query.isNotBlank()) {
                try {
                    kanaeService?.requestMusic(query)
                    etRequest.setText("")
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        btnTikTokConnect.setOnClickListener {
            try {
                if (kanaeService?.isTikTokConnected == true) {
                    kanaeService?.disconnectTikTok()
                } else {
                    val user = etTikTokUser.text.toString()
                    if (user.isNotBlank()) {
                        kanaeService?.connectTikTok(user)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        bindKanaeService()
    }

    private fun bindKanaeService() {
        try {
            val intent = Intent("ame.project.kanae.AIDL_SERVICE")
            intent.`package` = "ame.project.kanae"
            bindService(intent, connection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding", e)
        }
    }

    private fun updateUI() {
        try {
            val isPlaying = kanaeService?.isPlaying ?: false
            btnPlayPause.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause 
                else android.R.drawable.ic_media_play
            )

            val isTikTokConnected = kanaeService?.isTikTokConnected ?: false
            if (isTikTokConnected) {
                tvTikTokStatus.text = "TikTok: Connected"
                tvTikTokStatus.setTextColor(Color.GREEN)
                btnTikTokConnect.text = "DISCONNECT"
            }

            val currentSongJson = kanaeService?.currentSongJson
            if (!currentSongJson.isNullOrEmpty()) {
                val json = org.json.JSONObject(currentSongJson)
                tvTitle.text = json.optString("title", "No Title")
                tvArtist.text = json.optString("channel", "No Artist")
            }

            val volume = kanaeService?.volume ?: 1f
            sbVolume.progress = (volume * 100).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateUI", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try { kanaeService?.unregisterCallback(callback) } catch (e: Exception) {}
            unbindService(connection)
            isBound = false
        }
    }
}

/*
 * NL Studio - KanaeOverlayBridge
 *
 * Singleton yang bertugas:
 *  1. Bind ke IKanaeService milik Kanae Player (tanpa startForegroundService,
 *     karena di sini kita cuma butuh baca data, bukan menyalakan playback).
 *  2. Menerima callback onCustomOverlaysChanged() dan menyimpan hasil parse-nya
 *     sebagai List<KanaeWebOverlay>.
 *  3. Memberi tahu listener (mis. MainActivity) setiap kali daftar overlay berubah,
 *     lewat onOverlaysUpdated.
 *
 * Cara pakai (di Activity yang punya editor layer):
 *
 *   override fun onStart() {
 *       super.onStart()
 *       KanaeOverlayBridge.onOverlaysUpdated = { list -> runOnUiThread { renderKanaeOverlayButtons(list) } }
 *       KanaeOverlayBridge.bind(this)
 *   }
 *
 *   override fun onStop() {
 *       super.onStop()
 *       KanaeOverlayBridge.unbind(this)
 *   }
 */

package ame.project.nlsdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import org.json.JSONArray

/**
 * Representasi satu Custom Web Overlay yang dikirim dari Kanae Player.
 * Field ini mengikuti CustomOverlayConfig di sisi Kanae (id, name, url).
 */
data class KanaeWebOverlay(
    val id: String,
    val name: String,
    val url: String
)

object KanaeOverlayBridge {

    private const val TAG = "KanaeOverlayBridge"
    private const val KANAE_PACKAGE = "ame.project.kanae"
    private const val KANAE_AIDL_ACTION = "ame.project.kanae.AIDL_SERVICE"

    private var service: IKanaeService? = null
    private var bound = false

    /** Daftar overlay terakhir yang diketahui (bisa dibaca kapan saja tanpa menunggu callback). */
    var overlays: List<KanaeWebOverlay> = emptyList()
        private set

    /** Dipanggil setiap kali daftar overlay berubah. NOTE: dipanggil dari binder thread,
     *  jadi kalau mau update UI, wrap dengan runOnUiThread di sisi pemanggil. */
    var onOverlaysUpdated: ((List<KanaeWebOverlay>) -> Unit)? = null

    /** Dipanggil saat berhasil / gagal connect ke service Kanae, berguna untuk menampilkan
     *  status "Kanae Player belum terbuka" di UI kalau isConnected == false. */
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    val isConnected: Boolean get() = bound

    private fun parseOverlays(json: String?): List<KanaeWebOverlay> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val result = ArrayList<KanaeWebOverlay>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    KanaeWebOverlay(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        url = o.optString("url")
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Gagal parse overlaysJson", e)
            emptyList()
        }
    }

    private val callback = object : IKanaeCallback.Stub() {
        override fun onTrackChanged(title: String?, artist: String?, duration: String?, thumbnail: String?) {}
        override fun onLyricsChanged(lyrics: String?) {}
        override fun onQueueChanged(queueJson: String?) {}
        override fun onPlaybackStatusChanged(isPlaying: Boolean, position: Long, duration: Long) {}
        override fun onChatMessage(user: String?, message: String?) {}
        override fun onGiftMessage(user: String?, gift: String?, giftUrl: String?, count: Int) {}
        override fun onTikTokStatus(connected: Boolean, username: String?) {}
        override fun onUserJoined(user: String?, profileUrl: String?) {}
        override fun onUserLiked(user: String?, profileUrl: String?, count: Int) {}
        override fun onUserFollowed(user: String?, profileUrl: String?) {}
        override fun onUserShared(user: String?, profileUrl: String?) {}

        override fun onCustomOverlaysChanged(overlaysJson: String?) {
            overlays = parseOverlays(overlaysJson)
            Log.d(TAG, "onCustomOverlaysChanged: ${overlays.size} overlay diterima")
            onOverlaysUpdated?.invoke(overlays)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IKanaeService.Stub.asInterface(binder)
            bound = true
            onConnectionStateChanged?.invoke(true)
            try {
                service?.registerCallback(callback)
                // registerCallback di sisi Kanae otomatis mengirim daftar overlay saat ini,
                // tapi kita minta ulang juga untuk jaga-jaga.
                service?.requestCustomOverlays()
            } catch (e: Exception) {
                Log.e(TAG, "Error registerCallback", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            onConnectionStateChanged?.invoke(false)
        }
    }

    /**
     * Bind ke service Kanae. Aman dipanggil berkali-kali (no-op kalau sudah bound).
     * Kalau Kanae Player belum terpasang / service tidak ada, bindService cukup
     * mengembalikan false / melempar exception yang kita tangkap - overlays akan
     * tetap kosong sampai Kanae dibuka.
     */
    fun bind(context: Context) {
        if (bound) return
        try {
            val intent = Intent(KANAE_AIDL_ACTION).apply { `package` = KANAE_PACKAGE }
            val ok = context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                Log.w(TAG, "bindService ke Kanae gagal (Kanae Player belum terpasang/aktif?)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding ke Kanae", e)
        }
    }

    fun unbind(context: Context) {
        if (!bound) return
        try {
            service?.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        try {
            context.applicationContext.unbindService(connection)
        } catch (_: Exception) {
        }
        service = null
        bound = false
    }

    /** Minta ulang daftar overlay terbaru secara manual, mis. saat user pull-to-refresh. */
    fun refresh() {
        try {
            service?.requestCustomOverlays()
        } catch (e: Exception) {
            Log.e(TAG, "Error requestCustomOverlays", e)
        }
    }
}

package ame.project.nlstudio.OBS;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REFACTORED: VideoCacheManager mengelola pool VideoTextureDecoder (GPU warm players).
 *
 * Optimisasi ganti-scene:
 * 1. decoder.stop() yang mahal/bisa lama TIDAK dipanggil di dalam blok `synchronized`, supaya
 *    acquire() untuk video LAIN (misal pas ganti scene) tidak ikut ke-block menunggu.
 * 2. release() tidak langsung men-stop decoder begitu refcount = 0. Ada GRACE_PERIOD_MS: kalau
 *    scene yang sama di-acquire lagi dalam waktu itu (user gonta-ganti scene bolak-balik),
 *    decoder yang sudah "warm" dipakai lagi tanpa re-buffer/re-create ExoPlayer -> scene switch
 *    kelihatan instant.
 * 3. Pool dibatasi MAX_WARM_DECODERS dengan strategi LRU. Jumlah scene video bisa dinamis/tidak
 *    diketahui di awal, dan Android membatasi jumlah instance hardware video decoder yang boleh
 *    aktif BERSAMAAN di seluruh sistem (bukan cuma app ini, biasanya sekitar 4 di banyak device).
 *    Jadi TIDAK semua scene bisa/boleh di-warm terus-menerus tanpa batas - yang paling lama tidak
 *    dipakai akan otomatis dilepas duluan begitu ada scene baru yang butuh slot. Decoder yang lagi
 *    aktif dipakai (refCount > 0) tidak pernah ikut di-evict oleh mekanisme ini.
 */
public class VideoCacheManager {
    private static final String TAG = "VideoCache";
    private static final long GRACE_PERIOD_MS = 5000; // 5 detik "keep-warm" sebelum benar2 di-stop

    // Batas aman jumlah decoder hardware yang di-warm bersamaan. Naikkan hati-hati - device
    // low-end sering cuma sanggup ~4 instance decoder hardware aktif di SELURUH sistem.
    private static final int MAX_WARM_DECODERS = 4;

    private static volatile VideoCacheManager instance;

    public interface ProgressListener {
        void onProgress(int frames);
        void onComplete(boolean fullyCached);
    }

    // accessOrder=true -> LinkedHashMap otomatis pindahkan entry yang di-get/put ke posisi
    // "paling baru dipakai". Urutan dari depan = least-recently-used, pas untuk basis LRU eviction.
    private final LinkedHashMap<String, VideoTextureDecoder> decoderMap =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Integer> refCounts = new HashMap<>();
    private final Map<String, Runnable> pendingEvictions = new HashMap<>();
    private final Handler evictionHandler = new Handler(Looper.getMainLooper());

    private VideoCacheManager() {}

    public static VideoCacheManager getInstance() {
        if (instance == null) {
            synchronized (VideoCacheManager.class) {
                if (instance == null) instance = new VideoCacheManager();
            }
        }
        return instance;
    }

    /**
     * Ambil decoder untuk URI tertentu. Jika belum ada, akan dibuatkan baru (warm).
     * Kalau pool sudah penuh (MAX_WARM_DECODERS), decoder yang paling lama tidak dipakai
     * (dan sedang tidak aktif) akan dilepas dulu untuk memberi tempat.
     */
    public VideoTextureDecoder acquire(Context context, Uri uri, int w, int h, int fps) {
        if (uri == null) return null;
        String key = uri.toString();

        Runnable pendingEvict;
        synchronized (this) {
            pendingEvict = pendingEvictions.remove(key);
        }
        if (pendingEvict != null) {
            evictionHandler.removeCallbacks(pendingEvict);
            Log.d(TAG, "Cancel eviction, decoder dipakai lagi: " + key);
        }

        VideoTextureDecoder decoder;
        List<VideoTextureDecoder> lruVictims = null;
        synchronized (this) {
            // get() di sini juga menandai key ini sebagai "paling baru dipakai" (accessOrder).
            decoder = decoderMap.get(key);
            if (decoder == null) {
                lruVictims = evictLruIfNeededLocked();
                Log.d(TAG, "Creating new warm decoder: " + key);
                decoder = new VideoTextureDecoder(context, uri, w, h, true, fps, null);
                decoder.start();
                decoderMap.put(key, decoder);
            }
            refCounts.merge(key, 1, Integer::sum);
        }

        // Stop korban LRU di luar lock supaya tidak nge-block pemanggil lain.
        if (lruVictims != null) {
            for (VideoTextureDecoder victim : lruVictims) victim.stop();
        }
        return decoder;
    }

    /** Dipanggil hanya dari dalam synchronized(this). */
    private List<VideoTextureDecoder> evictLruIfNeededLocked() {
        List<VideoTextureDecoder> victims = new ArrayList<>();
        if (decoderMap.size() < MAX_WARM_DECODERS) return victims;

        Iterator<Map.Entry<String, VideoTextureDecoder>> it = decoderMap.entrySet().iterator();
        while (decoderMap.size() >= MAX_WARM_DECODERS && it.hasNext()) {
            Map.Entry<String, VideoTextureDecoder> entry = it.next();
            String key = entry.getKey();
            Integer count = refCounts.get(key);
            if (count != null && count > 0) {
                // Masih aktif dipakai (scene lain yang sedang tampil) - jangan disentuh,
                // lanjut cari kandidat berikutnya yang benar-benar idle.
                continue;
            }
            it.remove();
            refCounts.remove(key);
            Runnable pending = pendingEvictions.remove(key);
            if (pending != null) evictionHandler.removeCallbacks(pending);
            Log.d(TAG, "LRU evict warm decoder (pool penuh, " + MAX_WARM_DECODERS + " slot): " + key);
            victims.add(entry.getValue());
        }
        return victims;
    }

    public synchronized boolean isCached(Uri uri) {
        if (uri == null) return false;
        return decoderMap.containsKey(uri.toString());
    }

    public boolean isFullyCached(Uri uri) {
        return isCached(uri);
    }

    /**
     * Lepaskan penggunaan decoder. Decoder tidak langsung di-stop; ditunda [GRACE_PERIOD_MS]
     * supaya kalau scene yang sama di-buka lagi sebentar lagi, tidak perlu re-buffer dari nol.
     * (Kalau sementara itu pool penuh dan slotnya dibutuhkan scene lain, LRU eviction di
     * acquire() bisa menghentikannya lebih awal dari grace period ini.)
     */
    public void release(Uri uri) {
        if (uri == null) return;
        final String key = uri.toString();

        boolean shouldScheduleEviction;
        synchronized (this) {
            Integer count = refCounts.get(key);
            if (count == null) {
                shouldScheduleEviction = false;
            } else if (count <= 1) {
                refCounts.remove(key);
                shouldScheduleEviction = true;
            } else {
                refCounts.put(key, count - 1);
                shouldScheduleEviction = false;
            }
        }

        if (!shouldScheduleEviction) return;

        Runnable evict = new Runnable() {
            @Override
            public void run() {
                VideoTextureDecoder decoderToStop = null;
                synchronized (VideoCacheManager.this) {
                    pendingEvictions.remove(key);
                    if (!refCounts.containsKey(key)) {
                        decoderToStop = decoderMap.remove(key);
                    }
                }
                if (decoderToStop != null) {
                    Log.d(TAG, "Evicting idle warm decoder: " + key);
                    decoderToStop.stop(); // di luar lock - tidak nge-block acquire() video lain
                }
            }
        };
        pendingEvictions.put(key, evict);
        evictionHandler.postDelayed(evict, GRACE_PERIOD_MS);
    }

    /**
     * Panaskan (prepare/buffer) sebuah video di awal, TANPA menahannya permanen. Setelah warm,
     * decoder langsung dilepas lagi ke jalur normal (grace period + LRU cap) - jadi prefetch aman
     * dipanggil untuk banyak scene sekaligus tanpa membuat semuanya "terkunci" selamanya dan
     * mendesak scene lain kehabisan slot decoder.
     */
    public void prefetch(Context context, Uri uri, int w, int h, int fps, long memoryBudget, ProgressListener listener) {
        if (uri == null) {
            if (listener != null) listener.onComplete(true);
            return;
        }
        acquire(context, uri, w, h, fps);
        release(uri);
        if (listener != null) listener.onComplete(true);
    }

    public void prefetch(Context context, Uri uri, int w, int h, int fps, long memoryBudget,
                         int maxFrames, ProgressListener listener) {
        prefetch(context, uri, w, h, fps, memoryBudget, listener);
    }

    public void clearAll() {
        List<VideoTextureDecoder> toStop;
        synchronized (this) {
            for (Runnable r : pendingEvictions.values()) evictionHandler.removeCallbacks(r);
            pendingEvictions.clear();
            toStop = new ArrayList<>(decoderMap.values());
            decoderMap.clear();
            refCounts.clear();
        }
        for (VideoTextureDecoder d : toStop) d.stop();
    }
}
package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton untuk mengelola cache background video antar scene.
 * Cache dihasilkan oleh VideoTextureDecoder dalam format RGB_565 untuk hemat RAM.
 */
public class VideoCacheManager {
    private static final String TAG = "VideoCache";
    private static VideoCacheManager instance;

    public interface ProgressListener {
        void onProgress(int frames);
        void onComplete();
    }

    private final Map<String, List<Bitmap>> cacheMap = new ConcurrentHashMap<>();
    private final Map<String, VideoTextureDecoder> prefetchers = new ConcurrentHashMap<>();
    private final Map<String, ProgressListener> listeners = new ConcurrentHashMap<>();

    private VideoCacheManager() {}

    public static synchronized VideoCacheManager getInstance() {
        if (instance == null) instance = new VideoCacheManager();
        return instance;
    }

    public boolean isCached(Uri uri) {
        if (uri == null) return true;
        List<Bitmap> frames = cacheMap.get(uri.toString());
        return frames != null && !frames.isEmpty() && !prefetchers.containsKey(uri.toString());
    }

    /** Mulai pre-caching untuk URI tertentu jika belum ada. */
    public void prefetch(Context context, Uri uri, int w, int h, int fps, long memoryBudget, ProgressListener listener) {
        if (uri == null) {
            if (listener != null) listener.onComplete();
            return;
        }
        String key = uri.toString();
        
        // Jika sudah ada cache lengkap, langsung panggil onComplete
        if (isCached(uri)) {
            if (listener != null) listener.onComplete();
            return;
        }

        if (listener != null) listeners.put(key, listener);
        
        // Jika sedang berjalan, biarkan saja (listener sudah didaftarkan)
        if (prefetchers.containsKey(key)) return;

        Log.d(TAG, "Mulai prefetch: " + key);
        List<Bitmap> frames = Collections.synchronizedList(new ArrayList<>());
        
        VideoTextureDecoder decoder = new VideoTextureDecoder(context, uri, w, h, false, new VideoTextureDecoder.Listener() {
            @Override
            public void onFrame(Bitmap bitmap) {
                int frameSize = w * h * 2; // RGB_565
                if ((long) (frames.size() + 1) * frameSize > memoryBudget) {
                    Log.d(TAG, "Budget penuh untuk " + key + ", berhenti prefetch");
                    stopAndFinalize(key);
                    return;
                }
                Bitmap copy = bitmap.copy(Bitmap.Config.RGB_565, false);
                if (copy != null) {
                    frames.add(copy);
                    ProgressListener l = listeners.get(key);
                    if (l != null) l.onProgress(frames.size());
                }
            }

            @Override
            public void onComplete() {
                Log.d(TAG, "Prefetch selesai: " + key + " (" + frames.size() + " frames)");
                stopAndFinalize(key);
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Prefetch error: " + key, e);
                stopAndFinalize(key);
            }
        });

        cacheMap.put(key, frames);
        prefetchers.put(key, decoder);
        decoder.start();
    }

    private void stopAndFinalize(String key) {
        VideoTextureDecoder decoder = prefetchers.remove(key);
        if (decoder != null) {
            decoder.stop();
        }
        ProgressListener l = listeners.remove(key);
        if (l != null) l.onComplete();
    }

    public List<Bitmap> getCache(Uri uri) {
        return uri == null ? null : cacheMap.get(uri.toString());
    }

    public void clearAll() {
        for (VideoTextureDecoder d : prefetchers.values()) d.stop();
        prefetchers.clear();
        for (List<Bitmap> list : cacheMap.values()) {
            for (Bitmap b : list) b.recycle();
        }
        cacheMap.clear();
    }
}

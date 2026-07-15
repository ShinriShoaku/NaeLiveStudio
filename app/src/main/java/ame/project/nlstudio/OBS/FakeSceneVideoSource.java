package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.pedro.encoder.input.sources.video.VideoSource;

import java.util.concurrent.CopyOnWriteArrayList;

public class FakeSceneVideoSource extends VideoSource implements SceneCrossfadeSupport {

    private static final String TAG = "FakeSceneVideoSource";

    // Budget memori cache. ~250MB cukup buat klip pendek-menengah; disesuaikan lagi kalau perlu.
    private static final long CACHE_MEMORY_BUDGET_BYTES = 250L * 1024 * 1024;
    // Batas durasi cache dalam DETIK (dihitung terhadap fps asli dari encoder). Video lebih
    // panjang dari ini otomatis fallback ke live-streaming, bukan dipotong/di-cache sebagian.
    private static final int CACHE_MAX_DURATION_SECONDS = 20;

    public enum Mode { STATIC_IMAGE, VIDEO_FILE }

    private enum VideoPlaybackState { LOADING_CACHE, CACHED, LIVE_STREAM }

    private final Context context;
    private final Mode mode;
    private final Bitmap staticImage;
    private final Uri videoUri;

    private Surface targetSurface;
    private Thread drawThread;
    private volatile boolean running = false;
    // FPS asli dari encoder (lewat create()), dipakai buat pacing loop gambar.
    private volatile int targetFps = 30;

    // ---- Crossfade transisi scene ----
    // Durasi fade saat scene ini BARU MULAI (dari snapshot scene sebelumnya -> konten scene ini).
    private static final long FADE_DURATION_NS = 350_000_000L; // 350ms
    // Bitmap terakhir dari scene SEBELUMNYA, di-set oleh StreamService.switchScene() SEBELUM
    // start() dipanggil, supaya draw-loop bisa cross-dissolve dari frame lama ke frame baru
    // alih-alih langsung "cut" (yang kerasa patah/berat karena teardown+setup source lama&baru).
    private volatile Bitmap fadeFromSnapshot;
    private long fadeStartNs = -1L;

    // Buffer offscreen tempat kita compose 1 frame lengkap SEBELUM di-blit ke Surface encoder.
    // Ini titik yang juga dipakai untuk mengambil "snapshot frame terakhir" saat scene ini
    // akan diganti ke scene lain (lihat peekCurrentFrame()).
    private Bitmap frameBuffer;
    private final Object frameBufferLock = new Object();

    /** Dipanggil StreamService SEBELUM start(), untuk mewariskan frame terakhir scene sebelumnya
     *  supaya scene ini bisa fade-in dari situ. Aman dipanggil dengan null (tidak ada fade). */
    public void setFadeFromSnapshot(Bitmap snapshot) {
        this.fadeFromSnapshot = snapshot;
    }

    /** Ambil salinan frame yang lagi ditampilkan sekarang (dipakai StreamService sebagai bahan
     *  fade-out sebelum scene ini di-stop() & diganti scene lain). Bisa null kalau belum ada frame. */
    public Bitmap peekCurrentFrame() {
        synchronized (frameBufferLock) {
            if (frameBuffer == null || frameBuffer.isRecycled()) return null;
            return frameBuffer.copy(frameBuffer.getConfig(), false);
        }
    }

    // ---- State khusus mode VIDEO_FILE ----
    private volatile VideoPlaybackState videoPlaybackState = VideoPlaybackState.LOADING_CACHE;
    private final CopyOnWriteArrayList<Bitmap> cachedFrames = new CopyOnWriteArrayList<>();
    private VideoTextureDecoder prefetchDecoder; // decode sekali jalan buat isi cache (loop=false)
    private VideoTextureDecoder liveDecoder;     // fallback real-time (loop=true), dipakai kalau cache dibatalkan
    private volatile Bitmap latestLiveFrame;
    private final Object liveFrameLock = new Object();

    public FakeSceneVideoSource(Context context, Bitmap staticImage) {
        super();
        this.context = context;
        this.mode = Mode.STATIC_IMAGE;
        this.staticImage = staticImage;
        this.videoUri = null;
    }

    public FakeSceneVideoSource(Context context, Uri videoFileUri) {
        super();
        this.context = context;
        this.mode = Mode.VIDEO_FILE;
        this.staticImage = null;
        this.videoUri = videoFileUri;
    }

    public void setResolution(int width, int height) {
        setWidth(width);
        setHeight(height);
    }

    @Override
    protected boolean create(int width, int height, int fps, int rotation) {
        setWidth(width);
        setHeight(height);
        this.targetFps = fps > 0 ? fps : 30;
        return true;
    }

    @Override
    public void start(@NonNull SurfaceTexture surfaceTexture) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0) w = 720;
        if (h <= 0) h = 1280;

        surfaceTexture.setDefaultBufferSize(w, h);

        this.targetSurface = new Surface(surfaceTexture);
        running = true;

        if (mode == Mode.VIDEO_FILE) {
            startVideoPlayback(w, h);
        }
        startDrawLoop();
    }

    @Override
    public void stop() {
        running = false;
        // Cegah callback prefetch yang masih nyangkut nulis ke cache setelah stop() dipanggil.
        videoPlaybackState = VideoPlaybackState.LIVE_STREAM;

        if (drawThread != null) {
            try {
                drawThread.join(300);
            } catch (InterruptedException ignored) {}
            drawThread = null;
        }
        if (prefetchDecoder != null) {
            prefetchDecoder.stop();
            prefetchDecoder = null;
        }
        if (liveDecoder != null) {
            liveDecoder.stop();
            liveDecoder = null;
        }
        recycleCachedFrames();
        latestLiveFrame = null;

        synchronized (frameBufferLock) {
            if (frameBuffer != null && !frameBuffer.isRecycled()) frameBuffer.recycle();
            frameBuffer = null;
        }
        if (fadeFromSnapshot != null && !fadeFromSnapshot.isRecycled()) {
            fadeFromSnapshot.recycle();
        }
        fadeFromSnapshot = null;

        if (targetSurface != null) {
            targetSurface.release();
            targetSurface = null;
        }
    }

    @Override
    public void release() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void recycleCachedFrames() {
        for (Bitmap b : cachedFrames) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        cachedFrames.clear();
    }

    /** Mulai proses cache (decode sekali, non-loop). Kalau nabrak budget, otomatis fallback live. */
    private void startVideoPlayback(final int w, final int h) {
        videoPlaybackState = VideoPlaybackState.LOADING_CACHE;

        final int frameBytes = w * h * 2; // RGB_565 = 2 byte/pixel
        final int maxCacheFrames = Math.max(30, targetFps * CACHE_MAX_DURATION_SECONDS);

        prefetchDecoder = new VideoTextureDecoder(context, videoUri, w, h, false, new VideoTextureDecoder.Listener() {
            @Override
            public void onFrame(Bitmap bitmap) {
                if (videoPlaybackState != VideoPlaybackState.LOADING_CACHE) {
                    return; // sudah selesai/dibatalkan sebelumnya, abaikan frame nyasar
                }

                long projectedBytes = (long) (cachedFrames.size() + 1) * frameBytes;
                if (cachedFrames.size() >= maxCacheFrames || projectedBytes > CACHE_MEMORY_BUDGET_BYTES) {
                    Log.d(TAG, "Cache budget kelewat (frame=" + cachedFrames.size()
                            + ", bytes=" + projectedBytes + ") -> fallback ke live-streaming");
                    abandonCacheAndFallbackToLive(w, h);
                    return;
                }

                // Copy WAJIB: bitmap dari callback ini double-buffer milik decoder, isinya akan
                // ditimpa lagi di frame berikutnya. Sekalian convert ke RGB_565 buat hemat memori.
                Bitmap copy = bitmap.copy(Bitmap.Config.RGB_565, false);
                if (copy != null) {
                    cachedFrames.add(copy);
                }
            }

            @Override
            public void onComplete() {
                if (videoPlaybackState != VideoPlaybackState.LOADING_CACHE) {
                    return; // sudah keburu fallback ke live sebelum sempat onComplete
                }
                if (cachedFrames.isEmpty()) {
                    // Gagal dapat frame sama sekali - jaga-jaga fallback ke live.
                    abandonCacheAndFallbackToLive(w, h);
                    return;
                }
                videoPlaybackState = VideoPlaybackState.CACHED;
                Log.d(TAG, "Cache video selesai: " + cachedFrames.size() + " frame @" + w + "x" + h);
                stopDecoderAsync(prefetchDecoder);
                prefetchDecoder = null;
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Prefetch decoder error, fallback ke live-streaming", e);
                abandonCacheAndFallbackToLive(w, h);
            }
        });
        prefetchDecoder.start();
    }

    /** Buang cache parsial & pindah ke mode decode real-time terus-menerus (loop=true). */
    private synchronized void abandonCacheAndFallbackToLive(int w, int h) {
        if (videoPlaybackState == VideoPlaybackState.LIVE_STREAM) {
            return; // sudah fallback sebelumnya, jangan dobel start decoder
        }
        videoPlaybackState = VideoPlaybackState.LIVE_STREAM;

        recycleCachedFrames();
        stopDecoderAsync(prefetchDecoder);
        prefetchDecoder = null;

        liveDecoder = new VideoTextureDecoder(context, videoUri, w, h, true,
                bitmap -> {
                    synchronized (liveFrameLock) {
                        latestLiveFrame = bitmap;
                    }
                });
        liveDecoder.start();
    }

    /**
     * Panggil decoder.stop() di thread LAIN (bukan thread saat ini). Wajib dipakai kalau
     * pemanggilan terjadi dari DALAM callback milik decoder itu sendiri (onFrame/onComplete/
     * onError berjalan di GL-handler-thread internal VideoTextureDecoder) - decoder.stop() yang
     * asli itu blocking (post + wait ke thread yang sama), jadi kalau dipanggil langsung dari
     * thread-nya sendiri akan DEADLOCK.
     */
    private void stopDecoderAsync(final VideoTextureDecoder decoder) {
        if (decoder == null) return;
        new Thread(decoder::stop, "FakeSceneVideoSource-decoder-stop").start();
    }

    /**
     * Draw-loop tunggal buat semua mode. Pacing pakai jadwal absolut berbasis nanoTime + fps
     * asli dari encoder, bukan hardcode 33ms dengan sleep dihitung dari elapsed loop saat ini
     * saja (drift menumpuk seiring waktu). Lihat penjelasan sama di CompositeSceneVideoSource.
     */
    private void startDrawLoop() {
        drawThread = new Thread(() -> {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            long frameIntervalNs = 1_000_000_000L / Math.max(1, targetFps);
            long nextFrameTimeNs = System.nanoTime();
            int cacheFrameIndex = 0;
            fadeStartNs = fadeFromSnapshot != null ? System.nanoTime() : -1L;

            while (running && targetSurface != null && targetSurface.isValid()) {
                long now = System.nanoTime();
                if (now < nextFrameTimeNs) {
                    long sleepNs = nextFrameTimeNs - now;
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                Canvas surfaceCanvas = null;
                try {
                    surfaceCanvas = targetSurface.lockCanvas(null);
                    if (surfaceCanvas != null) {
                        int cW = surfaceCanvas.getWidth();
                        int cH = surfaceCanvas.getHeight();

                        synchronized (frameBufferLock) {
                            if (frameBuffer == null || frameBuffer.getWidth() != cW || frameBuffer.getHeight() != cH) {
                                if (frameBuffer != null && !frameBuffer.isRecycled()) frameBuffer.recycle();
                                frameBuffer = Bitmap.createBitmap(cW, cH, Bitmap.Config.ARGB_8888);
                            }
                            Canvas bufCanvas = new Canvas(frameBuffer);
                            bufCanvas.drawColor(Color.BLACK);

                            Bitmap frame = null;
                            if (mode == Mode.STATIC_IMAGE) {
                                frame = staticImage;
                            } else {
                                switch (videoPlaybackState) {
                                    case CACHED:
                                        if (!cachedFrames.isEmpty()) {
                                            frame = cachedFrames.get(cacheFrameIndex % cachedFrames.size());
                                            cacheFrameIndex++;
                                        }
                                        break;
                                    case LOADING_CACHE:
                                        // FIX: dulu freeze di frame pertama selama loading (kelihatan
                                        // "berhenti"/patah). Sekarang ikut jalan lewat frame yang SUDAH
                                        // ke-decode sejauh ini (di-clamp, tidak lompat ke frame yang
                                        // belum ada) - dipacing draw-loop ini sendiri (targetFps),
                                        // jadi kelihatan main normal, bukan freeze. Begitu
                                        // videoPlaybackState pindah ke CACHED, cacheFrameIndex sudah
                                        // nyambung di posisi yang sama - tidak ada lompatan/glitch.
                                        if (!cachedFrames.isEmpty()) {
                                            int loadIdx = Math.min(cacheFrameIndex, cachedFrames.size() - 1);
                                            frame = cachedFrames.get(loadIdx);
                                            if (cacheFrameIndex < cachedFrames.size() - 1) {
                                                cacheFrameIndex++;
                                            }
                                        }
                                        break;
                                    case LIVE_STREAM:
                                    default:
                                        synchronized (liveFrameLock) {
                                            frame = latestLiveFrame;
                                        }
                                        break;
                                }
                            }

                            if (frame != null && !frame.isRecycled()) {
                                Rect dst = fitCenterRect(frame.getWidth(), frame.getHeight(), cW, cH);
                                bufCanvas.drawBitmap(frame, null, dst, paint);
                            }

                            // Cross-dissolve: kalau baru saja transisi dari scene lain, blend
                            // snapshot scene lama di atas dengan alpha yang makin turun sampai
                            // FADE_DURATION_NS terlampaui, lalu snapshot dibuang (tidak dipakai lagi).
                            Bitmap fadeSnap = fadeFromSnapshot;
                            if (fadeSnap != null && !fadeSnap.isRecycled() && fadeStartNs >= 0) {
                                long elapsed = System.nanoTime() - fadeStartNs;
                                if (elapsed >= FADE_DURATION_NS) {
                                    fadeFromSnapshot = null;
                                    fadeSnap.recycle();
                                } else {
                                    float t = 1f - ((float) elapsed / FADE_DURATION_NS); // 1 -> 0
                                    fadePaint.setAlpha(Math.round(t * 255f));
                                    bufCanvas.drawBitmap(fadeSnap, null, new Rect(0, 0, cW, cH), fadePaint);
                                }
                            }

                            surfaceCanvas.drawBitmap(frameBuffer, 0, 0, null);
                        }
                    }
                } finally {
                    if (surfaceCanvas != null) {
                        targetSurface.unlockCanvasAndPost(surfaceCanvas);
                    }
                }

                nextFrameTimeNs += frameIntervalNs;
                long lagNs = System.nanoTime() - nextFrameTimeNs;
                if (lagNs > frameIntervalNs) {
                    nextFrameTimeNs = System.nanoTime();
                }
            }
        }, "FakeSceneVideoSource-draw");
        drawThread.start();
    }

    private Rect fitCenterRect(int srcW, int srcH, int dstW, int dstH) {
        float scale = Math.min((float) dstW / srcW, (float) dstH / srcH);
        int w = Math.round(srcW * scale);
        int h = Math.round(srcH * scale);
        int left = (dstW - w) / 2;
        int top = (dstH - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }
}
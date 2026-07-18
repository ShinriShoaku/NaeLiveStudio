package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.pedro.encoder.input.sources.video.VideoSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ame.project.nlstudio.scene.LayerType;
import ame.project.nlstudio.OBS.MusicBus;
import ame.project.nlstudio.OBS.TikTokChatBus;

/**
 * Video source ala OBS: gambar BACKGROUND lalu di atasnya digambar semua LAYER overlay.
 * Fix: Menggunakan ukuran Canvas asli untuk menggambar agar Full Scale dan menangani
 * aspek rasio screen capture agar tidak gepeng atau muncul bar hitam yang tidak diinginkan.
 */
public class CompositeSceneVideoSource extends VideoSource implements SceneCrossfadeSupport {

    private static final String TAG = "RES-COMP";

    // Budget memori cache. ~250MB cukup buat klip pendek-menengah; disesuaikan lagi kalau perlu.
    private static final long CACHE_MEMORY_BUDGET_BYTES = 250L * 1024 * 1024;
    // Batas durasi cache dalam DETIK (dihitung terhadap fps asli dari encoder). Video lebih
    // panjang dari ini otomatis fallback ke live-streaming, bukan dipotong/di-cache sebagian.
    private static final int CACHE_MAX_DURATION_SECONDS = 20;

    public enum BackgroundType { COLOR, IMAGE, VIDEO }

    private enum VideoPlaybackState { LOADING_CACHE, CACHED, LIVE_STREAM }

    public static class Layer {
        public final Bitmap bitmap;
        public final String uri;
        public final LayerType type;
        public final float x, y, w, h;
        public final int zIndex;
        public ame.project.nlstudio.scene.VoiceAnimConfig voiceAnimConfig;
        public java.util.Map<String, Bitmap> voiceAnimBitmaps;

        public Layer(Bitmap bitmap, String uri, LayerType type, float x, float y, float w, float h, int zIndex) {
            this.bitmap = bitmap;
            this.uri = uri;
            this.type = type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.zIndex = zIndex;
        }
    }

    private final Context context;
    private final BackgroundType backgroundType;
    private final Bitmap backgroundImage;
    private final Uri backgroundVideoUri;
    private final List<Layer> layers;

    // REMOVED internal screen capture for Android 14+ compatibility.
    // Screen capture is now managed globally by StreamService to avoid
    // recreating VirtualDisplay when switching scenes.

    private Surface targetSurface;
    private Thread drawThread;
    private VideoTextureDecoder videoBgDecoder;
    private VideoTextureDecoder prefetchDecoder;
    private volatile boolean running = false;
    private volatile Bitmap currentVideoBgFrame;
    private final Object videoBgLock = new Object();

    private volatile VideoPlaybackState videoPlaybackState = VideoPlaybackState.LIVE_STREAM;
    private final CopyOnWriteArrayList<Bitmap> cachedFrames = new CopyOnWriteArrayList<>();

    private int designWidth = 1080;
    private int designHeight = 1920;

    // FPS asli yang diminta encoder lewat create(). Dipakai buat pacing draw loop biar
    // gak hardcode 30fps - kalau user pilih 24/25/60fps di UI, draw loop ikut menyesuaikan.
    private volatile int targetFps = 30;
    private volatile float micLevel = 0.0f;

    // Resolusi ASLI yang diminta encoder lewat create(). Ini yang dipakai buat set ukuran
    // buffer SurfaceTexture, BUKAN designWidth/designHeight (yang cuma dipakai buat hitung
    // aspect ratio screen capture). Kalau tidak di-set, buffer SurfaceTexture akan tetap
    // pakai ukuran dari VideoSource sebelumnya (misal ScreenSource) -> composite kita jadi
    // di-scale kecil & di-tengah-in (letterbox) oleh GL renderer punya library.
    private int encoderWidth = 0;
    private int encoderHeight = 0;

    // ---- Crossfade transisi scene (sama seperti FakeSceneVideoSource, lihat komentar di sana) ----
    private static final long FADE_DURATION_NS = 350_000_000L; // 350ms
    private volatile Bitmap fadeFromSnapshot;
    private long fadeStartNs = -1L;
    private Bitmap frameBuffer;
    private final Object frameBufferLock = new Object();

    /** Dipanggil StreamService SEBELUM start(), mewariskan frame terakhir scene sebelumnya. */
    public void setFadeFromSnapshot(Bitmap snapshot) {
        this.fadeFromSnapshot = snapshot;
    }

    /** Salinan frame yang sedang tampil sekarang, dipakai sebagai bahan fade-out scene berikutnya. */
    public Bitmap peekCurrentFrame() {
        synchronized (frameBufferLock) {
            if (frameBuffer == null || frameBuffer.isRecycled()) return null;
            return frameBuffer.copy(frameBuffer.getConfig(), false);
        }
    }

    public CompositeSceneVideoSource(Context context, BackgroundType backgroundType,
                                     Bitmap backgroundImage, Uri backgroundVideoUri,
                                     List<Layer> layers, int width, int height) {
        super();
        this.context = context;
        this.backgroundType = backgroundType;
        this.backgroundImage = backgroundImage;
        this.backgroundVideoUri = backgroundVideoUri;
        this.layers = new CopyOnWriteArrayList<>(layers);
        this.designWidth = width;
        this.designHeight = height;

        // FIX: TIDAK PERLU REFLECTION LAGI.
        // Di source RootEncoder saat ini, VideoSource sudah jadi Kotlin class dengan
        // `var width` / `var height` PUBLIC (bukan private final seperti asumsi lama).
        // Backing field-nya tetap private di level bytecode, tapi Kotlin otomatis membuat
        // setter Java biasa (setWidth/setHeight) yang bisa langsung dipanggil dari subclass
        // Java ini - jadi cukup panggil langsung, tanpa reflection sama sekali.
        setWidth(width);
        setHeight(height);
        Log.d(TAG, "Constructor: Forced VideoSource resolution to " + width + "x" + height);
    }

    public void setMicLevel(float level) {
        this.micLevel = level;
    }

    @Override
    protected boolean create(int width, int height, int fps, int rotation) {
        this.encoderWidth = width;
        this.encoderHeight = height;
        this.targetFps = fps > 0 ? fps : 30;
        Log.d(TAG, "create(): encoder minta " + width + "x" + height + " fps=" + fps);
        return true;
    }

    @Override
    public void start(@NonNull SurfaceTexture surfaceTexture) {
        // FIX: paksa ukuran buffer SurfaceTexture sama persis dengan resolusi video yang
        // di-prepare di encoder. Tanpa ini, kalau sebelumnya source lain (misal ScreenSource)
        // pernah pakai resolusi/aspect ratio berbeda, buffer lama itu akan ke-carry over dan
        // di-scale+di-tengah-in (letterbox, muncul bar hitam) oleh GL renderer library -
        // inilah penyebab hasil live jadi kecil di tengah & keliatan landscape.
        //
        // CATATAN PENTING: sebelumnya kode ini PRIORITASKAN encoderWidth/Height (hasil create()).
        // Masalahnya, kalau changeVideoSource() dipanggil MID-STREAM (ganti scene sambil live),
        // RootEncoder tidak selalu memanggil ulang create() pada source yang baru - jadi
        // encoderWidth/Height bisa nyangkut di 0 (default) padahal kita sudah start(). Sekarang
        // designWidth/Height DIJAMIN benar oleh StreamService (selalu diisi dari savedWidth/Height,
        // yaitu resolusi asli hasil prepareVideo()), jadi designWidth/Height jadi sumber utama yang
        // dipercaya. encoderWidth/Height cuma dipakai kalau designWidth/Height belum pernah di-set
        // sama sekali (harusnya tidak pernah terjadi kalau caller benar).
        int w = designWidth > 0 ? designWidth : encoderWidth;
        int h = designHeight > 0 ? designHeight : encoderHeight;
        Log.d(TAG, "start(): encoderWidth/Height=" + encoderWidth + "x" + encoderHeight
                + " designWidth/Height=" + designWidth + "x" + designHeight
                + " -> DIPAKAI setDefaultBufferSize=" + w + "x" + h
                + " (sumber=" + (designWidth > 0 ? "DESIGN(=encoder aktual dari StreamService)" : "ENCODER") + ")");
        surfaceTexture.setDefaultBufferSize(w, h);

        this.targetSurface = new Surface(surfaceTexture);
        running = true;

        if (backgroundType == BackgroundType.VIDEO && backgroundVideoUri != null) {
            startVideoBackgroundLoop();
        }
        startCompositeDrawLoop();
    }

    @Override
    public void stop() {
        running = false;
        joinQuietly(drawThread);

        if (videoBgDecoder != null) {
            videoBgDecoder.stop();
            videoBgDecoder = null;
        }
        if (prefetchDecoder != null) {
            prefetchDecoder.stop();
            prefetchDecoder = null;
        }
        recycleCachedFrames();

        synchronized (videoBgLock) {
            currentVideoBgFrame = null;
        }

        drawThread = null;
        if (targetSurface != null) {
            targetSurface.release();
            targetSurface = null;
        }

        synchronized (frameBufferLock) {
            if (frameBuffer != null && !frameBuffer.isRecycled()) frameBuffer.recycle();
            frameBuffer = null;
        }
        if (fadeFromSnapshot != null && !fadeFromSnapshot.isRecycled()) {
            fadeFromSnapshot.recycle();
        }
        fadeFromSnapshot = null;
    }

    @Override
    public void release() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            // FIX ANR: Gunakan timeout yang lebih kecil (200ms) untuk join thread di UI thread.
            // Jika lewat batas, interrupt saja agar tidak memblokir UI (ANR).
            t.join(200);
            if (t.isAlive()) {
                t.interrupt();
            }
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * FIX SMOOTHNESS & RESOURCE: Sebelumnya background video didecode terus-menerus.
     * Sekarang kita coba cache ke RAM (RGB_565) kalau durasinya pendek (<20s) biar CPU/GPU
     * lebih santai. Kalau kepanjangan atau RAM gak cukup, otomatis balik ke live-streaming.
     */
    private void startVideoBackgroundLoop() {
        if (backgroundVideoUri == null) return;

        // Cek apakah sudah ada di Global Cache
        List<Bitmap> globalCache = VideoCacheManager.getInstance().getCache(backgroundVideoUri);
        if (globalCache != null && !globalCache.isEmpty()) {
            Log.d(TAG, "Menggunakan Global Cache untuk BG video: " + globalCache.size() + " frames");
            cachedFrames.addAll(globalCache);
            videoPlaybackState = VideoPlaybackState.CACHED;
            return;
        }

        videoPlaybackState = VideoPlaybackState.LOADING_CACHE;
        final int w = designWidth;
        final int h = designHeight;
        final int frameBytes = w * h * 2; // RGB_565
        final int maxCacheFrames = Math.max(30, targetFps * CACHE_MAX_DURATION_SECONDS);

        prefetchDecoder = new VideoTextureDecoder(context, backgroundVideoUri, w, h, false, new VideoTextureDecoder.Listener() {
            @Override
            public void onFrame(Bitmap bitmap) {
                if (videoPlaybackState != VideoPlaybackState.LOADING_CACHE) return;

                long projectedBytes = (long) (cachedFrames.size() + 1) * frameBytes;
                if (cachedFrames.size() >= maxCacheFrames || projectedBytes > CACHE_MEMORY_BUDGET_BYTES) {
                    Log.d(TAG, "BG Cache budget full (" + cachedFrames.size() + " frames) -> fallback live");
                    abandonCacheAndFallbackToLive();
                    return;
                }

                Bitmap copy = bitmap.copy(Bitmap.Config.RGB_565, false);
                if (copy != null) cachedFrames.add(copy);
            }

            @Override
            public void onComplete() {
                if (videoPlaybackState != VideoPlaybackState.LOADING_CACHE) return;
                if (cachedFrames.isEmpty()) {
                    abandonCacheAndFallbackToLive();
                    return;
                }
                videoPlaybackState = VideoPlaybackState.CACHED;
                Log.d(TAG, "BG Cache selesai: " + cachedFrames.size() + " frames");
                if (prefetchDecoder != null) {
                    prefetchDecoder.stop();
                    prefetchDecoder = null;
                }
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "BG Cache error, fallback live", e);
                abandonCacheAndFallbackToLive();
            }
        });
        prefetchDecoder.start();
    }

    private void abandonCacheAndFallbackToLive() {
        videoPlaybackState = VideoPlaybackState.LIVE_STREAM;
        recycleCachedFrames();
        if (prefetchDecoder != null) {
            prefetchDecoder.stop();
            prefetchDecoder = null;
        }

        videoBgDecoder = new VideoTextureDecoder(context, backgroundVideoUri, designWidth, designHeight,
                bitmap -> {
                    synchronized (videoBgLock) {
                        currentVideoBgFrame = bitmap;
                    }
                });
        videoBgDecoder.start();
    }

    private void recycleCachedFrames() {
        // JANGAN recycle jika itu milik VideoCacheManager (global)
        List<Bitmap> globalCache = VideoCacheManager.getInstance().getCache(backgroundVideoUri);
        if (globalCache != null && !globalCache.isEmpty()) {
            cachedFrames.clear();
            return;
        }

        for (Bitmap b : cachedFrames) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        cachedFrames.clear();
    }

    private void startCompositeDrawLoop() {
        drawThread = new Thread(() -> {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            long lastLog = 0L;

            long frameIntervalNs = 1_000_000_000L / Math.max(1, targetFps);
            long nextFrameTimeNs = System.nanoTime();
            fadeStartNs = fadeFromSnapshot != null ? System.nanoTime() : -1L;

            while (running) {
                Surface surface = targetSurface;
                if (surface == null || !surface.isValid()) {
                    if (running) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            break;
                        }
                        continue;
                    }
                    break;
                }

                long now = System.nanoTime();
                if (now < nextFrameTimeNs) {
                    long sleepNs = nextFrameTimeNs - now;
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                // Cek lagi setelah sleep
                if (!running || surface != targetSurface || !surface.isValid()) continue;

                Canvas surfaceCanvas = null;
                try {
                    surfaceCanvas = surface.lockCanvas(null);
                    if (surfaceCanvas != null) {
                        int cW = surfaceCanvas.getWidth();
                        int cH = surfaceCanvas.getHeight();

                        synchronized (frameBufferLock) {
                            if (frameBuffer == null || frameBuffer.getWidth() != cW || frameBuffer.getHeight() != cH) {
                                if (frameBuffer != null && !frameBuffer.isRecycled()) frameBuffer.recycle();
                                frameBuffer = Bitmap.createBitmap(cW, cH, Bitmap.Config.ARGB_8888);
                            }
                            Canvas bufCanvas = new Canvas(frameBuffer);
                            bufCanvas.save();
                            bufCanvas.scale((float) cW / designWidth, (float) cH / designHeight);

                            // FIX: Gunakan satu lock untuk seluruh proses drawing agar tidak crash saat stop()
                            synchronized (videoBgLock) {
                                drawBackground(bufCanvas, paint, designWidth, designHeight);
                                drawLayers(bufCanvas, paint, designWidth, designHeight);
                            }

                            bufCanvas.restore();

                            // Cross-dissolve dari snapshot scene sebelumnya (lihat FakeSceneVideoSource
                            // untuk penjelasan lengkap kenapa ini dilakukan di titik ini).
                            Bitmap fadeSnap = fadeFromSnapshot;
                            if (fadeSnap != null && !fadeSnap.isRecycled() && fadeStartNs >= 0) {
                                long elapsed = System.nanoTime() - fadeStartNs;
                                if (elapsed >= FADE_DURATION_NS) {
                                    fadeFromSnapshot = null;
                                    fadeSnap.recycle();
                                } else {
                                    float t = 1f - ((float) elapsed / FADE_DURATION_NS);
                                    fadePaint.setAlpha(Math.round(t * 255f));
                                    bufCanvas.drawBitmap(fadeSnap, null, new Rect(0, 0, cW, cH), fadePaint);
                                }
                            }

                            surfaceCanvas.drawBitmap(frameBuffer, 0, 0, null);
                        }

                        long logNow = System.currentTimeMillis();
                        if (logNow - lastLog > 5000) {
                            lastLog = logNow;
                            Log.d(TAG, "drawLoop: Rendered " + designWidth + "x" + designHeight +
                                    " into Canvas " + cW + "x" + cH + " @" + targetFps + "fps");
                        }
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    Log.w(TAG, "drawLoop: surface error (already locked or released), skipping frame");
                } catch (Exception e) {
                    Log.e(TAG, "drawLoop: unexpected error during draw", e);
                } finally {
                    if (surfaceCanvas != null) {
                        try {
                            surface.unlockCanvasAndPost(surfaceCanvas);
                        } catch (Exception e) {
                            Log.w(TAG, "drawLoop: error unlocking canvas", e);
                        }
                    }
                }

                nextFrameTimeNs += frameIntervalNs;
                long lagNs = System.nanoTime() - nextFrameTimeNs;
                if (lagNs > frameIntervalNs) {
                    nextFrameTimeNs = System.nanoTime();
                }
            }
            Log.d(TAG, "drawLoop finished");
        }, "CompositeScene-draw");
        drawThread.start();
    }

    /** Menghitung Rect tujuan agar gambar fit di tengah tanpa distorsi (Letterbox/Pillarbox jika perlu). */
    private Rect calculateFitRect(int srcW, int srcH, int dstW, int dstH) {
        float ratio = Math.min((float) dstW / srcW, (float) dstH / srcH);
        int w = Math.round(srcW * ratio);
        int h = Math.round(srcH * ratio);
        int left = (dstW - w) / 2;
        int top = (dstH - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }

    // Index frame video background, dikelola di sini (bukan lagi parameter dari draw-loop) supaya
    // bisa lanjut mulus dari LOADING_CACHE -> CACHED tanpa lompatan (lihat drawBackground()).
    private int bgFrameIndex = 0;

    private void drawBackground(Canvas canvas, Paint paint, int cW, int cH) {
        canvas.drawColor(Color.BLACK);
        Bitmap bg = null;
        synchronized (videoBgLock) {
            if (backgroundType == BackgroundType.IMAGE) {
                bg = backgroundImage;
            } else if (backgroundType == BackgroundType.VIDEO) {
                if (videoPlaybackState == VideoPlaybackState.CACHED && !cachedFrames.isEmpty()) {
                    bg = cachedFrames.get(bgFrameIndex % cachedFrames.size());
                    bgFrameIndex++;
                } else if (videoPlaybackState == VideoPlaybackState.LOADING_CACHE && !cachedFrames.isEmpty()) {
                    // FIX: dulu freeze di frame pertama selama loading (kelihatan "berhenti").
                    // Sekarang ikut jalan lewat frame yang SUDAH ke-decode sejauh ini (di-clamp,
                    // tidak lompat ke frame yang belum ada) - dipacing draw-loop ini sendiri
                    // (targetFps), jadi kelihatan main normal, bukan freeze. Begitu
                    // videoPlaybackState pindah ke CACHED, bgFrameIndex sudah nyambung di posisi
                    // yang sama - tidak ada lompatan/glitch saat transisi.
                    int loadIdx = Math.min(bgFrameIndex, cachedFrames.size() - 1);
                    bg = cachedFrames.get(loadIdx);
                    if (bgFrameIndex < cachedFrames.size() - 1) {
                        bgFrameIndex++;
                    }
                } else {
                    bg = currentVideoBgFrame;
                }
            }

            if (bg != null && !bg.isRecycled()) {
                Rect dst = fillRect(bg.getWidth(), bg.getHeight(), cW, cH);
                canvas.drawBitmap(bg, null, dst, paint);
            }
        }
    }

    private void drawLayers(Canvas canvas, Paint paint, int cW, int cH) {
        for (Layer layer : layers) {
            Bitmap bmp = layer.bitmap;
            boolean isScreen = layer.type == LayerType.SCREEN;
            boolean isVoiceAnim = layer.type == LayerType.VOICE_ANIM;
            boolean isTikTokChat = layer.type == LayerType.TIKTOK_CHAT;
            boolean isTikTokGift = layer.type == LayerType.TIKTOK_GIFT;
            boolean isTikTokJoin = layer.type == LayerType.TIKTOK_JOIN;
            boolean isMusicCurrent = layer.type == LayerType.MUSIC_CURRENT;
            boolean isMusicQueue = layer.type == LayerType.MUSIC_QUEUE;

            RectF dst = new RectF(
                    layer.x * cW,
                    layer.y * cH,
                    (layer.x + layer.w) * cW,
                    (layer.y + layer.h) * cH
            );

            // FIX: dulu ketiga tipe ini jatuh ke default di bawah dan cuma gambar bitmap
            // placeholder statis ("CHAT"/"GIFT"/"JOIN") yang dibuat SEKALI di StreamService saat
            // scene JSON di-parse - tidak pernah update walau ada chat/gift/join baru masuk lewat
            // IKanaeCallback. Sekarang di-render ULANG TIAP FRAME dari data live yang ditampung
            // TikTokChatBus (diisi StreamService lewat binding ke IKanaeService), mirip pola
            // layer SCREEN yang ambil globalScreenFrame tiap frame.
            if (isTikTokChat || isTikTokGift || isTikTokJoin || isMusicCurrent || isMusicQueue) {
                int lw = Math.max(1, Math.round(dst.width()));
                int lh = Math.max(1, Math.round(dst.height()));
                if (isTikTokChat) {
                    bmp = TikTokChatBus.getInstance().renderChatOverlay(context, lw, lh);
                } else if (isTikTokGift) {
                    bmp = TikTokChatBus.getInstance().renderGiftOverlay(context, lw, lh);
                } else if (isTikTokJoin) {
                    bmp = TikTokChatBus.getInstance().renderJoinOverlay(context, lw, lh);
                } else if (isMusicCurrent) {
                    bmp = MusicBus.getInstance().renderCurrentSong(context, lw, lh);
                } else if (isMusicQueue) {
                    bmp = MusicBus.getInstance().renderQueue(context, lw, lh);
                }
                if (bmp != null && !bmp.isRecycled()) {
                    canvas.drawBitmap(bmp, null, dst, paint);
                }
                continue;
            }

            if (isScreen) {
                StreamService svc = StreamService.getInstance();
                if (svc != null) {
                    synchronized (svc.getGlobalScreenLock()) {
                        bmp = svc.getGlobalScreenFrame();
                        if (bmp != null && !bmp.isRecycled()) {
                            int sCapW = svc.getGlobalCapW();
                            int sCapH = svc.getGlobalCapH();
                            Rect srcRect = new Rect(0, 0, sCapW, sCapH);
                            RectF cropDst = fillRectF(sCapW, sCapH, dst);
                            canvas.save();
                            canvas.clipRect(dst);
                            canvas.drawBitmap(bmp, srcRect, cropDst, paint);
                            canvas.restore();
                        }
                    }
                }
                continue;
            }

            if (isVoiceAnim && layer.voiceAnimConfig != null && layer.voiceAnimBitmaps != null) {
                float level = this.micLevel;
                ame.project.nlstudio.scene.VoiceAnimItem selectedItem = null;
                float maxThreshold = -1f;
                for (ame.project.nlstudio.scene.VoiceAnimItem item : layer.voiceAnimConfig.getItems()) {
                    if (level >= item.getThreshold() && item.getThreshold() > maxThreshold) {
                        if (layer.voiceAnimBitmaps.containsKey(item.getImageUri())) {
                            selectedItem = item;
                            maxThreshold = item.getThreshold();
                        }
                    }
                }
                if (selectedItem != null) {
                    bmp = layer.voiceAnimBitmaps.get(selectedItem.getImageUri());
                }

                // Brightness & Scale effect using dynamic config
                float minB = layer.voiceAnimConfig.getMinBrightness();
                float sInt = layer.voiceAnimConfig.getScaleIntensity();
                float tStart = layer.voiceAnimConfig.getEffectThresholdStart();
                float tEnd = layer.voiceAnimConfig.getEffectThresholdEnd();

                float range = Math.max(0.01f, tEnd - tStart);
                float normalizedLevel = Math.max(0.0f, Math.min(1.0f, (level - tStart) / range));

                float brightness = minB + (normalizedLevel * (1.0f - minB));
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setScale(brightness, brightness, brightness, 1f);
                paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));

                float baseScale = 1.0f - (sInt / 2.0f);
                float scale = baseScale + (normalizedLevel * sInt);
                float centerX = dst.centerX();
                float centerY = dst.centerY();
                float newW = dst.width() * scale;
                float newH = dst.height() * scale;
                dst.set(centerX - newW / 2f, centerY - newH / 2f, centerX + newW / 2f, centerY + newH / 2f);
            }

            if (bmp != null && !bmp.isRecycled()) {
                canvas.drawBitmap(bmp, null, dst, paint);
            }

            // Reset filter for next layers
            paint.setColorFilter(null);
        }
    }

    /** Center Crop logic for layer: memotong sisi agar gambar memenuhi area tujuan tanpa distorsi. */
    private RectF fillRectF(int srcW, int srcH, RectF dst) {
        float scale = Math.max(dst.width() / srcW, dst.height() / srcH);
        float w = srcW * scale;
        float h = srcH * scale;
        float left = dst.left + (dst.width() - w) / 2;
        float top = dst.top + (dst.height() - h) / 2;
        return new RectF(left, top, left + w, top + h);
    }

    private Rect fillRect(int srcW, int srcH, int dstW, int dstH) {
        float scale = Math.max((float) dstW / srcW, (float) dstH / srcH);
        int w = Math.round(srcW * scale);
        int h = Math.round(srcH * scale);
        int left = (dstW - w) / 2;
        int top = (dstH - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }
}
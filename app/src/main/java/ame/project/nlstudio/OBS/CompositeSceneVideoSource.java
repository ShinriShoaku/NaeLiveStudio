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
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.pedro.encoder.input.sources.video.VideoSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ame.project.nlstudio.scene.LayerType;
import ame.project.nlstudio.scene.AnimationEffect;

/**
 * GPU Optimized Composite Scene Video Source.
 * Renders background video via OES texture and overlays via RGBA texture.
 */
public class CompositeSceneVideoSource extends VideoSource implements SceneCrossfadeSupport {

    private static final String TAG = "Composite-GPU";

    public static class Layer {
        public final Bitmap bitmap;
        public final String uri;
        public final LayerType type;
        public final float x, y, w, h;
        public final int zIndex;
        public ame.project.nlstudio.scene.VoiceAnimConfig voiceAnimConfig;
        public java.util.Map<String, Bitmap> voiceAnimBitmaps;
        public ParticleSystem particleSystem;

        public final RectF reusableDst = new RectF();
        public android.graphics.ColorMatrix reusableColorMatrix;
        public android.graphics.ColorMatrixColorFilter reusableColorFilter;
        // OPTIMASI: brightness terakhir yang dipakai buat reusableColorFilter, supaya
        // ColorMatrixColorFilter tidak di-`new` ulang tiap frame (30x/detik) kalau mic level
        // sedang stabil/diam - ColorMatrixColorFilter di Android immutable begitu dibuat,
        // jadi cuma perlu re-create kalau brightness-nya benar2 berubah signifikan.
        public float lastAppliedBrightness = Float.NaN;

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

    // FIX BUG: background IMAGE dulu digambar dengan src=null (drawBitmap(bmp, null, dstFullCanvas))
    // yang artinya seluruh bitmap sumber dipaksa masuk ke dst apa adanya -> kalau aspect ratio
    // gambar beda dari aspect ratio resolusi custom (designWidth x designHeight), hasilnya
    // stretch/gepeng, bukan center-crop kayak background OBS pada umumnya. Rect ini dihitung
    // SEKALI di constructor (backgroundImage & designWidth/Height sudah final utk objek ini) berupa
    // area di DALAM bitmap sumber yang di-crop tengah supaya aspect ratio-nya pas dengan target,
    // baru itu yang digambar full ke dst (lihat drawAllLayers()).
    private final Rect backgroundImageCropRect;

    public enum BackgroundType { COLOR, IMAGE, VIDEO, SCREEN }

    private Surface targetSurface;
    private Thread drawThread;
    private VideoTextureDecoder videoBgDecoder;
    private final java.util.Map<String, VideoTextureDecoder> videoLayerDecoders = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean running = false;

    private int designWidth = 1080;
    private int designHeight = 1920;
    private volatile int targetFps = 30;

    private volatile float micLevel = 0.0f;

    // GL Resources
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int oesProgram, rgbaProgram;
    private int overlayTextureId = 0;
    // FIX PERFORMA BESAR: background BackgroundType.SCREEN (rekam layar) dulu digambar lewat
    // Canvas 2D (software, CPU) - lihat catatan panjang di computeRequiresDynamicOverlayRedraw()
    // dan drawAllLayers(). Sekarang screen capture punya texture GL sendiri, di-upload &
    // digambar langsung di GPU (persis seperti video background), BUKAN lewat overlayBuffer lagi.
    private int screenTextureId = 0;
    private final java.util.Map<VideoTextureDecoder, Integer> decoderTextures = new java.util.HashMap<>();
    private Bitmap overlayBuffer;
    private Canvas overlayCanvas;
    private final Object overlayLock = new Object();

    // ==== OPTIMASI: skip re-render + re-upload overlay texture kalau scene statis ====
    // Sebelumnya overlayBuffer (bitmap se-resolusi design, misal 1080x1920) di-erase, digambar
    // ulang total, lalu di-upload ke GPU lewat GLUtils.texImage2D di SETIAP frame (30x/detik),
    // walaupun kontennya tidak berubah sama sekali (misal cuma 1 layer gambar statis). Upload
    // CPU->GPU sebesar itu tiap frame mahal, apalagi di device low-end.
    //
    // `layers` (dan backgroundType/backgroundImage) sudah final utk sepanjang umur objek ini -
    // tiap ganti scene, StreamService bikin CompositeSceneVideoSource baru (lihat
    // StreamService.applyCompositeScene), bukan mutasi layer list yang sudah jalan. Jadi aman
    // dihitung SEKALI di constructor:
    //  - overlayHasAnyContent : apa ada sesuatu utk digambar ke overlay sama sekali.
    //  - requiresDynamicOverlayRedraw : apa isi overlay Canvas bisa berubah antar-frame (chat/
    //    gift/join TikTok, lagu berjalan, partikel efek, voice-anim yang bereaksi ke mic, atau
    //    layer PiP screen-share). Background SCREEN (rekam layar penuh) TIDAK dihitung di sini lagi
    //    karena sekarang digambar langsung sbg texture GPU terpisah (lihat runDrawLoop()), bukan
    //    lewat overlayBuffer. Kalau TIDAK ada satupun tipe layer dinamis ini, overlay-nya statis ->
    //    cukup di-render & di-upload SEKALI saja pas frame pertama, frame selanjutnya tinggal
    //    gambar ulang texture yang sudah ada di GPU (murah, tanpa sentuh Canvas/CPU dan tanpa
    //    texImage2D lagi).
    private final boolean overlayHasAnyContent;
    private final boolean requiresDynamicOverlayRedraw;
    private volatile boolean overlayNeedsInitialRender = true;

    private static boolean computeRequiresDynamicOverlayRedraw(BackgroundType backgroundType, List<Layer> layers) {
        // CATATAN: BackgroundType.SCREEN (rekam layar sbg background) TIDAK lagi bikin fungsi ini
        // return true - screen capture sekarang digambar langsung sbg texture GPU terpisah di
        // runDrawLoop() (selalu di-refresh tiap frame di jalur itu sendiri, terlepas dari flag
        // ini), bukan lewat overlayBuffer/Canvas lagi. Jadi kalau scene screen-record TIDAK
        // punya layer dinamis lain (chat/gift/dst di bawah), overlay Canvas-nya sungguhan statis
        // dan boleh di-skip render+upload-nya tiap frame.
        for (Layer l : layers) {
            switch (l.type) {
                case EFFECT:        // partikel beranimasi tiap frame
                case TIKTOK_CHAT:
                case TIKTOK_GIFT:
                case TIKTOK_JOIN:
                case MUSIC_CURRENT:
                case MUSIC_QUEUE:
                case SCREEN:        // layer screen-share sbg elemen overlay (mis. PiP kecil), beda dari BackgroundType.SCREEN di atas - ini masih lewat Canvas jadi tetap dianggap dinamis
                case VOICE_ANIM:    // bereaksi ke level mic yang berubah tiap saat
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    // FIX "posisi kebalik" pas scene Layar HP (SCREEN): frame mentah dari StreamService berasal
    // langsung dari ImageReader (VirtualDisplay hasil MediaProjection, lihat
    // StreamService.updateGlobalScreenBitmap()), di-copy row-major APA ADANYA lewat
    // copyPixelsFromBuffer() tanpa pemrosesan orientasi. Di beberapa GPU/driver (terutama PowerVR,
    // seperti yang kepakai di device ini - lihat log PVRSRVBridgeCall/IMGEGLImage) buffer yang
    // dikirim balik dari VirtualDisplay dgn VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR baris pixelnya
    // terbalik (bottom-up), sedangkan Bitmap Android normalnya top-down - hasilnya SCREEN layer
    // tampil vertikal terbalik (atas<->bawah ketukar), PERSIS seperti yang dilaporkan. Semua
    // background/layer LAIN (video/image) tidak kena karena tidak lewat jalur ImageReader ini.
    // Di-flip di sini (saat digambar ke Canvas overlay) karena jauh lebih murah drpd flip per-pixel
    // di CPU tiap frame di StreamService. Kalau ternyata di device tertentu malah jadi kebalik pas
    // di-flip (artinya device itu memang tidak butuh koreksi), tinggal set false.
    private static final boolean FLIP_SCREEN_CAPTURE_VERTICALLY = true;

    private FloatBuffer vertexBuffer, texCoordBuffer;
    private FloatBuffer screenVertexBuffer; // Buffer khusus untuk Fit-Center screen
    private final float[] identityMatrix = new float[16];
    private final float[] flipMatrix = new float[16];
    {
        android.opengl.Matrix.setIdentityM(identityMatrix, 0);
        android.opengl.Matrix.setIdentityM(flipMatrix, 0);
        // FIX: Y-axis flip untuk output Canvas. Android Bitmap/Canvas (0,0) di top-left,
        // sedangkan OpenGL (0,0) di bottom-left. Tanpa flip, hasil render Canvas ke GL
        // akan terbalik vertikal (atas-bawah ketukar).
        android.opengl.Matrix.scaleM(flipMatrix, 0, 1f, -1f, 1f);
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
        this.overlayHasAnyContent = (backgroundType == BackgroundType.IMAGE && backgroundImage != null)
                || !this.layers.isEmpty();
        this.requiresDynamicOverlayRedraw = computeRequiresDynamicOverlayRedraw(backgroundType, this.layers);
        this.backgroundImageCropRect = (backgroundType == BackgroundType.IMAGE && backgroundImage != null)
                ? computeCenterCropSrcRect(backgroundImage.getWidth(), backgroundImage.getHeight(), width, height)
                : null;
        setWidth(width);
        setHeight(height);
    }

    /** Hitung area crop-tengah (mirip ScaleType.CENTER_CROP) di dalam bitmap sumber berukuran
     *  srcW x srcH, supaya kalau digambar full ke area dstW x dstH, aspect ratio-nya pas tanpa
     *  gepeng/stretch dan tanpa letterbox - kelebihan sisi yang tidak muat dipotong simetris. */
    private static Rect computeCenterCropSrcRect(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return new Rect(0, 0, srcW, srcH);
        float srcAspect = (float) srcW / srcH;
        float dstAspect = (float) dstW / dstH;
        int cropW, cropH, left, top;
        if (srcAspect > dstAspect) {
            // Sumber lebih "lebar" dari target -> crop kiri-kanan, tinggi dipakai penuh.
            cropH = srcH;
            cropW = Math.max(1, Math.round(srcH * dstAspect));
            left = (srcW - cropW) / 2;
            top = 0;
        } else {
            // Sumber lebih "tinggi"/kotak dari target -> crop atas-bawah, lebar dipakai penuh.
            cropW = srcW;
            cropH = Math.max(1, Math.round(srcW / dstAspect));
            left = 0;
            top = (srcH - cropH) / 2;
        }
        return new Rect(left, top, left + cropW, top + cropH);
    }

    public void setMicLevel(float level) { this.micLevel = level; }

    @Override
    protected boolean create(int width, int height, int fps, int rotation) {
        this.targetFps = fps > 0 ? fps : 30;
        return true;
    }

    @Override
    public void start(@NonNull SurfaceTexture surfaceTexture) {
        surfaceTexture.setDefaultBufferSize(designWidth, designHeight);
        this.targetSurface = new Surface(surfaceTexture);
        running = true;

        if (backgroundType == BackgroundType.VIDEO && backgroundVideoUri != null) {
            // Efficiency: Background video capped at 720p max to save GPU/CPU decoding power.
            int bgW = Math.min(designWidth, 1280);
            int bgH = Math.min(designHeight, 720);
            videoBgDecoder = VideoCacheManager.getInstance().acquire(context, backgroundVideoUri, bgW, bgH, targetFps);
            videoBgDecoder.setPlayWhenReady(true);
        }

        // Pre-initialize Video Layers (Removed as per user request to use only background video)

        startDrawLoop();
    }

    @Override
    public void stop() {
        running = false;
        if (drawThread != null) {
            try { drawThread.join(1000); } catch (InterruptedException ignored) {}
            drawThread = null;
        }
        if (backgroundVideoUri != null) {
            VideoCacheManager.getInstance().release(backgroundVideoUri);
        }
        videoBgDecoder = null;

        for (String uriStr : videoLayerDecoders.keySet()) {
            VideoCacheManager.getInstance().release(Uri.parse(uriStr));
        }
        videoLayerDecoders.clear();

        // FIX "Handler on a dead thread": JANGAN langsung release targetSurface di sini.
        // targetSurface membungkus SurfaceTexture milik Pedro's library. Jika di-release
        // sebelum library selesai melakukan cleanup (misal black frame / tryClear),
        // internal Handler di SurfaceTexture (yg dibuat di thread GL sebelumnya) akan
        // komplain saat diakses. Biarkan GC yang membersihkan Surface wrapper ini,
        // atau biarkan library Pedro yang mengelola siklus hidup SurfaceTexture-nya.
        targetSurface = null;

        synchronized (overlayLock) {
            if (overlayBuffer != null) { overlayBuffer.recycle(); overlayBuffer = null; }
        }
        overlayNeedsInitialRender = true;
    }

    @Override
    public void release() { stop(); }

    @Override
    public boolean isRunning() { return running; }

    private void startDrawLoop() {
        drawThread = new Thread(this::runDrawLoop, "Composite-GPU-Thread");
        drawThread.start();
    }

    private void runDrawLoop() {
        initGl();

        long frameIntervalNs = 1_000_000_000L / targetFps;
        long nextFrameTimeNs = System.nanoTime();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        while (running) {
            long now = System.nanoTime();
            if (now < nextFrameTimeNs) {
                try { Thread.sleep((nextFrameTimeNs - now) / 1_000_000L); } catch (InterruptedException e) { break; }
            }

            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 1. Draw Background Video (Lowest Z-Index)
            if (backgroundType == BackgroundType.VIDEO && videoBgDecoder != null) {
                int texId = getDecoderTexture(videoBgDecoder);
                videoBgDecoder.updateTexImage();
                drawTexture(oesProgram, texId, videoBgDecoder.getTexMatrix(), true);
            } else if (backgroundType == BackgroundType.SCREEN) {
                // FIX PERFORMA: dulu screen-capture di-composite lewat Canvas 2D (CPU) ke dalam
                // overlayBuffer bareng semua layer lain, lalu SELURUH overlayBuffer di-upload
                // sebagai 1 texture RGBA - itu artinya tiap frame ada 2 operasi CPU berat:
                // (a) canvas.drawBitmap() men-scale bitmap screen capture secara software ke
                //     ukuran designWidth x designHeight (resample CPU, mahal utk bitmap besar),
                // (b) GLUtils.texImage2D() upload ULANG seluruh bitmap besar itu tiap frame.
                // Ini yang bikin frame rate aktual jatuh jauh di bawah target (misal target 30fps
                // tapi cuma dapat ~5fps) - loop render jadi lebih lambat dari frameIntervalNs
                // karena kerja CPU per frame kelamaan, padahal encoder tetap nunggu di target 30fps
                // sehingga keyframe interval yang harusnya 2 detik jadi molor berkali-kali lipat.
                //
                // Sekarang screen-capture bitmap di-upload ke texture GL SENDIRI (tanpa lewat
                // Canvas sama sekali) dan di-scale ke layar penuh oleh GPU lewat quad/shader biasa
                // (drawTexture) - jauh lebih murah daripada resample software. overlayBuffer
                // sekarang cuma isi layer TAMBAHAN (chat/gambar/dll), bukan screen capture-nya.
                StreamService svc = StreamService.getInstance();
                if (svc != null) {
                    synchronized (svc.getGlobalScreenLock()) {
                        Bitmap bg = svc.getGlobalScreenFrame();
                        if (bg != null && !bg.isRecycled()) {
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTextureId);
                            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bg, 0);

                            // FIX STRETCH: Hitung vertices agar fit-center
                            updateScreenVertices(bg.getWidth(), bg.getHeight(), designWidth, designHeight);
                            drawTextureWithBuffer(rgbaProgram, screenTextureId, flipMatrix, false, screenVertexBuffer);
                        }
                    }
                }
            }

            // 2. Draw Other Backgrounds and Layers in Z-order
            if (overlayHasAnyContent) {
                if (requiresDynamicOverlayRedraw || overlayNeedsInitialRender) {
                    drawAllLayers(paint);
                    overlayNeedsInitialRender = false;
                } else {
                    // OPTIMASI: scene ini statis (tidak ada layer chat/gift/join/music/effect/
                    // voice-anim/screen) - texture overlay yang sudah di-upload di frame pertama
                    // masih valid, jadi tinggal digambar ulang tanpa sentuh Canvas CPU maupun
                    // texImage2D lagi.
                    redrawCachedOverlayTexture();
                }
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface);

            nextFrameTimeNs += frameIntervalNs;
            if (System.nanoTime() - nextFrameTimeNs > frameIntervalNs) nextFrameTimeNs = System.nanoTime();
        }
        releaseGl();
    }

    /** Gambar 1 bitmap frame screen-capture ke Canvas dengan Fit-Center (jaga aspect ratio).
     *  Orientasi sudah ditangani secara global lewat flipMatrix saat render Canvas ke GL. */
    private static void drawScreenFrame(Canvas canvas, Bitmap frame, RectF dst, Paint paint) {
        if (frame == null || frame.isRecycled()) return;
        
        float srcAspect = (float) frame.getWidth() / frame.getHeight();
        float dstAspect = dst.width() / dst.height();
        
        RectF finalDst = new RectF(dst);
        if (srcAspect > dstAspect) {
            // Bitmap lebih lebar -> paskan lebar, tinggi dikurangi (bar hitam atas-bawah)
            float newHeight = dst.width() / srcAspect;
            float dy = (dst.height() - newHeight) / 2f;
            finalDst.set(dst.left, dst.top + dy, dst.right, dst.bottom - dy);
        } else {
            // Bitmap lebih tinggi -> paskan tinggi, lebar dikurangi (bar hitam kiri-kanan)
            float newWidth = dst.height() * srcAspect;
            float dx = (dst.width() - newWidth) / 2f;
            finalDst.set(dst.left + dx, dst.top, dst.right - dx, dst.bottom);
        }
        
        canvas.drawBitmap(frame, null, finalDst, paint);
    }

    private void drawAllLayers(Paint paint) {
        synchronized (overlayLock) {
            if (overlayBuffer == null) {
                overlayBuffer = Bitmap.createBitmap(designWidth, designHeight, Bitmap.Config.ARGB_8888);
                overlayCanvas = new Canvas(overlayBuffer);
            }
            overlayBuffer.eraseColor(Color.TRANSPARENT);
            boolean hasPendingOverlays = false;

            // Draw Background Image if active (Z-index effectively -1). Background SCREEN sudah
            // digambar terpisah langsung di GPU (lihat runDrawLoop()), jadi tidak lagi lewat sini.
            if (backgroundType == BackgroundType.IMAGE && backgroundImage != null) {
                // FIX: dulu src=null (seluruh bitmap dipaksa masuk dst) -> stretch/gepeng kalau
                // aspect ratio beda. Sekarang pakai backgroundImageCropRect (center-crop).
                overlayCanvas.drawBitmap(backgroundImage, backgroundImageCropRect, new Rect(0,0, designWidth, designHeight), paint);
                hasPendingOverlays = true;
            }

            for (Layer layer : layers) {
                // Non-video layer: batch onto Canvas
                drawSingleLayer(overlayCanvas, layer, paint);
                hasPendingOverlays = true;
            }

            // Final flush for overlays on top
            if (hasPendingOverlays) {
                flushOverlayCanvas();
            }
        }
    }

    /** Gambar ulang overlay texture yang SUDAH ter-upload ke GPU, tanpa render Canvas ulang
     *  maupun texImage2D lagi. Dipakai utk scene statis (lihat requiresDynamicOverlayRedraw). */
    private void redrawCachedOverlayTexture() {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawTexture(rgbaProgram, overlayTextureId, flipMatrix, false);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void flushOverlayCanvas() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBuffer, 0);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawTexture(rgbaProgram, overlayTextureId, flipMatrix, false);
        GLES20.glDisable(GLES20.GL_BLEND);

        // Reset canvas for next batch if needed (though we currently erase at start of drawAllLayers)
        overlayBuffer.eraseColor(Color.TRANSPARENT);
    }

    private int getDecoderTexture(VideoTextureDecoder decoder) {
        Integer texId = decoderTextures.get(decoder);
        if (texId == null) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texId = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            decoder.attachToGLContext(texId);
            decoderTextures.put(decoder, texId);
        }
        return texId;
    }


    private void drawSingleLayer(Canvas canvas, Layer layer, Paint paint) {
        Bitmap bmp = layer.bitmap;
        RectF dst = layer.reusableDst;
        dst.set(layer.x * designWidth, layer.y * designHeight, (layer.x + layer.w) * designWidth, (layer.y + layer.h) * designHeight);

        if (layer.type == LayerType.EFFECT) {
            if (layer.particleSystem == null) {
                AnimationEffect effect = AnimationEffect.BURST;
                try {
                    String effectName = layer.uri.replace("effect:", "");
                    effect = AnimationEffect.valueOf(effectName);
                } catch (Exception ignored) {}
                layer.particleSystem = new ParticleSystem(effect, designWidth, designHeight);
            }
            layer.particleSystem.updateAndDraw(canvas, paint, dst);
            return;
        }

        // Dynamic Layers
        if (layer.type == LayerType.TIKTOK_CHAT || layer.type == LayerType.TIKTOK_GIFT ||
                layer.type == LayerType.TIKTOK_JOIN || layer.type == LayerType.MUSIC_CURRENT ||
                layer.type == LayerType.MUSIC_QUEUE) {
            int lw = Math.max(1, Math.round(dst.width()));
            int lh = Math.max(1, Math.round(dst.height()));
            if (layer.type == LayerType.TIKTOK_CHAT) bmp = TikTokChatBus.getInstance().renderChatOverlay(context, lw, lh);
            else if (layer.type == LayerType.TIKTOK_GIFT) bmp = TikTokChatBus.getInstance().renderGiftOverlay(context, lw, lh);
            else if (layer.type == LayerType.TIKTOK_JOIN) bmp = TikTokChatBus.getInstance().renderJoinOverlay(context, lw, lh);
            else if (layer.type == LayerType.MUSIC_CURRENT) bmp = MusicBus.getInstance().renderCurrentSong(context, lw, lh);
            else if (layer.type == LayerType.MUSIC_QUEUE) bmp = MusicBus.getInstance().renderQueue(context, lw, lh);

            if (bmp != null && !bmp.isRecycled()) canvas.drawBitmap(bmp, null, dst, paint);
            return;
        }

        if (layer.type == LayerType.SCREEN) {
            StreamService svc = StreamService.getInstance();
            if (svc != null) {
                synchronized (svc.getGlobalScreenLock()) {
                    bmp = svc.getGlobalScreenFrame();
                    if (bmp != null && !bmp.isRecycled()) drawScreenFrame(canvas, bmp, dst, paint);
                }
            }
            return;
        }

        if (layer.type == LayerType.VOICE_ANIM && layer.voiceAnimConfig != null && layer.voiceAnimBitmaps != null) {
            float level = this.micLevel;
            ame.project.nlstudio.scene.VoiceAnimItem selectedItem = null;
            float maxT = -1f;
            for (ame.project.nlstudio.scene.VoiceAnimItem item : layer.voiceAnimConfig.getItems()) {
                if (level >= item.getThreshold() && item.getThreshold() > maxT) {
                    selectedItem = item; maxT = item.getThreshold();
                }
            }
            if (selectedItem != null) {
                bmp = layer.voiceAnimBitmaps.get(selectedItem.getImageUri());

                // Voice anim effects
                float minB = layer.voiceAnimConfig.getMinBrightness();
                float sInt = layer.voiceAnimConfig.getScaleIntensity();
                float tStart = layer.voiceAnimConfig.getEffectThresholdStart();
                float tEnd = layer.voiceAnimConfig.getEffectThresholdEnd();
                float range = Math.max(0.01f, tEnd - tStart);
                float norm = Math.max(0.0f, Math.min(1.0f, (level - tStart) / range));

                float brightness = minB + (norm * (1.0f - minB));
                // OPTIMASI: ColorMatrixColorFilter di Android immutable begitu dibuat (matrix-nya
                // di-snapshot saat construction), jadi sebelumnya kode ini selalu bikin objek baru
                // tiap frame (30x/detik) walau brightness-nya nyaris sama -> GC churn terus-terusan
                // selama layer VOICE_ANIM aktif. Sekarang cuma re-create kalau brightness berubah
                // > 0.5% dari nilai terakhir, sisanya reuse filter yang sudah ada.
                if (layer.reusableColorFilter == null || Float.isNaN(layer.lastAppliedBrightness)
                        || Math.abs(layer.lastAppliedBrightness - brightness) > 0.005f) {
                    if (layer.reusableColorMatrix == null) layer.reusableColorMatrix = new android.graphics.ColorMatrix();
                    layer.reusableColorMatrix.setScale(brightness, brightness, brightness, 1f);
                    layer.reusableColorFilter = new android.graphics.ColorMatrixColorFilter(layer.reusableColorMatrix);
                    layer.lastAppliedBrightness = brightness;
                }
                paint.setColorFilter(layer.reusableColorFilter);

                float scale = (1.0f - (sInt / 2.0f)) + (norm * sInt);
                float cx = dst.centerX(); float cy = dst.centerY();
                float nw = dst.width() * scale; float nh = dst.height() * scale;
                dst.set(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f);
            }
        }

        if (bmp != null && !bmp.isRecycled()) {
            canvas.drawBitmap(bmp, null, dst, paint);
        }
        paint.setColorFilter(null);
    }

    private void initGl() {
        setupEgl();
        oesProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        rgbaProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_RGBA);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        overlayTextureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        int[] screenTex = new int[1];
        GLES20.glGenTextures(1, screenTex, 0);
        screenTextureId = screenTex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).position(0);
    }

    private void drawTexture(int program, int texId, float[] matrix, boolean isOes) {
        drawTextureWithBuffer(program, texId, matrix, isOes, vertexBuffer);
    }

    private void drawTextureWithBuffer(int program, int texId, float[] matrix, boolean isOes, FloatBuffer vBuffer) {
        if (vBuffer == null) vBuffer = vertexBuffer;
        GLES20.glUseProgram(program);
        int posLoc = GLES20.glGetAttribLocation(program, "aPosition");
        int texLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        int mtxLoc = GLES20.glGetUniformLocation(program, "uMatrix");

        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vBuffer);
        GLES20.glEnableVertexAttribArray(texLoc);
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glUniformMatrix4fv(mtxLoc, 1, false, matrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(isOes ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D, texId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int lastSrcW = -1, lastSrcH = -1;

    private void updateScreenVertices(int srcW, int srcH, int dstW, int dstH) {
        if (srcW == lastSrcW && srcH == lastSrcH && screenVertexBuffer != null) return;
        lastSrcW = srcW; lastSrcH = srcH;

        float srcAspect = (float) srcW / srcH;
        float dstAspect = (float) dstW / dstH;

        float x = 1.0f, y = 1.0f;
        if (srcAspect > dstAspect) {
            y = dstAspect / srcAspect;
        } else {
            x = srcAspect / dstAspect;
        }

        float[] vdata = {
                -x, -y,
                 x, -y,
                -x,  y,
                 x,  y
        };

        if (screenVertexBuffer == null) {
            screenVertexBuffer = ByteBuffer.allocateDirect(vdata.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        screenVertexBuffer.clear();
        screenVertexBuffer.put(vdata).position(0);
    }

    private void setupEgl() {
        // FIX: sebelumnya tidak ada pengecekan error sama sekali di sini - kalau salah satu
        // langkah EGL gagal di device/driver tertentu, kode lanjut jalan dengan EGL_NO_DISPLAY /
        // EGL_NO_CONTEXT / config kosong dan baru meledak (atau nge-black-screen tanpa pesan
        // jelas) di panggilan GLES berikutnya - susah didiagnosis. Sekarang tiap langkah dicek dan
        // di-log pakai eglGetError() kalau gagal, lalu berhenti sebelum bikin state GL rusak.
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "setupEgl: eglGetDisplay gagal (EGL_NO_DISPLAY)");
            return;
        }

        int[] ver = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) {
            Log.e(TAG, "setupEgl: eglInitialize gagal, error=0x" + Integer.toHexString(EGL14.eglGetError()));
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            return;
        }

        int[] attr = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attr, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            Log.e(TAG, "setupEgl: eglChooseConfig gagal / tidak ada config RGBA8888 ES2 yang cocok, error=0x"
                    + Integer.toHexString(EGL14.eglGetError()));
            return;
        }

        int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "setupEgl: eglCreateContext gagal, error=0x" + Integer.toHexString(EGL14.eglGetError()));
            return;
        }

        int[] surfAttr = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], targetSurface, surfAttr, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "setupEgl: eglCreateWindowSurface gagal, error=0x" + Integer.toHexString(EGL14.eglGetError()));
            return;
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "setupEgl: eglMakeCurrent gagal, error=0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private void releaseGl() {
        for (java.util.Map.Entry<VideoTextureDecoder, Integer> entry : decoderTextures.entrySet()) {
            entry.getKey().detachFromGLContext();
            GLES20.glDeleteTextures(1, new int[]{entry.getValue()}, 0);
        }
        decoderTextures.clear();

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
    }

    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private int loadShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = aPosition;\n" +
                    "  vTexCoord = (uMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_RGBA =
            "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}\n";

    @Override public void setFadeFromSnapshot(Bitmap s) {}
    @Override public Bitmap peekCurrentFrame() { return null; }

    // --- Particle System for Stream Output (Canvas based for simplicity and lightweight) ---
    public static class ParticleSystem {
        private static final int MAX_PARTICLES = 200; // Reduced for stream to keep it lightweight
        private final AnimationEffect effect;
        private final int width, height;
        private final Particle[] pool;
        private long lastUpdateTime;
        private final java.util.Random random = new java.util.Random();

        private static class Particle {
            boolean active = false;
            long birthTime;
            float life, x0, y0, vx, vy, r, g, b, baseSize, shape, seed, seed2;
        }

        public ParticleSystem(AnimationEffect effect, int width, int height) {
            this.effect = effect;
            this.width = width;
            this.height = height;
            this.pool = new Particle[MAX_PARTICLES];
            for (int i = 0; i < MAX_PARTICLES; i++) pool[i] = new Particle();
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public void updateAndDraw(Canvas canvas, Paint paint, RectF dst) {
            long now = System.currentTimeMillis();
            float dt = (now - lastUpdateTime) / 1000f;
            lastUpdateTime = now;

            // Density based on layer size (proportional to area)
            float densityFactor = (dst.width() * dst.height()) / (width * height);
            int spawnRate = Math.max(1, (int)(10 * densityFactor * 5)); // Base spawn rate scaled

            // Auto spawn for persistent effects
            if (isPersistent(effect)) {
                spawnParticles(spawnRate);
            }

            for (Particle p : pool) {
                if (!p.active) continue;
                float elapsed = (now - p.birthTime) / 1000f;
                if (elapsed >= p.life) {
                    p.active = false;
                    continue;
                }

                float t = Math.min(1f, elapsed / p.life);
                float curX, curY, curAlpha, curSize;

                // Simple physics port from QuickAnimationView
                switch (effect) {
                    case BURST:
                        float damp = Math.max(0f, 1f - 0.5f * elapsed);
                        curX = p.x0 + p.vx * elapsed * damp;
                        curY = p.y0 + p.vy * elapsed * damp + 0.4f * elapsed * elapsed;
                        curAlpha = 1f - t;
                        curSize = p.baseSize * (1f - 0.2f * t);
                        break;
                    case SNOW:
                    case CONFETTI:
                    case LEAVES:
                    case PETALS:
                        float drift = (float)Math.sin(elapsed * 2f + p.seed * 6.283f) * 0.06f;
                        curX = p.x0 + p.vx * elapsed + drift;
                        curY = p.y0 + p.vy * elapsed;
                        curAlpha = Math.min(elapsed / 0.2f, (p.life - elapsed) / 0.5f);
                        curSize = p.baseSize;
                        break;
                    case HEARTS:
                    case BUBBLES:
                        float sway = (float)Math.sin(elapsed * 3f + p.seed * 6.283f) * 0.08f;
                        curX = p.x0 + p.vx * elapsed + sway;
                        curY = p.y0 + p.vy * elapsed;
                        curAlpha = Math.min(elapsed / 0.2f, (p.life - elapsed) / 0.5f);
                        curSize = p.baseSize;
                        break;
                    default:
                        curX = p.x0 + p.vx * elapsed;
                        curY = p.y0 + p.vy * elapsed;
                        curAlpha = 1f - t;
                        curSize = p.baseSize;
                }

                paint.setARGB((int)(curAlpha * 255), (int)(p.r * 255), (int)(p.g * 255), (int)(p.b * 255));

                // Scale coordinate to layer bounds instead of full design resolution
                float px = dst.left + (curX + 1f) / 2f * dst.width();
                float py = dst.top + (curY + 1f) / 2f * dst.height();

                // Scale particle size based on layer width
                float scaledSize = curSize * (dst.width() / width);

                // Draw simple shapes on canvas
                if (p.shape == 1f) { // HEART approx
                    canvas.drawCircle(px, py, scaledSize / 2f, paint);
                } else if (p.shape == 3f) { // DIAMOND
                    canvas.drawRect(px - scaledSize/2, py - scaledSize/2, px + scaledSize/2, py + scaledSize/2, paint);
                } else {
                    canvas.drawCircle(px, py, scaledSize / 2f, paint);
                }
            }
            paint.setAlpha(255);
        }

        private boolean isPersistent(AnimationEffect e) {
            return e == AnimationEffect.SNOW || e == AnimationEffect.CONFETTI || e == AnimationEffect.BUBBLES ||
                    e == AnimationEffect.LEAVES || e == AnimationEffect.PETALS || e == AnimationEffect.STARDUST || e == AnimationEffect.HEARTS;
        }

        private void spawnParticles(int count) {
            int spawned = 0;
            for (int i = 0; i < MAX_PARTICLES && spawned < count; i++) {
                if (!pool[i].active) {
                    initParticle(pool[i]);
                    spawned++;
                }
            }
        }

        private void initParticle(Particle p) {
            p.active = true;
            p.birthTime = System.currentTimeMillis();
            p.seed = random.nextFloat();
            p.seed2 = random.nextFloat() * 2f - 1f;

            p.x0 = random.nextFloat() * 2f - 1f;
            p.y0 = (effect == AnimationEffect.SNOW || effect == AnimationEffect.CONFETTI) ? -1.1f : 1.1f;
            p.vx = random.nextFloat() * 0.4f - 0.2f;
            p.vy = (effect == AnimationEffect.SNOW || effect == AnimationEffect.CONFETTI) ? (random.nextFloat() * 0.3f + 0.2f) : -(random.nextFloat() * 0.3f + 0.2f);

            p.r = random.nextFloat(); p.g = random.nextFloat(); p.b = random.nextFloat();
            p.life = random.nextFloat() * 2f + 2f;
            p.baseSize = random.nextFloat() * 20f + 10f;

            if (effect == AnimationEffect.HEARTS) { p.r = 1f; p.g = 0.4f; p.b = 0.4f; p.shape = 1f; }
            else if (effect == AnimationEffect.SNOW) { p.r = 1f; p.g = 1f; p.b = 1f; p.shape = 0f; }
            else if (effect == AnimationEffect.CONFETTI) { p.shape = 3f; }
        }
    }
}
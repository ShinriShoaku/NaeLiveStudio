package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.pedro.encoder.input.sources.video.VideoSource;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ame.project.nlstudio.scene.LayerType;

/**
 * Video source ala OBS: gambar BACKGROUND lalu di atasnya digambar semua LAYER overlay.
 * Fix: Menggunakan ukuran Canvas asli untuk menggambar agar Full Scale dan menangani
 * aspek rasio screen capture agar tidak gepeng atau muncul bar hitam yang tidak diinginkan.
 */
public class CompositeSceneVideoSource extends VideoSource {

    private static final String TAG = "RES-COMP";

    public enum BackgroundType { COLOR, IMAGE, VIDEO }

    public static class Layer {
        public final Bitmap bitmap;
        public final String uri;
        public final LayerType type;
        public final float x, y, w, h;
        public final int zIndex;

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

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread screenCaptureThread;
    private Bitmap reusableScreenBitmap;
    private volatile Bitmap currentScreenFrame;
    private final Object screenLock = new Object();

    private Surface targetSurface;
    private Thread drawThread;
    private VideoTextureDecoder videoBgDecoder;
    private volatile boolean running = false;
    private volatile Bitmap currentVideoBgFrame;
    private final Object videoBgLock = new Object();

    private int designWidth = 1080;
    private int designHeight = 1920;

    // FPS asli yang diminta encoder lewat create(). Dipakai buat pacing draw loop biar
    // gak hardcode 30fps - kalau user pilih 24/25/60fps di UI, draw loop ikut menyesuaikan.
    private volatile int targetFps = 30;

    // Resolusi ASLI yang diminta encoder lewat create(). Ini yang dipakai buat set ukuran
    // buffer SurfaceTexture, BUKAN designWidth/designHeight (yang cuma dipakai buat hitung
    // aspect ratio screen capture). Kalau tidak di-set, buffer SurfaceTexture akan tetap
    // pakai ukuran dari VideoSource sebelumnya (misal ScreenSource) -> composite kita jadi
    // di-scale kecil & di-tengah-in (letterbox) oleh GL renderer punya library.
    private int encoderWidth = 0;
    private int encoderHeight = 0;

    private long lastLayerLogMs = 0L;

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

    public void setMediaProjection(MediaProjection mp) {
        this.mediaProjection = mp;
    }

    public void setResolution(int width, int height) {
        this.designWidth = width;
        this.designHeight = height;
        Log.d(TAG, "setResolution() dipanggil dari StreamService: designWidth/Height = " + width + "x" + height);
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

        boolean hasScreenLayer = false;
        for (Layer l : layers) {
            if (l.type == LayerType.SCREEN) {
                hasScreenLayer = true;
                break;
            }
        }

        if (hasScreenLayer && mediaProjection != null) {
            startScreenCapture();
        }

        if (backgroundType == BackgroundType.VIDEO && backgroundVideoUri != null) {
            startVideoBackgroundLoop();
        }
        startCompositeDrawLoop();
    }

    private int capW, capH;

    private void startScreenCapture() {
        screenCaptureThread = new HandlerThread("ScreenCapture");
        screenCaptureThread.start();
        Handler handler = new Handler(screenCaptureThread.getLooper());

        // Gunakan resolusi layar asli agar capture tidak ter-scale/pillarbox di dalam VirtualDisplay
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;

        // Kita gunakan width/height design sebagai limit resolusi capture agar tidak terlalu berat,
        // tapi tetap mengikuti aspek rasio HP asli.
        float designRatio = (float) designWidth / designHeight;
        float screenRatio = (float) screenW / screenH;

        if (screenRatio > designRatio) { // Screen is wider
            capW = designWidth;
            capH = Math.round(designWidth / screenRatio);
        } else {
            capH = designHeight;
            capW = Math.round(designHeight * screenRatio);
        }

        Log.d(TAG, "startScreenCapture(): screenW/H(live displayMetrics)=" + screenW + "x" + screenH
                + " designWidth/Height(scene)=" + designWidth + "x" + designHeight
                + " -> capW/H=" + capW + "x" + capH);

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("CompositeScreen",
                capW, capH, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler);

        imageReader.setOnImageAvailableListener(reader -> {
            try {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    synchronized (screenLock) {
                        updateScreenBitmap(image);
                    }
                    image.close();
                }
            } catch (Exception ignored) {}
        }, handler);
    }

    private void updateScreenBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            int bitmapWidth = image.getWidth() + rowPadding / pixelStride;

            if (reusableScreenBitmap == null || reusableScreenBitmap.getWidth() != bitmapWidth || reusableScreenBitmap.getHeight() != image.getHeight()) {
                if (reusableScreenBitmap != null) reusableScreenBitmap.recycle();
                reusableScreenBitmap = Bitmap.createBitmap(bitmapWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
            }
            buffer.rewind();
            reusableScreenBitmap.copyPixelsFromBuffer(buffer);
            currentScreenFrame = reusableScreenBitmap;
        } catch (Exception e) {
            Log.e(TAG, "updateScreenBitmap error", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        joinQuietly(drawThread);

        if (videoBgDecoder != null) {
            videoBgDecoder.stop();
            videoBgDecoder = null;
        }
        synchronized (videoBgLock) {
            currentVideoBgFrame = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (screenCaptureThread != null) {
            screenCaptureThread.quitSafely();
            screenCaptureThread = null;
        }

        synchronized (screenLock) {
            if (reusableScreenBitmap != null) {
                reusableScreenBitmap.recycle();
                reusableScreenBitmap = null;
            }
            currentScreenFrame = null;
        }

        drawThread = null;
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

    private void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(300);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * FIX SMOOTHNESS: sebelumnya di sini pakai MediaMetadataRetriever.getFrameAtTime() yang
     * dipanggil berulang tiap ~33ms - itu didesain buat ambil thumbnail sesekali, BUKAN
     * playback real-time (tiap panggilan seek + redecode dari keyframe terdekat, bisa >100ms).
     * Itu penyebab utama background video patah-patah.
     *
     * Sekarang pakai VideoTextureDecoder: video didecode terus-menerus lewat MediaPlayer ->
     * SurfaceTexture -> GL, dan frame baru cuma diproses saat memang tersedia (event-driven,
     * bukan polling). Hasilnya ditulis ke currentVideoBgFrame dengan lock singkat + double
     * buffer di dalam VideoTextureDecoder supaya draw thread di bawah tidak pernah baca frame
     * yang sedang setengah ditulis (anti-tearing).
     */
    private void startVideoBackgroundLoop() {
        videoBgDecoder = new VideoTextureDecoder(context, backgroundVideoUri, designWidth, designHeight,
                bitmap -> {
                    synchronized (videoBgLock) {
                        currentVideoBgFrame = bitmap;
                    }
                });
        videoBgDecoder.start();
    }

    private void startCompositeDrawLoop() {
        drawThread = new Thread(() -> {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            long lastLog = 0L;

            // FIX PACING: sebelumnya frameTimeMs di-hardcode 33ms (30fps) dan sleep dihitung
            // dari "elapsed sejak awal loop ini saja" - errornya tidak dikompensasi, jadi makin
            // lama makin ngaco (drift menumpuk). Sekarang pakai jadwal absolut berbasis
            // nanoTime yang mengikuti fps ASLI dari encoder (targetFps, diisi di create()),
            // dan kalau sempat telat jauh (misal abis GC pause panjang) jadwal di-reset -
            // bukan coba "kejar ketinggalan" dengan beruntun render tanpa sleep sama sekali
            // (yang justru bikin makin patah-patah / thermal throttle).
            long frameIntervalNs = 1_000_000_000L / Math.max(1, targetFps);
            long nextFrameTimeNs = System.nanoTime();

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

                Canvas canvas = null;
                try {
                    canvas = targetSurface.lockCanvas(null);
                    if (canvas != null) {
                        int cW = canvas.getWidth();
                        int cH = canvas.getHeight();

                        canvas.save();
                        canvas.scale((float) cW / designWidth, (float) cH / designHeight);

                        drawBackground(canvas, paint, designWidth, designHeight);
                        drawLayers(canvas, paint, designWidth, designHeight);

                        canvas.restore();

                        long logNow = System.currentTimeMillis();
                        if (logNow - lastLog > 5000) {
                            lastLog = logNow;
                            Log.d(TAG, "drawLoop: Rendered " + designWidth + "x" + designHeight +
                                    " into Canvas " + cW + "x" + cH + " @" + targetFps + "fps");
                        }
                    }
                } finally {
                    if (canvas != null) {
                        targetSurface.unlockCanvasAndPost(canvas);
                    }
                }

                nextFrameTimeNs += frameIntervalNs;
                long lagNs = System.nanoTime() - nextFrameTimeNs;
                if (lagNs > frameIntervalNs) {
                    // Ketinggalan lebih dari 1 frame penuh (stall/GC pause panjang) - reset
                    // jadwal daripada burst-render tanpa jeda untuk "kejar ketinggalan".
                    nextFrameTimeNs = System.nanoTime();
                }
            }
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

    private void drawBackground(Canvas canvas, Paint paint, int cW, int cH) {
        canvas.drawColor(Color.BLACK);
        Bitmap bg = null;
        if (backgroundType == BackgroundType.IMAGE) {
            bg = backgroundImage;
        } else if (backgroundType == BackgroundType.VIDEO) {
            synchronized (videoBgLock) {
                bg = currentVideoBgFrame;
            }
        }
        if (bg != null) {
            Rect dst = fillRect(bg.getWidth(), bg.getHeight(), cW, cH);
            canvas.drawBitmap(bg, null, dst, paint);
        }
    }

    private void drawLayers(Canvas canvas, Paint paint, int cW, int cH) {
        for (Layer layer : layers) {
            Bitmap bmp = layer.bitmap;
            boolean isScreen = layer.type == LayerType.SCREEN;

            RectF dst = new RectF(
                    layer.x * cW,
                    layer.y * cH,
                    (layer.x + layer.w) * cW,
                    (layer.y + layer.h) * cH
            );

            if (isScreen) {
                synchronized (screenLock) {
                    bmp = currentScreenFrame;
                    if (bmp != null && !bmp.isRecycled()) {
                        // FIX: Gunakan capW dan capH yang sudah dihitung di startScreenCapture
                        // untuk membuang padding hasil imageToBitmap secara akurat.
                        Rect srcRect = new Rect(0, 0, capW, capH);
                        RectF cropDst = fillRectF(capW, capH, dst);

                        long now = System.currentTimeMillis();
                        if (now - lastLayerLogMs > 5000) {
                            lastLayerLogMs = now;
                            Log.d(TAG, "drawLayers(SCREEN): cap=" + capW + "x" + capH
                                    + " bmpActual=" + bmp.getWidth() + "x" + bmp.getHeight()
                                    + " -> dstBox=" + dst + " cropDst=" + cropDst);
                        }

                        canvas.save();
                        canvas.clipRect(dst);
                        canvas.drawBitmap(bmp, srcRect, cropDst, paint);
                        canvas.restore();
                    }
                }
                continue;
            }

            if (bmp == null || bmp.isRecycled()) continue;
            canvas.drawBitmap(bmp, null, dst, paint);
        }
    }

    /** Center Crop logic untuk layer: memotong sisi agar gambar memenuhi area tujuan tanpa distorsi. */
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
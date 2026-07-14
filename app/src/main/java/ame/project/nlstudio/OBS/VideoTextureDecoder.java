package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Decode video file secara TERUS-MENERUS (streaming), BUKAN seek-per-frame seperti
 * MediaMetadataRetriever.getFrameAtTime().
 *
 * Alurnya: MediaExtractor + MediaCodec (VIDEO TRACK SAJA) -> SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 * -> render ke FBO offscreen -> glReadPixels -> Bitmap. Semua kerja GL/EGL jalan di HandlerThread
 * miliknya sendiri, dipicu oleh SurfaceTexture.OnFrameAvailableListener (bukan Thread.sleep
 * polling), jadi frame baru cuma diproses saat memang ada frame baru dari decoder hardware.
 *
 * KENAPA MediaExtractor+MediaCodec MANUAL, BUKAN MediaPlayer (versi sebelumnya):
 * MediaPlayer otomatis handle audio+video sekaligus - walau di-setVolume(0,0) supaya senyap,
 * audio track-nya TETAP didecode dan tetap punya audio session aktif di sistem. Audio session
 * yang masih aktif itu bisa "bocor" ke fitur internal-audio-capture (mis. AudioMixSource /
 * capture audio sistem lewat MediaProjection yang dipakai StreamService) walau device speaker
 * sendiri diam - itu penyebab suara dari fake scene video ikut kerekam padahal sudah "di-mute".
 * Solusinya bukan senyapin volume, tapi jangan pernah sentuh audio track-nya sama sekali:
 * MediaExtractor di sini cuma men-select TRACK VIDEO (lihat setupExtractorAndCodec()), audio
 * track di file itu tidak pernah dipilih/didecode/dibuka session-nya - jadi memang tidak ada
 * audio apapun yang bisa dikapture siapapun, bukan sekadar "disenyapin".
 *
 * Loop video (kalau loop=true) dilakukan dengan extractor.seekTo(0) + codec.flush() saat EOS -
 * SATU decoder yang di-reset, bukan bongkar-pasang objek MediaCodec baru tiap kali video habis
 * (flush+seek itu operasi cepat karena codec-nya sudah "panas"/siap).
 *
 * Kenapa ini jauh lebih smooth dibanding MediaMetadataRetriever:
 * - Tidak ada seek + redecode-dari-keyframe-terdekat tiap kali minta frame (itu yang bikin
 *   MediaMetadataRetriever bisa >100ms per panggilan dan menyebabkan patah-patah).
 * - Decoder hardware push frame secara alami sesuai frame rate video aslinya (dipacing manual
 *   berbasis presentationTimeUs di decodeLoop()).
 * - Pakai double-buffer Bitmap (outputBitmaps[0]/[1]) supaya thread yang MEMBACA hasil frame
 *   (composite draw thread / cache prefetch) tidak pernah membaca Bitmap yang sedang ditulis
 *   ulang isinya (mencegah tearing), tanpa perlu alokasi Bitmap baru tiap frame (mengurangi
 *   tekanan GC).
 */
public class VideoTextureDecoder {

    public interface Listener {
        void onFrame(Bitmap bitmap);

        /** Dipanggil sekali saat video sampai akhir DAN loop=false. Default no-op supaya
         *  interface ini tetap functional interface (lambda lama seperti `bitmap -> {...}`
         *  di CompositeSceneVideoSource tetap kompatibel tanpa perlu diubah). */
        default void onComplete() {}

        /** Dipanggil kalau decoder gagal (misal file rusak/tidak didukung/tidak ada video track). */
        default void onError(Exception e) {}
    }

    private static final String TAG = "VideoTextureDecoder";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    // Y di-flip di sini karena glReadPixels membaca framebuffer dari bawah ke atas,
                    // sedangkan Bitmap Android disimpan dari atas ke bawah. Flip ini mengkompensasi
                    // itu supaya hasil akhir Bitmap orientasinya benar (tidak terbalik).
                    "    gl_Position = vec4(aPosition.x, -aPosition.y, aPosition.z, aPosition.w);\n" +
                    "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private static final float[] FULL_RECT_VERTICES = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f,
    };
    private static final float[] FULL_RECT_TEX_COORDS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    private final Context context;
    private final Uri videoUri;
    private final int outWidth;
    private final int outHeight;
    private final boolean loop;
    private final Listener listener;

    private HandlerThread glThread;
    private Handler glHandler;
    private Thread decodeThread;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int oesTextureId;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;
    private MediaExtractor extractor;
    private MediaCodec codec;

    private int program;
    private int aPositionLoc, aTexCoordLoc, uTexMatrixLoc;
    private FloatBuffer vertexBuffer, texCoordBuffer;

    private int fboId, fboTextureId;
    private ByteBuffer readBuffer;
    private final Bitmap[] outputBitmaps = new Bitmap[2];
    private int bufferIndex = 0;

    private final float[] texMatrix = new float[16];
    private volatile boolean running = false;

    /** Constructor lama, dipertahankan demi kompatibilitas (loop=true, dipakai CompositeSceneVideoSource). */
    public VideoTextureDecoder(Context context, Uri videoUri, int outWidth, int outHeight, Listener listener) {
        this(context, videoUri, outWidth, outHeight, true, listener);
    }

    /**
     * @param loop true = video di-loop terus (seekTo(0)+flush() tiap EOS), streaming real-time.
     *             false = decode SEKALI sampai habis lalu {@link Listener#onComplete()} dipanggil
     *             dan tidak ada frame baru lagi - dipakai untuk pre-decode/caching sekali jalan.
     */
    public VideoTextureDecoder(Context context, Uri videoUri, int outWidth, int outHeight,
                               boolean loop, Listener listener) {
        this.context = context;
        this.videoUri = videoUri;
        this.outWidth = Math.max(2, outWidth);
        this.outHeight = Math.max(2, outHeight);
        this.loop = loop;
        this.listener = listener;
    }

    /** Mulai decode. Aman dipanggil dari thread manapun. */
    public void start() {
        glThread = new HandlerThread("VideoTextureDecoder-GL");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        glHandler.post(this::initGlAndDecoder);
    }

    /** Hentikan & release semua resource (EGL, GL, MediaCodec, MediaExtractor). Blocking singkat sampai selesai. */
    public void stop() {
        running = false;

        if (decodeThread != null) {
            decodeThread.interrupt();
            try {
                decodeThread.join(200);
            } catch (InterruptedException ignored) {}
            decodeThread = null;
        }

        final Handler handler = glHandler;
        if (handler != null) {
            if (handler.getLooper().getThread() == Thread.currentThread()) {
                // FIX DEADLOCK: Jika dipanggil dari thread GL sendiri (misal dari onFrame callback),
                // langsung eksekusi release tanpa post & wait.
                releaseGlAndCodec();
            } else {
                final Object lock = new Object();
                final boolean[] done = {false};
                synchronized (lock) {
                    handler.post(() -> {
                        releaseGlAndCodec();
                        synchronized (lock) {
                            done[0] = true;
                            lock.notifyAll();
                        }
                    });
                    try {
                        // Tunggu sebentar agar resource bersih, tapi jangan kelamaan (ANR)
                        if (!done[0]) lock.wait(200);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        if (glThread != null) {
            glThread.quitSafely();
            glThread = null;
        }
        glHandler = null;
    }

    private void initGlAndDecoder() {
        try {
            setupEgl();
            setupGlResources();

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            oesTextureId = textures[0];
            surfaceTexture = new SurfaceTexture(oesTextureId);
            surfaceTexture.setDefaultBufferSize(outWidth, outHeight);
            inputSurface = new Surface(surfaceTexture);

            surfaceTexture.setOnFrameAvailableListener(st -> {
                Handler h = glHandler;
                if (h != null) h.post(this::drawFrame);
            }, glHandler);

            setupExtractorAndCodec();

            running = true;
            decodeThread = new Thread(this::decodeLoop, "VideoTextureDecoder-codec");
            decodeThread.start();
        } catch (Exception e) {
            Log.e(TAG, "initGlAndDecoder gagal", e);
            if (listener != null) listener.onError(e);
        }
    }

    /**
     * Siapkan MediaExtractor + MediaCodec buat decode VIDEO TRACK SAJA. Audio track (kalau ada
     * di file itu) SENGAJA tidak pernah di-selectTrack() - lihat penjelasan lengkap di komentar
     * class ini kenapa ini penting (mencegah audio "bocor" ke recording).
     */
    private void setupExtractorAndCodec() throws IOException {
        extractor = new MediaExtractor();
        extractor.setDataSource(context, videoUri, null);

        int videoTrackIndex = -1;
        MediaFormat videoFormat = null;
        int trackCount = extractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoTrackIndex = i;
                videoFormat = f;
                break;
            }
        }
        if (videoTrackIndex < 0 || videoFormat == null) {
            throw new IOException("Tidak ada video track di file: " + videoUri);
        }
        extractor.selectTrack(videoTrackIndex);

        String mime = videoFormat.getString(MediaFormat.KEY_MIME);
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(videoFormat, inputSurface, null, 0);
        codec.start();
    }

    /**
     * Loop decode manual: feed compressed data dari extractor ke codec, ambil output yang sudah
     * didecode, render ke inputSurface (yang mentrigger onFrameAvailable -> drawFrame() di
     * glHandler). Pacing pakai presentationTimeUs asli dari video biar kecepatan playback benar
     * (bukan secepat mungkin decode terus, yang bisa bikin drop frame/salah kecepatan).
     */
    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputEos = false;
        long startTimeNs = -1;

        try {
            while (running) {
                if (!inputEos) {
                    int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int sampleSize = inBuf != null ? extractor.readSampleData(inBuf, 0) : -1;
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            long ptsUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex >= 0) {
                    boolean render = info.size > 0;
                    if (render) {
                        if (startTimeNs < 0) {
                            startTimeNs = System.nanoTime() - info.presentationTimeUs * 1000L;
                        }
                        long ptsNs = startTimeNs + info.presentationTimeUs * 1000L;
                        long delayNs = ptsNs - System.nanoTime();
                        if (delayNs > 0) {
                            try {
                                Thread.sleep(delayNs / 1_000_000L, (int) (delayNs % 1_000_000L));
                            } catch (InterruptedException e) {
                                codec.releaseOutputBuffer(outIndex, false);
                                break;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, render);

                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (eos) {
                        if (loop && running) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            codec.flush();
                            inputEos = false;
                            startTimeNs = -1;
                        } else {
                            running = false;
                            if (listener != null) listener.onComplete();
                            break;
                        }
                    }
                }
                // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED:
                // tidak perlu penanganan khusus buat kasus pemakaian kita (output selalu ke Surface).
            }
        } catch (Exception e) {
            Log.e(TAG, "decodeLoop error", e);
            if (listener != null) listener.onError(e);
        }
    }

    private void setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay gagal");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize gagal");
        }
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw new RuntimeException("eglChooseConfig gagal");
        }
        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext gagal");

        int[] pbufAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreatePbufferSurface gagal");

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent gagal");
        }
    }

    private void setupGlResources() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord");
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");

        vertexBuffer = ByteBuffer.allocateDirect(FULL_RECT_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(FULL_RECT_VERTICES).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(FULL_RECT_TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(FULL_RECT_TEX_COORDS).position(0);

        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        fboId = fbo[0];

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        fboTextureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outWidth, outHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO tidak lengkap, status=" + status);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        readBuffer = ByteBuffer.allocateDirect(outWidth * outHeight * 4).order(ByteOrder.nativeOrder());
        outputBitmaps[0] = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        outputBitmaps[1] = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
    }

    private int buildProgram(String vertexSrc, String fragmentSrc) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Link program gagal: " + log);
        }
        return prog;
    }

    private int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Compile shader gagal: " + log);
        }
        return shader;
    }

    private void drawFrame() {
        if (!running || surfaceTexture == null) return;
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            GLES20.glViewport(0, 0, outWidth, outHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPositionLoc);
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

            texCoordBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glDisableVertexAttribArray(aTexCoordLoc);

            readBuffer.rewind();
            GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer);
            readBuffer.rewind();

            // Tulis ke buffer yang TIDAK sedang dibaca (double buffer), baru swap referensi.
            Bitmap target = outputBitmaps[bufferIndex];
            target.copyPixelsFromBuffer(readBuffer);
            bufferIndex = 1 - bufferIndex;

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            if (listener != null) listener.onFrame(target);
        } catch (Exception e) {
            Log.e(TAG, "drawFrame error", e);
        }
    }

    private void releaseGlAndCodec() {
        running = false;
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (extractor != null) {
            try { extractor.release(); } catch (Exception ignored) {}
            extractor = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }
}
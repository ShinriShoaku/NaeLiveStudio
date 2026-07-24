package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
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

/**
 * GPU Optimized Fake Scene Video Source.
 */
public class FakeSceneVideoSource extends VideoSource implements SceneCrossfadeSupport {

    private static final String TAG = "FakeScene-GPU";

    public enum Mode { STATIC_IMAGE, VIDEO_FILE }

    private final Context context;
    private final Mode mode;
    private Bitmap staticImage;
    private final Uri videoUri;
    private boolean isImageUpdatePending = false;

    private Surface targetSurface;
    private Thread drawThread;
    private volatile boolean running = false;
    private volatile int targetFps = 30;

    private VideoTextureDecoder videoDecoder;

    // GL Resources
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int oesProgram, rgbaProgram;
    private int imageTextureId = 0;
    private int videoTextureId = 0;
    private FloatBuffer vertexBuffer, texCoordBuffer;
    private final float[] identityMatrix = new float[16];
    private final float[] flipMatrix = new float[16];
    {
        android.opengl.Matrix.setIdentityM(identityMatrix, 0);
        android.opengl.Matrix.setIdentityM(flipMatrix, 0);
        // FIX: Flip Y-axis untuk output Bitmap (STATIC_IMAGE). Android Bitmap top-down,
        // OpenGL bottom-up. Tanpa flip, gambar background di FakeScene akan terbalik.
        android.opengl.Matrix.scaleM(flipMatrix, 0, 1f, -1f, 1f);
    }

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

    /**
     * Update bitmap secara on-the-fly tanpa restart thread.
     * Cocok buat update overlay AFK tiap detik biar gak berat.
     */
    public void updateStaticImage(Bitmap newBitmap) {
        if (mode != Mode.STATIC_IMAGE) return;
        synchronized (this) {
            this.staticImage = newBitmap;
            this.isImageUpdatePending = true;
        }
    }

    @Override
    protected boolean create(int width, int height, int fps, int rotation) {
        this.targetFps = fps > 0 ? fps : 30;
        return true;
    }

    @Override
    public void start(@NonNull SurfaceTexture surfaceTexture) {
        surfaceTexture.setDefaultBufferSize(getWidth(), getHeight());
        this.targetSurface = new Surface(surfaceTexture);
        running = true;

        if (mode == Mode.VIDEO_FILE && videoUri != null) {
            videoDecoder = VideoCacheManager.getInstance().acquire(context, videoUri, getWidth(), getHeight(), targetFps);
            videoDecoder.setPlayWhenReady(true);
        }

        drawThread = new Thread(this::runDrawLoop, "FakeScene-GPU-Thread");
        drawThread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (drawThread != null) {
            // Bangunkan thread dari Thread.sleep() di runDrawLoop supaya keluar SEGERA,
            // daripada nunggu pasif sampai frame interval berikutnya atau timeout join.
            drawThread.interrupt();
            try { drawThread.join(1000); } catch (InterruptedException ignored) {}
            drawThread = null;
        }
        if (videoUri != null) {
            VideoCacheManager.getInstance().release(videoUri);
        }
        videoDecoder = null;

        // FIX "Handler on a dead thread": JANGAN langsung release targetSurface di sini.
        // targetSurface membungkus SurfaceTexture milik Pedro's library. Jika di-release
        // sebelum library selesai melakukan cleanup (misal black frame / tryClear),
        // internal Handler di SurfaceTexture (yg dibuat di thread GL sebelumnya) akan
        // komplain saat diakses. Biarkan GC yang membersihkan Surface wrapper ini.
        targetSurface = null;

        // JANGAN recycle staticImage di sini jika masih ada kemungkinan diakses oleh
        // GL thread yang belum tuntas (walau sudah join). Biarkan pemanggil atau GC yang handle.
    }

    @Override public void release() { stop(); }
    @Override public boolean isRunning() { return running; }

    private void runDrawLoop() {
        initGl();
        long frameIntervalNs = 1_000_000_000L / targetFps;
        long nextFrameTimeNs = System.nanoTime();

        while (running) {
            long now = System.nanoTime();
            if (now < nextFrameTimeNs) {
                try { Thread.sleep((nextFrameTimeNs - now) / 1_000_000L); } catch (InterruptedException e) { break; }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            if (mode == Mode.VIDEO_FILE && videoDecoder != null) {
                if (videoTextureId == 0) {
                    int[] tex = new int[1];
                    GLES20.glGenTextures(1, tex, 0);
                    videoTextureId = tex[0];
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    videoDecoder.attachToGLContext(videoTextureId);
                }
                videoDecoder.updateTexImage();
                drawTexture(oesProgram, videoTextureId, videoDecoder.getTexMatrix(), true);
            } else if (mode == Mode.STATIC_IMAGE && staticImage != null) {
                synchronized (this) {
                    if (imageTextureId == 0) {
                        int[] tex = new int[1];
                        GLES20.glGenTextures(1, tex, 0);
                        imageTextureId = tex[0];
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, staticImage, 0);
                        isImageUpdatePending = false;
                    } else if (isImageUpdatePending) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, staticImage, 0);
                        isImageUpdatePending = false;
                    }
                }
                drawTexture(rgbaProgram, imageTextureId, flipMatrix, false);
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            nextFrameTimeNs += frameIntervalNs;
            if (System.nanoTime() - nextFrameTimeNs > frameIntervalNs) nextFrameTimeNs = System.nanoTime();
        }
        releaseGl();
    }

    private void initGl() {
        setupEgl();
        oesProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        rgbaProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_RGBA);
        vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).position(0);
    }

    private void setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);
        int[] attr = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attr, 0, configs, 0, 1, num, 0);
        int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
        int[] surfAttr = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], targetSurface, surfAttr, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void drawTexture(int program, int texId, float[] matrix, boolean isOes) {
        GLES20.glUseProgram(program);
        int posLoc = GLES20.glGetAttribLocation(program, "aPosition");
        int texLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        int mtxLoc = GLES20.glGetUniformLocation(program, "uMatrix");
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(texLoc);
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glUniformMatrix4fv(mtxLoc, 1, false, matrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(isOes ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D, texId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void releaseGl() {
        if (videoDecoder != null) {
            videoDecoder.detachFromGLContext();
        }
        if (videoTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{videoTextureId}, 0);
            videoTextureId = 0;
        }
        if (imageTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{imageTextureId}, 0);
            imageTextureId = 0;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
        }
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
}
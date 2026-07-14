package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.pedro.encoder.input.sources.video.VideoSource;

import java.io.IOException;

/**
 * Fake scene / scene tambahan: menampilkan GAMBAR STATIS (looping) atau VIDEO FILE (looping)
 * ke Surface milik encoder.
 */
public class FakeSceneVideoSource extends VideoSource {

    public enum Mode { STATIC_IMAGE, VIDEO_FILE }

    private final Context context;
    private final Mode mode;
    private final Bitmap staticImage;
    private final Uri videoUri;

    private Surface targetSurface;
    private MediaPlayer mediaPlayer;
    private Thread staticDrawThread;
    private volatile boolean running = false;

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
        if (mode == Mode.STATIC_IMAGE) {
            startStaticImageLoop();
        } else {
            startVideoFileLoop();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (staticDrawThread != null) {
            try {
                staticDrawThread.join(300);
            } catch (InterruptedException ignored) {}
            staticDrawThread = null;
        }
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
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

    private void startStaticImageLoop() {
        staticDrawThread = new Thread(() -> {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            while (running && targetSurface != null && targetSurface.isValid()) {
                Canvas canvas = null;
                try {
                    canvas = targetSurface.lockCanvas(null);
                    if (canvas != null) {
                        int cW = canvas.getWidth();
                        int cH = canvas.getHeight();
                        canvas.drawColor(Color.BLACK);
                        if (staticImage != null) {
                            Rect dst = fitCenterRect(staticImage.getWidth(), staticImage.getHeight(), cW, cH);
                            canvas.drawBitmap(staticImage, null, dst, paint);
                        }
                    }
                } finally {
                    if (canvas != null) {
                        targetSurface.unlockCanvasAndPost(canvas);
                    }
                }
                try {
                    Thread.sleep(33);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "FakeSceneVideoSource-image");
        staticDrawThread.start();
    }

    private Rect fitCenterRect(int srcW, int srcH, int dstW, int dstH) {
        float scale = Math.min((float) dstW / srcW, (float) dstH / srcH);
        int w = Math.round(srcW * scale);
        int h = Math.round(srcH * scale);
        int left = (dstW - w) / 2;
        int top = (dstH - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }

    private void startVideoFileLoop() {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(context, videoUri);
            mediaPlayer.setSurface(targetSurface);
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (IOException e) {
            running = false;
        }
    }
}

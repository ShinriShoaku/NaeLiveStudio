package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * REFACTORED: VideoTextureDecoder provides a detached SurfaceTexture for GPU compositing.
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoTextureDecoder {
    private static final String TAG = "VideoDecoder-GPU";

    public interface Listener {
        default void onFrameAvailable() {}
        default void onComplete() {}
        default void onError(Exception e) {}
    }

    private final Context context;
    private final Uri videoUri;
    private final int width, height;
    private final boolean loop;
    private Listener listener;

    private ExoPlayer exoPlayer;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;
    private volatile boolean running = false;
    private boolean isAttached = false;
    private final float[] texMatrix = new float[16];

    public VideoTextureDecoder(Context context, Uri uri, int w, int h, boolean loop, int fps, Listener listener) {
        this.context = context.getApplicationContext();
        this.videoUri = uri;
        this.width = Math.max(2, w);
        this.height = Math.max(2, h);
        this.loop = loop;
        this.listener = listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;

        // Create a detached SurfaceTexture
        surfaceTexture = new SurfaceTexture(0);
        surfaceTexture.detachFromGLContext();
        surfaceTexture.setDefaultBufferSize(width, height);
        inputSurface = new Surface(surfaceTexture);

        surfaceTexture.setOnFrameAvailableListener(st -> {
            if (listener != null && running) listener.onFrameAvailable();
        });

        new Handler(Looper.getMainLooper()).post(() -> {
            if (!running) return;
            try {
                VideoDiskCacheManager cacheManager = VideoDiskCacheManager.getInstance(context);
                exoPlayer = new ExoPlayer.Builder(context)
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheManager.createCacheDataSourceFactory(context)))
                        .build();

                exoPlayer.setTrackSelectionParameters(
                        exoPlayer.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .setMaxVideoSize(width, height)
                                .build()
                );

                exoPlayer.setRepeatMode(loop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                exoPlayer.setVideoSurface(inputSurface);
                exoPlayer.setMediaItem(MediaItem.fromUri(videoUri));
                exoPlayer.setPlayWhenReady(false);

                exoPlayer.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_ENDED && !loop) {
                            if (listener != null) listener.onComplete();
                        }
                    }
                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        if (listener != null) listener.onError(error);
                    }
                });

                exoPlayer.prepare();
            } catch (Exception e) {
                if (listener != null) listener.onError(e);
            }
        });
    }

    public void attachToGLContext(int texId) {
        if (surfaceTexture != null && !isAttached) {
            try {
                surfaceTexture.attachToGLContext(texId);
                isAttached = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to attach SurfaceTexture to GL context", e);
            }
        }
    }

    public void detachFromGLContext() {
        if (surfaceTexture != null && isAttached) {
            try {
                surfaceTexture.detachFromGLContext();
                isAttached = false;
            } catch (Exception e) {
                Log.e(TAG, "Failed to detach SurfaceTexture from GL context", e);
            }
        }
    }

    public void updateTexImage() {
        if (surfaceTexture != null && isAttached) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(texMatrix);
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage failed: " + e.getMessage());
            }
        }
    }

    public float[] getTexMatrix() { return texMatrix; }

    public void setPlayWhenReady(boolean play) {
        if (exoPlayer != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (exoPlayer != null) exoPlayer.setPlayWhenReady(play);
            });
        }
    }

    public void stop() {
        running = false;

        final ExoPlayer player = exoPlayer;
        exoPlayer = null;
        if (player != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // PENTING: kalau stop() ini sendiri sudah dipanggil DARI main thread, jangan
                // post+await ke main thread juga - itu self-deadlock (runnable yang di-post
                // tidak akan pernah jalan selama thread ini masih nunggu di await(), jadi selalu
                // kena timeout penuh). Karena sudah di main thread, langsung release saja.
                releasePlayerSafely(player);
            } else {
                CountDownLatch latch = new CountDownLatch(1);
                new Handler(Looper.getMainLooper()).post(() -> {
                    releasePlayerSafely(player);
                    latch.countDown();
                });
                try { latch.await(800, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            }
        }

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
            surfaceTexture = null;
        }
        isAttached = false;
    }

    private void releasePlayerSafely(ExoPlayer player) {
        try {
            player.stop();
            player.release();
        } catch (Exception e) {
            Log.w(TAG, "Error releasing ExoPlayer", e);
        }
    }
}
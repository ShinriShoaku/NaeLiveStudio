package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

@OptIn(markerClass = UnstableApi.class)
public class ExoVideoDecoder {
    private final Context context;
    private final Uri videoUri;
    private final boolean loop;
    private final VideoTextureDecoder.Listener listener; // Reusing existing listener interface
    
    private ExoPlayer player;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private int oesTextureId;

    public ExoVideoDecoder(Context context, Uri videoUri, boolean loop, VideoTextureDecoder.Listener listener) {
        this.context = context;
        this.videoUri = videoUri;
        this.loop = loop;
        this.listener = listener;
    }

    public void start(int width, int height) {
        new Handler(Looper.getMainLooper()).post(() -> {
            VideoDiskCacheManager cacheManager = VideoDiskCacheManager.getInstance(context);
            
            player = new ExoPlayer.Builder(context)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheManager.createCacheDataSourceFactory(context)))
                    .build();

            player.setRepeatMode(loop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
            player.setMediaItem(MediaItem.fromUri(videoUri));
            player.setVideoSurface(surface);
            player.prepare();
            player.play();
            
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_ENDED && !loop) {
                        if (listener != null) listener.onComplete();
                    }
                }
            });
        });
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
        if (player != null) {
            player.setVideoSurface(surface);
        }
    }

    public void stop() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (player != null) {
                player.release();
                player = null;
            }
        });
    }
}

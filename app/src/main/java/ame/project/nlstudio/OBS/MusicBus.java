package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ame.project.nlstudio.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicBus {
    private static final MusicBus instance = new MusicBus();
    public static MusicBus getInstance() { return instance; }

    private String currentSongJson;
    private String queueJson;
    private volatile boolean queueDirty = false;

    private Bitmap cachedCurrentBitmap;
    private int cachedCurrentW = -1, cachedCurrentH = -1;

    private Bitmap cachedQueueBitmap;
    private int cachedQueueW = -1, cachedQueueH = -1;
    private String lastQueueJsonRendered;

    private View cachedCurrentView;
    private String lastViewJson;
    private float currentRotation = 0f;
    private long lastRenderTime = 0;

    private final Map<String, Bitmap> thumbCache = new HashMap<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private MusicBus() {}

    public synchronized void onTrackChanged(String title, String artist, String duration, String thumbnail) {
        if (title == null || title.isEmpty()) {
            currentSongJson = null;
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("title", title);
            obj.put("channel", artist);
            obj.put("duration", duration);
            obj.put("thumbnail", thumbnail);
            currentSongJson = obj.toString();
            preload(thumbnail);
        } catch (Exception ignored) {}
    }

    public synchronized void onCurrentSongUpdate(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            this.currentSongJson = null;
            return;
        }
        this.currentSongJson = json;
        try {
            JSONObject obj = new JSONObject(json);
            preload(obj.optString("thumbnail"));
        } catch (Exception ignored) {}
    }

    public synchronized void onQueueChanged(String json) {
        if (json == null || json.isEmpty() || json.equals("[]")) {
            this.queueJson = null;
            this.queueDirty = true;
            return;
        }
        this.queueJson = json;
        this.queueDirty = true;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                preload(arr.getJSONObject(i).optString("thumbnail"));
            }
        } catch (Exception ignored) {}
    }

    private void preload(String url) {
        if (url == null || url.isEmpty() || thumbCache.containsKey(url)) return;
        loader.execute(() -> {
            try {
                Bitmap bmp = BitmapFactory.decodeStream(new URL(url).openStream());
                if (bmp != null) {
                    synchronized (thumbCache) {
                        thumbCache.put(url, bmp);
                    }
                }
            } catch (Exception e) {
                Log.e("MusicBus", "Failed to load thumb: " + url);
            }
        });
    }

    public Bitmap renderCurrentSong(Context context, int w, int h) {
        if (w <= 0 || h <= 0 || currentSongJson == null) {
            lastRenderTime = 0;
            return null;
        }

        long now = System.currentTimeMillis();
        if (lastRenderTime > 0) {
            float dt = (now - lastRenderTime) / 1000f;
            currentRotation += dt * 15f; // 15 degrees per second
            if (currentRotation >= 360f) currentRotation -= 360f;
        }
        lastRenderTime = now;

        // Note: We bypass the "same JSON" check for the bitmap cache because we want to animate.
        // However, we still reuse the bitmap if size matches.
        if (cachedCurrentBitmap == null || cachedCurrentW != w || cachedCurrentH != h) {
            if (cachedCurrentBitmap != null) cachedCurrentBitmap.recycle();
            cachedCurrentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cachedCurrentW = w;
            cachedCurrentH = h;
        } else {
            cachedCurrentBitmap.eraseColor(Color.TRANSPARENT);
        }

        Canvas canvas = new Canvas(cachedCurrentBitmap);

        try {
            JSONObject obj = new JSONObject(currentSongJson);
            String title = obj.optString("title", "Unknown");
            String channel = obj.optString("channel", obj.optString("artist", ""));
            String reqBy = obj.optString("requestedBy", "");
            String thumbUrl = obj.optString("thumbnail");

            if (cachedCurrentView == null || !currentSongJson.equals(lastViewJson)) {
                Context themeContext = new ContextThemeWrapper(context, R.style.Theme_NLStudio);
                cachedCurrentView = LayoutInflater.from(themeContext).inflate(R.layout.item_music_current, null);
                lastViewJson = currentSongJson;
            }
            
            TextView tvTitle = cachedCurrentView.findViewById(R.id.tv_music_title);
            TextView tvChannel = cachedCurrentView.findViewById(R.id.tv_music_channel);
            TextView tvReq = cachedCurrentView.findViewById(R.id.tv_music_requested);
            ImageView ivThumb = cachedCurrentView.findViewById(R.id.iv_music_thumb);

            if (tvTitle != null) tvTitle.setText(title);
            if (tvChannel != null) tvChannel.setText(channel);
            if (tvReq != null) {
                if (!reqBy.isEmpty()) {
                    tvReq.setVisibility(View.VISIBLE);
                    tvReq.setText("Requested by " + reqBy);
                } else {
                    tvReq.setVisibility(View.GONE);
                }
            }

            if (ivThumb != null) {
                ivThumb.setRotation(currentRotation);
                if (thumbUrl != null) {
                    Bitmap thumb;
                    synchronized (thumbCache) { thumb = thumbCache.get(thumbUrl); }
                    if (thumb != null) {
                        ivThumb.setImageBitmap(thumb);
                    }
                }
            }

            cachedCurrentView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            cachedCurrentView.layout(0, 0, w, h);
            cachedCurrentView.draw(canvas);

        } catch (Exception e) {
            Log.e("MusicBus", "Error rendering current song xml", e);
        }

        return cachedCurrentBitmap;
    }

    public Bitmap renderQueue(Context context, int w, int h) {
        if (w <= 0 || h <= 0 || queueJson == null) return null;

        if (cachedQueueBitmap != null && cachedQueueW == w && cachedQueueH == h && queueJson.equals(lastQueueJsonRendered)) {
            return cachedQueueBitmap;
        }

        if (cachedQueueBitmap == null || cachedQueueW != w || cachedQueueH != h) {
            if (cachedQueueBitmap != null) cachedQueueBitmap.recycle();
            cachedQueueBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cachedQueueW = w;
            cachedQueueH = h;
        } else {
            cachedQueueBitmap.eraseColor(Color.TRANSPARENT);
        }

        Canvas canvas = new Canvas(cachedQueueBitmap);
        lastQueueJsonRendered = queueJson;

        try {
            JSONArray arr = new JSONArray(queueJson);
            Context themeContext = new ContextThemeWrapper(context, R.style.Theme_NLStudio);
            LayoutInflater inflater = LayoutInflater.from(themeContext);
            
            View containerView = inflater.inflate(R.layout.item_music_queue_container, null);
            LinearLayout listContainer = containerView.findViewById(R.id.queue_list_container);

            if (listContainer != null) {
                for (int i = 0; i < Math.min(arr.length(), 8); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    View row = inflater.inflate(R.layout.item_music_queue_row, listContainer, false);
                    
                    TextView tvIdx = row.findViewById(R.id.tv_queue_index);
                    TextView tvTitle = row.findViewById(R.id.tv_queue_title);
                    TextView tvReq = row.findViewById(R.id.tv_queue_requester);

                    if (tvIdx != null) tvIdx.setText((i + 1) + ".");
                    if (tvTitle != null) tvTitle.setText(item.optString("title"));
                    if (tvReq != null) tvReq.setText(item.optString("requestedBy"));
                    
                    listContainer.addView(row);
                }
            }

            containerView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            containerView.layout(0, 0, w, h);
            containerView.draw(canvas);

        } catch (Exception e) {
            Log.e("MusicBus", "Error rendering queue xml", e);
        }

        return cachedQueueBitmap;
    }

    public void reset() {
        synchronized (this) {
            currentSongJson = null;
            queueJson = null;
            queueDirty = true;
            lastQueueJsonRendered = null;
            cachedCurrentView = null;
            lastViewJson = null;
            currentRotation = 0f;
            lastRenderTime = 0;
            if (cachedCurrentBitmap != null) {
                cachedCurrentBitmap.recycle();
                cachedCurrentBitmap = null;
            }
            if (cachedQueueBitmap != null) {
                cachedQueueBitmap.recycle();
                cachedQueueBitmap = null;
            }
        }
        synchronized (thumbCache) {
            thumbCache.clear();
        }
    }

    public synchronized boolean isCurrentSongActive() { return currentSongJson != null; }
    public synchronized boolean isQueueActive() { return queueJson != null; }
    public boolean isQueueDirty() { return queueDirty; }
    public void clearQueueDirty() { queueDirty = false; }
}

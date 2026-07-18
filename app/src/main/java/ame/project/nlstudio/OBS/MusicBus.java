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

    private Bitmap cachedCurrentBitmap;
    private int cachedCurrentW = -1, cachedCurrentH = -1;
    private String lastCurrentJsonRendered;

    private Bitmap cachedQueueBitmap;
    private int cachedQueueW = -1, cachedQueueH = -1;
    private String lastQueueJsonRendered;

    private final Map<String, Bitmap> thumbCache = new HashMap<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private MusicBus() {}

    public synchronized void onTrackChanged(String title, String artist, String duration, String thumbnail) {
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
        this.currentSongJson = json;
        try {
            JSONObject obj = new JSONObject(json);
            preload(obj.optString("thumbnail"));
        } catch (Exception ignored) {}
    }

    public synchronized void onQueueChanged(String json) {
        this.queueJson = json;
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
        if (w <= 0 || h <= 0 || currentSongJson == null) return null;

        if (cachedCurrentBitmap != null && cachedCurrentW == w && cachedCurrentH == h && currentSongJson.equals(lastCurrentJsonRendered)) {
            return cachedCurrentBitmap;
        }

        if (cachedCurrentBitmap == null || cachedCurrentW != w || cachedCurrentH != h) {
            if (cachedCurrentBitmap != null) cachedCurrentBitmap.recycle();
            cachedCurrentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cachedCurrentW = w;
            cachedCurrentH = h;
        } else {
            cachedCurrentBitmap.eraseColor(Color.TRANSPARENT);
        }

        Canvas canvas = new Canvas(cachedCurrentBitmap);
        lastCurrentJsonRendered = currentSongJson;

        try {
            JSONObject obj = new JSONObject(currentSongJson);
            String title = obj.optString("title", "Unknown");
            String channel = obj.optString("channel", obj.optString("artist", ""));
            String reqBy = obj.optString("requestedBy", "");
            String thumbUrl = obj.optString("thumbnail");

            Context themeContext = new ContextThemeWrapper(context, R.style.Theme_NLStudio);
            View view = LayoutInflater.from(themeContext).inflate(R.layout.item_music_current, null);
            
            TextView tvTitle = view.findViewById(R.id.tv_music_title);
            TextView tvChannel = view.findViewById(R.id.tv_music_channel);
            TextView tvReq = view.findViewById(R.id.tv_music_requested);
            ImageView ivThumb = view.findViewById(R.id.iv_music_thumb);

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

            if (ivThumb != null && thumbUrl != null) {
                Bitmap thumb;
                synchronized (thumbCache) { thumb = thumbCache.get(thumbUrl); }
                if (thumb != null) {
                    ivThumb.setImageBitmap(thumb);
                }
            }

            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, w, h);
            view.draw(canvas);

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
            lastCurrentJsonRendered = null;
            lastQueueJsonRendered = null;
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
}

package ame.project.nlstudio.OBS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ame.project.nlstudio.R;

/**
 * Singleton yang menampung event live TikTok (chat/gift/join) yang diterima dari IKanaeCallback
 * (AIDL, dari service Kanae), lalu me-render-nya jadi Bitmap overlay yang dipakai
 * CompositeSceneVideoSource buat layer TIKTOK_CHAT / TIKTOK_GIFT / TIKTOK_JOIN.
 *
 * FIX ROOT CAUSE: sebelumnya layer TikTok di jalur RECORDING (StreamService.applyCompositeScene)
 * cuma bitmap placeholder statis ("CHAT"/"GIFT"/"JOIN") yang digambar SEKALI pas scene JSON
 * di-parse, dan CompositeSceneVideoSource.drawLayers() tidak punya kasus khusus buat tipe ini -
 * jadi bitmap statis itu digambar ulang tiap frame tanpa pernah berubah. Data live dari
 * IKanaeCallback (onChatMessage/onGiftMessage/onUserJoined) juga tidak pernah di-hook ke mana pun
 * di StreamService/MainActivity - makanya chat TIDAK PERNAH muncul di hasil record walau koneksi
 * TikTok di service Kanae sendiri normal.
 *
 * Sekarang: StreamService bind ke IKanaeService & implement IKanaeCallback, forward semua event
 * ke singleton ini. CompositeSceneVideoSource.drawLayers() re-render bitmap overlay dari sini
 * TIAP FRAME (mirip pola globalScreenFrame yang dipakai layer SCREEN).
 */
public class TikTokChatBus {

    private static final TikTokChatBus instance = new TikTokChatBus();
    public static TikTokChatBus getInstance() { return instance; }

    private static final int MAX_CHAT_LINES = 6;
    private static final long GIFT_DISPLAY_MS = 4000L;
    private static final long JOIN_DISPLAY_MS = 3000L;

    public static class ChatEntry {
        public final String user;
        public final String message;
        public ChatEntry(String user, String message) {
            this.user = user;
            this.message = message;
        }
    }

    private final Deque<ChatEntry> chatLines = new ArrayDeque<>();
    private final Object chatLock = new Object();
    private volatile boolean chatDirty = true;
    private final Object giftLock = new Object();
    private final Object joinLock = new Object();

    private volatile String lastGiftUser;
    private volatile String lastGiftName;
    private volatile String lastGiftUrl;
    private volatile int lastGiftCount;
    private volatile long lastGiftTimestamp = 0L;

    private volatile String lastJoinUser;
    private volatile String lastJoinProfileUrl;
    private volatile long lastJoinTimestamp = 0L;

    private final Map<String, Bitmap> imageCache = new HashMap<>();
    private final ExecutorService imageLoaderExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean connected = false;
    private volatile String connectedUsername;

    // Cache bitmap chat supaya tidak re-draw canvas kalau isinya belum berubah (dipanggil ~30x/detik).
    //
    // FIX RACE CONDITION: sebelumnya cuma ada 1 bitmap per overlay (misal cachedChatBitmap).
    // renderXOverlay() dipanggil dari render thread (Composite-GPU-Thread), yang men-trigger
    // eraseColor() lalu Handler.post() ke MAIN thread untuk benar2 menggambar (LayoutInflater/View
    // wajib di UI thread) - lalu method langsung return referensi bitmap yang SAMA itu ke pemanggil
    // TANPA menunggu Runnable di main thread selesai. Render thread lalu langsung meng-upload
    // bitmap itu ke GPU texture (GLUtils.texImage2D) di frame yang sama. Karena tidak ada
    // sinkronisasi antara "main thread menggambar ke bitmap X" dan "render thread membaca pixel
    // bitmap X", hasilnya bisa berupa frame kosong/transparan (abis eraseColor, belum digambar) atau
    // konten tergores (partial draw) yang ke-capture ke output stream -> flicker teks chat/gift/join.
    //
    // Sekarang pakai double-buffer: main thread SELALU menggambar ke buffer belakang ("back"),
    // dan index "front" (yang dibaca render thread) baru di-swap setelah gambar selesai, di dalam
    // synchronized block yang sama dipakai render thread utk membaca. Render thread jadi selalu
    // dapat bitmap yang lengkap - kalaupun agak "telat" 1 frame, tidak pernah setengah-gambar.
    private final Bitmap[] chatBitmaps = new Bitmap[2];
    private int chatFrontIndex = 0;
    private int cachedChatW = -1, cachedChatH = -1;

    private final Bitmap[] giftBitmaps = new Bitmap[2];
    private int giftFrontIndex = 0;
    private int cachedGiftW = -1, cachedGiftH = -1;
    private long lastGiftRenderedTs = -1;

    private final Bitmap[] joinBitmaps = new Bitmap[2];
    private int joinFrontIndex = 0;
    private int cachedJoinW = -1, cachedJoinH = -1;
    private long lastJoinRenderedTs = -1;

    private TikTokChatBus() {}

    // ==== Dipanggil dari implementasi IKanaeCallback di StreamService (thread binder) ====

    public void onChatMessage(String user, String message) {
        synchronized (chatLock) {
            chatLines.addLast(new ChatEntry(user, message));
            while (chatLines.size() > MAX_CHAT_LINES) chatLines.removeFirst();
            chatDirty = true;
        }
    }

    public void onGiftMessage(String user, String gift, String giftUrl, int count) {
        lastGiftUser = user;
        lastGiftName = gift;
        lastGiftUrl = giftUrl;
        lastGiftCount = count;
        lastGiftTimestamp = System.currentTimeMillis();
        preloadImage(giftUrl);
    }

    public void onUserJoined(String user, String profileUrl) {
        lastJoinUser = user;
        lastJoinProfileUrl = profileUrl;
        lastJoinTimestamp = System.currentTimeMillis();
        preloadImage(profileUrl);
    }

    public void onUserLiked(String user, String profileUrl, int count) {
        // Implementasi opsional, bisa diarahkan ke gift atau join overlay jika mau
        preloadImage(profileUrl);
    }

    public void onUserFollowed(String user, String profileUrl) {
        preloadImage(profileUrl);
    }

    public void onUserShared(String user, String profileUrl) {
        preloadImage(profileUrl);
    }

    private void preloadImage(String url) {
        if (url == null || url.isEmpty() || imageCache.containsKey(url)) return;
        imageLoaderExecutor.execute(() -> {
            try {
                Bitmap bmp = android.graphics.BitmapFactory.decodeStream(new URL(url).openStream());
                if (bmp != null) {
                    synchronized (imageCache) {
                        imageCache.put(url, bmp);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("TikTokChatBus", "Failed to load image: " + url, e);
            }
        });
    }

    private Bitmap getCachedImage(String url) {
        if (url == null) return null;
        synchronized (imageCache) {
            return imageCache.get(url);
        }
    }

    public void setConnectionStatus(boolean connected, String username) {
        this.connected = connected;
        this.connectedUsername = username;
    }

    public boolean isConnected() { return connected; }
    public String getConnectedUsername() { return connectedUsername; }

    /** Panggil saat live/record dimulai atau dihentikan supaya overlay tidak nyisa data sesi lama. */
    public void reset() {
        synchronized (chatLock) {
            chatLines.clear();
            chatDirty = true;
            for (int i = 0; i < chatBitmaps.length; i++) {
                if (chatBitmaps[i] != null && !chatBitmaps[i].isRecycled()) chatBitmaps[i].recycle();
                chatBitmaps[i] = null;
            }
            cachedChatW = -1;
            cachedChatH = -1;
            chatFrontIndex = 0;
        }
        synchronized (giftLock) {
            lastGiftUser = null;
            lastGiftTimestamp = 0L;
            lastGiftRenderedTs = -1;
            for (int i = 0; i < giftBitmaps.length; i++) {
                if (giftBitmaps[i] != null && !giftBitmaps[i].isRecycled()) giftBitmaps[i].recycle();
                giftBitmaps[i] = null;
            }
            cachedGiftW = -1;
            cachedGiftH = -1;
            giftFrontIndex = 0;
        }
        synchronized (joinLock) {
            lastJoinUser = null;
            lastJoinTimestamp = 0L;
            lastJoinRenderedTs = -1;
            for (int i = 0; i < joinBitmaps.length; i++) {
                if (joinBitmaps[i] != null && !joinBitmaps[i].isRecycled()) joinBitmaps[i].recycle();
                joinBitmaps[i] = null;
            }
            cachedJoinW = -1;
            cachedJoinH = -1;
            joinFrontIndex = 0;
        }
    }

    // ==== Dipanggil dari CompositeSceneVideoSource.drawLayers(), TIAP FRAME draw-loop ====

    /** Render (atau ambil dari cache kalau belum ada chat baru) daftar chat terbaru, ukuran w x h. */
    public Bitmap renderChatOverlay(Context context, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        List<ChatEntry> snapshot;
        synchronized (chatLock) {
            Bitmap front = chatBitmaps[chatFrontIndex];
            if (!chatDirty && front != null && !front.isRecycled() && cachedChatW == w && cachedChatH == h) {
                return front;
            }
            snapshot = new ArrayList<>(chatLines);
            chatDirty = false;
        }

        // Gambar ke buffer BELAKANG (bukan yang lagi dibaca render thread), supaya render thread
        // yang mungkin lagi baca "front" tidak pernah lihat frame setengah-gambar.
        int backIndex = 1 - chatFrontIndex;
        boolean sizeChanged = (cachedChatW != w || cachedChatH != h);
        Bitmap backBitmap = chatBitmaps[backIndex];
        if (backBitmap == null || backBitmap.isRecycled() || sizeChanged) {
            if (backBitmap != null && !backBitmap.isRecycled()) backBitmap.recycle();
            backBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            chatBitmaps[backIndex] = backBitmap;
        } else {
            backBitmap.eraseColor(Color.TRANSPARENT);
        }

        final Bitmap targetBitmap = backBitmap;
        final int targetW = w;
        final int targetH = h;
        final int targetBackIndex = backIndex;

        // FIX: Rendering views to bitmap HARUS di UI thread untuk menghindari Resources$NotFoundException
        // (ResourcesOffloading) dan race condition pada LayoutInflater/View drawing di Android 13+.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (targetBitmap.isRecycled()) return;
            Canvas canvas = new Canvas(targetBitmap);
            Context themeContext = new ContextThemeWrapper(context, R.style.Theme_NLStudio);

            // Root container untuk menampung bubble chat
            LinearLayout container = new LinearLayout(themeContext);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.BOTTOM);
            container.setLayoutParams(new ViewGroup.LayoutParams(targetW, targetH));

            LayoutInflater inflater = LayoutInflater.from(themeContext);
            for (ChatEntry entry : snapshot) {
                View view = inflater.inflate(R.layout.item_chat_bubble_boxed, container, false);
                TextView tvUser = view.findViewById(R.id.tv_username);
                TextView tvMsg = view.findViewById(R.id.tv_message);
                if (tvUser != null) tvUser.setText(entry.user);
                if (tvMsg != null) tvMsg.setText(entry.message);
                container.addView(view);
            }

            container.measure(View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.EXACTLY));
            container.layout(0, 0, targetW, targetH);
            container.draw(canvas);

            // Gambar selesai - baru sekarang buffer ini "sah" jadi front, di-swap di dalam lock
            // yang sama dipakai render thread supaya tidak pernah kebaca separuh-jadi.
            synchronized (chatLock) {
                cachedChatW = targetW;
                cachedChatH = targetH;
                chatFrontIndex = targetBackIndex;
            }
        });

        // Kembalikan front yang masih valid (boleh 1 frame "telat", tapi selalu lengkap/utuh).
        synchronized (chatLock) {
            Bitmap front = chatBitmaps[chatFrontIndex];
            return (front != null && !front.isRecycled()) ? front : null;
        }
    }

    /** Overlay gift, cuma tampil GIFT_DISPLAY_MS sejak gift terakhir masuk. Null kalau lagi tidak ada / sudah expired. */
    public Bitmap renderGiftOverlay(Context context, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        if (lastGiftUser == null) return null;
        if (System.currentTimeMillis() - lastGiftTimestamp > GIFT_DISPLAY_MS) return null;

        synchronized (giftLock) {
            Bitmap front = giftBitmaps[giftFrontIndex];
            if (front != null && !front.isRecycled() && cachedGiftW == w && cachedGiftH == h
                    && lastGiftRenderedTs == lastGiftTimestamp) {
                return front;
            }
        }

        int backIndex = 1 - giftFrontIndex;
        boolean sizeChanged = (cachedGiftW != w || cachedGiftH != h);
        Bitmap backBitmap = giftBitmaps[backIndex];
        if (backBitmap == null || backBitmap.isRecycled() || sizeChanged) {
            if (backBitmap != null && !backBitmap.isRecycled()) backBitmap.recycle();
            backBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            giftBitmaps[backIndex] = backBitmap;
        } else {
            backBitmap.eraseColor(Color.TRANSPARENT);
        }

        final Bitmap targetBitmap = backBitmap;
        final int targetW = w;
        final int targetH = h;
        final int targetBackIndex = backIndex;
        final long timestamp = lastGiftTimestamp;
        final String user = lastGiftUser;
        final String giftName = lastGiftName;
        final int giftCount = lastGiftCount;
        final String giftUrl = lastGiftUrl;

        lastGiftRenderedTs = timestamp;

        new Handler(Looper.getMainLooper()).post(() -> {
            if (targetBitmap.isRecycled()) return;
            Canvas canvas = new Canvas(targetBitmap);
            Context themeContext = new ContextThemeWrapper(context, R.style.Theme_NLStudio);
            View view = LayoutInflater.from(themeContext).inflate(R.layout.overlay_tiktok_notification, null);
            TextView tvUser = view.findViewById(R.id.tiktok_notif_user);
            TextView tvAction = view.findViewById(R.id.tiktok_notif_action);
            ImageView ivGift = view.findViewById(R.id.tiktok_notif_image);

            if (tvUser != null) tvUser.setText(user);
            if (tvAction != null) tvAction.setText(giftName + " x" + giftCount);
            if (ivGift != null) {
                Bitmap giftBmp = getCachedImage(giftUrl);
                if (giftBmp != null) {
                    ivGift.setImageBitmap(giftBmp);
                } else {
                    int resId = context.getResources().getIdentifier("kanae", "drawable", context.getPackageName());
                    if (resId != 0) {
                        ivGift.setImageResource(resId);
                    } else {
                        ivGift.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                }
            }

            view.measure(View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, targetW, targetH);
            view.draw(canvas);

            synchronized (giftLock) {
                cachedGiftW = targetW;
                cachedGiftH = targetH;
                giftFrontIndex = targetBackIndex;
            }
        });

        synchronized (giftLock) {
            Bitmap front = giftBitmaps[giftFrontIndex];
            return (front != null && !front.isRecycled()) ? front : null;
        }
    }

    /** Overlay join, cuma tampil JOIN_DISPLAY_MS sejak join terakhir masuk. Null kalau lagi tidak ada / sudah expired. */
    public Bitmap renderJoinOverlay(Context context, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        if (lastJoinUser == null) return null;
        if (System.currentTimeMillis() - lastJoinTimestamp > JOIN_DISPLAY_MS) return null;

        synchronized (joinLock) {
            Bitmap front = joinBitmaps[joinFrontIndex];
            if (front != null && !front.isRecycled() && cachedJoinW == w && cachedJoinH == h
                    && lastJoinRenderedTs == lastJoinTimestamp) {
                return front;
            }
        }

        int backIndex = 1 - joinFrontIndex;
        boolean sizeChanged = (cachedJoinW != w || cachedJoinH != h);
        Bitmap backBitmap = joinBitmaps[backIndex];
        if (backBitmap == null || backBitmap.isRecycled() || sizeChanged) {
            if (backBitmap != null && !backBitmap.isRecycled()) backBitmap.recycle();
            backBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            joinBitmaps[backIndex] = backBitmap;
        } else {
            backBitmap.eraseColor(Color.TRANSPARENT);
        }

        final Bitmap targetBitmap = backBitmap;
        final int targetW = w;
        final int targetH = h;
        final int targetBackIndex = backIndex;
        final long timestamp = lastJoinTimestamp;
        final String user = lastJoinUser;
        final String profileUrl = lastJoinProfileUrl;

        lastJoinRenderedTs = timestamp;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (targetBitmap.isRecycled()) return;
                Canvas canvas = new Canvas(targetBitmap);
                Context themeContext = new ContextThemeWrapper(context, R.style.Theme_NLStudio);
                View view = LayoutInflater.from(themeContext).inflate(R.layout.overlay_tiktok_join, null);
                TextView tvUser = view.findViewById(R.id.join_user_text);
                ImageView ivJoin = view.findViewById(R.id.join_user_image);

                if (tvUser != null) {
                    tvUser.setText(user + " joined");
                } else {
                    android.util.Log.e("TikTokChatBus", "TextView join_user_text NOT FOUND in layout!");
                }

                if (ivJoin != null) {
                    Bitmap profileBmp = getCachedImage(profileUrl);
                    if (profileBmp != null) {
                        ivJoin.setImageBitmap(profileBmp);
                    } else {
                        int resId = context.getResources().getIdentifier("kanae", "drawable", context.getPackageName());
                        if (resId != 0) {
                            ivJoin.setImageResource(resId);
                        } else {
                            ivJoin.setImageResource(android.R.drawable.ic_menu_gallery);
                        }
                    }
                }

                view.measure(View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, targetW, targetH);
                view.draw(canvas);

                synchronized (joinLock) {
                    cachedJoinW = targetW;
                    cachedJoinH = targetH;
                    joinFrontIndex = targetBackIndex;
                }

                android.util.Log.d("TikTokChatBus", "Join Overlay drawn successfully");
            } catch (Exception e) {
                android.util.Log.e("TikTokChatBus", "Error inflating/drawing join overlay", e);
            }
        });

        synchronized (joinLock) {
            Bitmap front = joinBitmaps[joinFrontIndex];
            return (front != null && !front.isRecycled()) ? front : null;
        }
    }
}
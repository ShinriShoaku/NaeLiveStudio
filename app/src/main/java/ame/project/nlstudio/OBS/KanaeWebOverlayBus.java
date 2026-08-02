package ame.project.nlstudio.OBS;

import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KanaeWebOverlayBus Optimized v6.
 * Mencegah video membeku dengan JS Heartbeat dan Visibility Force.
 */
public class KanaeWebOverlayBus {

    private static final String TAG = "KanaeWebOverlayBus";
    private static final int MAX_ENTRIES = 3; // Kurangi jumlah untuk stabilitas GPU PowerVR
    private static KanaeWebOverlayBus instance;

    public static synchronized KanaeWebOverlayBus getInstance() {
        if (instance == null) instance = new KanaeWebOverlayBus();
        return instance;
    }

    public static class Entry {
        public String id;
        public String url;
        public int w, h;
        public long lastUsedTime;
        
        public WebView webView;
        public SurfaceTexture surfaceTexture;
        public Surface surface;
        public VirtualDisplay virtualDisplay;
        public WebPresentation presentation;
        
        public final float[] texMatrix = new float[16];
        
        public volatile boolean isReady = false;
        public volatile boolean isSurfaceReady = false;
        public boolean isReleased = false;
        public volatile boolean hasFrame = false;
        public boolean isAttachedToGl = false;
        public int currentTexId = -1;

        public Entry() {
            android.opengl.Matrix.setIdentityM(texMatrix, 0);
        }

        public void updateTexture(int texId) {
            if (isReleased) return;
            lastUsedTime = System.currentTimeMillis();
            
            if (!isAttachedToGl) {
                if (surfaceTexture != null) {
                    try {
                        surfaceTexture.attachToGLContext(texId);
                        isAttachedToGl = true;
                        currentTexId = texId;
                    } catch (Exception e) {
                        Log.e(TAG, "Attach failed, re-creating ST: " + e.getMessage());
                        // Fallback: Re-create ST on GL thread if attach fails
                        surfaceTexture.release();
                        surfaceTexture = new SurfaceTexture(texId);
                        surfaceTexture.setDefaultBufferSize(w, h);
                        surfaceTexture.setOnFrameAvailableListener(st -> hasFrame = true);
                        isAttachedToGl = true;
                        currentTexId = texId;
                        final Surface newS = new Surface(surfaceTexture);
                        MAIN_HANDLER.post(() -> {
                            surface = newS;
                            if (virtualDisplay != null) virtualDisplay.setSurface(newS);
                        });
                    }
                }
            } else if (currentTexId != texId) {
                try {
                    surfaceTexture.detachFromGLContext();
                    surfaceTexture.attachToGLContext(texId);
                    currentTexId = texId;
                } catch (Exception ignored) {}
            }
            
            if (hasFrame && surfaceTexture != null) {
                try {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(texMatrix);
                    hasFrame = false;
                } catch (Exception ignored) {}
            }
        }
    }

    private static class WebPresentation extends Presentation {
        private final WebView webView;
        private final int w, h;

        public WebPresentation(Context context, Display display, WebView webView, int w, int h) {
            super(context, display);
            this.webView = webView;
            this.w = w;
            this.h = h;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            FrameLayout root = new FrameLayout(getContext());
            root.addView(webView, new FrameLayout.LayoutParams(w, h));
            setContentView(root);
            
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private final Map<String, Entry> entries = new HashMap<>();

    public void prewarm(Context context, String id, String url, int w, int h) {
        MAIN_HANDLER.post(() -> getOrLoadEntry(context, id, url, w, h));
    }

    public Entry getOrLoadEntry(final Context context, final String id, final String url, final int w, final int h) {
        if (url == null || url.isEmpty()) return null;

        synchronized (entries) {
            Entry e = entries.get(id);
            if (e != null) {
                if (e.url.equals(url) && Math.abs(e.w - w) < 20 && Math.abs(e.h - h) < 20) {
                    e.isReleased = false;
                    e.lastUsedTime = System.currentTimeMillis();
                    return e;
                }
                final Entry old = e;
                MAIN_HANDLER.post(() -> releaseInternal(old));
            }

            if (entries.size() >= MAX_ENTRIES) {
                Entry oldest = null;
                for (Entry entry : entries.values()) {
                    if (oldest == null || entry.lastUsedTime < oldest.lastUsedTime) oldest = entry;
                }
                if (oldest != null) {
                    final Entry toPrune = entries.remove(oldest.id);
                    MAIN_HANDLER.post(() -> releaseInternal(toPrune));
                }
            }

            Entry entry = new Entry();
            entry.id = id; entry.url = url;
            entry.w = Math.max(1, w); entry.h = Math.max(1, h);
            entry.lastUsedTime = System.currentTimeMillis();
            
            entry.surfaceTexture = new SurfaceTexture(0);
            entry.surfaceTexture.detachFromGLContext();
            entry.surfaceTexture.setDefaultBufferSize(entry.w, entry.h);
            entry.surfaceTexture.setOnFrameAvailableListener(st -> entry.hasFrame = true);
            entry.surface = new Surface(entry.surfaceTexture);

            entries.put(id, entry);
            MAIN_HANDLER.post(() -> setupHardwareOverlay(context, entry));
            return entry;
        }
    }

    private void setupHardwareOverlay(Context context, Entry entry) {
        if (OverlayHostActivity.getInstance() == null) {
            Intent intent = new Intent(context, OverlayHostActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(intent);
            MAIN_HANDLER.postDelayed(() -> setupHardwareOverlay(context, entry), 300);
            return;
        }

        Context hostContext = OverlayHostActivity.getInstance();
        entry.webView = new WebView(hostContext);
        entry.webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
        WebSettings s = entry.webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setOffscreenPreRaster(true);
        
        entry.webView.setWebChromeClient(new WebChromeClient());
        entry.webView.setBackgroundColor(0);
        entry.webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Heartbeat JS: Paksa DOM berubah tiap 100ms agar renderer display virtual tetap aktif
                String js = "document.body.style.background='transparent'; " +
                           "document.documentElement.style.background='transparent'; " +
                           "Object.defineProperty(document, 'visibilityState', {get: function() { return 'visible'; }}); " +
                           "Object.defineProperty(document, 'hidden', {get: function() { return false; }}); " +
                           "setInterval(function() { document.body.style.opacity = (document.body.style.opacity == '1' ? '0.999' : '1'); }, 100); " +
                           "document.dispatchEvent(new Event('visibilitychange'));";
                view.evaluateJavascript(js, null);
                entry.isReady = true;
            }
        });

        DisplayManager dm = (DisplayManager) hostContext.getSystemService(Context.DISPLAY_SERVICE);
        try {
            entry.virtualDisplay = dm.createVirtualDisplay("WebOverlay-" + entry.id,
                    entry.w, entry.h, DisplayMetrics.DENSITY_DEFAULT,
                    entry.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);

            entry.presentation = new WebPresentation(hostContext, entry.virtualDisplay.getDisplay(), entry.webView, entry.w, entry.h);
            entry.presentation.show();

            entry.webView.loadUrl(entry.url);
            entry.webView.onResume();
            entry.isSurfaceReady = true;
        } catch (Exception e) {
            Log.e(TAG, "VirtualDisplay failed", e);
        }
    }

    private void releaseInternal(Entry entry) {
        if (entry == null) return;
        entry.isReleased = true;
        entry.isSurfaceReady = false;
        if (entry.webView != null) {
            entry.webView.onPause();
            entry.webView.stopLoading();
            if (entry.webView.getParent() instanceof ViewGroup) ((ViewGroup) entry.webView.getParent()).removeView(entry.webView);
            entry.webView.destroy();
            entry.webView = null;
        }
        if (entry.presentation != null) { try { entry.presentation.dismiss(); } catch (Exception ignored) {} entry.presentation = null; }
        if (entry.virtualDisplay != null) { try { entry.virtualDisplay.release(); } catch (Exception ignored) {} entry.virtualDisplay = null; }
        if (entry.surface != null) { try { entry.surface.release(); } catch (Exception ignored) {} entry.surface = null; }
        if (entry.surfaceTexture != null) { try { entry.surfaceTexture.release(); } catch (Exception ignored) {} entry.surfaceTexture = null; }
    }

    public void releaseAll() {
        MAIN_HANDLER.post(() -> {
            synchronized (entries) {
                for (Entry e : new ArrayList<>(entries.values())) releaseInternal(e);
                entries.clear();
            }
        });
    }
}

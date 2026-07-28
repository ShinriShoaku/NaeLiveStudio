package ame.project.nlstudio.OBS;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * A transparent, off-screen Activity used to provide a stable window context
 * for hosting WebViews used as OBS overlays.
 */
public class OverlayHostActivity extends Activity {

    private static OverlayHostActivity instance;
    private FrameLayout rootLayout;

    public static OverlayHostActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        // Make activity transparent and non-interactive
        Window window = getWindow();
        window.setBackgroundDrawable(null);
        window.setDimAmount(0);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        // Posisi di pojok kiri atas, ukuran 1x1 agar tetap dianggap "visible" oleh sistem
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0; 
        lp.y = 0;
        lp.width = 1;  
        lp.height = 1;
        lp.alpha = 0.02f; // Hampir transparan tapi tidak 0
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.TRANSPARENT);
        setContentView(rootLayout);
    }

    public FrameLayout getRootLayout() {
        return rootLayout;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }
}

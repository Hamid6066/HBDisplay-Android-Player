package ir.hbintellisys.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Network-independent maintenance screen.
 * Must be explicitly started by an authorized on-device operator.
 * This activity does not install APKs, stop the legacy player, or contact a server.
 */
public final class MaintenanceActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101C2A);
        root.setPadding(36, 24, 36, 24);
        addLine(root, "سامانه نمایشگر برج ستاره", 34, 0xFFFFFFFF);
        addLine(root, "در حال به‌روزرسانی", 29, 0xFFE5C27A);
        addLine(root, "سامانه موقتاً در دسترس نیست", 21, 0xFFD8E0E8);
        addLine(root, "لطفاً شکیبا باشید", 19, 0xFFB0BFCE);
        setContentView(root);
    }

    private void addLine(LinearLayout root, String value, float sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, 12, 0, 12);
        root.addView(text);
    }
}

package com.portalroomos;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A small "OS" chip floating over whatever is on screen, so the dashboard is one
 * tap away from the Portal's own launcher.
 *
 * The stock launcher lists only Facebook's apps and cannot be given an icon, and
 * the device has no home key, notification shade or recents switcher, so without
 * this there is no way back into Room OS once you leave it. A system overlay is
 * the one mechanism Android offers a third-party app to put a control on top of
 * someone else's screen. It needs SYSTEM_ALERT_WINDOW, which a sideloaded app
 * cannot request with a dialog here; grant it once over adb:
 *
 *   adb shell appops set com.portalroomos SYSTEM_ALERT_WINDOW allow
 *
 * Without the grant this quietly does nothing rather than crashing.
 */
final class RoomOsOverlay {

    private static final String TAG = "RoomOsOverlay";

    private final Context context;
    private WindowManager windows;
    private View chip;

    RoomOsOverlay(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Puts the chip on screen. Safe to call repeatedly. */
    void show() {
        if (chip != null) return;
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "no draw-over-other-apps permission; the OS chip stays hidden");
            return;
        }
        try {
            TextView view = new TextView(context);
            view.setText("OS");
            view.setTextSize(17);
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
            view.setTextColor(context.getColor(R.color.accent));
            view.setBackground(context.getDrawable(R.drawable.chip_bg));
            view.setGravity(Gravity.CENTER);
            float density = context.getResources().getDisplayMetrics().density;
            view.setMinWidth((int) (64 * density));
            view.setMinHeight((int) (44 * density));
            view.setOnClickListener(v -> {
                Intent open = new Intent(context, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                context.startActivity(open);
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not focusable and not touch-modal, so every touch outside the
                // chip still reaches the app underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            // Clear of the stock launcher's account avatar in the corner.
            lp.x = (int) (96 * density);
            lp.y = (int) (14 * density);

            windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windows == null) return;
            windows.addView(view, lp);
            chip = view;
            Log.i(TAG, "OS chip shown");
        } catch (Exception e) {
            Log.w(TAG, "could not show the OS chip", e);
            chip = null;
        }
    }

    /** Takes it down, e.g. while Room OS itself is the thing on screen. */
    void hide() {
        if (chip == null || windows == null) return;
        try {
            windows.removeView(chip);
        } catch (Exception ignored) {
        }
        chip = null;
    }
}

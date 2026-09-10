package com.portalroomos;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Tracks whether any of our own screens is in front, so the floating OS chip can
 * take itself down while the dashboard is already showing and come back when the
 * Portal's launcher or anything else is on top.
 */
public class RoomOsApp extends Application {

    private static int resumed = 0;
    private static Runnable listener;

    static boolean inForeground() { return resumed > 0; }

    /** Called whenever that answer changes. */
    static void onForegroundChange(Runnable r) {
        listener = r;
        if (r != null) r.run();
    }

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                resumed++;
                if (listener != null) listener.run();
            }

            @Override public void onActivityPaused(Activity a) {
                resumed = Math.max(0, resumed - 1);
                if (listener != null) listener.run();
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}

package com.portalroomos;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks whether any of our own screens is in front, and which, so the floating
 * chips can take themselves down while Room OS is already showing and come back
 * when the Portal's launcher or anything else is on top.
 *
 * Also owns the DIAL responder that makes the Portal a YouTube cast target. It
 * belongs to the process, not a screen: the speaker service keeps the process
 * alive from boot, so the Portal answers a phone's search whether or not anyone
 * has opened a screen since. The agents' watch and their floating chip belong to
 * the process for the same reason: an agent finishing pings the room whether or
 * not a screen is open.
 */
public class RoomOsApp extends Application {

    private static int resumed = 0;
    private static Activity front;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private DialServer dial;
    private AgentsOverlay agentsOverlay;

    static boolean inForeground() { return resumed > 0; }

    /** Whether this screen of ours is the one in front. */
    static boolean showing(Class<? extends Activity> screen) {
        return front != null && screen.isInstance(front);
    }

    /** Called now, and whenever what is in front changes. */
    static void addForegroundListener(Runnable r) {
        listeners.add(r);
        r.run();
    }

    static void removeForegroundListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        for (Runnable r : listeners) r.run();
    }

    @Override public void onCreate() {
        super.onCreate();
        dial = new DialServer(this);
        dial.start();
        AgentWatch.shared().start(this);
        if (AgentVoice.shared(this).enabled()) AgentVoice.shared(this).warm();
        agentsOverlay = new AgentsOverlay(this);
        agentsOverlay.attach();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                resumed++;
                front = a;
                changed();
            }

            @Override public void onActivityPaused(Activity a) {
                resumed = Math.max(0, resumed - 1);
                if (front == a) front = null;
                changed();
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}

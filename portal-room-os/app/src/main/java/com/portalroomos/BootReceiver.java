package com.portalroomos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Brings the Spotify Connect speaker up at boot, so "Portal" is available even
 * before anyone opens the dashboard. Room OS is not (yet) the launcher, so this
 * is the only thing that runs on a cold start.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i("Librespot", "boot completed: starting LibrespotService");
        Intent svc = new Intent(context, LibrespotService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}

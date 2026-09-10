package com.portalroomos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Brings the room up at boot: the Spotify Connect speaker, so "Portal" is
 * available before anyone touches the screen, and the dashboard itself, so a
 * wall panel that has been unplugged comes back showing the room rather than the
 * stock launcher. Starting an activity from BOOT_COMPLETED is allowed on API 28;
 * the background-start restrictions arrived in API 29.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i("Librespot", "boot completed: starting LibrespotService");
        Intent svc = new Intent(context, LibrespotService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);

        Intent dash = new Intent(context, MainActivity.class);
        dash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(dash);
    }
}

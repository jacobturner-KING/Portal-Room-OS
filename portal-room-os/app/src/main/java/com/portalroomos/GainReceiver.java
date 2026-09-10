package com.portalroomos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Exported hook so the speaker gain can be tuned from adb without a rebuild
 * (the service itself is not exported, so the shell can't start it directly):
 *
 *   adb shell am broadcast -a com.portalroomos.SET_GAIN --ei gain_db 10 \
 *       -n com.portalroomos/.GainReceiver
 */
public class GainReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_GAIN = "com.portalroomos.SET_GAIN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_GAIN.equals(intent.getAction())) return;
        if (!intent.hasExtra(LibrespotService.EXTRA_GAIN_DB)) {
            Log.w("Librespot", "SET_GAIN without gain_db extra");
            return;
        }
        int db = intent.getIntExtra(LibrespotService.EXTRA_GAIN_DB, 0);
        Intent svc = new Intent(context, LibrespotService.class).putExtra(LibrespotService.EXTRA_GAIN_DB, db);
        context.startForegroundService(svc);
    }
}

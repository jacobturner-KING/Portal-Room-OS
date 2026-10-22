package com.portalroomos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Says a line in the agents' voice, from adb, for trying the voice out:
 *
 *   adb shell am broadcast -a com.portalroomos.SAY -n com.portalroomos/.SayReceiver --es text "Hello"
 *
 * Guarded by DUMP in the manifest, like the AppTabs link: only the adb shell may send it.
 */
public class SayReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String text = intent.getStringExtra("text");
        AgentVoice.shared(context).say(text == null ? "This is how your agents will sound." : text);
        setResultData("ok");
    }
}

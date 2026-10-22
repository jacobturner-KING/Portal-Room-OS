package com.portalroomos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * AppTabs on the Mac at the other end of the USB cable links this panel to it:
 * after `adb reverse tcp:4741 tcp:4741`, it sends
 *
 *   adb shell am broadcast -a com.portalroomos.SET_AGENT_SOURCE \
 *     -n com.portalroomos/.AgentSourceReceiver \
 *     --es url http://127.0.0.1:4741 --es token <token> --es name <machine>
 *
 * and `--ez clear true` to unlink. The manifest guards this receiver with the
 * DUMP permission, which the adb shell holds and ordinary apps cannot, so no
 * app on the Portal can point the Agents screen somewhere else. Only loopback
 * addresses are taken: the link is the cable, never the network.
 *
 * Answers with result data "ok", which is how AppTabs knows this build has it.
 */
public class AgentSourceReceiver extends BroadcastReceiver {
    static final String ACTION = "com.portalroomos.SET_AGENT_SOURCE";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (intent.getBooleanExtra("clear", false)) {
            AgentWatch.shared().setLocal(null, null, null);
            Log.i("AgentSource", "AppTabs link cleared");
            setResultData("ok");
            return;
        }
        String url = intent.getStringExtra("url");
        String token = intent.getStringExtra("token");
        String name = intent.getStringExtra("name");
        if (url == null || token == null || token.length() < 32
                || !(url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"))) {
            setResultData("refused: needs a loopback url and a token");
            return;
        }
        AgentWatch.shared().setLocal(url, token, name == null ? "" : name);
        Log.i("AgentSource", "linked to AppTabs on " + name + " at " + url);
        setResultData("ok");
    }
}

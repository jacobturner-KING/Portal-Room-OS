package com.portalroomos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Games on the wall. The games are web pages (Black Lightning is DOSBox in
 * WASM, and a rebuilt engine), so this is a picker and a full-panel WebView:
 * no browser chrome, nothing between the game and the controller.
 *
 * Controllers reach the page through the Gamepad API, which Chromium feeds
 * from the key and motion events this activity dispatches. That is where a
 * PS5 pad gets its buttons corrected (see {@link DualSense}), and where every
 * controller key is kept from falling through to Android: an unhandled
 * gamepad B turns into Back and Home into the launcher, which would drop you
 * out of the game on a jump. The PS / Home button is taken instead as "back
 * to the picker".
 *
 * Leaving for the dashboard pauses the game, audio included, and coming back
 * picks it up where it was. Only the picker's own exit unloads it.
 */
public class GamesActivity extends Activity implements InputManager.InputDeviceListener {

    private static final String TAG = "Games";
    static final String SITE = "https://duke-nukem-1.jacob-c6f.workers.dev/";
    private static final String CONTROLLER_TEST = "controller-test";

    private static final class Game {
        final String title, blurb, url;
        Game(String title, String blurb, String url) {
            this.title = title; this.blurb = blurb; this.url = url;
        }
    }

    // ?autostart skips js-dos's play button; the page otherwise waits for a tap
    // on touch screens, for iOS's sake.
    private static final Game[] GAMES = {
        new Game("Black Lightning", "The 1991 original, running in DOSBox", SITE + "?autostart"),
        new Game("Remaster", "The rebuilt engine: HD art, CRT, save and load", SITE + "remaster"),
        new Game("Black Lightning 3D", "First person · needs a controller", SITE + "fps3d.html"),
        new Game("Controller test", "Every button, as the games see it", CONTROLLER_TEST),
    };

    private InputManager input;
    private View picker, exitChip;
    private TextView padText;
    private WebView web;
    private boolean playing;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_games);
        hideSystemUi();

        input = (InputManager) getSystemService(INPUT_SERVICE);
        picker = findViewById(R.id.picker);
        padText = findViewById(R.id.padText);
        exitChip = findViewById(R.id.exitChip);
        exitChip.setOnClickListener(v -> closeGame());
        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.pairBtn).setOnClickListener(v -> openBluetooth());
        buildCards();

        web = findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                      // saves and settings
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);      // sound from the first frame, no tap needed
        s.setSupportZoom(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                v.evaluateJavascript(AUDIO_JS, null);
            }

            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(AUDIO_JS, null);   // in case the early one hit the old document
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "console: " + m.message());
                return true;
            }
        });
    }

    private void buildCards() {
        LinearLayout row = findViewById(R.id.gameRow);
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < GAMES.length; i++) {
            Game g = GAMES[i];
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setBackgroundResource(R.drawable.card_bg);
            card.setPadding((int) (20 * dp), (int) (16 * dp), (int) (20 * dp), (int) (16 * dp));
            card.setFocusable(true);
            card.setClickable(true);
            card.setOnClickListener(v -> openGame(g));

            TextView title = new TextView(this);
            title.setText(g.title);
            title.setTextColor(getColor(R.color.text));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView blurb = new TextView(this);
            blurb.setText(g.blurb);
            blurb.setTextColor(getColor(R.color.muted));
            blurb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            blurb.setGravity(Gravity.CENTER);
            blurb.setPadding(0, (int) (8 * dp), 0, 0);
            card.addView(blurb);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) lp.setMarginStart((int) (12 * dp));
            row.addView(card, lp);
        }
        row.getChildAt(0).requestFocus();
    }

    private void openGame(Game g) {
        Log.i(TAG, "opening " + g.title);
        if (CONTROLLER_TEST.equals(g.url)) {
            web.loadDataWithBaseURL(SITE, CONTROLLER_TEST_HTML, "text/html", "utf-8", null);
        } else {
            web.loadUrl(g.url);
        }
        playing = true;
        picker.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        exitChip.setVisibility(View.VISIBLE);
        web.onResume();
        web.requestFocus();
    }

    private void closeGame() {
        if (!playing) return;
        Log.i(TAG, "back to the picker");
        playing = false;
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        exitChip.setVisibility(View.GONE);
        picker.setVisibility(View.VISIBLE);
        LinearLayout row = findViewById(R.id.gameRow);
        row.getChildAt(0).requestFocus();
    }

    private void openBluetooth() {
        // The stock Android pairing screen survives on the Portal, reachable only
        // by name; the generic action is the fallback.
        Intent i = new Intent().setClassName("com.android.settings",
            "com.android.settings.Settings$BluetoothSettingsActivity");
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "no stock Bluetooth screen; trying the generic one", e);
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (Exception e2) { padText.setText("No Bluetooth settings screen on this device"); }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isController(event)) return super.dispatchKeyEvent(event);
        KeyEvent e = DualSense.fix(event);
        if (!playing) return super.dispatchKeyEvent(e);   // D-pad and A drive the picker
        if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.getAction() == KeyEvent.ACTION_UP) closeGame();
            return true;
        }
        super.dispatchKeyEvent(e);
        return true;   // never let B become Back mid-game
    }

    private static boolean isController(KeyEvent e) {
        int src = e.getSource();
        return KeyEvent.isGamepadButton(e.getKeyCode())
            || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private List<InputDevice> controllers() {
        List<InputDevice> out = new ArrayList<>();
        for (int id : input.getInputDeviceIds()) {
            InputDevice d = input.getInputDevice(id);
            if (d == null || d.isVirtual()) continue;
            int src = d.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) out.add(d);
        }
        return out;
    }

    private void showControllers() {
        List<InputDevice> pads = controllers();
        if (pads.isEmpty()) {
            padText.setText("No controller · pair one over Bluetooth, or plug in a USB pad");
            return;
        }
        StringBuilder b = new StringBuilder("🎮 ");
        for (int i = 0; i < pads.size(); i++) {
            InputDevice d = pads.get(i);
            if (i > 0) b.append(" · ");
            b.append(d.getName());
            if (DualSense.is(d)) b.append(" (PS5 buttons corrected)");
            Log.i(TAG, String.format("controller: %s %04x:%04x sources=0x%x",
                d.getName(), d.getVendorId(), d.getProductId(), d.getSources()));
        }
        padText.setText(b);
    }

    @Override public void onInputDeviceAdded(int deviceId) { showControllers(); }
    @Override public void onInputDeviceRemoved(int deviceId) { showControllers(); }
    @Override public void onInputDeviceChanged(int deviceId) { showControllers(); }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        input.registerInputDeviceListener(this, null);
        showControllers();
        if (playing) {
            web.onResume();
            web.evaluateJavascript("window.__roomosAudio&&window.__roomosAudio('resume')", null);
        }
    }

    @Override protected void onPause() {
        input.unregisterInputDeviceListener(this);
        if (playing) {
            web.evaluateJavascript("window.__roomosAudio&&window.__roomosAudio('suspend')", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override public void onBackPressed() {
        if (playing) closeGame();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /**
     * Keeps a hand on every AudioContext the page makes, so leaving the screen
     * can silence the game. WebView's onPause stops the frames but not Web
     * Audio. If the page wrapped AudioContext itself first (the DOSBox page
     * does), this wraps the wrapper, and both still see every context.
     */
    private static final String AUDIO_JS =
        "(function(){if(window.__roomosAudio)return;var all=[];"
        + "var O=window.AudioContext||window.webkitAudioContext;"
        + "if(O){var P=function(a){var c=a===undefined?new O():new O(a);all.push(c);return c};"
        + "P.prototype=O.prototype;window.AudioContext=P;if(window.webkitAudioContext)window.webkitAudioContext=P;}"
        + "window.__roomosAudio=function(what){all.forEach(function(c){try{c[what]()}catch(e){}})};})();";

    /**
     * What the games read: the Gamepad API's standard layout, with each button
     * lit while held. Changes also go to the console, and so to logcat, which
     * is how a session checks a new controller without watching the panel.
     */
    private static final String CONTROLLER_TEST_HTML =
        "<!doctype html><meta name=viewport content='width=device-width'>"
        + "<style>body{margin:0;background:#101218;color:#F6F7FB;font:18px sans-serif;padding:70px 40px 20px}"
        + "h1{font-size:26px;margin:0 0 6px}#id{color:#BCC3D1;margin-bottom:22px;min-height:22px}"
        + "#b{display:grid;grid-template-columns:repeat(6,1fr);gap:10px}"
        + ".k{border:1px solid #66FFFFFF;border-radius:14px;padding:14px 8px;text-align:center;background:#1a1f2c}"
        + ".k.on{background:#AEC0FF;color:#101218;font-weight:bold}"
        + "#a{margin-top:20px;color:#BCC3D1;font-family:monospace;font-size:17px}</style>"
        + "<h1>Controller test</h1><div id=id>Press any button to wake the controller</div><div id=b></div><div id=a></div>"
        + "<script>var N=['✕ / A','○ / B','□ / X','△ / Y','L1','R1','L2','R2','Create / Select','Options / Start',"
        + "'L3','R3','D-pad up','D-pad down','D-pad left','D-pad right','PS / Home'];"
        + "var B=document.getElementById('b');N.forEach(function(n,i){var d=document.createElement('div');"
        + "d.className='k';d.id='k'+i;d.textContent=n;B.appendChild(d)});var last='';"
        + "setInterval(function(){var g=Array.prototype.filter.call(navigator.getGamepads?navigator.getGamepads():[],Boolean)[0];"
        + "if(!g)return;document.getElementById('id').textContent=g.id+' · mapping: '+(g.mapping||'none');"
        + "var on=[];g.buttons.forEach(function(x,i){var p=x.pressed||x.value>0.5;if(p)on.push(i);"
        + "var el=document.getElementById('k'+i);if(el)el.className='k'+(p?' on':'')});"
        + "var ax=g.axes.map(function(v){return v.toFixed(2)});"
        + "document.getElementById('a').textContent='sticks: '+ax.join('  ');"
        + "var s='pad '+g.id+' buttons ['+on.join(',')+'] axes ['+ax.map(function(v){return Math.round(v)}).join(',')+']';"
        + "if(s!==last){last=s;console.log(s)}},30);</script>";
}

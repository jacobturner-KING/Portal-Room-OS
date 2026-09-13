package com.portalroomos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

/**
 * YouTube on the wall: the same leanback site smart TVs run
 * (youtube.com/tv), in a WebView wearing a TV user agent. The site does the
 * work. A phone reaches it two ways: a DIAL launch from {@link DialServer}
 * lands here with a pairing code in the URL, and the site's own "Link with TV
 * code" (under its Settings) ties this screen to a YouTube account for good, so
 * the Portal is offered in the phone's cast picker even from another network.
 * The Portal has no Google services, so this is the only YouTube it can run.
 *
 * The screen sits behind the dashboard rather than dying with it. Leaving via
 * the OS chip pauses the video and swaps the dashboard to the front; the page
 * keeps its lounge connection, so the phone still sees it. When the phone
 * starts something, the page's video fires a playing event, a small JS bridge
 * hears it, and this screen steps forward again. Spotify playing on this
 * speaker is paused at that moment so the two do not talk over each other.
 * Nothing here calls the WebView's onPause, on purpose: that stops timers and
 * with them the connection the phone is holding.
 *
 * A finished cast leaves the site on its home row, which is not what a wall
 * should show, so after two minutes in front with nothing playing and nobody
 * touching the panel the screen hands back to the dashboard on its own. A
 * phone pressing play brings it straight back.
 *
 * Only one instance ever exists (singleTop, always launched with
 * REORDER_TO_FRONT), so the DIAL launch, the dashboard button and the bridge
 * all land in the same page. A relaunch with a new URL loads it in place.
 */
public class YouTubeActivity extends Activity {

    private static final String TAG = "YouTube";
    static final String TV_URL = "https://www.youtube.com/tv";
    /**
     * What the site keys the TV experience on. A Samsung Tizen string is the one
     * that reliably gets the leanback app. The WebView underneath is current
     * Chromium, but the site reads the version inside the string, and an old one
     * earns a "this device no longer fully supports YouTube" banner, so keep it
     * to a recent TV year. Change this if Google ever stops serving the TV site
     * to it.
     */
    static final String TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 8.0) AppleWebKit/537.36 (KHTML, like Gecko) 108.0.5359.1/8.0 TV Safari/537.36";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_WARM = "warm";
    private static final int RETRY_MS = 15000;
    private static final int IDLE_CHECK_MS = 30000;
    /** Checks in a row with nothing playing before the dashboard comes back. */
    private static final int IDLE_LIMIT = 4;

    private static volatile YouTubeActivity current;

    private final Handler handler = new Handler();
    private WebView web;
    private TextView status;
    private RoomBrainClient brain;
    private boolean resumed;
    private String failedUrl;
    private int idleChecks;

    /** Whether the page is alive, which is what DIAL reports as the app state. */
    static boolean running() {
        return current != null;
    }

    /**
     * Bring the screen forward, loading {@code url} if given, from any context
     * or thread. A live instance starts the intent itself, from inside the
     * task: with NEW_TASK from outside, Android treats an intent for the task's
     * root activity as "bring the task forward" and delivers nothing, and after
     * a boot this screen is that root.
     */
    static void open(Context context, String url) {
        Intent i = new Intent(context, YouTubeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (url != null) i.putExtra(EXTRA_URL, url);
        YouTubeActivity alive = current;
        if (alive != null) {
            alive.startActivity(i);
        } else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }

    /**
     * Boot: load the page and put the dashboard over it. Done from in here once
     * the screen exists, because two starts fired back to back from a receiver
     * leave the lower one as a record with no activity behind it.
     */
    static void warm(Context context) {
        Intent i = new Intent(context, YouTubeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(EXTRA_WARM, true);
        context.startActivity(i);
    }

    /** DIAL asked for the app to stop. */
    static void stop() {
        YouTubeActivity a = current;
        if (a != null) a.runOnUiThread(a::finish);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_youtube);
        hideSystemUi();
        current = this;

        brain = new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN);
        status = findViewById(R.id.statusText);
        findViewById(R.id.osChip).setOnClickListener(v -> leave());

        web = findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // the site keeps its screen id and link here
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // a cast must start with nobody touching the wall
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);   // dial_data is plain http on the LAN
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setUserAgentString(TV_USER_AGENT);
        CookieManager.getInstance().setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);   // chrome://inspect from the Mac
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new Bridge(), "RoomOs");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;   // everything stays in this WebView
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                showStatus("Loading YouTube…");
                v.evaluateJavascript(BRIDGE_JS, null);   // early, ahead of the site's own scripts
            }

            @Override public void onPageFinished(WebView v, String url) {
                failedUrl = null;
                showStatus(null);
                v.evaluateJavascript(BRIDGE_JS, null);   // again, in case the early one hit the old document
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                // Usually Wi-Fi not being up yet after a boot. Keep trying.
                failedUrl = r.getUrl().toString();
                showStatus("YouTube unreachable · " + e.getDescription() + " · retrying");
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, RETRY_MS);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "console: " + m.message());
                return true;
            }
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        // Even a load nobody cast to carries DIAL context, so the site registers
        // as a screen and keeps its lounge connection open for phones.
        web.loadUrl(url != null ? url : DialServer.launchUrl(""));
        if (getIntent().getBooleanExtra(EXTRA_WARM, false)) {
            // Only the wallpaper shows before the dashboard lands; both share it.
            startActivity(new Intent(this, MainActivity.class));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra(EXTRA_URL);
        if (url != null) web.loadUrl(url);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemUi();
        idleChecks = 0;
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, IDLE_CHECK_MS);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(idleCheck);
        super.onPause();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        idleChecks = 0;   // someone is using the panel; stay
        return super.dispatchTouchEvent(ev);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current == this) current = null;
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    /** Back to the dashboard, leaving the page alive and quiet underneath. */
    private void leave() {
        Log.i(TAG, "leaving for the dashboard");
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null);
        Intent dash = new Intent(this, MainActivity.class);
        dash.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(dash);
    }

    private final Runnable retry = () -> {
        if (failedUrl != null && web != null) web.loadUrl(failedUrl);
    };

    /** Anything audible actually running in the page right now? */
    private static final String PLAYING_JS =
        "Array.prototype.some.call(document.querySelectorAll('video'),"
        + "function(v){return !v.paused&&!v.muted&&v.volume>0&&v.readyState>2})";

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            web.evaluateJavascript(PLAYING_JS, playing -> {
                if (!resumed) return;
                if ("true".equals(playing)) idleChecks = 0;
                else if (++idleChecks >= IDLE_LIMIT) {
                    Log.i(TAG, "nothing playing for a while; back to the dashboard");
                    leave();
                    return;
                }
                handler.postDelayed(this, IDLE_CHECK_MS);
            });
        }
    };

    private void showStatus(String text) {
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text != null) status.setText(text);
    }

    /** Spotify on this speaker yields to a cast. Other rooms' speakers are left alone. */
    private void duckSpotify() {
        brain.getPlayback((p, err) -> {
            if (err != null || p == null || !p.isPlaying) return;
            if (!DialServer.FRIENDLY_NAME.equals(p.deviceName)) return;
            brain.transport("pause", (m, e) -> Log.i(TAG, "paused Spotify for a cast: " + (e == null ? m : e.getMessage())));
        });
    }

    /**
     * Two jobs. The page must never learn it is behind the dashboard: on a
     * hidden document the site stops playback and unloads its player, and a
     * phone's next "play" would then land on nothing. The dashboard is only a
     * window in front, so visibility is pinned. The window-level capture
     * listener runs before any document listener whenever it was added, so this
     * holds even though it is injected after the site's scripts.
     *
     * Then media events. They do not bubble, but a capturing listener on the
     * document still sees every video element's, including ones the site
     * creates later. Muted playback is reported as such: the site autoplays
     * silent previews under focused thumbnails, and those must not pull the
     * screen forward.
     */
    private static final String BRIDGE_JS =
        "(function(){if(window.__roomos)return;window.__roomos=1;"
        + "try{Object.defineProperty(Document.prototype,'hidden',{get:function(){return false},configurable:true});"
        + "Object.defineProperty(Document.prototype,'visibilityState',{get:function(){return 'visible'},configurable:true});}catch(e){}"
        + "window.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation()},true);"
        + "['playing','pause','ended'].forEach(function(t){"
        + "document.addEventListener(t,function(e){var v=e.target;"
        + "if(v&&v.tagName==='VIDEO')RoomOs.media(t,!!v.muted||v.volume===0);},true);});})();";

    private final class Bridge {
        @JavascriptInterface public void media(String event, boolean muted) {
            Log.i(TAG, "video " + event + (muted ? " (muted)" : ""));
            if (!"playing".equals(event) || muted) return;
            runOnUiThread(() -> {
                duckSpotify();
                if (!resumed) open(YouTubeActivity.this, null);
            });
        }
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
}

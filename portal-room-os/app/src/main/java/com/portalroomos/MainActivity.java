package com.portalroomos;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private RoomBrainClient brain;
    private TextView time, date, weather, presence, focus, nextEvent, reminder, homeSummary, music, status;
    private TextView timerPhase, timerTime, timerName;
    private View timerCard, timerSpace;
    private TimerProgressView timerRing;
    /** The same Pomodoro the timer screen drives; reloaded whenever we resume. */
    private TimerState timer = new TimerState();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        hideSystemUi();

        time = findViewById(R.id.time);
        date = findViewById(R.id.date);
        weather = findViewById(R.id.weather);
        presence = findViewById(R.id.presence);
        focus = findViewById(R.id.focus);
        nextEvent = findViewById(R.id.nextEvent);
        reminder = findViewById(R.id.reminder);
        homeSummary = findViewById(R.id.homeSummary);
        music = findViewById(R.id.music);
        status = findViewById(R.id.status);
        timerCard = findViewById(R.id.timerCard);
        timerSpace = findViewById(R.id.timerSpace);
        timerRing = findViewById(R.id.timerRing);
        timerPhase = findViewById(R.id.timerPhase);
        timerTime = findViewById(R.id.timerTime);
        timerName = findViewById(R.id.timerName);

        brain = new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN);
        bindAction(R.id.voice, "voice");
        bindAction(R.id.lights, "toggle_lights");
        findViewById(R.id.musicBtn).setOnClickListener(v ->
            startActivity(new Intent(this, MusicActivity.class)));
        bindAction(R.id.add, "add_item");
        View.OnClickListener openCalendar = v ->
            startActivity(new Intent(this, CalendarActivity.class));
        findViewById(R.id.calendarBtn).setOnClickListener(openCalendar);
        nextEvent.setOnClickListener(openCalendar);
        findViewById(R.id.transitBtn).setOnClickListener(v ->
            startActivity(new Intent(this, TransitActivity.class)));
        View.OnClickListener openTimer = v ->
            startActivity(new Intent(this, TimerActivity.class));
        findViewById(R.id.timerBtn).setOnClickListener(openTimer);
        timerCard.setOnClickListener(openTimer);

        // Room OS offers itself as a home screen, so it needs a way back to the
        // stock launcher for the Portal's own features. Hidden if there is no
        // other home to go to.
        Intent stock = stockHome();
        View portalBtn = findViewById(R.id.portalBtn);
        if (stock == null) portalBtn.setVisibility(View.GONE);
        else portalBtn.setOnClickListener(v -> startActivity(stock));

        // Start the on-device Spotify Connect receiver ("Portal").
        startForegroundService(new Intent(this, LibrespotService.class));

        SingingBowl.shared().prepareAsync();
    }

    @Override protected void onResume() {
        super.onResume();
        // The timer screen owns the same model, so take it fresh rather than
        // trusting whatever this screen last saw.
        timer = TimerState.load(this);
        refreshTimerCard();
        // Ticking only while we are in front is also what stops the dashboard
        // and the timer screen both crediting the same phase.
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(stateTick);
        handler.post(clockTick);
        handler.post(stateTick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(stateTick);
        super.onPause();
    }

    /**
     * A running Pomodoro, glanceable from across the room. The dashboard walks
     * the same model the timer screen does, so a phase that lands while this is
     * the screen in front is credited and rung here.
     */
    private void refreshTimerCard() {
        if (timer.advance() > 0) {
            timer.save(this);
            SingingBowl.shared().ring();
        }
        boolean show = timer.showable();
        timerCard.setVisibility(show ? View.VISIBLE : View.GONE);
        timerSpace.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        int tint = getColor(TimerActivity.phaseColor(timer));
        timerRing.setArcColor(tint);
        timerRing.setProgress(timer.progress());
        timerPhase.setTextColor(tint);
        timerPhase.setText(TimerActivity.phaseWord(timer));
        timerTime.setText(TimerState.countdown(timer.remaining()));
        timerName.setText(timer.sessionName.isEmpty()
                ? timer.presetName() + " · " + timer.focusMin() + " / " + timer.breakMin()
                : timer.sessionName);
    }

    /**
     * The device's own home screen, whichever it is: every home activity except
     * ours. Resolved rather than hard-coded so this is not tied to one launcher.
     */
    private Intent stockHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            if (ri.activityInfo == null) continue;
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            // Resolve with CATEGORY_HOME, but launch without it. Room OS is a
            // home app too, so a HOME intent lands in the home task and this
            // activity simply comes back to the front. An explicit component
            // does not need the category to start.
            Intent go = new Intent(Intent.ACTION_MAIN);
            go.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return go;
        }
        return null;
    }

    private void bindAction(int id, String action) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> {
            status.setText("Sending: " + action + "…");
            brain.sendAction(action, (message, error) -> runOnUiThread(() -> {
                status.setText(error == null ? message : "Room Brain unavailable: " + error.getMessage());
                if (error == null) refreshState();
            }));
        });
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            time.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
            date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
            refreshTimerCard();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable stateTick = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 15000);
        }
    };

    private void refreshState() {
        brain.getState((s, error) -> runOnUiThread(() -> {
            if (error != null) {
                status.setText("Room Brain offline · dashboard running locally");
                return;
            }
            weather.setText(s.weather);
            focus.setText(s.focus);
            nextEvent.setText(s.nextEvent);
            reminder.setText(s.reminder);
            homeSummary.setText(s.homeSummary);
            music.setText(s.music);
            presence.setText(s.occupied ? "Room active" : "Ambient mode");
            status.setText("Room Brain connected");
        }));
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

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

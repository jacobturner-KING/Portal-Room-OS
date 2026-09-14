package com.portalroomos;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.widget.TextView;
import java.util.Locale;

/**
 * The recorder's face on a screen: one chip that is the Record button when
 * idle, the running clock while recording, and the outcome for a moment after.
 * The dashboard and the Meetings screen each own one; both drive the same
 * {@link RecorderService}, so a recording started on one can be stopped from
 * the other, and the chips agree because the service holds the state.
 */
final class RecordChip {
    static final int PERMISSION_REQUEST = 1;
    /** How long the saved or failed outcome stays on the chip. */
    private static final long OUTCOME_MS = 12_000;

    private final Activity activity;
    private final TextView chip;
    private final TextView status;
    /** The waveform beside the chip; shown only while recording. */
    private final LevelMeterView meter;
    private long outcomeShownAt;
    private String lastSavedId = "";

    RecordChip(Activity activity, TextView chip, LevelMeterView meter, TextView status) {
        this.activity = activity;
        this.chip = chip;
        this.meter = meter;
        this.status = status;
        chip.setOnClickListener(v -> toggle());
        refresh();
    }

    void toggle() {
        if (RecorderService.running()) {
            RecorderService.stop(activity);
            status.setText("Finishing the recording…");
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // The Portal's own permission dialog is white on white, so the adb
            // route is the one that reliably works; the dialog is tried anyway.
            status.setText("Microphone not granted · adb shell pm grant com.portalroomos android.permission.RECORD_AUDIO");
            activity.requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, PERMISSION_REQUEST);
            return;
        }
        RecorderService.start(activity);
        status.setText("Recording · tap the chip again to stop");
        refresh();
    }

    /** From the activity's permission callback; starts if the mic was just allowed. */
    void onPermissionResult(int code, int[] results) {
        if (code == PERMISSION_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggle();
    }

    /**
     * Call once a second. Returns the id of a recording that has just been
     * saved, once, so a screen that lists recordings can go and fetch it.
     */
    String refresh() {
        RecorderService.State st = RecorderService.state;
        long now = System.currentTimeMillis();
        String justSaved = null;
        if (st == RecorderService.State.SAVED || st == RecorderService.State.FAILED) {
            // The outcome shows for a moment, then the chip is a Record button again.
            if (outcomeShownAt == 0) outcomeShownAt = now;
            if (now - outcomeShownAt >= OUTCOME_MS) {
                outcomeShownAt = 0;
                RecorderService.state = st = RecorderService.State.IDLE;
            }
        }
        if (st == RecorderService.State.SAVED && !RecorderService.savedId.equals(lastSavedId)) {
            lastSavedId = RecorderService.savedId;
            justSaved = lastSavedId;
        }
        boolean live = st == RecorderService.State.RECORDING;
        meter.setVisibility(live ? android.view.View.VISIBLE : android.view.View.GONE);
        meter.setActive(live);
        switch (st) {
            case RECORDING: {
                long s = (now - RecorderService.startedAtMs) / 1000;
                chip.setText(String.format(Locale.US, "● %d:%02d · Stop", s / 60, s % 60));
                chip.setTextColor(activity.getColor(R.color.alert));
                break;
            }
            case FINISHING: {
                int left = RecorderService.chunksPending;
                chip.setText(left > 0 ? "Saving… " + left + " to go" : "Saving…");
                chip.setTextColor(activity.getColor(R.color.amber));
                break;
            }
            case SAVED:
                chip.setText("Saved · " + RecorderService.savedTitle);
                chip.setTextColor(activity.getColor(R.color.success));
                break;
            case FAILED:
                chip.setText("Recording failed");
                chip.setTextColor(activity.getColor(R.color.alert));
                status.setText(RecorderService.lastError);
                break;
            default:
                chip.setText("● Record");
                chip.setTextColor(activity.getColor(R.color.text));
        }
        return justSaved;
    }
}

package com.portalroomos;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.Locale;

/**
 * The agents talking back, in the Portal's own voice: the text-to-speech
 * engine Facebook ships on the device, through Android's standard
 * TextToSpeech API. On the device, so it costs nothing and needs no network.
 *
 * Short lines only: who finished or needs you, where, and the first sentence
 * of what it said. The whole reply is read only when asked (tap it). Rides the
 * media stream like the chime, so the room's volume control governs it, and
 * stays quiet while a microphone is open, so it never talks into a recording
 * or into what you are saying.
 */
final class AgentVoice {
    private static final String TAG = "AgentVoice";
    private static final String PREFS = "agent_voice";
    /** Long replies are cut here, at a sentence where possible. */
    private static final int SUMMARY_MAX = 160;

    private static AgentVoice instance;

    static synchronized AgentVoice shared(Context context) {
        if (instance == null) instance = new AgentVoice(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ready;
    private String pending;
    private int utterances;

    private AgentVoice(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return prefs.getBoolean("enabled", true);
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean("enabled", on).apply();
        if (!on) stop();
    }

    private void ensure() {
        if (tts != null) return;
        tts = new TextToSpeech(context, status -> {
            ready = status == TextToSpeech.SUCCESS;
            Log.i(TAG, "engine " + (ready ? "ready: " + tts.getDefaultEngine() : "failed, status " + status));
            if (!ready) return;
            tts.setLanguage(Locale.US);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { if (!"warmup".equals(id)) Log.i(TAG, "speaking " + id); }
                @Override public void onDone(String id) { Log.i(TAG, "done " + id); }
                @Override public void onError(String id) { Log.w(TAG, "error " + id); }
                @Override public void onError(String id, int code) { Log.w(TAG, "error " + id + " code " + code); }
            });
            if (pending != null) {
                String text = pending;
                pending = null;
                speakNow(text, TextToSpeech.QUEUE_ADD);
            } else if (warmWhenReady) {
                warmNow();
            }
        });
    }

    /** Says it now, replacing whatever was being said: something you asked to hear. */
    void say(String text) {
        speak(text, TextToSpeech.QUEUE_FLUSH);
    }

    /** A ping, spoken only when the voice is on, after anything already being said. */
    void announce(String text) {
        if (enabled()) speak(text, TextToSpeech.QUEUE_ADD);
    }

    /** Loads the engine and its voice ahead of the first ping, silently: the first load takes seconds. */
    void warm() {
        ensure();
        warmWhenReady = true;
        if (ready) warmNow();
    }

    private boolean warmWhenReady;

    private void warmNow() {
        warmWhenReady = false;
        java.io.File out = new java.io.File(context.getCacheDir(), "voice-warmup.wav");
        tts.synthesizeToFile("Ready.", new Bundle(), out, "warmup");
    }

    private void speak(String text, int mode) {
        if (text == null || text.trim().isEmpty()) return;
        // Never talk into an open microphone: a recording, or you talking to an agent.
        if (RecorderService.running() || VoiceNote.listeningNow) return;
        ensure();
        if (!ready) { pending = text; return; }
        speakNow(text, mode);
    }

    /** In pieces the engine takes (its limit is a few thousand characters), cut between sentences. */
    private void speakNow(String text, int mode) {
        int max = Math.max(200, TextToSpeech.getMaxSpeechInputLength() - 100);
        String rest = text;
        while (!rest.isEmpty()) {
            int cut = rest.length();
            if (cut > max) {
                cut = Math.max(rest.lastIndexOf(". ", max), rest.lastIndexOf(' ', max));
                if (cut <= 0) cut = max;
            }
            int r = tts.speak(rest.substring(0, cut).trim(), mode, new Bundle(), "agent-" + (++utterances));
            if (r != TextToSpeech.SUCCESS) Log.w(TAG, "speak refused: " + r);
            mode = TextToSpeech.QUEUE_ADD;
            rest = rest.substring(cut).trim();
        }
    }

    void stop() {
        if (tts != null) tts.stop();
    }

    /** "Claude Code finished in rea. The tests pass." / "Codex in api needs you. It wants to run npm test." */
    static String pingLine(RoomBrainClient.Agent a, AgentChime.Kind kind) {
        String where = a.project != null && !a.project.isEmpty() ? a.project : a.host.split(" · ")[0];
        if (kind == AgentChime.Kind.DONE) {
            String said = gist(a.reply);
            return a.agentLabel + " finished in " + where + "." + (said.isEmpty() ? "" : " " + said);
        }
        String why = a.permission != null ? "It wants to run " + gist(a.permission.summary) + "." : gist(a.attention);
        return a.agentLabel + " in " + where + " needs you." + (why.isEmpty() ? "" : " " + why);
    }

    /** A reply as something to say aloud: code blocks, markdown marks and links left out. */
    static String plain(String reply) {
        if (reply == null) return "";
        return reply
            .replaceAll("```[\\s\\S]*?```", " (code) ")
            .replaceAll("`([^`]*)`", "$1")
            .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
            .replaceAll("[*_#>|]+", " ")
            .replaceAll("https?://\\S+", "a link")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** The first sentence or so of a reply, without markdown, short enough to say in passing. */
    static String gist(String reply) {
        String t = plain(reply);
        if (t.length() <= SUMMARY_MAX) return t;
        int end = -1;
        for (String stop : new String[] { ". ", "! ", "? " }) {
            int i = t.indexOf(stop);
            if (i > 20 && i < SUMMARY_MAX && (end < 0 || i < end)) end = i + 1;
        }
        return end > 0 ? t.substring(0, end) : t.substring(0, SUMMARY_MAX).replaceAll("\\s+\\S*$", "") + "…";
    }
}

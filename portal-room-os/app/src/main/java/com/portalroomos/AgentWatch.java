package com.portalroomos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The panel's one view of the coding agents: polls the Room Brain's hub, holds
 * the latest snapshot, and turns changes into pings. The Agents screen, the
 * floating Agents chip and the banner all read it rather than polling their
 * own, so a finished turn chimes once however many of them are showing.
 *
 * It belongs to the process, like the DIAL responder: the speaker service keeps
 * the process up from boot, so an agent finishing while nobody has opened a
 * screen still pings the room.
 *
 * Polling, not a socket: there is no WebSocket client on this Android without a
 * library, and the cadence follows what is worth knowing. Fast while the Agents
 * screen is up, brisk while any agent is busy, and slow when all are idle.
 *
 * Two places agents come from, merged into one list:
 * - the Room Brain, over the internet: every machine whose hooks report to it;
 * - AppTabs on the Mac at the other end of the USB cable, through
 *   `adb reverse` (AppTabs sets it up and tells this app where to look; see
 *   {@link AgentSourceReceiver}). No internet involved.
 * Both speak the same contract. Each agent keeps the source it came from, so
 * an answer goes back the way it came; times are moved onto this panel's clock
 * as they are merged, so one list can mix both.
 */
final class AgentWatch {

    interface Listener {
        /** A new snapshot, or the error that stopped one; on the main thread. */
        void onAgents(RoomBrainClient.AgentSnapshot snapshot, String error);

        /** One agent just finished, or just started needing an answer. */
        default void onPing(RoomBrainClient.Agent agent, AgentChime.Kind kind) {}
    }

    static final long FAST_MS = 2_500, BUSY_MS = 8_000, IDLE_MS = 30_000;
    static final String ROOM = "room", LOCAL = "local";
    private static final String PREFS = "agent_source";

    private static AgentWatch instance;

    static synchronized AgentWatch shared() {
        if (instance == null) instance = new AgentWatch();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final RoomBrainClient room = roomConfigured() ? new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN) : null;
    /** AppTabs over USB, when linked. */
    private volatile RoomBrainClient local;
    private String localName = "";
    private SharedPreferences prefs;
    private Context appContext;
    private RoomBrainClient.AgentSnapshot latest;
    private String lastError;
    private boolean started, inFlight;
    private int fastHolders;
    /** What each agent last looked like, to tell a change from a repeat. */
    private final Map<String, Integer> doneSeen = new HashMap<>();
    private final Map<String, String> askSeen = new HashMap<>();
    /** Finished since the Agents screen was last looked at; the chip shows it. */
    private final Set<String> unseenDone = new HashSet<>();
    private boolean baseline;

    private AgentWatch() {}

    private static boolean roomConfigured() {
        return !Config.ROOM_BRAIN_URL.isEmpty() && !Config.APP_TOKEN.isEmpty();
    }

    /** Somewhere to read agents from: the Room Brain, or an AppTabs link. */
    boolean configured() {
        return room != null || local != null;
    }

    void start(Context context) {
        if (started) return;
        started = true;
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String url = prefs.getString("url", "");
        String token = prefs.getString("token", "");
        if (!url.isEmpty() && !token.isEmpty()) {
            local = new RoomBrainClient(url, token);
            localName = prefs.getString("name", "");
        }
        main.post(this::poll);
    }

    /** From {@link AgentSourceReceiver}: AppTabs linked (or unlinked) this panel over USB. */
    void setLocal(String url, String token, String name) {
        main.post(() -> {
            if (prefs != null) {
                if (url == null) prefs.edit().clear().apply();
                else prefs.edit().putString("url", url).putString("token", token).putString("name", name).apply();
            }
            local = url == null ? null : new RoomBrainClient(url, token);
            localName = name == null ? "" : name;
            refreshNow();
        });
    }

    String localName() {
        return localName;
    }

    /** The way back to where this agent came from. */
    RoomBrainClient clientFor(RoomBrainClient.Agent a) {
        return LOCAL.equals(a.source) ? local : room;
    }

    /** Speech to text happens on the Room Brain whichever way the agent is reached. */
    RoomBrainClient roomClient() {
        return room;
    }

    /** "Approve here" means here for every source. */
    void setWallApprovals(boolean on, RoomBrainClient.Callback<String> cb) {
        List<RoomBrainClient> all = new ArrayList<>();
        if (room != null) all.add(room);
        if (local != null) all.add(local);
        if (all.isEmpty()) { cb.onResult(null, new Exception("No agent source")); return; }
        final int[] left = { all.size() };
        final Exception[] first = { null };
        for (RoomBrainClient c : all) {
            c.setWallApprovals(on, (r, e) -> main.post(() -> {
                if (e != null && first[0] == null) first[0] = e;
                if (--left[0] == 0) cb.onResult(null, first[0]);
            }));
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
        if (latest != null || lastError != null) l.onAgents(latest, lastError);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The Agents screen holds this while it is in front. */
    void setFast(boolean fast) {
        fastHolders = Math.max(0, fastHolders + (fast ? 1 : -1));
        if (fast) refreshNow();
    }

    void refreshNow() {
        if (!started) return;
        main.removeCallbacks(pollRunnable);
        main.post(pollRunnable);
    }

    /** The Agents screen has been seen: finished turns stop counting as news. */
    void markSeen() {
        if (unseenDone.isEmpty()) return;
        unseenDone.clear();
        notifyListeners();
    }

    RoomBrainClient.AgentSnapshot latest() {
        return latest;
    }

    int unseenDone() {
        return unseenDone.size();
    }

    private final Runnable pollRunnable = this::poll;

    private void poll() {
        if (inFlight) return;
        RoomBrainClient r = room, l = local;
        if (r == null && l == null) {
            main.postDelayed(pollRunnable, IDLE_MS);
            return;
        }
        inFlight = true;
        final RoomBrainClient.AgentSnapshot[] got = new RoomBrainClient.AgentSnapshot[2];
        final String[] errors = new String[2];
        final int[] left = { (r != null ? 1 : 0) + (l != null ? 1 : 0) };
        Runnable joined = () -> {
            inFlight = false;
            merge(got[0], errors[0], got[1], errors[1], l != null);
            notifyListeners();
            main.removeCallbacks(pollRunnable);
            main.postDelayed(pollRunnable, nextDelay());
        };
        if (r != null) r.getAgents((s, e) -> main.post(() -> {
            got[0] = s;
            errors[0] = e == null ? null : (e.getMessage() == null ? "Room Brain unavailable" : e.getMessage());
            if (--left[0] == 0) joined.run();
        }));
        if (l != null) l.getAgents((s, e) -> main.post(() -> {
            got[1] = s;
            errors[1] = e == null ? null : "AppTabs on " + (localName.isEmpty() ? "the Mac" : localName) + " not reachable over USB";
            if (--left[0] == 0) joined.run();
        }));
    }

    /**
     * One list from both sources. A source that failed while the other answered
     * is a note, not an error: a Room Brain without the agents routes should not
     * hide the Mac on the cable.
     */
    private void merge(RoomBrainClient.AgentSnapshot roomSnap, String roomError,
                       RoomBrainClient.AgentSnapshot localSnap, String localError, boolean linked) {
        if (roomSnap == null && localSnap == null) {
            lastError = linked && localError != null ? localError : roomError != null ? roomError : localError;
            return;
        }
        long now = System.currentTimeMillis();
        RoomBrainClient.AgentSnapshot m = new RoomBrainClient.AgentSnapshot();
        m.serverNow = now;
        m.fetchedAt = now;
        LinkedHashSet<String> bridges = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            RoomBrainClient.AgentSnapshot s = i == 0 ? roomSnap : localSnap;
            if (s == null) continue;
            String source = i == 0 ? ROOM : LOCAL;
            long shift = s.fetchedAt - s.serverNow;
            m.rev += s.rev;
            // The Mac on the cable decides approvals for its own agents; otherwise the Room Brain's setting shows.
            if (i == 1 || localSnap == null) m.wallApprovals = s.wallApprovals;
            bridges.addAll(s.bridges);
            for (RoomBrainClient.Agent a : s.sessions) {
                a.source = source;
                a.startedAt += shift;
                a.updatedAt += shift;
                a.stateSince += shift;
                if (a.permission != null) a.permission.expiresAt += shift;
                if (a.lastSay != null) a.lastSay.at += shift;
                if (a.stats != null) {
                    if (a.stats.firstAt > 0) a.stats.firstAt += shift;
                    if (a.stats.lastAt > 0) a.stats.lastAt += shift;
                }
                m.sessions.add(a);
            }
        }
        if (linked && localError != null) notes.add(localError);
        if (roomSnap == null && roomError != null && localSnap != null) {
            // A Room Brain without the agents routes answers not_found: it has not been deployed with them yet.
            notes.add("not_found".equals(roomError) ? "Room Brain not deployed with agents yet" : "Room Brain: " + roomError);
        }
        m.bridges = new ArrayList<>(bridges);
        m.note = String.join(" · ", notes);
        lastError = null;
        apply(m);
    }

    private long nextDelay() {
        if (fastHolders > 0) return FAST_MS;
        if (latest != null) {
            for (RoomBrainClient.Agent a : latest.sessions) {
                if ("working".equals(a.state) || "waiting".equals(a.state)) return BUSY_MS;
            }
        }
        return IDLE_MS;
    }

    private void apply(RoomBrainClient.AgentSnapshot s) {
        Set<String> present = new HashSet<>();
        for (RoomBrainClient.Agent a : s.sessions) {
            present.add(a.id);
            Integer seenDone = doneSeen.put(a.id, a.doneCount);
            String ask = a.permission != null ? "p:" + a.permission.id
                : "waiting".equals(a.state) && !a.attention.isEmpty() ? "a:" + a.attention : "";
            String seenAsk = askSeen.put(a.id, ask);
            // The first snapshot after start, and an agent's first sighting, are history, not news.
            if (!baseline || seenDone == null) continue;
            if (a.doneCount > seenDone) {
                unseenDone.add(a.id);
                ping(a, AgentChime.Kind.DONE);
            } else if (!ask.isEmpty() && !ask.equals(seenAsk)) {
                ping(a, AgentChime.Kind.NEEDS_YOU);
            }
        }
        doneSeen.keySet().retainAll(present);
        askSeen.keySet().retainAll(present);
        unseenDone.retainAll(present);
        baseline = true;
        latest = s;
    }

    private void ping(RoomBrainClient.Agent a, AgentChime.Kind kind) {
        AgentChime.shared().play(kind);
        // The chime first, then the agent says what happened, in the Portal's own voice.
        if (appContext != null) AgentVoice.shared(appContext).announce(AgentVoice.pingLine(a, kind));
        for (Listener l : listeners) l.onPing(a, kind);
    }

    private void notifyListeners() {
        for (Listener l : listeners) l.onAgents(latest, lastError);
    }
}

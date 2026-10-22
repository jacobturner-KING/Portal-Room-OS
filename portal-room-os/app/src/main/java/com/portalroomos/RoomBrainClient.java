package com.portalroomos;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class RoomBrainClient {
    public interface Callback<T> { void onResult(T value, Exception error); }

    private final String baseUrl;
    private final String authToken;

    public RoomBrainClient(String baseUrl, String authToken) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.authToken = authToken;
    }

    // ---- Models ----
    public static class Playback {
        public boolean isPlaying;
        public String track, artists, album, artUrl, deviceName;
    }
    public static class Track {
        public String uri, name, artists, artUrl;
    }
    public static class Playlist {
        public String id, uri, name, imageUrl;
    }
    public static class Device {
        public String id, name, type;
        public boolean isActive;
    }
    public static class SearchResults {
        public List<Track> tracks = new ArrayList<>();
        public List<Playlist> playlists = new ArrayList<>();
    }

    // ---- Dashboard state ----
    public void getState(Callback<RoomState> callback) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpGet("/api/state"));
                RoomState s = new RoomState();
                s.weather = j.optString("weather", s.weather);
                s.focus = j.optString("focus", s.focus);
                s.nextEvent = j.optString("next_event", s.nextEvent);
                s.reminder = j.optString("reminder", s.reminder);
                s.homeSummary = j.optString("home_summary", s.homeSummary);
                s.music = j.optString("music", s.music);
                s.occupied = j.optBoolean("occupied", true);
                callback.onResult(s, null);
            } catch (Exception e) { callback.onResult(null, e); }
        }).start();
    }

    public void sendAction(String action, Callback<String> callback) {
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject().put("action", action);
                JSONObject j = new JSONObject(httpPostJson("/api/action", req.toString()));
                callback.onResult(j.optString("message", "Done"), null);
            } catch (Exception e) { callback.onResult(null, e); }
        }).start();
    }

    // ---- Spotify controller ----
    public void getPlayback(Callback<Playback> cb) {
        new Thread(() -> {
            try {
                String body = httpGet("/api/spotify/now");
                Playback p = new Playback();
                if (body != null && body.trim().startsWith("{")) {
                    JSONObject j = new JSONObject(body);
                    p.isPlaying = j.optBoolean("isPlaying", false);
                    p.track = optStr(j, "track");
                    p.artists = optStr(j, "artists");
                    p.album = optStr(j, "album");
                    p.artUrl = optStr(j, "artUrl");
                    p.deviceName = optStr(j, "deviceName");
                }
                cb.onResult(p, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void search(String q, Callback<SearchResults> cb) {
        new Thread(() -> {
            try {
                String enc = URLEncoder.encode(q, "UTF-8");
                JSONObject j = new JSONObject(httpGet("/api/spotify/search?q=" + enc));
                SearchResults r = new SearchResults();
                r.tracks = parseTracks(j.optJSONArray("tracks"));
                r.playlists = parsePlaylists(j.optJSONArray("playlists"));
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void getPlaylists(Callback<List<Playlist>> cb) {
        new Thread(() -> {
            try {
                String body = httpGet("/api/spotify/playlists");
                cb.onResult(parsePlaylists(asArray(body)), null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void getPlaylistTracks(String id, Callback<List<Track>> cb) {
        new Thread(() -> {
            try {
                String body = httpGet("/api/spotify/playlist?id=" + URLEncoder.encode(id, "UTF-8"));
                cb.onResult(parseTracks(asArray(body)), null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void getDevices(Callback<List<Device>> cb) {
        new Thread(() -> {
            try {
                JSONArray arr = asArray(httpGet("/api/spotify/devices"));
                List<Device> out = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        Device d = new Device();
                        d.id = optStr(o, "id");
                        d.name = optStr(o, "name");
                        d.type = optStr(o, "type");
                        d.isActive = o.optBoolean("isActive", false);
                        out.add(d);
                    }
                }
                cb.onResult(out, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void transport(String action, Callback<String> cb) {
        postAction("/api/spotify/transport", new JSONObjectSafe().put("action", action), cb);
    }

    public void play(String contextUri, String uri, String deviceId, Callback<String> cb) {
        JSONObjectSafe req = new JSONObjectSafe();
        if (contextUri != null) req.put("contextUri", contextUri);
        if (uri != null) req.putArray("uris", uri);
        if (deviceId != null) req.put("deviceId", deviceId);
        postAction("/api/spotify/play", req, cb);
    }

    public void transfer(String deviceId, Callback<String> cb) {
        postAction("/api/spotify/transfer", new JSONObjectSafe().put("deviceId", deviceId), cb);
    }

    private void postAction(String path, JSONObjectSafe req, Callback<String> cb) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpPostJson(path, req.toString()));
                cb.onResult(j.optString("message", j.optBoolean("ok", false) ? "Done" : "Failed"), null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    // ---- Transit ----
    public void getTransit(Callback<JSONObject> cb) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpGet("/api/transit"));
                if (j.has("error")) throw new Exception(j.optString("error"));
                cb.onResult(j, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    // ---- Calendar ----
    public static class CalendarInfo {
        public String id, name;
        public int color = CalUi.ACCENT;
        public boolean primary, editable;
    }
    public static class CalendarsResult {
        public List<CalendarInfo> calendars = new ArrayList<>();
        public boolean canEdit;
    }
    public static class UpdateResult {
        public boolean ok;
        public String message;
        public CalEvent event;
    }

    public void getCalendars(Callback<CalendarsResult> cb) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpGet("/api/calendar/calendars"));
                if (j.has("error")) throw new Exception(j.optString("error"));
                CalendarsResult r = new CalendarsResult();
                r.canEdit = j.optBoolean("canEdit", false);
                JSONArray arr = j.optJSONArray("calendars");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        CalendarInfo c = new CalendarInfo();
                        c.id = optStr(o, "id");
                        c.name = optStr(o, "name");
                        c.primary = o.optBoolean("primary", false);
                        c.editable = o.optBoolean("editable", false);
                        try { c.color = android.graphics.Color.parseColor(o.optString("color", "#8AA4FF")); }
                        catch (Exception ignored) {}
                        r.calendars.add(c);
                    }
                }
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void getEvents(String startIso, String endIso, Callback<List<CalEvent>> cb) {
        new Thread(() -> {
            try {
                String path = "/api/calendar/events?start=" + URLEncoder.encode(startIso, "UTF-8")
                    + "&end=" + URLEncoder.encode(endIso, "UTF-8");
                JSONObject j = new JSONObject(httpGet(path));
                if (j.has("error")) throw new Exception(j.optString("error"));
                List<CalEvent> out = new ArrayList<>();
                JSONArray arr = j.optJSONArray("events");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) out.add(CalEvent.fromJson(o));
                    }
                }
                cb.onResult(out, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void getBusyDays(String startIso, String endIso, String tz, Callback<Map<String, Integer>> cb) {
        new Thread(() -> {
            try {
                String path = "/api/calendar/days?start=" + URLEncoder.encode(startIso, "UTF-8")
                    + "&end=" + URLEncoder.encode(endIso, "UTF-8") + "&tz=" + URLEncoder.encode(tz, "UTF-8");
                JSONObject j = new JSONObject(httpGet(path));
                if (j.has("error")) throw new Exception(j.optString("error"));
                Map<String, Integer> out = new HashMap<>();
                JSONObject days = j.optJSONObject("days");
                if (days != null) {
                    Iterator<String> keys = days.keys();
                    while (keys.hasNext()) { String k = keys.next(); out.put(k, days.optInt(k, 0)); }
                }
                cb.onResult(out, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void updateEvent(JSONObject body, Callback<UpdateResult> cb) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpPostJson("/api/calendar/event/update", body.toString()));
                UpdateResult r = new UpdateResult();
                r.ok = j.optBoolean("ok", false);
                r.message = j.optString("message", r.ok ? "Saved" : "Failed");
                JSONObject ev = j.optJSONObject("event");
                if (ev != null) r.event = CalEvent.fromJson(ev);
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    // ---- Meetings (Granola) ----
    public static class Meeting {
        public String id, title, date, summary;
        public List<String> attendees = new ArrayList<>();
        /** "granola", or "portal" for a recording made on this device. */
        public String source = "granola";
        public int durationSec;
        /** Portal recordings: "recording" while the mic is live, "done" after. */
        public String status = "done";
        public boolean isPortal() { return "portal".equals(source); }
    }
    public static class MeetingList {
        public List<Meeting> meetings = new ArrayList<>();
        /** What Granola actually said, for when the list could not be parsed out of it. */
        public String raw = "";
        /** False when Granola is not linked; Portal recordings still come through. */
        public boolean granola = true;
    }
    public static class ChatTurn {
        public final String role, content;
        public ChatTurn(String role, String content) { this.role = role; this.content = content; }
    }
    public static class ChatReply {
        public String reply;
        /** Who answered: "granola" (its own AI) or "claude". */
        public String source;
    }

    public void getMeetings(String search, Callback<MeetingList> cb) {
        new Thread(() -> {
            try {
                String path = "/api/meetings?limit=200";
                if (search != null && !search.trim().isEmpty()) path += "&search=" + URLEncoder.encode(search.trim(), "UTF-8");
                JSONObject j = new JSONObject(httpGet(path));
                if (j.has("error")) throw new Exception(j.optString("error"));
                MeetingList r = new MeetingList();
                r.raw = j.optString("raw", "");
                r.granola = j.optBoolean("granola", true);
                JSONArray arr = j.optJSONArray("meetings");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Meeting m = new Meeting();
                    m.id = optStr(o, "id");
                    m.title = optStr(o, "title");
                    m.date = optStr(o, "date");
                    m.summary = optStr(o, "summary");
                    m.source = o.optString("source", "granola");
                    m.durationSec = o.optInt("durationSec", 0);
                    m.status = o.optString("status", "done");
                    JSONArray a = o.optJSONArray("attendees");
                    for (int k = 0; a != null && k < a.length(); k++) m.attendees.add(a.optString(k));
                    r.meetings.add(m);
                }
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void getMeetingNotes(String id, Callback<String> cb) {
        fetchText("/api/meetings/notes?id=", id, "notes", cb);
    }

    public void getMeetingTranscript(String id, Callback<String> cb) {
        fetchText("/api/meetings/transcript?id=", id, "transcript", cb);
    }

    private void fetchText(String pathPrefix, String id, String field, Callback<String> cb) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpGet(pathPrefix + URLEncoder.encode(id, "UTF-8")));
                if (j.has("error")) throw new Exception(j.optString("error"));
                cb.onResult(j.optString(field, ""), null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    /**
     * One question. Granola's own AI answers by default, across every meeting or
     * pinned to the given one; the Worker hands it to Claude instead when it has
     * a key for that, and says which in the reply.
     */
    public void chatMeeting(Meeting meeting, List<ChatTurn> history, Callback<ChatReply> cb) {
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject();
                if (meeting != null) {
                    req.put("meetingId", meeting.id);
                    req.put("title", meeting.title == null ? "" : meeting.title);
                    req.put("date", meeting.date == null ? "" : meeting.date);
                }
                JSONArray turns = new JSONArray();
                for (ChatTurn t : history) turns.put(new JSONObject().put("role", t.role).put("content", t.content));
                req.put("messages", turns);
                JSONObject j = new JSONObject(httpPostJson("/api/meetings/chat", req.toString()));
                if (j.has("error")) throw new Exception(j.optString("error"));
                ChatReply r = new ChatReply();
                r.reply = j.optString("reply", "");
                r.source = j.optString("source", "granola");
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    // ---- Portal recordings (blocking; RecorderService calls these off its own thread) ----

    /** One WAV chunk up; the Worker transcribes it before answering. Throws on any failure. */
    public void uploadChunkSync(String id, int seq, String startedAtIso, byte[] wav) throws Exception {
        String path = "/api/recordings/" + id + "/chunk?seq=" + seq
            + "&startedAt=" + URLEncoder.encode(startedAtIso, "UTF-8");
        HttpURLConnection c = open(path, "PUT");
        c.setReadTimeout(180000);   // Whisper on a three-minute chunk
        c.setRequestProperty("Content-Type", "audio/wav");
        c.setFixedLengthStreamingMode(wav.length);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) { out.write(wav); }
        JSONObject j = new JSONObject(read(c));
        if (!j.optBoolean("ok", false)) throw new Exception(j.optString("error", "chunk rejected"));
    }

    /** Stitches and names the recording; returns the title. */
    public String finishRecordingSync(String id, long durationSec) throws Exception {
        HttpURLConnection c = open("/api/recordings/" + id + "/finish", "POST");
        c.setReadTimeout(120000);   // naming runs a language model
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write(new JSONObject().put("durationSec", durationSec).toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject j = new JSONObject(read(c));
        if (!j.optBoolean("ok", false)) throw new Exception(j.optString("error", "finish rejected"));
        return j.optString("title", "Recording");
    }

    // ---- Agents: coding agents on your machines, through the Room Brain's hub ----
    public static class AgentPermission {
        public String id, tool, summary;
        public long expiresAt;
    }
    public static class AgentSay {
        /** "sent" (on its way to the bridge), "typed", or "failed" with an error. */
        public String text, status, error;
        public long at;
    }
    public static class Agent {
        public String id, agent, agentLabel, project, title, host;
        /** "idle", "working", "waiting", "done" or "ended". */
        public String state;
        public String activity;
        /** The pet's prop: think, edit, read, run, search, web, delegate, plan, tool, or "". */
        public String kind;
        public String prompt, reply, attention, talkNote;
        public long startedAt, updatedAt, stateSince;
        public int doneCount;
        public boolean canTalk;
        public AgentPermission permission;
        public AgentSay lastSay;
        /** Where it came from: AgentWatch.ROOM or AgentWatch.LOCAL (AppTabs over USB). */
        public String source = "room";
        /** Usage from the transcript, when the source reads it (AppTabs does). */
        public AgentStats stats;
    }
    public static class AgentStats {
        public long contextUsed, contextWindow, tokensIn, tokensOut, firstAt, lastAt;
        public int calls;
        /** Negative when unknown. */
        public double costUsd = -1;
    }
    public static class AgentSnapshot {
        public int rev;
        /** The Worker's clock when it answered, for durations that ignore this panel's drift. */
        public long serverNow;
        public long fetchedAt;
        public boolean wallApprovals;
        public List<String> bridges = new ArrayList<>();
        public List<Agent> sessions = new ArrayList<>();
        /** A source that did not answer while another did. */
        public String note = "";
    }
    public static class SayResult {
        public boolean ok;
        public String text, error;
    }

    public void getAgents(Callback<AgentSnapshot> cb) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpGet("/api/agents"));
                if (j.has("error")) throw new Exception(j.optString("error"));
                AgentSnapshot r = new AgentSnapshot();
                r.rev = j.optInt("rev", 0);
                r.serverNow = j.optLong("now", System.currentTimeMillis());
                r.fetchedAt = System.currentTimeMillis();
                r.wallApprovals = j.optBoolean("wallApprovals", false);
                JSONArray hosts = j.optJSONArray("bridges");
                for (int i = 0; hosts != null && i < hosts.length(); i++) r.bridges.add(hosts.optString(i));
                JSONArray arr = j.optJSONArray("sessions");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) r.sessions.add(parseAgent(o));
                }
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    private static Agent parseAgent(JSONObject o) {
        Agent a = new Agent();
        a.id = o.optString("id", "");
        a.agent = o.optString("agent", "generic");
        a.agentLabel = o.optString("agentLabel", "Agent");
        a.project = o.optString("project", "");
        a.title = o.optString("title", "");
        a.host = o.optString("host", "");
        a.state = o.optString("state", "idle");
        a.activity = o.optString("activity", "");
        a.kind = o.optString("kind", "");
        a.prompt = o.optString("prompt", "");
        a.reply = o.optString("reply", "");
        a.attention = o.optString("attention", "");
        a.talkNote = o.optString("talkNote", "");
        a.startedAt = o.optLong("startedAt", 0);
        a.updatedAt = o.optLong("updatedAt", 0);
        a.stateSince = o.optLong("stateSince", 0);
        a.doneCount = o.optInt("doneCount", 0);
        a.canTalk = o.optBoolean("canTalk", false);
        JSONObject p = o.optJSONObject("permission");
        if (p != null) {
            a.permission = new AgentPermission();
            a.permission.id = p.optString("id", "");
            a.permission.tool = p.optString("tool", "");
            a.permission.summary = p.optString("summary", "");
            a.permission.expiresAt = p.optLong("expiresAt", 0);
        }
        JSONObject st = o.optJSONObject("stats");
        if (st != null) {
            a.stats = new AgentStats();
            a.stats.contextUsed = st.optLong("contextUsed", 0);
            a.stats.contextWindow = st.optLong("contextWindow", 0);
            a.stats.tokensIn = st.optLong("tokensIn", 0);
            a.stats.tokensOut = st.optLong("tokensOut", 0);
            a.stats.calls = st.optInt("calls", 0);
            a.stats.firstAt = st.isNull("firstAt") ? 0 : st.optLong("firstAt", 0);
            a.stats.lastAt = st.isNull("lastAt") ? 0 : st.optLong("lastAt", 0);
            a.stats.costUsd = st.isNull("costUsd") ? -1 : st.optDouble("costUsd", -1);
        }
        JSONObject say = o.optJSONObject("lastSay");
        if (say != null) {
            a.lastSay = new AgentSay();
            a.lastSay.text = say.optString("text", "");
            a.lastSay.status = say.optString("status", "sent");
            a.lastSay.error = say.optString("error", "");
            a.lastSay.at = say.optLong("at", 0);
        }
        return a;
    }

    /** A spoken instruction: the Worker transcribes it and the agent's bridge types it. */
    public void sayToAgent(String id, byte[] wav, Callback<SayResult> cb) {
        new Thread(() -> {
            try {
                HttpURLConnection c = open("/api/agents/say?id=" + URLEncoder.encode(id, "UTF-8"), "POST");
                c.setRequestProperty("Content-Type", "audio/wav");
                c.setFixedLengthStreamingMode(wav.length);
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(wav); }
                JSONObject j = new JSONObject(read(c));
                SayResult r = new SayResult();
                r.ok = j.optBoolean("ok", false);
                r.text = j.optString("text", "");
                r.error = j.optString("error", r.ok ? "" : "Not sent");
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    /** Words for an agent as text: what AppTabs over USB takes. */
    public void sayTextToAgent(String id, String text, Callback<SayResult> cb) {
        new Thread(() -> {
            try {
                String body = new JSONObject().put("text", text).toString();
                JSONObject j = new JSONObject(httpPostJson("/api/agents/say?id=" + URLEncoder.encode(id, "UTF-8"), body));
                SayResult r = new SayResult();
                r.ok = j.optBoolean("ok", false);
                r.text = j.optString("text", text);
                r.error = j.optString("error", r.ok ? "" : "Not sent");
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    /** Speech to text on the Room Brain (Whisper), for agents reached another way. */
    public void transcribe(byte[] wav, Callback<SayResult> cb) {
        new Thread(() -> {
            try {
                HttpURLConnection c = open("/api/transcribe", "POST");
                c.setRequestProperty("Content-Type", "audio/wav");
                c.setFixedLengthStreamingMode(wav.length);
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(wav); }
                if (c.getResponseCode() == 404) throw new Exception("Voice for AppTabs agents needs the Room Brain deployed again (it has no /api/transcribe yet)");
                JSONObject j = new JSONObject(read(c));
                SayResult r = new SayResult();
                r.ok = j.optBoolean("ok", false);
                r.text = j.optString("text", "");
                r.error = j.optString("error", r.ok ? "" : "Not heard");
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    public void decideAgent(String id, String permissionId, boolean allow, Callback<String> cb) {
        postAgent("/api/agents/decide", new JSONObjectSafe().put("id", id).put("permissionId", permissionId).put("allow", allow), cb);
    }

    public void focusAgent(String id, Callback<String> cb) {
        postAgent("/api/agents/focus", new JSONObjectSafe().put("id", id), cb);
    }

    public void dismissAgent(String id, Callback<String> cb) {
        postAgent("/api/agents/dismiss", new JSONObjectSafe().put("id", id), cb);
    }

    public void setWallApprovals(boolean on, Callback<String> cb) {
        postAgent("/api/agents/settings", new JSONObjectSafe().put("wallApprovals", on), cb);
    }

    /** null on success; the Worker's reason otherwise, as the error. */
    private void postAgent(String path, JSONObjectSafe req, Callback<String> cb) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(httpPostJson(path, req.toString()));
                if (!j.optBoolean("ok", false)) throw new Exception(j.optString("error", "Failed"));
                cb.onResult(null, null);
            } catch (Exception e) { cb.onResult(null, e); }
        }).start();
    }

    // ---- Parsing helpers ----
    private static List<Track> parseTracks(JSONArray arr) {
        List<Track> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Track t = new Track();
            t.uri = optStr(o, "uri");
            t.name = optStr(o, "name");
            t.artists = optStr(o, "artists");
            t.artUrl = optStr(o, "artUrl");
            out.add(t);
        }
        return out;
    }

    private static List<Playlist> parsePlaylists(JSONArray arr) {
        List<Playlist> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Playlist p = new Playlist();
            p.id = optStr(o, "id");
            p.uri = optStr(o, "uri");
            p.name = optStr(o, "name");
            p.imageUrl = optStr(o, "imageUrl");
            out.add(p);
        }
        return out;
    }

    private static JSONArray asArray(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (!t.startsWith("[")) return null; // error object or empty
        try { return new JSONArray(t); } catch (Exception e) { return null; }
    }

    private static String optStr(JSONObject j, String key) {
        return j.isNull(key) ? null : j.optString(key, null);
    }

    // ---- HTTP ----
    private String httpGet(String path) throws Exception {
        return read(open(path, "GET"));
    }

    private String httpPostJson(String path, String json) throws Exception {
        HttpURLConnection c = open(path, "POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setConnectTimeout(6000);
        // Calendar range queries fan out to every Google calendar; give them longer.
        // A meeting question carries a whole transcript to Claude, longer still.
        // A spoken instruction waits on Whisper.
        c.setReadTimeout(path.startsWith("/api/meetings/chat") ? 120000
            : path.startsWith("/api/agents/say") || path.startsWith("/api/transcribe") ? 60000
            : path.startsWith("/api/calendar/") || path.startsWith("/api/transit") || path.startsWith("/api/meetings") ? 35000
            : 12000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + authToken);
        return c;
    }

    private static String read(HttpURLConnection c) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
            c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line);
        r.close();
        return b.toString();
    }

    /** Tiny wrapper so request building never throws checked JSONException at call sites. */
    private static class JSONObjectSafe {
        private final JSONObject o = new JSONObject();
        JSONObjectSafe put(String k, String v) { try { o.put(k, v); } catch (Exception ignored) {} return this; }
        JSONObjectSafe put(String k, boolean v) { try { o.put(k, v); } catch (Exception ignored) {} return this; }
        JSONObjectSafe putArray(String k, String v) {
            try { o.put(k, new JSONArray().put(v)); } catch (Exception ignored) {}
            return this;
        }
        @Override public String toString() { return o.toString(); }
    }
}

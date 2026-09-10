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
        c.setReadTimeout(path.startsWith("/api/calendar/") || path.startsWith("/api/transit") ? 35000 : 12000);
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
        JSONObjectSafe putArray(String k, String v) {
            try { o.put(k, new JSONArray().put(v)); } catch (Exception ignored) {}
            return this;
        }
        @Override public String toString() { return o.toString(); }
    }
}

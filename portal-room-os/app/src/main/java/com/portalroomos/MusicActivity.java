package com.portalroomos;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MusicActivity extends Activity {
    private RoomBrainClient brain;
    private final Handler handler = new Handler();

    private ImageView art;
    private TextView trackTitle, trackArtist, listTitle, statusText, deviceLabel;
    private Button playPause;
    private EditText searchInput;
    private ListView list;
    private ItemAdapter adapter;

    private String selectedDeviceId = null;
    private String selectedDeviceName = "active device";
    private String lastArtUrl = null;
    private boolean lastPlaying = false;

    // A generic list row.
    private static class Item {
        String title, subtitle, type, uri, id; // type: track | playlist | playall | back
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_music);
        hideSystemUi();

        brain = new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN);

        art = findViewById(R.id.art);
        trackTitle = findViewById(R.id.trackTitle);
        trackArtist = findViewById(R.id.trackArtist);
        listTitle = findViewById(R.id.listTitle);
        statusText = findViewById(R.id.statusText);
        deviceLabel = findViewById(R.id.deviceLabel);
        playPause = findViewById(R.id.playPause);
        searchInput = findViewById(R.id.searchInput);
        list = findViewById(R.id.list);

        adapter = new ItemAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onItemClick(adapter.items.get(position)));

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.prevBtn).setOnClickListener(v -> transport("previous", "Back"));
        findViewById(R.id.nextBtn).setOnClickListener(v -> transport("next", "Skipped"));
        playPause.setOnClickListener(v -> transport(lastPlaying ? "pause" : "play", null));
        findViewById(R.id.searchBtn).setOnClickListener(v -> doSearch());
        findViewById(R.id.playlistsBtn).setOnClickListener(v -> loadPlaylists());
        findViewById(R.id.deviceBtn).setOnClickListener(v -> pickDevice());

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                doSearch();
                return true;
            }
            return false;
        });

        deviceLabel.setText("Playing on: " + selectedDeviceName);
        loadPlaylists();
        handler.post(nowTick);
    }

    // ---- now-playing polling ----
    private final Runnable nowTick = new Runnable() {
        @Override public void run() {
            brain.getPlayback((p, err) -> runOnUiThread(() -> {
                if (err == null && p != null) updatePlayback(p);
            }));
            handler.postDelayed(this, 4000);
        }
    };

    private void updatePlayback(RoomBrainClient.Playback p) {
        lastPlaying = p.isPlaying;
        trackTitle.setText(p.track != null ? p.track : "Nothing playing");
        trackArtist.setText(p.artists != null ? p.artists : "");
        playPause.setText(p.isPlaying ? "Pause" : "Play");
        loadArt(p.artUrl);
    }

    // ---- actions ----
    private void transport(String action, String okLabel) {
        brain.transport(action, (msg, err) -> runOnUiThread(() -> {
            setStatus(err == null ? (okLabel != null ? okLabel : msg) : "Offline");
            handler.postDelayed(() -> brain.getPlayback((p, e) ->
                runOnUiThread(() -> { if (e == null && p != null) updatePlayback(p); })), 600);
        }));
    }

    private void doSearch() {
        String q = searchInput.getText().toString().trim();
        if (q.isEmpty()) return;
        setStatus("Searching…");
        brain.search(q, (r, err) -> runOnUiThread(() -> {
            if (err != null || r == null) { setStatus("Search failed"); return; }
            List<Item> items = new ArrayList<>();
            for (RoomBrainClient.Track t : r.tracks) {
                Item it = new Item();
                it.type = "track"; it.title = t.name; it.subtitle = t.artists; it.uri = t.uri;
                items.add(it);
            }
            for (RoomBrainClient.Playlist pl : r.playlists) {
                Item it = new Item();
                it.type = "playlist"; it.title = pl.name; it.subtitle = "Playlist"; it.id = pl.id; it.uri = pl.uri;
                items.add(it);
            }
            listTitle.setText("Results: " + q);
            adapter.set(items);
            setStatus(items.isEmpty() ? "No results" : items.size() + " results");
        }));
    }

    private void loadPlaylists() {
        setStatus("Loading playlists…");
        brain.getPlaylists((pls, err) -> runOnUiThread(() -> {
            if (err != null) { setStatus("Offline"); return; }
            List<Item> items = new ArrayList<>();
            for (RoomBrainClient.Playlist pl : pls) {
                Item it = new Item();
                it.type = "playlist"; it.title = pl.name; it.subtitle = "Playlist"; it.id = pl.id; it.uri = pl.uri;
                items.add(it);
            }
            listTitle.setText("Your playlists");
            adapter.set(items);
            setStatus(items.isEmpty() ? "No playlists — re-link Spotify for playlist access" : items.size() + " playlists");
        }));
    }

    private void openPlaylist(String id, String name, String contextUri) {
        setStatus("Loading " + name + "…");
        brain.getPlaylistTracks(id, (tracks, err) -> runOnUiThread(() -> {
            if (err != null || tracks == null) { setStatus("Couldn't load playlist"); return; }
            List<Item> items = new ArrayList<>();
            Item back = new Item(); back.type = "back"; back.title = "‹ Back to playlists"; items.add(back);
            Item all = new Item(); all.type = "playall"; all.title = "▶  Play this playlist"; all.uri = contextUri; items.add(all);
            for (RoomBrainClient.Track t : tracks) {
                Item it = new Item();
                it.type = "track"; it.title = t.name; it.subtitle = t.artists; it.uri = t.uri;
                items.add(it);
            }
            listTitle.setText(name);
            adapter.set(items);
            setStatus(tracks.size() + " tracks");
        }));
    }

    private void onItemClick(Item item) {
        switch (item.type) {
            case "track":
                brain.play(null, item.uri, selectedDeviceId, playResult("Playing: " + item.title));
                break;
            case "playlist":
                openPlaylist(item.id, item.title, item.uri);
                break;
            case "playall":
                brain.play(item.uri, null, selectedDeviceId, playResult("Playing playlist"));
                break;
            case "back":
                loadPlaylists();
                break;
        }
    }

    private RoomBrainClient.Callback<String> playResult(final String okMsg) {
        return (msg, err) -> runOnUiThread(() -> {
            setStatus(err != null ? "Offline" : (msg != null && msg.startsWith("No active") ? msg : okMsg));
            handler.postDelayed(() -> brain.getPlayback((p, e) ->
                runOnUiThread(() -> { if (e == null && p != null) updatePlayback(p); })), 900);
        });
    }

    private void pickDevice() {
        brain.getDevices((devices, err) -> runOnUiThread(() -> {
            if (err != null) { setStatus("Couldn't load devices"); return; }
            final List<RoomBrainClient.Device> ds = devices;
            final String[] names = new String[ds.size() + 1];
            names[0] = "Active device (auto)";
            for (int i = 0; i < ds.size(); i++) {
                names[i + 1] = ds.get(i).name + (ds.get(i).isActive ? "  •" : "");
            }
            new AlertDialog.Builder(this)
                .setTitle("Play on which device?")
                .setItems(names, (dialog, which) -> {
                    if (which == 0) { selectedDeviceId = null; selectedDeviceName = "active device"; }
                    else {
                        RoomBrainClient.Device d = ds.get(which - 1);
                        selectedDeviceId = d.id; selectedDeviceName = d.name;
                        // Move current playback to the chosen device immediately.
                        brain.transfer(d.id, (m, e) -> {});
                    }
                    deviceLabel.setText("Playing on: " + selectedDeviceName);
                })
                .show();
        }));
    }

    private void setStatus(String s) { statusText.setText(s); }

    // ---- album art loader (no external libs) ----
    private void loadArt(final String url) {
        if (url == null) { art.setImageDrawable(null); lastArtUrl = null; return; }
        if (url.equals(lastArtUrl)) return;
        lastArtUrl = url;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                final Bitmap bmp = BitmapFactory.decodeStream(c.getInputStream());
                runOnUiThread(() -> { if (url.equals(lastArtUrl) && bmp != null) art.setImageBitmap(bmp); });
            } catch (Exception ignored) {}
        }).start();
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

    // ---- list adapter ----
    private class ItemAdapter extends BaseAdapter {
        final List<Item> items = new ArrayList<>();
        void set(List<Item> newItems) { items.clear(); items.addAll(newItems); notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) v = getLayoutInflater().inflate(R.layout.row_item, parent, false);
            Item it = items.get(position);
            ((TextView) v.findViewById(R.id.itemTitle)).setText(it.title);
            TextView sub = v.findViewById(R.id.itemSubtitle);
            if (it.subtitle == null || it.subtitle.isEmpty()) { sub.setVisibility(View.GONE); }
            else { sub.setVisibility(View.VISIBLE); sub.setText(it.subtitle); }
            return v;
        }
    }
}

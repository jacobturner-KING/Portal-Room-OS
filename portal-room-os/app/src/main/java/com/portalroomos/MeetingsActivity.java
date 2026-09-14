package com.portalroomos;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Meetings from Granola, on the wall. The list comes from the Room Brain's
 * /api/meetings; picking one shows its notes, its transcript, or a chat about
 * it. A question with a meeting open goes to Claude with that meeting's notes
 * and transcript; with none open it goes to Granola's own search across every
 * meeting, so the screen is useful before anything is picked.
 *
 * Granola's answers are prose or JSON as it pleases, and the Worker parses the
 * list on a best-effort basis. When it could not find any meetings in what came
 * back, the raw text is shown in the list column instead of an empty panel, so
 * a parser that has fallen behind Granola still leaves something readable.
 */
public class MeetingsActivity extends Activity {
    private enum Tab { NOTES, TRANSCRIPT, CHAT }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.US);

    private final Handler handler = new Handler();
    private RoomBrainClient brain;
    private RecordChip recordChip;
    /** A recording that just finished: selected once the list has it. */
    private String pendingSelectId;
    private LinearLayout meetingList, chatList;
    private ScrollView bodyScroll;
    private TextView detailTitle, detailMeta, bodyText, statusText;
    private Button notesTab, transcriptTab, chatTab, sendBtn;
    private EditText searchInput, chatInput;
    private View chatRow;

    private final List<RoomBrainClient.Meeting> meetings = new ArrayList<>();
    private final Map<String, String> notesCache = new HashMap<>();
    private final Map<String, String> transcriptCache = new HashMap<>();
    /** One conversation per meeting, plus one for the cross-meeting box (key ""). */
    private final Map<String, List<RoomBrainClient.ChatTurn>> chats = new HashMap<>();
    /** Who answered each conversation last, for the label on its rows. */
    private final Map<String, String> answeredBy = new HashMap<>();
    private RoomBrainClient.Meeting selected;
    private Tab tab = Tab.CHAT;
    private boolean asking;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_meetings);
        hideSystemUi();

        brain = new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN);
        meetingList = findViewById(R.id.meetingList);
        chatList = findViewById(R.id.chatList);
        bodyScroll = findViewById(R.id.bodyScroll);
        detailTitle = findViewById(R.id.detailTitle);
        detailMeta = findViewById(R.id.detailMeta);
        bodyText = findViewById(R.id.bodyText);
        statusText = findViewById(R.id.statusText);
        notesTab = findViewById(R.id.notesTab);
        transcriptTab = findViewById(R.id.transcriptTab);
        chatTab = findViewById(R.id.chatTab);
        sendBtn = findViewById(R.id.sendBtn);
        searchInput = findViewById(R.id.searchInput);
        chatInput = findViewById(R.id.chatInput);
        chatRow = findViewById(R.id.chatRow);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.refreshBtn).setOnClickListener(v -> load());
        recordChip = new RecordChip(this, findViewById(R.id.recordChip), findViewById(R.id.recordMeter), statusText);
        searchInput.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || ev != null) { load(); hideKeyboard(v); return true; }
            return false;
        });
        notesTab.setOnClickListener(v -> showTab(Tab.NOTES));
        transcriptTab.setOnClickListener(v -> showTab(Tab.TRANSCRIPT));
        chatTab.setOnClickListener(v -> showTab(Tab.CHAT));
        sendBtn.setOnClickListener(v -> send());
        chatInput.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || ev != null) { send(); return true; }
            return false;
        });

        select(null);
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    /** Keeps the chip's clock moving, and pulls a recording in the moment it is saved. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String saved = recordChip.refresh();
            if (saved != null) {
                pendingSelectId = saved;
                load();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        recordChip.onPermissionResult(code, results);
    }

    // ---- the list ----

    private void load() {
        statusText.setText("Loading meetings…");
        brain.getMeetings(searchInput.getText().toString(), (r, err) -> runOnUiThread(() -> {
            meetingList.removeAllViews();
            meetings.clear();
            if (err != null) {
                statusText.setText(explain(err));
                addNote("not_connected".equals(err.getMessage())
                    ? "Granola is not linked yet. Open the Room Brain's /connect page and link it."
                    : "Could not reach the Room Brain.");
                return;
            }
            meetings.addAll(r.meetings);
            if (!r.granola) addNote("Granola is not linked. Open the Room Brain's /connect page to link it; recordings made here still show below.");
            if (meetings.isEmpty()) {
                if (r.raw.isEmpty()) addNote("No meetings yet. Tap Record on the dashboard to make one here.");
                else {
                    // Granola answered; the Worker just could not pick meetings out of it.
                    addNote("Granola's answer, as it came:");
                    addNote(r.raw);
                }
            }
            for (RoomBrainClient.Meeting m : meetings) addRow(m);
            int here = 0;
            for (RoomBrainClient.Meeting m : meetings) if (m.isPortal()) here++;
            statusText.setText(meetings.isEmpty() ? ""
                : meetings.size() + " meetings" + (here > 0 ? " · " + here + " recorded here" : " · Granola"));
            if (pendingSelectId != null) {
                for (RoomBrainClient.Meeting m : meetings) {
                    if (m.id.equals(pendingSelectId)) { select(m); break; }
                }
                pendingSelectId = null;
                return;
            }
            if (selected != null) {
                // Keep the open meeting if it is still in the list; otherwise start over.
                RoomBrainClient.Meeting still = null;
                for (RoomBrainClient.Meeting m : meetings) if (m.id.equals(selected.id)) still = m;
                if (still == null) select(null); else highlight();
            }
        }));
    }

    private void addRow(RoomBrainClient.Meeting m) {
        View row = getLayoutInflater().inflate(R.layout.row_meeting, meetingList, false);
        ((TextView) row.findViewById(R.id.meetingTitle)).setText(m.title == null || m.title.isEmpty() ? "Untitled meeting" : m.title);
        ((TextView) row.findViewById(R.id.meetingSub)).setText(subtitle(m));
        row.setTag(m.id);
        row.setOnClickListener(v -> select(m));
        meetingList.addView(row);
    }

    private void addNote(String text) {
        TextView tv = (TextView) getLayoutInflater().inflate(R.layout.row_transit_note, meetingList, false);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        tv.setText(text);
        meetingList.addView(tv);
    }

    private static String subtitle(RoomBrainClient.Meeting m) {
        String when = prettyDate(m.date);
        if (m.isPortal()) {
            if ("recording".equals(m.status)) return "Recording here now · " + when;
            String len = m.durationSec < 60 ? m.durationSec + " s" : Math.round(m.durationSec / 60f) + " min";
            return "Recorded here · " + when + " · " + len;
        }
        String who = m.attendees.isEmpty() ? "" : String.join(", ", m.attendees);
        if (when.isEmpty()) return who;
        if (who.isEmpty()) return when;
        return when + " · " + who;
    }

    private static String prettyDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(WHEN);
        } catch (Exception e) {
            return iso; // Granola gave prose; show it as it came
        }
    }

    private void highlight() {
        for (int i = 0; i < meetingList.getChildCount(); i++) {
            View row = meetingList.getChildAt(i);
            TextView title = row.findViewById(R.id.meetingTitle);
            if (title == null) continue;
            boolean on = selected != null && selected.id.equals(row.getTag());
            title.setTextColor(on ? CalUi.ACCENT : CalUi.TEXT);
            row.setBackgroundColor(on ? CalUi.TODAY_TINT : 0);
        }
    }

    // ---- one meeting ----

    private void select(RoomBrainClient.Meeting m) {
        selected = m;
        highlight();
        if (m == null) {
            detailTitle.setText("Ask across every meeting");
            detailMeta.setText("Pick a meeting on the left, or ask Granola a question below");
            chatInput.setHint("Ask Granola about any meeting");
            showTab(Tab.CHAT);
        } else {
            detailTitle.setText(m.title == null || m.title.isEmpty() ? "Untitled meeting" : m.title);
            detailMeta.setText(subtitle(m));
            chatInput.setHint("Ask about this meeting");
            showTab(Tab.NOTES);
        }
    }

    private void showTab(Tab wanted) {
        boolean hasMeeting = selected != null;
        final Tab t = hasMeeting ? wanted : Tab.CHAT; // nothing to read without a meeting
        tab = t;
        notesTab.setEnabled(hasMeeting);
        transcriptTab.setEnabled(hasMeeting);
        notesTab.setTextColor(t == Tab.NOTES ? CalUi.ACCENT : hasMeeting ? CalUi.TEXT : CalUi.MUTED);
        transcriptTab.setTextColor(t == Tab.TRANSCRIPT ? CalUi.ACCENT : hasMeeting ? CalUi.TEXT : CalUi.MUTED);
        chatTab.setTextColor(t == Tab.CHAT ? CalUi.ACCENT : CalUi.TEXT);
        chatRow.setVisibility(t == Tab.CHAT ? View.VISIBLE : View.GONE);
        chatList.setVisibility(t == Tab.CHAT ? View.VISIBLE : View.GONE);
        bodyText.setVisibility(t == Tab.CHAT ? View.GONE : View.VISIBLE);
        if (t == Tab.CHAT) { renderChat(); return; }

        String id = selected.id;
        Map<String, String> cache = t == Tab.NOTES ? notesCache : transcriptCache;
        String have = cache.get(id);
        if (have != null) { bodyText.setText(readable(have)); bodyScroll.scrollTo(0, 0); return; }
        bodyText.setText(t == Tab.NOTES ? "Loading notes…" : "Loading transcript…");
        RoomBrainClient.Callback<String> cb = (text, err) -> runOnUiThread(() -> {
            if (err != null) {
                if (selected != null && selected.id.equals(id) && tab == t) bodyText.setText(explain(err));
                return;
            }
            cache.put(id, text);
            if (selected != null && selected.id.equals(id) && tab == t) {
                bodyText.setText(text.isEmpty() ? (t == Tab.NOTES ? "No notes for this meeting." : "No transcript for this meeting.") : readable(text));
                bodyScroll.scrollTo(0, 0);
            }
        });
        if (t == Tab.NOTES) brain.getMeetingNotes(id, cb); else brain.getMeetingTranscript(id, cb);
    }

    /** Granola writes markdown; on a wall the marks are noise, the structure is not. */
    private static String readable(String s) {
        return s.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("(?m)^(\\s*)[-*]\\s+", "$1• ")
                .trim();
    }

    // ---- chat ----

    private List<RoomBrainClient.ChatTurn> history() {
        String key = selected == null ? "" : selected.id;
        List<RoomBrainClient.ChatTurn> h = chats.get(key);
        if (h == null) { h = new ArrayList<>(); chats.put(key, h); }
        return h;
    }

    private void renderChat() {
        chatList.removeAllViews();
        List<RoomBrainClient.ChatTurn> h = history();
        if (h.isEmpty()) {
            addTurn("", selected == null
                ? "Granola searches every meeting it holds. Try \"What did we decide about pricing last week?\""
                : "Questions here are answered from this meeting alone.", true);
        }
        for (RoomBrainClient.ChatTurn t : h) addTurn(t.role, t.content, false);
        bodyScroll.post(() -> bodyScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String answerer() {
        String key = selected == null ? "" : selected.id;
        String by = answeredBy.get(key);
        if ("claude".equals(by)) return "CLAUDE";
        if ("workers-ai".equals(by)) return "ROOM BRAIN";
        return "GRANOLA";
    }

    private TextView addTurn(String role, String text, boolean hint) {
        View row = getLayoutInflater().inflate(R.layout.row_chat, chatList, false);
        TextView who = row.findViewById(R.id.chatWho);
        TextView body = row.findViewById(R.id.chatText);
        if (hint) {
            who.setVisibility(View.GONE);
            body.setTextColor(CalUi.MUTED);
        } else {
            boolean user = "user".equals(role);
            who.setText(user ? "YOU" : answerer());
            who.setTextColor(user ? CalUi.MUTED : CalUi.ACCENT);
        }
        body.setText(text);
        chatList.addView(row);
        return body;
    }

    private void send() {
        if (asking) return;
        String q = chatInput.getText().toString().trim();
        if (q.isEmpty()) return;
        chatInput.setText("");
        hideKeyboard(chatInput);
        if (tab != Tab.CHAT) showTab(Tab.CHAT);

        RoomBrainClient.Meeting m = selected;
        List<RoomBrainClient.ChatTurn> h = history();
        if (h.isEmpty()) chatList.removeAllViews(); // the hint goes once there is a real turn
        h.add(new RoomBrainClient.ChatTurn("user", q));
        addTurn("user", q, false);
        TextView pending = addTurn("assistant", "…", false);
        bodyScroll.post(() -> bodyScroll.fullScroll(View.FOCUS_DOWN));
        asking = true;
        sendBtn.setEnabled(false);
        statusText.setText("Asking…");

        brain.chatMeeting(m, new ArrayList<>(h), (r, err) -> runOnUiThread(() -> {
            asking = false;
            sendBtn.setEnabled(true);
            if (err != null) {
                h.remove(h.size() - 1); // let the question be asked again
                pending.setText(explain(err));
                pending.setTextColor(CalUi.MUTED);
                statusText.setText("");
                return;
            }
            String reply = readable(r.reply); // Granola answers in markdown too
            h.add(new RoomBrainClient.ChatTurn("assistant", reply));
            answeredBy.put(m == null ? "" : m.id, r.source);
            pending.setText(reply);
            View row = (View) pending.getParent();
            ((TextView) row.findViewById(R.id.chatWho)).setText(answerer());
            statusText.setText("claude".equals(r.source)
                ? "Answered by Claude from this meeting's notes and transcript"
                : "workers-ai".equals(r.source) ? "Answered by the Room Brain from this recording's transcript"
                : m == null ? "Answered by Granola across your meetings" : "Answered by Granola from this meeting");
            bodyScroll.post(() -> bodyScroll.fullScroll(View.FOCUS_DOWN));
        }));
    }

    private static String explain(Exception err) {
        String m = err.getMessage() == null ? "" : err.getMessage();
        if ("not_connected".equals(m)) return "Granola is not linked · open the Room Brain's /connect page";
        if ("chat_not_configured".equals(m)) return "Meeting chat needs ANTHROPIC_API_KEY on the Room Brain";
        if (err instanceof java.net.SocketTimeoutException) return "The Room Brain took too long · try again";
        return "Room Brain unavailable: " + (m.isEmpty() ? err.getClass().getSimpleName() : m);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        hideSystemUi();
    }

    private int dp(int d) { return Math.round(CalUi.dp(meetingList, d)); }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
}

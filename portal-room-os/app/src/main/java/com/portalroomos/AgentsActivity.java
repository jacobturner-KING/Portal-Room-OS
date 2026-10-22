package com.portalroomos;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agents on the wall: every coding agent on every machine, from the Room
 * Brain's agent hub through {@link AgentWatch}.
 *
 * One at a time, each agent gets the screen: its pet acting out the work on
 * the left, and on the right what it is doing, what you last asked and what it
 * last said. Swipe for the next. Tap the pet (or Talk) and it puts on
 * headphones; say what you want and tap anywhere to send it. The Room Brain
 * turns the words into text and the bridge on that machine types them into the
 * agent's terminal. A permission prompt held for the panel shows Approve and
 * Deny; "Show on Mac" brings the agent's terminal to the front there.
 *
 * All at once, every agent is a tile; tap one to give it the screen.
 */
public class AgentsActivity extends Activity implements AgentWatch.Listener {

    static final String EXTRA_AGENT_ID = "agentId";
    private static final String EMPTY_HINT = "Link AppTabs on the Mac over USB or Wi-Fi, or set up a machine with portal-room-os/agents/install.mjs. Each Claude Code, Codex or Cursor session shows up here.";
    private static final int MIC_REQUEST = 7;

    private final Handler handler = new Handler();
    private RoomBrainClient.AgentSnapshot snapshot;
    private String selectedId;
    private boolean grid;

    private View single, gridScroll, empty, listening, askRow, petPanel;
    private LinearLayout gridList, dots;
    private PetView pet, emptyPet;
    private TextView summary, agentLine, project, stateLine, statsLine, activity, attention, askText;
    private TextView promptLabel, prompt, replyLabel, reply, sayLine, status, petHint, listeningText, emptyText, emptyHint;
    private Button approvalsBtn, viewBtn, sortBtn, talkBtn, focusBtn;
    private LevelMeterView voiceMeter;
    private GestureDetector swipes;

    /** Push-to-talk in progress, and for which agent. */
    private VoiceNote voice;
    private String talkingTo;
    /** An instruction on its way, so the pet shows it is sending. */
    private String sendingTo;

    private String gridKey = "";
    private final Map<String, View> tiles = new HashMap<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_agents);

        single = findViewById(R.id.single);
        gridScroll = findViewById(R.id.gridScroll);
        gridList = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        listening = findViewById(R.id.listening);
        askRow = findViewById(R.id.askRow);
        petPanel = findViewById(R.id.petPanel);
        dots = findViewById(R.id.dots);
        pet = findViewById(R.id.pet);
        emptyPet = findViewById(R.id.emptyPet);
        summary = findViewById(R.id.summary);
        agentLine = findViewById(R.id.agentLine);
        project = findViewById(R.id.project);
        stateLine = findViewById(R.id.stateLine);
        statsLine = findViewById(R.id.statsLine);
        activity = findViewById(R.id.activity);
        attention = findViewById(R.id.attention);
        askText = findViewById(R.id.askText);
        promptLabel = findViewById(R.id.promptLabel);
        prompt = findViewById(R.id.prompt);
        replyLabel = findViewById(R.id.replyLabel);
        reply = findViewById(R.id.reply);
        sayLine = findViewById(R.id.sayLine);
        status = findViewById(R.id.status);
        petHint = findViewById(R.id.petHint);
        listeningText = findViewById(R.id.listeningText);
        approvalsBtn = findViewById(R.id.approvalsBtn);
        viewBtn = findViewById(R.id.viewBtn);
        sortBtn = findViewById(R.id.sortBtn);
        emptyText = findViewById(R.id.emptyText);
        emptyHint = findViewById(R.id.emptyHint);
        try {
            sort = Sort.valueOf(getSharedPreferences("agents_screen", MODE_PRIVATE).getString("sort", Sort.STARTED.name()));
        } catch (IllegalArgumentException ignored) {
            sort = Sort.STARTED;
        }
        sortBtn.setOnClickListener(v -> cycleSort());
        talkBtn = findViewById(R.id.talkBtn);
        focusBtn = findViewById(R.id.focusBtn);
        voiceMeter = findViewById(R.id.voiceMeter);
        emptyPet.setMood(PetView.Mood.SLEEP, "");

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        viewBtn.setOnClickListener(v -> {
            grid = !grid;
            render();
        });
        approvalsBtn.setOnClickListener(v -> toggleApprovals());
        pet.setOnClickListener(v -> startTalk());
        talkBtn.setOnClickListener(v -> startTalk());
        listening.setOnClickListener(v -> sendTalk());
        findViewById(R.id.cancelTalkBtn).setOnClickListener(v -> cancelTalk("Not sent"));
        findViewById(R.id.allowBtn).setOnClickListener(v -> decide(true));
        findViewById(R.id.denyBtn).setOnClickListener(v -> decide(false));
        focusBtn.setOnClickListener(v -> {
            RoomBrainClient.Agent a = selected();
            if (a == null) return;
            status.setText("Bringing it forward on " + a.host + "…");
            AgentWatch.shared().clientFor(a).focusAgent(a.id, (r, e) -> runOnUiThread(() ->
                status.setText(e == null ? "Showing on " + a.host : e.getMessage())));
        });
        findViewById(R.id.dismissBtn).setOnClickListener(v -> {
            RoomBrainClient.Agent a = selected();
            if (a == null) return;
            AgentWatch.shared().clientFor(a).dismissAgent(a.id, (r, e) -> runOnUiThread(() -> {
                status.setText(e == null ? "Dismissed; it comes back if it does anything" : e.getMessage());
                AgentWatch.shared().refreshNow();
            }));
        });

        swipes = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) < 120 || Math.abs(dx) < Math.abs(dy) * 1.5f || Math.abs(vx) < 600) return false;
                step(dx < 0 ? 1 : -1);
                return true;
            }
        });

        selectedId = getIntent().getStringExtra(EXTRA_AGENT_ID);
        hideSystemUi();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String id = intent.getStringExtra(EXTRA_AGENT_ID);
        if (id != null) {
            selectedId = id;
            grid = false;
            render();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        AgentWatch.shared().addListener(this);
        AgentWatch.shared().setFast(true);
        AgentWatch.shared().markSeen();
        if (!AgentWatch.shared().configured()) status.setText("No Room Brain in local.properties, and no AppTabs link over USB");
    }

    @Override protected void onPause() {
        cancelTalk(null);
        AgentWatch.shared().removeListener(this);
        AgentWatch.shared().setFast(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!grid && voice == null && single.getVisibility() == View.VISIBLE && swipes.onTouchEvent(ev)
            && ev.getActionMasked() == MotionEvent.ACTION_UP) {
            // A swipe is not a tap: whatever the finger started on must not
            // click, least of all Approve on the agent that just slid in.
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---- AgentWatch ----

    @Override public void onAgents(RoomBrainClient.AgentSnapshot s, String error) {
        if (error != null) {
            status.setText("Room Brain unavailable · " + error);
            if (snapshot == null) render();
            return;
        }
        snapshot = s;
        AgentWatch.shared().markSeen();
        String bridges = s.bridges.isEmpty()
            ? "No bridge connected · link AppTabs over USB, or run portal-agent-bridge on the agent's machine"
            : "Connected: " + String.join(", ", s.bridges);
        status.setText(s.note.isEmpty() ? bridges : bridges + " · " + s.note);
        render();
    }

    // ---- What is shown ----

    private List<RoomBrainClient.Agent> agents() {
        return snapshot == null ? new ArrayList<>() : snapshot.sessions;
    }

    // ---- Sorting: the order the screen swipes through and the tiles sit in ----

    enum Sort {
        STARTED("Started"), UPDATED("Last updated"), TODAY("Today"), PROJECT("Project");
        final String label;
        Sort(String label) { this.label = label; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    /** An order by recent activity moves no more often than this, so tiles hold still to be read and tapped. */
    private static final long REORDER_MS = 30_000;
    private Sort sort = Sort.STARTED;
    private List<String> order = new ArrayList<>();
    private Sort orderSort;
    private long orderAt;

    private static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String projectOf(RoomBrainClient.Agent a) {
        return a.project == null ? "" : a.project;
    }

    /**
     * What the screen shows, in the chosen order. Started keeps the order agents
     * began in; Last updated and Today put the latest activity first (Today only
     * what moved since midnight); Project groups by repository, A to Z. The order
     * is worked out again when the sort or the set of agents changes, and for the
     * activity sorts at most every REORDER_MS; in between, changes update in place.
     */
    private List<RoomBrainClient.Agent> shown() {
        Map<String, RoomBrainClient.Agent> byId = new HashMap<>();
        List<RoomBrainClient.Agent> eligible = new ArrayList<>();
        long midnight = startOfToday();
        for (RoomBrainClient.Agent a : agents()) {
            if (sort == Sort.TODAY && a.updatedAt < midnight) continue;
            byId.put(a.id, a);
            eligible.add(a);
        }
        long now = System.currentTimeMillis();
        boolean sameSet = order.size() == byId.size() && byId.keySet().containsAll(order);
        boolean stale = (sort == Sort.UPDATED || sort == Sort.TODAY) && now - orderAt > REORDER_MS;
        if (sort != orderSort || !sameSet || stale) {
            List<RoomBrainClient.Agent> sorted = new ArrayList<>(eligible);
            switch (sort) {
                case UPDATED:
                case TODAY:
                    sorted.sort((x, y) -> Long.compare(y.updatedAt, x.updatedAt));
                    break;
                case PROJECT:
                    sorted.sort((x, y) -> {
                        String px = projectOf(x), py = projectOf(y);
                        // Agents with no project go last.
                        if (px.isEmpty() != py.isEmpty()) return px.isEmpty() ? 1 : -1;
                        int byName = px.compareToIgnoreCase(py);
                        return byName != 0 ? byName : Long.compare(y.updatedAt, x.updatedAt);
                    });
                    break;
                default:
                    // The sources already send agents in the order they started.
            }
            order = new ArrayList<>();
            for (RoomBrainClient.Agent a : sorted) order.add(a.id);
            orderSort = sort;
            orderAt = now;
        }
        List<RoomBrainClient.Agent> out = new ArrayList<>();
        for (String id : order) {
            RoomBrainClient.Agent a = byId.get(id);
            if (a != null) out.add(a);
        }
        return out;
    }

    private void cycleSort() {
        sort = sort.next();
        getSharedPreferences("agents_screen", MODE_PRIVATE).edit().putString("sort", sort.name()).apply();
        selectedId = null;
        render();
    }

    private RoomBrainClient.Agent selected() {
        List<RoomBrainClient.Agent> list = shown();
        if (list.isEmpty()) return null;
        for (RoomBrainClient.Agent a : list) if (a.id.equals(selectedId)) return a;
        // Nothing chosen, or it went away: whoever needs you, else the busiest, else the first.
        RoomBrainClient.Agent pick = list.get(0);
        for (RoomBrainClient.Agent a : list) {
            if ("waiting".equals(a.state)) { pick = a; break; }
            if ("working".equals(a.state) && !"working".equals(pick.state)) pick = a;
        }
        selectedId = pick.id;
        return pick;
    }

    private void step(int by) {
        List<RoomBrainClient.Agent> list = shown();
        if (list.size() < 2) return;
        RoomBrainClient.Agent cur = selected();
        int i = Math.max(0, list.indexOf(cur));
        selectedId = list.get((i + by + list.size()) % list.size()).id;
        // Slide the panel in from the side it came from.
        float from = by > 0 ? 80f : -80f;
        single.setAlpha(0.4f);
        single.setTranslationX(from);
        single.animate().translationX(0f).alpha(1f).setDuration(220).start();
        render();
    }

    private long serverNow() {
        if (snapshot == null) return System.currentTimeMillis();
        return snapshot.serverNow + (System.currentTimeMillis() - snapshot.fetchedAt);
    }

    private void render() {
        List<RoomBrainClient.Agent> list = shown();
        boolean none = list.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        single.setVisibility(!none && !grid ? View.VISIBLE : View.GONE);
        gridScroll.setVisibility(!none && grid ? View.VISIBLE : View.GONE);
        viewBtn.setText(grid ? "One at a time" : "All agents");
        viewBtn.setEnabled(!none);
        sortBtn.setText("Sort: " + sort.label);
        if (none && sort == Sort.TODAY && !agents().isEmpty()) {
            emptyText.setText("Nothing active today");
            emptyHint.setText("Tap Sort to see every agent.");
        } else {
            emptyText.setText("No agents yet");
            emptyHint.setText(EMPTY_HINT);
        }

        boolean wall = snapshot != null && snapshot.wallApprovals;
        approvalsBtn.setText(wall ? "Approve here: on" : "Approve here: off");
        approvalsBtn.setTextColor(getColor(wall ? R.color.accent : R.color.muted));

        int working = 0, needs = 0;
        for (RoomBrainClient.Agent a : list) {
            if ("working".equals(a.state)) working++;
            if ("waiting".equals(a.state)) needs++;
        }
        summary.setText(none ? "" : list.size() + (list.size() == 1 ? " agent" : " agents") + (sort == Sort.TODAY ? " today" : "")
            + (working > 0 ? " · " + working + " working" : "")
            + (needs > 0 ? " · " + needs + " need" + (needs == 1 ? "s" : "") + " you" : ""));

        if (none) return;
        if (grid) renderGrid(list); else renderOne(selected(), list);
    }

    private void renderOne(RoomBrainClient.Agent a, List<RoomBrainClient.Agent> list) {
        long now = serverNow();
        pet.setAgent(a.agent);
        pet.setVoice(voice != null ? voice.levels : null);
        PetView.Mood mood = a.id.equals(talkingTo) ? PetView.Mood.LISTENING
            : a.id.equals(sendingTo) ? PetView.Mood.SENDING
            : PetView.moodFor(a, now);
        pet.setMood(mood, a.kind);
        pet.setContentDescription(a.agentLabel + " pet, " + stateWord(a) + ". Tap to talk.");
        petHint.setText(a.canTalk ? (list.size() > 1 ? "Tap me to talk · swipe for the next agent" : "Tap me to talk")
            : (list.size() > 1 ? "Swipe for the next agent" : ""));

        agentLine.setText((a.agentLabel + " · " + a.host).toUpperCase(Locale.US));
        agentLine.setTextColor(getColor(R.color.accent));
        String name = !a.title.isEmpty() ? a.title : !a.project.isEmpty() ? a.project : a.agentLabel;
        project.setText(name);
        stateLine.setText(stateWord(a) + " · " + ago(now - a.stateSince));
        stateLine.setTextColor(getColor(stateColor(a)));
        activity.setText(a.activity);
        String usage = statsText(a, now);
        statsLine.setVisibility(usage.isEmpty() ? View.GONE : View.VISIBLE);
        statsLine.setText(usage);

        if (a.permission != null) {
            askRow.setVisibility(View.VISIBLE);
            askText.setText("Wants to " + ("Bash".equals(a.permission.tool) ? "run " : "use " + a.permission.tool + ": ")
                + a.permission.summary + " · " + Math.max(0, (a.permission.expiresAt - now) / 1000) + " s left");
        } else {
            askRow.setVisibility(View.GONE);
        }
        attention.setVisibility(a.permission == null && !a.attention.isEmpty() ? View.VISIBLE : View.GONE);
        attention.setText(a.attention);

        boolean asked = !a.prompt.isEmpty();
        promptLabel.setVisibility(asked ? View.VISIBLE : View.GONE);
        prompt.setVisibility(asked ? View.VISIBLE : View.GONE);
        prompt.setText(a.prompt);
        replyLabel.setText(a.agentLabel.toUpperCase(Locale.US) + " SAID");
        boolean said = !a.reply.isEmpty();
        replyLabel.setVisibility(said ? View.VISIBLE : View.GONE);
        reply.setText(a.reply);

        if (!a.canTalk) {
            sayLine.setText(a.talkNote);
            sayLine.setTextColor(getColor(R.color.muted));
        } else if (a.lastSay != null && !a.lastSay.text.isEmpty()) {
            boolean failed = "failed".equals(a.lastSay.status);
            sayLine.setText(failed ? "Couldn't type “" + a.lastSay.text + "” · " + a.lastSay.error
                : ("typed".equals(a.lastSay.status) ? "Typed: “" : "Sending: “") + a.lastSay.text + "”");
            sayLine.setTextColor(getColor(failed ? R.color.alert : R.color.muted));
        } else {
            sayLine.setText("");
        }
        talkBtn.setEnabled(a.canTalk);
        talkBtn.setAlpha(a.canTalk ? 1f : 0.5f);
        // The machine only: an AppTabs host also names the app and workspace, too long for a button.
        focusBtn.setText("Show on " + a.host.split(" · ")[0]);

        // One dot per agent, coloured by its state; the current one filled.
        dots.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (RoomBrainClient.Agent other : list) {
            View d = new View(this);
            int size = (int) ((other == a ? 16 : 11) * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins((int) (6 * density), 0, (int) (6 * density), 0);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            int c = getColor(stateColor(other));
            if (other == a) g.setColor(c); else g.setStroke((int) (2 * density), c);
            d.setBackground(g);
            d.setOnClickListener(v -> {
                selectedId = other.id;
                render();
            });
            dots.addView(d, lp);
        }
        dots.setVisibility(list.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private void renderGrid(List<RoomBrainClient.Agent> list) {
        StringBuilder key = new StringBuilder(sort.name()).append(':');
        for (RoomBrainClient.Agent a : list) key.append(a.id).append(',');
        float density = getResources().getDisplayMetrics().density;
        if (!key.toString().equals(gridKey)) {
            gridKey = key.toString();
            gridList.removeAllViews();
            tiles.clear();
            LinearLayout row = null;
            String group = null;
            int inGroup = 0;
            for (int i = 0; i < list.size(); i++) {
                // By project: a label above each repository's tiles, which start a fresh row.
                if (sort == Sort.PROJECT && !projectOf(list.get(i)).equals(group)) {
                    if (row != null && inGroup % 2 == 1) addGap(row, density);
                    group = projectOf(list.get(i));
                    inGroup = 0;
                    TextView label = new TextView(this);
                    label.setText(group.isEmpty() ? "No project" : group);
                    label.setTextColor(getColor(R.color.muted));
                    label.setTextSize(15);
                    label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
                    label.setBackground(getDrawable(R.drawable.chip_bg));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = (int) ((i == 0 ? 0 : 16) * density);
                    lp.bottomMargin = (int) (8 * density);
                    gridList.addView(label, lp);
                }
                if (inGroup++ % 2 == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (int) (200 * density));
                    if (i > 0 && !(sort == Sort.PROJECT && inGroup == 1)) rp.topMargin = (int) (10 * density);
                    gridList.addView(row, rp);
                }
                View tile = getLayoutInflater().inflate(R.layout.tile_agent, row, false);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (inGroup % 2 == 0) tp.leftMargin = (int) (10 * density);
                row.addView(tile, tp);
                String id = list.get(i).id;
                tile.setOnClickListener(v -> {
                    selectedId = id;
                    grid = false;
                    render();
                });
                tiles.put(id, tile);
            }
            if (row != null && inGroup % 2 == 1) addGap(row, density);
        }
        long now = serverNow();
        for (RoomBrainClient.Agent a : list) {
            View tile = tiles.get(a.id);
            if (tile == null) continue;
            PetView p = tile.findViewById(R.id.tilePet);
            p.setAgent(a.agent);
            p.setMood(PetView.moodFor(a, now), a.kind);
            ((TextView) tile.findViewById(R.id.tileAgent)).setText((a.agentLabel + " · " + a.host).toUpperCase(Locale.US));
            ((TextView) tile.findViewById(R.id.tileProject)).setText(
                !a.title.isEmpty() ? a.title : !a.project.isEmpty() ? a.project : a.agentLabel);
            TextView st = tile.findViewById(R.id.tileState);
            st.setText(stateWord(a) + " · " + ago(now - a.stateSince));
            st.setTextColor(getColor(stateColor(a)));
            ((TextView) tile.findViewById(R.id.tileActivity)).setText(
                a.permission != null ? "Asks: " + a.permission.summary : !a.attention.isEmpty() ? a.attention : a.activity);
        }
    }

    /** An odd count leaves a gap rather than one stretched tile. */
    private void addGap(LinearLayout row, float density) {
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        tp.leftMargin = (int) (10 * density);
        row.addView(new View(this), tp);
    }

    /** "Context 72% · $31 · running 3 h 27 min · 213 calls", from what the source read in the transcript. */
    static String statsText(RoomBrainClient.Agent a, long now) {
        RoomBrainClient.AgentStats st = a.stats;
        if (st == null || st.calls == 0) return "";
        List<String> parts = new ArrayList<>();
        if (st.contextWindow > 0) parts.add("Context " + Math.min(100, Math.round(100.0 * st.contextUsed / st.contextWindow)) + "%");
        if (st.costUsd >= 0) parts.add(st.costUsd < 10 ? String.format(Locale.US, "$%.2f", st.costUsd) : String.format(Locale.US, "$%.0f", st.costUsd));
        if (st.firstAt > 0) {
            boolean live = "working".equals(a.state) || "waiting".equals(a.state);
            long end = live ? now : (st.lastAt > 0 ? st.lastAt : now);
            parts.add((live ? "running " : "ran ") + ago(end - st.firstAt).replace("just now", "under a minute"));
        }
        parts.add(st.calls + (st.calls == 1 ? " call" : " calls"));
        return String.join(" · ", parts);
    }

    static String stateWord(RoomBrainClient.Agent a) {
        switch (a.state) {
            case "working": return "Working";
            case "waiting": return "Needs you";
            case "done": return "Done";
            case "ended": return "Closed";
            default: return "Idle";
        }
    }

    static int stateColor(RoomBrainClient.Agent a) {
        switch (a.state) {
            case "working": return R.color.accent;
            case "waiting": return R.color.amber;
            case "done": return R.color.success;
            default: return R.color.muted;
        }
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return "just now";
        long m = s / 60;
        if (m < 60) return m + " min";
        long h = m / 60;
        return h < 24 ? h + " h " + (m % 60) + " min" : (h / 24) + " d";
    }

    // ---- Approvals ----

    private void toggleApprovals() {
        boolean on = !(snapshot != null && snapshot.wallApprovals);
        status.setText(on ? "Claude Code will wait for your answer here before asking at the desk…"
            : "Permission prompts go back to the terminal…");
        AgentWatch.shared().setWallApprovals(on, (r, e) -> runOnUiThread(() -> {
            if (e != null) status.setText(e.getMessage());
            AgentWatch.shared().refreshNow();
        }));
    }

    private void decide(boolean allow) {
        RoomBrainClient.Agent a = selected();
        if (a == null || a.permission == null) return;
        askRow.setVisibility(View.GONE);
        status.setText(allow ? "Approved" : "Denied");
        AgentWatch.shared().clientFor(a).decideAgent(a.id, a.permission.id, allow, (r, e) -> runOnUiThread(() -> {
            if (e != null) status.setText(e.getMessage());
            AgentWatch.shared().refreshNow();
        }));
    }

    // ---- Talking ----

    private void startTalk() {
        RoomBrainClient.Agent a = selected();
        if (a == null || voice != null || grid) return;
        if (!a.canTalk) {
            status.setText(a.talkNote);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone not granted · adb shell pm grant com.portalroomos android.permission.RECORD_AUDIO");
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MIC_REQUEST);
            return;
        }
        VoiceNote note = new VoiceNote();
        if (!note.start()) {
            status.setText(note.error());
            return;
        }
        voice = note;
        talkingTo = a.id;
        voiceMeter.setSource(note.levels);
        voiceMeter.setActive(true);
        coverButtonRow();
        listening.setVisibility(View.VISIBLE);
        listeningText.setText("Listening to " + a.agentLabel + " · tap anywhere to send");
        render();
        handler.post(listenTick);
    }

    /** The listening bar sits exactly over the details panel's button row. */
    private void coverButtonRow() {
        View details = findViewById(R.id.details);
        View bar = findViewById(R.id.listeningBar);
        int[] panel = new int[2], root = new int[2];
        details.getLocationInWindow(panel);
        listening.getLocationInWindow(root);
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.width = details.getWidth();
        lp.leftMargin = panel[0] - root[0];
        lp.topMargin = panel[1] - root[1] + details.getHeight() - lp.height;
        bar.setLayoutParams(lp);
    }

    private final Runnable listenTick = new Runnable() {
        @Override public void run() {
            if (voice == null) return;
            long s = voice.elapsedMs() / 1000;
            listeningText.setText(String.format(Locale.US, "Listening %d:%02d · tap anywhere to send", s / 60, s % 60));
            // The length cap closes the mic; send what it heard.
            if (!voice.listening()) {
                sendTalk();
                return;
            }
            handler.postDelayed(this, 250);
        }
    };

    private void endListening() {
        handler.removeCallbacks(listenTick);
        voiceMeter.setActive(false);
        listening.setVisibility(View.GONE);
        talkingTo = null;
    }

    private void sendTalk() {
        VoiceNote note = voice;
        String id = talkingTo;
        if (note == null || id == null) return;
        voice = null;
        endListening();
        byte[] wav = note.finish();
        if (wav == null || note.elapsedMs() < 600) {
            status.setText(note.error() != null ? note.error() : "That was too short to send");
            render();
            return;
        }
        RoomBrainClient.Agent agent = null;
        for (RoomBrainClient.Agent a : agents()) if (a.id.equals(id)) agent = a;
        if (agent == null) {
            status.setText("That agent is gone");
            render();
            return;
        }
        sendingTo = id;
        status.setText("Sending…");
        render();
        RoomBrainClient.Callback<RoomBrainClient.SayResult> done = (r, e) -> runOnUiThread(() -> {
            sendingTo = null;
            if (e != null) status.setText("Not sent · " + e.getMessage());
            else if (!r.ok) status.setText((r.text.isEmpty() ? "" : "“" + r.text + "” · ") + r.error);
            else status.setText("Sent: “" + r.text + "”");
            AgentWatch.shared().refreshNow();
            render();
        });
        RoomBrainClient to = AgentWatch.shared().clientFor(agent);
        if (!AgentWatch.LOCAL.equals(agent.source)) {
            to.sayToAgent(id, wav, done);
            return;
        }
        // AppTabs over USB takes words, not audio: the Room Brain hears them first.
        RoomBrainClient ears = AgentWatch.shared().roomClient();
        if (ears == null) {
            done.onResult(null, new Exception("Speech to text needs the Room Brain in local.properties"));
            return;
        }
        ears.transcribe(wav, (heard, e) -> {
            if (e != null) { done.onResult(null, e); return; }
            if (!heard.ok) { done.onResult(heard, null); return; }
            to.sayTextToAgent(id, heard.text, done);
        });
    }

    private void cancelTalk(String why) {
        if (voice == null) return;
        voice.cancel();
        voice = null;
        endListening();
        if (why != null) status.setText(why);
        render();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == MIC_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startTalk();
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

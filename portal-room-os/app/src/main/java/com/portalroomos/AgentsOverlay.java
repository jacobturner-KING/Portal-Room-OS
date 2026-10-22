package com.portalroomos;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The agents' own way onto the screen, separate from the OS chip.
 *
 * - The Agents chip floats beside the OS chip wherever the OS chip does (over
 *   the stock launcher and anything that is not Room OS), opens the Agents
 *   screen, and wears a coloured dot: amber when an agent needs an answer,
 *   green when one has finished since you last looked, blue while any work.
 * - The banner drops in over anything, Room OS included, for a few seconds
 *   when an agent finishes or starts needing you, except over the Agents screen
 *   itself, which already shows it. Tap it to open that agent.
 *
 * Both need the same draw-over-other-apps grant as the OS chip; without it they
 * stay hidden and the chime still sounds.
 */
final class AgentsOverlay implements AgentWatch.Listener {

    private static final String TAG = "AgentsOverlay";
    private static final long BANNER_MS = 7_000;
    /** Left of the OS chip, with a gap. */
    private static final int CHIP_RIGHT_DP = RoomOsOverlay.RIGHT_DP + RoomOsOverlay.MIN_WIDTH_DP + 12;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private TextView chip, banner;
    private final Runnable rule = this::applyRule;
    private final Runnable hideBanner = this::removeBanner;

    AgentsOverlay(Context context) {
        this.context = context.getApplicationContext();
    }

    void attach() {
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        RoomOsApp.addForegroundListener(rule);
        AgentWatch.shared().addListener(this);
    }

    private boolean allowed() {
        if (windows != null && Settings.canDrawOverlays(context)) return true;
        Log.w(TAG, "no draw-over-other-apps permission; the Agents chip stays hidden");
        return false;
    }

    private void applyRule() {
        if (RoomOsApp.inForeground()) removeChip(); else showChip();
        if (banner != null && RoomOsApp.showing(AgentsActivity.class)) removeBanner();
    }

    @Override public void onAgents(RoomBrainClient.AgentSnapshot s, String error) {
        // A link made after boot: the chip may now have somewhere to point.
        if (chip == null && !RoomOsApp.inForeground()) showChip();
        if (chip != null) chip.setText(chipText(s));
    }

    @Override public void onPing(RoomBrainClient.Agent a, AgentChime.Kind kind) {
        if (RoomOsApp.showing(AgentsActivity.class)) return;
        String where = a.project.isEmpty() ? a.host : a.project;
        CharSequence text = kind == AgentChime.Kind.DONE
            ? dot("✓ ", R.color.success).append(a.agentLabel).append(" finished · ").append(where)
            : dot("● ", R.color.amber).append(a.agentLabel).append(" needs you · ")
                .append(a.permission != null ? a.permission.summary : a.attention);
        showBanner(text, a.id);
    }

    private SpannableStringBuilder dot(String mark, int color) {
        SpannableStringBuilder b = new SpannableStringBuilder(mark);
        b.setSpan(new ForegroundColorSpan(context.getColor(color)), 0, mark.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    private CharSequence chipText(RoomBrainClient.AgentSnapshot s) {
        int color = R.color.muted;
        int working = 0;
        boolean needsYou = false;
        if (s != null) {
            for (RoomBrainClient.Agent a : s.sessions) {
                if ("waiting".equals(a.state) || a.permission != null) needsYou = true;
                if ("working".equals(a.state)) working++;
            }
        }
        if (needsYou) color = R.color.amber;
        else if (AgentWatch.shared().unseenDone() > 0) color = R.color.success;
        else if (working > 0) color = R.color.accent;
        SpannableStringBuilder b = dot("● ", color).append("Agents");
        if (working > 0) b.append(" ").append(String.valueOf(working));
        return b;
    }

    private TextView pill(float sp) {
        TextView v = new TextView(context);
        v.setTextSize(sp);
        v.setTypeface(v.getTypeface(), Typeface.BOLD);
        v.setTextColor(context.getColor(R.color.text));
        v.setBackground(context.getDrawable(R.drawable.chip_bg));
        v.setGravity(Gravity.CENTER);
        float density = context.getResources().getDisplayMetrics().density;
        // The View's minimum, not TextView.setMinHeight: setSingleLine() would reset that one.
        v.setMinimumHeight((int) (RoomOsOverlay.MIN_HEIGHT_DP * density));
        return v;
    }

    private void showChip() {
        // Nowhere to read agents from yet (no Room Brain, no AppTabs link): no chip.
        if (chip != null || !AgentWatch.shared().configured() || !allowed()) return;
        try {
            TextView v = pill(17);
            v.setText(chipText(AgentWatch.shared().latest()));
            v.setOnClickListener(x -> open(null));
            windows.addView(v, RoomOsOverlay.floating(context, Gravity.TOP | Gravity.END, CHIP_RIGHT_DP, RoomOsOverlay.TOP_DP));
            chip = v;
        } catch (Exception e) {
            Log.w(TAG, "could not show the Agents chip", e);
        }
    }

    private void removeChip() {
        if (chip == null) return;
        try { windows.removeView(chip); } catch (Exception ignored) {}
        chip = null;
    }

    private void showBanner(CharSequence text, String agentId) {
        if (!allowed()) return;
        removeBanner();
        try {
            TextView v = pill(18);
            float density = context.getResources().getDisplayMetrics().density;
            v.setMaxWidth((int) (680 * density));
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setPadding((int) (22 * density), 0, (int) (22 * density), 0);
            v.setText(text);
            v.setOnClickListener(x -> {
                removeBanner();
                open(agentId);
            });
            windows.addView(v, RoomOsOverlay.floating(context, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, RoomOsOverlay.TOP_DP));
            banner = v;
            main.postDelayed(hideBanner, BANNER_MS);
        } catch (Exception e) {
            Log.w(TAG, "could not show the agent banner", e);
        }
    }

    private void removeBanner() {
        main.removeCallbacks(hideBanner);
        if (banner == null) return;
        try { windows.removeView(banner); } catch (Exception ignored) {}
        banner = null;
    }

    private void open(String agentId) {
        Intent i = new Intent(context, AgentsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (agentId != null) i.putExtra(AgentsActivity.EXTRA_AGENT_ID, agentId);
        context.startActivity(i);
    }
}

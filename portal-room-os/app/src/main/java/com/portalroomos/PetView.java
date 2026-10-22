package com.portalroomos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * An agent's pet: a small round creature that acts out what its agent is doing,
 * so the state reads from across the room before any text does.
 *
 * Working, it holds the tool of the moment (a pencil scribbling for an edit, an
 * open book for a read, a terminal with a blinking caret for a command, a
 * magnifier for a search, a globe for the web, a buddy for a delegated task, a
 * checklist for planning, thought bubbles while it thinks). Needing you, it
 * waves with a question mark over its head. Done, it hops among sparkles.
 * Idle, it breathes and blinks, and after a while falls asleep. Listening to
 * you, it wears headphones and its mouth moves with your voice.
 *
 * Each agent has its own colour and a mark on its head: Claude a spark, Codex
 * an antenna, Cursor a pointer tuft. Drawn entirely on the canvas, about thirty
 * frames a second while on screen and still otherwise.
 */
public class PetView extends View {

    enum Mood { IDLE, SLEEP, WORKING, WAITING, DONE, ENDED, LISTENING, SENDING }

    private static final long FRAME_MS = 33;
    /** How long the hop of joy lasts before it settles into a contented bob. */
    private static final float HOP_SECONDS = 4f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final int ink, paper, accent, amber, success, muted, screen, rule;

    private Mood mood = Mood.IDLE;
    private String agent = "generic";
    private String kind = "";
    private int body;
    private long moodSince = SystemClock.uptimeMillis();
    private final long born = SystemClock.uptimeMillis();
    private LevelRing voice;

    public PetView(Context context) {
        this(context, null);
    }

    public PetView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ink = 0xFF151826;
        paper = context.getColor(R.color.text);
        accent = context.getColor(R.color.accent);
        amber = context.getColor(R.color.amber);
        success = context.getColor(R.color.success);
        muted = context.getColor(R.color.muted);
        screen = context.getColor(R.color.bg);
        rule = context.getColor(R.color.rule);
        body = context.getColor(R.color.pet_generic);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    /** claude, codex, cursor or generic: the colour and the mark on its head. */
    void setAgent(String agent) {
        this.agent = agent == null ? "generic" : agent;
        body = getContext().getColor(colorFor(this.agent));
        invalidate();
    }

    static int colorFor(String agent) {
        switch (agent == null ? "" : agent) {
            case "claude": return R.color.pet_claude;
            case "codex": return R.color.pet_codex;
            case "cursor": return R.color.pet_cursor;
            default: return R.color.pet_generic;
        }
    }

    void setMood(Mood m, String kind) {
        String k = kind == null ? "" : kind;
        if (m != mood) moodSince = SystemClock.uptimeMillis();
        boolean changed = m != mood || !k.equals(this.kind);
        mood = m;
        this.kind = k;
        if (changed) invalidate();
    }

    /** Where the listening mouth takes its level from. */
    void setVoice(LevelRing ring) {
        voice = ring;
    }

    /** The agent's state as the pet's mood; long idle is sleep. */
    static Mood moodFor(RoomBrainClient.Agent a, long nowServer) {
        if (a == null) return Mood.SLEEP;
        switch (a.state) {
            case "working": return Mood.WORKING;
            case "waiting": return Mood.WAITING;
            case "done": return nowServer - a.stateSince > 20 * 60_000 ? Mood.SLEEP : Mood.DONE;
            case "ended": return Mood.ENDED;
            default: return nowServer - a.stateSince > 10 * 60_000 ? Mood.SLEEP : Mood.IDLE;
        }
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        if (s <= 0) return;
        float t = (SystemClock.uptimeMillis() - born) / 1000f;
        float inMood = (SystemClock.uptimeMillis() - moodSince) / 1000f;

        float cx = w / 2f - (mood == Mood.WORKING && hasProp() ? s * 0.07f : 0f);
        float rx = s * 0.25f, ry = s * 0.22f;
        float baseY = h / 2f + s * 0.06f;

        // Motion: how high it is off the ground, and how squashed.
        float lift = 0f, sx = 1f, sy = 1f;
        switch (mood) {
            case WORKING:
                lift = Math.abs(wave(t, 1.6f)) * s * 0.015f;
                break;
            case DONE: {
                float phase = (t * 1.4f) % 1f;
                float hop = inMood < HOP_SECONDS ? s * 0.09f : s * 0.015f;
                lift = (float) Math.sin(phase * Math.PI) * hop;
                if (phase < 0.08f || phase > 0.92f) { sx = 1.06f; sy = 0.93f; }
                break;
            }
            case WAITING:
                lift = Math.abs(wave(t, 0.9f)) * s * 0.01f;
                break;
            case LISTENING:
                sy = 1f + level() * 0.05f;
                break;
            case ENDED:
                sy = 0.94f;
                break;
            default: {
                float breath = wave(t, mood == Mood.SLEEP ? 0.18f : 0.25f);
                sy = 1f + breath * 0.02f;
                sx = 1f - breath * 0.01f;
            }
        }
        float cy = baseY - lift;
        int col = mood == Mood.ENDED ? muted : body;

        // Shadow, smaller the higher it hops.
        fill.setColor(0x55000000);
        float shadow = rx * (1f - lift / (s * 0.25f));
        oval.set(cx - shadow, baseY + ry * 0.98f, cx + shadow, baseY + ry * 0.98f + s * 0.04f);
        c.drawOval(oval, fill);

        // Feet, shuffling while it works.
        float shuffle = mood == Mood.WORKING ? wave(t, 3.2f) * s * 0.012f : 0f;
        fill.setColor(shade(col, 0.72f));
        foot(c, cx - rx * 0.45f, cy + ry * sy * 0.9f + shuffle, s);
        foot(c, cx + rx * 0.45f, cy + ry * sy * 0.9f - shuffle, s);

        // Arms: the left waves when it needs you; the right holds the tool.
        fill.setColor(shade(col, 0.85f));
        if (mood == Mood.WAITING) {
            // Raised up and to the left (270 is straight up), waving.
            float a = 228f + wave(t, 1.8f) * 22f;
            arm(c, cx - rx * 0.92f, cy - ry * 0.1f, s, a);
        } else {
            arm(c, cx - rx * 0.97f, cy + ry * 0.2f, s, 200f);
        }
        float handX = cx + rx * 1.05f, handY = cy + ry * 0.12f;
        arm(c, cx + rx * 0.95f, cy + ry * 0.2f, s, mood == Mood.WORKING && hasProp() ? -25f : -20f);

        // Body, with a soft highlight.
        fill.setColor(col);
        oval.set(cx - rx * sx, cy - ry * sy, cx + rx * sx, cy + ry * sy);
        c.drawOval(oval, fill);
        fill.setColor(0x33FFFFFF);
        oval.set(cx - rx * 0.62f, cy - ry * 0.78f, cx - rx * 0.05f, cy - ry * 0.32f);
        c.drawOval(oval, fill);

        float top = cy - ry * sy;
        headMark(c, cx, top, s, t, col);
        face(c, cx, cy, rx, ry, s, t);

        if (mood == Mood.LISTENING) headphones(c, cx, cy, rx, ry, sy, s, t);
        if (mood == Mood.WORKING && hasProp()) prop(c, handX + s * 0.1f, handY - s * 0.02f, s, t);
        if (mood == Mood.WORKING && !hasProp()) thinking(c, cx + rx * 0.55f, top, s, t);
        if (mood == Mood.WAITING) bubble(c, cx + rx * 0.9f, top - s * 0.1f, s, t, "?", amber);
        if (mood == Mood.SENDING) bubble(c, cx + rx * 0.9f, top - s * 0.1f, s, t, "…", accent);
        if (mood == Mood.DONE) sparkles(c, cx, cy, rx, ry, s, t);
        if (mood == Mood.SLEEP) sleep(c, cx + rx * 0.7f, top, s, t);

        if (mood != Mood.ENDED && isShown()) postInvalidateDelayed(FRAME_MS);
    }

    private boolean hasProp() {
        return !kind.isEmpty() && !"think".equals(kind);
    }

    private float level() {
        return voice == null ? 0f : voice.latest();
    }

    private static float wave(float t, float hz) {
        return (float) Math.sin(t * hz * 2 * Math.PI);
    }

    private static int shade(int color, float f) {
        int a = color >>> 24, r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (a << 24) | ((int) (r * f) << 16) | ((int) (g * f) << 8) | (int) (b * f);
    }

    private void foot(Canvas c, float x, float y, float s) {
        oval.set(x - s * 0.06f, y - s * 0.02f, x + s * 0.06f, y + s * 0.04f);
        c.drawOval(oval, fill);
    }

    /** A stubby arm from (x, y), rotated by deg (0 points right). */
    private void arm(Canvas c, float x, float y, float s, float deg) {
        c.save();
        c.rotate(deg, x, y);
        oval.set(x - s * 0.02f, y - s * 0.032f, x + s * 0.11f, y + s * 0.032f);
        c.drawOval(oval, fill);
        c.restore();
    }

    private void headMark(Canvas c, float cx, float top, float s, float t, int col) {
        int tint = mood == Mood.ENDED ? muted : shade(col, 0.78f);
        switch (agent) {
            case "claude": {
                // A spark: six rounded rays, turning slowly.
                line.setColor(tint);
                line.setStrokeWidth(s * 0.022f);
                float r = s * 0.05f, y = top - s * 0.035f;
                float spin = mood == Mood.WORKING ? t * 40f : t * 8f;
                for (int i = 0; i < 6; i++) {
                    double a = Math.toRadians(spin + i * 60);
                    c.drawLine(cx, y, cx + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r, line);
                }
                break;
            }
            case "codex": {
                // An antenna whose ball wobbles.
                line.setColor(tint);
                line.setStrokeWidth(s * 0.014f);
                float sway = wave(t, mood == Mood.WORKING ? 1.4f : 0.5f) * s * 0.02f;
                float bx = cx + s * 0.03f + sway, by = top - s * 0.09f;
                c.drawLine(cx + s * 0.01f, top + s * 0.005f, bx, by, line);
                fill.setColor(mood == Mood.WORKING && wave(t, 2f) > 0 ? accent : tint);
                c.drawCircle(bx, by, s * 0.022f, fill);
                break;
            }
            case "cursor": {
                // A pointer-shaped tuft.
                fill.setColor(tint);
                path.reset();
                path.moveTo(cx - s * 0.025f, top + s * 0.01f);
                path.lineTo(cx - s * 0.02f, top - s * 0.085f);
                path.lineTo(cx + s * 0.045f, top - s * 0.02f);
                path.lineTo(cx + s * 0.012f, top - s * 0.02f);
                path.close();
                c.drawPath(path, fill);
                break;
            }
            default:
                fill.setColor(tint);
                c.drawCircle(cx, top, s * 0.03f, fill);
        }
    }

    private void face(Canvas c, float cx, float cy, float rx, float ry, float s, float t) {
        float ex = rx * 0.38f, ey = cy - ry * 0.18f;
        float eyeW = s * 0.05f, eyeH = s * 0.06f;
        boolean closed = mood == Mood.SLEEP || mood == Mood.ENDED;
        line.setColor(ink);
        line.setStrokeWidth(s * 0.014f);
        if (closed) {
            for (int side = -1; side <= 1; side += 2) {
                oval.set(cx + side * ex - eyeW, ey - eyeH * 0.4f, cx + side * ex + eyeW, ey + eyeH * 0.6f);
                c.drawArc(oval, 20, 140, false, line);
            }
        } else if (mood == Mood.DONE) {
            // Happy eyes: upturned arcs.
            for (int side = -1; side <= 1; side += 2) {
                oval.set(cx + side * ex - eyeW, ey - eyeH * 0.5f, cx + side * ex + eyeW, ey + eyeH * 0.9f);
                c.drawArc(oval, 200, 140, false, line);
            }
        } else {
            boolean blink = (t % 3.7f) < 0.12f;
            float lookX = 0f, lookY = 0f;
            if (mood == Mood.WORKING) {
                if ("read".equals(kind)) lookX = wave(t, 0.7f);
                else if (hasProp()) { lookX = 0.8f; lookY = 0.4f; }
                else { lookX = -0.3f; lookY = -0.8f; } // thinking: eyes up
            } else if (mood == Mood.WAITING) {
                lookY = -0.3f;
            } else if (mood == Mood.LISTENING) {
                lookY = 0.2f;
            }
            for (int side = -1; side <= 1; side += 2) {
                float x = cx + side * ex;
                fill.setColor(paper);
                float hh = blink ? eyeH * 0.12f : eyeH;
                oval.set(x - eyeW, ey - hh, x + eyeW, ey + hh);
                c.drawOval(oval, fill);
                if (!blink) {
                    fill.setColor(ink);
                    c.drawCircle(x + lookX * eyeW * 0.4f, ey + lookY * eyeH * 0.35f, eyeW * 0.55f, fill);
                    fill.setColor(paper);
                    c.drawCircle(x + lookX * eyeW * 0.4f - eyeW * 0.18f, ey + lookY * eyeH * 0.35f - eyeW * 0.2f, eyeW * 0.16f, fill);
                }
            }
        }

        // Cheeks when pleased or listening.
        if (mood == Mood.DONE || mood == Mood.LISTENING) {
            fill.setColor(0x55FF8FA3);
            c.drawCircle(cx - rx * 0.62f, cy + ry * 0.12f, s * 0.03f, fill);
            c.drawCircle(cx + rx * 0.62f, cy + ry * 0.12f, s * 0.03f, fill);
        }

        float my = cy + ry * 0.18f;
        if (mood == Mood.LISTENING) {
            float open = Math.max(0.15f, level());
            fill.setColor(ink);
            oval.set(cx - s * 0.03f, my - s * 0.012f, cx + s * 0.03f, my + s * 0.012f + open * s * 0.05f);
            c.drawOval(oval, fill);
        } else if (mood == Mood.DONE) {
            fill.setColor(ink);
            oval.set(cx - s * 0.05f, my - s * 0.03f, cx + s * 0.05f, my + s * 0.05f);
            c.drawArc(oval, 0, 180, true, fill);
        } else if (mood == Mood.WAITING || mood == Mood.SENDING) {
            fill.setColor(ink);
            c.drawCircle(cx, my + s * 0.005f, s * 0.016f, fill);
        } else if (mood != Mood.ENDED) {
            oval.set(cx - s * 0.035f, my - s * 0.03f, cx + s * 0.035f, my + s * 0.02f);
            c.drawArc(oval, 20, 140, false, line);
        }
    }

    private void headphones(Canvas c, float cx, float cy, float rx, float ry, float sy, float s, float t) {
        line.setColor(accent);
        line.setStrokeWidth(s * 0.024f);
        oval.set(cx - rx * 1.02f, cy - ry * sy * 1.22f, cx + rx * 1.02f, cy + ry * 0.6f);
        c.drawArc(oval, 195, 150, false, line);
        fill.setColor(accent);
        for (int side = -1; side <= 1; side += 2) {
            float x = cx + side * rx * 0.98f;
            oval.set(x - s * 0.04f, cy - ry * 0.35f, x + s * 0.04f, cy + ry * 0.2f);
            c.drawRoundRect(oval, s * 0.03f, s * 0.03f, fill);
        }
        // Sound coming in, louder rings for a louder voice.
        float lv = level();
        line.setStrokeWidth(s * 0.01f);
        line.setColor(accent);
        for (int i = 0; i < 3; i++) {
            float p = ((t * 1.2f) + i / 3f) % 1f;
            int alpha = (int) (255 * (1f - p) * Math.min(1f, 0.25f + lv * 1.5f));
            line.setAlpha(alpha);
            float r = s * (0.05f + p * 0.12f);
            float x = cx + rx * 1.15f;
            oval.set(x - r, cy - ry * 0.1f - r, x + r, cy - ry * 0.1f + r);
            c.drawArc(oval, -40, 80, false, line);
            x = cx - rx * 1.15f;
            oval.set(x - r, cy - ry * 0.1f - r, x + r, cy - ry * 0.1f + r);
            c.drawArc(oval, 140, 80, false, line);
        }
        line.setAlpha(255);
    }

    /** The tool of the moment, in the right hand at (x, y). */
    private void prop(Canvas c, float x, float y, float s, float t) {
        line.setColor(paper);
        line.setStrokeWidth(s * 0.012f);
        switch (kind) {
            case "edit": {
                // Paper, and a pencil scribbling across it.
                fill.setColor(paper);
                oval.set(x - s * 0.07f, y - s * 0.02f, x + s * 0.09f, y + s * 0.15f);
                c.drawRoundRect(oval, s * 0.01f, s * 0.01f, fill);
                line.setColor(muted);
                line.setStrokeWidth(s * 0.008f);
                int lines = (int) ((t * 1.5f) % 4) + 1;
                for (int i = 0; i < lines; i++) {
                    float ly = y + s * (0.015f + i * 0.03f);
                    c.drawLine(x - s * 0.05f, ly, x + s * (i == lines - 1 ? 0.0f + 0.06f * ((t * 1.5f) % 1f) : 0.06f), ly, line);
                }
                float px = x - s * 0.04f + ((t * 1.5f) % 1f) * s * 0.1f;
                float py = y + s * (0.015f + (lines - 1) * 0.03f);
                c.save();
                c.rotate(-35, px, py);
                fill.setColor(amber);
                oval.set(px, py - s * 0.012f, px + s * 0.12f, py + s * 0.012f);
                c.drawRect(oval, fill);
                fill.setColor(ink);
                path.reset();
                path.moveTo(px, py - s * 0.012f);
                path.lineTo(px - s * 0.025f, py);
                path.lineTo(px, py + s * 0.012f);
                path.close();
                c.drawPath(path, fill);
                c.restore();
                break;
            }
            case "read": {
                // An open book, its pages lined.
                fill.setColor(paper);
                path.reset();
                path.moveTo(x, y + s * 0.02f);
                path.lineTo(x - s * 0.1f, y);
                path.lineTo(x - s * 0.1f, y + s * 0.12f);
                path.lineTo(x, y + s * 0.14f);
                path.lineTo(x + s * 0.1f, y + s * 0.12f);
                path.lineTo(x + s * 0.1f, y);
                path.close();
                c.drawPath(path, fill);
                line.setColor(muted);
                line.setStrokeWidth(s * 0.006f);
                c.drawLine(x, y + s * 0.02f, x, y + s * 0.14f, line);
                for (int i = 0; i < 3; i++) {
                    float ly = y + s * (0.04f + i * 0.028f);
                    c.drawLine(x - s * 0.08f, ly - s * 0.004f, x - s * 0.02f, ly, line);
                    c.drawLine(x + s * 0.02f, ly, x + s * 0.08f, ly - s * 0.004f, line);
                }
                break;
            }
            case "run": {
                // A little terminal, its caret blinking.
                fill.setColor(screen);
                oval.set(x - s * 0.08f, y - s * 0.02f, x + s * 0.12f, y + s * 0.12f);
                c.drawRoundRect(oval, s * 0.015f, s * 0.015f, fill);
                line.setColor(rule);
                line.setStrokeWidth(s * 0.005f);
                c.drawRoundRect(oval, s * 0.015f, s * 0.015f, line);
                text.setColor(success);
                text.setTextSize(s * 0.06f);
                text.setTextAlign(Paint.Align.LEFT);
                c.drawText(">", x - s * 0.065f, y + s * 0.065f, text);
                if ((t % 1f) < 0.55f) {
                    fill.setColor(paper);
                    c.drawRect(x - s * 0.025f, y + s * 0.06f, x + s * 0.01f, y + s * 0.07f, fill);
                }
                text.setTextAlign(Paint.Align.CENTER);
                break;
            }
            case "search": {
                // A magnifier circling.
                float a = t * 2.4f;
                float mx = x + (float) Math.cos(a) * s * 0.03f, my = y + s * 0.05f + (float) Math.sin(a) * s * 0.02f;
                line.setColor(paper);
                line.setStrokeWidth(s * 0.016f);
                c.drawCircle(mx, my, s * 0.045f, line);
                c.drawLine(mx + s * 0.032f, my + s * 0.032f, mx + s * 0.08f, my + s * 0.08f, line);
                fill.setColor(0x44AEC0FF);
                c.drawCircle(mx, my, s * 0.038f, fill);
                break;
            }
            case "web": {
                // A globe, its meridian turning.
                float gy = y + s * 0.05f, r = s * 0.06f;
                fill.setColor(0xFF2E4A7A);
                c.drawCircle(x, gy, r, fill);
                line.setColor(accent);
                line.setStrokeWidth(s * 0.007f);
                c.drawCircle(x, gy, r, line);
                c.drawLine(x - r, gy, x + r, gy, line);
                float m = (float) Math.cos(t * 1.5f) * r;
                oval.set(x - Math.abs(m), gy - r, x + Math.abs(m), gy + r);
                c.drawOval(oval, line);
                break;
            }
            case "delegate": {
                // A small buddy bouncing alongside.
                float by = y + s * 0.07f - Math.abs(wave(t, 2f)) * s * 0.03f;
                fill.setColor(shade(body, 0.9f));
                c.drawCircle(x, by, s * 0.05f, fill);
                fill.setColor(paper);
                c.drawCircle(x - s * 0.017f, by - s * 0.01f, s * 0.011f, fill);
                c.drawCircle(x + s * 0.017f, by - s * 0.01f, s * 0.011f, fill);
                break;
            }
            case "plan": {
                // A clipboard, ticks appearing one by one.
                fill.setColor(paper);
                oval.set(x - s * 0.07f, y - s * 0.02f, x + s * 0.08f, y + s * 0.15f);
                c.drawRoundRect(oval, s * 0.01f, s * 0.01f, fill);
                int done = (int) ((t * 0.8f) % 4);
                for (int i = 0; i < 3; i++) {
                    float ly = y + s * (0.02f + i * 0.04f);
                    line.setColor(muted);
                    line.setStrokeWidth(s * 0.006f);
                    c.drawLine(x - s * 0.01f, ly + s * 0.01f, x + s * 0.06f, ly + s * 0.01f, line);
                    if (i < done) {
                        line.setColor(0xFF2F8F5B);
                        line.setStrokeWidth(s * 0.01f);
                        c.drawLine(x - s * 0.055f, ly + s * 0.01f, x - s * 0.04f, ly + s * 0.022f, line);
                        c.drawLine(x - s * 0.04f, ly + s * 0.022f, x - s * 0.02f, ly - s * 0.004f, line);
                    }
                }
                break;
            }
            default: {
                // A spanner, turning.
                c.save();
                c.rotate(wave(t, 1.2f) * 30f, x, y + s * 0.05f);
                line.setColor(paper);
                line.setStrokeWidth(s * 0.02f);
                c.drawLine(x - s * 0.05f, y + s * 0.1f, x + s * 0.03f, y + s * 0.02f, line);
                line.setStrokeWidth(s * 0.014f);
                oval.set(x + s * 0.01f, y - s * 0.02f, x + s * 0.07f, y + s * 0.04f);
                c.drawArc(oval, 120, 270, false, line);
                c.restore();
            }
        }
        line.setColor(paper);
    }

    private void bubble(Canvas c, float x, float y, float s, float t, String mark, int color) {
        float pulse = 1f + wave(t, 1.2f) * 0.06f;
        float r = s * 0.065f * pulse;
        fill.setColor(screen);
        c.drawCircle(x, y, r, fill);
        line.setColor(color);
        line.setStrokeWidth(s * 0.01f);
        c.drawCircle(x, y, r, line);
        fill.setColor(screen);
        c.drawCircle(x - r * 0.9f, y + r * 1.1f, r * 0.22f, fill);
        c.drawCircle(x - r * 0.9f, y + r * 1.1f, r * 0.22f, line);
        text.setColor(color);
        text.setTextSize(s * 0.09f);
        c.drawText(mark, x, y + s * 0.032f, text);
    }

    private void sparkles(Canvas c, float cx, float cy, float rx, float ry, float s, float t) {
        float[][] at = { { -1.3f, -0.9f }, { 1.25f, -1.0f }, { -1.15f, 0.35f }, { 1.35f, 0.25f } };
        for (int i = 0; i < at.length; i++) {
            float tw = (wave(t + i * 0.37f, 1.1f) + 1f) / 2f;
            float r = s * (0.012f + 0.02f * tw);
            float x = cx + at[i][0] * rx, y = cy + at[i][1] * ry;
            fill.setColor(i % 2 == 0 ? amber : success);
            path.reset();
            path.moveTo(x, y - r * 2);
            path.quadTo(x, y, x + r * 2, y);
            path.quadTo(x, y, x, y + r * 2);
            path.quadTo(x, y, x - r * 2, y);
            path.quadTo(x, y, x, y - r * 2);
            c.drawPath(path, fill);
        }
        // A check badge.
        float bx = cx + rx * 0.95f, by = cy - ry * 0.95f, br = s * 0.045f;
        fill.setColor(success);
        c.drawCircle(bx, by, br, fill);
        line.setColor(ink);
        line.setStrokeWidth(s * 0.012f);
        c.drawLine(bx - br * 0.45f, by, bx - br * 0.1f, by + br * 0.38f, line);
        c.drawLine(bx - br * 0.1f, by + br * 0.38f, bx + br * 0.5f, by - br * 0.35f, line);
    }

    /** Thought bubbles rising, one after another. */
    private void thinking(Canvas c, float x, float y, float s, float t) {
        int lit = (int) ((t * 2.5f) % 4);
        for (int i = 0; i < 3; i++) {
            fill.setColor(i < lit ? paper : rule);
            c.drawCircle(x + i * s * 0.045f, y - s * (0.03f + i * 0.04f), s * (0.014f + i * 0.007f), fill);
        }
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) invalidate();
    }

    private void sleep(Canvas c, float x, float y, float s, float t) {
        text.setColor(muted);
        for (int i = 0; i < 3; i++) {
            float p = ((t * 0.35f) + i / 3f) % 1f;
            text.setAlpha((int) (255 * (1f - p)));
            text.setTextSize(s * (0.04f + 0.03f * p));
            c.drawText("z", x + p * s * 0.12f, y - p * s * 0.16f, text);
        }
        text.setAlpha(255);
    }
}

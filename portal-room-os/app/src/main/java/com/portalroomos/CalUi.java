package com.portalroomos;

import android.graphics.Color;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared palette, formats, and drawing helpers for the calendar views. */
final class CalUi {
    private CalUi() {}

    // Mirror of res/values/colors.xml. The calendar views paint onto the
    // translucent panel that floats over the background photo, so these are
    // checked by tools/contrast_check.py; keep the two files in step.
    static final int BG = 0xFF101218;
    static final int PANEL = 0xE00E1119;
    static final int TEXT = 0xFFF6F7FB;
    static final int MUTED = 0xFFBCC3D1;
    static final int ACCENT = 0xFFAEC0FF;
    static final int LINE = 0x66FFFFFF;
    static final int TODAY_TINT = 0x1FAEC0FF;
    static final int NOW = 0xFFFF7A7A;
    static final int INK = 0xFF10131A;

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    static float dp(View v, float d) { return d * v.getResources().getDisplayMetrics().density; }

    /**
     * Readable text color on a calendar's own color. Event chips are painted in
     * whatever color Google hands back, so pick whichever ink actually wins the
     * WCAG contrast ratio against it rather than guessing from a luminance cut.
     */
    static int textOn(int bg) {
        float l = Color.luminance(bg);
        float onWhite = 1.05f / (l + 0.05f);
        float onInk = (l + 0.05f) / (Color.luminance(INK) + 0.05f);
        return onInk >= onWhite ? INK : 0xFFFFFFFF;
    }

    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    static String fit(TextPaint p, String s, float maxW) {
        if (s == null) return "";
        return TextUtils.ellipsize(s, p, Math.max(0, maxW), TextUtils.TruncateAt.END).toString();
    }

    static String hourLabel(int h) {
        if (h == 0 || h == 24) return "12 AM";
        if (h < 12) return h + " AM";
        if (h == 12) return "12 PM";
        return (h - 12) + " PM";
    }

    /** "9 AM", "9:30 AM" */
    static String shortTime(ZonedDateTime t) { return t.format(TIME).replace(":00", ""); }

    /** Sunday-based week start. */
    static LocalDate startOfWeek(LocalDate d) { return d.minusDays(d.getDayOfWeek().getValue() % 7); }
}

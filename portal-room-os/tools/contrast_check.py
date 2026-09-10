#!/usr/bin/env python3
"""WCAG contrast audit for the Portal Room OS photo background.

Every piece of text in the app sits on a translucent panel, bar, chip or button
that floats over `portal_background.png`. This script proves the text stays
readable by checking each text/surface pair twice:

  worst  the surface composited over pure white (#FFFFFF) - the strictest
         bound possible, independent of which photo is used
  photo  the surface composited over the scrim composited over the brightest
         pixel the photo actually puts behind that surface

WCAG 2.1 SC 1.4.6 (Contrast Enhanced, level AAA) wants 7:1 for normal text and
4.5:1 for large text. Large means >=18sp, or >=14sp when bold (the app renders
at mdpi, where 1sp = 1px = 1dp, so sp maps straight onto the WCAG px scale).
SC 1.4.11 wants 3:1 for meaningful non-text (rules, borders, the now-line).

Run: python3 tools/contrast_check.py
Exits non-zero if anything fails, so it can gate a build.
"""
import re
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IMAGE = ROOT / "app/src/main/res/drawable-nodpi/portal_background.png"


# ---------------------------------------------------------------- colour math

def rgb(c):
    """'#RRGGBB' or '#AARRGGBB' -> (r, g, b, a) with a in 0..1."""
    h = c.lstrip("#")
    if len(h) == 8:
        a, r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4, 6))
        return r, g, b, a / 255.0
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return r, g, b, 1.0


def over(fg, bg):
    """Composite a translucent colour over an opaque one. Returns (r, g, b)."""
    fr, fg_, fb, fa = rgb(fg) if isinstance(fg, str) else fg
    br, bg_, bb = bg[:3]
    return (fa * fr + (1 - fa) * br,
            fa * fg_ + (1 - fa) * bg_,
            fa * fb + (1 - fa) * bb)


def stack(layers, base):
    """Composite layers back-to-front over an opaque base colour."""
    out = base
    for layer in layers:
        out = over(layer, out)
    return out


def luminance(c):
    def chan(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * chan(c[0]) + 0.7152 * chan(c[1]) + 0.0722 * chan(c[2])


def contrast(fg, bg):
    a, b = luminance(fg), luminance(bg)
    if a < b:
        a, b = b, a
    return (a + 0.05) / (b + 0.05)


# ------------------------------------------------------------------ png decode

def load_png(path):
    """Minimal PNG reader: 8-bit truecolour, non-interlaced. -> (w, h, pixels)."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, w = 8, b"", None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            w, h, depth, colour = struct.unpack(">IIBB", body[:10])
            if depth != 8 or colour not in (2, 6):
                raise ValueError(f"need 8-bit RGB(A), got depth={depth} colour={colour}")
            channels = 3 if colour == 2 else 4
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = w * channels
    out, prev, at = [], bytearray(stride), 0
    for _ in range(h):
        filt = raw[at]
        line = bytearray(raw[at + 1:at + 1 + stride])
        at += 1 + stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = prev[i]
            c = prev[i - channels] if i >= channels else 0
            if filt == 1:
                line[i] = (line[i] + a) & 0xFF
            elif filt == 2:
                line[i] = (line[i] + b) & 0xFF
            elif filt == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif filt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out.append(bytes(line))
        prev = line
    return w, h, out, channels


def brightest_in(img, box):
    """Highest-luminance pixel inside a fractional box (x0, y0, x1, y1)."""
    w, h, rows, ch = img
    x0, y0, x1, y1 = box
    best, best_l = (0, 0, 0), -1.0
    for y in range(int(y0 * h), max(int(y0 * h) + 1, int(y1 * h))):
        row = rows[y]
        for x in range(int(x0 * w), max(int(x0 * w) + 1, int(x1 * w))):
            i = x * ch
            px = (row[i], row[i + 1], row[i + 2])
            l = luminance(px)
            if l > best_l:
                best, best_l = px, l
    return best


# ------------------------------------------------------ palette, from the app
# Read straight out of the resources, so this script cannot drift from what
# actually ships. CalUi.java and TransitActivity.java repeat some of the same
# values for canvas-drawn views; both mirrors are cross-checked below.

RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java/com/portalroomos"


def read_colors():
    text = (RES / "values/colors.xml").read_text()
    return dict(re.findall(r'<color name="(\w+)">(#[0-9A-Fa-f]{6,8})</color>', text))


def read_scrim():
    """(rgb, stops) from the gradient in screen_bg.xml."""
    text = (RES / "drawable/screen_bg.xml").read_text()
    got = {}
    for which in ("start", "center", "end"):
        m = re.search(rf'android:{which}Color="#([0-9A-Fa-f]{{8}})"', text)
        if not m:
            raise SystemExit(f"screen_bg.xml has no {which}Color")
        got[which] = m.group(1)
    tints = {v[2:] for v in got.values()}
    if len(tints) != 1:
        raise SystemExit(f"scrim stops must share one tint, got {tints}")
    return "#" + tints.pop(), ((0.0, int(got["start"][:2], 16)),
                               (0.5, int(got["center"][:2], 16)),
                               (1.0, int(got["end"][:2], 16)))


def norm(v):
    v = v.lstrip("#").upper()
    return v if len(v) == 8 else "FF" + v


def check_mirrors(c):
    """Java constants that repeat colors.xml for canvas-drawn views."""
    out = []
    for f, want in (('CalUi.java', {"TEXT": "text", "MUTED": "muted",
                                    "ACCENT": "accent", "PANEL": "panel",
                                    "LINE": "rule"}),
                    ('TransitActivity.java', {"GREEN": "success",
                                              "AMBER": "amber",
                                              "RED": "alert"})):
        src = (SRC / f).read_text()
        got = dict((m, v.upper()) for m, v in
                   re.findall(r"(\w+) = 0x([0-9A-Fa-f]{8})", src))
        for const, res in want.items():
            if got.get(const) != norm(c[res]):
                out.append(f"{f}: {const}=#{got.get(const)} but "
                           f"colors.xml {res}={norm(c[res])}")
    return out


C = read_colors()
INK = {k: C[k] for k in ("text", "muted", "accent", "success", "amber", "alert")}

# Surfaces confined to one band get that band; anything reused up and down the
# screen is sampled across the whole photo, which is the stricter reading.
SURFACES = {
    "bar":         ([C["bar"]],            (0.00, 0.25)),  # calendar/transit toolbars
    "header":      ([C["panel"]],          (0.00, 0.25)),  # dashboard clock card
    "panel":       ([C["panel"]],          (0.00, 1.00)),
    "button":      ([C["button"]],         (0.00, 1.00)),
    "button_down": ([C["button_pressed"]], (0.00, 1.00)),
    "chip":        ([C["bar"]],            (0.00, 1.00)),
    "field":       ([C["panel"]],          (0.00, 1.00)),
    "dialog":      ([C["dialog"]],         (0.00, 1.00)),
    "dialog_field":([C["bar"], C["dialog"]], (0.00, 1.00)),
}

SCRIM_RGB, SCRIM_STOPS = read_scrim()

NOW_LINE = "#" + re.search(r"NOW = 0xFF([0-9A-Fa-f]{6})",
                           (SRC / "CalUi.java").read_text()).group(1)

# Non-text that has to stay visible: SC 1.4.11 asks 3:1.
NONTEXT = [
    ("calendar grid rules", C["rule"], "panel"),
    ("panel edge",          C["rule"], "panel"),
    ("button edge",         C["rule"], "button"),
    ("now line",            NOW_LINE,  "panel"),
    ("dial track",          C["rule"], "panel"),
    ("dial arc focus",      C["amber"],   "panel"),
    ("dial arc break",      C["success"], "panel"),
    ("dial arc idle",       C["accent"],  "panel"),
    ("list divider",        C["rule"], "panel"),
    ("dialog edge",         C["rule"], "dialog"),
]

# screen, element, ink, sp, bold, surface
ELEMENTS = [
    ("dashboard", "clock",              "text",    48, True,  "header"),
    ("dashboard", "date",               "muted",   18, False, "header"),
    ("dashboard", "weather",            "text",    24, False, "header"),
    ("dashboard", "presence",           "success", 15, False, "header"),
    ("dashboard", "panel labels",       "muted",   13, True,  "panel"),
    ("dashboard", "focus",              "text",    29, True,  "panel"),
    ("dashboard", "next event",         "text",    21, False, "panel"),
    ("dashboard", "reminder",           "muted",   17, False, "panel"),
    ("dashboard", "home summary",       "text",    19, False, "panel"),
    ("dashboard", "now playing",        "text",    19, False, "panel"),
    ("dashboard", "button labels",      "text",    18, False, "button"),
    ("dashboard", "button labels down", "text",    18, False, "button_down"),
    ("dashboard", "status line",        "muted",   13, False, "chip"),
    ("dashboard", "timer phase focus",  "amber",   13, True,  "panel"),
    ("dashboard", "timer phase break",  "success", 13, True,  "panel"),
    ("dashboard", "timer phase paused", "muted",   13, True,  "panel"),
    ("dashboard", "timer countdown",    "text",    44, True,  "panel"),
    ("dashboard", "timer name",         "muted",   15, False, "panel"),

    ("calendar",  "range title",        "text",    23, True,  "bar"),
    ("calendar",  "nav buttons",        "text",    14, False, "button"),
    ("calendar",  "mode button on",     "accent",  14, False, "button"),
    ("calendar",  "hour labels",        "muted",   12, False, "panel"),
    ("calendar",  "day header",         "text",    16, True,  "panel"),
    ("calendar",  "month day numbers",  "text",    14, False, "panel"),
    ("calendar",  "status line",        "muted",   12, False, "chip"),

    ("transit",   "screen title",       "text",    23, True,  "bar"),
    ("transit",   "updated at",         "muted",   14, False, "bar"),
    ("transit",   "row time",           "text",    24, True,  "panel"),
    ("transit",   "row am/pm",          "muted",   12, False, "panel"),
    ("transit",   "row title",          "text",    17, False, "panel"),
    ("transit",   "row subtitle",       "muted",   13, False, "panel"),
    ("transit",   "row status ok",      "success", 13, True,  "panel"),
    ("transit",   "row status warn",    "amber",   13, True,  "panel"),
    ("transit",   "row status late",    "alert",   13, True,  "panel"),
    ("transit",   "section header",     "muted",   13, True,  "panel"),
    ("transit",   "note",               "muted",   15, False, "panel"),

    ("music",     "track title",        "text",    24, True,  "panel"),
    ("music",     "track artist",       "muted",   17, False, "panel"),
    ("music",     "device label",       "muted",   13, False, "panel"),
    ("music",     "back button",        "muted",   14, False, "button"),
    ("music",     "search text",        "text",    16, False, "field"),
    ("music",     "search hint",        "muted",   16, False, "field"),
    ("music",     "list title",         "muted",   13, True,  "chip"),
    ("music",     "item title",         "text",    18, False, "panel"),
    ("music",     "item subtitle",      "muted",   14, False, "panel"),
    ("music",     "status line",        "muted",   13, False, "chip"),

    ("timer",     "screen title",       "text",    23, True,  "bar"),
    ("timer",     "header hint",        "muted",   14, False, "bar"),
    ("timer",     "mode button on",     "accent",  16, False, "button"),
    ("timer",     "mode button off",    "muted",   16, False, "button"),
    ("timer",     "countdown",          "text",    96, True,  "panel"),
    ("timer",     "hundredths",         "muted",   38, False, "panel"),
    ("timer",     "phase focus",        "amber",   22, True,  "panel"),
    ("timer",     "phase break",        "success", 22, True,  "panel"),
    ("timer",     "phase idle",         "accent",  22, True,  "panel"),
    ("timer",     "phase sub",          "muted",   17, False, "panel"),
    ("timer",     "panel labels",       "muted",   13, True,  "panel"),
    ("timer",     "preset on",          "accent",  18, False, "button"),
    ("timer",     "preset off",         "muted",   18, False, "button"),
    ("timer",     "session name",       "text",    18, False, "button"),
    ("timer",     "session unnamed",    "muted",   18, False, "button"),
    ("timer",     "session meta",       "muted",   14, False, "button"),
    ("timer",     "sessions empty",     "muted",   17, False, "panel"),
    ("timer",     "name chip",          "accent",  18, False, "chip"),
    ("timer",     "preset custom on",   "accent",  18, False, "button"),
    ("timer",     "preset custom off",  "muted",   18, False, "button"),
    ("timer",     "clear button",       "muted",   14, False, "button"),
    ("timer",     "lap summary",        "muted",   15, False, "panel"),
    ("timer",     "lap split",          "text",    22, True,  "panel"),
    ("timer",     "lap index",          "muted",   15, False, "panel"),
    ("timer",     "lap total",          "muted",   14, False, "panel"),
    ("timer",     "distraction time",   "text",    17, False, "panel"),
    ("timer",     "distraction where",  "muted",   15, False, "panel"),
    ("timer",     "transport primary",  "text",    26, True,  "button"),
    ("timer",     "transport labels",   "text",    22, False, "button"),
    ("timer",     "status line",        "muted",   13, False, "chip"),

    ("calendar",  "mode button off",    "muted",   14, False, "button"),

    ("dialog",    "title",              "text",    24, True,  "dialog"),
    ("dialog",    "field label",        "muted",   14, True,  "dialog"),
    ("dialog",    "stepper value",      "text",    42, True,  "dialog"),
    ("dialog",    "stepper sign",       "text",    30, False, "button"),
    ("dialog",    "unit",               "muted",   17, False, "dialog"),
    ("dialog",    "hint",               "muted",   15, False, "dialog"),
    ("dialog",    "name input",         "text",    22, False, "dialog_field"),
    ("dialog",    "name placeholder",   "muted",   22, False, "dialog_field"),
    ("dialog",    "confirm button",     "accent",  14, True,  "dialog"),
    ("dialog",    "cancel button",      "muted",   14, True,  "dialog"),
]


def scrim_alpha(y):
    """Gradient alpha (0..1) at fractional height y."""
    for (y0, a0), (y1, a1) in zip(SCRIM_STOPS, SCRIM_STOPS[1:]):
        if y0 <= y <= y1:
            t = (y - y0) / (y1 - y0)
            return (a0 + t * (a1 - a0)) / 255.0
    return SCRIM_STOPS[-1][1] / 255.0


def required(sp, bold):
    """AAA threshold. Large text is >=18sp, or >=14sp bold."""
    return 4.5 if (sp >= 18 or (bold and sp >= 14)) else 7.0


def main():
    img = load_png(IMAGE) if IMAGE.exists() else None
    if img is None:
        print(f"! {IMAGE} missing - photo column skipped\n")

    rows, failures = [], 0
    for screen, name, ink_key, sp, bold, surface in ELEMENTS:
        layers, band = SURFACES[surface]
        ink = rgb(INK[ink_key])[:3]
        need = required(sp, bold)

        worst = contrast(ink, stack(layers, (255, 255, 255)))

        photo = None
        if img:
            # weakest scrim anywhere in the band, over the band's brightest pixel
            alpha = min(scrim_alpha(band[0]), scrim_alpha(band[1]))
            sr, sg, sb, _ = rgb(SCRIM_RGB)
            lit = brightest_in(img, (0.0, band[0], 1.0, band[1]))
            scrimmed = over((sr, sg, sb, alpha), lit)
            photo = contrast(ink, stack(layers, scrimmed))

        ok = worst >= need and (photo is None or photo >= need)
        failures += not ok
        rows.append((screen, name, f"{sp}sp{' bold' if bold else ''}", surface,
                     need, worst, photo, ok))

    w = max(len(f"{s} / {n}") for s, n, *_ in rows)
    print(f"{'element'.ljust(w)}  {'size':>9}  {'surface':<11} {'need':>5} "
          f"{'worst':>6} {'photo':>6}")
    print("-" * (w + 46))
    last = None
    for screen, name, size, surface, need, worst, photo, ok in rows:
        if screen != last:
            print()
            last = screen
        p = f"{photo:5.1f}" if photo else "    -"
        print(f"{f'{screen} / {name}'.ljust(w)}  {size:>9}  {surface:<11} "
              f"{need:4.1f}: {worst:5.1f} {p}  {'ok' if ok else 'FAIL'}")

    print("\nnon-text (SC 1.4.11, needs 3.0:1)")
    for name, colour, surface in NONTEXT:
        layers, band = SURFACES[surface]
        base = stack(layers, (255, 255, 255))
        c = contrast(over(colour, base), base)
        ok = c >= 3.0
        failures += not ok
        print(f"  {name.ljust(w - 2)} {c:5.1f}  {'ok' if ok else 'FAIL'}")

    drift = check_mirrors(C)
    for line in drift:
        print(f"  ! {line}")
    failures += len(drift)

    print(f"\n{len(rows)} text checks, {failures} failing"
          f"  ->  {'AAA clean' if not failures else 'NOT COMPLIANT'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

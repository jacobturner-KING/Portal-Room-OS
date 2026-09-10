#!/usr/bin/env python3
"""Render the timer's singing bowl to a WAV so it can be auditioned off-device.

The synthesis constants are parsed out of SingingBowl.java rather than repeated
here, so the preview is always the tone the Portal actually plays. Change the
table in the Java file, re-run this, listen.

Run: python3 tools/bowl_preview.py [out.wav]
"""
import math
import re
import struct
import sys
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/portalroomos/SingingBowl.java"


def constants():
    text = SRC.read_text()

    def number(name):
        m = re.search(rf"{name}\s*=\s*([0-9.]+)", text)
        if not m:
            raise SystemExit(f"SingingBowl.java has no {name}")
        return float(m.group(1))

    block = re.search(r"PARTIALS\s*=\s*\{(.*?)\n    \};", text, re.S)
    if not block:
        raise SystemExit("could not find the PARTIALS table")
    partials = [[float(v) for v in row.split(",")]
                for row in re.findall(r"\{([^{}]+)\}", block.group(1))]
    return {
        "rate": int(number("SAMPLE_RATE")),
        "seconds": number("SECONDS"),
        "f0": number("F0"),
        "attack": number("ATTACK_SECONDS"),
        "fade": number("FADE_SECONDS"),
        "peak": number("PEAK"),
        "volume": number("VOLUME"),
        "partials": partials,
    }


def render(c):
    rate, n = c["rate"], int(c["seconds"] * c["rate"])
    buf = [0.0] * n

    for ratio, gain, decay, beat in c["partials"]:
        freq = c["f0"] * ratio
        low = 2 * math.pi * (freq - beat / 2) / rate
        high = 2 * math.pi * (freq + beat / 2) / rate
        for i in range(n):
            env = gain * math.exp(-(i / rate) / decay)
            buf[i] += env * (math.sin(low * i) + math.sin(high * i)) * 0.5

    attack = int(c["attack"] * rate)
    for i in range(min(attack, n)):
        buf[i] *= 0.5 - 0.5 * math.cos(math.pi * i / attack)
    fade = int(c["fade"] * rate)
    for i in range(max(0, n - fade), n):
        p = (n - i) / fade
        buf[i] *= 0.5 - 0.5 * math.cos(math.pi * p)

    loudest = max(abs(v) for v in buf) or 1.0
    # The device applies VOLUME at the track rather than to the samples; fold it
    # in here so the preview is as loud as the Portal actually plays it.
    scale = c["peak"] * c["volume"] * 32767 / loudest
    return [int(round(v * scale)) for v in buf]


def main():
    c = constants()
    print(f"{c['f0']:.0f} Hz fundamental, {len(c['partials'])} partials, "
          f"{c['seconds']:.1f} s at {c['rate']} Hz, playback volume "
          f"{c['volume']:.2f}")
    for ratio, gain, decay, beat in c["partials"]:
        print(f"  {c['f0'] * ratio:8.1f} Hz  gain {gain:.2f}  "
              f"decay {decay:.2f} s  beat {beat:.1f} Hz")

    samples = render(c)
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "singing-bowl.wav")
    with wave.open(str(out), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(c["rate"])
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))

    rate = c["rate"]
    def rms(a, b):
        window = samples[int(a * rate):int(b * rate)]
        return math.sqrt(sum(v * v for v in window) / max(1, len(window))) / 32767
    print(f"\nwrote {out}  ({out.stat().st_size / 1024:.0f} KB)")
    print(f"  peak {max(abs(v) for v in samples) / 32767:.2f} full scale")
    for a, b in ((0, 0.25), (1, 1.25), (3, 3.25), (6, 6.25)):
        print(f"  rms at {a:>4.1f}s  {rms(a, b):.4f}")


if __name__ == "__main__":
    main()

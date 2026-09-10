# Portal Room OS

A first-generation Meta Portal (2018, 10", codename `aloha`, Android 9 / API 28,
arm64, no Google services, locked bootloader) revived as a wall dashboard that is
also a full Spotify controller and its own Spotify Connect speaker.

The Portal is a thin terminal. A Cloudflare Worker ("Room Brain") holds every
integration, secret, and decision. Specs: `docs/PRODUCT_SPEC.md`, `docs/ARCHITECTURE.md`.

## What works today
- Dashboard (`MainActivity`): clock, weather, daily focus, next calendar event,
  home line, now-playing, and Talk / Lights / Music / Calendar / Transit / Timer
  / Add buttons. A running Pomodoro shows as a card at the top of the right
  column, with its ring, phase, remaining time and session name, sized to read
  from across the room; tap it to open the timer screen. The card is only there
  when a timer is going, so an idle dashboard stays clean.
- Spotify controller (`MusicActivity`): search, playlists, transport, album art,
  and a device picker.
- Calendar (`CalendarActivity`, from the Calendar button or the NEXT card): day,
  3-day and week time grids, a month grid, and a year heat map (one-off events only) across every
  Google calendar shown in your Google UI. Tap an event for details; Edit
  (title, location, description, times, all-day) appears once Google is linked
  with the edit scope. Custom views: `DayHeaderView`, `TimeGridView`,
  `MonthGridView`, `YearView`.
- Transit (`TransitActivity`, Transit button): a ferry board both ways with live
  vessel status, ETA, delays, drive-up space and service alerts; an optional
  passenger-only water taxi timetable; and live bus arrivals. Refreshes every
  minute. Data: WSDOT Ferries API (keyless) and OneBusAway Puget Sound (shared
  `TEST` key until `OBA_API_KEY` is set; the shared key throttles bursts, so an
  occasional "live data unavailable" line is expected).

  Which terminals, stops and routes it shows is not in the source. It comes from
  the Worker's `TRANSIT_CONFIG` value, so a checkout carries nobody's home town,
  and the screen adapts to whoever sets it: even the title and each stop's label
  arrive with the data. Unset, the screen says it is not configured. The shape is
  documented in `room-brain-worker/.dev.vars.example`.
- Timer (`TimerActivity`, Timer button): a Pomodoro focus timer and a stopwatch
  behind one mode switch, sharing a hand-drawn radial dial (`TimerProgressView`,
  a plain `View` with an `onDraw` arc). The Pomodoro runs 25/5, 50/10 or a
  custom interval you set with big steppers (focus 5 to 180 by 5, break 1 to 60
  by 1), rolls focus straight into its break, and keeps a tap-once distraction
  log so noting an interruption does not break flow. Name the session from the
  chip inside the ring before you start, or tap any finished row to rename it
  afterwards; the name sticks to following sessions until you change it, and
  each row records the interval and the minute the focus actually ended. The
  stopwatch does laps with splits plus fastest and slowest, and its ring sweeps
  once a minute since there is no end to count toward.

  A phase ends on a singing bowl. It is synthesised in `SingingBowl.java` rather
  than sampled, so the repo carries no audio blob and the tone is five rows of
  numbers: five inharmonic partials over a 216 Hz fundamental, the high ones
  decaying first, each ringing as a detuned pair so the tail shimmers.
  `tools/bowl_preview.py` parses those constants straight out of the Java and
  renders the same waveform to a WAV, so the tone can be auditioned on a laptop
  and cannot drift from what the Portal plays. It comes out of the media stream,
  so it follows the same volume as music, and rides it at half amplitude
  (`SingingBowl.VOLUME`, 0.5, about 6 dB down) because a bell at full scale on a
  stream librespot pins to maximum is startling. Raise that toward 1.0 for a
  louder bell; the preview folds the same figure in, so it stays honest.

  Picking an interval mid-session queues it rather than tearing down the run.

  The two run on different clocks on purpose. The Pomodoro pins its phase to a
  wall-clock instant, so twenty-five minutes is twenty-five minutes of real time
  whether or not the screen is up, and a phase that ends while you are away is
  picked up on return with its break anchored to the real boundary. Being away
  for hours credits exactly one session, never a chain of them. The stopwatch
  measures on the monotonic clock and persists a wall-clock anchor, so both
  survive leaving the screen, force-stop and reboot. Both can run at once; the
  toolbar shows the Pomodoro while the stopwatch is on screen.

  The Pomodoro model itself lives in `TimerState`, not in either screen, which
  is what lets the dashboard show it. Both screens load it, walk it over any
  boundary that has already passed, and write it back; only the screen in front
  ticks, so a phase is credited and rung exactly once. That means a phase
  landing while the dashboard is up rings there too. Nothing runs in the
  background, so a phase that ends while the Portal is on the calendar, transit
  or music screen is picked up silently on return rather than sounding.

- On-device audio (`LibrespotService`): a bundled Rust librespot 0.8.0 binary
  advertises the Portal as Connect device **"Portal"** and plays through the
  Portal's speaker. Verified underrun-free with a 1 s AudioTrack buffer.
  Credentials persist in the app's files dir, so it logs back in on every
  start and "Portal" stays in the Spotify device list. `BootReceiver` starts the
  service at boot.
- Room Brain Worker: your own `https://portal-room-brain.<subdomain>.workers.dev`
  (Spotify, Google Calendar, Open-Meteo weather auto-located from the Portal's
  network, Major Key and Home Assistant hooks ready for their secrets).

## Look and accessibility
Every screen sits on a photograph:
`app/src/main/res/drawable-nodpi/portal_background.png`, 1280x800, the Portal's
exact panel size, so it blits 1:1 with no scaling or distortion. It is set as
the theme's `windowBackground`, so all four activities inherit it and the
launch preview shows it too, with no dark flash. `drawable/screen_bg.xml`
layers that bitmap under a soft vertical scrim. The full-size master lives at
`../Portal Background.png`.

No text sits directly on the photo. Every label lives on a translucent dark
surface - a card (`panel_bg`), a toolbar (`bar_bg`), a pill (`chip_bg`) or a
button - and those surfaces are dark enough that the ink above clears **WCAG
2.1 level AAA** (SC 1.4.6: 7:1 for normal text, 4.5:1 for large) *even where
the photo is pure white*. Contrast therefore does not depend on which picture
is in place. Panel and button edges, the calendar rules and the now-line clear
3:1 for SC 1.4.11, which is what keeps a dark button findable when dark foliage
happens to sit behind it.

`tools/contrast_check.py` proves all of that rather than asserting it. It reads
`colors.xml`, the scrim out of `screen_bg.xml` and the Java palette mirrors,
decodes the PNG itself, and checks all 41 text pairs twice: against a pure
white backdrop (the strictest bound), and against the brightest pixel the photo
actually puts behind each surface. It exits non-zero on any failure.

```bash
python3 tools/contrast_check.py
```

The timer's two dialogs sit on their own opaque ground (`dialog_bg`) rather than
over the photo, so their contrast does not depend on the window behind them, and
they are checked the same way.

Run it after touching any colour. `CalUi.java` and `TransitActivity.java`
repeat part of the palette for the canvas-drawn calendar and transit views; the
script fails if either drifts from `colors.xml`. To swap the photo, drop in a
new 1280x800 PNG and re-run.

## Layout
```
app/                    Android app (package com.portalroomos, no AndroidX)
  src/main/jniLibs/arm64-v8a/liblibrespot.so   librespot binary (stripped, ~13 MB)
  src/main/res/drawable-nodpi/portal_background.png   wall photo, 1280x800
tools/contrast_check.py WCAG AAA gate for the palette over that photo.
room-brain-worker/      Cloudflare Worker (TypeScript). See its README for endpoints.
room-brain/             Retired FastAPI mock, kept for offline experiments only.
docs/                   Product spec and architecture.
```

## Build and deploy the app
`$PORTAL` below is your Portal's adb address: the serial from `adb devices` over
USB, or `<portal-ip>:5555` over Wi-Fi.

```bash
cd portal-room-os
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PORTAL=$(adb devices | awk 'NR==2 {print $1}')
python3 tools/contrast_check.py     # palette gate, must stay AAA clean
./gradlew :app:assembleDebug
adb -s $PORTAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $PORTAL shell am start -n com.portalroomos/.MainActivity
```
The Gradle wrapper is pinned to 8.9 (system Gradle 9.x is too new for AGP 8.5.2).
Always install with `adb install`; the on-device installer dialog is broken
(white on white).

`local.properties` (gitignored) must hold the SDK path, your Worker's URL, and
the app's bearer token, the same value as `APP_TOKEN` in the Worker's
`.dev.vars`:
```
sdk.dir=/opt/homebrew/share/android-commandlinetools
roomBrain.url=https://portal-room-brain.<subdomain>.workers.dev
roomBrain.appToken=<APP_TOKEN>
```
Neither is committed: both reach `Config.java` as `BuildConfig` fields, because
the Worker hostname carries the Cloudflare account subdomain.

Watch the speaker: `adb -s $PORTAL logcat -s Librespot`. While audio
flows it logs a line every 30 s with seconds streamed and the underrun delta
(should stay `+0`).

### Loudness
Three things set how loud the Portal is, in order:
1. The Spotify app's volume slider for "Portal" (librespot softvol, cubic curve).
   Push it to 100% for maximum output.
2. Android's media stream. The service pins it to max whenever audio starts.
3. A LoudnessEnhancer gain stage (limiter-backed, default +8 dB) on the
   AudioTrack. Tune it live, no rebuild, 0 to 20 dB; the value persists:
   ```bash
   adb -s $PORTAL shell am broadcast -a com.portalroomos.SET_GAIN --ei gain_db 12 -n com.portalroomos/.GainReceiver
   ```
   Higher values get louder but more compressed; back it off if it sounds squashed.

## Device access
- ADB is enabled under Settings → Debug. `adb devices` gives the serial.
- USB always works. Wi-Fi ADB is `adb connect <portal-ip>:5555`, armed with
  `adb tcpip 5555` over USB. It drops on every Portal reboot; re-cable to re-arm.
- The Portal must be on the same Wi-Fi as the phones that should see "Portal".
  The Worker is public, so the dashboard works from any network.
- Account setup only completed on a non-home Wi-Fi (Facebook "unknown error (1)"
  and WhatsApp "can't link" on the home network).

## Rebuild librespot (only if needed)
Clone `https://github.com/librespot-org/librespot` at `v0.8.0`, then with
`. $HOME/.cargo/env`, `ANDROID_HOME` set and CC/linker pointed at NDK
`30.0.16138531`'s `aarch64-linux-android28-clang`:
```bash
cargo build --release --target aarch64-linux-android \
  --no-default-features --features "rustls-tls-webpki-roots,with-libmdns"
```
`llvm-strip` the result and copy it to `app/src/main/jniLibs/arm64-v8a/liblibrespot.so`.
The service runs it with `-B pipe` (raw S16LE 44.1 kHz stereo on stdout),
`--system-cache` in the app files dir and a 1 GB `--cache` in the app cache dir.

## Worker
```bash
cd room-brain-worker
npx tsc --noEmit        # keep clean; ambient Env lives in src/env.d.ts
npx wrangler deploy --config wrangler.local.jsonc
```
`wrangler.jsonc` is committed with no account ids in it, so the KV namespace id
is a placeholder. Keep your real one in `wrangler.local.jsonc`, which is
gitignored, and deploy with `--config` as above; plain `npx wrangler deploy`
works too if you paste the id straight in and never commit it. First deploy:
`wrangler kv namespace create ROOM_KV` prints that id. Secrets are set with
`wrangler secret put` and mirrored in the gitignored `.dev.vars`, including the
optional `TRANSIT_CONFIG` that drives the Transit screen. Link or re-link accounts at
`<worker>/connect?admin=<ADMIN_TOKEN>`.

## Pending (needs the owner)
1. Re-link Spotify on `/connect` so playlists load (new scopes).
2. Re-link Google on `/connect` to grant `calendar.events`, which turns on
   event editing in the calendar screen (reads work with the old link).
   Publish the Google OAuth consent screen so the refresh token stops expiring
   after 7 days.
3. Major Key: set `MAJOR_KEY_URL` + `MAJOR_KEY_KEY`; it must answer
   `GET /focus` with `{ "focus": "..." }`.
4. Home Assistant: expose HA via a Cloudflare Tunnel, set `HA_URL` + `HA_TOKEN`
   secrets and the `HA_*` vars in `wrangler.jsonc`.
5. Optional: request a OneBusAway Puget Sound API key and set it as the
   `OBA_API_KEY` secret so the Transit screen stops sharing the throttled test key.

## Roadmap after that
1. Timer alerts from any screen: an `AlarmManager` wake-up plus a notification,
   so a finished Pomodoro is heard even from the calendar or transit screens.
   The dashboard and the timer screen already ring.
2. Presence (wake on approach), push-to-talk voice.
3. Make Room OS the default launcher (last).

Transit implementation notes (for later changes): terminal ids come from WSDOT's
`terminals/rest/terminalbasics`, and OneBusAway stop ids are the tail of a stop's
page URL on `pugetsound.onebusaway.org`. Both go into `TRANSIT_CONFIG`; nothing
location-specific belongs in `src/transit.ts`. WSF is also in OneBusAway as agency
95 if a GTFS view is ever needed.

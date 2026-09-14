# Portal Room OS

A first-generation Meta Portal (2018, 10", codename `aloha`, Android 9 / API 28,
arm64, no Google services, locked bootloader) revived as a wall dashboard that is
also a full Spotify controller and its own Spotify Connect speaker.

The Portal is a thin terminal. A Cloudflare Worker ("Room Brain") holds every
integration, secret, and decision. Specs: `docs/PRODUCT_SPEC.md`, `docs/ARCHITECTURE.md`.

## What works today
- Dashboard (`MainActivity`): clock, weather, daily focus, next calendar event,
  home line, now-playing, and Talk / Lights / Music / YouTube / Calendar /
  Meetings / Transit / Timer / Add buttons. A running Pomodoro shows as a card at the top of the right
  column, with its ring, phase, remaining time and session name, sized to read
  from across the room; tap it to open the timer screen. The card is only there
  when a timer is going, so an idle dashboard stays clean. The Portal button hands
  back to the stock launcher for the device's own features; see Getting to the
  dashboard.
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

  Which terminals, stops and routes it shows comes from the Worker's
  `TRANSIT_CONFIG` value, so the same screen serves any route: even the title
  and each stop's label arrive with the data. Unset, the screen says it is not
  configured. The shape is documented in `room-brain-worker/.dev.vars.example`.
- Timer (`TimerActivity`, Timer button): a Pomodoro focus timer and a stopwatch
  behind one mode switch, sharing a hand-drawn radial dial (`TimerProgressView`,
  a plain `View` with an `onDraw` arc). The Pomodoro runs 25/5, 50/10 or a
  custom interval: tap the third INTERVAL button to open an editor with big
  steppers (focus 5 to 180 by 5, break 1 to 60 by 1), and confirming selects it.
  It rolls focus straight into its break, and keeps a tap-once distraction
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
  (`SingingBowl.VOLUME`, 0.5, about 6 dB down) so a struck bell sits under music
  rather than on top of it. Raise that toward 1.0 for a louder bell; the preview
  folds the same figure in, so it stays honest.

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

- YouTube (`YouTubeActivity`, YouTube button, or a cast from a phone): the
  Portal is a cast target in the YouTube app, listed with the TVs on the same
  Wi-Fi. `DialServer` answers the app's DIAL search, and a launch opens
  YouTube's own TV site (youtube.com/tv, the one smart TVs run) in a WebView
  wearing a TV user agent; the site does the pairing and the playback. The
  screen sits behind the dashboard between casts and steps forward when the
  phone starts something; Spotify on this speaker pauses when it does. Two
  minutes with nothing playing and it hands back to the dashboard. See
  YouTube in Gotchas for how it hangs together and what to check.
- Meetings (`MeetingsActivity`, Meetings button): your Granola meetings on the
  wall. A list down the left (search by title), and on the right the open
  meeting's notes, its transcript, or a chat about it. Questions are answered
  by Granola's own AI, the one the Granola plan already pays for: across every
  meeting when none is open ("what did we decide about pricing last week?"),
  pinned to the open meeting otherwise, with the earlier turns folded in so
  follow-ups work. Granola is linked on the Worker's `/connect` page (nothing
  to configure: the Worker registers itself with Granola's OAuth server on the
  first link). Optional: set `ANTHROPIC_API_KEY` on the Worker and Claude takes
  the per-meeting questions instead, reading the whole transcript; Granola
  exposes no model choice, so that key is the only place a model gets picked.
  Read-only: Granola's server offers no way to record into it or push a
  transcript in, which is what the Record chip is for. See Granola in Gotchas.
- Record (`RecorderService`, the ● Record chip in the dashboard header and in
  the Meetings toolbar; `RecordChip` is the shared face): the Portal's own
  microphones, one tap to start and the same tap to stop, with the running time
  on the chip and a small live waveform beside it (`LevelMeterView`) that
  lights up when a voice is heard and sits as a thin muted line when the room
  is quiet, so it is obvious the mics are listening. Started on one screen, it
  can be stopped from the other. On the Meetings screen a finished recording is
  fetched and opened the moment it is saved. Audio goes up to the Worker in three-minute WAV
  chunks while it records and Whisper (Workers AI) transcribes each on arrival,
  so the transcript is all but done by the time you stop; the Worker then
  names it and writes a short summary, and it sits in the Meetings list marked
  "Recorded here", with Notes, Transcript and Chat like any other. Audio is
  never kept, only text. A call is captured as far as the room hears it:
  speakerphone yes, earpiece no. Needs the mic granted once over adb; see
  Gotchas.
- On-device audio (`LibrespotService`): a bundled Rust librespot 0.8.0 binary
  advertises the Portal as Connect device **"Room OS"** and plays through the
  Portal's speaker. Verified underrun-free with a 1 s AudioTrack buffer.
  Credentials persist in the app's files dir, so it logs back in on every
  start and "Room OS" stays in the Spotify device list. `BootReceiver` starts the
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
  src/main/java/com/portalroomos/DialServer.java      YouTube cast target (SSDP + DIAL over HTTP)
  src/main/java/com/portalroomos/YouTubeActivity.java the TV site in a WebView
  src/main/java/com/portalroomos/MeetingsActivity.java Granola meetings, notes, transcripts, chat
  src/main/java/com/portalroomos/RecorderService.java  the mic, in chunks, to the Worker
  src/main/java/com/portalroomos/RecordChip.java       the Record chip, shared by both screens
  src/main/java/com/portalroomos/LevelMeterView.java   the waveform beside the chip
  src/main/jniLibs/arm64-v8a/liblibrespot.so   librespot binary (stripped, ~13 MB)
  src/main/res/drawable-nodpi/portal_background.png   wall photo, 1280x800
tools/contrast_check.py WCAG AAA gate for the palette over that photo.
room-brain-worker/      Cloudflare Worker (TypeScript). See its README for endpoints.
docs/                   Product spec and architecture.
```

## Start here
Roughly two evenings from a boxed Portal to the dashboard on the wall.

You need a first-generation Portal (10", 2018, codename `aloha`), a Mac or Linux
box with `adb`, JDK 17 and the Android command line tools, Node and npm for the
Worker, a Cloudflare account (the free tier is enough), and **Spotify Premium**:
Connect refuses to hand a speaker to a free account, so the audio half simply
will not work without it. Google Calendar and Home Assistant are optional.

Do it in this order. The app is useless before the Worker exists, because it
needs the Worker's URL and token at build time.

1. **The Portal.** Complete Facebook's first-run setup, then enable ADB under
   Settings → Debug. See Device access. Some networks refuse account setup; if
   you hit that, try another Wi-Fi.
2. **The Worker.** `room-brain-worker/README.md`, First deploy. `APP_TOKEN` and
   `ADMIN_TOKEN` are not issued by anyone: invent two long random strings, they
   are just shared secrets between you, the Worker and the app. Note the Worker
   URL it prints.
3. **Link the accounts** at `<worker>/connect?admin=<ADMIN_TOKEN>`, after adding
   the redirect URIs to your Spotify and Google apps. Publish the Google consent
   screen or the calendar stops loading weekly; see Gotchas.
4. **Build and install the app** with the Worker URL and `APP_TOKEN` in
   `local.properties`, below.
5. **Grant the overlay** so the OS chip can float over the stock launcher, and
   sign the speaker in for the first time. Both are in Getting to the dashboard
   and Gotchas respectively.
6. **Optional:** `TRANSIT_CONFIG` for the transit board, Home Assistant for the
   home line and the Lights button, Major Key for the focus line. See Optional
   integrations.

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

`local.properties` holds the SDK path, your Worker's URL, and the app's bearer
token, the same value as `APP_TOKEN` on the Worker:
```
sdk.dir=/opt/homebrew/share/android-commandlinetools
roomBrain.url=https://portal-room-brain.<subdomain>.workers.dev
roomBrain.appToken=<APP_TOKEN>
```
Both reach `Config.java` as `BuildConfig` fields.

Watch the speaker: `adb -s $PORTAL logcat -s Librespot`. While audio
flows it logs a line every 30 s with seconds streamed and the underrun delta
(should stay `+0`).

### Loudness
Three things set how loud the Portal is, in order:
1. The Portal's own hardware volume control. The app never touches it. An earlier
   version pinned Android's media stream to maximum whenever audio started, which
   made the on-screen indicator and the physical control jump to full on every
   sound and silently overrode whatever the room was set to. Don't reintroduce
   that: this is the control the person in the room reaches for.
2. The Spotify app's volume slider for "Room OS" (librespot softvol, cubic curve),
   riding underneath. A fresh install starts at `DEFAULT_VOLUME_PCT` (100), but
   librespot caches the last volume in `files/librespot/volume` and the cache wins
   on every later start, so what you set in Spotify sticks. Delete that file to
   get the default back. The timer's bell is separate, at `SingingBowl.VOLUME`
   (0.5).
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
- The Portal must be on the same Wi-Fi as the phones that should see "Room OS",
  in Spotify and in YouTube alike. The Worker is public, so the dashboard works
  from any network.
- YouTube discovery is SSDP on UDP 1900 plus HTTP on TCP 8091 (`DialServer.PORT`),
  both on the Portal itself. A router that blocks multicast between clients
  hides the Portal from the cast picker; the "Link with TV code" route below
  still works then.
- Account setup only completed on a non-home Wi-Fi (Facebook "unknown error (1)"
  and WhatsApp "can't link" on the home network).

## Getting to the dashboard
The Portal's launcher lists only Facebook's apps (Contacts, App Store, Browser,
Settings, Help). It does not enumerate installed ones, so a sideloaded app cannot
put an icon on that grid or add a tab beside Home and Apps however it declares
itself, and the device has no home key, notification shade or recents switcher to
reach it another way. Two things close the gap, and they are a pair:

- **Out:** the dashboard's **Portal** button starts the stock launcher. It
  resolves every home activity except our own rather than naming a package, so it
  is not tied to one launcher, and hides itself if there is no other home.
- **Back in:** a small **OS** chip floats over whatever is on screen, top right,
  clear of the launcher's account avatar. Tapping it brings the dashboard
  forward. It stands down while one of our own screens is already showing, which
  `RoomOsApp` tracks through activity lifecycle callbacks.

A system overlay is the only mechanism Android gives a third-party app to put a
control on someone else's screen. It needs `SYSTEM_ALERT_WINDOW`, which a
sideloaded app cannot usefully prompt for here, so grant it once per device:

```bash
adb -s $PORTAL shell appops set com.portalroomos SYSTEM_ALERT_WINDOW allow
```

Without the grant the chip quietly stays hidden rather than crashing; look for
`no draw-over-other-apps permission` under the `RoomOsOverlay` log tag.
`BootReceiver` also starts the dashboard, not just the speaker, so an unplugged
panel comes back showing the room.

Room OS deliberately does **not** register `category.HOME`. It was tried and it
backfires: with two home apps the Home key starts asking which one to use, and,
worse, the Portal button stops working. Starting the stock launcher moves the
home task, the system then re-launches whatever it resolves as home, and with
Room OS in the running that is Room OS, so the screen bounces straight back. The
`ActivityManager` log shows it plainly, a `START` for the launcher followed
within a second by a `START ... cat=[android.intent.category.HOME]` for us. Being
an ordinary app and floating a chip over the launcher is the arrangement that
works. If the home role ever ends up in the wrong place:

```bash
adb -s $PORTAL shell cmd package set-home-activity <package>/<activity>
```

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
First deploy: `wrangler kv namespace create ROOM_KV` prints a namespace id.
Copy `wrangler.jsonc` to `wrangler.local.jsonc`, put the id there, and deploy
with `--config` as above. Secrets are set with `wrangler secret put` and
mirrored in `.dev.vars` for local development, including the optional
`TRANSIT_CONFIG` that drives the Transit screen. Link or re-link accounts at
`<worker>/connect?admin=<ADMIN_TOKEN>`.

## Gotchas

Things that cost time here, so they do not cost you any.

**The device**
- The panel is 1280x800 landscape at 160 dpi, so `1dp = 1px = 1sp` and the screen
  is 1280dp wide. That is an unusually large dp canvas: text and hit targets sized
  for a phone look tiny on a wall. The dashboard clock is 48sp and the timer's
  countdown 96sp for a reason.
- `adb shell am start` cannot open the non-launcher screens: they are
  `exported="false"`, so the shell (uid 2000) gets `Permission Denial ... not
  exported`. Drive them by tapping, or export one temporarily while debugging.
- The Portal's shell does 32-bit arithmetic. `echo $(($(date +%s) * 1000))`
  silently returns a negative number, which quietly corrupts any test that pokes
  epoch-millisecond values into the app. Compute those on the host.
- Wi-Fi ADB drops on every reboot and the on-device installer dialog is unusable
  (white on white). Re-arm over USB, and always `adb install`.

**Changing the app id**
- It gives the app a new data directory. The librespot credential cache goes with
  it, so "Room OS" stops appearing in Spotify until you pick it once from a client
  on the same Wi-Fi, and the timer's session history and distraction log start
  empty. Uninstall the old package afterwards or two builds will both advertise
  themselves as "Room OS".

**Spotify Connect, and the credential blob it all hangs on**
- Zeroconf login cannot work on this device. Android's hostname is `localhost`,
  libmdns advertises exactly that, so every Spotify client that discovers "Room OS"
  resolves it to itself and gives up. `setprop net.hostname` is blocked by SELinux,
  so there is no fix without root. Confirm it with
  `dns-sd -L "Room OS" _spotify-connect._tcp local.`: a working device answers with a
  real hostname, this one answers `localhost.local.`.
- librespot 0.8.0's `--enable-oauth` fallback is also dead. Its built-in client
  redirects to `http://127.0.0.1:5588/login`, which Spotify now rejects with
  "redirect_uri: Not matching configuration", and this build has no `--client-id`
  to point at your own app.
- So everything rests on the cached `files/librespot/credentials.json`. With it,
  librespot authenticates outbound and "Room OS" appears account-wide, no discovery
  involved. Without it there is no way in on the device itself. Anything that
  clears app data takes it, including changing the app id.
- Recovery. The blob is bound to the account, not the machine, so borrow a host
  whose hostname does resolve:
  ```bash
  librespot -n "Portal Setup" -B pipe --system-cache /tmp/lscache --cache /tmp/lscache
  ```
  Pick "Portal Setup" in Spotify on the same network, which writes
  `/tmp/lscache/credentials.json`, then move it across and restart the app:
  ```bash
  adb -s $PORTAL shell "run-as com.portalroomos sh -c 'cat > files/librespot/credentials.json'" < /tmp/lscache/credentials.json
  ```
  `adb logcat -s Librespot` then shows `Authenticated as`. Use the same librespot
  version at both ends.

**YouTube**
- Nothing to configure. The app answers a phone's DIAL search as soon as the
  process is up (`RoomOsApp` starts `DialServer`; the speaker service keeps the
  process alive from boot), and `BootReceiver` loads the YouTube page under the
  dashboard so the first cast lands on a warm page. The site only registers
  with YouTube's lounge (the phone-to-screen channel) once a cast has launched
  it; until then the phone reaches it through DIAL, which is up from boot.
- A cast the phone starts by launch reloads the page with its pairing code, so
  the site may show its "who's watching" account picker before the video if
  it cannot tell whose cast it is. If that happens on real casts, tap the
  account once; taps work on the leanback UI.
- Watch a phone find it: `adb -s $PORTAL logcat -s Dial YouTube`. A healthy
  sequence is `M-SEARCH from <phone>`, `GET /dd.xml`, `GET /apps/YouTube -> 200`,
  then on a cast `POST /apps/YouTube -> 201`, `launch: pairingCode=...`, the
  site posting `dial_data` (its screen id) and finally `video playing`. Probe it
  by hand from the Mac: `curl -i http://<portal-ip>:8091/dd.xml` and
  `curl http://<portal-ip>:8091/apps/YouTube`.
- The site is the real YouTube TV client, so the first phone to attach signs
  the screen into its account (the site reloads once with
  `is_account_switch=1`), and a new remote identity attaching makes it reload
  again, which drops whatever was playing. One household account attaching
  each time is the steady state.
- Fallback, and the way to be listed from outside the house: on the Portal,
  open YouTube, Settings (bottom of the left rail), "Link with TV code", and
  enter the code in the phone's YouTube app under Settings, Watch on TV. That
  link is account-level and survives networks and reboots.
- The site keys the TV experience on the user agent (`YouTubeActivity.TV_USER_AGENT`,
  a Samsung Tizen string). It may show a dismissable "this device no longer
  fully supports YouTube" banner; that is cosmetic. If Google ever stops
  serving the TV site to that string, changing it is the first thing to try.
- The page must never learn it is behind the dashboard. The injected bridge
  pins `document.hidden` to false and swallows `visibilitychange`, because on a
  hidden document the site unloads its player and a phone's next play lands on
  nothing. Likewise the activity never calls `WebView.onPause()`: that stops the
  page's timers and with them the lounge connection the phone is holding.
- Only unmuted playback pulls the screen forward. The leanback home autoplays
  silent previews under the focused thumbnail; those are reported as muted and
  ignored, or the dashboard would flip to YouTube on its own.
- `additionalDataUrl` has to point at localhost. The site refuses to post its
  screen id to any other host, which is fine: the page and `DialServer` share
  the device.
- The WebView is debuggable in debug builds: `adb forward tcp:9222
  localabstract:webview_devtools_remote_$(adb shell pidof com.portalroomos)`
  then `chrome://inspect` on the Mac, or `curl localhost:9222/json`.

**Drawing over the photo**
- Never carry state with `View.setAlpha()`. Fading a whole control composites its
  label toward the photograph and drops the contrast below AAA. Say it in colour
  instead: `@color/accent` selected, `@color/muted` not. The calendar's mode
  buttons made exactly this mistake.
- New text has to sit on one of the defined surfaces (`panel_bg`, `bar_bg`,
  `chip_bg`, `button_bg`) and be added to `ELEMENTS` in
  `tools/contrast_check.py`. Text placed straight on the photo cannot be made
  AAA, because the sky is nearly white.
- `AlertDialog` inherits `Theme.Material.Light`, so a dialog arrives white unless
  you give it a background and colour the buttons the platform builds. Dialogs and
  the soft keyboard also bring the system bars back over an immersive activity;
  the timer's dialogs call `hideSystemUi()` on dismiss.

**The timer**
- The Pomodoro is pinned to a wall-clock instant rather than counted in ticks, and
  only the screen in front advances it. Both matter: counting drifts and dies when
  the screen goes away, and if two screens ticked they would cross the same
  boundary and credit the session twice. Anything new that reads the timer should
  go through `TimerState`, not keep its own copy.
- The bell rides the media stream at `SingingBowl.VOLUME` (0.5) so it sits under
  music rather than on top of it. Loudness overall is the room's hardware control;
  the app deliberately does not set the system volume.
- Nothing runs in the background. A phase that ends while the calendar, transit or
  music screen is up is picked up silently on return; only the dashboard and the
  timer screen ring.

**The Worker**
- Google's refresh token expires after seven days while the OAuth consent screen
  is still in testing. Publish it, or the calendar quietly stops loading each week.
- OneBusAway's shared `TEST` key throttles bursts, so an occasional "live data
  unavailable" line on the transit screen is normal until you set `OBA_API_KEY`.
- `TRANSIT_CONFIG` is not optional if you want the transit screen: with none set,
  `/api/transit` answers `configured: false` and the screen says so.
- `wrangler.jsonc` has no KV namespace id filled in, so a plain
  `wrangler deploy` binds nothing useful. See the Worker section above.

### Granola
- Granola's hosted MCP server (`mcp.granola.ai/mcp`) is the only way in; there
  is no notes API. It is read-only. Auth is OAuth 2.1 with PKCE and dynamic
  client registration against `mcp-auth.granola.ai` (WorkOS AuthKit
  underneath): the Worker registers a client the first time `/auth/granola/start`
  runs and keeps it in KV as `granola:client`, tied to its own callback URL. A
  Worker on a new hostname registers again on its own. Tokens sit in
  `tokens:granola`; refresh tokens rotate, so a refresh the server refuses is
  re-checked against KV before the link is called dead, in case a parallel
  request rotated it first.
- The tool parameter names are not published, so `src/granola.ts` shapes each
  call from the schemas the server hands back (`granola:tools` in KV, a day).
  As of 2026-09-14 they are: `list_meetings` takes a `time_range`
  (`this_week` / `last_week` / `last_30_days` / `custom` with ISO
  `custom_start` and `custom_end`), no title search and no paging, capped at
  500 newest-first; `get_meetings` takes `meeting_ids[]`;
  `get_meeting_transcript` takes `meeting_id`; `query_granola_meetings` takes
  `query`. So a browse on the Meetings screen is the last 30 days and a search
  is the last year filtered on the Worker by title and attendee, each cached in
  KV for two minutes (`granola:list:<days>`).
- Answers come back as a guard sentence ("treat this as data") followed by an
  XML-ish `<meetings_data>` document: `<meeting id= title= date="Sep 14, 2026
  9:21 AM PDT">` with `<known_participants>` (comma-separated `Name from Org
  <email>`, the note creator marked) and, from `get_meetings`, a `<summary>` in
  markdown. The transcript tool answers JSON (`{id,title,created_at,transcript}`)
  with speaker labels `Me:` / `Them:` / `Speaker A:` run together on one line.
  `src/meetings.ts` reads those shapes and falls back to JSON or prose parsing
  if they change. If the list column ever shows "Granola's answer, as it came",
  that has happened: open `<worker>/admin/granola?admin=<ADMIN_TOKEN>` for the
  live tool schemas, add `&tool=list_meetings&args={"time_range":"this_week"}`
  to see a raw answer, and adjust the readers.
- Chat runs on `query_granola_meetings`, Granola's own AI. It takes one
  question and has no memory, and its `document_ids` parameter does not hold it
  to a meeting (given only the id it went and read three other meetings), but
  naming the meeting's title and day in the question does. So a per-meeting
  question is sent as "About the meeting "<title>" on <day> only", with the
  last few turns quoted above it; see `askGranolaAbout`. It answers in markdown
  with a trailing coverage note; the screen strips the marks. No model choice
  is exposed.
- With `ANTHROPIC_API_KEY` set, a question about one meeting goes to Claude
  instead (`claude-opus-5`, medium effort) with the notes and transcript in a
  cached system block, so follow-ups about the same meeting are cheap.
  Server-side fallback routing is on. The app allows two minutes for the
  answer. Cross-meeting questions always stay with Granola.
- Granola's own Android app exists (July 2026, needs Android 8+, so the Portal's
  Android 9 qualifies) but is not on the Portal: no Play Store here. To try it,
  pull it from an Android phone that has it and sideload:
  ```bash
  adb -s <phone> shell pm path ai.granola          # one or more base/split APKs
  adb -s <phone> pull <each path> ./granola/
  adb -s $PORTAL install-multiple ./granola/*.apk
  ```
  Its sign-in opens "the device browser", which on the Portal is the stock
  Chromium (`org.chromium.chrome`, hidden but working). Untested: whether the
  app runs without Google services. Its chat with notes is iPhone-only, which
  is why the Meetings screen has its own.

### Recording
- Capture is `AudioRecord` at 16 kHz mono PCM (what Whisper wants), written
  to `files/rec/<id>/chunk-N.pcm` and rolled every `CHUNK_SECONDS` (180 s,
  5.8 MB a chunk). An uploader thread wraps each in a WAV header and PUTs it
  to `/api/recordings/<id>/chunk?seq=N`; the Worker answers only after Whisper
  has the text, so a 200 means the chunk is safe to delete. Failures retry with
  a growing pause, up to twelve times; a chunk that never makes it is left on
  disk and the transcript has a hole there. Stopping enqueues the last chunk
  and then `finish`, which stitches, names and summarises.
- The id is minted on the device (`rec_<millis in base 36>`), so recording
  starts before any network. The Worker creates the record on the first chunk.
- Transcription is `@cf/openai/whisper-large-v3-turbo`, given the previous
  chunk's last 200 characters as `initial_prompt` so names stay spelled the same
  across a boundary. Naming and per-recording chat use
  `@cf/meta/llama-3.3-70b-instruct-fp8-fast` (JSON mode for the name) unless
  `ANTHROPIC_API_KEY` is set, in which case Claude does both. Llama's context
  is short, so a very long transcript is trimmed to its last 60k characters for
  a question and the answer says so.
- Only the transcript is stored (KV: `rec:<id>`, `rec:<id>:seg:N`,
  `rec:<id>:transcript`, `rec:index`). `DELETE /api/recordings/<id>` with the
  app token removes one; there is no delete on the screen yet.
- The mic hears the whole room. A test from a session picked up a live
  conversation happening next to the panel; treat the chip as a real recorder.
- The level meter maps RMS per 50 ms onto -60 to -12 dBFS. Measured on the
  Portal: a quiet room sits near -55, a voice a metre away runs -36 to -17, so
  the bars light above about -48 (`LevelMeterView.HEARD`). If the meter glows
  on an empty room, raise that; if speech stays grey, lower it.

## Optional integrations
The dashboard, calendar, music, transit, timer and YouTube casting all work
with just Spotify and Google linked (YouTube needs nothing linked at all).
These fill in the rest:

1. Spotify and Google are linked at `<worker>/connect?admin=<ADMIN_TOKEN>`.
   Google needs the `calendar.events` scope for event editing; reads work
   without it. Publish the consent screen (see Gotchas).
2. Transit: set `TRANSIT_CONFIG`. Shape and an example in `.dev.vars.example`.
3. Daily focus line: set `MAJOR_KEY_URL` + `MAJOR_KEY_KEY` to any service that
   answers `GET /focus` with `{ "focus": "..." }`. Without it the dashboard shows
   a standing prompt.
4. Home line and the Lights button: expose Home Assistant to the Worker (a
   Cloudflare Tunnel works), set the `HA_URL` + `HA_TOKEN` secrets and the `HA_*`
   vars in `wrangler.jsonc`.
5. Transit accuracy: request a OneBusAway Puget Sound API key and set it as
   `OBA_API_KEY` so the screen stops sharing the throttled test key.
6. Meetings: link Granola on `/connect` (any Granola plan; the free plan only
   exposes the last 30 days and no transcripts or search). That is all; the
   chat runs on Granola's AI. `wrangler secret put ANTHROPIC_API_KEY` only if
   you want Claude answering questions about the open meeting, and naming and
   answering questions about Portal recordings (Workers AI does both otherwise).
7. Recording: nothing to link. Workers AI is bound in `wrangler.jsonc` (`ai`),
   billed to the same Cloudflare account: Whisper is about $0.0005 per audio
   minute. Grant the mic once: `adb shell pm grant com.portalroomos
   android.permission.RECORD_AUDIO` (the on-device permission dialog is as
   unreadable as the installer's).

## Roadmap after that
1. Timer alerts from any screen: an `AlarmManager` wake-up plus a notification,
   so a finished Pomodoro is heard even from the calendar or transit screens.
   The dashboard and the timer screen already ring.
2. Presence (wake on approach), push-to-talk voice.
3. Recordings: a Delete button on the Meetings screen (the Worker route
   exists), and resuming uploads left on disk if the process died mid-recording.

Transit implementation notes (for later changes): terminal ids come from WSDOT's
`terminals/rest/terminalbasics`, and OneBusAway stop ids are the tail of a stop's
page URL on `pugetsound.onebusaway.org`. Both go into `TRANSIT_CONFIG`; nothing
location-specific belongs in `src/transit.ts`. WSF is also in OneBusAway as agency
95 if a GTFS view is ever needed.

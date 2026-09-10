# Portal Room OS — Product Spec v0.1

> **Status.** This is the design target, not a description of what exists. Much
> of it is still unbuilt. For what actually runs today, see "What works today"
> in `../README.md`.
>
> Built: the ambient dashboard, the Spotify controller and an on-device Spotify
> Connect speaker, the calendar screens, the transit board, and a Pomodoro timer
> and stopwatch. Everything sits on a photograph under a WCAG AAA contrast
> contract that `../tools/contrast_check.py` enforces.
>
> Not built: presence and approach detection, the sleep/ambient/active state
> machine, voice of any kind, reminders and the grocery list, and AI routing. The
> Talk, Lights and Add buttons post actions to the Room Brain, which answers only
> as far as its integrations are configured.

## Product thesis
Turn a discontinued Meta Portal into a locally intelligent room interface: an ambient, glanceable home surface that wakes when someone approaches, accepts touch/voice commands, controls the home, shows what matters next, and delegates heavy AI reasoning to a separate Room Brain.

## Principles
1. Ambient first; app second.
2. Local presence, not surveillance.
3. One glance should answer: what matters now, what happens next, and what needs attention?
4. Portal handles eyes/ears/display; Room Brain handles integrations and reasoning.
5. No Google Mobile Services dependencies on the Portal.
6. Every cloud feature should fail gracefully to a useful local display.
7. Camera/mic state must always be obvious.

## Required experiences
### Ambient / idle
- Clock and date
- Weather
- Rotating family photos or art (phase 2)
- Today's calendar / next event
- Daily focus (“What deserves your focus right now?”)
- Upcoming reminder
- Now playing
- Minimal home status

### Approach / presence
- Wake or brighten display when a person approaches
- Transition from ambient to interactive layout
- Do not identify faces in v1
- Local-only occupancy event by default

### Active touch
- Lights
- Thermostat
- Locks/status
- Music
- Cameras/doorbell shortcut
- Add reminder
- Add grocery/item
- “What’s next?”
- “Focus today”
- Timer: a Pomodoro with named sessions and a distraction log, and a stopwatch
- Transit: live ferries both directions (plus an optional passenger water taxi)
  and real-time bus arrivals for the configured stops, glanceable before leaving
  the house

### Voice
Phase 1: push-to-talk.
Phase 2: local wake word + streamed speech to Room Brain.
Phase 3: conversational follow-up with visible transcript and cancel control.

Example intents:
- What's on my calendar?
- Turn downstairs lights off.
- Play my morning playlist.
- What's my focus today?
- Add milk to groceries.

### AI
- LLM does not run on Portal in v1.
- Portal sends structured context or audio to Room Brain.
- Room Brain decides whether request is local home control, information retrieval, or AI reasoning.
- High-risk home actions (unlock door, garage open, security changes) require explicit confirmation.

## States
1. SLEEP — display off/dim.
2. AMBIENT — clock/photo/focus/next event.
3. APPROACH — presence detected; display wakes/brightens.
4. ACTIVE — touch controls and richer cards.
5. LISTENING — explicit mic indicator.
6. RESPONDING — transcript/result shown.
7. RETURN — after inactivity, fall back to ambient.

## Room Brain responsibilities
- Home Assistant integration
- Calendar integration
- Weather
- Music integration
- Transit (WSDOT Ferries + OneBusAway), cached so the Portal never calls them directly
- The Major Key / daily focus
- Reminders and grocery list
- AI model routing
- Authentication and secrets
- User/room profile configuration

## Portal responsibilities
- Fullscreen/kiosk UI
- Clock and cached last-known state
- Touch
- Speaker output
- Mic capture
- Camera/presence sensing where supported
- Ambient light/other sensors where supported
- Local state transitions

## Privacy requirements
- No face recognition by default.
- Presence is a boolean occupancy signal unless explicitly upgraded.
- Never store raw camera frames for presence detection.
- Voice capture begins only after push-to-talk in v1.
- Persistent visual mic/camera indicators whenever active.
- Physical privacy controls remain usable.
- The Room Brain is a public Cloudflare Worker, not a LAN service: it holds the
  integration credentials so the Portal never does, and every `/api` call carries
  a bearer token. The Portal accepts no inbound connections. An earlier draft of
  this spec assumed a LAN-only brain; the hosted Worker was chosen so the
  dashboard keeps working from any network and no port is opened at home.

## Resilience
- If Room Brain is offline, clock/date and cached content remain usable.
- Smart-home controls visibly disable if unavailable.
- Never represent stale lock/security state as current.

## Success criteria for v1
- Room OS is reachable in one tap from the Portal's own UI. (Making it the
  launcher was tried and rejected: registering as a home app breaks the button
  that returns to the stock UI. See Getting to the dashboard in the README.)
- UI runs smoothly on API 28/29.
- Room Brain state renders on the Portal, authenticated.
- At least one Home Assistant entity can be toggled.
- Daily focus + next calendar event render.
- Presence can switch Ambient ↔ Active on at least one supported method.
- Push-to-talk audio capture works end-to-end.

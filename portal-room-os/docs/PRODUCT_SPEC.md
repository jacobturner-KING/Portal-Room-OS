# Portal Room OS — Product Spec v0.1

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
- Prefer LAN-only Room Brain communication.

## Resilience
- If Room Brain is offline, clock/date and cached content remain usable.
- Smart-home controls visibly disable if unavailable.
- Never represent stale lock/security state as current.

## Success criteria for v1
- Portal launches directly into Room OS.
- UI runs smoothly on API 28/29.
- Room Brain state appears over LAN.
- At least one Home Assistant entity can be toggled.
- Daily focus + next calendar event render.
- Presence can switch Ambient ↔ Active on at least one supported method.
- Push-to-talk audio capture works end-to-end.

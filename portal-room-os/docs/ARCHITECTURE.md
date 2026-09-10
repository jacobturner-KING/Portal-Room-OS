# Architecture

> Presence adapters and the voice path below are design, not code. What exists is
> the Portal, the Worker, and the HTTPS between them.

```text
┌──────────────────────── META PORTAL ────────────────────────┐
│                                                            │
│  Camera ─┐                                                 │
│  Mic ────┼──> Sensor / Voice adapters ──┐                  │
│  Touch ──┘                              │                  │
│                                         v                  │
│                               Room OS state machine         │
│                          Sleep / Ambient / Active / Voice   │
│                                         │                  │
│                          Fullscreen native Android UI       │
│                                         │                  │
└─────────────────────────────────────────┼──────────────────┘
                                          │ HTTPS + bearer token
                                          v
┌──────────────────────── ROOM BRAIN ─────────────────────────┐
│ Context aggregator + policy + action router                 │
│                                                            │
│ Home Assistant   Calendar   Weather   Music   Major Key     │
│        │              │         │        │        │          │
│        └──────────────┴─────────┴────────┴────────┘          │
│                             │                              │
│                        AI model(s)                          │
└────────────────────────────────────────────────────────────┘
```

## Why this split
The Portal is old Android hardware and has no GMS. It should be treated as a durable room terminal, not the source of truth. All fast-changing integrations, credentials, and AI logic live outside the device.

## Interfaces
### GET /api/state
Returns the room's glanceable state.

### POST /api/action
Portal sends a structured action. Room Brain authenticates, applies safety rules, invokes the integration, and returns a human-readable result.

`/api/transit`, `/api/calendar/*` and the `/connect` linking page round it out;
`room-brain-worker/README.md` is the authoritative list.

### Future /api/voice
Portal uploads a short audio segment. Room Brain transcribes, routes intent, executes safe actions, and returns text + optional speech audio.

## Presence adapters
Use an interface so hardware can be swapped without changing UI:
1. Portal HA Bridge presence sensor over MQTT (fastest likely path).
2. Native Camera2 person detector.
3. Home Assistant room occupancy sensor.
4. PIR/mmWave external sensor.

## Security
- Put Portal on an IoT VLAN where practical.
- The Room Brain is a public Cloudflare Worker rather than a LAN service, so
  every `/api` route is gated on a bearer token and the admin linking page on a
  second one. That choice keeps the credentials off the device and opens no port
  at home, at the cost of the endpoint being internet-reachable.
- Secrets remain on Room Brain.
- Lock/unlock/open actions require confirmation and freshness checks.

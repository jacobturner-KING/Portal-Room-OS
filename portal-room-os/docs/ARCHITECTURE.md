# Architecture

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
                                          │ LAN HTTPS / WS
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
- Room Brain should accept only LAN traffic or mutual-authenticated requests.
- Secrets remain on Room Brain.
- Lock/unlock/open actions require confirmation and freshness checks.

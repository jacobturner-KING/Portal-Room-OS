# Portal Room Brain (Cloudflare Worker)

Always-on Room Brain for Portal Room OS. Aggregates weather, calendar, music, and
the daily focus into one `GET /api/state`, and relays button presses through
`POST /api/action`. Integrations and OAuth tokens live here; the Portal never sees
a credential.

## Endpoints

| Route | Auth | Purpose |
|-------|------|---------|
| `GET /health` | none | Liveness check |
| `GET /api/state` | `Authorization: Bearer <APP_TOKEN>` | Glanceable room state for the Portal |
| `POST /api/action` | `Authorization: Bearer <APP_TOKEN>` | `{ "action": "toggle_music" \| "music_next" \| "toggle_lights" \| "voice" \| "add_item" }` |
| `GET /api/transit` | bearer | Ferries both ways (live status, drive-up space, alerts), optional water taxi, live bus arrivals, all from `TRANSIT_CONFIG`; cached 40 s |
| `GET /api/calendar/calendars` | bearer | Calendars shown in Google's UI, plus `canEdit` |
| `GET /api/calendar/events?start=&end=` | bearer | Events across those calendars in an RFC3339 range |
| `GET /api/calendar/event?calendarId=&id=` | bearer | One event |
| `POST /api/calendar/event/update` | bearer | `{calendarId,id,title?,location?,description?,allDay?,start?,end?}` (needs the edit scope) |
| `GET /api/calendar/days?start=&end=&tz=` | bearer | Event counts per local day (year view) |
| `GET /api/meetings?search=&limit=` | bearer | Granola meetings and Portal recordings in one list, newest first (`source` says which); `granola: false` when unlinked |
| `GET /api/meetings/notes?id=` | bearer | One meeting's notes, as Granola wrote them |
| `GET /api/meetings/transcript?id=` | bearer | One meeting's transcript |
| `POST /api/meetings/chat` | bearer | `{meetingId?,title?,date?,messages:[{role,content}]}`; Granola's own AI answers (pinned to the meeting when one is given), or Claude for the per-meeting case when `ANTHROPIC_API_KEY` is set; `source` says which |
| `PUT /api/recordings/<id>/chunk?seq=N&startedAt=ISO` | bearer | One 16 kHz mono WAV chunk of a Portal recording; transcribed (Workers AI Whisper) before answering |
| `POST /api/recordings/<id>/finish` | bearer | `{durationSec}`; stitches the transcript, names and summarises the recording |
| `GET /api/recordings/<id>` | bearer | The recording's record |
| `DELETE /api/recordings/<id>` | bearer | Removes it and its transcript |
| `POST /api/agents/hook?agent=claude\|codex\|cursor\|generic` | `Bearer <AGENT_TOKEN>` | An agent's own hook payload, as is; machine and terminal in `X-Agent-*` headers. Returns `permissionId` + `waitSec` when a Claude Code permission prompt should wait for the panel |
| `GET /api/agents/permission?id=` | `Bearer <AGENT_TOKEN>` | A held prompt: `200` with the hook output to print, `204` still waiting, `410` gone |
| `GET /api/agents/bridge` | `AGENT_TOKEN` in the first message | WebSocket for a machine's bridge: receives `say` and `focus`, answers `result` |
| `GET /api/agents` | bearer | Every agent session: state, activity, prompt, reply, held permission, whether it can be talked to; `wallApprovals`; connected bridges |
| `POST /api/agents/say?id=` | bearer | `audio/wav` (transcribed by Whisper) or `{text}`; typed into the agent's terminal by its machine's bridge |
| `POST /api/transcribe` | bearer | `audio/wav` in, `{ok, text}` out (Whisper); the Portal uses it for agents it reaches over USB through AppTabs |
| `POST /api/agents/decide` | bearer | `{id, permissionId, allow}` |
| `POST /api/agents/focus` | bearer | `{id}`: bring the agent's terminal forward on its machine |
| `POST /api/agents/dismiss` | bearer | `{id}`: drop it from the list until it next does something |
| `POST /api/agents/settings` | bearer | `{wallApprovals}` |
| `GET /connect?admin=<ADMIN_TOKEN>` | admin | One-time page to link Spotify / Google / Granola |
| `GET /admin/granola?admin=&tool=&args=` | admin | Granola's live tool schemas, and one raw tool call for tuning the parsers |
| `GET /auth/{spotify,google,granola}/start` | admin cookie | Begins OAuth |
| `GET /auth/{spotify,google,granola}/callback` | state param | OAuth return |

## Secrets and configuration

Local dev reads `.dev.vars`. Production uses `wrangler secret put <NAME>`:

`APP_TOKEN`, `ADMIN_TOKEN`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`,
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `MAJOR_KEY_URL`, `MAJOR_KEY_KEY`,
`AGENT_TOKEN`, `HA_URL`, `HA_TOKEN` (Home Assistant base URL reachable from the Worker, e.g. via a
Cloudflare Tunnel, plus a long-lived access token), `OBA_API_KEY` (OneBusAway Puget
Sound key; the documented `TEST` key is used until it is set). `ANTHROPIC_API_KEY` (optional) to have
Claude, rather than Granola's own AI, answer the Meetings screen's questions about one
meeting. Granola needs no secret: the Worker registers itself with Granola's OAuth server
on the first link from `/connect`.

`AGENT_TOKEN` (optional) turns on the Agents screen: the hooks and bridges on your machines
send it, and it opens the `/api/agents/{hook,permission,bridge}` routes and nothing else.

Bindings: `ROOM_KV` (KV), `AI` (Workers AI, for Portal recordings and spoken agent
instructions) and `AGENT_HUB` (a SQLite-backed Durable Object, `src/agents.ts`, holding every
agent session and the bridges' hibernating sockets), all in `wrangler.jsonc`. The
`agents-v1` migration creates the Durable Object on the first deploy after it was added;
copy the `durable_objects` and `migrations` blocks into `wrangler.local.jsonc` too.

Non-secret vars (`WEATHER_LAT`, `WEATHER_LON`, `WEATHER_LABEL`) live in `wrangler.jsonc`.
Leave them empty and the Worker geolocates the Portal's own requests (Cloudflare `request.cf`,
city-level); `/api/state` reports the resolved spot in `weather_location`. Weather is cached
in KV for 10 minutes.

Home Assistant vars: `HA_SUMMARY_ENTITIES` (comma-separated entity ids shown on the
dashboard's home line) and `HA_LIGHT_ENTITY` (what the Lights button toggles via
`homeassistant.toggle`). Both stay inert until `HA_URL` + `HA_TOKEN` are set.

## First deploy

```bash
npm install
wrangler login                                   # one-time browser auth
wrangler kv namespace create ROOM_KV             # see below for where the id goes
wrangler secret put APP_TOKEN                     # repeat for each secret above
wrangler secret put TRANSIT_CONFIG                # optional; shape in .dev.vars.example
wrangler deploy
wrangler types                                    # regenerate Env after config changes
```

`wrangler.jsonc` has no KV namespace id filled in. Copy it to
`wrangler.local.jsonc`, put yours there, and deploy with
`wrangler deploy --config wrangler.local.jsonc`.

After deploy you get the Worker URL, e.g. `https://portal-room-brain.<subdomain>.workers.dev`.
Set these as the redirect URIs in each provider's dashboard:

- Spotify: `https://portal-room-brain.<subdomain>.workers.dev/auth/spotify/callback`
- Google:  `https://portal-room-brain.<subdomain>.workers.dev/auth/google/callback`

Then open `https://portal-room-brain.<subdomain>.workers.dev/connect?admin=<ADMIN_TOKEN>`
and link the accounts.

## Wire the Portal to it

The app reads both the Worker URL and the bearer token from `local.properties`
in `portal-room-os/`, and they reach `Config.java` as
`BuildConfig` fields:

```
roomBrain.url=https://portal-room-brain.<subdomain>.workers.dev
roomBrain.appToken=<the same APP_TOKEN this Worker has>
```

Every `/api` request then carries `Authorization: Bearer <APP_TOKEN>`. Rebuild
and install after changing either.

## Local dev

```bash
cp .dev.vars.example .dev.vars   # fill in values
npm run dev                      # http://127.0.0.1:8787
```

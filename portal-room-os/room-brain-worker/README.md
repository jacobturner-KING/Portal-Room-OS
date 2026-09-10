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
| `GET /connect?admin=<ADMIN_TOKEN>` | admin | One-time page to link Spotify / Google |
| `GET /auth/{spotify,google}/start` | admin cookie | Begins OAuth |
| `GET /auth/{spotify,google}/callback` | state param | OAuth return |

## Secrets (never committed)

Local dev reads `.dev.vars`. Production uses `wrangler secret put <NAME>`:

`APP_TOKEN`, `ADMIN_TOKEN`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`,
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `MAJOR_KEY_URL`, `MAJOR_KEY_KEY`,
`HA_URL`, `HA_TOKEN` (Home Assistant base URL reachable from the Worker, e.g. via a
Cloudflare Tunnel, plus a long-lived access token), `OBA_API_KEY` (OneBusAway Puget
Sound key; the documented `TEST` key is used until it is set).

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

The committed `wrangler.jsonc` carries no account ids, so it ships the KV
namespace id as a placeholder. Either paste yours in (and keep it out of any
commit), or copy the file to `wrangler.local.jsonc`, which is gitignored, put the
real id there and deploy with `wrangler deploy --config wrangler.local.jsonc`.

After deploy you get the Worker URL, e.g. `https://portal-room-brain.<subdomain>.workers.dev`.
Set these as the redirect URIs in each provider's dashboard:

- Spotify: `https://portal-room-brain.<subdomain>.workers.dev/auth/spotify/callback`
- Google:  `https://portal-room-brain.<subdomain>.workers.dev/auth/google/callback`

Then open `https://portal-room-brain.<subdomain>.workers.dev/connect?admin=<ADMIN_TOKEN>`
and link the accounts.

## Wire the Portal to it

The app reads both the Worker URL and the bearer token from `local.properties`
in `portal-room-os/`, which is gitignored, and they reach `Config.java` as
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

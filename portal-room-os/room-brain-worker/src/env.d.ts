// Ambient, global binding types for the Worker. Referenced (unimported) as `Env`
// across the source. Bindings and non-secret vars come from wrangler.jsonc;
// secrets are provided via `wrangler secret put` (and .dev.vars for local dev).
interface Env {
  // KV binding (wrangler.jsonc -> kv_namespaces[].binding)
  ROOM_KV: KVNamespace;

  // Non-secret vars (wrangler.jsonc -> vars)
  WEATHER_LAT?: string;
  WEATHER_LON?: string;
  WEATHER_LABEL?: string;
  HA_LIGHT_ENTITY?: string;
  HA_SUMMARY_ENTITIES?: string;
  /** JSON: terminals, ferry pairs, water taxi and bus stops. See .dev.vars.example. */
  TRANSIT_CONFIG?: string;

  // Secrets
  APP_TOKEN: string;
  ADMIN_TOKEN: string;
  SPOTIFY_CLIENT_ID: string;
  SPOTIFY_CLIENT_SECRET: string;
  GOOGLE_CLIENT_ID: string;
  GOOGLE_CLIENT_SECRET: string;
  MAJOR_KEY_URL?: string;
  MAJOR_KEY_KEY?: string;
  HA_URL?: string;
  HA_TOKEN?: string;
  OBA_API_KEY?: string;
}

// Ambient, global binding types for the Worker. Referenced (unimported) as `Env`
// across the source. Bindings and non-secret vars come from wrangler.jsonc;
// secrets are provided via `wrangler secret put` (and .dev.vars for local dev).
interface Env {
  // KV binding (wrangler.jsonc -> kv_namespaces[].binding)
  ROOM_KV: KVNamespace;
  // Workers AI (wrangler.jsonc -> ai.binding): Whisper for Portal recordings,
  // and a text model for naming and chatting about them without a Claude key.
  AI?: Ai;
  // Durable Object (wrangler.jsonc -> durable_objects): every coding agent's
  // session, and the sockets of the bridges that type into their terminals.
  AGENT_HUB: DurableObjectNamespace<import("./agents").AgentHub>;

  // Non-secret vars (wrangler.jsonc -> vars)
  WEATHER_LAT?: string;
  WEATHER_LON?: string;
  WEATHER_LABEL?: string;
  HA_LIGHT_ENTITY?: string;
  HA_SUMMARY_ENTITIES?: string;
  /** JSON: terminals, ferry pairs, water taxi and bus stops. See .dev.vars.example. */
  TRANSIT_CONFIG?: string;
  /** Whisper language for spoken agent instructions ("en", "es", ...); detected when unset. */
  AGENT_VOICE_LANGUAGE?: string;

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
  /** Claude, for the Meetings screen's chat over one meeting. */
  ANTHROPIC_API_KEY?: string;
  /** Shared with the hooks and bridges on your machines; opens /api/agents/{hook,permission,bridge} only. */
  AGENT_TOKEN?: string;
}

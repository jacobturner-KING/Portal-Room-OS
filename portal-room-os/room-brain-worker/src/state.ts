import { getJSON } from "./kv";
import type { ActionResult } from "./types";
import { getWeather } from "./weather";
import { getFocus } from "./majorkey";
import { getNextEvent, isGoogleConnected } from "./google";
import { getHomeSummary, isHomeAssistantConfigured, toggleLights } from "./homeassistant";
import {
  getNowPlaying,
  isSpotifyConnected,
  spotifyPlayPause,
  spotifyTransport,
} from "./spotify";

interface Overrides {
  focus?: string;
  reminder?: string;
}

// Each integration is best-effort: a failure falls back rather than breaking the panel.
async function safe<T>(fn: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await fn();
  } catch (err) {
    console.log(JSON.stringify({ level: "warn", msg: "integration_failed", error: String(err) }));
    return fallback;
  }
}

export async function buildState(env: Env, request?: Request): Promise<Record<string, unknown>> {
  const overrides = (await getJSON<Overrides>(env, "overrides")) ?? {};
  const [weather, focus, nextEvent, music, homeSummary, spotifyOn, googleOn] = await Promise.all([
    safe(() => getWeather(env, request), null),
    safe(() => getFocus(env), null),
    safe(() => getNextEvent(env), null),
    safe(() => getNowPlaying(env), null),
    safe(() => getHomeSummary(env), null),
    safe(() => isSpotifyConnected(env), false),
    safe(() => isGoogleConnected(env), false),
  ]);

  return {
    weather: weather?.text ?? "—",
    weather_location: weather ? { ...weather.location } : null,
    focus: focus ?? overrides.focus ?? "What deserves your focus right now?",
    next_event: nextEvent ?? (googleOn ? "No upcoming event" : "Connect Google Calendar"),
    reminder: overrides.reminder ?? "One intentional thing at a time.",
    home_summary: homeSummary ?? "Home Assistant not connected yet",
    music: music ?? (spotifyOn ? "Nothing playing" : "Connect Spotify"),
    occupied: true,
    connections: {
      spotify: spotifyOn,
      google: googleOn,
      major_key: Boolean(env.MAJOR_KEY_URL),
      home_assistant: isHomeAssistantConfigured(env),
    },
  };
}

export async function applyAction(env: Env, action: string): Promise<ActionResult> {
  switch (action) {
    case "toggle_music":
      return await spotifyPlayPause(env);
    case "music_play":
      return await spotifyTransport(env, "play");
    case "music_pause":
      return await spotifyTransport(env, "pause");
    case "music_next":
      return await spotifyTransport(env, "next");
    case "music_prev":
      return await spotifyTransport(env, "previous");
    case "toggle_lights":
      return await toggleLights(env);
    case "voice":
      return { ok: false, message: "Voice is a later milestone" };
    case "add_item":
      return { ok: false, message: "Add-item is a later milestone" };
    default:
      return { ok: false, message: `Unknown action: ${action}` };
  }
}

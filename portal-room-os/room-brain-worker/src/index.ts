import { checkAdmin, checkAppToken, unauthorized } from "./auth";
import { renderConnect } from "./connect";
import { del, getJSON, putJSON } from "./kv";
import { applyAction, buildState } from "./state";
import {
  getPlayback,
  getPlaylistTracks,
  isSpotifyConnected,
  listDevices,
  listPlaylists,
  spotifyAuthUrl,
  spotifyExchange,
  spotifySearch,
  spotifyTransport,
  startPlayback,
  transferPlayback,
  type TransportAction,
} from "./spotify";
import { googleAuthUrl, googleCanEdit, googleExchange, isGoogleConnected } from "./google";
import { busyDays, listCalendars, listEvents, getEvent, updateEvent, type EventPatch } from "./calendar";
import { getTransit } from "./transit";
import { isHomeAssistantConfigured } from "./homeassistant";

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function redirect(location: string): Response {
  return new Response(null, { status: 302, headers: { location } });
}

function callbackUri(request: Request, provider: string): string {
  return `${new URL(request.url).origin}/auth/${provider}/callback`;
}

async function newOAuthState(env: Env, provider: string): Promise<string> {
  const state = crypto.randomUUID();
  await putJSON(env, `oauth_state:${state}`, { provider }, 600);
  return state;
}

async function consumeOAuthState(env: Env, state: string, provider: string): Promise<boolean> {
  const rec = await getJSON<{ provider: string }>(env, `oauth_state:${state}`);
  if (!rec || rec.provider !== provider) return false;
  await del(env, `oauth_state:${state}`);
  return true;
}

async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const { pathname } = url;
  const method = request.method;

  if (method === "GET" && pathname === "/health") {
    return json({ ok: true, time: new Date().toISOString() });
  }

  // ---- Portal API (bearer-token auth) ----
  if (pathname === "/api/state" && method === "GET") {
    if (!checkAppToken(request, env)) return unauthorized();
    return json(await buildState(env, request));
  }

  if (pathname === "/api/action" && method === "POST") {
    if (!checkAppToken(request, env)) return unauthorized();
    let action = "";
    try {
      const body = (await request.json()) as { action?: string };
      action = body.action ?? "";
    } catch {
      // empty/invalid body -> unknown action
    }
    return json(await applyAction(env, action));
  }

  // ---- Spotify controller (bearer-token auth) ----
  if (pathname.startsWith("/api/spotify/")) {
    if (!checkAppToken(request, env)) return unauthorized();

    if (pathname === "/api/spotify/now" && method === "GET") {
      return json(await getPlayback(env));
    }
    if (pathname === "/api/spotify/devices" && method === "GET") {
      return json(await listDevices(env));
    }
    if (pathname === "/api/spotify/playlists" && method === "GET") {
      return json(await listPlaylists(env));
    }
    if (pathname === "/api/spotify/playlist" && method === "GET") {
      const id = url.searchParams.get("id");
      if (!id) return json({ error: "missing id" }, 400);
      return json(await getPlaylistTracks(env, id));
    }
    if (pathname === "/api/spotify/search" && method === "GET") {
      const q = url.searchParams.get("q") ?? "";
      if (!q.trim()) return json({ tracks: [], playlists: [], artists: [], albums: [] });
      return json(await spotifySearch(env, q));
    }
    if (pathname === "/api/spotify/transport" && method === "POST") {
      const body = (await request.json().catch(() => ({}))) as { action?: TransportAction };
      if (!body.action) return json({ ok: false, message: "missing action" }, 400);
      return json(await spotifyTransport(env, body.action));
    }
    if (pathname === "/api/spotify/play" && method === "POST") {
      const body = (await request.json().catch(() => ({}))) as {
        contextUri?: string;
        uris?: string[];
        deviceId?: string;
      };
      return json(await startPlayback(env, body));
    }
    if (pathname === "/api/spotify/transfer" && method === "POST") {
      const body = (await request.json().catch(() => ({}))) as { deviceId?: string; play?: boolean };
      if (!body.deviceId) return json({ ok: false, message: "missing deviceId" }, 400);
      return json(await transferPlayback(env, body.deviceId, body.play ?? true));
    }
    return json({ ok: false, error: "not_found" }, 404);
  }

  // ---- Transit (bearer-token auth) ----
  if (pathname === "/api/transit" && method === "GET") {
    if (!checkAppToken(request, env)) return unauthorized();
    return json(await getTransit(env));
  }

  // ---- Calendar (bearer-token auth) ----
  if (pathname.startsWith("/api/calendar/")) {
    if (!checkAppToken(request, env)) return unauthorized();

    if (pathname === "/api/calendar/calendars" && method === "GET") {
      return json((await listCalendars(env)) ?? { error: "not_connected" });
    }
    if (pathname === "/api/calendar/events" && method === "GET") {
      const start = url.searchParams.get("start");
      const end = url.searchParams.get("end");
      if (!start || !end) return json({ error: "missing start/end" }, 400);
      const events = await listEvents(env, start, end);
      return json(events ? { events } : { error: "not_connected" });
    }
    if (pathname === "/api/calendar/event" && method === "GET") {
      const calendarId = url.searchParams.get("calendarId");
      const id = url.searchParams.get("id");
      if (!calendarId || !id) return json({ error: "missing calendarId/id" }, 400);
      const event = await getEvent(env, calendarId, id);
      return json(event ?? { error: "not_found" }, event ? 200 : 404);
    }
    if (pathname === "/api/calendar/event/update" && method === "POST") {
      const body = (await request.json().catch(() => ({}))) as EventPatch;
      return json(await updateEvent(env, body));
    }
    if (pathname === "/api/calendar/days" && method === "GET") {
      const start = url.searchParams.get("start");
      const end = url.searchParams.get("end");
      const tz = url.searchParams.get("tz") ?? "UTC";
      if (!start || !end) return json({ error: "missing start/end" }, 400);
      const days = await busyDays(env, start, end, tz);
      return json(days ? { days } : { error: "not_connected" });
    }
    return json({ ok: false, error: "not_found" }, 404);
  }

  // ---- Admin: connect page ----
  if (pathname === "/connect" && method === "GET") {
    if (!checkAdmin(request, env)) return unauthorized();
    const status = {
      spotify: await isSpotifyConnected(env),
      google: await isGoogleConnected(env),
      googleEdit: await googleCanEdit(env),
      majorKey: Boolean(env.MAJOR_KEY_URL),
      homeAssistant: isHomeAssistantConfigured(env),
    };
    const res = renderConnect(status);
    res.headers.append(
      "set-cookie",
      `rk_admin=${encodeURIComponent(env.ADMIN_TOKEN)}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=3600`,
    );
    return res;
  }

  // ---- OAuth starts (admin-gated) ----
  if (pathname === "/auth/spotify/start" && method === "GET") {
    if (!checkAdmin(request, env)) return unauthorized();
    const state = await newOAuthState(env, "spotify");
    return redirect(spotifyAuthUrl(env, callbackUri(request, "spotify"), state));
  }

  if (pathname === "/auth/google/start" && method === "GET") {
    if (!checkAdmin(request, env)) return unauthorized();
    const state = await newOAuthState(env, "google");
    return redirect(googleAuthUrl(env, callbackUri(request, "google"), state));
  }

  // ---- OAuth callbacks (CSRF-validated by the state param) ----
  if (pathname === "/auth/spotify/callback" && method === "GET") {
    const code = url.searchParams.get("code");
    const state = url.searchParams.get("state") ?? "";
    if (!code || !(await consumeOAuthState(env, state, "spotify"))) {
      return json({ ok: false, error: "invalid_oauth_state" }, 400);
    }
    await spotifyExchange(env, code, callbackUri(request, "spotify"));
    return redirect("/connect");
  }

  if (pathname === "/auth/google/callback" && method === "GET") {
    const code = url.searchParams.get("code");
    const state = url.searchParams.get("state") ?? "";
    if (!code || !(await consumeOAuthState(env, state, "google"))) {
      return json({ ok: false, error: "invalid_oauth_state" }, 400);
    }
    await googleExchange(env, code, callbackUri(request, "google"));
    return redirect("/connect");
  }

  return json({ ok: false, error: "not_found" }, 404);
}

export default {
  async fetch(request, env): Promise<Response> {
    try {
      return await handle(request, env);
    } catch (err) {
      console.log(JSON.stringify({ level: "error", msg: "unhandled", error: String(err) }));
      return json({ ok: false, error: "internal_error" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;

import { checkAdmin, checkAgentToken, checkAppToken, unauthorized } from "./auth";
import { hookMeta, hubStub, parseAgent, parseHook, permissionHookOutput, transcribe } from "./agents";
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
import { GranolaNotConnected, granolaAuthUrl, granolaExchange, granolaStatus, listTools, callTool } from "./granola";
import {
  askGranola,
  askGranolaAbout,
  chatAboutMeeting,
  getMeetingNotes,
  getMeetingTranscript,
  listMeetings,
  type ChatTurn,
  type Meeting,
} from "./meetings";
import {
  addChunk,
  chatAboutRecording,
  deleteRecording,
  finishRecording,
  getNotes as getRecordingNotes,
  getRecording,
  getTranscript as getRecordingTranscript,
  isRecordingId,
  listRecordings,
} from "./recordings";

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

interface OAuthState {
  provider: string;
  /** PKCE verifier, for providers that use one. */
  verifier?: string;
}

async function newOAuthState(env: Env, provider: string, extra: Partial<OAuthState> = {}): Promise<string> {
  const state = crypto.randomUUID();
  await putJSON(env, `oauth_state:${state}`, { provider, ...extra }, 600);
  return state;
}

async function consumeOAuthState(env: Env, state: string, provider: string): Promise<OAuthState | null> {
  const rec = await getJSON<OAuthState>(env, `oauth_state:${state}`);
  if (!rec || rec.provider !== provider) return null;
  await del(env, `oauth_state:${state}`);
  return rec;
}

function chatTurns(v: unknown): ChatTurn[] {
  if (!Array.isArray(v)) return [];
  return v
    .filter((t) => t && typeof t === "object")
    .map((t) => t as { role?: unknown; content?: unknown })
    .filter((t) => (t.role === "user" || t.role === "assistant") && typeof t.content === "string" && t.content.trim())
    .map((t) => ({ role: t.role as ChatTurn["role"], content: String(t.content) }));
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

  // ---- Meetings: Granola notes and transcripts, chat over them (bearer-token auth) ----
  if (pathname.startsWith("/api/meetings")) {
    if (!checkAppToken(request, env)) return unauthorized();
    try {
      // One list: Granola's meetings and the Portal's own recordings, newest
      // first. Granola being unlinked is reported, not fatal, so recordings
      // still show.
      if (pathname === "/api/meetings" && method === "GET") {
        const search = url.searchParams.get("search") ?? "";
        const q = search.trim().toLowerCase();
        const limit = Math.min(500, Math.max(1, Number(url.searchParams.get("limit") ?? 30) || 30));
        let granola: { meetings: Meeting[]; raw: string } = { meetings: [], raw: "" };
        let connected = true;
        try {
          granola = await listMeetings(env, search, limit);
        } catch (err) {
          if (!(err instanceof GranolaNotConnected)) throw err;
          connected = false;
        }
        const portal = (await listRecordings(env)).filter((m) => !q || m.title.toLowerCase().includes(q));
        const when = (m: Meeting) => Date.parse(m.date) || 0; // ISO with mixed offsets; string order lies
        const meetings = [...granola.meetings.map((m) => ({ ...m, source: "granola" as const })), ...portal]
          .sort((a, b) => when(b) - when(a))
          .slice(0, limit);
        return json({ meetings, raw: meetings.length ? "" : granola.raw, granola: connected });
      }
      if (pathname === "/api/meetings/notes" && method === "GET") {
        const id = url.searchParams.get("id");
        if (!id) return json({ error: "missing id" }, 400);
        const notes = isRecordingId(id) ? await getRecordingNotes(env, id) : await getMeetingNotes(env, id);
        return json({ id, notes });
      }
      if (pathname === "/api/meetings/transcript" && method === "GET") {
        const id = url.searchParams.get("id");
        if (!id) return json({ error: "missing id" }, 400);
        const transcript = isRecordingId(id) ? await getRecordingTranscript(env, id) : await getMeetingTranscript(env, id);
        return json({ id, transcript });
      }
      // Granola's own AI answers by default: across every meeting when none is
      // open, pinned to the open one otherwise. With ANTHROPIC_API_KEY set,
      // Claude takes the per-meeting questions instead, reading the full
      // transcript and keeping the conversation.
      if (pathname === "/api/meetings/chat" && method === "POST") {
        const body = (await request.json().catch(() => ({}))) as {
          meetingId?: string;
          title?: string;
          date?: string;
          messages?: unknown;
        };
        const turns = chatTurns(body.messages);
        const last = turns[turns.length - 1];
        if (!last || last.role !== "user") return json({ error: "messages must end with a user turn" }, 400);
        if (!body.meetingId) return json({ reply: await askGranola(env, last.content), source: "granola" });
        if (isRecordingId(body.meetingId)) {
          const rec = await getRecording(env, body.meetingId);
          if (!rec) return json({ error: "not_found" }, 404);
          const transcript = await getRecordingTranscript(env, rec.id);
          return json(await chatAboutRecording(env, rec, transcript, turns));
        }
        const ref = { id: body.meetingId, title: body.title ?? "Meeting", date: body.date ?? "" };
        if (!env.ANTHROPIC_API_KEY) return json({ reply: await askGranolaAbout(env, ref, turns), source: "granola" });
        const [notes, transcript] = await Promise.all([
          getMeetingNotes(env, body.meetingId),
          getMeetingTranscript(env, body.meetingId).catch(() => ""),
        ]);
        const reply = await chatAboutMeeting(
          env,
          { title: body.title ?? "Meeting", date: body.date ?? "", notes, transcript },
          turns,
        );
        return json({ reply, source: "claude" });
      }
      return json({ ok: false, error: "not_found" }, 404);
    } catch (err) {
      if (err instanceof GranolaNotConnected) return json({ error: "not_connected" });
      throw err;
    }
  }

  // ---- Portal recordings: audio in, transcript kept (bearer-token auth) ----
  // PUT  /api/recordings/<id>/chunk?seq=N&startedAt=ISO   body: 16 kHz mono WAV
  // POST /api/recordings/<id>/finish  { durationSec }
  // GET  /api/recordings/<id>
  // DELETE /api/recordings/<id>
  const rec = pathname.match(/^\/api\/recordings\/(rec_[0-9a-z]+)(?:\/(chunk|finish))?$/);
  if (rec) {
    if (!checkAppToken(request, env)) return unauthorized();
    const [, id, verb] = rec;
    if (!isRecordingId(id)) return json({ error: "bad id" }, 400);
    if (verb === "chunk" && method === "PUT") {
      const seq = Number(url.searchParams.get("seq"));
      if (!Number.isInteger(seq) || seq < 0) return json({ error: "missing seq" }, 400);
      const wav = await request.arrayBuffer();
      if (wav.byteLength < 100) return json({ error: "empty chunk" }, 400);
      const seg = await addChunk(env, id, seq, url.searchParams.get("startedAt") ?? undefined, wav);
      return json({ ok: true, seq, ...seg });
    }
    if (verb === "finish" && method === "POST") {
      const body = (await request.json().catch(() => ({}))) as { durationSec?: number };
      const done = await finishRecording(env, id, typeof body.durationSec === "number" ? body.durationSec : undefined);
      return json({ ok: true, id: done.id, title: done.title, chunks: done.chunks });
    }
    if (!verb && method === "GET") {
      const r = await getRecording(env, id);
      return json(r ?? { error: "not_found" }, r ? 200 : 404);
    }
    if (!verb && method === "DELETE") {
      await deleteRecording(env, id);
      return json({ ok: true });
    }
    return json({ ok: false, error: "not_found" }, 404);
  }

  // ---- Agents: hooks and bridges on your machines (AGENT_TOKEN) ----
  // POST /api/agents/hook?agent=claude|codex|cursor|generic   body: the agent's own hook payload
  // GET  /api/agents/permission?id=   a held permission prompt: 200 hook output, 204 pending, 410 gone
  // GET  /api/agents/bridge           WebSocket; the token rides in the first message
  if (pathname === "/api/agents/hook" && method === "POST") {
    if (!env.AGENT_TOKEN) return json({ ok: false, error: "AGENT_TOKEN is not set on the Worker" }, 503);
    if (!checkAgentToken(request, env)) return unauthorized();
    const agent = parseAgent(url.searchParams.get("agent"));
    if (!agent) return json({ ok: false, error: "agent must be claude, codex, cursor or generic" }, 400);
    const payload = await request.json().catch(() => null);
    const update = parseHook(agent, payload);
    if (!update) return json({ ok: true, ignored: true });
    return json({ ok: true, ...(await hubStub(env).hook(hookMeta(agent, request.headers), update)) });
  }
  if (pathname === "/api/agents/permission" && method === "GET") {
    if (!checkAgentToken(request, env)) return unauthorized();
    const id = url.searchParams.get("id") ?? "";
    if (!/^[0-9a-f]{32}$/.test(id)) return json({ error: "bad id" }, 400);
    const r = await hubStub(env).permission(id);
    if (r.status === "pending") return new Response(null, { status: 204 });
    if (r.status === "gone") return new Response(null, { status: 410 });
    return json(permissionHookOutput(r.behavior));
  }
  if (pathname === "/api/agents/bridge" && method === "GET") {
    if (!env.AGENT_TOKEN) return json({ ok: false, error: "AGENT_TOKEN is not set on the Worker" }, 503);
    return hubStub(env).fetch(request);
  }

  // ---- Speech to text, for agents the Portal reaches another way (AppTabs over USB) ----
  if (pathname === "/api/transcribe" && method === "POST") {
    if (!checkAppToken(request, env)) return unauthorized();
    const wav = await request.arrayBuffer();
    if (wav.byteLength < 1000) return json({ ok: false, error: "That was too short to hear" });
    const text = await transcribe(env, wav);
    return json(text ? { ok: true, text } : { ok: false, error: "Didn't catch any words" });
  }

  // ---- Agents: the Portal's side (bearer-token auth) ----
  if (pathname.startsWith("/api/agents")) {
    if (!checkAppToken(request, env)) return unauthorized();
    const hub = hubStub(env);
    if (pathname === "/api/agents" && method === "GET") {
      return json(await hub.snapshot());
    }
    // Spoken (audio/wav, transcribed here) or typed ({text}); either way it is typed into the agent's terminal.
    if (pathname === "/api/agents/say" && method === "POST") {
      const id = url.searchParams.get("id") ?? "";
      let text = "";
      if ((request.headers.get("content-type") ?? "").startsWith("audio/")) {
        const wav = await request.arrayBuffer();
        if (wav.byteLength < 1000) return json({ ok: false, error: "That was too short to hear" });
        text = await transcribe(env, wav);
        if (!text) return json({ ok: false, error: "Didn't catch any words" });
      } else {
        const body = (await request.json().catch(() => ({}))) as { text?: unknown };
        text = typeof body.text === "string" ? body.text : "";
      }
      const r = await hub.say(id, text);
      return json(r.ok ? { ok: true, text: r.text ?? text } : { ok: false, text, error: r.error });
    }
    const body = (await request.json().catch(() => ({}))) as {
      id?: unknown;
      permissionId?: unknown;
      allow?: unknown;
      wallApprovals?: unknown;
    };
    const id = typeof body.id === "string" ? body.id : "";
    if (pathname === "/api/agents/decide" && method === "POST") {
      if (typeof body.permissionId !== "string" || typeof body.allow !== "boolean") {
        return json({ ok: false, error: "needs id, permissionId and allow" }, 400);
      }
      return json(await hub.decide(id, body.permissionId, body.allow));
    }
    if (pathname === "/api/agents/focus" && method === "POST") {
      return json(await hub.focus(id));
    }
    if (pathname === "/api/agents/dismiss" && method === "POST") {
      await hub.dismiss(id);
      return json({ ok: true });
    }
    if (pathname === "/api/agents/settings" && method === "POST") {
      const patch = typeof body.wallApprovals === "boolean" ? { wallApprovals: body.wallApprovals } : {};
      return json({ ok: true, settings: await hub.setSettings(patch) });
    }
    return json({ ok: false, error: "not_found" }, 404);
  }

  // Admin: what Granola's server actually exposes, for tuning the parsers.
  if (pathname === "/admin/granola" && method === "GET") {
    if (!checkAdmin(request, env)) return unauthorized();
    try {
      const tools = await listTools(env);
      const sample = url.searchParams.get("tool");
      const args = url.searchParams.get("args");
      const call = sample ? await callTool(env, sample, args ? (JSON.parse(args) as Record<string, unknown>) : {}) : null;
      return json({ tools, call });
    } catch (err) {
      if (err instanceof GranolaNotConnected) return json({ error: "not_connected" });
      return json({ error: String(err) }, 500);
    }
  }

  // ---- Admin: connect page ----
  if (pathname === "/connect" && method === "GET") {
    if (!checkAdmin(request, env)) return unauthorized();
    const granola = await granolaStatus(env);
    const status = {
      spotify: await isSpotifyConnected(env),
      google: await isGoogleConnected(env),
      googleEdit: await googleCanEdit(env),
      granola: granola.connected,
      granolaStale: granola.stale,
      meetingChat: Boolean(env.ANTHROPIC_API_KEY),
      majorKey: Boolean(env.MAJOR_KEY_URL),
      homeAssistant: isHomeAssistantConfigured(env),
    };
    const res = renderConnect(status, url.searchParams.get("err"));
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

  if (pathname === "/auth/granola/start" && method === "GET") {
    if (!checkAdmin(request, env)) return unauthorized();
    // PKCE: the verifier is minted with the URL and rides in the state record.
    const state = crypto.randomUUID();
    const { url: authUrl, verifier } = await granolaAuthUrl(env, callbackUri(request, "granola"), state);
    await putJSON(env, `oauth_state:${state}`, { provider: "granola", verifier } satisfies OAuthState, 600);
    return redirect(authUrl);
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

  if (pathname === "/auth/granola/callback" && method === "GET") {
    const code = url.searchParams.get("code");
    const state = url.searchParams.get("state") ?? "";
    const denied = url.searchParams.get("error");
    const rec = await consumeOAuthState(env, state, "granola");
    if (denied) return redirect(`/connect?err=${encodeURIComponent(`Granola: ${denied}`)}`);
    if (!code || !rec?.verifier) return json({ ok: false, error: "invalid_oauth_state" }, 400);
    try {
      await granolaExchange(env, code, callbackUri(request, "granola"), rec.verifier);
    } catch (err) {
      // Surface it on the page: the person linking is the one who can act on it.
      console.log(JSON.stringify({ level: "error", msg: "granola_link_failed", error: String(err) }));
      return redirect(`/connect?err=${encodeURIComponent(String(err))}`);
    }
    return redirect("/connect");
  }

  return json({ ok: false, error: "not_found" }, 404);
}

export { AgentHub } from "./agents";

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

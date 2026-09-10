import { getJSON, putJSON } from "./kv";
import type { ActionResult, TokenSet } from "./types";

const KEY = "tokens:spotify";
const SCOPES =
  "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
  "playlist-read-private playlist-read-collaborative user-library-read";

function basicAuth(env: Env): string {
  return "Basic " + btoa(`${env.SPOTIFY_CLIENT_ID}:${env.SPOTIFY_CLIENT_SECRET}`);
}

export function spotifyAuthUrl(env: Env, redirectUri: string, state: string): string {
  const p = new URLSearchParams({
    response_type: "code",
    client_id: env.SPOTIFY_CLIENT_ID,
    scope: SCOPES,
    redirect_uri: redirectUri,
    state,
  });
  return `https://accounts.spotify.com/authorize?${p.toString()}`;
}

export async function spotifyExchange(env: Env, code: string, redirectUri: string): Promise<void> {
  const res = await fetch("https://accounts.spotify.com/api/token", {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      authorization: basicAuth(env),
    },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri,
    }),
  });
  if (!res.ok) throw new Error(`spotify token exchange failed: ${res.status}`);
  const data = (await res.json()) as {
    access_token: string;
    refresh_token: string;
    expires_in: number;
    scope: string;
  };
  await putJSON(env, KEY, {
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expires_at: Date.now() + data.expires_in * 1000,
    scope: data.scope,
  } satisfies TokenSet);
}

async function accessToken(env: Env): Promise<string | null> {
  const t = await getJSON<TokenSet>(env, KEY);
  if (!t) return null;
  if (Date.now() < t.expires_at - 60_000) return t.access_token;

  const res = await fetch("https://accounts.spotify.com/api/token", {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      authorization: basicAuth(env),
    },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: t.refresh_token,
    }),
  });
  if (!res.ok) throw new Error(`spotify refresh failed: ${res.status}`);
  const data = (await res.json()) as {
    access_token: string;
    expires_in: number;
    refresh_token?: string;
  };
  const updated: TokenSet = {
    access_token: data.access_token,
    refresh_token: data.refresh_token ?? t.refresh_token,
    expires_at: Date.now() + data.expires_in * 1000,
    scope: t.scope,
  };
  await putJSON(env, KEY, updated);
  return updated.access_token;
}

export async function isSpotifyConnected(env: Env): Promise<boolean> {
  return (await getJSON<TokenSet>(env, KEY)) !== null;
}

export async function getNowPlaying(env: Env): Promise<string | null> {
  const token = await accessToken(env);
  if (!token) return null;
  const res = await fetch("https://api.spotify.com/v1/me/player/currently-playing", {
    headers: { authorization: `Bearer ${token}` },
  });
  if (res.status === 204) return "Nothing playing";
  if (!res.ok) return null;
  const data = (await res.json()) as {
    is_playing?: boolean;
    item?: { name: string; artists?: Array<{ name: string }> };
  };
  if (!data.item) return "Nothing playing";
  const artists = (data.item.artists ?? []).map((a) => a.name).join(", ");
  const suffix = data.is_playing ? "" : " · paused";
  return `${data.item.name} · ${artists}${suffix}`;
}

async function isPlaying(token: string): Promise<boolean | null> {
  const res = await fetch("https://api.spotify.com/v1/me/player", {
    headers: { authorization: `Bearer ${token}` },
  });
  if (res.status === 204) return null; // no active device
  if (!res.ok) return null;
  const data = (await res.json()) as { is_playing?: boolean };
  return Boolean(data.is_playing);
}

export async function spotifyPlayPause(env: Env): Promise<ActionResult> {
  const token = await accessToken(env);
  if (!token) return { ok: false, message: "Spotify not connected" };

  const playing = await isPlaying(token);
  if (playing === null) {
    return { ok: false, message: "No active Spotify device — start playback somewhere first" };
  }
  const endpoint = playing ? "pause" : "play";
  const res = await fetch(`https://api.spotify.com/v1/me/player/${endpoint}`, {
    method: "PUT",
    headers: { authorization: `Bearer ${token}` },
  });
  if (res.status === 404) return { ok: false, message: "No active Spotify device" };
  if (!res.ok && res.status !== 204) return { ok: false, message: `Spotify error ${res.status}` };
  return { ok: true, message: playing ? "Paused" : "Playing" };
}

export async function spotifyNext(env: Env): Promise<ActionResult> {
  return spotifyTransport(env, "next");
}

// ---- Full controller API: transport, now-playing, devices, search, playlists ----

function art(images?: Array<{ url: string }>): string | null {
  return images && images.length ? images[0].url : null;
}

export type TransportAction = "play" | "pause" | "next" | "previous";

export async function spotifyTransport(env: Env, action: TransportAction): Promise<ActionResult> {
  const token = await accessToken(env);
  if (!token) return { ok: false, message: "Spotify not connected" };
  const method = action === "next" || action === "previous" ? "POST" : "PUT";
  const res = await fetch(`https://api.spotify.com/v1/me/player/${action}`, {
    method,
    headers: { authorization: `Bearer ${token}` },
  });
  if (res.status === 404) return { ok: false, message: "No active Spotify device" };
  if (!res.ok && res.status !== 204) return { ok: false, message: `Spotify error ${res.status}` };
  const label: Record<TransportAction, string> = {
    play: "Playing",
    pause: "Paused",
    next: "Skipped",
    previous: "Back",
  };
  return { ok: true, message: label[action] };
}

export interface Playback {
  isPlaying: boolean;
  track: string | null;
  artists: string | null;
  album: string | null;
  artUrl: string | null;
  deviceId: string | null;
  deviceName: string | null;
  progressMs: number;
  durationMs: number;
}

export async function getPlayback(env: Env): Promise<Playback | null> {
  const token = await accessToken(env);
  if (!token) return null;
  const res = await fetch("https://api.spotify.com/v1/me/player", {
    headers: { authorization: `Bearer ${token}` },
  });
  const empty: Playback = {
    isPlaying: false, track: null, artists: null, album: null, artUrl: null,
    deviceId: null, deviceName: null, progressMs: 0, durationMs: 0,
  };
  if (res.status === 204) return empty;
  if (!res.ok) return null;
  const d = (await res.json()) as any;
  const item = d.item;
  return {
    isPlaying: Boolean(d.is_playing),
    track: item?.name ?? null,
    artists: item?.artists ? item.artists.map((a: any) => a.name).join(", ") : null,
    album: item?.album?.name ?? null,
    artUrl: art(item?.album?.images),
    deviceId: d.device?.id ?? null,
    deviceName: d.device?.name ?? null,
    progressMs: d.progress_ms ?? 0,
    durationMs: item?.duration_ms ?? 0,
  };
}

export async function listDevices(env: Env): Promise<unknown> {
  const token = await accessToken(env);
  if (!token) return { error: "not_connected" };
  const res = await fetch("https://api.spotify.com/v1/me/player/devices", {
    headers: { authorization: `Bearer ${token}` },
  });
  if (!res.ok) return { error: res.status };
  const d = (await res.json()) as any;
  return (d.devices ?? []).map((x: any) => ({
    id: x.id, name: x.name, type: x.type, isActive: x.is_active, volume: x.volume_percent,
  }));
}

export async function listPlaylists(env: Env): Promise<unknown> {
  const token = await accessToken(env);
  if (!token) return { error: "not_connected" };
  const res = await fetch("https://api.spotify.com/v1/me/playlists?limit=50", {
    headers: { authorization: `Bearer ${token}` },
  });
  if (res.status === 403) return { error: 403, hint: "re-link Spotify to grant playlist scopes" };
  if (!res.ok) return { error: res.status };
  const d = (await res.json()) as any;
  return (d.items ?? []).map((p: any) => ({
    id: p.id, uri: p.uri, name: p.name, imageUrl: art(p.images), tracks: p.tracks?.total ?? 0,
  }));
}

export async function getPlaylistTracks(env: Env, id: string, limit = 100): Promise<unknown> {
  const token = await accessToken(env);
  if (!token) return { error: "not_connected" };
  const res = await fetch(
    `https://api.spotify.com/v1/playlists/${encodeURIComponent(id)}/tracks?limit=${limit}`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  if (!res.ok) return { error: res.status };
  const d = (await res.json()) as any;
  return (d.items ?? [])
    .filter((i: any) => i.track)
    .map((i: any) => ({
      uri: i.track.uri,
      name: i.track.name,
      artists: (i.track.artists ?? []).map((a: any) => a.name).join(", "),
      artUrl: art(i.track.album?.images),
    }));
}

export async function spotifySearch(env: Env, q: string): Promise<unknown> {
  const token = await accessToken(env);
  if (!token) return { error: "not_connected" };
  const params = new URLSearchParams({ q, type: "track,playlist,artist,album", limit: "10" });
  const res = await fetch(`https://api.spotify.com/v1/search?${params.toString()}`, {
    headers: { authorization: `Bearer ${token}` },
  });
  if (!res.ok) return { error: res.status };
  const d = (await res.json()) as any;
  // Spotify search arrays can contain null entries; filter before mapping.
  return {
    tracks: (d.tracks?.items ?? []).filter(Boolean).map((t: any) => ({
      uri: t.uri, name: t.name,
      artists: (t.artists ?? []).map((a: any) => a.name).join(", "),
      artUrl: art(t.album?.images),
    })),
    playlists: (d.playlists?.items ?? []).filter(Boolean).map((p: any) => ({
      id: p.id, uri: p.uri, name: p.name, imageUrl: art(p.images),
    })),
    artists: (d.artists?.items ?? []).filter(Boolean).map((a: any) => ({
      uri: a.uri, name: a.name, imageUrl: art(a.images),
    })),
    albums: (d.albums?.items ?? []).filter(Boolean).map((al: any) => ({
      uri: al.uri, name: al.name,
      artists: (al.artists ?? []).map((a: any) => a.name).join(", "),
      imageUrl: art(al.images),
    })),
  };
}

export async function startPlayback(
  env: Env,
  opts: { contextUri?: string; uris?: string[]; deviceId?: string },
): Promise<ActionResult> {
  const token = await accessToken(env);
  if (!token) return { ok: false, message: "Spotify not connected" };
  const body: Record<string, unknown> = {};
  if (opts.contextUri) body.context_uri = opts.contextUri;
  if (opts.uris) body.uris = opts.uris;
  const q = opts.deviceId ? `?device_id=${encodeURIComponent(opts.deviceId)}` : "";
  const res = await fetch(`https://api.spotify.com/v1/me/player/play${q}`, {
    method: "PUT",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (res.status === 404) return { ok: false, message: "No active Spotify device — pick one first" };
  if (!res.ok && res.status !== 204) return { ok: false, message: `Spotify error ${res.status}` };
  return { ok: true, message: "Playing" };
}

export async function transferPlayback(env: Env, deviceId: string, play = true): Promise<ActionResult> {
  const token = await accessToken(env);
  if (!token) return { ok: false, message: "Spotify not connected" };
  const res = await fetch("https://api.spotify.com/v1/me/player", {
    method: "PUT",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ device_ids: [deviceId], play }),
  });
  if (!res.ok && res.status !== 204) return { ok: false, message: `Spotify error ${res.status}` };
  return { ok: true, message: "Playback moved" };
}

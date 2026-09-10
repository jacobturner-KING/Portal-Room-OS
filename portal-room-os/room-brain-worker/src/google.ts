import { getJSON, putJSON } from "./kv";
import type { TokenSet } from "./types";

const KEY = "tokens:google";
// calendar.readonly covers the calendar list; calendar.events allows editing events.
const SCOPE = "https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/calendar.events";

export function googleAuthUrl(env: Env, redirectUri: string, state: string): string {
  const p = new URLSearchParams({
    client_id: env.GOOGLE_CLIENT_ID,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: SCOPE,
    access_type: "offline",
    prompt: "consent",
    state,
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${p.toString()}`;
}

export async function googleExchange(env: Env, code: string, redirectUri: string): Promise<void> {
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: redirectUri,
      grant_type: "authorization_code",
    }),
  });
  if (!res.ok) throw new Error(`google token exchange failed: ${res.status}`);
  const data = (await res.json()) as {
    access_token: string;
    refresh_token?: string;
    expires_in: number;
    scope?: string;
  };
  if (!data.refresh_token) {
    // Google only returns a refresh_token on first consent; prompt=consent forces it.
    throw new Error("google returned no refresh_token (re-consent required)");
  }
  await putJSON(env, KEY, {
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expires_at: Date.now() + data.expires_in * 1000,
    scope: data.scope,
  } satisfies TokenSet);
}

export async function googleAccessToken(env: Env): Promise<string | null> {
  const t = await getJSON<TokenSet>(env, KEY);
  if (!t) return null;
  if (Date.now() < t.expires_at - 60_000) return t.access_token;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      refresh_token: t.refresh_token,
      grant_type: "refresh_token",
    }),
  });
  if (!res.ok) throw new Error(`google refresh failed: ${res.status}`);
  const data = (await res.json()) as { access_token: string; expires_in: number };
  const updated: TokenSet = {
    access_token: data.access_token,
    refresh_token: t.refresh_token,
    expires_at: Date.now() + data.expires_in * 1000,
    scope: t.scope,
  };
  await putJSON(env, KEY, updated);
  return updated.access_token;
}

export async function isGoogleConnected(env: Env): Promise<boolean> {
  return (await getJSON<TokenSet>(env, KEY)) !== null;
}

// True once the stored grant includes event editing (links made before the scope
// change carry no scope record and count as read-only until re-linked).
export async function googleCanEdit(env: Env): Promise<boolean> {
  const t = await getJSON<TokenSet>(env, KEY);
  const scope = t?.scope ?? "";
  return scope.includes("auth/calendar.events") || /auth\/calendar(\s|$)/.test(scope);
}

export async function getNextEvent(env: Env): Promise<string | null> {
  const token = await googleAccessToken(env);
  if (!token) return null;
  const params = new URLSearchParams({
    timeMin: new Date().toISOString(),
    maxResults: "10",
    singleEvents: "true",
    orderBy: "startTime",
  });
  const res = await fetch(
    `https://www.googleapis.com/calendar/v3/calendars/primary/events?${params.toString()}`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  if (!res.ok) return null;
  const data = (await res.json()) as {
    items?: Array<{
      summary?: string;
      start?: { dateTime?: string; date?: string; timeZone?: string };
    }>;
  };
  const now = Date.now();
  // Skip all-day events that already ended — Google can return prior-day all-day items.
  const ev = (data.items ?? []).find((e) => {
    if (e.start?.dateTime) return new Date(e.start.dateTime).getTime() >= now;
    if (e.start?.date) return new Date(`${e.start.date}T23:59:59`).getTime() >= now;
    return false;
  });
  if (!ev) return null;
  const when = ev.start?.dateTime
    ? new Date(ev.start.dateTime).toLocaleTimeString("en-US", {
        hour: "numeric",
        minute: "2-digit",
        timeZone: ev.start.timeZone,
      })
    : "All day";
  return `${when} · ${ev.summary ?? "Busy"}`;
}

// Google Calendar for the Portal's calendar screen: the user's calendars, events in
// a range, one event, updates, and per-day counts for the year view.
//
// Reads work with the original read-only Google link. Updates need the
// calendar.events scope, which means re-linking Google once on /connect; until
// then the Worker reports canEdit=false and updates come back with a clear message.
import { getJSON, putJSON } from "./kv";
import { googleAccessToken, googleCanEdit } from "./google";
import type { ActionResult } from "./types";

const API = "https://www.googleapis.com/calendar/v3";
const CALENDARS_TTL = 600;
const DAYS_TTL = 600;
const FALLBACK_COLOR = "#8aa4ff";

export interface CalendarInfo {
  id: string;
  name: string;
  color: string;
  primary: boolean;
  editable: boolean;
}

export interface CalEvent {
  id: string;
  calendarId: string;
  calendar: string;
  color: string;
  title: string;
  location: string | null;
  description: string | null;
  allDay: boolean;
  start: string; // RFC3339 with offset, or YYYY-MM-DD for all-day
  end: string; // same; all-day end is exclusive (Google's convention)
  status: string;
  htmlLink: string | null;
  attendees: string[];
  recurring: boolean;
  editable: boolean;
}

interface GCalendarListEntry {
  id: string;
  summary?: string;
  summaryOverride?: string;
  backgroundColor?: string;
  selected?: boolean;
  primary?: boolean;
  accessRole?: string;
}

interface GEventTime {
  dateTime?: string;
  date?: string;
  timeZone?: string;
}

interface GEvent {
  id: string;
  summary?: string;
  description?: string;
  location?: string;
  status?: string;
  htmlLink?: string;
  start?: GEventTime;
  end?: GEventTime;
  attendees?: Array<{ email?: string; displayName?: string; responseStatus?: string }>;
  recurringEventId?: string;
}

class GoogleApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function gfetch<T>(token: string, url: string, init: { method?: string; body?: string } = {}): Promise<T> {
  const res = await fetch(url, {
    method: init.method ?? "GET",
    body: init.body,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
  });
  if (!res.ok) {
    const text = (await res.text()).slice(0, 300);
    throw new GoogleApiError(res.status, `google calendar ${res.status}: ${text}`);
  }
  return (await res.json()) as T;
}

function enc(s: string): string {
  return encodeURIComponent(s);
}

function toEvent(e: GEvent, cal: CalendarInfo): CalEvent | null {
  if (!e.start || !e.end) return null;
  const allDay = Boolean(e.start.date);
  const start = allDay ? e.start.date : e.start.dateTime;
  const end = allDay ? e.end.date : e.end.dateTime;
  if (!start || !end) return null;
  return {
    id: e.id,
    calendarId: cal.id,
    calendar: cal.name,
    color: cal.color,
    title: e.summary?.trim() || "(No title)",
    location: e.location ?? null,
    description: e.description ?? null,
    allDay,
    start,
    end,
    status: e.status ?? "confirmed",
    htmlLink: e.htmlLink ?? null,
    attendees: (e.attendees ?? []).map((a) => a.displayName || a.email || "").filter(Boolean),
    recurring: Boolean(e.recurringEventId),
    editable: cal.editable,
  };
}

function unknownCalendar(id: string): CalendarInfo {
  return { id, name: id, color: FALLBACK_COLOR, primary: false, editable: false };
}

export async function listCalendars(env: Env): Promise<{ calendars: CalendarInfo[]; canEdit: boolean } | null> {
  const token = await googleAccessToken(env);
  if (!token) return null;
  const canEdit = await googleCanEdit(env);
  const cached = await getJSON<CalendarInfo[]>(env, "cache:gcal:calendars");
  if (cached) return { calendars: cached, canEdit };

  const data = await gfetch<{ items?: GCalendarListEntry[] }>(token, `${API}/users/me/calendarList?maxResults=100`);
  const all = data.items ?? [];
  // Google marks calendars shown in its UI with selected=true; fall back to all if none are.
  let shown = all.filter((c) => c.selected === true || c.primary === true);
  if (shown.length === 0) shown = all;
  const calendars: CalendarInfo[] = shown.map((c) => ({
    id: c.id,
    name: c.summaryOverride || c.summary || c.id,
    color: c.backgroundColor ?? FALLBACK_COLOR,
    primary: Boolean(c.primary),
    editable: c.accessRole === "owner" || c.accessRole === "writer",
  }));
  await putJSON(env, "cache:gcal:calendars", calendars, CALENDARS_TTL);
  return { calendars, canEdit };
}

async function pageEvents(
  token: string,
  cal: CalendarInfo,
  params: URLSearchParams,
  fields: string,
  maxPages: number,
): Promise<GEvent[]> {
  const out: GEvent[] = [];
  let pageToken: string | undefined;
  for (let i = 0; i < maxPages; i++) {
    const p = new URLSearchParams(params);
    p.set("fields", fields);
    if (pageToken) p.set("pageToken", pageToken);
    const data = await gfetch<{ items?: GEvent[]; nextPageToken?: string }>(
      token,
      `${API}/calendars/${enc(cal.id)}/events?${p.toString()}`,
    );
    out.push(...(data.items ?? []));
    if (!data.nextPageToken) break;
    pageToken = data.nextPageToken;
  }
  return out;
}

function sortKey(ev: CalEvent): number {
  return ev.allDay ? Date.parse(`${ev.start}T00:00:00Z`) - 1 : Date.parse(ev.start);
}

export async function listEvents(env: Env, timeMin: string, timeMax: string): Promise<CalEvent[] | null> {
  const token = await googleAccessToken(env);
  if (!token) return null;
  const cals = await listCalendars(env);
  if (!cals) return null;
  // No orderBy: asking Google to sort makes it materialize every recurring instance
  // first and was ~7x slower on this account. We sort after merging instead.
  const params = new URLSearchParams({ timeMin, timeMax, singleEvents: "true", maxResults: "250" });
  const fields =
    "nextPageToken,items(id,summary,description,location,status,htmlLink,start,end," +
    "attendees(email,displayName,responseStatus),recurringEventId)";
  const perCalendar = await Promise.all(
    cals.calendars.map(async (cal) => {
      const t0 = Date.now();
      try {
        const items = await pageEvents(token, cal, params, fields, 8);
        const ms = Date.now() - t0;
        if (ms > 2000) console.log(JSON.stringify({ level: "info", msg: "gcal_slow_calendar", calendar: cal.name, items: items.length, ms }));
        return items.map((e) => toEvent(e, cal));
      } catch (err) {
        console.log(JSON.stringify({ level: "warn", msg: "gcal_events_failed", calendar: cal.id, error: String(err) }));
        return [];
      }
    }),
  );
  const events = perCalendar.flat().filter((e): e is CalEvent => e !== null && e.status !== "cancelled");
  events.sort((a, b) => sortKey(a) - sortKey(b));
  return events;
}

export async function getEvent(env: Env, calendarId: string, id: string): Promise<CalEvent | null> {
  const token = await googleAccessToken(env);
  if (!token) return null;
  const cals = await listCalendars(env);
  const cal = cals?.calendars.find((c) => c.id === calendarId) ?? unknownCalendar(calendarId);
  try {
    const e = await gfetch<GEvent>(token, `${API}/calendars/${enc(calendarId)}/events/${enc(id)}`);
    return toEvent(e, cal);
  } catch (err) {
    if (err instanceof GoogleApiError && err.status === 404) return null;
    throw err;
  }
}

export interface EventPatch {
  calendarId?: string;
  id?: string;
  title?: string;
  location?: string;
  description?: string;
  allDay?: boolean;
  start?: string; // RFC3339 for timed, YYYY-MM-DD for all-day
  end?: string; // same; all-day end exclusive
}

export async function updateEvent(env: Env, patch: EventPatch): Promise<ActionResult & { event?: CalEvent }> {
  const token = await googleAccessToken(env);
  if (!token) return { ok: false, message: "Google not connected" };
  if (!patch.calendarId || !patch.id) return { ok: false, message: "missing calendarId or id" };

  const body: Record<string, unknown> = {};
  if (patch.title !== undefined) body.summary = patch.title;
  if (patch.location !== undefined) body.location = patch.location;
  if (patch.description !== undefined) body.description = patch.description;
  if (patch.start !== undefined || patch.end !== undefined) {
    if (patch.allDay === undefined) return { ok: false, message: "allDay is required when changing times" };
    // Setting the unused field to null switches an event between timed and all-day.
    if (patch.start !== undefined) {
      body.start = patch.allDay
        ? { date: patch.start, dateTime: null, timeZone: null }
        : { dateTime: patch.start, date: null };
    }
    if (patch.end !== undefined) {
      body.end = patch.allDay
        ? { date: patch.end, dateTime: null, timeZone: null }
        : { dateTime: patch.end, date: null };
    }
  }
  if (Object.keys(body).length === 0) return { ok: true, message: "Nothing to change" };

  const cals = await listCalendars(env);
  const cal = cals?.calendars.find((c) => c.id === patch.calendarId) ?? unknownCalendar(patch.calendarId);
  try {
    const e = await gfetch<GEvent>(token, `${API}/calendars/${enc(cal.id)}/events/${enc(patch.id)}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
    const event = toEvent(e, cal);
    return { ok: true, message: "Saved", event: event ?? undefined };
  } catch (err) {
    if (err instanceof GoogleApiError) {
      if (err.status === 403) {
        return {
          ok: false,
          message: "Google is linked read-only. Re-link Google on the connect page to allow editing.",
        };
      }
      if (err.status === 404) return { ok: false, message: "Event not found" };
      console.log(JSON.stringify({ level: "warn", msg: "gcal_update_failed", error: err.message }));
      return { ok: false, message: `Google error ${err.status}` };
    }
    throw err;
  }
}

// Number of one-off (non-recurring) events per local calendar day, for the year
// view's heat map. Recurring instances are skipped: daily routines would otherwise
// shade every day and hide the days that actually differ.
export async function busyDays(
  env: Env,
  timeMin: string,
  timeMax: string,
  tz: string,
): Promise<Record<string, number> | null> {
  const token = await googleAccessToken(env);
  if (!token) return null;
  const key = `cache:gcal:days:v2:${timeMin}:${timeMax}:${tz}`;
  const cached = await getJSON<Record<string, number>>(env, key);
  if (cached) return cached;
  const cals = await listCalendars(env);
  if (!cals) return null;

  let fmt: Intl.DateTimeFormat;
  try {
    fmt = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" });
  } catch {
    fmt = new Intl.DateTimeFormat("en-CA", { timeZone: "UTC", year: "numeric", month: "2-digit", day: "2-digit" });
  }
  const params = new URLSearchParams({ timeMin, timeMax, singleEvents: "true", maxResults: "2500" });
  const fields = "nextPageToken,items(status,start,end,recurringEventId)";
  const perCalendar = await Promise.all(
    cals.calendars.map((cal) => pageEvents(token, cal, params, fields, 4).catch(() => [] as GEvent[])),
  );

  const counts: Record<string, number> = {};
  const bump = (d: string) => {
    counts[d] = (counts[d] ?? 0) + 1;
  };
  const DAY_MS = 86_400_000;
  for (const e of perCalendar.flat()) {
    if (e.status === "cancelled" || !e.start || e.recurringEventId) continue;
    if (e.start.date) {
      const start = Date.parse(`${e.start.date}T00:00:00Z`);
      const end = e.end?.date ? Date.parse(`${e.end.date}T00:00:00Z`) : start + DAY_MS;
      for (let t = start, n = 0; t < end && n < 62; t += DAY_MS, n++) bump(new Date(t).toISOString().slice(0, 10));
    } else if (e.start.dateTime) {
      bump(fmt.format(new Date(e.start.dateTime)));
    }
  }
  await putJSON(env, key, counts, DAYS_TTL);
  return counts;
}

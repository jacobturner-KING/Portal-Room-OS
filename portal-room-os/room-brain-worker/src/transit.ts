// Regional transit for the Portal's Transit screen.
//
//   Ferries     WSDOT Ferries API (keyless as of Sept 2026): today's schedule per
//               terminal pair, live vessel positions, drive-up space, and alerts.
//   Water taxi  A passenger-only route via OneBusAway Puget Sound.
//   Buses       Real-time arrivals for the configured stops, via OneBusAway.
//
// Which terminals, stops and routes those are is deliberately not in this file:
// it comes from the TRANSIT_CONFIG value, so a checkout carries nobody's home
// town. See .dev.vars.example for the shape. With none set, /api/transit answers
// `configured: false` and the screen says so rather than showing an empty board.
//
// OneBusAway's documented "TEST" key is used until OBA_API_KEY is set. Everything
// is merged into one /api/transit response, cached for 40 s so the Portal's
// polling never hits the upstreams directly.
import { getJSON, putJSON } from "./kv";

const WSDOT = "https://www.wsdot.wa.gov/ferries/api";
const OBA = "https://api.pugetsound.onebusaway.org/api/where";
const TZ = "America/Los_Angeles";

/** Everything location-specific, parsed from the TRANSIT_CONFIG value. */
export interface TransitConfig {
  /** Screen title, e.g. "Island Transit". */
  title: string;
  /** WSDOT terminal id (as a string key) to the name to show. */
  terminals: Record<string, string>;
  /** Departing terminal pairs, [from, to]; arrivals are these reversed. */
  ferryPairs: Array<[number, number]>;
  /** WSDOT route ids whose service alerts are worth showing. */
  ferryRoutes: number[];
  /** Passenger-only ferry: one OneBusAway route and the stops it calls at. */
  waterTaxi: { route: string; stops: Array<{ id: string; goes: string; from: string }> } | null;
  bus: {
    stops: string[];
    routes: string[];
    /** Fallback names for when OneBusAway is throttled and cannot tell us. */
    names: Record<string, { name: string; direction: string }>;
    /** Dropped from the front of a stop name, e.g. a highway every stop shares. */
    trim: string;
    /** Compass letter to a human heading, e.g. { "N": "toward the ferry" }. */
    headings: Record<string, string>;
    /** Whole-name rewrites, applied first: { match: "Ferry Dock", as: "Ferry dock" }. */
    rename: Array<{ match: string; as: string }>;
  };
}

const NO_TRANSIT: TransitConfig = {
  title: "Transit",
  terminals: {},
  ferryPairs: [],
  ferryRoutes: [],
  waterTaxi: null,
  bus: { stops: [], routes: [], names: {}, trim: "", headings: {}, rename: [] },
};

function transitConfig(env: Env): TransitConfig {
  if (!env.TRANSIT_CONFIG) return NO_TRANSIT;
  try {
    const raw = JSON.parse(env.TRANSIT_CONFIG) as Partial<TransitConfig>;
    return {
      title: raw.title || NO_TRANSIT.title,
      terminals: raw.terminals ?? {},
      ferryPairs: (raw.ferryPairs ?? []).filter(
        (pair) => Array.isArray(pair) && pair.length === 2,
      ) as Array<[number, number]>,
      ferryRoutes: raw.ferryRoutes ?? [],
      waterTaxi: raw.waterTaxi ?? null,
      bus: { ...NO_TRANSIT.bus, ...(raw.bus ?? {}) },
    };
  } catch {
    return NO_TRANSIT;   // a malformed value should not take the dashboard down
  }
}

function configured(cfg: TransitConfig): boolean {
  return cfg.ferryPairs.length > 0 || cfg.bus.stops.length > 0 || cfg.waterTaxi !== null;
}

/** The label the Portal shows above a stop's arrivals, built here so the app
 *  carries no place names of its own. */
function stopLabel(cfg: TransitConfig, name: string, direction: string): string {
  let label = name;
  for (const rule of cfg.bus.rename) {
    if (rule.match && label.includes(rule.match)) { label = rule.as; break; }
  }
  if (label === name && cfg.bus.trim) label = label.replace(cfg.bus.trim, "");
  const heading = cfg.bus.headings[direction.slice(0, 1).toUpperCase()] ?? "";
  return heading ? `${label} · ${heading}` : label;
}

const RESPONSE_TTL = 120; // the Portal polls every 60 s; this halves upstream calls and KV writes
const SCHEDULE_TTL = 6 * 3600;
const ALERTS_TTL = 600;

export interface Sailing {
  from: string;
  to: string;
  departs: string; // ISO, scheduled
  vessel: string;
  notes: string[];
  status: "scheduled" | "at dock" | "underway";
  leftDock: string | null;
  eta: string | null;
  delayMin: number;
  driveUpSpaces: number | null;
}

export interface BusArrival {
  route: string;
  headsign: string;
  scheduled: string;
  predicted: string | null;
  live: boolean;
}

export interface BusStop {
  stopId: string;
  name: string;
  direction: string;
  /** Ready to display; the app does not rework it. */
  label: string;
  arrivals: BusArrival[];
  error: string | null; // set when the live feed could not be reached this round
}

export interface WaterTaxiSailing {
  direction: string;   // e.g. "to Downtown"; comes from the configured stops
  leavesFrom: string;
  scheduled: string;
  predicted: string | null;
}

export interface TransitResponse {
  generatedAt: string;
  /** Screen title from the config, so the app ships no place name. */
  title: string;
  /** False when TRANSIT_CONFIG is unset: the screen says so instead of looking broken. */
  configured: boolean;
  ferries: { departing: Sailing[]; arriving: Sailing[]; alerts: string[] };
  waterTaxi: WaterTaxiSailing[];
  buses: BusStop[];
  errors: string[];
}

// ---- helpers ----

function parseWsdotDate(s: string | null | undefined): string | null {
  if (!s) return null;
  const m = /\/Date\((\d+)/.exec(s);
  return m ? new Date(Number(m[1])).toISOString() : null;
}

function stripHtml(s: string | null | undefined): string {
  return (s ?? "").replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
}

async function fetchJson<T>(url: string, timeoutMs = 9000): Promise<T> {
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs), headers: { accept: "application/json" } });
  if (!res.ok) throw new Error(`${res.status} from ${url.split("?")[0]}`);
  return (await res.json()) as T;
}

async function cached<T>(env: Env, key: string, ttl: number, fn: () => Promise<T>): Promise<T> {
  const hit = await getJSON<T>(env, key);
  if (hit !== null) return hit;
  const value = await fn();
  await putJSON(env, key, value, ttl);
  return value;
}

function localDate(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

// ---- WSDOT ----

interface WsfTime {
  DepartingTime: string;
  VesselName: string;
  AnnotationIndexes?: number[];
}
interface WsfSchedule {
  TerminalCombos?: Array<{ Annotations?: string[]; Times?: WsfTime[] }>;
}
interface ScheduledSailing {
  departs: string;
  vessel: string;
  notes: string[];
}

async function scheduleToday(env: Env, from: number, to: number): Promise<ScheduledSailing[]> {
  return cached(env, `cache:wsf:sched:${from}-${to}:${localDate()}`, SCHEDULE_TTL, async () => {
    const d = await fetchJson<WsfSchedule>(`${WSDOT}/schedule/rest/scheduletoday/${from}/${to}/false`);
    const combo = d.TerminalCombos?.[0];
    if (!combo?.Times) return [];
    const annotations = combo.Annotations ?? [];
    return combo.Times.map((t) => ({
      departs: parseWsdotDate(t.DepartingTime) ?? "",
      vessel: t.VesselName,
      notes: (t.AnnotationIndexes ?? []).map((i) => stripHtml(annotations[i])).filter(Boolean),
    })).filter((s) => s.departs);
  });
}

interface WsfVessel {
  VesselName: string;
  DepartingTerminalID: number;
  ArrivingTerminalID: number;
  AtDock: boolean;
  LeftDock: string | null;
  Eta: string | null;
  ScheduledDeparture: string | null;
  InService: boolean;
}

interface WsfSpaceTerminal {
  TerminalID: number;
  DepartingSpaces?: Array<{
    Departure: string;
    VesselName: string;
    SpaceForArrivalTerminals?: Array<{
      TerminalID?: number;
      ArrivalTerminalIDs?: number[];
      DriveUpSpaceCount?: number;
      DisplayDriveUpSpace?: boolean;
    }>;
  }>;
}

interface WsfAlert {
  AffectedRouteIDs?: number[];
  AlertFullTitle?: string;
  AllRoutesFlag?: boolean;
}

async function wsfAlerts(env: Env, routeIds: Set<number>): Promise<string[]> {
  return cached(env, "cache:wsf:alerts", ALERTS_TTL, async () => {
    const all = await fetchJson<WsfAlert[]>(`${WSDOT}/schedule/rest/alerts`);
    const titles = all
      .filter((a) => !a.AllRoutesFlag && (a.AffectedRouteIDs?.length ?? 99) <= 6)
      .filter((a) => (a.AffectedRouteIDs ?? []).some((id) => routeIds.has(id)))
      .map((a) => stripHtml(a.AlertFullTitle))
      .filter(Boolean);
    return Array.from(new Set(titles)).slice(0, 4);
  });
}

function driveUpFor(spaces: WsfSpaceTerminal[], from: number, to: number, departsMs: number): number | null {
  const term = spaces.find((t) => t.TerminalID === from);
  for (const dep of term?.DepartingSpaces ?? []) {
    const t = parseWsdotDate(dep.Departure);
    if (!t || Math.abs(Date.parse(t) - departsMs) > 60_000) continue;
    for (const sa of dep.SpaceForArrivalTerminals ?? []) {
      const ids = sa.ArrivalTerminalIDs ?? (sa.TerminalID !== undefined ? [sa.TerminalID] : []);
      if (ids.includes(to) && sa.DisplayDriveUpSpace !== false && typeof sa.DriveUpSpaceCount === "number") {
        return sa.DriveUpSpaceCount;
      }
    }
  }
  return null;
}

function buildSailings(
  pairs: Array<[number, number]>,
  schedules: ScheduledSailing[][],
  vessels: WsfVessel[],
  spaces: WsfSpaceTerminal[],
  now: number,
  names: Record<string, string>,
): Sailing[] {
  const out: Sailing[] = [];
  pairs.forEach(([from, to], i) => {
    for (const s of schedules[i] ?? []) {
      const dep = Date.parse(s.departs);
      const vessel = vessels.find((v) => {
        if (!v.InService || v.DepartingTerminalID !== from || v.ArrivingTerminalID !== to) return false;
        const sched = parseWsdotDate(v.ScheduledDeparture);
        return sched !== null && Math.abs(Date.parse(sched) - dep) < 60_000;
      });
      const leftDock = vessel ? parseWsdotDate(vessel.LeftDock) : null;
      const eta = vessel ? parseWsdotDate(vessel.Eta) : null;
      const underway = Boolean(vessel && !vessel.AtDock && leftDock);
      // Keep upcoming sailings, plus a boat that is still on the water toward its dock.
      const relevant = dep >= now - 5 * 60_000 || (underway && eta !== null && Date.parse(eta) >= now - 2 * 60_000);
      if (!relevant) continue;
      let status: Sailing["status"] = "scheduled";
      let delayMin = 0;
      if (vessel) {
        if (underway) {
          status = "underway";
          delayMin = Math.max(0, Math.round((Date.parse(leftDock as string) - dep) / 60_000));
        } else if (vessel.AtDock) {
          status = "at dock";
          delayMin = Math.max(0, Math.round((now - dep) / 60_000));
        }
      }
      out.push({
        from: names[String(from)] ?? String(from),
        to: names[String(to)] ?? String(to),
        departs: s.departs,
        vessel: s.vessel,
        notes: s.notes,
        status,
        leftDock,
        eta,
        delayMin,
        driveUpSpaces: driveUpFor(spaces, from, to, dep),
      });
    }
  });
  out.sort((a, b) => Date.parse(a.departs) - Date.parse(b.departs));
  return out.slice(0, 9);
}

// ---- OneBusAway ----

interface ObaArrival {
  routeShortName?: string;
  tripHeadsign?: string;
  scheduledDepartureTime: number;
  predictedDepartureTime?: number;
  predicted?: boolean;
}
interface ObaStopRef {
  id: string;
  name?: string;
  direction?: string;
}
interface ObaArrivalsResponse {
  data?: {
    entry?: { stopId?: string; arrivalsAndDepartures?: ObaArrival[] };
    references?: { stops?: ObaStopRef[] };
  };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// The documented TEST key throttles bursts; one polite retry covers most 429s.
async function obaFetch<T>(url: string): Promise<T> {
  try {
    return await fetchJson<T>(url);
  } catch (err) {
    if (!String(err).startsWith("429")) throw err;
    await sleep(600);
    return await fetchJson<T>(url);
  }
}

// Run OBA calls one at a time with a short gap instead of a burst.
async function sequential<T>(tasks: Array<() => Promise<T>>): Promise<T[]> {
  const out: T[] = [];
  for (let i = 0; i < tasks.length; i++) {
    if (i > 0) await sleep(150);
    out.push(await tasks[i]());
  }
  return out;
}

async function obaArrivals(env: Env, stopId: string, minutesAfter: number): Promise<{ stop: ObaStopRef; arrivals: ObaArrival[] }> {
  const key = env.OBA_API_KEY || "TEST";
  const url = `${OBA}/arrivals-and-departures-for-stop/${encodeURIComponent(stopId)}.json?key=${encodeURIComponent(key)}&minutesBefore=2&minutesAfter=${minutesAfter}`;
  const d = await obaFetch<ObaArrivalsResponse>(url);
  const stop = d.data?.references?.stops?.find((s) => s.id === stopId) ?? { id: stopId };
  return { stop, arrivals: d.data?.entry?.arrivalsAndDepartures ?? [] };
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

// Water taxi: King County publishes no real-time for it, so use the day's timetable
// from OBA (one call per stop per day, cached) instead of live arrivals.
interface ObaScheduleResponse {
  data?: {
    entry?: {
      stopRouteSchedules?: Array<{
        routeId?: string;
        stopRouteDirectionSchedules?: Array<{
          tripHeadsign?: string;
          scheduleStopTimes?: Array<{ departureTime?: number; arrivalTime?: number }>;
        }>;
      }>;
    };
  };
}

async function waterTaxiTimetable(env: Env, routeId: string, stopId: string, headsignHas: string): Promise<string[]> {
  const day = localDate();
  return cached(env, `cache:oba:sched:${stopId}:${day}`, 12 * 3600, async () => {
    const key = env.OBA_API_KEY || "TEST";
    const url = `${OBA}/schedule-for-stop/${encodeURIComponent(stopId)}.json?key=${encodeURIComponent(key)}&date=${day}`;
    const d = await obaFetch<ObaScheduleResponse>(url);
    const out: string[] = [];
    for (const rs of d.data?.entry?.stopRouteSchedules ?? []) {
      if (rs.routeId && routeId && rs.routeId !== routeId) continue;
      for (const ds of rs.stopRouteDirectionSchedules ?? []) {
        if (!(ds.tripHeadsign ?? "").toLowerCase().includes(headsignHas.toLowerCase())) continue;
        for (const t of ds.scheduleStopTimes ?? []) {
          const ms = t.departureTime ?? t.arrivalTime;
          if (ms) out.push(iso(ms));
        }
      }
    }
    return out.sort();
  });
}

function toBusArrival(a: ObaArrival): BusArrival {
  const predicted = a.predictedDepartureTime && a.predictedDepartureTime > 0 ? a.predictedDepartureTime : 0;
  return {
    route: a.routeShortName ?? "",
    headsign: a.tripHeadsign ?? "",
    scheduled: iso(a.scheduledDepartureTime),
    predicted: predicted ? iso(predicted) : null,
    live: Boolean(predicted),
  };
}

function arrivalTime(a: BusArrival): number {
  return Date.parse(a.predicted ?? a.scheduled);
}

// ---- compose ----

export async function getTransit(env: Env): Promise<TransitResponse> {
  const hit = await getJSON<TransitResponse>(env, "cache:transit:v1");
  if (hit) return hit;

  const now = Date.now();
  const errors: string[] = [];
  const note = (what: string) => (err: unknown) => {
    errors.push(`${what}: ${String(err).slice(0, 120)}`);
    return null;
  };

  const cfg = transitConfig(env);
  if (!configured(cfg)) {
    return {
      generatedAt: new Date(now).toISOString(),
      title: cfg.title,
      configured: false,
      ferries: { departing: [], arriving: [], alerts: [] },
      waterTaxi: [],
      buses: [],
      errors: [],
    };
  }
  const busStops = cfg.bus.stops;
  const busRoutes = new Set(cfg.bus.routes);
  const pairsDeparting = cfg.ferryPairs;
  const pairsArriving = cfg.ferryPairs.map(([f, t]) => [t, f] as [number, number]);
  const taxiStops = cfg.waterTaxi?.stops ?? [];

  // WSDOT calls fan out in parallel; OneBusAway calls run one at a time (see obaFetch).
  const obaTasks = sequential<unknown>([
    ...busStops.map((id) => () => obaArrivals(env, id, 180).catch(note(`bus stop ${id}`))),
    ...taxiStops.map((stop) => () =>
      waterTaxiTimetable(env, cfg.waterTaxi?.route ?? "", stop.id, stop.goes)
        .catch(note(`water taxi (${stop.from})`))),
  ]);
  const [depSchedules, arrSchedules, vessels, spaces, alerts, obaResults] = await Promise.all([
    Promise.all(pairsDeparting.map(([f, t]) => scheduleToday(env, f, t).catch(note(`schedule ${f}-${t}`)))),
    Promise.all(pairsArriving.map(([f, t]) => scheduleToday(env, f, t).catch(note(`schedule ${f}-${t}`)))),
    fetchJson<WsfVessel[]>(`${WSDOT}/vessels/rest/vessellocations`).catch(note("vessels")),
    fetchJson<WsfSpaceTerminal[]>(`${WSDOT}/terminals/rest/terminalsailingspace`).catch(note("sailing space")),
    wsfAlerts(env, new Set(cfg.ferryRoutes)).catch(note("alerts")),
    obaTasks,
  ]);
  const busResults = obaResults.slice(0, busStops.length) as Array<{ stop: ObaStopRef; arrivals: ObaArrival[] } | null>;
  const taxiResults = obaResults.slice(busStops.length) as Array<string[] | null>;

  const ferries = {
    departing: buildSailings(pairsDeparting, depSchedules.map((s) => s ?? []), vessels ?? [], spaces ?? [], now, cfg.terminals),
    arriving: buildSailings(pairsArriving, arrSchedules.map((s) => s ?? []), vessels ?? [], spaces ?? [], now, cfg.terminals),
    alerts: alerts ?? [],
  };

  const buses: BusStop[] = busResults.map((r, i) => {
    const stopId = busStops[i];
    const known = cfg.bus.names[stopId];
    if (!r) {
      const err = errors.find((e) => e.startsWith(`bus stop ${stopId}`)) ?? "unavailable";
      const name = known?.name ?? stopId;
      const direction = known?.direction ?? "";
      return { stopId, name, direction, label: stopLabel(cfg, name, direction), arrivals: [], error: err };
    }
    const arrivals = r.arrivals
      .map(toBusArrival)
      .filter((a) => busRoutes.size === 0 || busRoutes.has(a.route))
      .filter((a) => arrivalTime(a) >= now - 2 * 60_000)
      .sort((a, b) => arrivalTime(a) - arrivalTime(b))
      .slice(0, 6);
    const name = r.stop.name ?? known?.name ?? stopId;
    const direction = r.stop.direction ?? known?.direction ?? "";
    return { stopId, name, direction, label: stopLabel(cfg, name, direction), arrivals, error: null };
  });

  const waterTaxi: WaterTaxiSailing[] = [];
  const pushTaxi = (times: string[] | null, direction: string, leavesFrom: string) => {
    for (const t of (times ?? []).filter((t) => Date.parse(t) >= now - 2 * 60_000).slice(0, 3)) {
      waterTaxi.push({ direction, leavesFrom, scheduled: t, predicted: null });
    }
  };
  taxiStops.forEach((stop, i) => pushTaxi(taxiResults[i], `to ${stop.goes}`, stop.from));
  waterTaxi.sort((a, b) => Date.parse(a.scheduled) - Date.parse(b.scheduled));

  const response: TransitResponse = {
    generatedAt: new Date(now).toISOString(),
    title: cfg.title,
    configured: true,
    ferries,
    waterTaxi,
    buses,
    errors,
  };
  await putJSON(env, "cache:transit:v1", response, RESPONSE_TTL);
  return response;
}

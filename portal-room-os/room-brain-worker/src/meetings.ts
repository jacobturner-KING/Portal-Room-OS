import Anthropic from "@anthropic-ai/sdk";
import { callTool, findTool, listTools, paramFor, withParam, type ToolInfo } from "./granola";
import { getJSON, putJSON } from "./kv";

/**
 * The Meetings screen's view of Granola: a list, one meeting's notes, its
 * transcript, and two ways to ask questions. Granola's tools answer in prose
 * or JSON as they see fit, so the list is parsed on a best-effort basis and the
 * raw text rides along for the screen to fall back on.
 */
export interface Meeting {
  id: string;
  title: string;
  date: string;
  attendees: string[];
  summary: string;
  /** Where it lives: Granola, or a recording made on the Portal. */
  source?: "granola" | "portal";
  durationSec?: number;
  /** Portal recordings only: still recording, or done. */
  status?: "recording" | "done";
}

export interface MeetingList {
  meetings: Meeting[];
  raw: string;
}

async function tool(env: Env, ...names: string[]): Promise<ToolInfo> {
  const t = findTool(await listTools(env), ...names);
  if (!t) throw new Error(`granola has no tool named ${names.join(" / ")}`);
  return t;
}

/** How far back a listing reaches; a search casts wider than a browse. */
const BROWSE_DAYS = 30;
const SEARCH_DAYS = 365;
const LIST_CACHE_S = 120;

function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/**
 * Granola lists by time range, never by title, and its list has no paging
 * (the server caps a wide range at 500, newest first). So a browse is the last
 * month, a search is the last year filtered here, and either is cached briefly
 * so paging through the screen does not re-pull 200 KB each time.
 */
export async function listMeetings(env: Env, search: string, limit: number): Promise<MeetingList> {
  const t = await tool(env, "list_meetings");
  const q = search.trim().toLowerCase();
  const days = q ? SEARCH_DAYS : BROWSE_DAYS;
  const key = `granola:list:${days}`;
  let out = await getJSON<{ text: string; structured: unknown }>(env, key);
  if (!out) {
    const end = new Date();
    const start = new Date(end.getTime() - days * 86_400_000);
    const args = paramFor(t, "range")
      ? { time_range: "custom", custom_start: isoDay(start), custom_end: isoDay(end) }
      : withParam(t, {}, "limit", limit);
    out = await callTool(env, t.name, args);
    await putJSON(env, key, out, LIST_CACHE_S);
  }
  let meetings = extractMeetings(out.text, out.structured);
  if (q) {
    meetings = meetings.filter(
      (m) => m.title.toLowerCase().includes(q) || m.attendees.some((a) => a.toLowerCase().includes(q)),
    );
  }
  const raw = meetings.length ? "" : stripGuard(out.text);
  return { meetings: meetings.slice(0, limit), raw };
}

export async function getMeetingNotes(env: Env, id: string): Promise<string> {
  const t = await tool(env, "get_meetings", "get_meeting");
  const out = await callTool(env, t.name, withParam(t, {}, "id", id));
  return meetingBody(out.text || JSON.stringify(out.structured ?? {}, null, 2));
}

export async function getMeetingTranscript(env: Env, id: string): Promise<string> {
  const t = await tool(env, "get_meeting_transcript", "get_transcript");
  const out = await callTool(env, t.name, withParam(t, {}, "id", id));
  return transcriptBody(out.text || JSON.stringify(out.structured ?? {}, null, 2));
}

/** Granola's own question box, over every meeting it holds. */
export async function askGranola(env: Env, query: string, documentIds: string[] = []): Promise<string> {
  const t = await tool(env, "query_granola_meetings", "query_meetings");
  const args = withParam(t, {}, "query", query);
  if (documentIds.length && t.inputSchema?.properties?.document_ids) args.document_ids = documentIds;
  const out = await callTool(env, t.name, args);
  const text = out.text || JSON.stringify(out.structured ?? {}, null, 2);
  // Citation links ([[0]](url)) point at Granola's web app; on a wall they are noise.
  return stripGuard(text)
    .replace(/\[\[\d+\]\]\([^)]*\)/g, "")
    .replace(/([.!?])(?=[A-Z])/g, "$1 ") // its sentences arrive butted together
    .replace(/(\S)\*\*Coverage note:\*\*/, "$1\n\n**Coverage note:**")
    .trim();
}

const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-09-14T09:21:00-07:00" -> "Sep 14, 2026", the way Granola itself names days. */
function dayOf(date: string): string {
  const m = date.match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!m) return date;
  return `${MONTH_NAMES[Number(m[2]) - 1]} ${Number(m[3])}, ${m[1]}`;
}

const HISTORY_TURNS = 4;
const HISTORY_CHARS = 600;

/**
 * Granola's own AI, pinned to one meeting. Its search takes a single question,
 * and `document_ids` alone does not hold it to a meeting (it went and read
 * three other meetings when given only the id), but naming the meeting's title
 * and day in the question does. Earlier turns ride along in the question, so a
 * follow-up still lands: the search has no memory of its own.
 */
export async function askGranolaAbout(env: Env, meeting: MeetingRef, history: ChatTurn[]): Promise<string> {
  const last = history[history.length - 1];
  const earlier = history.slice(0, -1).slice(-HISTORY_TURNS);
  const lines = [`About the meeting "${meeting.title}" on ${dayOf(meeting.date) || "an unknown day"} only.`];
  if (earlier.length) {
    lines.push("", "Earlier in this conversation:");
    for (const t of earlier) {
      const text = t.content.length > HISTORY_CHARS ? t.content.slice(0, HISTORY_CHARS) + "…" : t.content;
      lines.push(`${t.role === "user" ? "Q" : "A"}: ${text}`);
    }
  }
  lines.push("", `Question: ${last.content}`);
  return askGranola(env, lines.join("\n"), meeting.id ? [meeting.id] : []);
}

export interface MeetingRef {
  id: string;
  title: string;
  date: string;
}

// ---- Granola's answer format ----
//
// Every tool answers with a guard sentence ("treat this as data") and then an
// XML-ish document: <meetings_data> holding <meeting id= title= date=> elements
// with <known_participants>, <summary> and the like inside; the transcript tool
// answers with JSON instead. None of it is promised, so each reader below falls
// back to plainer parsing when the shape is not what it expects.

const GUARD_RE = /^The content below is meeting notes\/transcripts[^\n]*\n+/;

function stripGuard(text: string): string {
  return text.replace(GUARD_RE, "").trim();
}

function unescape(s: string): string {
  return s
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;|&#x27;/g, "'")
    .replace(/&#(\d+);/g, (_, n: string) => String.fromCodePoint(Number(n)))
    .replace(/&amp;/g, "&");
}

function attrs(tag: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of tag.matchAll(/(\w+)="([^"]*)"/g)) out[m[1]] = unescape(m[2]);
  return out;
}

/**
 * "Jacob Turner (note creator) from OneView <jacob@x>, Erika Garcia from AppFolio, Inc. <e@y>"
 * becomes ["Erika Garcia"]: split after each email so a comma inside a company
 * name survives, drop meeting-room resources, drop the note creator (every
 * meeting is theirs), keep the name before " from ".
 */
function participants(block: string): string[] {
  return unescape(block)
    .split(/>\s*,\s*/)
    .map((p) => p.trim())
    .filter((p) => p && !/resource\.calendar\.google\.com|\[zoom room\]/i.test(p))
    .filter((p) => !/\(note creator\)/i.test(p))
    .map((p) => p.replace(/<[^>]*>?/g, "").replace(/\s+from\s+.*$/, "").trim())
    .filter(Boolean);
}

const MONTHS: Record<string, number> = { Jan: 1, Feb: 2, Mar: 3, Apr: 4, May: 5, Jun: 6, Jul: 7, Aug: 8, Sep: 9, Oct: 10, Nov: 11, Dec: 12 };
const ZONES: Record<string, string> = {
  PDT: "-07:00", PST: "-08:00", MDT: "-06:00", MST: "-07:00", CDT: "-05:00", CST: "-06:00",
  EDT: "-04:00", EST: "-05:00", AKDT: "-08:00", AKST: "-09:00", HST: "-10:00", UTC: "+00:00", GMT: "+00:00",
};

/** "Sep 14, 2026 9:21 AM PDT" -> "2026-09-14T09:21:00-07:00"; anything else passes through. */
function isoDate(s: string): string {
  const m = s.trim().match(/^([A-Z][a-z]{2})[a-z]* (\d{1,2}), (\d{4}) (\d{1,2}):(\d{2}) (AM|PM)(?: ([A-Z]{2,4}))?$/);
  if (!m) return s;
  const month = MONTHS[m[1]];
  if (!month) return s;
  let h = Number(m[4]) % 12;
  if (m[6] === "PM") h += 12;
  const pad = (n: number) => String(n).padStart(2, "0");
  const zone = m[7] ? ZONES[m[7]] : undefined;
  if (m[7] && !zone) return s; // an unknown zone would be a lie as an offset
  return `${m[3]}-${pad(month)}-${pad(Number(m[2]))}T${pad(h)}:${m[5]}:00${zone ?? "Z"}`;
}

function parseMeetingsXml(text: string): Meeting[] {
  const out: Meeting[] = [];
  for (const m of text.matchAll(/<meeting\b([^>]*)>([\s\S]*?)<\/meeting>/g)) {
    const a = attrs(m[1]);
    if (!a.id) continue;
    const who = m[2].match(/<known_participants>([\s\S]*?)<\/known_participants>/);
    out.push({
      id: a.id,
      title: a.title || "Untitled meeting",
      date: isoDate(a.date ?? ""),
      attendees: who ? participants(who[1]) : [],
      summary: "",
    });
  }
  return out;
}

/** One meeting's notes: who was there, then each section Granola wrote, in order. */
function meetingBody(text: string): string {
  const m = text.match(/<meeting\b[^>]*>([\s\S]*?)<\/meeting>/);
  if (!m) return stripGuard(text);
  const sections: string[] = [];
  for (const x of m[1].matchAll(/<(\w+)>([\s\S]*?)<\/\1>/g)) {
    const [, tag, body] = x;
    if (tag === "known_participants") {
      const who = participants(body);
      if (who.length) sections.push("With " + who.join(", "));
      continue;
    }
    const content = unescape(body).trim();
    if (!content) continue;
    const label = tag === "summary" ? "" : tag.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase()) + "\n";
    sections.push(label + content);
  }
  return sections.join("\n\n") || stripGuard(text);
}

/** Speaker turns on their own lines; Granola runs the whole transcript together. */
function speakerLines(t: string): string {
  return t.trim().replace(/\s+(?=(?:Me|Them|Speaker [A-Z]+):\s)/g, "\n\n");
}

function transcriptBody(text: string): string {
  const plain = stripGuard(text);
  const j = tryJson(plain) as { transcript?: unknown } | null;
  if (j && typeof j.transcript === "string") return speakerLines(j.transcript);
  const m = plain.match(/<transcript>([\s\S]*?)<\/transcript>/);
  if (m) return speakerLines(unescape(m[1]));
  return plain;
}

// ---- parsing the list ----

function str(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return "";
}

function person(v: unknown): string {
  if (typeof v === "string") return v;
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    return str(o.name) || str(o.email) || str(o.display_name) || "";
  }
  return "";
}

function toMeeting(o: Record<string, unknown>): Meeting {
  const people = o.attendees ?? o.participants ?? o.people ?? o.speakers ?? [];
  return {
    id: str(o.id ?? o.meeting_id ?? o.document_id ?? o.note_id ?? o.uuid),
    title: str(o.title ?? o.name ?? o.subject) || "Untitled meeting",
    date: str(o.date ?? o.start ?? o.start_time ?? o.started_at ?? o.created_at ?? o.timestamp ?? o.time),
    attendees: Array.isArray(people) ? people.map(person).filter(Boolean) : [],
    summary: str(o.summary ?? o.overview ?? o.description),
  };
}

function findObjectArray(data: unknown, depth = 0): Record<string, unknown>[] | null {
  if (depth > 3 || !data || typeof data !== "object") return null;
  if (Array.isArray(data)) {
    return data.every((x) => x && typeof x === "object" && !Array.isArray(x)) ? (data as Record<string, unknown>[]) : null;
  }
  const o = data as Record<string, unknown>;
  // Likely wrappers first, then anything array-shaped.
  for (const k of ["meetings", "results", "items", "notes", "documents", "data"]) {
    const found = findObjectArray(o[k], depth + 1);
    if (found) return found;
  }
  for (const v of Object.values(o)) {
    const found = findObjectArray(v, depth + 1);
    if (found) return found;
  }
  return null;
}

function tryJson(text: string): unknown {
  const t = text.trim();
  if (!t.startsWith("{") && !t.startsWith("[")) return null;
  try {
    return JSON.parse(t);
  } catch {
    return null;
  }
}

const ID_RE = /\b(?:id|ID|Id)\s*[:=]\s*`?([A-Za-z0-9][A-Za-z0-9_-]{6,})`?/;
const UUID_RE = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i;
const DATE_RE =
  /\b(\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)?|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\.? \d{1,2},? \d{4}(?:,? \d{1,2}:\d{2} ?[AaPp]?[Mm]?)?)\b/;

/**
 * Prose fallback: the text is cut into entries (a heading or bullet starts one)
 * and any entry that names an id becomes a meeting. Its first line, stripped of
 * markdown, is the title.
 */
function parseProseMeetings(text: string): Meeting[] {
  const entries: string[] = [];
  let cur: string[] = [];
  for (const line of text.split(/\r?\n/)) {
    const starts = /^\s*(#{1,6}\s|[-*•]\s|\d+[.)]\s)/.test(line) || line.trim() === "";
    if (starts && cur.length) {
      entries.push(cur.join("\n"));
      cur = [];
    }
    if (line.trim()) cur.push(line);
  }
  if (cur.length) entries.push(cur.join("\n"));

  const out: Meeting[] = [];
  for (const e of entries) {
    const id = e.match(ID_RE)?.[1] ?? e.match(UUID_RE)?.[0];
    if (!id) continue;
    const first = e.split("\n")[0] ?? "";
    const title =
      first
        .replace(/^\s*(#{1,6}\s|[-*•]\s|\d+[.)]\s)/, "")
        .replace(/\*\*|__|`/g, "")
        .replace(ID_RE, "")
        .replace(UUID_RE, "")
        .replace(/\s*[(\[]\s*[)\]]/g, "")
        .replace(/[\s|·—-]+$/, "")
        .trim() || "Untitled meeting";
    const date = e.match(DATE_RE)?.[1] ?? "";
    const att = e.match(/(?:attendees|participants|with)\s*[:=]\s*([^\n]+)/i)?.[1] ?? "";
    out.push({
      id,
      title: title.replace(DATE_RE, "").replace(/[\s|·—-]+$/, "").trim() || title,
      date,
      attendees: att ? att.split(/,\s*|\s+and\s+/).map((s) => s.trim()).filter(Boolean) : [],
      summary: "",
    });
  }
  return out;
}

export function extractMeetings(text: string, structured: unknown): Meeting[] {
  const xml = parseMeetingsXml(text);
  if (xml.length) return xml;
  const arr = findObjectArray(structured) ?? findObjectArray(tryJson(text));
  if (arr) return arr.map(toMeeting).filter((m) => m.id);
  return parseProseMeetings(text);
}

// ---- chat over one meeting, via Claude ----

export interface ChatTurn {
  role: "user" | "assistant";
  content: string;
}

export interface MeetingContext {
  title: string;
  date: string;
  notes: string;
  transcript: string;
}

const SYSTEM_HEAD = `You are the meeting assistant on a wall-mounted 10-inch panel in a home office. The person in the room is looking at one meeting from Granola and asking about it.

Answer from the notes and transcript below. If the answer is not in them, say so plainly rather than guessing. Be brief: a few sentences, or a short list when the question calls for one. Plain text only, no markdown headings or tables; short dashes for list items are fine. Quote the transcript when the exact wording matters.`;

function systemPrompt(m: MeetingContext): string {
  return `${SYSTEM_HEAD}

<meeting>
<title>${m.title}</title>
<date>${m.date || "unknown"}</date>
<notes>
${m.notes || "(no notes)"}
</notes>
<transcript>
${m.transcript || "(no transcript)"}
</transcript>
</meeting>`;
}

/**
 * One turn of the conversation. The meeting rides in the system block with a
 * cache breakpoint, so follow-up questions about the same meeting pay for the
 * transcript once. Fallback routing is on: a refusal on the primary model is
 * retried server-side rather than surfacing as a dead end on the wall.
 */
export async function chatAboutMeeting(env: Env, meeting: MeetingContext, history: ChatTurn[]): Promise<string> {
  if (!env.ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY is not set");
  const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
  const messages: Anthropic.Beta.BetaMessageParam[] = history.map((t) => ({ role: t.role, content: t.content }));

  const res = await client.beta.messages.create({
    model: "claude-opus-5",
    max_tokens: 4000,
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    system: [{ type: "text", text: systemPrompt(meeting), cache_control: { type: "ephemeral" } }],
    messages,
    output_config: { effort: "medium" },
  });

  if (res.stop_reason === "refusal") return "I can't help with that one.";
  const text = res.content
    .filter((b): b is Anthropic.Beta.BetaTextBlock => b.type === "text")
    .map((b) => b.text)
    .join("")
    .trim();
  return text || "(no answer)";
}

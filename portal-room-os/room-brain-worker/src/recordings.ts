import Anthropic from "@anthropic-ai/sdk";
import { del, getJSON, putJSON } from "./kv";
import type { ChatTurn, Meeting } from "./meetings";

/**
 * Recordings made on the Portal itself: the Record chip on the dashboard, the
 * panel's own microphones. The app streams the audio up in short WAV chunks
 * while it records; each chunk is transcribed here the moment it lands
 * (Workers AI Whisper), so the transcript is nearly complete by the time the
 * chip is tapped again. Finishing stitches the segments together and writes a
 * title and a short summary, and from then on the recording sits in the
 * Meetings list next to the Granola ones.
 *
 * Audio is not kept. Only the transcript is, in KV, under a client-made id so
 * recording never waits on a round trip to start.
 */
export const REC_PREFIX = "rec_";
const INDEX_KEY = "rec:index";
const INDEX_MAX = 500;
const ID_RE = /^rec_[0-9a-z]{6,40}$/;
const WHISPER = "@cf/openai/whisper-large-v3-turbo";
const LLAMA = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
/** Roughly what the Llama context holds after the prompt; beyond it, the tail wins. */
const LLAMA_TRANSCRIPT_CHARS = 60_000;

export type RecordingStatus = "recording" | "done";

export interface Recording {
  id: string;
  startedAt: string; // ISO
  endedAt?: string;
  title: string;
  status: RecordingStatus;
  /** Chunks received so far; segments are numbered from 0. */
  chunks: number;
  durationSec: number;
  summary?: string;
}

interface Segment {
  text: string;
  durationSec: number;
}

export function isRecordingId(id: string): boolean {
  return ID_RE.test(id);
}

function key(id: string): string {
  return `rec:${id}`;
}

async function loadIndex(env: Env): Promise<string[]> {
  return (await getJSON<string[]>(env, INDEX_KEY)) ?? [];
}

async function ensure(env: Env, id: string, startedAt?: string): Promise<Recording> {
  const existing = await getJSON<Recording>(env, key(id));
  if (existing) return existing;
  const rec: Recording = {
    id,
    startedAt: startedAt && !Number.isNaN(Date.parse(startedAt)) ? startedAt : new Date().toISOString(),
    title: "Recording",
    status: "recording",
    chunks: 0,
    durationSec: 0,
  };
  await putJSON(env, key(id), rec);
  const index = await loadIndex(env);
  if (!index.includes(id)) await putJSON(env, INDEX_KEY, [id, ...index].slice(0, INDEX_MAX));
  return rec;
}

export async function getRecording(env: Env, id: string): Promise<Recording | null> {
  return await getJSON<Recording>(env, key(id));
}

function base64(bytes: ArrayBuffer): string {
  const u8 = new Uint8Array(bytes);
  let s = "";
  const step = 0x8000; // String.fromCharCode caps its argument count
  for (let i = 0; i < u8.length; i += step) s += String.fromCharCode(...u8.subarray(i, i + step));
  return btoa(s);
}

/** PCM seconds in a 16-bit mono 16 kHz WAV, from its byte length. */
function wavSeconds(bytes: number): number {
  return Math.max(0, bytes - 44) / (16_000 * 2);
}

/**
 * One chunk of audio in, its text out and stored. Idempotent by sequence
 * number, so the app can retry a chunk the network dropped. The previous
 * segment's tail is given to Whisper as context, which keeps names spelled the
 * same way across chunk boundaries.
 */
export async function addChunk(
  env: Env,
  id: string,
  seq: number,
  startedAt: string | undefined,
  wav: ArrayBuffer,
): Promise<{ text: string; durationSec: number }> {
  if (!env.AI) throw new Error("Workers AI binding missing; add \"ai\" to wrangler config");
  const rec = await ensure(env, id, startedAt);
  const segKey = `${key(id)}:seg:${seq}`;
  const prior = seq > 0 ? await getJSON<Segment>(env, `${key(id)}:seg:${seq - 1}`) : null;

  const out = await env.AI.run(WHISPER, {
    audio: base64(wav),
    task: "transcribe",
    initial_prompt: prior?.text.slice(-200) || undefined,
  });
  const seg: Segment = {
    text: (out.text ?? "").trim(),
    durationSec: out.transcription_info?.duration ?? wavSeconds(wav.byteLength),
  };
  await putJSON(env, segKey, seg);

  const fresh = (await getJSON<Recording>(env, key(id))) ?? rec;
  if (seq + 1 > fresh.chunks) fresh.chunks = seq + 1;
  await putJSON(env, key(id), fresh);
  return seg;
}

async function segments(env: Env, rec: Recording): Promise<Segment[]> {
  const out: Segment[] = [];
  for (let i = 0; i < rec.chunks; i++) {
    const s = await getJSON<Segment>(env, `${key(rec.id)}:seg:${i}`);
    if (s) out.push(s);
  }
  return out;
}

/** The recording's transcript as one text, from whatever chunks have landed. */
export async function getTranscript(env: Env, id: string): Promise<string> {
  const rec = await getRecording(env, id);
  if (!rec) return "";
  const done = await getJSON<{ text: string }>(env, `${key(id)}:transcript`);
  if (done) return done.text;
  return (await segments(env, rec)).map((s) => s.text).filter(Boolean).join("\n\n");
}

/**
 * Stops the clock, stitches the transcript, and names the recording. The
 * title and summary come from Claude when the Worker has a key, Workers AI
 * otherwise; with no transcript at all (silence, or every chunk lost), the
 * recording keeps its plain name so it still shows up and can be deleted.
 */
export async function finishRecording(env: Env, id: string, durationSec: number | undefined): Promise<Recording> {
  const rec = await ensure(env, id);
  const segs = await segments(env, rec);
  const text = segs.map((s) => s.text).filter(Boolean).join("\n\n");
  rec.endedAt = new Date().toISOString();
  rec.durationSec = durationSec ?? segs.reduce((n, s) => n + s.durationSec, 0);
  rec.status = "done";
  await putJSON(env, `${key(id)}:transcript`, { text });

  if (text.trim()) {
    try {
      const named = await nameRecording(env, text, rec.startedAt);
      rec.title = named.title || rec.title;
      rec.summary = named.summary;
    } catch (err) {
      console.log(JSON.stringify({ level: "warn", msg: "recording_naming_failed", error: String(err) }));
    }
  }
  if (rec.title === "Recording") rec.title = `Recording · ${prettyStart(rec.startedAt)}`;
  await putJSON(env, key(id), rec);
  return rec;
}

export async function deleteRecording(env: Env, id: string): Promise<void> {
  const rec = await getRecording(env, id);
  if (rec) for (let i = 0; i < rec.chunks; i++) await del(env, `${key(id)}:seg:${i}`);
  await del(env, `${key(id)}:transcript`);
  await del(env, key(id));
  await putJSON(env, INDEX_KEY, (await loadIndex(env)).filter((x) => x !== id));
}

function prettyLength(sec: number): string {
  if (sec < 60) return `${Math.round(sec)} s`;
  return `${Math.round(sec / 60)} min`;
}

function prettyStart(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    timeZone: "America/Los_Angeles",
  });
}

/** Every recording, newest first, shaped like the Granola meetings for one list. */
export async function listRecordings(env: Env): Promise<Meeting[]> {
  const index = await loadIndex(env);
  const recs = await Promise.all(index.map((id) => getRecording(env, id)));
  return recs
    .filter((r): r is Recording => r !== null)
    .map((r) => ({
      id: r.id,
      title: r.status === "recording" ? `Recording now · ${prettyStart(r.startedAt)}` : r.title,
      date: r.startedAt,
      attendees: [],
      summary: r.summary ?? "",
      source: "portal" as const,
      durationSec: r.durationSec,
      status: r.status,
    }))
    .sort((a, b) => b.date.localeCompare(a.date));
}

/** What the Notes tab shows for a recording. */
export async function getNotes(env: Env, id: string): Promise<string> {
  const rec = await getRecording(env, id);
  if (!rec) return "";
  const head = `Recorded on the Portal · ${prettyStart(rec.startedAt)} · ${prettyLength(rec.durationSec)}`;
  if (rec.status === "recording") return `${head}\n\nStill recording. The transcript grows as it goes.`;
  return rec.summary ? `${head}\n\n${rec.summary}` : `${head}\n\nNo summary: nothing was heard.`;
}

// ---- the language models ----

const NAMING_SYSTEM = `You name recordings of conversations from a home office. Given a transcript, answer with JSON only: {"title": ..., "summary": ...}. The title is specific, at most eight words, naming the topic, never "meeting" or "conversation". The summary is three to six short lines, each starting with "- ", covering what was discussed and any decisions or next steps. Plain text inside the strings, no markdown headings.`;

const NAMING_SCHEMA = {
  type: "object",
  properties: { title: { type: "string" }, summary: { type: "string" } },
  required: ["title", "summary"],
};

async function nameRecording(env: Env, transcript: string, startedAt: string): Promise<{ title: string; summary: string }> {
  const user = `Recorded ${prettyStart(startedAt)}.\n\n<transcript>\n${transcript}\n</transcript>`;
  let raw: string;
  if (env.ANTHROPIC_API_KEY) {
    raw = await askClaude(env, NAMING_SYSTEM, [{ role: "user", content: user }]);
  } else {
    if (!env.AI) throw new Error("Workers AI binding missing; add \"ai\" to wrangler config");
    const out = await env.AI.run(LLAMA, {
      messages: [
        { role: "system", content: NAMING_SYSTEM },
        { role: "user", content: user },
      ],
      response_format: { type: "json_schema", json_schema: NAMING_SCHEMA },
      max_tokens: 600,
    });
    const r = (out as { response?: unknown }).response;
    raw = typeof r === "string" ? r : JSON.stringify(r ?? {});
  }
  const m = raw.match(/\{[\s\S]*\}/);
  let parsed: { title?: unknown; summary?: unknown } = {};
  try {
    parsed = m ? (JSON.parse(m[0]) as typeof parsed) : {};
  } catch {
    // the model wandered off the schema; the plain name stands
  }
  const title = typeof parsed.title === "string" ? parsed.title.trim().slice(0, 80) : "";
  const summary = typeof parsed.summary === "string" ? parsed.summary.trim() : "";
  return { title, summary };
}

const CHAT_SYSTEM = `You are the meeting assistant on a wall-mounted 10-inch panel in a home office. The person in the room is asking about a conversation the panel itself recorded; the transcript is below. It has no speaker labels: work out who said what from context where it matters, and say when you cannot.

Answer from the transcript. If the answer is not in it, say so plainly rather than guessing. Be brief: a few sentences, or a short list when the question calls for one. Plain text only, no markdown headings or tables; short dashes for list items are fine.`;

/** A question about one recording; Claude with a key, Workers AI without. */
export async function chatAboutRecording(
  env: Env,
  rec: Recording,
  transcript: string,
  history: ChatTurn[],
): Promise<{ reply: string; source: "claude" | "workers-ai" }> {
  const head = `${CHAT_SYSTEM}\n\nRecorded ${prettyStart(rec.startedAt)}, ${prettyLength(rec.durationSec)}.`;
  if (env.ANTHROPIC_API_KEY) {
    const system = `${head}\n\n<transcript>\n${transcript}\n</transcript>`;
    return { reply: await askClaude(env, system, history), source: "claude" };
  }
  let body = transcript;
  let note = "";
  if (body.length > LLAMA_TRANSCRIPT_CHARS) {
    body = body.slice(-LLAMA_TRANSCRIPT_CHARS);
    note = "(Only the last part of a long transcript fit; set ANTHROPIC_API_KEY on the Room Brain for the whole thing.)\n\n";
  }
  const system = `${head}\n\n<transcript>\n${body}\n</transcript>`;
  return { reply: note + (await askLlama(env, system, history)), source: "workers-ai" };
}

async function askClaude(env: Env, system: string, history: ChatTurn[]): Promise<string> {
  const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
  const messages: Anthropic.Beta.BetaMessageParam[] = history.length
    ? history.map((t) => ({ role: t.role, content: t.content }))
    : [{ role: "user", content: "Go ahead." }];
  const res = await client.beta.messages.create({
    model: "claude-opus-5",
    max_tokens: 4000,
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    system: [{ type: "text", text: system, cache_control: { type: "ephemeral" } }],
    messages,
    output_config: { effort: "medium" },
  });
  if (res.stop_reason === "refusal") return "I can't help with that one.";
  return res.content
    .filter((b): b is Anthropic.Beta.BetaTextBlock => b.type === "text")
    .map((b) => b.text)
    .join("")
    .trim();
}

async function askLlama(env: Env, system: string, history: ChatTurn[]): Promise<string> {
  if (!env.AI) throw new Error("Workers AI binding missing; add \"ai\" to wrangler config");
  const messages = [
    { role: "system", content: system },
    ...(history.length ? history.map((t) => ({ role: t.role, content: t.content })) : [{ role: "user", content: "Go ahead." }]),
  ];
  const out = await env.AI.run(LLAMA, { messages, max_tokens: 1000 });
  const text = typeof out === "object" && out && "response" in out ? String((out as { response?: string }).response ?? "") : "";
  return text.trim() || "(no answer)";
}

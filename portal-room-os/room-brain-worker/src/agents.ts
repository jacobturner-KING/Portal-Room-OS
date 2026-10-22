import { DurableObject } from "cloudflare:workers";
import { base64 } from "./recordings";

/**
 * Agents on the wall: every coding agent running on every machine you use
 * (Claude Code, Codex, Cursor), one screen each on the Portal, with a pet that
 * acts out what it is doing, a ping when one finishes, voice to tell it what to
 * do next, and permission prompts answered from the panel.
 *
 * Three parties, and this file is the one that owns the meaning:
 *
 * - Hooks on each machine (agents/portal-agent-hook) post the agent's own hook
 *   payload here untouched, with the machine and terminal in headers. Parsing
 *   happens once, here, in `parseHook`; the hook script stays a dumb pipe.
 * - A bridge on each machine (agents/portal-agent-bridge.mjs) holds a WebSocket
 *   to the hub and types spoken instructions into the agent's terminal.
 * - The Portal polls the snapshot and posts voice, decisions and dismissals.
 *
 * All of it lives in one Durable Object, `AgentHub`. Hooks fire several times a
 * second during a busy turn, which KV's one-write-per-second-per-key limit and
 * eventual consistency cannot take; the object serialises them and holds the
 * bridges' sockets with hibernation, so an idle evening costs nothing.
 */

export type AgentKind = "claude" | "codex" | "cursor" | "generic";
export type AgentState = "idle" | "working" | "waiting" | "done" | "ended";
/** What the pet holds while working. */
export type Activity = "think" | "edit" | "read" | "run" | "search" | "web" | "delegate" | "plan" | "tool" | "";

/** Where the agent's terminal is, as reported by its hook; only the bridge on that host acts on it. */
export interface Terminal {
  tmuxPane?: string;
  tmuxSocket?: string;
  itermSession?: string;
  tty?: string;
  program?: string;
}

interface Permission {
  id: string;
  tool: string;
  summary: string;
  requestedAt: number;
  expiresAt: number;
  decision?: "allow" | "deny";
}

interface SayRecord {
  text: string;
  at: number;
  status: "sent" | "typed" | "failed";
  error?: string;
}

export interface Session {
  id: string;
  agent: AgentKind;
  sessionId: string;
  host: string;
  cwd: string;
  title: string;
  state: AgentState;
  activity: string;
  kind: Activity;
  prompt: string;
  reply: string;
  /** Desk-side attention, e.g. a permission dialog showing in the terminal. */
  attention: string;
  startedAt: number;
  updatedAt: number;
  stateSince: number;
  /** Bumped every time a turn finishes; the Portal pings when it moves. */
  doneCount: number;
  term: Terminal;
  /** The newest hook applied, by the hook's own clock: an older one arriving late is dropped. */
  lastEventAt: number;
  permission?: Permission;
  lastSay?: SayRecord;
}

interface Settings {
  /** Hold Claude Code's permission prompts for an answer from the panel. */
  wallApprovals: boolean;
}

/** One parsed hook event: what changes on the session. */
export interface HookUpdate {
  agent: AgentKind;
  sessionId: string;
  cwd?: string;
  title?: string;
  state?: AgentState;
  activity?: string;
  kind?: Activity;
  prompt?: string;
  reply?: string;
  attention?: string;
  /** The turn finished: bump doneCount. */
  finished?: boolean;
  /** Claude Code is asking permission and could wait for the panel. */
  permission?: { tool: string; summary: string };
  /** A tool ran or the turn moved on: any pending panel question is moot. */
  clearsPermission?: boolean;
}

export interface HookMeta {
  agent: AgentKind;
  host: string;
  term: Terminal;
  /** When the hook fired (ms), if it said; arrival time otherwise. */
  at: number;
}

const PROMPT_MAX = 600;
const REPLY_MAX = 2400;
const ACTIVITY_MAX = 120;
const SAY_MAX = 4000;
/** How long a hook may hold a permission prompt for the panel before the terminal asks instead. */
const PERMISSION_WAIT_SEC = 110;
/** A working session that has said nothing for this long has probably been closed. */
const STALE_MS = 30 * 60_000;
const KEEP_MS = 24 * 60 * 60_000;
const ENDED_KEEP_MS = 15 * 60_000;
const MAX_SESSIONS = 24;

const AGENT_LABEL: Record<AgentKind, string> = {
  claude: "Claude Code",
  codex: "Codex",
  cursor: "Cursor",
  generic: "Agent",
};

// ---- Parsing: each agent's own hook payload into one HookUpdate ----

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

/** One line, control characters out, cut to n. */
export function oneLine(s: string, n: number): string {
  const flat = s.replace(/[\u0000-\u001f\u007f]+/g, " ").replace(/\s+/g, " ").trim();
  return flat.length > n ? flat.slice(0, n - 1) + "…" : flat;
}

/** Keeps paragraph breaks, drops other control characters, cuts to n. */
function block(s: string, n: number): string {
  const clean = s.replace(/\r\n?/g, "\n").replace(/[\u0000-\u0009\u000b-\u001f\u007f]+/g, " ").trim();
  return clean.length > n ? clean.slice(0, n - 1) + "…" : clean;
}

function baseName(p: string): string {
  const parts = p.replace(/\/+$/, "").split("/");
  return parts[parts.length - 1] || p;
}

/** What a tool call looks like on the wall: a short line and the prop the pet holds. */
export function describeTool(tool: string, input: Record<string, unknown>): { activity: string; kind: Activity } {
  const file = str(input.file_path) || str(input.notebook_path) || str(input.path);
  const said = (verb: string, what: string, kind: Activity) => ({
    activity: oneLine(what ? `${verb} ${what}` : verb, ACTIVITY_MAX),
    kind,
  });
  switch (tool) {
    case "Edit":
    case "MultiEdit":
    case "Write":
    case "NotebookEdit":
    case "edit_file":
    case "write":
      return said(tool === "Write" ? "Writing" : "Editing", file ? baseName(file) : "", "edit");
    case "Read":
    case "read_file":
      return said("Reading", file ? baseName(file) : "", "read");
    case "Grep":
    case "Glob":
    case "LS":
    case "grep":
    case "codebase_search":
      return said("Searching", str(input.pattern) || str(input.query) || (file ? baseName(file) : ""), "search");
    case "Bash":
    case "BashOutput":
    case "Shell":
    case "run_terminal_cmd":
      // Claude Code writes a plain-language description for most commands
      // ("Run the test suite"); it reads better than the raw command line.
      return str(input.description)
        ? { activity: oneLine(str(input.description), ACTIVITY_MAX), kind: "run" }
        : said("Running", str(input.command), "run");
    case "WebFetch":
    case "WebSearch":
    case "web_search":
      return said(tool === "WebSearch" || tool === "web_search" ? "Searching the web for" : "Reading",
        str(input.query) || str(input.url), "web");
    case "Task":
    case "Agent":
      return said("Delegating:", str(input.description), "delegate");
    case "TodoWrite":
    case "ExitPlanMode":
      return said("Planning", "", "plan");
    default: {
      const mcp = tool.match(/^mcp__([^_]+(?:_[^_]+)*)__(.+)$/);
      if (mcp) return said("Using", `${mcp[1]} · ${mcp[2]}`, "tool");
      return said("Using", tool, "tool");
    }
  }
}

function claudeUpdate(p: Record<string, unknown>): HookUpdate | null {
  const sessionId = str(p.session_id);
  if (!sessionId) return null;
  const u: HookUpdate = { agent: "claude", sessionId, cwd: str(p.cwd) || undefined };
  const input = (p.tool_input && typeof p.tool_input === "object" ? p.tool_input : {}) as Record<string, unknown>;
  switch (str(p.hook_event_name)) {
    case "SessionStart":
      u.state = "idle";
      u.activity = "Ready";
      u.kind = "";
      if (str(p.session_title)) u.title = oneLine(str(p.session_title), 80);
      return u;
    case "UserPromptSubmit":
      u.state = "working";
      u.activity = "Thinking";
      u.kind = "think";
      u.prompt = block(str(p.prompt) || str(p.user_input), PROMPT_MAX);
      u.attention = "";
      u.clearsPermission = true;
      return u;
    case "PreToolUse": {
      const d = describeTool(str(p.tool_name), input);
      return { ...u, state: "working", activity: d.activity, kind: d.kind, attention: "" };
    }
    case "PostToolUse":
    case "PostToolUseFailure":
      // The tool has run, so whatever was asked about it is settled.
      return { ...u, state: "working", attention: "", clearsPermission: true };
    case "PermissionRequest": {
      const d = describeTool(str(p.tool_name), input);
      const detail = str(input.command) || str(input.file_path) || str(input.url) || d.activity;
      return {
        ...u,
        state: "waiting",
        activity: d.activity,
        kind: d.kind,
        permission: { tool: str(p.tool_name), summary: oneLine(detail, 300) },
      };
    }
    case "Notification": {
      const type = str(p.notification_type);
      const message = oneLine(str(p.message), 200);
      if (type === "permission_prompt" || type === "elicitation_dialog" || type === "agent_needs_input"
        || (!type && /permission/i.test(message))) {
        return { ...u, state: "waiting", attention: message || "Needs you" };
      }
      // idle_prompt and the rest repeat what Stop already said.
      return null;
    }
    case "PreCompact":
      return { ...u, state: "working", activity: "Compacting the conversation", kind: "think" };
    case "Stop":
      return {
        ...u,
        state: "done",
        activity: "Done",
        kind: "",
        reply: block(str(p.last_assistant_message), REPLY_MAX) || undefined,
        attention: "",
        finished: true,
        clearsPermission: true,
      };
    case "StopFailure":
      return { ...u, state: "done", activity: "Stopped with an error", kind: "", attention: "", finished: true };
    case "SessionEnd":
      return { ...u, state: "ended", activity: "Session closed", kind: "", attention: "", clearsPermission: true };
    default:
      return null;
  }
}

/** Codex's `notify` program: one JSON argument, today only at the end of a turn. */
function codexUpdate(p: Record<string, unknown>): HookUpdate | null {
  if (str(p.type) !== "agent-turn-complete") return null;
  const sessionId = str(p["thread-id"]) || str(p.thread_id) || str(p["turn-id"]);
  if (!sessionId) return null;
  const inputs = Array.isArray(p["input-messages"]) ? (p["input-messages"] as unknown[]).map(str).filter(Boolean) : [];
  return {
    agent: "codex",
    sessionId,
    cwd: str(p.cwd) || undefined,
    state: "done",
    activity: "Done",
    kind: "",
    prompt: inputs.length ? block(inputs[inputs.length - 1], PROMPT_MAX) : undefined,
    reply: block(str(p["last-assistant-message"]), REPLY_MAX) || undefined,
    attention: "",
    finished: true,
  };
}

/** Cursor's agent hooks (~/.cursor/hooks.json). Only observational events are registered. */
function cursorUpdate(p: Record<string, unknown>): HookUpdate | null {
  const sessionId = str(p.conversation_id);
  if (!sessionId) return null;
  const roots = Array.isArray(p.workspace_roots) ? (p.workspace_roots as unknown[]).map(str).filter(Boolean) : [];
  const u: HookUpdate = { agent: "cursor", sessionId, cwd: roots[0] };
  const input = (p.tool_input && typeof p.tool_input === "object" ? p.tool_input : {}) as Record<string, unknown>;
  switch (str(p.hook_event_name)) {
    case "sessionStart":
      return { ...u, state: "idle", activity: "Ready", kind: "" };
    case "beforeSubmitPrompt":
      return { ...u, state: "working", activity: "Thinking", kind: "think", prompt: block(str(p.prompt), PROMPT_MAX) };
    case "postToolUse": {
      const d = describeTool(str(p.tool_name), input);
      return { ...u, state: "working", activity: d.activity, kind: d.kind };
    }
    case "afterFileEdit":
      return { ...u, state: "working", activity: oneLine(`Editing ${baseName(str(p.file_path))}`, ACTIVITY_MAX), kind: "edit" };
    case "afterShellExecution":
      return { ...u, state: "working", activity: oneLine(`Running ${str(p.command)}`, ACTIVITY_MAX), kind: "run" };
    case "afterAgentResponse":
      return { ...u, reply: block(str(p.text), REPLY_MAX) || undefined };
    case "stop":
      return {
        ...u,
        state: "done",
        activity: str(p.status) === "error" ? "Stopped with an error" : str(p.status) === "aborted" ? "Stopped" : "Done",
        kind: "",
        finished: true,
      };
    case "sessionEnd":
      return { ...u, state: "ended", activity: "Session closed", kind: "" };
    default:
      return null;
  }
}

/**
 * Anything else that can run a command: post
 * {"session_id","event":"start|working|waiting|done|end","activity?","prompt?","reply?","cwd?"}.
 */
function genericUpdate(p: Record<string, unknown>): HookUpdate | null {
  const sessionId = str(p.session_id);
  if (!sessionId) return null;
  const event = str(p.event);
  const state: Record<string, AgentState> = { start: "idle", working: "working", waiting: "waiting", done: "done", end: "ended" };
  if (!state[event]) return null;
  return {
    agent: "generic",
    sessionId,
    cwd: str(p.cwd) || undefined,
    title: str(p.title) ? oneLine(str(p.title), 80) : undefined,
    state: state[event],
    activity: str(p.activity) ? oneLine(str(p.activity), ACTIVITY_MAX) : undefined,
    kind: str(p.activity) ? "tool" : undefined,
    prompt: str(p.prompt) ? block(str(p.prompt), PROMPT_MAX) : undefined,
    reply: str(p.reply) ? block(str(p.reply), REPLY_MAX) : undefined,
    finished: event === "done",
  };
}

export function parseAgent(v: string | null): AgentKind | null {
  return v === "claude" || v === "codex" || v === "cursor" || v === "generic" ? v : null;
}

export function parseHook(agent: AgentKind, payload: unknown): HookUpdate | null {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const p = payload as Record<string, unknown>;
  switch (agent) {
    case "claude": return claudeUpdate(p);
    case "codex": return codexUpdate(p);
    case "cursor": return cursorUpdate(p);
    case "generic": return genericUpdate(p);
  }
}

/** Host names and terminal ids arrive in headers; keep them to the characters such ids use. */
function idPart(v: string | null, max: number): string | undefined {
  if (!v) return undefined;
  const clean = v.replace(/[^A-Za-z0-9._:%/@+-]/g, "").slice(0, max);
  return clean || undefined;
}

export function hookMeta(agent: AgentKind, headers: Headers): HookMeta {
  const at = Number(headers.get("x-agent-time"));
  return {
    agent,
    at: Number.isFinite(at) && at > 0 ? at : Date.now(),
    host: idPart(headers.get("x-agent-host"), 64) ?? "unknown",
    term: {
      tmuxPane: idPart(headers.get("x-tmux-pane"), 16),
      tmuxSocket: idPart(headers.get("x-tmux-socket"), 200),
      itermSession: idPart(headers.get("x-iterm-session"), 80),
      tty: idPart(headers.get("x-tty"), 40),
      program: idPart(headers.get("x-term-program"), 40),
    },
  };
}

/** Typed into a terminal: no control characters, so nothing spoken can become a keystroke. */
export function sayText(s: string): string {
  return oneLine(s, SAY_MAX);
}

async function sessionKey(agent: AgentKind, host: string, sessionId: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${agent}\n${host}\n${sessionId}`));
  const hex = [...new Uint8Array(digest).slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${agent}-${hex}`;
}

function timingSafeEqualStr(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  if (ab.byteLength !== bb.byteLength) return false;
  return crypto.subtle.timingSafeEqual(ab, bb);
}

// ---- What the Portal sees ----

export interface SessionView {
  id: string;
  agent: AgentKind;
  agentLabel: string;
  project: string;
  title: string;
  host: string;
  state: AgentState;
  activity: string;
  kind: Activity;
  prompt: string;
  reply: string;
  attention: string;
  startedAt: number;
  updatedAt: number;
  stateSince: number;
  doneCount: number;
  /** A bridge on the agent's machine can type into its terminal. */
  canTalk: boolean;
  /** Why not, when it cannot. */
  talkNote: string;
  permission: { id: string; tool: string; summary: string; expiresAt: number } | null;
  lastSay: SayRecord | null;
}

export interface Snapshot {
  rev: number;
  now: number;
  wallApprovals: boolean;
  bridges: string[];
  sessions: SessionView[];
}

interface BridgeTag {
  host: string;
  authed: boolean;
  since: number;
}

type DecideResult = { ok: true } | { ok: false; error: string };
type SayResult = { ok: true; delivered: true; text?: string } | { ok: false; error: string };

export class AgentHub extends DurableObject<Env> {
  private sessions = new Map<string, Session>();
  private settings: Settings = { wallApprovals: false };
  private rev = 0;
  /** Sent instructions awaiting the bridge's result, by command id. Lost on eviction, which only leaves a "sent". */
  private pendingSays = new Map<string, string>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      const stored = await ctx.storage.list<Session>({ prefix: "s:" });
      for (const s of stored.values()) this.sessions.set(s.id, s);
      this.settings = (await ctx.storage.get<Settings>("settings")) ?? this.settings;
      this.rev = (await ctx.storage.get<number>("rev")) ?? 0;
    });
    // Keep-alives from the bridges answered without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  private async save(s: Session): Promise<void> {
    this.sessions.set(s.id, s);
    this.rev++;
    await this.ctx.storage.put({ [`s:${s.id}`]: s, rev: this.rev });
  }

  private async drop(id: string): Promise<void> {
    if (!this.sessions.delete(id)) return;
    this.rev++;
    await this.ctx.storage.delete(`s:${id}`);
    await this.ctx.storage.put("rev", this.rev);
  }

  /** Old, ended and surplus sessions out. */
  private async prune(now: number): Promise<void> {
    const gone: string[] = [];
    for (const s of this.sessions.values()) {
      if (now - s.updatedAt > KEEP_MS) gone.push(s.id);
      else if (s.state === "ended" && now - s.updatedAt > ENDED_KEEP_MS) gone.push(s.id);
    }
    const rest = [...this.sessions.values()].filter((s) => !gone.includes(s.id)).sort((a, b) => b.updatedAt - a.updatedAt);
    for (const s of rest.slice(MAX_SESSIONS)) gone.push(s.id);
    for (const id of gone) await this.drop(id);
  }

  /** A hook fired. Returns the permission id when the hook should wait for the panel. */
  async hook(meta: HookMeta, u: HookUpdate): Promise<{ permissionId?: string; waitSec?: number }> {
    const now = Date.now();
    const id = await sessionKey(u.agent, meta.host, u.sessionId);
    const prior = this.sessions.get(id);
    const s: Session = prior ?? {
      id,
      agent: u.agent,
      sessionId: u.sessionId,
      host: meta.host,
      cwd: "",
      title: "",
      state: "idle",
      activity: "Ready",
      kind: "",
      prompt: "",
      reply: "",
      attention: "",
      startedAt: now,
      updatedAt: now,
      stateSince: now,
      doneCount: 0,
      term: {},
      lastEventAt: 0,
    };
    // Background posts race; a PostToolUse landing after the Stop it preceded
    // must not wake a finished agent. A permission prompt is never stale: its
    // hook is waiting on the answer.
    if (prior && meta.at < (prior.lastEventAt ?? 0) && !u.permission) return {};
    s.lastEventAt = Math.max(meta.at, s.lastEventAt ?? 0);
    // The terminal can change under a resumed session; the latest hook knows where it is.
    if (Object.values(meta.term).some(Boolean)) s.term = meta.term;
    if (u.cwd) s.cwd = u.cwd;
    if (u.title !== undefined) s.title = u.title;
    if (u.state && u.state !== s.state) {
      s.state = u.state;
      s.stateSince = now;
    }
    if (u.activity !== undefined) s.activity = u.activity;
    if (u.kind !== undefined) s.kind = u.kind;
    if (u.prompt !== undefined) {
      s.prompt = u.prompt;
      // A new turn: the last one's reply no longer answers anything.
      s.reply = "";
    }
    if (u.reply !== undefined) s.reply = u.reply;
    if (u.attention !== undefined) s.attention = u.attention;
    if (u.finished) s.doneCount++;
    if (u.clearsPermission) s.permission = undefined;

    let wait: { permissionId?: string; waitSec?: number } = {};
    if (u.permission) {
      if (this.settings.wallApprovals) {
        s.permission = {
          id: crypto.randomUUID().replace(/-/g, ""),
          tool: u.permission.tool,
          summary: u.permission.summary,
          requestedAt: now,
          expiresAt: now + PERMISSION_WAIT_SEC * 1000,
        };
        s.attention = "Waiting for your answer here";
        wait = { permissionId: s.permission.id, waitSec: PERMISSION_WAIT_SEC };
      } else {
        // Asked at the desk: show it, but the terminal's dialog answers.
        s.attention = `Asking to ${u.permission.summary ? "run " + u.permission.summary : "use " + u.permission.tool}`;
      }
    }
    s.updatedAt = now;
    await this.save(s);
    await this.prune(now);
    return wait;
  }

  /** The hook's poll: the decision as Claude Code's hook output, "pending", or "gone". */
  async permission(permissionId: string): Promise<{ status: "pending" } | { status: "gone" } | { status: "decided"; behavior: "allow" | "deny" }> {
    const now = Date.now();
    for (const s of this.sessions.values()) {
      const p = s.permission;
      if (!p || p.id !== permissionId) continue;
      if (p.decision) {
        s.permission = undefined;
        s.attention = "";
        // Either way Claude carries on: with the tool, or told it was refused.
        s.state = "working";
        s.stateSince = now;
        s.updatedAt = now;
        await this.save(s);
        return { status: "decided", behavior: p.decision };
      }
      if (now > p.expiresAt + 15_000) {
        s.permission = undefined;
        s.attention = "Asked in the terminal instead";
        s.updatedAt = now;
        await this.save(s);
        return { status: "gone" };
      }
      return { status: "pending" };
    }
    return { status: "gone" };
  }

  async decide(id: string, permissionId: string, allow: boolean): Promise<DecideResult> {
    const s = this.sessions.get(id);
    if (!s?.permission || s.permission.id !== permissionId) return { ok: false, error: "That request has already been answered or has timed out" };
    if (Date.now() > s.permission.expiresAt) return { ok: false, error: "Too late: the terminal is asking now" };
    s.permission.decision = allow ? "allow" : "deny";
    s.attention = allow ? "Approved here" : "Denied here";
    await this.save(s);
    return { ok: true };
  }

  async setSettings(patch: Partial<Settings>): Promise<Settings> {
    if (typeof patch.wallApprovals === "boolean") this.settings.wallApprovals = patch.wallApprovals;
    await this.ctx.storage.put("settings", this.settings);
    this.rev++;
    await this.ctx.storage.put("rev", this.rev);
    return this.settings;
  }

  async dismiss(id: string): Promise<void> {
    await this.drop(id);
  }

  private bridgeFor(host: string): WebSocket | null {
    let best: WebSocket | null = null;
    let bestSince = -1;
    for (const ws of this.ctx.getWebSockets()) {
      const tag = ws.deserializeAttachment() as BridgeTag | null;
      if (!tag?.authed || tag.host !== host) continue;
      if (tag.since > bestSince) {
        best = ws;
        bestSince = tag.since;
      }
    }
    return best;
  }

  private bridgeHosts(): string[] {
    const hosts = new Set<string>();
    for (const ws of this.ctx.getWebSockets()) {
      const tag = ws.deserializeAttachment() as BridgeTag | null;
      if (tag?.authed) hosts.add(tag.host);
    }
    return [...hosts].sort();
  }

  private static reachable(t: Terminal): boolean {
    return Boolean(t.tmuxPane || t.itermSession || t.tty);
  }

  /** Spoken words to the agent's terminal, through the bridge on its machine. */
  async say(id: string, text: string): Promise<SayResult> {
    const s = this.sessions.get(id);
    if (!s) return { ok: false, error: "That agent is gone" };
    const clean = sayText(text);
    if (!clean) return { ok: false, error: "Nothing to send" };
    const r = await this.toBridge(s, { type: "say", text: clean }, { text: clean });
    return r.ok ? { ...r, text: clean } : r;
  }

  /** Brings the agent's terminal to the front on its machine. */
  async focus(id: string): Promise<SayResult> {
    const s = this.sessions.get(id);
    if (!s) return { ok: false, error: "That agent is gone" };
    return this.toBridge(s, { type: "focus" }, null);
  }

  private async toBridge(s: Session, msg: Record<string, unknown>, record: { text: string } | null): Promise<SayResult> {
    if (!AgentHub.reachable(s.term)) return { ok: false, error: "This agent's terminal did not report where it is" };
    const ws = this.bridgeFor(s.host);
    if (!ws) return { ok: false, error: `No bridge running on ${s.host}` };
    const cmd = crypto.randomUUID();
    try {
      ws.send(JSON.stringify({ ...msg, cmd, session: s.id, agent: s.agent, term: s.term }));
    } catch (err) {
      return { ok: false, error: `Bridge on ${s.host} dropped: ${String(err)}` };
    }
    if (record) {
      s.lastSay = { text: record.text, at: Date.now(), status: "sent" };
      this.pendingSays.set(cmd, s.id);
      await this.save(s);
    }
    return { ok: true, delivered: true };
  }

  snapshot(): Snapshot {
    const now = Date.now();
    const bridges = this.bridgeHosts();
    const sessions = [...this.sessions.values()]
      .sort((a, b) => a.startedAt - b.startedAt)
      .map((s): SessionView => {
        // A working session silent for half an hour was most likely closed without a goodbye.
        const stale = (s.state === "working" || s.state === "waiting") && now - s.updatedAt > STALE_MS;
        const permission = s.permission && !s.permission.decision && now <= s.permission.expiresAt
          ? { id: s.permission.id, tool: s.permission.tool, summary: s.permission.summary, expiresAt: s.permission.expiresAt }
          : null;
        const bridged = bridges.includes(s.host);
        const reachable = AgentHub.reachable(s.term);
        return {
          id: s.id,
          agent: s.agent,
          agentLabel: AGENT_LABEL[s.agent],
          project: s.cwd ? baseName(s.cwd) : "",
          title: s.title,
          host: s.host,
          state: stale ? "idle" : s.state,
          activity: stale ? "Quiet for a while" : s.activity,
          kind: stale ? "" : s.kind,
          prompt: s.prompt,
          reply: s.reply,
          attention: permission || !stale ? s.attention : "",
          startedAt: s.startedAt,
          updatedAt: s.updatedAt,
          stateSince: s.stateSince,
          doneCount: s.doneCount,
          canTalk: bridged && reachable && s.state !== "ended",
          talkNote: s.state === "ended" ? "Session closed"
            : !reachable ? "Run it in tmux, iTerm2 or Terminal to talk to it"
            : !bridged ? `Start the bridge on ${s.host} to talk to it` : "",
          permission,
          lastSay: s.lastSay ?? null,
        };
      });
    return { rev: this.rev, now, wallApprovals: this.settings.wallApprovals, bridges, sessions };
  }

  // ---- Bridges: WebSocket in, authenticated by their first message ----

  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") return new Response("expected websocket", { status: 426 });
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ host: "", authed: false, since: Date.now() } satisfies BridgeTag);
    return new Response(null, { status: 101, webSocket: client });
  }

  override async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const tag = ws.deserializeAttachment() as BridgeTag | null;
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw)) as Record<string, unknown>;
    } catch {
      ws.close(4000, "bad message");
      return;
    }
    if (!tag?.authed) {
      // The token rides in the first message rather than the URL, so it never lands in request logs.
      const token = str(msg.token);
      const host = idPart(str(msg.host), 64);
      if (msg.type !== "hello" || !host || !this.env.AGENT_TOKEN || !timingSafeEqualStr(token, this.env.AGENT_TOKEN)) {
        ws.close(4001, "unauthorized");
        return;
      }
      ws.serializeAttachment({ host, authed: true, since: Date.now() } satisfies BridgeTag);
      ws.send(JSON.stringify({ type: "welcome", host }));
      this.rev++;
      await this.ctx.storage.put("rev", this.rev);
      return;
    }
    if (msg.type === "result") {
      const cmd = str(msg.cmd);
      const sid = this.pendingSays.get(cmd);
      if (!sid) return;
      this.pendingSays.delete(cmd);
      const s = this.sessions.get(sid);
      if (!s?.lastSay) return;
      s.lastSay = msg.ok === true
        ? { ...s.lastSay, status: "typed" }
        : { ...s.lastSay, status: "failed", error: oneLine(str(msg.error) || "the bridge could not type it", 200) };
      await this.save(s);
    }
  }

  override async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, "closing");
    } catch {
      // already closed
    }
    this.rev++;
    await this.ctx.storage.put("rev", this.rev);
  }
}

export function hubStub(env: Env): DurableObjectStub<AgentHub> {
  return env.AGENT_HUB.get(env.AGENT_HUB.idFromName("hub"));
}

/** Whisper turns a spoken instruction into text; the language is detected unless pinned. */
export async function transcribe(env: Env, wav: ArrayBuffer): Promise<string> {
  if (!env.AI) throw new Error("Workers AI binding missing; add \"ai\" to the wrangler config");
  const out = await env.AI.run("@cf/openai/whisper-large-v3-turbo", {
    audio: base64(wav),
    task: "transcribe",
    language: env.AGENT_VOICE_LANGUAGE || undefined,
  });
  return (out.text ?? "").trim();
}

/** Claude Code's PermissionRequest hook output, printed verbatim by the waiting hook. */
export function permissionHookOutput(behavior: "allow" | "deny"): Record<string, unknown> {
  return {
    hookSpecificOutput: {
      hookEventName: "PermissionRequest",
      decision: behavior === "allow"
        ? { behavior: "allow" }
        : { behavior: "deny", message: "Denied from the Portal" },
    },
  };
}

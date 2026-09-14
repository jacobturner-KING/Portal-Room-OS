import { del, getJSON, putJSON } from "./kv";

/**
 * Granola, through its hosted MCP server. Granola has no REST API for notes;
 * the MCP server is the sanctioned way in, and it speaks OAuth 2.1 with dynamic
 * client registration, so there is no client id to paste anywhere: the Worker
 * registers itself the first time an admin links Granola, and keeps the id in
 * KV. Tokens live in KV like every other integration; the Portal never sees
 * them.
 *
 * The server is read-only (list and read meetings, notes, transcripts, plus
 * Granola's own natural-language search). Nothing here can create a meeting
 * or push audio in.
 */
export const MCP_URL = "https://mcp.granola.ai/mcp";
const AUTH_ISSUER = "https://mcp-auth.granola.ai";
const REGISTER_URL = `${AUTH_ISSUER}/oauth2/register`;
const AUTHORIZE_URL = `${AUTH_ISSUER}/oauth2/authorize`;
const TOKEN_URL = `${AUTH_ISSUER}/oauth2/token`;
// What the authorization server advertises; offline_access is the refresh token.
const SCOPE = "openid profile email offline_access";
const PROTOCOL = "2025-06-18";

const CLIENT_KEY = "granola:client";
const TOKEN_KEY = "tokens:granola";
const TOOLS_KEY = "granola:tools";

interface GranolaClient {
  client_id: string;
  client_secret?: string;
  redirect_uri: string;
}

interface GranolaTokens {
  access_token: string;
  refresh_token?: string;
  expires_at: number; // epoch ms
  scope?: string;
}

/** Thrown when there is no usable Granola grant; routes answer `not_connected`. */
export class GranolaNotConnected extends Error {
  constructor() {
    super("granola not connected");
  }
}

// ---- OAuth ----

function b64url(bytes: ArrayBuffer | Uint8Array): string {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function pkce(): Promise<{ verifier: string; challenge: string }> {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)));
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return { verifier, challenge: b64url(digest) };
}

// Register once per redirect URI (so a Worker on a new hostname re-registers).
async function ensureClient(env: Env, redirectUri: string): Promise<GranolaClient> {
  const existing = await getJSON<GranolaClient>(env, CLIENT_KEY);
  if (existing && existing.redirect_uri === redirectUri) return existing;
  const res = await fetch(REGISTER_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      client_name: "Portal Room Brain",
      redirect_uris: [redirectUri],
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
      scope: SCOPE,
    }),
  });
  if (!res.ok) throw new Error(`granola client registration failed: ${res.status}`);
  const data = (await res.json()) as { client_id: string; client_secret?: string };
  const client: GranolaClient = {
    client_id: data.client_id,
    client_secret: data.client_secret,
    redirect_uri: redirectUri,
  };
  await putJSON(env, CLIENT_KEY, client);
  return client;
}

/** Returns the authorize URL and the PKCE verifier to keep alongside `state`. */
export async function granolaAuthUrl(
  env: Env,
  redirectUri: string,
  state: string,
): Promise<{ url: string; verifier: string }> {
  const client = await ensureClient(env, redirectUri);
  const { verifier, challenge } = await pkce();
  const p = new URLSearchParams({
    response_type: "code",
    client_id: client.client_id,
    redirect_uri: redirectUri,
    scope: SCOPE,
    state,
    code_challenge: challenge,
    code_challenge_method: "S256",
    resource: MCP_URL, // RFC 8707: the token is for the MCP server, nothing else
  });
  return { url: `${AUTHORIZE_URL}?${p.toString()}`, verifier };
}

export async function granolaExchange(
  env: Env,
  code: string,
  redirectUri: string,
  verifier: string,
): Promise<void> {
  const client = await getJSON<GranolaClient>(env, CLIENT_KEY);
  if (!client) throw new Error("granola client registration missing");
  const body = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    redirect_uri: redirectUri,
    client_id: client.client_id,
    code_verifier: verifier,
    resource: MCP_URL,
  });
  if (client.client_secret) body.set("client_secret", client.client_secret);
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) {
    throw new Error(`granola token exchange failed: ${res.status} ${(await res.text()).slice(0, 200)}`);
  }
  const data = (await res.json()) as {
    access_token: string;
    refresh_token?: string;
    expires_in?: number;
    scope?: string;
  };
  await putJSON(env, TOKEN_KEY, {
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expires_at: Date.now() + (data.expires_in ?? 3600) * 1000,
    scope: data.scope,
  } satisfies GranolaTokens);
  session = null; // a new grant means a new MCP session
  await del(env, TOOLS_KEY);
}

/**
 * A live access token, refreshed when it is about to lapse. Refresh tokens
 * rotate, so the stored one is replaced on every refresh. A refresh the server
 * rejects does not wipe the grant: a parallel request may have rotated it
 * first, in which case its token is the good one.
 */
async function accessToken(env: Env): Promise<string | null> {
  const t = await getJSON<GranolaTokens>(env, TOKEN_KEY);
  if (!t) return null;
  if (Date.now() < t.expires_at - 60_000) return t.access_token;
  if (!t.refresh_token) return null;
  const client = await getJSON<GranolaClient>(env, CLIENT_KEY);
  if (!client) return null;

  const body = new URLSearchParams({
    grant_type: "refresh_token",
    refresh_token: t.refresh_token,
    client_id: client.client_id,
    resource: MCP_URL,
  });
  if (client.client_secret) body.set("client_secret", client.client_secret);
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) {
    const detail = (await res.text()).slice(0, 200);
    console.log(JSON.stringify({ level: "warn", msg: "granola_refresh_failed", status: res.status, detail }));
    if (res.status >= 500) throw new Error(`granola refresh failed: ${res.status}`);
    const again = await getJSON<GranolaTokens>(env, TOKEN_KEY);
    if (again && again.refresh_token !== t.refresh_token && Date.now() < again.expires_at - 60_000) {
      return again.access_token;
    }
    return null;
  }
  const data = (await res.json()) as {
    access_token: string;
    refresh_token?: string;
    expires_in?: number;
  };
  const updated: GranolaTokens = {
    access_token: data.access_token,
    refresh_token: data.refresh_token ?? t.refresh_token,
    expires_at: Date.now() + (data.expires_in ?? 3600) * 1000,
    scope: t.scope,
  };
  await putJSON(env, TOKEN_KEY, updated);
  return updated.access_token;
}

export interface GranolaStatus {
  connected: boolean;
  /** A grant exists but cannot be refreshed; re-link. */
  stale: boolean;
}

export async function granolaStatus(env: Env): Promise<GranolaStatus> {
  const t = await getJSON<GranolaTokens>(env, TOKEN_KEY);
  if (!t) return { connected: false, stale: false };
  const usable = Date.now() < t.expires_at - 60_000 || Boolean(t.refresh_token);
  return { connected: usable, stale: !usable };
}

// ---- MCP transport (Streamable HTTP) ----

interface RpcMessage {
  id?: number;
  result?: unknown;
  error?: { code: number; message: string };
}

interface RpcOutcome {
  status: number;
  body: RpcMessage | null;
  sessionId: string | null;
}

/** One MCP session per isolate and token; re-made when the server forgets it. */
let session: { token: string; id: string | null } | null = null;
let nextId = 10;

function parseRpc(text: string, contentType: string, id: number): RpcMessage | null {
  if (!contentType.includes("text/event-stream")) {
    try {
      return JSON.parse(text) as RpcMessage;
    } catch {
      return { error: { code: -1, message: text.slice(0, 300) } };
    }
  }
  // SSE: events separated by blank lines, each with one or more data: lines.
  let found: RpcMessage | null = null;
  for (const event of text.split(/\r?\n\r?\n/)) {
    const data = event
      .split(/\r?\n/)
      .filter((l) => l.startsWith("data:"))
      .map((l) => l.slice(5).trim())
      .join("\n");
    if (!data) continue;
    try {
      const m = JSON.parse(data) as RpcMessage;
      if (m.id === id) found = m;
    } catch {
      // keep-alives and partial frames are not for us
    }
  }
  return found;
}

async function rpc(
  token: string,
  method: string,
  params: unknown,
  id: number | null,
  sessionId: string | null,
): Promise<RpcOutcome> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    accept: "application/json, text/event-stream",
    authorization: `Bearer ${token}`,
    "mcp-protocol-version": PROTOCOL,
  };
  if (sessionId) headers["mcp-session-id"] = sessionId;
  const msg: Record<string, unknown> = { jsonrpc: "2.0", method, params };
  if (id !== null) msg.id = id;
  const res = await fetch(MCP_URL, { method: "POST", headers, body: JSON.stringify(msg) });
  const sid = res.headers.get("mcp-session-id") ?? sessionId;
  const text = await res.text();
  if (id === null) return { status: res.status, body: null, sessionId: sid };
  if (!res.ok) {
    return { status: res.status, body: { error: { code: res.status, message: text.slice(0, 300) } }, sessionId: sid };
  }
  return { status: res.status, body: parseRpc(text, res.headers.get("content-type") ?? "", id), sessionId: sid };
}

async function connect(token: string): Promise<string | null> {
  const init = await rpc(
    token,
    "initialize",
    {
      protocolVersion: PROTOCOL,
      capabilities: {},
      clientInfo: { name: "portal-room-brain", version: "1.0" },
    },
    1,
    null,
  );
  if (init.status === 401) throw new GranolaNotConnected();
  if (init.status >= 400 || init.body?.error) {
    throw new Error(`granola mcp initialize failed: ${init.status} ${init.body?.error?.message ?? ""}`);
  }
  await rpc(token, "notifications/initialized", {}, null, init.sessionId);
  return init.sessionId;
}

async function request(env: Env, method: string, params: unknown): Promise<unknown> {
  const token = await accessToken(env);
  if (!token) throw new GranolaNotConnected();
  if (!session || session.token !== token) session = { token, id: await connect(token) };

  let r = await rpc(token, method, params, nextId++, session.id);
  // The server dropped the session (a deploy, or an idle timeout): start over once.
  if (r.status === 404 || (r.status === 400 && /session/i.test(r.body?.error?.message ?? ""))) {
    session = { token, id: await connect(token) };
    r = await rpc(token, method, params, nextId++, session.id);
  }
  if (r.status === 401) {
    session = null;
    throw new GranolaNotConnected();
  }
  if (r.body?.error) throw new Error(`granola ${method}: ${r.body.error.message}`);
  return r.body?.result ?? {};
}

// ---- Tools ----

export interface ToolInfo {
  name: string;
  description?: string;
  inputSchema?: { properties?: Record<string, { type?: string; description?: string }>; required?: string[] };
}

export async function listTools(env: Env): Promise<ToolInfo[]> {
  const cached = await getJSON<ToolInfo[]>(env, TOOLS_KEY);
  if (cached) return cached;
  const result = (await request(env, "tools/list", {})) as { tools?: ToolInfo[] };
  const tools = result.tools ?? [];
  await putJSON(env, TOOLS_KEY, tools, 86_400);
  return tools;
}

export interface ToolOutput {
  text: string;
  structured: unknown;
}

function textOf(content: unknown): string {
  if (!Array.isArray(content)) return "";
  return content
    .map((c) => (c && typeof c === "object" && (c as { type?: string }).type === "text" ? String((c as { text?: string }).text ?? "") : ""))
    .filter(Boolean)
    .join("\n");
}

export async function callTool(env: Env, name: string, args: Record<string, unknown>): Promise<ToolOutput> {
  const result = (await request(env, "tools/call", { name, arguments: args })) as {
    content?: unknown;
    structuredContent?: unknown;
    isError?: boolean;
  };
  const text = textOf(result.content);
  if (result.isError) throw new Error(`granola ${name}: ${text.slice(0, 300) || "tool error"}`);
  return { text, structured: result.structuredContent ?? null };
}

/**
 * Granola's tool parameter names are not published, so calls are shaped from
 * the schemas the server hands back. Each role below is matched against the
 * tool's property names, first pattern wins.
 */
export type ParamRole = "id" | "search" | "limit" | "query" | "range";

const ROLE_PATTERNS: Record<ParamRole, RegExp[]> = {
  id: [/^(meeting_ids?|document_ids?|note_ids?|ids?)$/i, /ids?$/i, /id/i],
  search: [/^(search|query|q|title|filter)$/i, /search|query|title/i],
  limit: [/^(limit|max_results|max|count|page_size|n)$/i, /limit|max|count/i],
  query: [/^(query|question|prompt|message|q|text|input)$/i, /query|question|prompt|message/i],
  range: [/^(time_range|range|period)$/i],
};

export function findTool(tools: ToolInfo[], ...names: string[]): ToolInfo | null {
  for (const n of names) {
    const t = tools.find((x) => x.name === n);
    if (t) return t;
  }
  return null;
}

export function paramFor(tool: ToolInfo, role: ParamRole): { name: string; type: string } | null {
  const props = tool.inputSchema?.properties ?? {};
  for (const re of ROLE_PATTERNS[role]) {
    const name = Object.keys(props).find((k) => re.test(k));
    if (name) return { name, type: props[name]?.type ?? "string" };
  }
  return null;
}

/** Sets `role` on `args` in the shape the tool declares, if it declares one. */
export function withParam(
  tool: ToolInfo,
  args: Record<string, unknown>,
  role: ParamRole,
  value: string | number,
): Record<string, unknown> {
  const p = paramFor(tool, role);
  if (!p) return args;
  if (p.type === "array") args[p.name] = [value];
  else if (p.type === "integer" || p.type === "number") args[p.name] = Number(value);
  else args[p.name] = String(value);
  return args;
}

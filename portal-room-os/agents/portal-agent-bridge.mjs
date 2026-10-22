#!/usr/bin/env node
// portal-agent-bridge: the Portal's hands on this machine.
//
// Holds one WebSocket to the Room Brain's agent hub. When you talk to an agent
// on the Portal, the hub sends the words here and this types them into that
// agent's terminal; "Show on Mac" brings the terminal to the front. Nothing
// else: it reads no files, runs no agent, and only ever types into a terminal
// whose foreground process is not a bare shell, so spoken words can never land
// on a shell prompt as a command.
//
// Terminals it can reach: tmux (macOS and Linux), iTerm2 and Terminal.app
// (macOS, through AppleScript; the first use asks for Automation permission).
//
// Node 22 or later (built-in WebSocket). No dependencies.
//
//   node portal-agent-bridge.mjs            run
//   node portal-agent-bridge.mjs --dry-run  log what it would type, type nothing

import { execFile } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir, hostname, platform } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

const run = promisify(execFile);
const DRY = process.argv.includes("--dry-run");
const VERSION = 1;
const PING_MS = 25_000;
const SHELLS = new Set(["sh", "bash", "zsh", "fish", "dash", "ksh", "tcsh", "csh", "nu", "login"]);

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

/** KEY="value" lines, as install.mjs writes them. */
function readConfig() {
  const path = process.env.PORTAL_AGENTS_CONFIG || join(homedir(), ".portal-agents", "config");
  const out = {};
  for (const line of readFileSync(path, "utf8").split("\n")) {
    const m = line.match(/^\s*([A-Z_]+)=(?:"(.*)"|'(.*)'|(.*))\s*$/);
    if (m) out[m[1]] = m[2] ?? m[3] ?? m[4] ?? "";
  }
  return out;
}

const config = readConfig();
const url = (config.ROOM_BRAIN_URL || "").replace(/\/+$/, "");
const token = config.AGENT_TOKEN || "";
const host = config.AGENT_HOST || hostname().split(".")[0];
if (!url || !token) {
  console.error("ROOM_BRAIN_URL and AGENT_TOKEN must be set in ~/.portal-agents/config (run install.mjs)");
  process.exit(1);
}

/** One line, no control characters: nothing spoken can become a keystroke. */
function clean(text) {
  return String(text ?? "").replace(/[\u0000-\u001f\u007f]+/g, " ").replace(/\s+/g, " ").trim().slice(0, 4000);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function tmuxArgs(term, ...args) {
  return term.tmuxSocket ? ["-S", term.tmuxSocket, ...args] : args;
}

function processName(cmd) {
  return cmd.trim().split("/").pop().replace(/^-/, "");
}

/**
 * Refuses to type unless the terminal's foreground is something other than a
 * shell. If that cannot be established, it refuses too: an unknown foreground
 * is not an agent.
 */
async function foregroundOfTty(tty) {
  const { stdout } = await run("ps", ["-t", tty.replace(/^\/dev\//, ""), "-o", "stat=", "-o", "comm="]);
  return stdout.split("\n")
    .map((l) => l.trim().match(/^(\S+)\s+(.+)$/))
    .filter((m) => m && m[1].includes("+"))
    .map((m) => processName(m[2]));
}

function assertAgentInFront(names, where) {
  if (!names.length) throw new Error(`Could not tell what is running in ${where}`);
  if (names.every((n) => SHELLS.has(n))) throw new Error(`The agent in ${where} has exited; only a shell is left`);
}

async function tmuxSay(term, text) {
  const { stdout } = await run("tmux", tmuxArgs(term, "display-message", "-p", "-t", term.tmuxPane, "#{pane_current_command}"))
    .catch(() => { throw new Error("That tmux pane is gone"); });
  assertAgentInFront([processName(stdout)], `tmux pane ${term.tmuxPane}`);
  if (DRY) return log(`[dry-run] tmux ${term.tmuxPane} <- ${text}`);
  await run("tmux", tmuxArgs(term, "send-keys", "-t", term.tmuxPane, "-l", "--", text));
  // A beat between the text and Enter, so the TUI takes it as typed, then submits.
  await sleep(150);
  await run("tmux", tmuxArgs(term, "send-keys", "-t", term.tmuxPane, "Enter"));
}

async function osascript(lines, args) {
  const { stdout } = await run("osascript", [...lines.flatMap((l) => ["-e", l]), "--", ...args]);
  return stdout.trim();
}

const ITERM_FIND = [
  "on run argv",
  "  set wantId to item 1 of argv",
  "  set wantTty to item 2 of argv",
  "  set what to item 3 of argv",
  "  set payload to item 4 of argv",
  "  tell application \"iTerm2\"",
  "    repeat with w in windows",
  "      repeat with t in tabs of w",
  "        repeat with s in sessions of t",
  "          if unique id of s is wantId then",
  "            if wantTty is not \"\" and tty of s is not wantTty then return \"moved\"",
  "            if what is \"say\" then",
  "              tell s to write text payload",
  "            else",
  "              select w",
  "              tell t to select",
  "              tell s to select",
  "              activate",
  "            end if",
  "            return \"ok\"",
  "          end if",
  "        end repeat",
  "      end repeat",
  "    end repeat",
  "  end tell",
  "  return \"missing\"",
  "end run",
];

const TERMINAL_FIND = [
  "on run argv",
  "  set wantTty to item 1 of argv",
  "  set what to item 2 of argv",
  "  set payload to item 3 of argv",
  "  tell application \"Terminal\"",
  "    repeat with w in windows",
  "      repeat with t in tabs of w",
  "        if tty of t is wantTty then",
  "          if what is \"say\" then",
  "            do script payload in t",
  "          else",
  "            set selected of t to true",
  "            set index of w to 1",
  "            activate",
  "          end if",
  "          return \"ok\"",
  "        end if",
  "      end repeat",
  "    end repeat",
  "  end tell",
  "  return \"missing\"",
  "end run",
];

function devTty(tty) {
  return tty ? (tty.startsWith("/dev/") ? tty : `/dev/${tty}`) : "";
}

/** "w0t1p0:UUID" in the environment; AppleScript knows the session by the UUID. */
function itermId(session) {
  return session.includes(":") ? session.split(":").pop() : session;
}

function isIterm(term) {
  return Boolean(term.itermSession) && platform() === "darwin";
}

function isTerminalApp(term) {
  return term.program === "Apple_Terminal" && Boolean(term.tty) && platform() === "darwin";
}

async function say(term, rawText) {
  const text = clean(rawText);
  if (!text) throw new Error("Nothing to type");
  if (term.tmuxPane) return tmuxSay(term, text);
  if (isIterm(term) || isTerminalApp(term)) {
    if (!term.tty) throw new Error("This terminal did not report its tty");
    assertAgentInFront(await foregroundOfTty(term.tty), term.tty);
    if (DRY) return log(`[dry-run] ${term.program || "iTerm2"} ${term.tty} <- ${text}`);
    const r = isIterm(term)
      ? await osascript(ITERM_FIND, [itermId(term.itermSession), devTty(term.tty), "say", text])
      : await osascript(TERMINAL_FIND, [devTty(term.tty), "say", text]);
    if (r === "moved") throw new Error("That iTerm2 session is running something else now");
    if (r !== "ok") throw new Error("That terminal window is closed");
    return;
  }
  throw new Error(`Can't type into ${term.program || "this terminal"}; run the agent in tmux, iTerm2 or Terminal`);
}

const APPS = { "iTerm.app": "iTerm", Apple_Terminal: "Terminal", ghostty: "Ghostty", WezTerm: "WezTerm", WarpTerminal: "Warp" };

async function focus(term) {
  if (DRY) return log(`[dry-run] focus ${JSON.stringify(term)}`);
  if (isIterm(term)) {
    const r = await osascript(ITERM_FIND, [itermId(term.itermSession), devTty(term.tty), "focus", ""]);
    if (r !== "ok") throw new Error("That iTerm2 session is closed");
  } else if (isTerminalApp(term)) {
    const r = await osascript(TERMINAL_FIND, [devTty(term.tty), "focus", ""]);
    if (r !== "ok") throw new Error("That Terminal tab is closed");
  }
  if (term.tmuxPane) {
    await run("tmux", tmuxArgs(term, "select-window", "-t", term.tmuxPane)).catch(() => { throw new Error("That tmux pane is gone"); });
    await run("tmux", tmuxArgs(term, "select-pane", "-t", term.tmuxPane));
    const app = APPS[term.program];
    if (app && platform() === "darwin") await run("open", ["-a", app]).catch(() => {});
  }
  if (!term.tmuxPane && !isIterm(term) && !isTerminalApp(term)) throw new Error("Don't know how to bring that terminal forward");
}

let backoff = 1000;

function connect() {
  const wsUrl = url.replace(/^http/, "ws") + "/api/agents/bridge";
  const ws = new WebSocket(wsUrl);
  let ping;
  ws.addEventListener("open", () => {
    ws.send(JSON.stringify({ type: "hello", token, host, version: VERSION }));
    ping = setInterval(() => ws.readyState === WebSocket.OPEN && ws.send("ping"), PING_MS);
  });
  ws.addEventListener("message", async (ev) => {
    const raw = typeof ev.data === "string" ? ev.data : "";
    if (raw === "pong") return;
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (msg.type === "welcome") {
      backoff = 1000;
      log(`connected as ${msg.host}${DRY ? " (dry run)" : ""}`);
      return;
    }
    if (msg.type !== "say" && msg.type !== "focus") return;
    const term = msg.term && typeof msg.term === "object" ? msg.term : {};
    try {
      if (msg.type === "say") await say(term, msg.text);
      else await focus(term);
      log(`${msg.type} ${msg.agent} ok`);
      ws.send(JSON.stringify({ type: "result", cmd: msg.cmd, ok: true }));
    } catch (err) {
      const error = String(err?.message || err).split("\n")[0].slice(0, 200);
      log(`${msg.type} ${msg.agent} failed: ${error}`);
      ws.send(JSON.stringify({ type: "result", cmd: msg.cmd, ok: false, error }));
    }
  });
  ws.addEventListener("close", (ev) => {
    clearInterval(ping);
    if (ev.code === 4001) {
      console.error("The Room Brain refused AGENT_TOKEN; fix ~/.portal-agents/config");
      process.exit(2);
    }
    log(`disconnected (${ev.code}); retrying in ${Math.round(backoff / 1000)} s`);
    setTimeout(connect, backoff);
    backoff = Math.min(30_000, backoff * 2);
  });
  ws.addEventListener("error", () => {
    // close follows; the retry lives there
  });
}

connect();

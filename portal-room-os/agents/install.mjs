#!/usr/bin/env node
// Sets this machine up to show its coding agents on the Portal.
//
//   node install.mjs --url https://portal-room-brain.<you>.workers.dev --token <AGENT_TOKEN>
//   node install.mjs --uninstall
//
// It prints every change first and writes nothing until you say yes. Changes
// are additive: existing hooks and settings stay, each file it edits is backed
// up beside itself first, and running it twice changes nothing the second time.
//
//   ~/.portal-agents/            config (mode 600), the hook and the bridge
//   ~/.claude/settings.json      Claude Code hooks
//   ~/.codex/config.toml         notify = [...] (only if Codex has no notify yet)
//   ~/.cursor/hooks.json         Cursor agent hooks
//   a LaunchAgent (macOS)        keeps the bridge running; --no-bridge skips it
//
// Flags: --url, --token, --host (defaults to the short host name), --agents
// claude,codex,cursor (default: whichever are installed), --no-bridge, --yes.

import { execFileSync } from "node:child_process";
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir, hostname, platform } from "node:os";
import { dirname, join } from "node:path";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const HOME = homedir();
const DIR = join(HOME, ".portal-agents");
const CONFIG = join(DIR, "config");
const HOOK = join(DIR, "portal-agent-hook");
const BRIDGE = join(DIR, "portal-agent-bridge.mjs");
const CLAUDE = join(HOME, ".claude", "settings.json");
const CODEX = join(HOME, ".codex", "config.toml");
const CURSOR = join(HOME, ".cursor", "hooks.json");
const LABEL = "com.portalroomos.agent-bridge";
const PLIST = join(HOME, "Library", "LaunchAgents", `${LABEL}.plist`);
const MARK = "portal-agent-hook";

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
}
const has = (flag) => process.argv.includes(`--${flag}`);
const quote = (p) => (/[\s"'$`\\]/.test(p) ? `"${p.replace(/(["$`\\])/g, "\\$1")}"` : p);

function readJson(path) {
  if (!existsSync(path)) return {};
  const text = readFileSync(path, "utf8");
  if (!text.trim()) return {};
  try {
    return JSON.parse(text);
  } catch (err) {
    throw new Error(`${path} is not valid JSON (${err.message}); fix it or move it aside, then run again`);
  }
}

function backup(path) {
  if (!existsSync(path)) return null;
  const to = `${path}.bak-${new Date().toISOString().replace(/[:.]/g, "-")}`;
  copyFileSync(path, to);
  return to;
}

const json = (v) => JSON.stringify(v, null, 2) + "\n";

// ---- Claude Code ----

/** Every event goes up in the background except PermissionRequest, which may wait for the panel. */
function claudeHooks() {
  const cmd = `${quote(HOOK)} claude`;
  const bg = { type: "command", command: cmd, async: true, timeout: 15 };
  return {
    SessionStart: [{ hooks: [bg] }],
    UserPromptSubmit: [{ hooks: [bg] }],
    PreToolUse: [{ matcher: "*", hooks: [bg] }],
    PostToolUse: [{ matcher: "*", hooks: [bg] }],
    Notification: [{ hooks: [bg] }],
    Stop: [{ hooks: [bg] }],
    SessionEnd: [{ hooks: [bg] }],
    PermissionRequest: [{
      matcher: "*",
      hooks: [{ type: "command", command: cmd, timeout: 120, statusMessage: "Asking on the Portal…" }],
    }],
  };
}

const ours = (group) => JSON.stringify(group).includes(MARK);

function claudePlan(remove) {
  const settings = readJson(CLAUDE);
  const hooks = { ...(settings.hooks ?? {}) };
  const changes = [];
  if (remove) {
    for (const [event, groups] of Object.entries(hooks)) {
      if (!Array.isArray(groups) || !groups.some(ours)) continue;
      const kept = groups.filter((g) => !ours(g));
      if (kept.length) hooks[event] = kept; else delete hooks[event];
      changes.push(`remove the Portal hook from ${event}`);
    }
  } else {
    for (const [event, groups] of Object.entries(claudeHooks())) {
      const existing = Array.isArray(hooks[event]) ? hooks[event] : [];
      if (existing.some(ours)) continue;
      hooks[event] = [...existing, ...groups];
      changes.push(`add a hook for ${event}`);
    }
  }
  if (!changes.length) return null;
  const next = { ...settings, hooks };
  if (!Object.keys(hooks).length) delete next.hooks;
  return { path: CLAUDE, changes, write: () => writeFileSync(CLAUDE, json(next)) };
}

// ---- Codex ----

function codexPlan(remove) {
  const text = existsSync(CODEX) ? readFileSync(CODEX, "utf8") : "";
  const lines = text.split("\n");
  // Only a top-level key counts: everything after the first [table] belongs to it.
  const firstTable = lines.findIndex((l) => /^\s*\[/.test(l));
  const top = firstTable < 0 ? lines : lines.slice(0, firstTable);
  const notifyAt = top.findIndex((l) => /^\s*notify\s*=/.test(l));
  if (remove) {
    if (notifyAt < 0 || !top[notifyAt].includes(MARK)) return null;
    const next = lines.filter((_, i) => i !== notifyAt).join("\n");
    return { path: CODEX, changes: ["remove the Portal notify line"], write: () => writeFileSync(CODEX, next) };
  }
  if (notifyAt >= 0) {
    if (top[notifyAt].includes(MARK)) return null;
    return {
      path: CODEX,
      changes: [],
      note: `Codex already has a notify program (${top[notifyAt].trim()}); left alone. Call ${HOOK} codex "$1" from it to add the Portal.`,
    };
  }
  const line = `notify = [${JSON.stringify(HOOK)}, "codex"]`;
  const at = firstTable < 0 ? lines.length : firstTable;
  // Before the first [table], with a blank line to keep it apart.
  const next = [...lines.slice(0, at), line, ...(firstTable < 0 ? [] : [""]), ...lines.slice(at)].join("\n");
  return {
    path: CODEX,
    changes: [`add ${line} (Codex reports finished turns only)`],
    write: () => {
      mkdirSync(dirname(CODEX), { recursive: true });
      writeFileSync(CODEX, next.endsWith("\n") ? next : next + "\n");
    },
  };
}

// ---- Cursor ----

const CURSOR_EVENTS = ["sessionStart", "beforeSubmitPrompt", "postToolUse", "afterFileEdit", "afterShellExecution", "afterAgentResponse", "stop", "sessionEnd"];

function cursorPlan(remove) {
  const file = readJson(CURSOR);
  const hooks = { ...(file.hooks ?? {}) };
  const changes = [];
  for (const event of CURSOR_EVENTS) {
    const existing = Array.isArray(hooks[event]) ? hooks[event] : [];
    const mine = existing.some(ours);
    if (remove && mine) {
      const kept = existing.filter((h) => !ours(h));
      if (kept.length) hooks[event] = kept; else delete hooks[event];
      changes.push(`remove the Portal hook from ${event}`);
    } else if (!remove && !mine) {
      hooks[event] = [...existing, { command: `${quote(HOOK)} cursor` }];
      changes.push(`add a hook for ${event}`);
    }
  }
  if (!changes.length) return null;
  const next = { version: 1, ...file, hooks };
  return {
    path: CURSOR,
    changes,
    write: () => {
      mkdirSync(dirname(CURSOR), { recursive: true });
      writeFileSync(CURSOR, json(next));
    },
  };
}

// ---- The bridge, kept running by launchd ----

function plist() {
  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array><string>${esc(process.execPath)}</string><string>${esc(BRIDGE)}</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>${esc(join(DIR, "bridge.log"))}</string>
  <key>StandardErrorPath</key><string>${esc(join(DIR, "bridge.log"))}</string>
</dict>
</plist>
`;
}

function launchctl(...args) {
  try {
    execFileSync("launchctl", args, { stdio: "ignore" });
  } catch {
    // not loaded yet, or already gone
  }
}

function bridgePlan(remove) {
  if (platform() !== "darwin") {
    return remove ? null : {
      path: "bridge",
      changes: [],
      note: `Not macOS: run the bridge yourself, e.g. a systemd user service with ExecStart=${process.execPath} ${BRIDGE}`,
    };
  }
  const uid = process.getuid();
  if (remove) {
    if (!existsSync(PLIST)) return null;
    return {
      path: PLIST,
      changes: ["stop the bridge and remove its LaunchAgent"],
      write: () => {
        launchctl("bootout", `gui/${uid}/${LABEL}`);
        rmSync(PLIST, { force: true });
      },
    };
  }
  const body = plist();
  if (existsSync(PLIST) && readFileSync(PLIST, "utf8") === body) return null;
  return {
    path: PLIST,
    changes: ["install a LaunchAgent that keeps the bridge running"],
    write: () => {
      mkdirSync(dirname(PLIST), { recursive: true });
      writeFileSync(PLIST, body);
      launchctl("bootout", `gui/${uid}/${LABEL}`);
      launchctl("bootstrap", `gui/${uid}`, PLIST);
    },
  };
}

// ---- ~/.portal-agents ----

function filesPlan(url, token, host) {
  const config = `# Written by portal-room-os/agents/install.mjs\nROOM_BRAIN_URL="${url}"\nAGENT_TOKEN="${token}"\nAGENT_HOST="${host}"\n`;
  const same = (from, to) => existsSync(to) && readFileSync(from).equals(readFileSync(to));
  const changes = [];
  if (!existsSync(CONFIG) || readFileSync(CONFIG, "utf8") !== config) changes.push("write config (mode 600)");
  if (!same(join(HERE, "portal-agent-hook"), HOOK) || !same(join(HERE, "portal-agent-bridge.mjs"), BRIDGE)) {
    changes.push("copy portal-agent-hook and portal-agent-bridge.mjs");
  }
  if (!changes.length) return null;
  return {
    path: DIR,
    changes,
    write: () => {
      mkdirSync(DIR, { recursive: true, mode: 0o700 });
      writeFileSync(CONFIG, config, { mode: 0o600 });
      chmodSync(CONFIG, 0o600);
      copyFileSync(join(HERE, "portal-agent-hook"), HOOK);
      chmodSync(HOOK, 0o755);
      copyFileSync(join(HERE, "portal-agent-bridge.mjs"), BRIDGE);
    },
  };
}

function installed(agent) {
  const dirs = { claude: ".claude", codex: ".codex", cursor: ".cursor" };
  return existsSync(join(HOME, dirs[agent]));
}

async function main() {
  const remove = has("uninstall");
  const wanted = (arg("agents") ?? "claude,codex,cursor").split(",").map((s) => s.trim());
  const agents = wanted.filter((a) => ["claude", "codex", "cursor"].includes(a) && (arg("agents") || installed(a)));
  const plans = [];

  if (!remove) {
    const prior = existsSync(CONFIG) ? readFileSync(CONFIG, "utf8") : "";
    const pick = (key) => prior.match(new RegExp(`^${key}="(.*)"$`, "m"))?.[1];
    const url = (arg("url") ?? pick("ROOM_BRAIN_URL") ?? "").replace(/\/+$/, "");
    const token = arg("token") ?? pick("AGENT_TOKEN") ?? "";
    const host = (arg("host") ?? pick("AGENT_HOST") ?? hostname().split(".")[0]).replace(/[^A-Za-z0-9._-]/g, "");
    if (!/^https:\/\/[^\s"]+$/.test(url) || !token || /["\s\\$`]/.test(token)) {
      console.error("Needs --url https://<your worker> and --token <AGENT_TOKEN> (no quotes or spaces in the token).");
      process.exit(1);
    }
    const major = Number(process.versions.node.split(".")[0]);
    if (major < 22 && !has("no-bridge")) {
      console.error(`The bridge needs Node 22 or later (this is ${process.versions.node}). Upgrade, or pass --no-bridge.`);
      process.exit(1);
    }
    plans.push(filesPlan(url, token, host));
  }
  if (agents.includes("claude")) plans.push(claudePlan(remove));
  if (agents.includes("codex")) plans.push(codexPlan(remove));
  if (agents.includes("cursor")) plans.push(cursorPlan(remove));
  if (!has("no-bridge")) plans.push(bridgePlan(remove));
  if (remove && existsSync(DIR)) {
    plans.push({ path: DIR, changes: ["delete it (config, hook, bridge, log)"], write: () => rmSync(DIR, { recursive: true, force: true }) });
  }

  const todo = plans.filter(Boolean);
  if (!todo.some((p) => p.changes.length)) {
    for (const p of todo) if (p.note) console.log(`- ${p.note}`);
    console.log("Nothing to change.");
    return;
  }
  console.log(remove ? "This will remove the Portal from this machine:\n" : "This will set this machine up for the Portal:\n");
  for (const p of todo) {
    if (p.changes.length) console.log(`${p.path}\n${p.changes.map((c) => `  - ${c}`).join("\n")}`);
    if (p.note) console.log(`- ${p.note}`);
  }
  console.log("\nFiles that already exist are backed up beside themselves first.");

  if (!has("yes")) {
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    const answer = (await rl.question("Apply? [y/N] ")).trim().toLowerCase();
    rl.close();
    if (answer !== "y" && answer !== "yes") {
      console.log("Nothing written.");
      return;
    }
  }
  for (const p of todo) {
    if (!p.write) continue;
    if (p.path.endsWith(".json") || p.path.endsWith(".toml")) {
      const saved = backup(p.path);
      if (saved) console.log(`backed up ${p.path} -> ${saved}`);
    }
    p.write();
    console.log(`done: ${p.path}`);
  }
  if (!remove) {
    console.log("\nRestart running agents so they load the hooks. New sessions appear on the Portal as soon as they start.");
  }
}

main().catch((err) => {
  console.error(String(err.message || err));
  process.exit(1);
});

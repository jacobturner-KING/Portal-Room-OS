# Agents on the wall

The Portal's Agents screen shows every coding agent running on every machine
you set up: Claude Code, Codex and Cursor, one screen each with a pet acting out
the work, a ping when one finishes, push-to-talk to tell it what to do next, and
Claude Code's permission prompts answered from the panel.

Two small programs per machine make that happen, both talking to your Room Brain
Worker, never to the Portal directly:

- `portal-agent-hook` (bash + curl). Each agent runs it from its own hook
  system and it posts the agent's hook payload, untouched, to the Worker. It
  never holds an agent up: posts go in the background, and the only time it
  waits is a Claude Code permission prompt while **Approve here** is on.
- `portal-agent-bridge.mjs` (Node 22+, no dependencies). It holds one WebSocket
  to the Worker and, when you talk to an agent on the Portal, types the words
  into that agent's terminal; **Show on <machine>** brings the terminal forward.
  Without it the screen is read-only, which is fine for watching.

## Or: the Mac on the cable, through AppTabs

If AppTabs runs on the Mac the Portal is plugged into, nothing below is needed
for that Mac: press **Show on Portal (USB)** on AppTabs' Agents screen. AppTabs
already captures the agents with its own hooks and serves them to the Portal
over `adb reverse`, with no Room Brain and no internet. Do not install these
hooks on that Mac as well, or each agent shows twice. The steps below are for
other machines, reached through the Room Brain.

## Set up a machine

On the Worker, once:

```bash
cd room-brain-worker
npx wrangler secret put AGENT_TOKEN          # a long random string you invent
npx wrangler deploy --config wrangler.local.jsonc
```

The first deploy with the `AGENT_HUB` binding creates the `AgentHub` Durable
Object (migration `agents-v1`). `wrangler.local.jsonc` needs the same
`durable_objects` and `migrations` blocks as `wrangler.jsonc`.

Then on each machine with agents:

```bash
node agents/install.mjs --url https://portal-room-brain.<you>.workers.dev --token <AGENT_TOKEN>
```

It lists every change and writes nothing until you answer `y`:

| Where | What |
|-------|------|
| `~/.portal-agents/` | `config` (mode 600: Worker URL, token, machine name), the hook, the bridge |
| `~/.claude/settings.json` | Hooks for SessionStart, UserPromptSubmit, PreToolUse, PostToolUse, Notification, Stop, SessionEnd (all `async`), and PermissionRequest |
| `~/.codex/config.toml` | `notify = [hook, "codex"]`, only if Codex has no `notify` yet |
| `~/.cursor/hooks.json` | Observational agent hooks only, never a permission gate |
| `~/Library/LaunchAgents/com.portalroomos.agent-bridge.plist` | Keeps the bridge running (macOS). Elsewhere, run the bridge as a service yourself |

Existing hooks and settings stay; each file is backed up beside itself first;
running it again changes nothing. `--agents claude,codex` picks which (default:
whichever are installed), `--host` names the machine on the Portal, `--no-bridge`
skips the bridge, `--uninstall` takes it all back out. Restart running agents
so they pick up the hooks.

## What each agent shows

| | Claude Code | Codex | Cursor |
|---|---|---|---|
| Working, and on what | yes, per tool call | no (Codex's `notify` fires only at the end of a turn) | yes, from file edits, shell commands and tool calls |
| What you asked | yes | yes | yes |
| What it said | yes (`last_assistant_message`) | yes | yes (`afterAgentResponse`) |
| Done ping | yes | yes | yes |
| Needs-you ping | yes (permission and input prompts) | no | no |
| Approve / Deny on the panel | yes, with Approve here on | no | no |
| Talk to it | in tmux, iTerm2 or Terminal.app | in tmux, iTerm2 or Terminal.app | no (the agent lives in the editor, not a terminal) |

Anything else that can run a command can join as `generic`:
`portal-agent-hook generic <<< '{"session_id":"x","event":"working","activity":"Scoring batch 3"}'`
with `event` one of `start`, `working`, `waiting`, `done`, `end`, and optional
`cwd`, `title`, `prompt`, `reply`.

## Talking to an agent

Tap the pet (or Talk), speak, tap anywhere to send. The Portal sends the audio
to the Worker, Whisper writes it down (`AGENT_VOICE_LANGUAGE` pins the
language, otherwise it is detected), and the bridge on the agent's machine
types it into the agent's terminal and presses Enter. The screen shows what was
heard and whether it was typed.

The bridge finds the terminal from what the hook reported: the tmux pane
(`$TMUX_PANE`, preferred), else the iTerm2 session (`$ITERM_SESSION_ID`), else
the Terminal.app tab with the agent's tty. It refuses when:

- the terminal's foreground process is a shell, so the agent has exited and the
  words would run as a command;
- it cannot tell what is in the foreground;
- the iTerm2 session's tty no longer matches.

Words are flattened to one line with control characters removed before they
are typed, on the Worker and again in the bridge, so nothing spoken can become
a keystroke. The first use with iTerm2 or Terminal.app asks macOS for
Automation permission for whatever runs the bridge.

## Approving from the panel

Off by default. With **Approve here** on, Claude Code's PermissionRequest hook
waits up to 110 seconds for Approve or Deny on the Portal before the terminal
asks as usual; the terminal shows "Asking on the Portal…" meanwhile. Turn it
off when you are at the desk, or every prompt pauses first. Off, the Portal
still shows the agent needs you, and the terminal answers.

## Security

`AGENT_TOKEN` opens only `/api/agents/{hook,permission,bridge}`. It lives in
`~/.portal-agents/config` (mode 600) and reaches curl through a file
descriptor, never the command line. The bridge sends it in its first WebSocket
message rather than the URL, so it stays out of request logs. Anything holding
the Portal's `APP_TOKEN` can type into a bridged agent's terminal, as the panel
can; treat that token accordingly.

The hub keeps prompts and replies (cut to a few thousand characters) for up to
24 hours, in your own Durable Object. Audio is never kept.

## Checking it

```bash
tail -f ~/.portal-agents/bridge.log                       # the bridge (macOS service)
node ~/.portal-agents/portal-agent-bridge.mjs --dry-run   # log what it would type, type nothing
```

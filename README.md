# Portal-Hub

A first-generation Meta Portal turned into a room hub: wall dashboard, Spotify
controller, and its own Spotify Connect speaker, driven by a Cloudflare Worker
"Room Brain" that holds every integration and secret.

- `portal-room-os/` — the whole project: Android app, Worker, docs, and the
  runbook (`portal-room-os/README.md`) with build, deploy, and device notes.
- `Portal Background.png` — wallpaper asset for the dashboard.

Secrets never live in this repo: the Worker reads `.dev.vars` (gitignored) and
`wrangler secret put`; the app reads its bearer token from `local.properties`
(gitignored). See the runbook for both.

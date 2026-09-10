# Portal-Hub

A first-generation Meta Portal turned into a room hub: wall dashboard, Spotify
controller, and its own Spotify Connect speaker, driven by a Cloudflare Worker
"Room Brain" that holds every integration and secret.

- `portal-room-os/` — the whole project: Android app, Worker, docs, and the
  runbook (`portal-room-os/README.md`) with build, deploy, and device notes.
- `Portal Background.png` — wallpaper asset for the dashboard.

Configuration is local to each install: the Worker reads `.dev.vars` in
development and `wrangler secret put` in production, and the app reads
`local.properties`. The runbook covers both.

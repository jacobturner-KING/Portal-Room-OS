interface ConnectStatus {
  spotify: boolean;
  google: boolean;
  googleEdit: boolean;
  majorKey: boolean;
  homeAssistant: boolean;
}

function badge(ok: boolean): string {
  return ok ? "✅ connected" : "— not connected";
}

export function renderConnect(status: ConnectStatus): Response {
  const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Room Brain — Connect</title>
  <style>
    body { font-family: system-ui, sans-serif; background:#101218; color:#f6f7fb; margin:0; padding:40px 20px; }
    main { max-width: 560px; margin: 0 auto; }
    h1 { font-size: 20px; margin: 0 0 4px; }
    .muted { color:#a6adbd; font-size:14px; }
    .card { background:#191d27; border-radius:14px; padding:18px 20px; margin:16px 0; }
    a.btn { display:inline-block; background:#8aa4ff; color:#0b0d12; text-decoration:none;
            padding:10px 16px; border-radius:10px; font-weight:600; margin-top:10px; }
  </style>
</head>
<body>
  <main>
    <h1>Portal Room Brain</h1>
    <p class="muted">Link your accounts once. Tokens are stored server-side; the Portal never sees them.</p>

    <div class="card">
      <b>Spotify</b> — ${badge(status.spotify)}<br />
      <a class="btn" href="/auth/spotify/start">${status.spotify ? "Reconnect" : "Connect"} Spotify</a>
    </div>

    <div class="card">
      <b>Google Calendar</b> — ${badge(status.google)}${
                status.google ? (status.googleEdit ? " · can edit events" : " · read-only, reconnect to allow editing") : ""
              }<br />
      <a class="btn" href="/auth/google/start">${status.google ? "Reconnect" : "Connect"} Google</a>
    </div>

    <div class="card">
      <b>Major Key</b> — ${status.majorKey ? "configured" : "not configured"}<br />
      <span class="muted">Set MAJOR_KEY_URL and MAJOR_KEY_KEY as Worker secrets.</span>
    </div>

    <div class="card">
      <b>Home Assistant</b> — ${status.homeAssistant ? "configured" : "not configured"}<br />
      <span class="muted">Expose HA through a Cloudflare Tunnel, then set HA_URL and HA_TOKEN as Worker secrets
      and HA_LIGHT_ENTITY / HA_SUMMARY_ENTITIES in wrangler.jsonc.</span>
    </div>
  </main>
</body>
</html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8" } });
}

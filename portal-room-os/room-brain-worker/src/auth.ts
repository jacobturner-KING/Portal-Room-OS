export function unauthorized(): Response {
  return new Response(JSON.stringify({ ok: false, error: "unauthorized" }), {
    status: 401,
    headers: { "content-type": "application/json" },
  });
}

// Constant-time string compare to avoid timing side-channels on secret checks.
function timingSafeEqualStr(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  if (ab.byteLength !== bb.byteLength) return false;
  return crypto.subtle.timingSafeEqual(ab, bb);
}

// The Portal sends `Authorization: Bearer <APP_TOKEN>` on every /api call.
export function checkAppToken(request: Request, env: Env): boolean {
  const header = request.headers.get("authorization") ?? "";
  const prefix = "Bearer ";
  if (!header.startsWith(prefix)) return false;
  return timingSafeEqualStr(header.slice(prefix.length), env.APP_TOKEN);
}

// The /connect admin surface accepts either ?admin=<ADMIN_TOKEN> or the rk_admin cookie.
export function checkAdmin(request: Request, env: Env): boolean {
  const url = new URL(request.url);
  const q = url.searchParams.get("admin");
  if (q && timingSafeEqualStr(q, env.ADMIN_TOKEN)) return true;

  const cookie = request.headers.get("cookie") ?? "";
  const m = cookie.match(/(?:^|;\s*)rk_admin=([^;]+)/);
  if (m && timingSafeEqualStr(decodeURIComponent(m[1]), env.ADMIN_TOKEN)) return true;

  return false;
}

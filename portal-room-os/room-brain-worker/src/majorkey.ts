// Major Key is your own service in a separate codebase. The Room Brain calls it
// with a service key and reads back the daily focus. The Portal never sees it.
export async function getFocus(env: Env): Promise<string | null> {
  if (!env.MAJOR_KEY_URL) return null;
  const base = env.MAJOR_KEY_URL.replace(/\/$/, "");
  const res = await fetch(`${base}/focus`, {
    headers: env.MAJOR_KEY_KEY ? { authorization: `Bearer ${env.MAJOR_KEY_KEY}` } : {},
  });
  if (!res.ok) return null;
  const data = (await res.json()) as { focus?: string; text?: string };
  return data.focus ?? data.text ?? null;
}

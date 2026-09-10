export async function getJSON<T>(env: Env, key: string): Promise<T | null> {
  return await env.ROOM_KV.get<T>(key, "json");
}

export async function putJSON(env: Env, key: string, value: unknown, ttlSeconds?: number): Promise<void> {
  await env.ROOM_KV.put(
    key,
    JSON.stringify(value),
    ttlSeconds ? { expirationTtl: ttlSeconds } : undefined,
  );
}

export async function del(env: Env, key: string): Promise<void> {
  await env.ROOM_KV.delete(key);
}

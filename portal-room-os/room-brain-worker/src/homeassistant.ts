// Home Assistant over its REST API. The Worker reaches HA through a Cloudflare
// Tunnel (HA_URL, e.g. https://ha.example.com) using a long-lived access token
// (HA_TOKEN). Both are Worker secrets; nothing here runs until they are set, so
// the dashboard keeps showing "not connected" and the Lights button stays a stub.
//
//   HA_SUMMARY_ENTITIES  comma-separated entity ids shown on the dashboard's home line
//   HA_LIGHT_ENTITY      the light (or switch/group) the dashboard's Lights button toggles
import type { ActionResult } from "./types";

interface HaState {
  entity_id: string;
  state: string;
  attributes: {
    friendly_name?: string;
    current_temperature?: number;
    temperature?: number;
    [key: string]: unknown;
  };
}

export function isHomeAssistantConfigured(env: Env): boolean {
  return Boolean(env.HA_URL && env.HA_TOKEN);
}

function base(env: Env): string {
  return (env.HA_URL ?? "").replace(/\/$/, "");
}

function headers(env: Env): Record<string, string> {
  return { authorization: `Bearer ${env.HA_TOKEN}`, "content-type": "application/json" };
}

async function getState(env: Env, entityId: string): Promise<HaState | null> {
  const res = await fetch(`${base(env)}/api/states/${encodeURIComponent(entityId)}`, {
    headers: headers(env),
  });
  if (!res.ok) return null;
  return (await res.json()) as HaState;
}

// One short phrase per entity, e.g. "Living room on", "Thermostat 70°", "Front door locked".
function describe(s: HaState): string {
  const name = s.attributes.friendly_name ?? s.entity_id;
  const domain = s.entity_id.split(".")[0];
  if (domain === "climate") {
    const t = s.attributes.current_temperature ?? s.attributes.temperature;
    return t !== undefined && t !== null ? `${name} ${Math.round(Number(t))}°` : `${name} ${s.state}`;
  }
  if (domain === "lock") return `${name} ${s.state === "locked" ? "locked" : "UNLOCKED"}`;
  return `${name} ${s.state}`;
}

// Always fetched live: the product spec forbids showing stale lock/security state.
export async function getHomeSummary(env: Env): Promise<string | null> {
  if (!isHomeAssistantConfigured(env)) return null;
  const ids = (env.HA_SUMMARY_ENTITIES ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (ids.length === 0) return "Home Assistant connected";
  const states = await Promise.all(ids.map((id) => getState(env, id)));
  const parts = states.filter((s): s is HaState => s !== null).map(describe);
  return parts.length ? parts.join(" · ") : "Home Assistant unreachable";
}

export async function toggleLights(env: Env): Promise<ActionResult> {
  if (!isHomeAssistantConfigured(env)) {
    return { ok: false, message: "Home Assistant not connected yet" };
  }
  const entity = env.HA_LIGHT_ENTITY;
  if (!entity) return { ok: false, message: "Set HA_LIGHT_ENTITY to the light to toggle" };
  // homeassistant.toggle works for lights, switches, groups and fans alike.
  const res = await fetch(`${base(env)}/api/services/homeassistant/toggle`, {
    method: "POST",
    headers: headers(env),
    body: JSON.stringify({ entity_id: entity }),
  });
  if (!res.ok) return { ok: false, message: `Home Assistant error ${res.status}` };
  const after = await getState(env, entity);
  const name = after?.attributes.friendly_name ?? entity;
  return { ok: true, message: after ? `${name} ${after.state}` : "Toggled" };
}

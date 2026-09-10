// Keyless weather via Open-Meteo.
//
// Location: WEATHER_LAT / WEATHER_LON in wrangler.jsonc win when set. Otherwise the
// Worker uses Cloudflare's geo-IP of the request (request.cf), which for the Portal's
// own calls is the home network's location — city-level, which is all weather needs.
// Results are cached in KV for 10 minutes so the Portal's 15s polling stays polite.
import { getJSON, putJSON } from "./kv";

const CODES: Record<number, string> = {
  0: "Clear",
  1: "Mainly clear",
  2: "Partly cloudy",
  3: "Overcast",
  45: "Fog",
  48: "Rime fog",
  51: "Light drizzle",
  53: "Drizzle",
  55: "Heavy drizzle",
  61: "Light rain",
  63: "Rain",
  65: "Heavy rain",
  71: "Light snow",
  73: "Snow",
  75: "Heavy snow",
  77: "Snow grains",
  80: "Rain showers",
  81: "Rain showers",
  82: "Violent showers",
  85: "Snow showers",
  86: "Snow showers",
  95: "Thunderstorm",
  96: "Thunderstorm",
  99: "Thunderstorm",
};

const CACHE_TTL_SECONDS = 600;

export interface WeatherLocation {
  lat: string;
  lon: string;
  label: string;
  source: "config" | "geoip";
}

export interface WeatherReport {
  text: string; // e.g. "72° · Partly cloudy"
  location: WeatherLocation;
}

export function resolveLocation(env: Env, request?: Request): WeatherLocation | null {
  if (env.WEATHER_LAT && env.WEATHER_LON) {
    return { lat: env.WEATHER_LAT, lon: env.WEATHER_LON, label: env.WEATHER_LABEL ?? "", source: "config" };
  }
  const cf = request?.cf as IncomingRequestCfProperties | undefined;
  if (cf?.latitude && cf?.longitude) {
    const label = [cf.city, cf.regionCode].filter(Boolean).join(", ");
    return { lat: cf.latitude, lon: cf.longitude, label, source: "geoip" };
  }
  return null;
}

export async function getWeather(env: Env, request?: Request): Promise<WeatherReport | null> {
  const location = resolveLocation(env, request);
  if (!location) return null;

  // Round so tiny geo-IP jitter doesn't defeat the cache.
  const lat = Number(location.lat).toFixed(2);
  const lon = Number(location.lon).toFixed(2);
  const cacheKey = `cache:weather:${lat},${lon}`;
  const cached = await getJSON<{ text: string }>(env, cacheKey);
  if (cached) return { text: cached.text, location };

  const url =
    `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
    `&current=temperature_2m,weather_code&temperature_unit=fahrenheit`;
  const res = await fetch(url);
  if (!res.ok) return null;
  const data = (await res.json()) as {
    current?: { temperature_2m: number; weather_code: number };
  };
  if (!data.current) return null;
  const temp = Math.round(data.current.temperature_2m);
  const desc = CODES[data.current.weather_code] ?? "";
  const text = desc ? `${temp}° · ${desc}` : `${temp}°`;
  await putJSON(env, cacheKey, { text }, CACHE_TTL_SECONDS);
  return { text, location };
}

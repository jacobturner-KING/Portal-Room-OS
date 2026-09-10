package com.portalroomos;

/** Shared configuration for talking to the Room Brain Worker. */
public final class Config {
    private Config() {}

    // Room Brain runs as a Cloudflare Worker (always-on, HTTPS). The hostname
    // carries the Cloudflare account subdomain, so it is injected at build time
    // from local.properties (roomBrain.url) rather than committed.
    public static final String ROOM_BRAIN_URL = BuildConfig.ROOM_BRAIN_URL;

    // Bearer token the Worker checks on every /api call (Worker secret APP_TOKEN).
    // Injected at build time from local.properties (roomBrain.appToken); see app/build.gradle.
    public static final String APP_TOKEN = BuildConfig.ROOM_BRAIN_APP_TOKEN;
}

package com.example.babymonitor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class RemoteConfig {
    private static final String PREF_URLS = "remote_relay_urls_v1";
    private static final String PREF_VERSION = "remote_config_version_v1";
    private static final String PREF_LAST_ATTEMPT = "remote_config_last_attempt_v1";
    private static final String PREF_LAST_SUCCESS = "remote_config_last_success_v1";
    private static final String PREF_REFRESH_MS = "remote_config_refresh_ms_v1";

    private static final long DEFAULT_REFRESH_MS = 5 * 60 * 1000L;
    private static final long MIN_REFRESH_MS = 60 * 1000L;
    private static final long MAX_REFRESH_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    private static final String[] CONFIG_URLS = {
            "https://baby-monitor-secure-relay.mosheschwartzberg.workers.dev/client-config",
            "https://raw.githubusercontent.com/lionsgrandson/argus/main/remote-config.json"
    };

    static void refreshIfDue(Context context) {
        if (context == null) return;

        SharedPreferences prefs = AppPrefs.prefs(context);
        long now = System.currentTimeMillis();
        long refreshMs = clampRefresh(prefs.getLong(PREF_REFRESH_MS, DEFAULT_REFRESH_MS));
        long lastAttempt = prefs.getLong(PREF_LAST_ATTEMPT, 0L);
        if (lastAttempt > 0L && now - lastAttempt < refreshMs) return;

        prefs.edit().putLong(PREF_LAST_ATTEMPT, now).apply();

        Config best = null;
        for (String configUrl : CONFIG_URLS) {
            try {
                String body = ResilientHttp.get(configUrl, MAX_CONFIG_BYTES);
                Config candidate = parse(body);
                if (candidate != null && (best == null || candidate.version > best.version)) {
                    best = candidate;
                }
            } catch (Exception ignored) { }
        }

        if (best == null) return;

        StringBuilder joined = new StringBuilder();
        for (String relay : best.relayUrls) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(relay);
        }

        prefs.edit()
                .putString(PREF_URLS, joined.toString())
                .putInt(PREF_VERSION, best.version)
                .putLong(PREF_LAST_SUCCESS, now)
                .putLong(PREF_REFRESH_MS, best.refreshMs)
                .apply();
    }

    static List<String> relayCandidates(Context context, String currentRelay) {
        LinkedHashSet<String> out = new LinkedHashSet<>();

        if (context != null) {
            String stored = AppPrefs.prefs(context).getString(PREF_URLS, "");
            if (stored != null && !stored.trim().isEmpty()) {
                String[] lines = stored.split("\\n");
                for (String line : lines) {
                    String relay = line.trim();
                    if (validRelay(relay)) out.add(relay);
                }
            }
        }

        if (validRelay(currentRelay)) out.add(currentRelay.trim());
        if (validRelay(BuildConfig.DEFAULT_RELAY_URL)) out.add(BuildConfig.DEFAULT_RELAY_URL.trim());

        return new ArrayList<>(out);
    }

    private static Config parse(String body) {
        try {
            JSONObject root = new JSONObject(body);
            if (root.optInt("schema", 1) != 1) return null;

            int version = root.optInt("configVersion", 0);
            if (version <= 0) return null;

            JSONArray relays = root.optJSONArray("relayUrls");
            if (relays == null || relays.length() == 0) return null;

            List<String> urls = new ArrayList<>();
            LinkedHashSet<String> dedupe = new LinkedHashSet<>();
            for (int i = 0; i < relays.length() && dedupe.size() < 8; i++) {
                String relay = relays.optString(i, "").trim();
                if (validRelay(relay)) dedupe.add(relay);
            }
            urls.addAll(dedupe);
            if (urls.isEmpty()) return null;

            long refreshSeconds = root.optLong("refreshSeconds", DEFAULT_REFRESH_MS / 1000L);
            long refreshMs = clampRefresh(refreshSeconds * 1000L);
            return new Config(version, urls, refreshMs);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean validRelay(String relay) {
        if (relay == null || relay.trim().isEmpty()) return false;
        try {
            URI uri = new URI(relay.trim());
            if (!"wss".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getHost() == null || uri.getHost().trim().isEmpty()) return false;
            int port = uri.getPort();
            return port == -1 || (port > 0 && port <= 65535);
        } catch (Exception e) {
            return false;
        }
    }

    private static long clampRefresh(long value) {
        return Math.max(MIN_REFRESH_MS, Math.min(MAX_REFRESH_MS, value));
    }

    private static final class Config {
        final int version;
        final List<String> relayUrls;
        final long refreshMs;

        Config(int version, List<String> relayUrls, long refreshMs) {
            this.version = version;
            this.relayUrls = relayUrls;
            this.refreshMs = refreshMs;
        }
    }

    private RemoteConfig() {}
}

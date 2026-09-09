package com.example.babymonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ResilientDns {
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final int MAX_DOH_BYTES = 64 * 1024;

    private static final String[][] RESOLVERS = {
            {"1.1.1.1", "cloudflare-dns.com", "/dns-query"},
            {"1.0.0.1", "cloudflare-dns.com", "/dns-query"},
            {"8.8.8.8", "dns.google", "/resolve"},
            {"8.8.4.4", "dns.google", "/resolve"}
    };

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    static InetAddress[] resolve(String host) throws Exception {
        if (host == null || host.trim().isEmpty()) throw new UnknownHostException("empty host");

        Exception systemError = null;
        try {
            InetAddress[] normal = InetAddress.getAllByName(host);
            if (normal != null && normal.length > 0) {
                CACHE.put(host, new CacheEntry(normal, System.currentTimeMillis() + CACHE_MS));
                return normal;
            }
        } catch (Exception e) {
            systemError = e;
        }

        CacheEntry cached = CACHE.get(host);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()
                && cached.addresses.length > 0) {
            return cached.addresses;
        }

        LinkedHashSet<String> literals = new LinkedHashSet<>();
        Exception lastDohError = null;

        for (String[] resolver : RESOLVERS) {
            try {
                literals.addAll(query(resolver[0], resolver[1], resolver[2], host, "A"));
                if (literals.isEmpty()) {
                    literals.addAll(query(resolver[0], resolver[1], resolver[2], host, "AAAA"));
                }
                if (!literals.isEmpty()) break;
            } catch (Exception e) {
                lastDohError = e;
            }
        }

        if (!literals.isEmpty()) {
            List<InetAddress> addresses = new ArrayList<>();
            for (String literal : literals) {
                try {
                    addresses.add(InetAddress.getByName(literal));
                } catch (Exception ignored) { }
            }
            if (!addresses.isEmpty()) {
                InetAddress[] out = addresses.toArray(new InetAddress[0]);
                CACHE.put(host, new CacheEntry(out, System.currentTimeMillis() + CACHE_MS));
                return out;
            }
        }

        UnknownHostException failure = new UnknownHostException(host);
        if (systemError != null) failure.addSuppressed(systemError);
        if (lastDohError != null) failure.addSuppressed(lastDohError);
        throw failure;
    }

    private static List<String> query(String resolverIp, String tlsHost, String basePath,
                                      String host, String type) throws Exception {
        String target = basePath + "?name=" + URLEncoder.encode(host, "UTF-8")
                + "&type=" + URLEncoder.encode(type, "UTF-8");
        String json = ResilientHttp.getDirectLiteral(resolverIp, tlsHost, target, MAX_DOH_BYTES);

        JSONObject root = new JSONObject(json);
        JSONArray answers = root.optJSONArray("Answer");
        List<String> out = new ArrayList<>();
        if (answers == null) return out;

        int wantedType = "AAAA".equals(type) ? 28 : 1;
        for (int i = 0; i < answers.length(); i++) {
            JSONObject answer = answers.optJSONObject(i);
            if (answer == null || answer.optInt("type", -1) != wantedType) continue;
            String data = answer.optString("data", "").trim();
            if (!data.isEmpty()) out.add(data);
        }
        return out;
    }

    private static final class CacheEntry {
        final InetAddress[] addresses;
        final long expiresAt;

        CacheEntry(InetAddress[] addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }

    private ResilientDns() {}
}

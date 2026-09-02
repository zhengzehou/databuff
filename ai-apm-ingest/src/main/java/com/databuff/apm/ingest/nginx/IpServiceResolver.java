package com.databuff.apm.ingest.nginx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolve the caller service name from an IP via an external HTTP API.
 * <p>
 * Nginx access logs only carry caller IPs ({@code remote_addr} / {@code http_x_forwarded_for});
 * to render a real service topology (caller → callee) the IP must be mapped back to a service
 * name. The API is queried by the bare IP (a possible {@code host:port} endpoint is stripped),
 * the response provides {@code appModule} + {@code appName} which are joined into the service
 * name (e.g. {@code uletm} + {@code apm-service} = {@code uletm/apm-service}). Results are cached
 * (bounded TTL) and the bare IP is used as the fallback service identity when no endpoint is
 * configured or the call fails.
 */
public final class IpServiceResolver {

    private static final Logger log = LoggerFactory.getLogger(IpServiceResolver.class);

    /** Default TTL for a resolved IP → service mapping (1 hour). */
    private static final long DEFAULT_TTL_MS = 60L * 60L * 1000L;
    private static final int DEFAULT_MAX_SIZE = 50_000;
    private static final int CONNECT_TIMEOUT_MS = 1_000;
    private static final int READ_TIMEOUT_MS = 1_500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String singleFieldName;
    private final Cache cache;

    public IpServiceResolver(String endpoint, String singleFieldName) {
        this(endpoint, singleFieldName, DEFAULT_TTL_MS, DEFAULT_MAX_SIZE);
    }

    public IpServiceResolver(String endpoint, String singleFieldName, long ttlMs, int maxSize) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.singleFieldName = singleFieldName;
        this.cache = this.endpoint == null ? null : new Cache(ttlMs, maxSize);
    }

    /**
     * Resolve the service name for a caller host. Accepts a bare IP or an {@code ip:port}
     * endpoint; the API is always queried by the bare IP only. Lookup hits the cache first and
     * only calls the API on a miss.
     *
     * @param host raw caller address (bare IP or {@code ip:port})
     * @return service name (e.g. {@code uletm-apm-service}) if resolved; otherwise the bare IP
     */
    public String resolve(String host) {
        if (host == null || host.isBlank()) {
            return host;
        }
        String key = bareIp(host.trim());
        if (key == null || key.isBlank()) {
            return host.trim(); // not an IP:port / IP shape → keep as-is
        }
        if (cache == null) {
            // No endpoint configured → fall back to the IP as the service identity.
            return key;
        }
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String resolved = fetchRemote(key);
        if (resolved == null || resolved.isBlank()) {
            resolved = key; // fallback to IP
        }
        cache.put(key, resolved);
        return resolved;
    }

    /**
     * Strip a trailing port from {@code ip:port}, returning the bare IP. A value with no port is
     * returned unchanged; a non-IP/port shape is returned as-is.
     */
    static String bareIp(String host) {
        if (host == null || host.isBlank()) {
            return host;
        }
        String v = host.trim();
        // IPv6 with brackets like [::1]:8080 → strip brackets.
        if (v.startsWith("[")) {
            int close = v.indexOf(']');
            if (close > 0) {
                return v.substring(0, close + 1);
            }
            return v;
        }
        int colon = v.lastIndexOf(':');
        if (colon > 0) {
            String ipPart = v.substring(0, colon);
            String portPart = v.substring(colon + 1);
            // Only strip when the remainder is purely numeric (a port).
            if (!portPart.isEmpty() && portPart.chars().allMatch(Character::isDigit)) {
                return ipPart;
            }
        }
        return v;
    }

    /**
     * Whether a value is a plain IP address (IPv4 or IPv6), which is not a real service name.
     * Used to avoid registering caller IPs into the service catalog.
     */
    public static boolean isIpAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        // IPv4: digits and dots only, e.g. 172.25.186.89
        if (v.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) {
            return true;
        }
        // IPv6: contains a colon (e.g. ::1, fe80::a:b). A host:port (e.g. 1.2.3.4:8080)
        // is an endpoint, not a service name, so also treat it as non-service.
        return v.contains(":");
    }

    /**
     * Strip a trailing {@code -group} suffix from a service name, e.g.
     * {@code tms-expressmsg-group} → {@code tms-expressmsg}. No-op when the name does not end
     * with {@code -group} (IPs and other names are left unchanged).
     */
    public static String normalizeServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return serviceName;
        }
        String trimmed = serviceName.trim();
        if (trimmed.endsWith("-group")) {
            return trimmed.substring(0, trimmed.length() - "-group".length());
        }
        return trimmed;
    }

    /**
     * Call the external API: {@code GET <endpoint>?ip=<bareIp>}, parse the JSON object and build
     * the service name from {@code appModule} + {@code appName}. Falls back to
     * {@link #singleFieldName} when those are absent.
     */
    private String fetchRemote(String bareIp) {
        // 验证 bareIp 是否为有效的 IP 地址格式，如果不是则直接返回 null
        if (!isIpAddress(bareIp)) {
            log.debug("Invalid IP format for: {}, skipping API call", bareIp);
            return null;
        }
        HttpURLConnection conn = null;
        try {
            String urlStr = endpoint + (endpoint.contains("?") ? "&" : "?") + "ip=" + URLEncoder.encode(bareIp, StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                log.warn("IpServiceResolver endpoint returned HTTP {} for ip={}", code, bareIp);
                return null;
            }
            String body = readBody(conn);
            if (body == null || body.isBlank()) {
                return null;
            }
            return extractServiceName(body);
        } catch (Exception e) {
            log.warn("IpServiceResolver call failed for ip={}: {}", bareIp, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Parse the JSON response and produce a service name. Preferred: {@code appModule}/{@code appName}
     * (e.g. {@code uletm/apm-service}). Fallback: the single configured field, then the whole
     * trimmed response.
     */
    private String extractServiceName(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String module = textOrNull(root, "appModule");
            String appName = textOrNull(root, "appName");
            if (module != null && !module.isBlank() && appName != null && !appName.isBlank()) {
                return module.trim() + "/" + appName.trim();
            }
            if (module != null && !module.isBlank()) {
                return module.trim();
            }
            if (appName != null && !appName.isBlank()) {
                return appName.trim();
            }
        } catch (Exception e) {
            log.warn("IpServiceResolver: cannot parse JSON response: {}", e.getMessage());
        }
        // Fallback to a single configured field (legacy).
        if (singleFieldName != null && !singleFieldName.isBlank()) {
            String single = extractField(body, singleFieldName);
            if (single != null && !single.isBlank()) {
                return single.trim();
            }
        }
        // No usable service name in the response → null, so the caller falls back to the IP.
        return null;
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** Minimal extraction of a string JSON field (used as a fallback when Jackson fails). */
    private String extractField(String body, String field) {
        String needle = "\"" + field + "\"";
        int idx = body.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colon = body.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        if (start < body.length() && body.charAt(start) == '"') {
            int end = body.indexOf('"', start + 1);
            if (end > start) {
                return body.substring(start + 1, end);
            }
            return null;
        }
        int end = start;
        while (end < body.length() && body.charAt(end) != ',' && body.charAt(end) != '}' && body.charAt(end) != ' ') {
            end++;
        }
        return body.substring(start, end);
    }

    private static String readBody(HttpURLConnection conn) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String trimmed = endpoint.trim();
        if (trimmed.isEmpty() || "none".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    /** Minimal bounded TTL cache (bare IP → service name). */
    private static final class Cache {
        private final long ttlMs;
        private final int maxSize;
        private final Map<String, Entry> store = new ConcurrentHashMap<>();

        Cache(long ttlMs, int maxSize) {
            this.ttlMs = Math.max(1L, ttlMs);
            this.maxSize = Math.max(1, maxSize);
        }

        String get(String key) {
            Entry entry = store.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expiresAtMs < System.currentTimeMillis()) {
                store.remove(key, entry);
                return null;
            }
            return entry.value;
        }

        void put(String key, String value) {
            if (store.size() >= maxSize) {
                evictOne();
            }
            store.put(key, new Entry(value, System.currentTimeMillis() + ttlMs));
        }

        private void evictOne() {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Entry> e : store.entrySet()) {
                if (e.getValue().expiresAtMs < now) {
                    store.remove(e.getKey(), e.getValue());
                    return;
                }
            }
            if (!store.isEmpty()) {
                store.keySet().stream().findFirst().ifPresent(store::remove);
            }
        }

        int size() {
            return store.size();
        }

        private record Entry(String value, long expiresAtMs) {
        }
    }
}

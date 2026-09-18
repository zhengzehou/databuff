package com.databuff.apm.web.prometheus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the server-side Prometheus HTTP API client. */
@ConfigurationProperties(prefix = "apm.prometheus")
public class PrometheusProperties {

    private boolean enabled;
    private String baseUrl = "";
    private String historicalBaseUrl = "";
    private String apiPrefix = "/api/v1";
    private String historicalApiPrefix = "/api/v1";
    private String job = "";
    private String bearerToken = "";
    private String username = "";
    private String password = "";
    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs = 10_000;
    private int maxRangeSeconds = 7 * 24 * 60 * 60;
    private int maxDataPoints = 720;
    private int maxConcurrentQueries = 6;

    public boolean configured() {
        return enabled && !baseUrl().isBlank();
    }

    public String baseUrl() {
        return trim(baseUrl);
    }

    public String historicalBaseUrl() {
        return trim(historicalBaseUrl);
    }

    public String apiPrefix() {
        return normalizeApiPrefix(apiPrefix);
    }

    public String historicalApiPrefix() {
        return normalizeApiPrefix(historicalApiPrefix);
    }

    private static String normalizeApiPrefix(String value) {
        value = trim(value);
        if (value.isEmpty()) {
            return "";
        }
        return "/" + value.replaceAll("^/+|/+$", "");
    }

    public String job() {
        return trim(job);
    }

    public String bearerToken() {
        return trim(bearerToken);
    }

    public String username() {
        return trim(username);
    }

    public String password() {
        return password == null ? "" : password;
    }

    public int connectTimeoutMs() {
        return clamp(connectTimeoutMs, 500, 60_000, 3_000);
    }

    public int readTimeoutMs() {
        return clamp(readTimeoutMs, 1_000, 120_000, 10_000);
    }

    public int maxRangeSeconds() {
        return clamp(maxRangeSeconds, 60, 30 * 24 * 60 * 60, 7 * 24 * 60 * 60);
    }

    public int maxDataPoints() {
        return clamp(maxDataPoints, 60, 5_000, 720);
    }

    public int maxConcurrentQueries() {
        return clamp(maxConcurrentQueries, 1, 16, 6);
    }

    /** Builds an endpoint below the configured Prometheus base URL. */
    public String endpoint(String endpoint) {
        return endpoint(baseUrl(), apiPrefix(), endpoint);
    }

    /** Builds an endpoint below the historical Prometheus base URL. */
    public String historicalEndpoint(String endpoint) {
        String historicalBase = historicalBaseUrl();
        if (historicalBase.isEmpty()) {
            return endpoint(endpoint);
        }
        return endpoint(historicalBase, historicalApiPrefix(), endpoint);
    }

    public boolean historicalConfigured() {
        return configured() && !historicalBaseUrl().isBlank();
    }

    private static String endpoint(String configuredBaseUrl, String prefix, String endpoint) {
        String base = configuredBaseUrl.replaceAll("/+$", "");
        String normalizedEndpoint = endpoint == null ? "" : endpoint.replaceAll("^/+", "");
        if (!prefix.isEmpty() && base.endsWith(prefix)) {
            return base + "/" + normalizedEndpoint;
        }
        return base + prefix + "/" + normalizedEndpoint;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        int normalized = value > 0 ? value : fallback;
        return Math.min(max, Math.max(min, normalized));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getHistoricalBaseUrl() {
        return historicalBaseUrl;
    }

    public void setHistoricalBaseUrl(String historicalBaseUrl) {
        this.historicalBaseUrl = historicalBaseUrl;
    }

    public String getApiPrefix() {
        return apiPrefix;
    }

    public void setApiPrefix(String apiPrefix) {
        this.apiPrefix = apiPrefix;
    }

    public String getHistoricalApiPrefix() {
        return historicalApiPrefix;
    }

    public void setHistoricalApiPrefix(String historicalApiPrefix) {
        this.historicalApiPrefix = historicalApiPrefix;
    }

    public String getJob() {
        return job;
    }

    public void setJob(String job) {
        this.job = job;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxRangeSeconds() {
        return maxRangeSeconds;
    }

    public void setMaxRangeSeconds(int maxRangeSeconds) {
        this.maxRangeSeconds = maxRangeSeconds;
    }

    public int getMaxDataPoints() {
        return maxDataPoints;
    }

    public void setMaxDataPoints(int maxDataPoints) {
        this.maxDataPoints = maxDataPoints;
    }

    public int getMaxConcurrentQueries() {
        return maxConcurrentQueries;
    }

    public void setMaxConcurrentQueries(int maxConcurrentQueries) {
        this.maxConcurrentQueries = maxConcurrentQueries;
    }
}

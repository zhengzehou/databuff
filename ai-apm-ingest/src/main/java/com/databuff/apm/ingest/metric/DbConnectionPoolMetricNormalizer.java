package com.databuff.apm.ingest.metric;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps OpenTelemetry database connection-pool metrics onto DataBuff metric
 * identifiers.
 *
 * <p>The OpenTelemetry database pool conventions are still versioned: older
 * agents emit {@code db.client.connections.*}, while newer agents emit
 * {@code db.client.connection.*}. DataBuff keeps its existing
 * {@code service.db.connection.pool.*} identifiers, so the normalization is
 * intentionally kept at the ingest boundary.</p>
 */
public final class DbConnectionPoolMetricNormalizer {

    private static final String LEGACY_USAGE = "db.client.connections.usage";
    private static final String STABLE_COUNT = "db.client.connection.count";

    private static final String LEGACY_IDLE_MAX = "db.client.connections.idle.max";
    private static final String STABLE_IDLE_MAX = "db.client.connection.idle.max";
    private static final String LEGACY_IDLE_MIN = "db.client.connections.idle.min";
    private static final String STABLE_IDLE_MIN = "db.client.connection.idle.min";
    private static final String LEGACY_MAX = "db.client.connections.max";
    private static final String STABLE_MAX = "db.client.connection.max";
    private static final String LEGACY_PENDING = "db.client.connections.pending_requests";
    private static final String STABLE_PENDING = "db.client.connection.pending_requests";
    private static final String LEGACY_TIMEOUTS = "db.client.connections.timeouts";
    private static final String STABLE_TIMEOUTS = "db.client.connection.timeouts";
    private static final String LEGACY_CREATE_TIME = "db.client.connections.create_time";
    private static final String STABLE_CREATE_TIME = "db.client.connection.create_time";
    private static final String LEGACY_WAIT_TIME = "db.client.connections.wait_time";
    private static final String STABLE_WAIT_TIME = "db.client.connection.wait_time";
    private static final String LEGACY_USE_TIME = "db.client.connections.use_time";
    private static final String STABLE_USE_TIME = "db.client.connection.use_time";

    private static final String POOL_STATE = "db.client.connection.state";
    private static final String LEGACY_POOL_STATE = "state";

    private DbConnectionPoolMetricNormalizer() {
    }

    public record NormalizedMetric(String identifier, Number value) {
    }

    /**
     * Returns true for all currently known OTel connection-pool metric names.
     * Metrics that do not have a matching DataBuff field are deliberately
     * recognized here and skipped by the caller instead of being treated as
     * arbitrary custom metrics.
     */
    public static boolean isOpenTelemetryMetric(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return false;
        }
        return switch (metricName.trim()) {
            case LEGACY_USAGE, STABLE_COUNT,
                 LEGACY_IDLE_MAX, STABLE_IDLE_MAX,
                 LEGACY_IDLE_MIN, STABLE_IDLE_MIN,
                 LEGACY_MAX, STABLE_MAX,
                 LEGACY_PENDING, STABLE_PENDING,
                 LEGACY_TIMEOUTS, STABLE_TIMEOUTS,
                 LEGACY_CREATE_TIME, STABLE_CREATE_TIME,
                 LEGACY_WAIT_TIME, STABLE_WAIT_TIME,
                 LEGACY_USE_TIME, STABLE_USE_TIME -> true;
            default -> false;
        };
    }

    public static Optional<String> normalizeIdentifier(
            String otelName,
            Map<String, String> attributes) {
        return normalize(otelName, attributes, 0).stream()
                .map(NormalizedMetric::identifier)
                .findFirst();
    }

    /**
     * Normalizes scalar connection-pool metrics. The usage/count metric is
     * split by its pool state because DataBuff stores used and idle values in
     * separate fields.
     */
    public static List<NormalizedMetric> normalize(
            String otelName,
            Map<String, String> attributes,
            Number value) {
        if (otelName == null || otelName.isBlank() || value == null) {
            return List.of();
        }
        String name = otelName.trim();
        if (name.startsWith("service.db.connection.pool")) {
            return List.of(new NormalizedMetric(name, value));
        }
        return switch (name) {
            case LEGACY_USAGE, STABLE_COUNT -> mapUsage(attributes, value);
            case LEGACY_MAX, STABLE_MAX ->
                    List.of(new NormalizedMetric("service.db.connection.pool.maxSize", value));
            case LEGACY_PENDING, STABLE_PENDING ->
                    List.of(new NormalizedMetric("service.db.connection.pool.waiterNum", value));
            // There is no equivalent field in the current DataBuff pool-state
            // table for idle min/max, timeouts, create time, or use time.
            case LEGACY_IDLE_MAX, STABLE_IDLE_MAX,
                 LEGACY_IDLE_MIN, STABLE_IDLE_MIN,
                 LEGACY_TIMEOUTS, STABLE_TIMEOUTS,
                 LEGACY_CREATE_TIME, STABLE_CREATE_TIME,
                 LEGACY_WAIT_TIME, STABLE_WAIT_TIME,
                 LEGACY_USE_TIME, STABLE_USE_TIME -> List.of();
            default -> List.of();
        };
    }

    /**
     * Converts the OTel wait-time Histogram into the two scalar fields used by
     * {@code metric_service_db_connection_pool_get}. DataBuff stores the
     * accumulated wait time in milliseconds and the observation count in
     * {@code count}; callers can calculate the average as sum(waitTime) /
     * sum(count).
     */
    public static List<NormalizedMetric> normalizeHistogram(
            String otelName,
            Map<String, String> attributes,
            double sum,
            long count,
            String unit) {
        if (!isWaitTimeMetric(otelName)) {
            return List.of();
        }
        double waitTimeMillis = toMillis(sum, otelName, unit);
        return List.of(
                new NormalizedMetric("service.db.connection.pool.get.waitTime", waitTimeMillis),
                new NormalizedMetric("service.db.connection.pool.get.count", count));
    }

    /**
     * Returns the pool name from either DataBuff's legacy attribute or the
     * legacy/stable OpenTelemetry attributes.
     */
    public static String poolName(Map<String, String> attributes) {
        return attribute(
                attributes,
                "db.connection.pool.name",
                "db.client.connection.pool.name",
                "pool.name");
    }

    private static List<NormalizedMetric> mapUsage(
            Map<String, String> attributes,
            Number value) {
        String state = attribute(attributes, POOL_STATE, LEGACY_POOL_STATE);
        if (state == null) {
            return List.of();
        }
        return switch (state.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "used", "active", "in_use" ->
                    List.of(new NormalizedMetric("service.db.connection.pool.activeSize", value));
            case "idle" ->
                    List.of(new NormalizedMetric("service.db.connection.pool.idleSize", value));
            default -> List.of();
        };
    }

    private static boolean isWaitTimeMetric(String metricName) {
        return LEGACY_WAIT_TIME.equals(metricName) || STABLE_WAIT_TIME.equals(metricName);
    }

    private static double toMillis(double value, String metricName, String unit) {
        String normalizedUnit = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if (normalizedUnit.startsWith("{") && normalizedUnit.endsWith("}")) {
            normalizedUnit = normalizedUnit.substring(1, normalizedUnit.length() - 1).trim();
        }
        return switch (normalizedUnit) {
            case "s", "sec", "second", "seconds" -> value * 1_000.0;
            case "us", "µs", "microsecond", "microseconds" -> value / 1_000.0;
            case "ns", "nanosecond", "nanoseconds" -> value / 1_000_000.0;
            case "ms", "millisecond", "milliseconds" -> value;
            case "" -> isStableWaitTimeMetric(metricName) ? value * 1_000.0 : value;
            default -> value;
        };
    }

    private static boolean isStableWaitTimeMetric(String metricName) {
        return STABLE_WAIT_TIME.equals(metricName);
    }

    private static String attribute(Map<String, String> attributes, String... keys) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = attributes.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

package com.databuff.apm.ingest.metric;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DbConnectionPoolMetricNormalizerTest {

    @Test
    void mapsLegacyUsageByPoolState() {
        assertThat(DbConnectionPoolMetricNormalizer.normalize(
                "db.client.connections.usage",
                Map.of("pool.name", "orders-pool", "state", "used"),
                3L))
                .containsExactly(new DbConnectionPoolMetricNormalizer.NormalizedMetric(
                        "service.db.connection.pool.activeSize", 3L));

        assertThat(DbConnectionPoolMetricNormalizer.normalize(
                "db.client.connections.usage",
                Map.of("pool.name", "orders-pool", "state", "idle"),
                5L))
                .containsExactly(new DbConnectionPoolMetricNormalizer.NormalizedMetric(
                        "service.db.connection.pool.idleSize", 5L));
    }

    @Test
    void mapsStablePoolStateAndScalarMetrics() {
        assertThat(DbConnectionPoolMetricNormalizer.normalize(
                "db.client.connection.count",
                Map.of("db.client.connection.pool.name", "orders-pool",
                        "db.client.connection.state", "active"),
                4L))
                .containsExactly(new DbConnectionPoolMetricNormalizer.NormalizedMetric(
                        "service.db.connection.pool.activeSize", 4L));

        assertThat(DbConnectionPoolMetricNormalizer.normalize(
                "db.client.connection.max",
                Map.of("db.client.connection.pool.name", "orders-pool"),
                20L))
                .containsExactly(new DbConnectionPoolMetricNormalizer.NormalizedMetric(
                        "service.db.connection.pool.maxSize", 20L));

        assertThat(DbConnectionPoolMetricNormalizer.normalize(
                "db.client.connection.pending_requests",
                Map.of("db.client.connection.pool.name", "orders-pool"),
                2L))
                .containsExactly(new DbConnectionPoolMetricNormalizer.NormalizedMetric(
                        "service.db.connection.pool.waiterNum", 2L));
    }

    @Test
    void mapsWaitTimeHistogramAndConvertsSecondsToMillis() {
        List<DbConnectionPoolMetricNormalizer.NormalizedMetric> normalized =
                DbConnectionPoolMetricNormalizer.normalizeHistogram(
                        "db.client.connection.wait_time",
                        Map.of("db.client.connection.pool.name", "orders-pool"),
                        1.25,
                        10,
                        "s");

        assertThat(normalized).containsExactly(
                new DbConnectionPoolMetricNormalizer.NormalizedMetric(
                        "service.db.connection.pool.get.waitTime", 1250.0),
                new DbConnectionPoolMetricNormalizer.NormalizedMetric(
                        "service.db.connection.pool.get.count", 10L));
    }

    @Test
    void keepsLegacyWaitTimeMilliseconds() {
        assertThat(DbConnectionPoolMetricNormalizer.normalizeHistogram(
                "db.client.connections.wait_time",
                Map.of("pool.name", "orders-pool"),
                25.0,
                2,
                "ms").get(0).value())
                .isEqualTo(25.0);
    }

    @Test
    void resolvesAllSupportedPoolNameAttributes() {
        assertThat(DbConnectionPoolMetricNormalizer.poolName(
                Map.of("db.connection.pool.name", "databuff")))
                .isEqualTo("databuff");
        assertThat(DbConnectionPoolMetricNormalizer.poolName(
                Map.of("db.client.connection.pool.name", "otel-stable")))
                .isEqualTo("otel-stable");
        assertThat(DbConnectionPoolMetricNormalizer.poolName(
                Map.of("pool.name", "otel-legacy")))
                .isEqualTo("otel-legacy");
    }

    @Test
    void skipsPoolMetricsWithoutDataBuffField() {
        assertThat(DbConnectionPoolMetricNormalizer.normalize(
                "db.client.connection.timeouts",
                Map.of("pool.name", "orders-pool"),
                1L))
                .isEmpty();
        assertThat(DbConnectionPoolMetricNormalizer.normalizeHistogram(
                "db.client.connection.use_time",
                Map.of("pool.name", "orders-pool"),
                1.0,
                1,
                "s"))
                .isEmpty();
    }
}

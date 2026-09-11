package com.databuff.apm.ingest.otel;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbConnectionPoolOtelConverterTest {

    private final OtelConverter converter = new OtelConverter();

    @Test
    void convertsLegacyUsedAndIdleMetricsToPoolStateFields() {
        ExportMetricsServiceRequest request = metricsRequest(
                Metric.newBuilder()
                        .setName("db.client.connections.usage")
                        .setGauge(Gauge.newBuilder()
                                .addDataPoints(numberPoint(3, kv("pool.name", "orders-pool"), kv("state", "used")))
                                .addDataPoints(numberPoint(5, kv("pool.name", "orders-pool"), kv("state", "idle"))))
                        .build());

        List<OtelConverter.ConvertedMetric> converted = converter.convertMetrics(request);

        assertThat(converted).extracting(metric -> metric.line().metric())
                .containsExactly(
                        "service.db.connection.pool.activeSize",
                        "service.db.connection.pool.idleSize");
        assertThat(converted).extracting(metric -> metric.line().value())
                .containsExactly(3.0, 5.0);
        assertThat(converted).allSatisfy(metric ->
                assertThat(metric.line().connectionPoolName()).isEqualTo("orders-pool"));
    }

    @Test
    void convertsStablePoolMetricsToPoolStateFields() {
        ExportMetricsServiceRequest request = metricsRequest(
                Metric.newBuilder()
                        .setName("db.client.connection.max")
                        .setGauge(Gauge.newBuilder()
                                .addDataPoints(numberPoint(20,
                                        kv("db.client.connection.pool.name", "orders-pool"))))
                        .build(),
                Metric.newBuilder()
                        .setName("db.client.connection.pending_requests")
                        .setGauge(Gauge.newBuilder()
                                .addDataPoints(numberPoint(2,
                                        kv("db.client.connection.pool.name", "orders-pool"))))
                        .build());

        List<OtelConverter.ConvertedMetric> converted = converter.convertMetrics(request);

        assertThat(converted).extracting(metric -> metric.line().metric())
                .containsExactly(
                        "service.db.connection.pool.maxSize",
                        "service.db.connection.pool.waiterNum");
        assertThat(converted).extracting(metric -> metric.line().connectionPoolName())
                .containsOnly("orders-pool");
    }

    @Test
    void convertsWaitTimeHistogramToPoolGetFields() {
        ExportMetricsServiceRequest request = metricsRequest(
                Metric.newBuilder()
                        .setName("db.client.connection.wait_time")
                        .setUnit("s")
                        .setHistogram(Histogram.newBuilder()
                                .addDataPoints(HistogramDataPoint.newBuilder()
                                        .setSum(1.25)
                                        .setCount(10)
                                        .addAttributes(kv(
                                                "db.client.connection.pool.name", "orders-pool"))))
                        .build());

        List<OtelConverter.ConvertedMetric> converted = converter.convertMetrics(request);

        assertThat(converted).extracting(metric -> metric.line().metric())
                .containsExactly(
                        "service.db.connection.pool.get.waitTime",
                        "service.db.connection.pool.get.count");
        assertThat(converted).extracting(metric -> metric.line().value())
                .containsExactly(1250.0, 10L);
        assertThat(converted).allSatisfy(metric ->
                assertThat(metric.line().connectionPoolName()).isEqualTo("orders-pool"));
    }

    private static ExportMetricsServiceRequest metricsRequest(Metric... metrics) {
        ScopeMetrics.Builder scope = ScopeMetrics.newBuilder();
        scope.addAllMetrics(List.of(metrics));
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(kv("service.name", "orders"))
                                .addAttributes(kv("service.instance.id", "orders-1")))
                        .addScopeMetrics(scope))
                .build();
    }

    private static NumberDataPoint.Builder numberPoint(long value, KeyValue... attributes) {
        NumberDataPoint.Builder point = NumberDataPoint.newBuilder()
                .setTimeUnixNano(1_700_000_000_000_000_000L)
                .setAsInt(value);
        point.addAllAttributes(List.of(attributes));
        return point;
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }
}

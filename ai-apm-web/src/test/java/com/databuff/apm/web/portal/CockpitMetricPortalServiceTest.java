package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.web.cockpit.TrafficLightService;
import com.databuff.apm.web.metric.MetricQueryService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KPI 汇总 / 趋势的查询口径单测。
 * 通过 mock MetricQueryService 验证：sum 语义、加权比率与标量派生 avg 指标
 * （平均耗时 avgDuration 等）均按窗口总量（metricTotal）直接聚合，不经过按时间桶
 * GROUP BY 出序列（metricChart）再二次聚合；趋势按自然日对齐昨日窗口。
 */
class CockpitMetricPortalServiceTest {

    @Test
    void aggregatesKpiItemsAsPerItemWindowTotals() {
        MetricQueryService queryService = mock(MetricQueryService.class);
        TrafficLightService trafficLightService = mock(TrafficLightService.class);
        when(trafficLightService.longConnServices()).thenReturn(List.of());
        when(queryService.metricTotal(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) body.get("query");
            @SuppressWarnings("unchecked")
            Map<String, Object> metricQuery = (Map<String, Object>) query.get("A");
            String metric = String.valueOf(metricQuery.get("metric"));
            return switch (metric) {
                case "service.cnt" -> new ApmQueryModels.MetricTotalSnapshot(42D);
                case "service.error.pct" -> new ApmQueryModels.MetricTotalSnapshot(3.2D);
                default -> new ApmQueryModels.MetricTotalSnapshot(0D);
            };
        });
        CockpitMetricPortalService service = new CockpitMetricPortalService(queryService, trafficLightService);

        List<Map<String, Object>> rows = service.kpiSummary(Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_000_120L,
                "interval", 60,
                "items", List.of(
                        Map.of("key", "请求量", "metric", "service.cnt", "aggs", "sum"),
                        Map.of("key", "错误率", "metric", "service.error.pct", "aggs", "avg"))));

        assertThat(rows).containsExactly(
                Map.of("key", "请求量", "today", 42D, "yesterday", 42D),
                Map.of("key", "错误率", "today", 3.2D, "yesterday", 3.2D));
        // sum 语义与加权比率均按窗口总量（metricTotal）直接聚合：每项每窗口一次查询，
        // 不经过按时间桶 GROUP BY 出序列（metricChart）后再聚合
        verify(queryService, times(4)).metricTotal(anyMap());
        verify(queryService, never()).metricChart(anyMap());
    }

    @Test
    void aggregatesAvailabilityAndUnavailabilityAsWindowTotals() {
        MetricQueryService queryService = mock(MetricQueryService.class);
        TrafficLightService trafficLightService = mock(TrafficLightService.class);
        when(trafficLightService.longConnServices()).thenReturn(List.of());
        when(queryService.metricTotal(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) body.get("query");
            @SuppressWarnings("unchecked")
            Map<String, Object> metricQuery = (Map<String, Object>) query.get("A");
            String metric = String.valueOf(metricQuery.get("metric"));
            return switch (metric) {
                case "service.http.availability.pct" -> new ApmQueryModels.MetricTotalSnapshot(98.5D);
                case "service.http.unavailability.pct" -> new ApmQueryModels.MetricTotalSnapshot(1.5D);
                case "service.http.cnt" -> new ApmQueryModels.MetricTotalSnapshot(1_000D);
                default -> new ApmQueryModels.MetricTotalSnapshot(0D);
            };
        });
        CockpitMetricPortalService service = new CockpitMetricPortalService(queryService, trafficLightService);
        List<Map<String, Object>> inbound = List.of(
                Map.of("left", "isIn", "operator", "=", "right", "1", "connector", "AND"));

        List<Map<String, Object>> rows = service.kpiSummary(Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_000_120L,
                "interval", 60,
                "items", List.of(
                        Map.of("key", "可用性 SLA", "metric", "service.http.availability.pct",
                                "aggs", "avg", "filters", inbound),
                        Map.of("key", "服务不可用率", "metric", "service.http.unavailability.pct",
                                "aggs", "avg", "filters", inbound),
                        Map.of("key", "请求量", "metric", "service.http.cnt",
                                "aggs", "sum", "filters", inbound))));

        assertThat(rows).containsExactly(
                Map.of("key", "可用性 SLA", "today", 98.5D, "yesterday", 98.5D),
                Map.of("key", "服务不可用率", "today", 1.5D, "yesterday", 1.5D),
                Map.of("key", "请求量", "today", 1_000D, "yesterday", 1_000D));
        // 三个指标均按窗口总量（metricTotal）聚合，每项每窗口一次查询。可用性/不可用率互补
        // 由 SQL 派生表达式保证（unavailability = 100 - availability）；Java 侧 enforceAvailabilityComplement
        // 在当前重构中注释保留，未启用
        verify(queryService, times(6)).metricTotal(anyMap());
        verify(queryService, never()).metricChart(anyMap());
    }

    @Test
    void batchesTrendItemsAndKeepsPublicResponseShape() {
        MetricQueryService queryService = mock(MetricQueryService.class);
        TrafficLightService trafficLightService = mock(TrafficLightService.class);
        when(trafficLightService.longConnServices()).thenReturn(List.of());
        when(queryService.metricSeriesBatch(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) body.get("query");
            @SuppressWarnings("unchecked")
            Map<String, Object> metricQuery = (Map<String, Object>) query.get("A");
            @SuppressWarnings("unchecked")
            List<String> metrics = (List<String>) metricQuery.get("metrics");
            Map<String, MetricQueryService.MetricSeriesBatchSnapshot> series = new LinkedHashMap<>();
            for (String metric : metrics) {
                series.put(metric, new MetricQueryService.MetricSeriesBatchSnapshot(
                        "req/s",
                        List.of(
                                new ApmQueryModels.MetricSeriesPoint(1_710_000_000L, 10D),
                                new ApmQueryModels.MetricSeriesPoint(1_710_000_060L, 20D))));
            }
            return series;
        });
        CockpitMetricPortalService service = new CockpitMetricPortalService(queryService, trafficLightService);

        List<Map<String, Object>> rows = service.metricTrends(Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_000_120L,
                "interval", 60,
                "items", List.of(
                        Map.of("key", "请求量", "metric", "service.cnt", "aggs", "sum"),
                        Map.of("key", "错误率", "metric", "service.error.pct", "aggs", "avg"))));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("key", "请求量")
                .containsEntry("unit", "req/s")
                .containsEntry("today", List.of(
                        List.of(1_710_000_000_000L, 10D),
                        List.of(1_710_000_060_000L, 20D)))
                .containsEntry("yesterday", List.of(
                        // 「较昨日」按自然日对齐：昨日序列统一平移 24h（86400s）落到今日时间轴
                        List.of(1_710_000_000_000L + 86_400_000L, 10D),
                        List.of(1_710_000_060_000L + 86_400_000L, 20D)));
        // 同批两指标合并为一次批量序列查询，今日/昨日各一次
        verify(queryService, times(2)).metricSeriesBatch(anyMap());
    }

    @Test
    void routesDerivedAvgMetricsThroughWindowTotalsInsteadOfSeries() {
        MetricQueryService queryService = mock(MetricQueryService.class);
        TrafficLightService trafficLightService = mock(TrafficLightService.class);
        when(trafficLightService.longConnServices()).thenReturn(List.of());
        when(queryService.metricTotal(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) body.get("query");
            @SuppressWarnings("unchecked")
            Map<String, Object> metricQuery = (Map<String, Object>) query.get("A");
            String metric = String.valueOf(metricQuery.get("metric"));
            return switch (metric) {
                case "service.avgDuration" -> new ApmQueryModels.MetricTotalSnapshot(250D);
                case "service.http.client_error.pct" -> new ApmQueryModels.MetricTotalSnapshot(3.5D);
                default -> new ApmQueryModels.MetricTotalSnapshot(0D);
            };
        });
        CockpitMetricPortalService service = new CockpitMetricPortalService(queryService, trafficLightService);
        List<Map<String, Object>> inbound = List.of(
                Map.of("left", "isIn", "operator", "=", "right", "1", "connector", "AND"));

        List<Map<String, Object>> rows = service.kpiSummary(Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_000_120L,
                "interval", 60,
                "items", List.of(
                        Map.of("key", "平均响应时间", "metric", "service.avgDuration", "aggs", "avg"),
                        Map.of("key", "HTTP 4xx 率", "metric", "service.http.client_error.pct",
                                "aggs", "avg", "filters", inbound))));

        assertThat(rows).containsExactly(
                Map.of("key", "平均响应时间", "today", 250D, "yesterday", 250D),
                Map.of("key", "HTTP 4xx 率", "today", 3.5D, "yesterday", 3.5D));
        // avgDuration 与 client_error.pct 存在标量派生表达式：直接按窗口总量（metricTotal）返回
        // SUM(sumDuration)/SUM(cnt) 加权均值，不再按时间桶 GROUP BY 出序列（metricChart）后对
        // 分钟均值做简单平均——明细按分钟存放，跨分钟不加权平均会偏离真实窗口均值
        verify(queryService, times(4)).metricTotal(anyMap());
        verify(queryService, never()).metricChart(anyMap());
    }
}

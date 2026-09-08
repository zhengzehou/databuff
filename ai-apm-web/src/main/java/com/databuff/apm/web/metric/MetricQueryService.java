package com.databuff.apm.web.metric;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.common.query.ApmQueryModels.HttpEndpointPoint;
import com.databuff.apm.common.query.ApmQueryModels.HttpLatencyBucketPoint;
import com.databuff.apm.common.query.ApmQueryModels.MetricSeriesPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceMetricPoint;
import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.portal.PortalTimeParser;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.MetricIdentifierParser;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MetricQueryService {
    private static final Logger log = LoggerFactory.getLogger(MetricQueryService.class);

    private final ApmReadRepository readRepository;
    private final String metricDatabase;

    public MetricQueryService(ApmReadRepository readRepository, ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
    }

    public record MetricTotalsBatchSnapshot(Map<String, Double> totals, long matchedRows) {
        static MetricTotalsBatchSnapshot empty() {
            return new MetricTotalsBatchSnapshot(Map.of(), 0);
        }
    }

    public record MetricSeriesBatchSnapshot(String unit, List<MetricSeriesPoint> points) {
    }

    public List<ServiceMetricPoint> serviceSeries(ServiceSeriesRequest request) {
        try {
            String sql = MetricQueryBuilder.serviceSeriesSql(
                    metricDatabase, request.service(), request.from(), request.to());
            return readRepository.queryServiceMetrics(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<HttpEndpointPoint> httpEndpoints(HttpQueryRequest request) {
        try {
            String sql = MetricQueryBuilder.httpEndpointSummarySql(
                    metricDatabase,
                    request.service(),
                    request.from(),
                    request.to(),
                    request.limit(),
                    request.httpMethod(),
                    request.httpCode(),
                    request.urlContains());
            return readRepository.queryHttpEndpoints(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<HttpLatencyBucketPoint> httpLatencyBuckets(HttpQueryRequest request) {
        try {
            String sql = MetricQueryBuilder.httpLatencyDistributionSql(
                    metricDatabase,
                    request.service(),
                    request.from(),
                    request.to(),
                    request.httpMethod(),
                    request.httpCode(),
                    request.urlContains());
            return readRepository.queryHttpLatencyBuckets(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Map<String, List<String>> lastTags(LastTagsRequest request) {
        if (request.metrics() == null || request.metrics().isEmpty()
                || request.by() == null || request.by().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(request.metrics().get(0));
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String filters = buildFilterClause(request.filters());
            Set<String> knownTags = MetricSchemaRegistry.schema(parsed.measurement())
                    .map(schema -> new HashSet<>(schema.tagColumns()))
                    .orElse(null);
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String tag : request.by()) {
                // Schema stores Doris column names (service_id); portal requests use camelCase
                // (serviceId). Skip tags that exist in neither form — e.g. stale serviceType on
                // metric_service — so one bad column cannot wipe all filter keys.
                String column = MetricIdentifierParser.toColumnName(tag);
                if (knownTags != null && !knownTags.contains(tag) && !knownTags.contains(column)) {
                    continue;
                }
                try {
                    String sql = MetricQueryBuilder.metricTagDistinctSql(
                            metricDatabase, table, tag, request.fromMillis(), request.toMillis(), filters);
                    result.put(tag, readRepository.queryDistinctTags(sql));
                } catch (Exception e) {
                    result.put(tag, List.of());
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public List<MetricSeriesPoint> metricSeries(MetricSeriesRequest request) {
        if (request.metric() == null || request.metric().isBlank()) {
            return Collections.emptyList();
        }
        try {
            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(request.metric());
            if ("service.exception".equals(parsed.measurement())) {
                return serviceErrorSeries(request);
            }
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String filters = buildFilterClause(request.filters());
            String fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);
            String sql = MetricQueryBuilder.metricFieldSeriesSql(
                    metricDatabase, table, fieldColumn, request.fromMillis(), request.toMillis(), filters);
            List<MetricSeriesPoint> points = readRepository.queryMetricSeries(sql);
            return TimeSeriesFillUtil.fillMetricSeries(
                    points, request.fromMillis(), request.toMillis(), 60);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> metricChart(Map<String, Object> body) {
        long startedNanos = System.nanoTime();
        try {
            Map<String, Object> queryRoot = body.get("query") instanceof Map<?, ?> queryMap
                    ? (Map<String, Object>) queryMap
                    : Map.of();
            Map<String, Object> metricQuery = queryRoot.get("A") instanceof Map<?, ?> aMap
                    ? (Map<String, Object>) aMap
                    : Map.of();
            String metric = String.valueOf(metricQuery.getOrDefault("metric", ""));
            if (metric.isBlank()) {
                return List.of();
            }
            long start = normalizeTime(toLong(body.get("start")));
            long end = normalizeTime(toLong(body.get("end")));
            int interval = toInt(body.get("interval"), 60);
            List<MetricFilter> filters = parseFilters(metricQuery.get("from"));
            List<String> by = parseStringList(metricQuery.get("by"));
            Map<String, Object> order = metricQuery.get("order") instanceof Map<?, ?> orderMap
                    ? (Map<String, Object>) orderMap
                    : Map.of();
            int topLimit = toInt(order.get("limit"), 50);
            String aggs = stringValue(metricQuery.get("aggs"));

            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(metric);
            if ("service.exception".equals(parsed.measurement())) {
                List<Map<String, Object>> result = List.of(buildChartSeries(
                        serviceErrorSeries(new MetricSeriesRequest(metric, start, end, filters)),
                        Map.of(),
                        metric,
                        start,
                        end,
                        interval));
                log.info("metricChart metric={} exceptionSeries rows={} elapsedMs={}",
                        metric, result.size(), (System.nanoTime() - startedNanos) / 1_000_000D);
                return result;
            }
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String filterClause = buildFilterClause(filters);
            String fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);

            if (!by.isEmpty()) {
                String groupBy = by.get(0);
                String groupColumn = resolveChartGroupColumn(groupBy, parsed.measurement());
                String topSql = MetricQueryBuilder.metricTopGroupsSql(
                        metricDatabase, table, fieldColumn, groupColumn,
                        toMillis(start), toMillis(end), filterClause, topLimit, aggs);
                List<String> groups = readRepository.queryTopGroups(topSql);
                List<Map<String, Object>> series = new ArrayList<>();
                for (String groupValue : groups) {
                    String sql = MetricQueryBuilder.metricFieldSeriesByGroupSql(
                            metricDatabase, table, fieldColumn, groupColumn, groupValue,
                            toMillis(start), toMillis(end), filterClause, interval, aggs);
                    series.add(buildChartSeries(
                            readRepository.queryMetricSeries(sql),
                            Map.of(groupBy, groupValue),
                            metric,
                            start,
                            end,
                            interval));
                }
                log.info("metricChart metric={} by={} groups={} rows={} elapsedMs={}",
                        metric, groupBy, groups.size(), series.size(), (System.nanoTime() - startedNanos) / 1_000_000D);
                return series;
            }

            String sql = MetricQueryBuilder.metricFieldSeriesSql(
                    metricDatabase, table, fieldColumn, toMillis(start), toMillis(end), filterClause, interval, aggs);
            List<MetricSeriesPoint> raw = readRepository.queryMetricSeries(sql);
            log.info("metricChart metric={} by=none rows={} elapsedMs={} sql={}",
                    metric, raw.size(), (System.nanoTime() - startedNanos) / 1_000_000D, sql);
            return List.of(buildChartSeries(raw, Map.of(), metric, start, end, interval));
        } catch (Exception e) {
            log.error("metricChart error metric={} elapsedMs={}",
                    body.get("query"), (System.nanoTime() - startedNanos) / 1_000_000D, e);
            return List.of();
        }
    }

    /**
     * 无分桶窗口聚合：对目标表按条件直接 SUM 出窗口总量，返回 { total, matchedRows }。
     * 供只需一个总数的场景（如 KPI 汇总），与 metricChart 出序列后再求和等价，
     * 但省去按时间桶 GROUP BY 和序列回传的开销。入参同 metricChart 的 query.A（metric/from）。
     * matchedRows=0 表示窗口内无数据行（区别于真实 0 值），供上层做 service_id 回退重试。
     */
    public ApmQueryModels.MetricTotalSnapshot metricTotal(Map<String, Object> body) {
        try {
            Map<String, Object> queryRoot = body.get("query") instanceof Map<?, ?> queryMap
                    ? (Map<String, Object>) queryMap
                    : Map.of();
            Map<String, Object> metricQuery = queryRoot.get("A") instanceof Map<?, ?> aMap
                    ? (Map<String, Object>) aMap
                    : Map.of();
            String metric = String.valueOf(metricQuery.getOrDefault("metric", ""));
            if (metric.isBlank()) {
                return new ApmQueryModels.MetricTotalSnapshot(0, 0);
            }
            long start = normalizeTime(toLong(body.get("start")));
            long end = normalizeTime(toLong(body.get("end")));
            List<MetricFilter> filters = parseFilters(metricQuery.get("from"));

            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(metric);
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String filterClause = buildFilterClause(filters);
            String fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);
            String sql = MetricQueryBuilder.metricFieldTotalSql(
                    metricDatabase, table, fieldColumn, toMillis(start), toMillis(end), filterClause);
            long queryNanos = System.nanoTime();
            ApmQueryModels.MetricTotalSnapshot snapshot = readRepository.queryMetricTotal(sql);
            log.info("metricTotal metric={} table={} total={} matchedRows={} elapsedMs={} sql={}",
                    metric, table, snapshot.total(), snapshot.matchedRows(),
                    (System.nanoTime() - queryNanos) / 1_000_000D, sql);
            return snapshot;
        } catch (Exception e) {
            log.error("metricTotal error metric={}", body.get("query"), e.getMessage(), e);
            return new ApmQueryModels.MetricTotalSnapshot(0, 0);
        }
    }

    /** Multiple scalar metrics sharing one table and filter, read with one Doris scan. */
    public MetricTotalsBatchSnapshot metricTotalsBatch(Map<String, Object> body) {
        MetricBatchParams params = parseMetricBatchParams(body);
        if (params == null) {
            return MetricTotalsBatchSnapshot.empty();
        }
        try {
            String sql = MetricQueryBuilder.metricFieldsTotalSql(
                    metricDatabase, params.table(), params.fieldColumns(), params.aggregations(),
                    toMillis(params.start()), toMillis(params.end()), params.filterClause());
            long queryNanos = System.nanoTime();
            List<Map<String, Object>> rows = readRepository.queryRows(sql, 1);
            if (rows.isEmpty()) {
                log.info("metricTotalsBatch metrics={} table={} rows=0 elapsedMs={} sql={}",
                        params.metrics(), params.table(), (System.nanoTime() - queryNanos) / 1_000_000D, sql);
                return MetricTotalsBatchSnapshot.empty();
            }
            Map<String, Object> row = rows.get(0);
            Map<String, Double> totals = new LinkedHashMap<>();
            for (int i = 0; i < params.metrics().size(); i++) {
                totals.put(params.metrics().get(i), toDouble(row.get("metric_" + i)));
            }
            long matchedRows = toLong(row.get("matched_rows"));
            log.info("metricTotalsBatch metrics={} table={} matchedRows={} elapsedMs={} sql={}",
                    params.metrics(), params.table(), matchedRows,
                    (System.nanoTime() - queryNanos) / 1_000_000D, sql);
            return new MetricTotalsBatchSnapshot(totals, matchedRows);
        } catch (Exception e) {
            log.error("metricTotalsBatch error: {}", e.getMessage(), e);
            return MetricTotalsBatchSnapshot.empty();
        }
    }

    /** Multiple time series sharing one table and filter, read with one Doris scan. */
    public Map<String, MetricSeriesBatchSnapshot> metricSeriesBatch(Map<String, Object> body) {
        MetricBatchParams params = parseMetricBatchParams(body);
        if (params == null) {
            return Map.of();
        }
        try {
            String sql = MetricQueryBuilder.metricFieldsSeriesSql(
                    metricDatabase, params.table(), params.fieldColumns(), params.aggregations(),
                    toMillis(params.start()), toMillis(params.end()), params.filterClause(), params.interval());
            int bucketCount = (int) Math.max(1, Math.ceil((double) (params.end() - params.start()) / params.interval()));
            long queryNanos = System.nanoTime();
            List<Map<String, Object>> rows = readRepository.queryRows(sql, Math.min(1000, bucketCount));
            log.info("metricSeriesBatch metrics={} table={} rows={} buckets={} elapsedMs={} sql={}",
                    params.metrics(), params.table(), rows.size(), bucketCount,
                    (System.nanoTime() - queryNanos) / 1_000_000D, sql);
            Map<String, MetricSeriesBatchSnapshot> result = new LinkedHashMap<>();
            for (int i = 0; i < params.metrics().size(); i++) {
                List<MetricSeriesPoint> points = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    Object raw = row.get("metric_" + i);
                    points.add(new MetricSeriesPoint(toLong(row.get("epoch_sec")),
                            raw == null ? null : toDouble(raw)));
                }
                List<MetricSeriesPoint> filled = TimeSeriesFillUtil.fillMetricSeries(
                        points, toMillis(params.start()), toMillis(params.end()), params.interval());
                String metric = params.metrics().get(i);
                result.put(metric, new MetricSeriesBatchSnapshot(metricUnit(metric), filled));
            }
            return result;
        } catch (Exception e) {
            log.error("metricSeriesBatch error: {}", e.getMessage(), e);
            return Map.of();
        }
    }
    /**
     * 分组聚合标量：直接返回 top 分组的窗口总量（1 次查询）。
     * 替代 metricChart 分组路径"top 分组 + 逐组时序查询再求和"的 1+N 模式——
     * sum 语义下结果完全等价（分桶求和 == 窗口总量），avg 语义不等价（分桶均值），调用方需自行保证。
     * 入参同 metricChart 的 query.A（metric/from/by/order/aggs），by 为空返回空。
     */
    public List<ApmQueryModels.TopGroupTotal> metricTopGroupTotals(Map<String, Object> body) {
        try {
            MetricChartParams params = parseChartParams(body);
            if (params == null || params.by().isEmpty()) {
                return List.of();
            }
            String groupBy = params.by().get(0);
            String groupColumn = resolveChartGroupColumn(groupBy, params.parsed().measurement());
            String sql = MetricQueryBuilder.metricTopGroupsSql(
                    metricDatabase, params.table(), params.fieldColumn(), groupColumn,
                    toMillis(params.start()), toMillis(params.end()), params.filterClause(), params.topLimit(), params.aggs());
            log.info("metricTopGroupTotals metric={} groupBy={} sql={}", params.metric(), groupBy, sql);
            List<ApmQueryModels.TopGroupTotal> totals = readRepository.queryTopGroupTotals(sql);
            log.info("metricTopGroupTotals metric={} groups={}", params.metric(), totals.size());
            return totals;
        } catch (Exception e) {
            log.error("metricTopGroupTotals error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 分组 × 时间桶序列：单次 GROUP BY 分组列, epoch_sec 查询，Java 侧按组拆分并补零，
     * 返回序列形态与 metricChart 分组路径一致（tags 含分组列），替代 1+N 模式。
     * 入参同 metricChart 的 query.A；by 为空或字段不支持分桶合并时委托 metricChart。
     */
    public List<Map<String, Object>> metricGroupBucketSeries(Map<String, Object> body) {
        try {
            MetricChartParams params = parseChartParams(body);
            if (params == null || params.by().isEmpty()) {
                return metricChart(body);
            }
            String groupBy = params.by().get(0);
            String groupColumn = resolveChartGroupColumn(groupBy, params.parsed().measurement());
            String sql = MetricQueryBuilder.metricGroupBucketSeriesSql(
                    metricDatabase, params.table(), params.fieldColumn(), groupColumn,
                    toMillis(params.start()), toMillis(params.end()), params.filterClause(),
                    params.interval(), params.aggs());
            if (sql == null) {
                // JVM GC 单调计数等不支持分桶合并的字段，回退逐组路径
                return metricChart(body);
            }
            log.info("metricGroupBucketSeries metric={} groupBy={} sql={}", params.metric(), groupBy, sql);
            List<ApmQueryModels.GroupBucketPoint> rows = readRepository.queryGroupBucketSeries(sql);
            log.info("metricGroupBucketSeries metric={} rows={}", params.metric(), rows.size());
            if (rows.isEmpty() && "service.http".equals(params.parsed().measurement()) && "resource".equals(groupBy)) {
                // 与 metricChart 相同的回退：按 resource 无数据时尝试 url
                String fallbackSql = MetricQueryBuilder.metricGroupBucketSeriesSql(
                        metricDatabase, params.table(), params.fieldColumn(), "url",
                        toMillis(params.start()), toMillis(params.end()), params.filterClause(),
                        params.interval(), params.aggs());
                rows = readRepository.queryGroupBucketSeries(fallbackSql);
                groupBy = "url";
            }
            Map<String, List<MetricSeriesPoint>> byGroup = new LinkedHashMap<>();
            for (ApmQueryModels.GroupBucketPoint row : rows) {
                if (row.groupValue() == null || row.groupValue().isBlank()) {
                    continue;
                }
                byGroup.computeIfAbsent(row.groupValue(), key -> new ArrayList<>())
                        .add(new MetricSeriesPoint(row.epochSeconds(), row.value()));
            }
            List<Map<String, Object>> series = new ArrayList<>();
            for (Map.Entry<String, List<MetricSeriesPoint>> entry : byGroup.entrySet()) {
                List<MetricSeriesPoint> filled = TimeSeriesFillUtil.fillMetricSeries(
                        entry.getValue(), toMillis(params.start()), toMillis(params.end()), params.interval());
                List<List<Number>> values = filled.stream()
                        .map(point -> java.util.Arrays.<Number>asList(point.epochSeconds() * 1000L, point.value()))
                        .toList();
                Map<String, Object> row = new HashMap<>();
                row.put("values", values);
                row.put("tags", Map.of(groupBy, entry.getKey()));
                row.put("units", List.of("time", metricUnit(params.metric())));
                series.add(row);
            }
            return series;
        } catch (Exception e) {
            log.error("metricGroupBucketSeries error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private record MetricBatchParams(
            List<String> metrics,
            String table,
            List<String> fieldColumns,
            List<String> aggregations,
            String filterClause,
            int interval,
            long start,
            long end) {
    }

    @SuppressWarnings("unchecked")
    private MetricBatchParams parseMetricBatchParams(Map<String, Object> body) {
        try {
            Map<String, Object> queryRoot = body.get("query") instanceof Map<?, ?> queryMap
                    ? (Map<String, Object>) queryMap
                    : Map.of();
            Map<String, Object> metricQuery = queryRoot.get("A") instanceof Map<?, ?> aMap
                    ? (Map<String, Object>) aMap
                    : Map.of();
            List<String> metrics = parseStringList(metricQuery.get("metrics"));
            if (metrics.isEmpty()) {
                return null;
            }
            List<String> requestedAggregations = parseStringList(metricQuery.get("aggregations"));
            List<String> fieldColumns = new ArrayList<>(metrics.size());
            List<String> aggregations = new ArrayList<>(metrics.size());
            String table = null;
            for (int i = 0; i < metrics.size(); i++) {
                MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(metrics.get(i));
                String metricTable = MetricIdentifierParser.dorisTableName(parsed.measurement());
                if (table == null) {
                    table = metricTable;
                } else if (!table.equals(metricTable)) {
                    throw new IllegalArgumentException("batch metrics must use the same Doris table");
                }
                fieldColumns.add(MetricIdentifierParser.toDorisFieldColumn(parsed));
                aggregations.add(i < requestedAggregations.size() ? requestedAggregations.get(i) : "");
            }
            long start = normalizeTime(toLong(body.get("start")));
            long end = normalizeTime(toLong(body.get("end")));
            int interval = Math.max(60, toInt(body.get("interval"), 60));
            String filterClause = buildFilterClause(parseFilters(metricQuery.get("from")));
            return new MetricBatchParams(metrics, table, fieldColumns, aggregations,
                    filterClause, interval, start, end);
        } catch (Exception e) {
            log.error("parseMetricBatchParams error: {}", e.getMessage(), e);
            return null;
        }
    }
    /** metricChart/metricTopGroupTotals/metricGroupBucketSeries 共用的 query.A 入参解析结果。 */
    private record MetricChartParams(
            String metric,
            MetricIdentifierParser.ParsedMetric parsed,
            String table,
            String fieldColumn,
            String filterClause,
            List<String> by,
            String aggs,
            int topLimit,
            int interval,
            long start,
            long end) {
    }

    private MetricChartParams parseChartParams(Map<String, Object> body) {
        try {
            Map<String, Object> queryRoot = body.get("query") instanceof Map<?, ?> queryMap
                    ? (Map<String, Object>) queryMap
                    : Map.of();
            Map<String, Object> metricQuery = queryRoot.get("A") instanceof Map<?, ?> aMap
                    ? (Map<String, Object>) aMap
                    : Map.of();
            String metric = String.valueOf(metricQuery.getOrDefault("metric", ""));
            if (metric.isBlank()) {
                return null;
            }
            long start = normalizeTime(toLong(body.get("start")));
            long end = normalizeTime(toLong(body.get("end")));
            int interval = toInt(body.get("interval"), 60);
            List<MetricFilter> filters = parseFilters(metricQuery.get("from"));
            List<String> by = parseStringList(metricQuery.get("by"));
            Map<String, Object> order = metricQuery.get("order") instanceof Map<?, ?> orderMap
                    ? (Map<String, Object>) orderMap
                    : Map.of();
            int topLimit = toInt(order.get("limit"), 50);
            String aggs = stringValue(metricQuery.get("aggs"));
            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(metric);
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);
            String filterClause = buildFilterClause(filters);
            return new MetricChartParams(metric, parsed, table, fieldColumn, filterClause,
                    by, aggs, topLimit, interval, start, end);
        } catch (Exception e) {
            log.error("parseChartParams error: {}", e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> buildChartSeries(
            List<MetricSeriesPoint> points,
            Map<String, String> tags,
            String metric,
            long start,
            long end,
            int intervalSec) {
        List<MetricSeriesPoint> filled = TimeSeriesFillUtil.fillMetricSeries(
                points, toMillis(start), toMillis(end), intervalSec);
        List<List<Number>> values = filled.stream()
                .map(point -> java.util.Arrays.<Number>asList(point.epochSeconds() * 1000L, point.value()))
                .toList();
        Map<String, Object> series = new HashMap<>();
        series.put("values", values);
        series.put("tags", tags);
        series.put("units", List.of("time", metricUnit(metric)));
        return series;
    }

    private static String metricUnit(String metric) {
        String lower = metric.toLowerCase();
        if (lower.endsWith(".pct")) {
            return "%";
        }
        if (lower.endsWith(".avgduration")) {
            return "ms";
        }
        if (lower.contains("collection_count")) {
            return "count";
        }
        if (lower.contains("collection_time")) {
            return "ms";
        }
        if (lower.contains("duration") || lower.contains("latency")) {
            return "ns";
        }
        if (lower.contains("time")) {
            return "ns";
        }
        return "count";
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }
    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long toMillis(long normalizedSecondsOrMillis) {
        return normalizedSecondsOrMillis < 1_000_000_000_000L
                ? normalizedSecondsOrMillis * 1000L
                : normalizedSecondsOrMillis;
    }

    @SuppressWarnings("unchecked")
    private static List<MetricFilter> parseFilters(Object fromObject) {
        if (!(fromObject instanceof List<?> list)) {
            return List.of();
        }
        List<MetricFilter> filters = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object operator = map.get("operator");
                Object connector = map.get("connector");
                filters.add(new MetricFilter(
                        String.valueOf(map.get("left")),
                        String.valueOf(operator != null ? operator : "="),
                        map.get("right"),
                        String.valueOf(connector != null ? connector : "AND")));
            }
        }
        return filters;
    }

    private static List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private List<MetricSeriesPoint> serviceErrorSeries(MetricSeriesRequest request) {
        try {
            String table = MetricIdentifierParser.dorisTableName("service.exception");
            String filterClause = buildFilterClause(request.filters());
            String sql = MetricQueryBuilder.metricFieldSeriesSql(
                    metricDatabase, table, "cnt", request.fromMillis(), request.toMillis(), filterClause);
            List<MetricSeriesPoint> points = readRepository.queryMetricSeries(sql);
            return TimeSeriesFillUtil.fillMetricSeries(
                    points, request.fromMillis(), request.toMillis(), 60);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String normalizeFilterColumn(String column) {
        if ("exceptionName".equals(column) || "errorType".equals(column)) {
            return "exceptionName";
        }
        return column;
    }

    private static String resolveChartGroupColumn(String groupBy, String measurement) {
        if ("exceptionName".equals(groupBy)) {
            return "errorType";
        }
        if ("service.http".equals(measurement) && "resource".equals(groupBy)) {
            return "url";
        }
        return MetricIdentifierParser.toColumnName(groupBy);
    }

    private static String buildFilterClause(List<MetricFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        StringBuilder clause = new StringBuilder();
        for (MetricFilter filter : filters) {
            if (filter.left() == null || filter.right() == null) {
                continue;
            }
            clause.append(MetricQueryBuilder.metricFilterClause(
                    normalizeFilterColumn(filter.left()), filter.operator(), filter.right()));
        }
        return clause.toString();
    }

    public record ServiceSeriesRequest(String service, long from, long to) {
    }

    public record HttpQueryRequest(
            String service,
            long from,
            long to,
            int limit,
            String httpMethod,
            String httpCode,
            String urlContains) {

        public HttpQueryRequest(String service, long from, long to, int limit) {
            this(service, from, to, limit, null, null, null);
        }

        public HttpQueryRequest {
            if (limit <= 0) {
                limit = 100;
            }
        }
    }

    public record MetricFilter(String left, String operator, Object right, String connector) {
    }

    public record LastTagsRequest(
            long start,
            long end,
            List<String> metrics,
            List<String> by,
            @JsonProperty("from") List<MetricFilter> filters) {

        public long fromMillis() {
            return normalizeTime(start);
        }

        public long toMillis() {
            return normalizeTime(end);
        }
    }

    public record MetricSeriesRequest(
            String metric,
            long start,
            long end,
            @JsonProperty("from") List<MetricFilter> filters) {

        public long fromMillis() {
            return normalizeTime(start);
        }

        public long toMillis() {
            return normalizeTime(end);
        }
    }

    private static long normalizeTime(long value) {
        if (value <= 0) {
            return PortalTimeParser.portalEndNow();
        }
        return value < 1_000_000_000_000L ? value * 1000L : value;
    }
}

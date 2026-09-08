package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.common.storage.MetricIdentifierParser;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.web.cockpit.TrafficLightService;
import com.databuff.apm.web.metric.MetricQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 运维监控看板的模块化指标查询服务。
 * 按页面数据模块拆分接口（KPI 汇总 / 多指标趋势 / 服务排行 / 接口趋势下钻），
 * 每个模块一次请求，全部支持 serviceNames 服务筛选（IN 条件下推到 SQL）。
 * 「较昨日」的等长前一日窗口偏移也在服务端完成，前端不再自行拼装请求。
 */
@Service
public class CockpitMetricPortalService {
    private static final Logger log = LoggerFactory.getLogger(CockpitMetricPortalService.class);

    private final MetricQueryService metricQueryService;
    private final TrafficLightService trafficLightService;

    /** 分组查询的分组数上限（与前端旧 TOP_GROUP_LIMIT 一致）。 */
    private static final int TOP_GROUP_LIMIT = 200;
    private static final String HTTP_AVAILABILITY_METRIC = "service.http.availability.pct";
    private static final String HTTP_UNAVAILABILITY_METRIC = "service.http.unavailability.pct";
    private static final String HTTP_REQUEST_COUNT_METRIC = "service.http.cnt";

    public CockpitMetricPortalService(MetricQueryService metricQueryService, TrafficLightService trafficLightService) {
        this.metricQueryService = metricQueryService;
        this.trafficLightService = trafficLightService;
    }

    /** 查询窗口：start/end 为秒级时间戳（metricChart 内部归一化为毫秒）。 */
    private record Window(long startSec, long endSec, int interval) {
        long durationSec() {
            return Math.max(1, endSec - startSec);
        }
    }

    /** 单个指标项：key 为前端回传标识（如卡片标题），filters 为标签过滤（DSL from 形态）。 */
    private record MetricItem(String key, String metric, String aggs, List<Map<String, Object>> filters) {
    }

    private record MetricBatch(
            String table,
            String serviceColumn,
            List<Map<String, Object>> baseFilters,
            List<MetricItem> items) {
    }

    private record BatchSeriesValues(
            MetricBatch batch,
            Map<String, MetricQueryService.MetricSeriesBatchSnapshot> today,
            Map<String, MetricQueryService.MetricSeriesBatchSnapshot> yesterday) {
    }

    /** 一个时间点：t 为毫秒时间戳。 */
    record TrendPoint(long t, double v) {
    }

    /** 按时间桶合并（跨服务求和）后的单条序列 + 单位。 */
    private record Merged(String unit, List<TrendPoint> points) {
    }

    /**
     * KPI 卡汇总：一次返回多个指标的今日/昨日聚合值。
     * 入参 { start, end, interval, serviceNames, items:[{key, metric, aggs}] }，
     * 返回 [{ key, today, yesterday }]。
     *
     * 并发模型：sum 语义与存在标量派生表达式的指标（加权比率 error.pct /
     * availability.pct / client_error.pct、平均耗时 avgDuration 等）按
     * Doris 表 + 服务归属列 + filters 分组，同组指标在一次表扫描（metricFieldsTotalSql）
     * 中返回窗口总量，避免每张卡分别查询、也避免大时间窗口下按桶 GROUP BY 的序列查询；
     * 仅无标量表达式的 avg 指标与 JVM 单调计数器保留逐项序列路径。所有批次/窗口与
     * 逐项查询一次提交，在 Java 虚线程（Executors.newVirtualThreadPerTaskExecutor）上并发执行。
     */
    public List<Map<String, Object>> kpiSummary(Map<String, Object> body) {
        long startedNanos = System.nanoTime();
        Window window = parseWindow(body);
        List<String> serviceNames = parseStringList(body.get("serviceNames"));
        List<MetricItem> items = parseItems(body.get("items"));
        Map<String, double[]> valuesByKey = new LinkedHashMap<>();

        List<MetricBatch> batches = metricBatches(items.stream().filter(item -> isBatchable(item)).toList());
        List<MetricItem> seriesItems = items.stream().filter(item -> !isBatchable(item)).toList();
        log.info("kpiSummary request window={}-{} items={} batches={} seriesItems={} serviceNames={}",
                window.startSec(), window.endSec(), items.size(), batches.size(), seriesItems.size(), serviceNames);

        long queryPhaseNanos = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 批量标量模式：每个 batch 的今日/昨日窗口各一个并发任务（虚线程），
            // 组内多个指标共享一次 Doris 扫描；全部任务一次提交后统一 join。
            List<CompletableFuture<Map<String, double[]>>> futures = new ArrayList<>();
            for (MetricBatch batch : batches) {
                CompletableFuture<Map<String, Double>> today = CompletableFuture.supplyAsync(
                        () -> metricTotalsForBatch(batch, serviceNames,
                                window.startSec(), window.endSec(), window.interval()),
                        executor);
                CompletableFuture<Map<String, Double>> yesterday = CompletableFuture.supplyAsync(
                        () -> metricTotalsForBatch(batch, serviceNames,
                                window.startSec() - window.durationSec(), window.startSec(), window.interval()),
                        executor);
                futures.add(today.thenCombine(
                        yesterday,
                        (todayTotals, yesterdayTotals) -> mergeBatchTotals(batch, todayTotals, yesterdayTotals)));
            }
            // 仅无标量派生表达式的 avg 指标与 JVM 单调计数器保留逐项序列路径，同样并发
            for (MetricItem item : seriesItems) {
                CompletableFuture<Double> today = CompletableFuture.supplyAsync(
                        () -> aggregate(item, serviceNames, window, window.startSec(), window.endSec()),
                        executor);
                CompletableFuture<Double> yesterday = CompletableFuture.supplyAsync(
                        () -> aggregate(
                                item, serviceNames, window,
                                window.startSec() - window.durationSec(), window.startSec()),
                        executor);
                futures.add(today.thenCombine(
                        yesterday,
                        (todayValue, yesterdayValue) ->
                                Map.of(item.key(), new double[]{todayValue, yesterdayValue})));
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new)).join();
            // Futures are joined only after every batch/window task has been
            // submitted; response order is restored later from the input items.
            for (CompletableFuture<Map<String, double[]>> future : futures) {
                valuesByKey.putAll(future.join());
            }
        }
        log.info("kpiSummary concurrentQueries elapsedMs={} (batchQueries={} seriesQueries={})",
                (System.nanoTime() - queryPhaseNanos) / 1_000_000D,
                batches.size() * 2,
                seriesItems.size() * 2);

        enforceAvailabilityComplement(items, valuesByKey);

        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (MetricItem item : items) {
            double[] values = valuesByKey.getOrDefault(item.key(), new double[]{0.0, 0.0});
            result.add(Map.of(
                    "key", item.key(),
                    "today", values[0],
                    "yesterday", values[1]
            ));
        }
        log.info("kpiSummary done batchQueries={} seriesQueries={} items={} elapsedMs={}",
                batches.size() * 2,
                seriesItems.size() * 2,
                items.size(),
                (System.nanoTime() - startedNanos) / 1_000_000D);
        return result;
    }

    /**
     * Availability and unavailability are one indicator pair. Keep the public
     * response complementary even when a failed batch query falls back to
     * independently executed scalar queries. With no matched HTTP requests both
     * metrics retain the existing zero-value convention.
     */
    private static void enforceAvailabilityComplement(
            List<MetricItem> items, Map<String, double[]> valuesByKey) {
        MetricItem availability = findMetricItem(items, HTTP_AVAILABILITY_METRIC);
        MetricItem unavailability = findMetricItem(items, HTTP_UNAVAILABILITY_METRIC);
        if (availability == null || unavailability == null) {
            return;
        }

        double[] availableValues = valuesByKey.get(availability.key());
        double[] unavailableValues = valuesByKey.get(unavailability.key());
        if (availableValues == null || unavailableValues == null) {
            return;
        }

        MetricItem requestCount = items.stream()
                .filter(item -> HTTP_REQUEST_COUNT_METRIC.equals(item.metric()))
                .filter(item -> item.filters().equals(availability.filters()))
                .findFirst()
                .orElse(null);
        double[] requestCounts = requestCount == null ? null : valuesByKey.get(requestCount.key());

        for (int index = 0; index < availableValues.length; index++) {
            boolean hasRequests = requestCounts != null && requestCounts[index] > 0D;
            boolean hasAvailabilityResult = availableValues[index] != 0D || unavailableValues[index] != 0D;
            if (hasRequests || hasAvailabilityResult) {
                double expected = clampPercentage(100D - availableValues[index]);
                if (Math.abs(unavailableValues[index] - expected) > 1e-9) {
                    log.warn("Correct inconsistent availability pair windowIndex={} availability={} unavailability={} expected={}",
                            index, availableValues[index], unavailableValues[index], expected);
                }
                unavailableValues[index] = expected;
            }
        }
    }

    private static MetricItem findMetricItem(List<MetricItem> items, String metric) {
        return items.stream()
                .filter(item -> metric.equals(item.metric()))
                .findFirst()
                .orElse(null);
    }

    private static double clampPercentage(double value) {
        return Math.max(0D, Math.min(100D, value));
    }

    /**
     * 多指标趋势：核心趋势 / 趋势分组卡共用。
     * 入参 { start, end, interval, serviceNames, items:[{key, metric, aggs}] }，
     * 返回 [{ key, unit, today:[[tsMillis, v]...], yesterday:[[tsMillis, v]...] }]，
     * yesterday 已按窗口时长偏移对齐到今日时间轴。
     */
    public List<Map<String, Object>> metricTrends(Map<String, Object> body) {
        Window window = parseWindow(body);
        int interval = window.interval();
        List<String> serviceNames = parseStringList(body.get("serviceNames"));
        List<MetricItem> items = parseItems(body.get("items"));
        List<MetricBatch> batches = metricBatches(items);
        Map<String, Map<String, Object>> rowsByKey = new LinkedHashMap<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<BatchSeriesValues>> futures = batches.stream()
                    .map(batch -> CompletableFuture.supplyAsync(() -> {
                        CompletableFuture<Map<String, MetricQueryService.MetricSeriesBatchSnapshot>> todayFuture =
                                CompletableFuture.supplyAsync(
                                        () -> metricSeriesBatch(batch, serviceNames, window.startSec(), window.endSec(), interval),
                                        executor);
                        CompletableFuture<Map<String, MetricQueryService.MetricSeriesBatchSnapshot>> yesterdayFuture =
                                CompletableFuture.supplyAsync(
                                        () -> metricSeriesBatch(
                                                batch, serviceNames, (window.startSec() - window.durationSec()), window.startSec(), interval),
                                        executor);
                        return new BatchSeriesValues(batch, todayFuture.join(), yesterdayFuture.join());
                    }, executor))
                    .toList();

            for (CompletableFuture<BatchSeriesValues> future : futures) {
                BatchSeriesValues values = future.join();
                for (MetricItem item : values.batch().items()) {
                    Merged current = mergedFromBatch(values.today().get(item.metric()));
                    Merged previous = mergedFromBatch(values.yesterday().get(item.metric()));
                    if (current == null) {
                        current = mergedSeries(item, serviceNames, window, window.startSec(), window.endSec());
                    }
                    if (previous == null) {
                        previous = mergedSeries(item, serviceNames, window, window.startSec() - window.durationSec(), window.startSec());
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", item.key());
                    row.put("unit", current.unit());
                    row.put("today", toValueList(current.points()));
                    row.put("yesterday", shiftPoints(previous.points(), window.durationSec() * 1000L));
                    rowsByKey.put(item.key(), row);
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (MetricItem item : items) {
            Map<String, Object> row = rowsByKey.get(item.key());
            if (row != null) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 服务排行：按服务分组聚合单指标，返回 Top N。
     * 入参 { start, end, interval, serviceNames, metric, aggs, limit, includeSeries }，
     * 返回 [{ service, value, series? }]，value = 各时间桶按 aggs 聚合（sum/avg）。
     * includeSeries 为 true 时附带每服务的分桶序列（供错误次数等派生计算）。
     */
    public List<Map<String, Object>> serviceRanking(Map<String, Object> body) {
        Window window = parseWindow(body);
        List<String> serviceNames = parseStringList(body.get("serviceNames"));
        String metric = stringValue(body.get("metric"));
        String aggs = stringValue(body.get("aggs"));
        boolean includeSeries = Boolean.TRUE.equals(body.get("includeSeries"));
        List<Map<String, Object>> filters = parseFilters(body.get("filters"));
        int limit = toInt(body.get("limit"), 10);
        if (limit <= 0) {
            // limit<=0 表示不截断（如派生计算需要全量服务）
            limit = TOP_GROUP_LIMIT;
        }
        Map<String, Object> queryBody = buildMetricQueryBody(metric, aggs, serviceNames, "service", null,
                window.startSec(), window.endSec(), window.interval(), TOP_GROUP_LIMIT, filters);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!includeSeries && ("sum".equalsIgnoreCase(aggs) || isWeightedRatioMetric(metric))) {
            // sum 语义且无需序列：top 分组标量一次查询即最终结果。
            // 原实现丢弃该查询的总量、再发 1+N 次逐组时序查询重算同样的数（接口 4-5s 的主因）。
            for (ApmQueryModels.TopGroupTotal total : metricQueryService.metricTopGroupTotals(queryBody)) {
                if (total.groupValue() == null || total.groupValue().isBlank()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("service", total.groupValue());
                row.put("value", total.metricTotal());
                rows.add(row);
            }
        } else {
            // 需要序列（下钻派生计算）或 avg 语义：单次 GROUP BY service, epoch_sec 查询后 Java 拆分/补零
            List<Map<String, Object>> series = includeSeries
                    ? metricQueryService.metricGroupBucketSeries(queryBody)
                    : metricQueryService.metricChart(queryBody);
            for (Map<String, Object> raw : series) {
                Map<String, Object> tags = tagMap(raw);
                String service = stringValue(tags.get("service"));
                if (service.isEmpty()) {
                    service = stringValue(tags.get("serviceId"));
                }
                List<TrendPoint> points = readPoints(raw);
                if (service.isEmpty() || points.isEmpty()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("service", service);
                row.put("value", aggregatePoints(points, aggs));
                if (includeSeries) {
                    row.put("series", toValueList(points));
                }
                rows.add(row);
            }
        }
        if (includeSeries && isWeightedRatioMetric(metric) && !rows.isEmpty()) {
            Map<String, Double> weightedTotals = new LinkedHashMap<>();
            for (ApmQueryModels.TopGroupTotal total : metricQueryService.metricTopGroupTotals(queryBody)) {
                if (total.groupValue() != null && !total.groupValue().isBlank()) {
                    weightedTotals.put(total.groupValue(), total.metricTotal());
                }
            }
            for (Map<String, Object> row : rows) {
                Double total = weightedTotals.get(stringValue(row.get("service")));
                if (total != null) {
                    row.put("value", total);
                }
            }
        }
        if (isErrorRateMetric(metric)) {
            // Error rate service lists exclude services with no errors in the window.
            rows.removeIf(row -> doubleOf(row.get("value")) <= 0D);
        }
        rows.sort((a, b) -> Double.compare(doubleOf(b.get("value")), doubleOf(a.get("value"))));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /**
     * 接口趋势下钻：单个服务按维度（resource 等）分组，返回 Top N 接口的今日/昨日序列与聚合值。
     * 入参 { start, end, interval, serviceNames, service, metric, aggs, groupBy, limit }，
     * 返回 [{ name, today, yesterday, todaySeries, yesterdaySeries }]。
     */
    public List<Map<String, Object>> serviceEndpoints(Map<String, Object> body) {
        Window window = parseWindow(body);
        List<String> serviceNames = parseStringList(body.get("serviceNames"));
        String service = stringValue(body.get("service"));
        String metric = stringValue(body.get("metric"));
        String aggs = stringValue(body.get("aggs"));
        String groupBy = stringValue(body.get("groupBy"));
        int limit = toInt(body.get("limit"), 6);
        // 兼容前端排行别名 req/err/exc 及空 aggs；filters 为标签过滤（DSL from 形态，如 durationRange='3000ms+'）
        metric = resolveEndpointMetricAlias(metric);
        List<Map<String, Object>> filters = parseFilters(body.get("filters"));
        if (aggs.isEmpty()) {
            aggs = defaultAggsForMetric(metric);
        }
        if (groupBy.isEmpty()) {
            groupBy = "resource";
        }
        log.info("serviceEndpoints request window={} serviceNames={} service={} metric={} aggs={} groupBy={} limit={} body={}", window, serviceNames, service, metric, aggs, groupBy, limit, body);
        if ((service.isEmpty() && serviceNames.isEmpty()) || metric.isEmpty() || groupBy.isEmpty()) {
            log.warn("serviceEndpoints missing required param, return empty");
            return List.of();
        }
        // serviceNames 非空时优先使用 serviceNames 筛选，否则使用 service
        String effectiveService = service.isEmpty() ? serviceNames.get(0) : service;
        List<Map<String, Object>> todaySeries = metricGroupBucketSeries(metric, aggs, serviceNames, groupBy, effectiveService, window.startSec(), window.endSec(), window.interval(), limit, filters);
        log.info("serviceEndpoints todaySeries size={} for service={} metric={}", todaySeries.size(), effectiveService, metric);
        long yStart = window.startSec() - window.durationSec();
        long yEnd = window.endSec() - window.durationSec();
        List<Map<String, Object>> yesterdaySeries = metricGroupBucketSeries(metric, aggs, serviceNames, groupBy, effectiveService, yStart, yEnd, window.interval(), limit, filters);
        log.info("serviceEndpoints yesterdaySeries size={} for service={} metric={}", yesterdaySeries.size(), effectiveService, metric);
        Map<String, Double> todayWeightedTotals = isWeightedRatioMetric(metric)
                ? metricTopGroupValueMap(metric, aggs, serviceNames, groupBy, effectiveService,
                        window.startSec(), window.endSec(), window.interval(), limit, filters)
                : Map.of();
        Map<String, Double> yesterdayWeightedTotals = isWeightedRatioMetric(metric)
                ? metricTopGroupValueMap(metric, aggs, serviceNames, groupBy, effectiveService,
                        yStart, yEnd, window.interval(), limit, filters)
                : Map.of();
        long shiftMillis = window.durationSec() * 1000L;

        Map<String, List<TrendPoint>> todayMap = new LinkedHashMap<>();
        for (Map<String, Object> raw : todaySeries) {
            Map<String, Object> tags = tagMap(raw);
            String name = stringValue(tags.get(groupBy));
            if (name.isEmpty() && "resource".equals(groupBy)) {
                name = stringValue(tags.get("url"));
            }
            if (!name.isEmpty()) {
                todayMap.put(name, readPoints(raw));
            }
        }
        Map<String, List<TrendPoint>> yesterdayMap = new LinkedHashMap<>();
        for (Map<String, Object> raw : yesterdaySeries) {
            Map<String, Object> tags = tagMap(raw);
            String name = stringValue(tags.get(groupBy));
            if (name.isEmpty() && "resource".equals(groupBy)) {
                name = stringValue(tags.get("url"));
            }
            if (!name.isEmpty()) {
                yesterdayMap.put(name, readPoints(raw));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TrendPoint>> entry : todayMap.entrySet()) {
            List<TrendPoint> todayPoints = entry.getValue();
            if (todayPoints.isEmpty()) {
                continue;
            }
            List<TrendPoint> yesterdayPoints = yesterdayMap.getOrDefault(entry.getKey(), List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("today", todayWeightedTotals.getOrDefault(entry.getKey(), aggregatePoints(todayPoints, aggs)));
            row.put("yesterday", yesterdayWeightedTotals.getOrDefault(entry.getKey(), aggregatePoints(yesterdayPoints, aggs)));
            row.put("todaySeries", toValueList(todayPoints));
            row.put("yesterdaySeries", shiftPoints(yesterdayPoints, shiftMillis));
            rows.add(row);
        }
        rows.sort((a, b) -> Double.compare(doubleOf(b.get("today")), doubleOf(a.get("today"))));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    // ---------- 内部工具 ----------

    private List<MetricBatch> metricBatches(List<MetricItem> items) {
        Map<String, List<MetricItem>> grouped = new LinkedHashMap<>();
        Map<String, MetricBatch> metadata = new LinkedHashMap<>();
        for (MetricItem item : items) {
            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(item.metric());
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String serviceColumn = isDependencyMetric(item.metric()) ? "srcService" : "service";
            List<Map<String, Object>> filters = applySlowExclusions(
                    item.filters() == null ? List.of() : item.filters());
            String batchKey = table + '|' + serviceColumn + '|' + filters;
            grouped.computeIfAbsent(batchKey, ignored -> new ArrayList<>()).add(item);
            metadata.putIfAbsent(batchKey, new MetricBatch(table, serviceColumn, filters, List.of()));
        }
        List<MetricBatch> batches = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<MetricItem>> entry : grouped.entrySet()) {
            MetricBatch meta = metadata.get(entry.getKey());
            batches.add(new MetricBatch(meta.table(), meta.serviceColumn(), meta.baseFilters(), entry.getValue()));
        }
        return batches;
    }

    private Map<String, MetricQueryService.MetricSeriesBatchSnapshot> metricSeriesBatch(
            MetricBatch batch, List<String> serviceNames, long startSec, long endSec, int interval) {
        Map<String, MetricQueryService.MetricSeriesBatchSnapshot> last = Map.of();
        for (List<Map<String, Object>> filters : batchFilterVariants(batch, serviceNames)) {
            last = metricQueryService.metricSeriesBatch(
                    buildMetricBatchQueryBody(batch, filters, startSec, endSec, interval));
            if (serviceNames.size() != 1 || hasBatchSeriesValue(last)) {
                return last;
            }
        }
        return last;
    }

    /** 单批标量总量：按 name→id→code 过滤变体依次尝试，首个有命中行的变体即最终值（与 directTotal 回退口径一致）。 */
    private Map<String, Double> metricTotalsForBatch(
            MetricBatch batch, List<String> serviceNames, long startSec, long endSec, int interval) {
        Map<String, Double> last = Map.of();
        int variant = 0;
        for (List<Map<String, Object>> filters : batchFilterVariants(batch, serviceNames)) {
            long queryNanos = System.nanoTime();
            MetricQueryService.MetricTotalsBatchSnapshot snapshot = metricQueryService.metricTotalsBatch(
                    buildMetricBatchQueryBody(batch, filters, startSec, endSec, interval));
            double elapsedMs = (System.nanoTime() - queryNanos) / 1_000_000D;
            last = snapshot.totals();
            log.info("kpiSummary batchTotals table={} metrics={} variant={} window={}-{} matchedRows={} elapsedMs={}",
                    batch.table(),
                    batch.items().stream().map(MetricItem::metric).toList(),
                    variant++,
                    startSec,
                    endSec,
                    snapshot.matchedRows(),
                    elapsedMs);
            if (serviceNames.size() != 1 || snapshot.matchedRows() > 0) {
                return last;
            }
        }
        return last;
    }

    /** 批量总量按 metric 归属到批内每个 item 的 key（同批同 metric 的多个 item 共享同一总量）。 */
    private static Map<String, double[]> mergeBatchTotals(
            MetricBatch batch, Map<String, Double> today, Map<String, Double> yesterday) {
        Map<String, double[]> values = new LinkedHashMap<>();
        for (MetricItem item : batch.items()) {
            values.put(item.key(), new double[]{
                    today.getOrDefault(item.metric(), 0D),
                    yesterday.getOrDefault(item.metric(), 0D)});
        }
        return values;
    }

    /**
     * 能否走批量标量查询：sum 语义的窗口总量一次扫描 SUM 即可；存在标量派生表达式的
     * 指标（加权比率 error.pct / availability.pct / client_error.pct、平均耗时 avgDuration
     * 等）由 metricFieldsTotalSql 返回与文档口径一致的窗口总量，无需按时间桶 GROUP BY
     * 出序列再聚合——大时间窗口下省掉最昂贵的序列查询。
     * 既无 sum 语义又无标量派生表达式的 avg 指标（依赖分桶均值）以及 JVM 单调计数器
     * （jvm.gc.* 专用增量 SQL）保留逐项路径。
     */
    private static boolean isBatchable(MetricItem item) {
        MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(item.metric());
        if (parsed.measurement().startsWith("jvm.")) {
            return false;
        }
        if ("sum".equalsIgnoreCase(item.aggs())) {
            return true;
        }
        String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
        String fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);
        return MetricQueryBuilder.hasDerivedScalarExpression(table, fieldColumn);
    }

    private List<List<Map<String, Object>>> batchFilterVariants(
            MetricBatch batch, List<String> serviceNames) {
        List<List<Map<String, Object>>> variants = new ArrayList<>();
        List<Map<String, Object>> byName = new ArrayList<>(batch.baseFilters());
        if (!serviceNames.isEmpty()) {
            byName.add(Map.of(
                    "left", batch.serviceColumn(), "operator", "in",
                    "right", serviceNames, "connector", "AND"));
        }
        variants.add(byName);
        if (serviceNames.size() == 1) {
            String idColumn = "srcService".equals(batch.serviceColumn()) ? "srcServiceId" : "service_id";
            List<Map<String, Object>> byId = new ArrayList<>(batch.baseFilters());
            byId.add(Map.of(
                    "left", idColumn, "operator", "in",
                    "right", List.of(PortalServiceIdResolver.normalize(serviceNames.get(0))),
                    "connector", "AND"));
            variants.add(byId);
            // 与 directTotal / metricChartByServiceId 回退口径一致：仍无命中时按 serviceCode/srcService 再试
            String codeColumn = "srcService".equals(batch.serviceColumn()) ? "srcService" : "serviceCode";
            List<Map<String, Object>> byCode = new ArrayList<>(batch.baseFilters());
            byCode.add(Map.of(
                    "left", codeColumn, "operator", "in",
                    "right", serviceNames, "connector", "AND"));
            variants.add(byCode);
        }
        return variants;
    }

    private Map<String, Object> buildMetricBatchQueryBody(
            MetricBatch batch, List<Map<String, Object>> filters,
            long startSec, long endSec, int interval) {
        Map<String, String> uniqueMetrics = new LinkedHashMap<>();
        for (MetricItem item : batch.items()) {
            uniqueMetrics.putIfAbsent(item.metric(), item.aggs());
        }
        Map<String, Object> queryA = new LinkedHashMap<>();
        queryA.put("metrics", new ArrayList<>(uniqueMetrics.keySet()));
        queryA.put("aggregations", new ArrayList<>(uniqueMetrics.values()));
        if (!filters.isEmpty()) {
            queryA.put("from", filters);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start", startSec);
        body.put("end", endSec);
        body.put("interval", interval);
        body.put("query", Map.of("A", queryA));
        return body;
    }

    private static boolean hasBatchSeriesValue(
            Map<String, MetricQueryService.MetricSeriesBatchSnapshot> snapshots) {
        for (MetricQueryService.MetricSeriesBatchSnapshot snapshot : snapshots.values()) {
            if (snapshot.points().stream().anyMatch(point -> point.value() != null)) {
                return true;
            }
        }
        return false;
    }

    private static Merged mergedFromBatch(MetricQueryService.MetricSeriesBatchSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        List<TrendPoint> points = snapshot.points().stream()
                .map(point -> new TrendPoint(
                        point.epochSeconds() * 1000L,
                        point.value() == null ? 0D : point.value()))
                .toList();
        return new Merged(snapshot.unit(), points);
    }

    private double aggregate(MetricItem item, List<String> serviceNames, Window window, long startSec, long endSec) {
        if (isWeightedRatioMetric(item.metric())) {
            // Error rate is a weighted window ratio, not an average of bucket ratios.
            return directTotal(item, serviceNames, startSec, endSec);
        }
        // sum 语义（只要一个窗口总数）：直接对目标表条件聚合，跳过"按时间桶分组出序列、Java 再求和"
        if ("sum".equalsIgnoreCase(item.aggs())) {
            return directTotal(item, serviceNames, startSec, endSec);
        }
        // 既无 sum 语义又无标量派生表达式的 avg 指标（依赖分桶均值）保留原序列路径
        Merged merged = mergedSeries(item, serviceNames, window, startSec, endSec);
        return aggregatePoints(merged.points(), item.aggs());
    }

    /**
     * sum 语义的窗口总量：SELECT SUM(field) ... WHERE ts 范围 + 标签过滤，单行结果。
     * 有服务筛选时下推 service IN；单服务未命中任何行时回退 service_id / serviceCode
     * （与 mergedSeries 的回退口径一致，OTLP 指标常以 *_id 归档）。
     */
    private double directTotal(MetricItem item, List<String> serviceNames, long startSec, long endSec) {
        List<Map<String, Object>> base = applySlowExclusions(
                item.filters() == null ? List.of() : item.filters());
        List<List<Map<String, Object>>> variants = new ArrayList<>();
        List<Map<String, Object>> byName = new ArrayList<>(base);
        if (!serviceNames.isEmpty()) {
            byName.add(Map.of("left", "service", "operator", "in", "right", serviceNames, "connector", "AND"));
        }
        variants.add(byName);
        if (serviceNames.size() == 1) {
            List<String> ids = List.of(com.databuff.apm.common.util.PortalServiceIdResolver.normalize(serviceNames.get(0)));
            List<Map<String, Object>> byId = new ArrayList<>(base);
            byId.add(Map.of("left", "service_id", "operator", "in", "right", ids, "connector", "AND"));
            variants.add(byId);
            List<Map<String, Object>> byCode = new ArrayList<>(base);
            byCode.add(Map.of("left", "serviceCode", "operator", "in", "right", serviceNames, "connector", "AND"));
            variants.add(byCode);
        }
        for (List<Map<String, Object>> filters : variants) {
            Map<String, Object> queryA = new LinkedHashMap<>();
            queryA.put("metric", item.metric());
            if (!filters.isEmpty()) {
                queryA.put("from", filters);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("start", startSec);
            body.put("end", endSec);
            body.put("query", Map.of("A", queryA));
            long queryNanos = System.nanoTime();
            ApmQueryModels.MetricTotalSnapshot snapshot = metricQueryService.metricTotal(body);
            double elapsedMs = (System.nanoTime() - queryNanos) / 1_000_000D;
            log.info("kpiSummary directTotal metric={} window={}-{} matchedRows={} elapsedMs={}",
                    item.metric(), startSec, endSec, snapshot.matchedRows(), elapsedMs);
            if (snapshot.matchedRows() > 0) {
                return snapshot.total();
            }
            log.info("directTotal no rows, try next variant metric={} serviceNames={}", item.metric(), serviceNames);
        }
        return 0;
    }

    private Merged mergedSeries(MetricItem item, List<String> serviceNames, Window window, long startSec, long endSec) {
        long startedNanos = System.nanoTime();
        long chartNanos = System.nanoTime();
        List<Map<String, Object>> series = metricChart(item.metric(), item.aggs(), serviceNames, null, null, startSec, endSec, window.interval(), TOP_GROUP_LIMIT, item.filters());
        log.info("kpiSummary mergedSeries chart metric={} window={}-{} rows={} elapsedMs={}",
                item.metric(), startSec, endSec, series.size(), (System.nanoTime() - chartNanos) / 1_000_000D);
        TreeMap<Long, Double> buckets = new TreeMap<>();
        String unit = "";
        for (Map<String, Object> raw : series) {
            if (unit.isEmpty()) {
                unit = readUnit(raw);
            }
            for (TrendPoint point : readPoints(raw)) {
                buckets.merge(point.t(), point.v(), Double::sum);
            }
        }
        // 单服务且首轮无有效点位，回退尝试 service_id/serviceCode 维度
        if (buckets.isEmpty() && serviceNames.size() == 1) {
            log.info("mergedSeries empty for service={} metric={}, retry with service_id/serviceCode", serviceNames, item.metric());
            long retryNanos = System.nanoTime();
            List<Map<String, Object>> retrySeries = metricChartByServiceId(item.metric(), item.aggs(), serviceNames, startSec, endSec, window.interval(), item.filters());
            log.info("kpiSummary mergedSeries retry metric={} window={}-{} rows={} elapsedMs={}",
                    item.metric(), startSec, endSec, retrySeries.size(), (System.nanoTime() - retryNanos) / 1_000_000D);
            buckets.clear();
            unit = "";
            for (Map<String, Object> raw : retrySeries) {
                if (unit.isEmpty()) {
                    unit = readUnit(raw);
                }
                for (TrendPoint point : readPoints(raw)) {
                    buckets.merge(point.t(), point.v(), Double::sum);
                }
            }
            if (!buckets.isEmpty()) {
                log.info("mergedSeries retry hit serviceNames={} metric={} points={}", serviceNames, item.metric(), buckets.size());
            }
        }
        List<TrendPoint> points = new ArrayList<>(buckets.size());
        buckets.forEach((t, v) -> points.add(new TrendPoint(t, v)));
        if (points.isEmpty() && !serviceNames.isEmpty()) {
            log.info("mergedSeries still empty serviceNames={} metric={} window={}-{} (via service/service_id)", serviceNames, item.metric(), startSec, endSec);
        }
        log.info("kpiSummary mergedSeries done metric={} window={}-{} points={} elapsedMs={}",
                item.metric(), startSec, endSec, points.size(), (System.nanoTime() - startedNanos) / 1_000_000D);
        return new Merged(unit, points);
    }

    /** 单服务回退：按 service_id/srcServiceId IN 查询（OTLP 指标常以 *_id 归档） */
    private List<Map<String, Object>> metricChartByServiceId(String metric, String aggs, List<String> serviceNames, long startSec, long endSec, int interval, List<Map<String, Object>> filters) {
        if (metric.isEmpty() || serviceNames.isEmpty()) return List.of();
        filters = applySlowExclusions(filters);
        List<String> normalizedIds = serviceNames.stream().map(s -> com.databuff.apm.common.util.PortalServiceIdResolver.normalize(s)).toList();
        boolean isDep = isDependencyMetric(metric);
        String idColumn = isDep ? "srcServiceId" : "service_id";
        String byColumn = isDep ? "srcServiceId" : "service_id";
        List<Map<String, Object>> fromId = new ArrayList<>();
        if (filters != null) {
            fromId.addAll(filters);
        }
        fromId.add(Map.of("left", idColumn, "operator", "in", "right", normalizedIds, "connector", "AND"));
        List<String> byId = List.of(byColumn);
        Map<String, Object> queryA = new LinkedHashMap<>();
        queryA.put("metric", metric);
        queryA.put("from", fromId);
        queryA.put("aggs", aggs);
        queryA.put("by", byId);
        queryA.put("order", Map.of("limit", TOP_GROUP_LIMIT));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start", startSec);
        body.put("end", endSec);
        body.put("interval", interval);
        body.put("query", Map.of("A", queryA));
        List<Map<String, Object>> r = metricQueryService.metricChart(body);
        if (!r.isEmpty()) {
            log.info("metricChartByServiceId hit {} metric={} serviceNames={} size={}", idColumn, metric, serviceNames, r.size());
            return r;
        }
        // 再尝试 serviceCode/srcService
        String codeColumn = isDep ? "srcService" : "serviceCode";
        String byCode = isDep ? "srcService" : "serviceCode";
        List<Map<String, Object>> fromCode = new ArrayList<>();
        if (filters != null) {
            fromCode.addAll(filters);
        }
        fromCode.add(Map.of("left", codeColumn, "operator", "in", "right", serviceNames, "connector", "AND"));
        List<String> byCodeList = List.of(byCode);
        queryA.put("from", fromCode);
        queryA.put("by", byCodeList);
        body.put("query", Map.of("A", queryA));
        List<Map<String, Object>> r2 = metricQueryService.metricChart(body);
        if (!r2.isEmpty()) {
            log.info("metricChartByServiceId hit {} metric={} serviceNames={} size={}", codeColumn, metric, serviceNames, r2.size());
        } else {
            log.info("metricChartByServiceId miss both {}/{} metric={} serviceNames={}", idColumn, codeColumn, metric, serviceNames);
        }
        return r2;
    }

    private static boolean isDependencyMetric(String metric) {
        return metric != null && (metric.startsWith("service.db") || metric.startsWith("service.redis") || metric.startsWith("service.mq") || metric.startsWith("service.remote") || metric.startsWith("service.config"));
    }

    /**
     * 调用 MetricQueryService.metricChart（入参已构造成 query.A 形态）。
     * serviceName 非空：过滤该服务并按 groupBy 分组；
     * groupBy 非空（如 service 排行）：按 groupBy 分组，serviceNames 非空时附加 IN 过滤；
     * 否则（趋势/KPI）：serviceNames 非空时按 service 分组 + IN 过滤，无筛选时返回全局单条。
     * filters 为额外的标签过滤（DSL from 形态：{left, operator, right, connector}），
     * 如慢调用口径 durationRange='3000ms+'，会原样下推到 SQL WHERE。
     */
    private Map<String, Double> metricTopGroupValueMap(
            String metric, String aggs, List<String> serviceNames,
            String groupBy, String serviceName,
            long startSec, long endSec, int interval, int limit,
            List<Map<String, Object>> filters) {
        Map<String, Object> body = buildMetricQueryBody(
                metric, aggs, serviceNames, groupBy, serviceName,
                startSec, endSec, interval, TOP_GROUP_LIMIT, filters);
        Map<String, Double> values = new LinkedHashMap<>();
        for (ApmQueryModels.TopGroupTotal total : metricQueryService.metricTopGroupTotals(body)) {
            if (total.groupValue() != null && !total.groupValue().isBlank()) {
                values.put(total.groupValue(), total.metricTotal());
            }
        }
        if (values.isEmpty() && "resource".equals(groupBy) && metric.startsWith("service.http.")) {
            Map<String, Object> fallbackBody = buildMetricQueryBody(
                    metric, aggs, serviceNames, "url", serviceName,
                    startSec, endSec, interval, TOP_GROUP_LIMIT, filters);
            for (ApmQueryModels.TopGroupTotal total : metricQueryService.metricTopGroupTotals(fallbackBody)) {
                if (total.groupValue() != null && !total.groupValue().isBlank()) {
                    values.put(total.groupValue(), total.metricTotal());
                }
            }
        }
        return values;
    }

    private List<Map<String, Object>> metricGroupBucketSeries(
            String metric, String aggs, List<String> serviceNames,
            String groupBy, String serviceName,
            long startSec, long endSec, int interval, int limit,
            List<Map<String, Object>> filters) {
        if (metric.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = buildMetricQueryBody(
                metric, aggs, serviceNames, groupBy, serviceName,
                startSec, endSec, interval, limit, filters);
        return metricQueryService.metricGroupBucketSeries(body);
    }
    private List<Map<String, Object>> metricChart(
            String metric, String aggs, List<String> serviceNames,
            String groupBy, String serviceName,
            long startSec, long endSec, int interval, int limit,
            List<Map<String, Object>> filters) {
        if (metric.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = buildMetricQueryBody(metric, aggs, serviceNames, groupBy, serviceName, startSec, endSec, interval, limit, filters);
        log.info("metricChart query metric={} aggs={} serviceNames={} groupBy={} serviceName={} limit={} body={}", metric, aggs, serviceNames, groupBy, serviceName, limit, body);
        List<Map<String, Object>> result = metricQueryService.metricChart(body);
        log.info("metricChart result metric={} size={}", metric, result.size());
        return result;
    }

    /**
     * 构造 query.A 形态请求体（metricChart / metricTopGroupTotals / metricGroupBucketSeries 共用）。
     * 内含长连接服务排除（applySlowExclusions）与依赖类指标归属规则。
     */
    private Map<String, Object> buildMetricQueryBody(
            String metric, String aggs, List<String> serviceNames,
            String groupBy, String serviceName,
            long startSec, long endSec, int interval, int limit,
            List<Map<String, Object>> filters) {
        filters = applySlowExclusions(filters);
        // 依赖调用类指标归属 srcService（调用方），其余归属 service
        String filterColumn = isDependencyMetric(metric) ? "srcService" : "service";
        List<Map<String, Object>> from = new ArrayList<>();
        if (filters != null && !filters.isEmpty()) {
            from.addAll(filters);
        }
        List<String> by = new ArrayList<>();
        if (serviceName != null && !serviceName.isEmpty()) {
            from.add(Map.of("left", filterColumn, "operator", "=", "right", serviceName, "connector", "AND"));
            by.add(groupBy);
        } else if (groupBy != null && !groupBy.isEmpty()) {
            by.add(groupBy);
            if (!serviceNames.isEmpty()) {
                from.add(Map.of("left", filterColumn, "operator", "in", "right", serviceNames, "connector", "AND"));
            }
        } else if (!serviceNames.isEmpty()) {
            // A selected service only needs a WHERE filter; grouping would trigger a top+N query path.
            from.add(Map.of("left", filterColumn, "operator", "in", "right", serviceNames, "connector", "AND"));
        }
        Map<String, Object> queryA = new LinkedHashMap<>();
        queryA.put("metric", metric);
        if (!from.isEmpty()) {
            queryA.put("from", from);
        }
        queryA.put("aggs", aggs);
        if (!by.isEmpty()) {
            queryA.put("by", by);
            queryA.put("order", Map.of("limit", Math.max(1, Math.min(limit, TOP_GROUP_LIMIT))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start", startSec);
        body.put("end", endSec);
        body.put("interval", interval);
        body.put("query", Map.of("A", queryA));
        return body;
    }

    private double aggregatePoints(List<TrendPoint> points, String aggs) {
        if (points.isEmpty()) {
            return 0;
        }
        if ("avg".equalsIgnoreCase(aggs)) {
            return points.stream().mapToDouble(TrendPoint::v).average().orElse(0);
        }
        return sumPoints(points);
    }

    private static double sumPoints(List<TrendPoint> points) {
        return points.stream().mapToDouble(TrendPoint::v).sum();
    }

    private static List<List<Number>> toValueList(List<TrendPoint> points) {
        List<List<Number>> values = new ArrayList<>(points.size());
        for (TrendPoint point : points) {
            values.add(List.of(point.t(), point.v()));
        }
        return values;
    }

    private static List<List<Number>> shiftPoints(List<TrendPoint> points, long shiftMillis) {
        List<List<Number>> values = new ArrayList<>(points.size());
        for (TrendPoint point : points) {
            values.add(List.of(point.t() + shiftMillis, point.v()));
        }
        return values;
    }

    private List<TrendPoint> readPoints(Map<String, Object> series) {
        List<TrendPoint> points = new ArrayList<>();
        if (series.get("values") instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof List<?> pair && pair.size() >= 2) {
                    long t = toLong(pair.get(0));
                    double v = toDouble(pair.get(1));
                    points.add(new TrendPoint(t, v));
                }
            }
        }
        return points;
    }

    private static String readUnit(Map<String, Object> series) {
        if (series.get("units") instanceof List<?> units && units.size() >= 2 && units.get(1) != null) {
            return String.valueOf(units.get(1));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tagMap(Map<String, Object> series) {
        if (series.get("tags") instanceof Map<?, ?> tags) {
            return (Map<String, Object>) tags;
        }
        return Map.of();
    }

    private Window parseWindow(Map<String, Object> body) {
        long startSec = Math.max(1, toLong(body.get("start")));
        long endSec = Math.max(1, toLong(body.get("end")));
        if (endSec <= startSec) {
            endSec = startSec + 60;
        }
        int interval = toInt(body.get("interval"), 60);
        return new Window(startSec, endSec, interval);
    }

    private List<MetricItem> parseItems(Object itemsObject) {
        List<MetricItem> items = new ArrayList<>();
        if (itemsObject instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    String metric = stringValue(map.get("metric"));
                    if (!metric.isEmpty()) {
                        items.add(new MetricItem(
                                stringValue(map.get("key")),
                                metric,
                                stringValue(map.get("aggs")),
                                parseFilters(map.get("filters"))));
                    }
                }
            }
        }
        return items;
    }

    /**
     * 慢调用统计排除长连接服务：查询带 durationRange 过滤即视为慢调用口径，
     * 追加 traffic-light 配置的长连接服务列表为 service NOT IN 过滤（与异常服务统计共用同一配置）。
     */
    private List<Map<String, Object>> applySlowExclusions(List<Map<String, Object>> filters) {
        boolean slowQuery = filters != null && filters.stream()
                .anyMatch(f -> "durationRange".equals(String.valueOf(f.get("left"))));
        if (!slowQuery) {
            return filters;
        }
        List<String> excluded = trafficLightService.longConnServices();
        if (excluded.isEmpty()) {
            return filters;
        }
        List<Map<String, Object>> merged = new ArrayList<>(filters);
        merged.add(Map.of("left", "service", "operator", "NOT IN", "right", excluded, "connector", "AND"));
        return merged;
    }

    /**
     * 解析标签过滤条件（DSL from 形态：{left, operator, right, connector}），
     * operator/connector 缺省分别为 = / AND，与 MetricQueryService.parseFilters 口径一致。
     */
    private static List<Map<String, Object>> parseFilters(Object value) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map && map.get("left") != null && map.get("right") != null) {
                    Map<String, Object> filter = new LinkedHashMap<>();
                    filter.put("left", String.valueOf(map.get("left")));
                    filter.put("operator", map.get("operator") == null ? "=" : String.valueOf(map.get("operator")));
                    filter.put("right", map.get("right"));
                    filter.put("connector", map.get("connector") == null ? "AND" : String.valueOf(map.get("connector")));
                    filters.add(filter);
                }
            }
        }
        return filters;
    }

    private static List<String> parseStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = String.valueOf(item);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static boolean isWeightedRatioMetric(String metric) {
        return isErrorRateMetric(metric)
                || (metric != null && (metric.endsWith(".success.pct")
                || metric.endsWith(".availability.pct")
                || metric.endsWith(".unavailability.pct")));
    }

    private static boolean isErrorRateMetric(String metric) {
        return metric != null && metric.endsWith(".error.pct");
    }
    private static String resolveEndpointMetricAlias(String metric) {
        if (metric == null) return "";
        return switch (metric) {
            case "req" -> "service.http.cnt";
            case "err" -> "service.http.error";
            case "exc" -> "service.exception.cnt";
            default -> metric;
        };
    }

    private static String defaultAggsForMetric(String metric) {
        return isWeightedRatioMetric(metric) ? "avg" : "sum";
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "null".equalsIgnoreCase(text) ? "" : text;
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
            return 0;
        }
    }

    private static double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
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
}

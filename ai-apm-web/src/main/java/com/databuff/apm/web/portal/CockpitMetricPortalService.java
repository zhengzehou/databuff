package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels;
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
import java.util.stream.Collectors;

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
     */
    public List<Map<String, Object>> kpiSummary(Map<String, Object> body) {
        Window window = parseWindow(body);
        List<String> serviceNames = parseStringList(body.get("serviceNames"));
        List<MetricItem> items = parseItems(body.get("items"));
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        long yStart = window.startSec() - window.durationSec();
        long yEnd = window.endSec() - window.durationSec();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 提交每个 item 的计算任务
            List<CompletableFuture<Map<String, Object>>> futures = items.stream()
                    .map(item -> CompletableFuture.supplyAsync(() -> {
                        // 内部并行计算 today 和 yesterday
                        CompletableFuture<Double> todayFuture = CompletableFuture.supplyAsync(
                                () -> aggregate(item, serviceNames, window, window.startSec(), window.endSec()),
                                executor
                        );
                        CompletableFuture<Double> yesterdayFuture = CompletableFuture.supplyAsync(
                                () -> aggregate(item, serviceNames, window, yStart, yEnd),
                                executor
                        );

                        // 等待两个结果（可添加超时）
                        double today = todayFuture.join();
                        double yesterday = yesterdayFuture.join();

                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("key", item.key());
                        row.put("today", today);
                        row.put("yesterday", yesterday);
                        return row;
                    }, executor))
                    .toList();

            // 等待所有 item 完成，并保持顺序
            return futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 建议记录日志
            throw new RuntimeException("Failed to compute kpi summary", e);
        }
    }

    /**
     * 多指标趋势：核心趋势 / 趋势分组卡共用。
     * 入参 { start, end, interval, serviceNames, items:[{key, metric, aggs}] }，
     * 返回 [{ key, unit, today:[[tsMillis, v]...], yesterday:[[tsMillis, v]...] }]，
     * yesterday 已按窗口时长偏移对齐到今日时间轴。
     */
    public List<Map<String, Object>> metricTrends(Map<String, Object> body) {
        Window window = parseWindow(body);
        List<String> serviceNames = parseStringList(body.get("serviceNames"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetricItem item : parseItems(body.get("items"))) {
            Merged today = mergedSeries(item, serviceNames, window, window.startSec(), window.endSec());
            long yStart = window.startSec() - window.durationSec();
            long yEnd = window.endSec() - window.durationSec();
            Merged yesterday = mergedSeries(item, serviceNames, window, yStart, yEnd);
            long shiftMillis = window.durationSec() * 1000L;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", item.key());
            row.put("unit", today.unit());
            row.put("today", toValueList(today.points()));
            row.put("yesterday", shiftPoints(yesterday.points(), shiftMillis));
            result.add(row);
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
        if (!includeSeries && "sum".equalsIgnoreCase(aggs)) {
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
        List<Map<String, Object>> todaySeries = metricChart(metric, aggs, serviceNames, groupBy, effectiveService, window.startSec(), window.endSec(), window.interval(), limit, filters);
        log.info("serviceEndpoints todaySeries size={} for service={} metric={}", todaySeries.size(), effectiveService, metric);
        long yStart = window.startSec() - window.durationSec();
        long yEnd = window.endSec() - window.durationSec();
        List<Map<String, Object>> yesterdaySeries = metricChart(metric, aggs, serviceNames, groupBy, effectiveService, yStart, yEnd, window.interval(), limit, filters);
        log.info("serviceEndpoints yesterdaySeries size={} for service={} metric={}", yesterdaySeries.size(), effectiveService, metric);
        long shiftMillis = window.durationSec() * 1000L;

        Map<String, List<TrendPoint>> todayMap = new LinkedHashMap<>();
        for (Map<String, Object> raw : todaySeries) {
            String name = stringValue(tagMap(raw).get(groupBy));
            if (!name.isEmpty()) {
                todayMap.put(name, readPoints(raw));
            }
        }
        Map<String, List<TrendPoint>> yesterdayMap = new LinkedHashMap<>();
        for (Map<String, Object> raw : yesterdaySeries) {
            String name = stringValue(tagMap(raw).get(groupBy));
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
            row.put("today", sumPoints(todayPoints));
            row.put("yesterday", sumPoints(yesterdayPoints));
            row.put("todaySeries", toValueList(todayPoints));
            row.put("yesterdaySeries", shiftPoints(yesterdayPoints, shiftMillis));
            rows.add(row);
        }
        rows.sort((a, b) -> Double.compare(doubleOf(b.get("today")), doubleOf(a.get("today"))));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    // ---------- 内部工具 ----------

    private double aggregate(MetricItem item, List<String> serviceNames, Window window, long startSec, long endSec) {
        // sum 语义（只要一个窗口总数）：直接对目标表条件聚合，跳过"按时间桶分组出序列、Java 再求和"
        if ("sum".equalsIgnoreCase(item.aggs())) {
            return directTotal(item, serviceNames, startSec, endSec);
        }
        // avg 语义（如错误率）依赖分桶均值，保留原序列路径
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
            ApmQueryModels.MetricTotalSnapshot snapshot = metricQueryService.metricTotal(body);
            if (snapshot.matchedRows() > 0) {
                return snapshot.total();
            }
            log.info("directTotal no rows, try next variant metric={} serviceNames={}", item.metric(), serviceNames);
        }
        return 0;
    }

    private Merged mergedSeries(MetricItem item, List<String> serviceNames, Window window, long startSec, long endSec) {
        List<Map<String, Object>> series = metricChart(item.metric(), item.aggs(), serviceNames, null, null, startSec, endSec, window.interval(), TOP_GROUP_LIMIT, item.filters());
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
            List<Map<String, Object>> retrySeries = metricChartByServiceId(item.metric(), item.aggs(), serviceNames, startSec, endSec, window.interval(), item.filters());
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
            // 趋势/排行：按调用方聚合
            String byColumn = isDependencyMetric(metric) ? "srcService" : "service";
            by.add(byColumn);
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
        if ("service.http.error".equals(metric) || "service.error".equals(metric)) return "avg";
        return "sum";
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

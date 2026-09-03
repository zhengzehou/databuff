package com.databuff.apm.web.portal;

import com.databuff.apm.web.metric.MetricQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /** 分组查询的分组数上限（与前端旧 TOP_GROUP_LIMIT 一致）。 */
    private static final int TOP_GROUP_LIMIT = 200;

    public CockpitMetricPortalService(MetricQueryService metricQueryService) {
        this.metricQueryService = metricQueryService;
    }

    /** 查询窗口：start/end 为秒级时间戳（metricChart 内部归一化为毫秒）。 */
    private record Window(long startSec, long endSec, int interval) {
        long durationSec() {
            return Math.max(1, endSec - startSec);
        }
    }

    /** 单个指标项：key 为前端回传标识（如卡片标题）。 */
    private record MetricItem(String key, String metric, String aggs) {
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
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetricItem item : parseItems(body.get("items"))) {
            double today = aggregate(item, serviceNames, window, window.startSec(), window.endSec());
            long yStart = window.startSec() - window.durationSec();
            long yEnd = window.endSec() - window.durationSec();
            double yesterday = aggregate(item, serviceNames, window, yStart, yEnd);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", item.key());
            row.put("today", today);
            row.put("yesterday", yesterday);
            result.add(row);
        }
        return result;
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
        int limit = toInt(body.get("limit"), 10);
        if (limit <= 0) {
            // limit<=0 表示不截断（如派生计算需要全量服务）
            limit = TOP_GROUP_LIMIT;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : metricChart(metric, aggs, serviceNames, "service", null, window.startSec(), window.endSec(), window.interval())) {
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
        // 兼容前端排行别名 req/err/exc 及空 aggs
        metric = resolveEndpointMetricAlias(metric);
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
        List<Map<String, Object>> todaySeries = metricChart(metric, aggs, serviceNames, groupBy, effectiveService, window.startSec(), window.endSec(), window.interval(), limit);
        log.info("serviceEndpoints todaySeries size={} for service={} metric={}", todaySeries.size(), effectiveService, metric);
        long yStart = window.startSec() - window.durationSec();
        long yEnd = window.endSec() - window.durationSec();
        List<Map<String, Object>> yesterdaySeries = metricChart(metric, aggs, serviceNames, groupBy, effectiveService, yStart, yEnd, window.interval(), limit);
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
        Merged merged = mergedSeries(item, serviceNames, window, startSec, endSec);
        return aggregatePoints(merged.points(), item.aggs());
    }

    private Merged mergedSeries(MetricItem item, List<String> serviceNames, Window window, long startSec, long endSec) {
        List<Map<String, Object>> series = metricChart(item.metric(), item.aggs(), serviceNames, null, null, startSec, endSec, window.interval());
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
        List<TrendPoint> points = new ArrayList<>(buckets.size());
        buckets.forEach((t, v) -> points.add(new TrendPoint(t, v)));
        return new Merged(unit, points);
    }

    /**
     * 调用 MetricQueryService.metricChart（入参已构造成 query.A 形态）。
     * serviceName 非空：过滤该服务并按 groupBy 分组；
     * groupBy 非空（如 service 排行）：按 groupBy 分组，serviceNames 非空时附加 IN 过滤；
     * 否则（趋势/KPI）：serviceNames 非空时按 service 分组 + IN 过滤，无筛选时返回全局单条。
     */
    private List<Map<String, Object>> metricChart(
            String metric, String aggs, List<String> serviceNames,
            String groupBy, String serviceName,
            long startSec, long endSec, int interval) {
        return metricChart(metric, aggs, serviceNames, groupBy, serviceName, startSec, endSec, interval, TOP_GROUP_LIMIT);
    }

    private List<Map<String, Object>> metricChart(
            String metric, String aggs, List<String> serviceNames,
            String groupBy, String serviceName,
            long startSec, long endSec, int interval, int limit) {
        if (metric.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> from = new ArrayList<>();
        List<String> by = new ArrayList<>();
        if (serviceName != null && !serviceName.isEmpty()) {
            from.add(Map.of("left", "service", "operator", "=", "right", serviceName, "connector", "AND"));
            by.add(groupBy);
        } else if (groupBy != null && !groupBy.isEmpty()) {
            by.add(groupBy);
            if (!serviceNames.isEmpty()) {
                from.add(Map.of("left", "service", "operator", "in", "right", serviceNames, "connector", "AND"));
            }
        } else if (!serviceNames.isEmpty()) {
            by.add("service");
            from.add(Map.of("left", "service", "operator", "in", "right", serviceNames, "connector", "AND"));
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
        log.info("metricChart query metric={} aggs={} serviceNames={} groupBy={} serviceName={} limit={} body={}", metric, aggs, serviceNames, groupBy, serviceName, limit, body);
        List<Map<String, Object>> result = metricQueryService.metricChart(body);
        log.info("metricChart result metric={} size={}", metric, result.size());
        return result;
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
                                stringValue(map.get("aggs"))));
                    }
                }
            }
        }
        return items;
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

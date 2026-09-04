package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels.TrafficLightPoint;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.cockpit.TrafficLightService;
import com.databuff.apm.web.monitor.AlarmStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CockpitPortalService {

    private final TrafficLightService trafficLightService;
    private final ServicePortalService servicePortalService;
    private final AlarmStore alarmStore;

    public CockpitPortalService(
            TrafficLightService trafficLightService,
            ServicePortalService servicePortalService,
            AlarmStore alarmStore) {
        this.trafficLightService = trafficLightService;
        this.servicePortalService = servicePortalService;
        this.alarmStore = alarmStore;
    }

    public List<Map<String, Object>> servicesHealth(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String orderBy = ServicePortalService.stringValue(body.get("orderBy"), "alarm");
        Map<String, Object> config = trafficLightService.getConfig();
        double redThreshold = resolveRedThreshold(config, orderBy);
        double yellowThreshold = resolveYellowThreshold(config, orderBy);
        int limit = ServicePortalService.intValue(config.get("showServiceNumber"), 20);

        Map<String, Map<String, Object>> serviceInfoById = loadServiceInfoById(body, from, to);
        if (serviceInfoById.isEmpty()) {
            return List.of();
        }
        Set<String> serviceIdSet = serviceInfoById.keySet();

        long minuteMs = 60_000L;
        long startTime = from / minuteMs * minuteMs;
        long range = Math.max(0L, to - from);
        long minuteCount = range / minuteMs + (range % minuteMs > 0 ? 1 : 0);
        Map<Long, Map<String, Long>> alarmCountsByBucket = "exception".equalsIgnoreCase(orderBy)
                ? Map.of()
                : buildAlarmCountsByBucket(from, to, minuteMs, serviceIdSet, serviceInfoById);

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < minuteCount; i++) {
            long bucketStart = startTime + i * minuteMs;
            long bucketEnd = bucketStart + minuteMs;
            if (bucketStart >= to) {
                break;
            }

            Map<String, Long> valueByServiceId = "exception".equalsIgnoreCase(orderBy)
                    ? exceptionCountsForMinute(bucketStart, bucketEnd, serviceIdSet, serviceInfoById)
                    : alarmCountsByBucket.getOrDefault(bucketStart, Map.of());

            List<Map<String, Object>> serviceOrders = buildServiceOrders(
                    valueByServiceId, serviceInfoById, redThreshold, yellowThreshold);
            fillEmptyServiceIds(serviceOrders, serviceIdSet, serviceInfoById, limit, redThreshold, yellowThreshold);
            serviceOrders.sort((a, b) -> Long.compare(
                    longValue(b.get("value"), 0L),
                    longValue(a.get("value"), 0L)));
            if (serviceOrders.size() > limit) {
                serviceOrders = new ArrayList<>(serviceOrders.subList(0, limit));
            }

            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("timestamp", bucketStart);
            bucket.put("serviceOrders", serviceOrders);
            result.add(bucket);
        }
        return result;
    }

    public List<TrafficLightPoint> trafficLight(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        return trafficLightService.trafficLight(from, to);
    }

    public Map<String, Object> entityData(Map<String, Object> body) {
        String type = ServicePortalService.stringValue(body.get("type"), "SERVICE");
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);

        if (!"SERVICE".equalsIgnoreCase(type)) {
            return Map.of("total", 0, "healthRangeScoreList", List.of());
        }

        // 使用虚拟线程执行器，每个任务独占一个虚拟线程
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 异步提交三个独立任务
            CompletableFuture<List<String>> servicesFuture =
                    CompletableFuture.supplyAsync(() -> servicePortalService.listDistinctServices(from, to), executor);
            CompletableFuture<List<TrafficLightPoint>> trafficFuture =
                    CompletableFuture.supplyAsync(() -> trafficLightService.trafficLight(from, to), executor);
            CompletableFuture<Map<String, Object>> configFuture =
                    CompletableFuture.supplyAsync(() -> trafficLightService.getConfig(), executor);

            // 等待所有任务完成（任一失败则整体失败，可酌情降级）
            CompletableFuture.allOf(servicesFuture, trafficFuture, configFuture).join();

            // 获取结果
            List<String> services = servicesFuture.join();
            List<TrafficLightPoint> traffic = trafficFuture.join();
            Map<String, Object> config = configFuture.join();

            double errorRateThreshold = toDouble(config.get("errorRateThreshold"), 0.05);
            double minRequestCount = toDouble(config.get("minRequestCount"), 10);

            // 后续计算（与原逻辑完全一致）
            Map<String, List<TrafficLightPoint>> grouped = new LinkedHashMap<>();
            for (TrafficLightPoint point : traffic) {
                grouped.computeIfAbsent(point.ts(), key -> new ArrayList<>()).add(point);
            }

            List<Map<String, Object>> healthRangeScoreList = grouped.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> {
                        long unhealthy = entry.getValue().stream()
                                .filter(row -> !"green".equals(
                                        trafficLightColor(row, errorRateThreshold, minRequestCount)))
                                .count();
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("timestamp", parseTsMillis(entry.getKey()));
                        row.put("unhealthyCount", unhealthy);
                        return row;
                    })
                    .toList();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", services.size());
            data.put("healthRangeScoreList", healthRangeScoreList);
            return data;

        } catch (Exception e) {
            // 异常处理：可根据业务需求记录日志并返回降级数据
            return Map.of("total", 0, "healthRangeScoreList", List.of());
        }
    }

    public Map<String, Object> countServiceAlarms(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        long interval = Math.max(60_000L, longValue(body.get("interval"), 60_000L));
        String orderBy = ServicePortalService.stringValue(body.get("orderBy"), "alarm");
        Map<String, Object> config = trafficLightService.getConfig();
        double redThreshold = resolveRedThreshold(config, orderBy);
        double yellowThreshold = resolveYellowThreshold(config, orderBy);

        Map<Long, Map<String, Object>> buckets = initializeTrendBuckets(from, to, interval);
        if ("exception".equalsIgnoreCase(orderBy)) {
            fillExceptionTrendBuckets(buckets, from, to, interval, redThreshold, yellowThreshold);
        } else {
            Instant queryEnd = Instant.ofEpochMilli(to);
            long startTime = from / interval * interval;
            for (long bucketStart = startTime; bucketStart < to; bucketStart += interval) {
                long bucketEnd = Math.min(bucketStart + interval, to);
                long value = countAlarmsOverlappingMinute(bucketStart, bucketEnd, queryEnd);
                Map<String, Object> pointData = buckets.computeIfAbsent(
                        bucketStart, key -> newTrendPoint(0L, redThreshold, yellowThreshold));
                pointData.put("value", value);
                pointData.put("trafficLight", valueColor(value, redThreshold, yellowThreshold));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Object>> entry : buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            data.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return data;
    }

    private static Map<Long, Map<String, Object>> initializeTrendBuckets(long from, long to, long intervalMs) {
        Map<Long, Map<String, Object>> buckets = new LinkedHashMap<>();
        if (to <= from || intervalMs <= 0L) {
            return buckets;
        }
        long start = from / intervalMs * intervalMs;
        for (long time = start; time < to; time += intervalMs) {
            buckets.put(time, newTrendPoint(0L, 1D, 1D));
        }
        return buckets;
    }

    private static Map<String, Object> newTrendPoint(long value, double redThreshold, double yellowThreshold) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("value", value);
        point.put("trafficLight", valueColor(value, redThreshold, yellowThreshold));
        return point;
    }

    public Map<String, Object> getEntityAlarmList(Map<String, Object> body) {
        String type = ServicePortalService.stringValue(body.get("type"), "SERVICE");
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        if (!"SERVICE".equalsIgnoreCase(type)) {
            return emptyEntityAlarmList();
        }
        // 若传入分页参数，则对服务信息块分页（默认 100/页），保持 summary 统计为全量
        if (hasPageParam(body)) {
            return getEntityAlarmPaginated(body, from, to);
        }
        long duration = Math.max(0L, to - from);
        long previousFrom = from - duration;
        EntityAlarmSummary current = summarizeServiceEntityAlarms(body, from, to);
        EntityAlarmSummary previous = summarizeServiceEntityAlarms(body, previousFrom, from);
        return toEntityAlarmResponse(current, previous);
    }

    /**
     * 按数据块拆分 - 摘要块：仅返回统计计数与环比，不含 alarmEntityList。
     * 用于首屏快速渲染，避免一次性返回全量服务列表。
     */
    public Map<String, Object> getEntityAlarmSummary(Map<String, Object> body) {
        String type = ServicePortalService.stringValue(body.get("type"), "SERVICE");
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        if (!"SERVICE".equalsIgnoreCase(type)) {
            return emptyEntityAlarmSummary();
        }
        long duration = Math.max(0L, to - from);
        long previousFrom = from - duration;
        EntityAlarmSummary current = summarizeServiceEntityAlarms(body, from, to);
        EntityAlarmSummary previous = summarizeServiceEntityAlarms(body, previousFrom, from);
        return toEntityAlarmSummaryResponse(current, previous);
    }

    /**
     * 按数据块拆分 - 服务信息块：分页返回 alarmEntityList，默认 100/页。
     * 入参支持 page/pageNum + pageSize/size/offset 组合。
     */
    public Map<String, Object> getEntityAlarmPage(Map<String, Object> body) {
        String type = ServicePortalService.stringValue(body.get("type"), "SERVICE");
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        if (!"SERVICE".equalsIgnoreCase(type)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("total", 0);
            empty.put("page", 1);
            empty.put("pageSize", resolvePageSize(body));
            empty.put("list", List.of());
            empty.put("alarmEntityList", List.of());
            return empty;
        }
        return buildEntityAlarmPage(body, from, to);
    }

    private Map<String, Object> getEntityAlarmPaginated(Map<String, Object> body, long from, long to) {
        long duration = Math.max(0L, to - from);
        long previousFrom = from - duration;
        EntityAlarmSummary current = summarizeServiceEntityAlarms(body, from, to);
        EntityAlarmSummary previous = summarizeServiceEntityAlarms(body, previousFrom, from);
        Map<String, Object> data = toEntityAlarmResponse(current, previous);
        // 对 alarmEntityList 分页
        List<Map<String, Object>> fullList = current.alarmEntityList();
        int total = fullList.size();
        int page = resolvePage(body);
        int pageSize = resolvePageSize(body);
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> pageList = sliceList(fullList, offset, pageSize);
        data.put("alarmEntityList", pageList);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("offset", offset);
        return data;
    }

    private Map<String, Object> buildEntityAlarmPage(Map<String, Object> body, long from, long to) {
        EntityAlarmSummary current = summarizeServiceEntityAlarms(body, from, to);
        List<Map<String, Object>> fullList = current.alarmEntityList();
        int total = fullList.size();
        int page = resolvePage(body);
        int pageSize = resolvePageSize(body);
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> pageList = sliceList(fullList, offset, pageSize);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("offset", offset);
        data.put("size", pageList.size());
        data.put("list", pageList);
        data.put("alarmEntityList", pageList);
        return data;
    }

    private static boolean hasPageParam(Map<String, Object> body) {
        return body.containsKey("page") || body.containsKey("pageNum") || body.containsKey("pageSize") || body.containsKey("size");
    }

    private static int resolvePage(Map<String, Object> body) {
        int page = ServicePortalService.intValue(body.get("page"), 0);
        if (page <= 0) {
            page = ServicePortalService.intValue(body.get("pageNum"), 1);
        }
        return Math.max(1, page);
    }

    private static int resolvePageSize(Map<String, Object> body) {
        int size = ServicePortalService.intValue(body.get("pageSize"), 0);
        if (size <= 0) {
            size = ServicePortalService.intValue(body.get("size"), 0);
        }
        if (size <= 0) {
            Object offsetObj = body.get("offset");
            // 若仅传 offset/size 组合，size 仍为 100
            size = 100;
            if (offsetObj != null) {
                // 保持兼容，不改变 page 推导
            }
        }
        // 按需求默认 100/页，限制 1-500
        return Math.max(1, Math.min(size, 500));
    }

    private static List<Map<String, Object>> sliceList(List<Map<String, Object>> list, int offset, int pageSize) {
        if (list == null || list.isEmpty() || offset >= list.size()) {
            return List.of();
        }
        int end = Math.min(offset + pageSize, list.size());
        return new ArrayList<>(list.subList(offset, end));
    }

    private static Map<String, Object> emptyEntityAlarmSummary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", 0);
        data.put("matterDataCount", 0);
        data.put("minorDataCount", 0);
        data.put("noDataCount", 0);
        data.put("noAlarmCount", 0);
        data.put("matterDataCountRate", 0);
        data.put("minorDataCountRate", 0);
        data.put("noDataCountRate", 0);
        data.put("noAlarmCountRate", 0);
        return data;
    }

    private static Map<String, Object> toEntityAlarmSummaryResponse(
            EntityAlarmSummary current,
            EntityAlarmSummary previous) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", current.total());
        data.put("matterDataCount", current.matterCount());
        data.put("minorDataCount", current.minorCount());
        data.put("noDataCount", current.noDataCount());
        data.put("noAlarmCount", current.noAlarmCount());
        data.put("matterDataCountRate", changeRate(current.matterCount(), previous.matterCount()));
        data.put("minorDataCountRate", changeRate(current.minorCount(), previous.minorCount()));
        data.put("noDataCountRate", changeRate(current.noDataCount(), previous.noDataCount()));
        data.put("noAlarmCountRate", changeRate(current.noAlarmCount(), previous.noAlarmCount()));
        return data;
    }

    public List<Map<String, Object>> getAlarmCount(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        Instant fromInstant = Instant.ofEpochMilli(from);
        Instant toInstant = Instant.ofEpochMilli(to);

        AlarmLevelCounts counts = new AlarmLevelCounts();
        for (Alarm event : alarmStore.listRecent(Integer.MAX_VALUE)) {
            if (event.triggeredAt().isBefore(fromInstant) || event.triggeredAt().isAfter(toInstant)) {
                continue;
            }
            int level = portalAlarmLevel(event.level());
            if (level >= 3) {
                counts.matter++;
            } else if (level >= 2) {
                counts.minor++;
            } else {
                counts.noData++;
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("matterData", counts.matter);
        row.put("minorData", counts.minor);
        row.put("noData", counts.noData);
        return List.of(row);
    }

    private static final long DISPLAY_WINDOW_MS = 5 * 60_000L;

    public Map<String, Object> countServiceAlarmsTotal(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        long windowEnd = PortalTimeParser.parseMillis(body.get("windowEnd"), to);
        long windowFrom = Math.max(from, windowEnd - DISPLAY_WINDOW_MS);

        long alarmCount = countYellowRedOccurrences(buildWindowHealth(body, windowFrom, windowEnd, "alarm"));
        long exceptionCount = countYellowRedOccurrences(buildWindowHealth(body, windowFrom, windowEnd, "exception"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alarmCount", alarmCount);
        data.put("exceptionCount", exceptionCount);
        return data;
    }

    private List<Map<String, Object>> buildWindowHealth(
            Map<String, Object> body,
            long windowFrom,
            long windowEnd,
            String orderBy) {
        Map<String, Object> query = new LinkedHashMap<>(body);
        query.put("fromTime", windowFrom);
        query.put("toTime", windowEnd);
        query.put("orderBy", orderBy);
        return servicesHealth(query);
    }

    private static long countYellowRedOccurrences(List<Map<String, Object>> healthRows) {
        long count = 0L;
        for (Map<String, Object> row : healthRows) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> orders = (List<Map<String, Object>>) row.get("serviceOrders");
            if (orders == null) {
                continue;
            }
            for (Map<String, Object> order : orders) {
                String color = ServicePortalService.stringValue(order.get("trafficLight"), "");
                if ("yellow".equals(color) || "red".equals(color)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void fillExceptionTrendBuckets(
            Map<Long, Map<String, Object>> buckets,
            long from,
            long to,
            long intervalMs,
            double redThreshold,
            double yellowThreshold) {
        long startTime = from / intervalMs * intervalMs;
        for (long bucketStart = startTime; bucketStart < to; bucketStart += intervalMs) {
            long bucketEnd = Math.min(bucketStart + intervalMs, to);
            long value = trafficLightService.trafficLight(bucketStart, bucketEnd).stream()
                    .mapToLong(TrafficLightPoint::errorCount)
                    .sum();
            Map<String, Object> pointData = buckets.computeIfAbsent(
                    bucketStart, key -> newTrendPoint(0L, redThreshold, yellowThreshold));
            pointData.put("value", value);
            pointData.put("trafficLight", valueColor(value, redThreshold, yellowThreshold));
        }
    }

    private Map<String, Long> exceptionCountsForMinute(
            long bucketStart,
            long bucketEnd,
            Set<String> serviceIdSet,
            Map<String, Map<String, Object>> serviceInfoById) {
        Map<String, Long> valueByServiceId = new LinkedHashMap<>();
        for (TrafficLightPoint point : trafficLightService.trafficLight(bucketStart, bucketEnd)) {
            String serviceId = matchServiceId(point.service(), serviceIdSet, serviceInfoById);
            if (serviceId == null) {
                continue;
            }
            valueByServiceId.merge(serviceId, point.errorCount(), Long::sum);
        }
        return valueByServiceId;
    }

    private Map<Long, Map<String, Long>> buildAlarmCountsByBucket(
            long from,
            long to,
            long minuteMs,
            Set<String> serviceIdSet,
            Map<String, Map<String, Object>> serviceInfoById) {
        Instant queryEnd = Instant.ofEpochMilli(to);
        Map<Long, Map<String, Long>> countsByBucket = new LinkedHashMap<>();
        long startTime = from / minuteMs * minuteMs;
        for (long bucketStart = startTime; bucketStart < to; bucketStart += minuteMs) {
            long bucketEnd = Math.min(bucketStart + minuteMs, to);
            for (Alarm alarm : alarmStore.listRecent(Integer.MAX_VALUE)) {
                if (!alarmOverlapsMinute(alarm, bucketStart, bucketEnd, queryEnd)) {
                    continue;
                }
                String serviceId = matchServiceId(alarm.service(), serviceIdSet, serviceInfoById);
                if (serviceId == null) {
                    continue;
                }
                countsByBucket.computeIfAbsent(bucketStart, key -> new LinkedHashMap<>())
                        .merge(serviceId, 1L, Long::sum);
            }
        }
        return countsByBucket;
    }

    private long countAlarmsOverlappingMinute(long bucketStart, long bucketEnd, Instant queryEnd) {
        long count = 0L;
        for (Alarm alarm : alarmStore.listRecent(Integer.MAX_VALUE)) {
            if (alarmOverlapsMinute(alarm, bucketStart, bucketEnd, queryEnd)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Alarm active interval is half-open {@code [triggeredAt, resolvedAt or queryEnd)}.
     * One-shot alarms (resolved at the same instant they trigger, produced by
     * {@code AlarmStore.openResolved}) have zero duration, so the half-open interval
     * is empty and would never overlap any bucket; such alarms are counted in the
     * minute bucket that contains {@code triggeredAt}.
     */
    private static boolean alarmOverlapsMinute(
            Alarm alarm,
            long bucketStart,
            long bucketEnd,
            Instant queryEnd) {
        Instant alarmStart = alarm.triggeredAt();
        Instant alarmEnd = alarmEndExclusive(alarm, queryEnd);
        Instant bucketStartInstant = Instant.ofEpochMilli(bucketStart);
        Instant bucketEndInstant = Instant.ofEpochMilli(bucketEnd);
        if (alarmEnd.isAfter(alarmStart)) {
            return alarmStart.isBefore(bucketEndInstant) && alarmEnd.isAfter(bucketStartInstant);
        }
        return !alarmStart.isBefore(bucketStartInstant) && alarmStart.isBefore(bucketEndInstant);
    }

    private static boolean alarmOverlapsRange(Alarm alarm, long from, long to, Instant queryEnd) {
        Instant rangeStart = Instant.ofEpochMilli(from);
        Instant rangeEnd = Instant.ofEpochMilli(to);
        Instant alarmStart = alarm.triggeredAt();
        Instant alarmEnd = alarmEndExclusive(alarm, queryEnd);
        if (alarmEnd.isAfter(alarmStart)) {
            return alarmStart.isBefore(rangeEnd) && alarmEnd.isAfter(rangeStart);
        }
        return !alarmStart.isBefore(rangeStart) && alarmStart.isBefore(rangeEnd);
    }

    private static Instant alarmEndExclusive(Alarm alarm, Instant queryEnd) {
        return alarm.resolvedAt() != null ? alarm.resolvedAt() : queryEnd;
    }

    private List<Map<String, Object>> buildServiceOrders(
            Map<String, Long> valueByServiceId,
            Map<String, Map<String, Object>> serviceInfoById,
            double redThreshold,
            double yellowThreshold) {
        List<Map<String, Object>> serviceOrders = new ArrayList<>();
        for (Map.Entry<String, Long> entry : valueByServiceId.entrySet()) {
            Map<String, Object> serviceInfo = serviceInfoById.get(entry.getKey());
            if (serviceInfo == null) {
                continue;
            }
            serviceOrders.add(toServiceOrder(serviceInfo, entry.getValue(), redThreshold, yellowThreshold));
        }
        return serviceOrders;
    }

    private void fillEmptyServiceIds(
            List<Map<String, Object>> serviceOrders,
            Set<String> serviceIdSet,
            Map<String, Map<String, Object>> serviceInfoById,
            int limit,
            double redThreshold,
            double yellowThreshold) {
        Set<String> existing = new HashSet<>();
        for (Map<String, Object> order : serviceOrders) {
            existing.add(PortalServiceIdResolver.normalize(
                    ServicePortalService.stringValue(order.get("serviceId"), "")));
        }
        int expect = limit - serviceOrders.size();
        if (expect <= 0) {
            return;
        }
        for (String serviceId : serviceIdSet) {
            if (existing.contains(serviceId)) {
                continue;
            }
            Map<String, Object> serviceInfo = serviceInfoById.get(serviceId);
            if (serviceInfo == null) {
                continue;
            }
            serviceOrders.add(toServiceOrder(serviceInfo, 0L, redThreshold, yellowThreshold));
            expect--;
            if (expect <= 0) {
                break;
            }
        }
    }

    private Map<String, Object> toServiceOrder(
            Map<String, Object> serviceInfo,
            long value,
            double redThreshold,
            double yellowThreshold) {
        String serviceId = canonicalServiceId(serviceInfo);
        String serviceName = ServicePortalService.stringValue(serviceInfo.get("name"), serviceId);
        String service = ServicePortalService.stringValue(serviceInfo.get("service"), serviceName);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("serviceId", serviceId);
        order.put("name", serviceName);
        order.put("service", service);
        order.put("value", value);
        order.put("trafficLight", valueColor(value, redThreshold, yellowThreshold));
        order.put("businessName", ServicePortalService.stringValue(serviceInfo.get("businessName"), ""));
        return order;
    }

    private Map<String, Map<String, Object>> loadServiceInfoById(
            Map<String, Object> body,
            long from,
            long to) {
        Map<String, Object> query = new LinkedHashMap<>(body);
        query.put("fromTime", from);
        query.put("toTime", to);
        Map<String, Map<String, Object>> serviceInfoById = new LinkedHashMap<>();
        for (Map<String, Object> row : servicePortalService.basicServices(query)) {
            String serviceId = canonicalServiceId(row);
            if (serviceId.isBlank()) {
                continue;
            }
            serviceInfoById.putIfAbsent(serviceId, row);
        }
        return serviceInfoById;
    }

    private static String canonicalServiceId(Map<String, Object> row) {
        String id = ServicePortalService.stringValue(row.get("id"), "");
        if (!id.isBlank()) {
            return PortalServiceIdResolver.normalize(id);
        }
        String service = ServicePortalService.stringValue(row.get("service"), "");
        if (!service.isBlank()) {
            return PortalServiceIdResolver.normalize(service);
        }
        return PortalServiceIdResolver.normalize(ServicePortalService.stringValue(row.get("name"), ""));
    }

    private static String matchServiceId(
            String rawService,
            Set<String> serviceIdSet,
            Map<String, Map<String, Object>> serviceInfoById) {
        if (rawService == null || rawService.isBlank()) {
            return null;
        }
        String normalized = PortalServiceIdResolver.normalize(rawService);
        if (serviceIdSet.contains(normalized)) {
            return normalized;
        }
        for (String serviceId : serviceIdSet) {
            if (PortalServiceIdResolver.matches(rawService, serviceId)) {
                return serviceId;
            }
            Map<String, Object> info = serviceInfoById.get(serviceId);
            if (info == null) {
                continue;
            }
            String name = ServicePortalService.stringValue(info.get("name"), "");
            String service = ServicePortalService.stringValue(info.get("service"), "");
            if (PortalServiceIdResolver.matches(rawService, name)
                    || PortalServiceIdResolver.matches(rawService, service)) {
                return serviceId;
            }
        }
        return null;
    }

    private static double resolveRedThreshold(Map<String, Object> config, String orderBy) {
        if ("exception".equalsIgnoreCase(orderBy)) {
            return toDouble(config.get("exceptionRed"), toDouble(config.get("red"), 10D));
        }
        return toDouble(config.get("alarmRed"), toDouble(config.get("red"), 2D));
    }

    private static double resolveYellowThreshold(Map<String, Object> config, String orderBy) {
        if ("exception".equalsIgnoreCase(orderBy)) {
            return toDouble(config.get("exceptionYellow"), toDouble(config.get("yellow"), 2D));
        }
        return toDouble(config.get("alarmYellow"), toDouble(config.get("yellow"), 1D));
    }

    private static String valueColor(long value, double redThreshold, double yellowThreshold) {
        if (value >= redThreshold) {
            return "red";
        }
        if (value >= yellowThreshold) {
            return "yellow";
        }
        return "green";
    }

    private EntityAlarmSummary summarizeServiceEntityAlarms(Map<String, Object> body, long from, long to) {
        Map<String, Object> query = new LinkedHashMap<>(body);
        query.put("fromTime", from);
        query.put("toTime", to);

        List<Map<String, Object>> services = servicePortalService.basicServices(query);
        Set<String> servicesWithMetrics = new HashSet<>(servicePortalService.listDistinctServices(from, to));
        Map<String, Integer> maxLevelByServiceKey = maxAlarmLevelByService(from, to);

        int matterCount = 0;
        int minorCount = 0;
        int noDataCount = 0;
        int noAlarmCount = 0;
        List<Map<String, Object>> alarmEntityList = new ArrayList<>();

        for (Map<String, Object> service : services) {
            String entityId = ServicePortalService.stringValue(service.get("id"), "");
            String entityName = ServicePortalService.stringValue(service.get("name"), entityId);
            String serviceKey = resolveServiceKey(entityId, entityName, service.get("service"));
            boolean hasData = hasMetricData(serviceKey, servicesWithMetrics);
            int maxLevel = maxLevelByServiceKey.getOrDefault(serviceKey, 0);

            int matter = 0;
            int minor = 0;
            int noData = 0;
            if (!hasData) {
                noData = 1;
                noDataCount++;
            } else if (maxLevel >= 3) {
                matter = 1;
                matterCount++;
            } else if (maxLevel >= 2) {
                minor = 1;
                minorCount++;
            } else {
                noAlarmCount++;
            }

            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("entityId", entityId);
            entity.put("entityName", entityName);
            entity.put("matterDataCount", matter);
            entity.put("minorDataCount", minor);
            entity.put("noDataCount", noData);
            alarmEntityList.add(entity);
        }

        return new EntityAlarmSummary(
                services.size(),
                matterCount,
                minorCount,
                noDataCount,
                noAlarmCount,
                alarmEntityList);
    }

    private Map<String, Integer> maxAlarmLevelByService(long from, long to) {
        Instant fromInstant = Instant.ofEpochMilli(from);
        Instant toInstant = Instant.ofEpochMilli(to);
        Map<String, Integer> maxLevelByServiceKey = new HashMap<>();
        for (Alarm event : alarmStore.listRecent(Integer.MAX_VALUE)) {
            if (event.triggeredAt().isBefore(fromInstant) || event.triggeredAt().isAfter(toInstant)) {
                continue;
            }
            String serviceKey = resolveServiceKey(event.service(), event.service(), event.service());
            int level = portalAlarmLevel(event.level());
            maxLevelByServiceKey.merge(serviceKey, level, Math::max);
        }
        return maxLevelByServiceKey;
    }

    private static boolean hasMetricData(String serviceKey, Set<String> servicesWithMetrics) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return false;
        }
        String normalized = PortalServiceIdResolver.normalize(serviceKey);
        for (String candidate : servicesWithMetrics) {
            if (PortalServiceIdResolver.matches(serviceKey, candidate)
                    || PortalServiceIdResolver.matches(normalized, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveServiceKey(String entityId, String entityName, Object serviceField) {
        String service = ServicePortalService.stringValue(serviceField, "");
        if (entityId != null && !entityId.isBlank()) {
            return PortalServiceIdResolver.normalize(entityId);
        }
        if (service != null && !service.isBlank()) {
            return PortalServiceIdResolver.normalize(service);
        }
        return PortalServiceIdResolver.normalize(entityName);
    }

    private static Map<String, Object> emptyEntityAlarmList() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", 0);
        data.put("matterDataCount", 0);
        data.put("minorDataCount", 0);
        data.put("noDataCount", 0);
        data.put("noAlarmCount", 0);
        data.put("matterDataCountRate", 0);
        data.put("minorDataCountRate", 0);
        data.put("noDataCountRate", 0);
        data.put("noAlarmCountRate", 0);
        data.put("alarmEntityList", List.of());
        return data;
    }

    private static Map<String, Object> toEntityAlarmResponse(
            EntityAlarmSummary current,
            EntityAlarmSummary previous) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", current.total());
        data.put("matterDataCount", current.matterCount());
        data.put("minorDataCount", current.minorCount());
        data.put("noDataCount", current.noDataCount());
        data.put("noAlarmCount", current.noAlarmCount());
        data.put("matterDataCountRate", changeRate(current.matterCount(), previous.matterCount()));
        data.put("minorDataCountRate", changeRate(current.minorCount(), previous.minorCount()));
        data.put("noDataCountRate", changeRate(current.noDataCount(), previous.noDataCount()));
        data.put("noAlarmCountRate", changeRate(current.noAlarmCount(), previous.noAlarmCount()));
        data.put("alarmEntityList", current.alarmEntityList());
        return data;
    }

    private static double changeRate(long current, long previous) {
        if (previous <= 0L) {
            return current > 0L ? 100D : 0D;
        }
        return (current - previous) * 100D / previous;
    }

    private static int portalAlarmLevel(String level) {
        if ("critical".equalsIgnoreCase(level) || "error".equalsIgnoreCase(level)) {
            return 3;
        }
        return 2;
    }

    private record EntityAlarmSummary(
            int total,
            int matterCount,
            int minorCount,
            int noDataCount,
            int noAlarmCount,
            List<Map<String, Object>> alarmEntityList) {
    }

    private static final class AlarmLevelCounts {
        private int matter;
        private int minor;
        private int noData;
    }

    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String trafficLightColor(
            TrafficLightPoint point,
            double errorRateThreshold,
            double minRequestCount) {
        if (point.totalCount() < minRequestCount) {
            return "grey";
        }
        if (point.totalCount() <= 0) {
            return "grey";
        }
        double rate = (double) point.errorCount() / point.totalCount();
        if (rate > errorRateThreshold) {
            return "red";
        }
        if (rate > errorRateThreshold / 2) {
            return "yellow";
        }
        return "green";
    }

    private static long parseTsMillis(String ts) {
        if (ts == null || ts.isBlank()) {
            return 0L;
        }
        String text = ts.trim();
        if (text.chars().allMatch(Character::isDigit)) {
            long n = Long.parseLong(text);
            return n < 1_000_000_000_000L ? n * 1000L : n;
        }
        try {
            return ApmTimeZones.wallClockToEpochMilli(text);
        } catch (DateTimeParseException ignored) {
            String iso = text.replace(' ', 'T');
            if (!iso.contains("Z") && !iso.contains("+") && !iso.contains("-") && iso.contains("T")) {
                iso += "Z";
            }
            return Instant.parse(iso).toEpochMilli();
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

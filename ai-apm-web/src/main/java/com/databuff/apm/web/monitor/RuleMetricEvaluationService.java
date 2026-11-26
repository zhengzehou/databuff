package com.databuff.apm.web.monitor;

import com.databuff.apm.common.metric.MetricQueryDefinition;
import com.databuff.apm.common.query.ApmQueryModels.ErrorRateSnapshot;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.MetricIdentifierParser;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.web.monitor.eval.EventRulePayloadParser;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.metric.MetricCoreCatalogService;
import com.databuff.apm.web.metric.MetricQueryService;
import com.databuff.apm.web.portal.PortalTimeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RuleMetricEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RuleMetricEvaluationService.class);
    private static final int GROUP_EVALUATION_LIMIT = 50;

    public record GroupMetricValue(String service, String groupKey, double value) {
    }

    private final ApmReadRepository readRepository;
    private final String metricDatabase;
    private final MetricCoreCatalogService catalogService;

    public RuleMetricEvaluationService(
            ApmReadRepository readRepository,
            ApmStorageProperties storageProperties,
            MetricCoreCatalogService catalogService) {
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
        this.catalogService = catalogService;
    }

    public double evaluateRule(EventRule rule, long lookbackMillis) {
        long[] range = PortalTimeParser.metricEvaluationRange(lookbackMillis);
        return evaluateRule(rule, range[0], range[1]);
    }

    public double evaluateRule(EventRule rule, long from, long to) {
        long[] range = PortalTimeParser.normalizeMetricQueryRange(from, to);
        from = range[0];
        to = range[1];
        Map<String, Object> query = EventRulePayloadParser.parseQuery(rule.queryJson());
        Map<String, Object> primary = EventRulePayloadParser.primaryQueryItem(query);
        String metricId = resolveMetricId(rule, primary);
        List<MetricQueryService.MetricFilter> filters = parseFilters(primary, rule.service());
        return scalarValue(metricId, filters, from, to);
    }

    public List<GroupMetricValue> evaluateRuleGroups(EventRule rule, long lookbackMillis) {
        long[] range = PortalTimeParser.metricEvaluationRange(lookbackMillis);
        return evaluateRuleGroups(rule, range[0], range[1]);
    }

    public List<GroupMetricValue> evaluateRuleGroups(EventRule rule, long from, long to) {
        long[] range = PortalTimeParser.normalizeMetricQueryRange(from, to);
        from = range[0];
        to = range[1];
        Map<String, Object> query = EventRulePayloadParser.parseQuery(rule.queryJson());
        Map<String, Object> primary = EventRulePayloadParser.primaryQueryItem(query);
        List<String> groupByFields = EventRulePayloadParser.extractPrimaryGroupByFields(primary);
        String metricId = resolveMetricId(rule, primary);
        List<MetricQueryService.MetricFilter> baseFilters = parseFilters(primary, rule.service());
        if (groupByFields.isEmpty()) {
            double value = scalarValue(metricId, baseFilters, from, to);
            String service = serviceFromFilters(baseFilters);
            if ("*".equals(service)) {
                service = rule.service();
            }
            return List.of(new GroupMetricValue(service, service, value));
        }
        String groupBy = groupByFields.get(0);
        try {
            MetricQueryDefinition definition = catalogService.findOpenByIdentifier(metricId);
            String table;
            String fieldColumn;
            String measurement;
            if (definition != null) {
                table = definition.getDorisTable();
                measurement = definition.getMeasurement();
                if (table == null || table.isBlank()) {
                    table = MetricIdentifierParser.dorisTableName(measurement);
                }
                fieldColumn = definition.getField();
                if (fieldColumn == null || fieldColumn.isBlank()) {
                    fieldColumn = "cnt";
                }
                fieldColumn = MetricIdentifierParser.toDorisFieldColumn(measurement, fieldColumn);
            } else {
                MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(metricId);
                measurement = parsed.measurement();
                table = MetricIdentifierParser.dorisTableName(measurement);
                fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);
            }
            String groupColumn = resolveGroupColumn(groupBy, measurement);
            String filterClause = buildFilterClause(baseFilters);
            String topSql = MetricQueryBuilder.metricTopGroupsSql(
                    metricDatabase, table, fieldColumn, groupColumn, from, to, filterClause, GROUP_EVALUATION_LIMIT);
            List<String> groups = readRepository.queryTopGroups(topSql);
            List<GroupMetricValue> results = new ArrayList<>();
            for (String groupValue : groups) {
                List<MetricQueryService.MetricFilter> filters = new ArrayList<>(baseFilters);
                filters.add(new MetricQueryService.MetricFilter(groupBy, "=", groupValue, "AND"));
                double value = scalarValue(metricId, filters, from, to);
                String service = resolveEvaluatedService(groupByFields, groupBy, groupValue, rule.service());
                results.add(new GroupMetricValue(service, groupValue, value));
            }
            return results;
        } catch (Exception e) {
            log.debug("grouped metric evaluation failed for rule {}: {}", rule.id(), e.toString());
            return List.of();
        }
    }

    public double scalarValue(String metricId, List<MetricQueryService.MetricFilter> filters, long from, long to) {
        MetricQueryDefinition definition = catalogService.findOpenByIdentifier(metricId);
        if (definition == null) {
            return legacyFallback(metricId, filters, from, to);
        }
        String table = definition.getDorisTable();
        if (table == null || table.isBlank()) {
            table = MetricIdentifierParser.dorisTableName(definition.getMeasurement());
        }
        String filterClause = buildFilterClause(filters);
        try {
            if (metricId.endsWith(".error.pct")) {
                String sql = MetricQueryBuilder.metricErrorPctScalarSql(
                        metricDatabase, table, from, to, filterClause);
                ErrorRateSnapshot snapshot = readRepository.queryErrorRate(sql);
                return snapshot.totalCount() == 0 ? 0 : snapshot.errorRate() * 100;
            }
            if (metricId.endsWith(".success.pct")) {
                String sql = MetricQueryBuilder.metricErrorPctScalarSql(
                        metricDatabase, table, from, to, filterClause);
                ErrorRateSnapshot snapshot = readRepository.queryErrorRate(sql);
                return snapshot.totalCount() == 0 ? 100 : (1 - snapshot.errorRate()) * 100;
            }
            if (metricId.endsWith(".slow.pct")) {
                String sql = MetricQueryBuilder.metricAggregateScalarSql(
                        metricDatabase, table, "slow", from, to, filterClause);
                double slow = readRepository.queryMetricScalar(sql);
                String cntSql = MetricQueryBuilder.metricAggregateScalarSql(
                        metricDatabase, table, "cnt", from, to, filterClause);
                double cnt = readRepository.queryMetricScalar(cntSql);
                return cnt == 0 ? 0 : (slow / cnt) * 100;
            }
            if (metricId.endsWith(".avgDuration")) {
                String sql = MetricQueryBuilder.metricAvgDurationScalarSql(
                        metricDatabase, table, from, to, filterClause);
                return readRepository.queryMetricScalar(sql);
            }
            String field = definition.getField();
            if (field == null || field.isBlank()) {
                return 0;
            }
            field = MetricIdentifierParser.toDorisFieldColumn(definition.getMeasurement(), field);
            String sql = MetricQueryBuilder.metricAggregateScalarSql(
                    metricDatabase, table, field, from, to, filterClause);
            return readRepository.queryMetricScalar(sql);
        } catch (Exception e) {
            log.debug("metric scalar query failed for {}: {}", metricId, e.toString());
            return 0;
        }
    }

    private double legacyFallback(
            String metricId,
            List<MetricQueryService.MetricFilter> filters,
            long from,
            long to) {
        ThresholdEvaluationService legacy = new ThresholdEvaluationService(readRepository,
                new ApmStorageProperties(metricDatabase, metricDatabase, metricDatabase));
        String service = serviceFromFilters(filters);
        if (metricId != null && (metricId.contains("cnt") || metricId.contains("request"))) {
            return legacy.requestCountBetween(service, from, to);
        }
        return legacy.errorRateBetween(service, from, to) * 100;
    }

    @SuppressWarnings("unchecked")
    private static String resolveMetricId(EventRule rule, Map<String, Object> primary) {
        Object metricObj = primary.get("A");
        if (metricObj instanceof Map<?, ?> metricRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metric = (Map<String, Object>) metricRaw;
            Object metricName = metric.get("metric");
            String name = metricName == null ? "" : String.valueOf(metricName);
            if (!name.isBlank()) {
                return name;
            }
        }
        if (EventRule.METRIC_REQUEST_COUNT.equals(rule.metric())) {
            return "service.cnt";
        }
        return "service.error.pct";
    }

    @SuppressWarnings("unchecked")
    private static List<MetricQueryService.MetricFilter> parseFilters(Map<String, Object> primary, String fallbackService) {
        Object metricObj = primary.get("A");
            if (metricObj instanceof Map<?, ?> metricMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metric = (Map<String, Object>) metricMap;
                Object fromObj = metric.get("from");
                if (fromObj instanceof List<?> list && !list.isEmpty()) {
                    return list.stream()
                            .filter(Map.class::isInstance)
                            .map(item -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = (Map<String, Object>) item;
                                Object operator = map.get("operator");
                                Object connector = map.get("connector");
                                return new MetricQueryService.MetricFilter(
                                        String.valueOf(map.get("left")),
                                        String.valueOf(operator != null ? operator : "="),
                                        map.get("right"),
                                        String.valueOf(connector != null ? connector : "AND"));
                            })
                            .toList();
                }
            }
        if (fallbackService != null && !fallbackService.isBlank() && !"*".equals(fallbackService)) {
            return List.of(new MetricQueryService.MetricFilter("service", "=", fallbackService, "AND"));
        }
        return List.of();
    }

    private static String serviceFromFilters(List<MetricQueryService.MetricFilter> filters) {
        if (filters == null) {
            return "*";
        }
        return filters.stream()
                .filter(filter -> "service".equals(filter.left()))
                .map(filter -> String.valueOf(filter.right()))
                .findFirst()
                .orElse("*");
    }

    private static String buildFilterClause(List<MetricQueryService.MetricFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        StringBuilder clause = new StringBuilder();
        for (MetricQueryService.MetricFilter filter : filters) {
            if (filter.left() == null || filter.right() == null) {
                continue;
            }
            clause.append(MetricQueryBuilder.metricFilterClause(
                    filter.left(), filter.operator(), String.valueOf(filter.right())));
        }
        return clause.toString();
    }

    private static String resolveGroupColumn(String groupBy, String measurement) {
        if ("exceptionName".equals(groupBy)) {
            return "errorType";
        }
        if ("service.http".equals(measurement) && "resource".equals(groupBy)) {
            return "url";
        }
        return MetricIdentifierParser.toColumnName(groupBy);
    }

    private static String resolveEvaluatedService(
            List<String> groupByFields, String primaryGroupBy, String groupValue, String ruleService) {
        if ("service".equals(primaryGroupBy)) {
            return groupValue;
        }
        if (groupByFields.contains("service")) {
            return ruleService != null && !ruleService.isBlank() ? ruleService : "*";
        }
        return ruleService != null && !ruleService.isBlank() ? ruleService : "*";
    }
}

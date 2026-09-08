package com.databuff.apm.common.metric;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Expands {@link MetricCoreSeedRow} into portal identifier → {@link MetricQueryDefinition}. */
public final class MetricQueryCatalog {

    private MetricQueryCatalog() {
    }

    public static Map<String, MetricQueryDefinition> expand(Iterable<MetricCoreSeedRow> rows) {
        Map<String, MetricQueryDefinition> all = new TreeMap<>();
        for (MetricCoreSeedRow row : rows) {
            if (!row.enabled()) {
                continue;
            }
            all.putAll(expandRow(row));
        }
        return all;
    }

    private static Map<String, MetricQueryDefinition> expandRow(MetricCoreSeedRow row) {
        Map<String, MetricQueryDefinition> result = new LinkedHashMap<>();
        String measurement = row.measurement();
        for (Map.Entry<String, Map<String, Object>> entry : row.fields().entrySet()) {
            String field = entry.getKey();
            Map<String, Object> fieldInfo = entry.getValue();
            String identifier = measurement + "." + field;
            result.put(identifier, baseDefinition(row, identifier, field, fieldInfo));
        }
        Map<String, Object> cnt = row.fields().get("cnt");
        Map<String, Object> error = row.fields().get("error");
        Map<String, Object> slow = row.fields().get("slow");
        Map<String, Object> sumDuration = row.fields().get("sumDuration");
        if (cnt != null) {
            if (sumDuration != null) {
                result.put(measurement + ".avgDuration", derived(
                        row, measurement + ".avgDuration", "平均耗时", "ms", "毫秒",
                        "sum(sumDuration)/sum(cnt)", sumDuration));
            }
            if (error != null) {
                result.put(measurement + ".error.pct", derived(
                        row, measurement + ".error.pct", "错误率", "percent", "%",
                        "(sum(error)/sum(cnt)) * 100", error));
                if ("service.http".equals(measurement)) {
                    result.put(measurement + ".availability.pct", derived(
                            row, measurement + ".availability.pct", "可用性 SLA", "percent", "%",
                            "(1-(5xx+other error-marked requests excluding 4xx)/cnt) * 100", error));
                    result.put(measurement + ".unavailability.pct", derived(
                            row, measurement + ".unavailability.pct", "服务不可用率", "percent", "%",
                            "(5xx+other error-marked requests excluding 4xx)/cnt * 100", error));
                    result.put(measurement + ".client_error.pct", derived(
                            row, measurement + ".client_error.pct", "HTTP 4xx 率", "percent", "%",
                            "sum(http 4xx cnt)/sum(cnt) * 100", error));
                    result.put(measurement + ".server_error.pct", derived(
                            row, measurement + ".server_error.pct", "HTTP 5xx 率", "percent", "%",
                            "sum(http 5xx cnt)/sum(cnt) * 100", error));
                }
                result.put(measurement + ".success.pct", derived(
                        row, measurement + ".success.pct", "成功率", "percent", "%",
                        "service.http".equals(measurement)
                                ? "(1-(4xx+5xx+other error-marked requests)/cnt) * 100"
                                : "(1-sum(error)/sum(cnt)) * 100",
                        error));
            }
            if (slow != null) {
                result.put(measurement + ".slow.pct", derived(
                        row, measurement + ".slow.pct", "慢比率", "percent", "%",
                        "(sum(slow)/sum(cnt)) * 100", slow));
            }
        }
        return result;
    }

    private static MetricQueryDefinition baseDefinition(
            MetricCoreSeedRow row,
            String identifier,
            String field,
            Map<String, Object> fieldInfo) {
        MetricQueryDefinition def = new MetricQueryDefinition();
        def.setId(row.id());
        def.setIdentifier(identifier);
        def.setType1(row.type1());
        def.setType2(row.type2());
        def.setType3(row.type3());
        def.setApp(row.app());
        def.setDatabase(row.databaseName());
        def.setMeasurement(row.measurement());
        def.setField(field);
        def.setDesc(stringValue(fieldInfo.get("describe"), row.description()));
        def.setTagKey(row.tagKey());
        def.setTagValue(row.tagValue());
        Object fieldValue = fieldInfo.get("fieldValue");
        if (fieldValue instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            def.setFieldValue(cast);
        }
        def.setUnit(stringValue(fieldInfo.get("unit"), "count"));
        def.setUnitCn(stringValue(fieldInfo.get("unit_cn"), "个"));
        def.setMetricCn(stringValue(fieldInfo.get("metric_cn"), identifier));
        String aggregator = stringValue(fieldInfo.get("aggregatorType"), "sum");
        def.setAggregatorType(aggregator);
        def.setFormula(formulaFor(aggregator, field));
        def.setIsOpen(true);
        def.setCore(true);
        def.setBuiltin(row.builtin());
        def.setDorisTable(row.dorisTable());
        return def;
    }

    private static MetricQueryDefinition derived(
            MetricCoreSeedRow row,
            String identifier,
            String metricCn,
            String unit,
            String unitCn,
            String formula,
            Map<String, Object> templateField) {
        MetricQueryDefinition def = baseDefinition(row, identifier, null, templateField);
        def.setField(null);
        def.setMetricCn(metricCn);
        def.setDesc(metricCn);
        def.setUnit(unit);
        def.setUnitCn(unitCn);
        def.setFormula(formula);
        def.setAggregatorType("formula");
        return def;
    }

    private static String formulaFor(String aggregator, String field) {
        String fn = "avg".equals(aggregator) || "gauge".equals(aggregator) ? "avg" : aggregator;
        return fn + "(\"" + field + "\")";
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}

package com.databuff.apm.web.tools.local;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.storage.DorisTableNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Curated, read-only DataBuff schema discovery for external agents. */
@Component
public class DataCatalogTools {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final Set<String> BASE_TABLES = Set.of(
            DorisTableNames.TRACE_DC_SPAN,
            DorisTableNames.LOG_DC_RECORD,
            DorisTableNames.META_SERVICE,
            DorisTableNames.METRIC_PLATFORM,
            DorisTableNames.CONFIG_ALARM,
            DorisTableNames.CONFIG_ALARM_EVENT,
            DorisTableNames.CONFIG_METRIC_CORE);

    private final PlatformTools platformTools;
    private final ObjectMapper objectMapper;

    public DataCatalogTools(PlatformTools platformTools, ObjectMapper objectMapper) {
        this.platformTools = platformTools;
        this.objectMapper = objectMapper;
    }

    public String listDataTables(String keyword) {
        String normalized = normalize(keyword);
        List<Map<String, Object>> tables = allowedTables().stream()
                .filter(name -> normalized.isEmpty() || name.toLowerCase(Locale.ROOT).contains(normalized))
                .map(name -> Map.<String, Object>of(
                        "name", name,
                        "category", category(name),
                        "readOnly", true))
                .toList();
        return json(Map.of("tables", tables, "count", tables.size()));
    }

    public String getTableSchema(String table) {
        String safeTable = requireAllowedTable(table);
        return platformTools.queryDorisBusinessData("DESCRIBE `" + safeTable + "`", 500);
    }

    public String searchDataFields(String keyword, Integer limit) {
        String normalized = normalize(keyword);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("keyword is required");
        }
        int bounded = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> matches = new ArrayList<>();
        for (String measurement : MetricSchemaRegistry.allMeasurements()) {
            MetricSchemaRegistry.schema(measurement).ifPresent(schema -> {
                addMatch(matches, bounded, normalized, schema.tableName(), "measurement", measurement);
                schema.tagColumns().forEach(field ->
                        addMatch(matches, bounded, normalized, schema.tableName(), "tag", field));
                schema.fields().forEach(field ->
                        addMatch(matches, bounded, normalized, schema.tableName(), "field", field));
            });
            if (matches.size() >= bounded) {
                break;
            }
        }
        return json(Map.of("keyword", keyword.trim(), "matches", matches, "count", matches.size()));
    }

    public String getMetricCatalog(String measurement) {
        if (measurement != null && !measurement.isBlank()) {
            var schema = MetricSchemaRegistry.schema(measurement.trim())
                    .orElseThrow(() -> new IllegalArgumentException("unknown measurement: " + measurement));
            return json(metricSchema(schema));
        }
        List<Map<String, Object>> schemas = MetricSchemaRegistry.allMeasurements().stream()
                .map(name -> MetricSchemaRegistry.schema(name).orElseThrow())
                .map(DataCatalogTools::metricSchema)
                .toList();
        return json(Map.of("measurements", schemas, "count", schemas.size()));
    }

    private static Map<String, Object> metricSchema(MetricSchemaRegistry.MetricTableSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("measurement", schema.measurement());
        result.put("table", schema.tableName());
        result.put("tags", schema.tagColumns());
        result.put("fields", schema.fields());
        return result;
    }

    private static void addMatch(
            List<Map<String, Object>> matches,
            int limit,
            String keyword,
            String table,
            String kind,
            String value) {
        if (matches.size() >= limit || value == null
                || !value.toLowerCase(Locale.ROOT).contains(keyword)) {
            return;
        }
        matches.add(Map.of("table", table, "kind", kind, "name", value));
    }

    private static List<String> allowedTables() {
        Set<String> names = new LinkedHashSet<>(BASE_TABLES);
        names.addAll(MetricSchemaRegistry.allTableNames());
        return names.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static String requireAllowedTable(String table) {
        String normalized = table == null ? "" : table.trim();
        if (!SAFE_IDENTIFIER.matcher(normalized).matches() || !allowedTables().contains(normalized)) {
            throw new IllegalArgumentException("table is not exposed to ChatBI: " + normalized);
        }
        return normalized;
    }

    private static String category(String table) {
        if (table.startsWith("metric_")) return "metric";
        if (table.startsWith("trace_")) return "trace";
        if (table.startsWith("log_")) return "log";
        if (table.startsWith("config_alarm")) return "alarm";
        return "metadata";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize catalog result", e);
        }
    }
}

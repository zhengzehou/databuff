package com.databuff.apm.web.ai.mcp.standard;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class McpToolCatalog {

    private final List<McpToolDefinition> tools;

    public McpToolCatalog() {
        this.tools = List.of(
                tool("getCurrentTimeRange",
                        "Get current query time range",
                        schema(Map.of("rangeMinutes", integerProp("Minutes to look back from now")))),
                tool("getTimeRangeAroundTime",
                        "Get query time range around a HH:mm target time",
                        schema(Map.of("targetTime", stringProp("Target time in HH:mm format")))),
                tool("drawTrendCharts",
                        "Draw multiple trend charts from queried metric data",
                        schema(Map.of("charts", arrayProp("Trend chart specifications")))),
                tool("queryServicesAll",
                        "Query service list from service catalog; optional fromTime/toTime for time-windowed list",
                        schema(Map.of(
                                "keyword", stringProp("Optional service name keyword filter"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")))),
                tool("queryServicesByServiceType",
                        "Query service list by serviceType from service catalog; optional fromTime/toTime",
                        schema(Map.of(
                                "serviceType", stringProp("Service type filter"),
                                "keyword", stringProp("Optional service name keyword filter"),
                                "size", integerProp("Maximum number of results"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")))),
                tool("queryServiceTopology",
                        "Query upstream and downstream topology for one service by service name",
                        schema(Map.of(
                                "serviceName", stringProp("Service name"),
                                "serviceInstance", stringProp("Optional service instance"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")),
                        List.of("serviceName"))),
                tool("queryTraceListByCondition",
                        "Query trace list by service call condition",
                        schema(Map.of(
                                "srcServiceId", stringProp("Source service ID"),
                                "serviceId", stringProp("Target service ID"),
                                "componentType", stringProp("Component type filter"),
                                "resource", stringProp("Resource filter"),
                                "direction", stringProp("Call direction"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time"),
                                "size", integerProp("Maximum number of results")))),
                tool("queryTraceDetail",
                        "Query trace detail by traceId",
                        schema(Map.of("traceId", stringProp("Trace ID")),
                        List.of("traceId"))),
                tool("queryServiceAlarms",
                        "Query alarm data for one service entity",
                        schema(Map.of(
                                "serviceId", stringProp("Service ID"),
                                "status", integerProp("Alarm status filter"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")))),
                tool("queryMetricData",
                        "Query Doris metric tables by metric_core measurement, field, and tags",
                        schema(Map.of(
                                "queryRequests", arrayProp("Metric query request list"),
                                "size", integerProp("Maximum number of results per query")))),
                tool("queryLogTrend",
                        "Query log volume trend by service, service instance, severity, or keyword",
                        schema(logTrendSchema(), List.of("fromTime", "toTime"))),
                tool("queryLogDetail",
                        "Query paginated log detail lines; do not pass traceId or spanId",
                        schema(logDetailSchema(), List.of("fromTime", "toTime"))),
                tool("queryLogsByTraceId",
                        "Query paginated log lines for one traceId",
                        schema(Map.of(
                                "traceId", stringProp("Trace ID"),
                                "severities", stringArrayProp("Optional severity filters"),
                                "query", stringProp("Optional body keyword"),
                                "fromTime", stringProp("Optional start time, defaults to last 24 hours"),
                                "toTime", stringProp("Optional end time, defaults to now"),
                                "offset", integerProp("Pagination offset"),
                                "size", integerProp("Page size, max 200")),
                        List.of("traceId"))),
                tool("queryLogsBySpanId",
                        "Query paginated log lines for one spanId; pass traceId when known",
                        schema(Map.of(
                                "spanId", stringProp("Span ID"),
                                "traceId", stringProp("Optional but recommended trace ID"),
                                "severities", stringArrayProp("Optional severity filters"),
                                "query", stringProp("Optional body keyword"),
                                "fromTime", stringProp("Optional start time, defaults to last 24 hours"),
                                "toTime", stringProp("Optional end time, defaults to now"),
                                "offset", integerProp("Pagination offset"),
                                "size", integerProp("Page size, max 200")),
                        List.of("spanId"))),
                tool("inspectService",
                        "Inspect one service: entry metrics, ERROR/WARN/keyword logs, alarms, dependencies, error traces, instances; web also checks exception/JVM/CPU/memory",
                        schema(Map.of("serviceName", stringProp("Service name to inspect")),
                        List.of("serviceName"))),
                tool("listDataTables",
                        "List the curated read-only DataBuff tables exposed to ChatBI",
                        schema(Map.of("keyword", stringProp("Optional table-name filter")))),
                tool("getTableSchema",
                        "Describe one curated DataBuff table; sensitive configuration tables are not exposed",
                        schema(Map.of("table", stringProp("Exact table name returned by listDataTables")),
                                List.of("table"))),
                tool("searchDataFields",
                        "Search metric measurements, tags, and fields in the DataBuff metric catalog",
                        schema(Map.of(
                                "keyword", stringProp("Field, tag, or measurement keyword"),
                                "limit", integerProp("Maximum matches, default 100, max 500")),
                                List.of("keyword"))),
                tool("getMetricCatalog",
                        "Get DataBuff measurement-to-table, tag, and field mappings",
                        schema(Map.of("measurement", stringProp("Optional exact measurement")))),
                tool("queryDorisBusinessData",
                        "Execute one bounded read-only SQL statement against DataBuff Doris. Prefer structured tools first",
                        schema(Map.of(
                                "sql", stringProp("SELECT/SHOW/DESCRIBE/DESC/EXPLAIN/WITH statement"),
                                "maxRows", integerProp("Maximum rows, default 200, hard cap 1000")),
                                List.of("sql"))),
                tool("querySelfMonitorMetrics",
                        "Query DataBuff platform self-monitoring metrics for ingest, web, Doris, and pipeline health",
                        selfMonitorSchema()),
                tool("prometheus_query",
                        "Execute a read-only Prometheus instant PromQL query through DataBuff",
                        schema(Map.of(
                                "query", stringProp("PromQL expression"),
                                "time", stringProp("Optional evaluation timestamp")),
                                List.of("query"))),
                tool("prometheus_query_range",
                        "Execute a read-only Prometheus range PromQL query through DataBuff",
                        schema(Map.of(
                                "query", stringProp("PromQL expression"),
                                "start", stringProp("Start timestamp accepted by Prometheus"),
                                "end", stringProp("End timestamp accepted by Prometheus"),
                                "step", stringProp("Resolution, for example 60s")),
                                List.of("query", "start", "end", "step"))),
                tool("prometheus_series",
                        "Discover Prometheus time series and labels through DataBuff",
                        schema(Map.of(
                                "match", stringArrayProp("One or more series selectors"),
                                "start", stringProp("Optional start timestamp"),
                                "end", stringProp("Optional end timestamp")),
                                List.of("match"))),
                tool("prometheus_metadata",
                        "Read Prometheus metric metadata through DataBuff",
                        schema(Map.of(
                                "metric", stringProp("Optional metric name"),
                                "limit", integerProp("Optional result limit, max 10000")))));
    }

    public List<McpToolDefinition> listTools() {
        return tools;
    }

    public Optional<McpToolDefinition> findByName(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    private static McpToolDefinition tool(String name, String description, Map<String, Object> inputSchema) {
        return new McpToolDefinition(name, description, inputSchema, implementationFor(name));
    }

    private static Map<String, Object> logTrendSchema() {
        Map<String, Object> props = new LinkedHashMap<>(logFilterSchema());
        props.put("interval", integerProp("Optional bucket interval in seconds, default 60"));
        return props;
    }

    private static Map<String, Object> logDetailSchema() {
        Map<String, Object> props = new LinkedHashMap<>(logFilterSchema());
        props.put("offset", integerProp("Pagination offset, default 0"));
        props.put("size", integerProp("Page size, default 50, max 200"));
        return props;
    }

    private static Map<String, Object> logFilterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fromTime", stringProp("Query start time in yyyy-MM-dd HH:mm:ss"));
        props.put("toTime", stringProp("Query end time in yyyy-MM-dd HH:mm:ss"));
        props.put("services", stringArrayProp("Optional service names"));
        props.put("serviceIds", stringArrayProp("Optional service IDs"));
        props.put("serviceInstances", stringArrayProp("Optional service instance IDs (OTel service.instance.id)"));
        props.put("severities", stringArrayProp("Optional severity filters such as ERROR, WARN, INFO"));
        props.put("query", stringProp("Optional body keyword"));
        return props;
    }

    private static Map<String, Object> selfMonitorSchema() {
        return schema(Map.ofEntries(
                Map.entry("fromTime", stringProp("Required start time, yyyy-MM-dd HH:mm:ss")),
                Map.entry("toTime", stringProp("Required end time, yyyy-MM-dd HH:mm:ss")),
                Map.entry("mode", stringProp("series, summary, or list")),
                Map.entry("metrics", stringArrayProp("Exact platform metric names")),
                Map.entry("metricPrefixes", stringArrayProp("Metric prefixes")),
                Map.entry("metricSuffixes", stringArrayProp("Metric suffixes")),
                Map.entry("components", stringArrayProp("ingest and/or web")),
                Map.entry("instances", stringArrayProp("Instance filters")),
                Map.entry("dims", stringArrayProp("Dimension filters")),
                Map.entry("groupBy", stringProp("Comma-separated grouping columns")),
                Map.entry("value", stringProp("cnt, avg, gauge, auto, sum, max, or min")),
                Map.entry("stepSeconds", integerProp("Series resolution")),
                Map.entry("tag", stringProp("List-mode tag")),
                Map.entry("limit", integerProp("List-mode limit"))),
                List.of("fromTime", "toTime"));
    }

    private static String implementationFor(String name) {
        return switch (name) {
            case "getCurrentTimeRange" -> "commonTools.getCurrentTimeRange";
            case "getTimeRangeAroundTime" -> "commonTools.getTimeRangeAroundTime";
            case "drawTrendCharts" -> "commonTools.drawTrendCharts";
            case "queryServicesAll" -> "dataTools.queryServicesAll";
            case "queryServicesByServiceType" -> "dataTools.queryServicesByServiceType";
            case "queryServiceTopology" -> "dataTools.queryServiceTopology";
            case "queryTraceListByCondition" -> "dataTools.queryTraceListByCondition";
            case "queryTraceDetail" -> "dataTools.queryTraceDetail";
            case "queryServiceAlarms" -> "dataTools.queryServiceAlarms";
            case "queryMetricData" -> "dataTools.queryMetricData";
            case "queryLogTrend" -> "logTools.queryLogTrend";
            case "queryLogDetail" -> "logTools.queryLogDetail";
            case "queryLogsByTraceId" -> "logTools.queryLogsByTraceId";
            case "queryLogsBySpanId" -> "logTools.queryLogsBySpanId";
            case "inspectService" -> "inspectTools.inspectService";
            case "listDataTables" -> "dataCatalogTools.listDataTables";
            case "getTableSchema" -> "dataCatalogTools.getTableSchema";
            case "searchDataFields" -> "dataCatalogTools.searchDataFields";
            case "getMetricCatalog" -> "dataCatalogTools.getMetricCatalog";
            case "queryDorisBusinessData" -> "platformTools.queryDorisBusinessData";
            case "querySelfMonitorMetrics" -> "platformTools.querySelfMonitorMetrics";
            case "prometheus_query" -> "prometheusMcp.query";
            case "prometheus_query_range" -> "prometheusMcp.queryRange";
            case "prometheus_series" -> "prometheusMcp.series";
            case "prometheus_metadata" -> "prometheusMcp.metadata";
            default -> throw new IllegalArgumentException("unknown MCP tool: " + name);
        };
    }

    private static Map<String, Object> schema(Map<String, Object> properties) {
        return schema(properties, List.of());
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> arrayProp(String description) {
        return Map.of("type", "array", "description", description);
    }

    private static Map<String, Object> stringArrayProp(String description) {
        return Map.of(
                "type", "array",
                "description", description,
                "items", Map.of("type", "string"));
    }

    public record McpToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema,
            String implementation) {
    }
}

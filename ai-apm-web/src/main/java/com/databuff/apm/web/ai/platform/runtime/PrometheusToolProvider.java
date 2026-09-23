package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates read-only AgentScope tools backed directly by a Prometheus HTTP API. */
@Component
public class PrometheusToolProvider {

    private static final int DEFAULT_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 2_000_000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public PrometheusToolProvider(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    PrometheusToolProvider(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public PrometheusTools create(AiToolDefinition tool) {
        return new PrometheusTools(parseConfig(tool), objectMapper, httpClient);
    }

    /** Returns false for an unconfigured tool without attempting a network request. */
    public boolean isConfigured(AiToolDefinition tool) {
        if (tool == null) {
            return false;
        }
        String endpoint = tool.implementation() == null ? "" : tool.implementation().trim();
        if (tool.configJson() != null && !tool.configJson().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(tool.configJson());
                if (root != null && root.hasNonNull("endpoint")) {
                    endpoint = root.get("endpoint").asText("").trim();
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        return !endpoint.isBlank();
    }

    public Map<String, Object> probe(AiToolDefinition tool, JsonNode body) {
        PrometheusTools tools = create(tool);
        String query = body != null && body.hasNonNull("query") ? body.get("query").asText() : "up";
        String time = body != null && body.hasNonNull("time") ? body.get("time").asText() : null;
        String output = tools.prometheusQuery(query, time);
        return Map.of("toolId", tool.toolId(), "ok", true, "query", query, "output", output);
    }

    PrometheusConfig parseConfig(AiToolDefinition tool) {
        String endpoint = tool == null || tool.implementation() == null ? "" : tool.implementation().trim();
        Map<String, String> headers = new LinkedHashMap<>();
        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        if (tool != null && tool.configJson() != null && !tool.configJson().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(tool.configJson());
                if (root.hasNonNull("endpoint")) {
                    endpoint = root.get("endpoint").asText("").trim();
                }
                if (root.has("headers") && root.get("headers").isObject()) {
                    Map<String, String> parsed = objectMapper.convertValue(
                            root.get("headers"), new TypeReference<Map<String, String>>() {});
                    if (parsed != null) {
                        parsed.forEach((key, value) -> {
                            if (key != null && !key.isBlank() && value != null) {
                                headers.put(key.trim(), value);
                            }
                        });
                    }
                }
                timeoutSeconds = bounded(root.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS), 1, 120);
                maxResponseBytes = bounded(
                        root.path("maxResponseBytes").asInt(DEFAULT_MAX_RESPONSE_BYTES), 1_024, 10_000_000);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid Prometheus configJson: " + e.getMessage(), e);
            }
        }
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Prometheus endpoint", e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Prometheus endpoint must be an absolute HTTP(S) URL");
        }
        return new PrometheusConfig(endpoint, Map.copyOf(headers), timeoutSeconds, maxResponseBytes);
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record PrometheusConfig(String endpoint, Map<String, String> headers, int timeoutSeconds, int maxResponseBytes) {}

    public static final class PrometheusTools {
        private final PrometheusConfig config;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;

        PrometheusTools(PrometheusConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
            this.config = config;
            this.objectMapper = objectMapper;
            this.httpClient = httpClient;
        }

        @Tool(name = "prometheus_query", description = "只读查询 Prometheus 即时指标。用户询问服务器、主机、虚拟机、容器、Kubernetes 节点或实例的 CPU、内存、磁盘、网络、负载、进程、可用性等当前状态，或明确要求执行 PromQL 时，优先调用此工具；不要凭模型记忆回答指标值。")
        public String prometheusQuery(
                @ToolParam(name = "query", description = "PromQL expression") String query,
                @ToolParam(name = "time", required = false,
                        description = "Optional evaluation timestamp accepted by Prometheus") String time) {
            require(query, "query");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("query", query);
            optional(params, "time", time);
            return get("/api/v1/query", params);
        }

        @Tool(name = "prometheus_query_range", description = "只读查询 Prometheus 指标趋势。用户询问服务器/实例指标在一段时间内的变化、峰值、平均值、P95/P99、异常时间点或趋势时，必须优先调用此工具；使用 query_range 而不是多次猜测即时值。")
        public String prometheusQueryRange(
                @ToolParam(name = "query", description = "PromQL expression") String query,
                @ToolParam(name = "start", description = "Start timestamp accepted by Prometheus") String start,
                @ToolParam(name = "end", description = "End timestamp accepted by Prometheus") String end,
                @ToolParam(name = "step", description = "Query resolution, for example 60s") String step) {
            require(query, "query");
            require(start, "start");
            require(end, "end");
            require(step, "step");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("query", query);
            params.put("start", start);
            params.put("end", end);
            params.put("step", step);
            return get("/api/v1/query_range", params);
        }

        @Tool(name = "prometheus_series", description = "只读查找 Prometheus 时间序列和标签。当无法确定服务器指标名称、实例、主机、集群、namespace 或可用标签，需要先发现可查询的 series 时调用此工具。")
        public String prometheusSeries(
                @ToolParam(name = "match", description = "One or more series selectors") List<String> match,
                @ToolParam(name = "start", required = false, description = "Optional start timestamp") String start,
                @ToolParam(name = "end", required = false, description = "Optional end timestamp") String end) {
            if (match == null || match.isEmpty()) {
                throw new IllegalArgumentException("match is required");
            }
            Map<String, List<String>> params = new LinkedHashMap<>();
            params.put("match[]", match);
            optionalList(params, "start", start);
            optionalList(params, "end", end);
            return getMulti("/api/v1/series", params);
        }

        @Tool(name = "prometheus_metadata", description = "只读查询 Prometheus 指标元数据。当需要确认服务器指标的名称、类型、单位、帮助说明，或在生成 PromQL 前核对指标含义时调用此工具。")
        public String prometheusMetadata(
                @ToolParam(name = "metric", required = false, description = "Optional metric name") String metric,
                @ToolParam(name = "limit", required = false, description = "Optional result limit") Integer limit) {
            Map<String, String> params = new LinkedHashMap<>();
            optional(params, "metric", metric);
            if (limit != null) {
                params.put("limit", String.valueOf(Math.max(1, Math.min(10_000, limit))));
            }
            return get("/api/v1/metadata", params);
        }

        private String get(String path, Map<String, String> params) {
            Map<String, List<String>> multi = new LinkedHashMap<>();
            params.forEach((key, value) -> multi.put(key, List.of(value)));
            return getMulti(path, multi);
        }

        private String getMulti(String path, Map<String, List<String>> params) {
            StringBuilder url = new StringBuilder(config.endpoint()).append(path);
            boolean first = true;
            for (Map.Entry<String, List<String>> entry : params.entrySet()) {
                for (String value : entry.getValue()) {
                    if (value == null) {
                        continue;
                    }
                    url.append(first ? '?' : '&');
                    first = false;
                    url.append(encode(entry.getKey())).append('=').append(encode(value));
                }
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Accept", "application/json")
                    .GET();
            config.headers().forEach(builder::header);
            try {
                HttpResponse<String> response = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String body = response.body() == null ? "" : response.body();
                if (body.getBytes(StandardCharsets.UTF_8).length > config.maxResponseBytes()) {
                    throw new IllegalStateException("Prometheus response exceeds maxResponseBytes");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Prometheus HTTP " + response.statusCode() + ": " + body);
                }
                JsonNode json = objectMapper.readTree(body);
                if (!"success".equals(json.path("status").asText())) {
                    throw new IllegalStateException("Prometheus query failed: " + body);
                }
                return objectMapper.writeValueAsString(json);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Prometheus request interrupted", e);
            } catch (Exception e) {
                throw e instanceof IllegalStateException state ? state
                        : new IllegalStateException("Prometheus request failed: " + e.getMessage(), e);
            }
        }

        private static void require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }

        private static void optional(Map<String, String> params, String key, String value) {
            if (value != null && !value.isBlank()) {
                params.put(key, value.trim());
            }
        }

        private static void optionalList(Map<String, List<String>> params, String key, String value) {
            if (value != null && !value.isBlank()) {
                params.put(key, List.of(value.trim()));
            }
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}

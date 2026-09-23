package com.databuff.apm.web.ai.mcp.standard;

import com.databuff.apm.web.ai.platform.runtime.PrometheusToolProvider;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.databuff.apm.web.prometheus.PrometheusProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts the configured server-side Prometheus client tools to the standard MCP executor. */
@Component
public class PrometheusMcpFacade {

    private final PrometheusToolProvider provider;
    private final PrometheusProperties properties;
    private final ObjectMapper objectMapper;

    public PrometheusMcpFacade(
            PrometheusToolProvider provider,
            PrometheusProperties properties,
            ObjectMapper objectMapper) {
        this.provider = provider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String query(String query, String time) {
        return tools().prometheusQuery(query, time);
    }

    public String queryRange(String query, String start, String end, String step) {
        return tools().prometheusQueryRange(query, start, end, step);
    }

    public String series(List<String> match, String start, String end) {
        return tools().prometheusSeries(match, start, end);
    }

    public String metadata(String metric, Integer limit) {
        return tools().prometheusMetadata(metric, limit);
    }

    private PrometheusToolProvider.PrometheusTools tools() {
        if (!properties.configured()) {
            throw new IllegalStateException("Prometheus is not configured or enabled");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (!properties.bearerToken().isBlank()) {
            headers.put("Authorization", "Bearer " + properties.bearerToken());
        } else if (!properties.username().isBlank()) {
            String credentials = properties.username() + ":" + properties.password();
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8)));
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("endpoint", properties.baseUrl());
        config.put("headers", headers);
        config.put("timeoutSeconds", Math.max(1, properties.readTimeoutMs() / 1_000));
        config.put("maxResponseBytes", 2_000_000);
        Instant now = Instant.now();
        try {
            return provider.create(new AiToolDefinition(
                    "mcp-prometheus", "Prometheus", "MCP", "DataBuff Prometheus", ToolType.PROMETHEUS,
                    properties.baseUrl(), null, objectMapper.writeValueAsString(config),
                    true, true, 1, now, now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to configure Prometheus MCP tool", e);
        }
    }
}

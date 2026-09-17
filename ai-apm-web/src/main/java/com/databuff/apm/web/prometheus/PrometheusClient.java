package com.databuff.apm.web.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class PrometheusClient {
    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

    private final ObjectMapper objectMapper;
    private final PrometheusProperties properties;
    private final HttpClient httpClient;

    public PrometheusClient(ObjectMapper objectMapper, PrometheusProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public RangeResult queryRange(String query, long startSec, long endSec, long stepSeconds) {
        if (!properties.configured()) {
            throw new PrometheusException(503, "Prometheus is not configured or enabled");
        }
        if (query == null || query.isBlank() || startSec >= endSec || stepSeconds <= 0) {
            throw new PrometheusException(400, "Invalid Prometheus query parameters");
        }

        URI endpoint;
        try {
            endpoint = URI.create(properties.endpoint("query_range"));
        } catch (IllegalArgumentException e) {
            throw new PrometheusException(503, "Invalid Prometheus URL", e);
        }
        String scheme = endpoint.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new PrometheusException(503, "Prometheus URL must use HTTP or HTTPS");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("query", query);
        form.put("start", Long.toString(startSec));
        form.put("end", Long.toString(endSec));
        form.put("step", Long.toString(stepSeconds));

        String formBody = toFormBody(form);
        String fullUrl = endpoint + (endpoint.getQuery() == null ? "?" : "&") + formBody;
        log.info("Prometheus query_range URL: {}", fullUrl);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        formBody, StandardCharsets.UTF_8));
        String authorization = authorizationHeader();
        if (!authorization.isBlank()) {
            requestBuilder.header("Authorization", authorization);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrometheusException(502, "Prometheus query was interrupted", e);
        } catch (IOException | RuntimeException e) {
            throw new PrometheusException(502, "Prometheus service is unavailable", e);
        }

        JsonNode root = parseJson(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = jsonText(root, "error");
            String message = detail.isBlank()
                    ? "Prometheus returned HTTP " + response.statusCode()
                    : "Prometheus returned HTTP " + response.statusCode() + ": "
                            + safeMessage(detail);
            throw new PrometheusException(502, message);
        }
        if (root == null || !"success".equalsIgnoreCase(jsonText(root, "status"))) {
            String detail = root == null ? "Response is not valid JSON" : jsonText(root, "error");
            throw new PrometheusException(
                    502,
                    detail.isBlank()
                            ? "Prometheus returned an unsuccessful response"
                            : "Prometheus query failed: " + safeMessage(detail));
        }

        JsonNode data = root.get("data");
        if (data == null
                || !"matrix".equalsIgnoreCase(jsonText(data, "resultType"))
                || !data.path("result").isArray()) {
            throw new PrometheusException(
                    502, "Prometheus returned an unsupported query_range result");
        }
        return new RangeResult(
                parseSeries(data.path("result")),
                parseMessages(root, "warnings"),
                parseMessages(root, "infos"));
    }

    public InstantResult queryInstant(String query, long timeSec) {
        if (!properties.configured()) {
            throw new PrometheusException(503, "Prometheus is not configured or enabled");
        }
        if (query == null || query.isBlank() || timeSec <= 0) {
            throw new PrometheusException(400, "Invalid Prometheus query parameters");
        }

        URI endpoint;
        try {
            endpoint = URI.create(properties.endpoint("query"));
        } catch (IllegalArgumentException e) {
            throw new PrometheusException(503, "Invalid Prometheus URL", e);
        }
        String scheme = endpoint.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new PrometheusException(503, "Prometheus URL must use HTTP or HTTPS");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("query", query);
        form.put("time", Long.toString(timeSec));
        String formBody = toFormBody(form);
        String fullUrl = endpoint + (endpoint.getQuery() == null ? "?" : "&") + formBody;
        log.info("Prometheus query URL: {}", fullUrl);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        formBody, StandardCharsets.UTF_8));
        String authorization = authorizationHeader();
        if (!authorization.isBlank()) {
            requestBuilder.header("Authorization", authorization);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrometheusException(502, "Prometheus query was interrupted", e);
        } catch (IOException | RuntimeException e) {
            throw new PrometheusException(502, "Prometheus service is unavailable", e);
        }

        JsonNode root = parseJson(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = jsonText(root, "error");
            String message = detail.isBlank()
                    ? "Prometheus returned HTTP " + response.statusCode()
                    : "Prometheus returned HTTP " + response.statusCode() + ": "
                            + safeMessage(detail);
            throw new PrometheusException(502, message);
        }
        if (root == null || !"success".equalsIgnoreCase(jsonText(root, "status"))) {
            String detail = root == null ? "Response is not valid JSON" : jsonText(root, "error");
            throw new PrometheusException(
                    502,
                    detail.isBlank()
                            ? "Prometheus returned an unsuccessful response"
                            : "Prometheus query failed: " + safeMessage(detail));
        }

        JsonNode data = root.get("data");
        if (data == null
                || !"vector".equalsIgnoreCase(jsonText(data, "resultType"))
                || !data.path("result").isArray()) {
            throw new PrometheusException(
                    502, "Prometheus returned an unsupported query result");
        }
        return new InstantResult(
                parseInstantSeries(data.path("result")),
                parseMessages(root, "warnings"),
                parseMessages(root, "infos"));
    }

    private List<InstantSeries> parseInstantSeries(JsonNode result) {
        List<InstantSeries> series = new ArrayList<>();
        for (JsonNode item : result) {
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode metric = item.get("metric");
            if (metric != null && metric.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    labels.put(field.getKey(), field.getValue().asText(""));
                }
            }

            JsonNode value = item.get("value");
            if (value == null || !value.isArray() || value.size() < 2) {
                continue;
            }
            try {
                long timestampSeconds = new BigDecimal(value.get(0).asText()).longValue();
                Double number = finiteDouble(value.get(1).asText());
                if (number != null) {
                    series.add(new InstantSeries(labels, timestampSeconds, number));
                }
            } catch (NumberFormatException ignored) {
                // Preserve valid vector results when a single result is malformed.
            }
        }
        return series;
    }

    private List<Series> parseSeries(JsonNode result) {
        List<Series> series = new ArrayList<>();
        for (JsonNode item : result) {
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode metric = item.get("metric");
            if (metric != null && metric.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    labels.put(field.getKey(), field.getValue().asText(""));
                }
            }

            List<Sample> samples = new ArrayList<>();
            JsonNode values = item.get("values");
            if (values != null && values.isArray()) {
                for (JsonNode value : values) {
                    if (!value.isArray() || value.size() < 2) {
                        continue;
                    }
                    try {
                        long timestampMillis = new BigDecimal(value.get(0).asText())
                                .movePointRight(3)
                                .longValue();
                        Double number = finiteDouble(value.get(1).asText());
                        if (number != null) {
                            samples.add(new Sample(timestampMillis, number));
                        }
                    } catch (NumberFormatException ignored) {
                        // Preserve valid samples when a single sample is malformed.
                    }
                }
            }
            series.add(new Series(labels, samples));
        }
        return series;
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Double finiteDouble(String value) {
        if (value == null || value.isBlank()
                || "NaN".equalsIgnoreCase(value)
                || "+Inf".equalsIgnoreCase(value)
                || "-Inf".equalsIgnoreCase(value)
                || "Inf".equalsIgnoreCase(value)) {
            return null;
        }
        double number = Double.parseDouble(value);
        return Double.isFinite(number) ? number : null;
    }

    private static List<String> parseMessages(JsonNode root, String fieldName) {
        List<String> messages = new ArrayList<>();
        JsonNode values = root == null ? null : root.get(fieldName);
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    messages.add(safeMessage(text));
                }
            }
        }
        return messages;
    }

    private static String jsonText(JsonNode node, String fieldName) {
        if (node == null || node.get(fieldName) == null) {
            return "";
        }
        return node.get(fieldName).asText("").trim();
    }

    private String authorizationHeader() {
        if (!properties.bearerToken().isBlank()) {
            return "Bearer " + properties.bearerToken();
        }
        if (!properties.username().isBlank()) {
            String credentials = properties.username() + ":" + properties.password();
            return "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
        }
        return "";
    }

    private static String toFormBody(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String safeMessage(String value) {
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 240));
    }

    public record RangeResult(List<Series> series, List<String> warnings, List<String> infos) {
        public RangeResult {
            series = List.copyOf(series == null ? List.of() : series);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            infos = List.copyOf(infos == null ? List.of() : infos);
        }
    }
    public record InstantResult(
            List<InstantSeries> series,
            List<String> warnings,
            List<String> infos) {
        public InstantResult {
            series = List.copyOf(series == null ? List.of() : series);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            infos = List.copyOf(infos == null ? List.of() : infos);
        }
    }

    public record InstantSeries(
            Map<String, String> labels,
            long timestampSeconds,
            double value) {
        public InstantSeries {
            labels = Map.copyOf(labels == null ? Map.of() : labels);
        }
    }


    public record Series(Map<String, String> labels, List<Sample> samples) {
        public Series {
            labels = Map.copyOf(labels == null ? Map.of() : labels);
            samples = List.copyOf(samples == null ? List.of() : samples);
        }
    }

    public record Sample(long timestampMillis, double value) {
    }
}

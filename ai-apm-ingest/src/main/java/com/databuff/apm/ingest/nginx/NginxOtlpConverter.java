package com.databuff.apm.ingest.nginx;

import com.google.protobuf.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import com.databuff.apm.common.util.ServiceKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * Convert nginx access log JSON (or an ES {@code _source} wrapper) into OTLP protobuf requests so
 * that nginx traffic flows through the exact same {@code /v1/traces} + {@code /v1/metrics} +
 * {@code /v1/logs} ingest pipeline as the agent.
 * <ul>
 *   <li>trace request → {@code trace_dc_span} + trace-derived HTTP metrics ({@code metric_service_http})</li>
 *   <li>metric request → directly written to {@code metric_service_http} (cnt/error/slow/sumDuration/maxDuration)</li>
 *   <li>log request → {@code log_dc_record}</li>
 * </ul>
 * One access-log line → one {@code SPAN_KIND_SERVER} HTTP span + one HTTP metric batch + one log record.
 */
public final class NginxOtlpConverter {

    private static final Logger log = LoggerFactory.getLogger(NginxOtlpConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter NGINX_TIME_LOCAL =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Hosts whose traffic is excluded from ingestion (infrastructure / virtual hosts). */
    private static final Set<String> EXCLUDED_HOSTS =
            Set.of("i0.ule.com", "i1.ule.com", "track.ule.com");

    private final String requestTimeUnit;
    private final IpServiceResolver ipServiceResolver;

    public NginxOtlpConverter(String requestTimeUnit) {
        this(requestTimeUnit, new IpServiceResolver(null, null));
    }

    public NginxOtlpConverter(String requestTimeUnit, IpServiceResolver ipServiceResolver) {
        this.requestTimeUnit = requestTimeUnit == null ? "milliseconds" : requestTimeUnit;
        this.ipServiceResolver = ipServiceResolver != null ? ipServiceResolver : new IpServiceResolver(null, null);
    }

    /** Result of converting one nginx log line into the three OTLP requests. */
    public record Converted(
            ExportTraceServiceRequest traceRequest,
            ExportMetricsServiceRequest metricRequest,
            ExportLogsServiceRequest logRequest) {
    }

    /**
     * Parse the raw Kafka message and build the OTLP requests. Returns {@code null} when the line
     * cannot be parsed (missing service or malformed JSON).
     */
    public Converted convert(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode source = root.has("_source") ? root.get("_source") : root;
            NginxAccessLog accessLog = MAPPER.treeToValue(source, NginxAccessLog.class);
            if (accessLog == null) {
                return null;
            }
            if(accessLog.fullUri() == null
                    || accessLog.uri().startsWith("/purge/")
                    || accessLog.fullUri().contains("/checkhealth")
                    || accessLog.fullUri().contains("clock.ule.com/now")
                    || accessLog.fullUri().contains("sensorsdata.ule.com")
                    || accessLog.fullUri().contains("ac.ule.com")
                    || accessLog.fullUri().endsWith(".js")
                    || accessLog.fullUri().endsWith(".css")
                    || accessLog.fullUri().endsWith(".png")
                    || accessLog.fullUri().endsWith(".jpg")
                    || accessLog.fullUri().endsWith(".gif")
                    || accessLog.fullUri().contains("wholesale-api.ule.com/app/sysTime")
            ){
                return null;
            }
            return buildConverted(accessLog);
        } catch (Exception e) {
            log.warn("nginx OTLP convert failed (malformed-json): {}", truncate(json, 300));
            return null;
        }
    }

    private Converted buildConverted(NginxAccessLog l) {
        // Skip traffic for infrastructure/virtual hosts that are not application services.
        String host = l.host();
        if (host != null && EXCLUDED_HOSTS.contains(host.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }

        // Service identification: prefer proxy_host (upstream group) over host (virtual host);
        // strip the trailing "-group" suffix so the topology target matches the upstream service.
        String proxyHost = l.proxyHost();
        String serviceName;
        if (proxyHost != null && !proxyHost.isBlank()) {
            serviceName = IpServiceResolver.normalizeServiceName(proxyHost);
        } else {
            serviceName = l.host();
        }
        if (serviceName == null || serviceName.isBlank()) {
            return null;
        }

        // Time: time_local is the nginx-recorded occurrence time (already in +0800); use it as-is.
        // @timestamp (UTC) is kept as a fallback when time_local is absent.
        long startNanos = parseStartNanos(l.timeLocal(), l.timestamp());
        long durationNanos = parseDurationNanos(l.requestTime());
        long endNanos = startNanos + Math.max(0, durationNanos);

        // One access-log line = one request = one trace. Random trace_id avoids the previous
        // hash (service+uri+remote+second-precision start) colliding concurrent requests into
        // the same trace; span_id stays random.
        byte[] traceId = randomId(16);
        byte[] spanId = randomId(8);
        int status = l.status() != null ? l.status() : 200;
        String method = firstNonBlank(l.requestMethod(), "GET");
        String uri = firstNonBlank(l.uri(), l.requestUri(), "unknown");
        String spanName = method + " " + uri;
        boolean error = status >= 500;

        // --- Trace request: one SERVER HTTP span -------------------------------------------
        ResourceSpans resourceSpans = ResourceSpans.newBuilder()
                .setResource(serviceResource(serviceName, l))
                .addScopeSpans(ScopeSpans.newBuilder()
                        .addSpans(Span.newBuilder()
                                .setTraceId(ByteString.copyFrom(traceId))
                                .setSpanId(ByteString.copyFrom(spanId))
                                .setName(spanName)
                                .setKind(Span.SpanKind.SPAN_KIND_SERVER)
                                .setStartTimeUnixNano(startNanos)
                                .setEndTimeUnixNano(endNanos)
                                .setStatus(Status.newBuilder()
                                        .setCode(error
                                                ? Status.StatusCode.STATUS_CODE_ERROR
                                                : Status.StatusCode.STATUS_CODE_OK))
                                .addAttributes(kv("http.request.method", method))
                                .addAttributes(kv("http.response.status_code", Integer.toString(status)))
                                .addAttributes(kv("url.full", firstNonBlank(l.fullUri(), l.requestUri(), uri)))
                                .addAttributes(kv("http.route", uri))
                                .addAttributes(kv("url.path", uri))
                                .addAttributes(kv("http.scheme", l.scheme() == null ? "http" : l.scheme()))
                                .addAttributes(kv("server.address", firstNonBlank(l.upstreamAddr(), serviceName)))
                                .addAttributes(kv("net.peer.ip", firstNonBlank(l.remoteAddr(), "")))
                                .addAttributes(kv("http.user_agent", l.httpUserAgent() == null ? "" : l.httpUserAgent()))
                                .addAttributes(kv("client.address", firstNonBlank(resolveCallerIp(l), "")))
                                .addAttributes(kv("upstream_response_time", l.upstreamResponseTime() == null ? "" : l.upstreamResponseTime()))
                                .addAttributes(kv("request_length", l.requestLength() == null ? "" : l.requestLength()))
                                .addAttributes(kv("body_bytes_sent", l.bodyBytesSent() == null ? "" : l.bodyBytesSent()))
                                .addAttributes(kv("nginx.proxy_host", proxyHost == null ? "" : proxyHost))
                                .addAttributes(kv("nginx.server_addr", l.serverAddr() == null ? "" : l.serverAddr()))
                                .addAttributes(kv("nginx.type", l.type() == null ? "nginx" : l.type())))
                        .build())
                .build();

        // --- Log request: one log record -----------------------------------------------------
        SeverityNumber severityNumber;
        String severityText;
        if (status >= 500) {
            severityNumber = SeverityNumber.SEVERITY_NUMBER_ERROR;
            severityText = "NG-ERROR";
        } else if (status >= 400) {
            severityNumber = SeverityNumber.SEVERITY_NUMBER_WARN;
            severityText = "NG-WARN";
        } else {
            severityNumber = SeverityNumber.SEVERITY_NUMBER_INFO;
            severityText = "NG-INFO";
        }
        String body = method + " " + uri + " status=" + status + " duration=" + durationNanos + "ns"
                + " remote=" + firstNonBlank(resolveCallerIp(l), "") + " upstream=" + firstNonBlank(l.upstreamAddr(), "");
        LogRecord logRecord = LogRecord.newBuilder()
                .setTimeUnixNano(startNanos)
                .setObservedTimeUnixNano(startNanos)
                .setSeverityNumber(severityNumber)
                .setSeverityText(severityText)
                .setTraceId(ByteString.copyFrom(traceId))
                .setSpanId(ByteString.copyFrom(spanId))
                .addAttributes(kv("nginx.type", l.type() == null ? "nginx" : l.type()))
                .setBody(AnyValue.newBuilder().setStringValue(body))
                .build();
        ResourceLogs resourceLogs = ResourceLogs.newBuilder()
                .setResource(serviceResource(serviceName, l))
                .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(logRecord))
                .build();

        // --- Metric request: service.http counters (also written to metric_service_http) -----
        String callerIp = firstNonBlank(resolveCallerIp(l), "");
        String srcService = callerIp.isBlank() ? null : ipServiceResolver.resolve(callerIp);
        boolean isResolvedName = srcService != null && !IpServiceResolver.isIpAddress(srcService);
        double durationMs = durationNanos / 1_000_000.0;
        String fullUri = firstNonBlank(l.fullUri(), l.requestUri(), uri);
        ResourceMetrics resourceMetrics = ResourceMetrics.newBuilder()
                .setResource(serviceResource(serviceName, l))
                .addScopeMetrics(ScopeMetrics.newBuilder()
                        .addMetrics(httpGauge("service.http.cnt", startNanos, 1, method, uri, fullUri, status, srcService, isResolvedName))
                        .addMetrics(httpGauge("service.http.error", startNanos, error ? 1 : 0, method, uri, fullUri, status, srcService, isResolvedName))
                        .addMetrics(httpGauge("service.http.slow", startNanos, durationMs >= 500 ? 1 : 0, method, uri, fullUri, status, srcService, isResolvedName))
                        .addMetrics(httpGauge("service.http.sumDuration", startNanos, durationMs, method, uri, fullUri, status, srcService, isResolvedName))
                        .addMetrics(httpGauge("service.http.maxDuration", startNanos, durationMs, method, uri, fullUri, status, srcService, isResolvedName)))
                .build();

        return new Converted(
                ExportTraceServiceRequest.newBuilder().addResourceSpans(resourceSpans).build(),
                ExportMetricsServiceRequest.newBuilder().addResourceMetrics(resourceMetrics).build(),
                ExportLogsServiceRequest.newBuilder().addResourceLogs(resourceLogs).build());
    }

    private Metric httpGauge(String metricName, long startNanos, double value,
                             String method, String uri, String fullUri, int status,
                             String srcService, boolean isResolvedName) {
        NumberDataPoint.Builder point = NumberDataPoint.newBuilder()
                .setTimeUnixNano(startNanos)
                .addAttributes(kv("http.method", method))
                .addAttributes(kv("http.url", firstNonBlank(fullUri, uri)))
                .addAttributes(kv("http.status_code", Integer.toString(status)))
                .addAttributes(kv("isIn", "1"))
                .addAttributes(kv("isOut", "0"));
        // Integral counters (cnt/error/slow) must map onto BIGINT columns; durations stay double.
        if (value == Math.rint(value)) {
            point.setAsInt((long) value);
        } else {
            point.setAsDouble(value);
        }
        if (srcService != null && !srcService.isBlank()) {
            point.addAttributes(kv("srcService", srcService));
            if (isResolvedName) {
                point.addAttributes(kv("srcServiceId", ServiceKeyUtil.of(srcService)));
                point.addAttributes(kv("srcServiceInstance", srcService));
            }
        }
        return Metric.newBuilder()
                .setName(metricName)
                .setGauge(Gauge.newBuilder().addDataPoints(point))
                .build();
    }

    private Resource.Builder serviceResource(String serviceName, NginxAccessLog l) {
        Resource.Builder b = Resource.newBuilder()
                .addAttributes(kv("service.name", serviceName))
                .addAttributes(kv("host.name", firstNonBlank(l.serverAddr(), "unknown")));
        String instance = l.upstreamAddr();
        if (instance != null && !instance.isBlank()) {
            b.addAttributes(kv("service.instance.id", instance));
        }
        return b;
    }

    private long parseStartNanos(String timeLocal, String isoTimestamp) {
        if (timeLocal != null && !timeLocal.isBlank()) {
            try {
                Instant instant = Instant.from(NGINX_TIME_LOCAL.parse(timeLocal));
                return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
            } catch (DateTimeParseException ignored) {
            }
        }
        if (isoTimestamp != null && !isoTimestamp.isBlank()) {
            try {
                Instant instant = Instant.from(ISO_INSTANT.parse(isoTimestamp));
                return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
            } catch (DateTimeParseException ignored) {
            }
        }
        return System.currentTimeMillis() * 1_000_000L;
    }

    private long parseDurationNanos(String requestTimeSec) {
        double value = parseDoubleSafe(requestTimeSec);
        if (value == 0) {
            return 0;
        }
        double seconds = switch (requestTimeUnit.toLowerCase()) {
            case "ms", "millis", "milliseconds" -> value / 1000.0;
            case "us", "microseconds" -> value / 1_000_000.0;
            case "ns", "nanoseconds" -> value / 1_000_000_000.0;
            default -> value; // seconds
        };
        return (long) (seconds * 1_000_000_000L);
    }

    private static double parseDoubleSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String resolveCallerIp(NginxAccessLog l) {
        String xff = l.httpXForwardedFor();
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        if (l.remoteAddr() != null && !l.remoteAddr().isBlank()) {
            return l.remoteAddr().trim();
        }
        return null;
    }

    private static byte[] randomId(int bytes) {
        byte[] id = new byte[bytes];
        RNG.nextBytes(id);
        return id;
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value == null ? "" : value))
                .build();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "null";
        }
        String v = value.replace('\n', ' ').replace('\r', ' ');
        return v.length() <= max ? v : v.substring(0, max) + "...";
    }
}

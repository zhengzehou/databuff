package com.databuff.apm.ingest.nginx;

import com.databuff.apm.ingest.otel.OtlpIngestService;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nginx log ingestion entry point. Converts each Kafka access-log line into OTLP protobuf
 * requests and feeds them through {@link OtlpIngestService} so nginx traffic reuses the exact
 * same {@code /v1/traces} + {@code /v1/metrics} + {@code /v1/logs} pipeline as the agent:
 * <ul>
 *   <li>trace request → {@code trace_dc_span} + trace-derived HTTP metrics ({@code metric_service_http})</li>
 *   <li>metric request → HTTP counters written directly to {@code metric_service_http}</li>
 *   <li>log request → {@code log_dc_record}</li>
 * </ul>
 */
public final class NginxIngestService {

    private static final Logger log = LoggerFactory.getLogger(NginxIngestService.class);

    private final NginxOtlpConverter converter;
    private final OtlpIngestService otlpIngestService;
    private final AtomicLong requestsIngested = new AtomicLong();

    public NginxIngestService(NginxOtlpConverter converter, OtlpIngestService otlpIngestService) {
        this.converter = converter;
        this.otlpIngestService = otlpIngestService;
    }

    /**
     * Convert one nginx log line (JSON string) into OTLP and ingest it.
     *
     * @return number of spans accepted (0 or 1)
     */
    public int ingestLogLine(String json) {
        NginxOtlpConverter.Converted converted = converter.convert(json);
        if (converted == null) {
            return 0;
        }
        List<ResourceSpans> allSpans = new ArrayList<>(converted.traceRequest().getResourceSpansList());
        List<ResourceMetrics> allMetrics = new ArrayList<>(converted.metricRequest().getResourceMetricsList());
        List<ResourceLogs> allLogs = new ArrayList<>(converted.logRequest().getResourceLogsList());
        int accepted = ingest(new NginxOtlpConverter.Converted(
                ExportTraceServiceRequest.newBuilder().addAllResourceSpans(allSpans).build(),
                ExportMetricsServiceRequest.newBuilder().addAllResourceMetrics(allMetrics).build(),
                ExportLogsServiceRequest.newBuilder().addAllResourceLogs(allLogs).build()));

        if (accepted > 0) {
            requestsIngested.incrementAndGet();
        }
        return accepted;
    }

    /**
     * Batch ingest multiple log lines (e.g. from a polled Kafka record batch). Converted trace
     * resource-spans, metric resource-metrics and log resource-logs are merged into single
     * requests for efficiency.
     *
     * @return total spans accepted
     */
    public int ingestLogLines(List<String> logLines) {
        if (logLines == null || logLines.isEmpty()) {
            return 0;
        }
        List<ResourceSpans> allSpans = new ArrayList<>();
        List<ResourceMetrics> allMetrics = new ArrayList<>();
        List<ResourceLogs> allLogs = new ArrayList<>();
        int convertedCount = 0;
        for (String line : logLines) {
            NginxOtlpConverter.Converted converted = converter.convert(line);
            if (converted == null) {
                continue;
            }
            allSpans.addAll(converted.traceRequest().getResourceSpansList());
            allMetrics.addAll(converted.metricRequest().getResourceMetricsList());
            allLogs.addAll(converted.logRequest().getResourceLogsList());
            convertedCount++;
        }
        if (convertedCount == 0) {
            return 0;
        }
        int accepted = ingest(new NginxOtlpConverter.Converted(
                ExportTraceServiceRequest.newBuilder().addAllResourceSpans(allSpans).build(),
                ExportMetricsServiceRequest.newBuilder().addAllResourceMetrics(allMetrics).build(),
                ExportLogsServiceRequest.newBuilder().addAllResourceLogs(allLogs).build()));
        if (accepted > 0) {
            requestsIngested.addAndGet(accepted);
        }
        return accepted;
    }

    public long requestsIngested() {
        return requestsIngested.get();
    }

    /**
     * Push the converted OTLP requests through the standard pipeline. Trace spans drive the trace
     * storage and trace-derived HTTP metrics; the metric request writes HTTP counters directly;
     * logs are written to the log store.
     */
    private int ingest(NginxOtlpConverter.Converted converted) {
        int traces = otlpIngestService.ingestTraces(converted.traceRequest());
        otlpIngestService.ingestMetrics(converted.metricRequest());
        otlpIngestService.ingestLogs(converted.logRequest());
        return traces;
    }
}

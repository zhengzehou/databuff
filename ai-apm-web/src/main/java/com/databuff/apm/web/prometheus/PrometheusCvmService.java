package com.databuff.apm.web.prometheus;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
public class PrometheusCvmService {

    private static final Logger log = LoggerFactory.getLogger(PrometheusCvmService.class);
    private static final long DEFAULT_RANGE_SECONDS = 3_600L;
    private static final long MIN_STEP_SECONDS = 15L;
    private static final String PANEL_LABEL = "cvm_panel";
    private static final String SERIES_LABEL = "cvm_series";
    private static final String STAT_LABEL = "cvm_stat";
    private static final String FILESYSTEM_LABEL = "cvm_filesystem";
    private static final long MAX_STEP_SECONDS = 3_600L;
    private static final String RATE_WINDOW = "30m";
    private static final Pattern SAFE_INSTANCE = Pattern.compile("[A-Za-z0-9._:-]{1,253}");

    private final PrometheusClient client;
    private final PrometheusProperties properties;
    private final ExecutorService queryExecutor;

    public PrometheusCvmService(PrometheusClient client, PrometheusProperties properties) {
        this.client = client;
        this.properties = properties;
        this.queryExecutor = Executors.newFixedThreadPool(
                properties.maxConcurrentQueries(),
                runnable -> {
                    Thread thread = new Thread(runnable, "prometheus-cvm-query");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public CvmMetricResponse query(CvmMetricQueryRequest request) {
        if (!properties.configured()) {
            throw new PrometheusException(503, "Prometheus is not configured or enabled");
        }
        if (request == null || request.serviceId() == null || request.serviceId().isBlank()) {
            throw new PrometheusException(400, "serviceId must not be blank");
        }

        String instance = normalizeInstance(request.instanceIp());
        long now = System.currentTimeMillis() / 1_000L;
        long start = normalizeSeconds(request.start(), now - DEFAULT_RANGE_SECONDS);
        long end = normalizeSeconds(request.end(), now);
        if (start >= end) {
            throw new PrometheusException(400, "start must be before end");
        }

        long durationSeconds = Math.max(1L, (end - start));
        if (durationSeconds > properties.maxRangeSeconds()) {
            throw new PrometheusException(400, "The requested range is too large for CVM metrics");
        }

        long requestedStep = request.interval() == null ? 60L : request.interval();
        if (requestedStep <= 0) {
            throw new PrometheusException(400, "interval must be greater than zero");
        }
        long step = Math.min(MAX_STEP_SECONDS, Math.max(MIN_STEP_SECONDS, requestedStep));
        long pointStep = (durationSeconds + properties.maxDataPoints() - 1L)
                / properties.maxDataPoints();
        final long queryStep = Math.max(step, pointStep);

        List<PanelDefinition> panelDefinitions = panelDefinitions(instance);
        List<StatDefinition> statDefinitions = statDefinitions(instance);

        CompletableFuture<PrometheusClient.RangeResult> panelFuture = CompletableFuture.supplyAsync(
                () -> client.queryRange(combinedPanelQuery(panelDefinitions), start, end, queryStep),
                queryExecutor);
        CompletableFuture<PrometheusClient.InstantResult> statFuture = CompletableFuture.supplyAsync(
                () -> client.queryInstant(combinedStatQuery(statDefinitions), end),
                queryExecutor);
        CompletableFuture<PrometheusClient.InstantResult> filesystemFuture = CompletableFuture.supplyAsync(
                () -> client.queryInstant(filesystemQuery(instance), end),
                queryExecutor);

        Set<String> warnings = new LinkedHashSet<>();
        QueryOutcome<PrometheusClient.RangeResult> panelOutcome = await(
                panelFuture,
                new PrometheusClient.RangeResult(List.of(), List.of(), List.of()),
                "CVM chart");
        QueryOutcome<PrometheusClient.InstantResult> statOutcome = await(
                statFuture,
                new PrometheusClient.InstantResult(List.of(), List.of(), List.of()),
                "CVM summary");
        QueryOutcome<PrometheusClient.InstantResult> filesystemOutcome = await(
                filesystemFuture,
                new PrometheusClient.InstantResult(List.of(), List.of(), List.of()),
                "CVM filesystem");

        addMessages(warnings, panelOutcome.value());
        addMessages(warnings, statOutcome.value());
        addMessages(warnings, filesystemOutcome.value());

        if (!panelOutcome.success() && !statOutcome.success() && !filesystemOutcome.success()) {
            String message = firstMessage(
                    panelOutcome.error(), statOutcome.error(), filesystemOutcome.error());
            throw new PrometheusException(
                    502,
                    message == null ? "Prometheus CVM queries failed" : message);
        }

        Map<String, List<PrometheusClient.Series>> groupedSeries =
                groupPanelSeries(panelOutcome.value());
        String panelError = panelOutcome.success() ? null : panelOutcome.error();
        List<CvmMetricResponse.Panel> panels = panelDefinitions.stream()
                .map(definition -> toPanel(
                        definition,
                        groupedSeries.getOrDefault(definition.key(), List.of()),
                        panelError))
                .toList();

        String statError = statOutcome.success() ? null : statOutcome.error();
        List<CvmMetricResponse.Stat> stats =
                toStats(statDefinitions, statOutcome.value(), statError);
        String filesystemError = filesystemOutcome.success() ? null : filesystemOutcome.error();
        List<CvmMetricResponse.Filesystem> filesystems =
                toFilesystems(filesystemOutcome.value(), instance);
        if (filesystemError != null && !filesystemError.isBlank()) {
            warnings.add("CVM filesystem: " + filesystemError);
        }

        return new CvmMetricResponse(
                instance,
                start,
                end,
                queryStep,
                stats,
                filesystems,
                panels,
                new ArrayList<>(warnings));
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "configured", properties.configured(),
                "job", properties.job(),
                "apiPrefix", properties.apiPrefix());
    }

    private <T> QueryOutcome<T> await(
            CompletableFuture<T> future,
            T fallback,
            String category) {
        try {
            return new QueryOutcome<>(future.join(), true, null);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = queryError(cause);
            log.error("Prometheus {} query failed: {}", category, message, cause);
            return new QueryOutcome<>(fallback, false, message);
        } catch (Exception e) {
            String message = queryError(e);
            log.error("Prometheus {} query failed: {}", category, message, e);
            return new QueryOutcome<>(fallback, false, message);
        }
    }

    private static String queryError(Throwable error) {
        if (error instanceof PrometheusException && error.getMessage() != null
                && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return "Query failed";
    }

    private static void addMessages(Set<String> warnings, PrometheusClient.RangeResult result) {
        warnings.addAll(result.warnings());
        warnings.addAll(result.infos());
    }

    private static void addMessages(Set<String> warnings, PrometheusClient.InstantResult result) {
        warnings.addAll(result.warnings());
        warnings.addAll(result.infos());
    }

    private static String firstMessage(String... messages) {
        for (String message : messages) {
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return null;
    }

    private static Map<String, List<PrometheusClient.Series>> groupPanelSeries(
            PrometheusClient.RangeResult result) {
        Map<String, List<PrometheusClient.Series>> grouped = new LinkedHashMap<>();
        for (PrometheusClient.Series item : result.series()) {
            String key = item.labels().get(PANEL_LABEL);
            if (key != null && !key.isBlank()) {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
            }
        }
        return grouped;
    }

    private CvmMetricResponse.Panel toPanel(
            PanelDefinition definition,
            List<PrometheusClient.Series> source,
            String error) {
        List<CvmMetricResponse.Series> series = source.stream()
                .filter(item -> !item.samples().isEmpty())
                .map(item -> {
                    String seriesKey = item.labels().get(SERIES_LABEL);
                    Map<String, String> labels = publicLabels(item.labels());
                    int yAxisIndex = yAxisIndex(definition.key(), seriesKey);
                    return new CvmMetricResponse.Series(
                            seriesName(labels, item.labels(), definition.key(), definition.title()),
                            labels,
                            item.samples().stream()
                                    .map(sample -> List.<Object>of(
                                            sample.timestampMillis(),
                                            sample.value() * definition.valueScale()))
                                    .toList(),
                            seriesUnit(definition, seriesKey),
                            yAxisIndex);
                })
                .toList();
        return new CvmMetricResponse.Panel(
                definition.key(),
                definition.title(),
                definition.unit(),
                series,
                error);
    }

    private static Map<String, String> publicLabels(Map<String, String> labels) {
        Map<String, String> copy = new LinkedHashMap<>(labels);
        copy.remove(PANEL_LABEL);
        copy.remove(SERIES_LABEL);
        return copy;
    }

    private static int yAxisIndex(String panelKey, String seriesKey) {
        return "fileDescriptor".equals(panelKey) && "context".equals(seriesKey) ? 1 : 0;
    }

    private static String seriesUnit(PanelDefinition definition, String seriesKey) {
        if ("fileDescriptor".equals(definition.key()) && "context".equals(seriesKey)) {
            return "ops/s";
        }
        return definition.unit();
    }

    private static String seriesName(
            Map<String, String> labels,
            Map<String, String> rawLabels,
            String panelKey,
            String fallback) {
        String seriesKey = rawLabels.get(SERIES_LABEL);
        String device = firstNonBlank(labels.get("device"), labels.get("interface"));

        if (seriesKey != null && !seriesKey.isBlank()) {
            return meaningfulSeriesSuffix(panelKey, seriesKey, device, labels);
        }

        if ("filesystemUsage".equals(panelKey)) {
            String mountpoint = firstNonBlank(labels.get("mountpoint"), fallback);
            return "\u5206\u533a_" + (mountpoint == null ? "-" : mountpoint);
        }

        for (String key : List.of(
                "operation", "mode", "device", "mountpoint", "interface",
                "chip", "sensor", "cpu")) {
            String value = labels.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        String metric = labels.get("__name__");
        return metric == null || metric.isBlank() ? fallback : metric;
    }

    private static String meaningfulSeriesSuffix(
            String panelKey,
            String seriesKey,
            String device,
            Map<String, String> labels) {
        String disk = device == null ? "disk" : device;
        String mountpoint = firstNonBlank(labels.get("mountpoint"), seriesKey);
        return switch (panelKey) {
            case "fileDescriptor" -> "filefd".equals(seriesKey)
                    ? "\u6587\u4ef6\u63cf\u8ff0\u7b26"
                    : "\u4e0a\u4e0b\u6587\u5207\u6362";
            case "load" -> switch (seriesKey) {
                case "1m" -> "1\u5206\u949f\u8d1f\u8f7d";
                case "5m" -> "5\u5206\u949f\u8d1f\u8f7d";
                case "15m" -> "15\u5206\u949f\u8d1f\u8f7d";
                default -> "\u7cfb\u7edf\u8d1f\u8f7d_" + seriesKey;
            };
            case "cpu" -> switch (seriesKey) {
                case "system" -> "\u7cfb\u7edf";
                case "user" -> "\u7528\u6237";
                case "iowait" -> "iowait";
                case "total" -> "\u603b\u4f7f\u7528\u7387";
                default -> "\u0043PU_" + seriesKey;
            };
            case "network" -> (device == null ? "network" : device)
                    + ("receive".equals(seriesKey)
                    ? "_\u4e0b\u8f7d"
                    : "_\u4e0a\u4f20");
            case "filesystemUsage" -> "\u5206\u533a_"
                    + (mountpoint == null ? "-" : mountpoint);
            case "memoryUsage" -> "\u5185\u5b58\u4f7f\u7528\u7387";
            case "closeWait" -> "CLOSE_WAIT\u8fde\u63a5\u6570";
            case "memory" -> switch (seriesKey) {
                case "total" -> "\u603b\u5185\u5b58";
                case "used" -> "\u5df2\u7528\u5185\u5b58";
                case "available" -> "\u53ef\u7528\u5185\u5b58";
                default -> "\u5185\u5b58_" + seriesKey;
            };
            case "ioTime" -> disk + "_I/O\u8017\u65f6";
            case "diskIops", "diskThroughput", "diskLatency" ->
                    disk + ("read".equals(seriesKey)
                            ? "_\u8bfb\u53d6"
                            : "_\u5199\u5165");
            case "connections" -> switch (seriesKey) {
                case "CurrEstab" -> "TCP_\u5df2\u5efa\u7acb";
                case "TCP_tw" -> "TCP_TIME_WAIT";
                case "Sockets_used" -> "Socket_\u5df2\u4f7f\u7528";
                case "UDP_inuse" -> "UDP_\u4f7f\u7528\u4e2d";
                case "TCP_alloc" -> "TCP_\u5df2\u5206\u914d";
                default -> "\u8fde\u63a5_" + seriesKey;
            };
            default -> seriesKey;
        };
    }

    private static String withInstance(String instance, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return instance == null || instance.isBlank() ? "-" : instance;
        }
        if (instance == null || instance.isBlank()) {
            return suffix;
        }
        return instance + "_" + suffix;
    }

    private static String instanceLabel(Map<String, String> labels) {
        String value = labels.get("instance");
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(1, close) : value;
        }
        if (value.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) {
            return value.substring(0, value.lastIndexOf(':'));
        }
        return value;
    }

    private List<PanelDefinition> panelDefinitions(String instance) {
        String fileDescriptors = selector("node_filefd_allocated", instance);
        String contextSwitches = selector("node_context_switches_total", instance);
        String load1 = selector("node_load1", instance);
        String load5 = selector("node_load5", instance);
        String load15 = selector("node_load15", instance);

        String cpuSystem = selector("node_cpu_seconds_total", instance, "mode=\"system\"");
        String cpuUser = selector("node_cpu_seconds_total", instance, "mode=\"user\"");
        String cpuIowait = selector("node_cpu_seconds_total", instance, "mode=\"iowait\"");
        String cpuIdle = selector("node_cpu_seconds_total", instance, "mode=\"idle\"");

        String networkReceive = selector(
                "node_network_receive_bytes_total",
                instance,
                "device!~\"tap.*|veth.*|br.*|docker.*|virbr*|lo*\"");
        String networkTransmit = selector(
                "node_network_transmit_bytes_total",
                instance,
                "device!~\"tap.*|veth.*|br.*|docker.*|virbr*|lo*\"");

        String fsSize = selector(
                "node_filesystem_size_bytes", instance, "fstype=~\"ext4|xfs\"");
        String fsFree = selector(
                "node_filesystem_free_bytes", instance, "fstype=~\"ext4|xfs\"");

        String memTotal = selector("node_memory_MemTotal_bytes", instance);
        String memFree = selector("node_memory_MemFree_bytes", instance);
        String memCached = selector("node_memory_Cached_bytes", instance);
        String memBuffers = selector("node_memory_Buffers_bytes", instance);
        String memSlab = selector("node_memory_Slab_bytes", instance);

        String diskIoTime = selector("node_disk_io_time_seconds_total", instance);
        String diskReads = selector("node_disk_reads_completed_total", instance);
        String diskWrites = selector("node_disk_writes_completed_total", instance);
        String diskReadBytes = selector("node_disk_read_bytes_total", instance);
        String diskWriteBytes = selector("node_disk_written_bytes_total", instance);
        String diskReadTime = selector("node_disk_read_time_seconds_total", instance);
        String diskWriteTime = selector("node_disk_write_time_seconds_total", instance);

        String tcpEstablished = selector("node_netstat_Tcp_CurrEstab", instance);
        String tcpTw = selector("node_sockstat_TCP_tw", instance);
        String socketsUsed = selector("node_sockstat_sockets_used", instance);
        String udpInUse = selector("node_sockstat_UDP_inuse", instance);
        String tcpAlloc = selector("node_sockstat_TCP_alloc", instance);

        String fileDescriptorQuery = tagged(fileDescriptors, "filefd")
                + " or " + tagged(
                "irate(" + contextSwitches + "[" + RATE_WINDOW + "])", "context");
        String loadQuery = tagged(load1, "1m")
                + " or " + tagged(load5, "5m")
                + " or " + tagged(load15, "15m");
        String cpuQuery = tagged(
                "avg(irate(" + cpuSystem + "[" + RATE_WINDOW + "])) by (instance)",
                "system")
                + " or " + tagged(
                "avg(irate(" + cpuUser + "[" + RATE_WINDOW + "])) by (instance)",
                "user")
                + " or " + tagged(
                "avg(irate(" + cpuIowait + "[" + RATE_WINDOW + "])) by (instance)",
                "iowait")
                + " or " + tagged(
                "1 - avg(irate(" + cpuIdle + "[" + RATE_WINDOW + "])) by (instance)",
                "total");
        String networkQuery = tagged(
                "irate(" + networkReceive + "[" + RATE_WINDOW + "]) * 8",
                "receive")
                + " or " + tagged(
                "irate(" + networkTransmit + "[" + RATE_WINDOW + "]) * 8",
                "transmit");
        String memoryUsageQuery = tagged(
                "(sum(" + memTotal + ") - sum(" + memFree + ") - sum(" + memCached
                        + ") - sum(" + memBuffers + ") - sum(" + memSlab + ")) / sum(" + memTotal
                        + ") * 100",
                "memoryUsage");
        String closeWaitQuery = tagged(
                selector("node_tcp_connection_states", instance, "state=\"close_wait\""),
                "closeWait");
        String filesystemUsageQuery = "1 - (" + fsFree + " / " + fsSize + ")";
        String diskIoTimeQuery = tagged(
                "irate(" + diskIoTime + "[" + RATE_WINDOW + "])",
                "ioTime");
        String diskIopsQuery = tagged(
                "irate(" + diskReads + "[" + RATE_WINDOW + "])",
                "read")
                + " or " + tagged(
                "irate(" + diskWrites + "[" + RATE_WINDOW + "])",
                "write");
        String diskThroughputQuery = tagged(
                "irate(" + diskReadBytes + "[" + RATE_WINDOW + "])",
                "read")
                + " or " + tagged(
                "irate(" + diskWriteBytes + "[" + RATE_WINDOW + "])",
                "write");
        String diskLatencyQuery = tagged(
                "(irate(" + diskReadTime + "[" + RATE_WINDOW + "])"
                        + " / irate(" + diskReads + "[" + RATE_WINDOW + "]))",
                "read")
                + " or " + tagged(
                "(irate(" + diskWriteTime + "[" + RATE_WINDOW + "])"
                        + " / irate(" + diskWrites + "[" + RATE_WINDOW + "]))",
                "write");
        String connectionsQuery = tagged(tcpEstablished, "CurrEstab")
                + " or " + tagged(tcpTw, "TCP_tw")
                + " or " + tagged(socketsUsed, "Sockets_used")
                + " or " + tagged(udpInUse, "UDP_inuse")
                + " or " + tagged(tcpAlloc, "TCP_alloc");

        return List.of(
                new PanelDefinition(
                        "fileDescriptor",
                        "File descriptors and context switches",
                        "count",
                        fileDescriptorQuery),
                new PanelDefinition("load", "System load", "load", loadQuery),
                new PanelDefinition("cpu", "CPU utilization", "percent", cpuQuery, 100d),
                new PanelDefinition("network", "Network traffic", "bps", networkQuery),
                new PanelDefinition("memoryUsage", "Memory usage", "percent", memoryUsageQuery),
                new PanelDefinition("closeWait", "TCP CLOSE_WAIT", "count", closeWaitQuery),
                new PanelDefinition(
                        "filesystemUsage",
                        "Filesystem utilization",
                        "percent",
                        filesystemUsageQuery,
                        100d),
                new PanelDefinition(
                        "ioTime",
                        "I/O time per second",
                        "ms",
                        diskIoTimeQuery,
                        1_000d),
                new PanelDefinition(
                        "diskIops",
                        "Disk read/write rate (IOPS)",
                        "ops/s",
                        diskIopsQuery),
                new PanelDefinition(
                        "diskThroughput",
                        "Disk read/write throughput",
                        "byte/s",
                        diskThroughputQuery),
                new PanelDefinition(
                        "diskLatency",
                        "Per-I/O read/write latency",
                        "ms",
                        diskLatencyQuery,
                        1_000d),
                new PanelDefinition(
                        "connections",
                        "Network connection information",
                        "count",
                        connectionsQuery));
    }

    private List<CvmMetricResponse.Stat> toStats(
            List<StatDefinition> definitions,
            PrometheusClient.InstantResult result,
            String error) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (PrometheusClient.InstantSeries item : result.series()) {
            String key = item.labels().get(STAT_LABEL);
            if (key != null && !key.isBlank()) {
                values.put(key, item.value());
            }
        }
        return definitions.stream()
                .map(definition -> new CvmMetricResponse.Stat(
                        definition.key(),
                        definition.title(),
                        definition.unit(),
                        values.get(definition.key()),
                        error))
                .toList();
    }

    private List<CvmMetricResponse.Filesystem> toFilesystems(
            PrometheusClient.InstantResult result,
            String instance) {
        Map<String, FilesystemAccumulator> rows = new LinkedHashMap<>();
        for (PrometheusClient.InstantSeries item : result.series()) {
            String kind = item.labels().get(FILESYSTEM_LABEL);
            if (kind == null || kind.isBlank()) {
                continue;
            }
            Map<String, String> labels = publicLabels(item.labels());
            String mountpoint = firstNonBlank(labels.get("mountpoint"), "/");
            String device = firstNonBlank(labels.get("device"), "");
            String filesystem = firstNonBlank(labels.get("fstype"), device, "-");
            String key = mountpoint + "\u0000" + device + "\u0000" + filesystem;
            FilesystemAccumulator row = rows.computeIfAbsent(
                    key,
                    ignored -> new FilesystemAccumulator(filesystem, instance, mountpoint));
            switch (kind) {
                case "total" -> row.totalBytes = item.value();
                case "available" -> row.availableBytes = item.value();
                case "usedPercent" -> row.usedPercent = item.value() * 100d;
                default -> {
                    // Ignore an unknown tagged filesystem expression.
                }
            }
        }

        return rows.values().stream()
                .filter(row -> row.totalBytes != null
                        || row.availableBytes != null
                        || row.usedPercent != null)
                .sorted(Comparator.comparing(row -> row.mountpoint))
                .map(row -> new CvmMetricResponse.Filesystem(
                        row.filesystem,
                        row.ip,
                        row.mountpoint,
                        row.totalBytes,
                        row.availableBytes,
                        row.usedPercent))
                .toList();
    }

    private String combinedPanelQuery(List<PanelDefinition> definitions) {
        return definitions.stream()
                .map(definition -> tag(definition.query(), PANEL_LABEL, definition.key()))
                .collect(Collectors.joining(" or "));
    }

    private String combinedStatQuery(List<StatDefinition> definitions) {
        return definitions.stream()
                .map(StatDefinition::query)
                .collect(Collectors.joining(" or "));
    }

    private String filesystemQuery(String instance) {
        String fsSize = selector(
                "node_filesystem_size_bytes", instance, "fstype=~\"ext4|xfs\"");
        String fsAvailable = selector(
                "node_filesystem_avail_bytes", instance, "fstype=~\"ext4|xfs\"");
        String fsFree = selector(
                "node_filesystem_free_bytes", instance, "fstype=~\"ext4|xfs\"");
        String usedPercent = "1 - (" + fsFree + " / " + fsSize + ")";
        return tag(fsSize + " - 0", FILESYSTEM_LABEL, "total")
                + " or " + tag(fsAvailable + " - 0", FILESYSTEM_LABEL, "available")
                + " or " + tag(usedPercent, FILESYSTEM_LABEL, "usedPercent");
    }

    private List<StatDefinition> statDefinitions(String instance) {
        String cpuIdle = selector(
                "node_cpu_seconds_total", instance, "mode=\"idle\"");
        String cpuIowait = selector(
                "node_cpu_seconds_total", instance, "mode=\"iowait\"");
        String cpuSystem = selector(
                "node_cpu_seconds_total", instance, "mode=\"system\"");
        String memTotal = selector("node_memory_MemTotal_bytes", instance);
        String memAvailable = selector("node_memory_MemAvailable_bytes", instance);
        String swapTotal = selector("node_memory_SwapTotal_bytes", instance);
        String swapFree = selector("node_memory_SwapFree_bytes", instance);
        String bootTime = selector("node_boot_time_seconds", instance);

        return List.of(
                new StatDefinition(
                        "uptime", "System uptime", "second",
                        statQuery("uptime", "vector(scalar(sum(time() - " + bootTime + ")))")),
                new StatDefinition(
                        "memoryTotal", "Memory total", "byte",
                        statQuery("memoryTotal", "vector(scalar(sum(" + memTotal + ")))")),
                new StatDefinition(
                        "cpuCores", "CPU cores", "count",
                        statQuery(
                                "cpuCores",
                                "vector(scalar(sum(count(" + cpuSystem + ") by (cpu))))")),
                new StatDefinition(
                        "cpuIowait", "CPU iowait", "percent",
                        statQuery(
                                "cpuIowait",
                                "vector(scalar(avg(irate(" + cpuIowait
                                        + "[" + RATE_WINDOW + "])) * 100))")),
                new StatDefinition(
                        "cpuUsage", "Total CPU usage", "percent",
                        statQuery(
                                "cpuUsage",
                                "vector(scalar(100 - (avg(irate(" + cpuIdle
                                        + "[" + RATE_WINDOW + "])) * 100)))")),
                new StatDefinition(
                        "memoryUsage", "Memory usage", "percent",
                        statQuery(
                                "memoryUsage",
                                "vector(scalar((1 - (" + memAvailable + " / "
                                        + memTotal + ")) * 100))")),
                new StatDefinition(
                        "swapUsage", "Swap usage", "percent",
                        statQuery(
                                "swapUsage",
                                "vector(scalar((1 - (" + swapFree + " / "
                                        + swapTotal + ")) * 100))")));
    }

    private String statQuery(String key, String expression) {
        return tag(expression, STAT_LABEL, key);
    }

    private String selector(String metric, String instance, String... extraMatchers) {
        List<String> matchers = new ArrayList<>();
        matchers.add("instance=~\"" + instanceMatcher(instance) + "\"");
        if (!properties.job().isBlank()) {
            matchers.add("job=\"" + escapePromString(properties.job()) + "\"");
        }
        for (String extraMatcher : extraMatchers) {
            if (extraMatcher != null && !extraMatcher.isBlank()) {
                matchers.add(extraMatcher);
            }
        }
        return metric + "{" + String.join(",", matchers) + "}";
    }

    private static String tag(String query, String label, String value) {
        return "label_replace((" + query + "), \"" + label + "\", \""
                + escapePromString(value) + "\", \"__name__\", \".*\")";
    }

    private static String tagged(String query, String value) {
        return tag(query, SERIES_LABEL, value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeInstance(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PrometheusException(400, "instanceIp must not be blank");
        }
        String value = raw.trim();
        int parenthesisOpen = value.lastIndexOf('(');
        int parenthesisClose = value.lastIndexOf(')');
        if (parenthesisOpen >= 0 && parenthesisClose > parenthesisOpen) {
            value = value.substring(parenthesisOpen + 1, parenthesisClose).trim();
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                throw new PrometheusException(400, "Invalid bracketed instance");
            }
            String suffix = value.substring(close + 1);
            if (!suffix.isEmpty() && !suffix.matches(":[0-9]{1,5}")) {
                throw new PrometheusException(400, "Invalid instance port");
            }
            value = value.substring(1, close);
        } else if (value.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) {
            value = value.substring(0, value.lastIndexOf(':'));
        }
        if (value.equalsIgnoreCase("unknown") || !SAFE_INSTANCE.matcher(value).matches()) {
            throw new PrometheusException(400, "Invalid instanceIp");
        }
        return value;
    }

    private static String instanceMatcher(String instance) {
        String escaped = escapePromRegex(instance);
        if (instance.indexOf(':') >= 0) {
            return "(?:\\\\[" + escaped + "\\\\]|" + escaped + ")(?::[0-9]{1,5})?";
        }
        return escaped + "(?::[0-9]{1,5})?";
    }

    private static String escapePromRegex(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ("\\.^$|?*+()[]{}".indexOf(ch) >= 0) {
                escaped.append("\\\\");
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    private static String escapePromString(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "");
    }

    private static long normalizeSeconds(Long value, long fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    @PreDestroy
    void shutdown() {
        queryExecutor.shutdownNow();
    }

    private record PanelDefinition(
            String key,
            String title,
            String unit,
            String query,
            double valueScale) {
        private PanelDefinition(String key, String title, String unit, String query) {
            this(key, title, unit, query, 1d);
        }
    }

    private record StatDefinition(String key, String title, String unit, String query) {
    }

    private record QueryOutcome<T>(T value, boolean success, String error) {
    }

    private static final class FilesystemAccumulator {
        private final String filesystem;
        private final String ip;
        private final String mountpoint;
        private Double totalBytes;
        private Double availableBytes;
        private Double usedPercent;

        private FilesystemAccumulator(String filesystem, String ip, String mountpoint) {
            this.filesystem = filesystem;
            this.ip = ip;
            this.mountpoint = mountpoint;
        }
    }
}

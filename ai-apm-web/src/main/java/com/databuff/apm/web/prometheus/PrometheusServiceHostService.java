package com.databuff.apm.web.prometheus;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class PrometheusServiceHostService {
    private static final Pattern SAFE = Pattern.compile("[^\\r\\n\\u0000-\\u001f]{1,256}");
    private final PrometheusClient client;
    private final PrometheusProperties properties;
    private final ExecutorService executor;

    public PrometheusServiceHostService(PrometheusClient client, PrometheusProperties properties) {
        this.client = client;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrentQueries());
    }

    public Map<String, Object> query(ServiceHostMetricQueryRequest request) {
        if (!properties.configured()) throw new PrometheusException(503, "Prometheus is not configured or enabled");
        Labels labels = parse(request == null ? null : request.serviceName());
        long now = System.currentTimeMillis() / 1000L;
        long start = seconds(request.start(), now - 3600), end = seconds(request.end(), now);
        if (start >= end) throw new PrometheusException(400, "start must be before end");
        if (end - start > properties.maxRangeSeconds()) throw new PrometheusException(400, "The requested range is too large");
        long requested = request.interval() == null ? 60 : request.interval();
        if (requested <= 0) throw new PrometheusException(400, "interval must be greater than zero");
        long step = Math.max(15, Math.max(requested, (end - start + properties.maxDataPoints() - 1) / properties.maxDataPoints()));
        List<Definition> definitions = definitions(labels);
        List<CompletableFuture<Map<String, Object>>> futures = definitions.stream()
                .map(d -> CompletableFuture.supplyAsync(() -> panel(d, start, end, step), executor)).toList();
        List<Map<String, Object>> panels = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            Definition d = definitions.get(i);
            try { panels.add(futures.get(i).join()); }
            catch (CompletionException e) {
                String message = e.getCause() == null ? "Query failed" : String.valueOf(e.getCause().getMessage());
                panels.add(Map.of("key", d.key(), "title", d.title(), "unit", d.unit(), "series", List.of(), "error", message));
                warnings.add(d.title() + ": " + message);
            }
        }
        return Map.of("serviceName", request.serviceName(), "start", start, "end", end,
                "interval", step, "panels", panels, "warnings", warnings);
    }

    private Map<String, Object> panel(Definition d, long start, long end, long step) {
        PrometheusClient.RangeResult result = client.queryRange(d.query(), start, end, step);
        List<Map<String, Object>> series = result.series().stream().filter(s -> !s.samples().isEmpty()).map(s -> {
            Map<String, String> labels = new LinkedHashMap<>(s.labels());
            String name = labels.getOrDefault("instance", "unknown");
            if (labels.containsKey("direction")) name = labels.get("direction") + " " + name;
            if (labels.containsKey("mountpoint")) name += " " + labels.get("mountpoint");
            List<List<Object>> values = s.samples().stream()
                    .map(v -> List.<Object>of(v.timestampMillis(), v.value())).toList();
            return Map.<String, Object>of("name", name, "labels", labels, "values", values, "unit", d.unit());
        }).toList();
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("key", d.key()); panel.put("title", d.title()); panel.put("unit", d.unit());
        panel.put("series", series); panel.put("error", null);
        return panel;
    }

    private static List<Definition> definitions(Labels l) {
        String m = "Module=\"" + esc(l.module()) + "\",APP=\"" + esc(l.app()) + "\"";
        String idle = metric("node_cpu_seconds_total", m + ",mode=\"idle\""), all = metric("node_cpu_seconds_total", m);
        String total = metric("node_memory_MemTotal_bytes", m), free = metric("node_memory_MemFree_bytes", m);
        String cached = metric("node_memory_Cached_bytes", m), buffers = metric("node_memory_Buffers_bytes", m);
        String slab = metric("node_memory_Slab_bytes", m), fs = m + ",fstype=~\"ext4|xfs\"";
        String receive = metric("node_network_receive_bytes_total", m + ",device!=\"lo\"");
        String transmit = metric("node_network_transmit_bytes_total", m + ",device!=\"lo\"");
        return List.of(
            new Definition("cpu", "CPU使用率", "%", "100*(1-sum by(instance,Module,APP)(increase(" + idle + "[5m]))/sum by(instance,Module,APP)(increase(" + all + "[5m])))"),
            new Definition("memory", "内存使用率", "%", "((" + total + "-" + free + "-" + cached + "-" + buffers + "-" + slab + ")/" + total + ")*100"),
            new Definition("load", "服务器负载", "", metric("node_load1", m)),
            new Definition("network", "网络流量", "bps", tagged("sum by(instance,Module,APP)(irate(" + receive + "[5m])*8)", "接收") + " or " + tagged("sum by(instance,Module,APP)(irate(" + transmit + "[5m])*8)", "发送")),
            new Definition("closeWait", "CLOSE_WAIT", "", "sum by(instance,Module,APP)(" + metric("node_tcp_connection_states", m + ",state=\"close_wait\"") + ")"),
            new Definition("disk", "磁盘使用率", "%", "max by(instance,Module,APP)(round((1-" + metric("node_filesystem_avail_bytes", fs) + "/" + metric("node_filesystem_size_bytes", fs) + ")*100))")
        );
    }

    static Labels parse(String raw) {
        if (raw == null) throw new PrometheusException(400, "serviceName must not be blank");
        String[] p = raw.trim().split("/", -1);
        if (p.length != 2 || Arrays.stream(p).anyMatch(v -> v.isBlank() || !SAFE.matcher(v).matches()))
            throw new PrometheusException(400, "serviceName must use Module/APP format");
        return new Labels(p[0].trim(), p[1].trim());
    }
    private static String metric(String n, String m) { return n + "{" + m + "}"; }
    private static String tagged(String q, String v) { return "label_replace((" + q + "),\"direction\",\"" + v + "\",\"__name__\",\".*\")"; }
    private static String esc(String v) { return v.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static long seconds(Long v, long fallback) { return v == null || v <= 0 ? fallback : v > 10000000000L ? v / 1000 : v; }
    record Labels(String module, String app) {}
    private record Definition(String key, String title, String unit, String query) {}
}

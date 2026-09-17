package com.databuff.apm.web.prometheus;

import java.util.List;
import java.util.Map;

/** Normalized CVM dashboard response consumed by the service-detail CVM tab. */
public record CvmMetricResponse(
        String instanceIp,
        long start,
        long end,
        long interval,
        List<Stat> stats,
        List<Filesystem> filesystems,
        List<Panel> panels,
        List<String> warnings) {

    public CvmMetricResponse {
        stats = List.copyOf(stats == null ? List.of() : stats);
        filesystems = List.copyOf(filesystems == null ? List.of() : filesystems);
        panels = List.copyOf(panels == null ? List.of() : panels);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public record Stat(
            String key,
            String title,
            String unit,
            Double value,
            String error) {
    }

    public record Filesystem(
            String filesystem,
            String ip,
            String mountpoint,
            Double totalBytes,
            Double availableBytes,
            Double usedPercent) {
    }

    public record Panel(
            String key,
            String title,
            String unit,
            List<Series> series,
            String error) {

        public Panel {
            series = List.copyOf(series == null ? List.of() : series);
        }
    }

    public record Series(
            String name,
            Map<String, String> labels,
            List<List<Object>> values,
            String unit,
            int yAxisIndex) {

        public Series {
            labels = Map.copyOf(labels == null ? Map.of() : labels);
            values = List.copyOf(values == null ? List.of() : values);
        }
    }
}

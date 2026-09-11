package com.databuff.apm.ingest.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DbConnectionPoolMetricRowMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsStableOtelPoolUsageIntoPoolTable() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "orders",
                "service_id", "orders",
                "metric", "db.client.connection.count",
                "attributes", Map.of(
                        "db.client.connection.state", "used",
                        "db.client.connection.pool.name", "orders-pool"),
                "value", 3));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();

        assertThat(mapped.table()).isEqualTo("metric_service_db_connection_pool");
        assertThat(new String(mapped.rowBytes()))
                .contains("\"activeSize\":3")
                .contains("\"connectionPoolName\":\"orders-pool\"");
    }

    @Test
    void mapsPoolGetMetricIntoPoolGetTable() throws Exception {
        byte[] raw = JSON.writeValueAsBytes(Map.of(
                "ts", 1_700_000_000_000L,
                "service", "orders",
                "service_id", "orders",
                "metric", "service.db.connection.pool.get.waitTime",
                "attributes", Map.of("pool.name", "orders-pool"),
                "value", 27.5));

        OtlpMetricRowMapper.MappedRow mapped = OtlpMetricRowMapper.map(raw).orElseThrow();

        assertThat(mapped.table()).isEqualTo("metric_service_db_connection_pool_get");
        assertThat(new String(mapped.rowBytes()))
                .contains("\"waitTime\":27.5")
                .contains("\"connectionPoolName\":\"orders-pool\"");
    }
}

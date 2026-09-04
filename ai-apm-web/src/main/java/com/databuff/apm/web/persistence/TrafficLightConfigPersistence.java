package com.databuff.apm.web.persistence;

import com.databuff.apm.web.cockpit.TrafficLightService;

import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.common.storage.ApmReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TrafficLightConfigPersistence {

    private static final Logger log = LoggerFactory.getLogger(TrafficLightConfigPersistence.class);

    private final TrafficLightService trafficLightService;
    private final ApmReadRepository readRepository;
    private final String configDatabase;
    private volatile boolean persistenceEnabled;

    public TrafficLightConfigPersistence(
            TrafficLightService trafficLightService,
            ApmReadRepository readRepository,
            ApmStorageProperties storageProperties) {
        this.trafficLightService = trafficLightService;
        this.readRepository = readRepository;
        this.configDatabase = storageProperties.configDatabase();
    }

    void reloadFromStore() {
        ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
        if (!repository.cockpitConfigSchemaReady()) {
            log.info("Cockpit config store not ready; traffic-light config stays in-memory only");
            return;
        }
        try {
            Map<String, String> values = repository.loadCockpitConfig();
            if (!values.isEmpty()) {
                trafficLightService.setConfig(parseConfig(values));
            }
            persistenceEnabled = true;
            log.info("Traffic-light config persistence enabled ({} keys from store)", values.size());
        } catch (Exception e) {
            log.warn("Failed to load traffic-light config from store: {}", e.getMessage());
        }
    }

    public void persist(Map<String, Object> config) {
        if (!persistenceEnabled) {
            return;
        }
        ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
        try {
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                // 长连接服务列表存逗号分隔串，避免 List.toString 的 "[]" 包裹混入存储值
                if (TrafficLightService.KEY_LONG_CONN_SERVICES.equals(entry.getKey())
                        && entry.getValue() instanceof List<?> list) {
                    repository.upsertCockpitConfig(entry.getKey(),
                            list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
                    continue;
                }
                repository.upsertCockpitConfig(entry.getKey(), String.valueOf(entry.getValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to persist traffic-light config: {}", e.getMessage());
        }
    }

    private static Map<String, Object> parseConfig(Map<String, String> values) {
        java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
        putDoubleConfig(config, values, "errorRateThreshold");
        putLongConfig(config, values, "minRequestCount");
        putLongConfig(config, values, "showServiceNumber");
        putDoubleConfig(config, values, "alarmRed");
        putDoubleConfig(config, values, "alarmYellow");
        putDoubleConfig(config, values, "exceptionRed");
        putDoubleConfig(config, values, "exceptionYellow");
        // 逗号分隔串原样透传，由 TrafficLightService.setConfig 归一化为 List
        if (values.containsKey(TrafficLightService.KEY_LONG_CONN_SERVICES)) {
            config.put(TrafficLightService.KEY_LONG_CONN_SERVICES,
                    values.get(TrafficLightService.KEY_LONG_CONN_SERVICES));
        }
        return config;
    }

    private static void putDoubleConfig(Map<String, Object> config, Map<String, String> values, String key) {
        if (values.containsKey(key)) {
            config.put(key, Double.parseDouble(values.get(key)));
        }
    }

    private static void putLongConfig(Map<String, Object> config, Map<String, String> values, String key) {
        if (values.containsKey(key)) {
            config.put(key, Long.parseLong(values.get(key)));
        }
    }
}

package com.databuff.apm.web.persistence;

import com.databuff.apm.web.cockpit.TrafficLightService;

import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.common.storage.ApmReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Component;

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
                if (entry.getValue() != null) {
                    repository.upsertCockpitConfig(entry.getKey(), String.valueOf(entry.getValue()));
                }
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

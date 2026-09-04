package com.databuff.apm.web.portal;

import com.databuff.apm.web.cockpit.TrafficLightService;
import com.databuff.apm.web.persistence.TrafficLightConfigPersistence;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/cockpit")
public class CockpitPortalController {

    private final CockpitPortalService cockpitPortalService;
    private final TrafficLightService trafficLightService;
    private final CockpitMetricPortalService cockpitMetricService;
    private final TrafficLightConfigPersistence trafficLightConfigPersistence;

    public CockpitPortalController(
            CockpitPortalService cockpitPortalService,
            TrafficLightService trafficLightService,
            CockpitMetricPortalService cockpitMetricService,
            TrafficLightConfigPersistence trafficLightConfigPersistence) {
        this.cockpitPortalService = cockpitPortalService;
        this.trafficLightService = trafficLightService;
        this.cockpitMetricService = cockpitMetricService;
        this.trafficLightConfigPersistence = trafficLightConfigPersistence;
    }

    @PostMapping("/trafficLight")
    public Map<String, Object> trafficLight(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.servicesHealth(body));
    }

    @PostMapping("/entityData")
    public Map<String, Object> entityData(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.entityData(body));
    }

    @PostMapping("/workbench/getEntityDataCount")
    public Map<String, Object> getEntityDataCount(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.entityData(body));
    }

    @PostMapping("/workbench/getAlarmCount")
    public Map<String, Object> getAlarmCount(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.getAlarmCount(body));
    }

    @PostMapping("/alarm/getEntityAlarmList")
    public Map<String, Object> getEntityAlarmList(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.getEntityAlarmList(body));
    }

    /**
     * 按数据块拆分 - 摘要块：统计计数与环比（不含列表），首屏快速返回
     */
    @PostMapping("/alarm/getEntityAlarmSummary")
    public Map<String, Object> getEntityAlarmSummary(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.getEntityAlarmSummary(body));
    }

    /**
     * 按数据块拆分 - 服务信息块：分页查询报警实体列表，默认 100/页
     * 支持 page/pageNum/pageSize/size/offset 参数
     */
    @PostMapping("/alarm/getEntityAlarmPage")
    public Map<String, Object> getEntityAlarmPage(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.getEntityAlarmPage(body));
    }

    @GetMapping("/getConfig")
    public Map<String, Object> getConfig(@RequestParam(required = false) String type) {
        return portalEnvelope(buildHealthConfigView(type));
    }

    @PostMapping("/setConfig")
    public Map<String, Object> setConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> updates = new LinkedHashMap<>(body);
        String resolvedType = stringValue(body.get("type"), "alarm");
        Object red = body.get("red");
        Object yellow = body.get("yellow");
        if (red != null) {
            if ("exception".equalsIgnoreCase(resolvedType)) {
                updates.put("exceptionRed", red);
            } else {
                updates.put("alarmRed", red);
            }
        }
        if (yellow != null) {
            if ("exception".equalsIgnoreCase(resolvedType)) {
                updates.put("exceptionYellow", yellow);
            } else {
                updates.put("alarmYellow", yellow);
            }
        }
        trafficLightService.setConfig(updates);
        // 持久化到配置库（此前 portal 路径缺失，配置重启即丢失）
        trafficLightConfigPersistence.persist(updates);
        return portalEnvelope(buildHealthConfigView(resolvedType));
    }

    private Map<String, Object> buildHealthConfigView(String type) {
        Map<String, Object> raw = trafficLightService.getConfig();
        Map<String, Object> config = new LinkedHashMap<>(raw);

        Map<String, Object> alarm = new LinkedHashMap<>();
        alarm.put("red", threshold(raw, "alarmRed", 2D));
        alarm.put("yellow", threshold(raw, "alarmYellow", 1D));

        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("red", threshold(raw, "exceptionRed", 10D));
        exception.put("yellow", threshold(raw, "exceptionYellow", 2D));

        config.put("alarm", alarm);
        config.put("exception", exception);
        config.putIfAbsent("showServiceNumber", 10);

        String resolvedType = stringValue(type, "alarm");
        config.put("type", resolvedType);
        if ("exception".equalsIgnoreCase(resolvedType)) {
            config.put("red", exception.get("red"));
            config.put("yellow", exception.get("yellow"));
        } else {
            config.put("red", alarm.get("red"));
            config.put("yellow", alarm.get("yellow"));
        }
        return config;
    }

    private static double threshold(Map<String, Object> config, String key, double fallback) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    @PostMapping("/countServiceAlarms")
    public Map<String, Object> countServiceAlarms(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.countServiceAlarms(body));
    }

    @PostMapping("/countServiceAlarmsTotal")
    public Map<String, Object> countServiceAlarmsTotal(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitPortalService.countServiceAlarmsTotal(body));
    }

    /**
     * KPI 卡汇总：一次返回多个指标的今日/昨日聚合值，支持服务筛选。
     * 入参 { start, end, interval, serviceNames, items:[{key, metric, aggs}] }。
     */
    @PostMapping("/kpiSummary")
    public Map<String, Object> kpiSummary(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitMetricService.kpiSummary(body));
    }

    /**
     * 多指标趋势：核心趋势 / 趋势分组卡共用，返回各指标今日/昨日时序（昨日已对齐今日时间轴）。
     * 入参同 /kpiSummary。
     */
    @PostMapping("/metricTrends")
    public Map<String, Object> metricTrends(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitMetricService.metricTrends(body));
    }

    /**
     * 服务排行：按服务分组聚合单指标，返回 Top N（含可选分桶序列）。
     * 入参 { start, end, interval, serviceNames, metric, aggs, limit, includeSeries }。
     */
    @PostMapping("/serviceRanking")
    public Map<String, Object> serviceRanking(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitMetricService.serviceRanking(body));
    }

    /**
     * 接口趋势下钻：单服务按维度（resource 等）分组，返回 Top N 接口今日/昨日序列与聚合值。
     * 入参 { start, end, interval, service, metric, aggs, groupBy, limit }。
     */
    @PostMapping("/serviceEndpoints")
    public Map<String, Object> serviceEndpoints(@RequestBody Map<String, Object> body) {
        return portalEnvelope(cockpitMetricService.serviceEndpoints(body));
    }

    private static Map<String, Object> portalEnvelope(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("message", "success");
        response.put("data", data);
        return response;
    }
}

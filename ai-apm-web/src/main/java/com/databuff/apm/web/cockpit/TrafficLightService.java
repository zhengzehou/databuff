package com.databuff.apm.web.cockpit;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.common.query.ApmQueryModels.TrafficLightPoint;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 红绿灯服务 + 长连接服务配置中心。
 *
 * 长连接服务（KEY_LONG_CONN_SERVICES）的统一语义：IM/监控轮询等天然长耗时/持续请求的服务，
 * 不参与以下统计与告警口径（列表在本类集中维护，各消费方通过
 * {@link #longConnServices()} / {@link #longConnServiceSet()} /
 * {@link #excludeLongConnServices(Collection, java.util.function.Function)} 取用）：
 * <ul>
 *   <li>异常服务趋势（unhealthyServiceTrend，SQL NOT IN 下推）</li>
 *   <li>慢调用统计（cockpit 慢调用查询，durationRange 过滤 + service NOT IN）</li>
 *   <li>耗时告警评估（*.avgDuration 规则分组剔除，RuleMetricEvaluationService）</li>
 *   <li>红绿灯明细与服务告警趋势计数（trafficLight）</li>
 *   <li>告警列表/趋势/计数（AlarmService，按告警 service 过滤）</li>
 *   <li>告警关联事件（EventPersistence，SQL NOT IN 下推）</li>
 * </ul>
 * 错误率/异常数/请求量等结果类指标不受影响。
 */
@Service
public class TrafficLightService {

    /** 长连接服务列表配置键：统计异常服务数与慢调用数时按此排除（IM/监控轮询等天然长耗时服务）。 */
    public static final String KEY_LONG_CONN_SERVICES = "longConnServices";

    private final ApmReadRepository readRepository;
    private final String metricDatabase;
    private final Map<String, Object> config = new ConcurrentHashMap<>();

    public TrafficLightService(ApmReadRepository readRepository, ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
        config.put("errorRateThreshold", 0.05);
        config.put("minRequestCount", 10);
        config.put("showServiceNumber", 10);
        config.put("alarmRed", 2);
        config.put("alarmYellow", 1);
        config.put("exceptionRed", 10);
        config.put("exceptionYellow", 2);
        config.put(KEY_LONG_CONN_SERVICES, List.of());
    }

    public List<TrafficLightPoint> trafficLight(long fromMillis, long toMillis) {
        try {
            String sql = MetricQueryBuilder.trafficLightSql(metricDatabase, fromMillis, toMillis);
            List<TrafficLightPoint> points = readRepository.queryTrafficLight(sql);
            // 长连接服务配置排除：红绿灯明细、服务告警趋势计数等所有调用方统一剔除
            return excludeLongConnServices(points, TrafficLightPoint::service);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 每分钟桶的不健康服务数（工作台健康趋势），色值规则在 SQL 内下推。
     * 替代 trafficLight + Java 判色计数，阈值语义与 trafficLightColor 一致
     * （非 green = total &lt; min 或 total &lt;= 0 或 error/cnt &gt; 阈值/2）。
     * 长连接服务（KEY_LONG_CONN_SERVICES）不参与统计。
     */
    public List<ApmQueryModels.BucketCountPoint> unhealthyServiceTrend(
            long fromMillis, long toMillis, double errorRateThreshold, double minRequestCount) {
        try {
            String sql = MetricQueryBuilder.unhealthyServiceTrendSql(
                    metricDatabase, fromMillis, toMillis, errorRateThreshold, minRequestCount, longConnServices());
            return readRepository.queryBucketCounts(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 长连接服务列表（只读），空列表表示不排除。 */
    public List<String> longConnServices() {
        return normalizeServiceList(config.get(KEY_LONG_CONN_SERVICES));
    }

    /** 长连接服务集合（去重），供各统计口径统一匹配。 */
    public Set<String> longConnServiceSet() {
        return Set.copyOf(longConnServices());
    }

    /** 判断指定服务是否为配置的长连接服务。 */
    public boolean isLongConnService(String service) {
        return service != null && !service.isBlank() && longConnServiceSet().contains(service);
    }

    /**
     * 统一过滤入口：按服务名剔除长连接服务。
     * serviceGetter 返回 null/空白的元素视为服务不明，保留（与既有各口径行为一致）。
     */
    public <T> List<T> excludeLongConnServices(Collection<T> items, java.util.function.Function<T, String> serviceGetter) {
        List<T> safeItems = items == null ? List.of() : List.copyOf(items);
        Set<String> excluded = longConnServiceSet();
        if (excluded.isEmpty() || safeItems.isEmpty()) {
            return safeItems;
        }
        return safeItems.stream()
                .filter(item -> {
                    String service = serviceGetter.apply(item);
                    return service == null || service.isBlank() || !excluded.contains(service);
                })
                .toList();
    }

    public Map<String, Object> getConfig() {
        return Map.copyOf(config);
    }

    public void setConfig(Map<String, Object> updates) {
        updates.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (KEY_LONG_CONN_SERVICES.equals(key)) {
                config.put(key, normalizeServiceList(value));
                return;
            }
            config.put(key, value);
        });
    }

    /** 归一化长连接服务列表：接受 List 或逗号分隔字符串（兼容中文逗号、括号包裹与空白）。 */
    private static List<String> normalizeServiceList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
        }
        if (value instanceof String text) {
            String cleaned = text.trim();
            if (cleaned.startsWith("[")) {
                cleaned = cleaned.substring(1);
            }
            if (cleaned.endsWith("]")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            return Arrays.stream(cleaned.split("[,，]"))
                    .map(item -> item.trim().replace("'", "").replace("\"", ""))
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of();
    }
}

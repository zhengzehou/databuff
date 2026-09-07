package com.databuff.apm.ingest.nginx;

import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.common.storage.ApmReadRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * nginx 接入噪音流量过滤器（动态配置）。
 *
 * <p>丢弃规则四类，内置规则始终生效（保持既有行为），配置规则做增量叠加：
 * <ul>
 *   <li>host 关键词：模糊匹配（忽略大小写的 contains），配置键 {@code nginxNoiseHosts}</li>
 *   <li>URI 关键词：模糊匹配（contains），配置键 {@code nginxNoiseUris}</li>
 *   <li>URI 前缀：startsWith（内置 /purge/）</li>
 *   <li>URI 后缀：endsWith（内置静态资源 .js/.css/.png/.jpg/.gif）</li>
 * </ul>
 *
 * <p>配置存于 cockpit 配置表（键 {@code nginxNoiseHosts} / {@code nginxNoiseUris}，逗号分隔，
 * 由 web 端配置抽屉或 POST /cockpit/setConfig 维护），本类定时刷新缓存，免重启生效；
 * 读取失败时沿用上一次配置。热路径仅读 volatile 字段。
 */
public class NginxNoiseFilter {

    private static final Logger log = LoggerFactory.getLogger(NginxNoiseFilter.class);

    public static final String KEY_HOSTS = "nginxNoiseHosts";
    public static final String KEY_URIS = "nginxNoiseUris";

    /** 内置 host 精确黑名单（等价 ES terms host.keyword，忽略大小写）：基础设施/内部系统/打点域名。 */
    private static final Set<String> BUILTIN_HOST_EXACT = Set.of(
            "i0.ule.com", "i1.ule.com", "track.ule.com", "www.ule.com", "clock.ule.com",
            "mirrors.uletm.com", "oa.ule.com", "opt.uletm.com", "jira.uletm.com", "wiki.uletm.com",
            "cms.uletm.com", "cms.ulecn.tom.com", "tom-mail-internal.prd.uledns.com",
            "tom-intl-mail.http.prd.uledns.com", "prometheus-pushgateway.http.prd.uledns.com");
    /** 内置 proxy_host 精确黑名单（等价 ES terms proxy_host.keyword）：注册中心/监控采集等内部组件。 */
    private static final Set<String> BUILTIN_PROXY_HOST_EXACT = Set.of(
            "pub-nacos-group", "cloudsearch6-control-monitorservice-group");
    /** 内置 URI 关键词（contains）：健康检查/打点/内部接口。 */
    private static final List<String> BUILTIN_URI_KEYWORDS = List.of(
            "/checkhealth", "clock.ule.com/now", "sensorsdata.ule.com", "ac.ule.com",
            "wholesale-api.ule.com/app/sysTime");
    /** 内置 URI 前缀（startsWith）：purge 缓存刷新、默认页、track2 埋点。 */
    private static final List<String> BUILTIN_URI_PREFIXES = List.of("/purge/", "/default_", "/track2");
    /** 内置 URI 后缀（endsWith）：静态资源。 */
    private static final List<String> BUILTIN_URI_SUFFIXES =
            List.of(".js", ".css", ".png", ".jpg", ".gif");

    private final ApmReadRepository readRepository;
    private final String configDatabase;

    /** 动态部分：配置的 host/URI 关键词（小写化）。volatile 保证热路径可见性。 */
    private volatile Set<String> dynamicHostKeywords = Set.of();
    private volatile Set<String> dynamicUriKeywords = Set.of();

    public NginxNoiseFilter(ApmReadRepository readRepository, String configDatabase) {
        this.readRepository = readRepository;
        this.configDatabase = configDatabase;
    }

    /** 启动即加载一次；之后定时刷新（失败沿用旧配置）。 */
    @PostConstruct
    void refreshInitial() {
        refresh();
    }
    /** per 1h refresh*/
    @Scheduled(fixedDelayString = "${ingest.nginx-kafka.noise-filter-refresh-ms:3600000}", initialDelay = 0)
    public void refresh() {
        try {
            ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
            if (!repository.cockpitConfigSchemaReady()) {
                return;
            }
            Map<String, String> values = repository.loadCockpitConfig();
            this.dynamicHostKeywords = Set.copyOf(splitKeywords(values.get(KEY_HOSTS)));
            this.dynamicUriKeywords = Set.copyOf(splitKeywords(values.get(KEY_URIS)));
        } catch (Exception e) {
            log.warn("Failed to refresh nginx noise filter config: {}", e.getMessage());
        }
    }

    /**
     * 命中任一规则返回 true（整条日志的 trace/metric/log 三份 OTLP 均不产生）。
     * host/proxyHost 精确名单忽略大小写；配置的 host/URI 关键词模糊匹配（contains）；
     * URI 先取 fullUri、为空回退 uri。
     */
    public boolean shouldDrop(String host, String proxyHost, String uri, String fullUri) {
        if (host != null && !host.isBlank()) {
            String normalized = host.trim().toLowerCase(Locale.ROOT);
            if (BUILTIN_HOST_EXACT.contains(normalized)) {
                return true;
            }
            for (String keyword : dynamicHostKeywords) {
                if (normalized.contains(keyword)) {
                    return true;
                }
            }
        }
        if (proxyHost != null && !proxyHost.isBlank()
                && BUILTIN_PROXY_HOST_EXACT.contains(proxyHost.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        String target = uri != null && !uri.isBlank() ? uri : fullUri;
        if (target == null) {
            return false;
        }
        for (String prefix : BUILTIN_URI_PREFIXES) {
            if (target.contains(prefix)) {
                return true;
            }
        }
        for (String keyword : BUILTIN_URI_KEYWORDS) {
            if (target.contains(keyword)) {
                return true;
            }
        }
        for (String keyword : dynamicUriKeywords) {
            if (target.contains(keyword)) {
                return true;
            }
        }
        for (String suffix : BUILTIN_URI_SUFFIXES) {
            if (target.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 逗号/中文逗号分隔 → 去空白去引号的小写关键词集合；入参 null/空白返回空集。 */
    private static Set<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("[")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return Arrays.stream(cleaned.split("[,，]"))
                .map(item -> item.trim().replace("'", "").replace("\"", ""))
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

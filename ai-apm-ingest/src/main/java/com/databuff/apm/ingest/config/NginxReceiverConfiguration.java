package com.databuff.apm.ingest.config;

import com.databuff.apm.ingest.nginx.IpServiceResolver;
import com.databuff.apm.ingest.nginx.NginxIngestService;
import com.databuff.apm.ingest.nginx.NginxKafkaReceiver;
import com.databuff.apm.ingest.nginx.NginxNoiseFilter;
import com.databuff.apm.ingest.nginx.NginxOtlpConverter;
import com.databuff.apm.ingest.otel.OtlpIngestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NginxReceiverConfiguration {

    @Bean
    @Conditional(NginxKafkaEnabledCondition.class)
    IpServiceResolver ipServiceResolver(
            @Value("${ingest.nginx-kafka.ip-service-url:}") String ipServiceUrl,
            @Value("${ingest.nginx-kafka.ip-service-name-field:serviceName}") String nameField) {
        return new IpServiceResolver(ipServiceUrl, nameField);
    }

    /**
     * 噪音流量过滤器：内置规则 + 动态配置（cockpit 配置表键 nginxNoiseHosts / nginxNoiseUris，
     * 定时刷新免重启）。配置在 web 端配置抽屉或 POST /cockpit/setConfig 维护。
     */
    @Bean
    @Conditional(NginxKafkaEnabledCondition.class)
    NginxNoiseFilter nginxNoiseFilter(
            com.databuff.apm.common.storage.ApmReadRepository ingestApmReadRepository,
            @Value("${ingest.doris.metric-database") String configDatabase) {
        return new NginxNoiseFilter(ingestApmReadRepository, configDatabase);
    }

    @Bean
    @Conditional(NginxKafkaEnabledCondition.class)
    NginxOtlpConverter nginxOtlpConverter(
            @Value("${ingest.nginx-kafka.request-time-unit:milliseconds}") String requestTimeUnit,
            IpServiceResolver ipServiceResolver,
            NginxNoiseFilter noiseFilter) {
        return new NginxOtlpConverter(requestTimeUnit, ipServiceResolver, noiseFilter);
    }

    @Bean
    @Conditional(NginxKafkaEnabledCondition.class)
    NginxIngestService nginxIngestService(
            NginxOtlpConverter converter,
            OtlpIngestService otlpIngestService) {
        return new NginxIngestService(converter, otlpIngestService);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @Conditional(NginxKafkaEnabledCondition.class)
    NginxKafkaReceiver nginxKafkaReceiver(
            @Value("${ingest.nginx-kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${ingest.nginx-kafka.topic:nginx-access-log}") String topic,
            @Value("${ingest.nginx-kafka.group-id:databuff-nginx-ingest}") String groupId,
            @Value("${ingest.nginx-kafka.max-poll-records:500}") int maxPollRecords,
            @Value("${ingest.nginx-kafka.poll-timeout-ms:100}") int pollTimeoutMs,
            @Value("${ingest.nginx-kafka.auto-offset-reset:earliest}") String autoOffsetReset,
            NginxIngestService ingestService) {
        return new NginxKafkaReceiver(bootstrapServers, topic, groupId, maxPollRecords, pollTimeoutMs, autoOffsetReset, ingestService);
    }

    /** Condition: enable when ingest.nginx-kafka.enabled=true */
    public static class NginxKafkaEnabledCondition implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String val = context.getEnvironment().getProperty("ingest.nginx-kafka.enabled");
            return "true".equalsIgnoreCase(val);
        }
    }
}
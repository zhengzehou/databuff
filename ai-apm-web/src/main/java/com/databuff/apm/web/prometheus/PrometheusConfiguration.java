package com.databuff.apm.web.prometheus;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PrometheusProperties.class)
public class PrometheusConfiguration {
}

package com.databuff.apm.web.prometheus;

public record ServiceHostMetricQueryRequest(String serviceName, Long start, Long end, Long interval) {}

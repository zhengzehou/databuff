package com.databuff.apm.web.prometheus;

/** Portal request for the constrained CVM dashboard query. Times and interval are seconds. */
public record CvmMetricQueryRequest(
        String serviceId,
        String instanceIp,
        Long start,
        Long end,
        Long interval) {
}

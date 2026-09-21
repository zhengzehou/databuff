package com.databuff.apm.web.prometheus;

import com.databuff.apm.web.config.common.CommonResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/prometheus")
public class PrometheusController {

    private static final Logger log = LoggerFactory.getLogger(PrometheusController.class);

    private final PrometheusCvmService cvmService;
    private final PrometheusServiceHostService serviceHostService;

    public PrometheusController(
            PrometheusCvmService cvmService,
            PrometheusServiceHostService serviceHostService) {
        this.cvmService = cvmService;
        this.serviceHostService = serviceHostService;
    }

    @PostMapping("/cvm/metrics")
    public Map<String, Object> queryCvmMetrics(
            @RequestBody(required = false) CvmMetricQueryRequest request) {
        try {
            return CommonResponse.ok(cvmService.query(request));
        } catch (PrometheusException e) {
            log.warn("Prometheus CVM request failed: status={}, message={}",
                    e.status(), e.getMessage());
            return CommonResponse.fail(e.status(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected Prometheus CVM request failure", e);
            return CommonResponse.fail(500, "Prometheus CVM query failed");
        }
    }

    @PostMapping("/service-host/metrics")
    public Map<String, Object> queryServiceHostMetrics(
            @RequestBody(required = false) ServiceHostMetricQueryRequest request) {
        try {
            return CommonResponse.ok(serviceHostService.query(request));
        } catch (PrometheusException e) {
            log.warn("Prometheus service host request failed: status={}, message={}",
                    e.status(), e.getMessage());
            return CommonResponse.fail(e.status(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected Prometheus service host request failure", e);
            return CommonResponse.fail(500, "Prometheus service host query failed");
        }
    }
    @GetMapping("/status")
    public Map<String, Object> status() {
        return CommonResponse.ok(cvmService.status());
    }
}

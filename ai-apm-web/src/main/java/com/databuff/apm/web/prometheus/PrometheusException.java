package com.databuff.apm.web.prometheus;

/** Safe, portal-facing error raised by the Prometheus adapter. */
public class PrometheusException extends RuntimeException {

    private final int status;

    public PrometheusException(int status, String message) {
        super(message);
        this.status = status;
    }

    public PrometheusException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

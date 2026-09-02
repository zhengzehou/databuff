package com.databuff.apm.ingest.nginx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NginxAccessLog(
        @JsonProperty("status") Integer status,
        @JsonProperty("host") String host,
        // Kept as String because nginx emits "-" when the upstream never answered (e.g. status 499).
        @JsonProperty("upstream_response_time") String upstreamResponseTime,
        @JsonProperty("x_forwarded_port") String xForwardedPort,
        @JsonProperty("scheme") String scheme,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("server_protocol") String serverProtocol,
        @JsonProperty("request_method") String requestMethod,
        @JsonProperty("http_x_forwarded_for") String httpXForwardedFor,
        @JsonProperty("time_local") String timeLocal,
        @JsonProperty("body_bytes_sent") String bodyBytesSent,
        @JsonProperty("index_date") String indexDate,
        @JsonProperty("request_length") String requestLength,
        @JsonProperty("request_uri") String requestUri,
        @JsonProperty("@timestamp") String timestamp,
        @JsonProperty("http_user_agent") String httpUserAgent,
        @JsonProperty("server_addr") String serverAddr,
        @JsonProperty("remote_port") String remotePort,
        @JsonProperty("proxy_host") String proxyHost,
        @JsonProperty("uri") String uri,
        @JsonProperty("upstream_addr") String upstreamAddr,
        @JsonProperty("request_time") String requestTime,
        @JsonProperty("full_uri") String fullUri,
        @JsonProperty("remote_addr") String remoteAddr,
        @JsonProperty("type") String type
) {
}
package com.databuff.apm.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Employee passport login client ({@code im-admin-service}).
 * <p>
 * POSTs {@code service}/{@code accountName}/{@code accountPassword} as
 * {@code application/x-www-form-urlencoded} to the configured passport login URL. Success is
 * signaled by an empty/null {@code errorCode} in the JSON response; on success the
 * {@code user} object carries {@code realName}/{@code email}/{@code mobile}.
 */
@Component
public class PassportLoginClient {

    private static final Logger log = LoggerFactory.getLogger(PassportLoginClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String loginUrl;
    private final String service;
    private final Duration readTimeout;

    public PassportLoginClient(
            @Value("${apm.passport.login-url:}") String loginUrl,
            @Value("${apm.passport.service:databuff}") String service,
            @Value("${apm.passport.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${apm.passport.read-timeout-ms:10000}") long readTimeoutMs) {
        this.loginUrl = loginUrl == null ? "" : loginUrl.trim();
        this.service = service == null ? "" : service.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .build();
        this.readTimeout = Duration.ofMillis(Math.max(1, readTimeoutMs));
    }

    /**
     * Validate an employee account against the passport service.
     *
     * @return the authenticated user when {@code errorCode} is empty, {@link Optional#empty()}
     *         on rejection, non-2xx HTTP, network failure or malformed response
     */
    public Optional<PassportUser> login(String username, String password) {
        if (loginUrl.isEmpty()) {
            log.warn("Passport login URL not configured (apm.passport.login-url); "
                    + "cannot validate non-admin account {}", username);
            return Optional.empty();
        }
        try {
            String body = postForm(username, password);
            return parseResponse(body, username);
        } catch (Exception e) {
            log.warn("Passport login request failed for {}: {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    private String postForm(String username, String password) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("service", service);
        params.put("accountName", username);
        params.put("accountPassword", password);
        String form = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl))
                .timeout(readTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            log.warn("Passport login HTTP {} for {}: {}", response.statusCode(), loginUrl, response.body());
            throw new IOException("passport login returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Visible for tests: decide success and extract the user from a raw response body. */
    static Optional<PassportUser> parseResponse(String body, String username) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode errorCode = root.get("errorCode");
            if (errorCode != null && !errorCode.isNull() && !errorCode.asText().isBlank()) {
                log.debug("Passport login rejected for {}: errorCode={}", username, errorCode.asText());
                return Optional.empty();
            }
            JsonNode user = root.get("user");
            return Optional.of(new PassportUser(
                    username,
                    text(user, "realName"),
                    text(user, "email"),
                    text(user, "mobile")));
        } catch (Exception e) {
            log.warn("Passport login response unparseable for {}: {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Authenticated passport account with the profile fields exposed by the passport service. */
    public record PassportUser(String account, String realName, String email, String mobile) {
    }
}

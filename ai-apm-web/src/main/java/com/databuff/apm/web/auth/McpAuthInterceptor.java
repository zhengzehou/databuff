package com.databuff.apm.web.auth;

import com.databuff.apm.web.config.McpAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/** Authentication boundary for DSH and standard MCP calls. */
public class McpAuthInterceptor implements HandlerInterceptor {

    public static final String TEST_MODE_ATTRIBUTE = "mcpAuthTestMode";

    private final JwtTokenService jwtTokenService;
    private final McpAuthProperties properties;

    public McpAuthInterceptor(JwtTokenService jwtTokenService, McpAuthProperties properties) {
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!properties.required()) {
            request.setAttribute(RequestAuthSupport.USERNAME_ATTRIBUTE, properties.testUsername());
            request.setAttribute(TEST_MODE_ATTRIBUTE, true);
            return true;
        }
        String credential = request.getHeader(HttpHeaders.AUTHORIZATION);
        if ((credential == null || credential.isBlank()) && request.getCookies() != null) {
            credential = Arrays.stream(request.getCookies())
                    .filter(cookie -> "DATABUFF-Admin-Token".equals(cookie.getName()))
                    .map(jakarta.servlet.http.Cookie::getValue)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        var username = jwtTokenService.parseUsername(credential);
        if (username.isPresent()) {
            request.setAttribute(RequestAuthSupport.USERNAME_ATTRIBUTE, username.get());
            request.setAttribute(TEST_MODE_ATTRIBUTE, false);
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"DATABUFF_LOGIN_REQUIRED\",\"message\":\"DataBuff login is required\",\"loginUrl\":\"/databuff/login?redirect=/ai/\"}");
        return false;
    }
}

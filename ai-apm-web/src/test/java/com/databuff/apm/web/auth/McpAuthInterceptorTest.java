package com.databuff.apm.web.auth;

import com.databuff.apm.web.config.JwtProperties;
import com.databuff.apm.web.config.McpAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

class McpAuthInterceptorTest {

    private final JwtTokenService tokenService = new JwtTokenService(new JwtProperties("test-secret", 3600));

    @Test
    void disabledAuthUsesExplicitTestIdentity() throws Exception {
        McpAuthInterceptor interceptor = new McpAuthInterceptor(
                tokenService, new McpAuthProperties(false, "integration-test"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(RequestAuthSupport.USERNAME_ATTRIBUTE)).isEqualTo("integration-test");
        assertThat(request.getAttribute(McpAuthInterceptor.TEST_MODE_ATTRIBUTE)).isEqualTo(true);
    }

    @Test
    void requiredAuthRejectsMissingToken() throws Exception {
        McpAuthInterceptor interceptor = new McpAuthInterceptor(
                tokenService, new McpAuthProperties(true, "test"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("DATABUFF_LOGIN_REQUIRED");
    }

    @Test
    void requiredAuthAcceptsValidBearerToken() throws Exception {
        McpAuthInterceptor interceptor = new McpAuthInterceptor(
                tokenService, new McpAuthProperties(true, "test"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + tokenService.issueToken("alice"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(RequestAuthSupport.USERNAME_ATTRIBUTE)).isEqualTo("alice");
        assertThat(request.getAttribute(McpAuthInterceptor.TEST_MODE_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void requiredAuthAcceptsPortalTokenCookieForBrowserProbe() throws Exception {
        McpAuthInterceptor interceptor = new McpAuthInterceptor(
                tokenService, new McpAuthProperties(true, "test"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/webapi/api/v1/dsh/auth/status");
        request.setCookies(new Cookie("DATABUFF-Admin-Token", tokenService.issueToken("browser-user")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(RequestAuthSupport.USERNAME_ATTRIBUTE)).isEqualTo("browser-user");
    }
}

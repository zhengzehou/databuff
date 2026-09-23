package com.databuff.apm.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authentication policy shared by the standard MCP endpoint and the DSH login probe. */
@ConfigurationProperties(prefix = "apm.mcp.auth")
public record McpAuthProperties(boolean required, String testUsername) {

    public McpAuthProperties {
        if (testUsername == null || testUsername.isBlank()) {
            testUsername = "dsh-test-user";
        } else {
            testUsername = testUsername.trim();
        }
    }
}

package com.databuff.apm.web.config;

import com.databuff.apm.web.auth.AuthInterceptor;
import com.databuff.apm.web.auth.JwtTokenService;
import com.databuff.apm.web.auth.McpAuthInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(McpAuthProperties.class)
public class AuthConfiguration {

    @Bean
    JwtTokenService jwtTokenService(JwtProperties jwtProperties) {
        return new JwtTokenService(jwtProperties);
    }

    @Bean
    AuthInterceptor authInterceptor(JwtTokenService jwtTokenService) {
        return new AuthInterceptor(jwtTokenService);
    }

    @Bean
    McpAuthInterceptor mcpAuthInterceptor(
            JwtTokenService jwtTokenService,
            McpAuthProperties properties) {
        return new McpAuthInterceptor(jwtTokenService, properties);
    }
}

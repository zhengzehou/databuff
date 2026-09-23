package com.databuff.apm.web.ai.dsh;

import com.databuff.apm.web.auth.McpAuthInterceptor;
import com.databuff.apm.web.auth.RequestAuthSupport;
import com.databuff.apm.web.config.McpAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Browser login probe consumed by the DataBuff DSH plugin. */
@RestController
@RequestMapping("/api/v1/dsh/auth")
public class DshAuthController {

    private final McpAuthProperties properties;

    public DshAuthController(McpAuthProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authRequired", properties.required());
        result.put("authenticated", properties.required());
        result.put("testMode", Boolean.TRUE.equals(request.getAttribute(McpAuthInterceptor.TEST_MODE_ATTRIBUTE)));
        Object username = request.getAttribute(RequestAuthSupport.USERNAME_ATTRIBUTE);
        result.put("username", username == null ? "" : username.toString());
        return result;
    }
}

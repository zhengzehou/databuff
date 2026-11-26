package com.databuff.apm.web.ai.dsh;

import com.databuff.apm.web.auth.JwtTokenService;
import com.databuff.apm.web.auth.McpAuthInterceptor;
import com.databuff.apm.web.auth.RequestAuthSupport;
import com.databuff.apm.web.config.McpAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Browser login probe consumed by the DataBuff DSH plugin. */
@RestController
@RequestMapping("/api/v1/dsh/auth")
//@CrossOrigin(
//        origins =  {"http://localhost:5173", "http://127.0.0.1:3080", "http://localhost:3080"},  // 前端开发服务器地址
//        allowedHeaders = "*",
//        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS},
//        allowCredentials = "true", // 是否允许携带Cookie等凭证
//        maxAge = 3600 // 预检请求结果的缓存时间（秒）
//)
public class DshAuthController {

    private static Logger logger = LoggerFactory.getLogger("dsh-auth");
    private final McpAuthProperties properties;
    private final JwtTokenService jwtTokenService;

    public DshAuthController(JwtTokenService jwtTokenService, McpAuthProperties properties) {
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authRequired", properties.required());
        result.put("authenticated", properties.required());
        result.put("testMode", Boolean.TRUE.equals(request.getAttribute(McpAuthInterceptor.TEST_MODE_ATTRIBUTE)));
        Object username ;
        String credential = request.getHeader(HttpHeaders.AUTHORIZATION);
        if ((credential == null || credential.isBlank()) && request.getCookies() != null) {
            credential = Arrays.stream(request.getCookies())
                    .filter(cookie -> "DATABUFF-Admin-Token".equals(cookie.getName()))
                    .map(jakarta.servlet.http.Cookie::getValue)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        java.util.Optional<String> username2 = jwtTokenService.parseUsername(credential);
        if (username2.isPresent()) {
            username = username2.get();
        }else {
            username = request.getAttribute(RequestAuthSupport.USERNAME_ATTRIBUTE);
        }
        logger.info("{}访问了dsh chatbi",username);
        result.put("username", username == null ? "" : username.toString());
        return result;
    }
}

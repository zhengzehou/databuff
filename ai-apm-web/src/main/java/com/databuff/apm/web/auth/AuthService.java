package com.databuff.apm.web.auth;

import com.databuff.apm.web.admin.account.PortalUserManagementService;
import com.databuff.apm.web.admin.settings.SessionIdleSettingsService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    private static final String DEFAULT_CID = "NzhEMjlBOTk3MzkxRjk5MjQyMzMzQzI4";

    private final JwtTokenService jwtTokenService;
    private final PortalUserManagementService portalUserManagementService;
    private final SessionIdleSettingsService sessionIdleSettingsService;
    private final PassportLoginClient passportLoginClient;

    public AuthService(
            JwtTokenService jwtTokenService,
            PortalUserManagementService portalUserManagementService,
            SessionIdleSettingsService sessionIdleSettingsService,
            PassportLoginClient passportLoginClient) {
        this.jwtTokenService = jwtTokenService;
        this.portalUserManagementService = portalUserManagementService;
        this.sessionIdleSettingsService = sessionIdleSettingsService;
        this.passportLoginClient = passportLoginClient;
    }

    public LoginResult login(String username, String password) {
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            return LoginResult.failed("用户名或密码不能为空");
        }
        if (!portalUserManagementService.authenticate(username, password)) {
            return LoginResult.failed("用户名或密码错误");
        }
        return LoginResult.success(issueSessionToken(username.trim()), username.trim());
    }

    public Optional<PortalLoginPayload> portalLogin(String account, String password) {
        if (account == null || password == null || account.isBlank() || password.isBlank()) {
            return Optional.empty();
        }
        if (!portalUserManagementService.authenticate(account, password)) {
            return Optional.empty();
        }
        String normalizedAccount = account.trim();
        String token = issueSessionToken(normalizedAccount);
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("id", portalUserManagementService.findByAccount(normalizedAccount)
                .map(user -> user.id())
                .orElse(1L));
        ds.put("orgId", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("cid", DEFAULT_CID);
        body.put("ds", ds);
        return Optional.of(new PortalLoginPayload(body));
    }

    /**
     * Frontend login entry (param names unchanged): the built-in admin account keeps the local
     * flow, every other account is validated against the employee passport service. Both paths
     * issue the same {@link PortalLoginPayload} shape.
     */
    public Optional<PortalLoginPayload> portalLoginOrPassport(String account, String password) {
        if (account == null || password == null || account.isBlank() || password.isBlank()) {
            return Optional.empty();
        }
        String normalizedAccount = account.trim();
        if (portalUserManagementService.findByAccount(normalizedAccount).isPresent()) {
            return portalLogin(normalizedAccount, password);
        }
        Optional<PassportLoginClient.PassportUser> user =
                passportLoginClient.login(normalizedAccount, password);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("id", 1);
        ds.put("orgId", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", issueSessionToken(normalizedAccount));
        body.put("cid", DEFAULT_CID);
        body.put("ds", ds);
        return Optional.of(new PortalLoginPayload(body));
    }

    public Optional<String> resolveAccount(String authorizationHeader) {
        return jwtTokenService.parseUsername(authorizationHeader);
    }

    private String issueSessionToken(String username) {
        return jwtTokenService.issueToken(username, sessionIdleSettingsService.idleSeconds());
    }

    public record PortalLoginPayload(Map<String, Object> body) {
    }

    public record LoginResult(boolean ok, String token, String username, String message) {
        static LoginResult success(String token, String username) {
            return new LoginResult(true, token, username, null);
        }

        static LoginResult failed(String message) {
            return new LoginResult(false, null, null, message);
        }
    }
}

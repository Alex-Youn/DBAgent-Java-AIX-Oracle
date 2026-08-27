package com.dbagent.auth;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        Optional<AuthService.AuthSession> session = authService.login(req.username(), req.password());
        if (session.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("token", session.get().token());
            body.put("username", session.get().username());
            body.put("role", session.get().role());
            body.put("hidden_menus", session.get().hiddenMenus());
            body.put("hidden_dbs", session.get().hiddenDbs());
            // Effective permission (admin implicitly included), not the raw stored column - the
            // frontend just needs "can this account use Fleet Overview", not to re-derive it from role.
            body.put("fleet_overview", "admin".equals(session.get().role()) || session.get().fleetOverview());
            // Raw preference, not merged with role - unlike fleet_overview above this isn't a
            // permission (admin doesn't implicitly get "true" here), just "should login jump straight
            // to Fleet Overview" for whichever account this is.
            body.put("fleet_overview_auto_redirect", session.get().fleetOverviewAutoRedirect());
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Maps.of("success", false, "message", "아이디 또는 비밀번호가 올바르지 않습니다."));
    }

    @PostMapping("/api/check-auth")
    public ResponseEntity<Map<String, Object>> checkAuth(@RequestBody TokenRequest req) {
        if (req.token() == null || Strings.isBlank(req.token())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Maps.of("authenticated", false));
        }
        Optional<AuthService.AuthSession> session = authService.sessionForToken(req.token());
        if (session.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authenticated", true);
            body.put("username", session.get().username());
            body.put("role", session.get().role());
            body.put("hidden_menus", session.get().hiddenMenus());
            body.put("hidden_dbs", session.get().hiddenDbs());
            body.put("fleet_overview", "admin".equals(session.get().role()) || session.get().fleetOverview());
            body.put("fleet_overview_auto_redirect", session.get().fleetOverviewAutoRedirect());
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Maps.of("authenticated", false));
    }

    // Self-service - any account (including admin, who can't use the username-targeted /api/users
    // endpoints) flips their own "jump to Fleet Overview on login" preference here.
    @PostMapping("/api/me/fleet_overview_auto_redirect")
    public ResponseEntity<Map<String, Object>> setFleetOverviewAutoRedirect(@RequestBody SetFleetOverviewAutoRedirectRequest req) {
        Map<String, Object> result = authService.setFleetOverviewAutoRedirect(req.token(), req.autoRedirect());
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    @PostMapping("/api/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody ChangePasswordRequest req) {
        boolean ok = authService.changePassword(req.token(), req.currentPassword(), req.newPassword());
        if (ok) {
            return ResponseEntity.ok(Maps.of("success", true));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Maps.of("success", false, "message", "현재 비밀번호가 올바르지 않습니다."));
    }
}

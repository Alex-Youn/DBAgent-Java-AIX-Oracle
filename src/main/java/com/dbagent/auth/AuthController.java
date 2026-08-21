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
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Maps.of("authenticated", false));
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

package com.dbagent.auth;

import com.dbagent.util.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody CreateUserRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 계정을 생성할 수 있습니다."));
        }
        return ResponseEntity.ok(authService.createUser(req.username(), req.password(), req.role(), req.hiddenMenus(), req.hiddenDbs()));
    }

    @GetMapping("/api/users")
    public ResponseEntity<Map<String, Object>> listUsers(@RequestParam String token) {
        if (!authService.isAdmin(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 조회할 수 있습니다."));
        }
        return ResponseEntity.ok(Maps.of("users", authService.listUsers()));
    }

    @PutMapping("/api/users/{username}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable String username, @RequestBody UpdateUserRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 수정할 수 있습니다."));
        }
        return ResponseEntity.ok(authService.updateUser(username, req.role(), req.hiddenMenus(), req.hiddenDbs()));
    }

    @DeleteMapping("/api/users/{username}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String username, @RequestParam String token) {
        if (!authService.isAdmin(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Maps.of("success", false, "message", "관리자만 삭제할 수 있습니다."));
        }
        return ResponseEntity.ok(authService.deleteUser(username));
    }
}

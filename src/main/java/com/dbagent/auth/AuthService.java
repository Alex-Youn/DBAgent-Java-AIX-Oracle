package com.dbagent.auth;

import com.dbagent.util.Lists;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    public static final class AuthSession {
        private final String username;
        private final String role;
        private final List<String> hiddenMenus;
        private final List<String> hiddenDbs;
        private final String token;

        public AuthSession(String username, String role, List<String> hiddenMenus, List<String> hiddenDbs, String token) {
            this.username = username;
            this.role = role;
            this.hiddenMenus = hiddenMenus;
            this.hiddenDbs = hiddenDbs;
            this.token = token;
        }

        public String username() {
            return username;
        }

        public String role() {
            return role;
        }

        public List<String> hiddenMenus() {
            return hiddenMenus;
        }

        public List<String> hiddenDbs() {
            return hiddenDbs;
        }

        public String token() {
            return token;
        }
    }

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS users (username VARCHAR PRIMARY KEY, password VARCHAR, token VARCHAR)");

        // H2 supports ADD COLUMN IF NOT EXISTS natively, unlike SQLite - no need for the old
        // PRAGMA table_info() existence check this used to do.
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR NOT NULL DEFAULT 'user'");
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS hidden_menus VARCHAR");
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS hidden_dbs VARCHAR");

        // Reset every session on server restart, same as the Python init_db().
        jdbc.update("UPDATE users SET token = NULL");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, "admin");
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO users (username, password, role) VALUES (?, ?, ?)",
                    "admin", passwordEncoder.encode("admin"), "admin");
        } else {
            jdbc.update("UPDATE users SET role = 'admin' WHERE username = 'admin' AND (role IS NULL OR role != 'admin')");
        }
    }

    public Optional<AuthSession> login(String username, String password) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT password, role, hidden_menus, hidden_dbs FROM users WHERE username = ?", username);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
        String hash = (String) row.get("password");
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            return Optional.empty();
        }
        String token = Strings.toHex(randomBytes(32));
        jdbc.update("UPDATE users SET token = ? WHERE username = ?", token, username);
        return Optional.of(new AuthSession(username, (String) row.get("role"),
                parseCsv((String) row.get("hidden_menus")), parseCsv((String) row.get("hidden_dbs")), token));
    }

    public Optional<AuthSession> sessionForToken(String token) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT username, role, hidden_menus, hidden_dbs FROM users WHERE token = ?", token);
            return Optional.of(new AuthSession((String) row.get("username"), (String) row.get("role"),
                    parseCsv((String) row.get("hidden_menus")), parseCsv((String) row.get("hidden_dbs")), token));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean isAdmin(String token) {
        return sessionForToken(token).map(s -> "admin".equals(s.role())).orElse(false);
    }

    /** A blank dbId (no specific instance selected yet) is always allowed; admins bypass the restriction. */
    public boolean canAccessDb(String token, String dbId) {
        if (dbId == null || Strings.isBlank(dbId)) {
            return true;
        }
        return sessionForToken(token)
                .map(s -> "admin".equals(s.role()) || !s.hiddenDbs().contains(dbId))
                .orElse(false);
    }

    public Map<String, Object> createUser(String username, String password, String role,
                                           List<String> hiddenMenus, List<String> hiddenDbs) {
        if (username == null || Strings.isBlank(username) || password == null || Strings.isBlank(password)) {
            return Maps.of("success", false, "message", "아이디와 비밀번호를 입력하세요.");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            return Maps.of("success", false, "message", "이미 존재하는 아이디입니다.");
        }
        String resolvedRole = "admin".equals(role) ? "admin" : "user";
        jdbc.update("INSERT INTO users (username, password, role, hidden_menus, hidden_dbs) VALUES (?, ?, ?, ?, ?)",
                username, passwordEncoder.encode(password), resolvedRole, joinCsv(hiddenMenus), joinCsv(hiddenDbs));
        return Maps.of("success", true);
    }

    public Map<String, Object> updateUser(String username, String role, List<String> hiddenMenus, List<String> hiddenDbs) {
        if ("admin".equals(username)) {
            return Maps.of("success", false, "message", "admin 계정은 수정할 수 없습니다.");
        }
        String resolvedRole = "admin".equals(role) ? "admin" : "user";
        int updated = jdbc.update("UPDATE users SET role = ?, hidden_menus = ?, hidden_dbs = ? WHERE username = ?",
                resolvedRole, joinCsv(hiddenMenus), joinCsv(hiddenDbs), username);
        if (updated == 0) {
            return Maps.of("success", false, "message", "존재하지 않는 계정입니다.");
        }
        return Maps.of("success", true);
    }

    public List<Map<String, Object>> listUsers() {
        return jdbc.query("SELECT username, role, hidden_menus, hidden_dbs FROM users ORDER BY username", (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", rs.getString("username"));
            row.put("role", rs.getString("role"));
            row.put("hidden_menus", parseCsv(rs.getString("hidden_menus")));
            row.put("hidden_dbs", parseCsv(rs.getString("hidden_dbs")));
            return row;
        });
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || Strings.isBlank(csv)) {
            return Lists.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!Strings.isBlank(part)) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private String joinCsv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }

    public Map<String, Object> deleteUser(String username) {
        if ("admin".equals(username)) {
            return Maps.of("success", false, "message", "admin 계정은 삭제할 수 없습니다.");
        }
        int updated = jdbc.update("DELETE FROM users WHERE username = ?", username);
        if (updated == 0) {
            return Maps.of("success", false, "message", "존재하지 않는 계정입니다.");
        }
        return Maps.of("success", true);
    }

    public boolean changePassword(String token, String currentPassword, String newPassword) {
        List<String[]> row = jdbc.query("SELECT username, password FROM users WHERE token = ?",
                (rs, rowNum) -> new String[]{rs.getString("username"), rs.getString("password")}, token);
        if (row.isEmpty()) {
            return false;
        }
        String username = row.get(0)[0];
        String currentHash = row.get(0)[1];
        if (!passwordEncoder.matches(currentPassword, currentHash)) {
            return false;
        }
        jdbc.update("UPDATE users SET password = ? WHERE username = ?",
                passwordEncoder.encode(newPassword), username);
        return true;
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        secureRandom.nextBytes(b);
        return b;
    }
}

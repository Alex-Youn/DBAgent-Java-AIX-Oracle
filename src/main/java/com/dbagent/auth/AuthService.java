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
        private final boolean fleetOverview;
        private final boolean fleetOverviewAutoRedirect;
        private final String token;

        public AuthSession(String username, String role, List<String> hiddenMenus, List<String> hiddenDbs,
                            boolean fleetOverview, boolean fleetOverviewAutoRedirect, String token) {
            this.username = username;
            this.role = role;
            this.hiddenMenus = hiddenMenus;
            this.hiddenDbs = hiddenDbs;
            this.fleetOverview = fleetOverview;
            this.fleetOverviewAutoRedirect = fleetOverviewAutoRedirect;
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

        public boolean fleetOverview() {
            return fleetOverview;
        }

        public boolean fleetOverviewAutoRedirect() {
            return fleetOverviewAutoRedirect;
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
        // Fleet Overview access is opt-in per account (사용자 요청: "Admin으로 로그인할때만 유효한거고
        // 권한을 주면 가능하게") - unlike hidden_menus/hidden_dbs (deny-list, default visible), this is
        // an allow-list defaulting to 0/false so a freshly created account can't see it until an admin
        // explicitly grants it. admin itself bypasses this column entirely (see canAccessFleetOverview).
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS fleet_overview INTEGER NOT NULL DEFAULT 0");
        // Separate from the fleet_overview access grant above - this is "should logging in jump
        // straight to Fleet Overview" (사용자 요청: "admin 권한도 진입 옵션 선택할 수 있나"), a personal
        // preference any account with access can flip for themselves (see
        // setFleetOverviewAutoRedirect), including admin - admin can't be edited through the normal
        // 계정 관리 username-targeted update, but this one is self-service by token, not by username.
        // Defaults to 1 (on) so granting access still auto-redirects until someone opts out.
        jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS fleet_overview_auto_redirect INTEGER NOT NULL DEFAULT 1");

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
            row = jdbc.queryForMap("SELECT password, role, hidden_menus, hidden_dbs, fleet_overview, fleet_overview_auto_redirect FROM users WHERE username = ?", username);
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
                parseCsv((String) row.get("hidden_menus")), parseCsv((String) row.get("hidden_dbs")),
                isTrue(row.get("fleet_overview")), isTrue(row.get("fleet_overview_auto_redirect")), token));
    }

    public Optional<AuthSession> sessionForToken(String token) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT username, role, hidden_menus, hidden_dbs, fleet_overview, fleet_overview_auto_redirect FROM users WHERE token = ?", token);
            return Optional.of(new AuthSession((String) row.get("username"), (String) row.get("role"),
                    parseCsv((String) row.get("hidden_menus")), parseCsv((String) row.get("hidden_dbs")),
                    isTrue(row.get("fleet_overview")), isTrue(row.get("fleet_overview_auto_redirect")), token));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean isAdmin(String token) {
        return sessionForToken(token).map(s -> "admin".equals(s.role())).orElse(false);
    }

    /** Admin always has it; other accounts only if explicitly granted (fleet_overview column). */
    public boolean canAccessFleetOverview(String token) {
        return sessionForToken(token).map(s -> "admin".equals(s.role()) || s.fleetOverview()).orElse(false);
    }

    private boolean isTrue(Object dbValue) {
        return dbValue instanceof Number && ((Number) dbValue).intValue() != 0;
    }

    /**
     * Self-service, by token rather than username - unlike updateUser() this is intentionally not
     * blocked for admin, since it's a personal "jump to Fleet Overview on login or not" preference
     * for whichever account is calling it, not an admin managing someone else's permissions.
     */
    public Map<String, Object> setFleetOverviewAutoRedirect(String token, boolean autoRedirect) {
        Optional<AuthSession> session = sessionForToken(token);
        if (!session.isPresent()) {
            return Maps.of("success", false, "message", "로그인이 필요합니다.");
        }
        jdbc.update("UPDATE users SET fleet_overview_auto_redirect = ? WHERE username = ?",
                autoRedirect ? 1 : 0, session.get().username());
        return Maps.of("success", true);
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
                                           List<String> hiddenMenus, List<String> hiddenDbs, boolean fleetOverview) {
        if (username == null || Strings.isBlank(username) || password == null || Strings.isBlank(password)) {
            return Maps.of("success", false, "message", "아이디와 비밀번호를 입력하세요.");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            return Maps.of("success", false, "message", "이미 존재하는 아이디입니다.");
        }
        String resolvedRole = "admin".equals(role) ? "admin" : "user";
        jdbc.update("INSERT INTO users (username, password, role, hidden_menus, hidden_dbs, fleet_overview) VALUES (?, ?, ?, ?, ?, ?)",
                username, passwordEncoder.encode(password), resolvedRole, joinCsv(hiddenMenus), joinCsv(hiddenDbs), fleetOverview ? 1 : 0);
        return Maps.of("success", true);
    }

    public Map<String, Object> updateUser(String username, String role, List<String> hiddenMenus, List<String> hiddenDbs, boolean fleetOverview) {
        if ("admin".equals(username)) {
            return Maps.of("success", false, "message", "admin 계정은 수정할 수 없습니다.");
        }
        String resolvedRole = "admin".equals(role) ? "admin" : "user";
        int updated = jdbc.update("UPDATE users SET role = ?, hidden_menus = ?, hidden_dbs = ?, fleet_overview = ? WHERE username = ?",
                resolvedRole, joinCsv(hiddenMenus), joinCsv(hiddenDbs), fleetOverview ? 1 : 0, username);
        if (updated == 0) {
            return Maps.of("success", false, "message", "존재하지 않는 계정입니다.");
        }
        return Maps.of("success", true);
    }

    public List<Map<String, Object>> listUsers() {
        return jdbc.query("SELECT username, role, hidden_menus, hidden_dbs, fleet_overview FROM users ORDER BY username", (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", rs.getString("username"));
            row.put("role", rs.getString("role"));
            row.put("hidden_menus", parseCsv(rs.getString("hidden_menus")));
            row.put("hidden_dbs", parseCsv(rs.getString("hidden_dbs")));
            row.put("fleet_overview", rs.getInt("fleet_overview") != 0);
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

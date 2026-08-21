package com.dbagent.query;

import com.dbagent.auth.AuthService;
import com.dbagent.util.Lists;
import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class SqlQueryController {

    private final SqlQueryService queryService;
    private final DatabaseConfigService configService;
    private final AuthService authService;

    public SqlQueryController(SqlQueryService queryService, DatabaseConfigService configService, AuthService authService) {
        this.queryService = queryService;
        this.configService = configService;
        this.authService = authService;
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody SqlQueryRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "SQL 실행 권한이 없습니다."));
        }
        if (req.sql() == null || Strings.isBlank(req.sql())) {
            return ResponseEntity.ok(Maps.of("success", false, "message", "실행할 SQL을 입력하세요."));
        }
        try {
            TargetDbConfig target = configService.resolve(req.dbId(), req.account());
            return ResponseEntity.ok(queryService.execute(target, req.sql(), req.maxRows()));
        } catch (SQLException e) {
            return ResponseEntity.ok(Maps.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/accounts")
    public ResponseEntity<Map<String, Object>> accounts(@RequestParam("db_id") String dbId,
                                                          @RequestParam(required = false) String token) {
        if (!authService.canAccessDb(token, dbId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Maps.of("accounts", Lists.of()));
        }
        return ResponseEntity.ok(Maps.of("accounts", configService.listAccounts(dbId)));
    }
}

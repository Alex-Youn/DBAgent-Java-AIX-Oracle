package com.dbagent.aidba;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Java port of error_searcher.py's error-code lookup (the /api/aidba/error_search route). */
@Service
public class ErrorSearchService {

    @Value("${aidba.errors-db-path}")
    private String errorsDbPath;

    public Map<String, Object> getErrorSolution(String rawCode) {
        String errorCode = Strings.strip(rawCode).toUpperCase(Locale.ROOT);
        // H2 file DBs live on disk as <path>.mv.db; errorsDbPath itself has no extension.
        File dbFile = new File(errorsDbPath + ".mv.db");
        if (!dbFile.exists()) {
            return Maps.of("error", "DB 파일을 찾을 수 없습니다. oracle_errors.mv.db 파일이 존재하는지 확인해주세요.");
        }
        String h2Base = new File(errorsDbPath).getAbsolutePath();
        String sql = "SELECT cause, action, query_or_log FROM error_dictionary WHERE error_code = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:h2:file:" + h2Base, "sa", "");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, errorCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.put("error_code", errorCode);
                    result.put("cause", rs.getString("cause"));
                    result.put("action", rs.getString("action"));
                    result.put("query_or_log", rs.getString("query_or_log"));
                    return result;
                }
                return Maps.of("found", false,
                        "message", "[" + errorCode + "] 에 대한 지식을 데이터베이스에서 찾을 수 없습니다.");
            }
        } catch (SQLException e) {
            return Maps.of("error", "DB 조회 중 오류 발생: " + e.getMessage());
        }
    }
}

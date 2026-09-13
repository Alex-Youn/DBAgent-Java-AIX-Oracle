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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java port of error_searcher.py and the retrieval half of api_server.py's /api/aidba/chat route. */
@Service
public class ErrorSearchService {

    private static final Pattern ORA_CODE = Pattern.compile("ORA-\\d+");
    private static final Pattern NON_WORD_KOREAN = Pattern.compile("[^\\w가-힣]");

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

    /** Regex/keyword retrieval used as the RAG context for the chat endpoint (no embeddings involved). */
    public List<String> retrieveDocs(String prompt) {
        List<String> docs = new ArrayList<>();
        File dbFile = new File(errorsDbPath + ".mv.db");
        if (!dbFile.exists()) {
            return docs;
        }
        String h2Base = new File(errorsDbPath).getAbsolutePath();

        try (Connection conn = DriverManager.getConnection("jdbc:h2:file:" + h2Base, "sa", "")) {
            List<String> oraMatches = new ArrayList<>();
            Matcher matcher = ORA_CODE.matcher(prompt.toUpperCase(Locale.ROOT));
            while (matcher.find()) {
                oraMatches.add(matcher.group());
            }

            if (!oraMatches.isEmpty()) {
                String placeholders = String.join(",", Collections.nCopies(oraMatches.size(), "?"));
                String sql = "SELECT error_code, cause, action FROM error_dictionary WHERE error_code IN ("
                        + placeholders + ") LIMIT 5";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < oraMatches.size(); i++) {
                        ps.setString(i + 1, oraMatches.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            docs.add(formatDoc(rs.getString("error_code"), rs.getString("cause"), rs.getString("action")));
                        }
                    }
                }
            }

            if (docs.size() < 3) {
                List<String> keywords = extractKeywords(prompt, 5);
                if (!keywords.isEmpty()) {
                    String likeClauses = String.join(" OR ", Collections.nCopies(keywords.size(), "cause LIKE ? OR action LIKE ?"));
                    int limit = 5 - docs.size();
                    String sql = "SELECT error_code, cause, action FROM error_dictionary WHERE "
                            + likeClauses + " LIMIT " + limit;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int idx = 1;
                        for (String keyword : keywords) {
                            String param = "%" + keyword + "%";
                            ps.setString(idx++, param);
                            ps.setString(idx++, param);
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String entry = formatDoc(rs.getString("error_code"), rs.getString("cause"), rs.getString("action"));
                                if (!docs.contains(entry)) {
                                    docs.add(entry);
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            return docs;
        }

        return docs;
    }

    private List<String> extractKeywords(String prompt, int max) {
        String cleaned = NON_WORD_KOREAN.matcher(prompt).replaceAll(" ");
        List<String> result = new ArrayList<>();
        for (String token : cleaned.trim().split("\\s+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    private String formatDoc(String errorCode, String cause, String action) {
        return "에러 코드: " + errorCode + "\n발생 원인: " + cause + "\n조치 방안: " + action;
    }
}

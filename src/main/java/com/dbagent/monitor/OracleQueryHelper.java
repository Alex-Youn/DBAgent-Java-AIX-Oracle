package com.dbagent.monitor;

import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.util.Strings;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java port of api_server.py's get_inst_id(): resolves the RAC instance number for a target DB. */
@Component
public class OracleQueryHelper {

    private static final Pattern TRAILING_NUM = Pattern.compile("(\\d+)$");
    private static final Pattern ANY_NUM = Pattern.compile("(\\d+)");

    /**
     * 인스턴스 번호를 구한다. 조회가 실패(SQLException·타임아웃)하면 예외를 그대로 던진다.
     * (2026-10-09 점검 수정 A1-3) 예전에는 실패 시 조용히 1을 반환해 RAC에서 엉뚱한 인스턴스로 KILL이 나갈 수 있었다.
     * 호출부는 예외를 받으면 해당 KILL을 실패 처리해야 한다.
     *
     * @param queryTimeoutSeconds 0 이하면 타임아웃을 걸지 않는다
     */
    public int getInstId(Connection conn, TargetDbConfig target, int queryTimeoutSeconds) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (queryTimeoutSeconds > 0) st.setQueryTimeout(queryTimeoutSeconds);
            ResultSet rs = st.executeQuery("SELECT instance_number, instance_name FROM v$instance");

            List<Object[]> instances = new ArrayList<>();
            while (rs.next()) {
                instances.add(new Object[]{rs.getInt(1), rs.getString(2)});
            }

            String targetSid = target.sid();
            String dbId = target.id();
            String name = target.name();

            for (Object[] inst : instances) {
                String instName = (String) inst[1];
                if (isNotBlank(targetSid) && instName != null && instName.equalsIgnoreCase(targetSid)) {
                    return (int) inst[0];
                }
                if (isNotBlank(name) && instName != null && instName.equalsIgnoreCase(name)) {
                    return (int) inst[0];
                }
            }

            String tNum = trailingNum(targetSid);
            if (tNum == null) tNum = trailingNum(dbId);
            if (tNum == null) tNum = trailingNum(name);
            if (tNum == null) tNum = anyNum(targetSid);
            if (tNum == null) tNum = anyNum(dbId);
            if (tNum == null) tNum = anyNum(name);

            if (tNum != null) {
                for (Object[] inst : instances) {
                    String instName = (String) inst[1];
                    int instNum = (int) inst[0];
                    if ((instName != null && instName.endsWith(tNum)) || String.valueOf(instNum).equals(tNum)) {
                        return instNum;
                    }
                }
            }

            try (Statement st2 = conn.createStatement()) {
                if (queryTimeoutSeconds > 0) st2.setQueryTimeout(queryTimeoutSeconds);
                try (ResultSet rs2 = st2.executeQuery("SELECT sys_context('userenv', 'instance') FROM dual")) {
                    if (rs2.next()) {
                        return rs2.getInt(1);
                    }
                }
            }
            // v$instance·sys_context 모두 값이 없는 비정상 상황 - 임의의 1로 가정하지 않고 실패로 알린다
            throw new SQLException("instance number not found");
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !Strings.isBlank(s);
    }

    private String trailingNum(String s) {
        if (!isNotBlank(s)) return null;
        Matcher m = TRAILING_NUM.matcher(Strings.strip(s));
        return m.find() ? m.group(1) : null;
    }

    private String anyNum(String s) {
        if (!isNotBlank(s)) return null;
        Matcher m = ANY_NUM.matcher(s);
        return m.find() ? m.group(1) : null;
    }
}

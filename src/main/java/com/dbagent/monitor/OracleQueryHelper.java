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

    public int getInstId(Connection conn, TargetDbConfig target) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT instance_number, instance_name FROM v$instance")) {

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

            try (Statement st2 = conn.createStatement();
                 ResultSet rs2 = st2.executeQuery("SELECT sys_context('userenv', 'instance') FROM dual")) {
                if (rs2.next()) {
                    return rs2.getInt(1);
                }
            }
            return 1;
        } catch (SQLException e) {
            return 1;
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

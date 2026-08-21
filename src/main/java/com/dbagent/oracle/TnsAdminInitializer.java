package com.dbagent.oracle;

import com.dbagent.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Points the Oracle thin driver at tnsnames.ora so bare SID/alias DSNs (see OracleConnectionPoolManager)
 * resolve. Mirrors the Python app's deployment habit of only ever editing ORACLE_HOME in oracle.env when
 * moving between networks: if dbagent.oracle.tns-admin isn't explicitly set, this derives
 * {ORACLE_HOME}\network\admin from oracle.env instead, so the same one-line edit works for both.
 */
@Component
public class TnsAdminInitializer {

    @Value("${dbagent.oracle.tns-admin:}")
    private String tnsAdmin;

    @Value("${dbagent.oracle-env-path}")
    private String oracleEnvPath;

    @PostConstruct
    void init() {
        String resolved = (tnsAdmin != null && !Strings.isBlank(tnsAdmin)) ? tnsAdmin : deriveFromOracleHome();
        if (resolved != null && !Strings.isBlank(resolved)) {
            System.setProperty("oracle.net.tns_admin", resolved);
        }
    }

    private String deriveFromOracleHome() {
        File file = new File(oracleEnvPath);
        if (!file.exists()) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                int idx = line.indexOf('=');
                if (idx > 0 && "ORACLE_HOME".equals(line.substring(0, idx).trim())) {
                    String home = line.substring(idx + 1).trim();
                    return home.isEmpty() ? null : home + File.separator + "network" + File.separator + "admin";
                }
            }
        } catch (IOException ignored) {
            // No oracle.env / unreadable - tns_admin system property simply won't be set.
        }
        return null;
    }
}

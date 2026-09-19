package com.dbagent.oracle;

import com.dbagent.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Points the Oracle thin driver at tnsnames.ora so bare SID/alias DSNs (see OracleConnectionPoolManager)
 * resolve, when dbagent.oracle.tns-admin is explicitly set. (The old ORACLE_HOME-derived fallback was
 * removed once every deployment confirmed it sets this property explicitly - see oracle.env removal
 * migration checklist, 0단계 C.)
 */
@Component
public class TnsAdminInitializer {

    @Value("${dbagent.oracle.tns-admin:}")
    private String tnsAdmin;

    @PostConstruct
    void init() {
        if (!Strings.isBlank(tnsAdmin)) {
            System.setProperty("oracle.net.tns_admin", tnsAdmin);
        }
    }
}

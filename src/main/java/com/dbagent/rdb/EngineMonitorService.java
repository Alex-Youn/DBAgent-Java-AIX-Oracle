package com.dbagent.rdb;

import com.dbagent.oracle.TargetDbConfig;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Lightweight monitoring surface for non-Oracle engines (MySQL/MariaDB/PostgreSQL), implemented by
 * {@link MySqlMonitorService} and {@link PostgresMonitorService}. Dispatch happens by
 * {@link TargetDbConfig#dbType()} - see RdbMonitorController and MonitorController.fleetStatusFor().
 */
public interface EngineMonitorService {

    /** Session/process list - same rough shape across engines (id/user/host/state/query text). */
    List<Map<String, Object>> getSessions(TargetDbConfig target) throws SQLException;

    /**
     * Storage usage per schema/database. Reuses the same JSON shape as Oracle's
     * MonitorService.getTablespaces() (tablespace_name/status/total_mb/used_mb/free_mb/used_pct) so the
     * existing tablespace-summary rendering can be reused as-is - these engines have no
     * allocated-vs-used distinction like Oracle datafiles, so total_mb==used_mb and free_mb=0.
     */
    List<Map<String, Object>> getStorage(TargetDbConfig target) throws SQLException;

    /** Fleet Overview card status - same outer shape as MonitorService.getFleetStatus(). */
    Map<String, Object> getFleetStatus(TargetDbConfig target);
}

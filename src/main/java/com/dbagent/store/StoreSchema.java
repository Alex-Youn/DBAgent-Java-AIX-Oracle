package com.dbagent.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.dbagent.store.ColumnDef.bigint;
import static com.dbagent.store.ColumnDef.bool01;
import static com.dbagent.store.ColumnDef.dbl;
import static com.dbagent.store.ColumnDef.identity;
import static com.dbagent.store.ColumnDef.integer;
import static com.dbagent.store.ColumnDef.textLarge;
import static com.dbagent.store.ColumnDef.varchar;
import static com.dbagent.store.ColumnDef.varcharNoLengthInH2;

/**
 * 내부 저장소 32개 테이블의 정의(레지스트리). DDL의 원본은 여기 한 곳이다(설계서 2.4절 S2).
 *
 * <p>1단계(2026-10-13): 서비스들이 실행하는 현행 H2 DDL을 그대로 옮겼다 - 빈 H2에서 서비스 DDL로 만든 스키마와 이 정의로 만든
 * 스키마의 INFORMATION_SCHEMA 차이가 0건이다. 서비스는 아직 자기 DDL을 실행하며, 이 정의로 바꾸는 것은 2·3단계다.
 * 그때까지 테이블 구조를 바꾸면 서비스 DDL과 여기를 같이 고쳐야 한다.</p>
 *
 * <p>Oracle에서 달라지는 것은 컬럼 정의의 표시로 적는다: ""가 들어가는 컬럼은 {@code emptyAsNull}(NOT NULL을 뺀다),
 * 더 쓰지 않는 컬럼은 {@code oracleOmit}. H2에서 길이 없이 만들어진 VARCHAR의 Oracle 길이는 설계서 2.5절 값이며,
 * 거기에 없는 users.role(20)·hidden_menus(1000)·hidden_dbs(4000 → CLOB)는 1단계에서 정한 값이다(0-9 확인 대상).</p>
 */
public final class StoreSchema {

    private static final List<TableDef> TABLES = build();

    private StoreSchema() {
    }

    public static List<TableDef> all() {
        return TABLES;
    }

    public static List<TableDef> of(StoreId store) {
        List<TableDef> out = new ArrayList<TableDef>();
        for (TableDef t : TABLES) {
            if (t.store() == store) out.add(t);
        }
        return out;
    }

    public static TableDef table(String name) {
        for (TableDef t : TABLES) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        throw new IllegalArgumentException("저장소 테이블 정의가 없습니다: " + name);
    }

    private static ColumnDef dbId() {
        return varchar("db_id", 64).notNull();
    }

    private static List<TableDef> build() {
        List<TableDef> t = new ArrayList<TableDef>();

        // ------------------------------------------------------------------ users (AuthService)
        t.add(TableDef.of(StoreId.USERS, "users",
                varcharNoLengthInH2("username", 128),
                varcharNoLengthInH2("password", 100),
                // 세션을 sessions 테이블로 옮긴 뒤 아무도 읽지 않는 옛 컬럼
                varcharNoLengthInH2("token", 64).oracleOmit(),
                varcharNoLengthInH2("role", 20).notNull().dflt("'user'").addedLater(),
                varcharNoLengthInH2("hidden_menus", 1000).addedLater(),
                varcharNoLengthInH2("hidden_dbs", 4000).addedLater(),
                bool01("fleet_overview").notNull().dflt("0").addedLater(),
                bool01("fleet_overview_auto_redirect").notNull().dflt("1").addedLater())
                .pk("username"));
        t.add(TableDef.of(StoreId.USERS, "sessions",
                varcharNoLengthInH2("token", 64),
                varcharNoLengthInH2("username", 128).notNull(),
                bigint("created_at").addedLater())
                .pk("token"));

        // ------------------------------------------------------------------ dbconfig (DatabaseConfigService, AppSettingStore)
        t.add(TableDef.of(StoreId.DBCONFIG, "db_instances",
                varchar("id", 200),
                varchar("group_name", 200).notNull(),
                integer("group_order").notNull(),
                integer("instance_order").notNull(),
                varchar("name", 200).notNull().dflt("''").emptyAsNull(),
                varchar("db_type", 50).notNull().dflt("'oracle'"),
                varchar("host", 200).notNull().dflt("''").emptyAsNull(),
                integer("port").notNull().dflt("1521"),
                varchar("sid", 200).notNull().dflt("''").emptyAsNull(),
                varchar("db_user", 200).notNull().dflt("''").emptyAsNull(),
                varchar("password", 500).notNull().dflt("''").emptyAsNull(),
                varchar("connect_mode", 50).notNull().dflt("''").emptyAsNull(),
                integer("pool_min_idle"),
                integer("pool_max_size"),
                varchar("session_thresholds", 200),
                textLarge("accounts", 8000),
                varchar("expected_instance_name", 200).addedLater(),
                bool01("oracle_cdb").dflt("0").notNull().addedLater())
                .pk("id")
                // 비밀번호 실암호화 전에 만들어진 파일은 accounts가 VARCHAR(4000)이다
                .h2Legacy("ALTER TABLE db_instances ALTER COLUMN accounts VARCHAR(8000)"));
        t.add(TableDef.of(StoreId.DBCONFIG, "db_config_meta",
                integer("id").check("id = 1"),
                bool01("migrated_from_json").notNull().dflt("0"),
                varchar("password_key_id", 32).addedLater(),
                integer("password_format_ver").dflt("0").addedLater(),
                varchar("reencrypted_at", 40).addedLater())
                .pk("id"));
        t.add(TableDef.of(StoreId.DBCONFIG, "app_setting",
                varchar("setting_key", 200),
                varchar("setting_value", 4000).notNull().emptyAsNull(),
                varchar("updated_at", 40),
                varchar("updated_by", 100),
                integer("row_version").notNull().dflt("1"))
                .pk("setting_key"));
        t.add(TableDef.of(StoreId.DBCONFIG, "app_setting_db",
                varchar("db_id", 200).notNull(),
                varchar("setting_key", 200).notNull(),
                varchar("setting_value", 4000).notNull().emptyAsNull(),
                varchar("updated_at", 40),
                varchar("updated_by", 100),
                integer("row_version").notNull().dflt("1"))
                .pk("db_id", "setting_key"));
        t.add(TableDef.of(StoreId.DBCONFIG, "app_setting_history",
                bigint("id"),
                varchar("setting_key", 200).notNull(),
                varchar("db_id", 200),
                varchar("old_value", 4000),
                varchar("new_value", 4000),
                varchar("action", 20).notNull(),
                varchar("changed_by", 100),
                varchar("changed_at", 40).notNull(),
                varchar("reason", 1000))
                .pk("id"));
        t.add(TableDef.of(StoreId.DBCONFIG, "app_setting_meta",
                integer("id").check("id = 1"),
                bool01("migrated_from_properties").notNull().dflt("0"))
                .pk("id"));

        // ------------------------------------------------------------------ metrics (InstanceMetricHistoryService, MonitorStoreService)
        t.add(TableDef.of(StoreId.METRICS, "instance_metric_history",
                varcharNoLengthInH2("instance_name", 64).notNull(),
                varcharNoLengthInH2("metric_name", 64).notNull(),
                bigint("sampled_at").notNull(),
                dbl("value"))
                .pk("instance_name", "metric_name", "sampled_at")
                // 기본키와 컬럼이 같다 - H2에는 예전부터 있고, Oracle에서는 만들지 않는다(ORA-01408)
                .index(IndexDef.of("idx_instance_metric_history_lookup", "instance_name", "metric_name", "sampled_at")));
        // parse_calls_d·force_matching_signature는 나중에 추가된 컬럼이지만 addedLater로 표시하지 않는다 - H2 1.4.200은 컬럼을
        // 추가할 때 테이블 전체를 복사하므로(2026-10-12 폐쇄망 실측: 855만 행에서 5분 이상) 두 컬럼을 한 문장으로 추가하는
        // MonitorStoreService.addMissingSqlstatColumns()가 계속 맡는다.
        t.add(TableDef.of(StoreId.METRICS, "mon_sqlstat_delta",
                dbId(),
                bigint("collect_ts").notNull(),
                varchar("sql_id", 13).notNull(),
                bigint("plan_hash_value").notNull(),
                bigint("executions_d"),
                bigint("elapsed_us_d"),
                bigint("cpu_us_d"),
                bigint("buffer_gets_d"),
                bigint("disk_reads_d"),
                bigint("rows_d"),
                varchar("sql_text", 1000),
                bigint("parse_calls_d"),
                varchar("force_matching_signature", 24))
                .pk("db_id", "collect_ts", "sql_id", "plan_hash_value")
                // H2에서는 만들지 않는다(2026-10-11 결정: 파일이 8,284MB / 885MB로 부풂). Oracle에서는 만든다(없으면 sql_id 조회 370ms 이상)
                .index(IndexDef.of("idx_mon_sqlstat_delta_sql", "db_id", "sql_id", "collect_ts").h2Skip()));
        t.add(TableDef.of(StoreId.METRICS, "mon_lock_sample",
                dbId(),
                bigint("collect_ts").notNull(),
                integer("tx_wait_max"),
                integer("tm_wait_max"),
                integer("tm_holder_over_max"),
                varchar("incident_yn", 1))
                .pk("db_id", "collect_ts"));
        t.add(TableDef.of(StoreId.METRICS, "mon_kill_audit",
                identity("audit_id"),
                dbId(),
                bigint("kill_ts").notNull(),
                varchar("executed_by", 64).notNull(),
                bigint("sid").notNull(),
                bigint("serial_no").notNull(),
                varchar("instance_name", 64),
                varchar("username", 128),
                varchar("program", 128),
                varchar("machine", 128),
                varchar("lock_object", 261),
                bigint("last_call_et"),
                varchar("reason", 100),
                varchar("kill_result", 20),
                varchar("err_msg", 300))
                .pk("audit_id")
                .index(IndexDef.of("idx_mon_kill_audit_ts", "db_id", "kill_ts")));
        t.add(TableDef.of(StoreId.METRICS, "mon_check_result",
                dbId(),
                bigint("check_ts").notNull(),
                varchar("check_type", 30).notNull(),
                varchar("target_name", 261).notNull(),
                varchar("severity", 10).notNull(),
                dbl("metric_value"),
                dbl("threshold"),
                varchar("detail_json", 4000),
                varchar("ack_by", 64),
                bigint("ack_ts"))
                .pk("db_id", "check_ts", "check_type", "target_name")
                .index(IndexDef.of("idx_mon_check_result_type", "db_id", "check_type", "check_ts")));
        t.add(TableDef.of(StoreId.METRICS, "mon_check_ack",
                dbId(),
                varchar("check_type", 30).notNull(),
                varchar("target_name", 261).notNull(),
                varchar("fingerprint", 200).notNull(),
                varchar("ack_by", 64).notNull(),
                bigint("ack_ts").notNull())
                .pk("db_id", "check_type", "target_name"));
        t.add(TableDef.of(StoreId.METRICS, "mon_segment_size",
                dbId(),
                bigint("snap_date").notNull(),
                varchar("owner", 128).notNull(),
                varchar("segment_name", 128).notNull(),
                bigint("size_bytes").notNull())
                .pk("db_id", "snap_date", "owner", "segment_name"));

        // ------------------------------------------------------------------ perf (PerfStore/Lock/Obj/Sql/SysService)
        t.add(TableDef.of(StoreId.PERF, "perf_ash_min",
                dbId(),
                bigint("minute_key").notNull(),
                varchar("category", 16).notNull(),
                varchar("event", 64).notNull(),
                // 샘플러가 값이 없을 때 ""를 쓴다(InstanceMetricSamplerService) - Oracle에서는 NULL 허용
                varchar("username", 128).notNull().emptyAsNull(),
                varchar("machine", 64).notNull().emptyAsNull(),
                varchar("sql_id", 13).notNull().emptyAsNull(),
                integer("sql_opcode"),
                integer("seconds").notNull(),
                integer("sessions"),
                bigint("last_sid"),
                bigint("last_serial"))
                .index(IndexDef.of("idx_perf_ash_min_time", "db_id", "minute_key")));
        t.add(TableDef.of(StoreId.PERF, "perf_sql_text",
                dbId(),
                varchar("sql_id", 13).notNull(),
                varchar("sql_text", 1000),
                bigint("captured_key").notNull())
                .pk("db_id", "sql_id"));
        t.add(TableDef.of(StoreId.PERF, "perf_filter_value",
                dbId(),
                varchar("kind", 1).notNull(),
                varchar("val", 128).notNull(),
                bigint("last_key").notNull())
                .pk("db_id", "kind", "val"));
        t.add(TableDef.of(StoreId.PERF, "perf_collect_min",
                dbId(),
                bigint("minute_key").notNull(),
                varchar("kind", 16).notNull())
                .pk("db_id", "minute_key", "kind"));
        t.add(TableDef.of(StoreId.PERF, "perf_lock_incident",
                varchar("incident_id", 200),
                dbId(),
                varchar("lock_type", 2).notNull(),
                varchar("status", 8).notNull(),
                bigint("start_ts").notNull(),
                bigint("end_ts").notNull(),
                bigint("peak_ts").notNull(),
                bigint("duration_sec").notNull(),
                integer("max_waiters").notNull(),
                bigint("wait_session_sec").notNull(),
                bigint("blocker_sid"),
                bigint("blocker_serial"),
                bigint("blocker_inst"),
                varchar("blocker_user", 128),
                varchar("blocker_module", 128),
                varchar("blocker_program", 128),
                varchar("blocker_machine", 64),
                varchar("blocker_status", 16),
                bigint("blocker_idle_sec"),
                varchar("blocker_sql_id", 13),
                integer("req_mode"),
                integer("held_mode"),
                varchar("wait_event", 64),
                bigint("obj_id"),
                varchar("obj_name", 200),
                varchar("row_info", 200),
                varchar("cause_code", 24),
                varchar("cause_text", 400),
                varchar("cause_confidence", 8),
                varchar("fk_info", 400),
                varchar("cause_stats", 2000).addedLater())
                .pk("incident_id")
                .index(IndexDef.of("idx_perf_lock_incident_time", "db_id", "start_ts"))
                .index(IndexDef.of("idx_perf_lock_incident_end", "db_id", "end_ts"))
                .index(IndexDef.of("idx_perf_lock_incident_status", "db_id", "status")));
        t.add(TableDef.of(StoreId.PERF, "perf_lock_incident_waiter",
                varchar("incident_id", 200).notNull(),
                bigint("sid").notNull(),
                bigint("serial_no").notNull(),
                varchar("username", 128),
                varchar("module", 128),
                varchar("machine", 64),
                varchar("sql_id", 13),
                bigint("wait_sec"),
                bigint("parent_sid"))
                .pk("incident_id", "sid", "serial_no"));
        t.add(TableDef.of(StoreId.PERF, "perf_obj_seg",
                dbId(),
                bigint("minute_key").notNull(),
                varchar("stat", 4).notNull(),
                bigint("obj_id").notNull(),
                bigint("val").notNull())
                .pk("db_id", "minute_key", "stat", "obj_id"));
        t.add(TableDef.of(StoreId.PERF, "perf_obj_name",
                dbId(),
                bigint("obj_id").notNull(),
                varchar("obj_name", 400).notNull(),
                varchar("obj_type", 30))
                .pk("db_id", "obj_id"));
        t.add(TableDef.of(StoreId.PERF, "perf_obj_file",
                dbId(),
                bigint("minute_key").notNull(),
                varchar("kind", 1).notNull(),
                bigint("file_no").notNull(),
                bigint("reads_d").notNull(),
                bigint("writes_d").notNull(),
                bigint("readtim_d").notNull(),
                bigint("writetim_d").notNull())
                .pk("db_id", "minute_key", "kind", "file_no"));
        t.add(TableDef.of(StoreId.PERF, "perf_obj_file_name",
                dbId(),
                varchar("kind", 1).notNull(),
                bigint("file_no").notNull(),
                varchar("ts_name", 128),
                varchar("file_name", 513))
                .pk("db_id", "kind", "file_no"));
        t.add(TableDef.of(StoreId.PERF, "perf_obj_redo",
                dbId(),
                bigint("thread_no").notNull(),
                bigint("hour_key").notNull(),
                bigint("switches").notNull())
                .pk("db_id", "thread_no", "hour_key"));
        t.add(TableDef.of(StoreId.PERF, "perf_obj_temp",
                dbId(),
                bigint("minute_key").notNull(),
                varchar("sql_id", 13).notNull(),
                varchar("module", 64),
                bigint("temp_kb").notNull())
                .pk("db_id", "minute_key", "sql_id"));
        t.add(TableDef.of(StoreId.PERF, "perf_sql_hour",
                dbId(),
                bigint("hour_key").notNull(),
                varchar("sql_id", 13).notNull(),
                bigint("plan_hash_value").notNull(),
                varchar("fms", 24),
                bigint("executions").notNull(),
                bigint("elapsed_us").notNull(),
                bigint("cpu_us").notNull(),
                bigint("buffer_gets").notNull(),
                bigint("disk_reads").notNull(),
                bigint("rows_proc").notNull(),
                bigint("parse_calls").notNull())
                .pk("db_id", "hour_key", "sql_id", "plan_hash_value"));
        t.add(TableDef.of(StoreId.PERF, "perf_sql_literal_hour",
                dbId(),
                bigint("hour_key").notNull(),
                varchar("fms", 24).notNull(),
                bigint("sql_id_cnt").notNull(),
                bigint("execs").notNull(),
                bigint("parse_calls").notNull(),
                bigint("elapsed_us").notNull(),
                varchar("sample_sql_id", 13))
                .pk("db_id", "hour_key", "fms"));
        t.add(TableDef.of(StoreId.PERF, "perf_sql_plan_change",
                dbId(),
                varchar("sql_id", 13).notNull(),
                bigint("changed_key").notNull(),
                bigint("old_plan_hash").notNull(),
                bigint("new_plan_hash").notNull(),
                dbl("old_ms_per_exec").notNull(),
                dbl("new_ms_per_exec").notNull(),
                bigint("old_execs").notNull(),
                bigint("new_execs").notNull())
                .pk("db_id", "sql_id", "changed_key"));
        t.add(TableDef.of(StoreId.PERF, "perf_sys_metric",
                dbId(),
                bigint("minute_key").notNull(),
                varchar("metric_name", 64).notNull(),
                dbl("metric_value").notNull())
                .pk("db_id", "minute_key", "metric_name"));
        t.add(TableDef.of(StoreId.PERF, "perf_sys_event",
                dbId(),
                bigint("minute_key").notNull(),
                varchar("event", 64).notNull(),
                varchar("wait_class", 32),
                bigint("waits").notNull(),
                bigint("time_us").notNull())
                .pk("db_id", "minute_key", "event"));

        return Collections.unmodifiableList(t);
    }
}

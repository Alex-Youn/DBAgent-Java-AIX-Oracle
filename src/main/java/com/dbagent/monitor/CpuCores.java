package com.dbagent.monitor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * AAS 차트의 "CPU 코어" 기준선에 쓰는 코어 수 - v$osstat.NUM_CPU_CORES(물리 코어), 없으면 NUM_CPUS.
 *
 * <p>체크리스트 1-1(2026-09-24 폐쇄망 실측으로 NUM_CPU_CORES가 실제 서버 코어 수와 일치 확인). 코어
 * 기준선을 그리는 곳(Current Session 차트 getAshActivity, 60초 샘플러의 ash_cpu_cores)은 반드시 이
 * 헬퍼 하나를 거쳐야 화면마다 코어 수가 갈리지 않는다.
 *
 * <p>CPU 사용률(%)의 분모는 여기가 아니라 기존대로 NUM_CPUS(논리 CPU)를 쓴다(2026-09-25 오케스트레이터
 * 결정). 로컬 실측 NUM_CPUS=32 / NUM_CPU_CORES=16처럼 SMT 서버에서는 논리 CPU가 코어의 2~8배라,
 * 분모를 코어로 바꾸면 같은 부하에서 CPU%가 그만큼 부풀어 100%를 넘기 때문이다.
 */
final class CpuCores {

    private CpuCores() {
    }

    /** NUM_CPU_CORES, 없으면 NUM_CPUS, 둘 다 없으면 0. */
    static int query(Connection conn) throws SQLException {
        int cores = 0;
        int cpus = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT stat_name, value FROM v$osstat WHERE stat_name IN ('NUM_CPU_CORES', 'NUM_CPUS')")) {
            while (rs.next()) {
                if ("NUM_CPU_CORES".equals(rs.getString(1))) {
                    cores = rs.getInt(2);
                } else {
                    cpus = rs.getInt(2);
                }
            }
        }
        return cores > 0 ? cores : cpus;
    }
}

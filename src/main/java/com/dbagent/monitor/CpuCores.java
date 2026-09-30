package com.dbagent.monitor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * AAS 차트의 "CPU" 기준선에 쓰는 CPU 수 - v$osstat.NUM_CPUS(OS가 보는 논리 CPU).
 *
 * <p>2026-09-28 NUM_CPU_CORES → NUM_CPUS로 변경(운영 실측): 물리 CPU 18장 × 4코어 = 72코어 AIX 서버에서
 * NUM_CPU_CORES(72) 기준선으로는 AAS가 늘 기준선을 넘었는데 OS topas로는 CPU 37%만 쓰고 있었다. AIX SMT처럼
 * 코어당 스레드가 여러 개인 서버는 동시에 CPU를 쓸 수 있는 세션 수가 논리 CPU 수라, 기준선도 NUM_CPUS가 맞다.
 * CPU 사용률(%)의 분모(NUM_CPUS)와도 같은 기준이 된다.
 *
 * <p>기준선을 그리는 곳(Current Session 차트 getAshActivity, 60초 샘플러의 ash_cpu_cores)은 반드시 이 헬퍼
 * 하나를 거쳐야 화면마다 CPU 수가 갈리지 않는다.
 */
final class CpuCores {

    private CpuCores() {
    }

    /** NUM_CPUS, 없으면 0. */
    static int query(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT stat_name, value FROM v$osstat WHERE stat_name = 'NUM_CPUS'")) {
            return rs.next() ? rs.getInt(2) : 0;
        }
    }

    /**
     * NUM_CPU_CORES(물리 코어), 없으면 0 - ② 차트의 "물리 코어" 보조선 전용(2026-09-30 오케스트레이터 결정). CPU 초과·부하율
     * 판정은 계속 NUM_CPUS(위 query)로 한다(09-28 결정 유지). SMT 서버(폐쇄망 AIX 쿼드코어 18장 = 72코어)에서 CPU 층이 이 선을
     * 넘으면 코어를 스레드로 나눠 쓰기 시작해 세션당 처리 속도가 떨어질 수 있다는 참고선이다. 일부 플랫폼·권한에서는 행이 없다.
     */
    static int queryPhysical(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM v$osstat WHERE stat_name = 'NUM_CPU_CORES'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}

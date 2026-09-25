package com.dbagent.monitor;

/**
 * ASH(v$active_session_history) 행을 대기 분류로 나누는 CASE 식 - 화면마다 숫자가 어긋나지 않도록 모든
 * 경로가 이 한 곳의 식을 쓴다(2026-09-25, 전체 작업순서 C2).
 *
 * <ul>
 *   <li>7분류(CPU/Latch/User I/O/TX Lock/Sys I/O/TM Lock/Other): Current Session 차트(getAshActivity)와
 *       60초 샘플러의 ash_* 저장값. Active Session 리스트의 세션별 대기 분해와 같은 기준.</li>
 *   <li>8분류(Oracle 대기 클래스 CPU/User I/O/System I/O/Concurrency/Application/Commit/Network/Other):
 *       대시보드 개편(설계문서 대시보드 UI 개선 설계.md 2.3)용 ash_wc_* 저장값.</li>
 * </ul>
 *
 * 두 식 모두 테이블 별칭 h(v$active_session_history)를 전제로 하고, Idle 등 어디에도 속하지 않는 행은 NULL을
 * 돌려준다. 같은 ASH 행 집합에서 7분류 합계와 8분류 합계는 항상 같다(NULL이 되는 행이 같기 때문).
 */
final class AshCategories {

    private AshCategories() {
    }

    /** 7분류 키 - 배열 순서가 ash_* metric 저장 순서다. */
    static final String[] KEYS7 = {"cpu", "latch", "user_io", "tx_lock", "system_io", "tm_lock", "other"};

    /** 8분류 키 - 배열 순서가 ash_wc_* metric 저장 순서다. */
    static final String[] KEYS8 = {"cpu", "user_io", "system_io", "concurrency", "application", "commit", "network", "other"};

    static final String CASE7 =
            "CASE " +
            "WHEN h.session_state = 'ON CPU' THEN 'cpu' " +
            "WHEN h.wait_class = 'User I/O' THEN 'user_io' " +
            "WHEN h.wait_class = 'System I/O' THEN 'system_io' " +
            "WHEN h.event LIKE 'latch%' THEN 'latch' " +
            "WHEN h.event LIKE 'enq: TX%' THEN 'tx_lock' " +
            "WHEN h.event LIKE 'enq: TM%' THEN 'tm_lock' " +
            "WHEN h.wait_class NOT IN ('User I/O', 'System I/O', 'Idle') THEN 'other' " +
            "ELSE NULL END";

    static final String CASE8 =
            "CASE " +
            "WHEN h.session_state = 'ON CPU' THEN 'cpu' " +
            "WHEN h.wait_class = 'User I/O' THEN 'user_io' " +
            "WHEN h.wait_class = 'System I/O' THEN 'system_io' " +
            "WHEN h.wait_class = 'Concurrency' THEN 'concurrency' " +
            "WHEN h.wait_class = 'Application' THEN 'application' " +
            "WHEN h.wait_class = 'Commit' THEN 'commit' " +
            "WHEN h.wait_class = 'Network' THEN 'network' " +
            "WHEN h.wait_class <> 'Idle' THEN 'other' " +
            "ELSE NULL END";

    /** 키의 배열 위치, 없으면 -1. */
    static int indexOf(String[] keys, String key) {
        if (key == null) return -1;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) return i;
        }
        return -1;
    }
}

package com.dbagent.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Oracle 모드의 내부 저장소 풀을 만든다(설계서 2.3절). 저장소 4개가 같은 스키마를 가리키되 풀은 따로 둔다 - 로그인 경로가
 * 수집 쓰기와 접속을 다투지 않게 하려는 것이다.
 *
 * <p>여기서는 {@code dbagent.store.oracle.*}만 읽는다. H2용 키({@code spring.datasource.*},
 * {@code dbagent.<저장소>.datasource.*})는 읽지 않는다 - 번들 기본값의 {@code connection-init-sql=SET LOCK_TIMEOUT 5000}이
 * Oracle 접속에 실리면 ORA-00922로 접속마다 실패한다.</p>
 */
public final class OracleStorePools {

    private static final Logger log = LoggerFactory.getLogger(OracleStorePools.class);

    private static final String PREFIX = "dbagent.store.oracle.";

    /** 평문 비밀번호 경고는 한 번만 남긴다 - 저장소별 숨김 키로 users가 H2인 혼합 구성에서도 빠지지 않게 저장소 종류에 묶지 않는다. */
    private static final AtomicBoolean PLAIN_PASSWORD_WARNED = new AtomicBoolean(false);

    private OracleStorePools() {
    }

    public static HikariDataSource create(Environment env, StoreId id) {
        String url = required(env, "url");
        String username = required(env, "username");
        String password = readPassword(env);
        if (password.startsWith("ENC(")) {
            // conf 암호문 복호화는 2단계(체크리스트 2-1·2-2: 키 로딩을 빈 생성 전에도 쓸 수 있게 분리)에서 넣는다
            throw new IllegalStateException(PREFIX + "password의 ENC(...) 암호문은 아직 지원하지 않습니다(2단계 예정). 지금은 평문으로 적어야 합니다.");
        }
        if (PLAIN_PASSWORD_WARNED.compareAndSet(false, true)) {
            log.warn("내부 저장소(Oracle) 비밀번호가 conf에 평문으로 적혀 있습니다 - 검증 환경에서만 쓰십시오.");
        }

        HikariConfig c = new HikariConfig();
        c.setPoolName("store-" + id.key());
        c.setJdbcUrl(url);
        c.setDriverClassName("oracle.jdbc.OracleDriver");
        c.setUsername(username);
        c.setPassword(password);
        // 원격 DB에 저장소마다 10개는 과하다 - users·dbconfig 5, metrics·perf 4(합계 최대 18세션)
        int defaultMax = (id == StoreId.USERS || id == StoreId.DBCONFIG) ? 5 : 4;
        c.setMaximumPoolSize(env.getProperty(PREFIX + "pool." + id.key() + ".max-size", Integer.class, defaultMax));
        // Hikari는 minimumIdle을 안 주면 최대 크기와 같게 잡아 세션을 늘 최대로 열어 둔다
        c.setMinimumIdle(1);
        // 저장소가 죽었을 때 공용 @Scheduled 스레드(2개)를 30초씩 붙잡지 않도록 짧게
        c.setConnectionTimeout(3000);
        c.setValidationTimeout(2000);
        c.setMaxLifetime(1800000);
        c.setKeepaliveTime(300000);
        // 접속하지 못하면 기동을 멈춘다(자동으로 H2로 넘어가지 않는다 - 데이터가 두 곳으로 갈린다)
        c.setInitializationFailTimeout(1);
        c.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(env.getProperty(PREFIX + "connect-timeout-ms", Integer.class, 3000)));
        c.addDataSourceProperty("oracle.jdbc.ReadTimeout", String.valueOf(env.getProperty(PREFIX + "read-timeout-ms", Integer.class, 60000)));
        // DBA가 v$session에서 이 앱의 저장소 세션을 구분할 수 있게
        c.addDataSourceProperty("v$session.program", "DBAgent-Store");
        c.addDataSourceProperty("oracle.jdbc.defaultLobPrefetchSize", "32000");
        try {
            HikariDataSource ds = new HikariDataSource(c);
            log.info("내부 저장소 {}: Oracle 풀 {} 접속 성공 (url={}, user={}, 최대 {}개)", id.key(), c.getPoolName(), maskUrl(url), username,
                    c.getMaximumPoolSize());
            return ds;
        } catch (RuntimeException e) {
            log.error("내부 저장소(Oracle)에 접속하지 못해 기동을 멈춥니다: url={}, user={}. conf/dbagent.conf의 dbagent.store.oracle.* 값과 " +
                    "DB 상태를 확인하십시오. 기존 H2 저장소로 띄우려면 dbagent.store.type을 h2로 바꾸거나 그 줄을 지우면 됩니다.", maskUrl(url), username);
            throw e;
        }
    }

    /**
     * 비밀번호를 읽는다. 값이 없으면 접속을 시도하지 않고 멈춘다 - 빈 비밀번호로 로그인을 시도해 봐야 실패뿐이고,
     * "평문으로 적혀 있습니다" 경고가 먼저 나가 원인을 헷갈리게 한다.
     *
     * <p>Environment는 값 안의 {@code ${...}}를 다른 설정 참조로 풀려고 하고, 풀지 못하면 예외 문구에 값 전체(비밀번호)를 싣는다.
     * 그 예외를 그대로 올리면 기동 로그에 비밀번호가 남으므로 원인 예외를 붙이지 않고 다시 던진다.</p>
     */
    private static String readPassword(Environment env) {
        String v;
        try {
            v = env.getProperty(PREFIX + "password");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX + "password 값을 읽지 못했습니다 - 값에 ${...} 모양이 있으면 설정 참조로 해석됩니다" +
                    "(conf/dbagent.conf). 비밀번호 자체는 로그에 남기지 않습니다.");
        }
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("dbagent.store.type=oracle인데 " + PREFIX + "password 값이 없습니다(conf/dbagent.conf).");
        }
        return v;
    }

    /**
     * 로그·화면에 내보낼 접속 주소. {@code jdbc:oracle:thin:계정/비밀번호@...}처럼 주소에 계정 정보를 넣는 표기를 쓰면
     * 비밀번호가 그대로 보이므로 {@code @} 앞부분을 가린다. 계정 정보가 없는 보통 주소는 그대로 돌려준다.
     */
    public static String maskUrl(String url) {
        if (url == null) return null;
        return url.replaceFirst("^(jdbc:oracle:[^:@]+:)[^@]+@", "$1****@");
    }

    private static String required(Environment env, String key) {
        String v = env.getProperty(PREFIX + key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException("dbagent.store.type=oracle인데 " + PREFIX + key + " 값이 없습니다(conf/dbagent.conf).");
        }
        return v.trim();
    }
}

# DBAgent-Java 구현 현황

Oracle DB 모니터링/AI-DBA 도구의 Java·Spring Boot 포팅 버전. 기존 Python(`api_server.py` 등) 구현을 1:1에 가깝게 이식한 사내(한국특허정보원) 내부 도구.

## 1. 기술 스택

| 구분 | 내용 |
|---|---|
| 언어/런타임 | Java 17 |
| 프레임워크 | Spring Boot 3.3.5 (spring-boot-starter-web, -jdbc) |
| 대상 DB 드라이버 | Oracle JDBC (`ojdbc11`, 23.5.0.24.07) |
| 내부 저장소 | SQLite (`sqlite-jdbc`) — 로그인 계정(`users.db`), AI DBA 에러 사전(`oracle_errors.db`) |
| 커넥션 풀 | HikariCP (Oracle 대상 DB별로 개별 풀) |
| 비밀번호 해시 | spring-security-crypto (BCrypt) |
| 프런트엔드 | 정적 HTML/CSS/JS (`src/main/resources/static`) — SPA 형태, 프레임워크 없이 Vanilla JS |
| 빌드 | Maven (`spring-boot-maven-plugin`, fat jar) |
| 배포 | `dist/` — jlink 커스텀 JRE(Java 17) + fat jar + start/stop 스크립트 (Windows, 폐쇄망 대응) |

## 2. 패키지 구조 (`src/main/java/com/dbagent`)

```
DbAgentApplication.java        # Spring Boot 진입점

auth/                          # 로그인/세션 인증
  AuthController.java          # POST /api/login, /api/check-auth, /api/change-password
  AuthService.java             # SQLite users.db, BCrypt, 토큰 기반 세션(재시작 시 전체 만료)
  LoginRequest / TokenRequest / ChangePasswordRequest.java

oracle/                        # 대상 Oracle DB 설정·연결 관리
  DatabaseConfigService.java   # databases.json 파싱, db_id → 접속정보 resolve, oracle.env 폴백
  OracleConnectionPoolManager.java  # db_id별 HikariCP 풀 lazy 생성 + 장애 쿨다운(10s)
  TnsAdminInitializer.java     # oracle.env의 ORACLE_HOME → tnsnames.ora 경로 자동 유추
  TargetDbConfig.java          # 접속 정보 레코드
  ConfigController.java        # GET /api/config (비밀번호 제외 groups/instances + polling 주기)
  PoolTestController.java      # GET /api/pool/test (연결 풀 동작 확인용)

monitor/                       # 실제 모니터링 기능 (Oracle 조회 로직)
  MonitorController.java       # REST 엔드포인트 모음 (§4 참고)
  MonitorService.java          # 각 화면별 SQL 조회/가공 로직
  OracleQueryHelper.java       # 공통 쿼리 실행 헬퍼
  KillSessionRequest.java

aidba/                         # AI DBA (RAG-lite 챗봇)
  AiDbaController.java         # GET /api/aidba/error_search, POST /api/aidba/chat
  ErrorSearchService.java      # ORA-코드 정규식 매칭 + 키워드 LIKE 검색 (SQLite oracle_errors.db)
  OllamaChatService.java       # 로컬 Ollama 호출 (chat API, 구버전 대비 generate API 폴백)
  ChatRequest.java
```

## 3. 프런트엔드 (`src/main/resources/static`)

- `index.html`: 로그인 화면 + 좌측 사이드바(DB 그룹/인스턴스 목록, `#db-groups-container`에 JS가 동적 삽입) + 상단 탭 내비게이션
- `app.js`: 인증 확인 → `/api/config` 로드해 사이드바 트리 구성 → 탭 전환 시 해당 `/api/*` 폴링(기본 2초, `dbagent.ui.polling-interval-ms`) → Chart.js/Mermaid로 시각화
- `style.css`: 다크 테마, flex 기반 레이아웃
- `lib/`: 외부 의존성 로컬 번들 (chart.js, mermaid.min.js, lucide.js) — 폐쇄망에서 CDN 없이 동작

**탭(상단 nav) 구성:**
| 탭 | 대상 화면/기능 |
|---|---|
| Dashboard | 인스턴스/리스너 상태, 대시보드 통계, Top Events |
| Current Session | 현재 세션 목록, SID/SQL_ID 조회, 세션 Kill |
| 성능 이력 조회 | 기간별 세션/Top 세션 이력 (`history_sessions`, `history_top_sessions`) |
| Lock Holder/Waiter Tree | TM Lock 트리 (`/api/tmlock`) |
| 테이블 스페이스 조회 | Tablespace 사용률 (`/api/tablespace`) |
| Table Parent/Child 관계 | ERD 스키마(`/api/erd/schema`), FK 관계 그래프(`/api/relation`) |
| AI DBA | ORA 에러 코드 검색 + Ollama 기반 챗봇 |

## 4. REST API 엔드포인트

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/login` | 로그인, 토큰 발급 |
| POST | `/api/check-auth` | 토큰 유효성 확인 |
| POST | `/api/change-password` | 비밀번호 변경 |
| GET | `/api/config` | DB 그룹/인스턴스 목록(비밀번호 제외) + polling 주기 |
| GET | `/api/pool/test?db_id=` | 대상 DB 연결 풀 동작 테스트 |
| GET | `/api/dashboard?db_id=` | 대시보드 통계 |
| GET | `/api/health?db_id=` | 인스턴스/리스너 헬스체크 |
| GET | `/api/session?db_id=` | 현재 세션 목록 |
| GET | `/api/session_query?db_id=&sid=|sql_id=` | 특정 세션/SQL 상세 |
| POST | `/api/kill_session?db_id=` | 세션 강제 종료 |
| GET | `/api/tmlock?db_id=` | Lock Holder/Waiter 트리 |
| GET | `/api/tablespace?db_id=` | 테이블스페이스 사용률 |
| GET | `/api/top_events?db_id=` | Top Wait Events |
| GET | `/api/relation?db_id=&table_name=&direction=` | 테이블 부모/자식 관계 |
| GET | `/api/erd/schema?db_id=&owner=&prefix=` | 스키마 ERD 정보 |
| GET | `/api/table_info?db_id=&table_name=` | 테이블 상세 정보 |
| GET | `/api/failure_prob?db_id=` | 장애 확률(추정) |
| GET | `/api/history_sessions?db_id=&start_time=&end_time=&users=` | 기간별 세션 이력 |
| GET | `/api/history_top_sessions?db_id=&...` | 기간별 Top 세션 이력 |
| GET | `/api/db_users?db_id=` | DB 사용자 목록 |
| GET | `/api/aidba/error_search?code=` | ORA 에러 코드 사전 검색 |
| POST | `/api/aidba/chat` | AI DBA 챗봇 (RAG-lite + Ollama) |

## 5. 설정 파일

| 파일 | 역할 | 위치 |
|---|---|---|
| `application.properties` | 서버 포트(8005), 폴링 주기, 커넥션 타임아웃, 풀 크기 기본값, Ollama 설정 등 | `src/main/resources/` (jar 옆에 두면 외부 오버라이드 가능) |
| `databases.json` | 모니터링 대상 Oracle DB 그룹/인스턴스 목록 (id/host/port/sid/user/password 등) | 앱 작업 디렉터리 |
| `oracle.env` | `databases.json`에 없을 때의 기본(fallback) 접속정보 + `ORACLE_HOME` (tnsnames.ora 경로 유추용) | 앱 작업 디렉터리 |
| `users.db` | 로그인 계정 (SQLite, 최초 기동 시 `admin/admin` 자동 생성) | 앱 작업 디렉터리 |
| `data/oracle_errors.db` | AI DBA용 ORA 에러코드 사전 (SQLite) | `data/` |

- `databases.json`의 `password`는 `B64(...)` 형식이면 Base64 디코딩, 아니면 평문 그대로 사용 (암호화 아님, 단순 난독화).
- `host`가 빈 값이면 `sid`를 TNS 별칭으로 취급해 `tnsnames.ora`를 조회(RAC 등 대응).

## 6. 핵심 동작 특이사항

- **커넥션 풀**: `(db_id, user, dsn, SYSDBA 여부)` 조합별로 HikariCP 풀을 지연 생성. 연결 실패 시 10초 쿨다운을 걸어 죽은 DB에 반복 접속 시도하지 않음(단, 풀 과부하로 인한 타임아웃은 쿨다운 대상에서 제외).
- **SYS 계정**: `user`가 `SYS`(대소문자 무관)이면 `internal_logon=sysdba`로 접속.
- **인증**: 토큰은 서버 재시작 시 전부 무효화(재로그인 필요). SQLite는 동시 쓰기에 취약해 HikariCP 풀 크기를 1로 제한.
- **AI DBA**: 임베딩/벡터DB 없이 정규식(ORA-코드) + 키워드 LIKE 검색으로 컨텍스트를 추출한 뒤 로컬 Ollama(`qwen2.5:3b` 기본)에 전달하는 경량 RAG 구조.
- **폐쇄망 배포**: `dist/` 하위에 jlink로 만든 최소 JRE(`runtime/`)와 fat jar가 함께 배포되어 별도 JDK/인터넷 설치 없이 실행 가능. `jar.exe` 등 풀 JDK 도구는 포함되어 있지 않음.

## 7. 실행/배포

```
개발 실행:  mvn spring-boot:run  (또는 start.ps1 — jar 없으면 자동 빌드)
빌드:       mvn -DskipTests package  → target\dbagent-java-0.1.0.jar
배포:       dist\ 폴더(런타임+jar+스크립트) 전체를 대상 서버로 복사 후 start.bat 실행
중지:       stop.bat / stop.ps1 (PID 파일 기반)
```

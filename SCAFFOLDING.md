# DBAgent-Java 스캐폴딩 작업 정리 (2026-08-12)

Python으로 구현된 `DBAgent`(Flask + oracledb)를 Java/Spring Boot로 이식한 작업 기록입니다.

## 1. 개발 환경 구성

- **JDK**: Eclipse Temurin 17 (`C:\Java\jdk-17.0.20+8`) — winget 설치가 인증서 문제로 실패해서 Adoptium 릴리스를 직접 다운로드해서 압축 해제하는 방식으로 설치
- **Maven**: Apache Maven 3.9.16 (`C:\Java\apache-maven-3.9.16`) — 동일한 방식으로 설치
- 설치 과정에서 이 PC의 Windows 루트 인증서 저장소가 오래돼 `*.githubusercontent.com` 계열 인증서를 신뢰하지 못하는 문제가 있었음 → `certutil -generateSSTFromWU`로 최신 루트 인증서를 받아 신뢰 저장소에 반영해서 해결
- `JAVA_HOME`, `MAVEN_HOME`을 사용자 환경변수로 등록

## 2. 프로젝트 스캐폴딩

- 빌드 도구: **Maven**, 프레임워크: **Spring Boot 3.3.5**, Java 17
- 위치: `C:\AI-PROJECTS\DBAgent-Java` (Python 원본 `DBAgent`와 완전히 분리된 별도 디렉토리)
- `spring-boot-maven-plugin`으로 **Tomcat이 내장된 단일 실행 jar**로 패키징 (별도 Tomcat 설치 불필요)

### 주요 의존성 (`pom.xml`)
| 의존성 | 용도 (Python 대응) |
|---|---|
| `spring-boot-starter-web` | REST API (Flask 대체) |
| `spring-boot-starter-jdbc` | JdbcTemplate/HikariCP |
| `com.oracle.database.jdbc:ojdbc11` | Oracle 접속 (`oracledb` 대체) — **thin 드라이버만으로 11g~19c 전부 지원**, Python처럼 thick 모드/Oracle Client 설치 불필요 |
| `org.xerial:sqlite-jdbc` | `users.db`, `oracle_errors.db` (Python `sqlite3` 대체) |
| `spring-security-crypto` | 비밀번호 해시 (BCrypt, Python `werkzeug.security` 대체) |

### 패키지 구조
```
com.dbagent
├── DbAgentApplication.java        진입점
├── auth/          로그인, 인증, 비밀번호 변경 (SQLite users.db)
├── oracle/        DB 접속 설정, 커넥션 풀 매니저, TNS 설정
├── monitor/       세션/락/테이블스페이스 등 오라클 모니터링 (16개 라우트)
└── aidba/         정규식+SQLite 검색 + Ollama 기반 AI DBA 챗봇
```

## 3. 이식된 API (`api_server.py` 21개 라우트 전부)

| 그룹 | 라우트 |
|---|---|
| 인증 | `/api/login`, `/api/check-auth`, `/api/change-password` |
| 설정 | `/api/config` |
| 모니터링 | `/api/tmlock`, `/api/session`, `/api/tablespace`, `/api/dashboard`, `/api/top_events`, `/api/kill_session`, `/api/relation`, `/api/session_query`, `/api/table_info`, `/api/failure_prob`, `/api/history_sessions`, `/api/history_top_sessions`, `/api/health`, `/api/db_users`, `/api/erd/schema` |
| AI DBA | `/api/aidba/error_search`, `/api/aidba/chat` (정규식 + SQLite 검색 → Ollama 로컬 LLM 호출, 임베딩/벡터DB 없는 경량 RAG) |

프론트엔드(`web/`)는 원본을 그대로 복사해서 `src/main/resources/static`에 배치. `app.js`의 API 호출이 예전 Python 구조(정적 서버 8000 + API 서버 8001 분리)의 흔적으로 `:8001`에 하드코딩돼 있던 것을 상대경로(`/api/...`)로 수정해서 하나의 포트(8005)로 통합 서빙되도록 변경.

## 4. 발견하고 고친 버그

### 4.1 커넥션 풀 전역 락 문제
`OracleConnectionPoolManager`가 모든 DB의 풀 생성을 **단일 공용 락**으로 처리하고 있어서, 죽은 DB에 접속을 시도하는 동안 완전히 무관한 다른 DB의 접속까지 같이 멈추는 구조였음. → DB(pool key)별로 락을 분리(`ConcurrentHashMap<String, ReentrantLock>`).

### 4.2 예외 타입 불일치로 인한 미처리 500 에러
HikariCP가 커넥션 실패 시 `RuntimeException`을 던지는데, 컨트롤러들은 `catch (SQLException)`만 하고 있어서 그대로 새어나가 500 미처리 오류가 발생. → `SQLException`으로 일관되게 래핑.

### 4.3 오류 DB 감지 속도 (1초 이내 요구사항)
HikariCP의 풀 초기화(`initializationFailTimeout`)가 실패 시 내부적으로 ~1초 슬립 후 재시도하는 구조라 설정한 타임아웃의 2~4배가 걸림. → 풀 생성은 즉시 반환(`initializationFailTimeout=-1`, 백그라운드 초기화)하고, 실제 접속/실패는 `getConnection()` 호출 한 번에서만 일어나도록 구조 변경. 타임아웃 500ms 설정으로 실제 감지 600~700ms 달성. (`dbagent.oracle.connect-timeout-ms`로 조정 가능)

### 4.4 헬스체크 상태 오분류
`/api/health`가 예외 분류 시 HikariCP의 최상위 타임아웃 메시지만 보고 판단해서 실제 Oracle 에러(ORA- 코드, cause 체인 안에 있음)를 못 찾고 "리스너 Alive"로 잘못 표시됨. → cause 체인을 끝까지 훑어 실제 ORA- 코드로 분류, 확인 안 되는 경우는 인스턴스/리스너 둘 다 Not Alive로 보수적으로 처리.

### 4.5 프론트엔드 — DB 전환 시 이전 DB 데이터 잔상
대시보드의 5개 폴링 함수(수치/상태/락/세션/이벤트)가 단일 boolean 플래그로 중복 호출을 막고 있어서, 느린 DB 요청 중 새 DB로 전환하면 새 요청이 막히고 나중에 도착한 옛 응답이 화면에 그대로 반영됨. → DB별로 진행 상태 추적, 응답 도착 시 `window.currentDbId`와 비교해 다르면 폐기. 추가로 DB 클릭 즉시(응답 기다리지 않고) 화면을 전부 초기화하도록 강화.

### 4.6 (근본 원인) `db_id` 쿼리 파라미터 중복 → 항상 SYS 기본값으로 폴백
`app.js`의 거의 모든 API 호출에 `db_id`가 두 번 중복(`?db_id=X&db_id=X`, Python 8000/8001 분리 구조 시절 잔재)돼 있었음. Spring이 중복 파라미터를 `String`에 바인딩할 때 콤마로 합쳐(`"X,X"`) `databases.json`의 어떤 id와도 매칭이 안 되고 **조용히 SYS/oracle.env 기본값(ORCL)으로 폴백**되고 있었음. 통합DB #1이 "정상"으로 보인 건 우연히 그 폴백 대상과 같은 DB였기 때문. → `app.js`의 중복 파라미터 전부 제거 + `DatabaseConfigService`에 콤마 포함 시 첫 값만 사용하는 방어 로직 추가. **헤드리스 브라우저(Edge + CDP)로 실제 클릭을 자동화 재현해서 발견/검증.**

## 5. 실제 Oracle 19c 환경 검증

- 이 PC에 이미 설치된 Oracle 19c(싱글 인스턴스)에 thin 드라이버로 SYSDBA 접속 포함 정상 접속 확인
- `tnsnames.ora` 별칭(`ORCL`, `ORCL2`) 기반 접속 확인 (`oracle.net.tns_admin` 시스템 프로퍼티로 배선)
- 실 데이터 기준 `session`, `tablespace`, `erd/schema`(KIPOADM 스키마 수백 개 테이블+FK), `session_query`(SQL 텍스트/실행계획/바인드 캡처) 등 전부 정상 동작 확인
- 브라우저 탭을 반복적으로 열고 닫아도 서버 프로세스/응답속도에 영향 없음을 스트레스 테스트로 확인

## 6. 운영 스크립트

- `start.ps1` / `start.bat`: jar 없으면 `mvn package`로 빌드 → `javaw.exe`로 백그라운드(콘솔 없이) 기동 → PID를 `dbagent-java.pid`에 기록 → 포트 열릴 때까지 대기
- `stop.ps1` / `stop.bat`: PID 파일 기반 종료, 파일이 없거나 무효하면 포트(8005) 점유 프로세스를 찾아 종료
- 로그는 `dbagent-java.out.log` / `dbagent-java.err.log`로 분리 기록
- **JDK 17을 프로젝트 폴더 안(`jdk17/`)에 통째로 동봉**: `start.ps1`이 시스템 `JAVA_HOME`/`PATH`보다 이 폴더를 항상 최우선으로 사용하도록 되어 있음 → 대상 서버에 다른 버전의 Java(예: 1.6)가 이미 설치되어 있어도 전혀 건드리지 않고, 이 Agent만 격리된 Java 17로 기동됨. 시스템 Java 설정 변경이나 관리자 권한 설치가 전혀 필요 없음.

## 7. 폐쇄망 배포 시 필요한 것

- **Tomcat 별도 설치 불필요** (jar에 내장)
- **별도 Java 설치도 불필요** (`jdk17/` 폴더가 프로젝트 안에 동봉되어 있어 폴더째로 복사하면 끝)
- 필요: `DBAgent-Java` 폴더 전체(또는 최소 `jdk17/`, `target\dbagent-java-0.1.0.jar`, `databases.json`, `oracle.env`, `data/oracle_errors.db`, 운영 스크립트)
- **빌드는 인터넷 되는 환경에서 하고 결과물만 이관** (`mvn package`는 Maven Central 접속 필요, 폐쇄망에서 불가)
- `dbagent.oracle.tns-admin` 경로는 폐쇄망 서버의 실제 `ORACLE_HOME` 경로에 맞게 조정 필요 (jar 재빌드 없이 외부 `application.properties` 오버라이드 또는 실행 인자로 가능)
- AI DBA 챗봇(`/api/aidba/chat`)은 Ollama + `qwen2.5:3b` 모델이 폐쇄망에도 별도로 설치되어 있어야 동작 (모델 파일 직접 이관 필요, `ollama pull` 불가)
- 11g~19c 전 버전, RAC 포함 — Java thin 드라이버 + tnsnames.ora 별칭 방식으로 별도 코드 없이 대응 가능 (Python의 thick 모드 강제 문제가 Java에는 없음)

## 8. 남은 작업 / 참고

- `kill_session`은 로직만 검증했고 실제 운영 DB의 세션을 종료하는 테스트는 진행하지 않음 — 사용자 환경에서 직접 확인 필요
- 11g 인스턴스로의 실접속은 아직 미검증 (19c만 실제 검증됨)
- 비밀번호 해시는 Java 쪽이 BCrypt(별도 `users.db`)를 사용해 Python 쪽과 호환되지 않음 (의도된 설계, 데이터 공유 안 함)

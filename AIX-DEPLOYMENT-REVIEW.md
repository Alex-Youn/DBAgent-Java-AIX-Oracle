# DBAgent-Java → IBM AIX 이관 검토 (2026-08-16)

Windows에서 개발된 DBAgent-Java(Spring Boot 3.3.5)를 IBM AIX 7.2 서버로 이관하는 방안 검토 기록입니다.
대상 서버에는 이미 여러 모니터링 프로그램이 운영 중이라, **기존 시스템 Java/환경을 건드리지 않는 격리 배포**가 전제입니다.

## 1. 현재 프로젝트 구조

- Spring Boot 3.3.5, Java 17, `spring-boot-maven-plugin`으로 **Tomcat 내장 실행 jar**로 패키징 (별도 Tomcat 설치 불필요)
- "톰캣에 올린다"는 표현과 달리, 이미 자체적으로 Tomcat을 포함 → `java -jar`만으로 기동
- 외부 Tomcat에 반드시 올려야 하는 조직 정책이 있다면 `packaging=war` + `SpringBootServletInitializer` 상속으로 전환 가능 (지금 구조에선 불필요)
- JDK 17을 프로젝트 폴더(`jdk17/`) 안에 통째로 동봉해서, 대상 서버의 시스템 Java를 전혀 건드리지 않고 격리 구동하는 방식 채택 중 (Windows 기준)

## 2. AI-DBA 메뉴 중 Ollama 제외 관련

AI-DBA는 두 부분으로 나뉨:

| 기능 | 구현 | AIX 이관 |
|---|---|---|
| 에러코드 검색 (`error_search`) | 정규식 + SQLite 조회 (`oracle_errors.db`) | 유지 대상 |
| 챗봇 답변 생성 (`chat`) | 위 결과 + **Ollama** 로컬 LLM 호출 | 제외 대상 (Ollama가 AIX 미지원) |

→ Ollama 관련(`OllamaChatService.java`, `/api/aidba/chat` 엔드포인트)만 제거하고 `error_search`는 유지.

## 3. 진짜 블로커: SQLite JDBC의 AIX 네이티브 라이브러리 부재

`org.xerial:sqlite-jdbc`는 JNI 방식으로 플랫폼별 네이티브 `.so`/`.dll`을 jar 안에 내장. 실제 jar(`sqlite-jdbc-3.46.1.3.jar`)를 열어 확인한 결과:

```
Linux/{x86,x86_64,arm,arm64,ppc64,riscv64}, Linux-Musl, Linux-Android,
FreeBSD/{x86,x86_64,aarch64}, Mac/{x86_64,aarch64}, Windows/{x86,x86_64,arm}
```

**AIX용 빌드는 없음.** `Linux/ppc64`가 있어도 AIX는 별개 OS라 호환 안 됨 → 그대로 올리면 `UnsatisfiedLinkError`.

이게 걸리는 곳은 AI-DBA뿐 아니라 **로그인/인증도 포함**:
- `spring.datasource.url=jdbc:sqlite:users.db` (메인 datasource, 로그인)
- `aidba.errors-db-path=data/oracle_errors.db` (에러 검색)

→ Ollama만 빼는 걸로는 부족하고, **SQLite를 순수 Java DB(H2 등)로 교체하는 게 사실상 필수**.

- `ojdbc11`(Oracle JDBC)은 순수 Java thin 드라이버라 네이티브 코드 없음 → AIX에서 문제없음.

## 4. 필요 작업 목록

1. **JDK**: `jdk17/`(Windows 바이너리) 대신 AIX용 JDK를 별도 준비해서 동봉 (아래 5번 참고)
2. **SQLite → H2 마이그레이션** (사실상 필수)
   - `pom.xml` 의존성 교체 (`org.xerial:sqlite-jdbc` → `com.h2database:h2`)
   - `application.properties`의 datasource URL/driver 변경
   - `ErrorSearchService`의 `jdbc:sqlite:` URL을 H2 URL로 변경
   - 기존 SQLite 데이터(`users`, `error_dictionary` 테이블) 1회성 export/import
   - ⚠️ **Java 8로 갈 경우 주의**: H2 2.x는 Java 11+ 필요 → Java 8이면 마지막 8 지원 버전인 **H2 1.4.200**(유지보수 종료)으로 고정해야 함
3. **Ollama 챗봇 제거**: `OllamaChatService.java`, `/api/aidba/chat` 엔드포인트, 프론트엔드 챗봇 UI 비활성화
4. **운영 스크립트**: `start.ps1/bat`, `stop.ps1/bat` (PowerShell/cmd) → `start.sh`/`stop.sh` (ksh/bash)로 새로 작성 (PID 파일 관리, 포트 대기 로직은 그대로 이식)
5. **`oracle.env`의 `ORACLE_HOME`**: AIX 서버 실제 경로로 수정. 코드(`TnsAdminInitializer`)는 `File.separator` 사용이라 OS별 경로 처리는 이미 문제없음
6. (참고) `oracle.env`에 SYS 비밀번호가 평문 저장 — 서버 이관 시 파일 권한/관리 방식 점검 권장

## 5. JDK 버전 선택: 17 vs 8

### AIX용 JDK 배포 형태
- 전통적인 "IBM SDK, Java Technology Edition"은 **Java 8이 마지막 버전**. 11 이후는 이 제품명 자체가 없어지고 **IBM Semeru Runtime**(OpenJ9 기반)으로 전환됨 → **17은 Semeru 선택지 하나뿐**.
- Semeru는 `ibm-semeru-certified-jdk_ppc64_aix_17.0.xx.0.tar.gz` 형태로 배포 (Windows `jdk17/`와 동일하게 압축 풀어서 동봉 가능)
- ⚠️ **Adoptium/Temurin(HotSpot 기반) 빌드는 AIX 미지원** — 실제로 AIX 7.2에서 Temurin 17 기동 시도 시 `libjvm.so` 로드 실패 사례 있음 (adoptium-support #847). **반드시 IBM Semeru(OpenJ9)만 사용.**

### XL C++ 런타임 요구사항 비교

| 배포판 | XL C++ 최소 버전 |
|---|---|
| IBM Semeru Runtime 8/17 | **16.1.0.10** 이상 |
| IBM SDK Java Technology Edition 8 (전통, 2030-12-31까지 IBM 지원) | **13.1.0.1** 이상 |

- XL C++는 OS 공용 라이브러리라 JDK처럼 앱 폴더 안에 격리 동봉이 안 됨 → 시스템 전역에 실제로 설치/업그레이드해야 함
- 다만 XL C++ 런타임 업그레이드 자체는 보통 **추가 설치**라 기존 프로그램을 깨뜨리지 않는 경우가 많음 (운영팀 검증은 필요)
- 17로 가면 XL C++ 16.1 요구는 피할 수 없음 (Semeru 8/17 공통)

### Java 8 다운그레이드 시 코드 변경 범위 (실측)

Spring Boot 3.x는 Java 17 강제라 8을 쓰려면 **Spring Boot 2.7.x로 다운그레이드** 필요 (단, 2.7은 이미 EOL — 신규 운영에는 비권장 요소).

| 항목 | 해당 파일 | 내용 |
|---|---|---|
| jakarta.* → javax.* | `AuthService`, `DatabaseConfigService`, `TnsAdminInitializer` (3개) | Spring Boot 3의 Jakarta EE 9 네임스페이스를 2.x의 javax로 되돌림 |
| record 클래스 | `ChatRequest`, `LoginRequest`, `TokenRequest`, `ChangePasswordRequest`, `KillSessionRequest`, `TargetDbConfig` (6개) | Java 16+ 문법 → 평범한 클래스로 재작성 |
| `List.of`/`Map.of`/`Set.of` | `AiDbaController`, `ErrorSearchService`, `AuthController`, `MonitorController`, `MonitorService`, `PoolTestController` (6개) | Java 9+ 문법 → `Collections.unmodifiableMap` 등으로 교체 |
| `var` 타입 추론 | `AuthService.java:63` (1곳) | Java 10+ 문법 → 명시적 타입 |
| `java.net.http.HttpClient` | `OllamaChatService.java` | Java 11+ 전용 API. 단, Ollama 기능 자체를 제거하므로 파일 삭제로 자동 해결 |
| Oracle 드라이버 | `pom.xml` | `ojdbc11`(11+ 전용) → `ojdbc8`로 교체 |
| H2 버전 | `pom.xml` | 2.x는 Java 11+ 필요 → **H2 1.4.200**으로 고정 |

→ 약 10개 파일 + `pom.xml` 수준, 기계적 작업이라 리라이트 수준은 아님. 다만 **Spring Boot 2.7 EOL** 문제가 남음.

### 결론: Java 8 + Spring Boot 2.7로 확정 (2026-08-21)

**서버가 정부 관리 센터 소속이라 OS 레벨 설치/업그레이드를 우리 쪽에서 임의로 할 수 없음.** 이 제약이 결정적이라 아래처럼 확정한다:

- AIX 서버에 실제 설치된 XL C++는 `16.1.0.3`. Semeru 17이 요구하는 버전은 **16.1.0.10 이상**이라, `16.1.0.3`은 메이저.마이너(16.1)만 맞을 뿐 Fix Pack 레벨에서 미달 → **Semeru 17은 지금 상태로 기동조차 안 될 가능성이 높음** (XL C++ 미달 시 `java -version` 단계에서 `libc++` 관련 에러로 실패하는 사례가 IBM 자체 문서에도 있음)
- XL C++를 16.1.0.10 이상으로 올리는 건 OS 공용 라이브러리 업그레이드라 센터의 설치 프로세스를 거쳐야 함 → **우리 통제 밖, 사실상 불가**
- 반면 Java 8(전통 IBM SDK)이 요구하는 XL C++는 `13.1.0.1 이상`으로, 지금 설치된 `16.1.0.3`이 이미 넉넉히 충족 → **센터에 아무것도 요청할 필요 없음**
- 게다가 이 서버에서 지금 실제로 도는 `java -version`이 이미 IBM J9/OpenJ9 기반 Java 8(`1.8.0_251`, SR6 FP10)이라, **이미 검증된 조합**이기도 함
- JDK를 프로젝트 폴더 안에 압축 해제해서 동봉하는 것(`jdk17/`과 동일한 방식)은 OS 패키지 설치가 아니라 파일 복사이므로, 이 제약과 무관하게 우리 쪽에서 그대로 진행 가능

→ **Java 8 + Spring Boot 2.7 다운그레이드로 확정.** 4번 "필요 작업 목록"과 위 "Java 8 다운그레이드 시 코드 변경 범위" 표대로 진행. Spring Boot 2.7 EOL / H2 1.4.200 EOL은 감수해야 하는 기술부채로 인지하고 감.

<details>
<summary>참고: 확인에 사용한 실측 명령어/결과 (AIX 서버 직접 조회)</summary>

  ```
  lslpp -l | grep -i xlC        # XL C++ 런타임 현재 버전
   결과 : ORAKPMS:[orarac@kpoadm:/goracle]$ lslpp -l | grep -i xlC
           xlC.aix61.rte             16.1.0.3  COMMITTED  IBM XL C++ Runtime for AIX 6.1
           xlC.cpp                    9.0.0.0  COMMITTED  C for AIX Preprocessor
           xlC.msg.en_US.cpp          9.0.0.0  COMMITTED  C for AIX Preprocessor
           xlC.msg.en_US.rte         16.1.0.3  COMMITTED  IBM XL C++ Runtime
           xlC.rte                   16.1.0.3  COMMITTED  IBM XL C++ Runtime for AIX
           xlC.sup.aix50.rte          9.0.0.1  COMMITTED  XL C/C++ Runtime for AIX 5.2

  
  lslpp -l | grep -i java       # 이미 설치된 Java들 (다른 모니터링 도구가 쓰는 것 포함)
   결과 : ORAKPMS:[orarac@kpoadm:/goracle]$ lslpp -l | grep -i java
           Java6.samples.demo       6.0.0.645  COMMITTED  Java SDK 32-bit demo Samples
           Java6.samples.jnlp       6.0.0.645  COMMITTED  Java SDK 32-bit jnlp Samples
           Java6.sdk                6.0.0.645  COMMITTED  Java SDK 32-bit
           Java6.source             6.0.0.645  COMMITTED  Java SDK 32-bit Source
           Java6_64.samples.demo    6.0.0.645  COMMITTED  Java SDK 64-bit Demo Samples
           Java6_64.samples.jnlp    6.0.0.645  COMMITTED  Java SDK 64-bit jnlp Samples
           Java6_64.sdk             6.0.0.645  COMMITTED  Java SDK 64-bit
           Java6_64.source          6.0.0.645  COMMITTED  Java SDK 64-bit Source
           Java8_64.jre             8.0.0.605  COMMITTED  Java SDK 64-bit Java Runtime
           Java8_64.msg.ko_KR         8.0.0.0  COMMITTED  Java SDK 64-bit
           Java8_64.sdk             8.0.0.605  COMMITTED  Java SDK 64-bit Development
           SYMCnbjava                 7.7.3.0  COMMITTED  NetBackup Java Console Fileset
           SYMCnbjre                  7.7.3.0  COMMITTED  NetBackup Java JRE Fileset
           VRTSnbjava                 8.0.0.0  COMMITTED  NetBackup Java Console Fileset
           VRTSnbjre                  8.0.0.0  COMMITTED  NetBackup Java JRE Fileset
                                      3.2.5.0  COMMITTED  RSCT GUI JAVA Msgs - U.S.
                                      3.2.5.0  COMMITTED  RSCT RMC JAVA Msgs - U.S.
                                      3.2.5.0  COMMITTED  RSCT GUI JAVA Msgs - Korean
                                      3.2.5.0  COMMITTED  RSCT RMC JAVA Msgs - Korean
           Java6.sdk                6.0.0.645  COMMITTED  Java SDK 32-bit
           Java6_64.sdk             6.0.0.645  COMMITTED  Java SDK 64-bit
           Java8_64.jre             8.0.0.605  COMMITTED  Java SDK 64-bit Java Runtime
           SYMCnbjava                 7.7.3.0  COMMITTED  NetBackup Java Console Fileset
           SYMCnbjre                  7.7.3.0  COMMITTED  NetBackup Java JRE Fileset
           VRTSnbjava                 8.0.0.0  COMMITTED  NetBackup Java Console Fileset
           VRTSnbjre                  8.0.0.0  COMMITTED  NetBackup Java JRE Fileset

  
  which java && java -version   # 시스템 기본 java
  ```
  ORASTEL:[sfo_user@kpoadm:/home/sfo_user]$ which java
   /goracle/app/oracle/product/19.3.0/jdk/bin/java
   ORASTEL:[sfo_user@kpoadm:/home/sfo_user]$ java -version
   java version "1.8.0_251"
   Java(TM) SE Runtime Environment (build 8.0.6.10 - pap6480sr6fp10-20200408_01(SR6 FP10))
   IBM J9 VM (build 2.9, JRE 1.8.0 AIX ppc64-64-Bit Compressed References 20200402_443261 (JIT enabled, AOT enabled)
   OpenJ9   - 35ef566
   OMR      - 4bca4f4
   IBM      - 55acf4a)
   JCL - 20200407_01 based on Oracle jdk8u251-b08
  ORASTEL:[sfo_user@kpoadm:/home/sfo_user]$

  참고: `which java`가 가리키는 건 IBM이 lslpp로 관리하는 패키지가 아니라 **Oracle 19c 클라이언트에 번들된 JDK** (`/goracle/app/oracle/product/19.3.0/jdk/`). 이 서버엔 그 외에도 Java6 데모/샘플, `Java8_64.sdk`, NetBackup 번들 JRE 등 여러 Java가 공존 중이라, "다른 도구가 쓰는 검증된 조합"이 정확히 어떤 바이너리인지는 별도 확인이 필요함. 다만 위 결론에는 영향 없음 — 우리는 이 중 어느 것도 참조하지 않고 프로젝트 폴더에 독립적으로 JDK를 동봉하는 방식이라, 이 무엇이든 상관없이 XL C++ 버전 하나만 맞으면 됨.

</details>

## 6. 배포 구조 관련 Q&A

### Q. 여러 PC에서 접속해도 Oracle 커넥션 풀(min 2 ~ max 10)이 유지되나?

**Yes.** `OracleConnectionPoolManager`의 풀은 Spring 싱글톤 빈 안의 `Map<String, HikariDataSource>`로 관리되며, **AIX 서버에서 도는 JVM 프로세스 하나에 속한 전역 자원**. 풀 키는 `db_id+user+dsn+sysdba여부`로만 결정되고 클라이언트(PC)는 관여하지 않음 → 몇 대의 PC가 브라우저로 접속하든 해당 DB에 대해 최대 10개로 고정.

### Q. 지금 상태로 여러 PC에 "포팅"해서 각자 실행하면?

**커넥션 풀이 PC 수만큼 곱해짐.** 각 PC가 자기만의 JVM 프로세스(자기만의 풀)를 가지므로, PC 10대에 각자 설치해서 실행하면 최대 `10대 × 10개 = 100개`까지 Oracle 커넥션이 열릴 수 있음. Oracle `PROCESSES`/`SESSIONS` 한도에 걸릴 위험.
추가로 `users.db`(로그인)도 PC별로 따로 관리되어 세션이 공유 안 됨.

→ **결론: 앱은 AIX 서버 1대에만 배포하고, 나머지 PC들은 브라우저로 그 서버에 접속하는 구조가 맞음.** 각 PC에 앱을 통째로 복사해서 개별 실행하는 방식은 비권장.

### Q. 배포 방식에 대안이 있나? (앱을 AIX가 아닌 곳에)

- **대안: 앱은 Linux 서버/VM에, DB만 AIX에** — Oracle 접속이 네트워크 기반 thin 드라이버라 co-location 불필요. Linux면 Temurin 17을 그냥 tar.gz로 받아 쓸 수 있고, SQLite JDBC도 Linux 네이티브 빌드가 정식 존재해 H2 마이그레이션도 불필요해짐 → AIX 관련 이슈 전부 해소.
- **현재 상황상 채택 불가**: AIX에 이미 여러 모니터링 프로그램이 운영 중이라 앱도 AIX에 위치해야 하는 제약이 있음 → 이 대안은 보류.
- 이 경우 오히려 처음 방식(JDK를 프로젝트 폴더 안에 동봉해 시스템 Java와 격리)이 더 정당화됨 — 같은 서버에 있는 다른 프로그램들의 Java 의존성과 충돌 없이 독립 구동 가능. XL C++만 OS 공용이라 격리가 안 되는데, 5번 결론대로 Java 8 경로는 지금 설치된 버전(`16.1.0.3`)으로 이미 충족되므로 문제 없음.

## 7. 다음 액션 아이템

- [x] AIX 서버 `lslpp -l | grep -i xlC` / `java` 로 기존 환경 확인 → **Java 8 확정** (센터가 OS 레벨 설치를 통제하므로 XL C++ 업그레이드가 필요한 Java 17/Semeru는 배제, 5번 결론 참고)
- [x] **Spring Boot 3.3.5 → 2.7.18 다운그레이드** (완료 2026-08-21). 실제로 손댄 범위는 5번 표보다 컸음 — 전수 조사 결과 `String.isBlank()`(11개 파일 22곳), `String.strip()`(4곳), `String.stripTrailing()`(1곳, 전부 Java 11+)과 **`java.util.HexFormat`(Java 17+, 토큰 생성/BLOB 헥스 변환에 사용 중이었음)**이 원래 계획에서 누락돼 있었음 — `com.dbagent.util.{Maps,Lists,Strings}` 유틸 3개 신설해서 드롭인 교체. record 10개(외 `ChatRequest`는 삭제) → 접근자 메서드명(`token()` 등) 그대로 유지하는 일반 클래스로 전환해서 호출부 무변경. jakarta→javax(3개 파일), `var`/`instanceof` 패턴매칭 제거, `ojdbc11`→`ojdbc8`. **검증**: `<maven.compiler.release>8</maven.compiler.release>`로 JDK17 컴파일러가 Java 8 API 표면 기준 엄격 검사하도록 강제 → 컴파일 성공, 산출 클래스 파일 메이저 버전 52(Java 8) 확인, 로컬 기동 후 로그인/계정관리/SQL실행/세션Kill/에러검색 API 전부 스모크 테스트 통과.
- [ ] SQLite → H2 마이그레이션 (users.db, oracle_errors.db 7.6MB 실데이터 포함) — **H2는 1.4.200으로 고정** (2.x는 Java 11+ 필요). 다음 단계로 분리, 아직 미착수.
- [x] Ollama 챗봇 기능 제거 (완료 2026-08-21) — `OllamaChatService.java`/`ChatRequest.java` 삭제, `/api/aidba/chat` 라우트 제거(404 확인됨), 프런트 챗봇 탭 제거. `ErrorSearchService`의 RAG용 `retrieveDocs`/`extractKeywords`도 그 챗봇에서만 쓰이던 죽은 코드라 같이 정리, `/error_search`는 그대로 동작.
- [ ] AIX용 **IBM SDK, Java Technology Edition 8** tar.gz 확보 및 프로젝트 폴더 동봉 (Semeru 아님 — XL C++ 13.1.0.1만 요구)
- [ ] `start.sh`/`stop.sh` 작성 (ksh/bash)
- [ ] `oracle.env`의 `ORACLE_HOME`을 AIX 실제 경로로 수정
- [ ] (선택) `dbagent.oracle.tns-admin` 등 배포 환경별 `application.properties` 오버라이드 준비

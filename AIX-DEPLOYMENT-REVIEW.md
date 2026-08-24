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
- [x] SQLite → H2 마이그레이션 (완료 2026-08-24) — `h2` 1.4.200 고정, `spring.datasource.url=jdbc:h2:file:./users`(로그인), `aidba.errors-db-path=data/oracle_errors`(에러 사전) 전환 완료. 코드 내 SQLite 참조 전량 제거 확인.
- [x] Ollama 챗봇 기능 제거 (완료 2026-08-21) → **8번 참고, 2026-08-24 원격 GPU 서버 호출 방식으로 재복원**
- [x] AIX용 **IBM SDK, Java Technology Edition 8** 확보 및 프로젝트 폴더 동봉 (완료 2026-08-24) — `java8/` 폴더로 배치 (Semeru 아님 — XL C++ 13.1.0.1만 요구). `release` 파일 기준 `JAVA_VERSION="1.8.0_241"`, `OS_ARCH="ppc64"` 확인, `java8/bin/java`가 실제 AIX ppc64 XCOFF 실행 파일(Windows용 아님)인 것도 확인. `start-aix.sh`가 원래 `jdk8/bin/java`를 기대하던 걸 실제 폴더명(`java8/`)에 맞춰 수정, `.gitignore`에도 `java8/` 추가(274MB라 커밋 대상 아님, `jdk17/`와 동일하게 배제). 실행권한 문제는 9.2 참고.
- [x] `start.sh`/`stop.sh` 작성 (ksh/bash) → `start-aix.sh`/`stop-aix.sh` 작성 완료. PID 파일 관리, 포트 오픈 대기(최대 30초 polling), `application.properties`의 `server.port` 자동 읽기, 이 서버에 이미 떠 있는 다른 Java와 충돌 방지 위해 `java8/` 우선 사용 등 기존 `start.ps1`/`stop.ps1` 로직 이식. `stop-aix.sh`는 AIX에 `lsof` 상당 도구가 없어 PID 파일 우선 + 프로세스명 매칭을 폴백으로 사용. `java.io.tmpdir` 관련 추가 수정은 9.4 참고.
- [x] **AIX 실서버 첫 기동 및 DB 접속 확인 완료 (2026-08-24)** — 9번 전체 참고. 내부망에서 정상 기동 + 대상 Oracle DB 접속까지 확인됨 (외부망 접속은 보안정책상 차단, 예상된 정상 동작)
- [ ] `oracle.env`의 `ORACLE_HOME`을 AIX 실제 경로로 수정 — 사용자가 직접 필요 시점에 수정 예정 (아직 미확정)
- [ ] (선택) `dbagent.oracle.tns-admin` 등 배포 환경별 `application.properties` 오버라이드 준비 — 상동, 아직 미확정

## 8. Ollama 챗봇: 제거 후 원격 GPU 서버 호출 방식으로 복원 (2026-08-24)

AIX는 GPU 런타임이 없어 Ollama를 직접 못 올리므로 2026-08-21에 로컬 Ollama 챗봇 기능을 제거했었으나, **원격 GPU 서버에 Ollama를 두고 HTTP로만 호출하는 방식**으로 다시 복원함. SQLite 문제(네이티브 JNI 라이브러리)와 달리 Ollama 호출은 순수 REST 호출이라 AIX 여부와 무관 — 네트워크로 붙을 수 있는 GPU 서버만 있으면 됨.

- 제거된 원본 `OllamaChatService`는 `java.net.http.HttpClient`(Java 11+ 전용)를 썼으나, Java 8 고정 결정과 충돌 → **Spring `RestTemplate`**(spring-boot-starter-web에 이미 포함, 별도 의존성 불필요)로 재작성
- `/api/chat` 실패 시 구버전 Ollama용 `/api/generate`로 자동 폴백 (원 설계 유지)
- 메시지에서 `ORA-#####` 코드 정규식 추출 → H2 에러사전 조회 결과를 컨텍스트로 첨부하는 RAG-lite 로직 유지 (`ErrorSearchService.getErrorSolution` 재사용)
- 프런트엔드 AI DBA 탭에 "AI 챗봇 (Ollama)" 탭 UI 복원 (`index.html`/`app.js`)
- 실제 기동 후 스모크 테스트: 빈 메시지 검증 정상, Ollama 미기동 상태에서 타임아웃이 500 크래시가 아니라 깔끔한 JSON 에러로 반환되는 것까지 확인, `mvn package` 빌드 성공

**운영 시 설정** (`src/main/resources/application.properties`):

```properties
aidba.ollama.url=http://<GPU서버IP>:11434
aidba.ollama.model=qwen2.5:3b
aidba.ollama.timeout-ms=30000
```

필요 조건: GPU 서버에서 Ollama를 `OLLAMA_HOST=0.0.0.0`으로 기동(기본은 localhost만 허용이라 외부 접속 차단됨), AIX ↔ GPU 서버 간 11434 포트 방화벽 오픈.

## 9. AIX 서버 실배포 테스트 & 커넥션 풀 점검 (2026-08-24)

### 9.1 `dist-aix/` 배포 패키지 신규 생성

`dist/`는 Windows 전용(jlink Java 17 커스텀 런타임 + `.ps1`/`.bat`)이라 AIX용으로 재활용 불가 — 별도로 `dist-aix/` 폴더를 새로 만들어 `dbagent-java-0.1.0.jar`, `java8/`, `start-aix.sh`/`stop-aix.sh`, `databases.json`, `oracle.env`, `users.mv.db`, `data/oracle_errors.mv.db`를 모음. **`application.properties`는 의도적으로 제외** — 루트의 것은 Windows dev 전용 값(`server.port=8006`, `tns-admin=C:/Oracle/docker/network/Admin`)이라 그대로 넣으면 AIX에서 깨짐. AIX용 tns-admin 경로는 아직 미확정이라, 정해지면 사용자가 직접 새로 작성하기로 함. 전달용으로 `dist-aix.tar.gz`(186MB)도 생성, `.gitignore`에 `dist-aix/` 추가.

### 9.2 `java8/` 실행권한 문제 발견 및 수정

`java8/bin`, `java8/jre/bin` 아래 대부분의 실행파일(`java` 본체 포함, 일부는 예외적으로 남아있음)이 실행권한 없이(`-rw-r--r--`) 프로젝트 폴더에 놓여 있었음 — Windows로 옮겨오는 과정(압축 해제 등)에서 유닉스 실행비트가 소실된 것으로 추정. **이 환경(Windows/NTFS + git-bash)에서는 `chmod +x`를 실행해도 조용히 적용되지 않는 것도 확인함** (NTFS엔 애초에 실행비트 개념이 없어 로컬에서는 못 고침). → AIX(실제 POSIX 파일시스템)에서 압축 해제 후 `chmod -R +x java8/bin java8/jre/bin`을 실행하는 것으로 가이드, 사용자가 실기 확인 완료.

### 9.3 Windows 로컬 `dist/` 테스트 중 DB 미접속 원인 규명

사용자가 `dist\start.bat`으로 로컬 테스트했을 때 통합DB(ORCL/ORCL2)까지 접속 실패가 재현됨 → 원인은 `dist\application.properties`의 `tns-admin`이 존재하지 않는 경로(`C:/oracle_docker_data`)를 가리키고 있었고, `dist\databases.json`의 포트도 `1521`(로컬에 별도로 떠 있는 `tnslsnr.exe` 프로세스로, 실제 테스트용 Docker 컨테이너와 무관)로 낡아 있었던 것. 루트 폴더 쪽(`databases.json` 포트 `1522`=Docker 실제 매핑, `tns-admin=C:/Oracle/docker/network/Admin`)은 정상 동작 확인됨. **`dist\`는 `build.ps1 -Dist`가 jar만 동기화하고 설정 파일은 동기화하지 않아 쉽게 낡는다는 것도 확인** — 이후 로컬 확인은 `dist\`가 아니라 루트 `start.bat` 사용으로 정리.

### 9.4 AIX 서버 첫 기동 실패 → `java.io.tmpdir` 권한 문제 수정

AIX 실서버(`kpoadm`, `/SFO_REPOSIT/DBAgent/dist-aix`)에서 첫 기동 시도 시 Spring Boot가 내장 Tomcat의 임시 디렉터리를 만들지 못해 기동 실패:

```
Caused by: java.nio.file.AccessDeniedException: /goracle/tmp/tomcat.9090...
```

서버의 `TMPDIR`이 Oracle 쪽 공용 경로(`/goracle/tmp`)로 잡혀 있어 앱 실행 계정(`sfo_user`)에 쓰기 권한이 없었던 것. "다른 프로그램/공용 경로는 안 건드리고 격리 배포"라는 이 프로젝트의 기존 원칙에 맞춰, `/goracle/tmp` 권한을 풀어달라고 요청하는 대신 **앱 폴더 밑에 자체 `tmp/`를 만들어 `-Djava.io.tmpdir`로 지정**하도록 `start-aix.sh` 수정 (`dist-aix/`, `dist-aix.tar.gz` 동기화 완료). 재기동 후 정상 기동 확인 — 포트는 9090으로 직접 설정해서 사용 중이며, **내부망에서 대상 Oracle DB 접속까지 정상 확인됨** (외부망에서의 직접 접속은 보안정책상 차단, 예상된 정상 동작).

### 9.5 커넥션 풀 min 2 → max 20 조정 + 코드 점검

서버 이관하며 `pool_min_idle=2`, `pool_max_size=20`으로 조정. 실사용자 유입 전 점검 요청받아 `OracleConnectionPoolManager`/`PoolTestController`/`DatabaseConfigService`/`OracleQueryHelper`/`MonitorService` 전수 검토, 결론:

- **`min-idle`은 동시접속 처리 능력과 무관** (유휴 시 데워둘 커넥션 개수일 뿐) — 실제 동시 처리 한도는 `max-size`, 풀이 꽉 찼을 때 대기 허용 시간은 `dbagent.oracle.connect-timeout-ms`가 좌우함. 이 타임아웃 값이 "죽은 DB 빠른 판정"(TCP/Oracle 접속 타임아웃)과 "풀 대기 허용시간"(HikariCP `connectionTimeout`) **두 역할을 겸하고 있다는 점**은 향후 튜닝 시 참고할 것 (`OracleConnectionPoolManager.java:33-34, 134`).
- 풀/락/쿨다운이 전부 `(db_id, user, dsn, SYSDBA여부)` 키로 완전히 분리되어 있어, **한 DB의 접속 실패가 다른 DB에 영향을 주지 않는 것을 코드로 확인** (버그 없음). "풀 포화로 인한 타임아웃"과 "진짜 접속 실패"도 HikariCP 예외 메시지의 `waiting=N` 파싱으로 구분해서, 포화로 인한 실패는 10초 쿨다운 대상에서 제외 — 동시접속 몰림으로 한 번 실패해도 바로 다음 요청은 정상 재시도됨.
- 풀이 꽉 찼을 때(20개 모두 사용 중) 새 요청은 대기열에서 기다리다 **반납되는 커넥션을 그대로 재사용**함 (새로 커넥션을 맺는 게 아님). `connect-timeout-ms` 안에 안 풀리면 그때 "Connection is not available" 오류.
- `MonitorService`의 모든 DB 접근 지점이 `try (Connection conn = poolManager.getConnection(...))` 형태로 요청 종료 시 반드시 반납하는 것도 확인 — 커넥션 leak 없음.
- **참고 (운영 관점, 버그 아님)**: `max=20 × databases.json 대상 DB 6개 = 이론상 최대 120세션`. 각 대상 Oracle의 `PROCESSES`/`SESSIONS` 한도 대비 여유 있는지 확인 필요 (미확인 — 6번 Q&A에서도 이미 짚었던 부분).

#!/usr/bin/ksh
# Starts the DBAgent-Java Spring Boot service in the background on AIX.
# Counterpart of start.ps1 (Windows dev machine) - the jar is built on Windows and copied over here,
# this script only ever runs it. No auto-build fallback on purpose: this box has no Maven/JDK17.

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR" || exit 1

JAR="$SCRIPT_DIR/dbagent-java-0.1.0.jar"
PID_FILE="$SCRIPT_DIR/dbagent-java.pid"
OUT_LOG="$SCRIPT_DIR/dbagent-java.out.log"
ERR_LOG="$SCRIPT_DIR/dbagent-java.err.log"

# This server's $TMPDIR (e.g. /goracle/tmp) is a shared Oracle-owned path this app's account may not
# have write access to - embedded Tomcat fails to boot ("Unable to create tempDir") if it can't create
# a subdir there. Same isolation principle as the bundled JDK: give it its own tmp dir instead of
# depending on a shared system path.
TMP_DIR="$SCRIPT_DIR/tmp"
mkdir -p "$TMP_DIR"

# Reads server.port from application.properties next to this script (same file Spring Boot itself
# reads), so changing the port there doesn't also require editing this script by hand. Falls back to
# Spring Boot's own default (8005, see src/main/resources/application.properties) if missing.
PORT=8005
PROPS_FILE="$SCRIPT_DIR/application.properties"
if [ -f "$PROPS_FILE" ]; then
    LINE=$(grep '^[[:space:]]*server\.port[[:space:]]*=[[:space:]]*[0-9][0-9]*[[:space:]]*$' "$PROPS_FILE" | tail -1)
    if [ -n "$LINE" ]; then
        PORT=$(echo "$LINE" | sed -e 's/^[[:space:]]*server\.port[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/')
    fi
fi
# conf/dbagent.conf(부트스트랩 설정, 2026-10-04)가 있으면 그 포트가 application.properties보다 우선한다 -
# Spring도 conf 값을 우선 적용하므로 안내 포트가 실제 기동 포트와 맞도록 같은 순서로 읽는다.
CONF_FILE="$SCRIPT_DIR/conf/dbagent.conf"
if [ -f "$CONF_FILE" ]; then
    CONF_LINE=$(grep '^[[:space:]]*server\.port[[:space:]]*=[[:space:]]*[0-9][0-9]*[[:space:]]*$' "$CONF_FILE" | tail -1)
    if [ -n "$CONF_LINE" ]; then
        PORT=$(echo "$CONF_LINE" | sed -e 's/^[[:space:]]*server\.port[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/')
    fi
fi

# Bundled JDK (this folder) always wins, same reasoning as start.ps1's Get-JavaExe: this server
# already has several other Javas in play (Oracle client's bundled JDK, NetBackup's JRE, etc.) and
# this app must never touch those - it only ever launches its own co-located JDK 8.
JAVA_EXE="$SCRIPT_DIR/java8/bin/java"
if [ ! -x "$JAVA_EXE" ]; then
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "WARNING: bundled java8/ not found, falling back to \$JAVA_HOME ($JAVA_HOME) - verify it is Java 8."
        JAVA_EXE="$JAVA_HOME/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_EXE=$(command -v java)
        echo "WARNING: bundled java8/ not found, falling back to PATH java ($JAVA_EXE) - verify it is Java 8."
    else
        echo "ERROR: java not found. Expected a bundled JDK at $JAVA_EXE."
        exit 1
    fi
fi

if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "DBAgent-Java is already running (PID $OLD_PID)."
        exit 0
    fi
    rm -f "$PID_FILE"
fi

# pid 파일이 없거나 틀려도 같은 jar로 뜬 프로세스가 있으면 두 번째를 띄우지 않는다 (2026-10-12 폐쇄망 실측: 종료 중이던
# 프로세스 위에 새로 기동해 내부 DB 파일 잠금 오류가 났다). 전체 경로로 찾아 다른 폴더의 같은 이름 jar는 건드리지 않는다.
RUNNING_PID=$(ps -ef | grep -F "$JAR" | grep -v grep | awk '{print $2}' | head -1)
if [ -n "$RUNNING_PID" ]; then
    echo "DBAgent-Java is already running or still shutting down (PID $RUNNING_PID). Not starting a second one."
    echo "Check with: ps -p $RUNNING_PID"
    exit 1
fi

if [ ! -f "$JAR" ]; then
    echo "ERROR: $JAR not found. Build it on the dev machine (build.ps1) and copy it here first."
    exit 1
fi

# IBM JVM JIT 충돌 회피 (2026-10-12 폐쇄망 실측: Java 8 SR6 FP5에서 기동 직후 60초 샘플러 첫 사이클에
# JIT 컴파일된 org/h2/value/ValueString.get -> Value.cache 안에서 Signal 11(gpf)로 JVM이 죽음, javacore 확인).
#  - h2.objectCache=false : 충돌이 난 H2 값 캐시(여러 스레드가 잠금 없이 같이 쓰는 배열)를 끈다.
#  - Xjit:exclude         : 그 두 메서드를 JIT 컴파일에서 뺀다(인터프리터로 실행).
# exclude 값은 | 와 { } 가 들어 있어 반드시 따옴표로 감싼 채 넘긴다.
H2_CACHE_OPT="-Dh2.objectCache=false"
JIT_EXCLUDE_OPT="-Xjit:exclude={org/h2/value/Value.cache*|org/h2/value/ValueString.get*}"

echo "Starting DBAgent-Java (port $PORT) using $JAVA_EXE ..."
nohup "$JAVA_EXE" "$H2_CACHE_OPT" "$JIT_EXCLUDE_OPT" "-Djava.io.tmpdir=$TMP_DIR" -jar "$JAR" >"$OUT_LOG" 2>"$ERR_LOG" &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"
echo "Started with PID $NEW_PID. Waiting for port $PORT to open..."

READY=0
i=0
while [ $i -lt 30 ]; do
    if netstat -an 2>/dev/null | grep "\.${PORT}[[:space:]].*LISTEN" >/dev/null 2>&1; then
        READY=1
        break
    fi
    sleep 1
    i=$((i + 1))
done

if [ $READY -eq 1 ]; then
    echo "DBAgent-Java is up: http://localhost:$PORT"
else
    # 30초 안에 포트가 안 열려도 실패가 아닐 수 있다 (2026-10-12 폐쇄망 실측: 새 jar 첫 기동 때 내부 DB
    # 테이블 변경이 수 분 걸렸고, 그 사이 재기동해서 변경이 끊기고 파일 잠금 오류가 났다).
    if kill -0 "$NEW_PID" 2>/dev/null; then
        echo "Port $PORT is not open yet, but the process (PID $NEW_PID) is still running."
        echo "It may be upgrading the internal DB (first start after a new jar can take several minutes)."
        echo "Do NOT run stop/start again. Watch: tail -f $OUT_LOG  (wait for 'Tomcat started on port(s): $PORT')"
    else
        echo "Process $NEW_PID exited during startup. Check $OUT_LOG and $ERR_LOG (and javacore*.txt if any)."
    fi
fi

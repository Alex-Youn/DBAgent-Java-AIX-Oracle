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

# Bundled JDK (this folder) always wins, same reasoning as start.ps1's Get-JavaExe: this server
# already has several other Javas in play (Oracle client's bundled JDK, NetBackup's JRE, etc.) and
# this app must never touch those - it only ever launches its own co-located JDK 8.
JAVA_EXE="$SCRIPT_DIR/jdk8/bin/java"
if [ ! -x "$JAVA_EXE" ]; then
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "WARNING: bundled jdk8/ not found, falling back to \$JAVA_HOME ($JAVA_HOME) - verify it is Java 8."
        JAVA_EXE="$JAVA_HOME/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_EXE=$(command -v java)
        echo "WARNING: bundled jdk8/ not found, falling back to PATH java ($JAVA_EXE) - verify it is Java 8."
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

if [ ! -f "$JAR" ]; then
    echo "ERROR: $JAR not found. Build it on the dev machine (build.ps1) and copy it here first."
    exit 1
fi

echo "Starting DBAgent-Java (port $PORT) using $JAVA_EXE ..."
nohup "$JAVA_EXE" -jar "$JAR" >"$OUT_LOG" 2>"$ERR_LOG" &
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
    echo "Timed out waiting for port $PORT. Check $ERR_LOG for startup errors."
fi

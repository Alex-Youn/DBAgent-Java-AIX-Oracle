#!/usr/bin/ksh
# Stops the DBAgent-Java service started by start-aix.sh (PID file first, process-name lookup as
# fallback - AIX has no simple lsof/Get-NetTCPConnection equivalent for "who owns this port" short
# of the destructive rmsock trick, so the fallback matches on the running process instead).

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR" || exit 1

PID_FILE="$SCRIPT_DIR/dbagent-java.pid"
JAR_NAME="dbagent-java-0.1.0.jar"
STOPPED=0

if [ -f "$PID_FILE" ]; then
    STORED_PID=$(cat "$PID_FILE")
    if [ -n "$STORED_PID" ] && kill -0 "$STORED_PID" 2>/dev/null; then
        echo "Stopping DBAgent-Java (PID $STORED_PID)..."
        # Plain SIGTERM, not -9: unlike Windows Stop-Process -Force, this lets the JVM's shutdown
        # hook close the HikariCP pools cleanly before the process exits.
        kill "$STORED_PID"
        STOPPED=1
    fi
    rm -f "$PID_FILE"
fi

if [ $STOPPED -eq 0 ]; then
    FOUND_PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}' | head -1)
    if [ -n "$FOUND_PID" ]; then
        echo "No valid PID file; stopping process running $JAR_NAME (PID $FOUND_PID)..."
        kill "$FOUND_PID"
        STOPPED=1
    fi
fi

if [ $STOPPED -eq 1 ]; then
    echo "DBAgent-Java stopped."
else
    echo "DBAgent-Java does not appear to be running."
fi

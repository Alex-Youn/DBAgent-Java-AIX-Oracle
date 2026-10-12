#!/usr/bin/ksh
# Stops the DBAgent-Java service started by start-aix.sh (PID file first, process-name lookup as
# fallback - AIX has no simple lsof/Get-NetTCPConnection equivalent for "who owns this port" short
# of the destructive rmsock trick, so the fallback matches on the running process instead).

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR" || exit 1

PID_FILE="$SCRIPT_DIR/dbagent-java.pid"
JAR="$SCRIPT_DIR/dbagent-java-0.1.0.jar"
# 종료 신호를 보낸 뒤 프로세스가 실제로 끝나기를 기다리는 최대 시간(초).
WAIT_SECONDS=60

TARGET_PID=""
if [ -f "$PID_FILE" ]; then
    STORED_PID=$(cat "$PID_FILE")
    if [ -n "$STORED_PID" ] && kill -0 "$STORED_PID" 2>/dev/null; then
        TARGET_PID="$STORED_PID"
    fi
fi

if [ -z "$TARGET_PID" ]; then
    # 전체 경로로 찾는다 - 같은 서버의 다른 폴더에 있는 같은 이름 jar를 건드리지 않게.
    TARGET_PID=$(ps -ef | grep -F "$JAR" | grep -v grep | awk '{print $2}' | head -1)
    if [ -n "$TARGET_PID" ]; then
        echo "No valid PID file; found process running $JAR (PID $TARGET_PID)."
    fi
fi

if [ -z "$TARGET_PID" ]; then
    rm -f "$PID_FILE"
    echo "DBAgent-Java does not appear to be running."
    exit 0
fi

echo "Stopping DBAgent-Java (PID $TARGET_PID)..."
# Plain SIGTERM, not -9: unlike Windows Stop-Process -Force, this lets the JVM's shutdown
# hook close the HikariCP pools cleanly before the process exits.
kill "$TARGET_PID"
KILL_RC=$?
# 신호를 못 보냈는데(다른 계정이 띄운 프로세스 등) 프로세스가 그대로 있으면 "stopped"라고 하지 않는다 - 아래 대기 루프의
# kill -0 도 같은 이유로 실패해서, 살아 있는 프로세스를 끝난 것으로 보고 pid 파일을 지우게 된다 (2026-10-12 코드 점검 수정).
if [ $KILL_RC -ne 0 ] && ps -p "$TARGET_PID" >/dev/null 2>&1; then
    echo "ERROR: could not send the stop signal to PID $TARGET_PID (started by another user?). It is still running."
    echo "Stop it as the user that started it. Check with: ps -fp $TARGET_PID"
    exit 1
fi

# 종료 신호만 보내고 바로 "stopped"라고 하지 않는다 (2026-10-12 폐쇄망 실측: 내부 DB 테이블 변경 중이던 프로세스가
# 아직 살아 있는데 pid 파일을 지우고 끝내서, 바로 이어진 start가 두 번째 프로세스를 띄웠고 파일 잠금 오류가 났다).
# 프로세스가 실제로 사라질 때까지 기다리고, 그때 pid 파일을 지운다.
i=0
while [ $i -lt $WAIT_SECONDS ]; do
    if ! kill -0 "$TARGET_PID" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "DBAgent-Java stopped."
        exit 0
    fi
    sleep 1
    i=$((i + 1))
done

# 아직 살아 있다 - 강제 종료(-9)는 하지 않는다. 내부 DB 파일을 쓰는 중에 끊으면 다음 기동이 더 오래 걸린다.
echo "$TARGET_PID" > "$PID_FILE"
echo "PID $TARGET_PID is still shutting down after $WAIT_SECONDS seconds (it may be finishing an internal DB change)."
echo "Do NOT run start-aix.sh yet. Check with: ps -p $TARGET_PID   (run stop-aix.sh again to keep waiting)"
exit 1

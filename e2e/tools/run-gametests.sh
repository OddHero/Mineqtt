#!/bin/sh
# Runs the Fabric client game tests of this checkout, one client at a time across every worktree of the machine.
#   run-gametests.sh [suites]     suites: comma-separated names as the report writes them (screens,wires,...);
#                                 without it MINEQTT_GAMETEST_SUITES as set, else every suite
# MINEQTT_LANE is the bridge id of this run (default gametest), so runs of several lanes on one broker never read or
# clear each other's topics; MINEQTT_E2E_BROKER (tcp:// or ws://) points the tests at a broker as for a plain
# ./gradlew :fabric:26.2:runClientGameTest. The game tests compile before the lock is taken, so only the game window waits.
# The lock is the directory ~/.mineqtt-gametest.lock ($USERPROFILE on Windows), made with an atomic mkdir: a run waits
# up to MINEQTT_GAMETEST_LOCK_WAIT seconds (45 min) for it and breaks a lock older than 60 min (a run that was killed).
# The exit status is Gradle's, 3 when the lock was not acquired in time.
DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$DIR/../.." && pwd)
REPORT="$ROOT/fabric/versions/26.2/build/run/clientGameTest/mineqtt-gametest-report.txt"
WAIT_SECONDS=${MINEQTT_GAMETEST_LOCK_WAIT:-2700}
STALE_SECONDS=3600
POLL_SECONDS=5

if [ -n "$USERPROFILE" ] && command -v cygpath >/dev/null 2>&1; then
    LOCK_HOME=$(cygpath -u "$USERPROFILE")
else
    LOCK_HOME=${USERPROFILE:-$HOME}
fi
LOCK="$LOCK_HOME/.mineqtt-gametest.lock"

MINEQTT_GAMETEST_BRIDGE=${MINEQTT_LANE:-gametest}
export MINEQTT_GAMETEST_BRIDGE
if [ -n "$1" ]; then
    MINEQTT_GAMETEST_SUITES=$1
    export MINEQTT_GAMETEST_SUITES
fi

log() {
    echo "[run-gametests $(date '+%H:%M:%S') $MINEQTT_GAMETEST_BRIDGE] $*"
}

# Reads one file of the lock; empty while the lock is being written or gone.
lock_field() {
    cat "$LOCK/$1" 2>/dev/null
}

cd "$ROOT" || exit 1
log "bridge $MINEQTT_GAMETEST_BRIDGE, suites ${MINEQTT_GAMETEST_SUITES:-all}, broker ${MINEQTT_E2E_BROKER:-none (MQTT disabled)}"
./gradlew --console=plain :fabric:26.2:gametestClasses || exit $?

TOKEN="$$ $(date +%s) $ROOT"
release() {
    if [ "$(lock_field token)" = "$TOKEN" ]; then
        rm -rf "$LOCK"
        log "released $LOCK"
    fi
}

start=$(date +%s)
waiting=
until mkdir "$LOCK" 2>/dev/null; do
    now=$(date +%s)
    since=$(lock_field time)
    # A lock without a time file is either being written this instant or was left by a run killed while writing it.
    if [ -z "$since" ] && [ -n "$(find "$LOCK" -maxdepth 0 -mmin +60 2>/dev/null)" ]; then
        since=0
    fi
    if [ -n "$since" ] && [ $((now - since)) -gt $STALE_SECONDS ]; then
        # Moved aside first, so of two runs breaking it at once only one removes it; a lock another run took in the
        # meantime (another time) goes back.
        stale="$LOCK.stale.$$"
        if mv "$LOCK" "$stale" 2>/dev/null; then
            moved=$(cat "$stale/time" 2>/dev/null)
            if [ "${moved:-0}" = "$since" ]; then
                log "broke a stale lock of $(cat "$stale/owner" 2>/dev/null || echo 'an unknown run') from $(cat "$stale/started" 2>/dev/null || echo 'an unknown time')"
                rm -rf "$stale"
            elif ! mv "$stale" "$LOCK" 2>/dev/null; then
                log "warning: could not give back the fresh lock of $(cat "$stale/owner" 2>/dev/null || echo 'an unknown run')"
                rm -rf "$stale"
            fi
        fi
        continue
    fi
    if [ $((now - start)) -ge "$WAIT_SECONDS" ]; then
        log "gave up after ${WAIT_SECONDS}s: $LOCK is held by $(lock_field owner) (pid $(lock_field pid), since $(lock_field started))"
        exit 3
    fi
    if [ -z "$waiting" ]; then
        log "waiting for $LOCK, held by $(lock_field owner) (pid $(lock_field pid), since $(lock_field started))"
        waiting=1
    fi
    sleep "$POLL_SECONDS"
done
# Gradle runs in the background, so a signal ends it and frees the lock at once instead of after the run.
gradle_pid=
stop() {
    if [ -n "$gradle_pid" ]; then
        log "stopping gradle (pid $gradle_pid)"
        kill "$gradle_pid" 2>/dev/null
        wait "$gradle_pid" 2>/dev/null
    fi
    exit "$1"
}
trap release EXIT
trap 'stop 129' HUP
trap 'stop 130' INT
trap 'stop 143' TERM
echo "$TOKEN" > "$LOCK/token"
echo "$(whoami)@$(hostname) $ROOT (bridge $MINEQTT_GAMETEST_BRIDGE)" > "$LOCK/owner"
echo "$$" > "$LOCK/pid"
date '+%Y-%m-%d %H:%M:%S' > "$LOCK/started"
date +%s > "$LOCK/time"
log "acquired $LOCK after $(($(date +%s) - start))s"

./gradlew --console=plain :fabric:26.2:runClientGameTest &
gradle_pid=$!
wait "$gradle_pid"
status=$?
gradle_pid=
# Printed under the lock: the next run deletes the run directory with the report.
if [ -f "$REPORT" ]; then
    grep -E '^(SUITE|FAIL) ' "$REPORT"
else
    log "no report at $REPORT"
fi
log "gradle exit status $status"
exit $status

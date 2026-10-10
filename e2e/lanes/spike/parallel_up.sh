#!/usr/bin/env bash
# Spike measurement: start several lanes at once and report each one's wall time and server startup times.
#   parallel_up.sh <id>...        (logs in $TMPDIR/lane-up-<id>.log)
set -uo pipefail
export MSYS_NO_PATHCONV=1
HERE=$(cd "$(dirname "$0")" && pwd)
LANE="$HERE/../lane.sh"
PREFIX=${MINEQTT_LANE_PREFIX:-mineqtt-lane}
TMP=${TMPDIR:-/tmp}
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi; }

up_one() {
    local id=$1 start end rc
    start=$(date +%s.%N)
    bash "$LANE" up "$id" > "$TMP/lane-up-$id.log" 2>&1
    rc=$?
    end=$(date +%s.%N)
    awk -v id="$id" -v rc="$rc" -v s="$start" -v e="$end" 'BEGIN { printf "lane %s: up exit %s, wall %.1fs\n", id, rc, e - s }'
}

start=$(date +%s.%N)
for id in "$@"; do up_one "$id" & done
wait
end=$(date +%s.%N)
awk -v s="$start" -v e="$end" 'BEGIN { printf "all lanes up: %.1fs\n", e - s }'
containers=()
for id in "$@"; do containers+=("$PREFIX-$id-server-1"); done
python "$(winpath "$HERE/startup_times.py")" "${containers[@]}"

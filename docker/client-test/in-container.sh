#!/bin/sh
# Runs inside the mineqtt-client container. The source tree lives at /work/src on the container filesystem.
#   in-container.sh extract                 unpack /tmp/src.tar.gz (or /tmp/src.tar) into a fresh /work/src
#   in-container.sh build                   ./gradlew :fabric:26.2:build -x check (timed)
#   in-container.sh warm                    fill the Gradle seed volume (run by run.sh --warm)
#   in-container.sh test [Suite ...]        client game tests under Xvfb; with suite names, only those suites. The whole
#                                           console goes to /work/console.log; with MQ_OUTPUT=filtered only the Gradle
#                                           task, verdict, error and SUITE/FAIL lines are printed (remote runs)
#   in-container.sh collect all|failed|none a tar.gz of the results on stdout: report, latest.log, console.log and the
#                                           screenshots (all, only those of failing suites, or none)
#   in-container.sh shotmap                 "<suite> <screenshot>" for every screenshot (how collect picks them)
#   in-container.sh beat                    touches /tmp/heartbeat for every line on stdin (run.sh feeds it)
#   in-container.sh watchdog                the container's main process (see below)
set -eu
SRC=/work/src
RUN_DIR=${MQ_RUN_DIR:-$SRC/fabric/versions/26.2/build/run/clientGameTest}
cd /work 2>/dev/null || true   # absent only when shotmap is tried on the host (MQ_RUN_DIR)

# Prints "<suite> <screenshot file name>" for each screenshot. The harness does not tag screenshots with their suite, so
# they are matched by time: latest.log has a timestamped "SUITE PASS|FAIL <suite>" line at the end of every suite, and a
# screenshot belongs to the suite whose window (end of the previous suite, end of this one) holds its mtime. Both have
# whole seconds, so a screenshot in the second a suite ended is listed for both suites. A screenshot after the last
# SUITE line belongs to a suite that never finished and is listed as "-". The JVM and the container share their time
# zone (UTC); a run that crosses midnight is handled (times before the log's first line are of the next day).
shotmap() {
  log=$RUN_DIR/logs/latest.log
  [ -f "$log" ] || return 0
  {
    sed -n '1s/^\[\([0-9][0-9]\):\([0-9][0-9]\):\([0-9][0-9]\)\].*/B \1 \2 \3/p' "$log"
    sed -n 's/^\[\([0-9][0-9]\):\([0-9][0-9]\):\([0-9][0-9]\)\] .*(MineQTT-GameTest) SUITE [A-Z]* \([^ ]*\).*$/S \1 \2 \3 \4/p' "$log"
    for f in "$RUN_DIR"/screenshots/*.png; do
      [ -f "$f" ] && echo "P $(date -d "@$(stat -c %Y "$f")" '+%H %M %S') ${f##*/}"
    done
  } | awk '
    function t(h, m, s,   v) { v = h * 3600 + m * 60 + s; if (v < base) v += 86400; return v }
    $1 == "B" { base = $2 * 3600 + $3 * 60 + $4; next }
    $1 == "S" { n++; end[n] = t($2, $3, $4); name[n] = $5; next }
    $1 == "P" {
      v = t($2, $3, $4); hit = 0
      for (k = 1; k <= n; k++) {
        if ((k == 1 || v >= end[k - 1]) && v <= end[k]) { print name[k], $5; hit = 1 }
      }
      if (n == 0 || v > end[n]) print "-", $5
    }'
}

case "${1:-}" in
  extract)
    # Private Gradle user home from the read-only seed (see docker-compose.yml); skipped when /root/.gradle is the
    # writable seed itself (warm-up) or already filled.
    if [ -d /gradle-seed ] && [ ! -d /gradle-seed/caches ]; then
      echo "warning: the Gradle seed volume is empty, this run downloads everything (run.sh --warm --host <this host>)" >&2
    fi
    if [ -d /gradle-seed/caches ] && [ ! -d /root/.gradle/caches ]; then
      s=$(date +%s)
      mkdir -p /root/.gradle
      cp -a /gradle-seed/caches /gradle-seed/wrapper /root/.gradle/
      echo "seeded /root/.gradle ($(du -sh /root/.gradle | cut -f1)) in $(( $(date +%s) - s ))s"
    fi
    rm -rf "$SRC"
    mkdir -p "$SRC"
    if [ -f /tmp/src.tar.gz ]; then tar -xzf /tmp/src.tar.gz -C "$SRC"; else tar -xf /tmp/src.tar -C "$SRC"; fi
    # run.sh tars the working tree, which a Windows checkout (core.autocrlf=true) has in CRLF and without executable
    # bits: shell scripts need LF and +x to run here. Java, JSON and Gradle files read CRLF fine, as on the host.
    find "$SRC" -type f \( -name gradlew -o -name '*.sh' \) -exec sed -i 's/\r$//' {} + -exec chmod +x {} +
    echo "extracted $(find "$SRC" -type f | wc -l) files"
    ;;
  build)
    cd "$SRC"
    start=$(date +%s)
    ./gradlew --no-daemon --console=plain :fabric:26.2:build -x check
    echo "BUILD_SECONDS $(( $(date +%s) - start ))"
    ;;
  warm)
    # Fills the writable seed mounted at /root/.gradle: dependencies, Minecraft, assets and the game test classpath.
    cd "$SRC"
    ./gradlew --no-daemon --console=plain :fabric:26.2:build -x check :fabric:26.2:compileGametestJava :fabric:26.2:downloadAssets
    ;;
  test)
    shift
    cd "$SRC"
    if [ "$#" -gt 0 ]; then
      # No suite filter exists in the harness: shrink the entrypoint list of this copy. fabric/build.gradle counts the
      # expected suites from the same file, so the pass check stays consistent.
      list=""
      for s in "$@"; do list="$list\"art.rehra.mineqtt.fabric.gametest.$s\","; done
      list="${list%,}"
      awk -v list="$list" '
        /"fabric-client-gametest": \[/ { print; print "      " list; skip=1; next }
        skip && /\]/ { skip=0 }
        !skip { print }' fabric/src/gametest/resources/fabric.mod.json > /tmp/fmj.json
      cp /tmp/fmj.json fabric/src/gametest/resources/fabric.mod.json
      grep -A3 'fabric-client-gametest' fabric/src/gametest/resources/fabric.mod.json
    fi
    start=$(date +%s)
    rm -f /tmp/test.rc
    # MQ_TEST_TIMEOUT (seconds, run.sh sets it) ends a run that hangs: timeout signals its whole process group (Xvfb,
    # Gradle and the game), and KILLs it 30 s later. MINEQTT_CLIENT_HEAP (run.sh) reaches the game's -Xmx through
    # fabric/build.gradle.
    limit=${MQ_TEST_TIMEOUT:-1800}
    # The client's log goes to the console too (~0.5 MB for a full run): over a slow link only the lines that matter
    # cross it, the whole console stays in /work/console.log (collect returns it).
    if [ "${MQ_OUTPUT:-full}" = filtered ]; then
      { timeout -k 30 "$limit" xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew --no-daemon --console=plain :fabric:26.2:runClientGameTest 2>&1 \
          || echo $? > /tmp/test.rc; } | tee /work/console.log \
        | grep --line-buffered -E '^> |BUILD (SUCCESSFUL|FAILED)|FAILURE:|What went wrong|error:|Client game test|\(MineQTT-GameTest\) (SUITE|FAIL)|Crash report saved|OutOfMemoryError|Terminating due to' \
        || true
    else
      { timeout -k 30 "$limit" xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew --no-daemon --console=plain :fabric:26.2:runClientGameTest 2>&1 \
          || echo $? > /tmp/test.rc; } | tee /work/console.log
    fi
    rc=$(cat /tmp/test.rc 2>/dev/null || echo 0)
    case "$rc" in
      124|137) echo "TIMEOUT: the client game tests did not end within ${limit}s and were stopped (see console.log and latest.log)" ;;
    esac
    echo "TEST_SECONDS $(( $(date +%s) - start )) EXIT $rc"
    report=$RUN_DIR/mineqtt-gametest-report.txt
    if [ -f "$report" ]; then
      echo "SUITE PASS: $(grep -c '^SUITE PASS' "$report" || true)  FAIL lines: $(grep -c '^FAIL' "$report" || true)  SKIP lines: $(grep -c '^SKIP' "$report" || true)"
    fi
    exit "$rc"
    ;;
  collect)
    mode=${2:-all}
    out=/tmp/results
    rm -rf "$out"
    mkdir -p "$out"
    report=$RUN_DIR/mineqtt-gametest-report.txt
    [ -f "$report" ] && cp "$report" "$out/"
    [ -f "$RUN_DIR/logs/latest.log" ] && cp "$RUN_DIR/logs/latest.log" "$out/"
    [ -f /work/console.log ] && cp /work/console.log "$out/"
    if [ -d "$RUN_DIR/screenshots" ]; then
      case "$mode" in
        all)
          cp -a "$RUN_DIR/screenshots" "$out/" ;;
        failed)
          # Failing suites: SUITE FAIL and FAIL lines of the report; "-" is a suite that never finished.
          failing=" - "
          if [ -f "$report" ]; then
            failing="$failing$(sed -n 's/^SUITE FAIL \([^ ]*\).*/\1/p; s/^FAIL \([^:]*\):.*/\1/p' "$report" | sort -u | tr '\n' ' ')"
          fi
          mkdir -p "$out/screenshots"
          shotmap | while read -r suite file; do
            case "$failing" in *" $suite "*) cp -p "$RUN_DIR/screenshots/$file" "$out/screenshots/" ;; esac
          done
          rmdir "$out/screenshots" 2>/dev/null || true ;;
        none) ;;
        *) echo "collect: all, failed or none, not '$mode'" >&2; exit 2 ;;
      esac
    fi
    tar -czf - -C "$out" .
    ;;
  shotmap)
    shotmap
    ;;
  beat)
    # run.sh pipes a line every 15 s into a "docker exec -i ... mq beat" for the whole run: one connection instead of
    # one exec per beat, which matters over SSH. When run.sh dies the pipe closes, the loop ends and the beats stop.
    while read -r _; do touch /tmp/heartbeat; done
    ;;
  watchdog)
    # PID 1 of the container (CMD). With MINEQTT_WATCHDOG=<seconds> (run.sh sets it) the container stops itself once
    # /tmp/heartbeat, touched by run.sh every 15 s ("beat"), is older than that, and the stop ends any "docker exec" in
    # it (a Gradle run included). That covers a run.sh killed without its traps, for example by a command timeout on
    # Windows, which also leaves the "docker exec" running: killing the docker CLI does not stop an exec. Without the
    # variable (a stack started by hand) it only waits.
    trap 'exit 0' TERM INT
    limit=${MINEQTT_WATCHDOG:-0}
    touch /tmp/heartbeat
    while :; do
      if [ "$limit" -gt 0 ]; then
        age=$(( $(date +%s) - $(stat -c %Y /tmp/heartbeat 2>/dev/null || echo 0) ))
        if [ "$age" -gt "$limit" ]; then
          echo "watchdog: no heartbeat for ${age}s (limit ${limit}s), stopping the container" >&2
          exit 0
        fi
      fi
      sleep 5 &
      wait $!
    done
    ;;
  *)
    echo "usage: $0 extract|build|warm|test [Suite ...]|collect all|failed|none|shotmap|beat|watchdog" >&2
    exit 2
    ;;
esac

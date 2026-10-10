#!/bin/sh
# Measures N concurrent full client game test stacks from this checkout (each its own broker, Xvfb and compose project).
#   CLIENT_CPUS=4 CLIENT_MEMORY=8g sh docker/client-test/parallel.sh 3
# Writes build/docker-client-test/par<i>/ per stack, par<i>.log, and parallel-stats.txt (docker stats every ~2 s).
set -u
n="${1:-3}"
mkdir -p build/docker-client-test
stats=build/docker-client-test/parallel-stats.txt
: > "$stats"
start=$(date +%s)
pids=""
i=1
while [ "$i" -le "$n" ]; do
  ( s=$(date +%s); sh docker/client-test/run.sh "par$i" > "build/docker-client-test/par$i.log" 2>&1; rc=$?
    echo "par$i exit $rc wall $(( $(date +%s) - s ))s" >> "build/docker-client-test/par$i.log" ) &
  pids="$pids $!"
  i=$((i + 1))
done
( until docker ps --format '{{.Names}}' | grep -q 'mineqtt-client-par.*client'; do sleep 1; done
  while docker ps --format '{{.Names}}' | grep -q 'mineqtt-client-par'; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' $(docker ps -q --filter name=mineqtt-client-par) >> "$stats" 2>/dev/null
  done ) &
for p in $pids; do wait "$p"; done
echo "all $n stacks done in $(( $(date +%s) - start ))s"
for f in build/docker-client-test/par[0-9]*.log; do
  grep -hE "^seeded|TEST_SECONDS|SUITE PASS:|exit .* wall" "$f"
done

#!/bin/sh
# Runs the Fabric client game tests of this checkout in an isolated Docker stack (own broker, own Xvfb, no host ports),
# on this workstation or on one of the remote Docker hosts (docker/hosts.sh lists them).
#   sh docker/client-test/run.sh --warm [--host H]          once per Docker host (and after dependency bumps): fill
#                                                           that host's Gradle seed volume (and build its image)
#   sh docker/client-test/run.sh <id> [Suite ...] [options] all suites, or only the named ones (e.g. GroupGameTest)
# Options:
#   --host local|auto|<remote>    where the stack runs (default MINEQTT_DOCKER_HOST, else auto: the first remote of
#                                 docker/hosts.sh that answers, is ready and has a free slot, else local)
#   --screenshots all|failed|none which screenshots come back (default: all locally, failed on the remote, where every
#                                 byte crosses a slow link; a full set is ~180 files / 29 MB, take "all" for UI work)
# Works from any directory (Git Bash on Windows works). The stack is compose project mineqtt-client-<id> on the chosen
# host; results land in build/docker-client-test/<id>/ (mineqtt-gametest-report.txt, latest.log, console.log and
# screenshots/); the exit code is the Gradle verdict. The script prints the host, the wall time and the bytes it sent
# and received. The stack is removed at the end, also on failure or Ctrl+C; the image (mineqtt-client:<recipe hash>)
# and the Gradle seed volume (mineqtt-client-gradle) stay on that host.
#
# A run killed without its traps (a command timeout on Windows hard-kills the process tree) cannot remove its stack. Its
# client container then stops itself about MINEQTT_CLIENT_WATCHDOG_SEC (default 90) seconds later, because run.sh no
# longer sends heartbeats (in-container.sh watchdog/beat), and that also ends the Gradle run inside it. This works the
# same on the remote. The next run with the same id removes a leftover stack of that id before it starts, and any run
# breaks a slot whose stack is gone (below).
#
# Ships the working tree as git sees it, as a gzipped tar: tracked files with their uncommitted edits plus untracked
# files that are not ignored (git ls-files -co --exclude-standard). The container strips CR from gradlew and every *.sh,
# so a CRLF checkout (core.autocrlf=true) works.
#
# Slots: at most MINEQTT_CLIENT_SLOTS stacks (default 1, of 3 CPUs / 5 GB: the workstation's Docker VM is capped at
# 16 GB and shared with other projects) run at once on this workstation, and on
# each remote as many as its row in the host table says (e.g. 16 of 3 CPUs / 5 GB), counted by mkdir slots on
# this workstation (all runs start here) in MINEQTT_CLIENT_SLOT_DIR (default $USERPROFILE/.mineqtt-client-slots, $HOME
# elsewhere) and in the same path with "-<remote name>" appended. A run waits for a free slot (under auto: on any host
# it may use). A remote with build=manual in docker/hosts.sh is used by auto only once its image and seed exist
# (run.sh --warm --host <it>). A slot is taken to be
# left by a dead run and broken when its client container no longer runs on its host (checked once its stack is up),
# or when it is older than 60 minutes (MINEQTT_CLIENT_SLOT_STALE_MIN). --warm takes a slot too.
#
# Environment: CLIENT_CPUS / CLIENT_MEMORY (client container limits, default 3 and 5g here, the remote's row there), CLIENT_HEAP (the game's -Xmx,
# default 2g: the JVM default of 25 % of the container left the game out of heap at 4g), MINEQTT_CLIENT_TIMEOUT_MIN
# (default 30; the test step is stopped after it and the run fails: a game that hangs no longer holds its stack forever,
# because the heartbeat only notices a dead run.sh, not a stuck game),
# MINEQTT_DOCKER_HOST, MINEQTT_REMOTES, MINEQTT_SLOTS_<NAME>, MINEQTT_LOCAL_CONTEXT (docker/hosts.sh).
set -eu
export MSYS_NO_PATHCONV=1   # Git Bash would rewrite container paths such as /tmp/src.tar.gz

cd "$(dirname "$0")/../.."
. docker/hosts.sh

SEED=mineqtt-client-gradle
LOCAL_SLOTS=${MINEQTT_CLIENT_SLOTS:-1}
# Container size per host, unless CLIENT_CPUS / CLIENT_MEMORY are set: this workstation 4 CPUs / 8g, a remote its row
# in the host table (16 slots of 3 CPUs / 5g were the best throughput measured on a 64-thread host, e2e/README.md).
USER_CPUS=${CLIENT_CPUS:-}
USER_MEMORY=${CLIENT_MEMORY:-}
STALE_MIN=${MINEQTT_CLIENT_SLOT_STALE_MIN:-60}
TEST_TIMEOUT_MIN=${MINEQTT_CLIENT_TIMEOUT_MIN:-30}
CLIENT_HEAP=${CLIENT_HEAP:-2g}
WATCHDOG=${MINEQTT_CLIENT_WATCHDOG_SEC:-90}
if [ -n "${MINEQTT_CLIENT_SLOT_DIR:-}" ]; then
  SLOT_BASE=$MINEQTT_CLIENT_SLOT_DIR
elif [ -n "${USERPROFILE:-}" ] && command -v cygpath >/dev/null 2>&1; then
  SLOT_BASE="$(cygpath -u "$USERPROFILE")/.mineqtt-client-slots"
else
  SLOT_BASE="${HOME:?}/.mineqtt-client-slots"
fi

die() { echo "client-test: $*" >&2; exit 2; }
say() { echo "client-test: $*"; }
usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; }

warm=0
name=""
suites=""
shots=""
host_arg=${MINEQTT_DOCKER_HOST:-auto}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --warm) warm=1 ;;
    --host) [ "$#" -ge 2 ] || die "--host needs a value"; host_arg=$2; shift ;;
    --host=*) host_arg=${1#--host=} ;;
    --screenshots) [ "$#" -ge 2 ] || die "--screenshots needs a value"; shots=$2; shift ;;
    --screenshots=*) shots=${1#--screenshots=} ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) if [ "$warm" = 0 ] && [ -z "$name" ]; then name=$1; else suites="$suites $1"; fi ;;
  esac
  shift
done
mq_parse_host "$host_arg" || exit 2
case "$shots" in ""|all|failed|none) ;; *) die "--screenshots takes all, failed or none, not '$shots'" ;; esac
if [ "$warm" = 1 ]; then
  [ -z "$name$suites" ] || die "--warm takes no stack id or suites"
else
  [ -n "$name" ] || { usage >&2; exit 2; }
  case "$name" in
    warm|*[!a-z0-9_-]*) die "stack id must be lowercase letters, digits, - or _ (and not 'warm'): '$name'" ;;
  esac
fi
[ "$MQ_HOST_MODE" != remote ] || mq_require "$MQ_PICK" || exit 2

slot=""
token="$$.$(date +%s)"
compose=""
warm_ctr=""
beat_pid=""
cleanup() {
  rc=$?
  trap - EXIT INT TERM
  if [ -n "$beat_pid" ]; then kill "$beat_pid" 2>/dev/null || true; fi
  if [ -n "$compose" ]; then $compose down --remove-orphans --timeout 10 >/dev/null 2>&1 || true; fi
  if [ -n "$warm_ctr" ]; then dk rm -f "$warm_ctr" >/dev/null 2>&1 || true; fi
  # Only while it is still ours: a slot broken by another run may already belong to a third one.
  if [ -n "$slot" ] && [ "$(cat "$slot/token" 2>/dev/null)" = "$token" ]; then rm -rf "$slot"; fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# local|<remote>: every dk() call and the slot functions below act on that host from now on.
select_host() {
  mq_use_host "$1"
  if [ "$1" = local ]; then
    SLOT_DIR=$SLOT_BASE; SLOTS=$LOCAL_SLOTS
    CLIENT_CPUS=${USER_CPUS:-3}; CLIENT_MEMORY=${USER_MEMORY:-5g}
  else
    SLOT_DIR=$SLOT_BASE-$1; SLOTS=$(mq_slots "$1")
    CLIENT_CPUS=${USER_CPUS:-$(mq_field "$1" 4)}; CLIENT_MEMORY=${USER_MEMORY:-$(mq_field "$1" 5)}
  fi
  export CLIENT_CPUS CLIENT_MEMORY   # read by docker-compose.yml
}

compose_for() { # <id>: the compose command of that stack on the current host (with its cpuset and weight, if any)
  if mq_has_limits; then
    echo "dk compose -p mineqtt-client-$1 -f docker/client-test/docker-compose.yml -f docker/client-test/docker-compose.limits.yml"
  else
    echo "dk compose -p mineqtt-client-$1 -f docker/client-test/docker-compose.yml"
  fi
}

# <remote>: true when auto may use it: it answers, and a build=manual host already has this run's image and a seed
# that a warm-up with this image completed (the volume $WARMED, created at the end of a warm-up). auto never builds or
# warms there; that is done on purpose with an explicit --host. Asked once per remote and run, and only when auto gets
# to that remote (a remote behind one with a free slot is never asked).
remote_ready() {
  _rv=ready_$(mq_env_name "$1")
  _rr=$(eval "echo \"\${$_rv:-}\"")
  if [ -z "$_rr" ]; then
    _rr=no
    if ! mq_reachable "$1"; then
      say "remote $1 unreachable, skipping it"
    elif ! mq_build_manual "$1"; then
      _rr=yes
    else
      mq_use_host "$1"
      if dk image inspect "$IMAGE" >/dev/null 2>&1 && dk volume inspect "$WARMED" >/dev/null 2>&1; then
        _rr=yes
      else
        say "remote $1 is not ready for auto (no $IMAGE or no warm-up with it; run.sh --warm --host $1 at a quiet hour), skipping it"
      fi
    fi
    eval "$_rv=$_rr"
  fi
  [ "$_rr" = yes ]
}

# On a build=manual remote a test run never builds or warms: it needs the image and a completed warm-up.
require_warmed() {
  mq_is_remote && mq_build_manual "$MQ_HOST" || return 0
  dk image inspect "$IMAGE" >/dev/null 2>&1 && dk volume inspect "$WARMED" >/dev/null 2>&1 && return 0
  die "$MQ_HOST builds only on request: no $IMAGE or no warm-up with it there; run run.sh --warm --host $MQ_HOST at a quiet hour (e2e/README.md, Hosts)"
}

# Takes a free slot directory on the current host (mkdir is atomic).
take_free() { # <label>
  i=1
  while [ "$i" -le "$SLOTS" ]; do
    if mkdir "$SLOT_DIR/slot$i" 2>/dev/null; then
      slot="$SLOT_DIR/slot$i"
      echo "$token" > "$slot/token"
      echo "pid $$ stack $1 on $MQ_HOST in $(pwd) since $(date '+%Y-%m-%d %H:%M:%S')" > "$slot/owner"
      say "slot $i of $SLOTS on $MQ_HOST taken for $1"
      return 0
    fi
    i=$((i + 1))
  done
  return 1
}

# Breaks the slots of the current host that belong to runs which died without their EXIT trap (killed process tree,
# crashed machine): a slot whose client container no longer runs, or one older than STALE_MIN. One "docker ps" asks
# the host which stacks still run; when the host cannot be asked, only the age counts. The move is atomic; a slot
# released and taken again between the check and the move is recognised by its token and put back.
break_dead() {
  running=""
  asked=""
  if running=$(dk ps --filter label=com.docker.compose.service=client \
      --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null); then
    asked=1
    if [ -n "$(dk ps -q --filter 'name=^mineqtt-client-warm$' 2>/dev/null)" ]; then running="$running mineqtt-client-warm"; fi
    running=" $(echo $running) "
  fi
  broke=""
  for d in "$SLOT_DIR"/slot*; do
    case "$d" in *.stale.*) continue ;; esac
    [ -d "$d" ] || continue
    tok=$(cat "$d/token" 2>/dev/null || true)
    st=$(cat "$d/stack" 2>/dev/null || true)
    if [ -n "$(find "$d" -maxdepth 0 -mmin +"$STALE_MIN" 2>/dev/null)" ]; then
      why="older than $STALE_MIN min"
    elif [ -n "$st" ] && [ -n "$asked" ]; then
      case "$running" in *" mineqtt-client-$st "*) continue ;; esac
      why="stack $st is gone from $MQ_HOST"
    else
      continue
    fi
    mv "$d" "$d.stale.$$" 2>/dev/null || continue
    if [ "$(cat "$d.stale.$$/token" 2>/dev/null || true)" != "$tok" ]; then
      mv -T "$d.stale.$$" "$d" 2>/dev/null || rm -rf "$d.stale.$$"
      continue
    fi
    echo "client-test: broke slot $(basename "$d") on $MQ_HOST ($why): $(cat "$d.stale.$$/owner" 2>/dev/null || echo '?')" >&2
    rm -rf "$d.stale.$$"
    # The stopped containers and the network of the dead run.
    if [ -n "$st" ] && [ "$st" != warm ]; then
      $(compose_for "$st") down --remove-orphans --timeout 10 >/dev/null 2>&1 || true
    fi
    broke=1
  done
  [ -n "$broke" ]
}

try_slot() { # <label>: one attempt on the current host
  mkdir -p "$SLOT_DIR"
  take_free "$1" && return 0
  break_dead && take_free "$1" && return 0
  return 1
}

owners() { cat "$SLOT_DIR"/slot[0-9]/owner "$SLOT_DIR"/slot[0-9][0-9]/owner 2>/dev/null | tr '\n' ';'; }

# Takes a slot on the host the --host mode allows, waiting while all are taken. Under auto: the ready remotes in the
# order of docker/hosts.sh, then this workstation; a warm-up never goes to a build=manual remote under auto.
acquire_slot() { # <label>
  said=""
  if [ "$MQ_HOST_MODE" = auto ]; then
    while :; do
      full=""
      for r in $(mq_remotes) local; do
        if [ "$r" != local ]; then
          if [ "$warm" = 1 ] && mq_build_manual "$r"; then continue; fi
          remote_ready "$r" || continue
        fi
        select_host "$r"
        if try_slot "$1"; then
          [ -z "$full" ] || say "full:$full; running on $r"
          return 0
        fi
        full="$full $r ($SLOTS of $SLOTS)"
      done
      [ -n "$said" ] || say "all slots taken on$full, waiting"
      said=1
      sleep 10
    done
  fi
  if [ "$MQ_HOST_MODE" = remote ]; then select_host "$MQ_PICK"; else select_host local; fi
  until try_slot "$1"; do
    [ -n "$said" ] || say "all $SLOTS slots on $MQ_HOST taken ($(owners)), waiting"
    said=1
    sleep 10
  done
}

# The host each stack id last ran on, so a later run of the id looks for its leftover there only (and auto does not
# ask every remote on every run).
ID_DIR=$SLOT_BASE-ids
last_host() { cat "$ID_DIR/$1" 2>/dev/null || true; }

# Left by a killed run of this id on the current host: removed, together with that run's slot. Ids are unique per
# run, so this never ends another run.
remove_leftover() { # <id>
  c=$(compose_for "$1")
  [ -n "$($c ps -qa 2>/dev/null)" ] || return 0
  echo "client-test: removing leftover stack mineqtt-client-$1 on $MQ_HOST" >&2
  $c down --remove-orphans --timeout 10 >/dev/null 2>&1 || true
  for d in "$SLOT_DIR"/slot*; do
    case "$d" in *.stale.*) continue ;; esac
    if [ "$(cat "$d/stack" 2>/dev/null || true)" = "$1" ] && mv "$d" "$d.stale.$$" 2>/dev/null; then
      echo "client-test: freed its slot $(basename "$d")" >&2
      rm -rf "$d.stale.$$"
    fi
  done
}

# Marks the slot as up (from now on a stopped client container frees it) and feeds the container's watchdog while this
# script lives: one "docker exec -i ... mq beat" fed a line every 15 s, opened again 5 s after it drops (on the remote
# it is one SSH connection, which a network hiccup can cut while the run itself goes on; the next beat then fails on the
# closed pipe). Killing the process tree closes the pipe, which ends the beats; kill -0 ends both loops should they
# outlive this script.
stack_started() { # <stack id> <container>
  echo "$1" > "$slot/stack"
  mkdir -p "$ID_DIR" && echo "$MQ_HOST" > "$ID_DIR/$1"
  (
    while kill -0 $$ 2>/dev/null; do
      ( while kill -0 $$ 2>/dev/null; do echo beat || exit 0; sleep 15; done ) 2>/dev/null \
        | dk exec -i "$2" mq beat >/dev/null 2>&1 || true   # set -e would end the loop on the failed exec
      kill -0 $$ 2>/dev/null || break
      echo "client-test: heartbeat connection to $MQ_HOST dropped, opening it again" >&2
      sleep 5
    done
  ) &
  beat_pid=$!
}

# The client image is tagged with a hash of its recipe, so runs of different checkouts never swap each other's image
# and a recipe change builds a new one. Built on the current host when missing (on a build=manual remote only by
# --warm); ctx_bytes is the build context sent. WARMED, an empty volume, records that a warm-up with this image
# completed on that host.
IMAGE=mineqtt-client:$(cat docker/client-test/Dockerfile docker/client-test/in-container.sh | tr -d '\r' | sha256sum | cut -c1-12)
WARMED=mineqtt-client-warmed-${IMAGE#mineqtt-client:}
ctx_bytes=0
ensure_image() {
  dk image inspect "$IMAGE" >/dev/null 2>&1 && return 0
  if mq_is_remote && mq_build_manual "$MQ_HOST" && [ "$warm" != 1 ]; then require_warmed; fi
  say "building $IMAGE on $MQ_HOST"
  if ! blog=$(dk build --progress plain -t "$IMAGE" --label "mineqtt.owner=$MQ_OWNER" \
      --label "mineqtt.created=$(date +%s)" docker/client-test 2>&1); then
    echo "$blog" >&2
    die "image build failed on $MQ_HOST"
  fi
  ctx_bytes=$(echo "$blog" | sed -n 's/.*transferring context: \([0-9.]*\)\([kMG]*B\).*/\1 \2/p' | tail -n 1 \
    | awk '{ m = ($2 == "kB") ? 1000 : ($2 == "MB") ? 1000000 : ($2 == "GB") ? 1000000000 : 1; printf "%d", $1 * m }')
  ctx_bytes=${ctx_bytes:-0}
}
ensure_seed() {
  dk volume inspect "$SEED" >/dev/null 2>&1 \
    || dk volume create --label "mineqtt.owner=$MQ_OWNER" --label "mineqtt.created=$(date +%s)" "$SEED" >/dev/null
}

# Tars what git sees in the working tree, gzipped (6.5 MB raw, ~2.3 MB packed). A tracked file deleted in the working
# tree is still listed by -c; tar warns about it and skips it (--ignore-failed-read).
ship() { # <tar.gz file>
  git ls-files -co --exclude-standard --deduplicate -z | tar --null --ignore-failed-read -cf - -T - | gzip -6 > "$1"
}

bytes() { if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else echo 0; fi; }

# What crossed the link (on the remote) or the Docker socket (locally): payload bytes, without the SSH/API overhead.
report_transfer() { # <sent payload> <what> <received payload> <what>
  where="link to $MQ_HOST"
  mq_is_remote || where="local Docker (no link)"
  say "$where: sent $(mq_size "$1") ($2), received $(mq_size "$3") ($4)"
}

export MINEQTT_WATCHDOG="$WATCHDOG" MINEQTT_OWNER="$MQ_OWNER" MINEQTT_CREATED MINEQTT_CLIENT_IMAGE MINEQTT_MOSQUITTO_CONF
MINEQTT_CREATED=$(date +%s)
MINEQTT_CLIENT_IMAGE=$IMAGE
MINEQTT_MOSQUITTO_CONF=$(tr -d '\r' < docker/client-test/mosquitto.conf)
started=$(date +%s)

if [ "$warm" = 1 ]; then
  out=build/docker-client-test/warm
  mkdir -p "$out"
  ship "$out/src.tar.gz"
  acquire_slot warm
  # A build=manual remote runs production work: never write its seed while its test stacks copy from it.
  if mq_is_remote && mq_build_manual "$MQ_HOST" \
      && dk ps --filter label=com.docker.compose.service=client --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null \
        | tr -d '\r' | grep -q '^mineqtt-client-'; then
    die "client test stacks run on $MQ_HOST; warm it when none does (lane.sh list, remote-gc.sh --host $MQ_HOST --hours 0)"
  fi
  ensure_seed
  ensure_image
  # The only writer of the seed: it is mounted writable here and read-only in every test stack.
  # Run it while no test stack runs on that host: a stack seeding itself during the warm-up could copy a half-written
  # cache.
  if dk container inspect mineqtt-client-warm >/dev/null 2>&1; then
    die "another warm-up is running on $MQ_HOST (container mineqtt-client-warm; if it is left over: docker $MQ_ENDPOINT rm -f mineqtt-client-warm)"
  fi
  # shellcheck disable=SC2046
  dk run -d --rm --name mineqtt-client-warm --label "mineqtt.owner=$MQ_OWNER" --label "mineqtt.created=$MINEQTT_CREATED" \
    --cpus "${CLIENT_CPUS:-4}" --memory "${CLIENT_MEMORY:-8g}" $(mq_run_limits) \
    -e MINEQTT_WATCHDOG="$WATCHDOG" -v "$SEED":/root/.gradle "$IMAGE" >/dev/null
  warm_ctr=mineqtt-client-warm
  stack_started warm "$warm_ctr"
  dk cp "$out/src.tar.gz" "$warm_ctr:/tmp/src.tar.gz"
  dk exec "$warm_ctr" mq extract
  dk exec "$warm_ctr" mq warm
  src_bytes=$(bytes "$out/src.tar.gz")
  rm -f "$out/src.tar.gz"
  dk volume inspect "$WARMED" >/dev/null 2>&1 \
    || dk volume create --label "mineqtt.owner=$MQ_OWNER" --label "mineqtt.created=$(date +%s)" "$WARMED" >/dev/null
  say "seed $SEED on $MQ_HOST is warm ($(( $(date +%s) - started ))s)"
  report_transfer $((src_bytes + ctx_bytes)) "source $src_bytes B, build context $ctx_bytes B" 0 "console output not counted"
  exit 0
fi

out="build/docker-client-test/$name"
mkdir -p "$out"
ship "$out/src.tar.gz"
src_bytes=$(bytes "$out/src.tar.gz")

case "$MQ_HOST_MODE" in
  local) select_host local; remove_leftover "$name" ;;
  remote) select_host "$MQ_PICK"; remove_leftover "$name" ;;
  auto)
    select_host local; remove_leftover "$name"
    prev=$(last_host "$name")
    if [ -n "$prev" ] && [ "$prev" != local ] && mq_is_known "$prev" && mq_reachable "$prev"; then
      select_host "$prev"; remove_leftover "$name"
    fi ;;
esac

acquire_slot "$name"
require_warmed
[ -n "$shots" ] || { if mq_is_remote; then shots=failed; else shots=all; fi; }
ensure_seed
ensure_image
compose=$(compose_for "$name")
if ! uplog=$($compose up -d --quiet-pull 2>&1); then
  echo "$uplog" >&2
  die "could not start stack mineqtt-client-$name on $MQ_HOST"
fi
ctr=$($compose ps -q client)
stack_started "$name" "$ctr"

dk cp "$out/src.tar.gz" "$ctr:/tmp/src.tar.gz"
dk exec "$ctr" mq extract

output=full
mq_is_remote && output=filtered
{ trc=0; dk exec -e MQ_OUTPUT="$output" -e MQ_TEST_TIMEOUT="$((TEST_TIMEOUT_MIN * 60))" -e MINEQTT_CLIENT_HEAP="$CLIENT_HEAP" \
    "$ctr" mq test $suites || trc=$?; echo "$trc" > "$out/.rc"; } | tee "$out/.stream"
rc=$(cat "$out/.rc")
stream_bytes=$(bytes "$out/.stream")
rm -f "$out/.rc" "$out/.stream"

rm -rf "$out/screenshots" "$out/mineqtt-gametest-report.txt" "$out/latest.log" "$out/console.log" "$out/results.tar.gz"
dk exec "$ctr" mq collect "$shots" > "$out/results.tar.gz" || say "could not collect the results"
res_bytes=$(bytes "$out/results.tar.gz")
tar -xzf "$out/results.tar.gz" -C "$out" 2>/dev/null || say "could not unpack the results"
rm -f "$out/results.tar.gz" "$out/src.tar.gz"
n_shots=$(ls "$out/screenshots" 2>/dev/null | wc -l | tr -d ' ')

say "results in $out (exit $rc, host $MQ_HOST, $(( $(date +%s) - started ))s, screenshots: $shots, $n_shots files)"
report_transfer $((src_bytes + ctx_bytes)) "source $src_bytes B, build context $ctx_bytes B" \
  $((res_bytes + stream_bytes)) "results $res_bytes B, console $output $stream_bytes B"
exit "$rc"

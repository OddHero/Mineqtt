#!/bin/sh
# Removes MineQTT test stacks left on the remote Docker hosts: compose projects mineqtt-client* and mineqtt-lane*
# (containers, networks, volumes) and the warm-up container, older than N hours and no longer in use. Dry run unless
# --apply.
#   sh docker/client-test/remote-gc.sh [--hours N] [--apply] [--force] [--all-owners] [--host H]
#     --hours N       age limit (default 12), from the mineqtt.created label (container creation time without it)
#     --apply         remove them; without it the script only lists what it would remove
#     --force         also stacks still in use (see below); check with whoever runs them first
#     --all-owners    also stacks of other workstations and stacks without a mineqtt.owner label (made by older
#                     scripts); by default only this workstation's (mineqtt.owner=$MINEQTT_OWNER, default host name)
#     --host H        one Docker host: a remote of docker/hosts.sh or "local" (default: every remote of the table, one
#                     after the other; an unreachable one is reported and skipped)
# In use: a lane with any running container, a client stack whose client container runs, a running warm-up container.
# The age alone says nothing (a lane keeps its creation time through reload and reset), and the owner is the
# workstation, shared by everything that runs there. A client stack whose run died stops its client container by itself (watchdog)
# and is then removable; a lane that someone forgot keeps running, so it is listed as skipped and needs --force.
# Images and the Gradle seed volume (mineqtt-client-gradle) are never touched: they carry no compose project label.
# Only projects named mineqtt-client* / mineqtt-lane* are ever looked at; other projects on a shared host (also ones
# named mineqtt-<something else>, such as a website) are invisible to this script. It is the only cleanup the MineQTT
# tools do on a shared host: never prune images, volumes or the system there.
set -eu
export MSYS_NO_PATHCONV=1
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"   # absolute: the --host all loop runs it again after the cd
cd "$(dirname "$0")/../.."
. docker/hosts.sh

hours=12
apply=""
force=""
all=""
host=all
while [ "$#" -gt 0 ]; do
  case "$1" in
    --hours) [ "$#" -ge 2 ] || { echo "--hours needs a value" >&2; exit 2; }; hours=$2; shift ;;
    --hours=*) hours=${1#--hours=} ;;
    --apply) apply=1 ;;
    --force) force=1 ;;
    --all-owners) all=1 ;;
    --host) [ "$#" -ge 2 ] || { echo "--host needs a value" >&2; exit 2; }; host=$2; shift ;;
    --host=*) host=${1#--host=} ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
case "$hours" in ''|*[!0-9]*) echo "--hours takes whole hours" >&2; exit 2 ;; esac
if [ "$host" = all ] || [ "$host" = auto ]; then
  rc=0
  for r in $(mq_all_remotes); do
    echo "== $r"
    if ! mq_reachable "$r"; then echo "$r: unreachable, skipped"; rc=1; continue; fi
    sh "$SELF" --hours "$hours" ${apply:+--apply} ${force:+--force} ${all:+--all-owners} --host "$r" || rc=1
  done
  exit "$rc"
fi
mq_parse_host "$host" || exit 2
if [ "$MQ_HOST_MODE" = local ]; then mq_use_host local; else mq_require "$MQ_PICK" || exit 2; mq_use_host "$MQ_PICK"; fi

now=$(date +%s)
limit=$((hours * 3600))

# One line per resource: <project> <created epoch or -> <owner or -> <creation time as Docker prints it>
{
  dk ps -a --filter label=com.docker.compose.project \
    --format '{{.Label "com.docker.compose.project"}}|{{.Label "mineqtt.created"}}|{{.Label "mineqtt.owner"}}|{{.CreatedAt}}'
  dk ps -a --filter 'name=^mineqtt-client-warm$' \
    --format 'mineqtt-client-warm|{{.Label "mineqtt.created"}}|{{.Label "mineqtt.owner"}}|{{.CreatedAt}}'
  dk network ls --filter label=com.docker.compose.project \
    --format '{{.Label "com.docker.compose.project"}}|{{.Label "mineqtt.created"}}|{{.Label "mineqtt.owner"}}|{{.CreatedAt}}'
  dk volume ls --filter label=com.docker.compose.project \
    --format '{{.Label "com.docker.compose.project"}}|{{.Label "mineqtt.created"}}|{{.Label "mineqtt.owner"}}|'
} | tr -d '\r' | grep -E '^mineqtt-(client|lane)' > "${TMPDIR:-/tmp}/mineqtt-gc.$$" || true

projects=$(cut -d'|' -f1 "${TMPDIR:-/tmp}/mineqtt-gc.$$" | sort -u)

# The stacks in use (header): asked from the running containers. A failed listing ends the script (set -e) instead of
# treating every stack as idle.
running=$(dk ps --filter label=com.docker.compose.project \
  --format '{{.Label "com.docker.compose.project"}}|{{.Label "com.docker.compose.service"}}')
warm=$(dk ps -q --filter 'name=^mineqtt-client-warm$')
inuse=" $(printf '%s\n' "$running" | tr -d '\r' \
  | awk -F'|' '$1 ~ /^mineqtt-lane/ || ($1 ~ /^mineqtt-client/ && $2 == "client") { print $1 }' | sort -u | tr '\n' ' ') "
[ -z "$warm" ] || inuse="$inuse mineqtt-client-warm "

found=0
skipped=0
for p in $projects; do
  created=""
  owner=""
  while IFS='|' read -r proj c o at; do
    [ "$proj" = "$p" ] || continue
    [ -n "$o" ] && owner=$o
    if [ -z "$c" ] && [ -n "$at" ]; then
      # "2026-10-10 23:33:55 +0200 CEST": GNU date reads it without the zone name.
      c=$(date -d "$(echo "$at" | awk '{ print $1, $2, $3 }')" +%s 2>/dev/null || true)
    fi
    if [ -n "$c" ] && { [ -z "$created" ] || [ "$c" -lt "$created" ]; }; then created=$c; fi
  done < "${TMPDIR:-/tmp}/mineqtt-gc.$$"
  if [ -z "$all" ] && [ "$owner" != "$MQ_OWNER" ]; then continue; fi
  if [ -z "$created" ]; then
    echo "skip $p: no creation time"
    continue
  fi
  age=$((now - created))
  [ "$age" -gt "$limit" ] || continue
  desc="$p (owner ${owner:-none}, $((age / 3600)) h $((age % 3600 / 60)) min old) on $MQ_HOST"
  case "$inuse" in
    *" $p "*)
      if [ -z "$force" ]; then
        echo "skip $desc: in use (--force removes it)"
        skipped=$((skipped + 1))
        continue
      fi
      desc="$desc, in use" ;;
  esac
  found=$((found + 1))
  if [ -z "$apply" ]; then
    echo "would remove $desc"
    continue
  fi
  echo "removing $desc"
  if [ "$p" = mineqtt-client-warm ]; then
    dk rm -f mineqtt-client-warm >/dev/null 2>&1 || true
    continue
  fi
  ids=$(dk ps -aq --filter "label=com.docker.compose.project=$p" | tr -d '\r')
  [ -z "$ids" ] || dk rm -f $ids >/dev/null
  ids=$(dk network ls -q --filter "label=com.docker.compose.project=$p" | tr -d '\r')
  [ -z "$ids" ] || dk network rm $ids >/dev/null
  ids=$(dk volume ls -q --filter "label=com.docker.compose.project=$p" | tr -d '\r')
  [ -z "$ids" ] || dk volume rm $ids >/dev/null
done
rm -f "${TMPDIR:-/tmp}/mineqtt-gc.$$"
if [ "$found" = 0 ]; then
  echo "no idle MineQTT stacks older than $hours h on $MQ_HOST$([ -n "$all" ] || echo " owned by $MQ_OWNER")"
elif [ -z "$apply" ]; then
  echo "dry run: $found stack(s); rerun with --apply to remove them"
fi
[ "$skipped" = 0 ] || echo "$skipped stack(s) in use left alone; ask whoever runs them, or add --force"

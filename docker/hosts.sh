# Docker host selection shared by docker/client-test/run.sh, docker/client-test/remote-gc.sh and e2e/lanes/lane.sh
# (POSIX sh, sourced, not run).
#
# Hosts: "local" (this workstation's Docker) and the remote Docker hosts of the host table (below), reached over SSH.
# Every docker and compose call goes through dk(), which adds the selected host's endpoint. e2e/README.md, section
# "Hosts", has the rules for remote hosts; read them before you add one or change its numbers.
#
#   --host local|auto|<remote>   (or MINEQTT_DOCKER_HOST; default auto)
#     local     this workstation (context MINEQTT_LOCAL_CONTEXT, default: the current context, else desktop-linux)
#     <remote>  a remote of the table, by its name or its SSH name (user@host or host); "remote" means the first one
#     auto      the first remote, in table order, whose Docker daemon answers within 15 s, that is ready (a host with
#               build=manual only once its images and seed exist) and has room (the caller decides what room means),
#               else this workstation
#   MINEQTT_REMOTES          which remotes auto may use, in order (default: all of the table, in table order); an
#                            explicit --host may still name any remote of the table
#   MINEQTT_OWNER            value of the mineqtt.owner label on every resource (default: this machine's host name)
#
# Every resource the tools create carries the labels mineqtt.owner=<owner> and mineqtt.created=<epoch seconds>
# (docker/client-test/remote-gc.sh removes stacks by them).
#
# The remote hosts, in the order auto tries them, one line per host (docker/hosts.example has the format). The table
# is not part of the repository; it is read from the first of these files that exists:
#   1. $MINEQTT_HOSTS_FILE
#   2. <git common dir>/mineqtt-docker-hosts (shared by every worktree of the clone)
#   3. docker/hosts.local (ignored by git)
# Lines starting with # and blank lines are skipped. Without a table there are no remotes and auto runs locally.
# Callers source this file from the repository root, or set MQ_REPO to it first. Columns:
#   name      short name: --host value, slot dir suffix, display name
#   endpoint  ssh://user@host (docker -H; docker.exe on Windows uses Windows OpenSSH, so the key and the known_hosts
#             entry must be in %USERPROFILE%\.ssh), or the name of a Docker context
#   slots     client test stacks at once (run.sh)
#   cpus mem  size of one client stack (run.sh; CLIENT_CPUS / CLIENT_MEMORY override it)
#   lanes     lanes at once (lane.sh, checked by auto)
#   cpuset    CPUs every MineQTT container is pinned to, or - for none
#   shares    docker --cpu-shares of every MineQTT container, or - for Docker's default weight
#   build     auto: images are built and the seed filled whenever a run needs them; manual: only on an explicit
#             --host (lane.sh build, run.sh --warm), and auto skips the host until they exist
# Per-host overrides: MINEQTT_SLOTS_<NAME> and MINEQTT_LANES_<NAME> (name upper-cased, - as _), e.g.
# MINEQTT_SLOTS_BUILD_HOST=8.
MQ_REPO=${MQ_REPO:-.}
# The file the table comes from, or nothing.
mq_hosts_file() {
  if [ -n "${MINEQTT_HOSTS_FILE:-}" ]; then
    if [ -r "$MINEQTT_HOSTS_FILE" ]; then echo "$MINEQTT_HOSTS_FILE"; return 0; fi
    echo "docker/hosts.sh: MINEQTT_HOSTS_FILE $MINEQTT_HOSTS_FILE is not readable, ignoring it" >&2
  fi
  _mqg=$(git -C "$MQ_REPO" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
  if [ -n "$_mqg" ] && [ -r "$_mqg/mineqtt-docker-hosts" ]; then echo "$_mqg/mineqtt-docker-hosts"; return 0; fi
  if [ -r "$MQ_REPO/docker/hosts.local" ]; then echo "$MQ_REPO/docker/hosts.local"; fi
  return 0
}
MQ_HOSTS_FILE=$(mq_hosts_file)
MQ_HOST_TABLE=""
if [ -n "$MQ_HOSTS_FILE" ]; then
  # CRs are stripped: a table edited on Windows may have CRLF line ends.
  MQ_HOST_TABLE=$(tr -d '\r' < "$MQ_HOSTS_FILE" | sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d')
fi
MQ_OWNER=${MINEQTT_OWNER:-$(hostname 2>/dev/null || echo unknown)}
MQ_HOST_MODE=""   # local, auto or remote (after mq_parse_host)
MQ_PICK=""        # the remote's name when MQ_HOST_MODE is remote
MQ_HOST=""        # the selected host: local or a remote's name (after mq_use_host)
MQ_ENDPOINT=""    # how dk() reaches it
MQ_CPUSET=""      # the selected host's cpuset and cpu shares, empty for none (compose files and docker run read them)
MQ_CPU_SHARES=""
export MQ_CPUSET MQ_CPU_SHARES

mq_all_remotes() { printf '%s\n' "$MQ_HOST_TABLE" | awk 'NF >= 9 { print $1 }'; }
# The remotes auto may use, in order.
mq_remotes() {
  if [ -n "${MINEQTT_REMOTES:-}" ]; then
    for _mqr in $MINEQTT_REMOTES; do
      if mq_is_known "$_mqr"; then echo "$_mqr"; else echo "docker/hosts.sh: MINEQTT_REMOTES: unknown host $_mqr" >&2; fi
    done
  else
    mq_all_remotes
  fi
}
mq_is_known() { printf '%s\n' "$MQ_HOST_TABLE" | awk -v n="$1" 'NF >= 9 && $1 == n { f = 1 } END { exit !f }'; }
# <name> <column 2..9>: one value of the table ("-" becomes empty).
mq_field() {
  printf '%s\n' "$MQ_HOST_TABLE" | awk -v n="$1" -v c="$2" 'NF >= 9 && $1 == n { v = $c; if (v == "-") v = ""; print v; exit }'
}
mq_env_name() { echo "$1" | tr 'a-z-' 'A-Z_'; }
# <name>: a remote's settings, with the per-host overrides applied.
mq_slots() { _mqv=$(eval "echo \"\${MINEQTT_SLOTS_$(mq_env_name "$1"):-}\""); echo "${_mqv:-$(mq_field "$1" 3)}"; }
mq_lanes() { _mqv=$(eval "echo \"\${MINEQTT_LANES_$(mq_env_name "$1"):-}\""); echo "${_mqv:-$(mq_field "$1" 6)}"; }
mq_build_manual() { [ "$(mq_field "$1" 9)" = manual ]; }

# The context "local" uses. "local" gets no cpuset, no weight and builds freely, so it must never reach a remote of the
# table: not through a shell switched to a remote's context, not through DOCKER_HOST (the context "default" follows
# it), and not through a context of another name that points at a remote. Such an endpoint fails here.
mq_local_context() {
  if [ -n "${mq_local_ctx:-}" ]; then echo "$mq_local_ctx"; return; fi
  if [ -n "${MINEQTT_LOCAL_CONTEXT:-}" ]; then
    _mqc=$MINEQTT_LOCAL_CONTEXT
  else
    _mqc=${DOCKER_CONTEXT:-$(docker context show 2>/dev/null || true)}
    if [ -z "$_mqc" ] || mq_is_known "$_mqc" || { [ "$_mqc" = default ] && [ -n "${DOCKER_HOST:-}" ]; }; then
      _mqc=desktop-linux
    fi
  fi
  _mqe=$(docker context inspect "$_mqc" --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)
  if [ "$_mqc" = default ] && [ -n "${DOCKER_HOST:-}" ]; then _mqe=$DOCKER_HOST; fi
  for _mqr in $(mq_all_remotes); do
    _mqs=$(mq_field "$_mqr" 2); _mqs=${_mqs#ssh://}; _mqs=${_mqs#tcp://}
    case "$_mqe" in
      *"$_mqs"*|*"${_mqs#*@}"*)
        echo "docker/hosts.sh: the local Docker context '$_mqc' points at remote $_mqr ($_mqe); set" \
          "MINEQTT_LOCAL_CONTEXT to this workstation's context (desktop-linux) or use --host $_mqr" >&2
        return 2 ;;
    esac
  done
  mq_local_ctx=$_mqc
  echo "$_mqc"
}

# <value>: sets MQ_HOST_MODE to local, remote (MQ_PICK: which) or auto; returns 2 for an unknown value.
mq_parse_host() {
  MQ_PICK=""
  case "$1" in
    local|auto) MQ_HOST_MODE=$1; return 0 ;;
    remote) MQ_HOST_MODE=remote; MQ_PICK=$(mq_remotes | head -n 1); [ -n "$MQ_PICK" ] && return 0 ;;
  esac
  for _mqr in $(mq_all_remotes); do
    _mqs=$(mq_field "$_mqr" 2); _mqs=${_mqs#ssh://}
    case "$1" in
      "$_mqr"|"$_mqs"|"${_mqs#*@}") MQ_HOST_MODE=remote; MQ_PICK=$_mqr; return 0 ;;
    esac
  done
  echo "unknown host '$1': use local, auto or one of: $(mq_all_remotes | tr '\n' ' ')" >&2
  return 2
}

# <name>: true when that remote's Docker daemon answers within 15 s. Every real call goes the same way, so the probe
# asks the daemon, not only SSH: a daemon that is down or restarting, or a key that docker.exe (Windows OpenSSH) lacks,
# counts as unreachable. Probed once per remote and script run.
mq_reachable() {
  _mqvar=mq_reach_$(mq_env_name "$1")
  _mqr=$(eval "echo \"\${$_mqvar:-}\"")
  if [ -z "$_mqr" ]; then
    _mqr=no
    # shellcheck disable=SC2046
    if command -v timeout >/dev/null 2>&1; then
      timeout 15 docker $(mq_endpoint_args "$1") version >/dev/null 2>&1 </dev/null && _mqr=yes
    else
      docker $(mq_endpoint_args "$1") version >/dev/null 2>&1 </dev/null && _mqr=yes
    fi
    eval "$_mqvar=$_mqr"
  fi
  [ "$_mqr" = yes ]
}

# <name>: fails with a clear message when that remote was asked for explicitly and cannot be reached.
mq_require() {
  mq_reachable "$1" && return 0
  echo "remote Docker host $1 is unreachable (docker $(mq_endpoint_args "$1") version failed or took over 15 s);" \
    "use --host local, --host auto or another remote" >&2
  return 1
}

# <name>: the docker arguments that reach a remote: -H for an ssh:// or tcp:// endpoint, --context for a context name.
mq_endpoint_args() {
  _mqe=$(mq_field "$1" 2)
  case "$_mqe" in
    ssh://*|tcp://*) echo "-H $_mqe" ;;
    *) echo "--context $_mqe" ;;
  esac
}

# local|<remote>: selects the host every dk() call goes to, with its cpuset and cpu shares (empty for local).
mq_use_host() {
  case "$1" in
    local)
      # Resolved once per script run, in this shell (a failure must stop the caller, never leave --context empty).
      if [ -z "${mq_local_ctx:-}" ]; then mq_local_ctx=$(mq_local_context) || return 2; fi
      MQ_HOST=local; MQ_ENDPOINT="--context $mq_local_ctx"; MQ_CPUSET=""; MQ_CPU_SHARES="" ;;
    *)
      mq_is_known "$1" || { echo "mq_use_host: unknown host $1" >&2; return 2; }
      MQ_HOST=$1; MQ_ENDPOINT=$(mq_endpoint_args "$1"); MQ_CPUSET=$(mq_field "$1" 7); MQ_CPU_SHARES=$(mq_field "$1" 8)
      # The *.limits.yml compose files set both keys, so a host sets both or neither.
      if { [ -n "$MQ_CPUSET" ] && [ -z "$MQ_CPU_SHARES" ]; } || { [ -z "$MQ_CPUSET" ] && [ -n "$MQ_CPU_SHARES" ]; }; then
        echo "docker/hosts.sh: host $1 needs both cpuset and shares, or neither" >&2; return 2
      fi ;;
  esac
}
mq_is_remote() { [ "$MQ_HOST" != local ]; }
# True when the selected host pins and weighs its MineQTT containers (the compose files then add their *.limits.yml).
mq_has_limits() { [ -n "$MQ_CPUSET$MQ_CPU_SHARES" ]; }
# docker run arguments for the selected host's cpuset and cpu shares (none when unset).
mq_run_limits() {
  [ -z "$MQ_CPUSET" ] || printf ' --cpuset-cpus %s' "$MQ_CPUSET"
  [ -z "$MQ_CPU_SHARES" ] || printf ' --cpu-shares %s' "$MQ_CPU_SHARES"
}

# shellcheck disable=SC2086
dk() { docker $MQ_ENDPOINT "$@"; }

# Bytes as a short human-readable size.
mq_size() {
  awk -v b="$1" 'BEGIN { if (b >= 1048576) printf "%.1f MB", b / 1048576; else if (b >= 1024) printf "%.0f KB", b / 1024; else printf "%d B", b }'
}

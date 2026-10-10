#!/usr/bin/env bash
# One isolated MineQTT test lane per test run: broker + Fabric 26.2 dedicated server (+ Home Assistant with --ha), on this
# workstation or on one of the remote Docker hosts (docker/hosts.sh lists them). Works from Git Bash on Windows and
# from Linux/macOS shells. Needs docker (Compose v2) and python 3.
#
#   lane.sh build [--host H]            build the server image (up does it when it is missing; auto: here and on every
#                                       reachable remote with build=auto, never on a build=manual one)
#   lane.sh up <id> [--ha] [--jar] [--host H]   start the lane and wait until the server is up and MQTT connected
#                                       (--jar: ./gradlew :fabric:assemble first; the host-built jar is shipped)
#   lane.sh reload <id> [--jar]         ship the jar again and restart the server (world and config kept)
#   lane.sh wait <id>                   start the server if stopped and wait until it is healthy
#   lane.sh reset <id>                  new world: remove the server and its world volume, start again
#   lane.sh down <id>                   remove everything of the lane, volumes included
#   lane.sh rcon <id> <command...>      RCON command (no leading slash), prints the reply
#   lane.sh pub <id> <mosquitto_pub args...>   e.g. pub 1 -t mineqtt/x -m on
#   lane.sh sub <id> <mosquitto_sub args...>   e.g. sub 1 -v -t 'mineqtt/#' -W 5
#   lane.sh smoke <id>                  place a Smart Lamp, print /mineqtt status and the bridge state topic
#   lane.sh ports <id>                  host endpoints (RCON, MQTT, game, Home Assistant); a remote lane has none
#   lane.sh status <id>                 host, containers, health and endpoints
#   lane.sh stats <id>                  CPU and memory of the lane containers (one sample)
#   lane.sh logs <id> [service] [docker compose logs args...]   default service: server
#   lane.sh ha-onboard <id>             onboard the lane's Home Assistant and add the MQTT integration
#   lane.sh ha <id> <ha.py args...>     e2e/ha.py against the lane's Home Assistant (devices, entities, state ...)
#   lane.sh list                        lanes on this workstation and on every remote
#
# --host local|auto|<remote> (also before the command; default MINEQTT_DOCKER_HOST, else auto) picks the Docker host
# for up and build: auto takes the first remote of docker/hosts.sh whose Docker daemon answers, that is ready (a
# build=manual host only once it has the server image: lane.sh build --host <it>) and runs fewer lanes than its row
# allows (MINEQTT_LANES_<NAME> overrides it), else this workstation. Every later command finds the lane's host by
# itself (recorded on up in MINEQTT_LANE_STATE_DIR, default ~/.mineqtt-lanes). A remote lane publishes no host ports:
# rcon, pub, sub, smoke and ha go through "docker compose exec" on its host, and the jar (~6.5 MB) crosses the link on
# up and reload. On a host with a cpuset and cpu shares, every lane container gets them (docker-compose.limits.yml).
#
# Environment: MINEQTT_LANE_PREFIX (project name prefix, default mineqtt-lane; must start with mineqtt-lane, so lane
# commands never touch other projects on a shared host and remote-gc.sh sees every lane), LANE_SERVER_IMAGE (default
# mineqtt-lane-server:<mc>-<loader>-<recipe hash>), MC_CPUS / MC_MEMORY / MC_JAVA_OPTS (server limits),
# MINEQTT_KEEP_TICKING (true: never pause when empty), HA_VERSION, PYTHON, and the host variables of docker/hosts.sh.
set -euo pipefail
shopt -s inherit_errexit
# Git Bash would rewrite arguments that look like paths (/server/..., $SYS/#); every host path is converted below.
export MSYS_NO_PATHCONV=1

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
PREFIX=${MINEQTT_LANE_PREFIX:-mineqtt-lane}
RCON_PASSWORD=mineqtt-lane
MQ_REPO=$REPO   # docker/hosts.sh reads the host table relative to it
# shellcheck source=../../docker/hosts.sh
. "$REPO/docker/hosts.sh"

winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi; }
die() { echo "lane: $*" >&2; exit 1; }
# lane.sh creates and, on down and reset, deletes containers and volumes named after the prefix, also on the shared
# remote, which runs other projects too: only the mineqtt-lane namespace is allowed.
[[ $PREFIX =~ ^mineqtt-lane[a-z0-9_-]*$ ]] \
    || die "MINEQTT_LANE_PREFIX must start with mineqtt-lane (lowercase letters, digits, - or _): '$PREFIX'"
prop() { sed -n "s/^$1=//p" "$REPO/gradle.properties" | tr -d '\r'; }

if [ -n "${MINEQTT_LANE_STATE_DIR:-}" ]; then
    STATE_DIR=$MINEQTT_LANE_STATE_DIR
elif [ -n "${USERPROFILE:-}" ] && command -v cygpath >/dev/null 2>&1; then
    STATE_DIR="$(cygpath -u "$USERPROFILE")/.mineqtt-lanes"
else
    STATE_DIR="${HOME:?}/.mineqtt-lanes"
fi

PYTHON=${PYTHON:-$(command -v python3 >/dev/null 2>&1 && python3 -c '' 2>/dev/null && echo python3 || echo python)}
MC_VERSION=$(prop minecraft_version)
LOADER_VERSION=$(prop fabric_loader_version)
FABRIC_API_VERSION=$(prop fabric_api_version)
ARCHITECTURY_VERSION=$(prop architectury_api_version)
INSTALLER_VERSION=${FABRIC_INSTALLER_VERSION:-1.1.2}
# The tag carries a hash of the image recipe and the pinned versions, so a dependency bump or a recipe change builds a
# new image by itself and checkouts on different versions never share one.
recipe_hash=$({ cat "$HERE/server/Dockerfile" "$HERE/server/entrypoint.sh" "$HERE/server/Rcon.java" | tr -d '\r'
    echo "$MC_VERSION $LOADER_VERSION $INSTALLER_VERSION $FABRIC_API_VERSION $ARCHITECTURY_VERSION"; } | sha256sum | cut -c1-10)
export LANE_SERVER_IMAGE=${LANE_SERVER_IMAGE:-mineqtt-lane-server:$MC_VERSION-$LOADER_VERSION-$recipe_hash}
export MINEQTT_OWNER=$MQ_OWNER MINEQTT_CREATED="" MINEQTT_MOSQUITTO_CONF MINEQTT_HA_CONFIGURATION
MINEQTT_MOSQUITTO_CONF=$(tr -d '\r' < "$HERE/mosquitto.conf")
MINEQTT_HA_CONFIGURATION=$(tr -d '\r' < "$REPO/e2e/homeassistant/configuration.yaml")

lane_id() {
    [ $# -ge 1 ] || die "missing lane id"
    [[ $1 =~ ^[a-z0-9][a-z0-9_-]{0,30}$ ]] || die "lane id must be lowercase letters, digits, - or _: '$1'"
    ID=$1
    export LANE_ID=$ID
}

# --- which Docker host a lane lives on ---------------------------------------------------------------------------
# The record <state dir>/<project> holds "host=local|<remote name>" and "created=<epoch>" ("host=remote", written
# when there was one remote, means the first remote of docker/hosts.sh). The created label must stay the same for the
# lane's whole life: compose recreates a container whose labels changed.
record_file() { echo "$STATE_DIR/$PREFIX-$ID"; }
record_get() {
    local f v; f=$(record_file)
    [ -f "$f" ] || return 0
    v=$(sed -n "s/^$1=//p" "$f" | head -n 1)
    # Records from the time of one remote: that remote is the first of the table.
    if [ "$1" = host ] && [ "$v" = remote ]; then v=$(mq_all_remotes | head -n 1); fi
    printf '%s\n' "$v"
}
record_put() { mkdir -p "$STATE_DIR"; printf 'host=%s\ncreated=%s\n' "$1" "$2" > "$(record_file)"; }
record_rm() { rm -f "$(record_file)"; }

lane_exists() { [ -n "$(dk ps -aq --filter "label=com.docker.compose.project=$PREFIX-$ID" 2>/dev/null)" ]; }
lane_count() { # lanes of any prefix (server containers, running or not) on the current host; fails when it cannot be asked
    local out
    out=$(dk ps -a --filter label=com.docker.compose.service=server \
        --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null) || return 1
    printf '%s\n' "$out" | tr -d '\r' | grep -c "^mineqtt-lane" || true
}

# The images a lane needs. A build=manual remote never builds or pulls them on the side: "lane.sh build --host <it>"
# does, at a quiet hour; until then auto skips it and an explicit --host fails.
MOSQUITTO_IMAGE=eclipse-mosquitto:2
HA_IMAGE=ghcr.io/home-assistant/home-assistant:${HA_VERSION:-2026.10.0}
lane_images() { echo "$LANE_SERVER_IMAGE $MOSQUITTO_IMAGE"; [ "${ha:-0}" != 1 ] || echo "$HA_IMAGE"; }
images_present() { local i; for i in $(lane_images); do dk image inspect "$i" >/dev/null 2>&1 || return 1; done; }

# Placing a lane (count, then create) holds this lock, so runs starting lanes at the same time cannot overshoot a
# host's lane cap. A lock older than 10 minutes is left by a killed lane.sh and broken.
PLACE_LOCK=""
place_lock() {
    local waited=0
    mkdir -p "$STATE_DIR"
    until mkdir "$STATE_DIR/.placing" 2>/dev/null; do
        if [ -n "$(find "$STATE_DIR/.placing" -maxdepth 0 -mmin +10 2>/dev/null)" ]; then
            rm -rf "$STATE_DIR/.placing"; continue
        fi
        [ $waited -gt 0 ] || echo "lane: another lane.sh is placing a lane, waiting"
        waited=$((waited + 1)); sleep 2
    done
    PLACE_LOCK="$STATE_DIR/.placing"
    trap place_unlock EXIT
}
place_unlock() { [ -z "$PLACE_LOCK" ] || rm -rf "$PLACE_LOCK"; PLACE_LOCK=""; }

# For every command but up and build: the recorded host; without a record, an explicit --host, else wherever the lane
# exists (this workstation first, then every remote of docker/hosts.sh). Without a record, a lane found nowhere while
# a remote cannot be asked fails rather than being taken for local: "down" would remove nothing and report success
# while the lane runs on. Exports MINEQTT_CREATED from the record or the running lane.
locate_lane() {
    local h r unasked=""
    h=$(record_get host)
    if [ -z "$h" ]; then
        case "$MQ_HOST_MODE" in
            local) h=local ;;
            remote) h=$MQ_PICK ;;
            *)
                mq_use_host local
                if lane_exists; then h=local; fi
                for r in $(mq_all_remotes); do
                    [ -z "$h" ] || break
                    if ! mq_reachable "$r"; then unasked="$unasked $r"; continue; fi
                    mq_use_host "$r"
                    if lane_exists; then h=$r; fi
                done
                if [ -z "$h" ]; then
                    [ -z "$unasked" ] || die "lane $ID is on no host that answers, and$unasked cannot be asked (no" \
                        "record in $STATE_DIR); retry when they answer, or pass --host"
                    h=local
                fi ;;
        esac
    fi
    [ "$h" = local ] || mq_require "$h" || exit 1
    mq_use_host "$h"
    MINEQTT_CREATED=$(record_get created)
    if [ -z "$MINEQTT_CREATED" ]; then
        MINEQTT_CREATED=$(dk ps -a --filter "label=com.docker.compose.project=$PREFIX-$ID" \
            --format '{{.Label "mineqtt.created"}}' 2>/dev/null | head -n 1 | tr -d '\r')
    fi
}

# For up: the recorded host while the lane still exists there, else the --host choice.
choose_lane_host() {
    local h n r cap picked=""
    h=$(record_get host)
    if [ -n "$h" ]; then
        if [ "$h" = local ] || mq_reachable "$h"; then
            mq_use_host "$h"
            if lane_exists; then
                [ "$MQ_HOST_MODE" = auto ] || { [ "$MQ_HOST_MODE" = local ] && [ "$h" = local ]; } \
                    || { [ "$MQ_HOST_MODE" = remote ] && [ "$MQ_PICK" = "$h" ]; } \
                    || die "lane $ID runs on $MQ_HOST; run lane.sh down $ID first"
                MINEQTT_CREATED=$(record_get created)
                return
            fi
        else
            echo "lane: warning: lane $ID was on $h, which is unreachable now; if it still runs there," \
                "docker/client-test/remote-gc.sh --host $h removes it later" >&2
        fi
        record_rm
    fi
    case "$MQ_HOST_MODE" in
        local) mq_use_host local ;;
        remote) mq_require "$MQ_PICK" || exit 1; mq_use_host "$MQ_PICK" ;;
        auto)
            for r in $(mq_remotes); do
                if ! mq_reachable "$r"; then echo "lane: remote $r unreachable"; continue; fi
                mq_use_host "$r"
                if mq_build_manual "$r" && ! images_present; then
                    echo "lane: remote $r is not ready for auto (missing one of: $(lane_images | tr '\n' ' '); lane.sh build --host $r at a quiet hour)"
                    continue
                fi
                cap=$(mq_lanes "$r")
                if ! n=$(lane_count); then
                    echo "lane: remote $r could not list its lanes"
                elif [ "$n" -ge "$cap" ]; then
                    echo "lane: remote $r is full ($n of $cap lanes)"
                else
                    picked=$r
                    break
                fi
            done
            if [ -n "$picked" ]; then mq_use_host "$picked"; else echo "lane: running locally"; mq_use_host local; fi ;;
    esac
    MINEQTT_CREATED=$(date +%s)
    record_put "$MQ_HOST" "$MINEQTT_CREATED"
}

# Every command knows both profiles, so down/status/logs also see Home Assistant. Local lanes get their host ports; a
# host with a cpuset and cpu shares gets them on every container.
dc() {
    local files=(-f "$(winpath "$HERE/docker-compose.yml")")
    mq_is_remote || files+=(-f "$(winpath "$HERE/docker-compose.ports.yml")")
    ! mq_has_limits || files+=(-f "$(winpath "$HERE/docker-compose.limits.yml")")
    dk compose -p "$PREFIX-$ID" "${files[@]}" "$@"
}
dc_all() { COMPOSE_PROFILES=ha dc "$@"; }

port_of() { # <service> <container port> -> host port, empty when not published/running (always on a remote lane)
    mq_is_remote && return 0
    local out
    out=$(dc_all port "$1" "$2" 2>/dev/null | tr -d '\r' | tail -n 1) || true
    [ -z "$out" ] || printf '%s\n' "${out##*:}"
}
need_port() { local p; p=$(port_of "$1" "$2"); [ -n "$p" ] || die "lane $ID: $1 is not running (lane.sh status $ID)"; printf '%s\n' "$p"; }

build_image() {
    if mq_is_remote && mq_build_manual "$MQ_HOST" && [ "$cmd" != build ]; then
        die "$MQ_HOST builds only on request: run lane.sh build --host $MQ_HOST at a quiet hour (e2e/README.md, Hosts)"
    fi
    echo "lane: building $LANE_SERVER_IMAGE on $MQ_HOST"
    dk build -t "$LANE_SERVER_IMAGE" \
        --label "mineqtt.owner=$MQ_OWNER" --label "mineqtt.created=$(date +%s)" \
        --build-arg MC_VERSION="$MC_VERSION" --build-arg LOADER_VERSION="$LOADER_VERSION" \
        --build-arg INSTALLER_VERSION="$INSTALLER_VERSION" \
        --build-arg FABRIC_API_VERSION="$FABRIC_API_VERSION" --build-arg ARCHITECTURY_VERSION="$ARCHITECTURY_VERSION" \
        "$(winpath "$HERE/server")"
}
ensure_image() { dk image inspect "$LANE_SERVER_IMAGE" >/dev/null 2>&1 || build_image; }

assemble() { (cd "$REPO" && ./gradlew :fabric:assemble -q); }
# The bundled MineQTT jar of fabric/build/libs (never the -raw jar, which lacks HiveMQ, or -sources). The jar of
# the checkout's mod_version wins, so jars of older versions left in build/libs do not get in the way.
host_jar() {
    local jar found="" current
    current="$REPO/fabric/build/libs/$(prop archives_name)-fabric-$(prop mod_version).jar"
    if [ -f "$current" ]; then
        printf '%s\n' "$current"
        return
    fi
    for jar in "$REPO"/fabric/build/libs/*.jar; do
        case "$jar" in *-raw.jar|*-sources.jar|*-dev*.jar) continue ;; esac
        [ -f "$jar" ] || continue
        [ -z "$found" ] || die "no jar for mod_version $(prop mod_version) and more than one other MineQTT jar in fabric/build/libs: $found $jar"
        found=$jar
    done
    [ -n "$found" ] || die "no jar in fabric/build/libs: run ./gradlew :fabric:assemble (or pass --jar)"
    printf '%s\n' "$found"
}
# Copies the jar into the lane's mods volume through the server container (created or running), always as
# mineqtt.jar, so a renamed build never leaves two jars behind.
ship_jar() {
    local jar ctr
    jar=$(host_jar)
    ctr=$(dc ps -aq server | tr -d '\r')
    [ -n "$ctr" ] || die "lane $ID has no server container"
    dk cp "$(winpath "$jar")" "$ctr:/opt/mineqtt/mineqtt.jar"
    if mq_is_remote; then
        echo "lane: shipped $(basename "$jar") ($(mq_size "$(wc -c < "$jar" | tr -d ' ')")) to $MQ_HOST"
    fi
}

endpoints() {
    if mq_is_remote; then
        echo "lane $ID ($PREFIX-$ID) on $MQ_HOST, bridge topic mineqtt/lane-$ID"
        echo "  remote lane: no host ports; use lane.sh rcon|pub|sub|smoke|ha $ID (they run inside the lane)"
        return 0
    fi
    local rcon mqtt game ha
    rcon=$(port_of server 25575); mqtt=$(port_of mosquitto 1883); game=$(port_of server 25565); ha=$(port_of homeassistant 8123)
    echo "lane $ID ($PREFIX-$ID) on local, bridge topic mineqtt/lane-$ID"
    [ -n "$rcon" ] && echo "  rcon   127.0.0.1:$rcon  password $RCON_PASSWORD"
    [ -n "$mqtt" ] && echo "  mqtt   tcp://127.0.0.1:$mqtt  (in the lane: mosquitto:1883)"
    [ -n "$game" ] && echo "  game   127.0.0.1:$game  (offline mode)"
    [ -n "$ha" ] && echo "  ha     http://127.0.0.1:$ha  (user mineqtt / mineqtt-e2e after ha-onboard)"
    return 0
}

# e2e/ha.py against the lane's Home Assistant: from here over the host port, or, on a remote lane, inside the HA
# container (it has python3), where ha.py keeps its token in the ha-config volume. "dump FILE" on a remote lane writes
# the file in the container and copies it back to FILE here.
ha_py() {
    if mq_is_remote; then
        local ctr
        ctr=$(dc_all ps -q homeassistant | tr -d '\r')
        [ -n "$ctr" ] || die "lane $ID: homeassistant is not running (lane.sh up $ID --ha)"
        dk exec "$ctr" mkdir -p /config/.mineqtt
        dk cp "$(winpath "$REPO/e2e/ha.py")" "$ctr:/config/.mineqtt/ha.py"
        if [ "${1:-}" = dump ]; then
            [ $# -eq 2 ] || die "usage: lane.sh ha $ID dump FILE"
            dc_all exec -T homeassistant python3 /config/.mineqtt/ha.py --url http://127.0.0.1:8123 \
                dump /config/.mineqtt/dump.json >/dev/null
            dk cp "$ctr:/config/.mineqtt/dump.json" "$(winpath "$2")"
            dk exec "$ctr" rm -f /config/.mineqtt/dump.json
            echo "wrote $2"
            return 0
        fi
        dc_all exec -T homeassistant python3 /config/.mineqtt/ha.py --url http://127.0.0.1:8123 "$@"
    else
        local p
        p=$(need_port homeassistant 8123) || exit 1
        "$PYTHON" "$(winpath "$REPO/e2e/ha.py")" --url "http://127.0.0.1:$p" "$@"
    fi
}

# --host before the command
host_arg=${MINEQTT_DOCKER_HOST:-auto}
while [ $# -gt 0 ]; do
    case "$1" in
        --host) [ $# -ge 2 ] || die "--host needs a value"; host_arg=$2; shift 2 ;;
        --host=*) host_arg=${1#--host=}; shift ;;
        *) break ;;
    esac
done
cmd=${1:-}; [ $# -gt 0 ] && shift
# ... and among the options of up, build and list
if [ "$cmd" = up ] || [ "$cmd" = build ] || [ "$cmd" = list ]; then
    rest=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --host) [ $# -ge 2 ] || die "--host needs a value"; host_arg=$2; shift 2 ;;
            --host=*) host_arg=${1#--host=}; shift ;;
            *) rest+=("$1"); shift ;;
        esac
    done
    set -- ${rest[@]+"${rest[@]}"}
fi
mq_parse_host "$host_arg" || exit 2

case "$cmd" in
    build)
        case "$MQ_HOST_MODE" in
            local) mq_use_host local; build_image ;;
            remote)
                mq_require "$MQ_PICK" || exit 1; mq_use_host "$MQ_PICK"; build_image
                # A build=manual host gets everything a lane may need now, so later lanes pull nothing on the side.
                if mq_build_manual "$MQ_PICK"; then dk pull -q "$MOSQUITTO_IMAGE" >/dev/null; dk pull -q "$HA_IMAGE" >/dev/null; fi ;;
            auto)
                mq_use_host local; build_image
                for r in $(mq_remotes); do
                    if mq_build_manual "$r"; then echo "lane: $r builds only on request (lane.sh build --host $r)"
                    elif mq_reachable "$r"; then mq_use_host "$r"; build_image
                    else echo "lane: remote $r unreachable, not built there"; fi
                done ;;
        esac ;;
    up)
        lane_id "$@"; shift
        ha=0 jar=0
        for arg in "$@"; do
            case "$arg" in --ha) ha=1 ;; --jar) jar=1 ;; *) die "unknown option $arg" ;; esac
        done
        [ $jar = 1 ] && assemble
        host_jar >/dev/null
        place_lock
        choose_lane_host
        if mq_is_remote && mq_build_manual "$MQ_HOST" && ! images_present; then
            record_rm
            die "$MQ_HOST lacks one of: $(lane_images | tr '\n' ' '); run lane.sh build --host $MQ_HOST at a quiet hour"
        fi
        ensure_image
        profiles=""
        [ $ha = 1 ] && profiles=ha
        # Create first (network, volumes, containers), ship the jar into the mods volume, then start.
        if ! out=$(COMPOSE_PROFILES=$profiles dc create --quiet-pull 2>&1); then
            printf '%s\n' "$out" >&2
            die "could not create lane $ID on $MQ_HOST"
        fi
        place_unlock
        ship_jar
        COMPOSE_PROFILES=$profiles dc up -d --wait --wait-timeout 600
        endpoints ;;
    reload)
        lane_id "$@"; shift
        [ "${1:-}" = "--jar" ] && assemble
        host_jar >/dev/null
        locate_lane
        ship_jar
        # Restart, not recreate: the entrypoint copies the jar again and the container keeps its config edits.
        dc restart --timeout 30 server
        dc up -d --wait --wait-timeout 600 --no-deps server
        endpoints ;;
    wait)
        lane_id "$@"; locate_lane
        dc up -d --wait --wait-timeout 600 --no-deps server >/dev/null
        endpoints ;;
    reset)
        lane_id "$@"; locate_lane
        dc rm -s -f server
        dk volume rm "$PREFIX-${ID}_world" >/dev/null
        dc up -d --wait --wait-timeout 600 server
        endpoints ;;
    down)
        lane_id "$@"; locate_lane
        ha=$(port_of homeassistant 8123)
        dc_all down -v --remove-orphans --timeout 30
        record_rm
        # ha.py keys its token on host and port; an ephemeral port may come back for another lane later.
        [ -n "$ha" ] && rm -f "$REPO/e2e/.state/127.0.0.1_$ha.token"
        true ;;
    rcon)
        lane_id "$@"; shift
        [ $# -gt 0 ] || die "rcon needs a command"
        locate_lane
        if mq_is_remote; then
            dc exec -T server lane-rcon "$@"
        else
            port=$(need_port server 25575)
            "$PYTHON" "$(winpath "$REPO/e2e/tools/rcon.py")" "$port" "$RCON_PASSWORD" "$@"
        fi ;;
    pub)
        lane_id "$@"; shift; locate_lane
        dc exec -T mosquitto mosquitto_pub "$@" ;;
    sub)
        lane_id "$@"; shift; locate_lane
        dc exec -T mosquitto mosquitto_sub "$@" ;;
    smoke)
        lane_id "$@"
        "$0" rcon "$ID" forceload add 0 0
        "$0" rcon "$ID" setblock 0 -60 0 mineqtt:rgb_led_block
        sleep 2
        "$0" rcon "$ID" mineqtt status
        echo "--- mineqtt/lane-$ID/bridge/state:"
        "$0" sub "$ID" -v -C 1 -W 10 -t "mineqtt/lane-$ID/bridge/state"
        echo "--- retained discovery configs under homeassistant/:"
        "$0" sub "$ID" -W 3 -F '%t' -t 'homeassistant/#' --retained-only 2>/dev/null | sort || true ;;
    ports)
        lane_id "$@"; locate_lane; endpoints ;;
    status)
        lane_id "$@"; locate_lane
        echo "host: $MQ_HOST (docker $MQ_ENDPOINT)"
        dc_all ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
        endpoints ;;
    stats)
        lane_id "$@"; locate_lane
        ids=$(dc_all ps -q | tr -d '\r')
        [ -n "$ids" ] || die "lane $ID is not running"
        # shellcheck disable=SC2086
        dk stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' $ids ;;
    logs)
        lane_id "$@"; shift; locate_lane
        service=${1:-server}; [ $# -gt 0 ] && shift
        dc_all logs "$service" "$@" ;;
    ha-onboard)
        lane_id "$@"; locate_lane
        ha_py onboard
        ha_py mqtt mosquitto 1883 ;;
    ha)
        lane_id "$@"; shift; locate_lane
        ha_py "$@" ;;
    list)
        if [ "$MQ_HOST_MODE" != remote ]; then
            mq_use_host local; echo "local:"; dk compose ls --all --filter "name=$PREFIX-"
        fi
        if [ "$MQ_HOST_MODE" != local ]; then
            hosts=$(mq_all_remotes)
            [ "$MQ_HOST_MODE" != remote ] || hosts=$MQ_PICK
            for r in $hosts; do
                if mq_reachable "$r"; then mq_use_host "$r"; echo "$r:"; dk compose ls --all --filter "name=$PREFIX-"
                elif [ "$MQ_HOST_MODE" = remote ]; then mq_require "$r" || exit 1
                else echo "$r: unreachable"; fi
            done
        fi ;;
    *)
        sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
        [ -z "$cmd" ] || [ "$cmd" = help ] || exit 2 ;;
esac

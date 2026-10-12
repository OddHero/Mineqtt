#!/bin/sh
# The upgrade of a world and a config that MineQTT 0.7.0 wrote (checklist item 40, M4 plan D17).
#   upgrade07.sh place  [--lane <id>]
#       on a fresh world of a 0.7.0 server: the four timers (one mid-count, a Daylight Timer at a custom time off a
#       minute boundary, an Alarm Clock that fires and a custom cron, each with both slots filled through NBT and an
#       owner), a second Daylight Timer at sunrise, a Block Sensor and a Block Emitter on one topic, and a configured
#       Smart Lamp; the day clock stands at the Alarm Clock's moment, so it fires once; then the ticks are frozen, the
#       world saved and what 0.7.0 counted is written to build/upgrade07/<id or host>.env
#   upgrade07.sh verify [--lane <id>] [--config <file>] [--log <file>]
#       after the server ran 0.7.0, stopped, and started again on this checkout's jar: the timers are devices with
#       their names, owners, settings, counters and slot items as legacy items, the Alarm Clock does not fire again on
#       the day it fired, the sensor and the emitter are a Block Observer and a Block Placer on the same topic for the
#       same owner, the lamp is unchanged, the Block Placer is still off, the config file is as 0.7.0 left it (JSON)
#       or still has the placer off (TOML), and the server log has no block entity load warning
#   upgrade07.sh lane <id> [--host H]
#       the whole Fabric run on a test lane: up on the 0.7.0 jar (build07.sh builds it when it is missing), place,
#       reload on this checkout's jar (lane.sh reload --jar: one Gradle build), verify. The lane stays up for more
#       checks: end it with lane.sh down <id>.
# Without --lane, RCON goes through game.py (MINEQTT_RCON_PORT, MINEQTT_RCON_PASSWORD), so a dedicated server of
# either loader works, the NeoForge one with the 0.7.0 NeoForge jar of build07.sh (e2e/README.md, item 40).
set -eu

DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$DIR/../.." && pwd)
LANE_SH="$REPO/e2e/lanes/lane.sh"
PY=${PYTHON:-$(command -v python3 >/dev/null 2>&1 && python3 -c '' 2>/dev/null && echo python3 || echo python)}
Y=-60
Z=20
# x of each block in the row at y -60, z 20; the observer and the placer face north, into z 19.
X_INTERVAL=0 X_DAYLIGHT=2 X_ALARM=4 X_CRON=6 X_SUNRISE=8 X_OBSERVER=10 X_PLACER=12 X_LAMP=14
OWNER='[I;-1512345678,123456789,-1987654321,42424242]'
LAMP_ID='[I;1431655765,1414541312,-2147483648,40]'
ALARM_TICKS=23990
CUSTOM_TICKS=4321
CRON='0 30 15 10 * * 1-5'
LINK_TOPIC=upgrade07/link
TICK_COUNTER=43210
# What 26.2 logs when it drops or cannot load a block entity (as allblocks.sh).
LOAD_WARNINGS='Skipping block ?entity|Failed to (create|load data for) block entity|Tried to load a (DUMMY )?block entity|mismatched block entity|but state .* does not allow it'

cmd=${1:-}
[ $# -gt 0 ] && shift
LANE="" CONFIG="" LOG="" HOST=""
case "$cmd" in
    lane) LANE=${1:-}; [ -n "$LANE" ] || { echo "upgrade07: lane needs an id" >&2; exit 2; }; shift ;;
esac
while [ $# -gt 0 ]; do
    case "$1" in
        --lane) LANE=${2:-}; shift 2 ;;
        --config) CONFIG=${2:-}; shift 2 ;;
        --log) LOG=${2:-}; shift 2 ;;
        --host) HOST=${2:-}; shift 2 ;;
        *) echo "upgrade07: unknown argument $1" >&2; exit 2 ;;
    esac
done
STATE="$REPO/build/upgrade07/${LANE:-host}.env"

lane() { sh "$LANE_SH" "$@"; }

rcon() {
    if [ -n "$LANE" ]; then
        lane rcon "$LANE" "$@" </dev/null | tr -d '\r'
    else
        "$PY" "$DIR/game.py" rcon "$@" </dev/null | tr -d '\r'
    fi
}

# The value part of a "data get block" answer, without spaces and without the type suffix of a number (43210L -> 43210).
value() {
    rcon data get block "$1" $Y "${3:-$Z}" "$2" | sed -n 's/.*has the following block data: //p' | tr -d ' ' | sed 's/^\(-\{0,1\}[0-9][0-9]*\)[bslfdBSLFD]$/\1/'
}

# A slot item: a paper with a name, as v1's slots held it.
paper() {
    echo "{Slot:$1b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"$2\"}}"
}

# The keys of a v1 block with slots: its name, owner and both slots.
v1base() {
    echo "CustomName:\"$1\",ownerName:\"Steve\",ownerUuid:$OWNER,privateMode:0b,Items:[$(paper 0 "$2"),$(paper 1 sub)]"
}

failed=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; failed=1; }
note() { echo "NOTE $*"; }

# expect <x> <path> <expected> <what>: the value at the path equals the expected one (spaces ignored).
expect() {
    actual=$(value "$1" "$2")
    if [ "$actual" = "$(echo "$3" | tr -d ' ')" ]; then pass "$4: $2 = $actual"; else fail "$4: $2 is '$actual', expected $3"; fi
}

# expect_default <x> <path> <value> <what>: the value at the path is the given one, or absent: a timer's settings
# leave out a value that is the default.
expect_default() {
    actual=$(value "$1" "$2")
    if [ -z "$actual" ] || [ "$actual" = "$(echo "$3" | tr -d ' ')" ]; then
        pass "$4: $2 = ${actual:-absent, the default $3}"
    else
        fail "$4: $2 is '$actual', expected $3"
    fi
}

# expect_has <x> <path> <text> <what>: the value at the path contains the text.
expect_has() {
    actual=$(value "$1" "$2")
    if echo "$actual" | grep -qF -- "$3"; then pass "$4: $2 has $3"; else fail "$4: $2 is '$actual', without $3"; fi
}

place() {
    mkdir -p "$(dirname "$STATE")"
    rcon forceload add 0 $Z 16 $Z
    # The clock stands at the Alarm Clock's moment, also across the restart: a fire that 0.8.0 repeated would show.
    rcon gamerule advance_time false
    rcon time set $ALARM_TICKS
    rcon setblock $X_OBSERVER $Y $((Z - 1)) minecraft:white_wool
    rcon setblock $X_PLACER $Y $((Z - 1)) minecraft:air
    rcon setblock $X_INTERVAL $Y $Z "mineqtt:interval_timer{intervalValue:100000L,unitIndex:0,tickCounter:${TICK_COUNTER}L,$(v1base "Upgrade Interval" upgrade07/interval)}"
    rcon setblock $X_DAYLIGHT $Y $Z "mineqtt:game_time_timer{triggerModeIndex:4,customTime:$CUSTOM_TICKS,$(v1base "Upgrade Daylight" upgrade07/daylight)}"
    rcon setblock $X_ALARM $Y $Z "mineqtt:alarm_clock{alarmTime:$ALARM_TICKS,$(v1base "Upgrade Alarm" upgrade07/alarm)}"
    rcon setblock $X_CRON $Y $Z "mineqtt:cron_timer{cronExpression:\"$CRON\",$(v1base "Upgrade Cron" upgrade07/cron)}"
    rcon setblock $X_SUNRISE $Y $Z "mineqtt:game_time_timer{triggerModeIndex:0,customTime:6000,$(v1base "Upgrade Sunrise" upgrade07/sunrise)}"
    rcon setblock $X_OBSERVER $Y $Z "mineqtt:block_sensor_block[facing=north]{BlockSensorDataMode:\"block_id\",CustomName:\"Upgrade Observer\",ownerName:\"Steve\",ownerUuid:$OWNER,Items:[$(paper 0 $LINK_TOPIC)]}"
    rcon setblock $X_PLACER $Y $Z "mineqtt:block_emitter_block[facing=north]{CustomName:\"Upgrade Placer\",ownerName:\"Steve\",ownerUuid:$OWNER,Items:[$(paper 0 $LINK_TOPIC)]}"
    rcon setblock $X_LAMP $Y $Z "mineqtt:rgb_led_block{mineqtt:{id:$LAMP_ID,name:\"Upgrade Lamp\",slug:\"upgrade_lamp\"}}"
    sleep 3
    rcon mineqtt devices raw upgrade_lamp channel upgrade07/lamp
    rcon mineqtt devices access upgrade_lamp public
    rcon mineqtt devices set upgrade_lamp '{"state":"ON","brightness":200}'
    sleep 2
    rcon tick freeze
    sleep 1
    rcon save-all flush
    alarm_day=$(value $X_ALARM lastAlarmDay)
    sunrise_day=$(value $X_SUNRISE lastTriggerDay)
    [ "$alarm_day" = 0 ] || { echo "upgrade07: the 0.7.0 Alarm Clock did not fire (lastAlarmDay '$alarm_day'): is the server ticking?" >&2; exit 1; }
    {
        echo "ALARM_DAY=$alarm_day"
        echo "ALARM_TIME=$(value $X_ALARM lastTriggerTime)"
        echo "SUNRISE_DAY=$sunrise_day"
        echo "SUNRISE_TIME=$(value $X_SUNRISE lastTriggerTime)"
        echo "INTERVAL_COUNTER=$(value $X_INTERVAL tickCounter)"
        echo "LAMP_TOPIC='$(value $X_LAMP mineqtt.raw.topic)'"
        echo "LAMP_CHANNEL='$(value $X_LAMP mineqtt.raw.channel)'"
        echo "LAMP_ACCESS='$(value $X_LAMP mineqtt.access)'"
        echo "LAMP_STATE='$(value $X_LAMP mineqtt.state)'"
    } > "$STATE"
    if [ -n "$LANE" ]; then
        lane exec "$LANE" cat config/mineqtt.json > "$STATE.config"
    elif [ -n "$CONFIG" ]; then
        cp "$CONFIG" "$STATE.config"
    fi
    cat "$STATE"
    echo "upgrade07: 0.7.0 world placed and saved; stop the server, swap the jar and run verify"
}

timer() {
    x=$1 name=$2 slot=$3
    expect "$x" mineqtt.name "\"$name\"" "$name keeps its anvil name"
    expect "$x" mineqtt.owner "$OWNER" "$name keeps its owner UUID"
    expect_has "$x" mineqtt.legacyItems "\"$slot\"" "$name keeps slot 0 as a legacy item"
    expect_has "$x" mineqtt.legacyItems '"sub"' "$name keeps slot 1 as a legacy item"
    if [ -z "$(value "$x" Items)" ]; then pass "$name has no slots left"; else fail "$name still has slots: $(value "$x" Items)"; fi
}

verify() {
    [ -f "$STATE" ] || { echo "upgrade07: no capture $STATE; run place on the 0.7.0 server first" >&2; exit 2; }
    # shellcheck disable=SC1090
    . "$STATE"
    rcon tick unfreeze
    # A second of ticks is the Alarm Clock's whole window; three show any fire.
    sleep 3

    timer $X_INTERVAL "Upgrade Interval" upgrade07/interval
    expect $X_INTERVAL mineqtt.state.interval 100000 "Interval Timer keeps its interval"
    expect_default $X_INTERVAL mineqtt.state.unit '"ticks"' "Interval Timer keeps its unit"
    counter=$(value $X_INTERVAL mineqtt.timer.tick_counter)
    if [ -n "$counter" ] && [ "$counter" -ge "$INTERVAL_COUNTER" ] && [ "$counter" -lt $((INTERVAL_COUNTER + 12000)) ]; then
        pass "Interval Timer counts on from 0.7.0's $INTERVAL_COUNTER: $counter"
    else
        fail "Interval Timer's counter is '$counter', 0.7.0 saved $INTERVAL_COUNTER"
    fi

    timer $X_DAYLIGHT "Upgrade Daylight" upgrade07/daylight
    expect $X_DAYLIGHT mineqtt.state.trigger_at '"custom"' "Daylight Timer stays at a custom time"
    expect $X_DAYLIGHT mineqtt.state.time_ticks $CUSTOM_TICKS "Daylight Timer's time off a minute boundary is exact"

    timer $X_ALARM "Upgrade Alarm" upgrade07/alarm
    expect $X_ALARM mineqtt.state.time_ticks $ALARM_TICKS "Alarm Clock keeps its time"
    expect_default $X_ALARM mineqtt.state.enabled 1 "Alarm Clock stays on"
    expect $X_ALARM mineqtt.timer.last_trigger_day "$ALARM_DAY" "Alarm Clock carries lastAlarmDay"
    expect $X_ALARM mineqtt.timer.last_trigger_time "$ALARM_TIME" "Alarm Clock does not fire again on the upgrade day"

    timer $X_CRON "Upgrade Cron" upgrade07/cron
    expect $X_CRON mineqtt.state.cron "\"$CRON\"" "Cron Timer keeps its expression"

    timer $X_SUNRISE "Upgrade Sunrise" upgrade07/sunrise
    # 0.7.0 counted a sunrise fire 10 ticks before the day turned for the day before; 0.8.0 counts it for the day of
    # its moment, so a timer saved between 0.7.0's two fires of one sunrise fires once more (CHANGELOG, Known limitations).
    if [ "$(value $X_SUNRISE mineqtt.timer.last_trigger_time)" = "$SUNRISE_TIME" ]; then
        note "Daylight Timer at sunrise, saved 10 ticks before the day turned, did not fire again"
    else
        note "Daylight Timer at sunrise, saved 10 ticks before the day turned (day $SUNRISE_DAY), fired once more after the upgrade (the known limitation)"
    fi

    expect $X_OBSERVER mineqtt.name '"Upgrade Observer"' "Block Sensor is a Block Observer with its name"
    expect $X_OBSERVER mineqtt.state.payload '"block_id"' "Block Observer keeps the data mode as its payload"
    expect $X_OBSERVER mineqtt.raw.topic "\"$LINK_TOPIC\"" "Block Observer keeps its topic"
    expect $X_OBSERVER mineqtt.raw.dir '"out"' "Block Observer's topic is a Raw Link out"
    expect $X_OBSERVER mineqtt.raw.grandfathered.topic "\"$LINK_TOPIC\"" "Block Observer's topic is grandfathered"
    expect $X_OBSERVER mineqtt.raw.grandfathered.owner "$OWNER" "for its owner"
    expect $X_PLACER mineqtt.name '"Upgrade Placer"' "Block Emitter is a Block Placer with its name"
    expect $X_PLACER mineqtt.raw.topic "\"$LINK_TOPIC\"" "Block Placer keeps its topic"
    expect $X_PLACER mineqtt.raw.dir '"in"' "Block Placer's topic is a Raw Link in"
    expect $X_PLACER mineqtt.raw.grandfathered.topic "\"$LINK_TOPIC\"" "Block Placer's topic is grandfathered"
    expect $X_PLACER mineqtt.raw.grandfathered.owner "$OWNER" "for its owner"
    expect_has $X_PLACER mineqtt.legacyItems "\"$LINK_TOPIC\"" "Block Placer keeps its slot as a legacy item"
    rcon mineqtt devices action upgrade_placer place >/dev/null
    sleep 1
    info=$(rcon mineqtt devices info upgrade_placer | grep -F "Last state")
    if echo "$info" | grep -qF "blockPlacer.enabled is off"; then pass "the Block Placer answers that it is off: $info"; else fail "the Block Placer does not answer that it is off: $info"; fi
    if rcon execute if block $X_PLACER $Y $((Z - 1)) minecraft:air | grep -q "Test passed"; then pass "the Block Placer places nothing"; else fail "the Block Placer placed a block while off"; fi

    expect $X_LAMP mineqtt.id "$LAMP_ID" "Smart Lamp keeps its id"
    expect $X_LAMP mineqtt.slug '"upgrade_lamp"' "Smart Lamp keeps its slug"
    expect $X_LAMP mineqtt.name '"Upgrade Lamp"' "Smart Lamp keeps its name"
    expect $X_LAMP mineqtt.raw.topic "$LAMP_TOPIC" "Smart Lamp keeps its Raw Link topic"
    expect $X_LAMP mineqtt.raw.channel "$LAMP_CHANNEL" "and its channel"
    expect $X_LAMP mineqtt.access "$LAMP_ACCESS" "Smart Lamp keeps its access mode"
    expect $X_LAMP mineqtt.state "$LAMP_STATE" "Smart Lamp keeps its light"

    devices=$(rcon mineqtt devices list)
    echo "$devices"
    loaded=$(echo "$devices" | grep -c "^upgrade_.* loaded at " || true)
    if [ "$loaded" -eq 8 ]; then pass "the eight devices are loaded"; else fail "expected the eight upgrade_ devices loaded, found $loaded"; fi

    if [ -n "$LANE" ]; then
        lane exec "$LANE" cat config/mineqtt.json > "$STATE.config.after"
        if [ -f "$STATE.config" ] && cmp -s "$STATE.config" "$STATE.config.after"; then
            pass "mineqtt.json is the file 0.7.0 left"
        else
            fail "mineqtt.json changed with the upgrade"; diff "$STATE.config" "$STATE.config.after" || true
        fi
        placer=$("$PY" -c 'import json,sys; print(json.load(sys.stdin).get("blockPlacer", {}).get("enabled", False))' < "$STATE.config.after")
        if [ "$placer" = False ]; then pass "mineqtt.json keeps blockPlacer.enabled=false"; else fail "mineqtt.json has blockPlacer.enabled=$placer"; fi
    elif [ -n "$CONFIG" ]; then
        case "$CONFIG" in
            *.toml)
                placer=$("$PY" -c 'import sys,tomllib; print(tomllib.load(open(sys.argv[1], "rb")).get("blockPlacer", {}).get("enabled"))' "$CONFIG") ;;
            *)
                placer=$("$PY" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("blockPlacer", {}).get("enabled", False))' "$CONFIG")
                if [ -f "$STATE.config" ] && cmp -s "$STATE.config" "$CONFIG"; then pass "mineqtt.json is the file 0.7.0 left"; else fail "mineqtt.json changed with the upgrade"; fi ;;
        esac
        if [ "$placer" = False ]; then pass "$(basename "$CONFIG") keeps blockPlacer.enabled=false"; else fail "$(basename "$CONFIG") has blockPlacer.enabled=$placer"; fi
    else
        note "no --config: the config file was not checked"
    fi

    if [ -n "$LANE" ]; then
        log=$(lane logs "$LANE" server --no-log-prefix 2>/dev/null | tr -d '\r')
    elif [ -n "$LOG" ]; then
        log=$(tr -d '\r' < "$LOG")
    else
        log=""
    fi
    if [ -z "$log" ]; then
        note "no server log: the load warnings were not checked"
    elif echo "$log" | grep -iE "$LOAD_WARNINGS"; then
        fail "the server log has the block entity load warnings above"
    else
        pass "the server log has no block entity load warning"
    fi

    [ $failed -eq 0 ] && echo "upgrade07: PASS" || echo "upgrade07: FAIL"
    return $failed
}

# The whole Fabric run on a test lane.
run_lane() {
    jar07=${MINEQTT_07_JAR:-$REPO/build/mineqtt-0.7.0/MineQTT-fabric-0.7.0.jar}
    [ -f "$jar07" ] || sh "$DIR/build07.sh" --loader fabric
    [ -f "$jar07" ] || { echo "upgrade07: no 0.7.0 jar at $jar07" >&2; exit 1; }
    host=""
    [ -n "$HOST" ] && host="--host $HOST"
    # The same environment on every lane.sh call: a changed one recreates the server container, which resets its config.
    # Keep ticking while empty, so the 0.7.0 timers count and fire without a player.
    export MINEQTT_KEEP_TICKING=true
    # shellcheck disable=SC2086
    MINEQTT_LANE_JAR=$jar07 sh "$LANE_SH" up "$LANE" $host
    # The config as 0.7.0 users have it: the Block Placer explicitly off, which 0.8.0 must keep. Read and edited in full
    # before anything is written: in one pipeline the writer's "cat >" could empty the file before the reader saw it.
    config07=$(lane exec "$LANE" cat config/mineqtt.json \
        | "$PY" -c 'import json,sys; c=json.load(sys.stdin); c.setdefault("blockPlacer", {})["enabled"]=False; print(json.dumps(c, indent=2))')
    [ -n "$config07" ] || { echo "upgrade07: could not read the lane's config/mineqtt.json" >&2; exit 1; }
    printf '%s\n' "$config07" | lane exec "$LANE" sh -c 'cat > config/mineqtt.json'
    MINEQTT_LANE_JAR=$jar07 sh "$LANE_SH" reload "$LANE"
    place
    sh "$LANE_SH" reload "$LANE" --jar
    verify
}

case "$cmd" in
    place) place ;;
    verify) verify ;;
    lane) run_lane ;;
    *) sed -n '2,21p' "$0"; exit 2 ;;
esac

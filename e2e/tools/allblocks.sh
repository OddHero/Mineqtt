#!/bin/sh
# One of each MineQTT block entity, placed with data and checked again after a restart (M2 plan M2-3, D13).
#   allblocks.sh place            force-load the row, place the 18 blocks with data, save
#   allblocks.sh verify [log]     after a restart: every block entity has its data back and the nine devices
#                                 re-attached, the six placed as devices with their ids; with the server log also no
#                                 block entity load warning. The humidity sensor converted into a Climate Sensor (M2-19),
#                                 the Static Publisher into a Redstone Input of rising edges (M2-20); the Dynamic
#                                 Publisher and the Topic Relay keep only their slot paper as legacy items (M2-21); the
#                                 v1 Light Remote is a Wall Switch with its slot paper as a legacy item (M2-22).
# Run place on a fresh world (the devices carry fixed ids; MQTT may be disabled), stop the server, start it again,
# then verify. RCON goes through game.py (MINEQTT_RCON_PORT, MINEQTT_RCON_PASSWORD), so either loader's dev server works.
DIR=$(cd "$(dirname "$0")" && pwd)
E="python $DIR/game.py"
Y=-60
# The devices' ids, at x 0, 2, 4, 6, 18 and 32. The Redstone Input reads a mineqtt compound without an id as v1 data.
ID='[I;1296651345,1414541312,-2147483648,'
# What 26.2 logs when it drops or cannot load a block entity (BlockEntity, LevelChunk; older versions said
# "Skipping BlockEntity with id").
LOAD_WARNINGS='Skipping block ?entity|Failed to (create|load data for) block entity|Tried to load a (DUMMY )?block entity|mismatched block entity|but state .* does not allow it'

# A named paper in slot 0; each block gets its own topic, so no two of them talk to each other.
paper() {
    echo "Items:[{Slot:0b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"allblocks/$1\"}}]"
}

# x|block|block entity data|path checked by verify|value expected there
blocks() {
    cat <<EOF
0|redstone_publisher_block|{mineqtt:{id:${ID}0],name:"Allblocks Input",slug:"allblocks_input"}}|mineqtt.name|"Allblocks Input"
2|redstone_emitter_block|{mineqtt:{id:${ID}2],name:"Allblocks Output",slug:"allblocks_output"}}|mineqtt.name|"Allblocks Output"
4|rgb_led_block|{mineqtt:{id:${ID}4],name:"Allblocks Lamp",slug:"allblocks_lamp"}}|mineqtt.name|"Allblocks Lamp"
6|motion_sensor_block|{mineqtt:{id:${ID}6],name:"Allblocks Presence",slug:"allblocks_presence"}}|mineqtt.name|"Allblocks Presence"
8|block_sensor_block|{BlockSensorDataMode:"name",$(paper block_sensor)}|BlockSensorDataMode|"name"
10|block_emitter_block|{$(paper block_emitter)}|Items[0].components|"allblocks/block_emitter"
12|light_remote_block|{CustomName:"Allblocks Switch",$(paper light_remote)}|mineqtt.legacyItems[0].components|"allblocks/light_remote"
14|cron_timer|{cronExpression:"0 0 0 12 * * *",$(paper cron_timer)}|cronExpression|"0 0 0 12 * * *"
16|interval_timer|{intervalValue:7L,$(paper interval_timer)}|intervalValue|7L
18|temperature_sensor_block|{mineqtt:{id:${ID}18],name:"Allblocks Climate",slug:"allblocks_climate"}}|mineqtt.name|"Allblocks Climate"
20|humidity_sensor_block|{UpdateIntervalTicks:40,Humidity:55.0d,CustomName:"Allblocks Humidity",$(paper humidity_sensor)}|mineqtt.state.raw_value|"humidity"
22|alarm_clock|{alarmTime:6000,$(paper alarm_clock)}|alarmTime|6000
24|game_time_timer|{customTime:4321,$(paper game_time_timer)}|customTime|4321
26|static_publisher|{staticMessage:"allblocks",wasPowered:0b,CustomName:"Allblocks Static",$(paper static_publisher)}|mineqtt.raw.edges|"rising"
28|dynamic_publisher|{dataType:2,$(paper dynamic_publisher)}|legacyItems[0].components|"allblocks/dynamic_publisher"
30|topic_relay|{sourceTopic:"allblocks/relay_source",$(paper topic_relay)}|legacyItems[0].components|"allblocks/topic_relay"
32|smart_plug|{mineqtt:{id:${ID}32],name:"Allblocks Plug",slug:"allblocks_plug"}}|mineqtt.name|"Allblocks Plug"
34|terminal_block[facing=east]|{lastTabId:"console_devices"}|lastTabId|"console_devices"
EOF
}

# The value part of a "data get block" answer ("... has the following block data: <value>").
value() {
    $E rcon data get block "$1" $Y 0 "$2" </dev/null | sed -n 's/.*has the following block data: //p'
}

place() {
    $E rcon forceload add 0 0 34 0
    blocks | while IFS='|' read -r x block nbt path expected; do
        $E rcon setblock "$x" $Y 0 "mineqtt:$block$nbt" </dev/null
    done
    sleep 2
    $E rcon mineqtt devices list
    $E rcon save-all flush
}

verify() {
    results=$(blocks | while IFS='|' read -r x block nbt path expected; do
        actual=$(value "$x" "$path")
        if [ -n "$actual" ] && echo "$actual" | grep -qF -- "$expected"; then
            echo "PASS $block: $path = $actual"
        else
            echo "FAIL $block: $path is '$actual', expected $expected"
        fi
    done)
    echo "$results"
    failed=0
    echo "$results" | grep -q "^FAIL" && failed=1
    if $E rcon execute if block 20 $Y 0 mineqtt:temperature_sensor_block </dev/null | grep -q "Test passed"; then
        echo "PASS the humidity sensor at x 20 is a Climate Sensor"
    else
        echo "FAIL the humidity sensor at x 20 did not convert into a Climate Sensor"; failed=1
    fi
    if $E rcon execute if block 26 $Y 0 mineqtt:redstone_publisher_block </dev/null | grep -q "Test passed"; then
        echo "PASS the Static Publisher at x 26 is a Redstone Input"
    else
        echo "FAIL the Static Publisher at x 26 did not convert into a Redstone Input"; failed=1
    fi
    if $E rcon execute if block 34 $Y 0 'mineqtt:terminal_block[facing=east]' </dev/null | grep -q "Test passed"; then
        echo "PASS the Bridge Console at x 34 keeps its facing"
    else
        echo "FAIL the Bridge Console at x 34 lost its facing"; failed=1
    fi
    for x in 0 2 4 6 18 32; do
        actual=$(value "$x" mineqtt.id | tr -d ' ')
        if [ "$actual" = "$ID$x]" ]; then
            echo "PASS device at x $x keeps its id $actual"
        else
            echo "FAIL device at x $x has the id '$actual', expected $ID$x]"; failed=1
        fi
    done
    devices=$($E rcon mineqtt devices list)
    echo "$devices"
    loaded=$(echo "$devices" | grep -c "^allblocks_.* loaded at ")
    if [ "$loaded" -eq 9 ] && echo "$devices" | grep -q "^9 devices:"; then
        echo "PASS the nine devices are loaded"
    else
        echo "FAIL expected exactly the nine allblocks devices, loaded"; failed=1
    fi
    if [ -n "$1" ]; then
        if grep -iE "$LOAD_WARNINGS" "$1"; then
            echo "FAIL $1 has the block entity load warnings above"; failed=1
        else
            echo "PASS $1 has no block entity load warning"
        fi
    fi
    [ $failed -eq 0 ] && echo "allblocks: PASS" || echo "allblocks: FAIL"
    return $failed
}

case "$1" in
    place) place ;;
    verify) verify "$2" ;;
    *) sed -n '2,8p' "$0"; exit 2 ;;
esac

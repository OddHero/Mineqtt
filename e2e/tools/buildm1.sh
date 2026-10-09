#!/bin/sh
# An M1 world for the upgrade to devices without containers (M2 plan M2-12): device data as M1 wrote it, a mineqtt
# compound of version 2 beside the slots (Items) and the keys of BaseMqttBlockEntity (topic, ownerName, privateMode,
# lastTabId), which M2-12 hoists into legacyItems and the link.
#   buildm1.sh place      on a fresh world of a dev server built BEFORE M2-12: places the devices and fills their slots
#   buildm1.sh capture    after a restart (the v1 emitter is adopted then): prints every device's block entity data
#   buildm1.sh check      after the upgrade: every slot item is in mineqtt.legacyItems, no top-level key is left, the
#                         sensor's eggs became its filter, the Raw Links are the captured ones
# Devices at y -60, z 0 (RCON through game.py; MQTT may be enabled, its retained topics are the bridge's):
#   x 0  Redstone Input   paper m1/door in slot 0, ON/SHUT payload papers in slots 2 and 3
#   x 4  Redstone Output  paper m1/door in slot 0, ON/SHUT payload papers: wireless redstone from the input
#   x 8  Smart Lamp       paper m1/lamp in slot 0, stone in slot 1
#   x 12 Presence Sensor  paper m1/hall in slot 0, zombie and pig eggs in slots 2 and 3, stone and dirt in the never
#                         shown slots 7 and 8, counting mobs
#   x 16 Redstone Output  private mode of its owner Steve, paper m1/private in slot 0
#   x 20 Redstone Output  v1 data (paper m1/legacy, payload papers), adopted with a grandfathered topic at the restart
DIR=$(cd "$(dirname "$0")" && pwd)
E="python $DIR/game.py"
Y=-60
# Steve's offline-mode UUID (UUID.nameUUIDFromBytes("OfflinePlayer:Steve")) as an NBT int array.
STEVE=$(python -c "import hashlib,struct; b=bytearray(hashlib.md5(b'OfflinePlayer:Steve').digest()); b[6]=b[6]&0x0f|0x30; b[8]=b[8]&0x3f|0x80; print('[I;%d,%d,%d,%d]' % struct.unpack('>4i', bytes(b)))")

paper() {
    echo "{Slot:$1b,id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"$2\"}}"
}

item() {
    echo "{Slot:$1b,id:\"minecraft:$2\",count:$3}"
}

place() {
    $E rcon forceload add 0 0 24 0
    $E rcon setblock 0 $Y 0 mineqtt:redstone_publisher_block
    $E rcon setblock 4 $Y 0 mineqtt:redstone_emitter_block
    $E rcon setblock 8 $Y 0 mineqtt:rgb_led_block
    $E rcon setblock 12 $Y 0 "mineqtt:motion_sensor_block[facing=south]"
    $E rcon setblock 16 $Y 0 mineqtt:redstone_emitter_block
    $E rcon setblock 20 $Y 0 mineqtt:redstone_emitter_block
    # The devices attach on the next tick; the slots are filled afterwards, as a player fills them in the screen.
    sleep 2
    $E rcon data merge block 0 $Y 0 "{Items:[$(paper 0 m1/door),$(paper 2 ON),$(paper 3 SHUT)]}"
    $E rcon data merge block 4 $Y 0 "{Items:[$(paper 0 m1/door),$(paper 2 ON),$(paper 3 SHUT)]}"
    $E rcon data merge block 8 $Y 0 "{Items:[$(paper 0 m1/lamp),$(item 1 stone 3)]}"
    $E rcon data merge block 12 $Y 0 "{Items:[$(paper 0 m1/hall),$(item 2 zombie_spawn_egg 1),$(item 3 pig_spawn_egg 2),$(item 7 stone 5),$(item 8 dirt 7)]}"
    $E rcon mineqtt devices setting "$($E rcon data get block 12 $Y 0 mineqtt.slug </dev/null | sed -n 's/.*data: "\(.*\)"/\1/p')" detect mobs
    $E rcon data merge block 16 $Y 0 "{ownerName:\"Steve\",privateMode:1b,mineqtt:{owner:$STEVE,ownerName:\"Steve\"},Items:[$(paper 0 m1/private)]}"
    $E rcon data remove block 20 $Y 0 mineqtt
    $E rcon data merge block 20 $Y 0 "{topic:\"m1/legacy\",lastTabId:\"settings\",ownerName:\"Steve\",privateMode:0b,currentSubscribedTopic:\"m1/legacy\",Items:[$(paper 0 m1/legacy),$(paper 2 UP),$(paper 3 DOWN)]}"
    sleep 2
    $E rcon mineqtt devices list
    $E rcon save-all flush
}

capture() {
    for x in 0 4 8 12 16 20; do
        echo "== x $x"
        $E rcon data get block $x $Y 0 </dev/null
    done
}

# The value part of a "data get block" answer ("... has the following block data: <value>").
value() {
    $E rcon data get block "$1" $Y 0 "$2" </dev/null | sed -n 's/.*has the following block data: //p'
}

expect() {
    actual=$(value "$1" "$2")
    if [ -n "$actual" ] && echo "$actual" | grep -qF -- "$3"; then
        echo "PASS x $1: $2 = $actual"
    else
        echo "FAIL x $1: $2 is '$actual', expected $3"; failed=1
    fi
}

absent() {
    if [ -z "$(value "$1" "$2")" ]; then
        echo "PASS x $1: no $2"
    else
        echo "FAIL x $1: $2 is still there"; failed=1
    fi
}

check() {
    failed=0
    for x in 0 4 8 12 16 20; do
        expect $x mineqtt.v 3
        for key in Items topic ownerName privateMode lastTabId currentSubscribedTopic; do absent $x $key; done
    done
    # legacyItems lists the stacks in slot order, each with its Slot.
    expect 0 'mineqtt.legacyItems[1].components' '"ON"'
    expect 0 mineqtt.raw.topic '"mineqtt/shared/m1/door"'
    expect 4 'mineqtt.legacyItems[2].components' '"SHUT"'
    expect 4 'mineqtt.legacyItems[2].Slot' 3b
    expect 8 'mineqtt.legacyItems[1].id' '"minecraft:stone"'
    expect 12 'mineqtt.legacyItems[1].id' '"minecraft:zombie_spawn_egg"'
    expect 12 'mineqtt.legacyItems[4].count' 7
    expect 12 mineqtt.state.filter '"zombie, pig"'
    expect 16 mineqtt.private 1b
    expect 16 mineqtt.raw.topic '/raw/players/'
    expect 20 mineqtt.raw.grandfathered.topic '"m1/legacy"'
    expect 20 'mineqtt.legacyItems[0].components' '"m1/legacy"'
    [ $failed -eq 0 ] && echo "buildm1: PASS" || echo "buildm1: FAIL"
    return $failed
}

case "$1" in
    place) place ;;
    capture) capture ;;
    check) check ;;
    *) sed -n '2,17p' "$0"; exit 2 ;;
esac

#!/bin/sh
# Builds the v1 world for E2E items 5-7: run against a dev server with MQTT disabled, then stop it and upgrade.
E="python $(dirname "$0")/game.py"
$E rcon forceload add 0 0 31 15
$E rcon forceload add 3000 3000
$E rcon forceload add 4000 4000
$E rcon setblock 0 -60 0 mineqtt:rgb_led_block
$E rcon setblock 4 -60 0 mineqtt:rgb_led_block
$E rcon setblock 3000 -60 3000 mineqtt:rgb_led_block
$E rcon setblock 20 -60 0 mineqtt:rgb_led_block
$E rcon setblock 4000 -60 4000 mineqtt:rgb_led_block
$E rcon setblock 8 -60 0 mineqtt:redstone_publisher_block
$E rcon setblock 12 -60 0 mineqtt:redstone_emitter_block
$E rcon setblock 16 -60 0 mineqtt:motion_sensor_block
sleep 2
$E v1lamp 0 -60 0 home/kitchen 15 0 0 255 1
$E v1lamp 4 -60 0 home/hall 0 15 0 200 1
$E v1lamp 3000 -60 3000 home/hall 0 15 0 200 1
$E v1lamp 20 -60 0 home/garden 0 0 15 255 0
$E v1lamp 4000 -60 4000 home/office 15 15 0 255 1
$E v1 8 -60 0 publisher home/garage
$E v1 12 -60 0 emitter home/garage
$E v1motion 16 -60 0 home/motion 0
$E rcon forceload remove 3000 3000
$E rcon forceload remove 4000 4000
$E rcon save-all flush

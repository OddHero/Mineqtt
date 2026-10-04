# Mineqtt

## Control real devices from inside your Minecraft World

Control real life devices with minecraft redstone or display sensor values on your fancy 7 segment redstone display, or
use it to wirelessly connect redstone contraptions in your Minecraft world.

Or maybe you want to know if your virtual nuclear reactor is about to blow up, with Mineqtt you could trigger a real
airhorn/sirene to wake you up before its too late.

## Inter Server Communication - Signals Between Minecraft Worlds

Another Use Case is wireless redstone between multiple servers that are connected to the same mqtt broker. You can also
send signals from Single Player Worlds to online Servers and Vice versa.

## Note of caution

This Mod establishes a connection to a seperate MQTT Server (Configurable). Connecting to public mqtt servers bears the
risk of someone else triggering your redstone or sending unfiltered Data to your server/world or receiving the signals
you send via unsecured public mqtt servers. I suggest using a self hosted MQTT-Broker.

## Features

### Block Sensor

The Block Sensor watches the adjacent block at its observer face. Place it like a vanilla observer;
it supports all six directions. Configure its topic in **MQTT Settings** with the same base/sub-topic
item slots and private mode as the other MineQTT blocks. In **Block Data**, select the payload:

| Mode                      | MQTT payload                                                                                                   |
|---------------------------|----------------------------------------------------------------------------------------------------------------|
| Full block data (default) | JSON containing ID, display name, state properties, position, dimension and block entity NBT converted to JSON |
| Block ID                  | Plain registry name, e.g. minecraft:stone                                                                      |
| Display name              | Plain display name, e.g. Stone, using the server language                                                      |
| State properties          | JSON object, e.g. {"facing":"north","powered":"true"}; {} for blocks without properties                        |

Full data example (the position is the watched block's position):

~~~json
{
  "id": "minecraft:stone",
  "name": "Stone",
  "properties": {},
  "position": {
    "x": 10,
    "y": 64,
    "z": 20
  },
  "dimension": "minecraft:overworld",
  "block_entity": null
}
~~~

The sensor publishes when the block in front updates, including replacement with air or changes to
its state properties. Full-data mode also checks block entity data every 10 game ticks (normally
0.5 seconds), so inventory changes can be published without replacing a chest. It sends a fresh
snapshot when enabled, loaded, reconnected, or when its topic or data mode changes. The selected
mode persists across world saves. A base-topic item or a valid private-mode owner is required;
the sensor uses the configured server MQTT connection.

Craft one with seven iron ingots around an observer, with redstone in the top-middle slot:

~~~text
Iron  Redstone  Iron
Iron  Observer  Iron
Iron  Iron      Iron
~~~

Run payload regression checks with ./gradlew :common:verifyBlockSensor (also included in check).
For an in-game check, subscribe to the configured MQTT topic, replace the watched block, toggle
a lever in front, test each payload mode, change chest contents in full-data mode, then reload the
world and reconnect the broker. Also check the sensing face when placing upward or downward.

## Roadmap

### Implemented

- Connect to MQTT-Broker with username/password Authentication
- Gui to configure topics for Senders and Receivers
- Portable Topic Debugger (Subscribe/Publish via handheld "Cyberdeck")
- Smart Light remote control

### Planned

- Send/Receive Redstone Signal Strength
- Integrate Native Minecraft features like chat, scorebord, achievements,...)

### Showcase

#### Send redstone state to MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/o80VsFaYbnM" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Receive redstone state from MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/bIY0cpPkwmU" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Configuring topics using item stacks and named paper

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/w5Pt1TFN1m4" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>


# MineQTT

## Control real devices from inside your Minecraft world

Control real life devices with Minecraft redstone, or show sensor values on a display in your world. Or connect
redstone contraptions wirelessly, inside one world and between worlds and servers.

Maybe you want to know when your virtual nuclear reactor is about to blow up. With MineQTT you could trigger a real
airhorn to wake you up before it is too late.

- **Real smart-home devices.** Home Assistant discovers every placed Smart Lamp, Redstone Output, Redstone Input,
  Smart Plug, Presence Sensor, Climate Sensor, Wall Switch, Display Panel, timer, Block Observer and Block Placer as
  its own device, under a **MineQTT Bridge** device that also reports the world's time, weather and load.
- **Mirror your house.** A lamp follows the real kitchen light, a Display Panel shows the outdoor temperature, and a
  pressure plate switches the real hallway light.
- **Works without the broker.** Wires connect devices on the server, so a Daylight Timer switches the lamps at sunset
  with no automation at all.
- **Signals between worlds.** Servers and single player worlds on the same broker send redstone signals to each other.

## Documentation

Everything about MineQTT is in the **[MineQTT wiki](https://github.com/OddHero/Mineqtt/wiki)**:

| Page | What it covers |
|---|---|
| [Getting Started](https://github.com/OddHero/Mineqtt/wiki/Getting-Started) | Install the mod, connect a broker, place your first device |
| [Configuration](https://github.com/OddHero/Mineqtt/wiki/Configuration) | Every config key and its default |
| [Broker Setup](https://github.com/OddHero/Mineqtt/wiki/Broker-Setup) | A private Mosquitto broker with an ACL |
| [Devices](https://github.com/OddHero/Mineqtt/wiki/Devices) | Every block, its topics, state and commands |
| [Configurator](https://github.com/OddHero/Mineqtt/wiki/Configurator) | The handheld tool: configure, link, group, bind and make rooms |
| [Home Assistant](https://github.com/OddHero/Mineqtt/wiki/Home-Assistant) | Discovery, world telemetry, presence and rooms |
| [Mirror Your House](https://github.com/OddHero/Mineqtt/wiki/Mirror-Your-House) | Bring Home Assistant entities into Minecraft and switch them back |
| [Commands](https://github.com/OddHero/Mineqtt/wiki/Commands) | Every `/mineqtt` command and permission |
| [Upgrading from v1](https://github.com/OddHero/Mineqtt/wiki/Upgrading-from-v1) | What changes when a v1 world loads |

What changed in each release is in [CHANGELOG.md](https://github.com/OddHero/Mineqtt/blob/main/CHANGELOG.md). To build MineQTT
from source, see [Developing MineQTT](https://github.com/OddHero/Mineqtt/blob/main/.github/development.md).

## Supported versions

MineQTT runs on Fabric and NeoForge, on every Minecraft release from 1.21 to 1.21.11 and from 26.1.2 to 26.3. There
is one jar per loader and version: `MineQTT-<loader>-<mod version>+<minecraft>.jar`.

- Fabric needs Fabric API and Architectury API.
- NeoForge needs Architectury API.
- Home Assistant needs version 2025.2 or newer, with the MQTT integration on the same broker.

The wiki's [Supported versions](https://github.com/OddHero/Mineqtt/wiki#supported-versions) lists which jar covers
which patch releases.

## Note of caution

MineQTT connects to an MQTT broker that you configure. On a public broker, anyone can trigger your redstone, send
data into your world and read the signals you send. Use a self-hosted broker with a password and an ACL
([Broker Setup](https://github.com/OddHero/Mineqtt/wiki/Broker-Setup)). MQTT stays disabled until you configure a
broker.

## Showcase

**Send redstone state to MQTT**

[![Send redstone state to MQTT](https://img.youtube.com/vi/o80VsFaYbnM/hqdefault.jpg)](https://www.youtube.com/watch?v=o80VsFaYbnM)

**Receive redstone state from MQTT**

[![Receive redstone state from MQTT](https://img.youtube.com/vi/bIY0cpPkwmU/hqdefault.jpg)](https://www.youtube.com/watch?v=bIY0cpPkwmU)

**Configuring topics using item stacks and named paper** (MineQTT v1)

[![Configuring topics using item stacks and named paper](https://img.youtube.com/vi/w5Pt1TFN1m4/hqdefault.jpg)](https://www.youtube.com/watch?v=w5Pt1TFN1m4)

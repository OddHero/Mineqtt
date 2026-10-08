# Mineqtt

## Control real devices from inside your Minecraft World

Control real life devices with minecraft redstone or display sensor values on your fancy 7 segment redstone display, or
use it to wirelessly connect redstone contraptions in your Minecraft world.

Or maybe you want to know if your virtual nuclear reactor is about to blow up, with Mineqtt you could trigger a real
airhorn/sirene to wake you up before its too late.

MineQTT blocks are real smart-home devices: Home Assistant discovers each placed lamp, redstone output, redstone input
and presence sensor as its own device under a **MineQTT Bridge** device for the world.

## Inter Server Communication - Signals Between Minecraft Worlds

Another Use Case is wireless redstone between multiple servers that are connected to the same mqtt broker. You can also
send signals from Single Player Worlds to online Servers and Vice versa.

## Note of caution

This Mod establishes a connection to a seperate MQTT Server (Configurable). Connecting to public mqtt servers bears the
risk of someone else triggering your redstone or sending unfiltered Data to your server/world or receiving the signals
you send via unsecured public mqtt servers. Use a self-hosted broker with a password and the ACL below. MQTT stays
disabled until you configure a broker.

## Configuration

The config file is `config/mineqtt.json` on Fabric (also editable in game through Mod Menu) and `config/mineqtt.toml`
on NeoForge (the same keys; the nested ones are TOML sections such as `[tls]`). A v1 file is migrated on the first start
and kept as `mineqtt.json.v1.bak` / `mineqtt.toml.v1.bak`.

| Key | Default | Meaning |
|---|---|---|
| `broker` | empty | Broker URI. Empty disables MQTT. See the forms below. |
| `protocol` | `5` | `5` or `3.1.1`. Use `3.1.1` for brokers without MQTT 5 (for example Mosquitto 1.x). |
| `username`, `password` | empty | Broker credentials. |
| `keepAliveSec` | 60 | MQTT keep-alive. |
| `tls.caFile` | empty | PEM file with the CA that signed the broker certificate. Empty uses the Java trust store. |
| `tls.insecureSkipVerify` | false | Skips certificate validation. For testing only; logs a warning on every connect. |
| `bridgeId` | empty | Topic level of this world, and the client id `mineqtt_<bridgeId>`. Empty generates one per world (`<world>_<4 hex>`, e.g. `world_7f3a`). |
| `baseTopic` | `mineqtt` | Root of every MineQTT topic. |
| `discoveryPrefix` | `homeassistant` | Home Assistant discovery prefix. |
| `haBirthTopic` | `homeassistant/status` | Home Assistant's birth topic; `online` there republishes everything. |
| `ha.expose` | `AUTO` | `ALL`, `OPT_IN`, `NONE`; `AUTO` means `ALL` in single player and `OPT_IN` on dedicated servers. See [Home Assistant](#home-assistant). |
| `acl.rawAllow` | `mineqtt/shared/#`, `<base>/<bridgeId>/raw/#` | Topics players may use for raw links and the Cyberdeck. |
| `acl.opsBypassAllowlist` | `AUTO` | Whether ops may use topics outside `acl.rawAllow`; `AUTO` = yes in single player, no on dedicated servers. |
| `acl.explorerBrokerWide` | false | Lets admins listen to the whole broker with the Cyberdeck. |
| `limits.maxDevicesPerPlayer` | 0 | Maximum devices one player may own; 0 is unlimited. Not enforced yet: the key is read and saved, but placing more devices is not refused. |
| `blockPlacer.enabled` | false | Enables the block emitter (places blocks from MQTT payloads). |

**Broker URI forms**

| Form | Transport | Default port |
|---|---|---|
| `host`, `host:port`, `tcp://host:port`, `mqtt://host:port` | MQTT over TCP | 1883 |
| `ssl://host:port`, `mqtts://host:port`, `tls://host:port` | MQTT over TLS | 8883 |
| `ws://host:port/path` | MQTT over WebSocket | 80, path `/mqtt` |
| `wss://host:port/path` | MQTT over secure WebSocket | 443, path `/mqtt` |

IPv6 literals go in brackets (`tcp://[::1]:1883`). Credentials, queries and fragments are not allowed in the URI.

**Password from the environment.** When `MINEQTT_PASSWORD` is set, it replaces the configured password, so the password
need not be stored in the config file. It is never logged and is masked in the config screens and in `/mineqtt status`.

**MQTT 3.1.1.** With `protocol: 5` against a broker that only speaks 3.1.1, the connection fails and `/mineqtt status`
says "if it only supports MQTT 3.1.1 set protocol: 3.1.1". MineQTT drops inbound messages larger than 64 KiB. On
MQTT 5 it tells the broker its maximum packet size, so the broker never sends such a message; MQTT 3.1.1 cannot, so a
3.1.1 client reads every message whole before dropping an oversized one. A broker-side limit (Mosquitto 2:
`max_packet_size`; Mosquitto 1.x: `message_size_limit`) keeps them away, but it applies to every client on the broker:
pick a value above the largest payload your other publishers send (camera snapshots, for example), or give MineQTT its
own listener or broker.

## Devices and topics

Each device publishes under `<baseTopic>/<bridgeId>/<slug>`, the slug derived from the device name
(`mineqtt/world_7f3a/kitchen_lamp`):

| Topic | Content |
|---|---|
| `…/<slug>` | State JSON, retained |
| `…/<slug>/set` | JSON command, e.g. `{"state":"ON","brightness":128}` |
| `…/<slug>/set/<field>` | Plain value, e.g. `…/set/state` with `TOGGLE` |
| `…/<slug>/availability` | `online` / `offline`, retained |
| `…/<slug>/action` | Events |
| `…/group/<slug>` (+ `/set`) | Groups |
| `…/bridge/state`, `…/bridge/info`, `…/bridge` | Bridge availability (also the last will), version info, telemetry |

Commands are never retained. A retained message on a device's `/set` topics is ignored, so a stale command left on the
broker cannot change a device when the server starts. Raw links are different, see below.

| Device (block) | State | Commands and notes |
|---|---|---|
| **Smart Lamp** (RGB LED block) | `state`, `brightness` 0-255, `color_mode` (`rgb`/`color_temp`), `color` {r,g,b}, `color_temp` and `color_temp_kelvin` (both in kelvin), `light_level`, `location` | Home Assistant JSON light commands. `color_temp` is read as kelvin from 1000 up and as mireds below, so v1 automations that send mireds keep working. Plain colour names and `#rrggbb` also work on the raw link. |
| **Redstone Output** (redstone emitter) | `state` ON/OFF, `power` 0-15, `mode`, `power_on_behaviour`, `last_level` | `{"power":7}`, `{"state":"ON"}` (restores the last non-zero level), `TOGGLE`. `mode`: `level` (analog, default) or `switch` (any level emits 15). `power_on_behaviour` (`previous`/`off`/`on`) applies when the server starts. |
| **Redstone Input** (redstone publisher) | `powered`, `signal` 0-15 | State is published at most once per second; short pulses are reported as `{"event_type":"pulse","signal":n}` on `…/action` at every rising edge. |
| **Presence Sensor** (motion sensor) | `occupancy`, `count`, `detect`, `range`, `clear_delay` | `/set/detect` (`players`/`mobs`/`all`), `/set/range` (1-8 blocks), `/set/clear_delay` (seconds). The count updates at once; occupancy stays on for `clear_delay` after the last detection. Spectators are never counted; spawn eggs in the filter slots restrict which mobs count. Sensors migrated from v1 count mobs, as v1 did. |

Lamps and outputs stay `online` while their chunk is unloaded: a command is queued and applied when the chunk loads.
Sensors go `offline` when unloaded.

**Raw link.** The topic in a block's item slots (named paper, as in v1) still works as a *raw link* next to the device
topics: outputs and lamps follow it, inputs and sensors publish to it with the v1 payloads (`ON`/`OFF`, `TRUE`/`FALSE`,
`0`-`15`, `TOGGLE`, or your custom on/off payloads from slots 2 and 3). Wireless redstone on one server keeps working
while the broker is down. Players may only use topics in `acl.rawAllow` (by default `mineqtt/shared/…` and their own
`<base>/<bridgeId>/raw/…`); topics that v1 blocks already used keep working for their owner. As in v1, a Redstone
Output applies the retained value on its raw link when it subscribes, and so do the blocks not yet migrated to the
device model (Block Emitter, Smart Plug, Topic Relay). Whoever may publish retained messages to those topics can
therefore change the world when the server starts, so limit write access to them in the broker ACL.

**Groups.** `/mineqtt group create <name>` and `/mineqtt group add <group> <device>` make devices of one type share a
state: a command on `…/group/<slug>/set` reaches every member, and Home Assistant shows one entity for the group.

### Commands

All need op level 2 (`/mineqtt publish` level 3).

| Command | Purpose |
|---|---|
| `/mineqtt status` | Connection, broker, protocol, device counts, Home Assistant mode, dropped messages |
| `/mineqtt reconnect` | Rebuild the connection from the current config |
| `/mineqtt devices list \| info <device> \| rename <device> <name> \| remove <device> \| prune` | Manage devices |
| `/mineqtt group create <name> \| add <group> <device> \| remove <group> [<device>] \| list` | Manage groups |
| `/mineqtt discovery republish \| clear [--bridge <oldId>]` | Republish or clear Home Assistant discovery |
| `/mineqtt publish "<topic>" <payload>` | Publish a message (checked against the ACL) |

## Home Assistant

**Requirements: Home Assistant 2025.2 or newer** with the MQTT integration connected to the same broker. MineQTT uses
device-based discovery and the colour temperature options in kelvin that 2025.2 introduced. Home Assistant 2025.1 still
creates the entities but reads the lamp's colour temperature as mireds, so colour temperatures show nonsense values.
Tested with 2025.2.5 and 2026.10.0.

What appears in Home Assistant:

- A **MineQTT Bridge** device per world (named after the world) with a connectivity sensor, version, device count,
  players online, a Republish button and (disabled by default) dropped-message counters.
- One device per exposed MineQTT block, linked to the bridge. The main entity is named after the device: a lamp named
  "Kitchen Lamp" becomes `light.kitchen_lamp`. Renaming the device keeps the entity and its history.
- All device entities become unavailable when the server stops or crashes; the bridge's connectivity sensor turns off.
- When Home Assistant restarts, MineQTT republishes everything within a few seconds; no server restart is needed.

**Which devices are discovered** (`ha.expose`):

| Mode | Default on | Behaviour |
|---|---|---|
| `ALL` | Single player and LAN worlds | Every device. |
| `OPT_IN` | Dedicated servers | Only devices that a player with op level 2 (or the `mineqtt:ha.expose` permission) exposed. Lamps migrated from v1 stay exposed. |
| `NONE` | | Nothing; existing discovery is removed. |

In M1 there is no in-game Expose button yet; on a dedicated server set `ha.expose` to `ALL` to discover new devices.

**Dedicated servers pause when empty.** Since Minecraft 1.21.2 a dedicated server stops ticking after
`pause-when-empty-seconds` (default 60) without players. MineQTT handles MQTT messages on the server tick, so while the
server is paused Home Assistant commands wait in the queue (`/mineqtt status` shows them as `queued`) and redstone does
not update. For a server that should react with nobody online, set `pause-when-empty-seconds=0` in `server.properties`.

### Broker ACL (Mosquitto)

Give MineQTT its own broker user and limit it to its namespaces:

```
# mosquitto.conf
allow_anonymous false
password_file /mosquitto/config/passwd
acl_file /mosquitto/config/acl

# acl
user mineqtt
topic readwrite mineqtt/#
topic readwrite homeassistant/#
topic read ha_statestream/#
# Raw-link topics outside mineqtt/ that your worlds still use from v1, for example:
# topic readwrite home/#
# Worlds upgraded from v1: a v1 baseTopic that the migration kept needs its own line instead of mineqtt/#:
# topic readwrite <v1 baseTopic>/#
# and the v1 status topic (<v1 baseTopic>/<statusTopic>) must be writable once, so its retained Offline can be cleared:
# topic write minecraft/status

user homeassistant
topic readwrite #
```

Give other broker users write access to `mineqtt/shared/#` and to raw-link topics only if you trust them: a retained
message there sets a Redstone Output or an unmigrated block when the server starts (see [Raw link](#devices-and-topics)).

### Home Assistant to Minecraft (coming in M3)

Mirroring real Home Assistant entities onto blocks will read Home Assistant's state through `mqtt_statestream`. The
configuration it will use:

```yaml
# configuration.yaml (coming in M3, not used yet)
mqtt_statestream:
  base_topic: ha_statestream
  publish_attributes: true
  publish_timestamps: true
  include:
    domains:
      - light
      - switch
      - input_boolean
```

## Upgrading from v1

- **Config:** `brokerUrl` becomes `broker` (the old public default `test.mosquitto.org` is not carried over), `clientId`
  is no longer used, a default `baseTopic` becomes `mineqtt` and a customised one is kept (extend the broker ACL for
  it). The old file is kept as a `.v1.bak`.
- **Topics:** devices now publish under `mineqtt/<bridgeId>/…`. The topics in the item slots keep working as raw links,
  including wireless redstone links between blocks and servers. v1 lamps also keep publishing `<topic>/state`.
- **Retain:** state, availability and discovery are now retained; commands still are not. v1's retained `Offline` on
  its status topic (`<v1 baseTopic>/<statusTopic>`, for example `minecraft/status` or `minecraft-<clientId>/status`) is
  cleared once; with a broker ACL, MineQTT needs write access to that topic for the first start after the upgrade.
- **Home Assistant:** v1 lamps keep their entities (same `light.…` entity id and history); Home Assistant moves them to
  new MineQTT devices. Lamps that shared one topic become one group and stay one light. Home Assistant may keep an empty
  device named "MineQTT RGB LED" per migrated topic; delete it under Settings → Devices.
  This also works for lamps in chunks nobody visits, and when Home Assistant is offline during the upgrade.
- **Lamp state:** the shared state per topic (`mineqtt_topic_states.json`) is imported once; afterwards each lamp has its
  own state, and lamps that shared a topic form a group. The old file is left untouched.
- **Blocks** keep their ids, items and settings. Blocks not yet migrated to the device model work exactly as in v1.

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

## Development launches

Gradle generates IntelliJ run configurations grouped under **Fabric** and **NeoForge**.
Each loader has four configurations:

- `Server:<loader>` starts a dedicated server without a GUI.
- `Client:<loader>` starts a client with DevAuth explicitly disabled.
- `Client:<loader>:Main` enables DevAuth using the `main` account.
- `Client:<loader>:Alt` enables DevAuth using the `alt` account.

The login variants use the accounts in your existing DevAuth configuration. Account setup
and tokens stay local; see the [DevAuth documentation](https://github.com/DJtheRedstoner/DevAuth#configuration-file).
All four configurations for a loader share that loader's existing run directory.

To regenerate the configurations, run `./gradlew :fabric:ideaSyncTask :neoforge:ideaSyncTask`.
The matching Gradle launch tasks are `runServer`, `runClient`, `runClientDevMain`, and
`runClientDevAlt` in each loader project. If an existing IntelliJ configuration retains old
JVM arguments after a dependency upgrade, remove that configuration and regenerate it;
Loom preserves JVM arguments in existing application configurations.

`./gradlew check` runs the regression checks. [`e2e/`](e2e/README.md) holds the Docker environment (Mosquitto 2 and 1.5,
Home Assistant) and the manual end-to-end checklist. The architecture is described in
[`.github/mineqtt-architecture.md`](.github/mineqtt-architecture.md).

## Roadmap

### Implemented

- Connect to an MQTT broker over TCP, TLS or WebSockets, MQTT 5 or 3.1.1, with username/password authentication
- Home Assistant discovery: bridge device, Smart Lamp, Redstone Output, Redstone Input, Presence Sensor
- Gui to configure topics for Senders and Receivers
- Portable Topic Debugger (Subscribe/Publish via handheld "Cyberdeck")
- Smart Light remote control

### Planned

- In-game device configuration (names, rooms, Home Assistant exposure) and more device types
- Mirror real Home Assistant entities onto blocks (digital twin)
- Integrate Native Minecraft features like chat, scorebord, achievements,...)

### Showcase

#### Send redstone state to MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/o80VsFaYbnM" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Receive redstone state from MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/bIY0cpPkwmU" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Configuring topics using item stacks and named paper

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/w5Pt1TFN1m4" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

# Mineqtt

## Control real devices from inside your Minecraft World

Control real life devices with minecraft redstone or display sensor values on your fancy 7 segment redstone display, or
use it to wirelessly connect redstone contraptions in your Minecraft world.

Or maybe you want to know if your virtual nuclear reactor is about to blow up, with Mineqtt you could trigger a real
airhorn/sirene to wake you up before its too late.

MineQTT blocks are real smart-home devices: Home Assistant discovers each placed Smart Lamp, Redstone Output, Redstone
Input, Smart Plug, Presence Sensor, Climate Sensor and Wall Switch as its own device under a **MineQTT Bridge** device
for the world. Devices can also be wired to each other on the server, so local contraptions keep working without the
broker. What changed in each release is in [CHANGELOG.md](CHANGELOG.md).

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
| `acl.rawAllow` | `mineqtt/shared/#`, `<base>/<bridgeId>/raw/#` | Topics players may use for raw links and the Configurator's explorer. |
| `acl.opsBypassAllowlist` | `AUTO` | Whether ops may use topics outside `acl.rawAllow`; `AUTO` = yes in single player, no on dedicated servers. |
| `acl.explorerBrokerWide` | false | Lets admins listen to the whole broker with the Configurator's explorer. |
| `limits.maxDevicesPerPlayer` | 0 | Maximum devices one player may own, loaded or not; 0 is unlimited. A player who owns as many cannot place another device (the action bar says why) or claim an ownerless one; operators are not limited. Only placing and claiming are checked: devices made by `/setblock`, `/clone` or structures, and an owner's devices above a lowered limit, are kept. The Block Sensor, Block Emitter, timers and Bridge Console are no devices and do not count. |
| `limits.maxWiresPerPlayer` | 256 | Maximum wires (Configurator links and `/mineqtt wires add` of a player) one player may own; 0 is unlimited. Wires made from the console or a command block count for nobody. |
| `blockPlacer.enabled` | false | Enables the block emitter (places blocks from MQTT payloads). |
| `server.keepTickingWhenEmpty` | false | Dedicated servers only: never pause with nobody online, overriding `pause-when-empty-seconds`. See "Dedicated servers pause when empty" under [Home Assistant](#home-assistant). |

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
| **Smart Lamp** (RGB LED block) | `state`, `brightness` 0-255, `color_mode` (`rgb`/`color_temp`), `color` {r,g,b}, `color_temp` and `color_temp_kelvin` (both in kelvin), `effect` (`off`, `pulse`, `candle`), `light_level`, `location` | Home Assistant JSON light commands. `color_temp` is read as kelvin from 1000 up and as mireds below, so v1 automations that send mireds keep working. `transition` (seconds, up to 3600) fades the block light and the colour, e.g. `{"state":"ON","brightness":200,"transition":2}`; the state shows the target at once, a lamp whose chunk is not loaded or is outside the simulation distance takes it without fading, a command without a transition ends a fade, and on a server paused while empty the fade waits until the server runs again. `effect` `pulse` breathes, `candle` flickers, `off` ends it; effects are drawn by each player's game, so the block light stays steady. Plain colour names and `#rrggbb` also work on the raw link. |
| **Redstone Output** (redstone emitter) | `state` ON/OFF, `power` 0-15, `mode`, `power_on_behaviour`, `last_level` | `{"power":7}`, `{"state":"ON"}` (restores the last non-zero level), `TOGGLE`. `mode`: `level` (analog, default) or `switch` (any level emits 15). `power_on_behaviour` (`previous`/`off`/`on`) applies when the server starts. |
| **Smart Plug** | `state` ON/OFF, `power` 0 or 15, `power_on_behaviour` | `{"state":"ON"}`, `OFF`, `TOGGLE`; any `power` above 0 switches it on. Home Assistant shows an outlet switch. `power_on_behaviour` (`previous`/`off`/`on`) applies when the server starts. A right-click switches it. Its raw link takes v1's plug payloads (`ON`/`OFF`, `TRUE`/`FALSE`, `1`/`0`, `TOGGLE`, or your custom on/off payloads; no levels) and, unlike v1, never repeats its own state there. |
| **Redstone Input** (redstone publisher) | `powered`, `signal` 0-15 | State is published at most once per second; short pulses are reported as `{"event_type":"pulse","signal":n}` on `…/action` at every rising edge. |
| **Presence Sensor** (motion sensor) | `occupancy`, `count`, `detect`, `range`, `clear_delay`, `filter` | `/set/detect` (`players`/`mobs`/`all`), `/set/range` (1-8 blocks), `/set/clear_delay` (seconds). The count updates at once; occupancy stays on for `clear_delay` after the last detection. Spectators are never counted. `filter` (the Controls tab's *Mobs* field) names the mobs that count, up to 8 ids such as `zombie, pig`; empty counts every mob. Sensors migrated from v1 count mobs, as v1 did, and the spawn eggs in their old filter slots became their filter. |
| **Wall Switch** (light remote) | `location` | Two buttons. A right-click presses the button it hits (left or right as you face the switch); its events on `…/action` are `{"event_type":"press","button":"left"}`, `double_press` (a second click within 8 ticks) and `hold` (the use key held for 10 ticks; fired once, nothing when you let go), each with the `button`. A single press is told 8 ticks after the click, once no second click came. `{"press_left":"PRESS"}` (or `/set/press_left` with `PRESS`, the Controls tab's buttons, `/mineqtt devices action <switch> press_right`) presses a button at once. Home Assistant shows an event entity and a device trigger per gesture and button ("left" pressed, double clicked, continuously pressed). Its raw link, if you give it one, publishes the event's name with the button (`press_left`, `double_press_right`, `hold_left`), never retained. Wire its `press` to a lamp, plug or output (Link mode or `/mineqtt wires`) to switch it without the broker; a wire carries both buttons' presses. |
| **Climate Sensor** (temperature sensor; humidity sensors convert into it) | `temperature` °C, `humidity` %, `illuminance` lx, `biome`, `interval`, `raw_value` | Takes a reading every `interval` seconds (`/set/interval`, 1-3600, default 10). The state is published when the temperature moved by 0.2 °C, the humidity by 1 % or anything else changed, at most once per second. The readings come from the spot: the biome's temperature and whether it rains or snows there, the height, day and night, rain, an open sky or a roof, heat (fire, lava, magma, campfires) and water or ice within 5 blocks, and daylight plus block light. Its raw link publishes the reading `raw_value` names (`temperature`, `humidity` or `illuminance`) at every reading, as v1 sensors did, with a decimal point whatever the server's language (`21.5`). |

Lamps, outputs and plugs stay `online` while their chunk is unloaded: a command is queued and applied when the chunk loads.
Sensors and Wall Switches go `offline` when unloaded.

**Raw link.** The topic a device had in its v1 item slots (named paper) still works as a *raw link* next to the device
topics: outputs, plugs and lamps follow it, inputs and sensors publish to it with the v1 payloads (`ON`/`OFF`,
`TRUE`/`FALSE`, `0`-`15`, `TOGGLE`, or your custom on/off payloads) and the Wall Switch with its event names. Devices have no slots any more: the raw link is set on the
Advanced tab, with a renamed paper or with `/mineqtt devices raw`. Wireless redstone on one server keeps working
while the broker is down. Players may only use topics in `acl.rawAllow` (by default `mineqtt/shared/…` and their own
`<base>/<bridgeId>/raw/…`); topics that v1 blocks already used keep working for their owner. As in v1, a Redstone
Output and a Smart Plug apply the retained value on their raw link when they subscribe, and so does the Block Emitter,
which is not yet migrated to the device model. Whoever may publish retained messages to those topics can
therefore change the world when the server starts, so limit write access to them in the broker ACL.

**Item and paper names go to a shared namespace.** A channel (an item or paper name, as in the v1 slots) that the
allowlist does not cover as written becomes `mineqtt/shared/<name>`: stone is `mineqtt/shared/minecraft/stone`, paper
named `garage` is `mineqtt/shared/garage`. Operators get no exception here, so blocks with the same item or paper talk
to each other on one server and across servers that share the broker and allow the same topics. The exception is a
topic its owner's v1 blocks already used: it stays as written for that owner only, so a v1 Redstone Output on
`minecraft/stone` follows its owner's new stone links but not another player's, which go to
`mineqtt/shared/minecraft/stone`. Paper named with a full topic, such as `mineqtt/shared/garage`, is used as written.
When a link moves or is refused, the player who set it (or its owner) is told in chat and in the action bar, and the
device screen shows why; links saved by a v2 test build that the allowlist now refuses were moved this way once.

**Advanced tab.** The device screen's Advanced tab edits the raw link. In *Channel* mode you type an item or paper
name and the server picks the topic, as it did for the v1 slots; in *Private* mode the same name goes into the
owner's own subtree `<base>/<bridgeId>/raw/players/<owner>/…`, which only the owner's devices use (v1's private mode; the device needs an owner); in *Topic* mode the topic is used
exactly as typed, if the server's allowlist lets both you and the device's owner use it. Enter or Save sends it; the
tab shows the topic the link listens on or sends to, or why it is refused. Inputs and sensors also have a retain
switch (their default is marked) and QoS 0 or 1 (a Wall Switch, whose link carries events, has the QoS only), and outputs, lamps and inputs the custom on and off payloads.
**Renamed paper** used on a device
with a raw link sets its channel to the paper's name without opening the screen; the paper is not used up, and the
action bar says where the link went. Both need the right to configure the device.

**Using and configuring devices.** A right-click *uses* a device that has a natural use: it switches a Smart Lamp or
a Smart Plug on or off, and presses the Wall Switch's button it hits. Every other block opens its screen on a right-click. Sneak + right-click always opens the screen, read-only
if you may not configure the device; holding a block while sneaking places it against the device instead, as against
a chest. A **name tag** with a name, used on a device, renames it (the slug and topics follow, as for the name field
on the General tab); it is not used up, and the action bar shows the new name and state topic. Renaming needs the
right to configure the device.

**Configurator** (formerly the Cyberdeck; same item id). A right-click opens the screen of the device you point at
from up to 16 blocks away, e.g. a ceiling lamp; the screen stays open while you hold the Configurator and stay within
that range. Pointing at nothing, it opens the topic explorer (listen, publish). Sneak + right-click into
the air steps its mode: *Configure*, *Link*, *Group* and *Copy-Paste*. Blocks that are not devices open from
close by only. The explorer no longer keeps anything on the item: whether you listen is kept by the
server (it resumes when you rejoin, until the server restarts), and its topic slots give their items back when the
screen closes. An old Cyberdeck gives back the items its slots held the first time you carry it.

**Link mode** wires one device to another on the server, without the broker (a *wire*, as `/mineqtt wires` makes
them). Click the device that drives, e.g. a Redstone Input, then the device it drives, e.g. a lamp, an output or any
lamp of a group: a picker lists every pair of ports that fit, the likely one first and selected (an input's `powered`
into a lamp's `state`), and the mapping (*follows*, *inverted*, *toggles*). *Link* makes the wire, and the action bar
names it: "Linked Garage Input.powered → Porch Lamp.state". A following or inverted wire sets the target to the
source's current value at once; a toggling one waits for the next press or switch-on. The same two clicks again offer
*Unlink* for a pair that is linked; clicking the first device again, or into the air, stops linking. Both devices
must be ones you may configure (every lamp of a group), and you may own `limits.maxWiresPerPlayer` wires. Sneak +
right-click in Link mode opens the device's screen. The **Links** button at the foot of the Advanced tab lists the
device's wires, from and to it and into its group, with the port at each end (mapping and group on hover); the × next
to one removes it for anyone who may configure the device, so you can always cut a wire to or from your device. A
wire into its group drives the other lamps too, so it goes only for someone who may configure its source or every
lamp of the group; to unhook just your lamp, take it out of the group.

**Group mode** puts devices of one type into one group. Click a lamp (or an output), then another: it joins the first
one's group and takes over the group's state. When the first device is in no group yet, a prompt asks for the name of
a new group, which both devices then make, the second with the first one's state. The first device stays picked, so
each further click adds another device; clicking it again, or into the air, stops. Joining a group needs the right to
configure every device in it, as groups have no owner; a group whose devices are all gone takes none, and a group made
from a v1 topic takes a device only when its owner may use that topic. A device that moves to another group leaves its
old one, and a group left without devices, this way or because its last block was broken, is removed.

**Copy-Paste mode** copies one device's settings onto others of its type: click the device to copy, then each device
to paste onto; the action bar counts what changed. It copies the Raw Link (channel, private channel or typed topic,
retain, QoS, payloads), the type's settings (an output's mode and power-on behaviour, a presence sensor's detection),
the Home Assistant exposure and the access mode, never the name, the state, the group or the wires; a device without a
Raw Link copies none, so the target keeps its own. Every setting is checked for you as if you changed it on the
target's screen: without the right to expose devices or to change the access mode those two are refused, listed in
chat, and a typed topic must be one you may use. Clicking the copied device again, or into the air, empties the
clipboard.

**Bridge Console** (formerly the Terminal; same block and item). A right-click opens a screen of the bridge: its *Bridge*
tab shows whether MQTT is connected (and the protocol), the broker (credentials masked), the bridge's topics, whether
Home Assistant's birth or last will was heard, whether the server ticks or is paused for being empty, the messages the
bridge dropped, and the wires with what they delivered and dropped; details show on hover. Its *Devices* tab lists the
devices you may configure (an operator sees all of them), faulted ones and ones with a problem first, each with how it
runs (online, no broker, unloaded, faulted, unknown type) and its type, place and fault or Raw Link problem on hover; its
*Groups* tab lists the groups whose every device you may configure. The screen follows the bridge every second. Only an
operator sees its actions, the buttons *Reconnect*, *Republish* and *Prune*, which run `/mineqtt reconnect`,
`/mineqtt discovery republish` and `/mineqtt devices prune` as that operator; everyone else sees "Read only". The
console is no device and no Home Assistant device, and in a land claim only the players the claim trusts open it.

**Access and Diagnostics tabs.** The Access tab shows who owns the device and its access mode: *private* (only the
owner and operators), *claim* (also the players the land claim at the device trusts; without a claim mod such as Flan
that is everyone) or *public* (anyone may use it, the claim decides who configures it). Only the owner or an operator
may change the mode. A device placed without an owner (by a command or a structure) can be made yours with *Make mine*
by anyone who may configure it. The Diagnostics tab shows whether the device is online, faulted (with a *Retry*
button) or not running, its UUID with a copy button, the last MQTT message it received and the last it sent (age,
size and payload; the payload only for players who may configure it) and how many messages it received, dropped and
sent since the server started. A device that still holds the items of its old configuration slots (topic papers,
payload papers, spawn eggs) has an *Eject* button there that puts them into your inventory; breaking the device drops
them as well.

**Wireless redstone is retained.** A Redstone Input publishes every edge on its raw link *retained*, so the topic
always holds the current level: an output on another server, or one that loads later or restarts, picks it up when it
subscribes. The input publishes its live level again after every reconnect (a broker restarted without persistence
holds nothing) and once after its chunk loads; while the broker is unreachable only the last level waits. Breaking the
input, or moving its link to another topic, publishes its off payload (`false` or the custom off payload) retained to
the old topic, so nothing stays on; other inputs of the same server on that topic then publish their own level again.
With several inputs on one topic the last one to publish wins. Unloading a chunk publishes nothing. An empty payload
never switches anything (it only deletes a retained message). An input with a custom `TOGGLE` payload is not retained,
as a stored toggle would flip every receiver again whenever it subscribes; it publishes its edges only, as in v1. An
input converted from a Static Publisher publishes rising edges only, with its on payload: an event, never retained and
never published again on its own.
Presence Sensor counts and Climate Sensor readings are not retained. Worlds from v1 and M1 switch to retained automatically. An input forgotten
without breaking its block in the game (`/mineqtt devices remove`, or `prune` after the block vanished while its chunk
was unloaded) leaves its last level on the raw link topic; publish an empty retained message there to delete it.

**Groups.** Devices of one type in a group share a state: a command on `…/group/<slug>/set` reaches every member, and
Home Assistant shows one entity for the group while it hides its members. Only lamps, outputs and plugs can be
grouped. On a lamp's, an output's or a plug's General tab the group is a button that opens the group page: the group's name
(edit it and *Rename*; the slug and topics follow, Home Assistant keeps the device), how many devices it holds and its
topic, *Leave*, *Members: hidden/shown* (whether Home Assistant also discovers each member as a device of its own;
needs the right to expose devices) and *Join a group*, which lists the groups of its type you may join. In no group the
name field names a new group and *Create* makes it with the device. Renaming a group and showing its members need the
right to configure every device in it. `/mineqtt group` does all of it by command.

### Commands

All need op level 2 (`/mineqtt publish` level 3), except `devices expose`: a player with the `mineqtt:ha.expose`
permission may use it on devices they may configure. Quotes around a name are dropped: `rename lamp "Living Room"`
names it `Living Room`.

| Command | Purpose |
|---|---|
| `/mineqtt status` | Connection, broker, protocol, device counts, wires and their messages, Home Assistant mode, dropped messages |
| `/mineqtt reconnect` | Rebuild the connection from the current config |
| `/mineqtt devices list \| info <device> \| rename <device> <name> \| remove <device> \| prune` | Manage devices |
| `/mineqtt devices set <device> <json>` | Command a device like its `…/set` topic, e.g. `set kitchen_lamp {"state":"ON"}`; an unloaded lamp or output queues it |
| `/mineqtt devices setting <device> <key> <value>` | Change a setting, as the device screen does: the type's settings (`mode`, `range`, …), its other writable fields as the Controls tab sets them (`power 7`, `light {"state":"ON","brightness":128}`) and `name`, `expose`, `access`, `raw.channel`, `raw.private`, `raw.topic`, `raw.retain`, `raw.qos`, `raw.on`, `raw.off`; out-of-range values are refused |
| `/mineqtt devices raw <device> channel <name> \| private <name> \| topic <topic> \| none \| retain on\|off \| qos 0\|1 \| on <payload> \| off <payload>` | Edit the Raw Link: a channel resolves like a v1 item or paper topic (`mineqtt/shared/<name>` unless allowed as written), a private channel into the owner's own subtree; a topic is used as typed when the ACL allows it for you and for the device's owner; `""` clears a payload |
| `/mineqtt devices action <device> <action> [value]` | Perform an action the device declares, e.g. `action garage_door state TOGGLE` |
| `/mineqtt devices expose <device> on \| off \| default` | Show a device to Home Assistant, hide it, or follow `ha.expose` again |
| `/mineqtt devices access <device> private \| claim \| public` | Set who besides the owner and operators may use and configure a loaded device |
| `/mineqtt devices claim <device>` | Become the owner of a loaded device that has none (run as a player) |
| `/mineqtt devices retry <device> \| <x y z>` | Attach a faulted device again (one whose setup failed; `status` and `devices info` show it) |
| `/mineqtt devices eject <device>` | Drop the items a loaded device's old configuration slots held where it stands (`devices info` lists how many) |
| `/mineqtt group create <name> \| add <group> <device> \| remove <group> [<device>] \| rename <group> <name> \| expose-members <group> on\|off \| list` | Manage groups. `rename` moves the group's topics to the new slug; `expose-members on` lets Home Assistant discover the members beside the group (each as its own exposure allows) |
| `/mineqtt wires list [<device> \| group.<group>] \| add <from> <to> [follow\|invert\|pulse_toggle] \| remove <id>` | Wire a device's port to another device's or a group's port, e.g. `add garage_input.powered group.porch_lamps.state`. A wire works on the server alone, without the broker, and an unloaded lamp or output gets its value when its chunk loads. Without a mapping it follows (an event toggles). A following or inverted wire sets its target to the source's current value when it is made. A player's wires count against `limits.maxWiresPerPlayer`, the console's do not. `list` shows the ids `remove` takes, or enough of their start |
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
- Devices that are gone do not linger: for five seconds after every connect MineQTT reads the retained device configs
  under the discovery prefix and clears each one that names this world's bridge but no device or group the world knows,
  such as a device whose placement a crash undid. Home Assistant then removes it. Configs of other worlds' bridges and of
  other integrations are never touched, and configs over 16 KiB are skipped. The cleanup is off while the device registry
  cannot be saved (a newer MineQTT wrote it), since it may then lack devices that exist.

**Which devices are discovered** (`ha.expose`):

| Mode | Default on | Behaviour |
|---|---|---|
| `ALL` | Single player and LAN worlds | Every device. |
| `OPT_IN` | Dedicated servers | Only devices that a player with op level 2 (or the `mineqtt:ha.expose` permission) exposed. Lamps migrated from v1 stay exposed. |
| `NONE` | | Nothing; existing discovery is removed. |

Expose a device with `/mineqtt devices expose <device> on` (`off` hides it again and removes its discovery, `default`
follows `ha.expose`); it also works while the device's chunk is unloaded. A device in a group is hidden while the group
hides its members (the group page's *Members* button, or `/mineqtt group expose-members`). With `OPT_IN` a group is
discovered only while one of its devices is exposed, or when it was made from a v1 topic.

**Dedicated servers pause when empty.** Since Minecraft 1.21.2 a dedicated server stops ticking after
`pause-when-empty-seconds` (default 60) without players. MineQTT keeps handling MQTT between the paused ticks: Home
Assistant commands and wireless redstone still switch lamps, emitters and other devices, states and discovery are still
published, and devices placed or loaded by commands attach. `/mineqtt status` shows `Server: paused (empty): MQTT handled
between ticks` and the `pause-when-empty-seconds` value. What the pause still holds back: block changes and their
immediate neighbour updates happen at once, but scheduled ticks (repeaters, comparators, observers, pistons), light
updates, block entity tickers (sensors, slot edits) and timers wait until a player joins. Only when such contraptions
must keep running with nobody online, set `server.keepTickingWhenEmpty` to true in the MineQTT config (or
`pause-when-empty-seconds=0` in `server.properties`). The opt-in keeps `server.properties` as it is, logs at startup that
it overrides `pause-when-empty-seconds`, and `/mineqtt status` then shows `Server: ticking, never pauses
(server.keepTickingWhenEmpty overrides pause-when-empty-seconds=60)`. It costs a full tick, 20 times a second, while
nobody plays.

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

MineQTT publishes retained messages on `mineqtt/shared/#` and on the raw-link topics of Redstone Inputs, so its user
needs write access *with retain* there. Mosquitto's `readwrite` includes it; brokers with a separate retain permission
(EMQX, HiveMQ, VerneMQ) must grant it for the MineQTT user of every server that shares those topics.

A Redstone Output whose chunk loads after a Redstone Input on the same server changed its raw-link topic gets the new
level from the broker's retained copy. That copy was published by the server's own client, and MineQTT subscribes with
MQTT 5 no-local, which a strict reading of the specification extends to retained messages. Mosquitto 2 delivers it
anyway; a broker that withholds it leaves such an output at its saved level until the input's next change. Run
`./gradlew :common:verifyBrokerSmoke --args=host:port` against your broker: "own retained message on a no-local
subscribe: yes" means it delivers. Outputs on other servers and on MQTT 3.1.1 are not affected.

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
- **Device slots:** the seven devices (lamp, Redstone Output and Input, Presence Sensor, Smart Plug, Climate Sensor, Wall Switch) have
  no item slots any more. What their slots held is kept with the block and handed out by the Diagnostics tab's *Eject* or dropped
  when the block is broken; nothing is lost. Spawn eggs in a motion sensor's filter slots became its mob filter.
  Comparators and hoppers no longer see these seven blocks as containers.
- **Smart Plug:** a plug keeps its topic as its raw link, its custom payloads, its owner and whether it was on. It now
  remembers that it was on across restarts (v1 forgot it, so a `TOGGLE` after a restart did nothing), no longer echoes
  its own `ON`/`OFF` back to its topic (v1 did, and heard itself), and ignores levels such as `7` on its raw link, as
  v1 did. It is switched on its device topic `…/<slug>/set` or by Home Assistant as an outlet.
- **Temperature and humidity sensors:** a Temperature Sensor becomes a Climate Sensor in place, and a Humidity Sensor
  turns into one the first time its chunk loads, facing the same way, with its topic, owner, interval and an anvil
  name; it keeps publishing the humidity on its topic. Their readings are now worked out the same way for every sensor
  at the same spot instead of jumping by up to a degree at random, a jungle is no longer colder than a desert, and the
  payload has a decimal point on every server (v1 wrote `21,5` on a German one). A Humidity Sensor item turns into a
  Climate Sensor when a player carries it; one in a chest becomes one alone in the crafting grid or in a crafter.
- **Static Publisher:** it turns into a Redstone Input the first time its chunk loads, with its topic, owner and an
  anvil name. Its message is the raw link's on payload (`true` if it had none), sent only when the signal rises, never
  when it falls, and not retained, exactly as v1 published it; the Advanced tab shows *Rising edges to* and its retain
  stays off. The Redstone Input reports its signal to Home Assistant as every input does, but no longer passes the
  signal on to the blocks around it as the Static Publisher did while powered. Its message is now set on the Advanced
  tab's *On* field. A Static Publisher item turns into a Redstone Input when a player carries it; one in a chest becomes
  one alone in the crafting grid or in a crafter.
- **Light Remote:** it is now the Wall Switch (same block and item), a plate with two buttons on a wall; a Light Remote
  placed in v1 faces north, also where it stands free. Its screen of light commands is gone, and so is the explorer's
  Remote tab (publish light JSON from the explorer's Publish tab instead). Its topic slots' items are kept as legacy
  items (Diagnostics tab, *Eject*). When it first loads, its v1 topic is matched against the lamps on this server: a
  Smart Lamp of the same owner listening on that topic, or the legacy group of lamps that shared it, gets a wire from
  the switch's `press`, so a press toggles it, also without the broker. Lamps far away are found too: the switch
  waits for the lamps that have not loaded since the upgrade, however long that takes, and gives lamps MineQTT knows
  nothing of yet one minute while the switch is loaded. Otherwise its topic is dropped, with a line in the server log; wire it yourself in
  Link mode.
- **Terminal:** it is now the Bridge Console (same block and item), which shows the bridge's state and the devices you
  may configure. A Terminal placed in v1 faces north; a new one faces the player who places it.
- **Dynamic Publisher and Topic Relay:** both are retired and do nothing any more: they neither subscribe nor publish,
  ignore redstone and have no screen. A placed one keeps its look and the items its slots held; break it (with any tool
  or by hand) to get a Network Chip and those items back. Right-clicking it says so, and so does the item's tooltip. Of
  the Dynamic Publisher's readings, the players online are reported by the MineQTT Bridge device in Home Assistant; server
  time, tick count, dimension and weather have no replacement. Copy topics to other topics in Home Assistant, Node-RED or
  a broker bridge instead of a Topic Relay: a block that republishes any topic it can read to any topic it may write
  bypasses the topic allowlist.
  Neither passes a redstone signal on any more. Their items no longer show in the creative tab.
- **Wireless redstone** (Redstone Input raw links) is now published retained, also for blocks placed in v1, unless a custom payload is `TOGGLE` or the input was a Static Publisher.
- **Recipes:** every block now has a 26.2 recipe behind the Network Chip ([Crafting](#crafting)). The v1 Block Sensor,
  Block Emitter and Terminal recipes are replaced, and the retired blocks are no longer craftable.
- **Hoppers:** hoppers, droppers and item pipes no longer move items into or out of a MineQTT block's slots (v1 let
  them); only the block's screen does.
- **Owners:** v1 saved a block's owner by name only. The name is now resolved to the player's UUID: online-mode
  servers ask Mojang's profiles once the block loads; offline-mode servers bind the player of exactly that name (case
  included) when they are online as the block loads, click it or, for a device, join. From then on only that player
  is the owner, also after a rename. A player who merely has the owner's name, such as an account that took a renamed
  player's old name or one whose name differs only in case, is not. Until then an offline-mode server treats the
  player of exactly the owner's name as the owner (behind a proxy that forwards online UUIDs, from the moment they are
  bound). Until an online-mode server has resolved the name, the owner gets only what everyone gets, and the block's
  screen says "not verified yet". A world moved from offline to online mode keeps the owners it bound, whose offline
  UUIDs no online player carries.
- **Retain:** state, availability and discovery are now retained; commands still are not. v1's retained `Offline` on
  its status topic (`<v1 baseTopic>/<statusTopic>`, for example `minecraft/status` or `minecraft-<clientId>/status`) is
  cleared once; with a broker ACL, MineQTT needs write access to that topic for the first start after the upgrade.
- **Home Assistant:** v1 lamps keep their entities (same `light.…` entity id and history) and their devices: the
  "MineQTT RGB LED" device of each topic becomes the MineQTT device, with its device id, area and device automations.
  Lamps that shared one topic become one group and stay one light. This also works for lamps in chunks nobody visits,
  and when Home Assistant is offline during the upgrade.
  - *Only if an earlier v2 test build already migrated your world* (it left an empty "MineQTT RGB LED" device next to
    each migrated lamp): the device config now names both devices. Each lamp keeps its `light.…` entity id and history.
    Home Assistant 2026.10 logs "Invalid device info … already registered" for those lamps and does not add their
    sensors until its next restart, then keeps one of the two devices (which one varies) and removes the other. Older
    versions such as 2025.2 keep both without errors; delete the empty device under Settings → Devices (if that fails
    with "Unknown error", restart Home Assistant and delete it again). Either way a lamp can end up on the device the
    test build made, which has a different device id and not the old device's area, so device automations aimed at the
    old device stop working: check each lamp's area and device automations afterwards and set them again.
- **Lamp state:** the shared state per topic (`mineqtt_topic_states.json`) is imported once; afterwards each lamp has its
  own state, and lamps that shared a topic form a group. The old file is left untouched.
- **Names:** the blocks are named after what they are now: the RGB LED Subscriber is the Smart Lamp, the Redstone
  Emitter the Redstone Output, the Redstone Publisher the Redstone Input, the Motion Sensor the Presence Sensor, the
  Temperature Sensor the Climate Sensor, the Light Remote the Wall Switch, the Terminal the Bridge Console and the
  Cyberdeck the Configurator. Their ids are unchanged, so existing blocks and items stay what they are.
- **Blocks** keep their ids, items and settings. Blocks not yet migrated to the device model work exactly as in v1.

## Crafting

Every MineQTT block and the Configurator are craftable in survival behind one component, the **Network Chip**. Its
recipe unlocks in the recipe book once you hold copper, redstone, quartz or an ender pearl, and every recipe that takes
a chip unlocks once you hold one. Each timer-cycle recipe unlocks once you hold the timer it starts from.

| Output | Pattern | Key |
|---|---|---|
| Network Chip ×4 | `CRC / QEQ / CRC` | C copper ingot, R redstone, Q quartz, E ender pearl |
| Redstone Input (formerly Redstone Publisher) | `SOS / RNR / SSS` | S smooth stone, O observer, R redstone, N Network Chip |
| Redstone Output (formerly Redstone Emitter) | `STS / RNR / SSS` | T redstone torch |
| Smart Plug | `ITI / RNR / ISI` | I iron ingot, T redstone torch |
| Smart Lamp (formerly RGB LED) ×2 | `GGG / GLG / INI` | G glass, L redstone lamp, I iron ingot |
| Presence Sensor (formerly Motion Sensor) | `SHS / ONO / SSS` | H tripwire hook, O observer |
| Climate Sensor (formerly Temperature Sensor) | `CDC / CNC / SSS` | C copper ingot, D daylight detector |
| Wall Switch (formerly Light Remote) | `B B / SNS` | B stone button |
| Alarm Clock | `SCS / RNR / SSS` | C clock |
| Block Sensor | `SOS / ONO / SSS` | O observer |
| Block Emitter | `SDS / RNR / SSS` | D dispenser |
| Configurator (formerly Cyberdeck) | `_CA / _CN / S__` | _ empty, C copper ingot, A amethyst shard, S stick |
| Bridge Console (formerly Terminal) | `IGI / NCN / III` | I iron ingot, G glass, C comparator |

Unlisted letters keep their meaning from the rows above (S smooth stone, R redstone, N Network Chip). The four timers
cycle in the crafting grid: one timer alone gives the next, Alarm Clock → Game Time Timer → Cron Timer → Interval
Timer → Alarm Clock. A Humidity Sensor alone in the grid (or a crafter) gives a Climate Sensor; that recipe unlocks once
you hold one; a Static Publisher alone gives a Redstone Input the same way. The Dynamic Publisher and Topic Relay are
retired: no recipe makes or uses them (see [Upgrading from v1](#upgrading-from-v1)).

## Features

### Block Sensor

The Block Sensor watches the adjacent block at its observer face. Place it like a vanilla observer;
it supports all six directions. Configure its topic in **MQTT Settings** with base/sub-topic item slots and
private mode, as v1 blocks did (the Block Sensor is not a device yet). In **Block Data**, select the payload:

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

Craft one from three observers around a Network Chip on smooth stone (see [Crafting](#crafting)):

~~~text
Smooth Stone  Observer      Smooth Stone
Observer      Network Chip  Observer
Smooth Stone  Smooth Stone  Smooth Stone
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

### Implemented (MineQTT 0.6.0: the v2 milestones 1 and 2)

- Connect to an MQTT broker over TCP, TLS or WebSockets, MQTT 5 or 3.1.1, with username/password authentication; MQTT
  stays disabled until a broker is configured
- Home Assistant discovery: bridge device, Smart Lamp (effects and transitions), Redstone Output, Redstone Input,
  Presence Sensor, Smart Plug, Climate Sensor, Wall Switch (event entity and device triggers) and groups; devices that
  are gone are removed from Home Assistant after the next connect (ghost cleanup); v1 lamps keep their entities
- Generated device screens (General, Controls, Advanced, Access, Diagnostics), name tags, and the Configurator with
  Configure at range, Link, Group and Copy-Paste
- Wires between devices on the server, working without the broker; groups that Home Assistant sees as one device
- Retained wireless redstone, item and paper topics in the shared `mineqtt/shared/…` namespace, and private topics
- MQTT keeps working while an empty dedicated server is paused, with an opt-in to keep it ticking
- Topic allowlist, owners and access modes per device, device and wire limits per player, a server-side guard on every
  client packet
- Bridge Console: the bridge's connection, counters, devices and groups in game, with reconnect, republish and prune for
  operators
- Survival recipes for every block behind one Network Chip, unlocked through the recipe book
- Portable topic explorer (listen and publish with the Configurator)

### Planned

- Milestone 3: mirror real Home Assistant entities and other MQTT devices onto blocks (digital twin), the Display Panel,
  and the Configurator's Bind mode
- Milestone 4: rooms (Home Assistant areas), the timers as devices and flow triggers, world telemetry and player
  presence, the Block Observer and a safe Block Placer
- Milestone 5: hardening, localisation and a documentation site
- Milestone 6: flows, like Node-RED made of blocks: logic blocks, data cables and a Flow Terminal

### Showcase

#### Send redstone state to MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/o80VsFaYbnM" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Receive redstone state from MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/bIY0cpPkwmU" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Configuring topics using item stacks and named paper

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/w5Pt1TFN1m4" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

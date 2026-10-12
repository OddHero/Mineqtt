# Mineqtt

## Control real devices from inside your Minecraft World

Control real life devices with minecraft redstone or display sensor values on your fancy 7 segment redstone display, or
use it to wirelessly connect redstone contraptions in your Minecraft world.

Or maybe you want to know if your virtual nuclear reactor is about to blow up, with Mineqtt you could trigger a real
airhorn/sirene to wake you up before its too late.

MineQTT blocks are real smart-home devices: Home Assistant discovers each placed Smart Lamp, Redstone Output, Redstone
Input, Smart Plug, Presence Sensor, Climate Sensor, Wall Switch, Display Panel, timer, Block Observer and Block Placer
as its own device under a **MineQTT Bridge** device for the world, which also reports the world's time, weather and
load and, for players who agree to it, who is online ([World telemetry](#world-telemetry),
[Privacy and presence](#privacy-and-presence)). [Rooms](#rooms) put devices into Home Assistant areas. Devices can
also be wired to each other on the server, so local contraptions keep working without the broker; a Daylight Timer
switches the lamps at sunset with no automation at all. The other way round, blocks can
[mirror your house](#mirror-your-house): a lamp follows the real kitchen light, a Display Panel shows the outdoor
temperature, and a pressure plate switches the real hallway light. What changed in each release is in
[CHANGELOG.md](CHANGELOG.md).

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
| `ha.expose` | `AUTO` | `ALL`, `OPT_IN`, `NONE`; `AUTO` means `ALL` in single player and LAN worlds and `OPT_IN` on dedicated servers. See [Home Assistant](#home-assistant). |
| `acl.rawAllow` | `mineqtt/shared/#`, `<base>/<bridgeId>/raw/#` | Topics players may use for raw links and the Configurator's explorer. |
| `acl.opsBypassAllowlist` | `AUTO` | Whether ops may use topics outside `acl.rawAllow`; `AUTO` = yes in single player and LAN worlds, no on dedicated servers. |
| `acl.explorerBrokerWide` | false | Lets admins listen to the whole broker with the Configurator's explorer. |
| `limits.maxDevicesPerPlayer` | 0 | Maximum devices one player may own, loaded or not; 0 is unlimited. A player who owns as many cannot place another device (the action bar says why) or claim an ownerless one; operators are not limited. Only placing and claiming are checked: devices made by `/setblock`, `/clone` or structures, and an owner's devices above a lowered limit, are kept. The timers, the Block Observer and the Block Placer are devices and count; the Bridge Console is no device and does not. |
| `limits.maxWiresPerPlayer` | 256 | Maximum wires (Configurator links and `/mineqtt wires add` of a player) one player may own; 0 is unlimited. Wires made from the console or a command block count for nobody. |
| `twin.statestreamBase` | `ha_statestream` | The `base_topic` of Home Assistant's `mqtt_statestream`. Read-only for players and ops; only admins (op level 3 or the `mineqtt:admin` permission) may listen there in the explorer. Must not overlap `discoveryPrefix`, `baseTopic` or `mineqtt/shared`; one trailing `/` is ignored. Empty: the twin reads nothing. |
| `twin.allowedEntities` | `AUTO` | Home Assistant entities the twin may read and bind, as globs over the entity id (`*`, `?`), e.g. `light.*`. Operators do not bypass it. `[]` turns the twin off; `AUTO` alone means every entity in single player and LAN worlds and **none on dedicated servers** (the twin starts off there; `/mineqtt status` and the log name this key). Set it before you open a world to LAN or share it: every guest may then bind and control what it allows. |
| `twin.haActionAllowlist` | the 15 domains of the control blueprint | Home Assistant domains Minecraft may control (`lock` and `climate` are opt-in). The control blueprint gates them again in Home Assistant. |
| `twin.maxRequestsPerSecond` | 20 | Home Assistant requests per second for the whole server (1-1000). |
| `twin.catalog` | true | Read the entity catalog of the MineQTT catalog blueprint. |
| `twin.catalogMax` | 2000 | Home Assistant entities the twin keeps at most (1-10000). |
| `blockPlacer.enabled` | false | Lets Block Placers place and clear blocks. While it is off a placer listens on nothing and answers every action with "disabled". |
| `server.keepTickingWhenEmpty` | false | Dedicated servers only: never pause with nobody online, overriding `pause-when-empty-seconds`. See "Dedicated servers pause when empty" under [Home Assistant](#home-assistant). |

**Keys added in 0.8.0** (world telemetry, players, the Block Placer and Block Observer, rooms). They are read,
checked and saved on both loaders (on Fabric `telemetry.allowBroadcast` needs a server restart). An existing config
needs no change: a missing key takes its default and is written on the next save. On NeoForge they are the TOML tables `[telemetry]`, `[blockObserver]` and `[rooms]`
plus two keys in `[blockPlacer]` and one in `[limits]`; on Fabric they are the **World** category of the Mod Menu
screen. A value out of range falls back to its default with one log line, except the two player keys: an invalid
`telemetry.players` counts as `OFF` and an invalid `telemetry.publishPlayerNames` as `false` (the narrowest value, with
one log line) until it is fixed.

| Key | Default | Meaning |
|---|---|---|
| `telemetry.enabled` | true | Publish the world's time, weather, day and server load on the bridge device; off keeps only the player count and drop counters. |
| `telemetry.intervalSeconds` | 10 | At most this often (5-300 s) a telemetry change other than players, day or weather is published. |
| `telemetry.players` | `PRESENCE` | The most published about a player who consents: `OFF` (counts only), `PRESENCE` (name and a presence device) or `FULL` (also health, dimension and position). Nothing is published about a player without their consent; see [Privacy and presence](#privacy-and-presence). |
| `telemetry.publishPlayerNames` | true | `false` publishes counts only (no names, no presence devices), whatever players consent to. It only narrows: `true` names nobody who has not consented. |
| `telemetry.allowBroadcast` | false | Let Home Assistant show a line of plain text in every player's chat, prefixed `[MQTT]`; see [World telemetry](#world-telemetry). Fabric reads it at start only. |
| `telemetry.maxPresenceDevices` | 64 | Presence devices at most (1-1000); beyond it no new player gets one. |
| `telemetry.forgetAfterDays` | 90 | Days a player may stay away before their presence device and name are forgotten (0-3650, 0 = never); their consent is kept. |
| `blockPlacer.playersMayConfigure` | `AUTO` | Whether players who may otherwise configure a Block Placer may do so (its block, its raw link and its other settings); operators always may, and the owner may always set its access mode. `AUTO` = yes on integrated servers, no on dedicated servers. |
| `blockPlacer.consumeItems` | true | A placed block costs its item from the container behind the Block Placer; clearing returns it. |
| `blockObserver.exportBlockEntityData` | false | Include the observed block's block-entity data in the Block Observer's full raw payload, only when observer and target share a claim (or no claim mod is installed); never in Home Assistant. |
| `rooms.maxSize` | 128 | The longest side of a room, in blocks (8-512). |
| `limits.maxRoomsPerPlayer` | 16 | Rooms one player may own (0-512, 0 = unlimited); operators are not limited, and a world holds at most 512 rooms. |

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
says "if it only supports MQTT 3.1.1 set protocol: 3.1.1". MineQTT drops inbound messages larger than 256 KiB
(the Home Assistant catalog's lists; every other subscription takes at most 64 KiB). On MQTT 5 it tells the broker its
maximum packet size, so the broker never sends such a message; MQTT 3.1.1 cannot, so a
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
| `…/bridge/event` | Player events (join, leave; death and advancement of consenting players), never retained |
| `…/player/<slug>` | A consenting player's presence (`{"presence":"ON"}`, with `telemetry.players=FULL` and their consent also health, dimension and position), retained; see [Privacy and presence](#privacy-and-presence) |

Commands are never retained. A retained message on a device's `/set` topics is ignored, so a stale command left on the
broker cannot change a device when the server starts. Raw links are different, see below.

| Device (block) | State | Commands and notes |
|---|---|---|
| **Smart Lamp** (RGB LED block) | `state`, `brightness` 0-255, `color_mode` (`rgb`/`color_temp`), `color` {r,g,b}, `color_temp` and `color_temp_kelvin` (both in kelvin), `effect` (`off`, `pulse`, `candle`), `light_level`, `location` | Home Assistant JSON light commands. `color_temp` is read as kelvin from 1000 up and as mireds below, so v1 automations that send mireds keep working. `transition` (seconds, up to 3600) fades the block light and the colour, e.g. `{"state":"ON","brightness":200,"transition":2}`; the state shows the target at once, a lamp whose chunk is not loaded or is outside the simulation distance takes it without fading, a command without a transition ends a fade, and on a server paused while empty the fade waits until the server runs again. `effect` `pulse` breathes, `candle` flickers, `off` ends it; effects are drawn by each player's game, so the block light stays steady. Plain colour names and `#rrggbb` also work on the raw link. The lamp stands on the floor, sits on a wall or hangs from a ceiling, by the face you place it against; it needs something solid under the middle of its socket and pops off when that goes away. |
| **Redstone Output** (redstone emitter) | `state` ON/OFF, `power` 0-15, `mode`, `power_on_behaviour`, `last_level` | `{"power":7}`, `{"state":"ON"}` (restores the last non-zero level), `TOGGLE`. `mode`: `level` (analog, default) or `switch` (any level emits 15). `power_on_behaviour` (`previous`/`off`/`on`) applies when the server starts. |
| **Smart Plug** | `state` ON/OFF, `power` 0 or 15, `power_on_behaviour` | `{"state":"ON"}`, `OFF`, `TOGGLE`; any `power` above 0 switches it on. Home Assistant shows an outlet switch. `power_on_behaviour` (`previous`/`off`/`on`) applies when the server starts. A right-click switches it. Its raw link takes v1's plug payloads (`ON`/`OFF`, `TRUE`/`FALSE`, `1`/`0`, `TOGGLE`, or your custom on/off payloads; no levels) and, unlike v1, never repeats its own state there. |
| **Redstone Input** (redstone publisher) | `powered`, `signal` 0-15 | State is published at most once per second; short pulses are reported as `{"event_type":"pulse","signal":n}` on `…/action` at every rising edge. |
| **Presence Sensor** (motion sensor) | `occupancy`, `count`, `detect`, `range`, `clear_delay`, `filter` | `/set/detect` (`players`/`mobs`/`all`), `/set/range` (1-8 blocks), `/set/clear_delay` (seconds). The count updates at once; occupancy stays on for `clear_delay` after the last detection. Spectators are never counted. `filter` (the Controls tab's *Mobs* field) names the mobs that count, up to 8 ids such as `zombie, pig`; empty counts every mob. Sensors migrated from v1 count mobs, as v1 did, and the spawn eggs in their old filter slots became their filter. |
| **Wall Switch** (light remote) | `location` | Two buttons. A right-click presses the button it hits (left or right as you face the switch); its events on `…/action` are `{"event_type":"press","button":"left"}`, `double_press` (a second click within 8 ticks) and `hold` (the use key held for 10 ticks; fired once, nothing when you let go), each with the `button`. A single press is told 8 ticks after the click, once no second click came. `{"press_left":"PRESS"}` (or `/set/press_left` with `PRESS`, the Controls tab's buttons, `/mineqtt devices action <switch> press_right`) presses a button at once. Home Assistant shows an event entity and a device trigger per gesture and button ("left" pressed, double clicked, continuously pressed). Its raw link, if you give it one, publishes the event's name with the button (`press_left`, `double_press_right`, `hold_left`), never retained. Wire its `press` to a lamp, plug or output (Link mode or `/mineqtt wires`) to switch it without the broker; a wire carries both buttons' presses. |
| **Climate Sensor** (temperature sensor; humidity sensors convert into it) | `temperature` °C, `humidity` %, `illuminance` lx, `biome`, `interval`, `raw_value` | Takes a reading every `interval` seconds (`/set/interval`, 1-3600, default 10). The state is published when the temperature moved by 0.2 °C, the humidity by 1 % or anything else changed, at most once per second. The readings come from the spot: the biome's temperature and whether it rains or snows there, the height, day and night, rain, an open sky or a roof, heat (fire, lava, magma, campfires) and water or ice within 5 blocks, and daylight plus block light. Its raw link publishes the reading `raw_value` names (`temperature`, `humidity` or `illuminance`) at every reading, as v1 sensors did, with a decimal point whatever the server's language (`21.5`). |
| **Display Panel** | `message` (up to 255 characters, line breaks kept), `level` 0-15, `color`, `glow`, `size`, `location` | A wall plate that shows up to four lines of text: `{"message":"Washer done!"}`, `/set/message`, its Home Assistant text entity, a wire into `message` or its Raw Link (which follows the topic's retained value; blank text clears it). Settings `color` (the 16 dye colours, default `cyan`), `glow` (on by default; off draws in the block's light) and `size` (`auto`, `large`, `medium`, `small`). A comparator reads `level`: a number as a gauge (0..15, or over the mirrored entity's range or `twin.min`/`twin.max`), on/off words as 15/0, one of a mirrored entity's options as its index, any other text as 15, nothing, `unknown` or `unavailable` as 0. A point in time (a timestamp sensor's state) is worded by each player's game ("in 2 h 14 min", "07:30") and keeps moving. A right-click opens its screen. |
| **Timers**: Interval Timer, Daylight Timer (formerly Game Time Timer), Alarm Clock, Cron Timer | `enabled`, `next_fire`, and the schedule of the kind: `interval` (1-1 000 000) with `unit` (`ticks`, `seconds`, `minutes`, `hours`); `trigger_at` (`sunrise`, `noon`, `sunset`, `midnight`, `custom`) with `time` (HH:MM, for `custom`); the Alarm Clock's `time`; the Cron Timer's `cron` (up to 64 characters, empty for no schedule) | Each fire pulses the block's redstone output (1 tick for the Interval and Cron Timer, 30 ticks for the Daylight Timer and the Alarm Clock) and publishes `{"event_type":"fired","source":"schedule"}` on `…/action`, at most 4 a second (wires get every fire). `{"state":"OFF"}` or `/set/enabled` `false` disarms the schedule, and enabling never fires at once. `{"trigger":"PRESS"}` (Home Assistant's *Trigger now*, the Controls tab, `/mineqtt devices action <timer> trigger`) fires at once with `source` `manual`, also while disabled; during the pulse of the last fire it is refused with the reason. A new interval or unit restarts the count (as in v1, the tick that ends a pulse is not counted, so a 20-tick interval fires every 21 ticks). The Daylight Timer and the Alarm Clock fire once a game day at their time; a `/time set` back over it does not fire it again that day. The Cron Timer follows the server's clock in the server's time zone; its seven fields are tick (0-19), second, minute, hour, day of month, month and weekday, so `0 */5 * * * * *` fires every 5 seconds and `0 0 30 7 * * 1-5` at 07:30 on weekdays. A step takes every n-th value: `*/n` from the field's first value, `v/n` from `v` to the field's last, `a-b/n` within the range. Its *Schedule* tab edits the expression field by field, with presets and the next three fires. `next_fire` is an ISO-8601 time with the server's offset (`Z` on a UTC server): an estimate from the tick rate for the tick-driven kinds, empty under 60 s ahead, while disabled, while the game is frozen (`/tick freeze`; every timer but the Cron Timer), for the Daylight Timer and the Alarm Clock while the day clock stands (`gamerule advance_time false`, `/time pause`; a clock slowed by `/time rate` keeps running and their estimate follows its rate), or when no fire comes within 400 days; it comes back within a tick or two once the clock or the ticks run again. It changes only when it moves by more than 5 s and is worked out again on every fire and edit and every 60 s. The Controls tab's status line says what comes next ("Next 06:00, in 19 min"). The Raw Link publishes `ON` on each fire, never retained, at most 4 a second. Timers keep running while the broker is down; unloaded they do not tick, so they are unavailable then and refuse commands. |
| **Block Observer** (formerly Block Sensor) | `block` (id), `properties` (the block state's properties), `signal` (its comparator level 0-15), `change_on`, `payload`, `location` | Watches the block at its observer face (place it like a vanilla observer, in any of the six directions). Home Assistant shows the block as a sensor with its properties as attributes, the level, and a `changed` event (`{"event_type":"changed","block":"minecraft:oak_stairs"}`, at most 2 a second); the state is published at most once a second. `change_on` (`/set/change_on`): `state` (default, any property counts) or `block` (only another block). Its Raw Link sends what `payload` names on every change, never retained: `full` (default, see [Block Observer payloads](#block-observer-payloads)), `block_id`, `name` or `properties`. It never loads the chunk in front. |
| **Block Placer** (formerly Block Emitter) | `block` (a setting), `placed`, `last_result`, `location` | Places its `block` in front (`place`) and clears it again (`clear`): Home Assistant's select of placeable blocks with *Place* and *Clear* buttons, the Controls tab, `/mineqtt devices action <placer> place`, or its Raw Link, which takes `place`/`ON`/`true`, `clear`/`OFF`/`false`, or a block to set and place, as `minecraft:redstone_lamp[lit=true]` or `{"id":"minecraft:white_wool"}` (with `"properties"`). See [Block Placer rules](#block-placer-rules) for what it places where. At most 4 actions a second; a retained message never builds; `last_result` says what happened or why not. It needs `blockPlacer.enabled`; on a dedicated server only operators configure it unless `blockPlacer.playersMayConfigure`. |

Lamps, outputs, plugs and Display Panels stay `online` while their chunk is unloaded: a command is queued and applied when the chunk loads.
Sensors, Wall Switches, timers, Block Observers and Block Placers go `offline` when unloaded.

**Raw link.** The topic a device had in its v1 item slots (named paper) still works as a *raw link* next to the device
topics: outputs, plugs and lamps follow it, inputs and sensors publish to it with the v1 payloads (`ON`/`OFF`,
`TRUE`/`FALSE`, `0`-`15`, `TOGGLE`, or your custom on/off payloads) and the Wall Switch with its event names. Devices have no slots any more: the raw link is set on the
Advanced tab, with a renamed paper or with `/mineqtt devices raw`. Wireless redstone on one server keeps working
while the broker is down. Players may only use topics in `acl.rawAllow` (by default `mineqtt/shared/…` and their own
`<base>/<bridgeId>/raw/…`); topics that v1 blocks already used keep working for their owner. As in v1, a Redstone
Output and a Smart Plug apply the retained value on their raw link when they subscribe (a Block Placer never does).
Whoever may publish retained messages to those topics can therefore change the world when the server starts, so limit
write access to them in the broker ACL.

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
that range. Pointing at nothing, it opens the explorer on its *Devices* tab. Sneak + right-click into
the air steps its mode: *Configure*, *Link*, *Group*, *Copy-Paste*, *Bind* (the
[Twin tab](#bind-a-block)) and *Room* (see [Rooms](#rooms)). Blocks that are not devices open from
close by only.

The explorer's tabs are *Devices*, *Settings*, *Traffic* (formerly *Explorer*: listen and see the topics' messages) and
*Publish*. *Devices* lists your own devices (an operator who may build sees every device, a player who may not build
sees none; other players' devices are never listed, not even public ones), searchable by name, slug, model or type,
in pages of 50, grouped by [room](#rooms) (rooms by name, then devices in no room) and sorted by name inside each. The
room button next to the search filters by one room (*All rooms* shows every one). Operators also see the device
groups. Each row shows the device's name, model, whether it is online (or without broker, queueing while unloaded,
unavailable, faulted, of an unknown type), up to three of its values, and how far away it is in which direction; the
slug, the place and the fault show on hover. The page follows the devices every 2 s.

Click a device to select it: *Use* does what a right-click on it does (switches a lamp, from anywhere; a lamp in an
unloaded chunk queues it and switches when it loads, a sensor there is refused), *Locate* marks it with particles
only you see for 5 s when it is within 128 blocks, else says how far and in which direction, and *Open* opens its
screen within 16 blocks. A room's header has *All on* and *All off*: they switch your lamps, plugs and outputs in that
room (at most 64, once a second; timers stay as they are), as `/mineqtt rooms switch` does. No button ever reaches another
player's device. The first page starts with the *You* row: what the server shares about you (on hover) and the buttons
*Presence*, *All* (only where the server shares full data) and *Off*, the same choices as `/mineqtt privacy share`;
the choice you made shows pressed. For an operator its hover also lists which players share their presence. The
explorer no longer keeps anything on the item: whether you listen is kept by the
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

**Room mode** makes and edits [rooms](#rooms). Click one corner block, then the opposite one, up to the
Configurator's 16 blocks away. Within hand reach an ordinary click uses doors, chests, levers and other blocks as with
any item, also while a first corner waits; a block that does nothing with the click becomes the corner, and so does
a device. A block with a use of its own becomes a corner when you click it from beyond reach. A prompt shows the box's
size and asks for the room's name, with suggestions (the rooms you see but do not own, and for players who may bind
devices to Home Assistant up to 16 of its area names) and, for operators and holders of `mineqtt:rooms.admin`, a
toggle between *Your devices* and *Everyone's devices*. Sneak + right-click on a block of one of your rooms, within hand reach, offers *Rename* and *Remove*.
Clicking into the air cancels. While you hold the Configurator in Room mode, the box you are making (white) and up to
four rooms nearby (yours cyan, rooms for everyone green, others grey) are outlined with particles only you see.
Refusals (too large, too many rooms, no right to build at a corner, adventure mode) show in the action bar, and the
first corner stays picked.

**Bridge Console** (formerly the Terminal; same block and item). A right-click opens a screen of the bridge: its *Bridge*
tab shows whether MQTT is connected (and the protocol; the broker, credentials masked, on hover), the bridge's topics,
whether Home Assistant's birth or last will was heard, whether the server ticks or is paused for being empty, *Traffic*
(the messages the bridge dropped and the wires, with what they delivered and dropped on hover), *World* (TPS, the
game time and the weather; MSPT, the day and the telemetry line on hover; a warning below 15 TPS) and *Players* (how many
are online; an operator also reads the names of up to 16 players who share their name). The telemetry line, also in
`/mineqtt status`, reads like `Telemetry: every 10 s · players: presence by consent (2 sharing) · broadcast off`. Its *Devices* tab lists the
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

**Adventure mode.** A player who may not build (adventure mode, spectator mode, or a mod that takes the ability away)
only *uses* MineQTT blocks, an operator and a device's owner included, as vanilla lets no one break blocks in adventure
mode. A right-click still switches a lamp or a plug and presses the Wall Switch, as far as the device's access mode
lets them. A device's screen shows its *Controls* tab alone, without the device's topics, owner or diagnostics; its
settings there are read-only, while a timer's *Trigger now* still works. A device without controls (the Redstone Input), a name tag, a renamed paper, the Configurator's Link, Group and Copy-Paste modes and its
reach from afar are refused, and the action bar says why; within reach it opens the Controls tab as sneak +
right-click does, and that screen stays open only within the player's reach. The Bridge Console opens read-only, without its actions and without its device
and group lists. A screen open when the game mode changes is rebuilt for the new mode, or closed.

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

All need op level 2 (`/mineqtt publish` level 3), except `devices expose`, `privacy` and `rooms`: a player with the
`mineqtt:ha.expose` permission may use `devices expose` on devices they may configure, and `privacy` and `rooms` are
open to every player. `/mineqtt` itself is open to every player and each subcommand checks its own permission, so a
player without op sees and completes only `privacy` and `rooms` (and `devices expose` with `mineqtt:ha.expose`), and
`/mineqtt status` answers them "Incorrect argument for command" (0.7.0 said "Unknown or incomplete command"). Quotes
around a name are dropped: `rename lamp "Living Room"` names it `Living Room`.

`mineqtt:twin.bind` binds a device to a Home Assistant entity, `mineqtt:twin.control` turns its control on (the
[Home Assistant twin](#mirror-your-house)). Everyone has both in single player and LAN worlds (every player on an
integrated server); on dedicated servers ops (level 2) and holders of the permission.
**A world opened to LAN** (or shared through a tunnel mod such as e4mc) **gives every guest both**: a guest binds the
blocks they place to any entity `twin.allowedEntities` lets through (every one with `AUTO`), or to an MQTT device
preset, and switches what Home Assistant labels *MineQTT control*. Set `twin.allowedEntities`, and label only what
guests may switch, before you share a world. A binding is also judged for the device's owner (an
ownerless device cannot be bound until it is claimed), again at every load and config reload.

`mineqtt:rooms.admin` (ops of level 2 and holders of the permission) makes [rooms](#rooms) that apply to every
device inside them, not only to the maker's own.

| Command | Purpose |
|---|---|
| `/mineqtt status` | Connection, broker, protocol, device counts, wires and their messages, Home Assistant mode, dropped messages, and the telemetry line (interval, what is shared about players, broadcast) |
| `/mineqtt privacy`, `/mineqtt privacy share on \| full \| off` | Every player: what is published about you, and your choice (presence, everything the server shares, nothing); see [Privacy and presence](#privacy-and-presence) |
| `/mineqtt players list \| forget <player>` | The players with a stored privacy choice and their presence devices; `forget` removes a player's name and presence device and keeps their choice (a stored name in any case, a UUID or an online player) |
| `/mineqtt rooms list \| info <room> \| create <name> <from> <to> [for-all] \| rename <room> <name> \| resize <room> <from> <to> \| remove <room>` | Every player: [rooms](#rooms). `<room>` is a name (your own room first) or `"#<8 hex>"` from `list` when several rooms share it; a name with spaces needs quotes before the corners, e.g. `create "Living Room" 0 64 0 5 67 5`. `for-all` (operators and `mineqtt:rooms.admin`) makes a room for every device inside; from the console or RCON every room is one for everyone |
| `/mineqtt rooms switch <room> on \| off` | Every player: switch your lamps, plugs and outputs in the room, at most 64, once a second (not in adventure mode; timers stay armed or disarmed); from the console every such device in the room. The reply counts them: "Kitchen: 2 switched on, 1 already on" |
| `/mineqtt reconnect` | Rebuild the connection from the current config |
| `/mineqtt devices list \| info <device> \| rename <device> <name> \| remove <device> \| prune` | Manage devices |
| `/mineqtt devices set <device> <json>` | Command a device like its `…/set` topic, e.g. `set kitchen_lamp {"state":"ON"}`; an unloaded lamp or output queues it |
| `/mineqtt devices setting <device> <key> <value>` | Change a setting, as the device screen does: the type's settings (`mode`, `range`, …), its other writable fields as the Controls tab sets them (`power 7`, `light {"state":"ON","brightness":128}`) and `name`, `expose`, `access`, `raw.channel`, `raw.private`, `raw.topic`, `raw.retain`, `raw.qos`, `raw.on`, `raw.off`, and the Home Assistant twin's keys: on a Smart Lamp and a Smart Plug `twin.entity`, `twin.device "<preset> <name>"`, `twin.preset`, `twin.unavailable hold\|off`, `twin.control true\|false`, `twin.resume press`; on a Redstone Output `twin.entity`, `twin.device`, `twin.preset`, `twin.path`, `twin.invert`, `twin.min`, `twin.max`, `twin.unavailable`, `twin.control`, `twin.resume`; on a Display Panel `twin.entity`, `twin.device`, `twin.preset`, `twin.path`, `twin.min`, `twin.max`, `twin.decimals`, `twin.unavailable`; on a Redstone Input `twin.target`, `twin.resume`; on a Wall Switch `twin.left`, `twin.right`, `twin.resume`; out-of-range values are refused |
| `/mineqtt devices bind <device> <entity> \| none \| resume`, `/mineqtt devices bind <device> target <target> <entity> \| none`, `/mineqtt devices bind <device> preset <preset> <name>` | Mirror a Home Assistant entity on a device (Smart Lamp, Redstone Output, Smart Plug, Display Panel), unbind it, or end the pause of its control after too many requests; `target` sets or removes what a Redstone Input (`target`) or a Wall Switch button (`left`, `right`) switches; `preset` mirrors an MQTT device through a preset instead. The entity must be in `twin.allowedEntities` and, while the catalog blueprint runs, labelled in Home Assistant; a preset's topics must pass the ACL |
| `/mineqtt devices raw <device> channel <name> \| private <name> \| topic <topic> \| none \| retain on\|off \| qos 0\|1 \| on <payload> \| off <payload>` | Edit the Raw Link: a channel resolves like a v1 item or paper topic (`mineqtt/shared/<name>` unless allowed as written), a private channel into the owner's own subtree; a topic is used as typed when the ACL allows it for you and for the device's owner; `""` clears a payload |
| `/mineqtt devices action <device> <action> [value]` | Perform an action the device declares, e.g. `action garage_door state TOGGLE`, `action porch_timer trigger`, `action wool_placer place` |
| `/mineqtt devices expose <device> on \| off \| default` | Show a device to Home Assistant, hide it, or follow `ha.expose` again |
| `/mineqtt devices access <device> private \| claim \| public` | Set who besides the owner and operators may use and configure a loaded device |
| `/mineqtt devices claim <device>` | Become the owner of a loaded device that has none (run as a player) |
| `/mineqtt devices retry <device> \| <x y z>` | Attach a faulted device again (one whose setup failed; `status` and `devices info` show it) |
| `/mineqtt devices eject <device>` | Drop the items a loaded device's old configuration slots held where it stands (`devices info` lists how many) |
| `/mineqtt group create <name> \| add <group> <device> \| remove <group> [<device>] \| rename <group> <name> \| expose-members <group> on\|off \| list` | Manage groups. `rename` moves the group's topics to the new slug; `expose-members on` lets Home Assistant discover the members beside the group (each as its own exposure allows) |
| `/mineqtt wires list [<device> \| group.<group>] \| add <from> <to> [follow\|invert\|pulse_toggle] \| remove <id>` | Wire a device's port to another device's or a group's port, e.g. `add garage_input.powered group.porch_lamps.state`. A wire works on the server alone, without the broker, and an unloaded lamp or output gets its value when its chunk loads. Without a mapping it follows (an event toggles). A following or inverted wire sets its target to the source's current value when it is made. A player's wires count against `limits.maxWiresPerPlayer`, the console's do not. `list` shows the ids `remove` takes, or enough of their start |
| `/mineqtt twin status \| entities [query] \| show <entity> \| ping \| refresh` | The twin: what it reads, the catalog and how its bound devices are doing (requests, answers, pauses); the entities you may bind (up to 40, searched by name, id or area); one entity as the twin knows it and the devices mirroring it; ask the control blueprint to answer (`pong`, or no answer within 5 s); ask Home Assistant for a fresh entity catalog (`bridge/ha_sync`) |
| `/mineqtt discovery republish \| clear [--bridge <oldId>]` | Republish or clear Home Assistant discovery |
| `/mineqtt publish "<topic>" <payload>` | Publish a message (checked against the ACL) |

## Home Assistant

**Requirements: Home Assistant 2025.2 or newer** with the MQTT integration connected to the same broker. MineQTT uses
device-based discovery and the colour temperature options in kelvin that 2025.2 introduced. Home Assistant 2025.1 still
creates the entities but reads the lamp's colour temperature as mireds, so colour temperatures show nonsense values.
Tested with 2025.2.5 and 2026.10.0.

What appears in Home Assistant:

- A **MineQTT Bridge** device per world (named after the world) with a connectivity sensor, version, device count,
  players online, the world's TPS, time of day, day, whether it is day and the weather, a *Player event* entity, a
  Republish button and (disabled by default) MSPT and the dropped-message counters; with `telemetry.allowBroadcast`
  also a *Broadcast* text entity; see [World telemetry](#world-telemetry).
- One device per exposed MineQTT block, linked to the bridge. The main entity is named after the device: a lamp named
  "Kitchen Lamp" becomes `light.kitchen_lamp` (Home Assistant 2026.10 puts the area first when it creates a device in
  one: the lamp "Smart Lamp 724a" in a room *Living Room* became `light.living_room_smart_lamp_724a`; 2025.2 does not). Renaming the device keeps the
  entity and its history. A device inside a [room](#rooms) suggests the room as its area.
- A *Player <name>* device per player who shares their presence, under the bridge; see
  [Privacy and presence](#privacy-and-presence).
- All device entities become unavailable when the server stops or crashes; the bridge's connectivity sensor turns off.
- When Home Assistant restarts, MineQTT republishes everything within a few seconds; no server restart is needed.
- A block that mirrors a Home Assistant entity keeps only a disabled diagnostic *Binding* entity while it is bound
  (see [What Home Assistant sees](#what-each-block-mirrors)).
- Devices that are gone do not linger: for five seconds after every connect MineQTT reads the retained device configs
  under the discovery prefix and clears each one that names this world's bridge but no device or group the world knows,
  such as a device whose placement a crash undid, and each presence device that no player of the world has any more.
  Home Assistant then removes it. Configs of other worlds' bridges and of
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
discovered only while one of its devices is exposed, or when it was made from a v1 topic. Hiding a device clears its
discovery once; Home Assistant then logs `No device components to cleanup for mineqtt_…` once per entity it removed
(two for a lamp: the light and its location), for clears it publishes itself. The warning is harmless.

**Dedicated servers pause when empty.** Since Minecraft 1.21.2 a dedicated server stops ticking after
`pause-when-empty-seconds` (default 60) without players. MineQTT keeps handling MQTT between the paused ticks: Home
Assistant commands and wireless redstone still switch lamps, emitters and other devices, states and discovery are still
published, and devices placed or loaded by commands attach. `/mineqtt status` shows `Server: paused (empty): MQTT handled
between ticks` and the `pause-when-empty-seconds` value. What the pause still holds back: block changes and their
immediate neighbour updates happen at once, but scheduled ticks (repeaters, comparators, observers, pistons), light
updates, block entity tickers (sensors, slot edits), timers and the bridge's telemetry wait until a player joins. Only when such contraptions
must keep running with nobody online, set `server.keepTickingWhenEmpty` to true in the MineQTT config (or
`pause-when-empty-seconds=0` in `server.properties`). The opt-in keeps `server.properties` as it is, logs at startup that
it overrides `pause-when-empty-seconds`, and `/mineqtt status` then shows `Server: ticking, never pauses
(server.keepTickingWhenEmpty overrides pause-when-empty-seconds=60)`. It costs a full tick, 20 times a second, while
nobody plays.

### World telemetry

The bridge's telemetry on `…/bridge` (retained) holds `players_online`, `dropped_inbound` and `dropped_outbound` and,
while `telemetry.enabled` is on, `players` (the names of the players online who share their presence, sorted; see
[Privacy and presence](#privacy-and-presence)), `tps`, `mspt`, `time_of_day` (the game time in 15-minute steps, `19:00`), `day`,
`is_day` (06:00 to 18:00 game time) and `weather` (`clear`, `rain` or `thunder`). Home Assistant shows them as *Players
online* (with `players` as an attribute), *TPS* and *MSPT* (diagnostic, MSPT disabled by default), *Time of day*,
*Day*, *Is day* and *Weather*. MineQTT samples the world every second: a change of the players, of day and night or of
the weather goes out within about 2 s, anything else at most every `telemetry.intervalSeconds`, TPS only when it moved
by 0.5 and MSPT by 1 ms. TPS and MSPT are vanilla's rolling average; TPS reads 0 while the game is frozen. With
`telemetry.enabled` off the six world entities read unknown. A dedicated server paused while empty samples nothing
(TPS keeps its last value) until it ticks again.

*Player event* (`…/bridge/event`, never retained) fires `{"event_type":"join"}` and `{"event_type":"leave"}` for every
player, with `player` (their name) only for a player who shares their presence. Deaths (`{"event_type":"death",
"player":"Steve","cause":"death.attack.fall"}`, the cause's translation key) and announced advancements are published
only for players who share their presence. At most
one join and one leave per player every 5 s and 10 events a second for the bridge are published; the excess counts in
`dropped_outbound`.

**Broadcast text** (off by default). With `telemetry.allowBroadcast` on, Home Assistant's *Broadcast* entity on the
bridge, or any client that may publish to `<base>/<bridge>/bridge/request/broadcast`, shows a line of plain text in
every player's chat behind `[MQTT]`. The text is never a command or a JSON component: at most 1 KiB of strict UTF-8,
formatting codes (`§`), control and bidi characters removed, whitespace collapsed, cut to 256 characters. Three are
shown at once, then one a second; a retained request is ignored, and each shown text is logged with its topic. The
answer goes to `bridge/response/broadcast`: `{"status":"ok"}` or `{"status":"error","error":…}` (`broadcast disabled`,
`rate limited`, `message over 1024 bytes`, `message is not UTF-8`, `empty message`). The Home Assistant entity (a text
of at most 255 characters, Home Assistant's own limit; configuration category, no state) exists only while the key is
on; turning it off removes it, also when Home Assistant was offline then. Restrict `<base>/+/bridge/request/#` in the
broker ACL to the clients that should send it.

**Example dashboard.** [`docs/dashboards/mineqtt-world.yaml`](docs/dashboards/mineqtt-world.yaml) is a Home Assistant
dashboard of the world: whether the server is online, TPS, players and their names, time of day, day and night,
weather, the load over the last hours and the player events. It uses only entities MineQTT creates. Create a dashboard
(Settings → Dashboards → Add dashboard → New dashboard from scratch), open its raw configuration editor and paste the
file. Its entity ids are those of a world named `world` (the bridge device is named after the world, so a world
*Survival* has `sensor.survival_tps`): replace `world_` with your world's name first. *MSPT* is disabled by default;
enable it in Home Assistant before you add it.

### Privacy and presence

Nothing that names a player is published until that player chooses. The one exception is the host of a single player
or LAN world, who shares their presence until they say otherwise (they get a note in chat); LAN guests and every
player on a dedicated server share nothing until they choose. The server's config can only narrow what players
choose: `telemetry.players` caps it (`OFF` shares no player at all, `PRESENCE` presence only, `FULL` also health,
dimension and position), and `telemetry.publishPlayerNames=false` shares counts only.

- **The prompt.** When the server shares players, MQTT is set up and `ha.expose` is not `NONE`, a player who never chose is asked once, at their
  first join, with clickable *[Share presence]* and *[Don't share]* (and *[Share all]* when the server is in `FULL`).
  The buttons run the command below.
- **`/mineqtt privacy`** tells you what is published about you and offers the choices again; `/mineqtt privacy share
  on` shares your presence, `share full` everything the server shares (on a `PRESENCE` server it says it shares presence
  only, and a wider mode later honours it), `share off` nothing. Operators list the stored choices with `/mineqtt players
  list` and remove a player's name and presence device with `/mineqtt players forget <player>`; the choice stays.
- **What a choice changes.** A sharing player's name appears in the bridge's `players` attribute and on their joins and
  leaves on *Player event*, and their deaths and advancements are published.
- **Presence devices.** Each sharing player is a Home Assistant device *Player <name>* (model *MineQTT Player*) under
  the bridge, with a presence sensor on `…/player/<slug>` (`{"presence":"ON"}` while online, `OFF` after they quit or the
  server stops, and `OFF` at the next connect after a crash). The slug (`player_steve`) stays when the player renames;
  the device's name follows. Its id is salted per world (`mineqtt_player_<32 hex>`) and never shows the Mojang UUID. It
  is discovered under `OPT_IN` too, since the player's consent is the opt-in; `ha.expose=NONE` removes the discovery and
  keeps the state topic. It goes away at `share off`, at `players forget`, after `telemetry.forgetAfterDays` days away
  (UTC days, checked at start and every 20 minutes, never for a player online) and for every player when the server
  stops sharing players (`telemetry.players=OFF` or `publishPlayerNames=false`; the choices are kept). Beyond
  `telemetry.maxPresenceDevices` no new player gets a device (none is taken from another player) and the operators
  online are told once.
- **Full sharing.** With `telemetry.players=FULL` and `share full`, the presence device also has *Health* (rounded to
  0.5 points, a quarter of a heart), *Dimension* and *Position* (`x, y, z`, with `x`, `y`, `z` as attributes). They are sampled every second
  and published at once on a dimension change, else at most every 5 s and only after 4 blocks of movement or a change of
  half a heart; at most 20 such updates a second for the whole server. Nothing of it is kept after the player quits. `share
  on`, or a server narrowed to `PRESENCE`, removes the three sensors and keeps the device.
- **Explorer.** A player's own `player/` topic shows in the Configurator's *Traffic* tab to that player and to admins
  (op level 3 or `mineqtt:admin`), never to other players.

### Rooms

A room is a named box of blocks in one dimension. Every device inside it suggests the room's name to Home Assistant as
its area (`suggested_area`). A room applies to its owner's devices; a room for everyone (`for-all`, made by operators
and holders of `mineqtt:rooms.admin`, and every room made from the console or RCON) applies to every device inside.
When rooms overlap, the smallest room that applies wins (a pantry inside the kitchen), the older one on a tie. Rooms
are made with the Configurator's [Room mode](#devices-and-topics) or `/mineqtt rooms`, by any player who may build at
both corners. A room's sides are at most `rooms.maxSize` blocks (both corners included), a player owns at most
`limits.maxRoomsPerPlayer` rooms (operators are not limited) and a world holds at most 512. Names are 1 to 32
characters and unique per owner, ignoring case. A device's General tab shows its room. Only a room's owner or an
operator renames, resizes or removes it, never in adventure or spectator mode; renaming or resizing a room for everyone
needs the right that making it needed, also for its owner.

Home Assistant creates an area that does not exist yet, and reuses one whose name differs only in case (`kitchen`
lands in *Kitchen*). With the [catalog blueprint](#set-up-home-assistant-about-five-minutes) running, a room named like
one of Home Assistant's areas, case and spaces ignored (`living room` or `livingroom` for *Living Room*), suggests the
area in Home Assistant's own spelling, from the device's next refresh on. The room prompt suggests up to 16 area names
to players who may bind devices to Home Assistant.

Home Assistant applies a suggested area when it creates a device. A device that Home Assistant already knew keeps its
area when a room comes, moves, is renamed or goes: MineQTT republishes it with the new suggestion, but you change the
area in Home Assistant. The two tested versions differ:

| Case | 2026.10 | 2025.2 |
|---|---|---|
| A new device | gets the room's area (created if missing) | the same |
| A known device without an area, now in a room | keeps no area | moves into the room's area |
| A device with an area | keeps it | keeps it |
| A device hidden and exposed again | gets its old area back | gets the current room's area |

The General tab shows the room with a `*` when the area Home Assistant was first told differs from the room (case
and spaces ignored), and says on hover which area that was, or that the device was first discovered without an area;
it never claims what Home Assistant did, so check the area there. A group suggests a room when all of its devices are
in that room. Copy-Paste copies no room.

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
message there sets a Redstone Output or a Smart Plug when the server starts (see [Raw link](#devices-and-topics)).

Keep `topic read ha_statestream/#` read-only for MineQTT's user. Inside the game, nobody (operators and the console
included) can publish into the statestream namespace (`twin.statestreamBase`) nor into any MineQTT server's `bridge/`
level (`<baseTopic>/+/bridge/#` and `mineqtt/+/bridge/#`, e.g. `mineqtt/shared/bridge/…`), even when `acl.rawAllow`
covers it. The Block Placer (formerly the Block Emitter) listens through its raw link under the same rules as every
device's and never acts on a retained message.

With the [Home Assistant twin](#mirror-your-house) on a broker that other clients share, keep the twin's topics to
the two parties that own them: Home Assistant runs a request on `mineqtt/+/bridge/ha_request` as if Minecraft had sent
it, and Minecraft trusts what arrives under `ha_statestream/#`. Only MineQTT's users may publish
`mineqtt/+/bridge/ha_request` and `mineqtt/+/bridge/ha_sync`; only Home Assistant's user may publish
`mineqtt/+/bridge/ha_response`, statestream (`ha_statestream/#`) and the catalog blueprint's
`ha_statestream/_mineqtt/#`. Mosquitto's grants only add up, so give no other user a line that covers those topics
(`#`, `mineqtt/#`, `ha_statestream/#`). With several MineQTT servers on one broker, give each its own user limited to
its own bridge, so one server cannot send requests in another's name (set `bridgeId` in each server's config so the
topic stays fixed), plus the raw-link lines above. For a server with `"bridgeId": "survival"`:

```
user mineqtt_survival
topic readwrite mineqtt/survival/#
topic readwrite mineqtt/shared/#
topic readwrite homeassistant/#
topic read ha_statestream/#
```

## Mirror your house

Milestone 3 (0.7.0) brings real Home Assistant entities into Minecraft and lets Minecraft switch them back: a Smart
Lamp lights up with the real kitchen light, a Redstone Output opens a piston door with the real front door, a Display
Panel shows the outdoor temperature, and a pressure plate in the game switches the real hallway light. Home Assistant
decides what Minecraft may see (a label and the statestream include) and what it may switch (a second label and a
blueprint); the server decides who may bind blocks; loop protection makes sure a mirrored block never echoes Home
Assistant's own changes back.

### Set up Home Assistant (about five minutes)

You need Home Assistant 2025.2 or newer with its MQTT integration on the same broker as MineQTT.

1. **Publish the entities' states with `mqtt_statestream`.** Add this to `configuration.yaml` and restart Home
   Assistant (statestream is configured in YAML only, and starts only after a restart that follows the MQTT
   integration's setup):

   ```yaml
   mqtt_statestream:
     base_topic: ha_statestream     # MineQTT's twin.statestreamBase; never "homeassistant", never under mineqtt/
     publish_attributes: true       # colour, brightness, unit, min/max and options are attributes
     publish_timestamps: true       # last_updated tells a fresh value from an old retained one
     include:
       entities:
         - light.kitchen_ceiling
         - binary_sensor.front_door
         - sensor.outdoor_temperature
         - scene.movie_night
   ```

   Keep the `include:` list tight: statestream publishes about 8 retained topics per entity (26 for a climate
   entity) and keeps them on the broker for good. Including whole domains (`include: domains: [light, switch]`) is
   fine for a small installation. Whatever you include, MineQTT never reads `person`, `device_tracker`, `zone`,
   `camera` or `alarm_control_panel` entities (they carry locations, occupancy and alarm codes).
2. **Create two labels** (Settings → Areas, labels & zones → Labels): *MineQTT read* and *MineQTT control*. Home
   Assistant gives them the ids `mineqtt_read` and `mineqtt_control`, which the blueprints expect (with other names,
   set the blueprints' label inputs).
3. **Import both blueprints and create an automation from each.** The defaults fit the setup above.

   [![Import the MineQTT catalog blueprint.](https://my.home-assistant.io/badges/blueprint_import.svg)](https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=https%3A%2F%2Fgithub.com%2FOddHero%2FMineqtt%2Fblob%2Fmain%2Fdocs%2Fblueprints%2Fautomation%2Fmineqtt%2Fmineqtt_catalog.yaml)
   **MineQTT catalog** ([`mineqtt_catalog.yaml`](docs/blueprints/automation/mineqtt/mineqtt_catalog.yaml)) publishes
   the labelled entities, retained, one list per domain with a fresh snapshot of each, under
   `ha_statestream/_mineqtt/`: at Home Assistant's start, when labels, entities or devices change, every 10 minutes and
   whenever a MineQTT server asks (5 s after it connects, after each Home Assistant start, and on `/mineqtt twin
   refresh`).

   [![Import the MineQTT control blueprint.](https://my.home-assistant.io/badges/blueprint_import.svg)](https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=https%3A%2F%2Fgithub.com%2FOddHero%2FMineqtt%2Fblob%2Fmain%2Fdocs%2Fblueprints%2Fautomation%2Fmineqtt%2Fmineqtt_control.yaml)
   **MineQTT control** ([`mineqtt_control.yaml`](docs/blueprints/automation/mineqtt/mineqtt_control.yaml)) carries out
   Minecraft's requests: only for entities, or entities of devices, labelled *MineQTT control*; only the domains you
   allow (locks and climate devices are off unless you add them), only fixed actions and data keys (never a lock's
   `open`, a code, `flash` or a script's variables), one entity per request. It answers every request on
   `…/ha_response`, before it calls the action, so even a failing call is answered, and writes accepted requests to the
   logbook: "Minecraft: turn on by Steve via Porch Lamp" ("by a redstone signal" when no player caused it; the
   *Log accepted requests* input turns it off). Its *Request topic* input serves every MineQTT server on the broker by
   default; enter `mineqtt/<bridgeId>/bridge/ha_request` to serve one.

   The badges open your own Home Assistant's import dialog (through my.home-assistant.io) and need the files on the
   repository's `main` branch, which they are from the 0.7.0 release on; you can also paste the file links into
   Settings → Automations & scenes → Blueprints → Import blueprint.
4. **Label what Minecraft may see and switch:** *MineQTT read* on entities (or whole devices) the game may show,
   *MineQTT control* on those it may also switch (control implies read). A label change reaches the game within about
   3 s, and removing a label takes the entity out of Minecraft's reach at once. A labelled entity also needs to be in
   the statestream `include:` to change live in the game; this template (Developer Tools → Template) prints the
   labelled entities, those of labelled devices included, as a list to paste after `entities:`:

   ```jinja
   {%- set ns = namespace(ids=[]) -%}
   {%- for label in ['mineqtt_read', 'mineqtt_control'] -%}
     {%- set ns.ids = ns.ids + (label_entities(label) | list) -%}
     {%- for dev in label_devices(label) -%}{%- set ns.ids = ns.ids + (device_entities(dev) | list) -%}{%- endfor -%}
   {%- endfor -%}
   {{ ns.ids | unique | sort | list }}
   ```

   (Without devices, `{{ label_entities('mineqtt_read') | list }}` is enough.)
5. **Check it from the game.** On a dedicated server the twin starts off: set `twin.allowedEntities` (for example
   `["light.*", "binary_sensor.front_door", "sensor.outdoor_*"]`, or `["*"]` when every labelled entity may be bound)
   and restart the server (NeoForge also reloads `mineqtt.toml` while it runs). `/mineqtt twin status` then says how many entities the catalog shares and when it was
   generated, `/mineqtt twin entities` lists what you may bind, and `/mineqtt twin ping` answers "pong" when the control
   blueprint runs.

Without the catalog blueprint MineQTT lists what statestream publishes (after 10 s), every entity
`twin.allowedEntities` lets through can be bound, and a value left stale on the broker by a Home Assistant restart
stays until the entity changes. With it, only labelled entities can be bound, and a bound entity whose label goes away
shows *Not shared*.

### Bind a block

Who may: on a dedicated server, an operator or a holder of `mineqtt:twin.bind` (binding) and `mineqtt:twin.control`
(turning control on), who may also configure the block; in single player and LAN worlds everyone, every guest of a
world opened to LAN included (see [Commands](#commands)). The entity must match
`twin.allowedEntities`, which no one bypasses, and, while the catalog runs, be labelled in Home Assistant. A binding is
also judged for the block's owner (an ownerless block cannot be bound until someone claims it), again whenever the
block loads and the config is reloaded: a binding the rules no longer allow is *Refused* and works again once they do.

**The Twin tab.** Every block that can follow or switch Home Assistant has a *Home Assistant* tab after Controls. A
mirroring block (Smart Lamp, Redstone Output, Smart Plug, Display Panel) shows its source (*This block*, *Home
Assistant* or *MQTT device*) with the bound entity, its name, id and area (or the [MQTT device](#mqtt-devices-without-home-assistant),
its preset and state topic), then its settings: control, invert, path, decimals, range and what happens while the
source is unavailable, as the type has them. On an unbound block the source button steps *This block* → *Home
Assistant* (which opens the entity picker) → *MQTT device* → *This block*; on a bound block one press unbinds it. A
Redstone Input shows its target, a Wall Switch its Left and Right targets, each with a × that removes it. The last line
tells how the binding is doing (see [Troubleshooting](#troubleshooting-by-the-health-line)), with *Resume* while its
control is paused, and counts the requests the [rates](#control-from-minecraft) refused since the block loaded or was
resumed ("2 refused by the rate"; hovering it names the last one's entity for players who may configure the block).
Every change on the tab is the command twin (`/mineqtt devices setting <device> twin.…`) and needs the same rights.

**The entity picker.** Clicking the entity (or *Pick…*) opens it: type part of a name, id or area and the list (at
most 40, grouped by Home Assistant area) narrows as you type; one button steps through the domains the block can
take; rows show a colour swatch, the value and *control* and *stale* badges; an entity id typed in full that Home
Assistant has not shared yet shows as "Use … (not seen yet)". Enter binds the selected row; *Rename* (on while the
block has its default name) names the block after the entity once the bind went through. Before the first bind of a block
Home Assistant knows, it warns that Home Assistant loses the block's own entities; on a block bound to an MQTT device it
warns "Binding replaces its MQTT device <name> (<preset>)." instead. The list always shows the answer to the query and
domain asked last. For a target the picker lists only entities Minecraft may
control: their domain allowed by `twin.haActionAllowlist` and, while the catalog runs, labelled *MineQTT control*.

**Configurator Bind mode.** Step the Configurator to *Bind* (sneak + right-click into the air; the cycle is
Configure, Link, Group, Copy-Paste, Bind) and click a block, also from up to 16 blocks away: its Twin tab opens with
the picker; on a Wall Switch it asks Left or Right first.

**By command:** `/mineqtt devices bind <device> <entity>` (or `none` to unbind), `devices bind <input> target target
<entity>`, `devices bind <switch> target left|right <entity>`, `devices bind <device> preset <preset> <name>`, and
`devices setting <device> twin.<key> <value>` for every row of the tab (the keys per type are in
[Commands](#commands)). Copy-Paste mode copies a binding too, each key checked for the player who pastes it as if
they set it on the tab; a placed copy of a bound block is *Refused* unless its placer may bind it, and loses control
unless they may turn it on.

### What each block mirrors

- **Smart Lamp**: a light's state, brightness and colour (in one step, never a flash of the old colour; a light on at
  brightness 0 shows at 1), or the on/off of a `switch`, `input_boolean`, `fan` or `binary_sensor` in white at full
  brightness. Its effect stays a setting of the lamp.
- **Redstone Output**: on/off entities (switch, input_boolean, binary_sensor, light, fan, cover, lock: unlocked is on)
  give 15 and 0, `twin.invert true` swaps them, and states in between (opening, unlocking) hold. Numbers map onto
  0..15 over `twin.min`/`twin.max`, else the entity's own range (an `input_number` at 42.5 of 0..100 gives 6), else
  0..15; `twin.path attributes.brightness` reads an attribute (a light's brightness over 0..255, a fan's percentage
  over 0..100; an off light reads 0); option lists (`input_select`, `select`) give the option's index; a climate gives
  its current temperature. Whether it acts as a switch or a gauge follows from the entity and the path, not from its
  `mode`.
- **Smart Plug**: a `switch`, `input_boolean`, `light` or `fan`.
- **Display Panel**: any readable entity, read-only: its value with its unit (`20.0 °C`, `42.5%`) under the panel's
  name, "Waiting for HA…" until a value arrives, a grey "Unavailable". `twin.path` picks an attribute (a value path:
  `state`, `attributes.brightness`, `attributes.forecast[0].temperature`, `attributes["unit.of"]`), `twin.decimals`
  (`auto`, 0-3) rounds numbers, `twin.min`/`twin.max` set the comparator's range. Edits by players, commands and Home
  Assistant are refused while it is bound; change the entity in Home Assistant.

Every mirroring block reads only while its chunk is loaded and catches up from the retained statestream topics when
it loads. While the entity is unavailable or unknown it holds its last value, tinted grey with a puff of smoke now and
then (`twin.unavailable off` turns it off instead), so a door piston does not move because a Zigbee stick rebooted. A
mirrored block keeps its state over a server restart: its power-on behaviour applies only while it is unbound. While
it is bound its Raw Link input is paused, it joins no group, and Home Assistant `/set` commands to it are refused.

**What Home Assistant sees.** A bound block's own Home Assistant entities are replaced by one disabled diagnostic
entity, *Binding*, which names the entity it follows and how it is doing; automations and dashboards that used the
block's entities stop working. This also happens when Home Assistant is offline during the bind (at its next start;
`devices info` says so meanwhile). Unbinding brings the entities back with the same unique_ids: Home Assistant 2026.x
restores their names, areas and labels, older versions (2025.x) create new registry entries and keep the disabled
*Binding* entity, which you delete by hand (Settings → Entities, show disabled). The Twin tab warns before a first
bind.

### Control from Minecraft

Turn control on (the Twin tab's switch, or `/mineqtt devices setting <device> twin.control true`; needs
`mineqtt:twin.control`, and the owner's permission counts at the time of use) and using a mirrored block (a
right-click, its Controls tab, a command or a wire) asks Home Assistant to change the entity instead of changing the
block. The block changes when Home Assistant reports the change; a faint sparkle shows a request that waits, for at
most 5 s ("no change from Home Assistant"). A right-click sends the opposite of a live value (`turn_off` when the light
is on, nothing when a wire asks for what the entity already shows), a `toggle` when the value is only retained, and
nothing when the entity is unavailable (the action bar says why). A Redstone Output sets numbers back through the
entity's range and step (`set_value`, a fan's percentage, a cover's position, a light's brightness) and option lists
by index; sensors, binary sensors, counters and a climate's temperature are read-only, and so is the Display Panel.
Anyone who may use the block switches the real entity (the Twin tab names the block's access mode). Requests go to
`mineqtt/<bridgeId>/bridge/ha_request` and are never sent late: while the broker is down a click is refused ("not sent:
the bridge is offline"). Home Assistant's answers only end the wait and explain refusals; the block's state only ever
comes from statestream.

**Rates.** At most 4 requests a second per block and per player, and `twin.maxRequestsPerSecond` (20) per server. Over
a rate, a state the world asked for (a redstone edge's on or off, a wire's value) waits and the newest is sent as soon
as the rate allows, so Home Assistant ends as the world left it; a brightness or colour dragged on the Controls tab is
held, and the newest one is sent at the rate; a player's switch, a toggle and a press are refused instead, counted per block
(`devices info`, the status line, the Twin tab's health line) and logged once per burst. A block that the world (a lever, a wire, a redstone loop)
makes send, or try to send, more than 8 requests within 10 s has its control paused (*Control paused*, red particles,
a warning in the log and a notice to its owner) until someone who may configure it presses *Resume*, runs
`/mineqtt devices bind <device> resume` or `devices setting <device> twin.resume press`. A player's own clicks never
pause a block: the rates cap them.

### Minecraft as a Home Assistant trigger

A Redstone Input switches an entity on its edges (`twin.target`): a rising edge turns it on, opens a cover, activates a
scene or script or presses a button; a falling edge turns it off or closes the cover (nothing for a scene, script or
button). Each Wall Switch button switches one (`twin.left`, `twin.right`): a press activates a scene or script,
presses a button, or toggles a light, switch, input_boolean or fan; double presses and holds are not bound yet. Bind
them on the Twin tab, with Bind mode or by command. The control blueprint and the *MineQTT control* label decide as
above, and an edge sends nothing when the entity already shows it. Requests from triggers name no player in Home
Assistant's logbook ("by a redstone signal"). A trigger whose entity Home Assistant no longer shares (its label removed
while the catalog runs) is not switched: the block shows *Not shared*, logs one warning, and heals when the entity is
labelled again. The Wall Switch's `left` and `right` events (with the gesture) also travel over wires: a wire from them
fires on every gesture of that button (a press, a double press and a hold), unlike a wire from `press`.

### Loops and what may send requests

Every change in the game remembers what started it. Only a chain a player or the world started may send a request:

| Started by | Example | May send a request |
|---|---|---|
| A player | a right-click, the Controls tab, `/mineqtt devices set` | yes |
| The world | a lever, a pressure plate, a redstone clock, a wire from them | yes |
| Home Assistant's state | a mirrored block changing because the entity changed, and everything it powers in the same tick | no |
| A Home Assistant command | a device's `/set` topic, and wires from that device | no |
| A raw topic | a Raw Link, and what a Block Placer reports on its out-ports after a raw message | no |
| A server start or chunk load | power-on behaviour, a queued command landing | no |

So a mirrored block cannot echo Home Assistant's state back, a Home Assistant automation that switches a plain MineQTT
block wired to a mirrored one does not bounce back, a restart never switches a real device, and anyone who may publish
on `mineqtt/shared/#` cannot switch an owner's real light through a wireless link. It also means a wire from one
mirrored block to another (real light A → real light B) never switches B in Home Assistant: link them in Home
Assistant. A refused attempt is counted (`/mineqtt status`: `dropped: 1 root`) and logged once per block.

**What the rule cannot see: delayed redstone.** A repeater, observer, comparator or any scheduled tick that reacts to
a mirrored block a tick later starts a new chain that counts as the world's, and so does a Home Assistant automation
that writes a raw topic. Such a chain may send requests: a mirrored Output driving a Redstone Input that targets the
same entity through a repeater makes a real loop. The rates bound it, and the pause after more than 8 requests in 10 s
ends it, with a warning naming the entity; take the repeater out and resume the block.

### Privacy

- What a mirrored block shows reaches every player near it: the lamp's colour, the output's level, the panel's text.
  That is the feature, so mirror only what the players of your server may see.
- The entity's id and name never go to players' games with the block. Only players who may configure a block see
  which entity it follows or switches (the Twin tab, its status line, its links list); everyone else reads that it
  mirrors a Home Assistant entity or an MQTT device. The picker answers only players who may bind, with allowed,
  shared entities only.
- A bound block's state topic on the broker names the entity (`binding.entity`, for the *Binding* entity in Home
  Assistant): any broker client can read it, and so can players who may configure the block, in the explorer.
- Requests carry the player's name (`origin.player`), which Home Assistant writes to its logbook. Turn the logbook line
  off in the control blueprint if you do not want that.
- The world saves only a block's own state and its binding, never Home Assistant's entity data or the catalog. The
  catalog lists only whitelisted attributes (never locations, codes, tokens or pictures).

### Troubleshooting by the health line

The Twin tab's last line (and `/mineqtt devices info <device>`, `Twin:`) tells how a binding is doing:

| Health line | Meaning | What to do |
|---|---|---|
| Waiting for Home Assistant | Nothing arrived for the entity yet | Is the entity in the statestream `include:` (and Home Assistant restarted since)? Does `twin.statestreamBase` equal statestream's `base_topic`? `/mineqtt twin show <entity>` shows what MineQTT knows |
| Live | Values arrive as Home Assistant changes them | nothing |
| Retained (may be old) | Only stored values since Home Assistant's last start | Normal right after a restart; it turns Live with the next change or catalog. If it stays, install the catalog blueprint. A right-click sends a toggle meanwhile |
| Unavailable in Home Assistant / Unknown in Home Assistant | Home Assistant says so | Fix the device in Home Assistant; the block holds (or turns off with `twin.unavailable off`) |
| Not shared by Home Assistant | The catalog runs and does not list the entity | Label it *MineQTT read* (or *MineQTT control*). If `/mineqtt twin status` names its domain's list as refused (larger than 64 KiB), share fewer entities of that domain |
| Refused | The server's rules refuse the binding now (hover for the reason) | Usually `twin.allowedEntities`, the twin being off, or the owner's `mineqtt:twin.bind`; it heals when the rules allow it |
| Control paused | More than 8 requests in 10 s | Remove the loop, then *Resume* |
| a request waits, then "no change from Home Assistant" | Home Assistant did not report a change within 5 s | Is the control blueprint's automation on, and does its request topic match (`/mineqtt twin ping`)? Is the entity in the statestream include? |
| answered not_labelled (or another answer) | The control blueprint refused | `not_labelled`: label it *MineQTT control*; `domain_not_allowed`: add the domain to the blueprint's allowed domains (and `twin.haActionAllowlist`); `no_such_entity`: the entity is gone (renamed in Home Assistant?), bind it again |
| Twin off: … | The server reads no Home Assistant entities | Set what it names (`twin.allowedEntities`, `twin.statestreamBase`) |
| Waiting for the MQTT device | Nothing arrived on the preset's state topic yet (an [MQTT device](#mqtt-devices-without-home-assistant)) | Is the device's name spelled as the device publishes it, and is it the right preset? The tab's tooltip (and `devices info`) shows the state topic: does the device publish there, retained? A device that publishes only on changes shows up with its next change |
| The MQTT device reports unavailable | The device's state reads `unavailable` | Fix the device; the block holds (or turns off with `twin.unavailable off`). Its last will is not read: a device that drops off keeps its last state |
| Unknown on the device's topic | A message arrived but the preset finds no state in it (not JSON, too large, nothing at the preset's state path, or `unknown`) | Choose the preset that matches the device's firmware and payload (a zigbee2mqtt light or switch, Tasmota, Shelly Gen2, ESPHome, or a generic topic) |
| … · N refused by the rate | N requests of this device were refused by the rates (4 a second per device and per player, `twin.maxRequestsPerSecond` per server) since it loaded or was resumed | Click less often or slow the redstone. Hover for the last one's entity (players who may configure the block). A state the world asked for is not refused but waits |

`/mineqtt twin status` also warns when the catalog is older than 30 minutes (is its automation on?) and names mirrored
entities whose catalog value changes (another state or other attributes) twice while no live value arrives ("no live
updates: add it to the mqtt_statestream include"; never scenes, scripts and buttons, which change only when triggered).
Home Assistant restarts alone never cause it: a restart re-dates every restored entity without changing it.

### MQTT devices without Home Assistant

A Smart Lamp, Smart Plug, Redstone Output or Display Panel can also mirror an MQTT device directly through a
*preset*, which makes the device's topics look like a Home Assistant entity of one kind: `/mineqtt devices bind
<device> preset <preset> <name>` (or `devices setting <device> twin.device "<preset> <name>"`; `twin.preset <preset>`
switches the preset of the device already named).
On
the Twin tab, step the source to *MQTT device*: the preset button cycles through the presets the block may follow (at
most 40; more by command), and typing the device's name into *Device name* and pressing Enter (or leaving the field)
binds it. Stepping the preset of a bound device switches its preset (`twin.preset`). Any change of the preset or the
device's name turns control off, of the same kind too (the command topic changes and is checked again): turn it on
again on the tab.
Players who may configure the block see the device, the preset and its state topic; everyone else reads "Bound to an
MQTT device". A block bound to an MQTT device works, and is not shown as "Twin off", on a server whose Home Assistant
twin is off. To switch such a block to a Home Assistant entity, pick one (Bind mode or the picker): the picker warns
that the binding replaces the MQTT device. Built-in presets: `mineqtt:zigbee2mqtt_light`,
`zigbee2mqtt_switch`, `zigbee2mqtt_sensor`, `zigbee2mqtt_contact` (state on `zigbee2mqtt/<name>`, the light's and
the switch's commands on `zigbee2mqtt/<name>/set`), `mineqtt:tasmota_power` (`stat/<name>/POWER`,
`cmnd/<name>/POWER`), `mineqtt:shelly_gen2_switch` (`<name>/status/switch:0`, `<name>/command/switch:0`),
`mineqtt:esphome_light` (`<name>/state`, `<name>/command`) and `esphome_sensor` (`<name>/state`),
`mineqtt:generic_topic` (a sensor) and `mineqtt:generic_switch` (a read-only on/off), whose name is the whole topic.

Binding needs `mineqtt:twin.bind` but not `twin.allowedEntities` or a statestream base: the broker ACL is the gate. A
state topic is readable without an `acl.rawAllow` entry, but never inside the statestream namespace, the twin's own
request traffic or this server's own device and group topics. Turning control on (`twin.control`,
`mineqtt:twin.control`) needs the command topic in `acl.rawAllow` (e.g. `zigbee2mqtt/+/set`, `cmnd/+/POWER`), for the
binder and the device's owner, and every command is checked again for the owner when it is sent. With control on,
using the block publishes the preset's command (a toggle while the device's state is only retained, else the explicit
state; typed values only) and the block follows when the device reports it; the rates, the pause and the rules of
[Loops](#loops-and-what-may-send-requests) apply as for Home Assistant. Datapacks add presets as
`data/<namespace>/mineqtt/binding_presets/<id>.json` (the kind, the state and command topics with `{device}`, the
state's path and on/off spellings, attributes, command templates with typed placeholders such as `{brightness}`,
`{red}`, `{color_temp_mired}`, and ranges that clamp them); a bad file is skipped with a warning naming its problems,
and `/reload` loads them again and judges every binding anew. `/mineqtt twin status` counts the loaded presets and
devices. Availability (last will) topics are not read yet, so an offline device keeps its last state, and presets
cannot be triggers of a Redstone Input or Wall Switch.

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
- **Timers:** the Interval Timer, the Game Time Timer (now the Daylight Timer), the Alarm Clock and the Cron Timer
  become devices in place, with their settings, counters, owner and anvil name; the items of their old slots are kept as
  legacy items (Diagnostics tab, *Eject*). A v1 Alarm Clock without an alarm is a disabled one set to 06:00, and an
  interval over 1 000 000 is clamped. Their slots never published, so they get no raw link. A Daylight Timer or Alarm
  Clock at or near sunrise fires once a day (v1 fired twice around the day boundary), a Cron Timer's `*/n` and `v/n`
  steps take every n-th value (v1 took every value, so `0 */5 * * * * *` fired every second), and a *Trigger now* during
  a pulse is refused with the reason. Their items count against `limits.maxDevicesPerPlayer`.
- **Block Sensor:** it is now the Block Observer, a device. Its topic becomes its raw link on the same topic (not
  retained), its data mode its `payload`, and its slot items are kept as legacy items. The `full` payload carries block
  entity data only with `blockObserver.exportBlockEntityData` ([Block Observer payloads](#block-observer-payloads)).
- **Block Emitter:** it is now the Block Placer, a device, and listens on its v1 topic as its raw link. It places only
  blocks of `#mineqtt:placeable` and never acts on NBT, block entity data, a property-only edit, a display name or a
  retained message ([Block Placer rules](#block-placer-rules)); by default the block's item comes from the container
  behind it. Its test tab is gone, and a block it placed in v1 is never cleared.
- **Block Sensor and Block Emitter topics** follow the raw link rules of every device: the topic stays its owner's
  (grandfathered) under the hard denies. A topic that is a wildcard filter (`+`, `#`), longer than 256 bytes, under the
  discovery prefix (e.g. `homeassistant/status`) or under any bridge's `bridge/` level no longer works after the upgrade;
  `/mineqtt devices info` shows why. A sensor and an emitter on one topic keep talking to each other on the server
  alone, also without the broker.
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
  Temperature Sensor the Climate Sensor, the Light Remote the Wall Switch, the Terminal the Bridge Console, the Block
  Sensor the Block Observer, the Block Emitter the Block Placer, the Game Time Timer the Daylight Timer and the Cyberdeck
  the Configurator. Their ids are unchanged, so existing blocks and items stay what they are.
- **Blocks** keep their ids, items and settings. Since 0.8.0 every block that talks MQTT is a device; the retired
  Dynamic Publisher and Topic Relay stay as described above.

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
| Display Panel | `GGG / GNG / SRS` | G black stained glass pane |
| Alarm Clock | `SCS / RNR / SSS` | C clock |
| Block Observer (formerly Block Sensor) | `SOS / ONO / SSS` | O observer |
| Block Placer (formerly Block Emitter) | `SDS / RNR / SSS` | D dispenser |
| Configurator (formerly Cyberdeck) | `_CA / _CN / S__` | _ empty, C copper ingot, A amethyst shard, S stick |
| Bridge Console (formerly Terminal) | `IGI / NCN / III` | I iron ingot, G glass, C comparator |

Unlisted letters keep their meaning from the rows above (S smooth stone, R redstone, N Network Chip). The four timers
cycle in the crafting grid: one timer alone gives the next, Alarm Clock → Daylight Timer → Cron Timer → Interval
Timer → Alarm Clock. A Humidity Sensor alone in the grid (or a crafter) gives a Climate Sensor; that recipe unlocks once
you hold one; a Static Publisher alone gives a Redstone Input the same way. The Dynamic Publisher and Topic Relay are
retired: no recipe makes or uses them (see [Upgrading from v1](#upgrading-from-v1)).

## Features

### Block Observer payloads

The Block Observer's Raw Link sends the payload its `payload` setting names, on every change of the block in front:

| `payload` | Raw Link payload |
|---|---|
| `full` (default) | JSON with the id, display name, state properties, position, dimension and block entity data |
| `block_id` | The registry name, e.g. `minecraft:stone` |
| `name` | The display name, e.g. `Stone`, in the server's language |
| `properties` | The state properties as JSON, e.g. `{"facing":"north","powered":"true"}`; `{}` for a block without properties |

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

`block_entity` is `null` unless the server sets `blockObserver.exportBlockEntityData` and the observer and its target
share one land claim (always, without a claim mod); block entity data never reaches Home Assistant. Data longer than
16 KiB is left out as `"block_entity":null,"truncated":true`. In `full` the payload is also compared every 10 ticks, so
a chest's changed contents go out without replacing the chest, and data the server stops allowing leaves at the next
comparison. The first look after placing sends the payload too, and so does a change of the `payload` setting.

### Block Placer rules

A Block Placer places only blocks in the block tag `#mineqtt:placeable`: by default the wool, concrete and terracotta
colours, glass and stained glass, the redstone lamp and the sea lantern (68 blocks). A datapack extends it with
`data/mineqtt/tags/block/placeable.json`, e.g. `{"values":["minecraft:oak_planks"]}`; after `/reload` Home Assistant's
select offers the new blocks. Whatever the tag says, a placer never places a block with a block entity, a game master
block, TNT, a block no tool breaks, a fluid, fire, air or a portal, and it refuses NBT or block entity data, display
names and blocks given by their properties alone.

It places into a replaceable space in front (such as air or tall grass) or replaces its own block, inside the world
border and outside spawn protection, both judged for its owner. Across a claim line (with a claim mod such as Flan) it
places only while its owner is online and may build there. A placement without properties takes the state a dispenser
would place. It clears only the block it placed itself, in any state of that block (a lamp it placed stays its own
while redstone lights it), without drops, and looks again right before it clears; a block a v1 Block Emitter placed is
never cleared. With `blockPlacer.consumeItems` (the default) the block's item comes from the container behind the
placer and goes back there on clear; it drops where the block stood when it does not fit, when the container's chunk
is not loaded or when the container lies across a claim line the placer may not reach. The container behind follows
the same claim rule as the space in front: the same claim as the placer, or its owner online and allowed to open
containers there; otherwise placing answers "refused by the claim behind it". The placer acts only on loaded chunks and never loads one: while the space in front is not loaded it
answers "the space in front is not loaded", and with `consumeItems` "the container behind it is not loaded" while
that is not. Each action, refused ones included, counts against the 4 a second.

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

### Implemented (MineQTT 0.7.0: milestone 3, the Home Assistant twin)

- Mirror Home Assistant entities on the Smart Lamp, Redstone Output (switch and gauge), Smart Plug and Display Panel,
  read through `mqtt_statestream` and the MineQTT catalog blueprint, with labels in Home Assistant deciding what
  Minecraft sees
- Switch them back through the MineQTT control blueprint (label, domain, action and data-key gates, logbook), with
  loop protection by cause, rates, and a pause for loops through delayed redstone
- The Redstone Input and the Wall Switch's buttons as Home Assistant triggers
- The Display Panel: four lines of text from Home Assistant, wires or a raw topic, with a comparator output
- The Twin tab, the searchable entity picker and the Configurator's Bind mode
- MQTT devices without Home Assistant (zigbee2mqtt, Tasmota, Shelly, ESPHome, any topic) through datapack presets
- Twin permissions, an allowlist of entities that is empty on dedicated servers by default, and binding details only
  for players who may configure a block
- Smart Lamps on walls and ceilings; adventure-mode players only use MineQTT blocks

### Implemented (MineQTT 0.8.0: milestone 4, the world twin)

- The four timers as devices with Home Assistant entities, a next-fire timestamp and the Cron Timer's Schedule tab, and
  as flow triggers that switch lamps without the broker
- World telemetry on the bridge device (TPS, MSPT, time of day, day, day or night, weather, players), player events
  and an optional broadcast text into the chat, never a command
- Per-player consent (`/mineqtt privacy`, a one-time prompt, the explorer's You row) and presence devices for players
  who share
- Rooms that put devices into Home Assistant areas, in Home Assistant's own spelling, with the Configurator's Room mode
- The Block Observer and a Block Placer limited to a tag of safe blocks, with claims, rates and no NBT
- A device-centric Configurator explorer: your devices by room, Use, Locate and Open from afar, a room's All on and All
  off
- An example Home Assistant dashboard of the world

### Planned

- Milestone 5: hardening, localisation and a documentation site
- Milestone 6: flows, like Node-RED made of blocks: logic blocks, data cables and a Flow Terminal

### Showcase

#### Send redstone state to MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/o80VsFaYbnM" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Receive redstone state from MQTT

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/bIY0cpPkwmU" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

#### Configuring topics using item stacks and named paper

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/w5Pt1TFN1m4" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

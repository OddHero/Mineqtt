# Changelog

## 0.9.0 (every Minecraft version from 1.21 to 26.3)

0.9.0 brings MineQTT to every Minecraft release from 1.21 to 26.3, on Fabric and NeoForge, and lets the Block Placer
build whatever you put into the container behind it, chosen from Home Assistant. Worlds and config files of 0.8.0 load
unchanged; a new config key takes its default. On Minecraft 26.2, update Architectury API to 21.1.12 or newer together
with MineQTT (see **Breaking changes**).

### For players

- **Every Minecraft version from 1.21 to 26.3.** There is one jar per loader for 1.21.1, 1.21.3, 1.21.4, 1.21.5,
  1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2 and 26.3, each also covering the patch releases next to it. The jars are
  named `MineQTT-<loader>-<mod version>+<minecraft>.jar`, and Modrinth lists one version per loader and Minecraft
  version.
- **Block Placer: the container behind it decides.** Home Assistant's block select now offers the blocks in the
  container behind each placer (and the block it is set to), so you pick from the frontend what it builds next; without
  a container it offers the `#mineqtt:placeable` list as before. The state carries the list as `choices`, and the
  placer looks again every five seconds and after each place and clear. While placing costs the item, any block you
  put into that container may be placed, stone and planks too, not only the tag's.
- **Textures:** the Redstone Output is red now and the Redstone Input blue: the red block is the one that gives off
  redstone.

### For server admins

- **`blockPlacer.containerUnlocks`** (new, default on): while `blockPlacer.consumeItems` is on, any block in the
  container behind a Block Placer may be placed. Turn it off to keep placers to `#mineqtt:placeable`. The placer as a
  whole stays off unless `blockPlacer.enabled`.
- Whatever the tag or the container says, a placer never places blocks with block entity data, TNT, fluids, fire,
  portals, unbreakable blocks, blocks of two parts (doors, tall plants) or blocks without an item, and it refuses a
  block that cannot stand in front (a torch without a wall). It reads the container only where the claim rule lets it
  open it, so a neighbour's chest across a claim line stays private.
- **Minecraft 1.21.1:** an empty dedicated server never pauses on 1.21.1, so `server.keepTickingWhenEmpty` does
  nothing there.

### Breaking changes

- **Architectury API 21.1.12 or newer on Minecraft 26.2.** 0.8.0 ran on 21.1.9; the 26.2 jar of 0.9.0 does not load
  with it. Each jar names the Architectury API version it needs; Fabric and NeoForge tell you at startup.
- **Block Placers with a container behind them** offer only that container's blocks in Home Assistant after the
  update, and can place any block from it (see `blockPlacer.containerUnlocks` above).

### For developers

- The build is a Stonecutter multi-version project like minecraft-mod-template. Gradle tasks carry the version, such
  as `:fabric:26.2:runClient` and `:fabric:26.2:runClientGameTest`, and `./gradlew build` builds and checks every
  version. Minecraft up to 1.21.11 builds with Java 21, 26.x with Java 25. The client game tests run from 1.21.4 on.
  Building, checks and releases are described in
  [`.github/development.md`](https://github.com/OddHero/Mineqtt/blob/main/.github/development.md).
- Architectury's transform tasks run one at a time, so parallel builds of several versions no longer fail at random.
- The message bus checks use a fixed clock, so their drain budget no longer fails on a busy machine.
- A select may take its options from a list in the device's state (`HaEntity.Select.optionsFrom`); discovery is
  published again when that list changes.

## 0.8.0 (MineQTT 2, Milestone 4: the world twin)

0.8.0 makes the Minecraft world itself a Home Assistant device and gives blocks real jobs in automations: the bridge
reports time, weather, load and the players who agree to it, the four timers become devices and flow triggers, the
Block Sensor and the Block Emitter return as a Block Observer and a safe Block Placer, rooms put devices into Home
Assistant areas, and the Configurator lists and switches your devices from anywhere. Nothing about a player leaves the
server without that player's consent, and no MQTT message ever runs a command. It is for Minecraft 26.2 on Fabric and
NeoForge and replaces 0.7.0, whose worlds and config files load unchanged on both loaders. Read **Breaking changes**
before you update a server: the timers, the Block Sensor and the Block Emitter work differently now, and client and
server must both run 0.8.0.

### For players

- **Timers are devices.** The Interval Timer, the Daylight Timer (formerly Game Time Timer), the Alarm Clock and the
  Cron Timer open the generated device screen. Its Controls tab has *Enabled*, *Trigger now*, the schedule (a typed
  number field for the interval, 1 to 1 000 000) and a status line ("Next 06:00, in 19 min", "Every 5 s", "Invalid
  cron", "Disabled"). Enabling never fires at once; *Trigger now* fires also while disabled and is refused during the
  pulse of the last fire. Settings, counters, owner and name carry over, and the items of the old slots are kept as
  legacy items (Diagnostics tab, *Eject*). Timer, Block Observer and Block Placer items count against
  `limits.maxDevicesPerPlayer`.
- **The Cron Timer's Schedule tab** edits its expression field by field, with presets and a preview of the next three
  fires.
- **Timers are flow triggers.** A timer's `fired` event (with its source, `schedule` or `manual`) drives wires: in Link
  mode a Daylight Timer wires to a lamp as a toggle, a Wall Switch to a timer as *Trigger now* and a timer to another
  timer, and a lever wired to a timer arms and disarms it. All of it works with the broker down. A timer's Raw Link
  publishes `ON` on each fire, never retained.
- **Block Observer** (formerly Block Sensor), a device: the block in front with its state properties, its comparator
  level, a `changed` event and the settings `change_on` (any state change, or only another block) and `payload` (what
  its Raw Link sends: `full`, `block_id`, `name` or `properties`). It never loads the chunk in front. Its `signal`
  wires to a Redstone Output's level.
- **Block Placer** (formerly Block Emitter), a device: places its block in front and clears it again, from Home
  Assistant, its Controls tab, a command, a wire or its Raw Link. It places only blocks of `#mineqtt:placeable` (wool,
  concrete, terracotta, glass, stained glass, the redstone lamp and the sea lantern by default), takes the block's item
  from the container behind it and gives it back on clear, clears only the block it placed (in any state of that
  block), acts at most 4 times a second and never on a retained message, and `last_result` says what happened or why
  not. It needs `blockPlacer.enabled`.
- **Rooms.** The Configurator's new **Room** mode (after Bind) makes a room from two opposite corners and a name, with
  suggestions; a sneak-click inside one of your rooms renames or removes it, and while you hold the Configurator in
  Room mode the box and nearby rooms are outlined with particles only you see. An ordinary click still opens doors and
  chests: a block without a use, a device, or any block beyond hand reach becomes the corner. The same by command:
  `/mineqtt rooms list|info|create|rename|resize|remove`. Your devices in a room take its name as their Home Assistant
  area when Home Assistant first sees them. A device's General tab shows its room, with a `*` when the area Home
  Assistant was first told differs (on hover: which one, or none).
- **Rooms follow Home Assistant's area names.** A room named like an area of the catalog, case and spaces ignored
  (`living room` for *Living Room*), uses Home Assistant's own spelling, and the room prompt suggests up to 16 area
  names to players who may bind devices to Home Assistant.
- **The Configurator opens on a Devices tab**: your devices (every device for an operator who may build; nobody else's,
  not even public ones), grouped by room, searchable and paged, with a room filter, each with its availability, up to
  three values and how far away it is in which direction. Select a device to *Use* it from anywhere (a lamp in an
  unloaded chunk switches when it loads), *Locate* it (particles for 5 s within 128 blocks, else how far and where) or
  *Open* its screen within 16 blocks. A room's header has *All on* and *All off*: they switch your lamps, plugs and
  outputs in that room (at most 64, once a second; timers are left alone), as `/mineqtt rooms switch <room> on|off`
  does. Operators also see the device groups. The topic explorer tab is now called *Traffic*.
- **Privacy.** Nothing that names you is published until you choose. A one-time chat prompt offers *[Share presence]*
  and *[Don't share]* (and *[Share all]* where the server shares full data); `/mineqtt privacy` tells you what is
  published about you, and `/mineqtt privacy share on|full|off` changes it, as does the *You* row at the top of the
  Devices tab. The host of a single player or LAN world shares their presence until they say otherwise; LAN guests do
  not.
- **The Bridge Console** shows the world (TPS, time, weather; MSPT, the day and the telemetry line on hover) and the
  players online, naming to operators only the players who share their name. The broker moved into the MQTT row's
  hover, and the drop counters and wires into one *Traffic* row.
- The Diagnostics tab counts events held back by an event's rate ("3 events throttled"), and screens without the
  player's inventory are 3 px taller.

### For server admins

- **World telemetry on the bridge device.** Home Assistant's bridge gains *TPS* and *MSPT* (diagnostic; MSPT disabled
  by default), *Time of day* (in 15-minute steps), *Day*, *Is day* (06:00 to 18:00 game time) and *Weather* (`clear`,
  `rain`, `thunder`); *Players online* lists the names of consenting players as its `players` attribute. A change of
  the players, of day and night or of the weather reaches Home Assistant within about 2 s, anything else at most every
  `telemetry.intervalSeconds`, and unchanged telemetry is not republished. The existing bridge entities keep their
  unique_ids and history.
- **Player events.** A *Player event* entity (`bridge/event`, never retained): the join and leave of every player, named
  only for consenting players; deaths (with the cause's translation key, never the death message) and announced
  advancements only of consenting players. At most one join and one leave per player every 5 s, and 10 events a second
  for the bridge.
- **Presence devices.** Each consenting player becomes a Home Assistant device *Player <name>* under the bridge with a
  presence sensor on `<base>/<bridge>/player/<slug>`: `OFF` after a quit, a graceful stop and, after a crash, at the
  next connect. Ids are salted per world (`mineqtt_player_<32 hex>`) and never show a Mojang UUID. With
  `telemetry.players=FULL` and a player's `share full` it also has health, dimension and position (at most every 5 s,
  after 4 blocks or half a heart; 20 such updates a second for the server). A presence device goes away at `share off`,
  `/mineqtt players forget`, after `telemetry.forgetAfterDays` away (counted from the last quit) and when the server
  stops sharing players; beyond `telemetry.maxPresenceDevices` nobody new gets one. The ghost cleanup also removes
  presence configs and retained presence states that no player of the world holds, and `/mineqtt discovery clear`
  clears the presence devices too.
- **Broadcast text, off by default.** With `telemetry.allowBroadcast`, Home Assistant's *Broadcast* text entity on the
  bridge (or any client that may write `<base>/<bridge>/bridge/request/broadcast`) shows a line of plain text in every
  player's chat behind `[MQTT]`. It is never a command or a JSON component: at most 1 KiB, cut to 256 characters,
  formatting codes and control and bidi characters removed, 3 at once and then 1 a second, a retained request ignored,
  each shown text logged, the answer on `bridge/response/broadcast`. Turning the key off removes the entity, also when
  Home Assistant was offline then. Restrict `<base>/+/bridge/request/#` in the broker ACL to the clients that should
  send it.
- **Timers, Block Observer and Block Placer in Home Assistant.** A timer has an enable switch (its primary entity), a
  *Fired* event, a *Trigger now* button, a *Next fire* timestamp and its schedule as configuration entities (the time
  with a pattern Home Assistant checks). The Block Observer has a block sensor whose attributes are the block's
  properties, a signal sensor, a *Changed* event and its two settings (the payload select disabled by default). The
  Block Placer has a select of the placeable blocks (refreshed after a `/reload` that changes the tag), *Place* and
  *Clear* buttons, a *Placed* binary sensor and a diagnostic *Last result*.
- **Rooms and areas.** A device in a room is discovered with the room's name as `suggested_area`, a group when all its
  devices are in one room. Home Assistant 2025.2.5 and 2026.10.0 create a missing area and reuse one spelled in another
  case.
- **New config keys**, all in effect on both loaders: `telemetry.enabled` (true), `telemetry.intervalSeconds` (10,
  5-300), `telemetry.players` (`PRESENCE`; `OFF`, `PRESENCE` or `FULL`: the most published about a consenting player),
  `telemetry.publishPlayerNames` (true; `false` publishes counts only and it never names anyone who did not consent),
  `telemetry.allowBroadcast` (false; Fabric reads it at start), `telemetry.maxPresenceDevices` (64, 1-1000),
  `telemetry.forgetAfterDays` (90, 0-3650, 0 = never), `blockPlacer.playersMayConfigure` (`AUTO`: yes on integrated,
  no on dedicated servers), `blockPlacer.consumeItems` (true), `blockObserver.exportBlockEntityData` (false),
  `rooms.maxSize` (128, 8-512) and `limits.maxRoomsPerPlayer` (16, 0 = unlimited; operators are not limited, and a
  world holds at most 512 rooms). An invalid `telemetry.players` counts as `OFF` and an invalid
  `telemetry.publishPlayerNames` as `false`. `configVersion` stays 2: the Fabric `mineqtt.json` is not rewritten, and
  NeoForge adds the new keys to `mineqtt.toml` and keeps every existing value, `blockPlacer.enabled` included.
- **New permission** `mineqtt:rooms.admin` (op level 2 by default): rooms that apply to every device inside them
  (`for-all`), not only to the maker's own.
- **Commands.** `/mineqtt` is reachable by every player and each subcommand checks its own permission: every player may
  use `privacy` and `rooms` (whose commands check ownership and build rights), everything else keeps its level, and a
  player without the right now reads "Incorrect argument for command" (0.7.0: "Unknown or incomplete command"). New:
  `/mineqtt privacy`, `privacy share on|full|off`, `players list|forget <player>` (op level 2) and `rooms
  list|info|create|rename|resize|remove|switch`. `/mineqtt status` ends with the telemetry line (`Telemetry: every
  10 s · players: presence by consent (2 sharing) · broadcast off`).
- **Block Placer rules.** On a dedicated server only operators configure a Block Placer unless
  `blockPlacer.playersMayConfigure`. It never places a block with a block entity, a game master block, TNT, a block no
  tool breaks, a fluid, fire, air or a portal, whatever a datapack adds to the tag. It respects the world border, spawn
  protection and, with a claim mod, the claim (the same claim as the placer, or its owner online and allowed to build
  there), also for the container behind it, and acts only while the chunks in front (and behind, when it takes items)
  are loaded. `blockPlacer.consumeItems=false` places without items.
- **Example dashboard.** `docs/dashboards/mineqtt-world.yaml` shows the world telemetry, the players and their events
  with entities MineQTT creates.
- **Downgrade.** 0.7.0 resets the settings of timers, Block Observers and Block Placers, loses the items of the timers'
  old slots, and forgets rooms, privacy choices and presence devices. It leaves in Home Assistant the presence devices,
  the timer, Block Observer and Block Placer devices (no longer refreshed, their buttons and switches doing nothing)
  and the bridge's new 0.8.0 entities (world telemetry, *Player event*, *Broadcast*); run `/mineqtt discovery clear`
  before the downgrade, or delete them in Home Assistant. Back up the world first.

### Breaking changes

- **Client and server must both run 0.8.0.** The screens of the four timers, the Block Sensor tab and the Block
  Emitter's test tab are gone with their six network payloads; the Devices tab, Room mode and the privacy choice add
  six new ones.
- **Timers are unavailable in Home Assistant while unloaded** (they do not tick then) and refuse commands; wires drop
  what they send to an unloaded timer.
- **Cron steps follow the documented grammar**: `*/n` now takes every n-th value from the field's first (0.7.0 took
  every value, so `0 */5 * * * * *` pulsed every second and now every 5 s), and `v/n` runs from v to the field's last
  (0.7.0 took v alone, so `0 0 5/15 * * * *` fired at minute 5 and now at 5, 20, 35 and 50). Check your Cron Timers
  after the upgrade.
- **The Block Observer's `full` payload** carries `block_entity` only with `blockObserver.exportBlockEntityData` and when
  the observer and its target share one claim (always, without a claim mod), cut at 16 KiB; otherwise it is `null`. The
  data mode is now the `payload` setting.
- **The Block Placer** no longer acts on NBT or block entity data, property-only edits of the block in front, display
  names, blocks outside `#mineqtt:placeable` or retained messages, and by default it needs the block's item in the
  container behind it. A block a v1 Block Emitter placed is never cleared. On a dedicated server a non-operator no
  longer configures the Block Emitters they own (now Block Placers) unless `blockPlacer.playersMayConfigure` is `TRUE`.
- **v1 Block Sensor and Block Emitter topics** follow the Raw Link rules of every device: grandfathered for their owner,
  under the hard denies. A topic that is a wildcard filter (`+`, `#`), longer than 256 bytes, under the discovery
  prefix (for example `homeassistant/status`) or under any bridge's `bridge/` level no longer works after the upgrade
  (`/mineqtt devices info` shows why).
- **No way back to 0.7.0 without losses** (see *Downgrade* under For server admins).

### Fixed

- A Daylight Timer or Alarm Clock at or near sunrise fires once a day (it fired twice around the day boundary).
- The Daylight Timer and the Alarm Clock show no next fire while the day clock stands (`gamerule advance_time false`,
  `/time pause`) and keep it under `/time rate`, their estimate following the clock's rate; with `/tick freeze` every
  tick-based timer shows none until the ticks run again.

### Known limitations

- Home Assistant applies a suggested area only when it creates a device. A device it already knows keeps its area when
  a room comes, moves, is renamed or goes, and the tested versions differ in two cases: a known device without an area
  moves into the room's area on 2025.2.5 and stays without one on 2026.10.0, and a device hidden and exposed again
  gets the current room's area on 2025.2.5 but its old area back on 2026.10.0. Change areas in Home Assistant.
- Home Assistant 2026.10.0 puts the area into the entity id of a device it creates inside one
  (`light.kitchen_smart_lamp_1a2b`); 2025.2.5 does not.
- A catalog area's spelling reaches a device's suggested area at its next discovery refresh; a catalog change alone
  republishes nothing.
- The Devices tab's room headers do not mark rooms whose name matches a Home Assistant area.
- `next_fire` of the Interval Timer, Daylight Timer and Alarm Clock is an estimate from the tick rate (lag and `/tick
  rate` move the real fire), and a dedicated server paused while empty keeps the one it showed before the pause.
- The Cron Timer follows the server's clock and time zone, so a daylight saving change can skip or repeat a fire.
- A timer's Raw Link always publishes `ON`; it has no custom payloads.
- A Daylight Timer at sunrise that 0.7.0 saved in the 20 ticks before the day turned (between 0.7.0's two fires of that
  sunrise) fires once more right after the upgrade.
- Timers fire only while their chunk is loaded.
- With a claim mod, a Block Placer whose owner is offline places, and takes items, only inside its own claim: an
  offline owner's build rights cannot be checked.
- A Home Assistant that was offline when the Block Placer's placeable blocks ran out (a datapack emptying the tag)
  keeps the select, unavailable, until blocks come back or it is deleted there.
- Home Assistant keeps the history of removed presence devices. Changing `baseTopic` or `bridgeId` while the server is
  stopped leaves the old presence states on the broker. After a `bridgeId` change alone, `/mineqtt discovery clear
  --bridge <old id>` clears them; after a `baseTopic` change, clear the retained `<old base>/<bridge id>/player/#`
  topics on the broker by hand.
- Home Assistant logs `No device components to cleanup for mineqtt_…` once per entity when a device is hidden or
  cleared; the warning is harmless.

### For developers

- Four new checks (37 in all): `verifyTimers`, `verifyTelemetry`, `verifyPrivacy` and `verifyRooms`;
  `verifyBlockSensor` and `verifyBlockEmitter` are rewritten for the new devices, and `verifyAlarmClock` and
  `verifyGameTimeTimer` keep their names and assertions on the new timer core. Six new client suites (33 in all):
  `timers`, `world_devices`, `telemetry`, `privacy`, `rooms` and `explorer`. `SecurityGuardsCheck` counts 32 payloads
  (six retired, six added).
- New device-model seams: `EventSpec.minInterval` (an event's rate on MQTT; the bus carries every event),
  `DeviceType.Builder.noV1Topic()` and `status(StatusLine)`, `Widget.NUMBER_FIELD`, `HaEntity.Sensor.jsonAttributes`,
  `HaEntity.Text.pattern`, runtime select options (`Ha.selectDynamic`) and component removal by object id
  (`DiscoveryPublisher.componentsRemoved`); the Minecraft-free packages `device.timer.core` and `telemetry.core`.
- `LegacySubscriptionFacade` and `ICallbackTarget` are gone: no shipped block used them since the Block Observer and the
  Block Placer. `BaseMqttBlockEntity` stays for the two converters.
- e2e: `ha.py` gains `attrs`, `press`, `set-text`, `select`, `event-last`, `areas`, `area-of` and `dashboard-check`;
  `tools/build07.sh` builds the 0.7.0 jars and `tools/upgrade07.sh` checks the upgrade on both loaders; `lane.sh` takes
  `MINEQTT_LANE_JAR` and gains `exec`, and a lane's Home Assistant gets its automations file before it starts.

## 0.7.0 (MineQTT 2, Milestone 3: mirror your house)

0.7.0 brings real Home Assistant entities into Minecraft and lets Minecraft switch them back, safely, also on a
public server: a block can *mirror* an entity (the Home Assistant twin), a Redstone Input or a Wall Switch can
*trigger* one, and a new Display Panel shows any value. It is for Minecraft 26.2 on Fabric and NeoForge and replaces
0.6.0. Worlds and config files of 0.6.0 load unchanged: apart from the breaking changes below, nothing behaves
differently until you bind a block. The setup is in the wiki:
[Mirror Your House](https://github.com/OddHero/Mineqtt/wiki/Mirror-Your-House).
Read **Breaking changes** before you update a server.

### For players

- **Mirror a Home Assistant entity.** A Smart Lamp, Redstone Output, Smart Plug or Display Panel can follow an entity
  of your Home Assistant instead of keeping its own state:
  - *Smart Lamp*: a light's state, brightness and colour in one step (no flash of the old colour), or the on/off of a
    switch, input_boolean, fan or binary sensor in white.
  - *Redstone Output*: on/off entities as 15/0 (`twin.invert` swaps them; opening, unlocking and other states in
    between hold), numbers over a range (`input_number`, sensors, a climate's temperature, attributes such as a
    light's brightness through `twin.path`) for a comparator gauge, an option list as the option's index.
  - *Smart Plug*: a switch, input_boolean, light or fan.
  - *Display Panel*: any readable entity, read-only, as its value with its unit under the panel's name.

  A mirrored block changes only when Home Assistant reports a change. While the entity is unavailable it holds its
  last value with a grey tint and a puff of smoke (or turns off, `twin.unavailable off`), and it keeps its state over
  a server restart.
- **Switch the real device from Minecraft.** With control on, using a mirrored block (a right-click, its Controls
  tab, a command or a wire) asks Home Assistant to change the entity; the block follows when Home Assistant has
  done it, and a faint sparkle shows a request that waits. A refusal is explained in the action bar ("Home Assistant
  did not change it: it is not labelled for MineQTT control in Home Assistant").
- **Minecraft as a Home Assistant trigger.** A Redstone Input switches an entity on its edges (on and off, open and
  close, a scene, a script or a button on the rising edge), and each button of a Wall Switch activates a scene or a
  script, presses a button or toggles a light, switch, input_boolean or fan.
- **New block: the Display Panel** (`GGG / GNG / SRS`, behind the Network Chip, in the creative tab). A wall plate
  with up to four lines of text from its Home Assistant text entity, wires, `/mineqtt devices set` or its Raw Link, in
  16 colours, glowing or not, in four sizes. A comparator reads it: a gauge for numbers, on/off for words, presence for
  other text. Points in time are worded by each player's game ("in 2 h 14 min", "07:30").
- **The Twin tab.** Every block that can follow or switch Home Assistant has a *Home Assistant* tab after Controls:
  choose *This block*, *Home Assistant* or *MQTT device*, pick the entity (or a preset and the device's name), set
  control, invert, range, path, decimals and what happens while the source is unavailable, see whether it is live and
  how many requests the rates refused (the last one's entity on hover for those who may configure the block), and
  resume a paused control. A Redstone Input and a Wall
  Switch bind what their edge or each button switches there, each with a × that removes it.
- **The entity picker.** Search Home Assistant's shared entities by name, id or area, grouped by area, with a colour
  swatch, the value and *control* and *stale* badges; Enter binds, and *Rename* names the block after the entity. For
  a trigger the picker lists only entities Minecraft may control. It warns when the binding would replace a block's
  MQTT device, and always shows the answer to the domain and query asked last.
- **Configurator Bind mode**, the last step of the mode cycle (Configure, Link, Group, Copy-Paste, Bind): a click opens
  a block's Twin tab with the picker, also from up to 16 blocks away; on a Wall Switch it asks Left or Right first.
- **MQTT devices without Home Assistant.** A lamp, plug, Redstone Output or Display Panel can mirror a zigbee2mqtt,
  Tasmota, Shelly Gen2 or ESPHome device, or any topic, through a preset, and with control on switch it. Choose the
  preset and type the device's name on the Twin tab (or use a command); it works while the Home Assistant twin is off.
- **Wall Switch:** new `left` and `right` events (with the gesture) for wires and triggers; a wire from them fires on
  every gesture of that button. Home Assistant's event entity and device triggers are unchanged.
- **Smart Lamp mounts** on floors, walls and ceilings, by the face you place it against, like a button; existing lamps
  stay on the floor, and a lamp drops when its support goes away.
- **Adventure mode.** A player who may not build (adventure or spectator mode) only uses MineQTT blocks, an operator
  and a device's owner included: a right-click still switches a lamp or a plug and presses the Wall Switch, and a
  device's screen shows its Controls tab alone, read-only where it would configure. Name tags, renamed paper, the
  Configurator's other modes and its reach from afar are refused with a reason; the Bridge Console opens read-only.
- **Privacy.** Only players who may configure a block see which Home Assistant entity or MQTT device it follows, also
  in its status line; everyone else reads that it mirrors one.

### For server admins

- **The Home Assistant setup** takes `mqtt_statestream` (base `ha_statestream`, attributes and timestamps on, a tight
  `include:` list), two labels and two automation blueprints, all in the wiki's
  [Mirror Your House](https://github.com/OddHero/Mineqtt/wiki/Mirror-Your-House#set-up-home-assistant):
  - *MineQTT catalog* (`docs/blueprints/automation/mineqtt/mineqtt_catalog.yaml`) publishes the entities you label
    *MineQTT read* or *MineQTT control* (or whose device you label) with fresh snapshots. Minecraft shows only those,
    corrects stale retained values after a Home Assistant restart, and warns about mirrored entities missing from the
    statestream include. Without it MineQTT lists what statestream publishes.
  - *MineQTT control* (`docs/blueprints/automation/mineqtt/mineqtt_control.yaml`) runs Minecraft's requests for
    entities, or devices, labelled *MineQTT control*, within fixed domains, actions and data keys, one entity per
    request; it answers every request and writes accepted ones to the logbook ("turn on by Steve via Porch Lamp").
    Locks and climate devices are opt-in, a lock can never be opened and no code is ever sent.

  Home Assistant 2025.2 or newer is still enough; 2025.2.5 and 2026.10.0 are tested.
- **New config keys** in a `twin` section: `twin.statestreamBase` (`ha_statestream`), `twin.allowedEntities`
  (globs over entity ids; `AUTO` = every entity in single player and LAN worlds, **none on dedicated servers**),
  `twin.haActionAllowlist` (the control blueprint's 15 domains), `twin.maxRequestsPerSecond` (20), `twin.catalog`
  (true) and `twin.catalogMax` (2000). **Dedicated servers start with the twin off**: the startup log, `/mineqtt
  status` (`Twin:` line), the Twin tab and the picker name `twin.allowedEntities`; operators do not bypass it. In
  `mineqtt.json` a list key that holds no list allows nothing. `configVersion` stays 2; an existing file gets the
  section on its next save.
- **New permissions** `mineqtt:twin.bind` (bind a block) and `mineqtt:twin.control` (turn its control on): everyone in
  single player and LAN worlds (every player on an integrated server), op level 2 or the permission on dedicated
  servers. Opening a world to LAN, or sharing it through a tunnel mod such as e4mc, gives every guest both, and a guest
  binds the blocks they place, to entities and to MQTT device presets: set `twin.allowedEntities` (and label in Home
  Assistant only what guests may switch) before you share a world. A binding is judged for the binder and for the
  device's owner, again at every load and config reload (a refused binding heals when the rules allow it), and for the
  placer of a copied block (control dropped).
- **Commands** (op level 2): `/mineqtt devices bind <device> <entity>|none|resume`, `devices bind <device> target
  <target> <entity>|none`, `devices bind <device> preset <preset> <name>`, the `twin.*` keys of `devices setting`
  (`twin.resume press` resumes a paused control), and `/mineqtt twin status|entities [query]|show <entity>|ping|refresh`.
  `devices info` gains `Twin:` and `Twin targets:` lines. `/mineqtt status` counts the bus's messages by cause
  (`emitted 22 (19 world, 1 raw, 2 ha)`) and drops for their cause (`dropped: 1 root`).
- **Loop safety.** Only a chain a player or the world started can send a request: a change that came from Home
  Assistant, a raw topic, a device's `/set` topic, a server start or a chunk load never does, so a mirrored block
  cannot echo Home Assistant's state back. Requests are limited to 4 a second per device and per player and
  `twin.maxRequestsPerSecond` per server; over a rate a state the world asked for waits (the newest is sent as soon as
  the rate allows, so Home Assistant ends as the world left it), a player's click, a toggle and a press are refused,
  counted per device (`devices info`, the status line, the Twin tab) and logged once per burst. A device the world makes send, or try to send, more than 8 requests in
  10 s has its control paused until a player resumes it. Delayed redstone (repeaters, observers) starts a new world
  chain: it is bounded by the rates and ended by the pause, as the wiki explains
  ([Delayed redstone](https://github.com/OddHero/Mineqtt/wiki/Control-from-Minecraft#delayed-redstone)).
- **MQTT device presets**: ten built-ins (`mineqtt:zigbee2mqtt_light`, `zigbee2mqtt_switch`, `zigbee2mqtt_sensor`,
  `zigbee2mqtt_contact`, `tasmota_power`, `shelly_gen2_switch`, `esphome_light`, `esphome_sensor`, `generic_topic`,
  `generic_switch`), and datapacks add their own under `data/<namespace>/mineqtt/binding_presets/`; a bad file is
  skipped with a warning naming its problems, and `/reload` judges every binding again. Presets need
  `mineqtt:twin.bind` but not `twin.allowedEntities`: the broker ACL is their gate. A state topic is readable without
  an `acl.rawAllow` entry but never inside the statestream namespace, the twin's request traffic or this server's
  device topics; control needs the command topic in `acl.rawAllow` (e.g. `zigbee2mqtt/+/set`, `cmnd/+/POWER`), checked
  for the owner at every send, and commands carry typed values only.
- **Broker ACL.** On a broker shared with other clients, only MineQTT's user should publish
  `mineqtt/+/bridge/ha_request` and `ha_sync`, only Home Assistant's user `mineqtt/+/bridge/ha_response`, statestream
  and the catalog's `ha_statestream/_mineqtt/#`. The wiki has the lines
  ([Broker Setup](https://github.com/OddHero/Mineqtt/wiki/Broker-Setup#home-assistant-twin-topics)).
- **Inbound size.** MineQTT now takes inbound messages up to 256 KiB (0.6.0: 64 KiB) for the catalog's per-domain lists,
  and tells an MQTT 5 broker that maximum packet size; every other subscription keeps its own smaller cap (mirrors
  64 KiB, commands and Raw Links 16 KiB).
- **Migration note.** Binding a device removes its Home Assistant entities, and automations and dashboards using them
  stop working; only a disabled diagnostic *Binding* entity is left. This also happens when Home Assistant is offline
  during the bind (at its next start). Unbinding brings the entities back with the same unique_ids: Home Assistant
  2026.x restores their names, areas and labels; older versions create new registry entries and keep the disabled
  *Binding* entity, which you delete by hand (Settings → Entities, show disabled).
- **Downgrade.** 0.6.0 ignores a block's binding and drops it at the next save, ignores the `twin` config section, and
  does not know the Display Panel, so Minecraft removes placed panels from a world opened with it.

### Breaking changes

- **The statestream namespace is read-only in game.** Nobody (operators and the console included) can publish into
  `twin.statestreamBase` (default `ha_statestream/#`), even when `acl.rawAllow` covers it.
- **Every MineQTT server's `bridge/` level is reserved.** Publishing to `<baseTopic>/+/bridge/#` and
  `mineqtt/+/bridge/#` (for example `mineqtt/shared/bridge/…`) is refused for everyone in game, whatever the
  allowlist says. A Raw Link or a v1 block on such a topic stops following it, with the usual notice to its owner.
- **The explorer:** only admins (op level 3 or `mineqtt:admin`) may listen on the statestream namespace; everyone else
  sees only the size of `bridge/ha_request`, `ha_response` and `ha_sync` messages, and no statestream topic.
- **The Block Emitter no longer subscribes** to the statestream namespace, to any server's `bridge/ha_request`,
  `ha_response` or `ha_sync`, or to this server's device and group topics (a bound device's state names its entity).
- **No way back to 0.6.0 without losses.** 0.6.0 drops every binding at the next save and removes placed Display
  Panels from the world (see *Downgrade* under For server admins): back up the world before you update.
- **Binding removes a device's Home Assistant entities**, and automations and dashboards using them stop working until
  it is unbound; Home Assistant before 2026.x then creates new registry entries (see *Migration note* under For server
  admins).

### Known limitations

- The "no live updates" warning counts a catalog change only when the entity's state or catalog attributes changed
  (Home Assistant restarts alone never trigger it), so an entity outside the statestream include that changes and
  changes back within one catalog cycle (10 minutes) is not counted for that cycle.
- Redstone edges that come faster than Home Assistant applies a request can still leave an entity opposite to the
  redstone in rare bursts (8 edges 150 ms apart).
- Double presses and holds of the Wall Switch cannot be bound to Home Assistant yet, and requests of a Redstone Input
  or Wall Switch name no player in Home Assistant's logbook ("by a redstone signal").
- Presets cannot be triggers of a Redstone Input or Wall Switch, and a preset device's availability (last will) topic
  is not read, so an offline device keeps its last state. The Twin tab's preset button lists at most 40 presets of the
  block's kinds; others are bound by command.
- With several Home Assistants on one broker, the first one's start ends the cleanup of entities a binding removed
  while they were offline.
- On NeoForge, a `twin.allowedEntities` or `twin.haActionAllowlist` value in `mineqtt.toml` that is no list is reset to
  its default (`AUTO`, every entity in single player and LAN worlds) by NeoForge's config loader before MineQTT reads it; write lists.
- A catalog list over 256 KiB (about 900 labelled entities of one domain) is dropped by the transport and logged
  instead of being refused with a reason in `/mineqtt twin status`.

### For developers

- Six new checks (33 in all): `verifyStatestreamParse`, `verifyValuePath`, `verifyValueMap`, `verifyHaCalls` (which
  compares both blueprints with the code), `verifyMirrorNoLoop` and `verifyPresetLoad`. Three new client suites
  (`mirror`, `bind`, `display-panel`; 27 in all, two of them, `adventure` and `lamp_mount`, from the post-0.6.0 fixes).
- Client game tests take `MINEQTT_GAMETEST_BRIDGE` (bridge id) and `MINEQTT_GAMETEST_SUITES` (suite filter);
  `e2e/tools/run-gametests.sh` serialises client runs across worktrees, `docker/client-test/run.sh` runs them headless
  in Docker, and `e2e/lanes/lane.sh` gives each test lane a private broker and server. The e2e environment has a twin
  fixture for both Home Assistant versions and `tools/fake_bulb.py`, a fake zigbee2mqtt bulb.

## 0.6.0 (MineQTT 2)

0.6.0 is the first release of the rebuilt MineQTT, called "MineQTT 2" in the game and in the design documents (the
retired blocks' tooltips say so). It replaces 0.5.3 for Minecraft 26.2 on Fabric and NeoForge. Every block and item
keeps its id, so v1 worlds load and upgrade in place; read **Breaking changes** before you update a server.

### For players

- **Blocks are smart-home devices.** The Smart Lamp, Redstone Output, Redstone Input, Smart Plug, Presence Sensor,
  Climate Sensor and Wall Switch each become a device of their own, with a name, a state topic and, when the server
  allows it, a device in Home Assistant. Renaming a device keeps its Home Assistant entities and their history.
- **New device line.**
  - *Smart Plug*: on or off, switched by a right-click; Home Assistant shows an outlet.
  - *Climate Sensor* (the old Temperature Sensor; Humidity Sensors turn into it): temperature, humidity and
    illuminance worked out from the spot (biome, height, weather, roof, heat and water nearby, light).
  - *Wall Switch* (the old Light Remote): two buttons with press, double press and hold, for Home Assistant
    automations and for wires.
  - *Bridge Console* (the old Terminal): the bridge's connection, devices and groups in game.
  - *Smart Lamp*: effects (`pulse`, `candle`) and smooth transitions.
- **Device screens.** Sneak + right-click opens a device's screen: *General* (name, Home Assistant exposure, group),
  *Controls*, *Advanced* (the raw link: channel, private channel or typed topic, retain, QoS, payloads, and the
  device's wires), *Access* (owner and access mode) and *Diagnostics* (status, last messages, *Retry*, *Eject* of old
  slot items). A right-click uses a lamp, a plug or a switch; a name tag renames a device; renamed paper sets a raw
  link's channel.
- **The Configurator** (the old Cyberdeck) opens device screens from up to 16 blocks away and has four modes:
  *Configure*, *Link* (wire one device to another), *Group* and *Copy-Paste*. Sneak + right-click into the air steps
  the mode.
- **Wires** connect a device's output to another device's input on the server, without the broker: a lever on a
  Redstone Input switches a lamp also while MQTT is down, and an unloaded lamp or output catches up when its chunk
  loads.
- **Wireless redstone is retained.** A Redstone Input's raw link keeps its current level on the broker, so receivers on
  other servers and after a restart pick it up. Item and paper names that the server's allowlist does not cover as
  written map to the shared `mineqtt/shared/…` topics; only a topic an owner's v1 blocks already used stays as written,
  for that owner. A refused or moved link is explained in chat and on the screen.
- **Crafting.** Every block has a survival recipe behind one new component, the **Network Chip**, unlocked through
  the recipe book.
- **Renamed.** The blocks carry the names of what they are now (Smart Lamp, Redstone Output and Input, Presence Sensor,
  Climate Sensor, Wall Switch, Bridge Console, Configurator); their ids are unchanged.

### For server admins

- **Home Assistant integration**: device-based MQTT discovery with a *MineQTT Bridge* device per world, availability
  that follows the server (also after a crash, through the last will), a republish when Home Assistant restarts, and
  the cleanup of devices that are gone. **Home Assistant 2025.2 or newer is required**; 2025.2.5 and 2026.10.0 are
  tested. Dedicated servers discover only the devices an operator (or a holder of `mineqtt:ha.expose`) exposes
  (`ha.expose`); single player discovers every device.
- **Config v2** (`config/mineqtt.json` on Fabric, `config/mineqtt.toml` on NeoForge, `configVersion: 2`). A v1 file is
  migrated on the first start and kept as `.v1.bak`: `brokerUrl` becomes `broker` (a URI: `tcp://`, `mqtts://`,
  `ws://`, `wss://`), `clientId` is no longer used (the client id is `mineqtt_<bridgeId>`, generated per world), a
  default `baseTopic` becomes `mineqtt` and a customised one is kept. New keys: `protocol` (5 or 3.1.1), TLS,
  `bridgeId`, the discovery prefix and birth topic, `ha.expose`, `acl.rawAllow`/`acl.opsBypassAllowlist`/
  `acl.explorerBrokerWide`, `blockPlacer.enabled`, `limits.maxDevicesPerPlayer`, `limits.maxWiresPerPlayer` and
  `server.keepTickingWhenEmpty`. The password can come from `MINEQTT_PASSWORD`.
- **Security**: one topic allowlist for every topic a player enters (`homeassistant/#`, the bridge namespace and `$SYS`
  are always refused), owners by UUID and per-device access modes (private, claim, public; Flan claims are honoured),
  a server-side check of every client packet, item data from creative players treated as untrusted, and optional
  per-player device and wire limits.
- **Empty dedicated servers**: MQTT is handled between the ticks of a server paused by `pause-when-empty-seconds`.
  `server.keepTickingWhenEmpty` keeps the whole server ticking instead.
- **Commands** (`/mineqtt`, op level 2 unless noted): `status`, `reconnect`, `devices …` (list, info, rename, set,
  setting, raw, action, expose, access, claim, retry, eject, remove, prune), `group …`, `wires …`, `discovery …` and
  `publish` (op level 3). Every screen action has a command twin.
- **Broker ACL**: the wiki's [Broker Setup](https://github.com/OddHero/Mineqtt/wiki/Broker-Setup) has a Mosquitto
  ACL for the MineQTT user. Retained messages on `mineqtt/shared/#` and on
  raw-link topics need write-with-retain for every server that shares them.
- **Migration notes**: v1 lamps keep their Home Assistant entities and devices (entity id, history, area, device
  automations), also in chunks nobody visits and when Home Assistant is offline during the upgrade; lamps that shared
  one topic become one group. v1's per-topic lamp state file is imported once and left untouched. v1's retained
  `Offline` status message is cleared once, so the MineQTT user needs write access to that topic for the first start.
  Load every v1 world once with a MineQTT 2 release (0.6.0 or later): the converters and the retired blocks will be
  removed in MineQTT 3.

### Breaking changes

- **The default broker is empty**: MQTT stays disabled until `broker` is set. The old public default
  `test.mosquitto.org` is not carried over.
- **Devices publish under `mineqtt/<bridgeId>/…`** instead of the v1 topics. The topics in the old item slots keep
  working as raw links; topics that the allowlist does not cover go to `mineqtt/shared/…` for new links.
- **`/mineqtt publish` needs op level 3** and is checked against the topic allowlist.
- **Retired blocks**: the Dynamic Publisher and the Topic Relay do nothing any more; break them for a Network Chip and
  their items. The Static Publisher turns into a Redstone Input (rising edges, not retained) and the Humidity Sensor
  into a Climate Sensor. Neither the Static nor the Dynamic Publisher passes a redstone signal on any more.
- **No more item slots on devices**: what they held is kept and handed out by *Eject* or dropped when the block
  breaks. Hoppers, droppers and item pipes no longer move items into or out of the slots the other MineQTT blocks keep.
- **The Light Remote's light-command screen and the explorer's Remote tab are gone**; a v1 Light Remote is wired to the
  lamps it controlled when they are the same owner's, otherwise its topic is dropped (logged).
- **One state per topic is gone**: lamps are devices of their own; lamps that must share a state form a group.
- **Removed**: the zombie goal and item portal features, `/mineqtt setgoal` and `/mineqtt enablegoal`, and their
  config keys (ignored when read).
- **New block names in payloads**: a Block Sensor in name mode and its full-data `name` publish the new display names
  (`Smart Lamp` instead of `RGB LED Subscriber`, and so on, see the wiki's
  [Upgrading from v1](https://github.com/OddHero/Mineqtt/wiki/Upgrading-from-v1#renamed-blocks)), and a Block Emitter
  no longer finds a MineQTT block by its old name. Block ids are unaffected.
- **Owners**: a v1 owner name is resolved to the player's UUID; a player who only shares the name is no owner.
- **Worlds of v2 test builds**: a raw link saved with `retain=false` by such a build becomes retained, and a test build
  that already migrated v1 lamps left an empty "MineQTT RGB LED" device per lamp in Home Assistant (see
  [Worlds from v2 test builds](https://github.com/OddHero/Mineqtt/blob/main/.github/development.md#worlds-from-v2-test-builds)).

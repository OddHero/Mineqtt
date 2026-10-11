# Changelog

## 0.7.0 (MineQTT 2, Milestone 3: mirror your house)

0.7.0 brings real Home Assistant entities into Minecraft and lets Minecraft switch them back, safely, also on a
public server: a block can *mirror* an entity (the Home Assistant twin), a Redstone Input or a Wall Switch can
*trigger* one, and a new Display Panel shows any value. It is for Minecraft 26.2 on Fabric and NeoForge and replaces
0.6.0. Worlds and config files of 0.6.0 load unchanged: apart from the breaking changes below, nothing behaves
differently until you bind a block. The setup is in the README, [Mirror your house](README.md#mirror-your-house).
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
  `include:` list), two labels and two automation blueprints, all in the README:
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
  chain: it is bounded by the rates and ended by the pause, as the README explains.
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
  and the catalog's `ha_statestream/_mineqtt/#`. The README has the lines.
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
- **Broker ACL**: the README has a Mosquitto ACL for the MineQTT user. Retained messages on `mineqtt/shared/#` and on
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
  (`Smart Lamp` instead of `RGB LED Subscriber`, and so on, see the README's "Upgrading from v1"), and a Block Emitter
  no longer finds a MineQTT block by its old name. Block ids are unaffected.
- **Owners**: a v1 owner name is resolved to the player's UUID; a player who only shares the name is no owner.
- **Worlds of v2 test builds**: a raw link saved with `retain=false` by such a build becomes retained, and a test build
  that already migrated v1 lamps left an empty "MineQTT RGB LED" device per lamp in Home Assistant (see the README,
  "Upgrading from v1").

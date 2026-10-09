# Changelog

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

# MineQTT v2 architecture

How MineQTT is put together since v2 (milestones M1 and M2, version 0.6.0), and why. The full design, with every decision, open question and
roadmap item, is not part of this repository. This file is the map for
anyone changing the code; read it before touching the MQTT core, a device type or the migration code.

## What v2 replaced

v1's rules were workarounds for its own architecture. They no longer apply:

| v1 rule | v2 |
|---|---|
| "No retain anywhere": status, state and discovery not retained | Everything MineQTT publishes **about itself** is retained: discovery, bridge and device availability, state, bridge info. **Commands are never retained**, and a retained message on a device's `/set` topics is never taken, so a stale command on the broker cannot change a device at startup. Raw Link inputs that keep v1 parity (Redstone Output, Smart Plug) and the unmigrated v1 blocks on `LegacySubscriptionFacade` still apply retained values, so their topics need broker ACL protection. (v1's discovery was in fact published retained; the old docs said otherwise.) |
| Discovery with `retain: false` "so offline changes are ignored" | Discovery declares `retain: false` for the *commands* HA sends. The config message itself is retained. A retained command on a `/set` topic is dropped (MQTT 5: `DO_NOT_SEND` subscriptions; both protocols: any inbound message with the retain flag). |
| "One state per topic": every lamp on a topic shares one state in `mineqtt_topic_states.json` | Identity and state are **per block**. Lamps that must share a state form an explicit **group**. The v1 file is imported once (legacy groups) and never written again. |
| `Thread.currentThread().getName().contains("Server thread")` instead of `level.isClientSide` | Client block entities never touch MQTT. Device attach is driven by `setLevel(ServerLevel)`, and all MQTT handlers run on the server thread because the inbound queue is drained in `SERVER_PRE`. The v1 problem came from the static client calling block entities from the MQTT thread and from client BEs registering subscriptions. |
| One static HiveMQ client, created when the overworld loads, topics looked up by received topic | One `MqttService` per server run, routing through a topic trie with full `+`/`#` support. |
| Hostname-based client id `minecraft-<host>` | Client id `mineqtt_<bridgeId>`; the bridge id is generated once per world and stored in the registry. |

## Overview

```
                       server thread                                    HiveMQ threads
 ┌──────────────────────────────────────────────────────────┐      ┌───────────────────────┐
 │ blocks/ (BEs)  ── DeviceHost ── DeviceLink ─┐             │      │ Mqtt5/Mqtt3 adapter   │
 │ sensor/emitter ── LegacySubscriptionFacade ─┤             │      │  global publish flow  │
 │ Cyberdeck, /mineqtt, C2S ── TopicAcl ───────┤             │      └──────────┬────────────┘
 │                                             ▼             │                 │ receive()
 │  DeviceRuntime ── DeviceRegistry (devices.nbt)            │                 ▼
 │  DiscoveryPublisher, BirthWatcher, BridgeDevice           │       InboundQueue (bounded,
 │                     │                                     │        ordered, byte budget)
 │                     ▼                                     │                 │
 │  MineqttRuntime ── ReplaceableMqttService ── HiveMqttService ◄── drainInbound() (SERVER_PRE)
 │                                   │  TopicTrie, LocalLoopback, echo window         │
 │                                   └─ OutboundBuffer ── flushOutbound() (SERVER_POST) ──► broker
 └──────────────────────────────────────────────────────────┘
```

## Packages (`common/src/main/java/art/rehra/mineqtt/`)

| Package | Contents |
|---|---|
| `mqtt/core` | The transport. **Minecraft-free**: only the JDK, Gson and HiveMQ (`verifyCoreIsolation` fails the build otherwise). `BrokerConfig` (URI parsing), `HiveMqttService` with `Mqtt5ClientAdapter`/`Mqtt3ClientAdapter`, `TopicTrie`, `InboundQueue`, `OutboundBuffer`, `LocalLoopback`, `TopicAcl` (rules as data). |
| `runtime` | `MineqttRuntime` (one per server run), `ServerHooks` (lifecycle wiring), `ReplaceableMqttService` (survives config reconnects), `TopicScheme`, `Slf4jMqttLog`. |
| `flow/core` | The flows seed (M2 plan §3, [Flows](#flows)). **Minecraft-free**: only the JDK, Gson and `security.TokenBucket` (`verifyCoreIsolation` scans it, and keeps `TokenBucket`/`RateLimit` JDK-only). `PortType` (the kind of a port: bool, level, number, enum, text, color, light, json, event; strict validators), `Coerce` (the coercion table, the bool spellings and the text sanitiser the device codecs use), `Endpoint`/`Wire`/`Mapping`/`Scope`, `FlowMessage`, `PortSpec` and the `MessageBus`. |
| `device` | `DeviceType`/`Field`/`Setting`/`Action`/`EventSpec`/`LinkSetting` declarations, derived `Port`s, `DeviceView` (a screen's snapshot), `DeviceLink` (per-BE state), `DeviceRuntime` (attach, routing, groups, pending commands), `DeviceRegistry` + `RegistryStore`, `RawLink`, `Slugs`, `ValueCodecs`. `device/types` holds `SmartLamp`, `RedstoneOutput`, `RedstoneInput`, `PresenceSensor`, `SmartPlug`, `ClimateSensor`, `WallSwitch`. |
| `ha` | `DiscoveryPublisher`, `BirthWatcher`, `BridgeDevice`, `LegacyDiscoveryMigration`, `GhostCleanup`, `Ha`/`HaEntity` component builders. |
| `legacy` | `LegacySubscriptionFacade` (the Block Sensor and the Block Emitter, the two v1 blocks that are no devices yet), `LegacyNbtV1` (v1 NBT reader), `LegacyTopicStateImport` (v1 topic-state file), `LegacyConverterBlock` (retiring blocks that turn into their successor, M2-19), `DecommissionedBlock` and `DecommissionedBlockEntity` (retired blocks that only wait to be broken for their parts, M2-21). |
| `security` | `AclContext` (player → ACL subject), `C2SGuard` + `TokenBucket`/`RateLimit`, `DeviceAccess`, `AccessMode`. |
| `registry` | `Catalog` (every block and item: craftable, creative, converter or decommissioned; the creative tab's order; a plain id table that `verifyCatalog` reads under a bare bootstrap), `MineqttDataComponents`. Recipes live in `data/mineqtt/recipe/` (26.2 format; every block and the Configurator need the Network Chip) and unlock through `data/mineqtt/advancement/recipes/`. |

## MQTT core

- **One service per server run.** `MineqttRuntime.start` (`SERVER_STARTED`) loads the registry, imports the v1 file,
  then connects. `runtime.mqtt()` is a `ReplaceableMqttService`: the same object for the whole run, so subscriptions and
  connect listeners survive a `/mineqtt reconnect` or a config change. Never cache the `HiveMqttService` behind it.
- **Protocols.** `protocol: 5` (default) or `3.1.1` is a plain config value. There is no auto-detection; when the first
  MQTT 5 connect fails the way a 3.1.1-only broker makes it fail (closed connection, undecodable CONNACK, or Mosquitto
  1.5's `UNSUPPORTED_PROTOCOL_VERSION` CONNACK), the connection state carries a hint to set `protocol: 3.1.1`.
- **Transports.** `tcp://`, `ssl://`/`mqtts://`, `ws://`, `wss://` (`BrokerConfig.parse`). TLS validates against the JVM
  trust store or `tls.caFile`. HiveMQ runs on Minecraft's Netty 4.2: the shipped jars leave `io/netty` out, and the dev
  runtime classpaths exclude HiveMQ's Netty 4.1 too (a stray 4.1 `netty-codec` broke WebSocket connects in dev).
- **Inbound.** HiveMQ's global publish callback enqueues into a bounded `InboundQueue` (entry count and byte budget,
  drop-oldest, counted). `drainInbound` runs in `SERVER_PRE` with a time and message budget and dispatches through the
  `TopicTrie`. Handlers therefore always run on the server thread, in broker order. Per handler: retained messages are
  dropped unless the subscription accepts them, payloads above its cap are dropped.
- **Covering subscriptions.** Broker subscriptions are ref-counted per filter. A filter covered by another registered
  filter is served by that one's broker subscription (Mosquitto sends one copy per matching subscription). Filters that
  only partly overlap still get broker-dependent duplicates.
- **Own echo.** Raw links use no-local plus an explicit `LocalLoopback`: a publish is delivered to matching local
  handlers at once (also with the broker down), never to the device that published it. On MQTT 3.1.1, which has no
  no-local, a short digest window of local publishes recognises and drops the broker's echo. An input that loads after
  this server stored a retained level gets it from the broker, not locally: Mosquitto 2 sends a client its own retained
  message despite no-local (M2 F4, design §12 Q12), so there is no local replay of retained levels. A strict reading of
  MQTT 5 [MQTT-3.8.3-3] forbids that delivery, so this relies on Mosquitto's lenient behaviour.
- **Outbound.** Every publish is an `Outbound` with a delivery class: `STATE` (coalesced per topic, deduplicated against
  the last bytes sent), `EDGE` (raw outputs and events, never coalesced) and an `OfflinePolicy` (`COALESCE`, `KEEP` in
  the outbox, `DROP`). `flushOutbound` runs in `SERVER_POST`. After a reconnect the outbox replays first.
- **Paused server (M2 F5).** An empty dedicated server stops ticking after `pause-when-empty-seconds`: `tickServer`
  returns before `SERVER_PRE` on both loaders, but the main loop still runs queued tasks between the ticks (that is why
  RCON answers). `PausedServerPump` posts one task there that runs the tick's work (`tickStart` without the tick stamp,
  `tickEnd` with the flush). It is woken by `MqttService.onInboundAvailable` (client threads: once per burst of messages,
  CONNACKs and acknowledgements, re-armed by each drain) and by its daemon thread "MineQTT pause wake", which every
  250 ms wakes while `shouldPump` holds (no tick for 250 ms or two tick lengths, so a slow `/tick rate` is no pause) and
  work is pending (`MqttService.hasPendingWork`, plus a volatile flag the server thread recomputes at every tick's end
  and that commands, config reloads, attaches, chunk loads and held states set). Posts use
  `server.schedule(server.wrapRunnable(...))`, never `execute`, which runs a task in place on the server thread. Block
  entity tickers, scheduled ticks and light wait for the unpause. The flows message bus drains in the pump too ([Flows](#flows)).
- **Keep ticking (M2 F6).** The opt-in `server.keepTickingWhenEmpty` (default false) makes `MinecraftServerPauseMixin`
  (in the `server` array of `mineqtt.mixins.json`, so clients never load it) reset `emptyTicks` at the head of every
  `tickServer` of a dedicated server, so the countdown to the pause never ends and a config reload applies at the next
  tick. Turning it off starts a full `pause-when-empty-seconds` countdown with vanilla's "pausing" line and autosave.
  `DedicatedServer.pauseWhenEmptySeconds()` is not touched, so it and the JSON-RPC management API keep reporting
  `server.properties`. `MineqttRuntime.pauseWhenEmptySeconds()` applies the opt-in itself (0 while it overrides), so the
  pump never runs.
- **Lifecycle quirks of HiveMQ 1.3.7** (each one cost a bug): a clean session ends the global publish flow at every
  disconnect, so the receiver is re-attached on every CONNACK; `disconnect()` cannot cancel a scheduled reconnect, so
  reconnects wait on a gate that `close()` completes; `close()` publishes the bridge's `offline` and waits up to 2 s for
  the PUBACK before disconnecting.

## Topics, retain and QoS

`<base>` defaults to `mineqtt`; `<bridgeId>` is generated once per world (`slug(levelName)_<4 hex>`) unless the config
sets it. Both the client id and the will are known before CONNECT, so two worlds on one broker never collide.

| Topic | Retain | Notes |
|---|---|---|
| `<base>/<bridgeId>/bridge/state` | yes | `online`/`offline`; also the will |
| `<base>/<bridgeId>/bridge/info` | yes | version, mc, loader, world, worldUuid, protocol, devices |
| `<base>/<bridgeId>/bridge` | yes | telemetry, at most every 10 s |
| `<base>/<bridgeId>/bridge/request/republish` | n/a | inbound; republishes discovery and state |
| `<base>/<bridgeId>/<slug>` | yes | device state JSON |
| `<base>/<bridgeId>/<slug>/availability` | yes | `online`/`offline` |
| `<base>/<bridgeId>/<slug>/set`, `…/set/<field>` | **never** | commands; four wildcard subscriptions serve every device |
| `<base>/<bridgeId>/<slug>/action` | no | events (Redstone Input pulse, Wall Switch press, double press and hold) |
| `<base>/<bridgeId>/group/<slug>` (+ `/set`) | yes / never | group state and commands |
| `homeassistant/device/mineqtt_<uuid32>/config` | yes | device-based discovery |
| raw topics (Raw Link) | per device: Redstone Input yes; Presence and Climate Sensor no; never for toggles, rising edges (converted Static Publisher) and events (Wall Switch) | v1 payload grammar, QoS 0/1 per device; the link's `retain` overrides the type default |

Unloaded devices follow their type's `UnloadedPolicy`: `QUEUE` devices (lamp, output, plug, groups) stay `online`, a command
is stored as the pending target and applied at the next attach; `UNAVAILABLE` devices (sensors) go `offline` on detach,
and every CONNACK forces unattached `UNAVAILABLE` records and faulted devices `offline`, so a retained `online` from a
crash never survives.

## Devices

- **Declared once.** A `DeviceType` lists its fields (with value codecs, access and a `PortType` kind), its HA
  components, its settings, actions and events, its unloaded policy, minimum state interval and deadband. Discovery
  payloads, command parsing, the queued-command preview and the device's ports are generated from it.
- **Ports (M2-4).** Readable fields and declared events are out-ports, writable fields (settings marked as such) and
  declared actions in-ports; field, action and event keys are frozen port ids, unique per direction (the Output's
  `state` is its on/off field out and its switch-state action in). `build()` refuses an HA event or device trigger
  without its `EventSpec` (a trigger also needs the event attribute it reads, with its subtype among the attribute's
  values), a button or notify without its `Action`, and a switch or light without a `state` in-port.
  A device may only publish declared events (`DeviceType.eventProblem`; `publishEvent` throws otherwise). Settings and
  `LinkSetting`s (name, expose, access, Raw Link channel/topic/retain/QoS/payloads, with design §8.5's bounds) parse a
  client's NBT strictly through the kind: out of range is refused, while HA's `/set` stays lenient (clamped).
  `/mineqtt devices info` lists the ports.
- **Identity per block.** Each device BE holds a `DeviceLink` with a random UUID (`mineqtt.id`), name, slug, owner,
  access mode, expose flag, Raw Link and state. Discovery object id `mineqtt_<uuid32>`, unique_ids
  `mineqtt_<uuid32>_<key>`; renaming changes the slug and topics but never the unique_ids.
- **Server-only lifecycle.** `DeviceBlockEntity.setLevel(ServerLevel)` schedules the attach one tick later (covers
  placement, chunk load, `/clone`, structures). `setRemoved` detaches; `preRemoveSideEffects` plus a block-state check
  in `setRemoved` destroys. Attach merges the BE with its registry record (registry wins for name, slug, group and, for
  a registered device, expose, which `/mineqtt devices expose` may set while it is unloaded; BE wins for state and
  owner), applies pending commands, publishes availability, state and discovery.
- **Attach guard** (M2 plan D7). Each device's attach (pending command, power-on, Raw Link open included), the
  startup power-on of its record and its prune check run in isolation (`AttachQueue.drain`/`isolate`): a
  `RuntimeException` is logged once with its stack trace (the same failure again as one line), the attach is undone and
  the device is faulted by position (`DeviceRuntime.faults()`) and `offline`; the other devices carry on. Republish and
  every CONNACK keep a faulted record `offline`. It is tried again when its chunk loads again or with
  `/mineqtt devices retry <slug|x y z>` (queued for the next tick); a successful attach ends the fault, breaking the
  block forgets it. A failed attach hands back what it used up, so the retry starts from the same data: v1 data awaits
  adoption again (saved as v1 data, adopted as trusted at the retry), a placement stays one, a pre-created v1 record
  keeps its v1 topic, and a record the attempt created is dropped (the block entity keeps the id it was given). A retry
  does not reapply a pending command the attach took, nor power on again a device whose power-on ran. `status`,
  `devices info` and `devices list` show faulted devices.
- **Registry.** `<world>/data/mineqtt/devices.nbt`, written atomically by `RegistryStore` (not `SavedData`: a `null`
  `DataFixTypes` wipes it on the second start). It holds the bridge id, world UUID, records (type, position, slug,
  pending command, last state, discovery hash, legacy unique_id), groups and migration flags. Saved when created, on
  overworld saves when dirty, and at `SERVER_STOPPED`. A structural change (device or group added or removed, slug,
  name or membership changed, owner literal added) is saved at the end of its tick (`tickEnd`, also in a paused
  server's pump), at most once a second counted from the last save; a failed one is tried again a second later (the
  error is logged once, then the recovery). Everything else (states, pending commands, discovery hashes,
  owner/access/expose copies, migration flags) waits for the autosave. A device broken in a loaded chunk is removed at
  once, but the structural saves keep its record (with its membership) until the next level save, because vanilla saves
  the changed chunk only some seconds later (at most once every 10 s per chunk); a crash in between restores block and
  record together.
- **One command path** (M2-5, M2 plan §3.8 #2). `DeviceRuntime.command(target, json, source, origin)` is how every
  command reaches a device: HA's `/set` and `/set/<field>`, group commands and joins, Raw Link inputs (also a legacy
  group's v1 topic), `/mineqtt devices set`, the device screens and their command twins, and wires; the timers will use it too (M4). The pure
  `DeviceRuntime.route` picks the route: a loaded device applies (`applyCommand`, then the state is published), an
  unloaded `QUEUE` device queues through `DeviceType.preview`, a group fans out, and the rest drops (an unloaded
  sensor, an unknown type). A group's members always see `CommandSource.GROUP`. Only the attach applies a queued
  command itself, as the second half of the `QUEUE` route. No block entity calls another or publishes MQTT directly.
- **Device config protocol** (M2-6). `DeviceConfig` (pure) validates every player change: a key is a `Setting` of the
  type (CONFIGURE; it reaches the device as the command `{"<key>": value}` through `DeviceRuntime.set` → `command`) or a
  `LinkSetting` with its gate (CONFIGURE, EXPOSE = CONFIGURE + `mayExpose`, ACCESS = owner or op); an action must be
  declared (USE). Values are checked strictly against the kind and bounds. `DeviceRuntime.configure`/`action` apply
  them for the screen's `DeviceConfigPayload(pos, key, Tag)` and `DeviceActionPayload(pos, action, value)` (both behind
  `C2SGuard.requireDevice`) and for `/mineqtt devices setting|raw|action` (op 2, `Grants.ALL`). Name and expose change
  in the registry, loaded or not; access and the Raw Link only while the block is loaded. A Raw Link channel is resolved
  as v1's item and paper topics were (`RawTopics.resolve`); a private channel (`raw.private`, M2-12) resolves into the
  owner's private subtree and sets the link's private mode, which a plain channel, a typed topic or a removal clears; a
  typed topic must pass `TopicAcl` for the editor (op bypass included) and for the owner, and is stored with no channel
  and `resolvedV` set, so no attach resolves it again. Every edit ends in `rawEdited`/`rawPayloadsChanged` with the old
  link (`RawRetain`). A refusal is told to the player in chat (`mineqtt.device.refused`). A device's screen opens with
  its `DeviceView` (built per player, with their `Grants`) in the menu's open data (M2-7), and every open screen of a
  device that changed gets a `DeviceViewPayload` at the end of the tick; the client keeps it on
  `TabbedMqttMenu.deviceView()`. An access change closes the open screen of a player who lost USE and reopens it for
  one whose CONFIGURE changed, since the screen's controls go by what the player could do when it opened. A Raw Link
  taken away turns a Redstone Output off (`DeviceHost.rawRemoved`).
- **Render-only update tag** (M2-7, plan D12). A device block entity's `getUpdateTag` is `RenderTag.updateTag(renderData())`:
  one `mineqtt_render` compound (the Smart Lamp's `{rgb, brightness, on}`, empty for the others), never the owner,
  topics, Raw Link or legacy items. `DeviceBlockEntity.loadAdditional` dispatches with `RenderTag.dispatch`: a
  `mineqtt` compound is v2 data, a render compound alone a client's render update (`loadRender` replaces only what it
  carries; the `DeviceLink` stays as it was), neither is v1 data, so a client never runs the v1 migrator on an update
  packet. The device screens read the `DeviceView` only. Blocks that are no devices (the legacy blocks and the timers)
  still send `saveWithFullMetadata`, which their screens read.
- **Lamp transitions and effects** (M2-25). A light command's `transition` (seconds, `ValueCodecs.transitionTicks`) is no
  state: `LightState.merge` and so the queued preview ignore it. The lamp's `light` (saved, published, in the view) is
  the target at once, so one state goes out per command; the block shows a transient `LightTransition` from what it
  showed, which `RgbLedBlockEntity.ServerTicker` steps every `SmartLamp.TRANSITION_STEP_TICKS` (2) ticks, sending the
  clients a render update only when the light level, colour or on changes (always the last step). A fade starts only
  where that ticker runs (`ServerLevel.isPositionTickingWithEntitiesLoaded`, the rule of `LevelChunk#isTicking`): a lamp
  in a loaded chunk outside the simulation distance shows the target at once. A command naming the fade's own target
  ends it (no transition) or restarts it from where it stands, without a state. Unloading or saving mid-fade keeps the
  target. `effect` is part of the light (`LightEffect`, `off` = none) and of the render tag (only while
  set); the client ticker animates the tint with `SmartLamp.effectFactor` (pulse in step on every lamp, candle per
  position, in tenths) and redraws the block's chunk section when it moved a step, at most every 2 ticks. The block
  light ignores effects.
- **Devices without containers** (M2-12, design §11.2 item 2). `DeviceBlockEntity extends BlockEntity implements
  DeviceHost, TabbedScreenHost` is the block entity of every device: the attach hooks, the render tag, `openScreen`
  (`DeviceMenu`), `accessChanged`, the editor of the last 30 s (`recentEditor`, set by `editRaw` for the screen and the
  paper) and the last tab (`DeviceLink.lastTab`). The subclasses (`RgbLedBlockEntity`, `RedstoneEmitterBlockEntity`,
  `PublisherBlockEntity`, `MotionSensorBlockEntity`) keep their state and read and write it beside the link
  (`saveDevice`/`loadDevice`). The compound is version 3: `DeviceLink.load` migrates M1's version 2, whose block kept
  `Items`, `privateMode` and `lastTabId` beside the compound, purely: the slot items become `legacyItems` (with their
  slots, `ItemStackWithSlot`), the private mode becomes the link's `private`, the tab its `lastTab`; the next save
  leaves the old keys out. v1 data keeps its items as legacy items too and is written back as it was read
  (`DeviceLink.saveV1`) until the attach adopts it. The Presence Sensor's spawn eggs (slots 2 to 6) become its `filter`
  setting (`LegacyNbtV1.eggFilter`, at most 8 entity ids) and stay among its legacy items. Breaking a device
  (`preRemoveSideEffects`) drops its legacy items; the Diagnostics tab's Eject (`DeviceEjectPayload`, CONFIGURE) puts
  them into the player's inventory, `/mineqtt devices eject <device>` drops them at the block; a device whose v1 data
  still waits for adoption ejects nothing. A placement (`DeviceBlockEntity.placedBy`, from `BaseMqttBlock.setPlacedBy`)
  drops copied legacy items with the copied identity and resets what the copy did (plan D14: output power 0, lamp off,
  sensor count 0, input signal 0), keeping its settings and Raw Link. `BaseMqttBlockEntity` is now only the legacy
  blocks' and the timers'; `BaseMqttBlock.click` and `SetActiveTabPayload` take either through `TabbedScreenHost`.
  Comparator and hopper access to the four devices, to the Smart Plug since M2-18 and to the Climate Sensor since M2-19,
  is gone (neither was documented).
- **Generated device screen** (M2-8, design §6.9, plan §3.8 #8). A device opens a `DeviceMenu`
  (`ui/device`): the shared `MQTT_TABBED_MENU` built for the device's block entity rather than a container, whose tabs
  are `DeviceTabs.forType(type)`; since M2-12 the menu has no slots, and since P1 not the player's inventory either
  (`TabbedMqttMenu.hasPlayerInventory()`), so its screen is the short panel `GUI_HEIGHT_NO_INVENTORY` (105 px; the tabs'
  content ends by y 100) and its title row names the device (`DeviceMenu.title()`, from the latest view). Legacy blocks
  and the Configurator keep the inventory on the 192 px panel, titled with the active tab. A view puts a note such as
  "Read only" at the right of the title row through `MqttTabView.titleNote()`, and the title makes room for it. A generated tab's controls are
  `DeviceTabs.Control`s taken from the declaration (key, `PortType`, `LinkSetting.Gate`): the kind picks the widget and
  the bounds, the gate against the view's `Grants` decides read-only, and the client sends `DeviceConfigPayload` with
  the key, which `DeviceConfig` validates against the same declaration (`verifyDeviceTabs`). The General tab has the
  name (Enter or Save; live slug and topic preview), the topic with a copy button, the group and the Expose button
  (`expose`, gate CONFIGURE plus `AclContext.mayExpose`, as `/mineqtt devices expose`). `DeviceView.Registration` carries
  what only the bridge knows: the topic root, whether Home Assistant discovers the device now, and its group's name.
  Open screens of a device are found with `TabbedMqttMenu.isFor(blockEntity)`, so a device without a container keeps
  getting its views.
- **Controls tab** (M2-9, design §6.9, §8.7). `ControlsTab.controls(type)` is one `DeviceTabs.Control` per in-port of
  the type: actions first (gate USE, sent as `DeviceActionPayload`), then commands to the other writable fields (gate
  USE, sent as `DeviceConfigPayload`: the Output's `power`, the Lamp's `light`), then the settings (gate CONFIGURE). The
  widget comes from the kind: BOOL a switch that sends ON or OFF, LEVEL and whole numbers a slider, ENUM a cycle
  button, LIGHT the light picker (brightness, hue, saturation and white, `LightControl`), an event action a button, TEXT
  a text field with Set (M2-12, the Presence Sensor's mob filter; Enter or Set sends it, the field then shows the text
  the server keeps). A type without in-ports (Redstone Input) has no Controls tab; more than five rows of controls fail
  `verifyDeviceTabs` rather than a screen. The C2S guard asks the permission a `DeviceConfigPayload` key needs
  (`DeviceConfig.permission`: USE for a command, CONFIGURE for anything else, unknown keys included), then
  `DeviceConfig.parse` checks the key's gate and its port strictly; a light command takes only `state`, `brightness`,
  `color` `{r,g,b}` and `color_temp_kelvin` (`LightState.commandProblem`) and reaches the lamp as sent; `build()`
  refuses a writable inline field that is no light, whose keys nothing would check. The view sends at most one pick
  every 100 ms (the guard allows 20 payloads a second) and the last one always (the light picker's partial commands
  merge, `LightControl.merge`), and a control keeps what the player picked for 750 ms before it follows the views
  again. The lamp's old Status tab is gone.
- **Advanced tab** (M2-10, design §6.9, §7.4). Every device has one, after Controls. `AdvancedTab.controls(type)` is
  one `DeviceTabs.Control` per Raw Link link setting the type has (`raw.channel`, `raw.private`, `raw.topic`, an
  output's `raw.retain` and `raw.qos`, custom `raw.on`/`raw.off`; gate CONFIGURE), checked like the other tabs by
  `verifyDeviceTabs`. The view's one link field sends `raw.channel` (Channel mode, resolved for the owner by
  `RawTopics.resolve`), `raw.private` (Private mode, M2-12: the channel in the owner's private subtree, v1's private
  mode) or `raw.topic` (Topic mode, judged for the editor and the owner, the operator bypass included);
  `AdvancedTab.Mode.of(view)` opens a link without a channel in Topic mode and a private one in Private mode, and the
  same channel in the other of Channel and Private mode is a change to save (`Mode.changes`). Retain shows the override or the type's default (`AdvancedTab.retain`), QoS is a
  0..1 slider, and at the foot the *Links* button to the device's wires (M2-16, below). A change the server refuses from any
  device screen is told in chat and sent back as `DeviceRefusedPayload` (`DeviceMenu.refusal()`), right after a fresh
  view, which the Advanced tab shows in place of the topic until it sends again or a view arrives with another Raw Link. A renamed paper used on a device with a Raw Link
  (`BaseMqttBlock.click`, `AdvancedTab.paperChannel`) goes through `DeviceRuntime.paperChannel`, the same
  `configure(raw.channel)` path with the player's grants (`raw.private` for a device in private mode), and answers in
  the action bar; it is not consumed.
- **Interaction grammar and Configurator** (M2-13, design §7.4). `blocks/Interaction.decide(sneaking, Held, type)` is
  the pure table `BaseMqttBlock.click` carries out (USE needed for anything but PASS): the Configurator opens the
  screen; sneaking opens it, except with a block item (PASS, vanilla places it); a block that is no device opens it;
  a renamed paper fills a device's channel (`paperChannel`), a named name tag renames it (`DeviceRuntime.nameTag`:
  `configure(name)` with the player's grants, answered in the action bar); a type with `DeviceType.use()` (declared
  with `Builder.use(actionKey)`, an action that toggles or fires; the Smart Lamp's `state`) performs it
  (`DeviceRuntime.use`: `action(key, Action.handValue())`, so it goes through `command`); else the screen opens.
  `CyberdeckItem.use` (the Configurator) steps its `ConfiguratorMode` (component `mineqtt:configurator_mode`) when
  sneaking, else picks a block up to `C2SGuard.CONFIGURATOR_RANGE` (16) and opens a device's screen, or the explorer
  when it hits no MineQTT block. `C2SGuard.reaches(player, level, pos, slack)` is the reach of both the guard (slack 1)
  and `DeviceMenu.stillValid` (slack 4): same dimension, and the interaction range or, with a Configurator in either
  hand, 16 blocks where `Level.mayInteract` allows (spawn protection, world border: vanilla asks it before a click
  within reach, never before a use into the air, so `configureAtRange` asks it too). A hand without anything of meaning
  lends the other hand's Configurator (`Held.of(player, hand)`). A spectator only looks: `click` opens the screen
  whatever they hold and never passes (vanilla's spectator branch would open the block entity's menu unchecked),
  `DeviceAccess.grants` gives them nothing and `C2SGuard` drops their payloads (`SPECTATOR`). The explorer (`CyberdeckMenu`) keeps its topic slots only while open (`clearContainer` on close);
  listening and private mode live in `CyberdeckSessionManager` and come with the open data. The old `listening`,
  `private_mode` and `inventory` components stay registered, deprecated, and `CyberdeckItem.inventoryTick` strips
  them, giving the slots' items back (`CyberdeckDataUtil.stripDeprecated`).
- **Access and Diagnostics tabs** (M2-11, design §6.9, §8.7). Every device has both, after Advanced. Access shows the
  owner (`DeviceView.Ownership`: nobody, known, or a v1 name not verified yet; whether a claim mod decides claim trust)
  and the `access` link setting as a cycle gated ACCESS: `DeviceAccess.grants(op, owner, mode, claimTrust, mayExpose)`
  gives the access grant only to the owner or an operator, so no mode locks the owner out. A device without an owner
  is claimed by a player with CONFIGURE (`DeviceAccess.mayClaim`) through `DeviceClaimPayload` and
  `DeviceRuntime.claim` (`DeviceLink.claim`). Diagnostics shows `DeviceView.Diagnostics`:
  the device's UUID, its availability (online, no broker, faulted with the fault, not running), the last MQTT message
  in and out (topic, size, age; the payload only to a player with CONFIGURE, redacted in `DeviceRuntime.view`) and the
  counts. `DeviceTraffic` keeps them per device in memory, a ring of one each way: inbound in `deliver` for commands
  from `/set` topics, groups and Raw Links (a message that commands nothing or is refused counts as dropped), outbound
  in `DeviceRuntime.publish` (state, events, Raw Link levels, state echo). A change of the broker connection's status
  marks every attached device's view stale in `tickEnd`, so open screens never keep a stale availability. A faulted
  device's Retry button sends `DeviceRetryPayload` (CONFIGURE) to `DeviceRuntime.retry(player, blockEntity)`, the twin
  of `devices retry`, which queues a position at most once a second; a failed attach pushes the new view to open
  screens, isolated like the attach. Below the counts, a device that keeps legacy items has an Eject button (M2-12,
  `DeviceView.legacyItems`). Twins: `devices access <device> <mode>`, `devices claim <device>` (a player),
  `devices eject <device>`, and `devices info` prints the counts and the legacy items.
- **Bridge Console** (M2-23, `ui/console`). `terminal_block` keeps its ids and gained a horizontal `facing` and a
  plain `TerminalBlockEntity` (no container, no owner, nothing sent to clients) that keeps only its last tab; it is no
  device and no HA device. `TerminalBlock` is a `BaseMqttBlock`, so `Interaction.decide` opens it like any block that is
  no device. `DeviceAccess.consolePermits`: USE (open it) follows the claim like a block without an owner, CONFIGURE
  (its actions) is for operators only. `BridgeConsole` (one per `MineqttRuntime`) builds each viewer's `ConsoleView`:
  the bridge (`mqtt.state()`, the redacted broker, protocol, `BirthWatcher.heard()` (birth or last will, since the
  server started or the topic changed), the pause state, `MqttService.Stats` drops and queues, the wire count with the
  bus's delivered and dropped totals), the devices the viewer may configure (`DeviceRuntime.configurer`; all for an
  operator; faulted, unknown type and Raw Link problem first; at most 64 rows) and the groups they manage
  (`GroupRules.manages`; all for an operator; at most 32). It travels in the tabbed menu's open data
  (`TabbedMqttMenu.writeOpenData(..., ConsoleView)`, which builds a `ConsoleMenu` on the client) and in
  `ConsoleViewPayload`, which `tickEnd` sends every open console once a second. `BridgeActionPayload(pos, BridgeAction)`
  (a closed enum: reconnect, republish, prune; an unknown ordinal does not decode) passes `C2SGuard.require(...,
  TerminalBlockEntity, CONFIGURE)`, and `BridgeConsole.act` checks the op level again and runs the command's own method
  (`MineQTTCommands.reconnect`/`republish`/`prune`) with the player's command source, then resends the view.
- **Groups.** Homogeneous, actuator-only. A group command fans out to members (applied or queued). Members are hidden
  from HA by default; HA sees one entity per group. Member-local changes do not update the group state. Groups have no
  owner (M2-17): `device/GroupRules` (pure) lets a player join a device to a group, rename the group or show its
  members (`exposeMembers`, also needs `mayExpose`) only with CONFIGURE on every member, as a wire into a group needs;
  a group without members is changed by nobody (only `/mineqtt group add` fills it), and a legacy group takes a device
  only when the ACL lets its owner use the v1 topic (allowlist, private subtree, their grandfathered literal), as the
  group follows that topic under the legacy rules alone. Leaving needs CONFIGURE on the device alone.
  `DeviceRuntime.changeGroup(record, GroupRules.Change, configures, mayExpose)` is the one player path (the group page
  and Group mode): `Join`, `Leave`, `Create`, `Rename`, `ExposeMembers`. A group that loses its last member, by a
  player's change or with its block (`forget`), is deleted (`GroupRules.dropsWhenEmptied`, never a legacy group), so
  players hold no groups without devices; a broken member's crash-restore copy then comes back in no group.
  `renameGroup` moves the slug (`DeviceRegistry.renameGroup`, structural),
  clears the old state topic, publishes the discovery at once (`DiscoveryPublisher.publishNow(group)`) and the state, if
  it has one yet, on the new topic, and marks the links lists of the group's wires. Twins: `/mineqtt group rename`, `group expose-members`.
- **Group page**: `DeviceGroups` (the device's group with its topic, member count, `exposeMembers` and whether the player
  manages it; up to 64 joinable groups with the total), built per player, is its own S2C payload sent after the open
  data (`DeviceRuntime.sendGroups`) and to every open device screen at the end of a tick in which any group changed
  (`staleGroups`). The General tab of a groupable type (`GeneralTab.grouping()`) has a group button that turns it to
  `GroupPages` (client): the group page (name field with Rename or Create, Leave, Members, Join) and a scrolling join
  page; changes go as `DeviceGroupPayload(pos, GroupRules.Change)` (guard CONFIGURE on the screen's device), refusals
  come back with key `group`.
- **Raw Link.** The v1 "topic" of a migrated block, kept as an extra binding next to the device topics, edited through
  the item slots in M1 and on the Advanced tab, with a paper or with `devices raw` since M2-12. Inputs subscribe with
  no-local + loopback; outputs publish edges. Every topic is checked by the `TopicAcl`; v1 topics of migrated blocks
  are grandfathered to their owner.
- **Slots closed to automation** (M2 plan D7). `BaseMqttBlockEntity` (the legacy blocks and the timers since M2-12) is a
  `WorldlyContainer` with no faces
  (`getSlotsForFace` empty, both `...ThroughFace` methods refuse, also vanilla's side-less insert), so hoppers, hopper
  minecarts, droppers and sided wrappers (Fabric `InventoryStorage.of(container, side)`, NeoForge
  `WorldlyContainerWrapper`) move nothing; the screen's slots are unchanged. Hoppers use the container on both loaders
  (NeoForge asks it before any capability). NeoForge registers no item capability for MineQTT's block entity types.
  Fabric's `ItemStorage.SIDED` container fallback would wrap the container whole for a side-less (`null`) query, so
  `MineqttFabric` registers an empty storage for every slotted MineQTT block entity type
  (`MineqttBlockEntityTypes.slottedTypes()`), which wins over the fallback for every side; devices and decommissioned
  blocks have no container.
- **Owners by UUID** (M2 plan D7, F11). `DeviceAccess.legacyOwnerMatches` is the one owner match: the stored UUID
  decides; a v1 owner name not resolved yet stands for `UUIDUtil.createOfflinePlayerUUID(name)` on a server without
  authentication (`AclContext.offlineOwner`) and for nobody in online mode, so a name alone never grants owner rights.
  Blocks that are no devices store `ownerUuid` next to v1's `ownerName` (v1 ignores it; placement and the private-mode
  claim set both, a non-player placement clears both). `BaseMqttBlockEntity.setLevel` queues the block for
  `DeviceRuntime`'s owner lookup (parked in `beforeStart` until `SERVER_STARTED`), drained at the next `tickStart`
  and asked again at every load until it resolves; the access check also asks, so an online owner of the name is
  bound before their click is judged. Devices and blocks share `DeviceRuntime.lookUpOwner`: an online player of
  exactly the name (`AclContext.onlineOwner`; vanilla's `getPlayerByName` ignores case, but offline UUIDs do not),
  else nothing in offline mode, else the profile cache off the server thread, one lookup per name. Offline mode never
  stores the derived UUID: behind a proxy that forwards online UUIDs nobody carries it, and the vanilla profile cache
  would ask Mojang for a UUID nobody on the server has. The match derives it meanwhile, and a device whose owner joins
  is bound then (`DeviceRuntime.playerJoined`). An unresolved owner on an online-mode server shows "owner not verified
  yet" on the screen's status line.
- **Item and paper topics** (`RawTopics`, M2 F1) resolve the same for host, guest and dedicated server, never through the
  op bypass: the owner's private subtree in private mode, else the literal when the owner's literal set (registry
  `ownerLiterals`: their grandfathered v1 topics and kept M1 topics) or `acl.rawAllow` covers it, else
  `mineqtt/shared/<literal>`, else a disabled link with the reason. The link keeps the literal as its `channel` and is
  resolved again at every attach, when the device gets its owner and when the owner's literal set gains the channel, so
  the result never depends on attach order and a fixed config heals a refusal. Links M1 saved (no `resolvedV`) are
  re-resolved once at attach: for a device the registry knew, kept while the ACL accepts them for the owner (op bypass
  included), else moved, and the owner is told. A grandfathered v1 topic joins its owner's set once, when the v1 owner
  name resolves; a player's claim of an ownerless device and untrusted data never fill it.
- **Visible refusals** (M2 F2). A Raw Link that moves or becomes refused is told in chat to the player who edited the
  device within the last 30 s (`DeviceBlockEntity.recentEditor`, set by `DeviceRuntime.editRaw` for the screen and a
  paper; a legacy block's `BaseMqttBlockEntity.recentEditor`, set by `TabbedMqttMenu.clicked` when a click of a player
  who may configure the block changed its slots, and by the private-mode payload), else to its owner (at
  once, or queued until they join or open a screen), plus the owner's action bar; an edit that lands on
  `mineqtt/shared/` tells the editor too. `NoticeGate` tells the same refusal once per device or legacy block topic; an
  unmigrated block's reload refusal reaches its owner by UUID while online. Open screens get `DeviceStatusPayload`
  (status line and problem, cut to 512 chars). A config reload that changed the ACL's rules calls
  `DeviceRuntime.aclChanged()` and `LegacySubscriptionFacade.aclChanged()`: links with a channel are resolved again
  (`RawTopics.recheck`), other links and legacy subscriptions open or close as the new ACL allows, and an output that
  works again publishes its current value. Fabric dedicated servers have no config reload; NeoForge reloads on file change.
- **Retained wireless redstone** (M2 F3). `RawLink.retain` is an override (`null` = the type's
  `RawLinkSpec.retainByDefault`, true for the Redstone Input), so M1 and v1 links are retained without a migration.
  `Outbound.rawRetained` is an EDGE (every edge, 40/s per origin) with `OfflinePolicy.COALESCE` (the last level waits in
  the outbox); over the cap the last level per topic is held until the origin may send again, not dropped. A custom
  `TOGGLE` payload is never retained (`RawLink.effectiveRetain`). A retaining output publishes its live level after
  every CONNACK, once after its first signal read at the attach (`DeviceLink.rawValueKnown`; `rawValue()` is null
  before it) and when it gets an owner. `RawRetain.plan` says what a link change or a destroy leaves on the broker: the
  type's off payload, retained, to a topic the output left (judged by the ACL as the old link, grandfather flag
  included), an empty retained clear when retain is switched off, and the level on a new topic. The off payload and the
  clear are `Outbound.rawRetainedFinal` (KEEP, never evicted). The other retaining outputs of the server on a topic left
  off or cleared republish theirs in the same tick (last writer wins). Unloading publishes nothing. Raw grammars and
  Raw Link inputs ignore empty payloads.

## Flows

The seed of the Node-RED-style flows (M2 plan §3; design §13). Devices are nodes, their derived `Port`s are the node
ports, and wires join an out-port to an in-port. `flow/core` holds the Minecraft-free part:

- **`Endpoint`** is sealed: `PortRef(node, port)` and `GroupPortRef(group, port)` are built; `TopicRef` and
  `HaEntityRef` are declared so switches stay exhaustive, and the registry codec (`device/WireCodecs`, DFU, so it lives
  outside `flow/core`) refuses them, as it refuses `Scope.NETWORK` until data cables exist.
- **`Wire(id, from, to, mapping, scope, owner, enabled)`**: `Mapping.FOLLOW` (the coerced value), `INVERT` (into a bool
  or level) and `PULSE_TOGGLE` (every event and every rising edge toggles a switch state). `Mapping.compatible` decides
  whether a pair may be wired, `Mapping.apply` is the pure transform.
- **`MessageBus`** (one per server, owned by the device runtime from M2-15): `emit` only queues a `FlowMessage`, and
  `drain` carries it over the source's wires through `Wiring` (wires and `PortSpec`s; `device.Port` is one) to a `Sink`
  (the device command path). FIFO; a drain travels at most 2000 wires or 2 ms, checked after every wire (a group target
  is one wire), and carries the rest over in order, a message left halfway first with its remaining wires; at most
  10 000 queued (the oldest dropped). Per source port a `TokenBucket` of 20/s with a burst of 40: beyond it an event is
  dropped, and a value is held back (the newest per port) until a token is free, so targets end up with the last
  value; a port without an enabled wire emits nothing. Per wire, a VALUE equal to the last one its target took is
  suppressed; a refused or failed delivery is not remembered, and events are never suppressed. A wire back to the
  message's own source port is skipped, and the `Sink` skips the source's own member when it fans out to a group.
  Every drop is counted by `MessageBus.Drop`.
- **Hops** count the wires a message travelled over. Whatever is emitted while the bus delivers a message (on the same
  stack, through `applyCommand`, `publishState` or a neighbour update) carries that message's hops, so hops never go down
  the command path, and a zero-delay cycle is cut after 16 wires (`Listener.loopCut`, once per wire). There is no
  same-msgId dedupe, so a diamond delivers both branches. A delivery may not drain the bus again.

How the server runs it (M2-15):

- **Wires live in the registry** (`devices.nbt` `wires`, schema 1; a file without it has none), indexed by source
  port, in the order they were made. Their ends name devices and groups by UUID and port key, so renames and unloads
  leave them alone. Removing a device (destroy, prune, `devices remove`, a replaced record) or
  deleting a group removes its wires; a broken device's wires ride along in the structural saves with its kept copy
  until the level save. Adding and removing a wire is structural (saved within a second). A wire whose end is gone
  when the file is read is skipped with a log line.
- **`RegistryWiring`** is the bus's `Wiring`: `from` is the registry's index, `out`/`in` the `Port` of the device's
  type or the group's member type (a removed device or an unknown type has none: `UNROUTABLE`). Its `problem` is the
  rule a new wire passes: a device's out-port (a group is a target only) to an in-port of another device or a group,
  which `Mapping.compatible` allows, and no second wire between the same ports. Who may wire is `LinkRules`' (below).
- **Emission**: `DeviceRuntime.publishState` emits every readable field whose value differs from the record's last
  state on its out-port (`DeviceType.outputs`: an inline field such as the lamp's `light` as one object), before the
  MQTT throttle, so the out-ports carry every change at once; `publishEvent` emits the event on its port. Both run on
  the stack of what changed the device, which keeps the hop contract. An unloaded actuator that queues a command
  emits nothing until it loads and applies it (it touches the world). A device whose state did not change while the
  server was down emits nothing at its attach, so a pulse-toggle target does not flip on a restart; what a record's
  power-on changed (`power_on_behaviour=off`) is emitted by `powerOnRecords` and delivered at the first drain.
- **Delivery** (the `Sink`): the in-port's command (`DeviceType.inPortCommand`: `{"<port>": value}`, an inline
  field's value as its own keys) goes through `DeviceRuntime.command(target, json, BINDING, source device)`, so a
  loaded device applies it, an unloaded output or lamp queues it and an unloaded sensor drops it (`REFUSED`), as for
  Home Assistant; a group fans it out and skips the member the message came from (`skipsMember`). A `REJECTED`
  result counts as refused. No broker is involved.
- **Drains**: after `drainInbound` (`MineqttRuntime.handleInbound`, so also in the paused-server pump), and at the
  start of `DeviceRuntime.tickEnd`, before the throttled states, the views and the flush. `emit` marks work for a
  paused server (`workQueued`), and `hasPendingWork` counts what the bus still holds. `stop()` clears the bus;
  removing a wire, a device or a group forgets what the bus kept for them.
- `/mineqtt wires list | add | remove` is the command twin of Link mode (a port is `<device>.<port>` or
  `group.<group>.<port>`; without a mapping `DefaultWiring.mapping`: an event into a toggling state toggles, anything
  else follows). `DefaultWiring.of(fromType, toType)` gives the pair Link mode offers first: `powered` or `occupancy`
  follows into `state`, `press` toggles it. `/mineqtt status` shows the wire count and the bus counters; console and
  command-block wires are owned by `Util.NIL_UUID`.

Who wires what, and the Configurator's Link mode (M2-16):

- **`LinkRules`** (pure): `allowed(registry, wire, configures, cap)` is `RegistryWiring.problem`, then CONFIGURE for
  the wire's owner on both ends (on every member of a group end), then `limits.maxWiresPerPlayer` (default 256, 0 is
  no limit) over the wires the owner already has. A wire of `Util.NIL_UUID` (console, command blocks) is trusted and
  counts against nobody. `DeviceRuntime.addWire(wire, configurer(player))` applies it for Link mode and
  `/mineqtt wires add` alike (an opped player is capped too). `options(registry, source, target)` is the port picker's
  list: every out-port/in-port pair some mapping joins, the `DefaultWiring.of` pair first, then the target's group
  with the same ports; a pair already wired names its wire (the picker's Unlink). `touches(registry, wire, device)` is
  what a device's links list shows: wires at the device and into its group. `mayRemove(registry, wire, device,
  configures)` is who may cut one there: a wire at the device with CONFIGURE on it, a wire into its group only with
  CONFIGURE on its source or on every member (one member's configurer cannot cut the others off).
  `DeviceRuntime.configurer(player)` asks `DeviceAccess` of a loaded device's block entity and of an unloaded one's
  record; both trust a claim only in the device's own dimension (`DeviceAccess.claimTrust`), as Flan reads the claim
  in the player's level at the device's coordinates, which matters for group members in other dimensions.
- **Seeding**: `addWire` sends a FOLLOW or INVERT wire's target the source's current value (`DeviceType.outputs` of
  its effective state, mapped) through `command(..., BINDING, source)`, so the target follows from the start; a
  PULSE_TOGGLE wire never seeds, as it would flip its target. An offline source (`DeviceRuntime.seeds`: an unloaded
  UNAVAILABLE sensor or a faulted device) does not seed either: its stored value is stale.
- **`items/ConfiguratorLink`** holds, per player on the server, the device clicked first and the offer the picker
  answers (forgotten on a mode step, on leaving and when the server stops). A click on a device in Link mode
  (`Interaction.Outcome.LINK`, within reach through `BaseMqttBlock.click`, from afar through the Configurator's
  `pick(16)`; both need USE, then CONFIGURE) picks the source, offers the wires to a second device in a
  `LinkPickerPayload` (S2C) or, on the source again, stops. The picker (`ui/LinkPickerScreen`, a plain client screen)
  answers with `LinkChoicePayload(offer, option index, mapping, unlink)`; `C2SGuard.requireOffer(..., LINK, ...)` accepts it only for the
  open offer, with a Configurator in Link mode, the second device still in reach and CONFIGURE on both devices (the
  offer stands for the screen of the other payloads), then `LinkRules` decides; the result goes to the action bar with
  names and ports (`DeviceRuntime.label`).
- **Group and Copy-Paste modes** (M2-17) follow the same shape: `ConfiguratorMode` `GROUP`, `COPY_PASTE`;
  `Interaction.Held.CONFIGURATOR_GROUP`/`_COPY` give `Outcome.GROUP`/`COPY` (sneaking opens the screen); every mode step
  goes through `items/ConfiguratorSessions` (dispatch, `forget` on a mode step or quit, `clear` at `SERVER_STOPPING`),
  and `ConfiguratorTarget` is the shared check of a clicked device (running, CONFIGURE). `ConfiguratorGroup` keeps the
  device clicked first: the next device joins its group (`changeGroup(Join)`), or, when it has none, a
  `GroupPromptPayload` opens `ui/GroupNameScreen`, answered by `GroupNamePayload(offer, name)` through
  `C2SGuard.requireOffer(..., GROUP, ...)` (the generalised Link guard), after which the first device creates the group
  and the second joins it. `ConfiguratorCopy` keeps a `device/DeviceClipboard` per player: the Raw Link settings, the
  type's settings, `expose` and `access` (last), as link setting keys with values taken from the copier's view; a paste
  (`DeviceRuntime.paste`) sends each through `configure(record, key, value, Editor.of(paster, target))`, so every one
  is checked for the paster, and skips the rest of the Raw Link when its channel or topic is refused.
- **Links list**: `DeviceLinks` (rows of wire id, direction, the device's port, the other end as name and port, the
  group, mapping, enabled; at most 64 with the total) is its own S2C payload, not part of the `DeviceView`, whose bytes
  it would crowd: sent right after a device screen's open data (`DeviceRuntime.sendLinks`) and at the end of a tick to
  the viewers of every device one of whose links changed (a wire made or removed, a peer renamed or removed, a group
  joined, left or deleted). The Advanced tab's *Links* button turns it to a scrolling list with a remove button per
  row (`DeviceUnlinkPayload`, guard CONFIGURE on the screen's device, then `LinkRules.mayRemove`). There are no
  per-wire counters yet.

## Home Assistant

- **Device-based discovery** (`cmps`), one retained config per device (device triggers included; a group's config carries
  only command entities), a **MineQTT Bridge** device (keyed by the world
  UUID, so a `bridgeId` change does not orphan it) as `via_device` of every device. The primary entity has
  `name: null`, so HA names it after the device (`light.kitchen`).
- **Availability** is `[bridge/state, <slug>/availability]` with `availability_mode: all`. The bridge's own
  connectivity sensor has no availability, so it shows `off` (not unavailable) when the bridge is offline.
- **Connect sequence** (every CONNACK): bridge `online`, one batched SUBSCRIBE, force unattached sensors `offline`,
  discovery at ≤50 msg/s, then 1 s later availability and state, then the pending v1 discovery migrations, then the
  outbox. `homeassistant/status online` (HA birth) repeats discovery and state after a random 0-3 s delay;
  `bridge/request/republish` and `/mineqtt discovery republish` repeat them at once.
- **Ghost cleanup** (`GhostCleanup`, design §4.5, M2-24): every CONNACK opens a 5 s scan of `<prefix>/device/+/config`
  (QoS 0, retained accepted, not no-local, so a strict MQTT 5 broker still sends ghosts this client id published in an
  earlier run; QoS 0 because Moquette drops the retain flag of stored messages sent at QoS 1/2). The pure
  `GhostCleanup.judge` keeps everything but a **retained** config whose object id is exactly `mineqtt_<uuid32>` (lowercase,
  as `DeviceRecord.objectId` writes it), whose id `DeviceRegistry.contains` denies (devices and groups), that is at most
  16 KiB (checked before parsing; the subscription's own cap is unlimited so a large foreign config is not logged as a
  drop) and whose `dev.via_device` (or `device.via_device`) is this bridge's `mineqtt_bridge_<worldUuid32>`. Live
  messages are ignored: own republishes and clears arrive live, and a live config naming this bridge could only come from
  a server running a copy of the world. Clears are `Outbound.clear` released through `DiscoveryPublisher.tryTakeToken`
  (the shared 50 msg/s budget), re-checked against the registry at release; a lost connection ends the scan and drops
  its clears (the next connect finds them again); `pending()` keeps a paused server pumped until the window closes. Off
  for the run while `RegistryStore.incompleteReason()` is set: the registry is not saved (newer schema, or an unreadable
  file that could not be moved aside) or came from the backup, which misses the devices of the last save. The
  class is Minecraft-free (JDK, Gson, `mqtt.core`; `verifyCoreIsolation` scope "ghost cleanup"), so broker integration
  scenario 17 runs it against Moquette and, with `-PexternalBroker`, Mosquitto.
- **Exposure.** `ha.expose`: `ALL` (default on integrated servers), `OPT_IN` (default on dedicated servers; a device is
  discovered once a permitted player exposes it, migrated v1 lamps are grandfathered), `NONE`. A device's own setting
  (`DeviceRuntime.setExpose`, `/mineqtt devices expose <slug> on|off|default`) overrides the policy except NONE. Under
  OPT_IN a group is discovered only while a member's own setting is on, or when it replaces a v1 topic
  (`DiscoveryPublisher.exposed(DeviceGroup)`), so any player may make a group without making a Home Assistant device;
  `setExpose`, `leave` and `forget` refresh the group's discovery. Exposing a device needs op level 2 or
  `mineqtt:ha.expose` (`AclContext.mayExpose(PermissionSet)`) plus CONFIGURE on the device; the `/mineqtt` root admits
  holders of that permission, so every other subcommand carries its own op requirement.
- **v1 migration, per topic** (`LegacyDiscoveryMigration`): `{"migrate_discovery": true}` on the old
  `homeassistant/light/<id>/config`, ≥2 s later the new device config carrying the v1 unique_id on its light, ≥2 s later
  an empty retained payload on the old topic; the topic is marked done on that PUBACK. HA keeps the entity registry
  entry, entity id and history. The v1 retained `Offline` status is cleared once. A device or group with a v1
  unique_id names it as its second device identifier (`dev.ids`, for good, also after the migration), so HA reuses
  the v1 device entry (M2 plan D7, F12).

## Security

- `TopicAcl` (pure, in `mqtt/core`) decides every topic a player, a block or a command uses. Hard denies (discovery
  prefix, bridge namespace, `$SYS`, other players' private subtrees) apply even to ops; the raw allowlist
  (`acl.rawAllow`) comes from the config; v1 topics of migrated devices are grandfathered per owner.
- `C2SGuard` validates every client-to-server payload: rate limit first, then no spectator, then menu/BE lookup, reach
  (16 blocks with a Configurator in hand where the level lets the player interact, `reaches`), claim permission
  (`MineqttPermission.USE`/`CONFIGURE`, Flan when present) and size limits. Device payloads use `requireDevice` (any
  `DeviceHost` block entity); a setting's NBT value is read within `C2SGuard.valueQuota()` (8 KiB, depth 4).
- Placement data from items (`BLOCK_ENTITY_DATA`) is untrusted: a fresh id is minted, the copied name, slug and
  grandfathering are dropped, the placer becomes the owner and the Raw Link is re-checked for them; a copied link with
  a channel (a grandfathered v1 topic becomes one) or without the `resolvedV` marker (M1 data) is then resolved for
  the placer like a new one.
- `limits.maxDevicesPerPlayer` (M2-26, default 0 = no limit) is checked at placement: every device's item is a
  `DeviceBlockItem` (a converter's item checks for its successor), whose `getPlacementState` returns null on the server
  when `DeviceRuntime.mayPlaceDevice(player)` refuses, so vanilla places nothing, keeps the item and sends the client
  the real blocks and the player's slots back (the client used the item up in its prediction); the player gets the reason in the action bar. A player's devices are `DeviceRegistry.ownedBy`
  (the records they own, loaded or not) plus their placements still waiting in the attach queue. Operators are not
  limited, and paths without a placing player (`/setblock`, `/clone`, structures) are not checked. A claim of an
  ownerless device (`DeviceRuntime.claim`: the Access tab's claim button and `/mineqtt devices claim`) is refused
  likewise for a player at the limit.
- The Block Placer (ex block emitter) is off unless `blockPlacer.enabled`. MQTT never runs server commands.

## Migration of v1 worlds

- Block, item and BE-type ids are unchanged. The two unmigrated blocks, the Block Sensor (publishes) and the Block
  Emitter (subscribes), keep their exact v1 NBT and run on `LegacySubscriptionFacade` (registration only for server BEs,
  hard-deny ACL, no-local + loopback). Since M2-26 they extend `BaseMqttBlockEntity` directly; the v1 base classes
  `MqttPublisherBlockEntity`/`MqttSubscriberBlockEntity` and the `SubscriptionManager` delegator are gone.
- A migrated type reads v1 NBT through `LegacyNbtV1` when the `mineqtt` compound is missing and keeps writing v1 keys
  until the device has attached; adoption happens at attach (`LegacyAdoption`).
- The Smart Plug (M2-18) was a legacy block through M1 and most of M2, so its data can also carry the `ownerUuid` the
  legacy layer resolved (F11): `LegacyNbtV1.Snapshot.ownerUuid` reads it, `writeDevice` writes it back while the data
  waits, and `DeviceLink.adopt` makes it the owner of data adopted on chunk load, so the grandfathered topic joins that
  owner's literal set at the first attach. A plug is the Output's logic with an on/off preset (`SmartPlug.Plug`; the
  writable `power`, 0 or 15, carries on/off through `QUEUE`, groups and toggles), with v1's plug grammar
  (`ValueCodecs.SWITCH`, no levels) on a Raw Link input that accepts retained values and no longer echoes its state.
  The converters of M2-19 and M2-20 read the same snapshot.
- The Climate Sensor (M2-19) keeps the temperature sensor's block and BE ids (`temperature_sensor_block`, type
  `mineqtt:climate_sensor`). v1 temperature and humidity data (`Temperature`/`Humidity`, `UpdateIntervalTicks`,
  `LastPublishTime`) is read by `LegacyNbtV1.climateState`: a `Humidity` without a `Temperature` is a humidity sensor,
  whose Raw Link keeps publishing the humidity (`raw_value`). Readings are a pure function of the spot
  (`ClimateSensor.measure` over an `Environment` the block entity gathers from loaded chunks only), taken at the first
  tick after the attach and every `interval`; the Raw Link gets each reading in `Locale.ROOT` notation, the state goes
  through the 0.2 °C / 1 % deadbands (`StateThrottle` compares with a tolerance, as 20.2 − 20.0 is below 0.2 in binary).
- **Converters** (`LegacyConverterBlock`, plan D11): a retiring block keeps its block state properties and its block
  entity keeps the v1 data as saved (the humidity sensor's is still a `BaseMqttBlockEntity`, whose
  `loadedOnServer` hook schedules the conversion instead of the owner lookup). `DeviceRuntime.scheduleConversion`
  queues it; the first `tickStart` after `setLevel`, before that tick's attaches, `LegacyConverterBlock.replace` saves
  the legacy block entity, sets the successor with every shared property (`UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`, so its
  slots do not drop) and loads the converted data (`convert`, pure) into the successor's block entity, which then
  attaches in the same tick and adopts it as trusted v1 data: topic grandfathered, `ownerUuid` bound, slots as legacy
  items, and the anvil name (`CustomName`, `LegacyNbtV1.Snapshot.name`) as the device name. A placed converter block
  (an item placed before its inventory tick converted it, or by a placer whose inventory never ticks) converts at
  once in `LegacyConverterBlock.setPlacedBy`, which then runs the successor's `setPlacedBy`: the item's data is
  untrusted (§8.6), so the placer owns the device, its id is fresh and nothing is grandfathered. The item is a
  `LegacyConverterBlock.ConverterItem`, which turns into the successor's item (components kept, block entity data
  converted) at its first inventory tick; items in containers convert through a shapeless 1:1 recipe, which
  `verifyCatalog` requires for every converter with a `Catalog` successor, which is also hidden from the creative tab.
- The Static Publisher (M2-20) converts into a Redstone Input. Its `staticMessage` stays in the converted data as the
  key that tells it apart: `LegacyNbtV1.read` makes it the snapshot's on payload (none for an empty message, as v1 then
  sent `true`; the third slot never fed the message, so its item is only a legacy item) with `Snapshot.edges` `RISING`,
  `writeDevice` writes it back while the data waits, and `DeviceLink.adopt` gives the output link `edges: rising` and an
  explicit `retain: false`. `RawLink.edges` (`BOTH` by default, never stored) is kept by every change of the link.
  Edges are filtered centrally: the input publishes each edge through `DeviceLink.publishRawEdge`, which
  `DeviceRuntime.publishRawEdge` drops for a falling edge of a `RISING` link, and a `RISING` link carries no level, so
  `effectiveRetain` is false and the runtime's level republishes (CONNACK, after the attach, on owner resolution, after
  an ACL change) skip it (`rawLevel`). The Advanced tab says *Rising edges to* and `devices info` "rising edges only";
  there is no control for the edges.
- **The Wall Switch** (M2-22, `WallSwitch`) keeps the Light Remote's block and BE ids (`light_remote_block`). Its
  events `press`, `double_press` and `hold` each carry `button` (`left`/`right`); its actions `press_left`/`press_right`
  fire a press at once (HA `/set`, the Controls tab, wires, `devices action`). A hand's right-click is no action: the
  block (`LightRemoteBlock.useDevice`, the `BaseMqttBlock` hook for a USE that depends on the hit) finds the button by
  raycasting the player's view on the server (the click event names no hit point) and feeds the pure
  `WallSwitch.Gestures`, which tells press, double press and hold apart from the uses alone (a held use key repeats every
  4 ticks; the BE's server ticker ends gestures). `UNAVAILABLE`, so a queued press never fires late. Its Raw Link is
  `RawLinkSpec.events()`: event names (`press_left`), no custom payloads, `level` false, so `effectiveRetain` is false
  and `LinkSetting.of` offers no `raw.retain`. HA gets an event entity (primary, `device_class: button`) and a
  `device_automation` trigger per gesture and button (`button_short_press`/`button_double_press`/`button_long_press`
  with subtype `left`/`right`, payload `press_left`, template `{{ value_json.event_type }}_{{ value_json.button }}`).
  The block has `facing` (horizontal) and `left`/`right` (a button shown pressed while held or for 20 ticks after an
  action); v1 blocks had no properties and load facing north.
  A v1 Light Remote is v1 data of a type with `remote()`: `DeviceLink.adopt` makes no Raw Link of its topic and the
  attach does not name it after the topic; its slots become legacy items. The topic goes into the record's `v1Topic`,
  which a v1 lamp's record keeps too. After each tick's attaches, `DeviceRuntime.wireRemotes` matches it (M2 plan D15,
  pure `LegacyAdoption.remoteTargets`) against the loaded Smart Lamps (their Raw Link topic, owners by UUID when both
  know it, else by v1 name), the unloaded lamps adopted with the topic (by owner UUID) and the legacy groups of lamps,
  and wires the switch's `press` to each match's `state` (`DefaultWiring`'s pair, `PULSE_TOGGLE`), trusted (no
  CONFIGURE or cap check), owned by the remote's owner or the console. While `LegacyAdoption.remoteUndecided` (an
  imported lamp on the topic that has not loaded since v1, or an unloaded one whose owner is not compared by UUID), the
  switch waits, matched again only when a lamp attaches, an owner becomes known or a record goes; with nothing it
  commanded known, it waits `REMOTE_WIRE_WAIT_TICKS` (1200) loaded ticks for lamps the registry does not know, then
  logs that the v1 commands are dropped. Settled, the record forgets the topic; until then an unload or a restart
  only pauses it.
- **Decommissioned blocks** (`DecommissionedBlock`, M2-21; the Dynamic Publisher and the Topic Relay) keep their ids,
  block state properties and blockstates (every `powered` variant shows one powered-down model), and are inert: no
  `BaseMqttBlock`, no screen, no ticker, no redstone in or out, no MQTT. Their block entity type ids stay, now made by
  the one `DecommissionedBlockEntity`, a plain block entity (no container) that keeps the v1 slots (`Items`) as
  `legacyItems` (`loadItems`/`saveItems`, pure; every other v1 key is dropped at the first save), drops them in
  `preRemoveSideEffects` and sends clients nothing. A placement clears the items an item's block entity data named
  (§8.6). Their loot tables drop one Network Chip, which `verifyCatalog` requires of every decommissioned entry along
  with being hidden from the creative tab; a right-click (`useWithoutItem`) tells in chat what replaced the block, and
  the items carry the same as a `minecraft:lore` default component (`DecommissionedBlock.lore`; `appendHoverText` is
  deprecated in 26.2). Their five C2S payloads, tabs and views are gone; `verifySecurityGuards` counts the payloads.
- `LegacyTopicStateImport` runs once before the first connect: a v1 topic with one lamp becomes a pre-created record,
  a topic with several lamps a legacy group. Records carry the v1 unique_id, so HA continuity does not depend on anyone
  visiting the lamps. The v1 file is read, never written or renamed.
- Config files migrate to `configVersion: 2` on both loaders; the v1 file is kept as `mineqtt.json.v1.bak` /
  `mineqtt.toml.v1.bak`.

## Testing

- `./gradlew check` runs plain `main`-method checks: `sensorChecks` (bootstrapped Minecraft: block sensor, emitter,
  timers, value codecs, registry round trip, lifecycle guards, legacy NBT migration, discovery payload golden files,
  config migration, security guards, `verifyDeviceTypes` (derived and frozen ports, strict settings and link settings,
  which port pairs a wire may join, the default wiring and the bus routing over a registry, the device view's stream
  codec and 32 KiB cap), and `verifyCatalog`: every `Catalog` entry's assets, loot table and lang keys,
  every 26.2 recipe and unlock advancement parsed with the vanilla codecs) and `brokerChecks` (no Minecraft: topic
  trie, broker URIs, ACL, queues, outbound buffer, `verifyMessageBus` (the flows bus and the coercion table), and `verifyBrokerIntegration` against an in-process Moquette, MQTT
  3.1.1 only).
- `e2e/` is the manual end-to-end environment: Mosquitto 2 (TCP, TLS, WebSockets), Mosquitto 1.5, and Home Assistant at
  the supported floor and current stable. Its README holds the checklist and the last recorded results, including the
  MQTT 5 integration run (`-PexternalBroker`).
- `:fabric:runClientGameTest` runs the Fabric client game tests (`fabric/src/gametest`): a real client joins an
  in-process dedicated server as a non-op player. It opens a game window, so `check` only compiles them; how to run
  them, the suites and the helpers are in `e2e/README.md`.

## Pitfalls

- Do not touch MQTT from client block entities or from `loadAdditional`/`saveAdditional`; go through `DeviceLink`.
- Do not subscribe per device; route through the registry by slug.
- Do not call `MineqttRuntime.of(server)` before `SERVER_STARTED`; pre-start chunk loads are queued until attach runs.
- Block entity types are registered in `common` (`MineqttBlockEntityTypes.init()`, M2 plan D13), never from a static
  initialiser and only after `MineqttBlocks.init()`: Architectury registers eagerly on Fabric, so a type whose block
  is not registered yet fails at startup. `check` cannot construct mod block entities; a new type is verified headless
  on both loaders with `e2e/tools/allblocks.sh`.
- A retained message is only ever taken as truth for Raw Link inputs that accept it (outputs, v1 parity) and for M3
  mirror sources; never for commands.
- When a v1 data path changes, extend `verifyLegacyNbtMigration`; when a discovery payload changes, update the golden
  files in `common/src/sensorChecks/resources/golden`.

# MineQTT v2 architecture

How MineQTT is put together since v2 (milestones M1 to M4, version 0.8.0), and why. The full design, with every decision, open question and
roadmap item, is not part of this repository. This file is the map for
anyone changing the code; read it before touching the MQTT core, a device type or the migration code.

## What v2 replaced

v1's rules were workarounds for its own architecture. They no longer apply:

| v1 rule | v2 |
|---|---|
| "No retain anywhere": status, state and discovery not retained | Everything MineQTT publishes **about itself** is retained: discovery, bridge and device availability, state, bridge info. **Commands are never retained**, and a retained message on a device's `/set` topics is never taken, so a stale command on the broker cannot change a device at startup. Raw Link inputs that keep v1 parity (Redstone Output, Smart Plug) still apply retained values, so their topics need broker ACL protection. (v1's discovery was in fact published retained; the old docs said otherwise.) |
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
 │ Cyberdeck, /mineqtt, C2S ── TopicAcl ───────┤             │      │  global publish flow  │
 │                                             │             │      └──────────┬────────────┘
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
| `mqtt/core` | The transport. **Minecraft-free**: only the JDK, Gson and HiveMQ (`verifyCoreIsolation` fails the build otherwise). `BrokerConfig` (URI parsing), `HiveMqttService` with `Mqtt5ClientAdapter`/`Mqtt3ClientAdapter`, `TopicTrie`, `InboundQueue`, `OutboundBuffer`, `LocalLoopback`, `TopicAcl` (rules as data; `checkTopicSource` judges an MQTT device preset's state topic, M3-17), `SharedSubscriptions` (one handler per filter for many holders, ref-counted; the twin's statestream leaves). |
| `runtime` | `MineqttRuntime` (one per server run), `ServerHooks` (lifecycle wiring), `ReplaceableMqttService` (survives config reconnects), `TopicScheme`, `Slf4jMqttLog`, `WorldClock` (M4-1: `overworldTicks(level)` = `Level.getOverworldClockTime()`, 0 without an overworld clock; `timeOfDay(level)`). |
| `util` | Pure helpers, JDK only (`verifyCoreIsolation` scope `clocks`): `CronSchedule` and `GameClock` (M4-1: ticks per day, time of day and day number (both floor, so a clock before tick 0 reads a valid time), `isDay` ([0, 12000)), HH:MM `format` and strict `parse` with v1's rounding, `formatRoundedDown` to 15-minute steps, signed `ticksUntil`/`ticksSince`, the four `Moment`s). |
| `flow/core` | The flows seed (M2 plan §3, [Flows](#flows)). **Minecraft-free**: only the JDK, Gson and `security.TokenBucket` (`verifyCoreIsolation` scans it, and keeps `TokenBucket`/`RateLimit` JDK-only). `PortType` (the kind of a port: bool, level, number, enum, text, color, light, json, event; strict validators), `Coerce` (the coercion table, the bool spellings, the guarded `Coerce.number` and the text sanitiser the device codecs use), `Colors` (colour parsing and conversion: hex, `rgb()`, dye names, `{r,g,b}`, hs, xy, kelvin), `Endpoint`/`Wire`/`Mapping`/`Scope`, `FlowMessage`, `Root` (what caused a message's chain), `PortSpec` and the `MessageBus`. |
| `twin/core` | The digital twin's pure core (M3 plan D1, D3, D7, D18). **Minecraft-free**: only the JDK, Gson, `flow.core` and itself (`verifyCoreIsolation`). `StatestreamTopic` (`<base>/<domain>/<object_id>/<leaf>`, the `_mineqtt` catalog topics, everything else ignored by reason), `StatestreamValue` (the raw state: text, unknown, unavailable, cleared; never JSON), `HaTime`, `EntityDocument` (one shared snapshot per entity; stale and null attributes held back), `EntityDocuments` (the fold of statestream and catalog with the D3 merge, freshness and bounds), `CatalogParser`/`CatalogEntry`, `EntityGlobs` (`twin.allowedEntities`), `ValuePath` (the path grammar, statestream leaves, raw payloads ≤ 64 KiB), `ValueMap` (bool, level by bool/range/option, number, colour, text with decimals and unit, timestamp, the light composite; empty means hold), `DisplayText` (the Display Panel's server-side layout, at most 4 × 32), `HaDomains` (HA entities as typed flow nodes: per domain the out-ports with their statestream leaves, the in-ports, the attribute whitelist; the D2 privacy domains unreadable), `HaCalls` and `Call` (the one service-call table with its per-service data-key whitelist, D11 freshness-aware switching and the D5 edge mapping; a `Call` outside the table cannot be constructed), `Binding` (a device's source, targets, control, settings and refusal; its reads are derived from the type's adapter, not stored), `PortRead`, `TwinHealth` (a bound device's health and the render hint clients draw), `TwinCoalescer` (D9: a burst of leaves applies once, at most 4 times a second per entity), `EntityScan` (M3-11: the fallback scan of `<base>/+/+/state` and `friendly_name` while no catalog answers; readable domains only, capped at `twin.catalogMax`), `EntitySearch` (D4's ranking, shared by `/mineqtt twin entities` and the picker; since M3-13 also the candidates and the read gate of a listing, `candidates`/`readable`), `BindingPreset` (M3-17: an MQTT device preset as a document synthesizer for one Home Assistant kind: the state topic's payload as an `EntityDocument`, the adapter's `Call` rendered into the preset's command through typed placeholders, the device-name rules), `PresetCodec` (strict parsing of preset files with problem lines; a bad file is skipped), `AreaNames` (M4-23: Home Assistant's area-name key, casefolded with spaces removed; the distinct areas of the catalog and a room name's spelling). `HaDomains.TRIGGER_DOMAINS` (scene, script, button, input_button) never infer drift. |
| `twin` | The twin's server side (M3). `TwinAdapter` (Minecraft-free: how a type takes part in the twin: primary port, mirrored keys, source domains with their reads, the statestream leaves to hold, forward to `HaCalls` outcomes, targets on out-ports with a per-target `Target` translation (M3-14; `targetsOnly()` adapters have no primary, no mirrored key and no source), `Extras` (M3-15: read-only values a source gives besides the in-port values, under the command key `twin`, honoured only from a twin apply), and extra statestream leaves (`Builder.leaves`: attributes a type reads, such as a unit; `Builder.extraLeaves`: per-domain leaves a write-back needs, such as an `input_number`'s `step`)), `BindingCodecs` (the `twin` compound of blocks and records), `TwinRuntime` (the read side: shared per-leaf statestream subscriptions of loaded bound devices, entity documents, coalescing and the apply cap, health, the mirror freshness; target holdings for devices with targets, M3-14; the catalog's share gate `shareProblem`, entity search and drift, M3-11; topic holdings of preset sources with their own coalescer, `presetDocument`, M3-17), `BindingPresets` (M3-17: the `SERVER_DATA` reload listener for `data/<ns>/mineqtt/binding_presets/*.json`; a reload judges every binding again), `HaEntityCatalog` (M3-11: the catalog's own subscription 5 s after the first CONNACK and the fallback scan 10 s after it while no catalog status came), `TwinGate` (the root gate: only `PLAYER` and `WORLD` may request), `TwinRequests` (the write-back: `twin.haActionAllowlist`, sliding-window rates per device, player and bridge (a world-caused explicit state over a rate is held, newest wins, `holds`; every rate refusal is counted per device and logged once per burst, `RateRefusals`), the circuit breaker (world-caused rate refusals count as attempts), `forwardPreset`/`sendPreset` for a preset source's commands (M3-17), `bridge/ha_request`, the advisory `bridge/ha_response`, ping and `bridge/ha_sync`), `PendingRequests` (requests waiting for their entity), `HaSink` (the bus's sink for Home Assistant ends: target wires and forwards). |
| `device` | `DeviceType`/`Field`/`Setting`/`Action`/`EventSpec`/`LinkSetting` declarations, derived `Port`s, `DeviceView` (a screen's snapshot), `DeviceLink` (per-BE state), `DeviceRuntime` (attach, routing, groups, pending commands), `DeviceRegistry` + `RegistryStore`, `RawLink`, `Slugs`, `ValueCodecs`, `TwinRules` (the twin gate for edits, the judgement for owner and placer, what a bound device refuses; `Rights` carries a `Topics` judge, the ACL for a preset's topics, M3-17; `targetHealth` for targets-only devices), `TwinWiring` (the derived `Scope.TWIN` wires of bound devices: source and target wires, a preset's topic source wire carrying a payload fingerprint, `targetDevices` for catalog changes), `TwinView` (M3-13: what the Twin tab shows one viewer; no entity, refusal or answer without CONFIGURE). `device/types` holds `SmartLamp`, `RedstoneOutput`, `RedstoneInput`, `PresenceSensor`, `SmartPlug`, `ClimateSensor`, `WallSwitch`, `DisplayPanel`, the four timers of `Timers` (M4-2), `BlockObserver` (M4-9), `BlockPlacer` (M4-10) and `ContainerObserver`; `device/timer/core` the timers' pure schedules and `device/PlacerRules` the placer's rules ([Devices](#devices)). Blocks live in `blocks` (the panel: `DisplayPanelBlock`, `DisplayPanelBlockEntity`; the timers: `TimerBlock`, `TimerBlockEntity`) and client renderers in `client` (`DisplayPanelRenderer`). |
| `telemetry` | World telemetry and player events (M4-7, M4-8). `telemetry/core` is **Minecraft-free** (`verifyCoreIsolation`): `WorldReading`, `Weather`, `TelemetrySnapshot`, `TelemetryThrottle`, `NamePolicy`, `PlayerEventMessage`, `EventLimiter`, `BroadcastText` (M4-22: the broadcast text's cleaning and rate). `TelemetrySampler` reads the server (names through each player's consent since M4-11; `statusLine`, M4-17), `PlayerEvents` registers the player listeners ([Home Assistant](#home-assistant)). |
| `presence` | Consent and presence (M4-11 to M4-13, plan D9, D10): `Consent`, `PlayerEntry` (the registry's `players` list), `PrivacyPolicy` (the one rule over config and consent), `PlayerPrivacy` (held by `MineqttRuntime.privacy()`), `PrivacyCommands` (`/mineqtt privacy`, `/mineqtt players`), `PresenceRuntime` (the presence devices, `MineqttRuntime.presence()`), `FullSample` ([Home Assistant](#home-assistant)). |
| `rooms` | Rooms (M4-14, M4-15, plan D11): `Room`, `RoomRules` (pure rules), `RoomIndex` (which room a device is in), `RoomEdits` (the one edit path of commands and the Room mode), `RoomOffer`, `RoomSwitch` (M4-19: a room's All on and All off, pure) ([Devices](#devices)). They use `BlockPos`/`GlobalPos`, so they are outside the isolation scopes and checked in sensorChecks. |
| `ui/explorer` | The Configurator's Devices tab (M4-16, plan D13): `DevicesTab` (server-safe, layout and pacing), `ExplorerPage` (the page and its bounded codec), `ExplorerDirectory` (the server's search, scope, room grouping and the You row), `ExplorerActions` (M4-18: Use, Locate, Open and the room switch of a selected row, and the privacy choice of the You row; server side), `DevicesTabView` (client). |
| `ha` | `DiscoveryPublisher` (with `suggested_area` since M4-14, in Home Assistant's spelling of a matching area since M4-23), `BirthWatcher`, `BridgeDevice` (with the world telemetry and player events since M4-7, M4-8, and the broadcast text since M4-22), `PresenceDevice` (M4-12), `LegacyDiscoveryMigration`, `GhostCleanup`, `Ha`/`HaEntity` component builders. |
| `legacy` | `LegacyNbtV1` (v1 NBT reader, also of the timers, the Block Sensor and the Block Emitter), `LegacyTopicStateImport` (v1 topic-state file), `LegacyConverterBlock` (retiring blocks that turn into their successor, M2-19), `DecommissionedBlock` and `DecommissionedBlockEntity` (retired blocks that only wait to be broken for their parts, M2-21). |
| `security` | `AclContext` (player → ACL subject), `C2SGuard` + `TokenBucket`/`RateLimit`, `DeviceAccess`, `AccessMode`. |
| `registry` | `Catalog` (every block and item: craftable, creative, converter, decommissioned or unreleased (M3-15: hidden, no recipe makes or uses it, it drops itself); the creative tab's order; a plain id table that `verifyCatalog` reads under a bare bootstrap), `MineqttDataComponents`. Recipes live in `data/mineqtt/recipe/` (26.2 format; every block and the Configurator need the Network Chip) and unlock through `data/mineqtt/advancement/recipes/`. |

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
  `TopicTrie`. Handlers therefore always run on the server thread, in broker order. A payload over the broker-wide
  `BrokerSettings.DEFAULT_MAX_INBOUND_BYTES` (256 KiB, the largest subscription cap: the HA catalog's) is dropped before
  the queue, and on MQTT 5 the CONNECT maximum packet size keeps the broker from sending it at all. Per handler:
  retained messages are dropped unless the subscription accepts them, payloads above its cap are dropped (mirror
  sources 64 KiB, commands and Raw Links 16 KiB).
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
| `<base>/<bridgeId>/bridge` | yes | telemetry (players and drop counters; world keys with `telemetry.enabled`), sampled every second, a significant change within 2 s, the rest at most every `telemetry.intervalSeconds` ([Home Assistant](#home-assistant)) |
| `<base>/<bridgeId>/bridge/event` | **never** | player events (`join`, `leave`; `death`, `advancement` only for a player who may be named), QoS 1, dropped while offline |
| `<base>/<bridgeId>/bridge/request/republish` | n/a | inbound; republishes discovery and state |
| `<base>/<bridgeId>/bridge/request/broadcast` | n/a | inbound; with `telemetry.allowBroadcast` shows the text in every player's chat behind `[MQTT]` (M4-22); a retained request is ignored |
| `<base>/<bridgeId>/bridge/response/<command>` | **never** | the answer to a request: `{"status":"ok"}` or `{"status":"error","error":…}` |
| `<base>/<bridgeId>/player/<slug>` | yes | a consenting player's presence device state (`presence` ON/OFF; with full sharing `health`, `dimension`, `x`, `y`, `z`), QoS 1; nobody publishes under `player/` from inside the game (`DENY_BRIDGE`) |
| `homeassistant/device/mineqtt_player_<32hex>/config` | yes | a presence device's discovery (sha256 of world UUID and player UUID, first 32 hex) |
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
  `EventSpec.busOnly` (M3-14) declares an event that wires and twin targets carry but nothing publishes (no action topic,
  Raw Link or HA entity; `build()` refuses an HA event entity or device trigger on one): the Wall Switch's `left` and
  `right` (attribute `gesture`), emitted next to `press`/`double_press`/`hold` for every gesture of that button.
- **M4 seams (M4-1; the timers use all of them since M4-2, the Block Observer's `changed` the throttle).** `EventSpec.minInterval(Duration)`
  (D3) throttles an event on its `action` topic and Raw Link only: `DeviceTraffic.admitEvent` keeps one clock per event
  type (taken only when admitted) and counts what it holds back; the bus always carries the event; `build()` refuses a
  minimum interval on a bus-only event. `DeviceRuntime.publishEvent(host, event, rawPayload)` and
  `DeviceLink.publishEvent(be, event, rawPayload)` publish an event and its Raw Link payload under one throttle
  decision. The Diagnostics tab and `devices info` add "N events throttled" only when N > 0.
  `Builder.noV1Topic()` makes `DeviceLink.adopt` take no Raw Link from v1 slots (owner, name and legacy items still
  adopt). `Builder.status(StatusLine)` gives a type a read-only status line (`DeviceView.statusLine`, at most 64 code
  points, kept by `redacted()`, carried in `DeviceViewPayload`).
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
  pending command, last state, discovery hash, legacy unique_id, the twin binding's copy, since M4-14 `discoveryArea`),
  groups, wires, since M4-14 `rooms` (after `wires`, always written, decoded with `decodeAll`: duplicates and anything
  over 512 skipped and reported, sizes not judged again, names only for being blank or over 32 code points), migration
  flags and since M4-11 `players` (after `v1StatusCleared`: one `PlayerEntry` per player with a choice or a presence
  device; a damaged entry, a duplicate UUID or a second holder of a slug is reported, the second holder loses the slug;
  a missing list means every consent is unset), and since M4-22 the top-level `bridgeBroadcastDiscovered` (written only
  while true, a structural change: the bridge's broadcast text entity was discovered and its removal has not reached
  an online Home Assistant yet). Saved when created, on
  overworld saves when dirty, and at `SERVER_STOPPED`. A structural change (device, group, wire or room added or
  removed, slug, name or membership changed, a room edited, owner literal added, a player's consent, prompt, name or
  slug changed) is saved at the end of its tick (`tickEnd`, also in a paused
  server's pump), at most once a second counted from the last save; a failed one is tried again a second later (the
  error is logged once, then the recovery). Everything else (states, pending commands, discovery hashes,
  owner/access/expose copies, migration flags) waits for the autosave. A device broken in a loaded chunk is removed at
  once, but the structural saves keep its record (with its membership) until the next level save, because vanilla saves
  the changed chunk only some seconds later (at most once every 10 s per chunk); a crash in between restores block and
  record together.
- **Twin bindings** (M3-7) live in the block (`DeviceLink`'s optional `twin` key, `VERSION` stays 3; an unbound device
  saves unchanged) with a copy in the record (`DeviceRecord.twin`, lenient: a broken compound loses only the binding).
  It is the record codec's 16th field, DFU's `group()` limit: later twin fields nest inside the binding. The record's
  discovery state is one codec field of two flat keys (`discoveryHash`, `discoveryCleanup`, M3-7b; the compound layout is
  unchanged and `discoveryCleanup` is written only while true), which shows how a later key fits.
- **One command path** (M2-5, M2 plan §3.8 #2). `DeviceRuntime.command(target, json, source, origin)` is how every
  command reaches a device: HA's `/set` and `/set/<field>`, group commands and joins, Raw Link inputs (also a legacy
  group's v1 topic), `/mineqtt devices set`, the device screens and their command twins, and wires; the timers, the
  Block Observer and the Block Placer take theirs the same way (M4). The pure
  `DeviceRuntime.route` picks the route: a loaded device applies (`applyCommand`, then the state is published), an
  unloaded `QUEUE` device queues through `DeviceType.preview`, a group fans out, and the rest drops (an unloaded
  sensor, an unknown type). A group's members always see `CommandSource.GROUP`. Only the attach applies a queued
  command itself, as the second half of the `QUEUE` route. No block entity calls another or publishes MQTT directly.
  `route` has `FORWARD` and `REFUSE` (M3): a command to a port a bound device's source owns is forwarded (players and
  wires, with control on and the owner's `mineqtt:twin.control`, asked at route time) or refused (Home Assistant, groups,
  the paused Raw Link, control off, a refused binding) before any queue; `TWIN` applies only to a loaded device and is
  never queued or forwarded. A forward applies nothing (non-optimistic); it goes to the `MirrorForwarder`
  (`MineqttRuntime.forwardToTwin` → `TwinRequests`) with the current root and the acting player. A seed into a mirrored
  port is skipped, a bound device's queued command is dropped at its attach, and a bound device joins no group. A
  device with a source skips its type's power-on (at the attach and in `powerOnRecords`, M3-12): the source owns its
  state, so a restart never drops a door or a piston.
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
  packet. The device screens read the `DeviceView` only. Blocks that are no devices still send
  `saveWithFullMetadata`, which their screens read. The Display Panel's render compound (M3-15) holds either
  `lines` (at most 4 × 32, laid out on the server by `DisplayText`, under the device name as `title` while bound) or
  `at` (a point in time in epoch millis that each client words itself), plus the resolved size, the colour as a dye id,
  `glow` and `waiting` (bound, no value yet); it is sent at most 4 times a second (the last one always). The client
  bounds what it reads the same way. `DeviceBlockEntity.twinHealthChanged` lets a device react to its twin health (the
  panel's comparator reads 0 while its source has no value) and `DeviceHost.identityChanged` to a rename.
- **Display Panel renderer** (M3-16). `client/DisplayPanelRenderer` uses the 26.2 render-state API
  (`DisplayPanelRenderState`, `submitText(pose, x, y, text, shadow, POLYGON_OFFSET, light, colour, background, outline)`:
  the light comes before the colour, pinned with `javap` on `AbstractSignRenderer`; glow is `LightCoordsUtil.FULL_BRIGHT`).
  It is registered through Architectury on Fabric and through `EntityRenderersEvent.RegisterRenderers` on NeoForge
  (Architectury's NeoForge registry fills the provider map too late for the first resource load).
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
- **Lamp mounts** (P3). `RgbLedBlock` has vanilla's `face` (`ATTACH_FACE`: floor, wall, ceiling; default floor) beside
  the six-valued `facing` it always had. Lamps saved before P3 have no `face` and load as floor lamps (the state codec
  fills a missing property from the default state), keeping their `facing` (always `north`) and `light_level`; `facing`
  keeps all six values for that reason. The clicked face decides the mount; a wall lamp's `facing` points away from its
  wall, a floor or ceiling lamp's is the player's horizontal facing (it only turns the textures); a click on a block the
  lamp replaces (short grass) takes the first of the player's looking directions with a support, as buttons do.
  `RgbLedBlock.pointing` (up, down, or a wall lamp's facing; a command-made `face=wall,facing=up|down` points that way)
  drives the outline (one box per direction, also the collision), the survival rule (`Block.canSupportCenter` on the
  support's face, as a torch or a lantern; placement is refused without it), `updateShape` (the lamp becomes air when
  the block on its support side became replaceable, air, a fluid or short grass, and it cannot survive, so the level
  drops it and `DeviceBlockEntity.preRemoveSideEffects` destroys the device) and `bulbCentre` (the client ticker's
  particles). The blockstate rotates the one model like a button's (wall x 90, ceiling x 180); the item model is the
  unrotated floor model. Legacy lamps standing where no support is (0.6.0 placed every lamp upright, also against walls,
  on other lamps, repeaters and daylight sensors) stay until the block on their support side goes away: a support that
  only changes its state (a lamp below switching on changes its `light_level` and updates the lamp above from below) is
  not gone. For the same reason a new lamp whose support is swapped for a block that cannot hold it (a slab set by
  command, a trapdoor opened) stays, where a torch would pop.
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
  sensor count 0, input signal 0), keeping its settings and Raw Link. `BaseMqttBlockEntity` is now only the base of the
  two converters (the Humidity Sensor and the Static Publisher, until they convert); the timers, the Block Observer and
  the Block Placer are devices since M4-3, M4-9 and M4-10; `BaseMqttBlock.click` and `SetActiveTabPayload` take either through `TabbedScreenHost`.
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
  M4-1: an integral NUMBER with more than `Widget.NUMBER_FIELD_STEPS` = 10 000 steps is a `NUMBER_FIELD` (a typed,
  digits-only field with Set/Enter, red and unsent when out of range, validated again by the server) instead of a
  slider; the Climate Sensor's interval and the Presence Sensor's clear delay (3600 steps) stay sliders. A type whose
  Home Assistant switch reads a writable BOOL field shows that field's toggle instead of the `state` action row
  (`ControlsTab.collapsesState`; the action still works for commands, Home Assistant and wires), and a type with a
  status line draws it on the last row, so it may use at most four rows for controls.
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
- **Twin tab** (M3-13, M3-14, plan D19). Every type with a twin adapter has a Twin tab after Controls: `ui/device/TwinTab`
  (server-safe) lays the type's twin link settings out in at most five rows ending by y 95 (the source cycle and the
  entity's pick, the entity's name, id and area, the settings, and last the health line with Resume; a type with targets
  a row per target with its pick and a remover, its details below), and `TwinTabView` draws it. Every change is the
  binding key's `DeviceConfigPayload`, the command twin's key. What it shows comes as a `TwinView` (`TwinViewPayload`,
  apart from the `DeviceView`, with every view of a twin type and when a request starts or ends waiting), built per
  viewer by `DeviceRuntime.twinView`: without CONFIGURE it names no entity and no MQTT device. The source cycle
  (`TwinTab.SourceMode`: This block, Home Assistant, MQTT device) unbinds a bound device in one press and steps
  an unbound one This block → Home Assistant (opening the picker) → MQTT device; the step is kept on the `DeviceMenu`
  (`sourceChoice`) across the picker. A `BySource(homeAssistant, mqttDevice)` place shows the Home Assistant cell or
  the MQTT device cell (`Preset`: the preset cycle, `twin.preset`; `Device`: the device-name field, `twin.device
  "<preset> <name>"` on Enter, a click elsewhere or leaving the tab), so every twin key has exactly one place. The preset
  list is a `PRESET` search (`TwinPicker.presets`, CONFIGURE plus `twin.bind`, the type's source kinds, at most 40,
  resent after 1 s up to 3 times), kept on the menu (`presetResults`, `presetChoice`). `TwinView.device`
  (`Device(preset, name, device, topic, kind)`, `Device.HIDDEN` without CONFIGURE; at most one source per view) makes a
  preset-bound device read as bound with the Home Assistant twin off. `TwinView.rateRefused` (every viewer) and
  `rateLast` (CONFIGURE only) put the rate refusals on the health line; `TwinRequests.Listener.rateRefused` marks the
  view stale. The entity picker (`ui/TwinPickerScreen`) is a client screen over the device screen, whose menu stays
  open, so its payloads pass the device screen's guard: `TwinSearchPayload(pos, kind, key, query, domain)` (typing and
  the domain chip debounced 250 ms; the server's per-player bucket allows bursts of 6 and 4 a second; an unanswered
  search is resent after 1 s, at most 3 times, `TwinPicker.resendDue`) answered by `TwinResultsPayload` (repeating the
  kind, query and domain, so the screen shows only the answer to its last search, `TwinPicker.answers`; at most 40 rows
  grouped by area, or a notice) from the pure `ui/device/TwinPicker.answer`. On a device bound to an MQTT device its
  warning line reads "Binding replaces its MQTT device <name> (<preset>)." A target's search keeps only what Minecraft may control
  (`TwinPicker.ControlFilter`: controllable domain, `twin.haActionAllowlist`, the control label while the catalog
  decides); on a type with several targets the picker first asks which one (the Left/Right step). Resume is the press
  `twin.resume` (CONFIGURE), never copied.
- **Interaction grammar and Configurator** (M2-13, design §7.4). `blocks/Interaction.decide(sneaking, Held, type)` is
  the pure table `BaseMqttBlock.click` carries out (USE needed for anything but PASS): the Configurator opens the
  screen; sneaking opens it, except with a block item (PASS, vanilla places it); a block that is no device opens it;
  a renamed paper fills a device's channel (`paperChannel`), a named name tag renames it (`DeviceRuntime.nameTag`:
  `configure(name)` with the player's grants, answered in the action bar); a type with `DeviceType.use()` (declared
  with `Builder.use(actionKey)`, an action that toggles or fires; the Smart Lamp's `state`) performs it
  (`DeviceRuntime.use`: `action(key, Action.handValue())`, so it goes through `command`); else the screen opens.
  `CyberdeckItem.use` (the Configurator) steps its `ConfiguratorMode` (component `mineqtt:configurator_mode`) when
  sneaking, else picks a block up to `C2SGuard.CONFIGURATOR_RANGE` (16) and opens a device's screen, or the explorer
  (on its Devices tab since M4-16) when it hits no MineQTT block; in Room mode it takes a corner instead (M4-15, below). `C2SGuard.reaches(player, level, pos, slack)` is the reach of both the guard (slack 1)
  and `DeviceMenu.stillValid` (slack 4): same dimension, and the interaction range or, with a Configurator in either
  hand, 16 blocks where `Level.mayInteract` allows (spawn protection, world border: vanilla asks it before a click
  within reach, never before a use into the air, so `configureAtRange` asks it too). A hand without anything of meaning
  lends the other hand's Configurator (`Held.of(player, hand)`). A spectator only looks: `click` opens the screen
  whatever they hold and never passes (vanilla's spectator branch would open the block entity's menu unchecked),
  `DeviceAccess.grants` gives them nothing and `C2SGuard` drops their payloads (`SPECTATOR`); as a player who may not
  build they see a device's Controls tab alone (below).
- **Players who may not build** (P2). `DeviceAccess.mayBuild(player)` (`Player.mayBuild()`, which vanilla takes in
  adventure and spectator mode, and no spectator) gates everything but USE: `permits(need, mode, op, owner, mayBuild,
  claimTrust)` refuses CONFIGURE before the access mode is asked, an operator and the owner included, so
  `DeviceAccess.check` (both the block and the record variant), `grants` (USE at most: configure, expose and access
  false), `consolePermits` and every `C2SGuard` CONFIGURE payload (`DENIED`), `DeviceRuntime.configurer` (Link/Group
  offers, wires) and the explorer's device topics follow (its group and unregistered device topics go to an operator who
  may build only); USE stays the access mode's. `C2SGuard.reaches` grants the Configurator's range only to a player who
  may build, so `DeviceMenu.stillValid` and the payload reach check keep such a player within their hand's reach. `DeviceRuntime.view` gives such
  a player `DeviceView.redacted()` (`controlsOnly`: position, type, name, grants and state, the rest blank; the
  constructor refuses a controls-only view granting more than USE), and `DeviceMenu` builds its tabs with
  `DeviceTabs.forViewer(type, controlsOnly)` on both sides (the client reads the flag from the view in the open data):
  the Controls tab alone. `MineqttNetworking.sendDeviceStatus`/`sendDeviceLinks`/`sendDeviceGroups` skip a
  controls-only screen (`DeviceMenu.controlsOnly(player)`). `DeviceBlockEntity.openScreen` refuses a type without
  controls, in the action bar (`mineqtt.game_mode.*`; a timer opens its Controls tab alone, whose *Trigger now* is
  USE). `BaseMqttBlockEntity.openScreen` has no caller since the timers became devices in M4-3 (the remaining legacy
  block entities are the converters, whose blocks are no `BaseMqttBlock` and convert on their first tick), so its old
  refusal `mineqtt.game_mode.no_screen` was removed in M4-20; a future caller must refuse a player who may not build
  first;
  `BaseMqttBlock.click` refuses PAPER, RENAME, LINK, GROUP and COPY, and `CyberdeckItem.configureAtRange`/`stepAtRange`
  refuse every MineQTT block, likewise (`mineqtt.game_mode.uses_only`). The Bridge Console's view is `operator` only for
  an operator who may build, so its buttons and its lists go. `TabbedMqttMenu` remembers `mayBuild` at its making;
  `TabbedMqttMenu.abilitiesChanged` (`TickEvent.PLAYER_POST`, registered in `MineQTT.init`, not the runtime, as block
  screens open without MQTT) closes a block screen whose player's `mayBuild` changed and asks the block's
  `openScreen` again after a USE check, if the old screen was still valid for them (a device's screen opened from afar
  is not reopened for a player who may no longer build). The explorer (`CyberdeckMenu`) keeps its topic slots only while open (`clearContainer` on close);
  listening and private mode live in `CyberdeckSessionManager` and come with the open data. The old `listening`,
  `private_mode` and `inventory` components stay registered, deprecated, and `CyberdeckItem.inventoryTick` strips
  them, giving the slots' items back (`CyberdeckDataUtil.stripDeprecated`).
- **Access and Diagnostics tabs** (M2-11, design §6.9, §8.7). Every device has both, after Advanced. Access shows the
  owner (`DeviceView.Ownership`: nobody, known, or a v1 name not verified yet; whether a claim mod decides claim trust)
  and the `access` link setting as a cycle gated ACCESS: `DeviceAccess.grants(op, owner, mayBuild, mode, claimTrust,
  mayExpose)` gives the access grant only to the owner or an operator who may build, so no mode locks the owner out. A device without an owner
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
  (its actions) is for operators who may build only (P2). `BridgeConsole` (one per `MineqttRuntime`) builds each viewer's `ConsoleView`:
  the bridge (`mqtt.state()`, the redacted broker, protocol, `BirthWatcher.heard()` (birth or last will, since the
  server started or the topic changed), the pause state, `MqttService.Stats` drops and queues, the wire count with the
  bus's delivered and dropped totals), since M4-17 the `ConsoleView.World` (sampled live at each refresh through
  `runtime.telemetry().sample()`, so it shows without a broker or with telemetry off: tps, mspt, time of day, day,
  day or night, weather, players online, and for an operator only the names the name policy allows, at most 16, the
  rest counted; plus `TelemetrySampler.statusLine`, the line `/mineqtt status` prints too), the devices the viewer may configure (`DeviceRuntime.configurer`; all for an
  operator; faulted, unknown type and Raw Link problem first; at most 64 rows) and the groups they manage
  (`GroupRules.manages`; all for an operator; at most 32). It travels in the tabbed menu's open data
  (`TabbedMqttMenu.writeOpenData(..., ConsoleView)`, which builds a `ConsoleMenu` on the client) and in
  `ConsoleViewPayload`, which `tickEnd` sends every open console once a second. `BridgeActionPayload(pos, BridgeAction)`
  (a closed enum: reconnect, republish, prune; an unknown ordinal does not decode) passes `C2SGuard.require(...,
  TerminalBlockEntity, CONFIGURE)`, and `BridgeConsole.act` checks the op level again and runs the command's own method
  (`MineQTTCommands.reconnect`/`republish`/`prune`) with the player's command source, then resends the view. The
  Bridge tab's rows are MQTT (the broker on hover), Topics, HA, Server, Traffic (drops and wires), World and Players
  (the telemetry line on hover). `ConsoleView.state`, `typeName` and `dimensionName` are shared with the explorer's rows.
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
- **Slots closed to automation** (M2 plan D7). `BaseMqttBlockEntity` (the legacy blocks since M2-12) is a
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
  paper), else to its owner (at once, or queued until they join or open a screen), plus the owner's action bar; an
  edit that lands on `mineqtt/shared/` tells the editor too. `NoticeGate` tells the same refusal once per device. Open
  screens get `DeviceStatusPayload` (status line and problem, cut to 512 chars). A config reload that changed the ACL's
  rules calls `DeviceRuntime.aclChanged()` alone (M4-20): links with a channel are resolved again
  (`RawTopics.recheck`), other links and legacy groups' v1 topics open or close as the new ACL allows, and an output
  that works again publishes its current value. Fabric dedicated servers have no config reload; NeoForge reloads on file change.
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
- **Timers** (M4-2 to M4-5, plan D2 to D5). The schedules are pure: `device/timer/core` (`verifyCoreIsolation` scope
  `device.timer.core`: JDK, `GameClock`, `CronSchedule`) holds `TimerKind` (the four kinds with their block ids and pulse
  lengths: 1 tick for interval and cron, 30 for the day kinds), `TimerNow` (game time, overworld clock, wall clock, zone,
  tick rate, frozen), `TimerState` (v1's counters: tick counter, last trigger day, tick and time), the sealed
  `TimerSchedule` (`IntervalSchedule`, `DayTimeSchedule` with its ±20-tick window counted on the day of the moment it
  fires around, so sunrise fires once, `CronTimerSchedule` on the wall clock in `TimerNow.zone`), `TimerStep` (one tick:
  NONE, FIRE or PULSE_OFF, and a manual trigger), `NextFire` (ISO-8601 with offset or `""`; the 5 s deadband and the
  60 s resync) and `TimerStatus` (the status line). `device/types/Timers` builds the four types from one factory:
  `enabled`, `next_fire` and the kind's schedule fields, the `state` and `trigger` actions, the `fired` event with its
  `source` (`schedule`, `manual`; 250 ms on MQTT, every fire on the bus), Home Assistant's switch, event, *Trigger now*
  button, timestamp sensor (template `… if value_json.next_fire else None`), config number/select/text and location;
  `UNAVAILABLE`, `noV1Topic()`, a status line, and `RawLinkSpec.events()` publishing `ON` per fire through
  `DeviceLink.publishEvent` (never retained). One `blocks/TimerBlock(kind)` and one `TimerBlockEntity extends
  DeviceBlockEntity` serve the four unchanged block and BE ids (`TimerBlockEntity.create(kind, …)`). The BE keeps
  `Timers.Settings` under `mineqtt.state` (defaults omitted; the time of day in ticks, so a v1 time off the minute stays
  exact) and `TimerState` under `mineqtt.timer`; it ticks whether or not it is attached (redstone works without a
  broker), arms on enable without firing, restarts the count on an interval or unit change, refuses a trigger during a
  pulse with its reason (counted as dropped), and recomputes `next_fire` on each fire, edit and attach and every 60 s,
  showing a new value only when it moved by more than 5 s. v1, 0.6.0 and 0.7.0 timer data (all v1-shaped) is read by
  `LegacyNbtV1.hasTimerKeys`/`timerState` and written back by `writeTimer` until the attach adopts it; `DeviceLink.adopt`
  drops a `noV1Topic` type's v1 private mode and last tab. The Cron Timer's *Schedule* tab (`ui/device/CronScheduleTab`,
  server-safe, with `CronScheduleTabView`) is the one custom tab `DeviceTabs.forType` adds (after Controls, for types
  with the Cron Timer's `cron` setting); it sends `cron` through `DeviceConfigPayload` like any setting. `util/CronSchedule`
  steps `*/n` from the field's first value and `v/n` from `v` to the field's last.
  **A standing clock**: the pure `device/timer/core/ClockWatch` is fed at
  `MineqttRuntime.tickStart` (SERVER_PRE, so never by the paused server's pump, which runs no tick) with the clock's real
  state, never guessed from its movement: `gamerule advance_time`, the overworld clock's `ClockState` (`paused` from
  `/time pause`, `rate` from `/time rate`, read through `WorldClock.overworldState`, i.e.
  `ServerClockManager.packState()`) and `isFrozen()`. The clock runs while `advance_time && !paused && !frozen`, so a
  slowed clock (a rate below 1 leaves its tick count standing on some ticks) keeps running. On a change of running or rate
  `DeviceRuntime.forEachAttached` calls `TimerBlockEntity.clockChanged()`, which recomputes and publishes.
  `TimerNow.clockRunning` and `TimerNow.clockRate` (from `WorldClock.running`/`rate`) make `DayTimeSchedule.next` empty
  while the clock stands and stretch its estimate by the rate (`TimerNow.serverTicksFor`); frozen ticks empty every
  tick-based kind (interval, day kinds), while an interval keeps its next fire with `advance_time false` (it counts block
  entity ticks) and cron is untouched (wall clock).
- **Block Observer** (M4-9, plan D12). `device/types/BlockObserver` (`mineqtt:block_observer`, on the
  `block_sensor_block` ids): `block`, `properties` (a JSON map, the HA sensor's `json_attributes`), `signal`,
  `change_on` (`block`/`state`), `payload` (`full`/`block_id`/`name`/`properties`, its HA select disabled by default) and
  `location`; the `changed` event (`block`) throttled to 500 ms, the state to 1 s; `UNAVAILABLE`; Raw Link
  `RawLinkSpec.out(…).withoutCustomPayloads()`. `BlockSensorBlockEntity` is a `DeviceBlockEntity`: once attached it looks
  every tick (never loading the target's chunk, `hasChunkAt`), reads the comparator level with every new state and every
  4 ticks otherwise, publishes `changed` through `publishEvent` and the payload through `publishRaw` unthrottled (on
  every change, the first look and a `payload` change; `full` also every 10 ticks when it differs). `full` carries
  `block_entity` only with `blockObserver.exportBlockEntityData` and `PermissionManager.sameClaim(level, observer,
  target)` (true without Flan; with Flan, one permission container for both positions), cut at 16 KiB UTF-8 to
  `"block_entity":null,"truncated":true`. v1 and 0.7.0 sensors adopt their topic as a grandfathered, unretained Raw Link
  output (`LegacyNbtV1.hasSensorKeys`/`sensorState`/`writeSensor`), the data mode as `payload`. Its `signal` resolves to
  the `power` of a Redstone Output and a Smart Plug by default.
- **Block Placer** (M4-10, plan D12). `device/PlacerRules` is the rule set: `parse` (the setblock form or `{"id":…,
  "properties":{…}}`; braces, `block_entity`, other keys, tags and trailing text refused), `hardDenial` (block entity,
  game master block, TNT, unbreakable, fluid, fire, air, portal, a two-part block (`DOUBLE_BLOCK_HALF`), a block without
  an item), `check` against `#mineqtt:placeable` (`data/mineqtt/tags/block/placeable.json`) or, while
  `containerUnlocks(consumeItems, blockPlacer.containerUnlocks)`, any block (`allowedBlocks`: placing costs the item, so
  only what somebody put into the container), `options` (the tag without hard denies, sorted), `choices` (the blocks of the
  block items in the container behind, through the face the placer touches, without hard denies), `claimAllows`,
  `underSpawnProtection` (the dedicated server's rule for an offline owner) and `take`/`give` (the container behind,
  sided faces respected). `device/types/BlockPlacer` (`mineqtt:block_placer`, on the `block_emitter_block` ids): the
  `block` setting, `placed`, `last_result`, `choices` (a JSON list, kept in the block entity's `state` with the setting),
  `location`, the `place` and `clear` actions; HA's `Ha.selectDynamic("block", …, BlockPlacer::options)
  .optionsFrom(CHOICES)`: the options are the device state's `choices` while it has entries
  (`HaEntity.Select.currentOptions(state)`, discovery built from `record.lastState()`), else the tag; with the template `{{ value_json.block.split('[')[0] if
  value_json.block else None }}`, buttons, a binary sensor and a diagnostic `last_result`; `UNAVAILABLE`, `editsWorld()`;
  Raw Link `RawLinkSpec.in(ValueCodecs.PLACER)` with `acceptRetained` false (`place`/`ON`/`true`, `clear`/`OFF`/`false`,
  anything else a block to set and place). `BlockEmitterBlockEntity` (a `DeviceBlockEntity`) runs a command as: the action
  gate (`blockPlacer.enabled`, then a 4-per-second `TokenBucket` that refused actions spend too), the block against the
  live tag, then place (world border, spawn protection and the claim for its owner, a replaceable target or its own
  block, the item taken, `setBlock` with sound and game event; without properties the dispenser's state) or clear (its
  own block only, no drops, the item given back); a block that cannot stand in front (`canSurvive`) is refused. Every
  100 ticks, after its own place or clear and when its block is set, it reads the container behind again
  (`refreshChoices`, only through `containerAllowed` and only while the chunk behind is loaded; `BlockPlacer.choices` adds
  the block it is set to) and publishes a changed list; `DeviceRuntime.publishState` refreshes the discovery of a device
  whose state list changed (`stateOptionsChanged`). A refusal sets `last_result`, publishes the state and returns
  REJECTED. It tracks the block it placed through its own state changes (`PlacerRules.follow`: the same block in any
  state stays its own, so a lamp it placed stays clearable while redstone lights it) and lets go on any other block;
  `clear` compares a fresh `getBlockState` with the record (`PlacerRules.ownBlock`) right before `destroyBlock`. Broker
  input never loads a chunk: `place` and `clear` are refused with `FRONT_NOT_LOADED` while the space in front is not
  loaded, and with `consumeItems` `place` also with `BEHIND_NOT_LOADED`. The container behind follows the target's claim
  rule (`claimAllows(claimMod, sameClaim(placer, behind), owner online && PermissionManager.canOpenContainer(owner,
  behind))`, Flan's OPENCONTAINER); refused, `place` answers `BEHIND_CLAIMED`, and `clear` still clears its own block
  but drops the item where it stood, as it does when the container's chunk is not loaded or the container is full. While
  `blockPlacer.enabled` is off it subscribes to nothing: `DeviceHost.rawInputOpen()` and one guard in
  `DeviceRuntime.openRaw`, with `DeviceLink.rawInputChanged`/`DeviceRuntime.rawInputChanged` following the flag (the
  Advanced tab still shows the link as set). `DeviceType.editsWorld()` with `DeviceConfig.mayConfigure(type, op,
  playersMayConfigure)` withholds all of CONFIGURE from a non-op on a dedicated server unless
  `blockPlacer.playersMayConfigure`, applied in `DeviceAccess` (block entity and record checks and the grants), so
  screens, C2S, paste and lists agree; the owner keeps the access mode. `runtime/ServerHooks` refreshes the select's
  options on `LifecycleEvent.TAGS_UPDATED`. v1 emitters adopt their topic as a grandfathered Raw Link input.
- **Container Observer** (after 0.9.1). `device/types/ContainerObserver` (`mineqtt:container_observer`, block
  `container_observer`, placed with its face against the block clicked): `container` (the block id, or a container
  entity's type id), `items` (a JSON map item id → count, sorted by id, at most `MAX_ITEMS` 64 ids, the most numerous
  first, within `MAX_ITEMS_BYTES` 8 KiB of ids), `total`, `distinct`, `slots_used`, `slots_total`, `fill` (percent as a
  comparator measures it, one decimal, 0 only when empty and 100 only when full), `empty`, `full`, `status`
  (`waiting`, `ok`, `no_container`, `claimed`) and `location`; no settings, actions, events or Raw Link (no Controls
  tab; the Advanced tab says there is no Raw Link), `UNAVAILABLE`, state at most once a second. Home Assistant gets one
  fixed set of entities whatever the container holds: the fill as the primary sensor with `items` as its
  `json_attributes`, *Items*, *Slots used*, the binary sensors *Empty* and *Full* (each value template gives `None`
  unless `status` is `ok`, so they read unknown without a container; `HaEntity.BinarySensor` took a `valueTemplate` for
  this), the diagnostics *Status* and *Container*, and the disabled location. `ContainerObserverBlockEntity` looks every
  `SAMPLE_TICKS` 10 ticks once attached (offset by position), through `HopperBlockEntity.getContainerAt` (double chests
  combined, container entities included), reading every slot as a comparator does, and publishes only a changed `Scan`.
  It counts by item id alone: names, enchantments and other components never leave the server. It never loads the
  chunk in front (no look while it is not loaded). It reads only under the Block Placer's claim rule for the container
  behind it (`ContainerObserver.mayRead` = `PlacerRules.claimAllows(claimMod, sameClaim(observer, container), owner
  online && canOpenContainer(owner, container))`), asked at every look; refused, the state is `Scan.claimed`: the
  container's id and nothing it holds. Only the link is saved: the scan is taken again after every load, and the
  attach's `writeState` looks at once, so a state read while the owner could open the container is never published
  again after a restart, and Home Assistant gets the contents rather than `waiting`. `verifyContainerObserver` covers
  the scan, the caps, the fill and the claim rule.
- **Rooms** (M4-14, plan D11; `rooms/`). `Room(id, name, dimension, min, max, owner, forAll, created)`; the codec
  normalises the corners. `RoomRules` (pure): names (sanitised, 1-32 code points, unique per owner ignoring case),
  size (each axis `max - min + 1 <= rooms.maxSize`), counts (`limits.maxRoomsPerPlayer`, ops exempt; 512 per world for
  everyone), `mayEdit` (owner or op), `visibleTo` (own, for everyone, or op), `PRECEDENCE` (volume, then created, then
  id) and `resolve` (a name, own room first, or `"#<8 hex>"`; ambiguous answers list the ids). `RoomIndex` (rebuilt by
  every registry room edit) derives a device's room from its position: the smallest room of its owner or for
  everyone that holds it; `affected(records, before, after)` is the dirty set of an edit (devices inside the old or new
  box the room applies to; a stranger's room touches nothing) and `commonRoom(members)` a group's room. Server-made
  rooms (console, RCON, command block) belong to `Util.NIL_UUID` and are for everyone. `RoomEdits` is the single edit
  path (`Actor`; `createProblem`, `buildProblem`: `DeviceAccess.mayBuild`, one dimension, `Level.mayInteract` and
  CONFIGURE in the claim at both corners) whose `Result` carries the affected devices; `DeviceRuntime.roomChanged(before,
  after)` refreshes their discovery and their groups' and marks their views changed. `DeviceView.Registration` carries
  `room` and `keptArea` (`Registration.keptArea(retained, discoveryArea, suggested)`), which `GeneralTabView` shows as a
  room row ("Kitchen *" with a tooltip when the area Home Assistant was first told differs from the room);
  `TabbedMqttMenu.GUI_HEIGHT_NO_INVENTORY` grew to 108 for it. `/mineqtt rooms` has no requirement on the literal or its
  leaves; the leaves judge the source.
  **All on and All off** (M4-19): the pure `rooms/RoomSwitch` picks the targets (`targets(records, room, roomOf, finds,
  hasState)`: the devices `RoomIndex.roomOf` puts into that room, so a nested room's device belongs to the nested room,
  whose type `switches` (groupable and with a `state` action: lamps, plugs and outputs; a timer's `state` arms it, so
  timers are left alone) and that the actor finds by the explorer's rule; at most `MAX_DEVICES` 64, the rest counted as
  skipped) and the rate (`due`: one switch a second per player, `MIN_INTERVAL_MILLIS`; the console has no limit).
  `DeviceRuntime.roomSwitch(room, on, player, finds)` sends `{"state":"ON|OFF"}` through `set(record, cmd, player)` (a
  `PLAYER` command; queued for an unloaded device that queues) and keeps the per-player timestamps in `roomSwitches`
  (a monotonic clock); `RoomSwitch.admit` drops every entry older than the interval before it judges a switch, so the
  map holds only the players who switched within the last second. `RoomSwitch.Result.describe` is the one reply of the explorer's header buttons
  and `/mineqtt rooms switch <room> on|off` (a player: their devices and not in adventure mode; the console: every
  such device in the room).
  **Home Assistant area names** (M4-23): `twin/core/AreaNames.key` is Home Assistant's own area-name normalisation
  (casefolded, spaces removed, so `livingroom` matches `Living Room`). `EntityDocuments.areas()` (cached, invalidated
  with the catalog) lists the catalog's distinct areas, `areas(EntityGlobs)` those the read gate lets through, and
  `TwinRuntime.areas()`/`readableAreas()` expose them. `RoomRules.area(roomName, areas)` gives Home Assistant's
  spelling, else the room's name, and `DiscoveryPublisher.setAreaNames(twin::areas)` (wired in `MineqttRuntime`) sends
  it as `sa`. The spelling applies at a device's next refresh, not when the catalog arrives (no republish hook: the
  catalog comes about 5 s after the connect's republish). `ConfiguratorRoom.suggestions(registry, actor, areas)` adds
  up to `AreaNames.MAX_SUGGESTIONS` 16 area names after the room names, only to a player who may bind
  (`AclContext.mayBindTwin`: ops and `mineqtt:twin.bind` holders on a dedicated server, everyone on an integrated one),
  only names that pass `RoomRules.nameProblem`, deduplicated by the key against the suggested and the player's own
  room names.
  **The Room mode's clicks**: `BaseMqttBlock`'s `RIGHT_CLICK_BLOCK` handler takes a click on a block that is no MineQTT
  block in Room mode only when the player sneaks (it picks the room there). An ordinary click goes to the block first
  (doors, chests, levers work, also while a corner A waits); a block that does nothing with it returns PASS, so the
  client uses the item and `CyberdeckItem.use` → `ConfiguratorRoom.clickAtRange` makes that block a corner. A block with
  its own use becomes a corner when clicked from beyond the hand's reach. A MineQTT block keeps `Interaction.decide`'s
  ROOM outcome, so a device is always a corner. Both sides decide alike, so the client never sends a second click for
  one the server took.

## Flows

The seed of the Node-RED-style flows (M2 plan §3; design §13). Devices are nodes, their derived `Port`s are the node
ports, and wires join an out-port to an in-port. `flow/core` holds the Minecraft-free part:

- **`Endpoint`** is sealed: `PortRef(node, port)` and `GroupPortRef(group, port)` are built by wires;
  `HaEntityRef(entityId, port)` and `TopicRef(topic, path)` are the ends of wires derived from device bindings (M3).
  The registry codec (`device/WireCodecs`, DFU, so it lives outside `flow/core`) never stores those ends nor
  `Scope.TWIN` (derived wires are never stored), and refuses `Scope.NETWORK` until data cables exist;
  `RegistryWiring.problem` refuses them as wire ends.
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
  suppressed; a refused or failed delivery is not remembered, and events are never suppressed. `Sink.deliver`
  returns `MessageBus.Outcome` (`TAKEN`, `REFUSED`, or `ROOT` for a refusal because of the message's root). A wire back to the
  message's own source port is skipped, and the `Sink` skips the source's own member when it fans out to a group.
  Every drop is counted by `MessageBus.Drop`.
- **Hops** count the wires a message travelled over. Whatever is emitted while the bus delivers a message (on the same
  stack, through `applyCommand`, `publishState` or a neighbour update) carries that message's hops, so hops never go down
  the command path, and a zero-delay cycle is cut after 16 wires (`Listener.loopCut`, once per wire). There is no
  same-msgId dedupe, so a diamond delivers both branches. A delivery may not drain the bus again.
- **Roots (M3).** Every message carries a `Root` (`WORLD`, `PLAYER`, `RAW`, `HA`, `TWIN`, `STARTUP`): what caused its
  chain. `MessageBus` keeps one current root: a delivery's is the delivered message's, and the runtime opens extents
  with `withRoot` (a command: its source's root per `DeviceRuntime.root`, GROUP and BINDING inherit; `powerOnRecords`
  and the attach: `STARTUP`; a Raw Link input's broker message: `RAW`, like every device's). A message emitted outside both is `WORLD` (block tickers, neighbour updates,
  hands), so a same-tick neighbour cascade inside a command inherits that command's root. Held values and drain
  carry-over keep their root. A nested extent never clears `RAW`, `HA`, `TWIN` or `STARTUP` (`Root.nested`). A sink
  refuses for a root with `Outcome.ROOT`, counted as the drop `root`. Roots are transient and never persisted; the bus
  counts emitted messages by root (`FlowCounters.roots`, shown by `/mineqtt status`), which the `wires` client suite
  reads. The twin's gate (`TwinGate.mayRequest`, M3-9) lets only `WORLD` and `PLAYER` (`Root.inGame()`) cause a Home
  Assistant request: a forward or a delivery to a Home Assistant end refused for its root is `Outcome.ROOT`, logged
  once per device and root. The TWIN apply path has no call edge to `TwinRequests`, and every request passes
  `TwinGate` first (step 1 of the D10 loop-safety proof).
- **Twin wires (M3-8).** A bound device's source wire `HaEntityRef(entity, port) → PortRef(device, primary)` is
  derived (`TwinWiring`, `Scope.TWIN`, never stored) while the device is loaded with a working binding and the twin is
  on. It carries the entity's whole document unmapped (no mapping or kind coercion; hops, root, change filter and
  counters apply); `DeviceRuntime.applyTwin` emits it in a `TWIN` extent, and the sink lets the device's binding read
  it (`DeviceType.twinCommand`) and applies it as `CommandSource.TWIN`. A forwarded command becomes requests through `TwinRequests`; a request waits for
  its entity's next apply, a refusal or 5 s, and the device's clients see it pending. More than 8 requests the world
  caused in 10 s pause the device's control (`CONTROL_PAUSED`) until a player resumes it; a player's own commands are
  capped by the device and player windows instead (`TwinRequests.countsForBreaker`). An unloaded device routes by its
  record's copy of the binding judged for its owner under the rules in force (`DeviceRuntime.binding`).
- **Mirroring types (M3-12, M3-15).** The Redstone Output and the Smart Plug mirror like the lamp: the Output reads
  on/off as 15/0 (`twin.invert` swaps), numbers over `[twin.min, twin.max]` (else a default per attribute, else the
  entity's own range), option indexes and `twin.path` attributes; switch or analog is chosen by the source and the path,
  not by its `mode`. It writes back switching, `set_value`/`set_percentage`/`set_cover_position`/a light's brightness
  and `select_option`, mapped back through the entity's document (`HaCalls.Mirror.document`). The Display Panel mirrors
  any readable domain read-only (no control key; `TwinRules.edit` refuses it), its comparator level riding as
  `TwinAdapter.Extras`.
- **Target wires (M3-14).** A targets-only adapter maps an out-port's values to calls (`TwinAdapter.Target`: the
  Redstone Input's edge rules on `powered`, the Wall Switch's press on `left`/`right`). `TwinWiring` derives one
  `Scope.TWIN` wire per working target, `PortRef(device, outPort) → HaEntityRef(entity, inPort)`, which
  `RegistryWiring` routes (`in` gives the entity's in-port) and `MessageBus.routeTwin` carries as the out-port's value,
  checked against its kind. `DeviceRuntime.delivers` sends a wire that ends at an entity to `deliverTarget` before the
  TWIN-scope test (a target wire is a TWIN wire too), and `HaSink` applies the root gate, then the adapter's
  translation, then `TwinRequests`. The target entity's `state` and `last_updated` are held (`TwinRuntime.holdTargets`)
  for toggles, dedupe and the pending state. Target requests carry no player (the bus has no actor). A new source or
  target must be shared by the catalog while one answers (`TwinRuntime.shareProblem`). A catalog change refreshes the
  devices whose targets switch the entity (`TwinWiring.targetDevices`); `TwinRules.targetHealth` gives `NOT_SHARED`
  while a target's entity is unreadable and heals when it is listed again, and `deliverTarget` drops a press to an
  unshared entity in catalog mode with one warning per device and entity.
- **MQTT device presets (M3-17).** A preset source is `TopicRef(stateTopic, "")` with `Settings.preset` (id, device,
  kind). `TwinRuntime` holds the state topic (one shared `SubOptions.MIRROR` subscription per topic, judged with
  `TopicAcl.checkTopicSource` for the owner), keeps its last payload and coalesces it; the topic source wire carries a
  fingerprint (`{"payload":"<hex>"}`), and the device reads the payload through its own loaded preset
  (`presetDocument`), so the adapters read it as an entity of the preset's kind. The write-back renders the adapter's
  `Call` through the preset and publishes it on the command topic (QoS 1, not retained, never late) behind the same
  root gate, rates and breaker, after `checkPublish` for the owner at every send. Presets need `mineqtt:twin.bind`
  but not the Home Assistant twin (`twin.allowedEntities`, a statestream base); an edit of the preset or the device's
  name (`TwinRules.presetSource`) always drops control, whatever the kind; `TwinRules.recheck` follows a moved
  topic, drops control on a new kind and refuses while the preset is gone.
- **Catalog (M3-11).** `HaEntityCatalog` subscribes `<base>/_mineqtt/catalog/+` and `<base>/_mineqtt/status` (retained,
  up to 256 KiB, so an over-cap list is refused visibly) 5 s after the first CONNACK, and the fallback scan 10 s after it
  while no status came; a status closes the scan and a cleared one reopens it. `MineqttRuntime` publishes
  `bridge/ha_sync` 5 s after every CONNACK and every Home Assistant birth (`BirthWatcher.onBirth` listeners hear each
  live birth at once; retained births are ignored), and the twin downgrades freshness at the birth itself. Drift
  (two catalog `last_updated` moves without a live statestream value) is reported for mirrored entities only, never for
  `HaDomains.TRIGGER_DOMAINS`.

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
- **Drains**: after `drainInbound` (`MineqttRuntime.handleInbound`, so also in the paused-server pump: attaches →
  inbound drain → twin drain, which emits the settled entities in a `TWIN` extent → bus drain → twin health refresh),
  and at the start of `DeviceRuntime.tickEnd`, before the throttled states, the views and the flush. The runtime's
  end of tick runs the twin requests (held values, timeouts) before the devices' `tickEnd`. `emit` marks work for a
  paused server (`workQueued`), and `hasPendingWork` counts what the bus still holds. `stop()` clears the bus;
  removing a wire, a device or a group forgets what the bus kept for them.
- `/mineqtt wires list | add | remove` is the command twin of Link mode (a port is `<device>.<port>` or
  `group.<group>.<port>`; without a mapping `DefaultWiring.mapping`: an event into a toggling state toggles, anything
  else follows). `DefaultWiring.of(fromType, toType)` gives the pair Link mode offers first, the first match of the D15
  list (M4-1, `DefaultWiring.pairs()`): `press` into `trigger` follows, `fired` into `trigger` follows, `powered` into
  `state` follows, `press` into `state` toggles, `occupancy` into `state` follows, `signal` into `power` follows, `fired`
  into `state` toggles. Among the 0.7.0 types this resolves exactly as M3 did (`verifyRuntimeWiring.defaultWiring`
  compares all ordered pairs with a copy of the M3 list). The shipped M4 types exercise the rest
  (`shippedTimerWiring`): a Wall Switch's `press` fires a timer (Trigger now), a timer's `fired` fires the next, a
  Redstone Input's `powered` and a Presence Sensor's `occupancy` arm a timer through `state`, a timer's `fired` toggles a
  lamp, an output or a plug; the Block Observer's `signal` follows into the `power` of a Redstone Output and of a Smart
  Plug. A chain of timers wired `fired` → `trigger` is cut at the bus's 16 hops. `/mineqtt status` shows the wire count and the bus counters; console and
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
- **Bind mode** (M3-13): `ConfiguratorMode.BIND`, last in the cycle; `Held.CONFIGURATOR_BIND` gives `Outcome.BIND`
  (sneaking opens the screen). `items/ConfiguratorBind` checks the clicked device (`ConfiguratorTarget`, CONFIGURE) and
  opens its screen on the Twin tab with a one-shot picker request in its first `TwinView` (`""` asks which target
  first); a device without a twin adapter is told so. No offer is kept: the picker's payloads pass the device screen's
  guard.
- **Room mode** (M4-15, plan D11): `ConfiguratorMode.ROOM` (`room`, ordinal 5, after Bind); `Held.CONFIGURATOR_ROOM`
  gives `Outcome.ROOM` whether sneaking or not. Within hand reach the `BaseMqttBlock` RIGHT_CLICK_BLOCK handler sends
  only a sneaking Room-mode click on a block that is no MineQTT block to `items/ConfiguratorRoom.clickBlock` (the room
  pick); an ordinary click goes to the block's own use first, and a block that does nothing with it passes the click on
  to `CyberdeckItem.use`, whose `clickAtRange` makes it a corner (see *The Room mode's clicks* under Rooms). `click()`
  handles ROOM before the USE check (a device is always a corner and never used); beyond reach `CyberdeckItem.use`
  calls `clickAtRange` (a pick within 16 blocks, else cancel; a sneak-use there steps the mode as in every mode). Corner A, then B: B is judged by `RoomEdits.createProblem` and either offers a `RoomOffer` through
  `RoomPromptPayload` (S2C: offer, size, existing name, at most 32 suggestions, `mayForAll`) or tells the refusal in the
  action bar and keeps A. A sneak-click within reach picks the smallest room the player may edit that holds the block.
  `ui/RoomNameScreen` answers with `RoomNamePayload(offer, Op {CREATE, RENAME, REMOVE}, name ≤ 132 bytes, forAll)`
  through `C2SGuard.requireOffer(player, payload, mode, offerOpen, dimension, AABB box, permitted)` (token, not
  spectator, offer open, Configurator in mode, same dimension, within 17 blocks of the box, `mayBuild && permitted`;
  pure `boxOfferFacts`), then the same `RoomEdits` path the commands use. Any answer that passes the guard uses the
  offer up (120 s lifetime). Build rights are checked at the clicked corners when offering and at the normalised
  corners when applying. `ConfiguratorRoom.tick` (SERVER_POST, registered in `MineQTT`) outlines the pending box and up
  to 4 rooms within 32 blocks with dust particles sent to that player only (`overrideLimiter`), at most 512 points every
  10 ticks, while they hold the Configurator in Room mode and may build.
- **Devices tab** (M4-16, plan D13; `ui/explorer`). The explorer's tabs are Devices (`cyberdeck_devices`, opened first),
  Settings, Traffic (the former Explorer tab, id `cyberdeck_explorer` unchanged) and Publish. `ExplorerQueryPayload(query
  ≤ 64, room ≤ 32, page)` passes `C2SGuard.requireCyberdeck` and the open menu's own `TokenBucket` (burst 6, 4/s,
  `CyberdeckMenu.takeExplorerQuery`); `ExplorerDirectory` answers with `ExplorerPagePayload(ExplorerPage)` (≤ 64 KiB;
  the page echoes query, room and page so the client drops stale answers, and carries its `Scope`). Scope: `ALL` for an
  operator who may build, else `OWN` (the viewer's own devices that they may also configure; another player's device is
  never listed, whatever its access mode or the claim), `NONE` for a player who may not build. Search over name, slug,
  model and type id (normalised: stripped, sanitised, lower case), pages of 50. Since M4-18 the rows are grouped by
  room: rooms first (by name, then id), devices in no room last, by name then slug inside each; the `room` filter
  matches a room's name ignoring case (two same-named rooms of different owners pass together in an operator's view).
  A row (`ExplorerPage.Row`) has its `Kind` (DEVICE or GROUP), name, slug, model, availability (online, no broker,
  queued, unavailable, faulted, unknown type, from `ConsoleView.state` and the type's `UnloadedPolicy`), a detail, up to
  3 values from `type.outputs` (no settings, no `location`; sanitised, cut to 32 code points), dimension, distance, an
  8-way bearing, `usable` (the type has a `use()` action), its room as an index into the page's `rooms`
  (`RoomRef(id, name, devices)`, at most 64, the rooms of the devices the player finds whatever the search) and a
  group's member count. A room header does not say whether the room's name matches a Home Assistant area (D13's
  "HA area" marker is not built). Group rows are listed for scope `ALL` only, without actions. The `You` record (`You.of`: the
  player's choice, what is shared and offered, the host flag, up to 12 report lines of `/mineqtt privacy`, and for an
  operator up to 16 presence devices as name and online/offline, no coordinates, the rest counted) rides on page 0 of
  an unfiltered, unsearched page, also in adventure mode. The codec stays within 64 KiB and refuses a row whose room
  index points past the page's rooms. The client debounces searches (250 ms), resends unanswered ones (1 s, 3 times),
  asks for the shown page again every 2 s and at once after any action.
- **Explorer actions** (M4-18, M4-19; `ui/explorer/ExplorerActions`). Selecting a row offers Use (usable types only),
  Locate and Open; a room header offers All on and All off; the You row offers Presence, All (only where the server
  shares full data) and Off. `ExplorerActionPayload(UUID target, Kind)` (Kind USE, LOCATE, OPEN, ROOM_ON, ROOM_OFF;
  for the room kinds the target is a room id) and `PrivacyChoicePayload(PrivacyCommands.Choice)` pass
  `C2SGuard.requireCyberdeck` and one per-screen `TokenBucket` (burst 4, then 2 per second,
  `CyberdeckMenu.takeExplorerAction`), under the global C2S limit. Every action asks the listing rule afresh
  (`ExplorerDirectory.finds`: a non-op must own the device and may configure it, an op who may build reaches all), so
  no action reaches or reveals a device the tab would not list; an unknown id gets the same answer as a foreign one.
  Use: `DeviceRuntime.explorerUse(record, player)` runs the type's `use()` through `action()`, so it goes through `set`
  and `command(PLAYER)`; `mayInteract` is asked on the device's own level for a loaded device; an unloaded `QUEUE`
  device queues, an unloaded `UNAVAILABLE` one is refused with the reason. Locate (`located`): END_ROD particles sent
  to that player only, over a column 8 blocks high, every 10 ticks for 100 ticks, within 128 blocks
  (`ExplorerActions.tick` at SERVER_POST, `forget` at PLAYER_QUIT, `clear` at SERVER_STOPPING), else how far and where.
  Open (`opens`): the device's screen within 16 blocks, measured from the eyes to the block like `C2SGuard.reaches`,
  else the distance. The room kinds need a room visible to the player (`RoomRules.visibleTo`) and go to
  `DeviceRuntime.roomSwitch` (see Rooms). The privacy choice runs `PrivacyCommands.share`, the command's code path,
  and answers in chat with the command's lines.
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
- **World telemetry and player events** (M4-7, M4-8, plan D6, D7). The pure part is `telemetry/core`
  (`verifyCoreIsolation` scope `telemetry.core`: JDK, Gson, `GameClock`, `TokenBucket`): `WorldReading` (what the server
  reads), `Weather`, `TelemetrySnapshot` (the D6 JSON: `players_online`, then with world keys `players` (sorted ignoring
  case, always present), `tps` (0 while frozen, capped at the tick rate), `mspt`, `time_of_day` (15-minute steps),
  `day`, `is_day`, `weather`, then the drop counters), `TelemetryThrottle` (sampled every 1 s; players, `is_day`, weather
  or the key set publish after a 2 s floor, the rest at most every `telemetry.intervalSeconds` (5-300) with the TPS 0.5
  and MSPT 1.0 deadbands measured from the published value), `NamePolicy` (the one name gate: `publishPlayerNames &&
  the server shares players && consent(uuid)`; `TelemetrySampler(server, Predicate<UUID> consents)` takes the consent
  from `PlayerPrivacy` since M4-11),
  `PlayerEventMessage` (join and leave, named only when allowed; death (its `death.attack.<msgId>` key, never the
  rendered message) and advancement (announced, displayed, not hidden) only for a player who may be named) and
  `EventLimiter` (per player and type 5 s for join and leave, 1 s for death and advancement, then a bridge
  `TokenBucket(10, 10/s)` whose drops count). `telemetry/TelemetrySampler(server)` reads the player list, the tick
  manager, `getAverageTickTimeNanos()` (vanilla's rolling average), `WorldClock.overworldTicks` and the overworld's rain
  and thunder. `ha/BridgeDevice(mqtt, topics, Supplier<WorldReading>, Supplier<Settings>, clock, republish)` samples on
  the wall clock, keeps `lastTelemetry()`, publishes `publishPlayerEvent` on `bridge/event` (`Outbound.event`: QoS 1,
  never retained, dropped offline) and adds `dropped()` of the limiter to `dropped_outbound`. Its new components
  (`mineqtt_bridge_<uuid32>_<key>`): `tps` (diagnostic), `mspt` (diagnostic, disabled), `time_of_day`, `day`
  (measurement), `is_day` (binary sensor), `weather` (enum) with templates that read `None` while a key is absent
  (`telemetry.enabled` off keeps them discovered and unknown), `player_event` (event: join, leave, death, advancement), and
  `players` as `json_attributes` of `players_online`; the 0.7.0 components keep their unique_ids. `telemetry/PlayerEvents`
  (registered once in `MineQTT.init`) maps Architectury's PLAYER_JOIN, PLAYER_QUIT, LIVING_DEATH (server players) and
  PLAYER_ADVANCEMENT to the running runtime's bridge; both loaders fire the advancement once per completion. A server
  paused while empty samples only when something else pumps it.
- **Consent** (M4-11, plan D9; `presence/`). `Consent` (UNSET, PRESENCE, FULL, OFF; `effective(singleplayerOwner)`:
  UNSET counts as PRESENCE only for the integrated host, never for LAN guests or on a dedicated server).
  `PlayerEntry(uuid, consent, asked, name, slug, lastSeenDay, fullDiscovered)` lives in the registry; it keeps a name
  (16 code points, no control characters), a slug and the last UTC day seen only while the player consents, and
  `forgotten()` keeps consent and `asked`. `PrivacyPolicy(mode, publishPlayerNames)` is the one rule: the config only
  narrows (`shared(effective)` caps a stored FULL at the mode, and a wider mode later honours it), `prompt(entry, host,
  bridgeShares)` asks once (a broker configured and `ha.expose` not NONE; the host gets a note instead of the choices;
  *[Share all]* only in `FULL`), and `report` builds `/mineqtt privacy`. `PlayerPrivacy` (`MineqttRuntime.privacy()`)
  handles joins (a never-consenting, never-asked player leaves no entry) and `choose(player, consent)`, the one path of
  the command and of any later screen, and tells its listeners (`PresenceRuntime`). `PrivacyCommands`: `privacy` (no
  requirement; its leaves need a player) with `share on|full|off`, and `players list|forget <player>` (level 2). The
  prompt's buttons are `ClickEvent.RunCommand("/mineqtt privacy share <word>")`.
- **Presence devices** (M4-12, M4-13, plan D10). `PresenceDevice.objectId(worldUuid, player)` is `mineqtt_player_`
  plus the first 32 hex of sha256(world UUID bytes ‖ player UUID bytes), so ids differ per world and show no Mojang
  UUID. The discovery: device "Player <name>" (model "MineQTT Player", `via_device` the bridge), availability the
  bridge's state only, QoS 1, the primary `presence` binary sensor (ON/OFF, device class presence) on
  `<base>/<bridge>/player/<slug>`. `PresenceRuntime` (a `PlayerPrivacy.Listener`, `MineqttRuntime.presence()`) creates
  the device on a join or a choice (slug `player_<name>`, unique, kept across renames), publishes ON at a join and OFF
  at a quit and at a graceful stop (before the MQTT close), republishes every state (offline OFF) at each CONNACK and
  birth, so a crash leaves no stale ON, and removes it (two KEEP clears) on `share off`, `players forget`,
  `telemetry.forgetAfterDays` (UTC days since `lastSeenDay`, checked at start and every 20 real minutes, never for a
  player online), mode OFF or the names veto (consents kept). At `telemetry.maxPresenceDevices` it refuses new devices
  (no eviction) and tells the operators online once. Discovery goes only while `ha.expose != NONE` (OPT_IN included)
  and within `DiscoveryPublisher.tryTakeToken`; clears and states are not rate limited. Full sharing (`FullSample`:
  health to 0.5, dimension, block x/y/z; sampled once a second; `due()`: the first, a dimension change at once, else
  ≥ 5 s and ≥ 4 blocks or a health change ≥ 1; a 20/s `TokenBucket` for sampled updates only) adds the health,
  dimension and position sensors (`PresenceDevice.fullComponents`; position with `json_attributes` x/y/z). Narrowing
  removes them through `DiscoveryPublisher.componentsRemoved`; `PlayerEntry.fullDiscovered` persists that the full
  config was published, so a restart re-issues a removal that Home Assistant missed, instead of the in-memory mark
  (which would remove at every restart for every presence-only device). An offline state never carries a reading.
- **Suggested area** (M4-14). `DiscoveryPublisher` adds `dev.sa` for a device inside a room (`area(DeviceRecord)`) and
  for a group whose members are all in one room (`area(DeviceGroup)`); the overloads without an area keep the older
  golden messages byte for byte. Since M4-23 the area is Home Assistant's spelling of a catalog area whose key
  matches the room's name (`RoomRules.area`, see Rooms), else the room's name. `DeviceRecord.discoveryArea` (the third
  flat key of `DISCOVERY`, the area or `""`; not for a v1-merged lamp, whose device Home Assistant already has) is the
  area of the message that **first** created the device in Home Assistant: `publish` records it only while it is
  absent (`Subject.createdArea`), and since the M4 fix of the kept-area hint hiding, `/mineqtt discovery clear` and a
  prefix change keep it. An absent `discoveryArea` (devices discovered before 0.8.0) reads as no area.
  `DeviceView.Registration.keptArea` compares it with the current room by `AreaNames.key`, so case and spaces never
  give a hint. MineQTT cannot see Home Assistant's area registry, and the versions differ (measured): both create the
  device and a missing area for a new device and keep the area of a device that has one; for a known device without an
  area 2026.10.0 keeps none while 2025.2.5 moves it into the suggested area; a device hidden and exposed again gets its
  old area back on 2026.10.0 and the current room on 2025.2.5. So the General tab's hint says which area Home
  Assistant was first told (`mineqtt.device.room.kept`) or that it got none (`room.kept_none`) and never claims what
  Home Assistant did; on 2025.2.5 it may show while Home Assistant already agrees.
- **Broadcast text** (M4-22, plan D8). Off by default (`telemetry.allowBroadcast`; Fabric reads it at start, NeoForge's
  config watcher applies it live). `telemetry/core/BroadcastText.parse(byte[])` refuses more than 1 KiB before
  decoding and anything that is not strict UTF-8; `clean` removes `§` with the code point after it, bidi controls
  (U+202A–U+202E, U+2066–U+2069, U+200E, U+200F, U+061C) and ISO controls, collapses whitespace to one space and cuts
  to 256 code points (never splitting a surrogate pair, never ending on a space); an empty result is refused;
  `Limiter` is a `TokenBucket` of 3, refilled at 1 per second. `BridgeDevice.onRequest` ignores retained requests (the
  stored copy is never replayed; a live `pub -r` reaches current subscribers as an ordinary message), answers
  `broadcast disabled` while off, `rate limited` over the bucket, and logs each shown text with its topic.
  `MineqttRuntime.showBroadcast` sends `[MQTT]` (lang `mineqtt.broadcast.prefix`, dark aqua) and the text as a literal
  to every player; it is never a command or a JSON component. While on, the bridge's discovery has the `broadcast` text
  entity (`BridgeDevice.broadcastComponent`: `command_topic` `bridge/request/broadcast`, `retain` false, `max` 255, Home
  Assistant's own limit for an MQTT text, `entity_category` config, the bridge's availability, no state topic).
  `DiscoveryPublisher.bridgeConfig()` builds the bridge message per the key, `bridgePublished` sets the registry mark
  `bridgeBroadcastDiscovered` when a message with the entity went out, and `removeBroadcast()` (from `republishAll()`
  and `configChanged()`) runs `bridgeComponentsRemoved({broadcast})` while the key is off and the mark is set, clearing
  the mark once Home Assistant is online. A discovery clear, a prefix change and a bridge without discovery clear the
  mark. Until Home Assistant's birth is heard on the current connection each connect, republish and config save with
  the mark set re-issues the stub (harmless); the first online birth runs DISCOVER then REMOVE once.
- **Reduced discovery of bound devices** (M3-7). A device bound to a source keeps its `dev`, `o`, availability and
  identifiers, but its `cmps` hold every declared entity as a platform-only stub `{"p": …}` plus the disabled
  diagnostic `binding` sensor (`<objectId>_binding`, reading `binding.entity` and `binding.health` from the device's
  state topic, which carries them only while bound). Home Assistant applies a stub only to a component it discovered in
  its current run, and clears the device's config itself after removing entities. So a binding that comes or goes
  publishes the removal (`DiscoveryPublisher.bindingChanged`, M3-7b: the reduced message while bound; once unbound
  every component stubbed, the binding sensor included, then the full message 2 s later) and watches the device's
  config topic for 30 s, publishing the steady message again 1 s after Home Assistant's clear. While Home Assistant is
  not known to be online (`MineqttRuntime.haOnline`: no birth heard on this connection) the record is marked
  (`DeviceRecord.discoveryCleanup`), and the next birth's republish sends the full device with the binding sensor, then
  2 s later the removal, and ends the mark; Home Assistant's clear ends it too. Unmarked devices republish as always.
  The stubs stay in the retained reduced message. One mark serves one Home Assistant per broker. A refused binding is
  still reduced; targets alone give the full config. 2025.2.5 cannot remove a disabled, unloaded entity through
  discovery, so a disabled Binding entity stays there after unbinding.
- **Cleanups by object id and component removal** (M4-1). The M3-7b cleanups are kept by discovery object id (a
  device's derives from its UUID; the device path is unchanged). `componentsRemoved(objectId, removed, current)` runs
  the same removal for an object whose message the publisher does not build from a record (M4's presence devices) and
  `bridgeComponentsRemoved(removed)` for the bridge's own message (the broadcast text): each removed component, as it
  was last published, becomes a platform stub, then the steady message follows `CLEANUP_STEP_MILLIS` later, or sooner
  on Home Assistant's clear; offline it is marked and runs at the next birth. While other components remain, both
  pinned Home Assistant versions remove the stubbed one at once and publish no clear (checked on both before M4-2), so the step
  ends the removal; entity ids and user renames of the remaining components are kept. These marks are in memory only (a restart
  before the birth or a discovery-prefix change forgets them, so their owners re-issue a removal at start). A component
  the message declares in full again before a step (turned back on, say while Home Assistant was offline) leaves the
  removal, so it is never stubbed nor replaced by its old definition; with none left the current message ends the
  removal and its mark. The object id of any registry device or group is refused: devices drop components only
  through their binding, groups never.
- **M4 discovery seams** (M4-1, used since M4-2 and M4-9). `HaEntity.Sensor.jsonAttributes(field)` adds `json_attributes_topic`
  (the state topic) and a `json_attributes_template` that gives `{}` when the key is missing.
  `HaEntity.Text.pattern(regex)` adds `pattern`: at most 128 characters in the subset Java, JavaScript and Python read
  alike (`Text.patternProblem`). `Ha.selectDynamic(key, field, Supplier<List<String>>)` is a select over a TEXT or ENUM
  field whose options come from a source at publish time (sorted, distinct, at most 128; `Select.valueTemplate` can
  strip block properties); the options are part of the discovery hash, an empty or failing source makes the select
  a platform-only stub `{"p":"select"}` kept in the retained message (never an empty select, which Home Assistant
  refuses, and never left out, which would leave the old select there); a Home Assistant online since it discovered
  the select removes it without clearing the device, one that was offline keeps it unavailable until the options come
  back (no cleanup can discover a select without options first), and `refreshDynamicOptions()` re-checks every device
  and group with such a select (after a datapack reload).
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
  drop) and whose `dev.via_device` (or `device.via_device`) is this bridge's `mineqtt_bridge_<worldUuid32>`; since
  M4-12 also such a config of `mineqtt_player_<32hex>` that no player entry of this world has
  (`GhostCleanup(..., Predicate<UUID> known, Predicate<String> knownPlayer, ...)`, `playerId(prefix, topic)`; each
  clear is re-checked per kind at release). The same window subscribes to `TopicScheme.playerRoot()` + `/+` (this
  bridge's presence states, read at each connect, so a moved root moves the scan) and `judgeState` clears a retained
  state whose slug no player entry holds (`PresenceRuntime.knowsSlug`): its topic names the player and a full reading
  holds their position. When the topics move during a run (`reconnect`, a connection settings change),
  `PresenceRuntime.topicsChanged(before)` clears every device's state under the old root, and `/mineqtt discovery clear
  <old bridge>` clears the presence states under that bridge id too. A base topic or bridge id changed while the server
  was stopped leaves the old root's states on the broker (no old root is kept). Live
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
  `mineqtt:ha.expose` (`AclContext.mayExpose(PermissionSet)`) plus CONFIGURE on the device; `devices` and
  `devices expose` require `mayExpose`, and every other subcommand carries its own op requirement (the root has none
  since M4-0, see [Security](#security)).
- **v1 migration, per topic** (`LegacyDiscoveryMigration`): `{"migrate_discovery": true}` on the old
  `homeassistant/light/<id>/config`, ≥2 s later the new device config carrying the v1 unique_id on its light, ≥2 s later
  an empty retained payload on the old topic; the topic is marked done on that PUBACK. HA keeps the entity registry
  entry, entity id and history. The v1 retained `Offline` status is cleared once. A device or group with a v1
  unique_id names it as its second device identifier (`dev.ids`, for good, also after the migration), so HA reuses
  the v1 device entry (M2 plan D7, F12).

## Home Assistant twin

Milestone 3 (version 0.7.0) mirrors Home Assistant entities and MQTT devices onto
devices and lets devices switch them back. It is built on the flows bus, not beside it: Home Assistant entities are
flow nodes with typed ports (`twin/core/HaDomains`), and a binding compiles into derived wires on the one
`MessageBus`, so bindings share its caps, change filter, counters, hop limit and roots. The details of each piece are
in the bullets of [Flows](#flows) (roots, twin wires, mirroring types, target wires, presets, catalog),
[Home Assistant](#home-assistant) (reduced discovery), [Devices](#devices) (the Twin tab, persistence) and
[Security](#security); this section is the map.

```
 Home Assistant ──mqtt_statestream──► <base>/<domain>/<object>/<leaf> ─┐  (only the leaves bound devices need)
 catalog blueprint ─────────────────► <base>/_mineqtt/{catalog/+,status} ─┤  (HaEntityCatalog, 5 s after CONNACK)
                                                                         ▼
   TwinRuntime: EntityDocuments (one document per entity: statestream and catalog layers, merge, freshness, read gate)
                │ TwinCoalescer (a burst applies once, ≤ 4/s per entity), drained after the inbound drain
                ▼
   DeviceRuntime.applyTwin ── TWIN extent ──► derived source wire HaEntityRef → PortRef(device, primary) (Scope.TWIN)
                ▼
   deliverTwin → DeviceType.twinCommand (the adapter's reads) → command(…, CommandSource.TWIN) → APPLY (loaded only)

   player / wire / Controls tab ──► route FORWARD ──► MirrorForwarder → TwinRequests ──► <bridge>/bridge/ha_request
   out-port of a target device  ──► derived target wire PortRef → HaEntityRef ──► HaSink → TwinRequests ──┘
                                     TwinRequests: TwinGate (root) → adapter/HaCalls → allowlist → breaker →
                                     connection → rates → publish; answers on bridge/ha_response are advisory
```

- **Packages.** `twin/core` is the pure core (Minecraft-free, `verifyCoreIsolation`): the statestream parser and the
  `EntityDocuments` hub, value paths and maps, `HaDomains`/`HaCalls`/`Call`, `Binding`, `TwinHealth`, `TwinCoalescer`,
  `EntityScan`, `EntitySearch`, `BindingPreset`/`PresetCodec`. `twin` holds the server side: `TwinAdapter` (pure too),
  `TwinRuntime` (read side), `HaEntityCatalog`, `TwinGate`, `TwinRequests`, `PendingRequests`, `HaSink`,
  `BindingPresets`, `BindingCodecs`. The device side is `device/TwinRules` (who may bind what, judged for editor, owner
  and placer), `device/TwinWiring` (the derived wires), `device/TwinView` (the Twin tab's per-viewer data) and the
  `FORWARD`/`REFUSE` routes of `DeviceRuntime`. Each type that takes part declares a `TwinAdapter`
  (`DeviceType.Builder.twin`): its primary port, the source domains with their reads, the forward through `HaCalls`,
  targets on out-ports, extras and extra leaves; `build()` refuses an adapter that breaks the rules (a target on an
  action, a read into a port it does not mirror).
- **Read side.** A loaded device with a working binding *holds* exactly the statestream leaves its adapter reads
  (`TwinAdapter.leaves`: `state`, `last_updated` and the attributes the reads and `twin.path` need, never `+`), one
  shared `SubOptions.MIRROR` subscription per leaf for every holder (`mqtt/core/SharedSubscriptions`), after
  `TopicAcl.checkMirror`. Every leaf folds into the one `EntityDocuments`: the raw state (never JSON), strict JSON
  attributes, timestamps, and per entity a statestream layer and a catalog layer. The newer `last_updated` wins
  (`EntityDocuments.merge`): a live statestream value always applies, a retained one with an older or missing
  timestamp loses to the catalog, so a stale retained value after a Home Assistant restart is corrected by the next
  catalog. Freshness is LIVE, RETAINED or NONE; a Home Assistant birth downgrades every LIVE entity to RETAINED until
  the next live value or catalog. Attributes are not delivered while the state is unavailable, unknown or cleared, and
  a null colour keeps the last one. The last holder of an entity closes its leaves and forgets its document, so the
  broker's retained replay restores it at the next attach (D17); nothing of it is ever saved.
- **Gates on reading.** `twin.allowedEntities` (globs; empty, the dedicated default, turns the Home Assistant twin
  off; nobody bypasses it) and, while the catalog answers (catalog mode: a status was received), membership in the
  catalog, which Home Assistant's labels decide; a domain list over 64 KiB is refused and its domain shares nothing
  (fails closed). The D2 privacy domains are unreadable in `HaDomains`, and every attribute read is whitelisted per
  domain. A new source or target must pass the share gate when it is bound (`TwinRules.shareRefusal`); a bound
  entity that leaves the catalog shows `NOT_SHARED`.
- **Apply.** `TwinRuntime.drain` (after `drainInbound`, also in the paused-server pump) hands each settled entity to
  `DeviceRuntime.applyTwin`, which emits the document on the derived source wires inside `bus.withRoot(Root.TWIN)`.
  A `Scope.TWIN` wire carries the whole document unmapped; the sink reads the entity's newest document through the
  device's binding (`DeviceType.twinCommand`) and applies it as `CommandSource.TWIN`, which routes `APPLY` for a
  loaded device and `DROP` for an unloaded one (never queued). The device's `publishState` then emits its out-ports
  with root `TWIN`, so ordinary wires carry the real house on. Health (`TwinHealth`) goes to the render hint byte, the
  state topic's `binding` object (Home Assistant's *Binding* entity) and the open screens.
- **Write side.** A non-TWIN command to a mirrored port of a bound device routes `FORWARD` (players and wires, with
  control on and the owner's `mineqtt:twin.control` at route time) or `REFUSE` (Home Assistant `/set`, groups, the
  paused Raw Link, control off, a refused binding), before any queue; a forward applies nothing (non-optimistic) and
  goes to `MineqttRuntime.forwardToTwin` → `TwinRequests` with the current root and the acting player. A target
  device's out-port reaches `HaSink` over its derived target wire. `TwinRequests` runs, in order: the root gate
  (`TwinGate.mayRequest`: `PLAYER` and `WORLD` only), the adapter's translation through `HaCalls` (D11: the opposite
  of a LIVE state, a `toggle` of a RETAINED one, a local refusal while nothing or unavailable is known, a dedupe
  skip when the entity already shows the value), `twin.haActionAllowlist`, the breaker, the connection (offline: refused,
  `OfflinePolicy.DROP`), the sliding one-second windows per device, player and bridge, then the publish on
  `bridge/ha_request` (QoS 1, not retained). A request waits for the entity's next apply, a refusal or 5 s
  (`PendingRequests`); `bridge/ha_response` only ends the wait and explains a refusal, it never changes a device.
- **Rates and the breaker.** Over a rate, a value with data and a world-caused explicit state wait (one slot per device
  and entity, newest wins, dropped on a pause, a binding change or offline, checked again when sent), while a
  player's switch, a toggle and a press are refused, counted per device (status line, `devices info`, the Twin tab
  through `TwinRequests.Listener.rateRefused`) and logged once per burst. A dedupe skip drops
  a held state it contradicts (`TwinRequests.supersede`). More than 8 world-caused requests or attempts in 10 s pause
  the device (`CONTROL_PAUSED`) until a player with CONFIGURE resumes it; requests with an `origin.player` never count
  towards the breaker (`countsForBreaker`).
- **Root provenance.** Every bus message carries a transient `Root`, assigned where the cause enters (see the Roots
  bullet under [Flows](#flows) for the mechanics):

  | Cause | Root |
  |---|---|
  | a twin apply (`command(…, TWIN)`) | `TWIN` |
  | a device's `/set` topic | `HA` |
  | a player's use, a C2S payload, a command twin | `PLAYER` |
  | a Raw Link (the Block Placer's included), a legacy group's v1 topic | `RAW` |
  | `powerOnRecords` and the whole attach (queued commands landing included) | `STARTUP` |
  | group fan-out and wire seeds | the outer root |
  | block tickers, neighbour updates, hands | the current root, else `WORLD` |

  A delivery runs in its message's root, held values and drain carry-over keep theirs, and a nested `PLAYER` or
  `WORLD` extent never clears an outer `RAW`, `HA`, `TWIN` or `STARTUP` (`Root.nested`). A refused root is the bus drop
  `root`, logged once per device and root. Wire seeds into mirrored and Home Assistant targets are skipped, since a
  seed is a command, not a bus message.
- **Loop safety (D10 proof sketch).** (1) The TWIN apply path (`TwinRuntime` → `applyTwin` → `command(…, TWIN)` →
  `applyCommand` → `publishState`) has no call edge to `TwinRequests`; every request passes `TwinGate` first. (2)
  Everything emitted in a TWIN extent carries `TWIN`, and the bus keeps it through hops, holds and carry-over. (3) No
  nested extent clears an outside root, and the attach and the queue's landing are `STARTUP`, so neither a nested
  command nor a queued `/set` turns incoming state into a player's or the world's. (4) Every path on which a broker
  message changes the world synchronously opens an outside root: `/set` topics (`HA`), Raw Links (the Block Placer's
  included) and legacy groups (`RAW`), the twin (`TWIN`). (5) So no request is causally downstream of incoming state within
  one tick's synchronous chain. Outside the proof: delayed redstone (repeaters, observers, scheduled ticks start a new
  `WORLD` chain, also after a `RAW`, `HA` or `TWIN` cause) and Home Assistant automations that write raw topics; the
  rates, the dedupe and the breaker bound them (`verifyMirrorNoLoop`'s repeater loops). M6's logic nodes must carry
  the root through; only an explicit, rate-capped node may ever clear it.
- **Catalog.** `HaEntityCatalog` opens its own SUBSCRIBE 5 s after the first CONNACK (after the device-critical retained
  messages) and, while no status came within 10 s, the fallback scan of `<base>/+/+/state` and `friendly_name`
  (`EntityScan`, capped at `twin.catalogMax`). `MineqttRuntime` (not `TwinRuntime`, which can never publish) sends
  `bridge/ha_sync` 5 s after every CONNACK and Home Assistant birth. The picker and `/mineqtt twin entities` rank
  through `EntitySearch`; drift (a catalog entry moving twice without a live statestream value, where a move counts only
  when the state or the whitelisted attributes changed, so Home Assistant's re-dating of restored entities at a start
  is none; `EntityDocuments.changedContent`) is reported for mirrored entities only, never for trigger domains.
- **Presets.** An MQTT device preset is a document synthesiser for one Home Assistant kind (`BindingPreset`): its state
  topic's payload becomes an `EntityDocument`, the adapter reads and forwards it unchanged, and `render` turns the
  adapter's `Call` into the device's command through typed placeholders. The source is `TopicRef(stateTopic, "")`
  with `Binding.Settings.preset`; its wire carries a payload fingerprint, and the device reads the payload through its
  own loaded preset. Presets need `mineqtt:twin.bind` but not the Home Assistant twin; the ACL gates them
  (`checkTopicSource` for the state topic, `checkPublish` for the owner at every send), behind the same root gate,
  rates and breaker.
- **Persistence.** A binding lives in the block (`DeviceLink`'s optional `twin` key) with a copy in the record
  (`DeviceRecord.twin`, the codec's last `group()` slot), never entity documents or the catalog. Unbound devices save
  byte-identical to 0.6.0, `DeviceLink.VERSION` stays 3, the registry schema 1 and `configVersion` 2.

## Security

- **The `/mineqtt` root is open** (M4-0, D14): it has no requirement and runs nothing; every child carries its own
  (`publish` level 3; `status`, `reconnect`, `group`, `wires`, `discovery`, `twin` level 2; `devices` and
  `devices expose` `mayExpose`, every other `devices` child level 2; `players` level 2), and the player commands
  `privacy` (M4-11) and `rooms` (M4-14) are open literals whose leaves judge the source. There is one
  `literal("mineqtt")` registration; `verifySecurityGuards.openCommandRoot` asserts the matrix per permission level and
  the tab completion each source is sent (`CommandNode.canUse`, `OPEN_LITERALS`). `AclContext.ROOMS_ADMIN`
  (`mineqtt:rooms.admin`) with `mayAdminRooms` (op level 2 or the atom) makes rooms for everyone (`forAll`).
- Player topics (M4-12): `TopicAcl.Exposure.Kind.PLAYER` for `player/<slug>`; `AclContext.mayReadPlayerTopic(subject,
  slug, slugOf)` shows one in the explorer to admins (`mineqtt:admin` or level 3) and to the player whose slug it is
  (`CyberdeckSessionManager`), and nobody publishes there.
- The explorer's Devices tab never lists another player's device: a non-op sees devices they own and may configure,
  even where the claim or the access mode would let them configure a stranger's (stricter than the Bridge Console's
  list); an operator who may build sees all, a player who may not build none (M4-16). Its actions (M4-18, M4-19:
  `ExplorerActionPayload`, `PrivacyChoicePayload`, both with enum codecs that refuse an unknown ordinal) pass
  `C2SGuard.requireCyberdeck`, a per-screen bucket (burst 4, 2/s) and the same listing rule again for every target, so
  Use, Locate and Open never reach or reveal another player's device; an unknown id is answered like a foreign one.
  A room switch reaches only the player's own devices with a state (at most 64, once a second per player); the console
  switches every device in the room.
- Broadcast text (M4-22) is off by default. It is never a command or a JSON component: a literal behind a fixed
  `[MQTT]` prefix, at most 1 KiB before decoding, cut to 256 code points with formatting codes, control and bidi
  characters removed, 3 at once then 1 per second, retained requests ignored, each shown text logged with its topic.
  Nobody in the game may publish under `bridge/` (`DENY_BRIDGE`, another bridge's `DENY_FOREIGN_BRIDGE`); restrict
  `<base>/+/bridge/request/#` in the broker ACL.
- `TopicAcl` (pure, in `mqtt/core`) decides every topic a player, a block or a command uses. Hard denies (discovery
  prefix, this server's bridge namespace except `raw/`, every server's `bridge/` level under the base topic and under
  `mineqtt` (`DENY_FOREIGN_BRIDGE`), the statestream base (`DENY_STATESTREAM`), `$SYS`, other players' private
  subtrees) apply even to ops; listening on the statestream base needs `mineqtt.admin` (`mineqtt:admin` or op level 3,
  `DENY_STATESTREAM_LISTEN`), and the explorer shows non-admins only the size of the twin's `bridge/ha_*` messages and
  hides statestream topics. A legacy group's v1 topic (`checkLegacy`, the topic the group follows and echoes to) has no
  admin, so it never follows the statestream base (`DENY_STATESTREAM_LEGACY`) or any bridge's `bridge/ha_*`
  (`DENY_TWIN_TRAFFIC`); every other v1 topic is a migrated device's Raw Link and goes through `checkPublish` with its
  grandfather flag (M4-20). An MQTT
  device preset's state topic (`checkTopicSource`, M3-17) follows `checkMirror` but, for every subject including admins
  and the console, never overlaps the statestream base (`DENY_STATESTREAM_SOURCE`) or the twin's traffic; its command
  topic needs `checkPublish`. Neither a preset's state topic nor a v1 listener reaches this server's device and group
  topics (`DENY_DEVICE_TOPICS`: a bound device's state names its entity, and the explorer shows those
  topics only to players who may configure the device); its `bridge/` and `raw/` subtrees stay open. A binding's entities (Twin tab, status line, Raw Link status) reach only viewers with
  CONFIGURE (M3-13). The raw allowlist (`acl.rawAllow`) comes from the config; v1 topics of migrated devices are
  grandfathered per owner. `MineqttRuntime.createAcl` always passes the normalised `twin.statestreamBase`, so a reload
  that moves it re-judges Raw Links.
- Twin permissions `mineqtt:twin.bind`/`mineqtt:twin.control` (`AclContext`, AUTO: everyone integrated, op level 2 or
  the permission dedicated) and `twin.allowedEntities`, which nobody bypasses (`AclContext.twinBindRefusal` takes no
  subject). They are server-wide atoms, not `MineqttPermission` claim permissions: a twin edit also needs CONFIGURE on
  the block, which carries the P2 rule (no CONFIGURE for a player who may not build).
- The Twin tab's preset search (`TwinSearchPayload` of kind `PRESET`) shares the entity search's guard
  (`requireDevice`, CONFIGURE), per-player bucket and `twin.bind` requirement, is answered only for `twin.device` on a
  type with a source (`TwinPicker.searchable`), and never asks the Home Assistant twin.
- Twin keys (`twin.*`, M3-7) need CONFIGURE plus the twin gate (`TwinRules.edit`): `mineqtt:twin.bind` and
  `twin.allowedEntities` for an entity or a `twin.path`, `mineqtt:twin.control` to turn control on (turning it off
  needs none). The binding is judged for the editor and for the device's owner, again for the owner at every attach and
  config reload (`twinRulesChanged`; a refused binding stays bound, reads and forwards nothing, and heals when the
  rules allow it again), and for the placer of a copied block (control dropped). `/mineqtt devices bind` acts with an
  operator's grants and the source's twin rights. Player-facing texts, the render data and the links list for viewers
  without CONFIGURE never name the entity (D13 #7); the device's state topic does (`binding.entity`), and so does its
  status line.
- `C2SGuard` validates every client-to-server payload: rate limit first, then no spectator, then menu/BE lookup, reach
  (16 blocks with a Configurator in hand where the level lets the player interact, `reaches`), claim permission
  (`MineqttPermission.USE`/`CONFIGURE`, Flan when present; CONFIGURE never for a player who may not build, P2) and
  size limits. Device payloads use `requireDevice` (any
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
- The Block Placer (ex block emitter) is off unless `blockPlacer.enabled`, and then subscribes to nothing. It places
  only `#mineqtt:placeable` (or, with `blockPlacer.containerUnlocks` while it consumes items, any block from the container
  behind it, which it reads only where the claim rule lets it open the container) minus the hard denies (`PlacerRules`), never with NBT or block entity data, never from a
  retained message, at most 4 actions a second, within the world border and outside spawn protection, across a claim
  line only for an online owner who may build there, and clears only its own block; on a dedicated server only operators
  configure it unless `blockPlacer.playersMayConfigure` ([Devices](#devices)). The Block Observer exports block entity
  data only under `blockObserver.exportBlockEntityData` and within one claim, never to Home Assistant. The Container
  Observer publishes a container's item counts (by item id, never names or other components) only where the Block
  Placer's claim rule lets its owner open the container, asked at every look, and never publishes a scan saved from
  before a load. MQTT never runs server commands.
- Player data: nothing names a player without consent (`NamePolicy`, `PrivacyPolicy`, [Home Assistant](#home-assistant)); joins and
  leaves of other players are published anonymously, deaths and advancements not at all. Presence ids are salted per
  world; the Bridge Console names players only to operators.
- Rooms (M4-14, M4-15): commands and the Room mode go through `RoomEdits` (build rights at both corners, size and count
  caps, `forAll` only with `mayAdminRooms`); `RoomNamePayload` passes the box-offer guard of `C2SGuard.requireOffer` and
  answers only the player's open offer.

## Migration of v1 worlds

- Block, item and BE-type ids are unchanged. Since M4-9 and M4-10 no block ran on the legacy subscription facade any
  more (the Block Sensor and the Block Emitter, its last users, became the Block Observer and the Block Placer), and
  M4-20 removed it with `ICallbackTarget`. The v1 base classes `MqttPublisherBlockEntity`/`MqttSubscriberBlockEntity`
  and the `SubscriptionManager` delegator are gone. A migrated v1 Block Sensor's or Block Emitter's topic follows the
  device Raw Link rules (grandfathered for its owner, under the hard denies), so a topic that is a wildcard filter,
  longer than 256 bytes, under the discovery prefix (e.g. `homeassistant/status`) or under any bridge's `bridge/`
  level no longer works after the upgrade; `/mineqtt devices info` shows why.
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
- **Narrowing keys** (M4-7). `telemetry.players` and `telemetry.publishPlayerNames` fall back to the narrowest value
  (`OFF`, `false`) when present but invalid, not to the default: `ConfigValidation.telemetryPlayersOrNarrowest` and
  `publishPlayerNamesOrNarrowest` take the raw value (any-case names; booleans or `"true"`/`"false"`), an absent key takes
  its default. On NeoForge both keys use the validator `isPresent` (`v != null`), so NeoForge never corrects a present
  value to the widest default at load or on a file-watcher reload; `TELEMETRY_PUBLISH_PLAYER_NAMES` is a plain
  `ConfigValue<Boolean>`, read raw, and `MineQTTConfigScreen` seeds a telemetry section into `sectionCache` that draws it
  as an on/off button. The narrowed values live in memory; the file keeps its text until the next save, as on Fabric.
- **Timers, Block Observer and Block Placer** (M4-3, M4-9, M4-10) adopt v1 data through `LegacyNbtV1` (`hasTimerKeys`,
  `hasSensorKeys`, and the common v1 snapshot for the emitter) and keep writing it back until their first attach ([Devices](#devices)). A timer's
  v1 slots never published, so it takes no Raw Link; the observer's topic becomes an unretained Raw Link output, the
  emitter's a Raw Link input that takes no retained message. A downgrade to 0.7.0 resets their settings.
  An upgrade from 0.7.0 is proven on both loaders by `e2e/tools/upgrade07.sh` (e2e checklist item 40): counters,
  `lastAlarmDay` and owner UUIDs carried, slots as legacy items, an off-minute custom time exact, topics unchanged, the
  config kept. Known limitation: a Daylight Timer at sunrise that 0.7.0 saved in the 20 ticks before the day turned
  (between 0.7.0's two fires of that sunrise) fires once more right after the upgrade.
- **Registry additions of 0.8.0** (M4-11, M4-14, M4-22): the `players` and `rooms` lists, the records' `discoveryArea`
  and the top-level `bridgeBroadcastDiscovered` need no migration (absent means every consent unset, no room, no area
  recorded, no broadcast entity to remove). 0.7.0 ignores them, so a downgrade forgets every privacy choice, presence
  slug and room, and leaves the presence configs it does not know on the broker.

## Testing

- `./gradlew check` runs plain `main`-method checks (38 tasks): `sensorChecks` (bootstrapped Minecraft: the Block
  Observer (`verifyBlockSensor`), the Block Placer and its rules (`verifyBlockEmitter`), the Container Observer
  (`verifyContainerObserver`), the timers (`verifyTimers`, with
  `verifyAlarmClock` and `verifyGameTimeTimer`; since the clock fix also a standing clock), `verifyPrivacy` (the consent
  matrix, the prompt and its buttons, `/mineqtt privacy`'s report, player entries, and `PresenceLifecycleCheck`: presence
  devices from consent to removal, slugs, the cap, forgetting, the crash rule, full sharing with its deadbands and
  budget), `verifyRooms` (corners, names, sizes, counts, which room a device is in, the dirty set, a group's room,
  references, offers, outlines, suggestions with Home Assistant's area names, `RoomRules.area`, the room switch's
  targets, cap and rate), value codecs, registry round trip, lifecycle guards, legacy NBT migration, discovery payload golden files,
  config migration, security guards, `verifyDeviceTypes` (derived and frozen ports, strict settings and link settings,
  which port pairs a wire may join, the default wiring and the bus routing over a registry, the device view's stream
  codec and 32 KiB cap), `verifyMirrorNoLoop` (the mirror route table, the twin type build rules, reading and
  forwarding of a synthetic twin type and the Smart Lamp, the side doors of bound devices, 1000 seeded statestream sequences through a real bus, roots through a real `MessageBus` and the real `TwinRequests`: the root gate, dedupe,
  rates, the breaker, answers, `powerOnRecords` as `STARTUP`; M3-12's Output and Plug level sequences and forwards;
  M3-14's targets through the real `DeviceRegistry`, `TwinWiring` and `RegistryWiring` and the repeater loop against the
  breaker; M3-15's read-only panel; M3-17's presets through the real bus and `TwinRequests`; the fast repeater loop
  ending paused, rate refusals, target sharing and the rate listener), `verifyDeviceTabs` (since M3-13 the Twin
  tab: rows per type, every twin key placed exactly once (no exemption for `twin.device`/`twin.preset`),
  editable exactly when the server takes it, the picker's area groups, free text and control filter),
  `verifySecurityGuards` (32 payloads: 26 after the timers', the Block Sensor's and the Block Emitter's went, then the Room mode's prompt and answer, the Devices tab's query and page, and its action and privacy choice; the explorer's scope, query bounds, room grouping and filter, group rows per scope and page codec with rooms and the You row; `explorerActions`: the listing rule, Use, Locate, Open, both payloads and the action bucket; the console's World part without names for non-operators; `roomMode` and `roomsCommand`; the Block
  Placer's configure gate; the picker's wire caps, views without details, the search bucket; preset
  bindings; `presetSearch()` and `pickerAnswersAndRates()`: the search kind, the answer's domain, resends, the
  view's preset source and rate fields), and `verifyCatalog`: every `Catalog` entry's assets, loot table and lang keys,
  every 26.2 recipe and unlock advancement parsed with the vanilla codecs) and `brokerChecks` (no Minecraft: topic
  trie, broker URIs, ACL, queues, outbound buffer, `verifyMessageBus` (the flows bus and the coercion table; a target wire carries the out-port value as it is, M3-14), `verifyStatestreamParse` (the twin core against the HA 2026.10.0 statestream capture: raw states, strict attributes, the catalog and the merge table), `verifyValuePath` and `verifyValueMap` (the twin's value paths, value maps and panel layout), `verifyHaCalls` (HA domain ports, the service-call table, the data-key whitelist, impossible calls, one target, privacy domains, D11 switching, D5 edges; it reads `docs/blueprints/automation/mineqtt/` (its task input and argument) through the brokerChecks helper `BlueprintFile`: the control blueprint's services line, defaults, reply rule and guards, M3-10, and the catalog blueprint's attributes line, triggers and topics, M3-11), `verifyStatestreamParse` also covers M3-11's restart snapshot, drift (never for trigger domains, nor for a re-dating alone, e.g. two Home Assistant restarts), catalog health, scan and search and targets leaving the catalog, `verifyPresetLoad` (M3-17: the built-in presets, rendering, hostile values, bad datapack files, device names, documents, the ACL of preset topics), and `verifyBrokerIntegration` against an in-process Moquette (scenario 18: the twin's shared statestream subscriptions), MQTT
  3.1.1 only).
- `verifyCoreIsolation` scans each Minecraft-free package against its allowed imports: `mqtt.core`, `flow.core`,
  `twin.core`, the ghost cleanup and, since M4-1, `device.timer.core` and `telemetry.core` (JDK, Gson,
  `util.GameClock`, `util.CronSchedule`, `security.TokenBucket`/`RateLimit` and their own package) and the single-class
  scope `clocks` (`GameClock`, `CronSchedule`: JDK only), plus `telemetry.core checks` for `verifyTelemetry`'s sources.
  The task also runs a self-test each time: its matcher must refuse `net.minecraft` and `DeviceType` imports in a
  synthetic source. `verifyTelemetry` (brokerChecks) covers the D6 payload, the throttle, the name policy and the player
  events with their caps, and since M4-22 the broadcast text's cleaning and rate (`broadcastText`, `broadcastRate`).
  Since M4-20 `verifyTopicAcl` judges the v1 rows as legacy groups' topics (`legacyGroups`) and the former facade
  blocks' topics as grandfathered Raw Links (`migratedLinks`), and `verifyRuntimeWiring.migratedLinks` holds a
  placer-shaped input and an observer-shaped output on their topic through an allowlist loss and refuses both under a
  moved statestream base. `verifyDiscoveryPublisher` covers the broadcast requests and the entity's removal mark
  (`broadcastRequests`, `broadcastEntity`), the area spelling and the kept first area; `verifyDiscoveryPayloads` has
  the golden `bridge_broadcast.json`; `verifyStatestreamParse.catalogAreas` the catalog's areas; `verifyPrivacy.guiChoices`
  the You row's choices.
- `e2e/` is the manual end-to-end environment: Mosquitto 2 (TCP, TLS, WebSockets), Mosquitto 1.5, and Home Assistant at
  the supported floor and current stable. Its README holds the checklist and the last recorded results, including the
  MQTT 5 integration run (`-PexternalBroker`). `ha.py dashboard-check docs/dashboards/mineqtt-world.yaml` holds the
  example dashboard to entities of MineQTT devices that are registered and enabled.
- `:fabric:26.2:runClientGameTest` runs the Fabric client game tests (`fabric/src/gametest`): a real client joins an
  in-process dedicated server as a non-op player. It opens a game window, so `check` only compiles them; how to run
  them, the suites (33 entrypoints since M4's `timers`, `world_devices`, `telemetry`, `privacy`, `rooms` and `explorer`; `TwinFixture` gives the twin suites a per-run
  statestream base) and the helpers are in `e2e/README.md`. `e2e/tools/run-gametests.sh [suites]` runs them one window at a
  time across worktrees (a machine-wide lock; `MINEQTT_LANE` sets the bridge id, `MINEQTT_GAMETEST_SUITES` filters suites).

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

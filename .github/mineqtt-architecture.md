# MineQTT v2 architecture

How MineQTT is put together since v2 (milestone M1), and why. The full design, with every decision, open question and
roadmap item, is not part of this repository. This file is the map for
anyone changing the code; read it before touching the MQTT core, a device type or the migration code.

## What v2 replaced

v1's rules were workarounds for its own architecture. They no longer apply:

| v1 rule | v2 |
|---|---|
| "No retain anywhere": status, state and discovery not retained | Everything MineQTT publishes **about itself** is retained: discovery, bridge and device availability, state, bridge info. **Commands are never retained**, and a retained message on a device's `/set` topics is never taken, so a stale command on the broker cannot change a device at startup. Raw Link inputs that keep v1 parity (Redstone Output) and the unmigrated v1 blocks on `LegacySubscriptionFacade` still apply retained values, so their topics need broker ACL protection. (v1's discovery was in fact published retained; the old docs said otherwise.) |
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
 │ unmigrated BEs ── LegacySubscriptionFacade ─┤             │      │  global publish flow  │
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
| `mqtt/core` | The transport. **Minecraft-free**: only the JDK, Gson and HiveMQ (`verifyMqttCoreIsolation` fails the build otherwise). `BrokerConfig` (URI parsing), `HiveMqttService` with `Mqtt5ClientAdapter`/`Mqtt3ClientAdapter`, `TopicTrie`, `InboundQueue`, `OutboundBuffer`, `LocalLoopback`, `TopicAcl` (rules as data). |
| `runtime` | `MineqttRuntime` (one per server run), `ServerHooks` (lifecycle wiring), `ReplaceableMqttService` (survives config reconnects), `TopicScheme`, `Slf4jMqttLog`. |
| `device` | `DeviceType`/`Field`/`Setting` declarations, `DeviceLink` (per-BE state), `DeviceRuntime` (attach, routing, groups, pending commands), `DeviceRegistry` + `RegistryStore`, `RawLink`, `Slugs`, `ValueCodecs`. `device/types` holds `SmartLamp`, `RedstoneOutput`, `RedstoneInput`, `PresenceSensor`. |
| `ha` | `DiscoveryPublisher`, `BirthWatcher`, `BridgeDevice`, `LegacyDiscoveryMigration`, `Ha`/`HaEntity` component builders. |
| `legacy` | `LegacySubscriptionFacade` (unmigrated blocks), `LegacyNbtV1` (v1 NBT reader), `LegacyTopicStateImport` (v1 topic-state file). |
| `security` | `AclContext` (player → ACL subject), `C2SGuard` + `TokenBucket`/`RateLimit`, `DeviceAccess`, `AccessMode`. |

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
  no-local, a short digest window of local publishes recognises and drops the broker's echo.
- **Outbound.** Every publish is an `Outbound` with a delivery class: `STATE` (coalesced per topic, deduplicated against
  the last bytes sent), `EDGE` (raw outputs and events, never coalesced) and an `OfflinePolicy` (`COALESCE`, `KEEP` in
  the outbox, `DROP`). `flushOutbound` runs in `SERVER_POST`. After a reconnect the outbox replays first.
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
| `<base>/<bridgeId>/<slug>/action` | no | events (Redstone Input pulse) |
| `<base>/<bridgeId>/group/<slug>` (+ `/set`) | yes / never | group state and commands |
| `homeassistant/device/mineqtt_<uuid32>/config` | yes | device-based discovery |
| raw topics (Raw Link) | per device (default no) | v1 payload grammar, QoS 0/1 per device |

Unloaded devices follow their type's `UnloadedPolicy`: `QUEUE` devices (lamp, output, groups) stay `online`, a command
is stored as the pending target and applied at the next attach; `UNAVAILABLE` devices (sensors) go `offline` on detach,
and every CONNACK forces unattached `UNAVAILABLE` records `offline`, so a retained `online` from a crash never survives.

## Devices

- **Declared once.** A `DeviceType` lists its fields (with value codecs and access), its HA components, its unloaded
  policy, minimum state interval and deadband. Discovery payloads, command parsing and the queued-command preview are
  generated from it.
- **Identity per block.** Each device BE holds a `DeviceLink` with a random UUID (`mineqtt.id`), name, slug, owner,
  access mode, expose flag, Raw Link and state. Discovery object id `mineqtt_<uuid32>`, unique_ids
  `mineqtt_<uuid32>_<key>`; renaming changes the slug and topics but never the unique_ids.
- **Server-only lifecycle.** `BaseMqttBlockEntity.setLevel(ServerLevel)` schedules the attach one tick later (covers
  placement, chunk load, `/clone`, structures). `setRemoved` detaches; `preRemoveSideEffects` plus a block-state check
  in `setRemoved` destroys. Attach merges the BE with its registry record (registry wins for name, slug, group; BE wins
  for state, owner and expose), applies pending commands, publishes availability, state and discovery.
- **Registry.** `<world>/data/mineqtt/devices.nbt`, written atomically by `RegistryStore` (not `SavedData`: a `null`
  `DataFixTypes` wipes it on the second start). It holds the bridge id, world UUID, records (type, position, slug,
  pending command, last state, discovery hash, legacy unique_id), groups and migration flags. Saved when created, on
  overworld saves when dirty, and at `SERVER_STOPPED`.
- **Groups.** Homogeneous, actuator-only. A group command fans out to members (applied or queued). Members are hidden
  from HA by default; HA sees one entity per group. Member-local changes do not update the group state.
- **Raw Link.** The v1 "topic" of a migrated block, kept as an extra binding next to the device topics, edited through
  the item slots in M1. Inputs subscribe with no-local + loopback; outputs publish edges. Every topic is checked by the
  `TopicAcl`; v1 topics of migrated blocks are grandfathered to their owner.

## Home Assistant

- **Device-based discovery** (`cmps`), one retained config per device, a **MineQTT Bridge** device (keyed by the world
  UUID, so a `bridgeId` change does not orphan it) as `via_device` of every device. The primary entity has
  `name: null`, so HA names it after the device (`light.kitchen`).
- **Availability** is `[bridge/state, <slug>/availability]` with `availability_mode: all`. The bridge's own
  connectivity sensor has no availability, so it shows `off` (not unavailable) when the bridge is offline.
- **Connect sequence** (every CONNACK): bridge `online`, one batched SUBSCRIBE, force unattached sensors `offline`,
  discovery at ≤50 msg/s, then 1 s later availability and state, then the pending v1 discovery migrations, then the
  outbox. `homeassistant/status online` (HA birth) repeats discovery and state after a random 0-3 s delay;
  `bridge/request/republish` and `/mineqtt discovery republish` repeat them at once.
- **Exposure.** `ha.expose`: `ALL` (default on integrated servers), `OPT_IN` (default on dedicated servers; a device is
  discovered once a permitted player exposes it, migrated v1 lamps are grandfathered), `NONE`.
- **v1 migration, per topic** (`LegacyDiscoveryMigration`): `{"migrate_discovery": true}` on the old
  `homeassistant/light/<id>/config`, ≥2 s later the new device config carrying the v1 unique_id on its light, ≥2 s later
  an empty retained payload on the old topic; the topic is marked done on that PUBACK. HA keeps the entity registry
  entry, entity id and history. The v1 retained `Offline` status is cleared once.

## Security

- `TopicAcl` (pure, in `mqtt/core`) decides every topic a player, a block or a command uses. Hard denies (discovery
  prefix, bridge namespace, `$SYS`, other players' private subtrees) apply even to ops; the raw allowlist
  (`acl.rawAllow`) comes from the config; v1 topics of migrated devices are grandfathered per owner.
- `C2SGuard` validates every client-to-server payload: rate limit first, then menu/BE lookup, reach, claim permission
  (`MineqttPermission.USE`/`CONFIGURE`, Flan when present) and size limits.
- Placement data from items (`BLOCK_ENTITY_DATA`) is untrusted: a fresh id is minted, the copied name, slug and
  grandfathering are dropped, the placer becomes the owner and the Raw Link is re-checked for them.
- The Block Placer (ex block emitter) is off unless `blockPlacer.enabled`. MQTT never runs server commands.

## Migration of v1 worlds

- Block, item and BE-type ids are unchanged. Unmigrated blocks keep their exact v1 NBT and run on
  `LegacySubscriptionFacade` (registration only for server BEs, hard-deny ACL, no-local + loopback).
- A migrated type reads v1 NBT through `LegacyNbtV1` when the `mineqtt` compound is missing and keeps writing v1 keys
  until the device has attached; adoption happens at attach (`LegacyAdoption`).
- `LegacyTopicStateImport` runs once before the first connect: a v1 topic with one lamp becomes a pre-created record,
  a topic with several lamps a legacy group. Records carry the v1 unique_id, so HA continuity does not depend on anyone
  visiting the lamps. The v1 file is read, never written or renamed.
- Config files migrate to `configVersion: 2` on both loaders; the v1 file is kept as `mineqtt.json.v1.bak` /
  `mineqtt.toml.v1.bak`.

## Testing

- `./gradlew check` runs plain `main`-method checks: `sensorChecks` (bootstrapped Minecraft: block sensor, emitter,
  timers, value codecs, registry round trip, lifecycle guards, legacy NBT migration, discovery payload golden files,
  config migration, security guards) and `brokerChecks` (no Minecraft: topic trie, broker URIs, ACL, queues, outbound
  buffer, and `verifyBrokerIntegration` against an in-process Moquette, MQTT 3.1.1 only).
- `e2e/` is the manual end-to-end environment: Mosquitto 2 (TCP, TLS, WebSockets), Mosquitto 1.5, and Home Assistant at
  the supported floor and current stable. Its README holds the checklist and the last recorded results, including the
  MQTT 5 integration run (`-PexternalBroker`).

## Pitfalls

- Do not touch MQTT from client block entities or from `loadAdditional`/`saveAdditional`; go through `DeviceLink`.
- Do not subscribe per device; route through the registry by slug.
- Do not call `MineqttRuntime.of(server)` before `SERVER_STARTED`; pre-start chunk loads are queued until attach runs.
- A retained message is only ever taken as truth for Raw Link inputs that accept it (outputs, v1 parity) and for M3
  mirror sources; never for commands.
- When a v1 data path changes, extend `verifyLegacyNbtMigration`; when a discovery payload changes, update the golden
  files in `common/src/sensorChecks/resources/golden`.

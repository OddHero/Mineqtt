# MineQTT end-to-end checklist

Manual end-to-end tests against real brokers and real Home Assistant instances (design §10.3). `./gradlew check` covers
everything that runs without Docker; this checklist covers what only a real game server, broker and Home Assistant
can show.

## Environment

| Service | Image | Host port (default) | Purpose |
|---|---|---|---|
| `mosquitto` | `eclipse-mosquitto:2` | 1883 TCP, 8883 TLS, 9001 WebSocket, 9443 secure WebSocket | Main broker, MQTT 3.1.1 and 5 |
| `mosquitto-1.5` | `eclipse-mosquitto:1.5` | 1885 | MQTT 3.1.1-only broker |
| `homeassistant` | `ghcr.io/home-assistant/home-assistant:2026.10.0` | 8123 | Current stable |
| `homeassistant-floor` | `ghcr.io/home-assistant/home-assistant:2025.2.5` | 8124 | Oldest supported version |

Every host port can be moved with an environment variable (`MQTT_PORT`, `MQTTS_PORT`, `WS_PORT`, `WSS_PORT`,
`MQTT15_PORT`, `HA_PORT`, `HA_FLOOR_PORT`) when the default is taken. The host ports are bound to 127.0.0.1 only: the
brokers accept anonymous clients and `ha.py` onboards Home Assistant with a fixed test owner. Inside the compose network
Home Assistant reaches the broker as `mosquitto:1883`.

```sh
cd e2e
sh certs/gen.sh                       # test CA, broker certificate and a wrong CA (git-ignored)
docker compose up -d
python ha.py --url http://localhost:8123 onboard     # owner user "mineqtt", stores a token in e2e/.state/
python ha.py --url http://localhost:8123 mqtt        # MQTT integration -> mosquitto:1883
python ha.py --url http://localhost:8124 onboard
python ha.py --url http://localhost:8124 mqtt
```

`ha.py` (Python standard library only) also lists what Home Assistant made of the discovery:
`python ha.py --url … devices` (devices with model and `via_device`), `entities [text]` (entity id, unique_id, naming,
state), `state <entity_id>`, `call <domain.service> '<json>'`, `restart`, and `dump <file>` (both registries and all
states, for before/after comparisons). Home Assistant logs MQTT discovery at debug level (`docker compose logs
homeassistant`).

Game side: a dedicated server (`./gradlew :fabric:runServer` or `:neoforge:runServer`) with `broker` set to one of the
listeners, for example `tcp://localhost:1883`, or `mqtts://localhost:8883` with `tls.caFile` pointing at
`e2e/certs/ca.crt`. RCON (`enable-rcon=true` in `server.properties`) makes the checklist scriptable.

`tools/` holds the game-side helpers the recorded run used (Python standard library only):

- `game.py`: edit the dev server's `mineqtt.json`, publish/subscribe/list/clear retained topics through the compose
  brokers, RCON commands, turn placed blocks into v1 data (`v1lamp`, `v1`, `v1motion`) and publish v1's exact light
  discovery (`v1disc`). Set `MINEQTT_RCON_PORT`/`MINEQTT_RCON_PASSWORD` to match `server.properties` (defaults 25611 /
  `mineqtt-e2e`).
- `buildv1.sh`: builds the v1 world of items 5-7 (run it with MQTT disabled, stop the server, then upgrade).
- `rcon.py` (RCON client), `regdiff.py` (entity registry entries from a `ha.py dump`), `connack.py` (raw reply of a
  broker to an MQTT 5 CONNECT, item 4).

Item 12 needs a client. A Fabric client game test (`fabric-client-gametest-api-v1`, entrypoint `fabric-client-gametest`,
`-Dfabric.client.gametest`) can drive it without a person: `worldBuilder().createServer()` starts a dedicated server in
the client's JVM, and the joining player is not an operator. In dev runs, the client then waits 15 s at exit and writes
a "Client shutdown from post-main" watchdog crash report. The thread dump shows two idle fixed pools of non-daemon
threads that exist before any server starts. They match the `newFixedThreadPool` calls in the Architectury
Transformer's dev runtime (`TinyRemapper`, `ReadClasspathProviderImpl`). MineQTT itself creates no executors.

Tear down with `docker compose down -v`. The brokers keep nothing on disk, so this also removes every retained message.

## Checklist

| # | Scenario | Expected |
|---|---|---|
| 1 | Fresh world, `broker: tcp://localhost:1883`, protocol 5 | The bridge and the four device types appear in HA, each device with the bridge as `via_device`. Availability flips when the server stops (graceful) and when it is killed (last will). |
| 2 | Same over `mqtts://localhost:8883` with `tls.caFile` | Connects. A wrong CA (`certs/wrong-ca.crt`) fails with a clear status message. |
| 3 | Same over `ws://localhost:9001/mqtt` and `wss://localhost:9443/mqtt` | Connects. |
| 4 | `protocol: 5` against `tcp://localhost:1885` (Mosquitto 1.5) | Connection fails and `/mineqtt status` shows the 3.1.1 hint. With `protocol: 3.1.1` everything works. |
| 5 | **v1 world, HA online:** one single-topic lamp, one shared-topic pair (one lamp in an unloaded chunk), an emitter/publisher wireless link and a motion sensor; the v1 discovery published as v1 did; HA created the v1 entities | After upgrading, the same `light.*` entity ids exist with their history. The pair is one light. The wireless link still works, also with the broker stopped. No `homeassistant/light/...` configs remain. |
| 6 | **v1 world, HA offline during the upgrade** (v1 config retained on the broker) | When HA starts, it adopts the legacy unique_id through the new device config, with no duplicate entity. |
| 7 | **v1 world whose v1 config was already cleared** by an unload | The new device config recreates the entity with the legacy unique_id. |
| 8 | HA restart | Birth, then republish. Entities return without restarting the server. |
| 9 | Retained `ON` published by hand to a `/set` topic, then server restart | World state unchanged. |
| 10 | Dedicated server with `ha.expose=OPT_IN` | New devices are not discovered until exposed. Migrated lamps are discovered (grandfathered). |
| 11 | Unloaded chunk: command a lamp from HA, then load the chunk | The lamp applies the queued state. Sensors in unloaded chunks show unavailable, also after a crash (kill) and restart. |
| 12 | Security: a non-op creative player picks a lamp with NBT whose raw topic is `zigbee2mqtt/x/set` and places it | The raw link is disabled and the owner is the placer. Cyberdeck listen cannot see `homeassistant/#` payloads or other players' devices. |
| 13 | `./gradlew :common:verifyBrokerIntegration -PexternalBroker=tcp://localhost:1883`, and again with `-PexternalBroker=ws://localhost:9001/mqtt` | Every scenario passes for both `MQTT_5` and `MQTT_3_1_1` (only "Refused covering filter" is skipped: it needs the embedded broker's ACL). This is the only coverage of the MQTT 5 scenarios, including no-local and `DO_NOT_SEND`. |

### Building a v1 world (items 5-7)

1. Start the server with MQTT disabled (`broker` empty) on a new world and place the blocks with `/setblock`
   (`/forceload add` first: 26.2 dedicated servers keep no spawn chunks loaded).
2. Replace each block's v2 data with v1 keys: `/data remove block <pos> mineqtt`, then `/data merge block <pos> {…}` with
   the v1 keys (`topic`, `ownerName`, `privateMode`, `Items` with a named paper in slot 0, and per type `Red`/`Green`/
   `Blue`/`TargetRed`/`TargetGreen`/`TargetBlue`/`Brightness`/`Lit`, `currentSubscribedTopic`, or
   `MotionDetected`/`LastMotionTime`/`LastCount`). Unload far chunks with `/forceload remove`, `save-all`, stop.
3. Delete `<world>/data/mineqtt/devices.nbt*` and write `<world>/data/mineqtt/mineqtt_topic_states.json`
   (`{"topicStates":{"home/kitchen":{"targetRed":15,…,"brightness":255,"lit":true,"blockPositions":["minecraft:overworld:0:-60:0"]}}}`).
4. Replace the config with a v1 file (`brokerUrl`, `clientId`, `baseTopic: minecraft-<clientId>`, no `configVersion`)
   and publish what v1 left on the broker: `minecraft-<clientId>/status` = `Offline` (retained), and per topic the
   retained `homeassistant/light/<sanitized topic>/config` exactly as v1's `HomeAssistantLight.toJson()` built it
   (`name` "RGB LED <topic>", `unique_id` = topic with every character outside `[a-zA-Z0-9_-]` replaced by `_`,
   `state_topic` `<topic>/state`, `supported_color_modes` `["rgb","hs","xy"]`, `color_temp` true with mireds 153-500,
   `device.identifiers` the unique_id as a string, `device.name` "MineQTT RGB LED").
5. Note the entity registry entries (`python ha.py dump before.json`), start the server (the upgrade), compare.

Home Assistant 2025.2 to 2025.x rejects v1's light config ("supported_color_modes must not be combined with
color_temp"), so on those versions the v1 lamps never had an entity; the upgrade creates it with the v1 unique_id.
Items 5 and 6 can only show entity-id continuity on a Home Assistant version that accepted the v1 config (2026.10 does).

## Results

### 2026-10-08, M1 (`feature/mqtt-core-rework`), Fabric dedicated dev server, both HA versions online unless noted

| # | Result | Evidence |
|---|---|---|
| 1 | PASS (both HA) | Bridge `t11world` plus Smart Lamp, Redstone Output, Redstone Input and Presence Sensor devices, all `via_device` = the bridge. HA commands applied in game (lamp RGB and kelvin, output power/switch, presence `detect`); input `powered`/`signal`/pulse event and presence `occupancy`/`count` reached HA. Graceful stop: the 17 other enabled entities (devices and bridge) `unavailable`, bridge connectivity `off`. Kill: the same through the last will. |
| 2 | PASS | `mqtts://localhost:8883` with `tls.caFile=ca.crt`: connected, HA round trip. `wrong-ca.crt`: `RECONNECTING (ConnectionFailedException: javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path building failed …)`. |
| 3 | PASS after fix | First run: every `ws://` connect failed in the dev server with `NoSuchMethodError DefaultHeaders.containsAny` (HiveMQ's Netty 4.1 `netty-codec` on the dev classpath shadowed Minecraft's 4.2 `netty-codec-base`). Fixed in `fix: run HiveMQ on Minecraft's Netty in dev runs`. Then `ws://localhost:9001/mqtt` and `wss://localhost:9443/mqtt` (CA) connected with HA round trips on Fabric, `ws://` also on NeoForge. |
| 4 | PASS after fix | Mosquitto 1.5 answers an MQTT 5 CONNECT with the 3.1.1 CONNACK `20 02 00 01`, which HiveMQ reports as `UNSUPPORTED_PROTOCOL_VERSION`; the status showed only that error, no hint. Fixed in `fix: show the MQTT 3.1.1 hint when the broker refuses MQTT 5 by CONNACK`; then `/mineqtt status`: `RECONNECTING (… UNSUPPORTED_PROTOCOL_VERSION. (the broker refused or dropped the MQTT 5 CONNECT - if it only supports MQTT 3.1.1 set protocol: 3.1.1))`. With `protocol: 3.1.1`: connected, bridge/state, info, device states and discovery retained on the 1.5 broker, `/set` and `/set/power` applied. `/mineqtt status` shows the 3.1.1 packet-size note. |
| 5 | PASS (stable HA) | Pre-upgrade entities `light.overworld_mineqtt_rgb_led_rgb_led_home_kitchen` / `…_home_hall` (HA 2026.10 put the v1 suggested area into the entity id). After the upgrade: same entity registry entry ids, same entity ids, unique_ids `home_kitchen`/`home_hall`, devices now "Kitchen" and "Hall" (model "Smart Lamp group") under bridge `t11v1`; HA logged "Migration to MQTT device discovery schema started" for both. The pair is one light (`hall` group, members hidden, one member unloaded); a HA command reached the loaded member. Config migrated (v1 backup kept), `minecraft-t11host/status` cleared, no `homeassistant/light/...` configs left except the not yet loaded `home_office` (item 6). Wireless link: publisher → emitter 15/0/15, also with the broker container paused. Leftover: an empty "MineQTT RGB LED" device per migrated topic stays in HA's device registry (README "Upgrading from v1"). Floor HA 2025.2.5 rejected the v1 configs (see above) and created `light.kitchen`/`light.hall`/`light.garden` with the v1 unique_ids. |
| 6 | PASS (stable HA) | Stable HA stopped, office lamp's chunk loaded (adopted, migrated, v1 config cleared), HA started: `light.overworld_mineqtt_rgb_led_rgb_led_home_office`, same registry entry id, device "Office" via `t11v1`, no duplicate. |
| 7 | PASS (stable HA) | `home/garden` v1 config cleared before the upgrade (HA removed the entity); after the upgrade HA recreated it with unique_id `home_garden` and the old entity id `light.overworld_mineqtt_rgb_led_rgb_led_home_garden`. |
| 8 | PASS | HA restart (both versions): `homeassistant/status offline` → `online`, MineQTT republished all device configs within the same second and the states one second later; entities returned with their states, no server restart. |
| 9 | PASS | MQTT 5: retained `{"state":"OFF"}` on the output's `/set` and `OFF` on the lamp's `/set/state`, restart after a kill: output stayed at power 7, lamp ON. MQTT 3.1.1 (Mosquitto 1.5): retained `/set/power 9`, restart: power stayed 5, log "Ignoring retained MQTT messages for mineqtt/…/+/set/+ … (commands are never taken from retained messages)". |
| 10 | PASS | Dedicated server, `ha.expose=AUTO` (OPT_IN): the four unexposed devices' configs were cleared, a new lamp was not discovered; `mineqtt.expose:1b` via `/data` plus a reattach published it. v1 world on the same mode: migrated lamps discovered (grandfathered), migrated emitter/publisher/motion sensor not. |
| 11 | PASS | Lamp in an unloaded chunk stayed `online`; `light.turn_on` blue/200 from HA updated its published state at once; on chunk load the lamp applied it (light level 12). Sensor in the unloaded chunk `offline`/unavailable; a hand-retained `online` was forced back to `offline` by the CONNACK after a kill and restart. |
| 12 | PASS | Driven by a Fabric client game test: a real client joined an in-process dedicated server (Docker Mosquitto 2, MQTT 5) as non-op creative player `Player725`. Another player's private lamp with the grandfathered raw topic `zigbee2mqtt/x/set` was copied with Ctrl + pick block (the item carried owner `Victim` and the grandfather flag) and placed: new device id, owner `Player725`, grandfather flag dropped, raw link disabled ("not in the raw topic allowlist (acl.rawAllow)"). Cyberdeck opened with the use key, Explorer tab, Listen clicked; then published a retained `homeassistant/light/…/config`, a `homeassistant/…/state`, a command to the other player's lamp and a message on their `…/raw/players/<uuid>/…` topic. None of these arrived, and neither did the state, availability or `/set` topics of the other player's lamp. The bridge topics, the player's own lamp and `mineqtt/shared/…` did arrive. With the default access mode `claim` and no claim mod, the other player's lamp is shown, as designed (design §8.7). |
| 13 | PASS | `tcp://localhost:1883` and `ws://localhost:9001/mqtt`, each: 26 PASSED (MQTT disabled, the 3.1.1 CONNACK refusal stub, scenarios 1-10, 12, 13 for both protocols), 2 SKIPPED ("Refused covering filter", needs the embedded broker's ACL). |

Home Assistant version floor: 2025.2.5 passed items 1 and 8 and created the migrated lamps with their v1 unique_ids.
2025.1.4 created the devices too but ignored `color_temp_kelvin`/`min_kelvin`/`max_kelvin`: it sent mireds and showed
a 3003 K lamp as `color_temp_kelvin: 333` with a 2000-6535 K range. Device-based discovery and `migrate_discovery`
already exist in 2024.11; the kelvin options arrived in 2025.2.

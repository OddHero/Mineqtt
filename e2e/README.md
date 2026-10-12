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
| `ha-config-init` | `alpine:3` | none | Runs once before both Home Assistants: creates `automations.yaml` (an empty list) in their volumes, see [Twin fixture](#twin-fixture-m3) |

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
python ha.py --url http://localhost:8124 login       # instead of onboard: a token for an onboarded volume (fresh checkout)
```

`ha.py` (Python standard library only) also lists what Home Assistant made of the discovery:
`python ha.py --url … devices` (devices with model and `via_device`), `entities [text]` (entity id, unique_id, naming,
state), `state <entity_id>`, `call <domain.service> '<json>'`, `restart`, `dump <file>` (both registries and all
states, for before/after comparisons), `triggers <device name>` (the device automation triggers Home Assistant offers for
it) and `watch <device name> [seconds]` (subscribes to its MQTT device triggers and prints each one that fires). Home Assistant logs MQTT discovery at debug level (`docker compose logs
homeassistant`). For the twin (M3) it also manages labels (`label create <name>`, `label add|remove <label>
<entity|device name>…`, `label list`), turns a blueprint into an automation (`automation from-blueprint <file> <id>
'<inputs-json>'`, and `automation delete <id>`), and reads an entity's logbook (`logbook <entity> [minutes]`) and an
automation's traces (`trace <automation> [run_id|last]`; a run's trace shows the service data each step sent).
For the world twin (M4) it presses, sets and selects entities, reads an event entity's last event, manages areas and
checks a dashboard's entities: see [World-twin fixture](#world-twin-fixture-m4).

Game side: a dedicated server (`./gradlew :fabric:runServer` or `:neoforge:runServer`) with `broker` set to one of the
listeners, for example `tcp://localhost:1883`, or `mqtts://localhost:8883` with `tls.caFile` pointing at
`e2e/certs/ca.crt`. RCON (`enable-rcon=true` in `server.properties`) makes the checklist scriptable.

`tools/` holds the game-side helpers the recorded run used (Python standard library only):

- `game.py`: edit the dev server's `mineqtt.json`, publish/subscribe/list/clear retained topics through the compose
  brokers, RCON commands, turn placed blocks into v1 data (`v1lamp`, `v1`, `v1motion`), set a v1 temperature or humidity
  sensor with its v1 data (`v1climate`, a humidity sensor converts into a Climate Sensor), set a v1 Static Publisher
  (`v1static`, it converts into a Redstone Input of rising edges), set a v1 Topic Relay or Dynamic Publisher
  (`v1retired`, it loads decommissioned) and publish v1's exact light discovery (`v1disc`). Set `MINEQTT_RCON_PORT`/`MINEQTT_RCON_PASSWORD` to match `server.properties` (defaults 25611 /
  `mineqtt-e2e`).
- `buildv1.sh`: builds the v1 world of items 5-7 (run it with MQTT disabled, stop the server, then upgrade).
- `allblocks.sh`: places one of each block entity with data and checks it after a restart (item 15, either loader).
- `buildm1.sh`: builds an M1 world (device data beside their slots) with a jar from before M2-12, prints its block
  entity data, and checks the upgraded world (item 16).
- `build07.sh`: builds the MineQTT 0.7.0 jars (Fabric and NeoForge, `--loader` picks one) from the commit that set
  `mod_version=0.7.0`, exported with `git archive` into a temporary folder, so nothing in the working tree changes;
  the jars land in `build/mineqtt-0.7.0/`. A test lane runs one with `MINEQTT_LANE_JAR=<jar> sh lanes/lane.sh up <id>`
  (or `reload <id>`), see [World-twin fixture](#world-twin-fixture-m4).
- `upgrade07.sh` (item 40, M4-21): the upgrade of a world and a config that 0.7.0 wrote. `place` sets, on a fresh
  world of a 0.7.0 server, the four timers (one mid-count, a Daylight Timer at a custom time off a minute boundary,
  an Alarm Clock that fires on a standing day clock, a custom cron; both slots filled through NBT, an owner UUID), a
  second Daylight Timer at sunrise, a Block Sensor and a Block Emitter on one topic and a configured Smart Lamp, then
  freezes the ticks, saves and records what 0.7.0 counted in `build/upgrade07/`; `verify`, after the jar swap, checks
  each of them as a device and prints `upgrade07: PASS`. `upgrade07.sh lane <id>` runs it all on a test lane (Fabric);
  without `--lane` it talks RCON through `game.py`, so a NeoForge server works the same.
- `fake_bulb.py` (item 31, M3-17): a fake zigbee2mqtt colour bulb, standard library only: retained JSON state on
  `zigbee2mqtt/<name>`, obeys `/set` (`state` ON/OFF/TOGGLE, `brightness` clamped to 254, `color` as r/g/b, x/y or
  hue/saturation, `color_temp` in mireds), availability `online`; against a broker (`--host`/`--port`) or a test lane
  (`--lane <id>`, through `lane.sh pub/sub`); `--clear` deletes its retained topics.
- `rcon.py` (RCON client), `regdiff.py` (entity registry entries from a `ha.py dump`), `connack.py` (raw reply of a
  broker to an MQTT 5 CONNECT, item 4).

Tear down with `docker compose down -v`. The brokers keep nothing on disk, so this also removes every retained message.

### Twin fixture (M3)

Both Home Assistant instances run a fixed set of test entities for the digital twin (M3 plan M3-1), and
`mqtt_statestream` is always on in `homeassistant/configuration.yaml`: base topic `ha_statestream`, attributes and
timestamps published, and an `include:` of exactly the twelve fixture entities below. Every state change of one of them
is published retained at QoS 1 as `ha_statestream/<domain>/<object_id>/<leaf>` (`state`, `last_changed`,
`last_updated` and one topic per attribute).

| Entity | What it is | Drives or is driven by |
|---|---|---|
| `input_boolean.m3_flag` | toggle | `light.m3_lamp` on/off |
| `input_boolean.m3_other` | toggle, never labelled for control | `scene.m3_scene` turns it on |
| `input_number.m3_level` | 0..100 %, step 0.5, slider | `light.m3_lamp` brightness (0..255), `sensor.m3_temperature` |
| `input_select.m3_mode` | `Eco`, `Comfort`, `Turbo Mode` | `scene.m3_scene` selects `Comfort` |
| `input_text.m3_note` | text, `initial: Grüße "quoted" / slash`, so every Home Assistant start resets it (cap §5) | |
| `input_button.m3_button` | button | |
| `counter.m3_counter` | counter from 0 | `script.m3_script` increments it |
| `light.m3_lamp` | template light, brightness only (no colour attributes) | `m3_flag`, `m3_level` |
| `sensor.m3_temperature` | template sensor, °C, one decimal: 15 + level / 10 | `m3_level` |
| `lock.m3_lock` | template lock, locked while `m3_bolt` is on | `input_boolean.m3_bolt` |
| `scene.m3_scene` | scene | sets `m3_other` and `m3_mode` |
| `script.m3_script` | script | increments `m3_counter` |
| `input_boolean.m3_bolt` | **not** in the statestream include | `lock.m3_lock`; the include-filter and drift cases |

Where they live:
- `homeassistant/packages/common/mineqtt_twin.yaml`: everything except the template light and lock, in a syntax both
  versions accept. Compose mounts it as `/config/packages/common`.
- `homeassistant/packages/2026.10/` and `homeassistant/packages/2025.2/`: `mineqtt_twin_templates.yaml` with
  `light.m3_lamp` and `lock.m3_lock`. No single syntax works on both pinned versions: 2025.2.5's `template:`
  integration has no lights or locks, and 2026.10.0 no longer sets up the legacy `light: - platform: template` (it
  only raises a repair issue). Each service mounts its own folder as `/config/packages/version`; keep both files equal.
- `configuration.yaml` loads them with `homeassistant: packages: !include_dir_named packages` (which searches
  subfolders, so every file name below `packages/` must be unique) and has `automation: !include automations.yaml`
  for the automation editor that `ha.py automation from-blueprint` uses. `ha-config-init` creates that file in fresh
  volumes, because an `!include` of a missing file starts Home Assistant in recovery mode.
- `../docs/blueprints/automation/mineqtt/` (the MineQTT blueprints) is mounted read-only at
  `/config/blueprints/automation/mineqtt/` in both services.

Every fixture entity has an entity registry entry, so `ha.py label` can label it. The twin's Home Assistant setup:

```sh
cd e2e
python ha.py --url http://localhost:8123 label create "MineQTT read"        # id mineqtt_read
python ha.py --url http://localhost:8123 label create "MineQTT control"     # id mineqtt_control
python ha.py --url http://localhost:8123 label add mineqtt_control light.m3_lamp input_boolean.m3_flag
python ha.py --url http://localhost:8123 label add mineqtt_read input_number.m3_level sensor.m3_temperature
python ha.py --url http://localhost:8123 automation from-blueprint mineqtt_control.yaml mineqtt_control '{}'   # M3-10 on
python ha.py --url http://localhost:8123 automation from-blueprint mineqtt_catalog.yaml mineqtt_catalog '{}'   # M3-11 on
python tools/game.py sub 5 'ha_statestream/#'                                # what Minecraft sees
```

The fixture check (M3-1), on each version after `docker compose up -d` (compose recreates a container whose mounts
changed; after an edit of a fixture file, `ha.py restart` makes Home Assistant read it):
1. Home Assistant starts without a configuration error or repair issue for the fixture (`docker compose logs`), and
   `ha.py state` answers for all thirteen entities.
2. One change of each included entity (`ha.py call input_boolean.toggle '{"entity_id":"input_boolean.m3_flag"}'`, and
   likewise `set_value`, `select_option`, `press`, `increment`, `light.turn_on` with a brightness, `lock.lock`,
   `scene.turn_on`, `script.turn_on`) appears under `ha_statestream/#`, retained.
3. Only included entities appear: `input_boolean.m3_bolt` never does, although switching it changes `lock.m3_lock`
   (which does). This is the first proof that the include filter works as documented (cap §1, §10.7).
4. On 2025.2.5 the format matches cap §7 on 2026.10.0: `state` raw (not JSON; `m3_note` shows the quotes and
   umlauts as they are, `m3_mode` `Turbo Mode` unquoted), attributes as JSON (`"°C"` for the unit), timestamps
   as raw ISO strings, retain 1 and QoS 1 (`mosquitto_sub -v -F '%r %q %t %p'`).

### World-twin fixture (M4)

What the M4 checks (items 32 to 40) need beyond the M3 fixture: a few more `ha.py` helpers, Home Assistant areas, a
dashboard check and the 0.7.0 jars to upgrade from.

`ha.py` helpers (each prints what it did or found; a refusal by Home Assistant exits non-zero with its message):

| Command | What it does |
|---|---|
| `state <entity>` | the entity's state and attributes as JSON (as before) |
| `attrs <entity>` | only its attributes, e.g. the `players` of the bridge's *Players online* |
| `press <entity>` | `button.press` (or `input_button.press`), e.g. a timer's *Trigger now* |
| `set-text <entity> <value>` | `text.set_value` (or `input_text`); Home Assistant refuses a value outside the entity's `pattern` (`25:00` for a timer's time) |
| `select <entity> <option>` | `select.select_option` (or `input_select`); an option the entity does not offer is refused before the call |
| `event-last <entity> [seconds]` | an event entity's last event: its time, `event_type` and data; with seconds it waits for a newer event and fails without one |
| `areas` | every area with its id and the devices in it |
| `areas create <name>`, `areas delete <name>` | create or delete an area (by name, any case, or id); an existing or a missing one is left as it is |
| `area-of <device>` | the area of the device with that name, and any of its entities placed in another area |
| `dashboard-check <file>` | see below |

Areas live in Home Assistant's area registry, not in the fixture YAML, so a fresh volume has only what onboarding
created. The set-up for items 37 and 39, on each version after `onboard`:

```sh
python ha.py --url http://localhost:8123 areas                        # what onboarding created; record it per version
python ha.py --url http://localhost:8123 areas delete Kitchen         # item 37 needs Kitchen missing, to see whether suggested_area creates it
python ha.py --url http://localhost:8123 areas create "Living Room"   # item 39: an existing area whose spelling a room reuses
python ha.py --url http://localhost:8123 areas                        # Living Room listed, no Kitchen
```

`dashboard-check <file>` reads a dashboard YAML (the standard library has no YAML parser, so the file is scanned, not
parsed): every entity id it names, as an `entity:` value, an `entities:` list item or inside a template such as
`states('sensor.x')`, outside comments, must be a registered entity of a MineQTT device that is not disabled. A
dashboard that names the bridge's MSPT sensor therefore fails until that entity is enabled. Each id is printed with
its verdict (`OK`, `MISSING`, `NOT MINEQTT`, `DISABLED`) and its state; an unavailable state passes, so the check
also runs with the game server stopped. Any other verdict fails the command.

The 0.7.0 jars: `sh tools/build07.sh` (one Gradle build in a temporary folder, under a minute when Gradle's caches
are warm) prints the paths of `build/mineqtt-0.7.0/MineQTT-fabric-0.7.0.jar` and `MineQTT-neoforge-0.7.0.jar`.
`MINEQTT_LANE_JAR=build/mineqtt-0.7.0/MineQTT-fabric-0.7.0.jar sh e2e/lanes/lane.sh up <id>` starts a lane on 0.7.0
(its log lists `mineqtt 0.7.0`); `sh e2e/lanes/lane.sh reload <id> --jar` then upgrades the same world and config to
the checkout's build.

### Session cleanup

After every end-to-end session (the M3 twin added the last two):
- Restore the game side: `server.properties`, `config/mineqtt.json`, `usercache.json` and the world of the dev server
  (`fabric/run` or `neoforge/run`), and end the server's JVM by its PID (`python tools/game.py pid`) if it lingers.
- Clear the session's retained topics: the bridge's `mineqtt/<bridgeId>/#` and its Home Assistant discovery
  (`/mineqtt discovery clear` before stopping), plus every topic the checklist items above name.
- Clear the twin's retained topics: `python tools/game.py clear 'ha_statestream/#'` (the catalog under
  `ha_statestream/_mineqtt/` included) and `python tools/game.py clear 'mineqtt/+/bridge/ha_request'
  'mineqtt/+/bridge/ha_response' 'mineqtt/+/bridge/ha_sync'`. Home Assistant republishes statestream topics on the
  next change of an included entity, so do this with both Home Assistants stopped, or accept the fixture's own
  topics coming back.
- Remove what the session added to Home Assistant: `ha.py automation delete <id>` for each blueprint automation and
  `ha.py label remove <label> <targets…>` for each label it set; Minecraft devices go with the discovery clear.

### Client game tests

What needs a game client is automated as Fabric client game tests (`fabric-client-gametest-api-v1`). They live in the
`gametest` source set of `fabric/`, a separate test mod (`mineqtt-gametest`) that only this run loads:

```sh
MINEQTT_E2E_BROKER=tcp://localhost:1883 ./gradlew :fabric:runClientGameTest   # from the repository root
```

- A game window opens on the desktop and closes by itself after about a minute; the test API feeds it its own input.
  To run them without a window, next to other runs, use `docker/client-test/run.sh` (see
  [Test lanes (Docker)](#test-lanes-docker)).
- Each suite starts its own dedicated server in the client's JVM (`worldBuilder().createServer()`, a fresh flat world)
  and joins it as `Player0`, who is not an operator.
- `MINEQTT_E2E_BROKER` (a `tcp://` or `ws://` URI) points that server's MineQTT at a broker, under the bridge id
  `gametest` (or `MINEQTT_GAMETEST_BRIDGE`, below); a second plain HiveMQ client, with the same username and password,
  publishes the probes. After each suite it clears the retained topics under `mineqtt/<bridge id>/#`, the Home Assistant discovery of the suite's world (the
  bridge's `homeassistant/device/mineqtt_bridge_<worldUuid>/config` and one per device and group, because every fresh
  world has a new UUID) and the probes. Without the variable MQTT stays disabled, and the parts that need a broker are
  reported as `SKIP`, never as passed.
- Results: every check is a `PASS`/`FAIL`/`SKIP` line in `fabric/build/run/clientGameTest/mineqtt-gametest-report.txt`
  (the task prints the skips), and the screenshots are in `fabric/build/run/clientGameTest/screenshots/`. The run
  directory is deleted at the start of every run.
- `check` compiles the tests but never runs them: they need a desktop and take a minute.

#### Several worktrees at once

```sh
MINEQTT_LANE=lane1 MINEQTT_E2E_BROKER=tcp://localhost:1883 sh e2e/tools/run-gametests.sh wires,static_publisher
```

- `MINEQTT_GAMETEST_BRIDGE` replaces the bridge id `gametest` (1-64 of `a-z 0-9 _ -`). The suites fold it into every
  topic they publish to or clear outside the bridge as well (`advanced_<id>`, `static_<id>/doorbell`, the
  placement probes, ...), so runs with different ids on one broker never read or clear each other's topics. Only
  Raw Link topics that come from an item (`mineqtt/shared/minecraft/stone` and `.../dirt` in `RawLinkNoticesGameTest`)
  are the same for every run.
- `MINEQTT_GAMETEST_SUITES` (comma-separated suite names as the report writes them: `screens`, `device-config`,
  `static_publisher`, ...) runs only those suites; the others return at once. The task then needs one `SUITE PASS` per
  selected suite, names a selected suite that did not pass (also a misspelt one) and still fails on any `FAIL`.
- `e2e/tools/run-gametests.sh [suites]` runs one client at a time across every worktree of the machine: it compiles the
  tests, waits up to 45 minutes (`MINEQTT_GAMETEST_LOCK_WAIT` seconds) for the lock directory
  `~/.mineqtt-gametest.lock` (`%USERPROFILE%` on Windows; it holds owner, pid and start time), breaks a lock older than
  60 minutes, exports `MINEQTT_GAMETEST_BRIDGE=${MINEQTT_LANE:-gametest}`, runs `:fabric:runClientGameTest`, prints
  the report's `SUITE` and `FAIL` lines and frees the lock on every exit (an INT, TERM or HUP stops Gradle first). It exits with Gradle's status,
  3 when the lock was not acquired in time.

Suites:

| Suite | Covers |
|---|---|
| `ScreensGameTest` | Every block with a screen is placed by the player, opened with the use key (sneak + use for the Smart Lamp, whose right-click switches it, M2-13), and every tab is clicked and photographed; a device's screen has no inventory, the short panel and the device's name as its title, every other screen keeps the player's inventory (P1); then the Configurator's (Cyberdeck's) explorer; then the Cloth config screen (the one ModMenu opens): every category is shown and photographed at its top and end, nothing counts as edited, and Escape leaves it without saving. |
| `PlacementSecurityGameTest` | Item 12: the non-op player cannot run `/mineqtt status` (since M4-0 the root is open to everyone and the command fails at its argument, `command.unknown.argument`); another player's private lamp with the grandfathered raw topic `zigbee2mqtt/x/set`, copied with Ctrl + pick block and placed, becomes a new device of the placer whose Raw Link is resolved for them into `mineqtt/shared/zigbee2mqtt/x/set`; the placer is told in chat and in the action bar, and the screen shows the shared link. The source's legacy stone (M1 slots) is not the copy's, and the copy is off in the source's colour (M2-12, plan D14). With a broker: the Cyberdeck explorer's Traffic tab (found by its id) receives the shared probe and the player's own device but no `homeassistant/#` message (retained or live) and none of the other player's private topics. |
| `RawLinkNoticesGameTest` | M2 F2: the player types stone's channel into the Advanced tab of a Redstone Input (real keys) and is told in chat that it uses `mineqtt/shared/minecraft/stone`, while the status line follows; Fabric's item storage finds nothing on a device (M2-12). A config reload without `mineqtt/shared/#` in `acl.rawAllow` refuses the link at once: one chat line to the editor, the owner's action bar, and the refusal in red on the open screen; restoring the allowlist opens the link again, without a restart. |
| `OwnerUuidGameTest` | M2 F11, M4-3: the joining player plays an alt that carries the name a renamed main account placed blocks under. A 0.7.0 Interval Timer saved with the main account's `ownerUuid` and the alt's name is adopted as a timer device owned by the main account, not the alt (without a claim mod a device of the default access stays open to everyone, so its screen still opens), and the main account's private lamp neither opens nor counts the alt as its owner. A v1 timer that only names the player is bound to the player at its attach, and a v1 lamp's owner name resolves to the player, whose screen then configures it; a v1 owner name that differs only in case binds neither. |
| `DeviceConfigGameTest` | M2-6: the non-op player places a Redstone Output, opens its screen and sends the device payloads the generated screens will send. The `DeviceView` comes with the screen's open data (M2-7), with the player's grants (owner: use, configure, access, not expose); `mode`, the name, the access mode, a Raw Link channel (its shared form) and an allowlisted typed topic (no channel) apply and the view follows; an unknown enum name, exposing without `mineqtt:ha.expose`, a typed topic outside the allowlist, retain on an input's link and an undeclared action are refused with a "Not changed:" chat line; the `state` action switches the output on and `TOGGLE` off. With the screen closed, both payloads are dropped silently. On another owner's claim lamp the player may use and configure but not change the access mode. |
| `RenderTagGameTest` | M2-7: the player places a Smart Lamp, a Redstone Output, a Redstone Input and a Presence Sensor. The server gives the lamp and the output a Raw Link channel, the output an ON payload and a level, and the input a redstone block. Every device's update tag is the `mineqtt_render` compound alone (the lamp's `{rgb, brightness, on}`, the others empty), and the client's block entities have no owner, device id, Raw Link or legacy items. The lamp re-tints from red to blue on the client at the same brightness (its light level, the only block state, stays 8). Each device's screen opens with its view in the open data at once and shows its values (lamp on, blue, 128; output power 7 and its `OPEN` payload; input powered; sensor range 3); the lamp's view has its resolved shared topic, which a private channel moves into the owner's subtree and back. |
| `DeviceScreenGameTest` | M2-8: the player places a Redstone Output; its screen is the generated device screen and opens on its General tab, with the bridge's topic root, no group and "not in Home Assistant" (OPT_IN) from the view, and a read-only Expose button. Typing a name into the field (real clicks and keys) previews the slug and sends nothing; Enter renames the device (the screen's title follows at once), and Copy puts its state topic on the clipboard (the player's clipboard is restored). As an operator the player steps Expose: on (the retained discovery config appears with the device's name, and a rename through the field changes that name), off (the config is cleared) and default. With a permission manager that trusts the player to use but not to configure (a claim mod's stand-in), another owner's lamp shows General read-only: no Save button, the field takes no typing, Expose inactive, and a rename payload sent anyway changes nothing. |
| `ControlsGameTest` | M2-9: the player switches a placed Redstone Output on from its Controls tab, drags its level slider with the real mouse (the server's level follows while the slider moves), flings it between 0 and 15 forty times within one tick as a fast mouse does (the tab sends within the C2S guard's rate, so the last level, 3, still arrives, and a click right after still counts) and steps its mode. On a Smart Lamp the light picker's saturation and hue make it blue, its white end sets 6500 K and its brightness half, each reaching the lamp and its retained Home Assistant state. A Presence Sensor's range slider sets 3 blocks, which the device's last state (`devices info`) shows, and its *Mobs* field takes `zombie pig` with Enter (kept as `zombie, pig`) and refuses an unknown mob (M2-12). With a permission manager that trusts the player to use but not to configure, another owner's output takes the switch and the level slider, shows mode and power-on behaviour read-only, and drops a mode payload sent anyway. |
| `AdvancedGameTest` | M2-10: the player gives a placed Redstone Input a channel on its Advanced tab (typed with real clicks and keys, sent with Enter) and sees the topic it resolves to; switches retain off (the broker's stored level is cleared) and on again, drags QoS to 1, and sets the on payload with Enter and the off payload with Save. In Private mode the same channel moves into the owner's subtree, reopens in Private mode and goes back with Channel mode (M2-12). A typed topic outside the allowlist is refused: the tab shows the reason, the chat says it too, and the link and the typed text stay; a command that changes the link ends the shown reason; an allowed typed topic is then used as typed, keeps the link's settings and reopens in Topic mode. A renamed paper used on a Redstone Output and on the input sets both channels without opening a screen or being used up, the action bar answers (and says "unchanged" the second time), and the output follows the input. With a permission manager that trusts the player to use but not to configure, another owner's output refuses the paper in the action bar and shows the tab read-only. |
| `InteractionGameTest` | M2-13: a right-click switches a Smart Lamp on and off (also with stone in hand, placing nothing) and opens no screen; sneak + right-click opens it. M2-18: a right-click switches a Smart Plug to full power and off again, the redstone lamp beside it follows, no screen opens; sneak + right-click opens its screen (Controls: Switch, Level, power-on behaviour; screenshots `interaction_plug_on`, `_off`, `_controls`). Sneaking with stone places it against a Redstone Output; a plain right-click opens the output's screen. A name tag named "Kitchen Lamp" renames the lamp (action bar "Renamed to Kitchen Lamp (mineqtt/gametest/kitchen_lamp)", slug `kitchen_lamp`), is not used up, and a second use says "Already called Kitchen Lamp". The Configurator: sneak + right-click into the air answers "Configurator mode: Link", then Group, Copy-Paste and Bind (M3-13), then "Configurator mode: Configure"; it opens the player's ceiling lamp about 10 blocks up, whose Controls switch turns it on; the screen closes when the Configurator leaves the hand and when the player teleports beyond 16 blocks; a Bridge Console 9 blocks away is refused ("opens only from close by"). Pointing at nothing it opens the explorer (on its Devices tab since M4-16), whose Traffic tab, found by its id, keeps its Listen state across a reopen with no `listening` component on the item. A Configurator carrying the deprecated `listening`, `private_mode` and `inventory` components loses them within a tick and its 3 papers are back in the inventory. Screenshots `interaction_*`. |
| `WiresGameTest` | M2-15: the console wires a Redstone Input to a Redstone Output and a Smart Lamp with `/mineqtt wires add` (no mapping: follow); an incompatible pair (a `pulse` event into `power`) and a self-wire are refused. The player flips a lever on the input by hand: the output goes to 15 and the lamp lights, and both go off when the lever goes back, four deliveries and nothing dropped on the server's message bus. Two outputs wired into each other, one way inverted, make a loop without a delay: the inverted wire sets its target to the opposite of its source as it is made (M2-16), which starts the loop, and it is cut once, after 16 deliveries, and nothing more moves. Breaking the input removes its two wires. M3-9 (`attachRoot`): an output in a far force-loaded chunk wired to a near output; the chunk unloads, Home Assistant's `/set` to the far output queues, and when the chunk loads again the queued command lands, the near output follows, and every message emitted since is `STARTUP`, none `WORLD` (the step gives the chunk source spare tick time, `ServerChunkCache.tick(() -> true, false)`, because a game test server never has any for chunk unloads). M4-10 (`placerRoot`): a broker message to a Block Placer's Raw Link places a redstone lamp, and what the placer then emits on its `last_result`, wired to a Display Panel, is `RAW`, not `WORLD`. Screenshot `wires_lever_on`. |
| `GroupGameTest` | M2-17: the non-op player steps the Configurator to Group mode, clicks a Smart Lamp that is on (exposed, so discovered), then a second lamp: the prompt asks for the group's name ("Living Room", typed into the focused field), *Create* makes it with both lamps (action bar "Created group Living Room with Kitchen Lamp and Hall Lamp") and the second takes the first's state; a third click adds a third lamp. With a broker the group's discovery is retained and the first lamp's is cleared (Home Assistant shows one light). Clicking the first lamp again or into the air stops; another owner's private lamp and an output are refused. The first lamp's General tab shows the group as a button that opens the group page (three devices, Members inactive for a non-op); *Rename* to "Lounge" moves the slug and the retained state topic and renames the discovery in place; opped, *Members* shows the members (the exposed lamp is discovered again) and hides them. The third lamp leaves, joins again from the join page, creates group Porch and joins Lounge, which removes the emptied Porch. With a permission manager that trusts the player to use only, the page is read-only and a Leave sent anyway is dropped. In Copy-Paste mode an output's channel, payload, mode, power-on behaviour, exposure and access mode are copied and pasted onto another output: four changed, the exposure and access mode refused and named in chat; opped, the paste sets those two; a lamp is refused as another type. Screenshots `group_*`. |
| `ClimateGameTest` | M2-19: the player places a Climate Sensor on the ground (facing up); it has read its spot at once (temperature, humidity, illuminance, a `minecraft:` biome) with interval 10 s and the Raw Link on the temperature. At `interval 1` (console), glowstone beside it raises the illuminance at the next reading; its screen's Controls tab shows *Interval* and *Raw Link sends*. A v1 humidity sensor set facing east on a wall with its v1 data (paper topic, owner, `CustomName`, 40 ticks) becomes an attached Climate Sensor facing east on the next tick, with its grandfathered topic, anvil name, owner and slot paper, publishing the humidity every 2 s; with a broker, its next reading arrives on its v1 topic live and not retained, equal to its humidity (60.0, not the temperature). A humidity sensor item placed from the server thread before any inventory tick (no inventory), with a forged owner in its block entity data and a forged `zigbee2mqtt/...` paper in its container component, becomes a Climate Sensor at once that the player owns, without legacy items, its topic not grandfathered but moved to the shared namespace (design §8.6). Two humidity sensor items given to the player turn into two Climate Sensors that keep their name, and a crafter with a humidity sensor in its grid makes a Climate Sensor through the 1:1 recipe. Screenshots `climate_placed`, `climate_controls`, `climate_converted`. |
| `StaticPublisherGameTest` | M2-20: a v1 Static Publisher set powered with its v1 data (paper topic, message `DING`, owner, `CustomName`, `wasPowered`, `lastPublishStatus`) becomes an attached Redstone Input on the next tick; its first signal read finds no signal and switches it off. Its Raw Link is the grandfathered v1 topic with `on` `DING`, `edges` `RISING` and `retain` false, and it keeps its anvil name, owner and slot paper; its Advanced tab's view shows the rising-edge link, not retained. With a broker, a live subscriber sees nothing for the conversion and its falling edge, then exactly one `DING` per pulse of a redstone block (two pulses, nothing on the falling edges), and a fresh subscriber finds nothing retained. Two Static Publisher items given to the player turn into two Redstone Inputs that keep their name, and a crafter makes a Redstone Input from one through the 1:1 recipe. Screenshots `static_publisher_converted`, `static_publisher_advanced`. |
| `WallSwitchGameTest` | M2-22: the non-op player places a Wall Switch against a stone wall: it faces away from the wall, both buttons up, the player's. With a broker and a Raw Link channel: a click on the left half shows the left button pressed and publishes `{"event_type":"press","button":"left"}` on `…/action` and `press_left` on the Raw Link, the right half `press`/`right`; two clicks 3 ticks apart are one `double_press`; the use key held for 30 ticks keeps the button shown pressed and is exactly one `hold`, with nothing on letting go; nothing is retained on the Raw Link topic. Its screen has the five device tabs; the Controls tab's *Press right button* publishes a press at once; the Advanced tab shows the link, not retained, and has no retain switch. A wire from `press` to a Smart Lamp (`/mineqtt wires add`, toggling) switches the lamp on and off a click at a time. A v1 Light Remote set next to a v1 lamp of the player on the same topic becomes a Wall Switch wired to that lamp (D15), without a Raw Link, not named after the topic, its paper a legacy item; pressing it toggles the lamp off; a v1 remote of another owner on the topic is wired to nothing. Screenshots `wall_switch_placed`, `_held` (the left rocker pressed, its indicator lit), `_general`, `_controls`, `_advanced`, `_migrated` (the item in hand). |
| `ConsoleGameTest` | M2-23: the non-op player places a Bridge Console; it faces them. A lamp of theirs is in a group, and a private output of nobody (set by the console) in another. Opened by the player, its screen is the short panel titled Bridge Console with the Bridge, Devices and Groups tabs: the bridge's topics and its connection; since M4-17 the World row (a HH:MM time, TPS above 0), the Players row (1 online, no name) and the exact telemetry line `Telemetry: every 10 s · players: presence by consent (0 sharing) · broadcast off`; "Read only" in the title row, no action button; the Devices tab lists their lamp alone, the Groups tab the lamp's group alone. A republish payload the player sends anyway gets no answer. Opped and reopened: every device and group, no note, the Reconnect, Republish and Prune buttons; an output set while the screen is open appears within the refresh; `weather rain` shows within the refresh and names stay 0 (nobody shares); `/mineqtt status` prints the same telemetry line; with a broker, a birth published on `homeassistant/status` shows as Home Assistant online; the Republish button answers in chat as `/mineqtt discovery republish` does. Screenshots `console_placed`, `console_bridge_read_only`, `console_devices_own`, `console_groups_own`, `console_bridge_operator`, `console_devices_operator`. |
| `LampEffectsGameTest` | M2-25: the player places a Smart Lamp. `{"state":"ON","brightness":200,"transition":2}` (console `devices set`) raises its block light from dark to 12 step by step over the 40 ticks, never falling, on the server and on the client, and the client's tint with it; the lamp's state is the target at once and, with a broker, exactly one state is published, the target with light level 12. `{"state":"OFF","transition":1}` fades it back down to dark. A 60 s fade's own target without a transition ends it at once, and with `"transition":1` shortens a 60 s fade down to about 20 ticks. A candle (`"effect":"candle"`) flickers the client's tint between half and full while the block light stays at 15, and the update tag carries `effect: candle`; a pulse comes back to the full tint, dims to about a third and moves through the shades between; `off` brings the steady full tint back and the state says `"effect":"off"`. Six chunks away at simulation distance 2 the lamp's chunk is loaded and its device attached but not ticking, and a transition shows its target (light level 12, white tint) at once on the server and the client. Screenshots `lamp_effects_fade_half`, `_fade_end`, `_candle`, `_candle_later`, `_pulse_bright`, `_pulse_dim`. |
| `DeviceLimitGameTest` | M2-26: every MineQTT block whose block entity is a device has a `DeviceBlockItem`, and no other block has one. With `limits.maxDevicesPerPlayer` at 1 the non-op survival player places a Smart Lamp by hand; the second lamp is not placed on the server, the client's predicted lamp is gone again, both sides keep two lamps in the hand, and the action bar says "Not placed: this server allows one device per player, and you own 1". A Bridge Console (no device) is placed at the limit; a Static Publisher, which would convert into a Redstone Input, is refused and kept with the same line. A lamp set by the console has no owner; the player at the limit sends its screen's claim payload and is refused ("Not changed: …: this server allows one device per player, and you own 1"), the lamp stays ownerless. As an operator the player places another lamp (owning two) and claims the ownerless one (owning three); with all three broken they own none, and of two placements in one server task only the first is placed (the placement waiting for its attach counts). Screenshot `device_limit_refused`. |
| `AdventureGameTest` | P2: the non-op survival player places a Smart Lamp, a Redstone Input, a Smart Plug, a Bridge Console and a Wall Switch; the console sets an Alarm Clock and a Redstone Output 8 blocks away. The lamp's screen, open with its five tabs, and the Alarm Clock's (a timer device since M4-3) are rebuilt with the Controls tab alone when `/gamemode adventure` runs and get their tabs back in survival; the Redstone Input's open screen closes in adventure mode, with "Redstone Input has no controls; devices can only be used in this game mode" in the action bar. A right-click on the Alarm Clock in adventure mode opens its Controls tab alone with its status line; *Trigger now* powers it, and a time sent anyway changes nothing. In adventure mode a right-click still switches the lamp and the plug (no screen) and presses the Wall Switch's left button. Sneak + right-click opens the lamp's Controls tab alone, whose view is controls-only with USE alone and no slug, owner, topic root, Raw Link, status or traffic; no links, group page or status line arrive; its switch works; a rename and an access change sent anyway change nothing, without a chat line. A right-click on the Redstone Input opens nothing and says why; a name tag on the lamp, a renamed paper on the input, the Configurator in Link mode within reach and in Group and Configure mode from afar answer "Devices can be used but not configured in this game mode" and change nothing. As an operator in adventure mode the lamp still shows its Controls tab alone with USE alone, and the Bridge Console opens read-only ("Read only", no action buttons, no devices or groups listed); a republish sent anyway is dropped. Screenshots `adventure_*`. |
| `LampMountGameTest` | P3: the Smart Lamp block states a 0.6.0 world stored (palette entries read from its region file: `facing` north, east or up, `light_level` 0 or 15, no `face`) decode as floor lamps with their facing and light level. The non-op player places lamps by hand on the top of the ground (a floor lamp), on the four sides of a floating stone block (wall lamps whose `facing` is the clicked side) and under a stone block (a ceiling lamp); each has the same state on the client, is the player's, and has an outline (and the same collision box) that touches its support and reaches 14 pixels out, with the particles' bulb centre inside it. The top of a bottom slab holds no lamp (nothing placed, still in hand). A click on the side of short grass on the ground, with nothing behind it, replaces the grass with a floor lamp. Switched on by `devices set`, every lamp lights its block at 15 on both sides. A block set and removed beside a wall lamp changes nothing; when the stone block, the ceiling block and the ground go away, the six lamps pop off on both sides, leave the registry and drop as six items. Lamps set by command as 0.6.0 placed them: one standing on another stays a device while the lower lamp switches on and off (its `light_level` updates the lamp above) and a side neighbour comes and goes, and pops off and drops when the lower lamp goes away; a floor lamp floating one block up stays while a side neighbour comes and goes and while a stone below holds it, and pops off and drops when that stone goes. Screenshots `lamp_mount_*` (both pairs of wall lamps, the ceiling lamp, the floor lamp, the lamp in hand). |
| `DecommissionedGameTest` | M2-21: a v1 Topic Relay (source and target topics, cached message, owner, `CustomName`, papers in slots 0 and 2) and a v1 Dynamic Publisher saved powered load as inert blocks whose saved data is only `legacyItems` (the papers in their slots); the Dynamic Publisher keeps its powered state. With a broker, a message on the relay's source topic and two redstone pulses next to both publish nothing on any target topic, and the relay's powered state stays. A right-click opens no screen and says in chat that the relay is retired, what replaced it and that breaking it gives a Network Chip; the item's tooltip has the retired line and the replacement line. Broken by a survival player with a stone pickaxe, the relay drops one Network Chip and its two papers, never itself. A Topic Relay item whose block entity data names five diamonds is placed with none of them and broken gives the chip alone. Screenshots `decommissioned_placed`, `decommissioned_use` (chat), `decommissioned_tooltip` (the hovered item in the inventory). |
| `LinkGameTest` | M2-16: the non-op player steps the Configurator to Link mode and clicks a Redstone Input whose lever is on, then a Smart Lamp: the port picker offers `powered` into `state` first and selected; *Link* wires them (action bar "Linked Garage Input.powered → Porch Lamp.state"), the lamp switches on at once (the input is on) and follows the lever. The same two clicks offer *Unlink*, which removes the wire. The mapping button inverts a wire into a Redstone Output (Enter links it, the output does the opposite of the lever); a member's group is offered after the member, and the input linked into the group switches both group lamps. Clicking the input twice, or into the air, stops linking; another owner's private output is refused. At a cap of two wires a third from Link mode is refused, so is the opped player's `/mineqtt wires add`, while the console's wire counts for nobody. The input's Advanced tab *Links* page lists its five links (port, other device and port, group), scrolls, and × removes one; a lamp of the group lists the wire into its group; with a permission manager that trusts the player to use only, the × buttons are inactive and a removal sent anyway is dropped. Breaking the input removes its wires. Screenshots `link_*`. |
| `AccessDiagnosticsGameTest` | M2-11: the owner of a placed Redstone Output steps its access mode on the Access tab from claim to public and private. On another owner's output, which the player may configure (no claim mod), the mode button is read-only, a mode change sent anyway is refused in chat and on the tab, and `/mineqtt devices access` sets it private, which closes the player's screen, and back to claim. An output set by command has no owner; *Make mine* makes the player its owner, who may then change the mode. Diagnostics shows the output's UUID (Copy puts it on the clipboard), online, the state it published, the Home Assistant command `{"power":7}` with payload and size (applied), a refused command counted as dropped, and refuses a retry of a device that is not faulted on the tab and in chat. With a permission manager that trusts the player to use but not to configure, another owner's output shows its last message without the payload, and the guard drops a retry. An output with M1 data (slots beside its compound) offers *Eject 7*, which puts its papers and stone into the player's inventory; another breaks and drops them, a third drops them with `devices eject`; a player who may only use one sees Eject inactive, and the guard drops an eject sent anyway (M2-12). Screenshots `access_*`, `diagnostics_*`. |
| `DisplayPanelGameTest` | M3-16 (`display-panel`): the non-op player places a Display Panel by hand against a stone wall: it faces away from the wall and is theirs. The client's finished lines: `Washer done!` fits two large lines, `21.4 °C` one, four line breaks give four small lines, medium shows three and cuts the rest with `…`; the renderer's extract draws the four lines facing south. Red without glow is drawn red in the block's light, a new panel glows cyan at full brightness. A comparator beside a panel on the ground lights a redstone lamp for text, `"7"` gives 7, `off` puts it out. A point in time travels as epoch millis without lines, and the client words it "in 2 h 14 min", five minutes later "in 2 h 9 min", from the same render data. Home Assistant's `/set` changes the client's lines. With a broker, the twin opened for `sensor.dp_<bridge>_*` and the player made operator: bound, the panel waits ("Waiting for HA…" under its name "Outdoor"), the retained value shows `12.5 °C`, a live change `14.5 °C`; the client block entity holds neither the binding nor the entity id; unavailable gives the grey "Unavailable" and comparator 0; then unbound, the config restored and the player deopped. Screenshots `panel_*`. |
| `MirrorGameTest` | M3-13 (`mirror`, needs a broker): a run's own statestream base (`ha_statestream_<bridge>`, `TwinFixture`), a probe as Home Assistant and the control blueprint, the player made operator. A lamp, an Output and a Plug the player placed are bound with `devices bind`. The lamp follows a light (on 128 red in one step, `retained`, then a live off keeps the colour, `live`); its Twin tab names the source and the entity, and its Control toggle turns control on; a right-click sends exactly one `light.turn_off` naming the player, the client shows the request pending and nothing changes until the probe's `accepted` and live off; a `not_labelled` answer reaches the action bar and changes nothing. The Output on an `input_number` gives 6 for 42.5 of 0..100, 4 with min 40 and max 50 typed into its Twin tab, 11 for a live 47.5. The exposed Plug is discovered in full, follows a switch, is reduced to the Binding entity, joins no group, its Advanced tab says its Raw Link is paused, and unbinding republishes its full discovery. M3-17's UI, the preset step: with the Home Assistant twin off the probe plays a retained zigbee2mqtt bulb; a fourth lamp's Twin tab steps This block → Home Assistant (the picker, Escape) → MQTT device, lists the server's lamp presets, steps to "zigbee2mqtt light" and binds the name typed into *Device name* with Enter; the lamp follows and the tab shows the MQTT device, its preset, topic and health (no "Twin off"); control is refused naming `acl.rawAllow`, then taken with `zigbee2mqtt/+/set` allowlisted; a click publishes `{"state":"TOGGLE"}` (retained only), the next after the bulb's live OFF `{"state":"ON"}`; without the allowlist entry a click is refused and nothing is published; Bind mode's picker warns "Binding replaces its MQTT device …"; the source cycle unbinds. Screenshots `mirror_*` (`mirror_twin_tab_preset`). |
| `BindGameTest` | M3-13, M3-14 (`bind`, needs a broker): a simulated catalog (labelled lights, a switch, a door contact, a flag, scenes and a script in several areas, some not for control). As a non-op the Configurator's Bind mode opens a lamp's Twin tab and picker, which answers without rows ("needs mineqtt:twin.bind"); as an operator typing `m3` lists the lamp's domains ranked and grouped by area with control badges and a colour swatch; Enter binds, the lamp is renamed after the entity and follows; the health line reads Live, then Unavailable; the source cycle unbinds. The player's command twins (`twin entities`, `devices setting … twin.entity`, `twin.resume press`). Copy-Paste binds a second lamp. With `twin.allowedEntities` matching nothing D4's help, empty the twin-off notice and a refused bind. A USE-only player sees a foreign lamp's Twin tab read-only and a search gets no answer. A Redstone Input's picker lists only controllable entities; its target row; its edges reach the probe as `turn_on`/`turn_off` naming no player. A Wall Switch's picker asks Left or Right; both bound separately; each press reaches the probe as its own request. The rate step: with the Wall Switch's Twin tab open, 5 console presses of the left button within a second; the open tab counts the refused one(s) and names `scene.m3_movie` for the owner. Screenshots `bind_picker*`, `twin_tab_*` (`twin_tab_rate`). |
| `RecipesGameTest` | M2 F13: every `Catalog` entry is registered and every registered MineQTT block and item is in the Catalog; the creative tab shows exactly the Catalog's creative entries in its order. Every file under `data/mineqtt/recipe/` loads as a recipe with its unlock advancement, the legacy `recipes/` folder ships nothing, and each crafting recipe, fed its own ingredients, is the one the grid picks (no clash with vanilla or another MineQTT recipe). In survival an ender pearl unlocks only the Network Chip recipe; the player crafts four chips at a crafting table through the recipe book (the server refuses unknown recipes), which unlocks the Redstone Input, and crafts one from a chip. No MineQTT recipe makes or uses an unreleased Catalog entry (M3-15). |
| `TimersGameTest` | M4-2 to M4-5 (`timers`): each of the four timers opens the generated device screen with its five tabs, its Controls tab showing *Trigger now*, the *Enabled* toggle (the switch's state row folded into it), its schedule and a status line. With `gamerule advance_time false` or `/time pause` the Daylight Timer and the Alarm Clock lose their next fire within a few ticks and get it back when the clock runs; `/time rate 0.5` keeps it, twice as far away. The Enabled toggle disarms the Interval Timer (no fire, no next fire, the status line says so); *Trigger now* pulses the Alarm Clock for 30 ticks, which a comparator behind it reads, and fires as `manual`; the Cron Timer refuses an expression that does not parse, with the reason, and takes one that does. Its *Schedule* tab shows the expression's seven fields in boxes, a preset fills them, a box that does not parse closes *Set*, and *Set* sends the expression, which the timer takes. In Link mode the picker offers a timer's `fired` into a lamp's `state` (toggle) first and a Wall Switch's `press` into a timer's `trigger`; linked, the switch fires the Alarm Clock and the Daylight Timer's fire toggles the lamp through the bus. A v1 alarm clock placed with its v1 data becomes a device with its time, day lock, owner and anvil name and no Raw Link, and the items of its old slots eject from its Diagnostics tab. |
| `WorldDevicesGameTest` | M4-9, M4-10 (`world_devices`, the Raw Link parts need a broker): a Block Observer facing a chest reports it, and its comparator level follows the chest's fill. Its Raw Link's `full` payload carries no block entity data until `blockObserver.exportBlockEntityData` is on (no claim mod: the same claim), then the chest's items, and leaves them out again when the flag goes; a redstone wire flickering in front for two seconds gives at most two `changed` events a second, the rest throttled; a v1 Block Sensor set with its v1 data becomes a Block Observer whose v1 topic is its unretained, grandfathered Raw Link with the data mode as its payload. A Block Placer with `blockPlacer.enabled` places white wool from the chest behind it and clears it back; TNT and block entity data are refused with their reason in `last_result` (counted as dropped), a retained message waiting on its topic builds nothing, ten messages at once act four times, and switched off it answers disabled; a v1 Block Emitter becomes a Block Placer listening on its v1 topic. |
| `TelemetryGameTest` | M4-7, M4-8 (`telemetry`, needs a broker): the retained bridge telemetry counts the joined player, names nobody (the player of a dedicated server has not consented) and carries tps, mspt, the time, the day, the weather and the drop counters; `/time set 13000` turns `is_day` off and reads `19:00`, `/time set 1000` turns it on again and reads `07:00`, each within seconds. On `bridge/event`, an announced advancement (`minecraft:story/mine_stone`) and a `/kill` (with `immediate_respawn`) of the player publish nothing; the player leaves and joins again (`GameTestSession.reconnect`), which publishes exactly `{"event_type":"leave"}` and `{"event_type":"join"}`, and the telemetry counts them again, unnamed. M4-11: after `/mineqtt privacy share on` the telemetry names the player, and a death (cause `death.attack.*`) and an advancement are published with their name; `share off` makes them anonymous again. |
| `PrivacyGameTest` | M4-11 to M4-13 (`privacy`, needs a broker): the non-op player of a dedicated server sees the privacy prompt once at the join, with *[Share presence]* and *[Don't share]* (the server shares presence, not full data), and not again after a rejoin; `/mineqtt privacy` says nothing names them. The command *[Share presence]* carries makes their presence device: a broker probe finds its retained discovery "Player0" under the bridge and `{"presence":"ON"}` on `player/<slug>`, and `/mineqtt privacy` lists it. The Traffic tab shows the player's own `player/` topic and hides another player's. On a server switched to `telemetry.players=full`, `share full` adds health, dimension and position, walking 6 blocks publishes a new position, and `share on` drops them again (the config declares them no more). Leaving publishes OFF, joining ON again; `share off` clears discovery and state. |
| `RoomsGameTest` | M4-15 (`rooms`): the non-op player steps the Configurator to Room mode and clicks two opposite corners: the first is named in the action bar and the box is outlined with particles for them, the second opens the name prompt with the box's size (6 × 4 × 6), the names of other rooms to reuse ("Great Hall") and no *Everyone's devices* choice. *Create* makes the room for their own devices, and the lamp inside shows it on its General tab; the same answer sent again is dropped. A sneak-click inside the room picks it: the prompt renames it, a second pick removes it ("no room here" after). A click into the air cancels a corner; a box larger than `rooms.maxSize` (8; the far corner through the Configurator's range) and a click in adventure mode are refused in the action bar; the command's `for-all` is refused to the non-op player, and a room made by command works; in adventure mode the player can neither rename nor remove it by command. Ordinary clicks (M4 fix): in Room mode a click on a chest opens it and sets no corner, a ground block is corner A, and while A waits a lever flips and the chest opens without becoming a corner; a click into the air then cancels A. Area names (M4-23, `TwinFixture` with a catalog of the areas *Living Room* and *Hallway*): the operator's prompt suggests Great Hall, Hallway and Living Room, the chip steps to Hallway, and a room typed `living room` is made as typed while its lamp names the area *Living Room*. |
| `ExplorerGameTest` | M4-16 (`explorer`): pointing at nothing, the Configurator opens on Devices, before Settings, Traffic (the topic explorer, its id unchanged) and Publish. The non-op player finds their own lamp and output, each with its model, its availability (online or no broker), its values, how far away it is and in which direction (east), but neither another player's public lamp nor a device of nobody. A search (`LAMP`) narrows the list and one that matches nothing says so. In adventure mode the tab lists nothing (scope none). As an operator the player finds every device, 59 of them on two pages of 50, foreign and ownerless ones included, and the page buttons step between the pages (every step waits for the page the tab shows, not only the one that arrived, so a click never hits a page button that is not drawn yet). M4-18: the You row (Presence, then Off, with the command's reply in chat and the made choice pressed); the room filter (Kitchen and back); from 38 blocks Use toggles the lamp, Locate marks it with particles and says "38 m W", Open is refused with the distance; actions forged on another player's lamp are refused, mark nothing and use nothing; from close by Open opens the lamp's screen; Use on a lamp in an unloaded chunk queues and applies when it loads. M4-19: in the for-all room Hall over the player's output and another player's lamp, *All on* switches the output ("Hall: 1 switched on") and leaves the foreign lamp; a second switch sent right after it is refused ("One room switch a second"), *All off* a second later works. Then `/mineqtt rooms switch Kitchen on|off` as the player (the opposite of the lamp's current state) switches their Kitchen lamp and says "Kitchen: 1 switched on|off". |

`GameTestSession` holds the helpers new suites build on: placing as the player, opening a block (`openBlock` picks the use key or sneak + use by the mod's own `Interaction.decide`, and aims again once the player sneaks, as the lower eyes can miss a small block) or the held item, `sneakUse`, `reconnect` (the player leaves and joins again), `aimFar` (a block beyond reach, for the Configurator),
clicking tabs, slots, buttons and other widgets with the mouse, commands as the console or the player, the received chat and action-bar
lines (`ChatLog`), soft checks, and screenshots. Add a suite as a new `FabricClientGameTest` class listed in
`fabric/src/gametest/resources/fabric.mod.json`; the task expects one `SUITE PASS` line per listed class.

The exit quirk: after a passing run the dev client waits 15 s and then writes a "Client shutdown from post-main" watchdog
crash report and exits with -8, because two idle fixed pools of non-daemon threads keep the JVM alive. They exist before
any server starts and match the `newFixedThreadPool` calls in the Architectury Transformer's dev runtime
(`TinyRemapper`, `ReadClasspathProviderImpl`); MineQTT itself creates no executors. A failing test exits with 1 before
that point. `:fabric:runClientGameTest` therefore accepts 0 and -8 and takes its verdict from the report.

## Test lanes (Docker)

Two tools give every test run its own environment in Docker, so parallel runs need no assigned ports, bridge ids
or desktop windows. Both run from Git Bash on Windows (and from Linux shells), bind no fixed host port, and run either
on this workstation or on a remote Docker host (a build host for the bulk of the work and, optionally, a shared machine
used only as throttled overflow; see "Hosts" below), which takes the CPU-heavy work off the workstation.

- `e2e/lanes/lane.sh`: a lane is one Compose project `mineqtt-lane-<id>` with its own Mosquitto broker, a Fabric 26.2
  dedicated server running this worktree's jar (bridge id `lane-<id>`, flat world, RCON) and, with `--ha`, its own
  Home Assistant. Healthy about 15 s (local) or 22 s (remote, the jar crosses the link) after `up`. Details and
  measurements: [lanes/REPORT.md](lanes/REPORT.md).
- `docker/client-test/run.sh`: the Fabric client game tests (above) in a Compose project `mineqtt-client-<id>`,
  headless under Xvfb with software OpenGL and a private broker. It ships the working tree (tracked edits and
  untracked, non-ignored files) into the container as a gzipped tar, so it never touches the worktree's build
  directories. A full run takes about 7 min locally and about 8-9 min on the remote host (slower cores, but off the
  workstation), a few suites about 1.5-2 min. Details and measurements:
  [../docker/client-test/REPORT.md](../docker/client-test/REPORT.md).

### Hosts

`run.sh` and `lane.sh` take `--host local|auto|<remote>` (or `MINEQTT_DOCKER_HOST`; default `auto`); `remote-gc.sh`
takes `--host local|<remote>` and by default sweeps every remote of the host table.

The host table lists the remote Docker hosts, one line per host: name, SSH endpoint, client slots and size, lane cap,
CPU pinning and weight, build policy. It is not part of the repository; `docker/hosts.sh` reads it from the first of
these files that exists:

1. `$MINEQTT_HOSTS_FILE`;
2. `<git common dir>/mineqtt-docker-hosts`, for example `.git/mineqtt-docker-hosts` (every worktree of the clone
   finds it there);
3. `docker/hosts.local` (ignored by git).

[`docker/hosts.example`](../docker/hosts.example) explains the columns and has two placeholder rows to copy. Lines
starting with `#` and blank lines are skipped. Without a table there are no remotes, and `auto` runs everything on this
workstation. Every docker and compose call goes to the selected host with `docker -H ssh://user@host` (a Docker context
name works as well), so no per-workstation set-up beyond the SSH key and the table is needed.

| Host | Client stacks | Lanes | MineQTT containers | Images and seed |
|---|---|---|---|---|
| A build host (the measured one: 64 threads, 125 GiB, also running other services) | 16 of 3 CPUs / 5 GB | 20 | unpinned, Docker's default weight | built whenever a run needs them (`build auto`) |
| An overflow host: a shared machine whose own services (production, for example) come first | 2 of 3 CPUs / 5 GB | 6 | pinned to half of its cores (whole cores, for example CPUs 4-7 and 12-15), `--cpu-shares 256` (measured: cgroup `cpu.weight` 10 in our containers, 100 in the host's own, on cgroup v2 with Docker 28.4) | manual (`build manual`): only with an explicit `--host <name>`, at a quiet hour |
| `local`: this workstation (32 threads; its Docker VM is capped at 16 GiB and shared with other projects; also every Gradle build) | 1 of 3 CPUs / 5 GB | 2 (not enforced) | Docker's defaults | built whenever a run needs them |

How to use them:

- **`auto` tries the remotes in table order, then this workstation**, and says in one line why it skipped a host
  (`remote <name> is not ready for auto ...`, `full: <name> (16 of 16); running on <next>`, `remote <name>
  unreachable`). A remote counts as unreachable when its Docker daemon does not answer `docker -H <endpoint> version`
  within 15 s (a daemon that is down, or an SSH key `docker.exe` lacks). An explicit `--host <remote>` that cannot reach
  it fails at once. `MINEQTT_REMOTES="<name> ..."` limits `auto` to those remotes, in that order (for example to keep it
  off an overflow host); `--host <name>` sends a run to any remote of the table on purpose.
- **The build host comes first in the table.** Use it freely up to its slots; the measurements below are for such a
  host.
- **An overflow host is a shared machine: its own services come first.** List it after the build host. `auto` gives it
  work only when the remotes before it are full or unreachable, and only once the host is ready (client image and Gradle
  seed for `run.sh`, lane server image for `lane.sh`), because nothing is ever built there on the side. Measure its load
  over some days (sysstat, for example) before you choose its slots and its quiet hours. Our containers are pinned to
  half of its cores and weigh a tenth of one of its own containers, so its services win any contention. The host's own
  services stay unpinned: the other cores are free of MineQTT, not reserved for them, and they may run on the pinned
  cores as well. Warm-ups: image builds and image pulls cannot be pinned (BuildKit and the daemon do them), and the
  Gradle warm-up is the most CPU-heavy step (~4 min, pinned but still a full build). So they happen there only on an
  explicit request: `lane.sh build --host <name>` (lane server image, plus pulling the Mosquitto and Home Assistant
  images) and `run.sh --warm --host <name>` (client image and seed), at a quiet hour for that host. A test run or
  `lane.sh up` never builds or pulls there: it fails with that hint, and `auto` skips the host until a warm-up with the
  current client image has completed (`run.sh` keeps an empty volume `mineqtt-client-warmed-<image hash>` as the
  record) and the lane images are present. `run.sh --warm` under `auto` never picks a `build manual` host, and an
  explicit warm-up there refuses to start while MineQTT client stacks run on it. After a dependency bump the seed is
  merely stale: runs download what is missing inside their pinned containers, until the next warm-up at a quiet hour.
- **Rules for every shared remote host** (any host that runs more than MineQTT's tests):
  1. Only through `run.sh`, `lane.sh` and `remote-gc.sh`, which add the host's pinning and weight. Never `docker run`
     or `docker compose up` there by hand, and never run commands on it over `ssh` yourself.
  2. Never touch a container, network, volume or image that is not ours: `mineqtt-client*`, `mineqtt-lane*`, the seed
     volume `mineqtt-client-gradle` and the images `mineqtt-client:*` and `mineqtt-lane-server:*`. Never `docker
     system|image|volume|container|network prune` on any remote: it would delete other services' data. `remote-gc.sh`
     is the only cleanup.
  3. Never publish a host port there (remote stacks publish none), and never point a test at a broker the host runs for
     its own services: every stack has its own broker.
  4. Keep to its client stacks and lanes. Raise them only after measuring its own services under load, and with the
     consent of whoever runs the host.
  5. Other projects (EscapeWorlds) do not use an overflow host unless the user says so.
- **Adding a host:**
  1. Add one line to the host table (format: `docker/hosts.example`), in the place `auto` should try it.
  2. Make sure the SSH key and the `known_hosts` entry are in `%USERPROFILE%\.ssh`: `docker.exe` uses Windows OpenSSH.
  3. Run `run.sh --warm --host <it>` and `lane.sh build --host <it>`.
  4. A host that runs other services gets a cpuset of whole cores (`lscpu -e` shows which CPUs share a core), `shares`
     256, `build manual` and few slots, and goes after the build host. Measure before giving it more.
- A lane remembers its host (`~/.mineqtt-lanes/mineqtt-lane-<id>`), so every later `lane.sh` command finds it without
  `--host`. Without that record a lane is looked for here, then on every remote. When it is found nowhere and a remote
  does not answer, the command fails instead of guessing (pass `--host`). A remote lane publishes no host ports: `rcon`,
  `pub`, `sub`, `smoke`, `status` and `ha-*` run inside the lane with `docker compose exec` (`rcon` through a small Java
  RCON client in the server image, `ha` by running `e2e/ha.py` inside the Home Assistant container; `ha <id> dump FILE`
  copies the dump back to `FILE` on the workstation). `ports` prints none. `MINEQTT_LANE_PREFIX` must start with
  `mineqtt-lane`.
- Nothing is bind-mounted from the workstation (a remote daemon cannot see this disk):
  - Broker and Home Assistant configs are compose `configs` filled from the files.
  - The lane server image downloads Fabric API and Architectury at build time (versions from `gradle.properties`,
    checked against Maven's sha1).
  - `lane.sh` copies the host-built MineQTT jar into the lane's `mods` volume on `up` and on every `reload` (with or
    without `--jar`).
- Every resource the tools create carries the labels `mineqtt.owner=<workstation host name>` and
  `mineqtt.created=<epoch>`. `sh docker/client-test/remote-gc.sh [--hours N] [--apply] [--force] [--all-owners] [--host
  H]` lists (dry run) or removes `mineqtt-client*`/`mineqtt-lane*` stacks older than N hours (default 12) on every
  remote (or on `H`). By default it only touches this workstation's stacks. It leaves stacks in use alone and lists
  them as skipped: a lane with a running container, or a client stack whose client container runs. A lane keeps its
  creation time through `reload` and `reset`, and the owner label is the workstation, shared by every run on it.
  `--force` removes stacks in use too; ask whoever runs them first. Projects with other names (a host's own services, for
  example) are invisible to it.

Bandwidth (the link from this workstation is slow: ~8-9 MB/s up, ~4.5 MB/s down, 0.4 s per SSH connection):

- **A remote client run** sends the source (~2.3 MB gzipped) and brings back only the report, `latest.log`,
  `console.log` and, by default, the screenshots of failing suites. The console streams only the Gradle task, verdict,
  error and SUITE/FAIL lines. `--screenshots all` (UI work: a full set is ~180 files, 29 MB, ~7 s of link time) or
  `none` overrides that; locally the default stays `all`. `run.sh` prints the bytes it sent and received.
- **A remote lane** costs the jar (~6.5 MB) on `up` and on each `reload`; everything else is a few KB per command.
- **Images** build on the host that runs them (the build context is a few KB). The Gradle seed volume is filled on
  each remote from the internet by `run.sh --warm --host <remote>`. Never copy images or volumes across the link.

Multi-host notes (add them to the usage block below):

```
Docker hosts: lane.sh and run.sh pick a host by themselves (auto: the remotes of the host table in order, then this
workstation) and print which; "running on ..." / "running locally" lines are normal. Force one with --host local|<name>.
An overflow host is a shared machine: never --host it, never ssh to it, never prune or touch non-mineqtt-client/lane
resources on any remote.
Remote lanes have no host ports: use lane.sh rcon/pub/sub/smoke/ha, never 127.0.0.1 ports; lane.sh finds the lane's host.
The link is slow (~8 MB/s up, ~4.5 MB/s down): each lane up/reload ships the ~6.5 MB jar, so reload only after a real change.
run.sh brings back the report, latest.log, console.log and only failing suites' screenshots from a remote; add
--screenshots all only when you review UI (~29 MB for a full run). run.sh prints the bytes sent and received.
Capacity: each remote's row of the host table (a build host: 16 client stacks / 20 lanes), local 1 / 2; run.sh waits for a free slot.
A client run is stopped after 30 min (MINEQTT_CLIENT_TIMEOUT_MIN) and fails with a TIMEOUT line.
```

Once per Docker host, and again after a dependency bump (Minecraft, loader, Fabric API, Architectury), the maintainer
runs the set-up commands while no client stack runs on that host. `lane.sh up` and `run.sh` build a missing image by
themselves on hosts with `build auto`, so there only the seed needs this. `docker.exe` on Windows connects with Windows
OpenSSH, so the key and the `known_hosts` entry must be in `%USERPROFILE%\.ssh` (Git Bash's `ssh` may use other ones).
The first line, run with each remote's endpoint from the host table, checks exactly what the tools check:

```sh
docker -H ssh://user@build-host version          # must print a Server section within a few seconds
sh e2e/lanes/lane.sh build --host local          # lane server image mineqtt-lane-server:<mc>-<loader>-<recipe hash>
sh docker/client-test/run.sh --warm --host local # client image mineqtt-client:<recipe hash> + Gradle seed volume mineqtt-client-gradle (cold ~3.5 min)
sh e2e/lanes/lane.sh build --host <name>         # the same on a remote (~30 s)
sh docker/client-test/run.sh --warm --host <name>      # ~5.5 min, downloads on the remote side
# an overflow host (build manual): the same two lines, only at a quiet hour (builds cannot be pinned)
```

Usage (`<id>`: a short unique lowercase id, for example the worktree name):

```
Test environment: your own Docker lane, on a remote Docker host when one is reachable and has room (else locally). From the repository root in Git Bash:
  ./gradlew :fabric:assemble && sh e2e/lanes/lane.sh up <id>      # broker + Fabric 26.2 server, waits until MQTT is connected (~15-25 s)
  sh e2e/lanes/lane.sh rcon <id> mineqtt status                    # any server command, no leading slash
  sh e2e/lanes/lane.sh pub <id> -t <topic> -m <payload>            # mosquitto_pub/_sub inside the lane broker
  (a JSON payload with commas started from a Windows program: pipe it, ... | lane.sh pub <id> -t <topic> -s, or Git Bash brace-expands it)
  sh e2e/lanes/lane.sh sub <id> -v -t 'mineqtt/#' -W 5
  sh e2e/lanes/lane.sh reload <id> --jar                           # after code changes: rebuild + ship the jar, restart the server (world kept, ~20 s)
  sh e2e/lanes/lane.sh logs <id> | status <id> | reset <id> (fresh world) | smoke <id>
  sh e2e/lanes/lane.sh up <id> --ha && sh e2e/lanes/lane.sh ha-onboard <id>   # Home Assistant; then: lane.sh ha <id> devices|entities|state ...
  (an ha-config-init container writes /config/automations.yaml before Home Assistant starts, so ha-onboard adds MQTT the first time)
Your bridge topic is mineqtt/lane-<id>. 26.2 keeps no spawn chunks: "forceload add 0 0" before setblock (flat world, surface y=-61).
Dedicated servers expose devices to HA only on request: "mineqtt devices expose <slug> on". Remote lanes have no host ports; use the commands above.
Twin bindings need an owned device (an ownerless one cannot be bound): place it with
  setblock <x y z> mineqtt:rgb_led_block{mineqtt:{owner:[I;a,b,c,d],ownerName:"Steve"}} and "op Steve" (the UUID from ops.json),
  and set twin.allowedEntities in config/mineqtt.json (a dedicated server defaults to [], twin off).
  A Redstone Input or Wall Switch with an owner-only mineqtt compound is read as v1 data (its owner is ignored): give it an id too,
  setblock ... mineqtt:redstone_publisher_block{mineqtt:{v:3,id:[I;1,2,3,41],owner:[I;a,b,c,d],ownerName:"Steve"}}.
  Read or edit files in the server container with lane.sh exec <id> <command...> (in /server, stdin passed through), e.g.
  exec <id> cat config/mineqtt.json. "lane.sh reload" restarts the server and keeps such edits, but a compose environment
  that differs from the one at up (MINEQTT_KEEP_TICKING, MINEQTT_HA_EXPOSE, ...) recreates the server container, which resets
  config/mineqtt.json, server.properties and ops.json (the world volume stays): export the same environment for every lane.sh
  call of a lane. Fabric reads the MineQTT config at start only. A server paused while empty never updates comparators: MINEQTT_KEEP_TICKING=true lane.sh up <id>
  (or server.keepTickingWhenEmpty in the config).
Client game tests (no window opens; waits for a free slot, so a run can take longer than its ~time):
  sh docker/client-test/run.sh <id> GroupGameTest ScreensGameTest  # only these suites (~1.5 min)
  sh docker/client-test/run.sh <id>                                # all suites (~5-7 min); once before handoff
  Add --screenshots all when you review UI screenshots (remote default: only failing suites' screenshots come back).
  Results: build/docker-client-test/<id>/ (mineqtt-gametest-report.txt, latest.log, console.log, screenshots/); exit code = verdict.
  Start run.sh in the background and wait for it to finish. Never run it
  under a short command timeout: a timed-out run is killed before it can clean up, its stack keeps running
  for about 90 s and its slot stays taken until another run frees it. Rerunning with the same <id> removes the leftover.
  --host local|<name> forces a host (default auto; never an overflow host); keep --screenshots and lane reloads lean, the link is slow.
ALWAYS finish with: sh e2e/lanes/lane.sh down <id>   (save what you need from "lane.sh logs <id>" first)
```

Capacity, shared by all runs:

| Host | Lanes | Client stacks |
|---|---|---|
| This workstation (32 threads; Docker VM capped at 16 GiB, shared with other projects; also runs every Gradle build) | 2 (Home Assistant only where needed) | 1 of 3 CPUs / 5 GB, enforced by `run.sh` (`MINEQTT_CLIENT_SLOTS`) |
| A build host (64 CPUs, 125 GiB, shared with other services) | 20 (`MINEQTT_LANES_<NAME>`, checked by `auto`) | 16 of 3 CPUs / 5 GB, enforced by `run.sh` (`MINEQTT_SLOTS_<NAME>`) |
| An overflow host (a shared machine; overflow only, see "Hosts") | 6 (`MINEQTT_LANES_<NAME>`) | 2 of 3 CPUs / 5 GB, pinned to half of its cores at a tenth of its own services' weight (`MINEQTT_SLOTS_<NAME>`) |

A lane takes ~0.55 GiB and under 1 % of a CPU idle (~0.9 GiB with `--ha`); its caps are 1.5 GiB / 2 CPUs for the
server and 1 GiB / 2 CPUs for Home Assistant. A client stack peaks at 4.5-4.8 GiB with the game's 2 GiB heap
(`CLIENT_HEAP`; the JVM's own default, a quarter of the container, ran the game out of heap at 4 GB). `CLIENT_CPUS` /
`CLIENT_MEMORY` override the size on every host.

Measured on a 64-thread remote host on 2026-10-11 (full 24-suite runs started 5 s apart; runs per hour with the server kept full):

| Stacks x size | Passed | Wall time per run | Runs per hour | Host at peak |
|---|---|---|---|---|
| 1 x 4 CPUs / 8 GB | 1/1 | 495 s | - | idle |
| 10 x 4 CPUs / 8 GB | 10/10 | 481-511 s | ~72 | 39 % CPU idle, 53 GB used |
| 10 x 2 CPUs / 5 GB | 10/10 | 585-841 s | - | 65 % CPU idle |
| 20 x 2 CPUs / 5 GB | 19/20 | 589-839 s | ~107 | load 68 on 64 threads; one `controls` check missed its 2 s window during a 2 s server stall |
| **16 x 3 CPUs / 5 GB** | **16/16** | **518-590 s** | **~106** | 30 % CPU idle, 77 GB used |

More than 16 stacks add no throughput (the CPUs are full) and make timing-sensitive suites flaky; fewer CPUs per
stack make every run slower. The 20 lanes fit beside them in practice (~0.9 GiB each) although their caps would not,
so raise neither limit without measuring again.

- `run.sh` counts stacks per host with mkdir slots on this workstation (every run starts here):
  `%USERPROFILE%\.mineqtt-client-slots` for local stacks and `%USERPROFILE%\.mineqtt-client-slots-<remote>` for each
  remote (`-<name>`, its name in the host table), and waits while all slots are taken (under `auto`: until any host it
  may use has one). The slot dirs are shared with other projects' client tests (EscapeWorlds takes at most one local
  slot and two slots of the build host, and does not use an overflow host); only `run.sh` writes a `stack` file into a slot. `run.sh` also records
  the host each stack id last ran on (`%USERPROFILE%\.mineqtt-client-slots-ids`), so a rerun of the id looks for its
  leftover only there and `auto` does not ask every remote on every run. A run removes its own
  stack and slot when it ends, fails or is interrupted. A run killed outright (a command timeout on Windows kills the
  process tree, so no trap runs) cannot: its client container then stops itself after about 90 s, as `run.sh` stops
  feeding it heartbeats (a long-lived `docker exec -i ... mq beat` fed a line every 15 s, so it works the same over
  SSH; `run.sh` opens it again when a connection drop ends it while the run goes on), and that also ends the Gradle
  run inside it (killing the docker CLI does not stop a `docker exec`). A waiting
  run breaks a slot whose client container no longer runs on its host (one `docker ps` per host and check), and
  removes that dead stack; a slot older than 60 minutes is broken as well. A run whose id already has a stack on a
  host it may use removes it, and its slot, before it starts.
- Never edit `run.sh` while one of your runs is going: `sh` reads the script as it executes it.
- **Every run ends with `sh e2e/lanes/lane.sh down <id>`**, also after a failure: a lane left running holds its
  memory until someone removes it. After a batch of runs, check for leftovers with `sh e2e/lanes/lane.sh list`
  (every host) and `sh docker/client-test/remote-gc.sh --hours 0` (dry run on every remote), and run `down` for each,
  or `remote-gc.sh --apply` for old remote stacks (`--force` as well for lanes that still run).
- Ports of local lanes are ephemeral and change on every container start: never cache them, `lane.sh` looks them up
  each time. Every lane has its own copy of the jar (taken when it was shipped).
- Client screenshots use software rendering (llvmpipe), so GPU-specific rendering bugs do not show. Treat a suite that
  fails only in Docker as a real race, as `GroupNameScreen` was.
- **NeoForge stays on the host path**: there is no NeoForge lane server image and no NeoForge client game test
  harness, so NeoForge is tested with `:neoforge:runServer` / `:neoforge:runClient` against the compose brokers above.
  The host path also stays the fallback when Docker Desktop is down.

## Checklist

| # | Scenario | Expected |
|---|---|---|
| 1 | Fresh world, `broker: tcp://localhost:1883`, protocol 5, `ha.expose=ALL`: one of every device type (`allblocks.sh place` gives every type, a converted humidity sensor and Static Publisher, the four v1 timers, the v1 Block Sensor and the v1 Block Emitter included) and a group of lamps | The bridge, every device and the group appear in HA with the entities of their golden discovery payloads, each with the bridge as `via_device`, the group's members hidden. Availability flips when the server stops (graceful) and when it is killed (last will). |
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
| 12 | Security: a non-op creative player picks a lamp with NBT whose raw topic is `zigbee2mqtt/x/set` and places it | The copy is a new device of the placer, whose raw link is resolved for them into `mineqtt/shared/zigbee2mqtt/x/set` (M2 F1; M1 disabled it), and the placer is told. Cyberdeck listen cannot see `homeassistant/#` payloads or other players' devices. Automated: `PlacementSecurityGameTest` (client game tests above). |
| 13 | `./gradlew :common:verifyBrokerIntegration -PexternalBroker=tcp://localhost:1883`, and again with `-PexternalBroker=ws://localhost:9001/mqtt` | Every scenario passes for both `MQTT_5` and `MQTT_3_1_1` (only "Refused covering filter" is skipped: it needs the embedded broker's ACL). This is the only coverage of the MQTT 5 scenarios, including no-local and `DO_NOT_SEND`. |
| 14 | **Same-server retained replay** (M2 plan D4): one dedicated server, a Redstone Input and a Redstone Output on one `mineqtt/shared/…` paper, their chunks far apart (400 blocks in the recorded run; a chunk near a force-loaded one stays in memory below full status, so its block entity is never unloaded, as 4 chunks apart showed). `/forceload remove` the output's chunk and wait until `devices list` shows it unloaded, power the input, `/forceload add`. Then the same across a restart: output unloaded, input powered, `stop`, start, let the input attach, then load the output's chunk. Also `./gradlew :common:verifyBrokerSmoke --args=localhost:1883` | The output comes back at `[power=15]` both times, from the broker's retained copy of the server's own level (nothing local reaches an unloaded output). The smoke probe reports "MQTT 5 own retained message on a no-local subscribe: yes". |
| 15 | **All block entities** (M2 plan M2-3): on a fresh world of each loader's dev server (MQTT may be disabled), `sh e2e/tools/allblocks.sh place`, `stop`, start again, then `sh e2e/tools/allblocks.sh verify <run>/logs/latest.log`. Repeat for every new block entity type (add it to the script's table first). | Every one of the 19 block entities has its data back (device name, type-specific key or the named paper in slot 0), the humidity sensor is a Climate Sensor publishing its humidity, the Static Publisher a Redstone Input of rising edges, the Dynamic Publisher and Topic Relay keep only their slot paper as `legacyItems`, the v1 Light Remote is a Wall Switch with its slot paper as a legacy item, the Bridge Console keeps its facing and last tab, the Display Panel its facing, id and message, the four v1 timers are timer devices with their anvil names and v1 settings (`mineqtt.state`), the v1 Block Sensor is a Block Observer with its data mode as `payload` and the v1 Block Emitter a Block Placer with its slot paper as a legacy item, the seven devices placed as devices keep their ids and all sixteen are listed as loaded, and the log has no block entity load warning (`Skipping block entity with invalid type`, `Failed to create`/`load data for block entity`, ...). The script prints `allblocks: PASS`. |
| 16 | **M1 world upgrade** (M2 plan M2-12): with a jar from before M2-12, `sh e2e/tools/buildm1.sh place` on a fresh world (Fabric dev server, MQTT on), stop, start again (the v1 output is adopted), `buildm1.sh capture`, stop; upgrade the jar and start; `sh e2e/tools/buildm1.sh check`; then a redstone block at the input, a Home Assistant `/set` to the lamp, and `/setblock <sensor> air destroy`. | Every device is version 3 with its slot items in `mineqtt.legacyItems` and no `Items`, `topic`, `ownerName`, `privateMode` or `lastTabId` beside it; the Raw Links are as captured (shared, private and grandfathered); the sensor's eggs are its `filter`. The output follows the input over the broker, the lamp follows Home Assistant, and the broken sensor drops its five stacks (`execute if entity @e[type=item,distance=..2]`). The script prints `buildm1: PASS`. |
| 17 | **Ghost cleanup** (M2 plan M2-24, design §4.5), HA online, `ha.expose=ALL`: a lamp and a group of a second lamp discovered; then, retained by hand, a copy of the lamp's config under a new `mineqtt_<uuid32>` (new unique_ids, name "Ghost Lamp") whose `via_device` is this world's bridge (`mineqtt_bridge_<worldUuid32>` from `bridge/info`), the same with another bridge's `via_device`, and a config under another object id (`e2e_…`) naming this bridge. HA shows all of them. Restart the server; again with `/mineqtt reconnect`, and on a server paused while empty. | Within 5 s of each connect the log says "Clearing the Home Assistant discovery …/mineqtt_<ghost>/config" and then "Home Assistant ghost cleanup: N discovery configs looked at, 1 ghost found"; the ghost's retained config is gone and HA removed "Ghost Lamp". The lamp, the group, the other bridge's lamp, the foreign config and every other bridge's discovery stay, retained and in HA, and the lamp still follows HA. A ghost published while the server runs stays until the next connect. |
| 18 | **Shared namespace** (M2 plan F1, D1): paper `garage` and a stone item in a Redstone Input and a Redstone Output; M1 link data (no `resolvedV`) set by `/data merge`, then a restart with `zigbee2mqtt/#` added to `acl.rawAllow`; a v1 emitter of Steve on `minecraft/stone`, and stone in new inputs of Steve and of Alex | Both links on `mineqtt/shared/garage` and `mineqtt/shared/minecraft/stone`, and the output follows. The allowed M1 topic stays as it was, the refused one moves to `mineqtt/shared/…` with a log line and a notice to its owner. Steve's new input keeps `minecraft/stone` ("kept for its owner") and drives his v1 emitter; Alex's goes to the shared form and does not. |
| 19 | **Visible refusals and ACL reload** (F2), on NeoForge (its config file watcher reloads): an input and an output on one channel; `acl.rawAllow` set to `["<base>/<bridgeId>/raw/#"]` in `mineqtt.toml`, then restored | Without a restart both links are refused (log, `devices info` "refused: …"); edges and broker messages no longer move the output. Restoring the allowlist opens both again and the output takes the input's live level. The player side is `RawLinkNoticesGameTest`. |
| 20 | **Retained wireless redstone** (F3): server A and server B (the other loader, other ports) on one broker, an input on A and outputs on A and B on one paper; a broker loss behind a killable TCP proxy, and a restart of a broker without persistence | Every edge arrives live with retain 0 and a fresh subscriber sees the level retained; B's output takes the level when it is set up and after its own restart; destroying or retargeting A's input leaves its off payload retained on the old topic; while the broker is unreachable only the last level waits in the outbox; after the broker restart the CONNACK republishes the level. |
| 21 | **Empty dedicated server** (F5, F6): `pause-when-empty-seconds=60` with nobody online, an input and an output on one channel, and an output driving a repeater line to a redstone lamp; then `server.keepTickingWhenEmpty=true` with `pause-when-empty-seconds=5` | After "Server empty for 60 seconds, pausing" a Home Assistant `/set` and an input edge still reach the output, its retained state follows, and `/mineqtt status` says `Server: paused (empty)`; the repeater (a scheduled tick) waits. With the opt-in the server never pauses, the repeater and the lamp follow, and the status says the opt-in overrides `pause-when-empty-seconds`. |
| 22 | **v1 lamp devices** (F12), both Home Assistant versions: items 5 and 6 with v1's discovery and its `suggested_area` | Home Assistant reuses the v1 "MineQTT RGB LED" device for the MineQTT device: the same device id and area, both identifiers in `dev.ids`, the same entity registry entry and entity id, and no empty device next to it. (The README explains the shape a v2 test build left.) |
| 23 | **Wires without the broker** (M2-15): an input under a lever wired to an output, a lamp and an output in a far force-loaded chunk; a restart with `broker` pointing at a closed port (the shared broker keeps running); an unloaded target; two outputs wired into each other | The targets follow the lever while `/mineqtt status` says `RECONNECTING`; the wires survive the restart; an unloaded output shows `Queued` and applies it when its chunk loads; the loop is cut after 16 deliveries (`dropped: 1 hops`, one log line). |
| 24 | **Newer Home Assistant entities** (M2-22, M2-25), both versions: a Wall Switch and a Smart Lamp exposed | `ha.py triggers` lists six device triggers (`button_short_press`, `button_double_press`, `button_long_press`, each `left` and `right`), and `ha.py watch` sees a press fire the matching one; the lamp offers `effect_list` `off`/`pulse`/`candle`, a `transition` fades its block light over the given seconds, and each command publishes one state. |
| 25 | **Mirror a Home Assistant entity** (the lamp since M3-8, the Output and the Plug since M3-12), both versions, the [twin fixture](#twin-fixture-m3), `twin.allowedEntities` covering it: an owned, exposed Smart Lamp bound with `/mineqtt devices bind <lamp> input_boolean.m3_flag`, then `light.m3_lamp`; a Redstone Output to an on/off entity, one in analog mode to `input_number.m3_level`, a Smart Plug to `input_boolean.m3_flag` | Lamp: `input_boolean.turn_on` of `m3_flag` lights it white at full brightness within a second, `devices info` shows `live`; bound to `light.m3_lamp`, `light.turn_on` with brightness 60 dims it in one step (one state publish per Home Assistant change, no flash of the old value; the fixture light has no colour); `light.turn_off` turns it off and keeps its brightness, `light.turn_on` restores it from Home Assistant's attributes. A Home Assistant restart turns the health `retained` after the birth and `live` again with the next change; the staleness of a retained value is recorded (expected without the catalog, cap §5). An unavailable entity gives health `unavailable` and the lamp holds (a client sees the grey tint and smoke). While bound, the lamp's Home Assistant device keeps its name and area, its entities are gone (not unavailable) and one disabled diagnostic *Binding* entity reads the entity id with `entity` and `health` attributes; Home Assistant logs no warning for the stubs, and neither a restart nor a birth recreates the entities; Home Assistant clears the device config itself after removing entities (it logs `No device components to cleanup` for its own clear) and MineQTT publishes the reduced config again within about 1 s, so it stays retained. Bound while Home Assistant is stopped (or before its first birth on this connection), the device is marked (`devices info`: "Home Assistant removes its old entities at its next start"): at the next birth MineQTT publishes the full device, 2 s later the reduced one, and the old entities go (M3-7b). `devices bind <lamp> none` brings the full device back with the same unique_ids, also when Home Assistant was stopped while unbinding (at its next start). 2026.10.0 restores names, areas, labels and `disabled_by` from its deleted-entity records and removes the *Binding* entity; 2025.2.5 creates new entries and keeps the disabled *Binding* entity as an orphan (delete it by hand). Output and Plug (M3-12), the control blueprint imported and the entities labelled `mineqtt_control`: an Output bound to `input_number.m3_level` goes to 11 (a comparator behind it reads 11) for `input_number.set_value` 75 within a second and to 6 for 42.5, `devices info` says `live`; with `twin.control true`, `devices set <output> {"power":15}` (or the Controls tab's level slider) sends one `input_number.set_value {"value":100}`, answered `accepted`, and the output shows 15 only once Home Assistant has 100. A Plug bound to `input_boolean.m3_flag` with control on: a player's right-click sends one `turn_on`/`turn_off` with `origin.player`, the plug follows only after statestream reports it, and `ha.py logbook input_boolean.m3_flag` names the player and the plug; after a Home Assistant restart (mirror `retained`) a right-click sends `input_boolean.toggle`. An Output bound to `m3_flag` with `twin.invert true` gives 0 for on and 15 for off. Binding an exposed Output and Plug reduces their discovery like the lamp's; unbinding brings the entities back with the same unique_ids. |
| 26 | **Catalog and labels** (M3-11), both versions, the catalog blueprint imported (`ha.py automation from-blueprint mineqtt_catalog.yaml mineqtt_catalog '{}'`) | `/mineqtt twin status` names the catalog (entities, generated) 5 s after the server connects (its `ha_sync`). Labelling an entity lists it in `/mineqtt twin entities` within about 3 s; binding an unlabelled one is refused ("not shared by Home Assistant"); unlabelling a bound lamp's entity shows `not_shared` in `devices info`, labelling it again heals it. A stale retained `ha_statestream/<domain>/<object>/state` (and an older `last_updated`) published by hand for an entity a lamp mirrors (the fixture's text entity needs a panel; `input_boolean.m3_flag` with a lamp shows the same), then a Home Assistant restart: retained after the birth, then Home Assistant's real value, live, from the catalog snapshot. A device label exposes its entities (a MineQTT lamp's device). A labelled, mirrored entity outside the include (`input_boolean.m3_bolt`) shows "no live updates: add it to the mqtt_statestream include" after two catalog cycles (`/mineqtt twin refresh` after each change; each a real state change, since a re-dating alone is no change). With the catalog automation off and `ha_statestream/_mineqtt/#` cleared, `twin entities` lists the statestream scan 10 s after the server connects; the catalog coming back closes the scan, clearing its status opens it again. |
| 27 | **Control blueprint** (M3-10), both versions, imported with `ha.py automation from-blueprint mineqtt_control.yaml mineqtt_control '{}'`, `light.m3_lamp`, `input_boolean.m3_flag` and `lock.m3_lock` labelled `mineqtt_control` | `/mineqtt twin ping` answers `pong` (with the automation off: "no answer from the control blueprint within 5 s"). A lamp bound to `light.m3_lamp` with control on, right-clicked (or `devices action <lamp> state TOGGLE`), sends exactly one `ha_request`; the light switches, statestream reports it, and only then the block follows; `ha.py logbook <entity>` shows "Minecraft: turn on by <player> via <lamp>" ("a redstone signal" without a player). Each refusal is answered and leaves the entity unchanged: `m3_other` (not labelled) `not_labelled`, shown in the action bar and `devices info`; `lock.m3_lock` `domain_not_allowed`; a hand-published `light.turn_on` at `input_boolean.m3_flag` `domain_mismatch`, `homeassistant.turn_on` too; an unknown entity `no_such_entity`; a list target or a non-JSON payload `bad_request`; `light.flash` `action_not_allowed`; a smuggled `data.entity_id` or `flash` is dropped (`ha.py trace`: only `brightness` reaches the call); a device label alone grants control (label a MineQTT lamp's device); a request whose data fails the service's schema is still answered (the answer goes first). Every template construct of the blueprint works on both versions. |
| 28 | **Minecraft as a Home Assistant trigger** (M3-14), both versions, the control blueprint imported and the targets labelled `mineqtt_control`: a Redstone Input under a pressure plate (or a lever) with `/mineqtt devices bind <input> target target input_boolean.m3_flag`, a Wall Switch with `devices bind <wall> target left scene.m3_scene` and `target right script.m3_script` | Stepping on the plate sends one `ha_request` `input_boolean.turn_on`, answered `accepted`, and `m3_flag` turns on; stepping off sends `turn_off`; with `m3_flag` already on, stepping on sends nothing (`twin status`: not needed). The left button sends one `scene.turn_on` (the scene's state timestamp changes), the right one `script.turn_on` (`counter.m3_counter` counts up, `last_triggered` changes); a double press and a hold send nothing. Target requests carry no player: the logbook reads "Minecraft: turn on by a redstone signal via <device>", also for a Wall Switch pressed by hand. The Wall Switch keeps its event entity and six device triggers (`ha.py triggers`), a press still fires `button_short_press`, and no `left`/`right` event type reaches Home Assistant. A left button bound to `light.m3_lamp` toggles the real light (live: the explicit opposite; after a Home Assistant restart, retained: `light.toggle`). An unlabelled target is refused (with the catalog blueprint running, already at the bind: "not shared by Home Assistant"; without it, the answer `not_labelled`). 2026.10.0: an Output mirroring `m3_flag` with `twin.invert` driving, through a repeater, an Input targeting `m3_flag` oscillates after one flip in Home Assistant and stops: with a slow answer at the Input's 9th request in 10 s (the breaker pauses its control: WARN line, `devices info`, `devices bind <input> resume` after removing the repeater), also with a real Home Assistant answering within about 0.25 s: the loop then runs at about 3.7 requests a second, within the per-device rate (`twin status`: 0 held for the rate), and the breaker pauses the Input after 8 requests; an edge over the rate is held and sent at the rate, which only a faster answer reaches in the loop (shown by `verifyMirrorNoLoop`) and the burst step shows on real Home Assistant. A lever toggled 5 times in 0.6 s into an Input targeting a light sends 4 requests, then the final state within the next second (`twin status`: 1 held for the rate), and nothing pauses; 6 Wall Switch presses in a second send 4 `scene.turn_on`, the other 2 are refused, counted (`devices info`: "2 requests refused by the rate") and logged once. Removing the labels of a bound target (then `twin refresh`) shows `not_shared` in `devices info` and sends nothing for a press (one WARN); relabelled it heals. Scenes and scripts never show "no live updates" after Home Assistant restarts, and neither does any mirrored entity that did not change (two `ha.py restart`s with a catalog cycle after each: no entity named). With a client looking at the Wall Switch's Twin tab during the presses, its health line counts "2 refused by the rate", and hovering it names the last target only for a player who may configure the block. With a client (M3-14 UI): Bind mode on the Input lists only the control-labelled entities of its target domains (with no control label left, it says to label them 'MineQTT control'), the Twin tab shows "Target: <name>"; on the Wall Switch it asks Left or Right first; a row's × removes that target; a USE-only player reads "Left: set" and no entity. |
| 29 | **Display Panel** (M3-15, M3-16), both versions for the data part, 2026.10.0 for the renderer; `twin.allowedEntities` covering `sensor.m3_*` and `input_number.m3_*`, an owned, exposed panel (`devices expose <panel> on`) with a comparator behind it, a client connected for the renderer | Unbound: Home Assistant shows a "Display Panel" device with a `text` entity (max 255) and the disabled diagnostics *Comparator level* and *Location*; `text.set_value` "Washer done!" shows in `devices info` with comparator 15, "7" gives comparator 7, and the text entity reads the value back. Bound to `input_number.m3_level`: `set_value` 42.5 shows `42.5%` (no space before `%`) at level 6, 75 shows `75.0%` at level 11. Bound to `sensor.m3_temperature` with `m3_level` 50: `20.0 °C` at level 15 (no range: 0..15 clamps), with `twin.min 15` and `twin.max 25` level 8; unavailable gives comparator 0 and `devices info` "unavailable", and the value and level come back with it. While bound Home Assistant keeps only the disabled *Binding* entity; a player's or `/mineqtt devices set` edit is refused with the read-only reason; unbinding brings the text entity back with the same unique_id. Renderer: the panel renamed "Outdoor" shows `Outdoor` over `20.0 °C`, then `22.5 °C` within a second of each `set_value` (screenshots), and a grey "Unavailable" under the name while the sensor is unavailable. |
| 30 | **Loop test** (M3-10), 2026.10.0: MineQTT's own exposed lamp A mirrored by lamp B with control on (A's light added to the statestream include and to `twin.allowedEntities` for the test, A's device labelled `mineqtt_control`), and a wire from A's `light` to B's `light` | One click on B gives one `ha_request`; Home Assistant switches A through its `/set`, A's change reaches B over the wire as an `HA`-rooted message and is refused (`/mineqtt twin status`: "refused: 1 root (roots: 1 ha)"), B follows through statestream, and nothing oscillates for 30 s. |
| 31 | **MQTT device presets** (M3-17), no Home Assistant needed: `python tools/fake_bulb.py --host localhost --port <broker> --name m3bulb` (or `--lane <id>`) playing a zigbee2mqtt bulb; a dev server on that broker with `zigbee2mqtt/+/set` in `acl.rawAllow` and an owned Smart Lamp; `/mineqtt devices bind <lamp> preset mineqtt:zigbee2mqtt_light m3bulb`, then `devices setting <lamp> twin.control true` | The lamp shows the bulb (`devices info`: "on zigbee2mqtt/m3bulb", its state). A right-click (or `devices action <lamp> state TOGGLE`) sends exactly one `zigbee2mqtt/m3bulb/set`: `{"state":"TOGGLE"}` while the state is only retained, else the explicit opposite; the bulb answers and the lamp follows. `devices set <lamp> {"brightness":100,"color":{"r":0,"g":0,"b":255}}` arrives typed (brightness clamped to 254). Without the allowlist entry `twin.control true` is refused and a click on a lamp whose control was on is refused with "not in the raw topic allowlist (acl.rawAllow)", nothing on `/set`. A Tasmota plug (`mineqtt:tasmota_power`, `cmnd/+/POWER` allowlisted) sends `cmnd/<name>/POWER ON`. The Generic topic aimed at `ha_statestream/...` is refused. With Home Assistant running, the preset-bound lamp's discovery is reduced to its *Binding* entity and comes back in full on `devices bind <lamp> none`. With a client: the Twin tab steps This block → Home Assistant (the picker; Escape) → MQTT device, the preset cell lists the built-ins a lamp may follow, "zigbee2mqtt light" plus `m3bulb` typed into *Device name* and Enter bind it; the tab reads MQTT device / zigbee2mqtt light / m3bulb with Live or Retained (never "Twin off", also with `twin.allowedEntities` empty), the field's tooltip "Reads zigbee2mqtt/m3bulb"; stepping the preset while bound re-binds (a new kind drops control); a player without CONFIGURE reads "MQTT device" and "Bound to an MQTT device" and no topic or name; Bind mode's picker warns "Binding replaces its MQTT device m3bulb (zigbee2mqtt light)." and Escape changes nothing; the source cycle unbinds. |
| 32 | **Timers as devices** (M4-3, M4-4), both versions: one of each timer (Interval Timer, Daylight Timer, Alarm Clock, Cron Timer) placed and exposed, an automation on a timer's `fired` event toggling a helper | Four timer devices: the `switch` toggles the block, *Trigger now* (`ha.py press`) fires and the `event` entity shows `fired` (`ha.py event-last`), `number`/`select`/`text` edit the schedule and an invalid time (`ha.py set-text … 25:00`) is refused by Home Assistant (pattern) and by MineQTT. `next_fire` renders as a relative time (unknown under 60 s); the automation runs on `fired`; disabling stops the schedule and clears `next_fire`. |
| 33 | **Bridge telemetry** (M4-7), both versions, a server with the default `telemetry.*` keys that keeps ticking (`server.keepTickingWhenEmpty`, `MINEQTT_KEEP_TICKING=true` for a lane, or a player online: a paused empty server samples nothing until something wakes it) | The bridge gains *TPS* (diagnostic), *MSPT* (diagnostic, disabled by default), *Time of day*, *Day*, *Is day* and *Weather* (an enum of `clear`, `rain`, `thunder`); *Players online* has the attribute `players` (empty without consent, `ha.py attrs`); the existing bridge entities keep their entity ids and history. `/time set 13000` turns *Is day* off within about 2 s; `/weather thunder` shows `rain` at once and `thunder` a few seconds later (vanilla ramps the storm up). With `telemetry.enabled=false` the six world entities read unknown and the log has no template or "No matching payload" warning. For a world named `world`, `python ha.py dashboard-check ../docs/dashboards/mineqtt-world.yaml` prints `OK` for every entity of the example dashboard and exits 0. |
| 34 | **Player events** (M4-8), both versions, a player joining and leaving | *Player event* (`event.<bridge>_player_event`, event types `join`, `leave`, `death`, `advancement`) shows the last join and leave without a name (`ha.py event-last`), and a logbook entry exists (`ha.py logbook`). A death or an advancement of a player who has not consented shows nothing. |
| 35 | **Block Observer and Block Placer** (M4-9, M4-10), both versions | Observer: the block sensor's attributes are the block properties, the signal sensor follows a comparator, the `changed` event fires, the `payload` select is disabled by default. Placer (with `blockPlacer.enabled=true`): select a block and press *Place* and *Clear*; the select state stays an option for a block with properties; *Last result* shows refusals (`NBT is never accepted`, `TNT is never placed`; for a placer whose space in front lies in an unloaded chunk `the space in front is not loaded`, with `blockPlacer.consumeItems` and the chunk behind it unloaded `the container behind it is not loaded`, and with a claim mod and a container behind it across a claim line its owner may not open `refused by the claim behind it: …`); a `/reload` that changes `#mineqtt:placeable` republishes the select's options. |
| 36 | **Consent and presence** (M4-11 to M4-13), both versions | The `players` attribute and `player_event.player` follow `/mineqtt privacy share`. "Player Steve" appears under the bridge after `share on` and its presence flips with join and quit; after `share off` the device disappears; no ghost after a Home Assistant restart. With `telemetry.players=full` and `share full`: health, dimension and position, removed again on `share on`. |
| 37 | **Rooms and `suggested_area`** (M4-14), both versions, the world-twin fixture's areas (Kitchen missing) | A lamp placed in the room *Kitchen* lands in the area *Kitchen* (`ha.py area-of`); both versions create the missing area, and a room spelled `kitchen` reuses it. A device that existed before with an area keeps it; a device without an area stays without one on 2026.10.0 but takes the room's area on 2025.2.5. Hidden and exposed again, a device gets its old area back on 2026.10.0 and the current room's on 2025.2.5. The General tab's hint names the area Home Assistant was first told (a lamp created in *Kitchen*, hidden, its room renamed *Cookhouse*, exposed again: "Cookhouse *", the hint naming Kitchen), or says that it was first discovered without an area; it never claims what Home Assistant did, so it is honest on both versions. A group lands in its room's area and keeps it when a member leaves the room. |
| 38 | **Broadcast text** (M4-22), both versions, `ha.expose` ALL. On Fabric the key needs a server restart (on a lane edit `config/mineqtt.json` with `lane.sh exec` and `docker restart` the server) | Default config: no *Broadcast* entity, and `pub <base>/<bridge>/bridge/request/broadcast -m Hi` answers `{"status":"error","error":"broadcast disabled"}` on `bridge/response/broadcast`. With `telemetry.allowBroadcast=true`: `text.<world>_broadcast` (configuration category, max 255, no state) is created without a warning; setting it to `§4Hi` followed by U+202E (`ha.py set-text`) logs `Broadcast text from <topic>: Hi` and `[MQTT] Hi` and answers `ok`; five sets within a second show three and answer `rate limited` twice; Home Assistant refuses a 256-character value, while one published by MQTT is shown whole. Turned off with Home Assistant online, Home Assistant logs `Removing component: text.<world>_broadcast` and the other bridge entities keep their entity ids; turned off while Home Assistant is stopped, the entity is gone after its next start (one create and remove pair) and a later restart creates nothing. `/mineqtt discovery clear` then `republish` with the key off sends no stub. |
| 39 | **Area spelling** (M4-23), both versions, the catalog blueprint imported and the fixture's area *Living Room* holding a labelled entity | A room typed `living room` (and one typed `livingroom`) over a new exposed lamp sends `"sa":"Living Room"` and puts the lamp into *Living Room* (`ha.py area-of`); no second area appears (`ha.py areas`). With `twin.catalog` false the typed name goes out. |
| 40 | **Upgrade 0.7.0 → 0.8.0** (M4-21), Fabric and NeoForge, a world and config made by the 0.7.0 jars of `tools/build07.sh`. Fabric: `sh tools/upgrade07.sh lane <id>` (up on the 0.7.0 jar with `blockPlacer.enabled=false` written into `mineqtt.json`, `place`, `lane.sh reload <id> --jar`, `verify`; `lane.sh down <id>` after). NeoForge: a NeoForge 26.2 dedicated server (installer `--install-server`, flat world, RCON, `pause-when-empty-seconds=0`) with the 0.7.0 NeoForge jar and Architectury in `mods/`; start, `MINEQTT_RCON_PORT=… MINEQTT_RCON_PASSWORD=… sh tools/upgrade07.sh place --config <server>/config/mineqtt.toml`, `stop`, swap in `neoforge/build/libs/MineQTT-neoforge-<version>.jar`, start, `… upgrade07.sh verify --config <server>/config/mineqtt.toml --log <server>/logs/latest.log`. For Home Assistant: `ha.expose` ALL (or OPT_IN), a `ha.py dump` before the jar swap and after | `upgrade07: PASS` on both loaders: the timers are devices with their anvil names, owner UUIDs, settings (the custom time exact) and counters (the interval counts on, the Alarm Clock keeps its day and does not fire again on the upgrade day), their slot papers are legacy items; the sensor and the emitter are a Block Observer (payload `block_id`) and a Block Placer on the same grandfathered topic for the same owner; the lamp keeps id, slug, name, Raw Link, access and light; the placer stays off (`mineqtt.json` as 0.7.0 left it, `mineqtt.toml` gains the new keys and keeps `enabled = false`); no block entity load warning. A Daylight Timer at sunrise saved in the 20 ticks before the day turns (the fixture saves it 10 ticks before) fires once more (known limitation, printed as a NOTE). After the upgrade the new devices appear (`ha.expose=ALL`) or wait for expose (`OPT_IN`), no ghost device stays, and every existing unique_id is unchanged. |

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

### 2026-10-09, M2 F4 (`feature/v2-m2`), Fabric dedicated dev server, Docker Mosquitto 2.1.2, MQTT 5

| # | Result | Evidence |
|---|---|---|
| 13 | PASS | `tcp://localhost:1883` and `ws://localhost:9001/mqtt`, each: scenarios 1-10 and 12-15 PASSED for both protocols, "Refused covering filter" SKIPPED. Scenario 15 ("Own retained, late input"): a no-local, retained-accepting input subscribed after the service stored `true` got it once, flagged retained; the next level came as the live loopback copy; after `close()` a new service with the same client id got the stored `false` once. |
| 14 | PASS | Bridge `f4test`, paper `f4door` (`mineqtt/shared/f4door`, Redstone Input retained). Input at (0, -60, 0), output at (400, -60, 0); live edges 15/0. Output chunk unloaded (`devices list`: unloaded), input powered (retained `true`), chunk loaded: `[power=15]`; the same with the input turned off: `[power=0]`. Output unloaded, input powered, `stop`, start, input attached and connected, then the output's chunk loaded: `[power=15]`, and the next live edge took it to 0. `verifyBrokerSmoke --args=localhost:1883`: "MQTT 5 own retained message on a no-local subscribe: yes". A first attempt with the output 4 chunks from the force-loaded input chunk never unloaded it: its chunk stayed in memory below full status, so the block entity stayed attached for minutes. |

### 2026-10-10, M2 final (`feature/v2-m2-finish`, version 0.6.0), Fabric dedicated dev server, Docker Mosquitto 2, both HA versions online

The last regression of Milestone 2, on the jars this commit builds (the bridge's version sensor read `0.6.0`). World
`m227world`, bridge `m227`, `ha.expose=ALL`, `allblocks.sh place` (nine devices: all seven types, with a humidity
sensor converted into a Climate Sensor and a Static Publisher into a Redstone Input) plus a group "E2E Lamps" of two
lamps (a third joined later) and two wires (`allblocks_input.powered -> allblocks_lamp.state` follow,
`allblocks_switch.press -> allblocks_plug.state` pulse_toggle). No bug was found, so no fix commit precedes the docs.

| # | Result | Evidence |
|---|---|---|
| 1 | PASS (both HA) | Both versions: the bridge `m227world` and ten devices with `via_device` = the bridge (nine devices and the group, whose members are hidden), the same 39 entities on each (identical lists): light with `effect_list` `[off, pulse, candle]` and colour modes `color_temp`/`rgb` (2000-6500 K), outlet switch with its power-on select, the output's switch, power number and two selects, the inputs' binary sensor, signal and pulse event, the presence sensor's occupancy, count, detect, range and clear delay, both Climate Sensors' temperature, humidity, illuminance, biome and interval number, the Wall Switch's event entity (`press`/`double_press`/`hold`, device class button), and every `location` (and the lamp's light level) disabled by default. `supported_features` 36 on 2026.10 (effect, transition) and 44 on 2025.2.5, which ignores `flash: false` (M2-25). Round trips: `light.turn_on` blue/200 on the group from 2026.10 lit both members (light level 12), `switch.turn_on` on the plug and `number.set_value` 7 on the output from 2025.2.5 reached the blocks, a redstone block at the input turned its binary sensor on in both and switched the wired lamp on. Graceful `stop`: all 29 enabled device entities and the group `unavailable`, connectivity `off`, on both. Kill (`Stop-Process -Force`): the same through the last will within 5 s. |
| 5 | PASS (both HA) | World `m227v1`, built like items 5-7 (`buildv1.sh` with this run's topics `m227/kitchen`, a `m227/hall` pair with one lamp unloaded, `m227/office` unloaded, an input/output link on `m227/garage`, a motion sensor), v1 config, v1's retained `Offline` and v1's exact discovery: 2026.10 created the three v1 lamps, 2025.2.5 rejected them, so the floor got v1's config without the colour-temperature keys (as F12). After the upgrade with HA online, on both versions every lamp kept its entity registry entry, entity id, device id and area (`regdiff.py`, e.g. stable `light.m227kitchen_mineqtt_rgb_led_rgb_led_m227_kitchen`, registry id `f89d2799…`, device `56442192…`, area `m227kitchen`), the device was renamed "Kitchen"/"Hall"/"Office" with `ids` = `[mineqtt_<uuid32>, m227_<topic>]` via the new bridge, the pair is one light (group `hall`, the unloaded member included), the unloaded office lamp was migrated without loading. No `homeassistant/light/…` config and no `minecraft-t227host/status` left. The garage link: a redstone block at the input set the output to 15, removing it to 0 (not repeated with the broker down; item 23 covers wires without the broker). `light.turn_off` from 2025.2.5 switched the kitchen lamp off. |
| 8 | PASS (both HA) | `ha.py restart` of both: every entity back with its state, no server restart. A live subscriber during a restart of 2025.2.5 saw `homeassistant/status` `offline`, `online` 10 s later, then 2 s later the bridge and ten device configs and 1 s later every device state. |
| 9 | PASS | Retained `{"state":"OFF"}` on the output's `/set`, `OFF` on the plug's `/set/state` and `{"state":"OFF"}` on the group's `/set`, published while the server was down; after the restart the output was still at power 7, the plug on and the group's lamp at light level 12. |
| 10 | PASS | The upgraded v1 world on its migrated config (`ha.expose` AUTO = OPT_IN): only the three lamps (grandfathered) were discovered, not the migrated output, input or presence sensor. |
| 13 | PASS | `tcp://localhost:1883` and `ws://localhost:9001/mqtt`, each: 34 PASSED (MQTT disabled, the 3.1.1 CONNACK refusal stub, scenarios 1-10 and 12-17 for both protocols), 2 SKIPPED ("Refused covering filter"). |
| 23 | PASS | The `m227world` server restarted with `broker=tcp://localhost:1884` (`RECONNECTING`, nothing listens there): both wires listed; the redstone block removed and set again at the input switched the lamp to 0 and back to 15; `devices action allblocks_switch press_left` and `press_right` switched the plug off and on again; status "emitted 4, delivered 4, dropped: none". (The loop and the unloaded target were shown by M2-15's run.) |
| 24 | PASS (both HA, partly) | `ha.py triggers "Allblocks Switch"` lists the six triggers on 2025.2.5 as on 2026.10 (the floor had not been checked before); the lamp's `effect_list` on both. `ha.py watch` and the transitions were shown on 2026.10 by M2-22 and M2-25 and not repeated. |

Notes:
- Both versions log "No device components to cleanup for mineqtt_<id>" four times per lamp that joins a group. MineQTT
  clears the hidden member's discovery once; Home Assistant itself then publishes an empty retained config to the topic
  of every device it removes (`async_remove_discovery_payload`), so with two instances on one broker each sees the other
  empties and warns. This is Home Assistant's behaviour, not a MineQTT bug.
- Client game tests (`MINEQTT_E2E_BROKER=tcp://localhost:1883 ./gradlew :fabric:runClientGameTest`), with the four renamed blocks: 22 suites PASS, 931 checks, none skipped; the crash report is the known post-main shutdown watchdog.
- Cleanup: `/mineqtt discovery clear` in both worlds (Home Assistant removed every test device), the retained
  `mineqtt/m227/#`, `mineqtt/m227v1_f29b/#`, `m227/#`, `minecraft-t227host/#` and `allblocks/#` topics cleared, both
  worlds deleted, `fabric/run` restored, both Home Assistant containers stopped.

### 2026-10-10, M3-1, M3-2 and M3-3, Docker Mosquitto 2, Home Assistant 2026.10.0 only

- Client game tests through the lock (`MINEQTT_LANE=int1 MINEQTT_E2E_BROKER=tcp://localhost:1883 sh e2e/tools/run-gametests.sh`,
  no filter): 22 suites PASS, 931 checks, no SKIP, no FAIL, exit 0; afterwards no retained topic containing `int1`.
- Twin fixture (M3-1's HA steps 1 to 4, stable instance only; the floor was not started): started with
  `docker compose up --no-deps ha-config-init` and `up -d --no-deps homeassistant` (no configuration error, no recovery
  mode, no template repair). All thirteen fixture entities answer `ha.py state`. One change per included entity: all
  twelve appear under `ha_statestream/<domain>/<object_id>/…`, 81 retained topics afterwards, every one retain 1 and
  QoS 1 (`mosquitto_sub -q 1 --retained-only -F '%r %q %t %p'`). States raw (`Turbo Mode`, a text with `ü`, quotes and a slash, a
  button's and a scene's state is an ISO timestamp), attributes JSON (`"°C"`: 2026.10.0 escapes non-ASCII in
  attribute JSON), timestamps raw ISO with `+00:00`. Include proof: `lock.lock`, `input_boolean.turn_off`/`turn_on` of
  `m3_bolt` flipped `ha_statestream/lock/m3_lock/state` (`unlocked`, `locked`) and never published an
  `input_boolean/m3_bolt` topic. At start, only the three template entities (lamp, lock, sensor) published; the
  restored helpers did not (cap §5). The live session (165 messages) and the retained snapshot (81) replayed through
  M3-2's `EntityDocuments` gave 12 deliverable documents each (LIVE and RETAINED), with every ignore/raw/bad-timestamp
  counter at 0; M3-3's `ValuePath`/`ValueMap` on them: lamp `attributes.brightness` 128 and `Light()`
  `{"state":"ON","brightness":128}`, `input_number.m3_level` 50.0 → level 8, `input_select.m3_mode` `Comfort` → 1,
  `sensor.m3_temperature` → `20.0 °C`.
- Cleanup: the Home Assistant service stopped, `ha_statestream/#` cleared with `--remove-retained` (0 left).

### 2026-10-10, M3-1 to M3-3 review fixes, Docker Mosquitto 2, Home Assistant 2025.2.5 only

- Twin fixture on the floor (M3-1's HA steps 1 to 7, the steps the run before still owed): the broker held no `ha_statestream/#`
  topic before; started with `docker compose up --no-deps ha-config-init` and `up -d --no-deps homeassistant-floor`
  (the stable instance stayed stopped throughout, so every statestream topic came from 2025.2.5). No configuration
  error, no recovery mode, no template repair (the only repair issue is `country_not_configured`); the legacy
  `platform: template` lamp and lock of `packages/2025.2` set up. All thirteen fixture entities answer `ha.py state` and
  have a registry entry. Token: the new `ha.py login` (the volume was already onboarded).
- Unlike 2026.10.0, the floor published **nothing** at start, not even the template lamp, lock and sensor (cap §5).
  One change per included entity (the calls of the fixture check): all twelve published (135 live messages); afterwards
  81 retained topics, every one retain 1 and QoS 1 (`mosquitto_sub -q 1 --retained-only -F '%r %q %t %p'`), the same 81
  topics as 2026.10.0's run.
- Cap §7 on 2025.2.5: `state` raw (`Comfort`, `floor ü "x" / y` with its quotes and umlaut, `50.0`, `2`; a button's and
  a scene's state is an ISO timestamp); attributes JSON (`"M3 Lamp"`, `["Eco", "Comfort", "Turbo Mode"]`, `null`,
  `false`, `0.5`, and the unit as `"°C"`: 2025.2.5 ASCII-escapes attribute JSON exactly like 2026.10.0);
  `last_updated`/`last_changed` raw ISO with microseconds and `+00:00`. No difference from 2026.10.0's format.
- Include proof: `lock.unlock`, then `input_boolean.turn_on`/`turn_off`/`turn_on` of `m3_bolt` flipped
  `ha_statestream/lock/m3_lock/state` (`unlocked`, `locked`, `unlocked`, `locked`), and no `input_boolean/m3_bolt` topic
  ever appeared, live or retained.
- `EntityDocuments` (with the review fixes) on the live session and on the retained snapshot: 12 entities each, all 12
  deliverable, LIVE and RETAINED respectively, every ignore/raw/bad-timestamp counter 0, and the same state, attributes
  and `last_updated` in both; `sensor.m3_temperature` reads `°C`.
- `ha.py restart`: `input_text.m3_note` came back at its initial `Grüße "quoted" / slash`, and statestream published
  nothing at start, so the retained `ha_statestream/input_text/m3_note/state` still said `floor ü "x" / y` (cap §5's
  stale value, reproduced on the floor). `label create "MineQTT read"` gave `mineqtt_read`; `label add`, `label list`
  and `label remove` on `light.m3_lamp` worked, and the label was deleted again.
- Cleanup: the floor stopped, `ha_statestream/#` cleared with `--remove-retained` (0 left).

### 2026-10-11, M3-7, M3-8 and M3-9, Fabric dedicated dev server, Docker Mosquitto 2, both HA versions one at a time

Item 25 (the lamp's read part: M3-8's HA steps 1 to 6 with M3-7's steps 1, 2, 3 and 5). Dev server from the worktree
(`broker tcp://localhost:1883`, `twin.allowedEntities ["light.m3_*","input_boolean.m3_*"]`, keep ticking, bridge
`world_eea6`), two exposed Smart Lamps owned by an opped `Steve` placed with `setblock … {mineqtt:{owner:…}}`. Stable
first (`up -d --no-deps homeassistant`, floor stopped), then the floor (stable stopped), so every statestream topic came
from the instance under test. No `ha_statestream/#` topic was retained before. M3-9's HA steps need M3-10's control
blueprint and were not run.

| Step | 2026.10.0 | 2025.2.5 |
|---|---|---|
| M3-8 1: bound to `input_boolean.m3_flag`, `turn_on` | PASS: lamp ON, white, 255 in the same second, health `live` | PASS, same |
| M3-8 2: bound to `light.m3_lamp`, `turn_on` brightness 60 | PASS: 60 in one state publish after Home Assistant's one burst (the fixture light has no colour, so no colour was checked) | PASS, same |
| M3-8 3: `turn_off`, `turn_on` | PASS: OFF with brightness 60 kept (statestream `brightness null`), ON 60 again | PASS, same |
| M3-8 4: `ha.py restart` | PASS: statestream published nothing during the restart; the birth made the health `retained` (as of the last change), MineQTT republished discovery, the next change made it `live` (brightness 100 → 99). Home Assistant restored the same state, so the retained value was only stale in its timestamp | PASS: the same; the floor's logbook has a fresh `on` at start (02:08:32) while the retained value says 02:08:22, same values |
| M3-8 5: unavailable | PASS (server side): no fixture entity can become unavailable on its own, so the state was set to `unavailable` through Home Assistant's REST API (`POST /api/states`, which statestream publishes as any change): health `unavailable`, the lamp held ON 99, the state topic's `binding.health` followed. Tint and smoke need a client and were not seen | PASS (server side), same |
| M3-7 1: reduced discovery | PASS: device keeps its name; light, light level and location are gone from the registry (not unavailable); one disabled diagnostic Binding sensor; enabled, it reads `light.m3_lamp` with attributes `entity`, `health` (`retained`, later `live`) and the icon | PASS, same |
| M3-7 2: warnings, restart, birth | **Partly.** At the bind Home Assistant removed the stubbed entities and then cleared the retained device config itself (two empty retained publishes on `homeassistant/device/mineqtt_<id>/config`, from `async_removed_from_registry`) and logged two `WARNING … No device components to cleanup for mineqtt_<id>` for those empty payloads. MineQTT republished the reduced config at the next birth; after that a restart logged nothing and recreated nothing | **Partly**, same two warnings and the same clear |
| M3-7 3: bind while Home Assistant is stopped | **FAIL**: the retained reduced config was there at start, but Home Assistant ignores a platform-only stub for a component it has not discovered in its current run, so lamp B kept `light` (unavailable), light level and location next to its new Binding entity | **FAIL**, same |
| M3-7 5: unbind | PASS with notes: the full device came back with the same unique_ids and the same entity ids (Home Assistant restores deleted entities); the Binding entity stays (the full config has no stub for it) and is an `unavailable` orphan after the next restart; on 2026.10.0 the light level sensor came back enabled although it is disabled by default | PASS with the same Binding leftover; the light level stayed disabled |

Cleanup: `/mineqtt discovery clear` with both instances running (after unbinding, so both lamps were discovered in full);
the stable instance kept lamp A's device (its disabled location and the orphaned Binding), removed with
`config/device_registry/remove_config_entry`; both instances stopped; `ha_statestream/#` and `mineqtt/world_eea6/#`
cleared (38 topics); the broker's retained `mineqtt/#` and `homeassistant/#` match the snapshot from before the session.
The dev server JVM lingered after `stop` and was ended by its PID.

### 2026-10-11, M3-7b, M3-10, M3-11, M3-12, M3-14, M3-15 and M3-16, Fabric dedicated dev server, private Mosquitto 2, both HA versions one at a time

Items 25 (lamp, Output, Plug, the M3-7b discovery cases), 26 (catalog, refusals), 27 (the request table), 28 (Input and
Wall Switch targets, the loop) and 29 (the panel's data part) PASS on 2026.10.0 and 2025.2.5, with the documented
2025.2.5 Binding orphan after unbinding; item 15 PASS on NeoForge. The catalog blueprint failed once a script had run
(a datetime attribute) and was fixed before the run ended. The repeater loop of item 28 ends at the per-device rate rather than
the breaker. Not run: a real client's player name and picker, the panel's render screenshots (the `display-panel`
client suite covers the drawing).

### 2026-10-11, M3-13, M3-14 UI, M3-17 and the fixes of the run before, Fabric dedicated dev server, private Mosquitto 2, both HA versions one at a time

Item 28's new rows PASS: the repeater loop now ends paused by the breaker after 8 requests on 2026.10.0 and 2025.2.5
(the run before's open point fixed; Home Assistant answered slower than the rate, so nothing was held in the loop, the hold
is covered by `verifyMirrorNoLoop` and the burst step); on 2026.10.0 only, a burst of edges ends with the entity as
the redstone left it (one state held for the rate), and presses over the rate are refused, counted and logged once and
pause at the 9th attempt; on both versions, an unlabelled bound target shows `not_shared` and sends nothing, and scenes
and scripts no longer drift after restarts. Item 31 PASS with
`tools/fake_bulb.py` (TOGGLE while retained, then explicit states, typed colour, the ACL refusal; the discovery
reduction on both versions). Open: after two Home Assistant restarts every mirrored entity that did not change is
reported as "no live updates" (restored entities are re-dated without a statestream publish). Not run: every client-only
step (the picker against a real catalog, the Twin tab, Bind mode, the player's name).

### M3 final (version 0.7.0), Fabric and NeoForge dedicated dev servers, private Mosquitto 2, both HA versions one at a time

The last regression of Milestone 3 (plan §4 M3-18), 2026-10-11, on the merged tree (the code of
`dfa54e2`, the bridge's version sensor reads `0.7.0`). Setup: the private pair `mineqtt-int7` (Mosquitto 2 on 1895,
2026.10.0 on 8135, 2025.2.5 on 8136, one Home Assistant running at a time), a Fabric dedicated dev server (bridge `int7`,
`ha.expose ALL`, `twin.allowedEntities ["*"]`, `acl.rawAllow` `zigbee2mqtt/+/set` and `cmnd/+/POWER`), fresh worlds for
items 1, 5/10, 15 and the 0.6.0 upgrade, a NeoForge dedicated dev server for item 15, both labels and both blueprints
imported from the tree. No client joined (no window may open on the test workstation), so the client-only rows are
"not run" and name the Docker suite that covers the same screen.

| # | Result | Evidence |
|---|---|---|
| 1 | PASS (both HA) | The bridge and 11 devices (ten devices with the Display Panel, the group; members hidden), 43 entities, identical on both versions; round trips (group light, plug, Output, Input); graceful stop and kill (last will within 0.2 s): every enabled entity `unavailable`, connectivity off |
| 5 | PASS (2026.10.0) | `buildv1.sh` world with v1 discovery: after the upgrade the same entity ids, registry ids and device ids, devices *Kitchen*, *Hall* (group, one member unloaded), *Garden*, *Office*; no `homeassistant/light/…` left; the wireless link works. 2025.2.5 rejects v1's config (no entity to keep) |
| 8 | PASS (both HA) | After the birth 19 device configs at +2.0 s (2025.2.5 +1.0 s) and the states within 7.4 s, every entity back; a mirrored lamp `retained` 1-2 s after the birth and `live` with the next change |
| 9 | PASS | Retained `{"state":"OFF"}` on an Output's `/set` and `OFF` on a plug's `/set/state`, published while the server was down: both still ON after the restart |
| 10 | PASS (2026.10.0) | The upgraded v1 world on `AUTO`: only the lamps and the group discovered (grandfathered); a new lamp only after `devices expose … on` |
| 13 | PASS | `tcp://localhost:1895` and `ws://localhost:9015/mqtt`: each 36 PASSED (scenarios 1-10 and 12-18 for both protocols plus the two stubs), 2 SKIPPED ("Refused covering filter") |
| 15 | PASS | NeoForge `allblocks: PASS`; Fabric every data, conversion, facing, id and log line PASS (its device count saw the two group lamps added to the same world for item 1) |
| 23 | PASS | `broker` on a closed port: `RECONNECTING`, the wire survived the restart and an Input switched the Output on and off twice |
| 24 | PASS (both HA, partly) | Six device triggers, the event entity (`press`/`double_press`/`hold`), `effect_list` `[off, pulse, candle]` (`supported_features` 36 / 44); `watch` and transitions not repeated |
| 25 | PASS (both HA) | Lamp on a flag and a light (one publish per change, brightness kept), retained/live/unavailable health, only *Binding* while bound, the offline bind (full device 2-3 s after the birth, then the reduced one), unbind with the same unique_ids (2026.10.0 removes *Binding*; 2025.2.5 keeps the orphan); Output 11/6 and `set_value 100` only once applied; Plug `turn_off` then follows, `toggle` while retained; inverted 0/15 |
| 26 | PASS (both HA) | The catalog 5 s after connect; a label lists an entity in 2 s; an unlabelled bind refused; `not_shared` and healing; a stale retained value corrected (`retained`, then `live` from the catalog); a device label shares its light; drift: no names after two restarts, `m3_bolt` after two real changes; the scan without the catalog |
| 27 | PASS (both HA) | `pong` and the 5 s no-answer; one request per toggle, the block after statestream, the logbook line; `not_labelled`, `domain_not_allowed`, `domain_mismatch` (twice), `no_such_entity`, `bad_request` (twice), `action_not_allowed`, smuggled keys dropped, a device label grants control, a schema failure still answered |
| 28 | PASS (both HA; loop, burst and presses on 2026.10.0) | Input edges `turn_on`/`turn_off`, nothing when already on; scene and script buttons; triggers kept; a light target explicit when live, `light.toggle` when retained; unlabelled refused; the repeater loop paused after 8 requests; 5 edges: 4 sent, the last held and sent, the light ends on; 6 presses: 4 sent, 2 refused and counted; an unshared target sends nothing and heals |
| 29 | PASS data part (both HA); render part not run | `text` entity (max 255), messages and levels, `42.5%`/6, `75.0%`/11, `20.0 °C`/15 and 8 with a range, unavailable level 0, read-only refusal, *Binding* while bound. Render: not run (no client; the `display-panel` suite draws it) |
| 30 | PASS | One click, one request, A switched through its `/set`, A's change refused on the wire ("refused: 1 root (roots: 1 ha)"), B follows through statestream, nothing for 30 s |
| 31 | PASS (server side); Twin tab steps not run | `TOGGLE` while retained, then `OFF`; typed colour; the lamp follows; reduced to *Binding* and back; a generic switch into the statestream namespace refused; a Tasmota plug. The Twin tab's *MQTT device* steps need a client: `mirror`'s preset step covers them |
| 0.6.0 upgrade | PASS (2026.10.0) | A world of a 0.6.0 dev server (`eb43f42`: all blocks, a group of two lamps, two wires) opened by 0.7.0: the same devices, group, wires and states, Home Assistant's registry identical but the version sensor (0.6.0 → 0.7.0), nothing bound; `mineqtt.json` loaded without messages and unchanged (no `twin` section); the only WARN is the documented "twin is off" line of a dedicated server |
| Even-edge burst | PASS (2026.10.0) | 4 sent, the 5th held, the 6th "not needed" dropped it; the light ends off, as the redstone |
| Two light targets | PASS (2026.10.0) | L R L R L R in 0.5 s: 6 requests, the last two held in their own slots and both sent |
| Client: picker | not run | No client; `bind` covers the picker, its domain chips included, against a simulated catalog |
| Client: Twin tab | not run | No client; `bind` and `mirror` cover the lines, the toggle and the answers (the server-side logbook line was checked in item 27, "by a redstone signal" from the console) |
| Client: colour picker | not run | No client; `mirror` covers it |
| Client: targets | not run | No client; `bind` covers it |
| Client: preset source | not run | No client; `mirror`'s preset step covers the source cycle, preset cycle, device name, control and the replace warning; item 31 sent brightness and colour to `fake_bulb.py` by command |
| NeoForge panel | not run | Needs a desktop window (`:neoforge:runClient`); NeoForge's server side passed item 15 |

### M4 (version 0.8.0), Fabric and NeoForge dedicated servers, private Mosquitto 2, both HA versions one at a time

Milestone 4's items were run as each part was merged, on 2026-10-11 and 2026-10-12: private pairs of Mosquitto 2 and
Home Assistant 2026.10.0, then 2025.2.5 with the first one stopped, a fresh broker and a fresh world (onboarding
created Bedroom, Kitchen and Living Room on both), and a Fabric dedicated dev server of the merged tree (`ha.expose
ALL`, `server.keepTickingWhenEmpty`, for items 37 and 39 also `twin.allowedEntities ["*"]` and the catalog
blueprint). Item 40 ran on test lanes with Home Assistant (`lane.sh up <id> --ha`), since a dev server cannot start
on the 0.7.0 jar, and on a NeoForge 26.2.0.88 dedicated server. No client joined (no window may open on the test
workstation): what needs a player is covered by the client suite named in the row. Every Home Assistant log was read
at WARNING and ERROR level; besides the evidence below it held only the lane's known `mqtt_statestream` setup error
before MQTT was added and the harmless `No device components to cleanup` line per removed entity.

The final regression of 0.8.0 on 2026-10-12 ran on the merged tree: `./gradlew build` with its 37 checks, all 33 client
suites in the Docker runner (33 passed), `allblocks.sh` and `upgrade07.sh` on both loaders, the v1 world items 5 to 7,
the dashboard check, and one pass over items 32 to 39 on each Home Assistant version (timer *Trigger now* with `source`
`manual`, telemetry, a published `join`, observer and placer, rooms creating *Kitchen* and reusing *Living Room*,
broadcast, room switch and *Use*), all as recorded before.

| # | Result | Evidence |
|---|---|---|
| 15 | PASS | `allblocks: PASS` (16 devices, the timers as devices with names and `mineqtt.state`, no block entity load warning) on fresh Fabric dev server worlds and fresh NeoForge worlds while the milestone was merged; again on the final 0.8.0 tree: 36 PASS lines each on a fresh Fabric dev server world and on a fresh world of a NeoForge 26.2.0.88 dedicated server with the 0.8.0 jar |
| 32 | PASS (both HA) | Per timer a switch (primary, `name: null`), a *Fired* event, *Trigger now*, a *Next fire* timestamp and the schedule as configuration entities (interval number 1..1000000, unit select, `trigger_at` select and pattern-checked time text, cron text of 64); the switch disarms (no fire for 10 s, *Next fire* unknown) and arms on the 5 s grid without an extra fire; *Trigger now* pulses the comparator with `source` `manual`, also while disarmed; an automation on `fired` toggled `input_boolean.m3_flag` every 5 s with logbook entries; edits land (`0 0 0 12 * * *` gives the next day 12:00Z); Home Assistant refuses `25:00` (`ValueError` naming the pattern, HTTP 500) and MineQTT drops `25:00` on `/set/time` and `every minute` on `/set/cron`; `next_fire` (`…Z` on MQTT, `+00:00` in Home Assistant) reads unknown under 60 s and while disarmed; a 5 s cron republishes it every other fire. With `advance_time false` the Daylight Timer's *Next fire* is unknown within 3 s and flat for 125 s while the Interval Timer keeps its own; `/tick freeze` empties both, and both come back |
| 33 | PASS (both HA) | *TPS* (diagnostic), *MSPT* (diagnostic, disabled), *Time of day*, *Day* (measurement), *Is day*, *Weather* (enum `clear`/`rain`/`thunder`); *Players online* has `players: []`; `time set 13000` turns *Is day* off within 0.6 s (19:00); `weather thunder` shows `rain`, then `thunder`; `tick freeze` gives TPS 0.0 and `tick unfreeze` 20.0 within the 10 s interval; `telemetry.enabled=false` leaves the six world entities unknown without a warning |
| 34 | PASS (both HA, Home Assistant side) | `event.world_player_event` with the types `join`, `leave`, `death`, `advancement`; a published `{"event_type":"join"}` shows `join` without a `player` attribute. A real player's joins, deaths and advancements, with and without consent: the `telemetry` client suite |
| 35 | PASS (both HA) | Observer: the primary sensor `minecraft:chest` with `facing`/`type`/`waterlogged` attributes, the signal (2 after three stacks), the *Changed* event (an oak stair's attributes and `block`), `change_on block` silent on a facing change, the `payload` select disabled by default, no attribute or state holding items. Placer (`blockPlacer.enabled`): 68 sorted options without TNT or barrier; white wool placed (chest 8 → 7, *Placed* on, "placed minecraft:white_wool") and cleared (chest 8); `minecraft:redstone_lamp[lit=true]` keeps the select on `minecraft:redstone_lamp`; "NBT is never accepted", "TNT is never placed"; after a datapack and `/reload` 69 options without a restart, TNT still refused. NeoForge with Flan 26.2-1.12.8 in the wilderness: the placer places, the observer sends `block_entity` with `exportBlockEntityData`; claim lines not tried |
| 36 | PASS (both HA) | "Player Steve" (model *MineQTT Player*, via the bridge) with `binary_sensor.player_steve` (presence, the primary entity): ON/OFF follow, unavailable with the bridge; with `telemetry.players=FULL` *Health*, *Dimension* and *Position* appear (unknown while Steve is offline; a published full state reads 20.0, `minecraft:overworld` and `12, -60, -3` with x/y/z attributes, a quit's `OFF` leaves them unknown), narrowing to `PRESENCE` removes the three (`Removing component …`) and keeps the device and entity ids, a restart does not bring them back; `/mineqtt players forget Steve` removes the device and leaves nothing retained; a ghost presence config and a retained ghost state are cleared at `/mineqtt reconnect`; no Player device after a Home Assistant restart. A player typing `share on/off/full`, joining and quitting: the `privacy` and `telemetry` client suites |
| 37 | PASS (both HA) | A lamp placed in the room *Kitchen* creates the missing area on both; a known device without an area keeps none on 2026.10.0 and moves to *Kitchen* on 2025.2.5; an area set by hand stays on both; after `rooms rename Kitchen Cookhouse` existing devices keep *Kitchen* and a new lamp creates *Cookhouse*; hidden and exposed again: *Kitchen* on 2026.10.0, *Cookhouse* on 2025.2.5; a group lands in its room's area and keeps it when a member leaves; a room `kitchen` puts a new lamp into *Kitchen* without a second area. The General tab's hint: `rooms` client suite and `verifyDeviceTabs` |
| 38 | PASS (both HA) | Steps of the row: no entity and `broadcast disabled` by default; `text.world_broadcast` (max 255, configuration) after the key and a restart; `§4Hi`+U+202E shows `[MQTT] Hi` and logs its topic; five sets give three lines and two `rate limited`; Home Assistant refuses 256 characters, MQTT shows them; off with Home Assistant online removes the entity (stub, then plain message) and the other 35 entities keep their ids; off while it is stopped removes it once after its start; `discovery clear` and `republish` send no stub |
| 39 | PASS (both HA) | With the catalog, rooms typed `living room` and `livingroom` send `"sa":"Living Room"` and both lamps land in *Living Room*, no second area; with `twin.catalog=false` `"sa":"livingroom"` goes out and Home Assistant still picks *Living Room* (it ignores case and spaces itself) |
| 40 | PASS | Fabric (`upgrade07.sh lane`) with `ha.expose` ALL and OPT_IN on 2026.10.0 and 2025.2.5: 60 PASS lines each, `upgrade07: PASS`; all 10 existing entities keep registry id, entity id and device; ALL adds 7 devices (52 entities), OPT_IN only the 7 new bridge entities until `devices expose upgrade_alarm on`; no ghost after `mineqtt reconnect` or a Home Assistant restart. NeoForge 26.2.0.88 dedicated server: 59 PASS lines, `mineqtt.toml` gained `[blockObserver]`, `[rooms]`, `[telemetry]` and kept `[blockPlacer] enabled = false`. The sunrise NOTE (known limitation) on all five runs. Final 0.8.0 tree (no Home Assistant): Fabric lane 60 PASS lines and NeoForge 26.2.0.88 59 PASS lines, `upgrade07: PASS` with the same NOTE |
| Explorer Use | PASS (both HA) | The action path of *Use* (`devices action <lamp> state TOGGLE`) flips the light within 3 s; a lamp at 4096 80 4096, unloaded, queues `{"state":"ON"}`, Home Assistant shows `on`, and the lamp is on when `forceload add` loads it. The Devices tab itself: `explorer` client suite |
| Room switch | PASS (both HA) | `rooms switch Garage on` / `off` over two lamps: "Garage: 2 switched on" / "Garage: 2 switched off", both lights follow |
| Placer and observer without broker | PASS | On the upgraded lane, `blockPlacer.enabled=true` and `consumeItems=false`, the broker stopped (`RECONNECTING`): orange wool set in front of the observer makes the placer on the same topic place orange wool |
| Kept-area hint | PASS (both HA) | A lamp created in *Kitchen*, hidden, its room renamed *Cookhouse*, exposed: *Kitchen* on 2026.10.0, *Cookhouse* (created) on 2025.2.5; a lamp discovered without a room, then a room *Pantry* over it: no area on 2026.10.0, *Pantry* on 2025.2.5; in each case the General tab names the area Home Assistant was first told |
| Dashboard | PASS (both HA) | `python ha.py dashboard-check ../docs/dashboards/mineqtt-world.yaml` against a fresh world named `world` on each version: `OK` for all 9 ids, `dashboard-check: 9 of 9 entities OK`, exit 0, `sensor.world_version` `0.8.0`; the markdown card's template rendered by Home Assistant's template API reads "0 online. Nobody online shares their presence." on both. The cards themselves were not drawn (no browser on the test workstation) |
| 5 | PASS (2026.10.0) | `buildv1.sh` world with MQTT off, `devices.nbt` removed, `mineqtt_topic_states.json` and a v1 config written, v1's retained `Offline` and exact discovery for `home/kitchen`, `home/hall`, `home/garden`, `home/office` on the private broker: after the upgrade with Home Assistant online the kitchen, hall and office lights keep their entity registry ids, entity ids (`light.kitchen_mineqtt_rgb_led_rgb_led_home_kitchen` …) and device ids, the devices are *Kitchen*, *Hall* (Smart Lamp group, one member unloaded) and *Office* (unloaded, migrated) via the bridge with `ids` `[home_<topic>, mineqtt_<uuid32>]`; "Migrated the v1 Home Assistant discovery" for all four; no `homeassistant/light/…` config and no `minecraft-<clientId>/status` left; the `home/garage` link set the Output to 15 and back to 0, also with the broker stopped; `light.turn_off` switched the kitchen lamp off. 2025.2.5 rejects v1's light config, so there is nothing to keep there |
| 6 | PASS (2026.10.0) | A one-lamp v1 world (`home/porch`), its v1 config retained and its entity created, Home Assistant stopped for the upgrade: MineQTT migrated the discovery while Home Assistant was offline; after its start the same registry id, entity id and device (*Porch*, via the bridge), no second light |
| 7 | PASS (2026.10.0) | `home/garden`'s v1 config cleared before the upgrade (Home Assistant removed the entity): the new device config recreated `light.garden_mineqtt_rgb_led_rgb_led_home_garden` with the unique_id `home_garden` |

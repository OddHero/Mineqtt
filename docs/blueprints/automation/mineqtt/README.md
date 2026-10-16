# MineQTT Home Assistant blueprints

Home Assistant automation blueprints for MineQTT's digital twin (Home Assistant to Minecraft and back). This folder
is their home: Home Assistant imports them from here, and the end-to-end environment (`e2e/docker-compose.yml`)
mounts it read-only into both test instances at `/config/blueprints/automation/mineqtt/`.

| Blueprint | What it does | Status |
|---|---|---|
| `mineqtt_control.yaml` | Runs the service calls Minecraft asks for on `mineqtt/<bridgeId>/bridge/ha_request`, for entities (or entities of devices) labelled *MineQTT control* only, within a fixed list of domains, actions and data keys, one entity per request. Answers on `…/ha_response` (before it runs the call, so every request is answered) and writes accepted requests to the logbook. `/mineqtt twin ping` checks that it answers. | Written (M3-10), released with MineQTT 0.7.0 |
| `mineqtt_catalog.yaml` | Publishes the entities labelled *MineQTT read* or *MineQTT control* (or of devices with those labels) as a retained catalog under the `mqtt_statestream` base topic, one list per domain with whitelisted attributes and a status, with fresh snapshots, so Minecraft knows which entities it may show and their current values. Runs at Home Assistant's start, on registry changes, every 10 minutes and when a MineQTT server asks (`ha_sync`). | Written (M3-11), released with MineQTT 0.7.0 |

Both need Home Assistant 2025.2.0 or newer, the MQTT integration, and `mqtt_statestream` with the base topic
`ha_statestream` (the setup is in the wiki's
[Mirror Your House](https://github.com/OddHero/Mineqtt/wiki/Mirror-Your-House#set-up-home-assistant)).

In the end-to-end environment a blueprint becomes an automation without the UI:

```sh
cd e2e
python ha.py --url http://localhost:8123 automation from-blueprint mineqtt_control.yaml mineqtt_control '{}'
```

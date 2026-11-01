# node-red-contrib-mineqtt

Node-RED nodes for [MineQTT](https://github.com/OddHero/Mineqtt): every Minecraft smart device and its Home Assistant
counterpart as a node you can wire. This is the third way to automate a MineQTT world, next to links in the game and
Home Assistant automations: place a lamp node, pick a lamp in its settings, place a switch node, pick a switch, wire
them up.

Each node can stand for a **Minecraft device** (found on your MQTT broker) or a **Home Assistant entity** of a
compatible kind, so the same flow can mix both: a Minecraft switch that toggles a Zigbee lamp, a real motion sensor
that shows on a Display Panel in the game. A Minecraft lamp that MineQTT binds to a real lamp (a twin) follows it by
itself; a flow only needs one of the two.

## Nodes

| Node | Minecraft devices | Home Assistant entities | Input | Output |
|---|---|---|---|---|
| smart lamp | Smart Lamp | `light` | ON/OFF/TOGGLE, brightness 0-255, `{state, brightness, color, color_temp_kelvin, effect, transition}` | ON/OFF |
| signal beacon | Signal Beacon | `light` | like the lamp, a dye name, or an alarm state | ON/OFF |
| redstone output | Redstone Output | `switch`, `input_boolean`, `light`, `fan` | ON/OFF/TOGGLE, power 0-15 | ON/OFF |
| smart plug | Smart Plug | `switch`, `input_boolean`, `light`, `fan` | ON/OFF/TOGGLE | ON/OFF |
| redstone input | Redstone Input | `binary_sensor`, `input_boolean` | | pulses (TOGGLE) or true/false |
| presence sensor | Presence Sensor | `binary_sensor` | | true/false |
| climate sensor | Climate Sensor | `sensor` | | temperature, humidity, illuminance or all |
| wall switch | Wall Switch | `event`, `binary_sensor`, `button`, `input_button`, `input_boolean` | `left`/`right` presses it | presses (TOGGLE) |
| display panel | Display Panel | `text`, `input_text` | text, or `{message, color, glow, size}` | the message |
| timer | Interval Timer, Daylight Timer, Alarm Clock, Cron Timer | `event`, `button`, `input_button`, `timer` | `trigger`, ON/OFF | fires (TOGGLE) or armed |
| block observer | Block Observer | `sensor` | | block id, or changes |
| block placer | Block Placer | `button`, `input_button` | `place`, `clear`, a block id | placed true/false |
| container observer | Container Observer | `sensor` | | fill %, item count, empty, full or items |
| entity observer | Entity Observer | `binary_sensor`, `sensor` | | present, entity id or count |
| vibration sensor | Vibration Sensor (0.11) | `binary_sensor`, `event` | | true/false, or vibrations |
| speaker | Speaker (0.11) | `siren` | ON/OFF/TOGGLE, a tone, `{announce}` | ON/OFF |
| smart lock | Smart Lock (0.11) | `lock` | LOCK/UNLOCK/TOGGLE | locked/unlocked/open/jammed, attempts |
| alarm panel | Alarm Panel (0.11) | `alarm_control_panel` | arm_away, arm_home, disarm (code), trigger | alarm state, events |

Every output carries `msg.payload` (the main value), `msg.state` (the whole state), `msg.device` (`name`, `room`,
`type`, `source`) and, for events, `msg.event` and `msg.eventType`. Trigger-like outputs (switch presses, timer fires,
redstone pulses) send `TOGGLE` by default, so a switch node wired straight into a lamp node toggles the lamp; each such
node can send the event name instead. The state a node finds when it starts only sets its status: outputs fire on
later changes, so deploying never triggers a flow by itself.

## Setup

1. Install the package from the palette manager (or `npm install node-red-contrib-mineqtt` in your Node-RED folder,
   or in the Node-RED add-on of Home Assistant OS).
2. Add a **MineQTT broker** configuration: the broker your MineQTT servers use. Its user needs read access to
   `mineqtt/#` and `homeassistant/device/#` and write access to `mineqtt/+/+/set` and `mineqtt/+/+/set/#` (with a
   custom base topic or discovery prefix, use those). Deploy once; the device lists fill from the broker's retained
   messages.
3. For Home Assistant entities, add a **MineQTT Home Assistant** configuration. In the Node-RED add-on of Home
   Assistant OS, leave URL and token empty (it uses the Supervisor's connection); elsewhere enter the server's URL and
   a long-lived access token (your profile, Security tab).
4. Place device nodes, pick a device or entity in each, wire them up, deploy.

MineQTT publishes each device's name, type and room for Home Assistant discovery. On a dedicated server devices are
exposed to Home Assistant only on request (`ha.expose`); the nodes still find unexposed devices by their topic name and
guess their type from their state.

## Example

`examples/switch-toggles-lamp.json` (Import > Examples in the editor): a wall switch wired into a smart lamp.

## Development

```
npm install
npm test        # unit tests, the catalog against MineQTT's discovery goldens, and a flow in the real Node-RED runtime
npm run build   # regenerates nodes/devices.html from lib/types.js after a change to the node table
```

The node table is `lib/types.js`; `nodes/devices.js` registers one runtime node per entry and
`scripts/build-html.js` generates the editor part from the same table.

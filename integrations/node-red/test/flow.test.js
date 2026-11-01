'use strict';

// The real Node-RED runtime with an in-process MQTT broker: a wall switch node wired into a smart lamp node, as a
// user would wire them in the editor. A press published on the switch's action topic must arrive as a TOGGLE on the
// lamp's command topic, and a lamp state change must come out of the lamp node.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const net = require('net');
const path = require('path');
const helper = require('node-red-node-test-helper');
const mqtt = require('mqtt');
const configNodes = require('../nodes/config.js');
const deviceNodes = require('../nodes/devices.js');

helper.init(require.resolve('node-red'));

const GOLDEN = path.join(__dirname, '..', '..', '..', 'common', 'src', 'sensorChecks', 'resources', 'golden');
const golden = (name) => JSON.parse(fs.readFileSync(path.join(GOLDEN, name), 'utf8'));

function waitFor(cond, ms = 5000) {
    return new Promise((resolve, reject) => {
        const start = Date.now();
        const tick = () => {
            if (cond()) resolve();
            else if (Date.now() - start > ms) reject(new Error('timed out'));
            else setTimeout(tick, 20);
        };
        tick();
    });
}

test('a switch press toggles the lamp through the flow', async (t) => {
    const { Aedes } = await import('aedes').then((m) => ({ Aedes: m.Aedes || m.default }));
    const aedes = await (Aedes.createBroker ? Aedes.createBroker() : new Aedes());
    const server = net.createServer(aedes.handle);
    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    const port = server.address().port;
    await new Promise((r) => helper.startServer(r));

    const lamp = golden('lamp_room.json');
    const sw = golden('wall_switch.json');
    const client = mqtt.connect(`mqtt://127.0.0.1:${port}`);
    await new Promise((r) => client.on('connect', r));
    const retain = { qos: 1, retain: true };
    await client.publishAsync(`homeassistant/device/${lamp.dev.ids[0]}/config`, JSON.stringify(lamp), retain);
    await client.publishAsync(`homeassistant/device/${sw.dev.ids[0]}/config`, JSON.stringify(sw), retain);
    await client.publishAsync('mineqtt/world_7f3a/kitchen_lamp', JSON.stringify({ state: 'OFF', brightness: 255, location: {} }), retain);
    const commands = [];
    await client.subscribeAsync('mineqtt/world_7f3a/+/set');
    client.on('message', (topic, payload) => commands.push([topic, payload.toString()]));

    t.after(async () => {
        await helper.unload();
        await new Promise((r) => helper.stopServer(r));
        client.end(true);
        await new Promise((r) => aedes.close(r));
        server.close();
    });

    const flow = [
        { id: 'b', type: 'mineqtt-broker', url: `mqtt://127.0.0.1:${port}`, baseTopic: 'mineqtt', discoveryPrefix: 'homeassistant' },
        { id: 's', type: 'mineqtt-wall-switch', source: 'mineqtt', broker: 'b', device: sw.dev.ids[0], button: 'any', events: 'press', payloadMode: 'toggle', wires: [['l']] },
        { id: 'l', type: 'mineqtt-smart-lamp', source: 'mineqtt', broker: 'b', device: lamp.dev.ids[0], otherInput: 'toggle', wires: [['out']] },
        { id: 'out', type: 'helper' },
        // The same lamp, set to also send the state it finds at the start (e.g. for a display).
        { id: 'l2', type: 'mineqtt-smart-lamp', source: 'mineqtt', broker: 'b', device: lamp.dev.ids[0], initial: 'yes', wires: [['out2']] },
        { id: 'out2', type: 'helper' },
    ];
    await helper.load([configNodes, deviceNodes], flow);
    const atStart = [];
    helper.getNode('out2').on('input', (msg) => atStart.push(msg));
    const broker = helper.getNode('b');
    await waitFor(() => broker.connected && broker.catalog.get(lamp.dev.ids[0]) && broker.catalog.get(sw.dev.ids[0])
        && broker.catalog.get(lamp.dev.ids[0]).state);

    const out = helper.getNode('out');
    const outputs = [];
    out.on('input', (msg) => outputs.push(msg));

    await client.publishAsync('mineqtt/world_7f3a/hall_switch/action', JSON.stringify({ event_type: 'hold', button: 'left' }));
    await client.publishAsync('mineqtt/world_7f3a/hall_switch/action', JSON.stringify({ event_type: 'press', button: 'left' }));
    await waitFor(() => commands.length >= 1);
    await new Promise((r) => setTimeout(r, 200));
    assert.deepStrictEqual(commands, [['mineqtt/world_7f3a/kitchen_lamp/set', '{"state":"TOGGLE"}']], 'only the press, not the hold');

    // MineQTT answers with the new state: the lamp node reports the change (the startup state did not count).
    assert.strictEqual(outputs.length, 0);
    await client.publishAsync('mineqtt/world_7f3a/kitchen_lamp', JSON.stringify({ state: 'ON', brightness: 255, location: {} }), retain);
    await waitFor(() => outputs.length >= 1);
    assert.strictEqual(outputs[0].payload, 'ON');
    assert.strictEqual(outputs[0].device.name, 'Kitchen Lamp');
    assert.strictEqual(outputs[0].device.room, 'Kitchen');
    assert.strictEqual(outputs[0].state.location, undefined);

    // "At start: send the current state": the retained OFF first, then the change, each once.
    await waitFor(() => atStart.length >= 2);
    assert.deepStrictEqual(atStart.map((m) => m.payload), ['OFF', 'ON']);
    assert.strictEqual(atStart[0].state.location, undefined);
});

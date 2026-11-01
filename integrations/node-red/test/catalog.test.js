'use strict';

// The catalog against MineQTT's own discovery goldens (common/src/sensorChecks/resources/golden), so a change to the
// discovery format that breaks the nodes fails here.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { Catalog, guessType } = require('../lib/catalog');

const GOLDEN = path.join(__dirname, '..', '..', '..', 'common', 'src', 'sensorChecks', 'resources', 'golden');
const golden = (name) => JSON.parse(fs.readFileSync(path.join(GOLDEN, name), 'utf8'));
const discover = (cat, msg) => cat.handle(`homeassistant/device/${msg.dev.ids[0]}/config`, JSON.stringify(msg));

test('every device golden yields its type, name and topics', () => {
    const skip = new Set(['bridge.json', 'bridge_broadcast.json', 'player_presence.json', 'player_presence_full.json', 'm4_seams.json']);
    const files = fs.readdirSync(GOLDEN).filter((f) => f.endsWith('.json') && !skip.has(f));
    assert.ok(files.length >= 25, `only ${files.length} goldens`);
    for (const f of files) {
        const msg = golden(f);
        const cat = new Catalog();
        discover(cat, msg);
        const rec = cat.get(msg.dev.ids[0]);
        assert.ok(rec, `${f}: not catalogued`);
        assert.strictEqual(rec.typeId, msg.dev.mdl_id, f);
        assert.strictEqual(rec.name, msg.dev.name, f);
        assert.strictEqual(rec.bridgeId, 'world_7f3a', f);
        assert.match(rec.slug, /^[a-z0-9_-]+$/, f);
        assert.strictEqual(cat.commandTopic(rec.key), `mineqtt/world_7f3a/${rec.group ? 'group/' : ''}${rec.slug}/set`, f);
    }
});

test('room, state, availability and events reach the device by its stable key', () => {
    const cat = new Catalog();
    const lamp = golden('lamp_room.json');
    const sw = golden('wall_switch.json');
    discover(cat, lamp);
    discover(cat, sw);
    const key = lamp.dev.ids[0];
    assert.strictEqual(cat.get(key).room, 'Kitchen');
    const seen = [];
    cat.on((event, k, data) => seen.push([event, k, data]));
    cat.handle('mineqtt/world_7f3a/kitchen_lamp', JSON.stringify({ state: 'ON', brightness: 200, location: {} }));
    cat.handle('mineqtt/world_7f3a/kitchen_lamp/availability', 'offline');
    cat.handle('mineqtt/world_7f3a/hall_switch/action', JSON.stringify({ event_type: 'press', button: 'left' }));
    cat.handle('mineqtt/world_7f3a/kitchen_lamp/set', JSON.stringify({ state: 'OFF' })); // a command, not a state
    assert.deepStrictEqual(seen.map(([e]) => e), ['state', 'availability', 'event']);
    assert.strictEqual(cat.get(key).state.brightness, 200);
    assert.strictEqual(cat.get(key).available, false);
    assert.deepStrictEqual(seen[2][2], { event_type: 'press', button: 'left' });
    assert.strictEqual(seen[2][1], sw.dev.ids[0]);
});

test('a rename moves the topics but keeps the key', () => {
    const cat = new Catalog();
    const lamp = golden('lamp_room.json');
    discover(cat, lamp);
    const renamed = JSON.parse(JSON.stringify(lamp).replace(/kitchen_lamp/g, 'pantry_lamp').replace('"Kitchen Lamp"', '"Pantry Lamp"'));
    discover(cat, renamed);
    assert.strictEqual(cat.commandTopic(lamp.dev.ids[0]), 'mineqtt/world_7f3a/pantry_lamp/set');
    assert.strictEqual(cat.get(lamp.dev.ids[0]).name, 'Pantry Lamp');
    assert.strictEqual(cat.list().length, 1);
});

test('devices without discovery are listed by topic, with a guessed type, and adopted by later discovery', () => {
    const cat = new Catalog();
    cat.handle('mineqtt/srv/smart_lamp_3f9c', JSON.stringify({ state: 'OFF', brightness: 255, color_mode: 'rgb', color: { r: 255, g: 255, b: 255 }, effect: 'off', light_level: 0, location: {} }));
    cat.handle('mineqtt/srv/smart_lamp_3f9c/availability', 'online');
    cat.handle('mineqtt/srv/bridge', JSON.stringify({ players_online: 0 })); // telemetry, not a device
    cat.handle('mineqtt/srv/bridge/state', 'online');
    const [d] = cat.list(['mineqtt:smart_lamp']);
    assert.strictEqual(d.key, 'topic:mineqtt/srv/smart_lamp_3f9c');
    assert.strictEqual(d.typeId, 'mineqtt:smart_lamp');
    assert.strictEqual(cat.list().length, 1);
    const msg = JSON.parse(JSON.stringify(golden('lamp.json')).replace(/world_7f3a/g, 'srv'));
    const slug = msg.avty[1].t.split('/')[2];
    const moved = JSON.parse(JSON.stringify(msg).replace(new RegExp(slug, 'g'), 'smart_lamp_3f9c'));
    discover(cat, moved);
    assert.strictEqual(cat.list().length, 1);
    assert.strictEqual(cat.get(moved.dev.ids[0]).state.state, 'OFF');
});

test('guessType knows the field sets of the device types', () => {
    assert.strictEqual(guessType({ powered: true, signal: 11, location: {} }), 'mineqtt:redstone_input');
    assert.strictEqual(guessType({ location: {} }), 'mineqtt:wall_switch');
    assert.strictEqual(guessType({ locked: true, relock: 0, state: 'locked' }), 'mineqtt:smart_lock');
    assert.strictEqual(guessType({ foo: 1 }), null);
});

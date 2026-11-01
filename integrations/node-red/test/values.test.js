'use strict';

const test = require('node:test');
const assert = require('node:assert');
const V = require('../lib/values');
const { TYPES } = require('../lib/types');

const type = (id) => TYPES.find((t) => t.id === id);

test('switch words', () => {
    assert.strictEqual(V.switchCommand(true), 'ON');
    assert.strictEqual(V.switchCommand('off'), 'OFF');
    assert.strictEqual(V.switchCommand(' Toggle '), 'TOGGLE');
    assert.strictEqual(V.switchCommand({ on: false }), 'OFF');
    assert.strictEqual(V.switchCommand('press'), null);
    assert.strictEqual(V.fallbackSwitch(undefined), 'TOGGLE');
    assert.strictEqual(V.fallbackSwitch('ignore'), null);
});

test('light commands', () => {
    assert.deepStrictEqual(V.lightCommand('TOGGLE'), { state: 'TOGGLE' });
    assert.deepStrictEqual(V.lightCommand(128), { state: 'ON', brightness: 128 });
    assert.deepStrictEqual(V.lightCommand(0), { state: 'OFF' });
    assert.deepStrictEqual(V.lightCommand({ rgb_color: [1, 2, 3] }), { state: 'ON', color: { r: 1, g: 2, b: 3 } });
    assert.deepStrictEqual(V.lightCommand({ state: false }), { state: 'OFF' });
    assert.strictEqual(V.lightCommand({ foo: 1 }), null);
    assert.deepStrictEqual(V.lightServiceData({ color: '#ff8000', brightness: 10 }), { brightness: 10, rgb_color: [255, 128, 0] });
    assert.deepStrictEqual(V.lightServiceData({ color_temp: 370 }), { color_temp_kelvin: 2703 });
});

test('a switch event wired into a lamp toggles it', () => {
    const lamp = type('smart-lamp');
    const sw = type('wall-switch');
    const { triggerPayload } = require('../lib/types');
    const payload = triggerPayload({ payloadMode: 'toggle' }, 'press');
    assert.deepStrictEqual(lamp.command({ payload }, { otherInput: 'toggle' }), { state: 'TOGGLE' });
    assert.ok(sw.eventFilter({ event_type: 'press', button: 'left' }, { button: 'any', events: 'press' }));
    assert.ok(!sw.eventFilter({ event_type: 'hold', button: 'left' }, { button: 'any', events: 'press' }));
    assert.ok(!sw.eventFilter({ event_type: 'press', button: 'right' }, { button: 'left', events: 'press' }));
});

test('device commands match the MineQTT contract', () => {
    assert.deepStrictEqual(type('redstone-output').command({ payload: 9 }, {}), { power: 9 });
    assert.deepStrictEqual(type('smart-lock').command({ payload: 'unlock' }, {}), { locked: false });
    assert.deepStrictEqual(type('smart-lock').command({ payload: 'TOGGLE' }, {}), { toggle: 'PRESS' });
    assert.deepStrictEqual(type('alarm-panel').command({ payload: 'disarm' }, { code: '1234' }), { action: 'DISARM', code: '1234' });
    assert.deepStrictEqual(type('alarm-panel').command({ payload: 'arm_away', code: 99 }, {}), { action: 'ARM_AWAY', code: '99' });
    assert.strictEqual(type('alarm-panel').command({ payload: 'dance' }, {}), null);
    assert.deepStrictEqual(type('signal-beacon').command({ payload: 'triggered' }, {}), { alarm: 'triggered' });
    assert.deepStrictEqual(type('signal-beacon').command({ payload: 'light_blue' }, {}), { state: 'ON', color: 'light_blue' });
    assert.deepStrictEqual(type('display-panel').command({ payload: 21.5 }, {}), { message: '21.5' });
    assert.deepStrictEqual(type('display-panel').command({ payload: 'Ding dong!\\nDoor' }, {}), { message: 'Ding dong!\nDoor' });
    assert.deepStrictEqual(type('timer').command({ payload: 'trigger' }, {}), { trigger: 'PRESS' });
    assert.deepStrictEqual(type('speaker').command({ payload: 'doorbell' }, {}), { state: 'ON', tone: 'doorbell' });
    assert.deepStrictEqual(type('block-placer').command({ payload: 'minecraft:stone' }, {}), { block: 'minecraft:stone', place: 'PRESS' });
    assert.deepStrictEqual(type('block-placer').command({ payload: 'TOGGLE' }, {}, { state: { placed: true } }), { clear: 'PRESS' });
    assert.deepStrictEqual(type('wall-switch').command({ payload: 'right' }, {}), { press_right: 'PRESS' });
});

test('Home Assistant commands', () => {
    const lamp = type('smart-lamp').ha;
    assert.deepStrictEqual(lamp.command({ payload: 'TOGGLE' }, {}, 'light.x'), { service: 'light.toggle', data: {} });
    assert.deepStrictEqual(lamp.command({ payload: { brightness: 50 } }, {}, 'light.x'), { service: 'light.turn_on', data: { brightness: 50 } });
    assert.deepStrictEqual(type('smart-plug').ha.command({ payload: 'ON' }, {}, 'input_boolean.y'), { service: 'input_boolean.turn_on', data: {} });
    assert.deepStrictEqual(type('smart-lock').ha.command({ payload: 'TOGGLE' }, {}, 'lock.z', { state: 'locked' }), { service: 'lock.unlock', data: {} });
    assert.deepStrictEqual(type('display-panel').ha.command({ payload: 'hi' }, {}, 'input_text.t'), { service: 'input_text.set_value', data: { value: 'hi' } });
    assert.deepStrictEqual(type('block-placer').ha.command({ payload: 'x' }, {}, 'button.b'), { service: 'button.press', data: {} });
    assert.deepStrictEqual(type('alarm-panel').ha.command({ payload: 'disarm' }, { code: '42' }, 'alarm_control_panel.a'), { service: 'alarm_control_panel.alarm_disarm', data: { code: '42' } });
});

test('every type is complete', () => {
    for (const t of TYPES) {
        assert.ok(t.typeIds.length && t.haDomains.length && t.help && t.statePayload, t.id);
        if (t.inputs) assert.ok(typeof t.command === 'function', `${t.id}: input without command`);
        assert.ok(t.ha && t.ha.payload, `${t.id}: no Home Assistant payload`);
    }
    const covered = new Set(TYPES.flatMap((t) => t.typeIds));
    for (const id of ['smart_lamp', 'redstone_output', 'redstone_input', 'presence_sensor', 'smart_plug', 'climate_sensor',
        'wall_switch', 'display_panel', 'interval_timer', 'game_time_timer', 'alarm_clock', 'cron_timer', 'block_observer',
        'block_placer', 'container_observer', 'entity_observer', 'vibration_sensor', 'speaker', 'smart_lock', 'alarm_panel',
        'signal_beacon']) {
        assert.ok(covered.has(`mineqtt:${id}`), `no node for mineqtt:${id}`);
    }
});

'use strict';

// One entry per MineQTT device type with a twin: the palette node, which MineQTT types and Home Assistant domains it
// accepts, how an input message becomes a command, and what it sends on its output. nodes/devices.js registers a
// runtime node per entry and scripts/build-html.js generates the editor part (nodes/devices.html) from the same
// table, so the two cannot drift apart.
//
// Conventions for every node:
//   - Output messages: msg.payload is the node's main value (ON/OFF, a number, an event), msg.state the whole state
//     (MineQTT's state JSON without location, or the Home Assistant state in the same shape where it makes sense),
//     msg.device {key, name, room, type, source}. Events carry msg.event (the event object) and msg.eventType.
//   - Trigger-like outputs (switch presses, timer fires, pulses) send "TOGGLE" by default, so wiring a switch node
//     straight into a lamp node toggles the lamp. The node's "Output" option switches to the event name instead.
//   - Inputs accept the natural payloads (ON/OFF/TOGGLE, true/false, numbers, objects); anything else follows the
//     node's "Other input" option where it has one.

const V = require('./values');

const TRIGGER_PAYLOAD = {
    name: 'payloadMode', label: 'Output', type: 'select', default: 'toggle',
    values: [['toggle', 'TOGGLE (toggles a lamp or switch it is wired to)'], ['event', 'event name'], ['true', 'true']],
};
const OTHER_INPUT = {
    name: 'otherInput', label: 'Other input', type: 'select', default: 'toggle',
    values: [['toggle', 'toggles'], ['on', 'switches on'], ['off', 'switches off'], ['ignore', 'is ignored']],
};
const EMIT = (values, def) => ({
    name: 'emit', label: 'Send', type: 'select', default: def,
    values: values.map((v) => [v, { state: 'state changes', event: 'events', both: 'state changes and events' }[v]]),
});

// Whether a node that sends state changes also sends the state it finds when it starts (a deploy, a reconnect), e.g. for
// a display that shows a sensor's value. Off by default, so a switch wired into a lamp does not switch it at a deploy.
const INITIAL = {
    name: 'initial', label: 'At start', type: 'select', default: 'no',
    values: [['no', 'send nothing (only changes)'], ['yes', 'send the current state']],
};

function triggerPayload(opt, eventType) {
    switch (opt.payloadMode) {
        case 'event': return eventType;
        case 'true': return true;
        default: return 'TOGGLE';
    }
}

function switchOrFallback(payload, opt) {
    return V.switchCommand(payload) || V.fallbackSwitch(opt.otherInput);
}

const ON_OFF_SERVICES = (domain, word) => ({
    service: `${domain}.${word === 'ON' ? 'turn_on' : word === 'OFF' ? 'turn_off' : 'toggle'}`, data: {},
});

/** Home Assistant entities that act as a trigger: event and button entities on every new event, binary sensors when
 *  they turn on. Returns the event name, or undefined when the change is no trigger. */
function haTrigger(entity, prev) {
    const domain = entity.entity_id.split('.')[0];
    if (!prev || prev.state === entity.state) return undefined;
    if (entity.state === 'unavailable' || entity.state === 'unknown') return undefined;
    if (domain === 'event') return (entity.attributes && entity.attributes.event_type) || 'event';
    if (domain === 'button' || domain === 'input_button') return 'press';
    if (domain === 'binary_sensor' || domain === 'input_boolean' || domain === 'switch') {
        return entity.state === 'on' ? 'on' : undefined;
    }
    return 'changed';
}

const ALARM_ACTIONS = {
    arm_away: 'ARM_AWAY', away: 'ARM_AWAY', arm_home: 'ARM_HOME', home: 'ARM_HOME',
    disarm: 'DISARM', off: 'DISARM', trigger: 'TRIGGER', panic: 'TRIGGER',
};
const ALARM_STATES = new Set(['disarmed', 'arming', 'pending', 'disarming', 'armed_home', 'armed_away',
    'armed_night', 'armed_vacation', 'armed_custom_bypass', 'triggered']);
const TONES = ['alarm', 'chime', 'doorbell', 'beep', 'bell'];
const TIMER_TYPES = ['mineqtt:interval_timer', 'mineqtt:game_time_timer', 'mineqtt:alarm_clock', 'mineqtt:cron_timer'];

const TYPES = [
    {
        id: 'smart-lamp', label: 'smart lamp', typeIds: ['mineqtt:smart_lamp'], haDomains: ['light'],
        color: '#F3D35B', icon: 'font-awesome/fa-lightbulb-o', inputs: 1, emits: ['state'],
        options: [OTHER_INPUT],
        help: 'A Smart Lamp, or any Home Assistant light. Input: ON, OFF, TOGGLE, true/false, a brightness 0-255, or '
            + 'an object with state, brightness, color ({r,g,b}, "#rrggbb" or a dye name), color_temp_kelvin, effect and '
            + 'transition. Output: ON/OFF, msg.state holds brightness and colour.',
        command: (msg, opt) => V.lightCommand(msg.payload) || wordCommand(V.fallbackSwitch(opt.otherInput)),
        statePayload: (s) => s.state,
        ha: {
            command(msg, opt) {
                const cmd = V.lightCommand(msg.payload) || wordCommand(V.fallbackSwitch(opt.otherInput));
                if (!cmd) return null;
                if (cmd.state === 'OFF') return { service: 'light.turn_off', data: cmd.transition !== undefined ? { transition: Number(cmd.transition) } : {} };
                const data = V.lightServiceData(cmd);
                if (cmd.state === 'TOGGLE') return { service: 'light.toggle', data };
                return { service: 'light.turn_on', data };
            },
            state: (e) => V.lightFromHa(e),
            payload: (e) => (e.state === 'on' ? 'ON' : 'OFF'),
        },
    },
    {
        id: 'signal-beacon', label: 'signal beacon', typeIds: ['mineqtt:signal_beacon'], haDomains: ['light'],
        color: '#E2725B', icon: 'font-awesome/fa-flag', inputs: 1, emits: ['state'],
        options: [OTHER_INPUT],
        help: 'A Signal Beacon (its beam colour snaps to the 16 dyes). Input: like the smart lamp, a dye name such as '
            + '"light_blue", or an alarm state (disarmed, armed_away, triggered, ...) to show it as a colour; wire an alarm '
            + 'panel node into it. Output: ON/OFF, msg.state.dye.',
        command(msg, opt) {
            const p = msg.payload;
            if (typeof p === 'string' && ALARM_STATES.has(p.trim().toLowerCase())) return { alarm: p.trim().toLowerCase() };
            if (typeof p === 'string' && /^[a-z_]+$/.test(p.trim()) && !V.switchCommand(p)) return { state: 'ON', color: p.trim() };
            return V.lightCommand(p) || wordCommand(V.fallbackSwitch(opt.otherInput));
        },
        statePayload: (s) => s.state,
        ha: {
            command(msg, opt) {
                const cmd = V.lightCommand(msg.payload) || wordCommand(V.fallbackSwitch(opt.otherInput));
                if (!cmd) return null;
                if (cmd.state === 'OFF') return { service: 'light.turn_off', data: {} };
                return { service: cmd.state === 'TOGGLE' ? 'light.toggle' : 'light.turn_on', data: V.lightServiceData(cmd) };
            },
            state: (e) => V.lightFromHa(e),
            payload: (e) => (e.state === 'on' ? 'ON' : 'OFF'),
        },
    },
    {
        id: 'redstone-output', label: 'redstone output', typeIds: ['mineqtt:redstone_output'],
        haDomains: ['switch', 'input_boolean', 'light', 'fan'],
        color: '#D9534F', icon: 'font-awesome/fa-toggle-on', inputs: 1, emits: ['state'],
        options: [OTHER_INPUT],
        help: 'A Redstone Output, or a Home Assistant switch. Input: ON, OFF, TOGGLE, true/false, or a power level '
            + '0-15 (Minecraft only). Output: ON/OFF, msg.state.power holds the level.',
        command(msg, opt) {
            if (typeof msg.payload === 'number' && msg.payload > 1) return { power: Math.max(0, Math.min(15, Math.round(msg.payload))) };
            const w = switchOrFallback(msg.payload, opt);
            return w ? { state: w } : null;
        },
        statePayload: (s) => s.state,
        ha: haOnOff(),
    },
    {
        id: 'smart-plug', label: 'smart plug', typeIds: ['mineqtt:smart_plug'], haDomains: ['switch', 'input_boolean', 'light', 'fan'],
        color: '#5BC0DE', icon: 'font-awesome/fa-plug', inputs: 1, emits: ['state'],
        options: [OTHER_INPUT],
        help: 'A Smart Plug, or any Home Assistant switch. Input: ON, OFF, TOGGLE or true/false. Output: ON/OFF.',
        command(msg, opt) { const w = switchOrFallback(msg.payload, opt); return w ? { state: w } : null; },
        statePayload: (s) => s.state,
        ha: haOnOff(),
    },
    {
        id: 'redstone-input', label: 'redstone input', typeIds: ['mineqtt:redstone_input'], haDomains: ['binary_sensor', 'input_boolean'],
        color: '#D9534F', icon: 'font-awesome/fa-sign-in', inputs: 0, emits: ['state', 'event', 'both'], defaultEmit: 'event',
        events: ['pulse'], options: [TRIGGER_PAYLOAD],
        help: 'A Redstone Input (a redstone signal in the world), or a Home Assistant binary sensor. Events: one message '
            + 'per rising edge ("pulse"). State changes: true/false, msg.state.signal holds the level 0-15.',
        statePayload: (s) => !!s.powered,
        ha: { state: (e) => ({ powered: e.state === 'on' }), payload: (e) => e.state === 'on', trigger: haTrigger },
    },
    {
        id: 'presence-sensor', label: 'presence sensor', typeIds: ['mineqtt:presence_sensor'], haDomains: ['binary_sensor'],
        color: '#7DB46C', icon: 'font-awesome/fa-male', inputs: 0, emits: ['state'],
        help: 'A Presence Sensor, or a Home Assistant occupancy/motion sensor. Output: true while someone is there, '
            + 'msg.state.count holds how many.',
        statePayload: (s) => !!s.occupancy,
        ha: { state: (e) => ({ occupancy: e.state === 'on' }), payload: (e) => e.state === 'on' },
    },
    {
        id: 'climate-sensor', label: 'climate sensor', typeIds: ['mineqtt:climate_sensor'], haDomains: ['sensor'],
        color: '#7DB46C', icon: 'font-awesome/fa-thermometer-half', inputs: 0, emits: ['state'],
        options: [{
            name: 'measure', label: 'Value', type: 'select', default: 'temperature',
            values: [['temperature', 'temperature (°C)'], ['humidity', 'humidity (%)'], ['illuminance', 'illuminance (lx)'], ['all', 'all (object)']],
        }],
        help: 'A Climate Sensor, or a Home Assistant sensor. Output: the chosen value as a number (Minecraft) or the '
            + 'sensor\'s state (Home Assistant); msg.state holds all readings.',
        statePayload: (s, opt) => (opt.measure === 'all' ? V.stripState(s) : s[opt.measure || 'temperature']),
        changeKey: (s, opt) => (opt.measure === 'all' ? JSON.stringify(V.stripState(s)) : s[opt.measure || 'temperature']),
        ha: { state: (e) => ({ value: V.haNumber(e.state), unit: e.attributes && e.attributes.unit_of_measurement }), payload: (e) => V.haNumber(e.state) },
    },
    {
        id: 'wall-switch', label: 'wall switch', typeIds: ['mineqtt:wall_switch'],
        haDomains: ['event', 'binary_sensor', 'input_button', 'button', 'input_boolean'],
        color: '#A6BBCF', icon: 'font-awesome/fa-hand-pointer-o', inputs: 1, emits: ['event'],
        events: ['press', 'double_press', 'hold'],
        options: [
            { name: 'button', label: 'Button', type: 'select', default: 'any', values: [['any', 'any'], ['left', 'left'], ['right', 'right']] },
            { name: 'events', label: 'Press', type: 'select', default: 'press', values: [['press', 'short press'], ['double_press', 'double press'], ['hold', 'hold'], ['any', 'any']] },
            TRIGGER_PAYLOAD,
        ],
        help: 'A Wall Switch, or a Home Assistant button, event entity (e.g. a Zigbee remote) or binary sensor. Output: '
            + 'one message per press of the chosen button and press type, TOGGLE by default; msg.button says which '
            + 'button. Input (Minecraft only): "left" or "right" presses that button as if a player had.',
        command(msg) {
            const p = typeof msg.payload === 'string' ? msg.payload.trim().toLowerCase() : msg.payload;
            if (p === 'right') return { press_right: 'PRESS' };
            if (p === 'both') return { press_left: 'PRESS', press_right: 'PRESS' };
            return { press_left: 'PRESS' };
        },
        eventFilter: (evt, opt) => (opt.button === 'any' || !opt.button || evt.button === opt.button)
            && (opt.events === 'any' || evt.event_type === (opt.events || 'press')),
        statePayload: () => undefined,
        ha: { command: haPress, trigger: haTrigger, state: (e) => ({ state: e.state, attributes: e.attributes }), payload: (e) => e.state },
    },
    {
        id: 'display-panel', label: 'display panel', typeIds: ['mineqtt:display_panel'], haDomains: ['text', 'input_text'],
        color: '#C0C0C0', icon: 'font-awesome/fa-television', inputs: 1, emits: ['state'],
        help: 'A Display Panel, or a Home Assistant text entity. Input: the text to show (up to 255 characters; a new '
            + 'line, or \\n typed in a text field, starts a new line); an object may also set color (dye name), glow and '
            + 'size (Minecraft). Output: the message.',
        command(msg) {
            const p = msg.payload;
            if (p && typeof p === 'object' && !Array.isArray(p)) {
                const out = {};
                for (const k of ['message', 'color', 'glow', 'size']) if (p[k] !== undefined) out[k] = p[k];
                if (out.message !== undefined) out.message = displayText(out.message);
                return Object.keys(out).length ? out : null;
            }
            if (p === undefined || p === null) return null;
            return { message: displayText(p) };
        },
        statePayload: (s) => s.message,
        ha: {
            command(msg, opt, entity) {
                const p = msg.payload;
                const text = p && typeof p === 'object' ? p.message : p;
                if (text === undefined || text === null) return null;
                return { service: `${entity.split('.')[0]}.set_value`, data: { value: displayText(text) } };
            },
            state: (e) => ({ message: e.state }), payload: (e) => e.state,
        },
    },
    {
        id: 'timer', label: 'timer', typeIds: TIMER_TYPES, haDomains: ['event', 'input_button', 'button', 'timer'],
        color: '#E9967A', icon: 'font-awesome/fa-clock-o', inputs: 1, emits: ['event', 'state', 'both'], defaultEmit: 'event',
        events: ['fired'], options: [TRIGGER_PAYLOAD],
        help: 'An Interval Timer, Daylight Timer, Alarm Clock or Cron Timer, or a Home Assistant event/button/timer. '
            + 'Events: one message each time it fires (TOGGLE by default). State changes: true/false (armed), '
            + 'msg.state.next_fire. Input (Minecraft): "trigger" fires it now, ON/OFF/TOGGLE arms or disarms it.',
        command(msg) {
            const p = msg.payload;
            if (typeof p === 'string' && ['trigger', 'press', 'fire'].includes(p.trim().toLowerCase())) return { trigger: 'PRESS' };
            const w = V.switchCommand(p);
            return w ? { state: w } : { trigger: 'PRESS' };
        },
        statePayload: (s) => !!s.enabled,
        ha: { command: haPress, trigger: haTrigger, state: (e) => ({ state: e.state }), payload: (e) => e.state },
    },
    {
        id: 'block-observer', label: 'block observer', typeIds: ['mineqtt:block_observer'], haDomains: ['sensor'],
        color: '#8FBC8F', icon: 'font-awesome/fa-cube', inputs: 0, emits: ['state', 'event', 'both'], defaultEmit: 'state',
        events: ['changed'], options: [TRIGGER_PAYLOAD],
        help: 'A Block Observer, or a Home Assistant sensor. State changes: the block id it watches (e.g. '
            + '"minecraft:stone"), msg.state.properties holds its block state. Events: one message per change.',
        statePayload: (s) => s.block,
        ha: { trigger: haTrigger, state: (e) => ({ block: e.state }), payload: (e) => e.state },
    },
    {
        id: 'block-placer', label: 'block placer', typeIds: ['mineqtt:block_placer'], haDomains: ['button', 'input_button'],
        color: '#8FBC8F', icon: 'font-awesome/fa-cubes', inputs: 1, emits: ['state'],
        help: 'A Block Placer (the server must enable it), or a Home Assistant button. Input: "place" or "clear", a '
            + 'block id such as "minecraft:white_wool" (placed at once), or TOGGLE/ON/OFF (place/clear by its state). '
            + 'Output: true while its block is placed.',
        command(msg, opt, rec) {
            const p = typeof msg.payload === 'string' ? msg.payload.trim() : msg.payload;
            if (p && typeof p === 'object') return p;
            if (typeof p === 'string' && p.includes(':')) return { block: p, place: 'PRESS' };
            if (typeof p === 'string' && p.toLowerCase() === 'clear') return { clear: 'PRESS' };
            const w = V.switchCommand(p);
            if (w === 'OFF') return { clear: 'PRESS' };
            if (w === 'TOGGLE' && rec && rec.state && rec.state.placed) return { clear: 'PRESS' };
            return { place: 'PRESS' };
        },
        statePayload: (s) => !!s.placed,
        ha: { command: haPress, state: (e) => ({ state: e.state }), payload: (e) => e.state },
    },
    {
        id: 'container-observer', label: 'container observer', typeIds: ['mineqtt:container_observer'], haDomains: ['sensor'],
        color: '#DEB887', icon: 'font-awesome/fa-archive', inputs: 0, emits: ['state'],
        options: [{
            name: 'measure', label: 'Value', type: 'select', default: 'fill',
            values: [['fill', 'fill level (%)'], ['total', 'item count'], ['empty', 'empty (true/false)'], ['full', 'full (true/false)'], ['items', 'items (object)']],
        }],
        help: 'A Container Observer (a chest, barrel, ...), or a Home Assistant sensor. Output: the chosen value; '
            + 'msg.state.items maps item ids to counts.',
        statePayload: (s, opt) => s[opt.measure || 'fill'],
        changeKey: (s, opt) => JSON.stringify(s[opt.measure || 'fill']),
        ha: { state: (e) => ({ value: V.haNumber(e.state) }), payload: (e) => V.haNumber(e.state) },
    },
    {
        id: 'entity-observer', label: 'entity observer', typeIds: ['mineqtt:entity_observer'], haDomains: ['binary_sensor', 'sensor'],
        color: '#DEB887', icon: 'font-awesome/fa-paw', inputs: 0, emits: ['state', 'event', 'both'], defaultEmit: 'state',
        events: ['changed'],
        options: [
            { name: 'measure', label: 'Value', type: 'select', default: 'present', values: [['present', 'present (true/false)'], ['entity', 'entity id'], ['count', 'count']] },
            TRIGGER_PAYLOAD,
        ],
        help: 'An Entity Observer (an entity in front of it), or a Home Assistant sensor. State changes: the chosen '
            + 'value. Events: one message whenever the entity in front changes.',
        statePayload: (s, opt) => (opt.measure === 'entity' ? s.entity : opt.measure === 'count' ? s.count : !!s.present),
        ha: { trigger: haTrigger, state: (e) => ({ state: e.state }), payload: (e) => (e.state === 'on' ? true : e.state === 'off' ? false : V.haNumber(e.state)) },
    },
    {
        id: 'vibration-sensor', label: 'vibration sensor', typeIds: ['mineqtt:vibration_sensor'], haDomains: ['binary_sensor', 'event'],
        color: '#7DB46C', icon: 'font-awesome/fa-rss', inputs: 0, emits: ['state', 'event', 'both'], defaultEmit: 'state',
        events: ['step', 'projectile', 'item', 'entity', 'door', 'container', 'block', 'other'], options: [TRIGGER_PAYLOAD],
        help: 'A Vibration Sensor (MineQTT 0.11), or a Home Assistant vibration/motion sensor. State changes: true while '
            + 'it senses vibrations. Events: one message per vibration group (step, door, block, ...).',
        statePayload: (s) => !!s.vibration,
        ha: { trigger: haTrigger, state: (e) => ({ vibration: e.state === 'on' }), payload: (e) => e.state === 'on' },
    },
    {
        id: 'speaker', label: 'speaker', typeIds: ['mineqtt:speaker'], haDomains: ['siren'],
        color: '#B39DDB', icon: 'font-awesome/fa-volume-up', inputs: 1, emits: ['state'],
        options: [{ name: 'tone', label: 'Tone', type: 'select', default: '', values: [['', "the speaker's own"], ...TONES.map((t) => [t, t])] }],
        help: 'A Speaker (MineQTT 0.11), or a Home Assistant siren. Input: ON, OFF, TOGGLE, a tone name (alarm, chime, '
            + 'doorbell, beep, bell) to play it, an object {state, tone, duration, volume_level}, or {announce: "text"} '
            + 'for a chat announcement (only if the server allows them). Output: ON/OFF.',
        command(msg, opt) {
            const p = msg.payload;
            if (p && typeof p === 'object' && !Array.isArray(p)) return p;
            if (typeof p === 'string' && TONES.includes(p.trim().toLowerCase())) return { state: 'ON', tone: p.trim().toLowerCase() };
            const w = V.switchCommand(p) || 'ON';
            return w === 'ON' && opt.tone ? { state: 'ON', tone: opt.tone } : { state: w };
        },
        statePayload: (s) => s.state,
        ha: {
            command(msg, opt) {
                const p = msg.payload;
                const data = {};
                let word = V.switchCommand(p) || 'ON';
                if (p && typeof p === 'object') {
                    if (p.tone) data.tone = p.tone;
                    if (p.duration) data.duration = p.duration;
                    if (p.volume_level !== undefined) data.volume_level = p.volume_level;
                } else if (typeof p === 'string' && TONES.includes(p.trim().toLowerCase())) { data.tone = p.trim().toLowerCase(); word = 'ON'; }
                if (word === 'ON' && !data.tone && opt.tone) data.tone = opt.tone;
                return { service: `siren.${word === 'ON' ? 'turn_on' : word === 'OFF' ? 'turn_off' : 'toggle'}`, data: word === 'ON' ? data : {} };
            },
            state: (e) => ({ state: e.state === 'on' ? 'ON' : 'OFF' }), payload: (e) => (e.state === 'on' ? 'ON' : 'OFF'),
        },
    },
    {
        id: 'smart-lock', label: 'smart lock', typeIds: ['mineqtt:smart_lock'], haDomains: ['lock'],
        color: '#F0AD4E', icon: 'font-awesome/fa-lock', inputs: 1, emits: ['state', 'event', 'both'], defaultEmit: 'state',
        events: ['attempt'], options: [TRIGGER_PAYLOAD],
        help: 'A Smart Lock (MineQTT 0.11), or a Home Assistant lock. Input: LOCK/ON/true locks, UNLOCK/OFF/false '
            + 'unlocks, TOGGLE toggles. State changes: locked, unlocked, open or jammed. Events: an "attempt" when someone '
            + 'tries the locked door or chest.',
        command(msg) {
            const p = msg.payload;
            if (typeof p === 'string' && /^(un)?lock(ed)?$/i.test(p.trim())) return { locked: !/^un/i.test(p.trim()) };
            const w = V.switchCommand(p) || 'TOGGLE';
            return w === 'TOGGLE' ? { toggle: 'PRESS' } : { locked: w === 'ON' };
        },
        statePayload: (s) => s.state,
        ha: {
            command(msg, opt, entity, current) {
                const p = msg.payload;
                let lock;
                if (typeof p === 'string' && /^(un)?lock(ed)?$/i.test(p.trim())) lock = !/^un/i.test(p.trim());
                else {
                    const w = V.switchCommand(p) || 'TOGGLE';
                    lock = w === 'TOGGLE' ? !(current && current.state === 'locked') : w === 'ON';
                }
                return { service: lock ? 'lock.lock' : 'lock.unlock', data: {} };
            },
            state: (e) => ({ state: e.state }), payload: (e) => e.state,
        },
    },
    {
        id: 'alarm-panel', label: 'alarm panel', typeIds: ['mineqtt:alarm_panel'], haDomains: ['alarm_control_panel'],
        color: '#F0AD4E', icon: 'font-awesome/fa-shield', inputs: 1, emits: ['state', 'event', 'both'], defaultEmit: 'state',
        events: ['arm', 'disarm', 'trigger', 'wrong_code'],
        options: [TRIGGER_PAYLOAD, { name: 'code', label: 'Code', type: 'password', default: '' }],
        help: 'An Alarm Panel (MineQTT 0.11), or a Home Assistant alarm panel. Input: arm_away, arm_home, disarm or '
            + 'trigger (panic), or {action, code}. Disarming needs the code: msg.code, or the code set here (kept in the '
            + 'node\'s credentials). State changes: disarmed, arming, armed_home, armed_away, pending or triggered; wire '
            + 'them into a signal beacon to show the state in the world. Events: arm, disarm, trigger, wrong_code.',
        command(msg, opt) {
            const p = msg.payload;
            let action;
            let code = msg.code !== undefined ? String(msg.code) : opt.code || null;
            if (p && typeof p === 'object') { action = p.action; if (p.code !== undefined) code = String(p.code); }
            else if (typeof p === 'string') action = ALARM_ACTIONS[p.trim().toLowerCase()] || p.trim().toUpperCase();
            if (!['ARM_AWAY', 'ARM_HOME', 'DISARM', 'TRIGGER'].includes(String(action).toUpperCase())) return null;
            return { action: String(action).toUpperCase(), code: code || null };
        },
        statePayload: (s) => s.state,
        ha: {
            command(msg, opt) {
                const p = msg.payload;
                let action = p && typeof p === 'object' ? p.action : typeof p === 'string' ? (ALARM_ACTIONS[p.trim().toLowerCase()] || p.trim().toUpperCase()) : null;
                action = String(action).toUpperCase();
                const services = { ARM_AWAY: 'alarm_arm_away', ARM_HOME: 'alarm_arm_home', DISARM: 'alarm_disarm', TRIGGER: 'alarm_trigger' };
                if (!services[action]) return null;
                const code = msg.code !== undefined ? String(msg.code) : (p && p.code) || opt.code;
                return { service: `alarm_control_panel.${services[action]}`, data: code ? { code: String(code) } : {} };
            },
            state: (e) => ({ state: e.state }), payload: (e) => e.state,
        },
    },
];

/** Display text: "\n" typed in a Node-RED text field (a backslash and an n) becomes a line break; 255 characters. */
function displayText(text) { return String(text).replace(/\\n/g, '\n').slice(0, 255); }

function wordCommand(word) { return word ? { state: word } : null; }

/** Presses a Home Assistant button (or starts a timer) for any input; other domains have no command. */
function haPress(msg, opt, entity) {
    const domain = entity.split('.')[0];
    if (domain === 'button' || domain === 'input_button') return { service: `${domain}.press`, data: {} };
    if (domain === 'timer') return { service: 'timer.start', data: {} };
    return null;
}

function haOnOff() {
    return {
        command(msg, opt, entity) {
            const w = V.switchCommand(msg.payload) || V.fallbackSwitch(opt.otherInput);
            if (!w) return null;
            return ON_OFF_SERVICES(entity.split('.')[0], w);
        },
        state: (e) => ({ state: e.state === 'on' ? 'ON' : 'OFF' }),
        payload: (e) => (e.state === 'on' ? 'ON' : 'OFF'),
    };
}

for (const t of TYPES) {
    t.nodeType = `mineqtt-${t.id}`;
    t.options = t.options || [];
    t.defaultEmit = t.defaultEmit || t.emits[0];
    if (t.emits.length > 1) t.options = [EMIT(t.emits, t.defaultEmit), ...t.options];
    if (t.emits.includes('state') || t.emits.includes('both')) t.options = [...t.options, INITIAL];
}

module.exports = { TYPES, triggerPayload, haTrigger };

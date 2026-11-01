'use strict';

// Turns the payloads people wire into a node into MineQTT commands. Everything here is pure and has no Node-RED or
// MQTT dependency, so test/values.test.js covers it directly.

const SWITCH_WORDS = {
    on: 'ON', true: 'ON', '1': 'ON', yes: 'ON',
    off: 'OFF', false: 'OFF', '0': 'OFF', no: 'OFF',
    toggle: 'TOGGLE',
};

/** ON, OFF or TOGGLE for a switch-like payload (boolean, 0/1, on/off/toggle in any case), otherwise null. */
function switchCommand(payload) {
    if (typeof payload === 'boolean') return payload ? 'ON' : 'OFF';
    if (typeof payload === 'number') return payload === 0 ? 'OFF' : payload === 1 ? 'ON' : null;
    if (typeof payload === 'string') return SWITCH_WORDS[payload.trim().toLowerCase()] || null;
    if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
        if (typeof payload.on === 'boolean') return payload.on ? 'ON' : 'OFF';
        if (typeof payload.state === 'string' || typeof payload.state === 'boolean') {
            return switchCommand(payload.state);
        }
    }
    return null;
}

/** The command a node sends for an input it does not understand: its "other input" option. */
function fallbackSwitch(option) {
    switch (option) {
        case 'on': return 'ON';
        case 'off': return 'OFF';
        case 'ignore': return null;
        default: return 'TOGGLE';
    }
}

const LIGHT_KEYS = ['state', 'brightness', 'color', 'color_temp', 'color_temp_kelvin', 'effect', 'transition'];

/**
 * A light command (the Smart Lamp's JSON schema) for a payload: a switch word, a brightness number (0 switches off),
 * an object with light keys ({state, brightness, color: {r,g,b} | "#rrggbb" | dye name, color_temp_kelvin, effect,
 * transition}), Home Assistant style keys (rgb_color, brightness_pct, kelvin), or null.
 */
function lightCommand(payload) {
    if (typeof payload === 'number' && Number.isFinite(payload)) {
        const b = Math.max(0, Math.min(255, Math.round(payload)));
        return b === 0 ? { state: 'OFF' } : { state: 'ON', brightness: b };
    }
    const word = switchCommand(typeof payload === 'object' ? null : payload);
    if (word) return { state: word };
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
    const out = {};
    for (const key of LIGHT_KEYS) if (payload[key] !== undefined) out[key] = payload[key];
    if (typeof out.state === 'boolean') out.state = out.state ? 'ON' : 'OFF';
    if (typeof payload.on === 'boolean' && out.state === undefined) out.state = payload.on ? 'ON' : 'OFF';
    if (Array.isArray(payload.rgb_color) && payload.rgb_color.length === 3) {
        const [r, g, b] = payload.rgb_color;
        out.color = { r, g, b };
    }
    if (typeof payload.brightness_pct === 'number') out.brightness = Math.round(payload.brightness_pct * 2.55);
    if (typeof payload.kelvin === 'number' && out.color_temp_kelvin === undefined) out.color_temp_kelvin = payload.kelvin;
    if (Object.keys(out).length === 0) return null;
    // A colour, brightness or effect without a state means "on with these settings", as in Home Assistant.
    if (out.state === undefined) out.state = 'ON';
    return out;
}

/** Home Assistant service data for a light command made by lightCommand. */
function lightServiceData(cmd) {
    const data = {};
    if (cmd.brightness !== undefined) data.brightness = Number(cmd.brightness);
    if (cmd.color && typeof cmd.color === 'object' && !Array.isArray(cmd.color)) {
        data.rgb_color = [cmd.color.r || 0, cmd.color.g || 0, cmd.color.b || 0];
    } else if (Array.isArray(cmd.color)) {
        data.rgb_color = cmd.color.slice(0, 3);
    } else if (typeof cmd.color === 'string') {
        const hex = /^#?([0-9a-f]{6})$/i.exec(cmd.color.trim());
        if (hex) {
            const n = parseInt(hex[1], 16);
            data.rgb_color = [(n >> 16) & 255, (n >> 8) & 255, n & 255];
        } else {
            data.color_name = cmd.color.replace(/_/g, '');
        }
    }
    const kelvin = cmd.color_temp_kelvin !== undefined ? cmd.color_temp_kelvin
        : cmd.color_temp !== undefined ? (cmd.color_temp >= 1000 ? cmd.color_temp : Math.round(1000000 / cmd.color_temp))
            : undefined;
    if (kelvin !== undefined) data.color_temp_kelvin = Number(kelvin);
    if (cmd.effect !== undefined) data.effect = cmd.effect;
    if (cmd.transition !== undefined) data.transition = Number(cmd.transition);
    return data;
}

/** A Home Assistant light state in the Smart Lamp's state shape, so both sources look alike on a node's output. */
function lightFromHa(entity) {
    const a = entity.attributes || {};
    const out = { state: entity.state === 'on' ? 'ON' : 'OFF' };
    if (typeof a.brightness === 'number') out.brightness = a.brightness;
    if (a.color_mode) out.color_mode = a.color_mode;
    if (Array.isArray(a.rgb_color)) out.color = { r: a.rgb_color[0], g: a.rgb_color[1], b: a.rgb_color[2] };
    if (typeof a.color_temp_kelvin === 'number') out.color_temp_kelvin = a.color_temp_kelvin;
    if (a.effect) out.effect = a.effect;
    return out;
}

/** A number from a Home Assistant state string, or the string itself (e.g. "unavailable"). */
function haNumber(state) {
    const n = Number(state);
    return state !== '' && Number.isFinite(n) ? n : state;
}

/** Drops the read-only location (and a twin binding note) from a published state before it goes out of a node. */
function stripState(state) {
    if (!state || typeof state !== 'object') return state;
    const { location, binding, ...rest } = state;
    return rest;
}

module.exports = {
    switchCommand, fallbackSwitch, lightCommand, lightServiceData, lightFromHa, haNumber, stripState, LIGHT_KEYS,
};

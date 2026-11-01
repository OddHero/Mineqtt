'use strict';

// The device catalog of one broker: which MineQTT devices exist, their type, name, room and current topics. It is
// fed with every message under the discovery prefix and the base topic (config node) and holds no MQTT client itself,
// so test/catalog.test.js drives it with plain messages.
//
// Device keys are stable across renames: "mineqtt_<uuid32>" from Home Assistant discovery, or "topic:<root>/<slug>"
// for a device that is not exposed to Home Assistant (dedicated servers default to opt-in exposure; their states and
// commands work all the same).

const RESERVED = new Set(['bridge', 'group', 'raw', 'player', 'set']);

/** The MineQTT type id for a state that has no discovery, from the fields it publishes (best effort). */
function guessType(state) {
    if (!state || typeof state !== 'object') return null;
    const has = (...keys) => keys.every((k) => k in state);
    if (has('dye', 'beacon')) return 'mineqtt:signal_beacon';
    if (has('color_mode', 'light_level')) return 'mineqtt:smart_lamp';
    if (has('power', 'mode', 'last_level')) return 'mineqtt:redstone_output';
    if (has('power', 'power_on_behaviour')) return 'mineqtt:smart_plug';
    if (has('powered', 'signal')) return 'mineqtt:redstone_input';
    if (has('occupancy', 'detect')) return 'mineqtt:presence_sensor';
    if (has('temperature', 'humidity')) return 'mineqtt:climate_sensor';
    if (has('message', 'glow')) return 'mineqtt:display_panel';
    if (has('next_fire', 'cron')) return 'mineqtt:cron_timer';
    if (has('next_fire', 'trigger_at')) return 'mineqtt:game_time_timer';
    if (has('next_fire', 'unit')) return 'mineqtt:interval_timer';
    if (has('next_fire')) return 'mineqtt:alarm_clock';
    if (has('change_on', 'block')) return 'mineqtt:block_observer';
    if (has('choices', 'placed')) return 'mineqtt:block_placer';
    if (has('container', 'fill')) return 'mineqtt:container_observer';
    if (has('entity', 'present')) return 'mineqtt:entity_observer';
    if (has('vibration', 'frequency')) return 'mineqtt:vibration_sensor';
    if (has('tone', 'volume')) return 'mineqtt:speaker';
    if (has('locked', 'relock')) return 'mineqtt:smart_lock';
    if (has('armed', 'code_set')) return 'mineqtt:alarm_panel';
    const keys = Object.keys(state).filter((k) => k !== 'location' && k !== 'binding');
    if (keys.length === 0 && 'location' in state) return 'mineqtt:wall_switch';
    return null;
}

class Catalog {
    constructor({ baseTopic = 'mineqtt', discoveryPrefix = 'homeassistant' } = {}) {
        this.base = baseTopic.replace(/\/+$/, '');
        this.discoveryPrefix = discoveryPrefix.replace(/\/+$/, '');
        this.devices = new Map(); // key -> device record
        this.byRootSlug = new Map(); // "<root>/<slug>" -> key
        this.listeners = new Set(); // fn(event, key, data)
    }

    subscriptions() {
        return [`${this.discoveryPrefix}/device/+/config`, `${this.base}/#`];
    }

    on(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }

    emit(event, key, data) {
        for (const fn of this.listeners) {
            try { fn(event, key, data); } catch (e) { /* a listener's bug must not stop the others */ }
        }
    }

    /** Feeds one MQTT message (topic, payload as string). */
    handle(topic, payload) {
        if (topic.startsWith(`${this.discoveryPrefix}/device/`) && topic.endsWith('/config')) {
            this.discovery(topic.slice(this.discoveryPrefix.length + 8, -7), payload);
            return;
        }
        if (!topic.startsWith(`${this.base}/`)) return;
        const parts = topic.slice(this.base.length + 1).split('/');
        if (parts.length < 2) return;
        const bridgeId = parts[0];
        let slug = parts[1];
        let rest = parts.slice(2);
        let group = false;
        if (slug === 'group' && parts.length >= 3) { group = true; slug = parts[2]; rest = parts.slice(3); }
        else if (RESERVED.has(slug)) return;
        if (rest[0] === 'set') return; // commands, ours or someone else's
        const root = `${this.base}/${bridgeId}`;
        const rs = `${root}/${group ? 'group/' : ''}${slug}`;
        if (rest.length === 0) this.state(rs, root, bridgeId, slug, group, payload);
        else if (rest.length === 1 && rest[0] === 'availability') this.availability(rs, root, bridgeId, slug, group, payload);
        else if (rest.length === 1 && rest[0] === 'action') this.action(rs, payload);
    }

    discovery(objectId, payload) {
        const old = this.devices.get(objectId);
        if (!payload) { // cleared: the device is gone or no longer exposed
            if (old) {
                this.byRootSlug.delete(old.rootSlug);
                this.devices.delete(objectId);
                this.emit('removed', objectId, old);
            }
            return;
        }
        let msg;
        try { msg = JSON.parse(payload); } catch (e) { return; }
        const dev = msg && msg.dev;
        if (!dev || dev.mf !== 'MineQTT' || typeof dev.mdl_id !== 'string' || !dev.mdl_id.startsWith('mineqtt:')) return;
        const stateTopic = Object.values(msg.cmps || {})
            .map((c) => c && c.state_topic)
            .find((t) => typeof t === 'string' && !t.endsWith('/action') && !t.endsWith('/availability'));
        const avty = Array.isArray(msg.avty) ? msg.avty.map((a) => a && a.t).filter(Boolean) : [];
        const bridgeState = avty.find((t) => t.endsWith('/bridge/state'));
        if (!stateTopic || !bridgeState) return;
        const root = bridgeState.slice(0, -'/bridge/state'.length);
        if (!stateTopic.startsWith(`${root}/`)) return;
        const tail = stateTopic.slice(root.length + 1);
        const group = tail.startsWith('group/');
        const slug = group ? tail.slice(6) : tail;
        const rec = {
            key: objectId,
            typeId: dev.mdl_id,
            model: dev.mdl || dev.mdl_id,
            name: dev.name || slug,
            room: dev.sa || '',
            bridgeId: root.slice(this.base.length + 1),
            root,
            slug,
            group,
            rootSlug: `${root}/${tail}`,
            discovered: true,
            mirrored: Object.values(msg.cmps || {}).some((c) => c && c.p === 'sensor' && /_binding$/.test(c.unique_id || '')),
            available: old ? old.available : undefined,
            state: old ? old.state : undefined,
        };
        // A device seen before through its topics only (not exposed yet) is the same device: carry its state over.
        const topicKey = `topic:${rec.rootSlug}`;
        const pre = this.devices.get(topicKey);
        if (pre) {
            rec.state = rec.state || pre.state;
            rec.available = rec.available === undefined ? pre.available : rec.available;
            this.devices.delete(topicKey);
            this.emit('removed', topicKey, pre);
        }
        if (old && old.rootSlug !== rec.rootSlug) this.byRootSlug.delete(old.rootSlug); // renamed
        this.devices.set(objectId, rec);
        this.byRootSlug.set(rec.rootSlug, objectId);
        this.emit(old ? 'changed' : 'added', objectId, rec);
    }

    /** The record for a root/slug, created from the topics alone when discovery does not know it (yet). */
    recordFor(rs, root, bridgeId, slug, group) {
        const known = this.byRootSlug.get(rs);
        if (known) return this.devices.get(known);
        const key = `topic:${rs}`;
        let rec = this.devices.get(key);
        if (!rec) {
            rec = {
                key, typeId: null, model: group ? 'Group' : 'Device', name: slug, room: '', bridgeId, root, slug, group,
                rootSlug: rs, discovered: false, available: undefined, state: undefined,
            };
            this.devices.set(key, rec);
            this.byRootSlug.set(rs, key);
            this.emit('added', key, rec);
        }
        return rec;
    }

    state(rs, root, bridgeId, slug, group, payload) {
        if (!payload) { // a rename or removal clears the old state
            const key = this.byRootSlug.get(rs);
            const rec = key && this.devices.get(key);
            if (rec) {
                rec.state = undefined;
                if (!rec.discovered) {
                    this.devices.delete(key);
                    this.byRootSlug.delete(rs);
                    this.emit('removed', key, rec);
                }
            }
            return;
        }
        let state;
        try { state = JSON.parse(payload); } catch (e) { return; }
        if (!state || typeof state !== 'object' || Array.isArray(state)) return;
        const rec = this.recordFor(rs, root, bridgeId, slug, group);
        if (!rec.discovered && !rec.typeId) rec.typeId = guessType(state);
        const prev = rec.state;
        rec.state = state;
        this.emit('state', rec.key, { state, prev });
    }

    availability(rs, root, bridgeId, slug, group, payload) {
        if (!payload) return;
        const rec = this.recordFor(rs, root, bridgeId, slug, group);
        rec.available = payload === 'online';
        this.emit('availability', rec.key, rec.available);
    }

    action(rs, payload) {
        const key = this.byRootSlug.get(rs);
        if (!key || !payload) return;
        let evt;
        try { evt = JSON.parse(payload); } catch (e) { return; }
        if (evt && typeof evt.event_type === 'string') this.emit('event', key, evt);
    }

    get(key) { return this.devices.get(key); }

    /** The command topic of a device, following renames. */
    commandTopic(key) {
        const rec = this.devices.get(key);
        return rec ? `${rec.rootSlug}/set` : null;
    }

    /** Devices for the editor's picker: those of the given type ids, plus untyped ones (shown last). */
    list(typeIds) {
        const want = typeIds && typeIds.length ? new Set(typeIds) : null;
        return [...this.devices.values()]
            .filter((d) => !want || !d.typeId || want.has(d.typeId))
            .map((d) => ({
                key: d.key, name: d.name, model: d.model, typeId: d.typeId, room: d.room, bridgeId: d.bridgeId,
                slug: d.slug, group: d.group, discovered: d.discovered, available: d.available,
            }))
            .sort((a, b) => (a.typeId ? 0 : 1) - (b.typeId ? 0 : 1)
                || a.room.localeCompare(b.room) || a.name.localeCompare(b.name));
    }
}

module.exports = { Catalog, guessType, RESERVED };

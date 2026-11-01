'use strict';

// A small Home Assistant WebSocket API client: authenticates with a long-lived access token, keeps every entity's
// state current (get_states + state_changed), knows each entity's area for the editor's picker, and calls services.
// It reconnects by itself with a growing delay (2 s up to 60 s).

const { EventEmitter } = require('events');
const WebSocket = require('ws');

class HaClient extends EventEmitter {
    constructor({ url, token, log = () => {} }) {
        super();
        this.url = String(url || '').replace(/\/+$/, '');
        this.token = token;
        this.log = log;
        this.states = new Map();
        this.areas = new Map(); // entity_id -> area name
        this.pending = new Map();
        this.nextId = 1;
        this.connected = false;
        this.closed = false;
        this.delay = 2000;
        this.setMaxListeners(0);
    }

    wsUrl() {
        return `${this.url.replace(/^http/, 'ws')}/api/websocket`;
    }

    start() {
        if (this.closed || this.ws) return;
        let ws;
        try {
            ws = new WebSocket(this.wsUrl());
        } catch (e) {
            this.log(`Home Assistant: ${e.message}`);
            this.retry();
            return;
        }
        this.ws = ws;
        ws.on('message', (data) => this.onMessage(data));
        ws.on('error', (e) => this.log(`Home Assistant: ${e.message}`));
        ws.on('close', () => {
            this.ws = null;
            const was = this.connected;
            this.connected = false;
            for (const { reject } of this.pending.values()) reject(new Error('connection closed'));
            this.pending.clear();
            if (was) this.emit('disconnected');
            this.retry();
        });
    }

    retry() {
        if (this.closed) return;
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.start(), this.delay);
        this.delay = Math.min(this.delay * 2, 60000);
    }

    stop() {
        this.closed = true;
        clearTimeout(this.timer);
        if (this.ws) this.ws.close();
    }

    send(obj) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
    }

    call(type, extra = {}) {
        return new Promise((resolve, reject) => {
            if (!this.connected) { reject(new Error('Home Assistant is not connected')); return; }
            const id = this.nextId++;
            this.pending.set(id, { resolve, reject });
            this.send({ id, type, ...extra });
        });
    }

    async onMessage(data) {
        let msg;
        try { msg = JSON.parse(data.toString()); } catch (e) { return; }
        if (msg.type === 'auth_required') {
            this.send({ type: 'auth', access_token: this.token });
        } else if (msg.type === 'auth_invalid') {
            this.log(`Home Assistant: authentication failed (${msg.message || 'invalid token'})`);
            this.emit('auth_invalid');
        } else if (msg.type === 'auth_ok') {
            this.connected = true;
            this.delay = 2000;
            try {
                await this.call('subscribe_events', { event_type: 'state_changed' });
                const states = await this.call('get_states');
                this.states.clear();
                for (const s of states) this.states.set(s.entity_id, s);
                await this.loadAreas();
            } catch (e) {
                this.log(`Home Assistant: ${e.message}`);
            }
            this.emit('connected');
        } else if (msg.type === 'result') {
            const p = this.pending.get(msg.id);
            if (!p) return;
            this.pending.delete(msg.id);
            if (msg.success) p.resolve(msg.result);
            else p.reject(new Error((msg.error && msg.error.message) || 'Home Assistant refused the request'));
        } else if (msg.type === 'event' && msg.event && msg.event.event_type === 'state_changed') {
            const { entity_id: id, new_state: now, old_state: old } = msg.event.data;
            if (now) this.states.set(id, now); else this.states.delete(id);
            this.emit('state', id, now, old);
        }
    }

    async loadAreas() {
        try {
            const [areas, devices, entities] = await Promise.all([
                this.call('config/area_registry/list'),
                this.call('config/device_registry/list'),
                this.call('config/entity_registry/list'),
            ]);
            const areaName = new Map(areas.map((a) => [a.area_id, a.name]));
            const deviceArea = new Map(devices.map((d) => [d.id, d.area_id]));
            this.areas.clear();
            for (const e of entities) {
                const area = e.area_id || deviceArea.get(e.device_id);
                if (area && areaName.has(area)) this.areas.set(e.entity_id, areaName.get(area));
            }
        } catch (e) {
            // A non-admin token cannot read the registries; the picker then lists entities without areas.
            this.log(`Home Assistant: no areas (${e.message})`);
        }
    }

    callService(service, data, entityId) {
        const [domain, name] = service.split('.');
        return this.call('call_service', { domain, service: name, service_data: data || {}, target: { entity_id: entityId } });
    }

    /** Entities for the editor's picker, filtered by domain. */
    list(domains) {
        const want = domains && domains.length ? new Set(domains) : null;
        return [...this.states.values()]
            .filter((s) => !want || want.has(s.entity_id.split('.')[0]))
            .map((s) => ({
                entity_id: s.entity_id,
                name: (s.attributes && s.attributes.friendly_name) || s.entity_id,
                area: this.areas.get(s.entity_id) || '',
                state: s.state,
            }))
            .sort((a, b) => a.area.localeCompare(b.area) || a.name.localeCompare(b.name));
    }
}

module.exports = { HaClient };

'use strict';

// The device nodes, one per entry in lib/types.js. Each node is bound either to a MineQTT device (through a
// mineqtt-broker config node) or to a Home Assistant entity (through a mineqtt-homeassistant config node).
// The state that arrives when a node starts (MQTT's retained state, Home Assistant's current state) only sets the
// status; outputs fire on later changes and events, so a deploy never triggers a flow by itself.

const { TYPES, triggerPayload } = require('../lib/types');
const V = require('../lib/values');

module.exports = function (RED) {
    for (const type of TYPES) register(RED, type);
};

function register(RED, type) {
    function DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const opt = {};
        for (const o of type.options) {
            opt[o.name] = o.type === 'password' ? (node.credentials || {})[o.name] || ''
                : (config[o.name] !== undefined && config[o.name] !== '' ? config[o.name] : o.default);
        }
        const emit = opt.emit || type.defaultEmit;
        const sendStates = emit === 'state' || emit === 'both';
        const sendEvents = emit === 'event' || emit === 'both';
        const source = config.source === 'ha' ? 'ha' : 'mineqtt';
        let lastKey;
        let seen = false;

        const status = (fill, shape, text) => node.status({ fill, shape, text: String(text).slice(0, 40) });
        const shown = (p) => (p === undefined ? 'ok' : typeof p === 'object' ? JSON.stringify(p) : p);

        function output(msg) { node.send(msg); }

        if (source === 'ha') {
            const server = RED.nodes.getNode(config.ha);
            const entity = config.entity;
            if (!server || !entity) { status('red', 'ring', 'no entity chosen'); return; }
            const device = { key: entity, name: entity, room: '', type: entity.split('.')[0], source: 'homeassistant' };
            const describe = () => {
                const s = server.ha.states.get(entity);
                device.name = (s && s.attributes && s.attributes.friendly_name) || entity;
                device.room = server.ha.areas.get(entity) || '';
            };
            const showState = (s) => {
                if (!s) { status('red', 'ring', 'entity not found'); return; }
                if (s.state === 'unavailable') { status('yellow', 'ring', 'unavailable'); return; }
                status('green', 'dot', shown(type.ha.payload ? type.ha.payload(s) : s.state));
            };
            node.haStatus = (up) => {
                if (!up) { status('red', 'ring', 'Home Assistant disconnected'); return; }
                describe();
                const s = server.ha.states.get(entity);
                showState(s);
                if (s && !seen) { seen = true; lastKey = JSON.stringify(type.ha.payload ? type.ha.payload(s) : s.state); }
            };
            const onState = (id, now, old) => {
                if (id !== entity) return;
                showState(now);
                if (!now) return;
                describe();
                const payload = type.ha.payload ? type.ha.payload(now) : now.state;
                const state = type.ha.state ? type.ha.state(now) : now;
                const key = JSON.stringify(payload);
                if (type.ha.trigger && (sendEvents || type.emits[0] === 'event')) {
                    const evt = type.ha.trigger(now, old);
                    if (evt !== undefined) {
                        output({ topic: device.name, payload: triggerPayload(opt, evt), eventType: evt, event: { event_type: evt, attributes: now.attributes }, state, device });
                    }
                }
                if (sendStates && seen && key !== lastKey && now.state !== 'unavailable') {
                    output({ topic: device.name, payload, state, device });
                }
                lastKey = key;
                seen = true;
            };
            server.users.add(node);
            server.ha.on('state', onState);
            node.haStatus(server.ha.connected);
            node.on('input', (msg, send, done) => {
                if (!type.inputs || !type.ha.command) { done(); return; }
                const cmd = type.ha.command(msg, opt, entity, server.ha.states.get(entity));
                if (!cmd) { node.warn(`nothing to send for ${JSON.stringify(msg.payload)}`); done(); return; }
                server.ha.callService(cmd.service, cmd.data, entity).then(() => done(), (e) => done(e));
            });
            node.on('close', () => { server.ha.removeListener('state', onState); server.users.delete(node); });
            return;
        }

        const broker = RED.nodes.getNode(config.broker);
        const key = config.device;
        if (!broker || !key) { status('red', 'ring', 'no device chosen'); return; }
        const catalog = broker.catalog;
        const device = { key, name: key, room: '', type: '', source: 'mineqtt' };
        const describe = (rec) => {
            device.name = rec.name; device.room = rec.room; device.type = rec.typeId || ''; device.slug = rec.slug;
            device.bridge = rec.bridgeId;
        };
        const changeKey = (s) => JSON.stringify(type.changeKey ? type.changeKey(s, opt) : type.statePayload(s, opt));
        const refresh = () => {
            const rec = catalog.get(key);
            if (!broker.connected) { status('red', 'ring', 'broker disconnected'); return; }
            if (!rec) { status('red', 'ring', 'device not found'); return; }
            describe(rec);
            if (rec.available === false) { status('yellow', 'ring', 'offline'); return; }
            if (rec.state) {
                if (!seen) { seen = true; lastKey = changeKey(rec.state); }
                status('green', 'dot', shown(type.statePayload(rec.state, opt)));
            } else {
                status('grey', 'dot', 'waiting for state');
            }
        };
        node.brokerStatus = () => refresh();
        const unlisten = catalog.on((event, k, data) => {
            if (k !== key) return;
            if (event === 'state') {
                const rec = catalog.get(key);
                describe(rec);
                const state = V.stripState(data.state);
                const ck = changeKey(data.state);
                if (sendStates && seen && ck !== lastKey) {
                    const payload = type.statePayload(data.state, opt);
                    if (payload !== undefined) output({ topic: device.name, payload, state, device });
                }
                lastKey = ck;
                seen = true;
                refresh();
            } else if (event === 'event') {
                if (!sendEvents) return;
                if (type.eventFilter && !type.eventFilter(data, opt)) return;
                const rec = catalog.get(key);
                const msg = {
                    topic: device.name, payload: triggerPayload(opt, data.event_type), eventType: data.event_type,
                    event: data, state: rec && V.stripState(rec.state), device,
                };
                if (data.button) msg.button = data.button;
                output(msg);
            } else {
                refresh();
            }
        });
        broker.users.add(node);
        refresh();
        node.on('input', (msg, send, done) => {
            if (!type.command) { done(); return; }
            const cmd = type.command(msg, opt, catalog.get(key));
            if (!cmd) { node.warn(`nothing to send for ${JSON.stringify(msg.payload)}`); done(); return; }
            if (!broker.command(key, cmd)) { done(new Error(catalog.get(key) ? 'broker disconnected' : 'device not found')); return; }
            done();
        });
        node.on('close', () => { unlisten(); broker.users.delete(node); });
    }

    const credentials = {};
    for (const o of type.options) if (o.type === 'password') credentials[o.name] = { type: 'password' };
    RED.nodes.registerType(type.nodeType, DeviceNode, { credentials });
}

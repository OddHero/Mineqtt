'use strict';

// The two configuration nodes: "mineqtt-broker" (the MQTT broker MineQTT talks to, with the device catalog built from
// its retained messages) and "mineqtt-homeassistant" (a Home Assistant server for its entities). Both offer the
// editor an HTTP endpoint listing what can be picked.

const mqtt = require('mqtt');
const { Catalog } = require('../lib/catalog');
const { HaClient } = require('../lib/ha');

module.exports = function (RED) {
    function MineqttBrokerNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.catalog = new Catalog({ baseTopic: config.baseTopic || 'mineqtt', discoveryPrefix: config.discoveryPrefix || 'homeassistant' });
        node.connected = false;
        node.users = new Set();

        const creds = node.credentials || {};
        const options = {
            clientId: `nodered_mineqtt_${node.id.replace(/[^a-zA-Z0-9]/g, '').slice(0, 12)}_${Math.random().toString(16).slice(2, 6)}`,
            username: creds.username || undefined,
            password: creds.password || undefined,
            reconnectPeriod: 5000,
            connectTimeout: 15000,
            protocolVersion: config.protocol === '5' ? 5 : 4,
            clean: true,
        };
        node.client = mqtt.connect(config.url || 'mqtt://localhost:1883', options);
        node.client.on('connect', () => {
            node.connected = true;
            node.log(`connected to ${config.url}`);
            node.client.subscribe(node.catalog.subscriptions(), { qos: 1 }, (err) => {
                if (err) node.error(`subscribe failed: ${err.message}`);
            });
            for (const user of node.users) user.brokerStatus(true);
        });
        node.client.on('close', () => {
            if (node.connected) for (const user of node.users) user.brokerStatus(false);
            node.connected = false;
        });
        node.client.on('error', (e) => node.warn(`MQTT: ${e.message}`));
        node.client.on('message', (topic, payload) => node.catalog.handle(topic, payload.toString()));

        /** Sends a command to a device's /set topic; false when the device is unknown or the broker is down. */
        node.command = function (key, obj) {
            const topic = node.catalog.commandTopic(key);
            if (!topic || !node.connected) return false;
            node.client.publish(topic, JSON.stringify(obj), { qos: 1, retain: false });
            return true;
        };

        node.on('close', (done) => {
            node.client.end(true, {}, () => done());
        });
    }
    RED.nodes.registerType('mineqtt-broker', MineqttBrokerNode, {
        credentials: { username: { type: 'text' }, password: { type: 'password' } },
    });

    function MineqttHomeAssistantNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.users = new Set();
        // In the Home Assistant OS Node-RED add-on, an empty URL and token use the Supervisor's proxy and token.
        const supervisor = process.env.SUPERVISOR_TOKEN;
        const token = (node.credentials || {}).token || supervisor;
        const url = config.url || (supervisor ? 'http://supervisor/core' : '');
        node.ha = new HaClient({ url, token, log: (m) => node.warn(m) });
        node.ha.on('connected', () => { node.log(`connected to ${config.url}`); for (const u of node.users) u.haStatus(true); });
        node.ha.on('disconnected', () => { for (const u of node.users) u.haStatus(false); });
        if (url && token) node.ha.start();
        else node.warn('Home Assistant: enter a URL and a token (outside the Home Assistant OS add-on)');
        node.on('close', (done) => { node.ha.stop(); done(); });
    }
    RED.nodes.registerType('mineqtt-homeassistant', MineqttHomeAssistantNode, {
        credentials: { token: { type: 'password' } },
    });

    // The editor's pickers. A config node that was never deployed is unknown to the runtime: the editor says so.
    RED.httpAdmin.get('/mineqtt/devices', RED.auth.needsPermission('mineqtt-broker.read'), (req, res) => {
        const broker = RED.nodes.getNode(req.query.broker);
        if (!broker || !broker.catalog) { res.json({ error: 'deploy', devices: [] }); return; }
        const types = String(req.query.types || '').split(',').filter(Boolean);
        res.json({ connected: broker.connected, devices: broker.catalog.list(types) });
    });
    RED.httpAdmin.get('/mineqtt/entities', RED.auth.needsPermission('mineqtt-homeassistant.read'), (req, res) => {
        const server = RED.nodes.getNode(req.query.server);
        if (!server || !server.ha) { res.json({ error: 'deploy', entities: [] }); return; }
        const domains = String(req.query.domains || '').split(',').filter(Boolean);
        res.json({ connected: server.ha.connected, entities: server.ha.list(domains) });
    });
};

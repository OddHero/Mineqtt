package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Profiles;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.twin.core.TwinHealth;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Home Assistant as the twin suites simulate it on the test broker (M3 plan §4: "client suites simulate HA through
 * {@code BrokerProbe}"): statestream leaves and the catalog blueprint's topics published by a probe, and the control
 * blueprint answering requests. The run gets a statestream base of its own ({@code ha_statestream_<bridge>}), so runs
 * that share a broker never read each other's entities or catalogs, and the twin is opened for the suite's entities with
 * the player made an operator, as a dedicated server's defaults keep it off and binding needs {@code mineqtt:twin.bind}
 * (the test server is dedicated; the integrated AUTO rule cannot be had here). Everything is restored when it closes; the
 * retained topics are cleared when the session ends.
 */
final class TwinFixture implements AutoCloseable {
    static final int ANSWER_TICKS = 100;
    private static final int SETTLE_TICKS = 10;

    private final GameTestSession session;
    private final BrokerProbe probe;
    private final String base;
    private final String savedBase;
    private final List<String> savedAllowed;
    private final boolean savedCatalog;

    TwinFixture(GameTestSession session, List<String> allowed) {
        this.session = session;
        this.probe = BrokerProbe.connect(session.broker().orElseThrow());
        this.base = "ha_statestream_" + GameTestSession.BRIDGE_ID.replace('-', '_');
        this.savedBase = MineQTTConfig.twinStatestreamBase;
        this.savedAllowed = MineQTTConfig.twinAllowedEntities;
        this.savedCatalog = MineQTTConfig.twinCatalog;
        MineQTTConfig.twinStatestreamBase = base;
        MineQTTConfig.twinAllowedEntities = List.copyOf(allowed);
        MineqttRuntime.configChanged();
        session.context().waitTicks(SETTLE_TICKS);
        op();
    }

    String base() {
        return base;
    }

    /** Makes the player an operator, who holds {@code mineqtt:twin.bind} and {@code mineqtt:twin.control} on a dedicated server. */
    void op() {
        session.consoleCommand("op " + session.playerName());
        session.awaitServer(s -> Profiles.isOp(s, Profiles.of(session.player(s))));
    }

    /** Takes the player's operator rights away again: a dedicated server's player, who may not bind. */
    void deop() {
        session.consoleCommand("deop " + session.playerName());
        session.awaitServer(s -> !Profiles.isOp(s, Profiles.of(session.player(s))));
    }

    BrokerProbe probe() {
        return probe;
    }

    /** Opens the twin for these entities only, as a reload of {@code twin.allowedEntities} would. */
    void allow(List<String> allowed) {
        MineQTTConfig.twinAllowedEntities = List.copyOf(allowed);
        MineqttRuntime.configChanged();
        session.context().waitTicks(SETTLE_TICKS);
    }

    /** One statestream leaf of an entity, as Home Assistant's mqtt_statestream publishes it; retained ones are cleared at the end. */
    void leaf(String entityId, String leaf, String payload, boolean retained) {
        String topic = base + "/" + entityId.replace('.', '/') + "/" + leaf;
        if (retained) session.clearAtClose(topic);
        probe.publish(topic, payload, retained);
    }

    /** An entity's attributes, its {@code last_updated}, then its state, retained, as statestream does at its start. */
    void entity(String entityId, String state, Map<String, String> attributes) {
        attributes.forEach((key, json) -> leaf(entityId, key, json, true));
        leaf(entityId, "last_updated", "\"" + Instant.now() + "\"", true);
        leaf(entityId, "state", state, true);
    }

    /** A live change of an entity's state (not retained, so the twin counts it live). */
    void live(String entityId, String state) {
        leaf(entityId, "last_updated", "\"" + Instant.now() + "\"", false);
        leaf(entityId, "state", state, false);
    }

    /** The catalog blueprint's list of a domain (D3), retained. */
    void catalog(String domain, String entriesJson) {
        String topic = base + "/_mineqtt/catalog/" + domain;
        session.clearAtClose(topic);
        probe.publish(topic, entriesJson, true);
    }

    /** The catalog blueprint's status, which makes the catalog decide what may be bound (D3). */
    void catalogStatus(int entities) {
        String topic = base + "/_mineqtt/status";
        session.clearAtClose(topic);
        probe.publish(topic, "{\"v\":1,\"blueprint\":\"catalog\",\"generated\":\"" + Instant.now() + "\",\"read_label\":\"mineqtt_read\","
                + "\"control_label\":\"mineqtt_control\",\"entities\":" + entities + "}", true);
    }

    /** Waits until the twin reads the catalog and it decides (catalog mode). */
    boolean awaitCatalog() {
        return session.await(() -> session.onRuntime(runtime -> runtime.twin().catalogMode()), ANSWER_TICKS * 3);
    }

    /**
     * Waits until the twin's fallback scan is open (D4: 10 s after the first connect while no catalog answers), so it lists
     * the entities statestream publishes, with their friendly names.
     */
    boolean awaitScan() {
        return session.await(() -> session.onRuntime(runtime -> runtime.twin().status(List.of()).catalog().scanOpen()), ANSWER_TICKS * 4);
    }

    /** The control blueprint's requests, as the probe sees them. */
    BrokerProbe.Listener requests() {
        return probe.listen(MineQTTConfig.baseTopic + "/" + GameTestSession.BRIDGE_ID + "/bridge/ha_request");
    }

    /** The next request within the timeout, as JSON; empty when none came. */
    Optional<JsonObject> awaitRequest(BrokerProbe.Listener requests, int timeoutTicks) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            Optional<String> next = requests.nextLive();
            if (next.isPresent()) return Optional.of(JsonParser.parseString(next.get()).getAsJsonObject());
            session.context().waitTick();
        }
        return Optional.empty();
    }

    /** The control blueprint's answer to a request ({@code accepted}, {@code not_labelled}, …). */
    void answer(JsonObject request, String status) {
        String entity = request.getAsJsonObject("target").get("entity_id").getAsString();
        probe.publish(MineQTTConfig.baseTopic + "/" + GameTestSession.BRIDGE_ID + "/bridge/ha_response",
                "{\"id\":\"" + request.get("id").getAsString() + "\",\"status\":\"" + status + "\",\"entity\":\"" + entity + "\",\"blueprint\":1}", false);
    }

    // ===== The server's side =====

    static DeviceRecord recordAt(MineqttRuntime runtime, BlockPos pos) {
        return runtime.registry().records().stream().filter(record -> record.pos().pos().equals(pos)).findFirst()
                .orElseThrow(() -> new AssertionError("No device at " + pos.toShortString()));
    }

    String slug(BlockPos pos) {
        return session.onRuntime(runtime -> recordAt(runtime, pos).slug());
    }

    /** The state the device at {@code pos} publishes, read on the server. */
    JsonObject state(BlockPos pos) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(pos) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }

    /** The entity the device at {@code pos} mirrors, if bound. */
    Optional<String> source(BlockPos pos) {
        return Optional.ofNullable(session.device(pos, link -> link.twin() == null ? null : link.twin().sourceEntity().orElse(null)));
    }

    /** The MQTT device preset the device at {@code pos} mirrors through, if bound to one (M3-17). */
    Optional<art.rehra.mineqtt.twin.core.Binding.PresetRef> preset(BlockPos pos) {
        return Optional.ofNullable(session.device(pos, link -> link.twin() == null ? null : link.twin().preset().orElse(null)));
    }

    Optional<TwinHealth> health(BlockPos pos) {
        return session.onRuntime(runtime -> runtime.devices().twinHealth(recordAt(runtime, pos).id()));
    }

    boolean awaitHealth(BlockPos pos, TwinHealth health) {
        return session.await(() -> health(pos).filter(health::equals).isPresent(), ANSWER_TICKS);
    }

    @Override
    public void close() {
        try {
            probe.close();
        } finally {
            MineQTTConfig.twinStatestreamBase = savedBase;
            MineQTTConfig.twinAllowedEntities = savedAllowed;
            MineQTTConfig.twinCatalog = savedCatalog;
            MineqttRuntime.configChanged();
            session.consoleCommand("deop " + session.playerName());
        }
    }
}

package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.mqtt.ICallbackTarget;
import art.rehra.mineqtt.mqtt.core.BrokerSettings;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * How the unmigrated v1 blocks reach MQTT: through the running server's
 * {@link art.rehra.mineqtt.mqtt.core.MqttService}, with v1 topic semantics and the hard deny of the {@link TopicAcl}
 * on every topic (the raw allowlist does not apply, so no existing world breaks).
 * <ul>
 *     <li>Only block entities in a {@link ServerLevel} that are not removed register. Every message is checked
 *     against the block entity again before delivery: a stale one (removed, of another server, or replaced at its
 *     position) is unregistered. A block whose chunk is in memory but below FULL is not stale: it keeps its
 *     subscriptions, since nothing would open them again when the chunk is FULL, and misses the message, because
 *     delivering would load the chunk.</li>
 *     <li>Each block subscribes its filter itself, so wildcards deliver, every message is delivered in order (no
 *     coalescing), and the block's {@link #origin} keeps it from hearing its own publishes while blocks on the same
 *     server hear each other through the local loopback, also with the broker down.</li>
 *     <li>Blocks register while chunks load, which can be before the server has started. Those registrations wait
 *     until {@link #attach}.</li>
 * </ul>
 * Server thread only.
 */
public final class LegacySubscriptionFacade {
    // Raw topics of v1: retained values are delivered, as v1 did.
    private static final boolean ACCEPT_RETAINED = true;
    // v1 had no per-block limit, so blocks keep the broker-wide inbound cap instead of the 16 KiB of a Raw Link.
    private static final int MAX_PAYLOAD_BYTES = BrokerSettings.DEFAULT_MAX_INBOUND_BYTES;
    private static final int MAX_LOGGED_REFUSALS = 256;

    // Target -> filter -> its subscription; null while not open (before attach, or refused by the ACL).
    private static final Map<ICallbackTarget, Map<String, Subscription>> registrations = new IdentityHashMap<>();
    // Topics a block was refused to publish to, so a sensor publishing every tick logs it once.
    private static final Set<String> loggedRefusals = new HashSet<>();
    private static MineqttRuntime runtime;

    private LegacySubscriptionFacade() {
    }

    /** {@code SERVER_STARTED}: opens the subscriptions of the blocks that registered while the server started. */
    public static void attach(MineqttRuntime started) {
        runtime = started;
        for (ICallbackTarget target : new ArrayList<>(registrations.keySet())) {
            if (isStale(target)) {
                unregister(target);
            } else {
                registrations.get(target).replaceAll((filter, subscription) -> subscription != null ? subscription : open(filter, target));
            }
        }
    }

    /** {@code SERVER_STOPPING}: closes every subscription; the blocks of the next server register anew. */
    public static void detach() {
        registrations.values().forEach(filters -> filters.values().forEach(LegacySubscriptionFacade::close));
        registrations.clear();
        loggedRefusals.clear();
        runtime = null;
    }

    /**
     * Subscribes a block entity to a v1 topic or filter. Nothing happens on the client, for a block entity without a
     * level (still loading; it asks again from {@code setLevel}) or a removed one, and a second call for the same
     * filter does nothing.
     */
    public static void subscribe(String filter, ICallbackTarget target) {
        if (filter.isEmpty() || !isServerSide(target) || target.isRemoved()) return;
        Map<String, Subscription> filters = registrations.computeIfAbsent(target, t -> new HashMap<>());
        if (filters.containsKey(filter)) return;
        filters.put(filter, runtime == null ? null : open(filter, target));
    }

    public static void unsubscribe(String filter, ICallbackTarget target) {
        if (!isServerSide(target)) return;
        Map<String, Subscription> filters = registrations.get(target);
        if (filters == null || !filters.containsKey(filter)) return;
        close(filters.remove(filter));
        if (filters.isEmpty()) registrations.remove(target);
        MineQTT.LOGGER.info("Unsubscribed {} from MQTT topic {}", target.getBlockPos().toShortString(), filter);
    }

    /**
     * A block publishes with v1's flags: QoS 0, not retained, dropped while the broker is unreachable (blocks on this
     * server still receive it).
     *
     * @return why nothing was published; also logged once per topic
     */
    public static Optional<String> publish(Level level, BlockPos pos, String topic, String payload) {
        return publish(level, pos, topic, payload, 0, OfflinePolicy.DROP);
    }

    /**
     * A block publishes an edge that is never coalesced and never retained.
     *
     * @return why nothing was published; also logged once per topic
     */
    public static Optional<String> publish(Level level, BlockPos pos, String topic, String payload, int qos, OfflinePolicy offline) {
        if (!(level instanceof ServerLevel)) return Optional.of("only the server publishes");
        Optional<String> refusal = send(topic, payload.getBytes(StandardCharsets.UTF_8), qos, offline, origin(level, pos));
        refusal.ifPresent(reason -> {
            if (loggedRefusals.size() < MAX_LOGGED_REFUSALS && loggedRefusals.add(topic)) {
                MineQTT.LOGGER.warn("Block at {} does not publish to '{}': {}", pos.toShortString(), topic, reason);
            }
        });
        return refusal;
    }

    public static boolean isConnected() {
        return MineqttRuntime.current().map(running -> running.mqtt().state().isConnected()).orElse(false);
    }

    /** A block's origin: the same for every publish and subscription of that position. */
    public static UUID origin(Level level, BlockPos pos) {
        return UUID.nameUUIDFromBytes((level.dimension().identifier() + ":" + pos.asLong()).getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<String> send(String topic, byte[] payload, int qos, OfflinePolicy offline, UUID origin) {
        Optional<MineqttRuntime> running = MineqttRuntime.current();
        if (running.isEmpty()) return Optional.of("MineQTT is not running");
        TopicAcl.Decision decision = running.get().acl().checkLegacy(topic, true);
        if (!decision.allowed()) return Optional.of(decision.message());
        running.get().mqtt().publish(new Outbound(topic, payload, qos, false, offline, Outbound.Delivery.EDGE, origin));
        return Optional.empty();
    }

    private static Subscription open(String filter, ICallbackTarget target) {
        TopicAcl.Decision decision = runtime.acl().checkLegacy(filter, false);
        if (!decision.allowed()) {
            MineQTT.LOGGER.warn("Block at {} does not subscribe to '{}': {}", target.getBlockPos().toShortString(), filter, decision.message());
            return null;
        }
        MineQTT.LOGGER.info("Subscribed {} to MQTT topic {}", target.getBlockPos().toShortString(), filter);
        // No-local plus loopback, like a Raw Link input.
        SubOptions options = new SubOptions(1, ACCEPT_RETAINED, true, MAX_PAYLOAD_BYTES, origin(target.getLevel(), target.getBlockPos()));
        return runtime.mqtt().subscribe(filter, options, message -> deliver(target, message));
    }

    private static void deliver(ICallbackTarget target, Inbound message) {
        if (isStale(target)) {
            MineQTT.LOGGER.debug("Dropping the subscriptions of the stale block entity at {}", target.getBlockPos());
            unregister(target);
            return;
        }
        if (!target.getLevel().isLoaded(target.getBlockPos())) {
            MineQTT.LOGGER.debug("Block at {} misses a message on {}: its chunk is not loaded", target.getBlockPos(), message.topic());
            return;
        }
        target.onMessageReceived(message.topic(), message.text());
    }

    private static void unregister(ICallbackTarget target) {
        Map<String, Subscription> filters = registrations.remove(target);
        if (filters != null) filters.values().forEach(LegacySubscriptionFacade::close);
    }

    private static void close(Subscription subscription) {
        if (subscription != null) subscription.close();
    }

    private static boolean isServerSide(ICallbackTarget target) {
        return target.getLevel() instanceof ServerLevel;
    }

    /**
     * No longer a block entity of the running server: removed (unload and replacement both remove it), in another
     * server's level, or no longer at its position. A chunk below FULL keeps its block entities, so it does not make
     * them stale; it is not checked, which also keeps this from loading a chunk.
     */
    private static boolean isStale(ICallbackTarget target) {
        BlockPos pos = target.getBlockPos();
        if (!(target.getLevel() instanceof ServerLevel level) || runtime == null || level.getServer() != runtime.server()
                || target.isRemoved()) {
            return true;
        }
        return level.isLoaded(pos) && level.getBlockEntity(pos) != target;
    }
}

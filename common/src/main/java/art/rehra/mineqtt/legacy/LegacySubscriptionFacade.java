package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.mqtt.ICallbackTarget;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.NoticeGate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * How the two blocks that are no devices yet reach MQTT: the Block Sensor publishes and the Block Emitter subscribes,
 * through the running server's {@link art.rehra.mineqtt.mqtt.core.MqttService}, with v1 topic semantics and the hard
 * deny of the {@link TopicAcl} on every topic (the raw allowlist does not apply, so no existing world breaks). Every
 * other v1 block became a device, a converter or a decommissioned block in M2; this facade goes in v3.0.
 * <ul>
 *     <li>Only block entities in a {@link ServerLevel} that are not removed register. Every message is checked
 *     against the block entity again before delivery: a stale one (removed, of another server, or replaced at its
 *     position) is unregistered. A block whose chunk is in memory but below FULL is not stale: it keeps its
 *     subscriptions, since nothing would open them again when the chunk is FULL, and misses the message, because
 *     delivering would load the chunk.</li>
 *     <li>Each block subscribes its filter itself, so wildcards deliver, every message is delivered in order (no
 *     coalescing), and the block's {@link #origin} keeps it from hearing its own publishes while blocks on the same
 *     server hear each other through the local loopback, also with the broker down.</li>
 *     <li>A message is delivered in a {@link art.rehra.mineqtt.flow.core.Root#RAW} extent
 *     ({@link art.rehra.mineqtt.device.DeviceRuntime#fromBroker}, M3 plan D10): a device that the Block Emitter's placed
 *     block powers in the same tick emits RAW, not WORLD, so a broker message never switches a real device.</li>
 *     <li>Blocks register while chunks load, which can be before the server has started. Those registrations wait
 *     until {@link #attach}.</li>
 *     <li>A refused topic is logged once and told once to the player who just edited the block (M2 plan D1). A config
 *     reload judges every subscription again ({@link #aclChanged}) and tells a new refusal to the owner when nobody
 *     edited the block moments ago.</li>
 * </ul>
 * Server thread only.
 */
public final class LegacySubscriptionFacade {
    // Raw topics of v1: retained values are delivered, as v1 did.
    private static final boolean ACCEPT_RETAINED = true;
    // v1 had no per-block limit, so blocks keep the 64 KiB of a mirror source instead of the 16 KiB of a Raw Link.
    private static final int MAX_PAYLOAD_BYTES = SubOptions.MIRROR.maxPayloadBytes();
    private static final int MAX_LOGGED_REFUSALS = 256;

    // Target -> filter -> its subscription; null while not open (before attach, or refused by the ACL).
    private static final Map<ICallbackTarget, Map<String, Subscription>> registrations = new IdentityHashMap<>();
    // Topics a block was refused to publish to, so a sensor publishing every tick logs it once.
    private static final Set<String> loggedRefusals = new HashSet<>();
    // The refusal each block's topic was last seen with, so its editor is told once.
    private static final NoticeGate<BlockTopic> refusals = new NoticeGate<>();
    private static MineqttRuntime runtime;

    private record BlockTopic(UUID origin, String topic) {
    }

    private LegacySubscriptionFacade() {
    }

    /** {@code SERVER_STARTED}: opens the subscriptions of the blocks that registered while the server started. */
    public static void attach(MineqttRuntime started) {
        runtime = started;
        for (ICallbackTarget target : new ArrayList<>(registrations.keySet())) {
            if (isStale(target)) {
                unregister(target);
            } else {
                registrations.get(target).replaceAll((filter, subscription) -> subscription != null ? subscription : open(filter, target, false));
            }
        }
    }

    /** {@code SERVER_STOPPING}: closes every subscription; the blocks of the next server register anew. */
    public static void detach() {
        registrations.values().forEach(filters -> filters.values().forEach(LegacySubscriptionFacade::close));
        registrations.clear();
        loggedRefusals.clear();
        refusals.clear();
        runtime = null;
    }

    /**
     * The ACL changed (a config reload): a subscription it refused before opens now if allowed, an open one it refuses
     * now closes, without a restart. Publishes are judged when they happen.
     */
    public static void aclChanged() {
        if (runtime == null) return;
        // A publish refused, allowed and refused again is logged again. The notice gate stays: it forgets a refusal once
        // it heals, so a refusal the reload left as it was is not told again.
        loggedRefusals.clear();
        for (ICallbackTarget target : new ArrayList<>(registrations.keySet())) {
            if (isStale(target)) {
                unregister(target);
                continue;
            }
            rejudge(registrations.get(target), filter -> runtime.acl().checkLegacy(filter, false).allowed(),
                    filter -> open(filter, target, true), LegacySubscriptionFacade::close);
        }
    }

    /**
     * One block's subscriptions under a changed ACL: an open one the ACL still allows stays as it is, so no retained
     * value is delivered again; every other one is closed and opened again, which opens it only if the ACL allows it now.
     *
     * @param filters each filter and its subscription, null while not open; replaced in place
     * @param open    opens a filter after judging it again; null when refused
     */
    public static <S> void rejudge(Map<String, S> filters, Predicate<String> allowed, Function<String, S> open, Consumer<S> close) {
        filters.replaceAll((filter, subscription) -> {
            if (subscription != null && allowed.test(filter)) return subscription;
            if (subscription != null) close.accept(subscription);
            return open.apply(filter);
        });
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
        filters.put(filter, runtime == null ? null : open(filter, target, false));
    }

    public static void unsubscribe(String filter, ICallbackTarget target) {
        if (!isServerSide(target)) return;
        Map<String, Subscription> filters = registrations.get(target);
        if (filters == null || !filters.containsKey(filter)) return;
        close(filters.remove(filter));
        if (filters.isEmpty()) registrations.remove(target);
        // Set again later, a refused filter is told again.
        refusals.forget(new BlockTopic(origin(target.getLevel(), target.getBlockPos()), filter));
        MineQTT.LOGGER.info("Unsubscribed {} from MQTT topic {}", target.getBlockPos().toShortString(), filter);
    }

    /**
     * A block publishes with v1's flags: QoS 0, not retained, dropped while the broker is unreachable (blocks on this
     * server still receive it), an edge that is never coalesced.
     *
     * @return why nothing was published; also logged once per topic
     */
    public static Optional<String> publish(Level level, BlockPos pos, String topic, String payload) {
        if (!(level instanceof ServerLevel)) return Optional.of("only the server publishes");
        Optional<String> refusal = send(topic, payload.getBytes(StandardCharsets.UTF_8), origin(level, pos));
        refusal.ifPresent(reason -> {
            if (loggedRefusals.size() < MAX_LOGGED_REFUSALS && loggedRefusals.add(topic)) {
                MineQTT.LOGGER.warn("Block at {} does not publish to '{}': {}", pos.toShortString(), topic, reason);
            }
        });
        refused(level, pos, topic, refusal.orElse(null), false);
        return refusal;
    }

    public static boolean isConnected() {
        return MineqttRuntime.current().map(running -> running.mqtt().state().isConnected()).orElse(false);
    }

    /** A block's origin: the same for every publish and subscription of that position. */
    public static UUID origin(Level level, BlockPos pos) {
        return UUID.nameUUIDFromBytes((level.dimension().identifier() + ":" + pos.asLong()).getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<String> send(String topic, byte[] payload, UUID origin) {
        Optional<MineqttRuntime> running = MineqttRuntime.current();
        if (running.isEmpty()) return Optional.of("MineQTT is not running");
        TopicAcl.Decision decision = running.get().acl().checkLegacy(topic, true);
        if (!decision.allowed()) return Optional.of(decision.message());
        running.get().mqtt().publish(new Outbound(topic, payload, 0, false, OfflinePolicy.DROP, Outbound.Delivery.EDGE, origin));
        return Optional.empty();
    }

    /** @param reload opened again for a changed ACL ({@link #refused}) */
    private static Subscription open(String filter, ICallbackTarget target, boolean reload) {
        TopicAcl.Decision decision = runtime.acl().checkLegacy(filter, false);
        boolean news = refused(target.getLevel(), target.getBlockPos(), filter, decision.allowed() ? null : decision.message(), reload);
        if (!decision.allowed()) {
            if (news) MineQTT.LOGGER.warn("Block at {} does not subscribe to '{}': {}", target.getBlockPos().toShortString(), filter, decision.message());
            return null;
        }
        MineQTT.LOGGER.info("Subscribed {} to MQTT topic {}", target.getBlockPos().toShortString(), filter);
        // No-local plus loopback, like a Raw Link input.
        SubOptions options = new SubOptions(1, ACCEPT_RETAINED, true, MAX_PAYLOAD_BYTES, origin(target.getLevel(), target.getBlockPos()));
        return runtime.mqtt().subscribe(filter, options, message -> deliver(target, message));
    }

    /**
     * Records whether a block's topic is refused and tells a new refusal as a device's is told (M2 plan D1): a chat line
     * to the player who edited the block moments ago ({@link BaseMqttBlockEntity#recentEditor}), else, for a config
     * reload ({@code reload}), to the owner while online; plus the owner's action bar. Without an editor, a block that
     * loads with its topic refused, or whose publish is refused, is only logged: nothing changed that the owner did not
     * hear about. The owner is found by UUID (M2 plan D7): an owner name an online-mode server has not resolved yet
     * reaches nobody.
     *
     * @return whether the refusal is news
     */
    private static boolean refused(Level level, BlockPos pos, String topic, @Nullable String reason, boolean reload) {
        if (!refusals.update(new BlockTopic(origin(level, pos), topic), reason)) return false;
        // Never loads a chunk: a block whose chunk is not loaded was edited by nobody moments ago.
        if (!(level instanceof ServerLevel serverLevel) || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof BaseMqttBlockEntity block)) {
            return true;
        }
        PlayerList players = serverLevel.getServer().getPlayerList();
        ServerPlayer editor = block.recentEditor() == null ? null : players.getPlayer(block.recentEditor());
        UUID ownerId = AclContext.knownOwner(serverLevel.getServer(), block.getOwnerUuid(), block.getOwnerName());
        ServerPlayer owner = ownerId == null ? null : players.getPlayer(ownerId);
        Component notice = Component.translatable("mineqtt.legacy.refused", block.getDisplayName(), topic, reason);
        if (editor != null) {
            editor.sendSystemMessage(notice);
        } else if (reload && owner != null) {
            owner.sendSystemMessage(notice);
        } else {
            return true;
        }
        if (owner != null) owner.sendSystemMessage(notice, true);
        return true;
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
        // A broker message: whatever the block changes in the world, and what that powers in the same tick, is RAW.
        runtime.devices().fromBroker(() -> target.onMessageReceived(message.topic(), message.text()));
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

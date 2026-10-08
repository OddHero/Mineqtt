package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The one gate every client-to-server payload passes (design §8.5): the client is never trusted. A block payload is
 * accepted only from a player who has that block's screen open, is in reach of a loaded block entity of the expected
 * type and holds the permission it needs; a Cyberdeck payload only from a player with the Cyberdeck screen open. Every
 * player may send {@value #PACKETS_PER_SECOND} payloads per second; a rejected payload counts too, so a flood is cut
 * off before any lookup. Rejections are dropped silently, like vanilla does, and logged at debug. Server thread only.
 */
public final class C2SGuard {
    public static final int PACKETS_PER_SECOND = 20;
    /** The largest message payload a player may enter. */
    public static final int MAX_PAYLOAD_BYTES = 1024;
    // Configurator range is M2; a block screen needs the vanilla reach plus one block of slack for latency.
    private static final double REACH_SLACK = 1.0;
    private static final RateLimit PACKETS = new RateLimit(PACKETS_PER_SECOND);

    /** Why a payload was dropped, in the order the guard checks. */
    public enum Rejection {
        RATE_LIMITED,
        NO_MENU,
        MENU_INVALID,
        NOT_LOADED,
        OUT_OF_REACH,
        WRONG_BLOCK_ENTITY,
        DENIED
    }

    /**
     * What the guard asks, lazily and in this order, so a payload that fails early costs no block entity lookup or
     * claim check.
     */
    record Facts(BooleanSupplier tokenTaken, BooleanSupplier menuOpen, BooleanSupplier menuValid, BooleanSupplier loaded,
                 BooleanSupplier inReach, BooleanSupplier blockEntityMatches, BooleanSupplier permitted) {
    }

    private C2SGuard() {
    }

    /**
     * The block entity at {@code pos} if the player may act on it with {@code need}.
     *
     * @param payload the payload, named in the debug log
     */
    public static <T extends BlockEntity> Optional<T> require(ServerPlayer player, CustomPacketPayload payload, BlockPos pos, Class<T> type, MineqttPermission need) {
        Optional<Rejection> rejection = decide(new Facts(
                () -> PACKETS.tryTake(player.getUUID()),
                () -> player.containerMenu instanceof TabbedMqttMenu menu && pos.equals(menu.blockPos),
                () -> player.containerMenu.stillValid(player),
                () -> player.level().isLoaded(pos),
                () -> player.isWithinBlockInteractionRange(pos, REACH_SLACK),
                () -> type.isInstance(player.level().getBlockEntity(pos)),
                () -> DeviceAccess.check(player, player.level().getBlockEntity(pos), need)));
        if (rejection.isPresent()) {
            log(player, payload, rejection.get());
            return Optional.empty();
        }
        return Optional.of(type.cast(player.level().getBlockEntity(pos)));
    }

    /**
     * The open Cyberdeck screen, if the player has one; it is only valid while the player holds that Cyberdeck.
     *
     * @param payload the payload, named in the debug log
     */
    public static Optional<CyberdeckMenu> requireCyberdeck(ServerPlayer player, CustomPacketPayload payload) {
        Optional<Rejection> rejection = decide(new Facts(
                () -> PACKETS.tryTake(player.getUUID()),
                () -> player.containerMenu instanceof CyberdeckMenu,
                () -> player.containerMenu.stillValid(player),
                () -> true, () -> true, () -> true, () -> true));
        if (rejection.isPresent()) {
            log(player, payload, rejection.get());
            return Optional.empty();
        }
        return Optional.of((CyberdeckMenu) player.containerMenu);
    }

    /** Whether a player-entered topic fits the topic limit; the {@link TopicAcl} validates it further where it is used. */
    public static boolean fitsTopic(String topic) {
        return topic.getBytes(StandardCharsets.UTF_8).length <= TopicAcl.MAX_TOPIC_BYTES;
    }

    /** Whether a player-entered message payload fits {@value #MAX_PAYLOAD_BYTES} bytes. */
    public static boolean fitsPayload(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES;
    }

    /** The player left; their rate limit starts afresh. */
    public static void forget(UUID player) {
        PACKETS.forget(player);
    }

    public static void clear() {
        PACKETS.clear();
    }

    static Optional<Rejection> decide(Facts facts) {
        if (!facts.tokenTaken().getAsBoolean()) return Optional.of(Rejection.RATE_LIMITED);
        if (!facts.menuOpen().getAsBoolean()) return Optional.of(Rejection.NO_MENU);
        if (!facts.menuValid().getAsBoolean()) return Optional.of(Rejection.MENU_INVALID);
        if (!facts.loaded().getAsBoolean()) return Optional.of(Rejection.NOT_LOADED);
        if (!facts.inReach().getAsBoolean()) return Optional.of(Rejection.OUT_OF_REACH);
        if (!facts.blockEntityMatches().getAsBoolean()) return Optional.of(Rejection.WRONG_BLOCK_ENTITY);
        if (!facts.permitted().getAsBoolean()) return Optional.of(Rejection.DENIED);
        return Optional.empty();
    }

    private static void log(ServerPlayer player, CustomPacketPayload payload, Rejection rejection) {
        MineQTT.LOGGER.debug("Dropped {} from {}: {}", payload.type().id(), player.getGameProfile().name(), rejection);
    }
}

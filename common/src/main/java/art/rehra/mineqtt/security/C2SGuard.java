package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The one gate every client-to-server payload passes (design §8.5): the client is never trusted. A block payload is
 * accepted only from a player who has that block's screen open, {@link #reaches reaches} a loaded block entity of the
 * expected type (from afar with a Configurator in hand, if they may build) and holds the permission it needs; a Cyberdeck payload only from
 * a player with the Cyberdeck screen open; a Configurator's Link mode pick or Group mode name only while it answers the
 * player's open offer ({@link #requireOffer}). A spectator only looks, as vanilla lets them look into a chest: none of their
 * payloads is accepted. Every player may send {@value #PACKETS_PER_SECOND} payloads per second; a rejected payload
 * counts too, so a flood is cut off before any lookup. Rejections are dropped silently, like vanilla does, and logged at
 * debug. Server thread only.
 */
public final class C2SGuard {
    public static final int PACKETS_PER_SECOND = 20;
    /** The largest message payload a player may enter. */
    public static final int MAX_PAYLOAD_BYTES = 1024;
    // NBT accounts a string as 36 bytes plus 2 per character, so 8 KiB holds a payload of 1 KiB with room to spare.
    private static final long MAX_VALUE_NBT_BYTES = 8 * 1024;
    private static final int MAX_VALUE_NBT_DEPTH = 4;
    /** How far the Configurator reaches (design §7.4): it opens a device's screen from up to this many blocks away. */
    public static final double CONFIGURATOR_RANGE = 16.0;
    // A block screen needs the reach plus one block of slack for latency.
    private static final double REACH_SLACK = 1.0;
    private static final RateLimit PACKETS = new RateLimit(PACKETS_PER_SECOND);

    /** Why a payload was dropped, in the order the guard checks. */
    public enum Rejection {
        RATE_LIMITED,
        SPECTATOR,
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
    record Facts(BooleanSupplier tokenTaken, BooleanSupplier acting, BooleanSupplier menuOpen, BooleanSupplier menuValid,
                 BooleanSupplier loaded, BooleanSupplier inReach, BooleanSupplier blockEntityMatches, BooleanSupplier permitted) {
    }

    private C2SGuard() {
    }

    /**
     * The block entity at {@code pos} if the player may act on it with {@code need}.
     *
     * @param payload the payload, named in the debug log
     * @param type    the block entity's class, or an interface it implements ({@code TabbedScreenHost}: a device's or
     *                another block's)
     */
    public static <T> Optional<T> require(ServerPlayer player, CustomPacketPayload payload, BlockPos pos, Class<T> type, MineqttPermission need) {
        return require(player, payload, pos, type::isInstance, blockEntity -> need).map(type::cast);
    }

    /**
     * The device block entity at {@code pos} if the player may act on it with {@code need}: the device payloads of the
     * generated screens (M2-6), whatever block entity class hosts the device ({@code DeviceBlockEntity} since M2-12).
     *
     * @param payload the payload, named in the debug log
     */
    public static Optional<BlockEntity> requireDevice(ServerPlayer player, CustomPacketPayload payload, BlockPos pos, MineqttPermission need) {
        return requireDevice(player, payload, pos, type -> need);
    }

    /**
     * {@link #requireDevice(ServerPlayer, CustomPacketPayload, BlockPos, MineqttPermission)} for a payload whose need
     * depends on the device's type, such as a {@code DeviceConfigC2S} key ({@code DeviceConfig#permission}).
     *
     * @param need the permission the payload needs on a device of the given type
     */
    public static Optional<BlockEntity> requireDevice(ServerPlayer player, CustomPacketPayload payload, BlockPos pos, Function<DeviceType, MineqttPermission> need) {
        return require(player, payload, pos, DeviceHost.class::isInstance, device -> need.apply(((DeviceHost) device).deviceType()));
    }

    /**
     * The bounds of an NBT value a client sends (a setting of {@code DeviceConfigC2S}): the largest legitimate one is a
     * payload of {@value #MAX_PAYLOAD_BYTES} bytes, and settings are flat.
     */
    public static NbtAccounter valueQuota() {
        return new NbtAccounter(MAX_VALUE_NBT_BYTES, MAX_VALUE_NBT_DEPTH);
    }

    // need is asked last, of the block entity that matched.
    private static Optional<BlockEntity> require(ServerPlayer player, CustomPacketPayload payload, BlockPos pos, Predicate<BlockEntity> matches,
                                                 Function<BlockEntity, MineqttPermission> need) {
        Optional<Rejection> rejection = decide(new Facts(
                () -> PACKETS.tryTake(player.getUUID()),
                () -> !player.isSpectator(),
                () -> player.containerMenu instanceof TabbedMqttMenu menu && pos.equals(menu.blockPos),
                () -> player.containerMenu.stillValid(player),
                () -> player.level().isLoaded(pos),
                () -> reaches(player, player.level(), pos, REACH_SLACK),
                () -> matches.test(player.level().getBlockEntity(pos)),
                () -> {
                    BlockEntity blockEntity = player.level().getBlockEntity(pos);
                    return DeviceAccess.check(player, blockEntity, need.apply(blockEntity));
                }));
        if (rejection.isPresent()) {
            log(player, payload, rejection.get());
            return Optional.empty();
        }
        return Optional.of(player.level().getBlockEntity(pos));
    }

    /**
     * The open Cyberdeck screen, if the player has one; it is only valid while the player holds that Cyberdeck.
     *
     * @param payload the payload, named in the debug log
     */
    public static Optional<CyberdeckMenu> requireCyberdeck(ServerPlayer player, CustomPacketPayload payload) {
        Optional<Rejection> rejection = decide(new Facts(
                () -> PACKETS.tryTake(player.getUUID()),
                () -> !player.isSpectator(),
                () -> player.containerMenu instanceof CyberdeckMenu,
                () -> player.containerMenu.stillValid(player),
                () -> true, () -> true, () -> true, () -> true));
        if (rejection.isPresent()) {
            log(player, payload, rejection.get());
            return Optional.empty();
        }
        return Optional.of((CyberdeckMenu) player.containerMenu);
    }

    /**
     * The device a Configurator answer was offered for, if the player may act on it: a Link mode pick
     * ({@code LinkChoiceC2S}, M2-16) or a Group mode group name ({@code GroupNameC2S}, M2-17). The answer must be to the
     * offer the server made them, they hold a Configurator in that mode, the device they clicked second is still loaded
     * where it was and within the Configurator's reach, and they may configure both devices. The offer takes the place
     * of a screen, as the picker and the prompt are client screens with no menu.
     *
     * @param mode             the Configurator mode the offer belongs to
     * @param offerOpen        whether the payload answers the player's open offer
     * @param target           where the device the player clicked second was, or null without an offer
     * @param targetId         that device's id, or null without an offer
     * @param configuresSource whether the player may configure the device they clicked first
     * @param configuresTarget whether they may configure the device they clicked second
     */
    public static Optional<BlockEntity> requireOffer(ServerPlayer player, CustomPacketPayload payload, ConfiguratorMode mode, boolean offerOpen,
                                                     @Nullable GlobalPos target, @Nullable UUID targetId, BooleanSupplier configuresSource,
                                                     BooleanSupplier configuresTarget) {
        Optional<Rejection> rejection = decide(offerFacts(
                () -> PACKETS.tryTake(player.getUUID()),
                () -> !player.isSpectator(),
                offerOpen,
                () -> holdsConfigurator(player) && CyberdeckDataUtil.mode(CyberdeckMenu.heldConfigurator(player)) == mode,
                () -> player.level().dimension().equals(target.dimension()) && player.level().isLoaded(target.pos()),
                () -> reaches(player, player.level(), target.pos(), REACH_SLACK),
                () -> player.level().getBlockEntity(target.pos()) instanceof DeviceHost host && targetId.equals(host.deviceLink().id()),
                configuresSource, configuresTarget));
        if (rejection.isPresent()) {
            log(player, payload, rejection.get());
            return Optional.empty();
        }
        return Optional.of(player.level().getBlockEntity(target.pos()));
    }

    /**
     * What {@link #requireOffer} asks, in the guard's order: the open offer stands for the open screen, the Configurator
     * in the offer's mode for the screen being valid, the device clicked second for the block entity, and CONFIGURE on
     * both devices for the permission.
     */
    static Facts offerFacts(BooleanSupplier tokenTaken, BooleanSupplier acting, boolean offerOpen, BooleanSupplier inMode, BooleanSupplier loaded,
                            BooleanSupplier inReach, BooleanSupplier sameDevice, BooleanSupplier configuresSource, BooleanSupplier configuresTarget) {
        return new Facts(tokenTaken, acting, () -> offerOpen, inMode, loaded, inReach, sameDevice,
                () -> configuresSource.getAsBoolean() && configuresTarget.getAsBoolean());
    }

    /**
     * Whether the player reaches the block at {@code pos} in {@code level}: in the same dimension, within their block
     * interaction range, or within {@link #CONFIGURATOR_RANGE} while they hold a Configurator in either hand (the range
     * variant of design §8.5, tied to the item in hand: putting it away ends it) and may build: a player who may not
     * ({@link DeviceAccess#mayBuild}, P2) only uses a device within reach, as the Configurator opens nothing from afar for
     * them, so their screen does not stay open from afar either. From afar the level must let them
     * interact with the block ({@link Level#mayInteract}: spawn protection, the world border), as vanilla asks before a
     * click within reach, which is the only way a screen within reach opens.
     *
     * @param slack blocks beyond either range that still count, for latency
     */
    public static boolean reaches(Player player, Level level, BlockPos pos, double slack) {
        return reaches(player.level() == level, player.isWithinBlockInteractionRange(pos, slack),
                holdsConfigurator(player) && DeviceAccess.mayBuild(player),
                () -> level.mayInteract(player, pos), new AABB(pos).distanceToSqr(player.getEyePosition()), slack);
    }

    /**
     * The pure rule of {@link #reaches(Player, Level, BlockPos, double)}.
     *
     * @param inReach     whether the block is within the player's block interaction range plus {@code slack}
     * @param mayInteract whether the level lets the player interact with the block; asked only from afar
     * @param distanceSqr the squared distance from the player's eyes to the block's cube, as the interaction range
     *                    measures it
     */
    static boolean reaches(boolean sameLevel, boolean inReach, boolean holdsConfigurator, BooleanSupplier mayInteract, double distanceSqr, double slack) {
        if (!sameLevel) return false;
        if (inReach) return true;
        double range = CONFIGURATOR_RANGE + slack;
        return holdsConfigurator && distanceSqr < range * range && mayInteract.getAsBoolean();
    }

    /** Whether the player holds a Configurator (the {@code cyberdeck} item) in either hand. */
    public static boolean holdsConfigurator(Player player) {
        return !CyberdeckMenu.heldConfigurator(player).isEmpty();
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
        if (!facts.acting().getAsBoolean()) return Optional.of(Rejection.SPECTATOR);
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

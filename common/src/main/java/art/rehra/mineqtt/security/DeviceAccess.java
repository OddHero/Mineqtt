package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.blocks.entities.TerminalBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.integrations.MineqttPermission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Whether a player may use or configure a MineQTT block (design §8.7). Operators and the owner always may; otherwise
 * the device's {@link AccessMode} decides, with the claim at the block (Flan) as the trust check.
 * <p>
 * The owner is matched by UUID only (M2 plan D7): a player who merely carries the owner's name (an alt named after a
 * renamed account) is not the owner. An unmigrated v1 block has no access mode, so it counts as {@link AccessMode#CLAIM}:
 * without a claim mod everyone may use and configure it, as in v1. Its owner is a name until the name is resolved to
 * the block's {@code ownerUuid} at load or at the owner's click; a v1 device's owner name is resolved at its attach or
 * when its owner joins. Only an online player of exactly that name, or an online-mode server's profile lookup, binds it.
 * Until then a server without authentication matches the UUID derived from the name ({@link AclContext#offlineOwner});
 * on an online-mode server the owner gets only what the access mode gives everyone.
 * <p>
 * A player who may not build ({@link #mayBuild}: adventure or spectator mode, or abilities taken by anything else) only
 * uses MineQTT blocks (P2): they never configure one, expose it or set its access, an operator or the owner neither, as
 * vanilla lets no operator in adventure mode break a block. Whether they may use it stays the access mode's decision.
 */
public final class DeviceAccess {
    private DeviceAccess() {
    }

    /**
     * A loaded MineQTT block. The claim is only asked when the player is in the block's dimension, as for a record: the
     * claim API reads the player's level, so from another dimension it would answer for the same coordinates there.
     */
    public static boolean check(ServerPlayer player, BlockEntity blockEntity, MineqttPermission need) {
        boolean op = AclContext.isOp(player.permissions());
        boolean mayBuild = mayBuild(player);
        BooleanSupplier claimTrust = claimTrust(sameLevel(player, blockEntity),
                () -> MineQTT.permissionManager.canInteract(player, blockEntity.getBlockPos(), need));
        if (blockEntity instanceof DeviceHost host) {
            return permits(need, host.deviceLink().access(), op, isOwner(player, blockEntity), mayBuild, claimTrust);
        }
        if (blockEntity instanceof BaseMqttBlockEntity) {
            return permits(need, AccessMode.CLAIM, op, isOwner(player, blockEntity), mayBuild, claimTrust);
        }
        if (blockEntity instanceof TerminalBlockEntity) {
            return consolePermits(need, op, mayBuild, claimTrust);
        }
        return op && (mayBuild || need == MineqttPermission.USE);
    }

    /**
     * Whether a player may build, and so configure MineQTT blocks (P2): their abilities let them build
     * ({@link Player#mayBuild()}, which vanilla takes away in adventure and spectator mode) and they are no spectator.
     * A player who may not only uses the blocks: a device's screen shows them its Controls tab alone, and the other
     * screens, the paper, the name tag and the Configurator are refused them.
     */
    public static boolean mayBuild(Player player) {
        return player.mayBuild() && !player.isSpectator();
    }

    /**
     * The Bridge Console's rule (M2 plan §4.2 M2-23): anyone the claim at the console trusts may open it (USE), as a
     * block without an owner, and sees only the devices and groups they may configure; its actions (CONFIGURE) act on the
     * whole bridge, so only an operator who may build may run them, whatever the claim says.
     *
     * @param mayBuild whether the player may build ({@link #mayBuild(Player)})
     */
    public static boolean consolePermits(MineqttPermission need, boolean op, boolean mayBuild, BooleanSupplier claimTrust) {
        if (need == MineqttPermission.USE) return permits(need, AccessMode.CLAIM, op, false, claimTrust);
        return op && mayBuild;
    }

    /**
     * What a player may change on a device (design §8.7, M2-6): use it, configure it, expose it to Home Assistant
     * (CONFIGURE and {@link AclContext#mayExpose}) and set its access mode (the owner or an operator). A spectator may
     * change nothing, whoever they are: they only look at a device's Controls tab, as into a chest ({@code C2SGuard}).
     */
    public static DeviceView.Grants grants(ServerPlayer player, BlockEntity blockEntity) {
        if (player.isSpectator()) return DeviceView.Grants.NONE;
        AccessMode access = ((DeviceHost) blockEntity).deviceLink().access();
        boolean sameLevel = sameLevel(player, blockEntity);
        return grants(AclContext.isOp(player.permissions()), isOwner(player, blockEntity), mayBuild(player), access,
                need -> claimTrust(sameLevel, () -> MineQTT.permissionManager.canInteract(player, blockEntity.getBlockPos(), need)).getAsBoolean(),
                AclContext.mayExpose(player.permissions()));
    }

    /**
     * The decision behind {@link #grants(ServerPlayer, BlockEntity)}: USE and CONFIGURE as {@link #permits} gives them,
     * the exposure on top of CONFIGURE, and the access mode only to the owner or an operator (M2-11), whatever the mode
     * and the claim say. Nobody else may change who has access, so no access mode can lock the owner out. A player who
     * may not build gets USE at most (P2).
     *
     * @param mayBuild   whether the player may build ({@link #mayBuild(Player)})
     * @param claimTrust whether the claim at the device trusts the player with a permission; asked only when it matters
     * @param mayExpose  whether the player holds the right to expose devices ({@link AclContext#mayExpose})
     */
    public static DeviceView.Grants grants(boolean op, boolean owner, boolean mayBuild, AccessMode access, Predicate<MineqttPermission> claimTrust,
                                           boolean mayExpose) {
        boolean use = permits(MineqttPermission.USE, access, op, owner, mayBuild, () -> claimTrust.test(MineqttPermission.USE));
        boolean configure = permits(MineqttPermission.CONFIGURE, access, op, owner, mayBuild, () -> claimTrust.test(MineqttPermission.CONFIGURE));
        return new DeviceView.Grants(use, configure, configure && mayExpose, mayBuild && (op || owner));
    }

    /**
     * Whether a device has an owner (M2-11), as its Access tab shows it.
     *
     * @param owner      the owner's UUID, or null
     * @param ownerName  the owner's name, or a v1 owner name not resolved yet; empty when there is none
     * @param onlineMode whether the server authenticates players: without, a name stands for the UUID derived from it
     */
    public static DeviceView.Owner owner(@Nullable UUID owner, String ownerName, boolean onlineMode) {
        if (owner != null) return DeviceView.Owner.KNOWN;
        if (ownerName.isEmpty()) return DeviceView.Owner.NOBODY;
        return onlineMode ? DeviceView.Owner.NOT_VERIFIED : DeviceView.Owner.KNOWN;
    }

    /**
     * Whether a player may claim a device (M2-11): it has no owner, not even a v1 owner name still to resolve, and they
     * may configure it. The claimer becomes its owner.
     */
    public static boolean mayClaim(DeviceView.Grants grants, DeviceView.Owner owner) {
        return owner == DeviceView.Owner.NOBODY && grants.configure();
    }

    /**
     * Whether the player owns a MineQTT block, by UUID. A legacy block whose owner name is not resolved yet asks for the
     * lookup, which binds an online owner of exactly that name at once, so the owner's first click already counts.
     */
    public static boolean isOwner(ServerPlayer player, BlockEntity blockEntity) {
        boolean onlineMode = player.level().getServer().usesAuthentication();
        if (blockEntity instanceof DeviceHost host) {
            DeviceLink link = host.deviceLink();
            return legacyOwnerMatches(link.owner(), link.ownerName(), onlineMode, player.getUUID());
        }
        if (blockEntity instanceof BaseMqttBlockEntity legacy) {
            if (legacy.getOwnerUuid() == null) DeviceRuntime.resolveLegacyOwner(legacy);
            return legacyOwnerMatches(legacy.getOwnerUuid(), legacy.getOwnerName(), onlineMode, player.getUUID());
        }
        return false;
    }

    /**
     * A registered device that may be unloaded or far away, e.g. for the explorer. The claim is only asked in the
     * player's own dimension, since the claim API reads the player's level.
     */
    public static boolean check(ServerPlayer player, DeviceRecord record, MineqttPermission need) {
        boolean sameLevel = player.level().dimension().equals(record.pos().dimension());
        return permits(need, record.access(), AclContext.isOp(player.permissions()), player.getUUID().equals(record.owner()), mayBuild(player),
                claimTrust(sameLevel, () -> MineQTT.permissionManager.canInteract(player, record.pos().pos(), need)));
    }

    /**
     * The claim's trust in a player at a device, which only counts in the device's own dimension: the claim API reads
     * the player's level. From another dimension it is refused without asking the claim.
     *
     * @param sameLevel whether the player is in the device's dimension
     * @param claim     whether the claim at the device's coordinates in the player's level trusts them
     */
    static BooleanSupplier claimTrust(boolean sameLevel, BooleanSupplier claim) {
        return () -> sameLevel && claim.getAsBoolean();
    }

    private static boolean sameLevel(ServerPlayer player, BlockEntity blockEntity) {
        Level level = blockEntity.getLevel();
        return level != null && level.dimension().equals(player.level().dimension());
    }

    /**
     * Whether a player is the owner of a block that names its owner by UUID, or by a v1 owner name not resolved yet
     * (M2 plan D7). The name alone never matches: on a server without authentication it stands for the UUID derived from
     * it, which is the UUID of the player of exactly that name there; on an online-mode server it matches nobody until
     * it is resolved.
     *
     * @param ownerUuid  the resolved owner, or null
     * @param ownerName  the v1 owner name, empty when there is none
     * @param onlineMode whether the server authenticates players ({@code online-mode})
     * @param player     the asking player's UUID
     */
    public static boolean legacyOwnerMatches(@Nullable UUID ownerUuid, String ownerName, boolean onlineMode, UUID player) {
        UUID owner = ownerUuid != null ? ownerUuid : AclContext.offlineOwner(ownerName, onlineMode);
        return player.equals(owner);
    }

    /**
     * The decision for a player: one who may not build only uses (P2), so anything but USE is refused them before the
     * access mode is asked, an operator and the owner included.
     *
     * @param mayBuild   whether the player may build ({@link #mayBuild(Player)})
     * @param claimTrust whether the claim at the device trusts the player with {@code need}; asked only when it matters
     */
    public static boolean permits(MineqttPermission need, AccessMode access, boolean op, boolean owner, boolean mayBuild, BooleanSupplier claimTrust) {
        return (mayBuild || need == MineqttPermission.USE) && permits(need, access, op, owner, claimTrust);
    }

    /**
     * The access mode's decision, for a player who may build.
     *
     * @param claimTrust whether the claim at the device trusts the player with {@code need}; asked only when it matters
     */
    public static boolean permits(MineqttPermission need, AccessMode access, boolean op, boolean owner, BooleanSupplier claimTrust) {
        if (op || owner) return true;
        return switch (access) {
            case PRIVATE -> false;
            case PUBLIC -> need == MineqttPermission.USE || claimTrust.getAsBoolean();
            case CLAIM -> claimTrust.getAsBoolean();
        };
    }
}

package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.integrations.MineqttPermission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.BooleanSupplier;

/**
 * Whether a player may use or configure a MineQTT block (design §8.7). Operators and the owner always may; otherwise
 * the device's {@link AccessMode} decides, with the claim at the block (Flan) as the trust check.
 * <p>
 * A device names its owner by UUID. An unmigrated v1 block only knows its owner's name and has no access mode, so it
 * counts as {@link AccessMode#CLAIM}: without a claim mod everyone may use and configure it, as in v1.
 */
public final class DeviceAccess {
    private DeviceAccess() {
    }

    public static boolean check(ServerPlayer player, BlockEntity blockEntity, MineqttPermission need) {
        boolean op = AclContext.isOp(player.permissions());
        BooleanSupplier claimTrust = () -> MineQTT.permissionManager.canInteract(player, blockEntity.getBlockPos(), need);
        if (blockEntity instanceof DeviceHost host) {
            DeviceLink link = host.deviceLink();
            return permits(need, link.access(), op, player.getUUID().equals(link.owner()), claimTrust);
        }
        if (blockEntity instanceof BaseMqttBlockEntity legacy) {
            boolean owner = !legacy.getOwnerName().isEmpty() && legacy.getOwnerName().equals(player.getGameProfile().name());
            return permits(need, AccessMode.CLAIM, op, owner, claimTrust);
        }
        return op;
    }

    /**
     * A registered device that may be unloaded or far away, e.g. for the explorer. The claim is only asked in the
     * player's own dimension, since the claim API reads the player's level.
     */
    public static boolean check(ServerPlayer player, DeviceRecord record, MineqttPermission need) {
        boolean sameLevel = player.level().dimension().equals(record.pos().dimension());
        return permits(need, record.access(), AclContext.isOp(player.permissions()), player.getUUID().equals(record.owner()),
                () -> sameLevel && MineQTT.permissionManager.canInteract(player, record.pos().pos(), need));
    }

    /**
     * The decision itself.
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

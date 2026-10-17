package art.rehra.mineqtt.items;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.LockTargets;
import art.rehra.mineqtt.compat.ContainerLocks;
import art.rehra.mineqtt.integrations.MineqttPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

/**
 * The Smart Lock's item: used on a door, trapdoor, fence gate or container it puts the lock's plate onto that target,
 * on the face the player clicked or, when that is taken, on the free face of the target (both doors of a double door,
 * both halves of a double chest) nearest the player ({@link LockTargets#spots}). The click goes to the item, not the
 * target: a door does not swing and a chest does not open ({@code SmartLocks} hands the click over before vanilla
 * uses the block). It is refused, in the action bar, on a block that cannot be locked, on a target that already has a
 * Smart Lock or another lock, where the player may not build or use the target (claims, spawn protection), and when no
 * face of the target is free. Everything a device's item does besides applies (the device limit, the placer as owner).
 */
public class SmartLockItem extends DeviceBlockItem {
    public SmartLockItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * Used on a block without {@code SmartLocks} taking the click first (another mod answered it before): onto a target
     * as {@link #placeOn}, anywhere else refused with a hint. Neither side predicts a plate.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        boolean lockable = LockTargets.lockable(LockTargets.Lookup.of(context.getLevel()), context.getClickedPos());
        if (context.getPlayer() instanceof ServerPlayer player) {
            if (lockable) return placeOn(player, context.getHand(), context.getClickedPos(), context.getClickedFace());
            player.sendSystemMessage(Component.translatable("mineqtt.smart_lock.place_hint"), true);
        }
        return lockable ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    /**
     * Puts the plate onto the target at {@code target}, clicked on {@code face}: on the server, for the player's hand.
     * Every refusal is told in the action bar.
     */
    public InteractionResult placeOn(ServerPlayer player, InteractionHand hand, BlockPos target, Direction face) {
        ServerLevel level = (ServerLevel) player.level();
        LockTargets.Lookup lookup = LockTargets.Lookup.of(level);
        List<BlockPos> group = LockTargets.group(lookup, target);
        if (group.isEmpty()) return refuse(player, "place_hint");
        if (!LockTargets.plates(lookup, target).isEmpty()) return refuse(player, "already_locked");
        for (BlockPos part : group) {
            if (level.getBlockEntity(part) instanceof BaseContainerBlockEntity container && ContainerLocks.foreign(container)) return refuse(player, "foreign_lock");
        }
        // Locking a target keeps others out of it: only who may build there and use it may lock it.
        for (BlockPos part : group) {
            if (!level.mayInteract(player, part) || !MineQTT.permissionManager.canPlace(player, part)
                    || !MineQTT.permissionManager.canInteract(player, part, MineqttPermission.USE)) {
                return refuse(player, "claimed");
            }
        }
        ItemStack stack = player.getItemInHand(hand);
        List<Direction> towardsPlayer = Arrays.stream(Direction.orderedByNearest(player)).map(Direction::getOpposite).toList();
        for (LockTargets.Spot spot : LockTargets.spots(lookup, target, face, towardsPlayer)) {
            BlockPos at = spot.plate();
            if (!level.isInWorldBounds(at) || !level.getBlockState(at).canBeReplaced() || !level.mayInteract(player, at)
                    || !player.mayUseItemAt(at, spot.outward(), stack) || !MineQTT.permissionManager.canPlace(player, at)) {
                continue;
            }
            BlockPos part = spot.part();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(part).relative(spot.outward(), 0.5), spot.outward(), part, false);
            // Vanilla's placement: the item used up, the sound, the placer as owner and the device limit's refusal.
            return place(new BlockPlaceContext(player, hand, stack, hit));
        }
        return refuse(player, "no_room");
    }

    private static InteractionResult refuse(ServerPlayer player, String reason) {
        player.sendSystemMessage(Component.translatable("mineqtt.smart_lock." + reason), true);
        return InteractionResult.FAIL;
    }
}

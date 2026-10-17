package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.SmartLockBlockEntity;
import art.rehra.mineqtt.compat.ContainerLocks;
import art.rehra.mineqtt.device.types.SmartLock;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.items.SmartLockItem;
import art.rehra.mineqtt.security.DeviceAccess;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
//? if >=1.21.2 && <26.2 {
/*import net.minecraft.world.InteractionResult;
*///?}
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where the Smart Lock acts on other blocks, through Architectury's events on both loaders (no mixin):
 * <ul>
 *     <li>A right-click on a lockable target with the Smart Lock item places the plate ({@link SmartLockItem#placeOn}),
 *     before vanilla would use the target.</li>
 *     <li>A right-click on a target a locked Smart Lock covers: a player it lets in ({@link SmartLockBlockEntity#mayPass})
 *     uses it as always (a container's vanilla lock is lifted for exactly that use); anybody else is refused and
 *     counted as an attempt: vanilla refuses a container with its own message, a door, trapdoor or gate is refused here
 *     with the same message and sound. A sneaking player with an item in hand does not use the target, so nothing is
 *     refused. The client, which knows the lock from the plate's {@code locked} state, predicts nothing on a locked
 *     target and lets the server decide.</li>
 *     <li>Breaking a plate needs CONFIGURE on it (its owner, players its access mode lets configure it, operators who may
 *     build). Breaking a locked container takes its vanilla lock off first, so a shulker box keeps no lock as an item.</li>
 * </ul>
 * Claim mods answer the same events with their own rules; a click either refuses stays refused.
 */
public final class SmartLocks {
    private static boolean registered;

    private SmartLocks() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> clickResult(click(player, hand, pos, face)));
        //? if >=26.2 {
        BlockEvent.BREAK.register((level, pos, state, player) -> breaking(level, pos, state, player));
        //?} else {
        /*BlockEvent.BREAK.register((level, pos, state, player, xp) -> breaking(level, pos, state, player));
        *///?}
    }

    // Architectury's right-click event answers with an InteractionResult from 1.21.2 to 26.1.2, else with an EventResult.
    //? if >=1.21.2 && <26.2 {
    /*private static InteractionResult clickResult(EventResult result) {
        return result.asMinecraft();
    }
    *///?} else {
    private static EventResult clickResult(EventResult result) {
        return result;
    }
    //?}

    /** A right-click on any block, before vanilla handles it; see the class comment. */
    static EventResult click(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        Level level = player.level();
        if (level.getBlockState(pos).getBlock() instanceof BaseMqttBlock) return EventResult.pass();
        LockTargets.Lookup lookup = LockTargets.Lookup.of(level);
        if (player.isSpectator() || !LockTargets.lockable(lookup, pos)) return EventResult.pass();
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof SmartLockItem item) {
            if (player instanceof ServerPlayer serverPlayer) item.placeOn(serverPlayer, hand, pos, face);
            return EventResult.interruptTrue();
        }
        // Vanilla uses no block for a sneaking player with an item in a hand: nothing to stop, the item goes on.
        if (player.isSecondaryUseActive() && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) return EventResult.pass();
        List<BlockPos> plates = LockTargets.plates(lookup, pos);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            boolean locked = plates.stream().anyMatch(plate -> level.getBlockState(plate).getValue(SmartLockBlock.LOCKED));
            // Not predicted: the server opens it for a player the lock lets in and refuses everybody else.
            return locked ? EventResult.interruptTrue() : EventResult.pass();
        }
        ServerLevel serverLevel = (ServerLevel) level;
        List<BlockPos> group = LockTargets.group(lookup, pos);
        if (plates.isEmpty()) {
            // A vanilla lock no plate holds any more (a shulker box carried off, a plate gone while unloaded) comes off.
            for (BaseContainerBlockEntity container : containers(serverLevel, group)) ContainerLocks.clear(container);
            return EventResult.pass();
        }
        Optional<SmartLockBlockEntity> lock = plates.stream().map(plate -> SmartLockBlockEntity.at(serverLevel, plate)).flatMap(Optional::stream)
                .filter(SmartLockBlockEntity::locked).findFirst();
        if (lock.isEmpty()) return EventResult.pass();
        SmartLockBlockEntity smartLock = lock.get();
        List<BaseContainerBlockEntity> containers = containers(serverLevel, group);
        if (smartLock.mayPass(serverPlayer)) {
            smartLock.authorized();
            if (containers.isEmpty() || hand != InteractionHand.MAIN_HAND) return EventResult.pass();
            useUnlocked(serverPlayer, pos, face, containers);
            return EventResult.interruptTrue();
        }
        smartLock.attempt(SmartLock.Method.HAND);
        if (!containers.isEmpty() && containers.stream().allMatch(ContainerLocks::apply)) {
            // Vanilla refuses it with its "is locked" message and sound, as any locked container.
            return EventResult.pass();
        }
        BlockState state = serverLevel.getBlockState(pos);
        serverPlayer.sendSystemMessage(Component.translatable("container.isLocked", state.getBlock().getName()), true);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 1.0F);
        // The client may have swung the door already, which vanilla resends for the clicked block and its neighbour only.
        for (BlockPos part : group) serverPlayer.connection.send(new ClientboundBlockUpdatePacket(serverLevel, part));
        return EventResult.interruptFalse();
    }

    /**
     * A player the lock lets in uses the locked container: its vanilla lock (both halves of a double chest) is lifted for
     * this one use, which opens the menu now, and put back at once; the open menu stays valid, as vanilla asks the lock
     * only when it opens.
     */
    private static void useUnlocked(ServerPlayer player, BlockPos pos, Direction face, List<BaseContainerBlockEntity> containers) {
        List<BaseContainerBlockEntity> lifted = new ArrayList<>();
        for (BaseContainerBlockEntity container : containers) {
            if (ContainerLocks.locked(container)) {
                ContainerLocks.set(container, LockCode.NO_LOCK);
                lifted.add(container);
            }
        }
        try {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
            player.level().getBlockState(pos).useWithoutItem(player.level(), player, hit);
        } finally {
            for (BaseContainerBlockEntity container : lifted) ContainerLocks.set(container, ContainerLocks.SMART_LOCK);
        }
    }

    private static List<BaseContainerBlockEntity> containers(ServerLevel level, List<BlockPos> group) {
        List<BaseContainerBlockEntity> containers = new ArrayList<>();
        for (BlockPos part : group) {
            if (level.getBlockEntity(part) instanceof BaseContainerBlockEntity container) containers.add(container);
        }
        return containers;
    }

    /** A player breaking a block: a plate only with CONFIGURE on it; a locked container loses its vanilla lock first. */
    static EventResult breaking(Level level, BlockPos pos, BlockState state, ServerPlayer player) {
        if (state.getBlock() instanceof SmartLockBlock && level.getBlockEntity(pos) instanceof SmartLockBlockEntity lock) {
            if (DeviceAccess.check(player, lock, MineqttPermission.CONFIGURE)) return EventResult.pass();
            player.sendSystemMessage(Component.translatable("mineqtt.smart_lock.protected"), true);
            return EventResult.interruptFalse();
        }
        if (level.getBlockEntity(pos) instanceof BaseContainerBlockEntity container) ContainerLocks.clear(container);
        return EventResult.pass();
    }
}

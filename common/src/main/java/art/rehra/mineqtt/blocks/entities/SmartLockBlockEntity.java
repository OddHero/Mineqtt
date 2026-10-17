package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.LockTargets;
import art.rehra.mineqtt.blocks.SmartLockBlock;
import art.rehra.mineqtt.compat.ContainerLocks;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.SmartLock;
import art.rehra.mineqtt.device.types.SmartLock.Settings;
import art.rehra.mineqtt.device.types.SmartLock.Status;
import art.rehra.mineqtt.device.types.SmartLock.TargetKind;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.DeviceAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Smart Lock ({@link SmartLock}): whether it is locked, its relock delay and countdown, the attempts it counted and
 * its target's id are in the {@code mineqtt} compound. The target itself is never stored: it is the block the plate
 * aims at ({@link SmartLockBlock#support}) and the group around it ({@link LockTargets#group}), read again every tick, so
 * a chest that becomes double or a second door hung beside the first is locked with it.
 * <p>
 * Every server tick it watches its target: a door, trapdoor or gate of a locked target that opens without a player the
 * lock lets in having just used it (redstone, a mob, a wind charge) is closed again and counted as an attempt; the
 * containers of a locked target carry vanilla's lock ({@link ContainerLocks}), which comes off when it is unlocked or
 * the plate goes; the plate's {@link SmartLockBlock#LOCKED} follows the lock. It never loads its target's chunk.
 */
public class SmartLockBlockEntity extends DeviceBlockEntity {
    /** How often, in ticks, the containers' vanilla lock is checked against the lock besides every change. */
    public static final int CONTAINER_SYNC_TICKS = 20;

    private final DeviceLink link = new DeviceLink(SmartLock.TYPE);
    private Settings settings = Settings.DEFAULT;
    // What it last found at its target; never saved, read again every tick.
    private Status status = Status.OK;
    private TargetKind kind = TargetKind.OTHER;
    private boolean open;
    private final Map<BlockPos, Boolean> wasOpen = new HashMap<>();
    private boolean watching;
    // A permitted player used the target since the last look: the next opening it sees is theirs.
    private boolean authorized;
    private long lastAttemptAt = Long.MIN_VALUE / 2;
    private boolean syncContainers = true;

    public SmartLockBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.SMART_LOCK.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return SmartLock.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        if (level instanceof ServerLevel serverLevel && !watching) look(serverLevel);
        settings.write(out, status, open).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    /** {@code locked} (true, false, LOCK, UNLOCK, TOGGLE), the actions {@code lock}, {@code unlock} and {@code toggle}, and {@code relock}. */
    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Boolean> to = SmartLock.lockTo(in, settings.locked());
        Optional<Integer> relock = in.get(SmartLock.RELOCK);
        if (to.isEmpty() && relock.isEmpty()) return CommandResult.rejected("not a smart lock command: " + in.keys());
        Settings before = settings;
        relock.ifPresent(seconds -> settings = settings.relockTo(seconds));
        to.ifPresent(this::lockTo);
        if (settings.equals(before)) return CommandResult.unchanged();
        setChanged();
        return CommandResult.applied();
    }

    /** Whether it is locked. */
    public boolean locked() {
        return settings.locked();
    }

    /** What it last found at its target. */
    public Status status() {
        return status;
    }

    /** How many attempts it counted; read by the game tests. */
    public int attempts() {
        return settings.attempts();
    }

    /**
     * Whether {@code player} may open the locked target and toggle the lock by hand ({@link SmartLock#mayPass}): the
     * owner, an operator under {@code smartLock.opsBypass}, or whom the access mode lets use it. A spectator never.
     */
    public boolean mayPass(ServerPlayer player) {
        if (player.isSpectator()) return false;
        boolean sameLevel = level != null && level.dimension().equals(player.level().dimension());
        return SmartLock.mayPass(DeviceAccess.isOwner(player, this), AclContext.isOp(player.permissions()), MineQTTConfig.smartLockOpsBypass,
                link.access(), () -> sameLevel && MineQTT.permissionManager.canInteract(player, worldPosition, MineqttPermission.USE));
    }

    /**
     * A player {@link #mayPass} is using the target: the door, trapdoor or gate that opens before the next look is
     * theirs. Vanilla opens it within that same use, so the next look sees it, takes it as theirs and forgets the mark.
     */
    public void authorized() {
        authorized = true;
    }

    /**
     * Somebody or something tried to get past the locked lock: counted and published as an {@code attempt} (how and on
     * what, never who), at most once a tick; the event itself at most once a second.
     */
    public void attempt(SmartLock.Method method) {
        if (level == null || level.getGameTime() == lastAttemptAt) return;
        lastAttemptAt = level.getGameTime();
        settings = settings.attempted();
        setChanged();
        link.publishEvent(this, SmartLock.attempt(method, kind));
        link.stateChanged(this);
    }

    /**
     * Locks or unlocks. Locking closes every door, trapdoor and gate of the target that stands open and puts vanilla's
     * lock on its containers; unlocking takes that lock off and opens a door, trapdoor or gate a redstone signal still
     * holds, as the lock had kept it shut.
     */
    private void lockTo(boolean to) {
        boolean changed = to != settings.locked();
        settings = settings.lockedTo(to);
        if (!changed || !(level instanceof ServerLevel serverLevel)) return;
        BlockPos support = SmartLockBlock.support(worldPosition, getBlockState());
        if (serverLevel.hasChunkAt(support)) {
            LockTargets.Lookup lookup = LockTargets.Lookup.of(serverLevel);
            for (BlockPos part : LockTargets.swinging(lookup, LockTargets.group(lookup, support))) {
                BlockState state = serverLevel.getBlockState(part);
                boolean powered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
                if (to && state.getValue(BlockStateProperties.OPEN)) swing(serverLevel, part, state, false);
                if (!to && powered && !state.getValue(BlockStateProperties.OPEN)) swing(serverLevel, part, state, true);
                wasOpen.put(part, serverLevel.getBlockState(part).getValue(BlockStateProperties.OPEN));
            }
        }
        syncContainers = true;
        look(serverLevel);
        mirror(serverLevel);
    }

    // One server tick: the target, the relock countdown, redstone opening a locked target, the containers' lock.
    private void tick(ServerLevel level) {
        boolean before = open;
        Status statusBefore = status;
        Settings settingsBefore = settings;
        settings = settings.tickRelock();
        if (settings.relockDue()) {
            lockTo(true);
            // The countdown is part of the saved settings, but it is no change to publish.
        }
        look(level);
        if (syncContainers || (level.getGameTime() + worldPosition.asLong()) % CONTAINER_SYNC_TICKS == 0) syncContainers(level);
        mirror(level);
        if (!settings.equals(settingsBefore)) setChanged();
        if (open != before || status != statusBefore || settings.locked() != settingsBefore.locked() || !settings.target().equals(settingsBefore.target())
                || settings.paired() != settingsBefore.paired()) {
            link.stateChanged(this);
        }
    }

    /**
     * Reads the target: its status, kind, id and pair, whether a door of it stands open; a door, trapdoor or gate of a
     * locked target that opened since the last look without a permitted player's use is closed again and counted.
     */
    private void look(ServerLevel level) {
        BlockPos support = SmartLockBlock.support(worldPosition, getBlockState());
        if (!level.hasChunkAt(support)) {
            // Never loaded for a look: a target in a chunk that is not loaded is missing until it loads.
            status = SmartLock.status(false, settings.target());
            open = false;
            return;
        }
        LockTargets.Lookup lookup = LockTargets.Lookup.of(level);
        Optional<TargetKind> found = LockTargets.kind(lookup, support);
        status = SmartLock.status(found.isPresent(), settings.target());
        found.ifPresent(value -> kind = value);
        Optional<String> id = found.map(value -> BuiltInRegistries.BLOCK.getKey(level.getBlockState(support).getBlock()).toString());
        settings = settings.target(id, LockTargets.paired(lookup, support));
        boolean anyOpen = false;
        if (found.isPresent()) {
            List<BlockPos> swinging = LockTargets.swinging(lookup, LockTargets.group(lookup, support));
            wasOpen.keySet().retainAll(swinging);
            for (BlockPos part : swinging) {
                BlockState state = level.getBlockState(part);
                boolean isOpen = state.getValue(BlockStateProperties.OPEN);
                Boolean was = wasOpen.get(part);
                if (watching && settings.locked() && isOpen && was != null && !was && !authorized) {
                    boolean powered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
                    swing(level, part, state, false);
                    isOpen = false;
                    attempt(powered ? SmartLock.Method.REDSTONE : SmartLock.Method.OTHER);
                }
                wasOpen.put(part, isOpen);
                anyOpen |= isOpen;
            }
        } else {
            wasOpen.clear();
        }
        open = anyOpen;
        watching = true;
        authorized = false;
    }

    /** Puts vanilla's lock on the target's containers while locked with a target, and takes it off otherwise. */
    private void syncContainers(ServerLevel level) {
        syncContainers = false;
        BlockPos support = SmartLockBlock.support(worldPosition, getBlockState());
        if (!level.hasChunkAt(support)) return;
        LockTargets.Lookup lookup = LockTargets.Lookup.of(level);
        boolean lock = settings.locked() && status == Status.OK;
        for (BlockPos part : LockTargets.group(lookup, support)) {
            if (!level.hasChunkAt(part) || !(level.getBlockEntity(part) instanceof BaseContainerBlockEntity container)) continue;
            if (lock) {
                ContainerLocks.apply(container);
            } else {
                ContainerLocks.clear(container);
            }
        }
    }

    // The plate's light and the clients' knowledge of the lock follow it.
    private void mirror(ServerLevel level) {
        BlockState state = getBlockState();
        if (state.getBlock() instanceof SmartLockBlock && state.getValue(SmartLockBlock.LOCKED) != settings.locked()) {
            level.setBlock(worldPosition, state.setValue(SmartLockBlock.LOCKED, settings.locked()), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Opens or closes a door (both halves, with its sound), trapdoor or fence gate as its own use would, with the sound
     * and the game event; a redstone signal still on keeps it as it is set here until the signal changes.
     */
    public static void swing(ServerLevel level, BlockPos pos, BlockState state, boolean open) {
        if (state.getValue(BlockStateProperties.OPEN) == open) return;
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(null, level, state, pos, open);
            return;
        }
        level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
        level.playSound(null, pos, swingSound(state, open), SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
        level.gameEvent(null, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    }

    private static SoundEvent swingSound(BlockState state, boolean open) {
        if (state.getBlock() instanceof FenceGateBlock) return open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE;
        if (state.is(Blocks.IRON_TRAPDOOR)) return open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE;
        return open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE;
    }

    /**
     * The plate is gone for good (broken, popped off, blown up): its vanilla lock comes off the target's containers with
     * it, then the device is destroyed. A plate that only unloads keeps it, and a lock no plate holds any more comes off
     * at the next use of the container ({@code SmartLocks}).
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        releaseContainers();
        super.preRemoveSideEffects(pos, state);
    }

    /** A plate a command replaced ({@code /setblock}, {@code /fill}): the containers' lock comes off too. */
    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel && serverLevel.isLoaded(worldPosition)
                && !(serverLevel.getBlockState(worldPosition).getBlock() instanceof SmartLockBlock)) {
            releaseContainers();
        }
        super.setRemoved();
    }

    private void releaseContainers() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        BlockPos support = SmartLockBlock.support(worldPosition, getBlockState());
        if (!serverLevel.hasChunkAt(support)) return;
        LockTargets.Lookup lookup = LockTargets.Lookup.of(serverLevel);
        List<BlockPos> group = LockTargets.group(lookup, support);
        for (BlockPos part : group.isEmpty() ? List.of(support) : group) {
            if (serverLevel.hasChunkAt(part) && serverLevel.getBlockEntity(part) instanceof BaseContainerBlockEntity container
                    && LockTargets.plates(lookup, part).stream().noneMatch(plate -> !plate.equals(worldPosition))) {
                ContainerLocks.clear(container);
            }
        }
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        link.save(output).store("state", Settings.CODEC, settings);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        settings = link.load(input, false).flatMap(data -> data.read("state", Settings.CODEC)).orElse(Settings.DEFAULT);
    }

    /** A placed copy keeps its relock delay; it is locked and finds its own target. */
    @Override
    protected void placedCopy() {
        settings = settings.placed();
        syncContainers = true;
    }

    /** Each server tick: the target, the relock countdown and the containers' lock. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (blockEntity instanceof SmartLockBlockEntity lock && level instanceof ServerLevel serverLevel) lock.tick(serverLevel);
        }
    }

    /** The lock whose plate stands at {@code pos}, if one does. */
    public static Optional<SmartLockBlockEntity> at(@Nullable Level level, BlockPos pos) {
        return level != null && level.getBlockEntity(pos) instanceof SmartLockBlockEntity lock ? Optional.of(lock) : Optional.empty();
    }
}

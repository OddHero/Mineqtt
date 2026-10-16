package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.ContainerObserverBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.ContainerObserver;
import art.rehra.mineqtt.device.types.ContainerObserver.Scan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The Container Observer ({@link ContainerObserver}): looks at the container its face touches once attached, every
 * {@value ContainerObserver#SAMPLE_TICKS} ticks, and publishes its state when what it saw changed. Only the link is
 * saved: what it saw is looked at again after every load, so a state read while its owner could open the container is
 * never published again once they no longer can. Clients get no render data.
 */
public class ContainerObserverBlockEntity extends DeviceBlockEntity {
    private final DeviceLink link = new DeviceLink(ContainerObserver.TYPE);
    // What the last look saw since the block entity loaded; null before the first one.
    private @Nullable Scan scan;

    public ContainerObserverBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.CONTAINER_OBSERVER.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return ContainerObserver.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    /**
     * Its state: what it saw at its last look. The first state after a load (the attach's) looks at once, so Home
     * Assistant gets the container's contents rather than a waiting state; {@link Scan#WAITING} only while the
     * container's chunk is not loaded yet.
     */
    @Override
    public void writeState(StateWriter out) {
        if (scan == null && level instanceof ServerLevel serverLevel) scan = look(serverLevel);
        (scan == null ? Scan.WAITING : scan).write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        return CommandResult.rejected("a container observer takes no commands: " + in.keys());
    }

    /**
     * Looks again and publishes the state when it changed.
     */
    private void refresh(ServerLevel serverLevel) {
        Scan next = look(serverLevel);
        if (next == null || next.equals(scan)) return;
        scan = next;
        link.stateChanged(this);
    }

    /**
     * What is in front now: the container's contents, only its id while its owner may not open it ({@link #mayRead}),
     * or that there is none.
     *
     * @return null while the chunk in front is not loaded: it never loads it, nor reports an unloaded container as gone
     */
    private @Nullable Scan look(ServerLevel serverLevel) {
        Direction facing = getBlockState().getValue(ContainerObserverBlock.FACING);
        BlockPos at = worldPosition.relative(facing);
        if (!serverLevel.hasChunkAt(at)) return null;
        Container container = HopperBlockEntity.getContainerAt(serverLevel, at);
        if (container == null) return Scan.NO_CONTAINER;
        String id = container instanceof Entity entity
                ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()
                : BuiltInRegistries.BLOCK.getKey(serverLevel.getBlockState(at).getBlock()).toString();
        if (!mayRead(serverLevel, at)) return Scan.claimed(id);
        return ContainerObserver.scan(container, id);
    }

    /**
     * Whether it may read the container at {@code at} ({@link ContainerObserver#mayRead}): asked at every look, so a claim
     * change or its owner leaving takes the contents out of its state at the next one.
     */
    private boolean mayRead(ServerLevel serverLevel, BlockPos at) {
        if (!MineQTT.permissionManager.hasClaimMod()) return true;
        UUID owner = link.owner();
        ServerPlayer online = owner == null ? null : serverLevel.getServer().getPlayerList().getPlayer(owner);
        return ContainerObserver.mayRead(true, MineQTT.permissionManager.sameClaim(serverLevel, worldPosition, at),
                online != null && online.level() == serverLevel && MineQTT.permissionManager.canOpenContainer(online, at));
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        link.save(output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        link.load(input, false);
        scan = null;
    }

    /** A placed copy has seen nothing yet. */
    @Override
    protected void placedCopy() {
        scan = null;
    }

    /**
     * Once attached, a look every {@value ContainerObserver#SAMPLE_TICKS} ticks, spread over the ticks by position so
     * many observers do not all look in the same one.
     */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof ContainerObserverBlockEntity observer) || !(level instanceof ServerLevel serverLevel) || !observer.link.attached()) return;
            if (observer.scan == null || Math.floorMod(level.getGameTime() + pos.hashCode(), ContainerObserver.SAMPLE_TICKS) == 0) {
                observer.refresh(serverLevel);
            }
        }
    }
}

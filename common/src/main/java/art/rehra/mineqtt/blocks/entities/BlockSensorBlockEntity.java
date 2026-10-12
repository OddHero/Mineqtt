package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.BlockSensorBlock;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.BlockObserver.Observation;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * The Block Observer ({@link BlockObserver}): looks at the block in front of its face every tick once attached. Its
 * settings and what it saw last are in the {@code mineqtt} compound; v1 data is read on load and adopted when the device
 * attaches. Clients get no render data.
 */
public class BlockSensorBlockEntity extends DeviceBlockEntity {
    // How often the comparator level of an unchanged block is read, in game ticks.
    private static final int SIGNAL_TICKS = 4;

    private final DeviceLink link = new DeviceLink(BlockObserver.TYPE);
    private Observation observation = Observation.DEFAULT;
    // What the last look saw, since the attach; null until the first one, so the observation loaded may be stale.
    private @Nullable BlockState observed;
    private @Nullable BlockPos observedPos;
    // The Raw Link payload of the last look, live once the first look is done.
    private @Nullable String rawPayload;
    // A setting changed what the Raw Link carries: the next look sends it.
    private boolean rawRequested;

    public BlockSensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.BLOCK_SENSOR_BLOCK.get(), pos, blockState);
    }

    @Override
    public DeviceType deviceType() {
        return BlockObserver.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        observation.write(out).put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        Optional<Observation> next = observation.apply(in);
        if (next.isEmpty()) return CommandResult.rejected("not a block observer command: " + in.keys());
        if (next.get().equals(observation)) return CommandResult.unchanged();
        if (next.get().payload() != observation.payload()) rawRequested = true;
        observation = next.get();
        setChanged();
        return CommandResult.applied();
    }

    /**
     * The payload the Raw Link output carries, once the observer looked after the attach: until then the block it saw is
     * the one saved at the unload, which a retained publish must never repeat.
     */
    @Override
    public @Nullable String rawValue() {
        return rawPayload;
    }

    /**
     * Looks at the block in front, unless its chunk is not loaded: the observer never loads it, nor reports an unloaded
     * block as air. Its state follows the block and its comparator level; a change ({@link BlockObserver#changed}) is a
     * {@code changed} event and goes to the Raw Link. A {@code full} payload is sampled every
     * {@value BlockObserver#BLOCK_ENTITY_SAMPLE_TICKS} ticks besides, and sent when its block entity data changed, as v1
     * did, or may no longer be sent.
     */
    private void look(ServerLevel level, BlockState state) {
        Direction facing = state.getValue(BlockSensorBlock.FACING);
        BlockPos targetPos = worldPosition.relative(facing);
        if (!level.hasChunkAt(targetPos)) return;
        BlockState target = level.getBlockState(targetPos);
        // The first look after the attach (or a turn of the block) compares with what the observer saw before it unloaded;
        // a new observer has seen nothing, so its first look is no change.
        boolean first = observed == null || !targetPos.equals(observedPos);
        // A container's level changes without its block state: it is read with every new state and a few times a second,
        // more often than the state is published.
        int signal = first || target != observed || level.getGameTime() % SIGNAL_TICKS == 0
                ? target.hasAnalogOutputSignal() ? target.getAnalogOutputSignal(level, targetPos, facing.getOpposite()) : 0
                : observation.signal();
        boolean changed = first
                ? !observation.block().isEmpty() && BlockObserver.changedSince(observation.changeOn(), observation.block(), observation.properties(), target)
                : BlockObserver.changed(observation.changeOn(), observed, target);
        observed = target;
        observedPos = targetPos;

        Observation next = observation.looked(target, signal);
        if (!next.equals(observation)) {
            observation = next;
            setChanged();
            link.stateChanged(this);
        }
        if (changed) link.publishEvent(this, BlockObserver.changedEvent(next.block()));

        // A full payload is sampled while it may carry block entity data, and also when it may no longer, so data the
        // server stops allowing (or a claim line now between them) leaves the Raw Link at the next sample.
        boolean full = observation.payload() == BlockObserver.Payload.FULL;
        boolean sample = full && level.getGameTime() % BlockObserver.BLOCK_ENTITY_SAMPLE_TICKS == 0;
        if (!(changed || first || rawRequested || sample)) return;
        boolean exports = full && BlockObserver.exportsBlockEntity(MineQTTConfig.blockObserverExportBlockEntityData,
                MineQTT.permissionManager.sameClaim(level, worldPosition, targetPos));
        String payload = BlockObserver.payload(observation.payload(), target, targetPos, level.dimension().identifier().toString(),
                exports ? entityData(level, targetPos) : null);
        if (changed || first || rawRequested || !payload.equals(rawPayload)) link.publishRaw(this, payload);
        rawPayload = payload;
        rawRequested = false;
    }

    // The target's block entity data, as a {@code full} payload may carry it; null for a block without one.
    private static @Nullable CompoundTag entityData(ServerLevel level, BlockPos targetPos) {
        BlockEntity target = level.getBlockEntity(targetPos);
        return target == null ? null : target.saveWithFullMetadata(level.registryAccess());
    }

    @Override
    protected void saveDevice(ValueOutput output) {
        saveObserver(link, observation, output);
    }

    @Override
    protected void loadDevice(ValueInput input) {
        observation = loadObserver(link, input);
        observed = null;
        rawPayload = null;
    }

    /** A placed copy keeps its settings, and has seen nothing yet. */
    @Override
    protected void placedCopy() {
        observation = observation.placed();
        observed = null;
        rawPayload = null;
    }

    /**
     * An observer's data: the link with the observer as its {@code state}, or v1's keys while its v1 data waits for
     * adoption, so it is still v1 data when it loads again.
     */
    public static void saveObserver(DeviceLink link, Observation observation, ValueOutput output) {
        if (link.awaitingAdoption()) {
            link.saveV1(output);
            LegacyNbtV1.writeSensor(output, observation);
        } else {
            link.save(output).store("state", Observation.CODEC, observation);
        }
    }

    /**
     * Loads an observer's link and returns the observer; v1 data, also a placed v1 item's, is kept for adoption, its data
     * mode as the payload.
     */
    public static Observation loadObserver(DeviceLink link, ValueInput input) {
        return link.load(input, LegacyNbtV1.hasSensorKeys(input))
                .map(data -> data.read("state", Observation.CODEC).orElse(Observation.DEFAULT))
                .orElseGet(() -> LegacyNbtV1.sensorState(input));
    }

    /** A look every tick once the device is attached. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (blockEntity instanceof BlockSensorBlockEntity observer && level instanceof ServerLevel serverLevel && observer.link.attached()) {
                observer.look(serverLevel, state);
            }
        }
    }
}

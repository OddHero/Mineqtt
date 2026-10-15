package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.TimerBlockEntity;
import art.rehra.mineqtt.device.timer.core.TimerKind;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;

/**
 * The block of each of the four timers (M4 plan D2), one class for the four kinds: the Interval Timer, the Daylight Timer
 * ({@code game_time_timer}), the Alarm Clock and the Cron Timer keep their v1 block ids and states. A fire gives a
 * redstone pulse of level 15 on every side, which the {@code power} property shows; its block entity
 * ({@link TimerBlockEntity}) keeps the schedule and drives the pulse.
 */
public class TimerBlock extends BaseMqttBlock {
    private static final Codec<TimerKind> KIND = Codec.STRING.comapFlatMap(id -> TimerKind.byId(id).map(DataResult::success)
            .orElseGet(() -> DataResult.error(() -> "Not a timer: " + id)), TimerKind::id);
    public static final MapCodec<TimerBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            KIND.fieldOf("kind").forGetter(TimerBlock::kind),
            propertiesCodec()
    ).apply(instance, TimerBlock::new));
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    private final TimerKind kind;

    public TimerBlock(TimerKind kind, Properties properties) {
        super(properties, CODEC);
        this.kind = kind;
        this.registerDefaultState(this.getStateDefinition().any().setValue(POWER, 0));
    }

    /** Which of the four timers this block is. */
    public TimerKind kind() {
        return kind;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
    }

    /** Tells the blocks around and the blocks around those that the timer's power changed. */
    public void updateNeighbors(Level level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            BlockPos blockPos = pos.relative(direction);
            Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(level, direction, Direction.UP);
            level.neighborChanged(blockPos, this, orientation);
            level.updateNeighborsAtExceptFromFacing(blockPos, this, direction.getOpposite(), orientation);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        this.updateNeighbors(level, pos, state);
    }

    /** A timer broken during its pulse no longer powers what it powered. */
    //? if >=1.21.5 {
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        if (!movedByPiston) {
            this.updateNeighbors(level, pos, state);
        }
    }
    //?} else {
    /*@Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!movedByPiston && !state.is(newState.getBlock())) this.updateNeighbors(level, pos, state);
    }
    *///?}

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return TimerBlockEntity.create(kind, pos, state);
    }

    /** The server counts, fires and ends pulses; the client draws the block state alone. */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? null : new TimerBlockEntity.ServerTicker<>();
    }
}

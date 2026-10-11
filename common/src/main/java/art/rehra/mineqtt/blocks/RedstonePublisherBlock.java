package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.blocks.entities.PublisherBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;

/** The Redstone Input block: {@code powered} while it receives a signal, which its block entity reports. */
public class RedstonePublisherBlock extends BaseMqttBlock {
    public static final MapCodec<RedstonePublisherBlock> CODEC = simpleCodec(RedstonePublisherBlock::new);

    public static final BooleanProperty POWERED;

    static {
        POWERED = BlockStateProperties.POWERED;
    }

    public RedstonePublisherBlock(Properties properties) {
        super(properties, CODEC);

        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));
    }

    /** The signal decides the block entity's state; a change of the {@code powered} property is an edge. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PublisherBlockEntity input) {
            input.signalChanged(inputSignal(level, pos));
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    /** The strongest signal the block receives from any side, a redstone wire's level included. */
    public static int inputSignal(Level level, BlockPos pos) {
        int maxSignal = 0;
        for (Direction direction : Direction.values()) {
            BlockPos blockpos = pos.relative(direction);
            int i = level.getSignal(blockpos, direction);
            if (i >= 15) {
                return i;
            }
            BlockState blockstate = level.getBlockState(blockpos);
            int signal = Math.max(i, blockstate.is(Blocks.REDSTONE_WIRE) ? blockstate.getValue(RedStoneWireBlock.POWER) : 0);
            maxSignal = Math.max(maxSignal, signal);
        }
        return maxSignal;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        this.updateNeighbors(level, pos, state);
    }



    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        if (!movedByPiston) {
            this.updateNeighbors(level, pos, state);
        }
    }

    protected void updateNeighbors(Level level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            BlockPos blockPos = pos.relative(direction);
            Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(level, direction, Direction.UP);
            level.neighborChanged(blockPos, this, orientation);
            level.updateNeighborsAtExceptFromFacing(blockPos, this, direction.getOpposite(), orientation);
        }
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(POWERED, false);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected boolean isSignalSource(BlockState state) { return true; }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PublisherBlockEntity(pos, state);
    }

    /** The server follows slot edits and reads the signal once after loading; the client has nothing to tick. */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? new DeviceBlockEntity.TwinClientTicker<>() : new PublisherBlockEntity.ServerTicker<>();
    }

}

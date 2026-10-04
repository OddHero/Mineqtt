package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.SmartPlugBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

public class SmartPlugBlock extends BaseSubscriberBlock {

    public static final MapCodec<SmartPlugBlock> CODEC = simpleCodec(SmartPlugBlock::new);

    public static final IntegerProperty POWER;

    static {
        POWER = BlockStateProperties.POWER;
    }

    public SmartPlugBlock(Properties properties) {
        super(properties, CODEC);

        this.registerDefaultState(this.getStateDefinition().any().setValue(POWER, 0));
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

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmartPlugBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return new SmartPlugBlockEntity.Ticker<>();
    }
}

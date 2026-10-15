package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.compat.Redstone;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
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
import org.jetbrains.annotations.Nullable;

/**
 * The Smart Plug block. It emits the {@code power} property, which its block entity sets to 0 or 15; the property keeps
 * v1's full 0–15 range, so saved block states stay valid.
 */
public class SmartPlugBlock extends BaseMqttBlock {

    public static final MapCodec<SmartPlugBlock> CODEC = simpleCodec(SmartPlugBlock::new);

    public static final IntegerProperty POWER = BlockStateProperties.POWER;

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
        Redstone.updateNeighbors(level, pos, this);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        this.updateNeighbors(level, pos, state);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmartPlugBlockEntity(pos, state);
    }

    /** The server keeps the level in step with the plug; the client has nothing to tick. */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? new DeviceBlockEntity.TwinClientTicker<>() : new SmartPlugBlockEntity.ServerTicker<>();
    }
}

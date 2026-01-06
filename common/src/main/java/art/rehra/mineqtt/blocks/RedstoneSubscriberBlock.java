package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.SubscriberBlockEntity;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public class RedstoneSubscriberBlock extends BaseSubscriberBlock {

    // Blocks internal State
    public static final BooleanProperty POWERED;
    private final ConcurrentHashMap<String, Mqtt3Publish> receivedMessages;

    static {
        POWERED = BlockStateProperties.POWERED;
    }

    public RedstoneSubscriberBlock(Properties properties) {
        super(properties, CODEC);

        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));

        receivedMessages = new ConcurrentHashMap<>();
    }    public static final MapCodec<RedstoneSubscriberBlock> CODEC = simpleCodec(RedstoneSubscriberBlock::new);



    @Override
    protected boolean isSignalSource(BlockState state) { return true; }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        this.updateNeighbors(level, pos, state);
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
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SubscriberBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return new SubscriberBlockEntity.Ticker<>();
    }
}

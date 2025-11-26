package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class RgbLedBlock extends BaseSubscriberBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final IntegerProperty RED = IntegerProperty.create("red", 0, 15);
    public static final IntegerProperty GREEN = IntegerProperty.create("green", 0, 15);
    public static final IntegerProperty BLUE = IntegerProperty.create("blue", 0, 15);
    public static final IntegerProperty BRIGHTNESS = IntegerProperty.create("brightness", 0, 15);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    // Define the shape that matches our model (socket + bulb)
    // Socket base: 5,0,5 to 11,3,11 (6x3x6)
    // Socket middle: 6,3,6 to 10,5,10 (4x2x4)
    // Bulb: approximately 5,5,5 to 11,14,11 (6x9x6 for pear shape)
    private static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 14.0, 11.0);

    public final MapCodec<RgbLedBlock> CODEC = simpleCodec(RgbLedBlock::new);

    public RgbLedBlock(Properties properties) {
        super(properties, simpleCodec(RgbLedBlock::new));
        this.registerDefaultState(this.getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(RED, 0)
                .setValue(GREEN, 0)
                .setValue(BLUE, 0)
                .setValue(BRIGHTNESS, 15)
                .setValue(LIT, false));
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        Direction facing = state.getValue(FACING);

        // Input direction (opposite of facing) doesn't output signal
        if (direction == facing.getOpposite()) {
            return 0;
        }

        // Calculate which output this is based on rotation from facing direction
        Direction[] outputs = getOutputDirections(facing);

        if (direction == outputs[0]) {
            return state.getValue(RED);
        } else if (direction == outputs[1]) {
            return state.getValue(GREEN);
        } else if (direction == outputs[2]) {
            return state.getValue(BLUE);
        }

        return 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    private Direction[] getOutputDirections(Direction facing) {
        return switch (facing) {
            case NORTH -> new Direction[]{Direction.EAST, Direction.SOUTH, Direction.WEST};
            case SOUTH -> new Direction[]{Direction.WEST, Direction.NORTH, Direction.EAST};
            case EAST -> new Direction[]{Direction.SOUTH, Direction.WEST, Direction.NORTH};
            case WEST -> new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH};
            case UP -> new Direction[]{Direction.NORTH, Direction.DOWN, Direction.SOUTH};
            case DOWN -> new Direction[]{Direction.NORTH, Direction.UP, Direction.SOUTH};
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, RED, GREEN, BLUE);
    }

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
    protected MapCodec<RgbLedBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RgbLedBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return new RgbLedBlockEntity.Ticker<>();
    }
}


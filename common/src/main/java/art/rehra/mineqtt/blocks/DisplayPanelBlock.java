package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.DisplayPanelBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Display Panel block (M3 plan D18): a 1×1 plate against the face it was placed on, its screen facing
 * {@link #FACING}, like the Wall Switch. A right-click opens its screen. A comparator reads the panel's level
 * ({@link art.rehra.mineqtt.device.types.DisplayPanel#comparator}) from behind or beside it.
 */
public class DisplayPanelBlock extends BaseMqttBlock {
    public static final MapCodec<DisplayPanelBlock> CODEC = simpleCodec(DisplayPanelBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** How thick the plate is, in sixteenths of a block. */
    public static final int THICKNESS = 2;

    // The plate, against the wall behind its screen.
    private static final VoxelShape NORTH_SHAPE = Block.box(0.0, 0.0, 16.0 - THICKNESS, 16.0, 16.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, THICKNESS);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 0.0, 0.0, THICKNESS, 16.0, 16.0);
    private static final VoxelShape WEST_SHAPE = Block.box(16.0 - THICKNESS, 0.0, 0.0, 16.0, 16.0, 16.0);

    public DisplayPanelBlock(Properties properties) {
        super(properties, CODEC);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Against a wall it faces away from it; on a floor or a ceiling it faces the player. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        return defaultBlockState().setValue(FACING, face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** The panel's level: 0 while it shows nothing, its source has no value, or it has not loaded its data yet. */
    @Override
    //? if >=1.21.9 {
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
    //?} else {
    /*protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
    *///?}
        return level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel ? panel.comparatorLevel() : 0;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayPanelBlockEntity(pos, state);
    }

    /** The server sends the render updates it held back (at most 4 a second); the client ticks the twin's particles. */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? new DisplayPanelBlockEntity.ClientTicker<>() : new DisplayPanelBlockEntity.ServerTicker<>();
    }
}

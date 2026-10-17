package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.AlarmPanelBlockEntity;
import art.rehra.mineqtt.device.types.AlarmPanel;
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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Alarm Panel ({@link AlarmPanelBlockEntity}): a keypad 10 wide, 12 high and 2 deep against the face it was placed
 * on, facing {@link #FACING} like the Display Panel. {@link #STATUS} mirrors the alarm so every client draws its light:
 * green disarmed, amber blinking while arming or pending, red armed, red flashing triggered (animated textures, no
 * client code). A right-click opens its keypad. Players walk through it, as through a button.
 */
public class AlarmPanelBlock extends BaseMqttBlock {
    public static final MapCodec<AlarmPanelBlock> CODEC = simpleCodec(AlarmPanelBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<AlarmPanel.Light> STATUS = EnumProperty.create("status", AlarmPanel.Light.class);

    // The keypad, against the wall behind it.
    private static final VoxelShape NORTH_SHAPE = Block.box(3.0, 2.0, 14.0, 13.0, 14.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(3.0, 2.0, 0.0, 13.0, 14.0, 2.0);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 2.0, 3.0, 2.0, 14.0, 13.0);
    private static final VoxelShape WEST_SHAPE = Block.box(14.0, 2.0, 3.0, 16.0, 14.0, 13.0);

    public AlarmPanelBlock(Properties properties) {
        super(properties, CODEC);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(STATUS, AlarmPanel.Light.DISARMED));
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
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATUS);
    }

    /** Against a wall it faces away from it; on a floor or a ceiling it faces the player. A new panel is disarmed. */
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
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AlarmPanelBlockEntity(pos, state);
    }

    /** The server counts the delays, the siren and the lockout down every tick; the client has nothing to tick. */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : new AlarmPanelBlockEntity.ServerTicker<>();
    }
}

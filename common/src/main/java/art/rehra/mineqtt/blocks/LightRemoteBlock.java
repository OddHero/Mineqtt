package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.LightRemoteBlockEntity;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.types.WallSwitch;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Wall Switch block (v1's Light Remote id): a plate with two buttons on the face it was placed against, its front
 * facing {@link #FACING}. A right-click presses the button it hits; {@link #LEFT} and {@link #RIGHT} show a button
 * pressed. A Light Remote saved by v1 had no block state properties and loads facing north.
 */
public class LightRemoteBlock extends BaseMqttBlock {
    public static final MapCodec<LightRemoteBlock> CODEC = simpleCodec(LightRemoteBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The left button, as seen by a player facing the switch, is pressed. */
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    /** The right button is pressed. */
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    // The plate with its buttons, against the wall behind it.
    private static final VoxelShape NORTH_SHAPE = Block.box(2.0, 3.0, 13.0, 14.0, 13.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(2.0, 3.0, 0.0, 14.0, 13.0, 3.0);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 3.0, 2.0, 3.0, 13.0, 14.0);
    private static final VoxelShape WEST_SHAPE = Block.box(13.0, 3.0, 2.0, 16.0, 13.0, 14.0);

    public LightRemoteBlock(Properties properties) {
        super(properties, CODEC);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(LEFT, false).setValue(RIGHT, false));
    }

    /**
     * The button a point on the switch belongs to: left or right of the plate's middle, as seen by a player facing the
     * switch.
     *
     * @param facing where the switch's front faces
     * @param dx     the point's x relative to the block's centre
     * @param dz     the point's z relative to the block's centre
     */
    public static WallSwitch.Button buttonAt(Direction facing, double dx, double dz) {
        // Facing the switch, a player looks against its facing; their right hand points to its counter-clockwise side.
        Direction right = facing.getCounterClockWise();
        return dx * right.getStepX() + dz * right.getStepZ() > 0 ? WallSwitch.Button.RIGHT : WallSwitch.Button.LEFT;
    }

    /**
     * A right-click presses the button it hits. The click event names no hit point, so it is found again along the
     * player's view: on the switch where the view meets it, else where the view crosses the block's cube. A switch that
     * does not run neither moves nor clicks; the player is told why.
     */
    @Override
    protected void useDevice(ServerPlayer player, BlockEntity blockEntity) {
        if (!(blockEntity instanceof LightRemoteBlockEntity wallSwitch) || !DeviceRuntime.running(player, blockEntity)) return;
        BlockPos pos = blockEntity.getBlockPos();
        double reach = player.blockInteractionRange() + 1;
        HitResult hit = player.pick(reach, 1.0F, false);
        Vec3 eyes = player.getEyePosition();
        Vec3 at = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK && blockHit.getBlockPos().equals(pos)
                ? hit.getLocation()
                : new AABB(pos).clip(eyes, eyes.add(player.getViewVector(1.0F).scale(reach))).orElse(Vec3.atCenterOf(pos));
        Vec3 centre = Vec3.atCenterOf(pos);
        wallSwitch.usedByHand(buttonAt(blockEntity.getBlockState().getValue(FACING), at.x - centre.x, at.z - centre.z));
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
        builder.add(FACING, LEFT, RIGHT);
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
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LightRemoteBlockEntity(pos, state);
    }

    /** The server tells the gestures and lets go of the buttons; the client has nothing to tick. */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? null : new LightRemoteBlockEntity.ServerTicker<>();
    }
}

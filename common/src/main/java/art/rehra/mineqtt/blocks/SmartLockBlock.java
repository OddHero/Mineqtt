package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.SmartLockBlockEntity;
import art.rehra.mineqtt.device.types.SmartLock;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.security.DeviceAccess;
import com.mojang.serialization.MapCodec;
import dev.architectury.event.EventResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
//? if >=1.21.2 {
import net.minecraft.world.level.ScheduledTickAccess;
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Smart Lock's plate ({@link SmartLockBlockEntity}): a small keypad attached to a face of the door, trapdoor, fence
 * gate or container it locks, aimed at it. {@link #FACE} and {@link #FACING} say where it is mounted, as for a button: on
 * top of its target (floor), under it (ceiling) or on a side (wall, {@code facing} pointing away from the target).
 * {@link #LOCKED} mirrors the device's lock, so every client draws its light red or green and knows not to predict a
 * locked door opening.
 * <p>
 * The target holds the plate: it is placed only onto a lockable target ({@code SmartLockItem}), and it pops off,
 * dropping its item, when the target goes away (broken, blown up, moved by a piston), which destroys the device. A target
 * swapped for a block that cannot be locked (a command) keeps it, reporting {@code target_missing}. Players walk through
 * it; pistons do not move it (it has a block entity). Only its owner, players who may configure it and operators break it
 * ({@code SmartLocks}).
 */
public class SmartLockBlock extends BaseMqttBlock {
    public static final MapCodec<SmartLockBlock> CODEC = simpleCodec(SmartLockBlock::new);
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LOCKED = BooleanProperty.create("locked");

    // A keypad 6 wide, 8 high and 2 deep, against the target's face.
    private static final VoxelShape WALL_NORTH = Block.box(5.0, 4.0, 14.0, 11.0, 12.0, 16.0);
    private static final VoxelShape WALL_SOUTH = Block.box(5.0, 4.0, 0.0, 11.0, 12.0, 2.0);
    private static final VoxelShape WALL_WEST = Block.box(14.0, 4.0, 5.0, 16.0, 12.0, 11.0);
    private static final VoxelShape WALL_EAST = Block.box(0.0, 4.0, 5.0, 2.0, 12.0, 11.0);
    private static final VoxelShape FLOOR_Z = Block.box(5.0, 0.0, 4.0, 11.0, 2.0, 12.0);
    private static final VoxelShape FLOOR_X = Block.box(4.0, 0.0, 5.0, 12.0, 2.0, 11.0);
    private static final VoxelShape CEILING_Z = Block.box(5.0, 14.0, 4.0, 11.0, 16.0, 12.0);
    private static final VoxelShape CEILING_X = Block.box(4.0, 14.0, 5.0, 12.0, 16.0, 11.0);

    public SmartLockBlock(Properties properties) {
        super(properties, CODEC);
        registerDefaultState(getStateDefinition().any().setValue(FACE, AttachFace.WALL).setValue(FACING, Direction.NORTH).setValue(LOCKED, true));
    }

    /** The direction the plate points, away from its target: up on top of it, down under it, its facing on a side. */
    public static Direction outward(BlockState state) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> Direction.UP;
            case CEILING -> Direction.DOWN;
            case WALL -> state.getValue(FACING);
        };
    }

    /** The position of the target part a plate in {@code state} at {@code pos} aims at. */
    public static BlockPos support(BlockPos pos, BlockState state) {
        return pos.relative(outward(state).getOpposite());
    }

    /** A plate pointing {@code outward}, away from its target; one on top of or under it faces {@code horizontal}. */
    public BlockState mounted(Direction outward, Direction horizontal, boolean locked) {
        BlockState state = switch (outward) {
            case UP -> defaultBlockState().setValue(FACE, AttachFace.FLOOR).setValue(FACING, horizontal);
            case DOWN -> defaultBlockState().setValue(FACE, AttachFace.CEILING).setValue(FACING, horizontal);
            default -> defaultBlockState().setValue(FACE, AttachFace.WALL).setValue(FACING, outward);
        };
        return state.setValue(LOCKED, locked);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean alongZ = state.getValue(FACING).getAxis() == Direction.Axis.Z;
        return switch (state.getValue(FACE)) {
            case FLOOR -> alongZ ? FLOOR_Z : FLOOR_X;
            case CEILING -> alongZ ? CEILING_Z : CEILING_X;
            case WALL -> switch (state.getValue(FACING)) {
                case SOUTH -> WALL_SOUTH;
                case WEST -> WALL_WEST;
                case EAST -> WALL_EAST;
                default -> WALL_NORTH;
            };
        };
    }

    /** Players walk through it, as through a button. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * Placed against the face of a lockable target only: the clicked block, which {@code SmartLockItem} chose among the
     * target's free faces. A click on a block the plate would replace, or on a block that cannot be locked, places
     * nothing.
     */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.replacingClickedOnBlock()) return null;
        Direction outward = context.getClickedFace();
        BlockState state = mounted(outward, context.getHorizontalDirection(), true);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** It stands only against a lockable target. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return LockTargets.lockable(LockTargets.Lookup.of(level), support(pos, state));
    }

    /**
     * A plate whose target went away pops off; the level then drops it as if it was broken, and the device is destroyed.
     * Gone means the block it aims at can be replaced (air, a fluid): a door that opens, a chest that becomes double or a
     * target a command swapped for stone keeps it.
     */
    @Override
    //? if >=1.21.2 {
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
    //?} else {
    /*protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState, net.minecraft.world.level.LevelAccessor level, BlockPos pos,
                                     BlockPos neighbourPos) {
    *///?}
        if (direction == outward(state).getOpposite() && neighbourState.canBeReplaced()) {
            return Blocks.AIR.defaultBlockState();
        }
        //? if >=1.21.2 {
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random);
        //?} else {
        /*return super.updateShape(state, direction, neighbourState, level, pos, neighbourPos);
        *///?}
    }

    /**
     * A player who may not use the lock trying its plate is an attempt ({@code plate}), told in the action bar as a locked
     * container is; the click then goes on as on any block. Everything else is {@link BaseMqttBlock#click}'s.
     */
    @Override
    public EventResult click(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        if (player instanceof ServerPlayer serverPlayer && hand == InteractionHand.MAIN_HAND && !player.isSpectator() && !player.isShiftKeyDown()
                && player.level().getBlockEntity(pos) instanceof SmartLockBlockEntity lock && !DeviceAccess.check(serverPlayer, lock, MineqttPermission.USE)) {
            lock.attempt(SmartLock.Method.PLATE);
            serverPlayer.sendSystemMessage(Component.translatable("mineqtt.smart_lock.no_access"), true);
            player.level().playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return super.click(player, hand, pos, face);
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
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING, LOCKED);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmartLockBlockEntity(pos, state);
    }

    /** The server watches the target every tick: redstone that opens it, a pair forming, the relock countdown. */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : new SmartLockBlockEntity.ServerTicker<>();
    }
}

package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The Smart Lamp block. Colour and state live in its block entity; the light level is a block state property, because
 * Minecraft's lighting needs it, and so is where the lamp is mounted.
 * <p>
 * Mounting (P3): {@link #FACE} says whether the lamp stands on a floor, sits on a wall or hangs from a ceiling, as for
 * buttons and levers. A wall lamp's {@link #FACING} points away from its wall; a floor or ceiling lamp keeps the
 * placing player's horizontal facing, which only turns the round lamp's textures. Lamps saved before {@code FACE}
 * existed have no value for it and load with its default, {@code floor}, so they stay where they were; their stored
 * {@code facing} (always {@code north}, unused until then) keeps parsing because {@code FACING} keeps all six values.
 * The clicked face decides the mount, and the lamp needs a support under the middle of its socket, as a torch or a
 * lantern does: it cannot be placed without one and pops off, dropping itself, when the support goes away. A support that
 * only changes its state stays one: 0.6.0 placed lamps without a support check, also on other lamps and on blocks whose
 * top changes with their state (repeaters, daylight sensors), and those lamps must not pop when that block switches.
 */
public class RgbLedBlock extends BaseMqttBlock {

    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final IntegerProperty LIGHT_LEVEL = IntegerProperty.create("light_level", 0, 15);

    /** How far the bulb's middle lies from the block's centre, towards where the lamp points. */
    private static final double BULB_OFFSET = 0.1;

    // The model's bounds (socket and bulb, 6x14x6), by the direction the lamp points: away from its support.
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Map.of(
            Direction.UP, Block.box(5.0, 0.0, 5.0, 11.0, 14.0, 11.0),
            Direction.DOWN, Block.box(5.0, 2.0, 5.0, 11.0, 16.0, 11.0),
            Direction.NORTH, Block.box(5.0, 5.0, 2.0, 11.0, 11.0, 16.0),
            Direction.SOUTH, Block.box(5.0, 5.0, 0.0, 11.0, 11.0, 14.0),
            Direction.WEST, Block.box(2.0, 5.0, 5.0, 16.0, 11.0, 11.0),
            Direction.EAST, Block.box(0.0, 5.0, 5.0, 14.0, 11.0, 11.0)));

    public final MapCodec<RgbLedBlock> CODEC = simpleCodec(RgbLedBlock::new);

    public RgbLedBlock(Properties properties) {
        super(properties.lightLevel(state -> state.getValue(LIGHT_LEVEL)), simpleCodec(RgbLedBlock::new));
        this.registerDefaultState(this.getStateDefinition().any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
                .setValue(LIGHT_LEVEL, 0));
    }

    /**
     * The direction the lamp points, away from its support: up on a floor, down from a ceiling, its facing on a wall. A
     * wall state facing up or down, which only a command can set, points that way too.
     */
    public static Direction pointing(BlockState state) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> Direction.UP;
            case CEILING -> Direction.DOWN;
            case WALL -> state.getValue(FACING);
        };
    }

    /** The middle of the bulb of a lamp in {@code state} at {@code pos}, where its particles rise from. */
    public static Vec3 bulbCentre(BlockState state, BlockPos pos) {
        return Vec3.atCenterOf(pos).relative(pointing(state), BULB_OFFSET);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(pointing(state));
    }

    /**
     * The clicked face decides: the top of a block gives a floor lamp, its bottom a ceiling lamp, a side a wall lamp. A
     * click on a block the lamp replaces (short grass, a thin snow layer) hit that block's outline, which says nothing
     * about the support, so there the lamp takes the first direction the player looks along that has one, as vanilla's
     * buttons and torches do.
     */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction horizontal = context.getHorizontalDirection();
        if (context.replacingClickedOnBlock() && context.getPlayer() != null) {
            for (Direction towardsSupport : context.getNearestLookingDirections()) {
                BlockState state = mountedState(towardsSupport.getOpposite(), horizontal);
                if (state.canSurvive(context.getLevel(), context.getClickedPos())) {
                    return state;
                }
            }
            return null;
        }
        BlockState state = mountedState(context.getClickedFace(), horizontal);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /**
     * A lamp pointing {@code pointing}, away from its support; a floor or ceiling lamp faces {@code horizontal}, the
     * placing player's horizontal facing.
     */
    private BlockState mountedState(Direction pointing, Direction horizontal) {
        return switch (pointing) {
            case UP -> defaultBlockState().setValue(FACE, AttachFace.FLOOR).setValue(FACING, horizontal);
            case DOWN -> defaultBlockState().setValue(FACE, AttachFace.CEILING).setValue(FACING, horizontal);
            default -> defaultBlockState().setValue(FACE, AttachFace.WALL).setValue(FACING, pointing);
        };
    }

    /** The support must hold the middle of the socket, as for a torch on a floor or a lantern on a ceiling. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction pointing = pointing(state);
        return Block.canSupportCenter(level, pos.relative(pointing.getOpposite()), pointing);
    }

    /**
     * A lamp whose support went away pops off; the level then drops it as if it was broken. Gone means the block on the
     * support side can be replaced (air, a fluid, short grass): a block that stays there and only changes its state, such
     * as a lamp below switching on, keeps a lamp it never held, which 0.6.0 placed without a survival check.
     */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (direction == pointing(state).getOpposite() && neighbourState.canBeReplaced() && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random);
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
        builder.add(FACE, FACING, LIGHT_LEVEL);
    }

    // Not @Override: blocks have no codec from 26.3 on.
    protected MapCodec<RgbLedBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RgbLedBlockEntity(pos, state);
    }

    /** The server follows slot edits and the light level; the client only draws particles. */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide() ? new RgbLedBlockEntity.ClientTicker<>() : new RgbLedBlockEntity.ServerTicker<>();
    }
}

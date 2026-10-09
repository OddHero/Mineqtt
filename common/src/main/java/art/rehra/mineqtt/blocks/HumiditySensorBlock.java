package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.HumiditySensorBlockEntity;
import art.rehra.mineqtt.legacy.LegacyConverterBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The v1 humidity sensor, which converts into the Climate Sensor ({@code temperature_sensor_block}, M2-19) with its
 * facing, its topic, its owner and its name, and keeps publishing its humidity on its topic. It keeps v1's shape and
 * {@code facing} for the moment it exists.
 */
public class HumiditySensorBlock extends LegacyConverterBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final MapCodec<HumiditySensorBlock> CODEC = simpleCodec(HumiditySensorBlock::new);
    private static final String TEMPERATURE = "Temperature";

    // The sensor body as v1 had it.
    private static final VoxelShape NORTH_SHAPE = Block.box(4.0, 4.0, 8.0, 12.0, 12.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(4.0, 4.0, 0.0, 12.0, 12.0, 8.0);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 4.0, 4.0, 8.0, 12.0, 12.0);
    private static final VoxelShape WEST_SHAPE = Block.box(8.0, 4.0, 4.0, 16.0, 12.0, 12.0);
    private static final VoxelShape UP_SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 8.0, 12.0);
    private static final VoxelShape DOWN_SHAPE = Block.box(4.0, 8.0, 4.0, 12.0, 16.0, 12.0);

    public HumiditySensorBlock(Properties properties) {
        super(properties, MineqttBlocks.TEMPERATURE_SENSOR_BLOCK);
        this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<HumiditySensorBlock> codec() {
        return CODEC;
    }

    /**
     * A v1 humidity sensor's data is the Climate Sensor's v1 data as it stands: its slots, owner, private mode and name,
     * its interval and its last humidity. Its {@code Humidity} without a {@code Temperature} is what makes the Climate
     * Sensor publish the humidity on the v1 topic ({@code raw_value} humidity).
     */
    @Override
    public CompoundTag convert(CompoundTag legacy) {
        return toClimateSensor(legacy);
    }

    /** {@link #convert}, without a block instance (blocks cannot be made outside the registry). */
    public static CompoundTag toClimateSensor(CompoundTag legacy) {
        CompoundTag data = legacy.copy();
        data.remove(TEMPERATURE);
        return data;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            case UP -> UP_SHAPE;
            case DOWN -> DOWN_SHAPE;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HumiditySensorBlockEntity(pos, state);
    }
}

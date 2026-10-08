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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Smart Lamp block. Colour and state live in its block entity; only the light level is a block state property,
 * because Minecraft's lighting needs it. {@code FACING} is unused and kept so existing block states stay valid.
 */
public class RgbLedBlock extends BaseMqttBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final IntegerProperty LIGHT_LEVEL = IntegerProperty.create("light_level", 0, 15);

    // Define the shape that matches our model (socket + bulb)
    // Socket base: 5,0,5 to 11,3,11 (6x3x6)
    // Socket middle: 6,3,6 to 10,5,10 (4x2x4)
    // Bulb: approximately 5,5,5 to 11,14,11 (6x9x6 for pear shape)
    private static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 14.0, 11.0);

    public final MapCodec<RgbLedBlock> CODEC = simpleCodec(RgbLedBlock::new);

    public RgbLedBlock(Properties properties) {
        super(properties.lightLevel(state -> state.getValue(LIGHT_LEVEL)), simpleCodec(RgbLedBlock::new));
        this.registerDefaultState(this.getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIGHT_LEVEL, 0));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIGHT_LEVEL);
    }

    @Override
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

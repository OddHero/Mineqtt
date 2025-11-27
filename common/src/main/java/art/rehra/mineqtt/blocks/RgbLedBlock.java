package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.mqtt.BlockStateManager;
import art.rehra.mineqtt.mqtt.homeassistant.HomeAssistantDiscoveryManager;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class RgbLedBlock extends BaseSubscriberBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final IntegerProperty LIGHT_LEVEL = IntegerProperty.create("light_level", 0, 15);
    // Note: RGB and LIT moved to block entity data to reduce block states
    // LIGHT_LEVEL is in block state because Minecraft's lighting system requires it
    // This reduces state count from 49k to just 6 states (one per facing direction)
    // Light emission and color are now stored in the block entity

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
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Unregister from Home Assistant discovery before the block is destroyed
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof RgbLedBlockEntity ledEntity) {
                String topic = ledEntity.getCombinedTopic();
                if (topic != null && !topic.isEmpty()) {
                    String blockId = level.dimension().location() + ":" + pos.toShortString();
                    HomeAssistantDiscoveryManager.unregisterDevice(topic, blockId);
                }
            }

            // Remove block state from persistence
            BlockStateManager.removeBlockState(level.dimension(), pos);
        }

        return super.playerWillDestroy(level, pos, state, player);
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


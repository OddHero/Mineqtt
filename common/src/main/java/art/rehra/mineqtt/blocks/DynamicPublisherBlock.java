package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.legacy.DecommissionedBlock;
import art.rehra.mineqtt.legacy.DecommissionedBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The v1 Dynamic Publisher, decommissioned in MineQTT 2 (design §7.2): it published server information (time, tick,
 * players, dimension, weather) on a rising redstone edge, which the MineQTT Bridge device's telemetry and the Climate
 * Sensor now report. It keeps v1's {@code powered} property, so saved states load, and its slot items until broken.
 */
public class DynamicPublisherBlock extends DecommissionedBlock {

    public static final MapCodec<DynamicPublisherBlock> CODEC = simpleCodec(DynamicPublisherBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public DynamicPublisherBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<DynamicPublisherBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DecommissionedBlockEntity(MineqttBlockEntityTypes.DYNAMIC_PUBLISHER_BLOCK.get(), pos, state);
    }
}

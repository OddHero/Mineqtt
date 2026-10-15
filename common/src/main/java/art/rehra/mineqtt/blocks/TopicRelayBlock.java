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
 * The v1 Topic Relay, decommissioned in MineQTT 2 (design §7.2): it republished the last message of one topic to
 * another on a rising redstone edge. Routing MQTT to MQTT belongs in Home Assistant, Node-RED or a broker bridge, and a
 * block that copies any topic it can read to any topic it may write is a hole in the topic ACL. It keeps v1's
 * {@code powered} property, so saved states load, and its slot items until broken.
 */
public class TopicRelayBlock extends DecommissionedBlock {

    public static final MapCodec<TopicRelayBlock> CODEC = simpleCodec(TopicRelayBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public TopicRelayBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));
    }

    // Not @Override: blocks have no codec from 26.3 on.
    protected MapCodec<TopicRelayBlock> codec() {
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
        return new DecommissionedBlockEntity(MineqttBlockEntityTypes.TOPIC_RELAY_BLOCK.get(), pos, state);
    }
}

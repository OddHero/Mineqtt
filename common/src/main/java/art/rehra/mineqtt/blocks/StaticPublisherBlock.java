package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.StaticPublisherBlockEntity;
import art.rehra.mineqtt.legacy.LegacyConverterBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The v1 Static Publisher, which converts into a Redstone Input ({@code redstone_publisher_block}, M2 plan D11) with its
 * {@code powered} state, its topic, its owner and its name. Its message stays the payload of every rising edge, sent
 * non-retained and never on the falling edge, as v1 published it. It keeps v1's {@code powered} property for the moment
 * it exists.
 */
public class StaticPublisherBlock extends LegacyConverterBlock {

    public static final MapCodec<StaticPublisherBlock> CODEC = simpleCodec(StaticPublisherBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    // v1's record of its last edge, which the powered property holds as well, and its screen's status line.
    private static final String WAS_POWERED = "wasPowered";
    private static final String LAST_PUBLISH_STATUS = "lastPublishStatus";

    public StaticPublisherBlock(Properties properties) {
        super(properties, MineqttBlocks.PUBLISHER_BLOCK);
        this.registerDefaultState(this.getStateDefinition().any().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<StaticPublisherBlock> codec() {
        return CODEC;
    }

    /**
     * A v1 Static Publisher's data is the Redstone Input's v1 data with its {@code staticMessage}: its slots, owner,
     * private mode and name, and the message that makes the input's Raw Link send it on rising edges only.
     */
    @Override
    public CompoundTag convert(CompoundTag legacy) {
        return toRedstoneInput(legacy);
    }

    /** {@link #convert}, without a block instance (blocks cannot be made outside the registry). */
    public static CompoundTag toRedstoneInput(CompoundTag legacy) {
        CompoundTag data = legacy.copy();
        data.remove(WAS_POWERED);
        data.remove(LAST_PUBLISH_STATUS);
        return data;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(POWERED, false);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StaticPublisherBlockEntity(pos, state);
    }
}

package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.SpeakerBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.2 {
import net.minecraft.world.level.redstone.Orientation;
//?}
import org.jetbrains.annotations.Nullable;

/**
 * The Speaker ({@link SpeakerBlockEntity}): sounds all around it, so it has no facing. A rising redstone signal turns
 * its siren on for its duration, as a note block plays on one; the signal falling leaves it sounding.
 */
public class SpeakerBlock extends BaseMqttBlock {
    public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);

    public SpeakerBlock(Properties properties) {
        super(properties, CODEC);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    /** The signal at the block, from any side, as a note block reads it; its block entity turns a rise into a run. */
    @Override
    //? if >=1.21.2 {
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
    //?} else {
    /*protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos orientation, boolean movedByPiston) {
    *///?}
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            speaker.signalChanged(level.hasNeighborSignal(pos));
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    /** The server plays the siren's pattern and counts its duration down every tick; the client has nothing to tick. */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : new SpeakerBlockEntity.ServerTicker<>();
    }
}

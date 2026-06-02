package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.blocks.entities.LightRemoteBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class LightRemoteBlock extends BaseMqttBlock {
    public LightRemoteBlock(Properties properties) {
        super(properties, CODEC);
    }    public static final MapCodec<LightRemoteBlock> CODEC = simpleCodec(LightRemoteBlock::new);

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LightRemoteBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return new LightRemoteBlockEntity.Ticker<>();
    }


}

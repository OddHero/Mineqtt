package art.rehra.mineqtt.blocks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public abstract class BaseSubscriberBlock extends BaseMqttBlock {

    public BaseSubscriberBlock(Properties properties, MapCodec<? extends BaseSubscriberBlock> codec) {
        super(properties, codec);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        level.scheduleTick(pos, this, 1);
    }

}


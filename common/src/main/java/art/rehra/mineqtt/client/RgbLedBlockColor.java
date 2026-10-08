package art.rehra.mineqtt.client;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

public class RgbLedBlockColor implements BlockTintSource {

    @Override
    public int color(BlockState state) {
        // Default to white if no world context is available
        return 0xFFFFFFFF;
    }

    @Override
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        // The lamp's 8-bit colour, quantised to its light level (stored in the block entity, not the block state)
        if (level != null && pos != null && level.getBlockEntity(pos) instanceof RgbLedBlockEntity ledEntity) {
            return 0xFF000000 | ledEntity.tint();
        }

        // Default to white if block entity not available
        return 0xFFFFFFFF;
    }
}

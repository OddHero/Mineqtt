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
        // Query block entity for RGB values (now stored in block entity, not block state)
        if (level != null && pos != null && level.getBlockEntity(pos) instanceof RgbLedBlockEntity ledEntity) {
            int red = ledEntity.getRed();
            int green = ledEntity.getGreen();
            int blue = ledEntity.getBlue();

            // Convert from 0-15 range to 0-255 range
            int r = (red * 255) / 15;
            int g = (green * 255) / 15;
            int b = (blue * 255) / 15;

            // Return as packed ARGB integer (0xAARRGGBB)
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        // Default to white if block entity not available
        return 0xFFFFFFFF;
    }
}

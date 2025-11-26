package art.rehra.mineqtt.client;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class RgbLedBlockColor implements BlockColor {

    @Override
    public int getColor(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        // Query block entity for RGB values (now stored in block entity, not block state)
        if (level != null && pos != null && level.getBlockEntity(pos) instanceof RgbLedBlockEntity ledEntity) {
            int red = ledEntity.getRed();
            int green = ledEntity.getGreen();
            int blue = ledEntity.getBlue();

            // Convert from 0-15 range to 0-255 range
            int r = (red * 255) / 15;
            int g = (green * 255) / 15;
            int b = (blue * 255) / 15;

            // Return as packed RGB integer (0xRRGGBB)
            return (r << 16) | (g << 8) | b;
        }

        // Default to white if block entity not available
        return 0xFFFFFF;
    }
}


package art.rehra.mineqtt.client;

import art.rehra.mineqtt.blocks.RgbLedBlock;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class RgbLedBlockColor implements BlockColor {

    @Override
    public int getColor(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        // Get RGB values from block state (0-15 range)
        int red = state.getValue(RgbLedBlock.RED);
        int green = state.getValue(RgbLedBlock.GREEN);
        int blue = state.getValue(RgbLedBlock.BLUE);

        // Convert from 0-15 range to 0-255 range
        int r = (red * 255) / 15;
        int g = (green * 255) / 15;
        int b = (blue * 255) / 15;

        // Return as packed RGB integer (0xRRGGBB)
        return (r << 16) | (g << 8) | b;
    }
}


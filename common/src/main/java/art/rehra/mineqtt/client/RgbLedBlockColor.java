package art.rehra.mineqtt.client;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.1 {
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
//?} else {
/*import net.minecraft.client.color.block.BlockColor;
import net.minecraft.world.level.BlockAndTintGetter;
*///?}

// Block tints are BlockTintSources from 26.1 on, BlockColors before.
//? if >=26.1 {
public class RgbLedBlockColor implements BlockTintSource {
//?} else {
/*public class RgbLedBlockColor implements BlockColor {
*///?}

    //? if >=26.1 {
    @Override
    public int color(BlockState state) {
        // Default to white if no world context is available
        return 0xFFFFFFFF;
    }

    @Override
    //?} else {
    /*@Override
    public int getColor(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        return colorInWorld(state, level, pos);
    }

    *///?}
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        // The lamp's 8-bit colour, quantised to its light level: the block entity has it from the render update of its
        // update tag (RenderTag), not from the block state
        if (level != null && pos != null && level.getBlockEntity(pos) instanceof RgbLedBlockEntity ledEntity) {
            return 0xFF000000 | ledEntity.tint();
        }

        // Default to white if block entity not available
        return 0xFFFFFFFF;
    }
}

package art.rehra.mineqtt.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
//? if >=1.21.2 {
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
//?}

/** Neighbour updates of a block that powers every side: 1.21.2 passes an orientation instead of the source position. */
public final class Redstone {

    private Redstone() {
    }

    /** Tells the six neighbours, and theirs, that the block's signal changed. */
    public static void updateNeighbors(Level level, BlockPos pos, Block block) {
        for (Direction direction : Direction.values()) {
            BlockPos blockPos = pos.relative(direction);
            //? if >=1.21.2 {
            Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(level, direction, Direction.UP);
            level.neighborChanged(blockPos, block, orientation);
            level.updateNeighborsAtExceptFromFacing(blockPos, block, direction.getOpposite(), orientation);
            //?} else {
            /*level.neighborChanged(blockPos, block, pos);
            level.updateNeighborsAtExceptFromFacing(blockPos, block, direction.getOpposite());
            *///?}
        }
    }
}

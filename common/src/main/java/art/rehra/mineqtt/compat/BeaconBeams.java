package art.rehra.mineqtt.compat;

import art.rehra.mineqtt.blocks.SignalBeaconBlock;
import art.rehra.mineqtt.device.types.SignalBeacon;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Beacon beams as vanilla's {@code BeaconBlockEntity.tick} scans them: a block whose light dampening is 15 stops a beam,
 * except bedrock. The dampening is {@code getLightBlock(level, pos)} before 1.21.2, {@code getLightBlock()} up to 1.21.11
 * and {@code getLightDampening()} from 26.1.
 */
public final class BeaconBeams {

    private BeaconBeams() {
    }

    /** Whether {@code state} at {@code pos} stops a beacon's beam, as vanilla's scan decides it. */
    public static boolean stopsBeam(Level level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.BEDROCK)) return false;
        //? if >=26.1 {
        return state.getLightDampening() >= 15;
        //?} else if >=1.21.2 {
        /*return state.getLightBlock() >= 15;
        *///?} else {
        /*return state.getLightBlock(level, pos) >= 15;
        *///?}
    }

    /**
     * Whether a beacon's beam reaches the block at {@code pos} from below: the first beacon under it, looking down past
     * every block that lets a beam through (Signal Beacons as they stand in for glass), and whether the first layer of its
     * pyramid is built ({@link #hasPyramid}). It reads the block's own column, which is in its loaded chunk, and the
     * beacon's base, never loading a chunk. Upwards nothing is judged: a block above that stops the beam is the
     * builder's to see.
     */
    public static SignalBeacon.Status status(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        while (true) {
            cursor.move(0, -1, 0);
            if (level.isOutsideBuildHeight(cursor)) return SignalBeacon.Status.NO_BEACON;
            BlockState state = level.getBlockState(cursor);
            if (state.is(Blocks.BEACON)) return hasPyramid(level, cursor) ? SignalBeacon.Status.BEAM : SignalBeacon.Status.INACTIVE;
            if (stopsBeam(level, cursor, SignalBeaconBlock.beamStandIn(state))) return SignalBeacon.Status.NO_BEACON;
        }
    }

    /**
     * Whether the beacon at {@code beacon} stands on the first layer of a pyramid: the 3 x 3 blocks below it are all
     * {@code #minecraft:beacon_base_blocks}, as vanilla's {@code updateBase} asks for level 1. Read here rather than from
     * the beacon, which works its level out only while its beam already reaches the sky (not while a Signal Beacon that is
     * off stops it). A base block in a chunk that is not loaded counts as missing.
     */
    public static boolean hasPyramid(Level level, BlockPos beacon) {
        BlockPos.MutableBlockPos base = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                base.set(beacon.getX() + dx, beacon.getY() - 1, beacon.getZ() + dz);
                if (!level.isLoaded(base) || !level.getBlockState(base).is(BlockTags.BEACON_BASE_BLOCKS)) return false;
            }
        }
        return true;
    }
}

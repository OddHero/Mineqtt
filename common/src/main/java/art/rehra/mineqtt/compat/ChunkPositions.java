package art.rehra.mineqtt.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Chunk positions and their packed long form, which 26.1 renamed ({@code containing}, {@code pack}, {@code x()}). */
public final class ChunkPositions {

    private ChunkPositions() {
    }

    public static ChunkPos containing(BlockPos pos) {
        //? if >=26.1 {
        return ChunkPos.containing(pos);
        //?} else {
        /*return new ChunkPos(pos);
        *///?}
    }

    /** The packed form of the chunk that holds the block. */
    public static long pack(BlockPos pos) {
        //? if >=26.1 {
        return ChunkPos.pack(pos);
        //?} else {
        /*return ChunkPos.asLong(pos);
        *///?}
    }

    public static long pack(ChunkPos chunk) {
        //? if >=26.1 {
        return chunk.pack();
        //?} else {
        /*return chunk.toLong();
        *///?}
    }

    public static int x(ChunkPos chunk) {
        //? if >=26.1 {
        return chunk.x();
        //?} else {
        /*return chunk.x;
        *///?}
    }

    public static int z(ChunkPos chunk) {
        //? if >=26.1 {
        return chunk.z();
        //?} else {
        /*return chunk.z;
        *///?}
    }

    /** Whether the chunk that holds the block ticks its entities. */
    public static boolean isEntityTicking(ServerLevel level, BlockPos pos) {
        //? if >=1.21.5 {
        return level.isPositionTickingWithEntitiesLoaded(pack(pos));
        //?} else {
        /*return level.isPositionEntityTicking(pos);
        *///?}
    }
}

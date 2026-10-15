package art.rehra.mineqtt.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block entity's side effects of its block's removal ({@code preRemoveSideEffects}): Minecraft calls them from 1.21.5
 * on, before the block is removed, unless the change sets UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS. Before 1.21.5 the mod's
 * blocks call them from {@code onRemove}, unless {@link #withoutSideEffects} runs the change.
 */
public final class BlockRemoval {
    private static boolean skipped;

    private BlockRemoval() {
    }

    /** A block entity with removal side effects. */
    public interface SideEffects {
        void preRemoveSideEffects(BlockPos pos, BlockState state);
    }

    /** Runs a block change whose removed block entity has no side effects, as UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS. */
    public static void withoutSideEffects(Runnable change) {
        boolean before = skipped;
        skipped = true;
        try {
            change.run();
        } finally {
            skipped = before;
        }
    }

    /** Whether a removal now has side effects: not inside {@link #withoutSideEffects}. */
    public static boolean sideEffects() {
        return !skipped;
    }
}

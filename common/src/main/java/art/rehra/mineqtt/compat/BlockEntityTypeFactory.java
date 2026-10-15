package art.rehra.mineqtt.compat;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

/** Creates the mod's block entity types: Minecraft's constructor is private from 1.21.2 to 26.1. */
public final class BlockEntityTypeFactory {

    private BlockEntityTypeFactory() {
    }

    public static <T extends BlockEntity> BlockEntityType<T> create(BlockEntityType.BlockEntitySupplier<T> factory, Set<Block> blocks) {
        //? if <1.21.2 {
        /*return new BlockEntityType<>(factory, blocks, null);
        *///?} else if <26.2 {
        /*try {
            // NeoForge makes the constructor public, Fabric does not; vanilla registers its own types from inside the class.
            var constructor = BlockEntityType.class.getDeclaredConstructor(BlockEntityType.BlockEntitySupplier.class, Set.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            BlockEntityType<T> type = (BlockEntityType<T>) constructor.newInstance(factory, blocks);
            return type;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create a block entity type", e);
        }
        *///?} else {
        return new BlockEntityType<>(factory, blocks);
        //?}
    }
}

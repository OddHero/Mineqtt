package art.rehra.mineqtt.compat;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;

/** Minecraft's block entity loading, which takes a ValueInput from 1.21.6 on and the tag and registries before. */
public final class BlockEntityStorage {

    private BlockEntityStorage() {
    }

    public static void loadCustomOnly(BlockEntity blockEntity, ValueInput input) {
        //? if >=1.21.6 {
        blockEntity.loadCustomOnly(input);
        //?} else {
        /*blockEntity.loadCustomOnly(((art.rehra.mineqtt.compat.storage.TagValueInput) input).tag(), input.lookup());
        *///?}
    }
}

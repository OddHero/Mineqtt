package art.rehra.mineqtt.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/** Built-in registries as lookups: a registry is one from 1.21.2 on, before it hands one out. */
public final class Lookups {

    private Lookups() {
    }

    public static HolderLookup<Block> blocks() {
        //? if >=1.21.2 {
        return BuiltInRegistries.BLOCK;
        //?} else {
        /*return BuiltInRegistries.BLOCK.asLookup();
        *///?}
    }
}

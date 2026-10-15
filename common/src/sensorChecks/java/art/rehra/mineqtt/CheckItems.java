package art.rehra.mineqtt;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
//? if >=1.21.9 {
import net.minecraft.world.item.component.TypedEntityData;
//?}
//? if >=26.1 {
import net.minecraft.core.component.DataComponents;
//?}

/** Item setup the bootstrapped checks share. */
public final class CheckItems {
    private CheckItems() {
    }

    /**
     * Gives the items their default components, which a server binds when it loads its data packs; the common ones are
     * enough for the items the checks use. Before 26.1 an item carries its components from its construction on.
     */
    public static void bindCommonComponents(Iterable<Item> items) {
        //? if >=26.1 {
        for (Item item : items) item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        //?}
    }

    public static void bindCommonComponents(Item... items) {
        bindCommonComponents(java.util.List.of(items));
    }

    /**
     * Applies an item's block entity data to a block entity, as a placed block item does: typed data from 1.21.9 on,
     * custom data with the type's id before.
     */
    public static boolean loadItemData(BlockEntityType<?> type, CompoundTag data, BlockEntity blockEntity, HolderLookup.Provider registries) {
        //? if >=1.21.9 {
        return TypedEntityData.of(type, data).loadInto(blockEntity, registries);
        //?} else {
        /*CompoundTag tag = data.copy();
        tag.putString("id", BlockEntityType.getKey(type).toString());
        return net.minecraft.world.item.component.CustomData.of(tag).loadInto(blockEntity, registries);
        *///?}
    }

}

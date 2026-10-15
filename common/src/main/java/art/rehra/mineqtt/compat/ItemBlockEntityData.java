package art.rehra.mineqtt.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
//? if >=1.21.9 {
import net.minecraft.world.item.component.TypedEntityData;
//?} else {
/*import net.minecraft.world.item.component.CustomData;
*///?}

import java.util.Optional;

/**
 * An item's block entity data ({@code minecraft:block_entity_data}): typed data from 1.21.9 on, custom data with the
 * type's id inside before.
 */
public final class ItemBlockEntityData {

    private ItemBlockEntityData() {
    }

    /** The data without the type's id; empty when the item has none. */
    public static Optional<CompoundTag> read(ItemStack stack) {
        //? if >=1.21.9 {
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data == null ? Optional.empty() : Optional.of(data.copyTagWithoutId());
        //?} else {
        /*CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) return Optional.empty();
        CompoundTag tag = data.copyTag();
        tag.remove("id");
        return Optional.of(tag);
        *///?}
    }

    /** Gives the item this data for a block entity of the type. */
    public static void set(ItemStack stack, BlockEntityType<?> type, CompoundTag tag) {
        //? if >=1.21.9 {
        stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(type, tag));
        //?} else {
        /*CompoundTag data = tag.copy();
        data.putString("id", BlockEntityType.getKey(type).toString());
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
        *///?}
    }
}

package art.rehra.mineqtt.compat;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//? if >=1.21.6 {
import net.minecraft.world.ContainerHelper;
//?} else {
/*import net.minecraft.world.ItemStackWithSlot;
*///?}

/**
 * A container's saved slots ({@code Items}) in a ValueInput and ValueOutput: Minecraft's ContainerHelper takes them from
 * 1.21.6 on; before, the slots are read and written as it does then.
 */
public final class ContainerItems {

    private ContainerItems() {
    }

    public static void load(ValueInput input, NonNullList<ItemStack> items) {
        //? if >=1.21.6 {
        ContainerHelper.loadAllItems(input, items);
        //?} else {
        /*for (ItemStackWithSlot slot : input.listOrEmpty("Items", ItemStackWithSlot.CODEC)) {
            if (slot.isValidInContainer(items.size())) items.set(slot.slot(), slot.stack());
        }
        *///?}
    }

    public static void save(ValueOutput output, NonNullList<ItemStack> items) {
        //? if >=1.21.6 {
        ContainerHelper.saveAllItems(output, items);
        //?} else {
        /*ValueOutput.TypedOutputList<ItemStackWithSlot> list = output.list("Items", ItemStackWithSlot.CODEC);
        for (int slot = 0; slot < items.size(); slot++) {
            if (!items.get(slot).isEmpty()) list.add(new ItemStackWithSlot(slot, items.get(slot)));
        }
        *///?}
    }

}

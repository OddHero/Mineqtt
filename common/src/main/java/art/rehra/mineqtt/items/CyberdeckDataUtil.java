package art.rehra.mineqtt.items;

import art.rehra.mineqtt.registry.MineqttDataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Utility for Cyberdeck item data handling using 1.21 Data Components.
 */
public final class CyberdeckDataUtil {
    private CyberdeckDataUtil() {
    }

    public static boolean isListening(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getOrDefault(MineqttDataComponents.LISTENING.get(), false);
    }

    public static void setListening(ItemStack stack, boolean value) {
        if (stack.isEmpty()) return;
        stack.set(MineqttDataComponents.LISTENING.get(), value);
    }

    public static void loadToContainer(ItemStack stack, SimpleContainer container, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return;
        ItemContainerContents contents = stack.get(MineqttDataComponents.INVENTORY.get());
        if (contents != null) {
            contents.copyInto(container.getItems());
        }
    }

    public static void saveFromContainer(ItemStack stack, SimpleContainer container, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return;
        stack.set(MineqttDataComponents.INVENTORY.get(), ItemContainerContents.fromItems(container.getItems()));
    }
}

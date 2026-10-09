package art.rehra.mineqtt.items;

import art.rehra.mineqtt.registry.MineqttDataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/**
 * The data the Configurator (the {@code cyberdeck} item) keeps on itself: its {@link ConfiguratorMode}. The explorer's
 * listening, private mode and topic slots used to live on the item too; {@link #stripDeprecated} takes them off.
 */
public final class CyberdeckDataUtil {
    private CyberdeckDataUtil() {
    }

    public static ConfiguratorMode mode(ItemStack stack) {
        return stack.getOrDefault(MineqttDataComponents.CONFIGURATOR_MODE.get(), ConfiguratorMode.CONFIGURE);
    }

    public static void setMode(ItemStack stack, ConfiguratorMode mode) {
        stack.set(MineqttDataComponents.CONFIGURATOR_MODE.get(), mode);
    }

    /**
     * Removes the deprecated explorer components (design §7.2) from a Configurator.
     *
     * @return copies of the items its old topic slots held, which belong to the player again; empty when it held none
     */
    @SuppressWarnings("deprecation")
    public static List<ItemStack> stripDeprecated(ItemStack stack) {
        stack.remove(MineqttDataComponents.LISTENING.get());
        stack.remove(MineqttDataComponents.PRIVATE_MODE.get());
        ItemContainerContents contents = stack.remove(MineqttDataComponents.INVENTORY.get());
        return contents == null ? List.of() : contents.nonEmptyItemCopyStream().toList();
    }
}

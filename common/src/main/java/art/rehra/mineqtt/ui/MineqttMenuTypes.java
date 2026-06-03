package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Registry of all MineQTT {@link MenuType}s.
 *
 * <p>Every MQTT block opens a single shared, tabbed menu type ({@link #MQTT_TABBED_MENU}).
 * The set of tabs displayed inside the menu is provided by the {@code BaseMqttBlockEntity}
 * itself, so the screen automatically adapts to whichever block is opened. The Cyberdeck
 * keeps its own dedicated menu because it is not a block but an item.</p>
 */
public class MineqttMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(MineQTT.MOD_ID, Registries.MENU);

    /**
     * Unified tabbed menu used by every MQTT block.
     */
    public static RegistrySupplier<MenuType<TabbedMqttMenu>> MQTT_TABBED_MENU;
    public static RegistrySupplier<MenuType<CyberdeckMenu>> CYBERDECK_MENU;

    @SuppressWarnings("unchecked")
    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Menu Types");

        MQTT_TABBED_MENU = MENU_TYPES.register("mqtt_tabbed",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new TabbedMqttMenu(id, inventory, inventory.player, buf.readBlockPos(), null)));

        CYBERDECK_MENU = MENU_TYPES.register("cyberdeck",
                () -> MenuRegistry.ofExtended((id, inventory, buf) -> {
                    buf.readByte(); // Read dummy byte to avoid empty buffer issues
                    ItemStack stack = inventory.player.getMainHandItem();
                    if (!(stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem)) {
                        stack = inventory.player.getOffhandItem();
                    }
                    return new CyberdeckMenu(id, inventory, stack);
                }));

        MENU_TYPES.register();
    }
}

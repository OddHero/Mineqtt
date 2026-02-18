package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

public class MineqttMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(MineQTT.MOD_ID, Registries.MENU);

    public static RegistrySupplier<MenuType<RedstoneEmitterBlockMenu>> REDSTONE_EMITTER_BLOCK_MENU;
    public static RegistrySupplier<MenuType<PublisherBlockMenu>> PUBLISHER_BLOCK_MENU;
    public static RegistrySupplier<MenuType<RgbLedBlockMenu>> RGB_LED_BLOCK_MENU;
    public static RegistrySupplier<MenuType<MotionSensorBlockMenu>> MOTION_SENSOR_BLOCK_MENU;
    public static RegistrySupplier<MenuType<CyberdeckMenu>> CYBERDECK_MENU;
    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Menu Types");

        REDSTONE_EMITTER_BLOCK_MENU = MENU_TYPES.register("redstone_emitter_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new RedstoneEmitterBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        PUBLISHER_BLOCK_MENU = MENU_TYPES.register("publisher_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new PublisherBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        RGB_LED_BLOCK_MENU = MENU_TYPES.register("rgb_led_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new RgbLedBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        MOTION_SENSOR_BLOCK_MENU = MENU_TYPES.register("motion_sensor_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new MotionSensorBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        CYBERDECK_MENU = MENU_TYPES.register("cyberdeck",
                () -> MenuRegistry.ofExtended((id, inventory, buf) -> {
                    // On server, it's safer to find it in inventory.
                    // On client, it's also safer to find it in inventory as we don't send the slot index in buf here (yet).
                    ItemStack stack = inventory.player.getMainHandItem();
                    if (!(stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem)) {
                        stack = inventory.player.getOffhandItem();
                    }
                    return new CyberdeckMenu(id, inventory, stack);
                }));

        MENU_TYPES.register();
    }
}

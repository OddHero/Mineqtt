package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;

public class MineqttMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(MineQTT.MOD_ID, Registries.MENU);

    public static RegistrySupplier<MenuType<SubscriberBlockMenu>> SUBSCRIBER_BLOCK_MENU;
    public static RegistrySupplier<MenuType<PublisherBlockMenu>> PUBLISHER_BLOCK_MENU;
    public static RegistrySupplier<MenuType<RgbLedBlockMenu>> RGB_LED_BLOCK_MENU;
    public static RegistrySupplier<MenuType<MotionSensorBlockMenu>> MOTION_SENSOR_BLOCK_MENU;
    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Menu Types");

        SUBSCRIBER_BLOCK_MENU = MENU_TYPES.register("subscriber_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new SubscriberBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        PUBLISHER_BLOCK_MENU = MENU_TYPES.register("publisher_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new PublisherBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        RGB_LED_BLOCK_MENU = MENU_TYPES.register("rgb_led_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new RgbLedBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        MOTION_SENSOR_BLOCK_MENU = MENU_TYPES.register("motion_sensor_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new MotionSensorBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        MENU_TYPES.register();
    }
}

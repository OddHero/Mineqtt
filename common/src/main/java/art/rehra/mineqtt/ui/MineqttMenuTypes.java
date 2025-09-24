package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.SubscriberBlockEntity;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.MenuType;

import java.util.function.Supplier;

public class MineqttMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(MineQTT.MOD_ID, Registries.MENU);

    public static RegistrySupplier<MenuType<SubscriberBlockMenu>> SUBSCRIBER_BLOCK_MENU;
    public static RegistrySupplier<MenuType<PublisherBlockMenu>> PUBLISHER_BLOCK_MENU;

    public static void init() {
        MineQTT.LOGGER.info("Registering MineQTT Menu Types");

        SUBSCRIBER_BLOCK_MENU = registerMenuType("subscriber_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new SubscriberBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        PUBLISHER_BLOCK_MENU = registerMenuType("publisher_block",
                () -> MenuRegistry.ofExtended((id, inventory, buf) ->
                        new PublisherBlockMenu(id, inventory, inventory.player, buf.readBlockPos())));

        MENU_TYPES.register();

    }

    public static void initClient() {
        ClientLifecycleEvent.CLIENT_SETUP.register(client -> {
            MenuRegistry.registerScreenFactory(SUBSCRIBER_BLOCK_MENU.get(), SubscriberBlockScreen::new);
            MenuRegistry.registerScreenFactory(PUBLISHER_BLOCK_MENU.get(), PublisherBlockScreen::new);
        });
    }

    public static <T extends MenuType<?>> RegistrySupplier<T> registerMenuType(String name, Supplier<T> menuType) {
        return MENU_TYPES.register(ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name), menuType);
    }
}

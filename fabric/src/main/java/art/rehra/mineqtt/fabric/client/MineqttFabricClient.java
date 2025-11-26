package art.rehra.mineqtt.fabric.client;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.client.RgbLedBlockColor;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.PublisherBlockScreen;
import art.rehra.mineqtt.ui.RgbLedBlockScreen;
import art.rehra.mineqtt.ui.SubscriberBlockScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

public final class MineqttFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // This entrypoint is suitable for setting up client-specific logic, such as rendering.
        MineQTT.LOGGER.info("MineQTT Fabric Client initializing");

        // Register screens using Fabric's native screen registration
        MenuScreens.register(MineqttMenuTypes.SUBSCRIBER_BLOCK_MENU.get(), SubscriberBlockScreen::new);
        MenuScreens.register(MineqttMenuTypes.RGB_LED_BLOCK_MENU.get(), RgbLedBlockScreen::new);
        MenuScreens.register(MineqttMenuTypes.PUBLISHER_BLOCK_MENU.get(), PublisherBlockScreen::new);

        // Register RGB LED block color handler
        ColorProviderRegistry.BLOCK.register(new RgbLedBlockColor(), MineqttBlocks.RGB_LED_BLOCK.get());
        // Note: Item color registration not needed as items default to white
    }
}

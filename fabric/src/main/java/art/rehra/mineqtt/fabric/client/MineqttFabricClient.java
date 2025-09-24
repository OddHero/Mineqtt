package art.rehra.mineqtt.fabric.client;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.PublisherBlockScreen;
import art.rehra.mineqtt.ui.SubscriberBlockScreen;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public final class MineqttFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // This entrypoint is suitable for setting up client-specific logic, such as rendering.
        MineQTT.LOGGER.info("MineQTT Fabric Client initializing");

        // Register screens using Fabric's native screen registration
        MenuScreens.register(MineqttMenuTypes.SUBSCRIBER_BLOCK_MENU.get(), SubscriberBlockScreen::new);
        MenuScreens.register(MineqttMenuTypes.PUBLISHER_BLOCK_MENU.get(), PublisherBlockScreen::new);
    }
}

package art.rehra.mineqtt.fabric.client;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.client.DisplayPanelRenderer;
import art.rehra.mineqtt.client.RgbLedBlockColor;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.framework.MineqttClientTabs;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

import java.util.List;

public final class MineqttFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MineQTT.LOGGER.info("MineQTT Fabric Client initializing");

        // Register the unified tabbed screen for every MQTT block.
        MenuScreens.register(MineqttMenuTypes.MQTT_TABBED_MENU.get(), TabbedMqttScreen::new);
        MenuScreens.register(MineqttMenuTypes.CYBERDECK_MENU.get(), CyberdeckScreen::new);

        // Wire tab-id -> client view implementations.
        MineqttClientTabs.registerAll();

        // RGB LED block color handler
        BlockColorRegistry.register(List.of(new RgbLedBlockColor()), MineqttBlocks.RGB_LED_BLOCK.get());

        // The Display Panel's text (M3 plan D18); NeoForge registers it in EntityRenderersEvent.RegisterRenderers.
        BlockEntityRendererRegistry.register(MineqttBlockEntityTypes.DISPLAY_PANEL.get(), DisplayPanelRenderer::new);
    }
}

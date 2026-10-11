package art.rehra.mineqtt.neoforge.client;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.entities.MineqttBlockEntityTypes;
import art.rehra.mineqtt.client.DisplayPanelRenderer;
import art.rehra.mineqtt.client.RgbLedBlockColor;
import art.rehra.mineqtt.neoforge.config.NeoForgeConfigScreenIntegration;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.framework.MineqttClientTabs;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import java.util.List;

@EventBusSubscriber(modid = MineQTT.MOD_ID, value = Dist.CLIENT)
public class MineqttNeoForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MineQTT.LOGGER.info("MineQTT NeoForge Client initializing");
        ModList.get().getModContainerById(MineQTT.MOD_ID).ifPresent(NeoForgeConfigScreenIntegration::register);
        // Wire tab-id -> client view implementations.
        MineqttClientTabs.registerAll();
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MineqttMenuTypes.MQTT_TABBED_MENU.get(), TabbedMqttScreen::new);
        event.register(MineqttMenuTypes.CYBERDECK_MENU.get(), CyberdeckScreen::new);
    }

    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.BlockTintSources event) {
        event.register(List.of(new RgbLedBlockColor()), MineqttBlocks.RGB_LED_BLOCK.get());
    }

    /**
     * The Display Panel's text (M3 plan D18), as the Fabric client registers it through Architectury. NeoForge's own event
     * runs while the block entity renderers are built, so the renderer is there at the first resource load; Architectury's
     * NeoForge registry adds it to vanilla's provider map directly, which FMLClientSetupEvent may reach after that load.
     */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(MineqttBlockEntityTypes.DISPLAY_PANEL.get(), DisplayPanelRenderer::new);
    }
}

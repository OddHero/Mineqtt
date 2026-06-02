package art.rehra.mineqtt.neoforge.client;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.client.RgbLedBlockColor;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.framework.MineqttClientTabs;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = MineQTT.MOD_ID, value = Dist.CLIENT)
public class MineqttNeoForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MineQTT.LOGGER.info("MineQTT NeoForge Client initializing");
        // Wire tab-id -> client view implementations.
        MineqttClientTabs.registerAll();
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MineqttMenuTypes.MQTT_TABBED_MENU.get(), TabbedMqttScreen::new);
        event.register(MineqttMenuTypes.CYBERDECK_MENU.get(), CyberdeckScreen::new);
    }

    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register(new RgbLedBlockColor(), MineqttBlocks.RGB_LED_BLOCK.get());
    }
}

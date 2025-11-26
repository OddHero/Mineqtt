package art.rehra.mineqtt.neoforge.client;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.client.RgbLedBlockColor;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import art.rehra.mineqtt.ui.PublisherBlockScreen;
import art.rehra.mineqtt.ui.RgbLedBlockScreen;
import art.rehra.mineqtt.ui.SubscriberBlockScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = MineQTT.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class MineqttNeoForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MineQTT.LOGGER.info("MineQTT NeoForge Client initializing");
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MineqttMenuTypes.SUBSCRIBER_BLOCK_MENU.get(), SubscriberBlockScreen::new);
        event.register(MineqttMenuTypes.RGB_LED_BLOCK_MENU.get(), RgbLedBlockScreen::new);
        event.register(MineqttMenuTypes.PUBLISHER_BLOCK_MENU.get(), PublisherBlockScreen::new);
    }

    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register(new RgbLedBlockColor(), MineqttBlocks.RGB_LED_BLOCK.get());
        // Note: Item color registration not needed as items default to white
    }
}


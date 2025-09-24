package art.rehra.mineqtt.fabric.client;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.ui.MineqttMenuTypes;
import net.fabricmc.api.ClientModInitializer;

public final class MineqttFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // This entrypoint is suitable for setting up client-specific logic, such as rendering.
        MineQTT.LOGGER.info("MineQTT Fabric Client initializing");

        MineqttMenuTypes.initClient();
    }
}

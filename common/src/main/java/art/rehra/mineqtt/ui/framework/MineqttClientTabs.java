package art.rehra.mineqtt.ui.framework;

import art.rehra.mineqtt.ui.framework.tabs.*;
import art.rehra.mineqtt.ui.framework.views.*;

/**
 * Client-side bootstrap that wires every built-in MQTT tab id to its on-screen
 * implementation. Must be called once per client (both Fabric &amp; NeoForge).
 */
public final class MineqttClientTabs {

    private MineqttClientTabs() {
    }

    public static void registerAll() {
        MqttTabViews.register(SettingsTab.ID, SettingsTabView::new);
        MqttTabViews.register(PublisherValuesTab.ID, PublisherValuesTabView::new);
        MqttTabViews.register(MotionSensorTab.ID, MotionSensorTabView::new);
        MqttTabViews.register(RgbLedStatusTab.ID, RgbLedStatusTabView::new);
        MqttTabViews.register(LightRemoteTab.ID, LightRemoteTabView::new);
    }
}

package art.rehra.mineqtt.ui.framework;

import art.rehra.mineqtt.ui.console.ConsoleBridgeTab;
import art.rehra.mineqtt.ui.console.ConsoleBridgeTabView;
import art.rehra.mineqtt.ui.console.ConsoleDevicesTab;
import art.rehra.mineqtt.ui.console.ConsoleDevicesTabView;
import art.rehra.mineqtt.ui.console.ConsoleGroupsTab;
import art.rehra.mineqtt.ui.console.ConsoleGroupsTabView;
import art.rehra.mineqtt.ui.device.AccessTab;
import art.rehra.mineqtt.ui.device.AccessTabView;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.device.AdvancedTabView;
import art.rehra.mineqtt.ui.device.ControlsTab;
import art.rehra.mineqtt.ui.device.ControlsTabView;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.device.DiagnosticsTabView;
import art.rehra.mineqtt.ui.device.GeneralTab;
import art.rehra.mineqtt.ui.device.GeneralTabView;
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
        MqttTabViews.register(GeneralTab.ID, GeneralTabView::new);
        MqttTabViews.register(ControlsTab.ID, ControlsTabView::new);
        MqttTabViews.register(AdvancedTab.ID, AdvancedTabView::new);
        MqttTabViews.register(AccessTab.ID, AccessTabView::new);
        MqttTabViews.register(DiagnosticsTab.ID, DiagnosticsTabView::new);
        MqttTabViews.register(ConsoleBridgeTab.ID, ConsoleBridgeTabView::new);
        MqttTabViews.register(ConsoleDevicesTab.ID, ConsoleDevicesTabView::new);
        MqttTabViews.register(ConsoleGroupsTab.ID, ConsoleGroupsTabView::new);
        MqttTabViews.register(SettingsTab.ID, SettingsTabView::new);
        MqttTabViews.register(BlockSensorTab.ID, BlockSensorTabView::new);
        MqttTabViews.register(BlockEmitterTab.ID, BlockEmitterTabView::new);
        MqttTabViews.register(CyberdeckExplorerTab.ID, CyberdeckExplorerTabView::new);
        MqttTabViews.register(CyberdeckPublishTab.ID, CyberdeckPublishTabView::new);
        MqttTabViews.register(CronTimerTab.ID, CronTimerTabView::new);
        MqttTabViews.register(IntervalTimerTab.ID, IntervalTimerTabView::new);
        MqttTabViews.register(AlarmClockTab.ID, AlarmClockTabView::new);
        MqttTabViews.register(GameTimeTimerTab.ID, GameTimeTimerTabView::new);
    }
}

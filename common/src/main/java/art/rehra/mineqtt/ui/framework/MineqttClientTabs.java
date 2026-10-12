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
import art.rehra.mineqtt.ui.device.CronScheduleTab;
import art.rehra.mineqtt.ui.device.CronScheduleTabView;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.device.DiagnosticsTabView;
import art.rehra.mineqtt.ui.device.GeneralTab;
import art.rehra.mineqtt.ui.device.GeneralTabView;
import art.rehra.mineqtt.ui.device.TwinTab;
import art.rehra.mineqtt.ui.device.TwinTabView;
import art.rehra.mineqtt.ui.explorer.DevicesTab;
import art.rehra.mineqtt.ui.explorer.DevicesTabView;
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
        MqttTabViews.register(CronScheduleTab.ID, CronScheduleTabView::new);
        MqttTabViews.register(TwinTab.ID, TwinTabView::new);
        MqttTabViews.register(AdvancedTab.ID, AdvancedTabView::new);
        MqttTabViews.register(AccessTab.ID, AccessTabView::new);
        MqttTabViews.register(DiagnosticsTab.ID, DiagnosticsTabView::new);
        MqttTabViews.register(ConsoleBridgeTab.ID, ConsoleBridgeTabView::new);
        MqttTabViews.register(ConsoleDevicesTab.ID, ConsoleDevicesTabView::new);
        MqttTabViews.register(ConsoleGroupsTab.ID, ConsoleGroupsTabView::new);
        MqttTabViews.register(DevicesTab.ID, DevicesTabView::new);
        MqttTabViews.register(SettingsTab.ID, SettingsTabView::new);
        MqttTabViews.register(CyberdeckExplorerTab.ID, CyberdeckExplorerTabView::new);
        MqttTabViews.register(CyberdeckPublishTab.ID, CyberdeckPublishTabView::new);
    }
}

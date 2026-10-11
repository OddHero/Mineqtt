package art.rehra.mineqtt.items;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.TwinPicker;
import art.rehra.mineqtt.ui.device.TwinTab;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The Configurator's Bind mode (M3 plan D19, the M2-17 recipe): a click on a device whose type takes part in the Home
 * Assistant twin opens its screen, within reach or from afar, on the Twin tab with the entity picker open for its
 * binding key. Opening it needs CONFIGURE, as every Configurator step does ({@link ConfiguratorTarget}); what the picker
 * lists and what a pick binds is judged by the device screen's guard and the twin gate, as on the tab itself. A click
 * into the air, or on a device without a twin, says how the mode works. Bind keeps nothing between clicks. Server
 * thread only.
 */
public final class ConfiguratorBind {
    private ConfiguratorBind() {
    }

    /** A click in Bind mode on a device the player may use, within reach or from afar. */
    public static void click(ServerPlayer player, DeviceBlockEntity device) {
        ConfiguratorTarget clicked = ConfiguratorTarget.of(player, device).orElse(null);
        if (clicked == null) return;
        TwinAdapter adapter = device.deviceType().twin().orElse(null);
        if (adapter == null) {
            ConfiguratorTarget.tell(player, Component.translatable("mineqtt.configurator.bind.no_twin", clicked.record().name()));
            return;
        }
        DeviceMenu.open(player, device, TwinTab.ID, pick(TwinPicker.keys(adapter)));
    }

    /**
     * The binding key the picker opens for: the only one a type has, {@code ""} to ask which first when it has several,
     * or null for none (the tab alone).
     */
    static String pick(List<String> keys) {
        if (keys.isEmpty()) return null;
        return keys.size() == 1 ? keys.getFirst() : "";
    }

    /** A click into the air, or on a block that is no device, in Bind mode: says how the mode works. */
    public static void clickAir(ServerPlayer player) {
        ConfiguratorTarget.tell(player, Component.translatable("mineqtt.configurator.bind.hint"));
    }
}

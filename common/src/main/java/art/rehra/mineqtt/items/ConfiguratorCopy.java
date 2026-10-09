package art.rehra.mineqtt.items;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceClipboard;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Configurator's Copy-Paste mode (design §7.4, M2 plan §4.2 M2-17): click device A to copy its settings
 * ({@link DeviceClipboard}: the Raw Link settings, the type's settings, the Home Assistant exposure and the access mode),
 * then each device of A's type to paste them onto. A paste sends every setting as the player would from the device's
 * screen ({@link DeviceRuntime#paste}), so each is checked for them, and the action bar counts what changed; refusals
 * are listed in chat. Clicking A again, or into the air, empties the clipboard. Copying needs CONFIGURE on A, as every
 * Configurator step does. The clipboard is kept on the server, per player, until they empty it, change the
 * Configurator's mode or leave. Server thread only.
 */
public final class ConfiguratorCopy {
    private static final Map<UUID, Copied> CLIPBOARDS = new HashMap<>();

    /** What a player copied, and from which device. */
    private record Copied(UUID source, DeviceClipboard clipboard) {
    }

    private ConfiguratorCopy() {
    }

    /**
     * A click in Copy-Paste mode on a device the player may use, within reach or from afar: with an empty clipboard it
     * copies the device, else it pastes onto it; on the device copied it empties the clipboard.
     */
    public static void click(ServerPlayer player, DeviceBlockEntity device) {
        ConfiguratorTarget clicked = ConfiguratorTarget.of(player, device).orElse(null);
        if (clicked == null) return;
        DeviceRecord record = clicked.record();
        Copied copied = CLIPBOARDS.get(player.getUUID());
        if (copied == null) {
            DeviceClipboard clipboard = DeviceClipboard.of(device.deviceType(), DeviceRuntime.view(player, device));
            CLIPBOARDS.put(player.getUUID(), new Copied(record.id(), clipboard));
            tell(player, Component.translatable("mineqtt.configurator.copy_paste.copied", record.name()));
        } else if (copied.source().equals(record.id())) {
            CLIPBOARDS.remove(player.getUUID());
            tell(player, Component.translatable("mineqtt.configurator.copy_paste.stopped", copied.clipboard().source()));
        } else if (!copied.clipboard().type().equals(record.type())) {
            tell(player, Component.translatable("mineqtt.configurator.copy_paste.other_type", record.name(), copied.clipboard().source()));
        } else {
            DeviceClipboard current = DeviceClipboard.of(device.deviceType(), DeviceRuntime.view(player, device));
            DeviceRuntime.Pasted pasted = clicked.devices().paste(record, copied.clipboard(), current, DeviceRuntime.Editor.of(player, device));
            pasted.refused().forEach(reason -> player.sendSystemMessage(Component.translatable("mineqtt.configurator.copy_paste.refused", reason)));
            tell(player, pasted.refused().isEmpty()
                    ? Component.translatable("mineqtt.configurator.copy_paste.pasted", record.name(), pasted.changed(), pasted.unchanged())
                    : Component.translatable("mineqtt.configurator.copy_paste.pasted_refused", record.name(), pasted.changed(), pasted.refused().size()));
        }
    }

    /** A click into the air, or on a block that is no device, in Copy-Paste mode: empties the clipboard, or says how the mode works. */
    public static void clickAir(ServerPlayer player) {
        Copied copied = CLIPBOARDS.remove(player.getUUID());
        tell(player, copied == null ? Component.translatable("mineqtt.configurator.copy_paste.hint")
                : Component.translatable("mineqtt.configurator.copy_paste.stopped", copied.clipboard().source()));
    }

    /** The player changed the Configurator's mode or left: the clipboard is emptied. */
    public static void forget(UUID player) {
        CLIPBOARDS.remove(player);
    }

    /** The server stops. */
    public static void clear() {
        CLIPBOARDS.clear();
    }

    private static void tell(ServerPlayer player, Component message) {
        ConfiguratorTarget.tell(player, message);
    }
}

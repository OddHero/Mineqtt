package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The device types MineQTT knows, so a registry record of an unloaded device can be resolved to its type. Types are
 * registered during mod init; a record whose type is unknown (e.g. after a downgrade) is kept but not published.
 */
public final class DeviceTypes {
    private static final Map<Identifier, DeviceType> TYPES = new LinkedHashMap<>();

    private DeviceTypes() {
    }

    /** @throws IllegalStateException when a type with the same id is already registered */
    public static synchronized void register(DeviceType type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) throw new IllegalStateException("Device type registered twice: " + type.id());
    }

    /** Registers MineQTT's own types; mod init calls it once, on client and server alike. */
    public static void registerBuiltIn() {
        register(SmartLamp.TYPE);
        register(RedstoneOutput.TYPE);
        register(RedstoneInput.TYPE);
        register(PresenceSensor.TYPE);
        register(SmartPlug.TYPE);
        register(ClimateSensor.TYPE);
        register(WallSwitch.TYPE);
    }

    public static synchronized @Nullable DeviceType get(Identifier id) {
        return TYPES.get(id);
    }

    /** Every registered type, in the order of registration. */
    public static synchronized List<DeviceType> all() {
        return List.copyOf(TYPES.values());
    }
}

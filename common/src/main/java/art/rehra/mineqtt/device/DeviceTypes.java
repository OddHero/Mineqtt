package art.rehra.mineqtt.device;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
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

    public static synchronized @Nullable DeviceType get(Identifier id) {
        return TYPES.get(id);
    }
}

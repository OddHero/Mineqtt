package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.Optional;

/**
 * A writable field a player configures in game: a GUI control picked by the field's {@link PortType} and the bounds of
 * what a client may send (from M2-6), behind CONFIGURE rather than USE. When Home Assistant should change it too, the
 * type also declares a config entity for the field.
 */
public record Setting<T>(Field<T> field) {
    public Setting {
        if (!field.access().writable()) throw new IllegalArgumentException("Setting " + field.key() + " needs a writable field");
        if (field.inline()) throw new IllegalArgumentException("Setting " + field.key() + " needs a field of its own key");
    }

    public String key() {
        return field.key();
    }

    public PortType kind() {
        return field.kind();
    }

    /**
     * A value sent by a client, decoded through the field's codec. Stricter than a Home Assistant command: a value that
     * does not fit the kind (a number out of range, an unknown enum name, garbage) is an error instead of being clamped.
     */
    public DataResult<T> parse(Tag value) {
        return parse(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, value));
    }

    /** {@link #parse(Tag)} of a value given as JSON, as a command twin reads it ({@link DeviceConfig#commandValue}). */
    public DataResult<T> parse(JsonElement json) {
        Optional<String> problem = field.kind().problem(json);
        if (problem.isPresent()) return DataResult.error(() -> "Setting " + field.key() + ": " + problem.get());
        return field.codec().parse(JsonOps.INSTANCE, json);
    }
}

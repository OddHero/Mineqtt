package art.rehra.mineqtt.device;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * A writable field a player configures in game (GUI control and C2S bounds, from M2). Its value is bounded by the
 * field's codec; when Home Assistant should change it too, the type also declares a config entity for the field.
 */
public record Setting<T>(Field<T> field) {
    public Setting {
        if (!field.access().writable()) throw new IllegalArgumentException("Setting " + field.key() + " needs a writable field");
    }

    /** A value sent by a client, decoded and bounded like any other input; an error for anything out of shape. */
    public DataResult<T> parse(Tag value) {
        return field.codec().parse(NbtOps.INSTANCE, value);
    }
}

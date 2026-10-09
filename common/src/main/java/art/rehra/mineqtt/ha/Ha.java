package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.device.Field;
import net.minecraft.util.StringRepresentable;

import java.util.Arrays;
import java.util.List;

/**
 * Builders for the Home Assistant entities a device type declares (design §6.4). Each entity is named after its key
 * until {@code .name(...)} says otherwise; {@code .name(null)} makes it the device's primary entity.
 */
public final class Ha {
    private Ha() {
    }

    /** A JSON-schema light with on/off only; add brightness, colour modes and effects with its modifiers. */
    public static HaEntity.Light light(String key, Field<?> field) {
        return new HaEntity.Light(HaEntity.Meta.of(key), field, false, List.of("onoff"), 2000, 6500, List.of());
    }

    /** A switch over a field; set its values with {@code .on(...)} and {@code .off(...)}. */
    public static <T> HaEntity.Switch<T> switchOf(String key, Field<T> field) {
        return new HaEntity.Switch<>(HaEntity.Meta.of(key), field, null, null, null);
    }

    /** A number input; the range defaults to 0–100 in steps of 1. */
    public static HaEntity.Number number(String key, Field<Integer> field) {
        return new HaEntity.Number(HaEntity.Meta.of(key), field, 0, 100, 1, false, null);
    }

    public static HaEntity.BinarySensor binarySensor(String key, Field<Boolean> field) {
        return new HaEntity.BinarySensor(HaEntity.Meta.of(key), field, null);
    }

    public static HaEntity.Sensor sensor(String key, Field<?> field) {
        return new HaEntity.Sensor(HaEntity.Meta.of(key), field, null, null, null, null);
    }

    /** A select whose options are the serialized names of the field's enum. */
    public static <E extends Enum<E> & StringRepresentable> HaEntity.Select select(String key, Field<E> field) {
        List<String> options = Arrays.stream(field.defaultValue().getDeclaringClass().getEnumConstants())
                .map(StringRepresentable::getSerializedName).toList();
        return new HaEntity.Select(HaEntity.Meta.of(key), field, options);
    }

    public static HaEntity.Event event(String key, String... eventTypes) {
        return new HaEntity.Event(HaEntity.Meta.of(key), List.of(eventTypes), null);
    }

    public static HaEntity.Button button(String key) {
        return new HaEntity.Button(HaEntity.Meta.of(key));
    }

    /** A text input of up to 255 characters. */
    public static HaEntity.Text text(String key, Field<String> field) {
        return new HaEntity.Text(HaEntity.Meta.of(key), field, 255);
    }

    public static HaEntity.Notify notify(String key) {
        return new HaEntity.Notify(HaEntity.Meta.of(key));
    }

    /**
     * A device trigger keyed {@code <type>_<subtype>}, fired by the declared event {@code event} whose attribute
     * {@code attribute} is the subtype.
     */
    public static HaEntity.DeviceTrigger deviceTrigger(String type, String subtype, String event, String attribute) {
        return new HaEntity.DeviceTrigger(HaEntity.Meta.of(type + "_" + subtype), type, subtype, event, attribute);
    }
}

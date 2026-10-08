package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.ha.Ha;
import com.google.gson.JsonObject;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;

/**
 * The Smart Lamp ({@code rgb_led_block}): an 8-bit colour or a colour temperature, a brightness, and the block light
 * 0–15 that the brightness gives (design §4.4, §7.2). Home Assistant sees a JSON-schema light. The Raw Link follows a
 * v1 topic with v1's grammar; a migrated lamp echoes its state to {@code <topic>/state} as v1 did.
 */
public final class SmartLamp {
    /** {@code state}, {@code brightness}, {@code color_mode}, {@code color} and the colour temperature, side by side. */
    public static final Field<LightState> LIGHT = Field.inline("light", LightState.MAP_CODEC, LightState.DEFAULT, Access.READ_WRITE);
    public static final Field<Integer> LIGHT_LEVEL = Field.intRange("light_level", 0, 15, Access.READ);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "smart_lamp"))
            .model("Smart Lamp").unloaded(UnloadedPolicy.QUEUE).groupable(true)
            .field(LIGHT).field(LIGHT_LEVEL).field(CommonFields.LOCATION)
            .entity(Ha.light("light", LIGHT).brightness(true).colorModes("rgb", "color_temp").kelvin(ValueCodecs.MIN_KELVIN, ValueCodecs.MAX_KELVIN).name(null))
            .entity(Ha.sensor("light_level", LIGHT_LEVEL).name("Emitted light level").stateClass("measurement").diagnostic().disabledByDefault())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // v1 lamps read their state from the world file, never from a retained message.
            .rawLink(RawLinkSpec.in(ValueCodecs.LIGHT).stateEcho("state", "brightness", "color"))
            .preview(SmartLamp::preview)
            .build();

    private SmartLamp() {
    }

    /** The block light a lamp gives: none while off, else 1–15 from its brightness. */
    public static int lightLevel(LightState light) {
        if (!light.on() || light.brightness() == 0) return 0;
        return Math.max(1, Math.round(light.brightness() * 15 / 255.0f));
    }

    /** The lamp's full state JSON, e.g. for a record created before its block entity loaded. */
    public static JsonObject state(LightState light, GlobalPos pos) {
        return new StateWriter().put(LIGHT, light).put(LIGHT_LEVEL, lightLevel(light)).put(CommonFields.LOCATION, pos).json();
    }

    /** The light's keys alone, as a command that sets every one of them (a group's state, a queued target). */
    public static JsonObject command(LightState light) {
        return new StateWriter().put(LIGHT, light).json();
    }

    // An unloaded lamp's state after a command: the command merged like a loaded lamp merges it.
    private static JsonObject preview(JsonObject state, CommandReader command) {
        LightState light = LIGHT.parse(state).orElse(LightState.DEFAULT).merge(command.json());
        LightState.KEYS.forEach(state::remove);
        command(light).entrySet().forEach(entry -> state.add(entry.getKey(), entry.getValue()));
        state.addProperty(LIGHT_LEVEL.key(), lightLevel(light));
        return state;
    }
}

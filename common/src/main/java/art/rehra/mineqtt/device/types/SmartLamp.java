package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightEffect;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import com.google.gson.JsonObject;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;

/**
 * The Smart Lamp ({@code rgb_led_block}): an 8-bit colour or a colour temperature, a brightness, and the block light
 * 0–15 that the brightness gives (design §4.4, §7.2). Home Assistant sees a JSON-schema light with effects; a command's
 * {@code transition} fades the block light and the tint on the server, while an effect is drawn by the clients alone.
 * The Raw Link follows a v1 topic with v1's grammar; a migrated lamp echoes its state to {@code <topic>/state} as v1 did.
 */
public final class SmartLamp {
    /** How often a fading lamp's block light and tint move on, in game ticks. */
    public static final int TRANSITION_STEP_TICKS = 2;
    /** How often a client redraws a lamp's effect, in game ticks. */
    public static final int EFFECT_STEP_TICKS = 2;
    // An effect dims the tint in this many steps, so a client redraws only when it moves a step.
    private static final float EFFECT_STEPS = 10;
    private static final int PULSE_PERIOD_TICKS = 40;
    private static final float PULSE_DIMMEST = 0.3f;
    // A candle flickers between this share and full, towards a new random brightness every few ticks.
    private static final float CANDLE_DIMMEST = 0.55f;
    private static final int CANDLE_FLICKER_TICKS = 3;

    /** {@code state}, {@code brightness}, {@code color_mode}, {@code color} and the colour temperature, side by side. */
    public static final Field<LightState> LIGHT = Field.inline("light", LightState.MAP_CODEC, LightState.DEFAULT, Access.READ_WRITE, PortType.LIGHT);
    public static final Field<Integer> LIGHT_LEVEL = Field.level("light_level", Access.READ);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "smart_lamp"))
            .model("Smart Lamp").unloaded(UnloadedPolicy.QUEUE).groupable(true)
            .field(LIGHT).field(LIGHT_LEVEL).field(CommonFields.LOCATION)
            // The light's on/off alone, as Home Assistant and a switch-like wire send it: ON, OFF or TOGGLE.
            .action(Action.state())
            // A right-click switches it on or off.
            .use(Action.STATE)
            .entity(Ha.light("light", LIGHT).brightness(true).colorModes("rgb", "color_temp").kelvin(ValueCodecs.MIN_KELVIN, ValueCodecs.MAX_KELVIN)
                    .effects(LightEffect.names()).name(null))
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

    /**
     * The share of its tint a lamp shows at game time {@code time} for its effect, in {@value #EFFECT_STEPS} steps up to
     * 1 (none). A pulse dims to {@value #PULSE_DIMMEST} and back every {@value #PULSE_PERIOD_TICKS} ticks, every lamp in
     * step; a candle flickers between {@value #CANDLE_DIMMEST} and full, its own way for each {@code seed} (its position).
     */
    public static float effectFactor(LightEffect effect, long time, long seed) {
        float factor = switch (effect) {
            case NONE -> 1;
            case PULSE -> {
                double phase = 2 * Math.PI * Math.floorMod(time, PULSE_PERIOD_TICKS) / PULSE_PERIOD_TICKS;
                yield PULSE_DIMMEST + (1 - PULSE_DIMMEST) * (float) (0.5 + 0.5 * Math.cos(phase));
            }
            case CANDLE -> {
                long step = Math.floorDiv(time, CANDLE_FLICKER_TICKS);
                float between = Math.floorMod(time, CANDLE_FLICKER_TICKS) / (float) CANDLE_FLICKER_TICKS;
                float flicker = noise(seed, step) + (noise(seed, step + 1) - noise(seed, step)) * between;
                yield CANDLE_DIMMEST + (1 - CANDLE_DIMMEST) * flicker;
            }
        };
        return Math.round(factor * EFFECT_STEPS) / EFFECT_STEPS;
    }

    // A repeatable random number in [0, 1) for a seed and a step (SplitMix64's mixer).
    private static float noise(long seed, long step) {
        long z = seed * 0x9E3779B97F4A7C15L + step;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
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

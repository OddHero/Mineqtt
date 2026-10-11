package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.types.RedstoneOutput.PowerOn;
import art.rehra.mineqtt.device.types.RedstoneOutput.Switch;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.core.HaDomains;
import art.rehra.mineqtt.twin.core.PortRead;
import art.rehra.mineqtt.twin.core.ValueMap;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Smart Plug ({@code smart_plug}): the Redstone Output's logic with an on/off preset (design §4.4, §7.2). It emits
 * full power or nothing, and Home Assistant switches it as an outlet; a right-click switches it too. Its power-on
 * behaviour decides whether it starts on or off when its server starts. The Raw Link follows a v1 topic with v1's plug
 * grammar and applies the broker's retained value, as v1 plugs did; unlike v1, the plug never sends its own state to
 * that topic, so it no longer hears itself. Bound to a Home Assistant entity, it mirrors its on and off ({@link #TWIN}).
 */
public final class SmartPlug {
    /** The level a plug emits while it is on. */
    public static final int ON_POWER = 15;

    public static final Field<Switch> STATE = Field.onOff("state", Switch.class, Access.READ);
    /**
     * 0 or {@value #ON_POWER}: writable like the Output's level, so a queued command and a group's state carry the plug's
     * on/off; any level above 0 switches it on.
     */
    public static final Field<Integer> POWER = Field.level("power", Access.READ_WRITE);
    public static final Field<PowerOn> POWER_ON = Field.enumOf("power_on_behaviour", PowerOn.class, Access.READ_WRITE);
    /** The Home Assistant domains a plug mirrors, in the picker's order (M3 plan D4, D5). */
    public static final List<String> TWIN_DOMAINS = List.of("switch", "input_boolean", "light", "fan");
    /**
     * How a plug mirrors a Home Assistant entity (M3 plan D5): on while the entity is on, off while it is off; a
     * transitional state holds. The source owns the plug's on and off; its power-on behaviour stays a setting. With control
     * on, a right-click or a wire switches the entity instead ({@link #forward}).
     */
    public static final TwinAdapter TWIN = TwinAdapter.builder(POWER.key())
            .source((domain, settings) -> Map.of(POWER.key(), new PortRead(ValuePath.keys(ValuePath.STATE), new ValueMap.LevelByBool(false))),
                    TWIN_DOMAINS.toArray(String[]::new))
            .mirrors(Action.STATE)
            .forward(SmartPlug::forward)
            .off(POWER.key(), new JsonPrimitive(0))
            .build();

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "smart_plug"))
            .model("Smart Plug").unloaded(UnloadedPolicy.QUEUE).groupable(true)
            .field(STATE).field(POWER).field(POWER_ON).field(CommonFields.LOCATION)
            .setting(new Setting<>(POWER_ON))
            // Home Assistant's switch sends {"state":"ON"}: ON, OFF or TOGGLE.
            .action(Action.state())
            // A right-click switches it on or off.
            .use(Action.STATE)
            .entity(Ha.switchOf("plug", POWER).on(ON_POWER).off(0).deviceClass("outlet").name(null))
            .entity(Ha.select("power_on_behaviour", POWER_ON).config())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // v1 plugs applied the broker's retained value when they subscribed.
            .rawLink(RawLinkSpec.in(ValueCodecs.SWITCH).acceptRetained(true))
            .preview(SmartPlug::preview)
            .powerOn(SmartPlug::poweredOn)
            .twin(TWIN)
            .build();

    private SmartPlug() {
    }

    /**
     * What a command to a mirrored plug asks of its source (D5, D11), through the service-call table: ON, OFF or a level
     * (on above 0) becomes {@code turn_on}/{@code turn_off}, and nothing when a LIVE state already shows it; a right-click's
     * TOGGLE becomes the opposite of a LIVE state, or {@code <domain>.toggle} of a retained one, and is refused while
     * nothing is known or the entity is unavailable.
     */
    private static List<HaCalls.Outcome> forward(JsonObject command, String domain, HaCalls.Mirror mirror, Binding binding) {
        JsonElement state = command.get(STATE.key());
        JsonElement value = state != null ? state
                : Coerce.numeric(command.get(POWER.key())).<JsonElement>map(level -> new JsonPrimitive(level.signum() > 0)).orElse(command.get(POWER.key()));
        return List.of(HaCalls.resolve(domain, HaDomains.STATE_LEAF, value, mirror));
    }

    /** A plug's state: whether it is on, and how it starts when its server starts. */
    public record Plug(boolean on, PowerOn powerOn) {
        public static final Plug DEFAULT = new Plug(false, PowerOn.PREVIOUS);
        /** The plug in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Plug> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                POWER.codec().lenientOptionalFieldOf(POWER.key(), 0).forGetter(Plug::power),
                POWER_ON.codec().lenientOptionalFieldOf(POWER_ON.key(), PowerOn.PREVIOUS).forGetter(Plug::powerOn)
        ).apply(instance, (power, powerOn) -> new Plug(power > 0, powerOn)));

        /** A v1 plug, which only had the level of its block. */
        public static Plug ofPower(int power) {
            return new Plug(power > 0, PowerOn.PREVIOUS);
        }

        /** The plug a state JSON shows, e.g. an unloaded plug's record; what is missing keeps its default. */
        public static Plug of(JsonObject state) {
            return new Plug(read(state, POWER) > 0, read(state, POWER_ON));
        }

        /** The level it emits: {@value #ON_POWER} while on, else 0. */
        public int power() {
            return on ? ON_POWER : 0;
        }

        /**
         * The plug after a partial command: {@code state} ON, OFF, TOGGLE or a boolean spelling, which wins over
         * {@code power} (on above 0); {@code power_on_behaviour}.
         *
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Plug> apply(CommandReader command) {
            Optional<Integer> level = command.get(POWER);
            Optional<PowerOn> newPowerOn = command.get(POWER_ON);
            Optional<String> state = command.text(STATE.key()).map(String::trim);
            Optional<Boolean> switchOn = state.flatMap(text -> text.equalsIgnoreCase("TOGGLE") ? Optional.of(!on) : ValueCodecs.bool(text));
            if (level.isEmpty() && newPowerOn.isEmpty() && switchOn.isEmpty()) return Optional.empty();
            boolean target = switchOn.orElseGet(() -> level.map(value -> value > 0).orElse(on));
            return Optional.of(new Plug(target, newPowerOn.orElse(powerOn)));
        }

        /** The plug as its server starts: {@link #powerOn()} decides. */
        public Plug poweredOn() {
            return switch (powerOn) {
                case PREVIOUS -> this;
                case OFF -> new Plug(false, powerOn);
                case ON -> new Plug(true, powerOn);
            };
        }

        /** A placed copy (M2 plan D14): it keeps its power-on behaviour and is off. */
        public Plug placed() {
            return new Plug(false, powerOn);
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(STATE, on ? Switch.ON : Switch.OFF).put(POWER, power()).put(POWER_ON, powerOn);
        }

        private static <T> T read(JsonObject state, Field<T> field) {
            JsonElement value = state.get(field.key());
            return value == null ? field.defaultValue() : field.parse(value).orElse(field.defaultValue());
        }
    }

    // An unloaded plug's state after a command: the command applied as a loaded plug applies it.
    private static JsonObject preview(JsonObject state, CommandReader command) {
        return Plug.of(state).apply(command).map(next -> written(next, state)).orElse(state);
    }

    // An unloaded plug's state when its server starts, as a loaded one's powerOn() sets it.
    private static JsonObject poweredOn(JsonObject state) {
        Plug plug = Plug.of(state);
        Plug started = plug.poweredOn();
        return started.equals(plug) ? state : written(started, state);
    }

    // The plug as a state JSON with the location of the state it replaces.
    private static JsonObject written(Plug plug, JsonObject replaced) {
        JsonObject state = plug.write(new StateWriter()).json();
        JsonElement location = replaced.get(CommonFields.LOCATION.key());
        if (location != null) state.add(CommonFields.LOCATION.key(), location);
        return state;
    }
}

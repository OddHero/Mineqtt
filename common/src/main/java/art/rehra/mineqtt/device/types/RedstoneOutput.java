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
import net.minecraft.util.StringRepresentable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Redstone Output ({@code redstone_emitter_block}): emits a redstone level 0–15 that Home Assistant switches and
 * sets (design §4.4, §7.2). Switching it on restores the last level above 0; in {@code switch} mode every level above 0
 * is full power. Its power-on behaviour decides the level it starts at when its server starts. The Raw Link follows a
 * v1 topic with v1's grammar and applies the broker's retained value, as v1 emitters did. Bound to a Home Assistant
 * entity, it mirrors it as a switch or analog ({@link #TWIN}).
 */
public final class RedstoneOutput {
    public static final int MAX_POWER = 15;

    /** Whether any level above 0 is emitted as such or as full power. */
    public enum Mode implements StringRepresentable {
        /** Any level 0–15, as v1 emitters had; first, so it is the default. */
        LEVEL,
        /** Off or full power. */
        SWITCH;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The level an output starts at when its server starts, as a zigbee2mqtt plug after a power cut. */
    public enum PowerOn implements StringRepresentable {
        /** The level it had; first, so it is the default and v1 emitters keep their level. */
        PREVIOUS,
        OFF,
        /** The last level above 0. */
        ON;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code ON} while the output emits a level above 0. */
    public enum Switch implements StringRepresentable {
        OFF,
        ON;

        @Override
        public String getSerializedName() {
            return name();
        }
    }

    public static final Field<Switch> STATE = Field.onOff("state", Switch.class, Access.READ);
    public static final Field<Integer> POWER = Field.level("power", Access.READ_WRITE);
    public static final Field<Mode> MODE = Field.enumOf("mode", Mode.class, Access.READ_WRITE);
    public static final Field<PowerOn> POWER_ON = Field.enumOf("power_on_behaviour", PowerOn.class, Access.READ_WRITE);
    /** The level ON restores; in the state, so an unloaded output's queued ON turns it on at that level. */
    public static final Field<Integer> LAST_LEVEL = Field.intRange("last_level", 1, MAX_POWER, MAX_POWER, null, Access.READ);
    /**
     * The Home Assistant domains an output mirrors, in the picker's order (M3 plan D4, D5): what switches on and off
     * first, then numbers, then option lists.
     */
    public static final List<String> TWIN_DOMAINS = List.of("switch", "input_boolean", "binary_sensor", "light", "fan", "cover", "lock",
            "input_number", "number", "sensor", "counter", "climate", "input_select", "select");
    // The range of a Home Assistant attribute an analog output reads when twin.min and twin.max leave it open: a light's
    // brightness is 0-255, a fan's speed and a cover's position are percentages.
    private static final Map<String, List<Integer>> ATTRIBUTE_RANGES = Map.of("brightness", List.of(0, 255), "percentage", List.of(0, 100),
            "current_position", List.of(0, 100), "current_tilt_position", List.of(0, 100));
    // The in-port of a domain that takes a number back, by the statestream leaf the output reads it from.
    private static final Map<String, String> NUMBER_PORTS = Map.of("input_number/state", HaDomains.VALUE_PORT, "number/state", HaDomains.VALUE_PORT,
            "fan/percentage", "percentage", "cover/current_position", "position", "light/brightness", "light");
    /**
     * How an output mirrors a Home Assistant entity (M3 plan D5): a domain that is on or off emits 15 or 0
     * ({@code twin.invert} swaps them; a transitional state such as {@code opening} or {@code unlocking} holds); a number
     * (an {@code input_number}, a sensor, a climate's current temperature, or the attribute {@code twin.path} names, such as
     * a light's brightness) is mapped linearly from {@code [twin.min, twin.max]} to 0–15, the range defaulting to the
     * entity's own {@code min}/{@code max}; an option list emits the index of its option. The source owns the level and the
     * switch; the mode and the power-on behaviour stay settings of the output. With control on, a change goes back to the
     * source instead ({@link #forward}).
     */
    public static final TwinAdapter TWIN = TwinAdapter.builder(POWER.key())
            .source((domain, settings) -> Map.of(POWER.key(), twinRead(domain, settings)), TWIN_DOMAINS.toArray(String[]::new))
            .mirrors(Action.STATE)
            .forward(RedstoneOutput::forward)
            // A level written back to a number is snapped to the entity's step.
            .extraLeaves(domain -> domain.equals("input_number") || domain.equals("number") ? List.of("step") : List.of())
            .off(POWER.key(), new JsonPrimitive(0))
            .shapes(TwinAdapter.Shape.INVERT, TwinAdapter.Shape.RANGE, TwinAdapter.Shape.PATH)
            .build();

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "redstone_output"))
            .model("Redstone Output").unloaded(UnloadedPolicy.QUEUE).groupable(true)
            .field(STATE).field(POWER).field(MODE).field(POWER_ON).field(LAST_LEVEL).field(CommonFields.LOCATION)
            .setting(new Setting<>(MODE)).setting(new Setting<>(POWER_ON))
            // Home Assistant's switch sends {"state":"ON"}: ON restores the last level, OFF is 0, TOGGLE flips.
            .action(Action.state())
            .entity(Ha.switchOf("output", POWER).on(MAX_POWER).off(0).name(null))
            .entity(Ha.number("power_level", POWER).range(0, MAX_POWER).slider())
            .entity(Ha.select("mode", MODE).config())
            .entity(Ha.select("power_on_behaviour", POWER_ON).config())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // v1 emitters applied the broker's retained value when they subscribed.
            .rawLink(RawLinkSpec.in(ValueCodecs.REDSTONE).acceptRetained(true))
            .preview(RedstoneOutput::preview)
            .powerOn(RedstoneOutput::poweredOn)
            .twin(TWIN)
            .build();

    private RedstoneOutput() {
    }

    /**
     * How an output reads a source of {@code domain} into its level: {@code twin.path}, else the state (a climate's
     * current temperature); the state of a domain that is on or off by on and off, an option list's by the option's
     * index, any other value as a number over the range.
     */
    public static PortRead twinRead(String domain, Binding.Settings settings) {
        ValuePath path = settings.path() != null ? settings.path()
                : domain.equals("climate") ? ValuePath.keys(ValuePath.ATTRIBUTES, "current_temperature") : ValuePath.keys(ValuePath.STATE);
        boolean state = path.equals(ValuePath.keys(ValuePath.STATE));
        if (state && HaDomains.domain(domain).flatMap(HaDomains.Domain::bool).isPresent()) return new PortRead(path, new ValueMap.LevelByBool(settings.invert()));
        if (state && (domain.equals("input_select") || domain.equals("select"))) return new PortRead(path, new ValueMap.LevelByOption(null));
        List<Integer> known = path.statestreamLeaf().map(ATTRIBUTE_RANGES::get).orElse(null);
        BigDecimal min = settings.min() != null ? settings.min() : known == null ? null : BigDecimal.valueOf(known.get(0));
        BigDecimal max = settings.max() != null ? settings.max() : known == null ? null : BigDecimal.valueOf(known.get(1));
        return new PortRead(path, new ValueMap.LevelByRange(min, max, settings.invert()));
    }

    /**
     * What a command to a mirrored output asks of its source (D5, D11), through the service-call table, read the same way
     * back as {@link #twinRead} reads the source:
     * <ul>
     *     <li>a domain that is on or off is switched: a level above 0 or ON is on, swapped by {@code twin.invert}, and a
     *     TOGGLE is the opposite of a LIVE state or {@code <domain>.toggle} of a retained one;</li>
     *     <li>a number is set to the value the level stands for in the range ({@code input_number.set_value},
     *     {@code fan.set_percentage}, {@code cover.set_cover_position}, a light's brightness); ON is the top of the range,
     *     OFF the bottom, TOGGLE the other end from what the source shows;</li>
     *     <li>an option list selects the option at the level's index;</li>
     *     <li>a value read from something Home Assistant cannot set (a sensor, a climate's current temperature) is
     *     refused, as a {@code binary_sensor} is.</li>
     * </ul>
     */
    private static List<HaCalls.Outcome> forward(JsonObject command, String domain, HaCalls.Mirror mirror, Binding binding) {
        PortRead read = twinRead(domain, binding.settings());
        JsonElement state = command.get(Action.STATE);
        JsonElement power = command.get(POWER.key());
        HaCalls.Outcome outcome = switch (read.map()) {
            case ValueMap.LevelByBool bool -> HaCalls.resolve(domain, HaDomains.STATE_LEAF, switchValue(state, power, bool.invert()), mirror);
            case ValueMap.LevelByOption option -> forwardOption(domain, option, state, power, mirror);
            case ValueMap.LevelByRange range -> forwardNumber(domain, read, range, state, power, mirror);
            default -> throw new IllegalStateException("An output reads no " + read.map());
        };
        return List.of(outcome);
    }

    // On or off for the source's state port: the state's value (TOGGLE as it is), else whether the level is above 0;
    // swapped when inverted. A value that is neither stays as it is, so the table refuses it with its reason.
    private static JsonElement switchValue(JsonElement state, JsonElement power, boolean invert) {
        JsonElement value = state != null ? state : power;
        if (isToggle(value)) return new JsonPrimitive(Coerce.TOGGLE);
        Optional<Boolean> on = state != null ? Coerce.bool(state) : Coerce.numeric(power).map(level -> level.signum() > 0);
        return on.<JsonElement>map(onValue -> new JsonPrimitive(onValue != invert)).orElse(value);
    }

    // A command's power as a level 0-15; empty when it is no number.
    private static Optional<Integer> level(JsonElement power) {
        return Coerce.numeric(power).map(level -> level.max(BigDecimal.ZERO).min(BigDecimal.valueOf(MAX_POWER)).intValue());
    }

    private static HaCalls.Outcome forwardNumber(String domain, PortRead read, ValueMap.LevelByRange range, JsonElement state, JsonElement power, HaCalls.Mirror mirror) {
        String inPort = NUMBER_PORTS.get(domain + "/" + read.path().statestreamLeaf().orElse(""));
        if (inPort == null) {
            return new HaCalls.Refused(HaCalls.Reason.READ_ONLY, HaDomains.controllable(domain)
                    ? "the output reads " + read.path() + " of it, which Minecraft cannot set" : domain + " entities cannot be controlled");
        }
        // An open end of the range is the entity's own min or max, which only its document holds.
        if ((range.min() == null || range.max() == null) && mirror.freshness() == HaCalls.Mirror.Freshness.NONE) {
            return new HaCalls.Refused(HaCalls.Reason.NOT_LIVE, "its range is not known while nothing mirrors it; set twin.min and twin.max");
        }
        Optional<Integer> level;
        if (power != null) {
            level = level(power);
            if (level.isEmpty()) return new HaCalls.Refused(HaCalls.Reason.BAD_VALUE, "not a level: " + power);
        } else if (isToggle(state)) {
            // The other end of the range from the level the source shows now.
            if (!mirror.known()) return new HaCalls.Refused(HaCalls.Reason.NOT_LIVE, "its value is not known, so it cannot be toggled");
            level = read.apply(mirror.document()).map(shown -> shown.getAsInt() > 0 ? 0 : MAX_POWER);
            if (level.isEmpty()) return new HaCalls.Refused(HaCalls.Reason.BAD_VALUE, "its value is no number, so it cannot be toggled");
        } else {
            level = Coerce.bool(state).map(on -> on ? MAX_POWER : 0);
            if (level.isEmpty()) return new HaCalls.Refused(HaCalls.Reason.BAD_VALUE, "not on, off or TOGGLE: " + state);
        }
        BigDecimal value = range.value(level.get(), mirror.document());
        return switch (inPort) {
            case "light" -> {
                JsonObject light = new JsonObject();
                light.addProperty(Action.STATE, value.signum() > 0 ? "ON" : "OFF");
                if (value.signum() > 0) light.addProperty("brightness", value.setScale(0, RoundingMode.HALF_UP).intValue());
                yield HaCalls.resolve(domain, inPort, light, mirror);
            }
            case "percentage", "position" -> HaCalls.resolve(domain, inPort, new JsonPrimitive(value.setScale(0, RoundingMode.HALF_UP).intValue()), mirror);
            default -> HaCalls.resolve(domain, inPort, new JsonPrimitive(value), mirror);
        };
    }

    private static HaCalls.Outcome forwardOption(String domain, ValueMap.LevelByOption option, JsonElement state, JsonElement power, HaCalls.Mirror mirror) {
        if (power == null && isToggle(state)) return new HaCalls.Refused(HaCalls.Reason.BAD_VALUE, "an option list cannot be toggled; set a level");
        Optional<Integer> level = power != null ? level(power) : Coerce.bool(state).map(on -> on ? MAX_POWER : 0);
        if (level.isEmpty()) return new HaCalls.Refused(HaCalls.Reason.BAD_VALUE, "not a level: " + (power != null ? power : state));
        Optional<String> picked = option.option(level.get(), mirror.document());
        if (picked.isEmpty()) {
            return mirror.freshness() == HaCalls.Mirror.Freshness.NONE ? new HaCalls.Refused(HaCalls.Reason.NOT_LIVE, "its options are not known while nothing mirrors it")
                    : new HaCalls.Refused(HaCalls.Reason.BAD_VALUE, "it has no options");
        }
        return HaCalls.resolve(domain, "option", new JsonPrimitive(picked.get()), mirror);
    }

    private static boolean isToggle(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().trim().equalsIgnoreCase(Coerce.TOGGLE);
    }

    /**
     * An output's state.
     *
     * @param power     the level it emits; in {@link Mode#SWITCH} 0 or 15
     * @param lastLevel the level ON restores: the power while it is above 0, else the last level that was
     */
    public record Output(int power, int lastLevel, Mode mode, PowerOn powerOn) {
        public static final Output DEFAULT = new Output(0, MAX_POWER, Mode.LEVEL, PowerOn.PREVIOUS);
        /** The output in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Output> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                POWER.codec().lenientOptionalFieldOf(POWER.key(), 0).forGetter(Output::power),
                LAST_LEVEL.codec().lenientOptionalFieldOf(LAST_LEVEL.key(), MAX_POWER).forGetter(Output::lastLevel),
                MODE.codec().lenientOptionalFieldOf(MODE.key(), Mode.LEVEL).forGetter(Output::mode),
                POWER_ON.codec().lenientOptionalFieldOf(POWER_ON.key(), PowerOn.PREVIOUS).forGetter(Output::powerOn)
        ).apply(instance, Output::new));

        public Output {
            power = Math.clamp(power, 0, MAX_POWER);
            if (mode == Mode.SWITCH && power > 0) power = MAX_POWER;
            lastLevel = power > 0 ? power : Math.clamp(lastLevel, 1, MAX_POWER);
        }

        /** A v1 emitter, which only had the level of its block. */
        public static Output ofLevel(int power) {
            return new Output(power, MAX_POWER, Mode.LEVEL, PowerOn.PREVIOUS);
        }

        /** The output a state JSON shows, e.g. an unloaded output's record; what is missing keeps its default. */
        public static Output of(JsonObject state) {
            return new Output(read(state, POWER), read(state, LAST_LEVEL), read(state, MODE), read(state, POWER_ON));
        }

        public boolean on() {
            return power > 0;
        }

        /**
         * The output after a partial command: {@code power}; {@code state} ON (the last level, or the command's
         * {@code power} above 0), OFF, TOGGLE or a boolean spelling; {@code mode} and {@code power_on_behaviour}.
         *
         * @return empty when the command has none of these keys in a readable form
         */
        public Optional<Output> apply(CommandReader command) {
            Optional<Integer> level = command.get(POWER);
            Optional<Mode> newMode = command.get(MODE);
            Optional<PowerOn> newPowerOn = command.get(POWER_ON);
            Optional<String> state = command.text(STATE.key()).map(String::trim);
            Optional<Boolean> switchOn = state.flatMap(text -> text.equalsIgnoreCase("TOGGLE") ? Optional.of(!on()) : ValueCodecs.bool(text));
            if (level.isEmpty() && newMode.isEmpty() && newPowerOn.isEmpty() && switchOn.isEmpty()) return Optional.empty();
            int target = level.orElse(power);
            if (switchOn.isPresent()) target = switchOn.get() ? level.filter(value -> value > 0).orElse(lastLevel) : 0;
            return Optional.of(new Output(target, lastLevel, newMode.orElse(mode), newPowerOn.orElse(powerOn)));
        }

        /** The output as its server starts: {@link #powerOn()} decides. */
        public Output poweredOn() {
            return switch (powerOn) {
                case PREVIOUS -> this;
                case OFF -> new Output(0, lastLevel, mode, powerOn);
                case ON -> new Output(lastLevel, lastLevel, mode, powerOn);
            };
        }

        /** A placed copy (M2 plan D14): it keeps its mode, power-on behaviour and the level ON restores, and emits nothing. */
        public Output placed() {
            return new Output(0, lastLevel, mode, powerOn);
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(STATE, on() ? Switch.ON : Switch.OFF).put(POWER, power).put(MODE, mode).put(POWER_ON, powerOn).put(LAST_LEVEL, lastLevel);
        }

        private static <T> T read(JsonObject state, Field<T> field) {
            JsonElement value = state.get(field.key());
            return value == null ? field.defaultValue() : field.parse(value).orElse(field.defaultValue());
        }
    }

    // An unloaded output's state after a command: the command applied as a loaded output applies it.
    private static JsonObject preview(JsonObject state, CommandReader command) {
        return Output.of(state).apply(command).map(next -> written(next, state)).orElse(state);
    }

    // An unloaded output's state when its server starts, as a loaded one's powerOn() sets it.
    private static JsonObject poweredOn(JsonObject state) {
        Output output = Output.of(state);
        Output started = output.poweredOn();
        return started.equals(output) ? state : written(started, state);
    }

    // The output as a state JSON with the location of the state it replaces.
    private static JsonObject written(Output output, JsonObject replaced) {
        JsonObject state = output.write(new StateWriter()).json();
        JsonElement location = replaced.get(CommonFields.LOCATION.key());
        if (location != null) state.add(CommonFields.LOCATION.key(), location);
        return state;
    }
}

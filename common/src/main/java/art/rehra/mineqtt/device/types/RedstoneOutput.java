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
import art.rehra.mineqtt.ha.Ha;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;
import java.util.Optional;

/**
 * The Redstone Output ({@code redstone_emitter_block}): emits a redstone level 0–15 that Home Assistant switches and
 * sets (design §4.4, §7.2). Switching it on restores the last level above 0; in {@code switch} mode every level above 0
 * is full power. Its power-on behaviour decides the level it starts at when its server starts. The Raw Link follows a
 * v1 topic with v1's grammar and applies the broker's retained value, as v1 emitters did.
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

    public static final Field<Switch> STATE = Field.enumOf("state", Switch.class, Access.READ);
    public static final Field<Integer> POWER = Field.intRange("power", 0, MAX_POWER, Access.READ_WRITE);
    public static final Field<Mode> MODE = Field.enumOf("mode", Mode.class, Access.READ_WRITE);
    public static final Field<PowerOn> POWER_ON = Field.enumOf("power_on_behaviour", PowerOn.class, Access.READ_WRITE);
    /** The level ON restores; in the state, so an unloaded output's queued ON turns it on at that level. */
    public static final Field<Integer> LAST_LEVEL = new Field<>("last_level", ValueCodecs.intRange(1, MAX_POWER), MAX_POWER, Access.READ, false);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "redstone_output"))
            .model("Redstone Output").unloaded(UnloadedPolicy.QUEUE).groupable(true)
            .field(STATE).field(POWER).field(MODE).field(POWER_ON).field(LAST_LEVEL).field(CommonFields.LOCATION)
            .entity(Ha.switchOf("output", POWER).on(MAX_POWER).off(0).name(null))
            .entity(Ha.number("power_level", POWER).range(0, MAX_POWER).slider())
            .entity(Ha.select("mode", MODE).config())
            .entity(Ha.select("power_on_behaviour", POWER_ON).config())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // v1 emitters applied the broker's retained value when they subscribed.
            .rawLink(RawLinkSpec.in(ValueCodecs.REDSTONE).acceptRetained(true))
            .preview(RedstoneOutput::preview)
            .powerOn(RedstoneOutput::poweredOn)
            .build();

    private RedstoneOutput() {
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

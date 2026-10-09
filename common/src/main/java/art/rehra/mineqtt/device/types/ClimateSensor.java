package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.ha.Ha;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The Climate Sensor ({@code temperature_sensor_block}): measures the temperature, the humidity and the illuminance
 * where it stands and names its biome (design §4.4, §7.2). It took over from the v1 temperature and humidity sensors,
 * whose humidity sensors convert into it. It takes a reading every {@code interval} seconds; its state is published at
 * most once a second, and only when the temperature moved by 0.2 °C, the humidity by 1 % or anything else changed.
 * <p>
 * The Raw Link publishes the reading {@code raw_value} names at every reading, as v1 sensors published theirs, with one
 * decimal in {@link Locale#ROOT} notation ({@code 21.5}; v1 wrote the server's locale, {@code 21,5} on a German one).
 * The readings are worked out from the world ({@link #measure}), the same way for every sensor at the same spot.
 */
public final class ClimateSensor {
    public static final double MIN_TEMPERATURE = -50;
    public static final double MAX_TEMPERATURE = 120;
    /** The brightest reading: full daylight and a block light of 15 at once. */
    public static final int MAX_ILLUMINANCE = 101_000;
    public static final int MIN_INTERVAL = 1;
    public static final int MAX_INTERVAL = 3600;
    /** v1's interval: 200 ticks. */
    public static final int DEFAULT_INTERVAL = 10;
    /** How far around itself the sensor feels heat and water, in blocks: v1's radius. */
    public static final int SCAN_RADIUS = 5;
    public static final int TICKS_PER_SECOND = 20;
    /** The sky darkening of the darkest night ({@code Level#getSkyDarken}). */
    public static final int MAX_SKY_DARKEN = 11;
    private static final int MAX_LIGHT = 15;

    /** Which reading the Raw Link carries. */
    public enum RawValue implements StringRepresentable {
        /** v1 temperature sensors published their temperature; first, so it is the default. */
        TEMPERATURE,
        /** What a converted v1 humidity sensor publishes. */
        HUMIDITY,
        ILLUMINANCE;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The biome's precipitation where the sensor stands. */
    public enum Precipitation {
        NONE, RAIN, SNOW
    }

    public static final Field<Double> TEMPERATURE = Field.decimal("temperature", MIN_TEMPERATURE, MAX_TEMPERATURE, 1, 20.0, "°C", Access.READ);
    public static final Field<Double> HUMIDITY = Field.decimal("humidity", 0, 100, 1, 50.0, "%", Access.READ);
    public static final Field<Integer> ILLUMINANCE = Field.intRange("illuminance", 0, MAX_ILLUMINANCE, 0, "lx", Access.READ);
    /** The biome's id, e.g. {@code minecraft:plains}; empty before the first reading. */
    public static final Field<String> BIOME = Field.text("biome", Access.READ);
    /** Seconds between two readings. */
    public static final Field<Integer> INTERVAL = Field.intRange("interval", MIN_INTERVAL, MAX_INTERVAL, DEFAULT_INTERVAL, "s", Access.READ_WRITE);
    public static final Field<RawValue> RAW_VALUE = Field.enumOf("raw_value", RawValue.class, Access.READ_WRITE);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "climate_sensor"))
            .model("Climate Sensor").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1))
            .deadband(TEMPERATURE, 0.2).deadband(HUMIDITY, 1)
            .field(TEMPERATURE).field(HUMIDITY).field(ILLUMINANCE).field(BIOME).field(INTERVAL).field(RAW_VALUE).field(CommonFields.LOCATION)
            .setting(new Setting<>(INTERVAL)).setting(new Setting<>(RAW_VALUE))
            .entity(Ha.sensor("temperature", TEMPERATURE).deviceClass("temperature").unit("°C").stateClass("measurement"))
            .entity(Ha.sensor("humidity", HUMIDITY).deviceClass("humidity").unit("%").stateClass("measurement"))
            .entity(Ha.sensor("illuminance", ILLUMINANCE).deviceClass("illuminance").unit("lx").stateClass("measurement"))
            .entity(Ha.number("interval", INTERVAL).range(MIN_INTERVAL, MAX_INTERVAL).unit("s").config())
            .entity(Ha.sensor("biome", BIOME).name("Biome").diagnostic())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            // The reading is the payload; v1's slots 2 and 3 held nothing for a sensor. Leaving a topic it retained to
            // clears it, as there is no reading for "off". A reading is no wireless redstone, so it is not retained unless
            // the link says so (M2 plan D2).
            .rawLink(RawLinkSpec.out(custom -> "").withoutCustomPayloads())
            .build();

    private ClimateSensor() {
    }

    /**
     * What the sensor knows of the spot it stands on, gathered from the world by its block entity.
     *
     * @param biomeTemperature the biome's base temperature, Minecraft's scale (plains 0.8, desert 2.0, snowy plains 0)
     * @param aboveSeaLevel    blocks above the sea level, negative below it
     * @param skyDarken        how much the time of day and the weather darken the sky, 0 at a clear noon to
     *                         {@value #MAX_SKY_DARKEN} at night
     * @param skyLight         the sky light reaching the spot, 0–15
     * @param blockLight       the block light at the spot, 0–15
     * @param openSky          whether the sky is visible from the spot
     * @param rainedOn         whether rain falls on the spot now
     * @param heatSources      fire, campfires, lava and magma within {@value #SCAN_RADIUS} blocks
     * @param coldSources      water and ice within {@value #SCAN_RADIUS} blocks
     */
    public record Environment(String biome, double biomeTemperature, Precipitation precipitation, int aboveSeaLevel, int skyDarken, int skyLight,
                              int blockLight, boolean openSky, boolean rainedOn, int heatSources, int coldSources) {
    }

    /**
     * The readings at a spot. v1's rules, without its random noise and with the biome's real temperature instead of
     * "has precipitation" (which made a jungle colder than a desert):
     * <ul>
     *     <li>Temperature: 20 °C per unit of the biome's temperature, less 2 (plains 14 °C, desert 38 °C, snowy plains
     *     −2 °C); 0.05 °C colder per block above the sea level and 0.1 °C warmer per block below it; up to 5 °C warmer by
     *     day and colder by night, which the open sky amplifies by half and a roof damps to a fifth; 3 °C colder in the
     *     rain; 3 °C per heat source (at most 30 °C) and −1 °C per water or ice block (at most −10 °C).</li>
     *     <li>Humidity: 25 % in a dry biome, 55 % where it snows and 65 % where it rains; 5 % drier by day and moister by
     *     night, 10 % more under a roof, 20 % more in the rain, 2 % per water or ice block (at most 30 %) and −5 % per
     *     heat source (at most −30 %).</li>
     *     <li>Illuminance: daylight (the sky light less the sky's darkening, {@code 10^(level/3)} lx: 100 000 lx at
     *     level 15, 1 000 lx at 9) plus artificial light (the block light, {@code 10^(level/5)} lx: 1 000 lx at 15),
     *     rounded; darkness is 0 lx.</li>
     * </ul>
     */
    public static Reading measure(Environment at) {
        double day = 5 * (1 - 2.0 * Math.clamp(at.skyDarken(), 0, MAX_SKY_DARKEN) / MAX_SKY_DARKEN);
        double temperature = at.biomeTemperature() * 20 - 2;
        temperature += at.aboveSeaLevel() > 0 ? -0.05 * at.aboveSeaLevel() : -0.1 * at.aboveSeaLevel();
        temperature += at.openSky() ? day * 1.5 : day * 0.2;
        if (at.rainedOn()) temperature -= 3;
        temperature += Math.min(at.heatSources() * 3.0, 30) - Math.min(at.coldSources() * 1.0, 10);

        double humidity = switch (at.precipitation()) {
            case NONE -> 25;
            case SNOW -> 55;
            case RAIN -> 65;
        };
        humidity -= day;
        if (!at.openSky()) humidity += 10;
        if (at.rainedOn()) humidity += 20;
        humidity += Math.min(at.coldSources() * 2.0, 30) - Math.min(at.heatSources() * 5.0, 30);

        int daylight = Math.clamp(at.skyLight() - at.skyDarken(), 0, MAX_LIGHT);
        int artificial = Math.clamp(at.blockLight(), 0, MAX_LIGHT);
        double lux = (daylight == 0 ? 0 : Math.pow(10, daylight / 3.0)) + (artificial == 0 ? 0 : Math.pow(10, artificial / 5.0));
        return new Reading(round(Math.clamp(temperature, MIN_TEMPERATURE, MAX_TEMPERATURE)), round(Math.clamp(humidity, 0, 100)),
                (int) Math.min(Math.round(lux), MAX_ILLUMINANCE), at.biome());
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** One reading of the sensor. */
    public record Reading(double temperature, double humidity, int illuminance, String biome) {
        public static final Reading DEFAULT = new Reading(TEMPERATURE.defaultValue(), HUMIDITY.defaultValue(), 0, "");

        /**
         * The reading {@code value} names, as the Raw Link carries it: temperature and humidity with one decimal in
         * {@link Locale#ROOT} notation, the illuminance in whole lux.
         */
        public String raw(RawValue value) {
            return switch (value) {
                case TEMPERATURE -> ValueCodecs.format(temperature, 1);
                case HUMIDITY -> ValueCodecs.format(humidity, 1);
                case ILLUMINANCE -> Integer.toString(illuminance);
            };
        }
    }

    /**
     * A sensor's settings and its last reading.
     *
     * @param interval    seconds between two readings
     * @param lastReading the game time of the last reading; not part of the state JSON
     */
    public record Climate(int interval, RawValue rawValue, Reading reading, long lastReading) {
        public static final Climate DEFAULT = new Climate(DEFAULT_INTERVAL, RawValue.TEMPERATURE, Reading.DEFAULT, 0);
        /** The sensor in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Climate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                INTERVAL.codec().lenientOptionalFieldOf(INTERVAL.key(), DEFAULT_INTERVAL).forGetter(Climate::interval),
                RAW_VALUE.codec().lenientOptionalFieldOf(RAW_VALUE.key(), RawValue.TEMPERATURE).forGetter(Climate::rawValue),
                TEMPERATURE.codec().lenientOptionalFieldOf(TEMPERATURE.key(), TEMPERATURE.defaultValue()).forGetter(climate -> climate.reading().temperature()),
                HUMIDITY.codec().lenientOptionalFieldOf(HUMIDITY.key(), HUMIDITY.defaultValue()).forGetter(climate -> climate.reading().humidity()),
                ILLUMINANCE.codec().lenientOptionalFieldOf(ILLUMINANCE.key(), 0).forGetter(climate -> climate.reading().illuminance()),
                BIOME.codec().lenientOptionalFieldOf(BIOME.key(), "").forGetter(climate -> climate.reading().biome()),
                Codec.LONG.lenientOptionalFieldOf("last_reading", 0L).forGetter(Climate::lastReading)
        ).apply(instance, (interval, rawValue, temperature, humidity, illuminance, biome, lastReading) ->
                new Climate(interval, rawValue, new Reading(temperature, humidity, illuminance, biome), lastReading)));

        public Climate {
            interval = Math.clamp(interval, MIN_INTERVAL, MAX_INTERVAL);
        }

        /**
         * A v1 temperature or humidity sensor: its interval in ticks (at least a second, rounded up to whole seconds), the
         * reading it kept and the game time it last published. A humidity sensor, converted into a Climate Sensor, keeps
         * publishing its humidity.
         *
         * @param temperature the temperature a v1 temperature sensor kept; null for a humidity sensor
         * @param humidity    the humidity a v1 humidity sensor kept; null for a temperature sensor
         */
        public static Climate ofV1(int intervalTicks, @Nullable Double temperature, @Nullable Double humidity, long lastPublish) {
            int seconds = Math.ceilDiv(Math.max(intervalTicks, TICKS_PER_SECOND), TICKS_PER_SECOND);
            RawValue rawValue = temperature == null && humidity != null ? RawValue.HUMIDITY : RawValue.TEMPERATURE;
            Reading reading = new Reading(temperature == null ? TEMPERATURE.defaultValue() : Math.clamp(temperature, MIN_TEMPERATURE, MAX_TEMPERATURE),
                    humidity == null ? HUMIDITY.defaultValue() : Math.clamp(humidity, 0, 100), 0, "");
            return new Climate(seconds, rawValue, reading, lastPublish);
        }

        /** A placed copy (M2 plan D14): the settings stay, the reading is its own spot's, taken anew. */
        public Climate placed() {
            return new Climate(interval, rawValue, Reading.DEFAULT, 0);
        }

        /** The interval in game ticks. */
        public int intervalTicks() {
            return interval * TICKS_PER_SECOND;
        }

        /** Whether a reading is due at {@code gameTime}; also when the last one lies in the future (data from another world). */
        public boolean due(long gameTime) {
            return gameTime < lastReading || gameTime - lastReading >= intervalTicks();
        }

        /** The sensor after a reading at {@code gameTime}. */
        public Climate read(Reading next, long gameTime) {
            return new Climate(interval, rawValue, next, gameTime);
        }

        /** The payload of the Raw Link: the reading {@link #rawValue} names. */
        public String raw() {
            return reading.raw(rawValue);
        }

        /**
         * The sensor after a partial command: {@code interval} and {@code raw_value}.
         *
         * @return empty when the command has neither key in a readable form
         */
        public Optional<Climate> apply(CommandReader command) {
            Optional<Integer> newInterval = command.get(INTERVAL);
            Optional<RawValue> newRawValue = command.get(RAW_VALUE);
            if (newInterval.isEmpty() && newRawValue.isEmpty()) return Optional.empty();
            return Optional.of(new Climate(newInterval.orElse(interval), newRawValue.orElse(rawValue), reading, lastReading));
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(TEMPERATURE, reading.temperature()).put(HUMIDITY, reading.humidity()).put(ILLUMINANCE, reading.illuminance())
                    .put(BIOME, reading.biome()).put(INTERVAL, interval).put(RAW_VALUE, rawValue);
        }
    }
}

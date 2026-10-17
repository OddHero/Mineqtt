package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Colors;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.ha.HaEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * The Signal Beacon ({@code signal_beacon}): a lens that colours the beam of a vanilla beacon below it, so a status shows
 * across the map. Vanilla tints a beam only in the 16 dye colours of stained glass, so every colour a command asks for
 * becomes the nearest of them ({@link #nearest}), and the device reports the colour the beam really has. Home Assistant
 * sees a JSON-schema light in {@code rgb} mode with the effects {@code off} and {@code blink}; the {@code alarm} in-port
 * takes an Alarm Panel's state and shows it: green disarmed, amber blinking while arming or pending, red armed, red
 * blinking triggered ({@link #alarm}).
 * <p>
 * The beam itself is vanilla's: the block's {@code color} and {@code lit} block states stand in for stained glass of
 * that colour, or for an opaque block while off or between two blinks, in the beacon's own beam scan (the mixin
 * {@code BeaconBlockEntityMixin}), on the server and on every client alike. Without a beacon below it the block is a
 * coloured lens and nothing more; its {@code beacon} field says so ({@link Status}).
 */
public final class SignalBeacon {
    /** A blink shows the beam this many ticks, then hides it as long: once a second, every Signal Beacon in step. */
    public static final int BLINK_HALF_PERIOD_TICKS = 10;
    /** How often the block looks for the beacon below it, in game ticks. */
    public static final int STATUS_INTERVAL_TICKS = 20;
    /** Home Assistant's brightness for a light that always shines at full: the beam has one brightness. */
    public static final int BRIGHTNESS = 255;
    /** The only colour mode: Home Assistant reads the colour only next to it. */
    public static final String COLOR_MODE = "rgb";
    /** The in-port that takes an alarm panel's state. */
    public static final String ALARM = "alarm";
    /** How much a colour's brightness (HSV value) counts against its hue and saturation when the nearest dye is picked. */
    static final double VALUE_WEIGHT = 0.75;
    /** Below this value (brightness, 0 to 1) a colour's hue fades out, so a nearly black colour is black. */
    static final double DARK_KNEE = 0.25;

    /** The beam colour of a disarmed alarm: lime, the bright green of the dyes (green itself is a dark olive). */
    public static final DyeColor DISARMED_DYE = DyeColor.LIME;
    /** The beam colour of an alarm on its way: arming, pending (the entry delay) or disarming, blinking. */
    public static final DyeColor PENDING_DYE = DyeColor.ORANGE;
    /** The beam colour of an armed alarm; it blinks while triggered. */
    public static final DyeColor ARMED_DYE = DyeColor.RED;

    /** What the beam shows on top of its colour. */
    public enum Effect implements StringRepresentable {
        /** A steady beam. */
        OFF("off"),
        /** The beam shows for {@value #BLINK_HALF_PERIOD_TICKS} ticks and hides as long, all Signal Beacons in step. */
        BLINK("blink");

        private final String name;

        Effect(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        /** Every serialized name, {@code off} first: Home Assistant's {@code effect_list}. */
        public static List<String> names() {
            return Stream.of(values()).map(Effect::getSerializedName).toList();
        }

        /** The effect of a name, without regard to case or spaces around it; {@code none} is {@code off}, too. */
        public static Optional<Effect> byName(String name) {
            String text = name.trim();
            if (text.equalsIgnoreCase("none")) return Optional.of(OFF);
            return Stream.of(values()).filter(effect -> effect.name.equalsIgnoreCase(text)).findFirst();
        }
    }

    /** Whether a beacon's beam reaches the block, as the {@code beacon} field says it. */
    public enum Status implements StringRepresentable {
        /** A beacon below on a pyramid (its first layer at least), whose beam nothing stops before it reaches the block. */
        BEAM,
        /** A beacon below without a pyramid: it shows no beam whatever the block does. */
        INACTIVE,
        /** No beacon below, or a block between them that stops its beam: a coloured lens and nothing more. */
        NO_BEACON;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A Signal Beacon's light: on or off, the dye its beam has, and its effect.
     */
    public record Beam(boolean on, DyeColor dye, Effect effect) {
        public static final Beam DEFAULT = new Beam(false, DyeColor.WHITE, Effect.OFF);
        /** The keys the light has at the top level of the device's state JSON. */
        public static final List<String> KEYS = List.of("state", "brightness", "color_mode", "color", "effect");
        /** The beam in the block entity: {@code on}, {@code dye}, {@code effect}; unreadable values keep their default. */
        public static final Codec<Beam> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.lenientOptionalFieldOf("on", false).forGetter(Beam::on),
                DyeColor.CODEC.lenientOptionalFieldOf("dye", DyeColor.WHITE).forGetter(Beam::dye),
                StringRepresentable.fromEnum(Effect::values).lenientOptionalFieldOf("effect", Effect.OFF).forGetter(Beam::effect)
        ).apply(instance, Beam::new));
        /**
         * The light's keys as Home Assistant's JSON light reads them: {@code state}, {@code brightness} (always
         * {@value #BRIGHTNESS}), {@code color_mode} ({@value #COLOR_MODE}), {@code color} (the beam colour of its dye)
         * and {@code effect}. Reading is as lenient as {@link #merge}: what is missing or unreadable keeps the default, and
         * a colour reads as its nearest dye, so the beam colour reads back as its own dye.
         */
        public static final MapCodec<Beam> MAP_CODEC = new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return KEYS.stream().map(ops::createString);
            }

            @Override
            public <T> DataResult<Beam> decode(DynamicOps<T> ops, MapLike<T> input) {
                JsonObject json = new JsonObject();
                input.entries().forEach(entry -> ops.getStringValue(entry.getFirst())
                        .ifSuccess(key -> json.add(key, ops.convertTo(JsonOps.INSTANCE, entry.getSecond()))));
                // The brightness a state carries is always full; it must not dim the colour it is read with.
                json.remove("brightness");
                return DataResult.success(DEFAULT.merge(json));
            }

            @Override
            public <T> RecordBuilder<T> encode(Beam beam, DynamicOps<T> ops, RecordBuilder<T> builder) {
                builder.add("state", ops.createString(beam.on() ? "ON" : "OFF"));
                builder.add("brightness", ops.createInt(BRIGHTNESS));
                builder.add("color_mode", ops.createString(COLOR_MODE));
                builder.add("color", JsonOps.INSTANCE.convertTo(ops, ValueCodecs.Rgb.ofPacked(beamColor(beam.dye())).toJson()));
                builder.add("effect", ops.createString(beam.effect().getSerializedName()));
                return builder;
            }
        };

        public Beam {
            java.util.Objects.requireNonNull(dye, "dye");
            java.util.Objects.requireNonNull(effect, "effect");
        }

        /**
         * Applies a light command: {@code state} (ON, OFF, TOGGLE or a boolean), {@code color} in any form Home Assistant
         * or a wire sends ({@link ValueCodecs#color(JsonElement)}; a dye name such as {@code light_blue} is that dye
         * exactly, any other colour its nearest dye), {@code color_temp_kelvin} or {@code color_temp} (the colour of that
         * temperature), and {@code effect}. A {@code brightness} beside a colour or a temperature scales it first, as Home
         * Assistant does for a light without brightness (so a dimmed white is a grey dye); a brightness alone changes
         * nothing, as the beam has one brightness. Missing keys keep their value; unknown keys, unreadable values and
         * {@code transition} are ignored.
         */
        public Beam merge(JsonObject command) {
            boolean on = this.on;
            DyeColor dye = this.dye;
            Effect effect = this.effect;
            JsonElement state = command.get(Action.STATE);
            if (state != null && state.isJsonPrimitive()) {
                String text = state.getAsString().trim();
                if (text.equalsIgnoreCase(Coerce.TOGGLE)) on = !on;
                else if (state.getAsJsonPrimitive().isBoolean()) on = state.getAsBoolean();
                else if (text.equalsIgnoreCase("ON") || text.equalsIgnoreCase("OFF")) on = text.equalsIgnoreCase("ON");
            }
            Optional<DyeColor> named = command.has("color") ? dyeNamed(command.get("color")) : Optional.empty();
            if (named.isPresent()) {
                dye = named.get();
            } else {
                OptionalInt asked = askedColor(command);
                if (asked.isPresent()) dye = nearest(scaled(asked.getAsInt(), command));
            }
            JsonElement newEffect = command.get("effect");
            if (newEffect != null && newEffect.isJsonPrimitive()) effect = Effect.byName(newEffect.getAsString()).orElse(effect);
            return new Beam(on, dye, effect);
        }

        /** Whether the beam shows at game time {@code time}: while on, and with a blink only in its first half-period. */
        public boolean lit(long time) {
            if (!on) return false;
            return effect != Effect.BLINK || Math.floorMod(Math.floorDiv(time, BLINK_HALF_PERIOD_TICKS), 2L) == 0;
        }

        /** A placed copy (M2 plan D14): its dye and effect stay, and it is off. */
        public Beam placed() {
            return new Beam(false, dye, effect);
        }

        // The colour a command asks for: its colour, else its colour temperature.
        private static OptionalInt askedColor(JsonObject command) {
            if (command.has("color")) {
                Optional<ValueCodecs.Rgb> color = ValueCodecs.color(command.get("color"));
                if (color.isPresent()) return OptionalInt.of(color.get().packed());
            }
            JsonElement temperature = command.has("color_temp_kelvin") ? command.get("color_temp_kelvin") : command.get("color_temp");
            if (temperature == null) return OptionalInt.empty();
            JsonObject kelvin = new JsonObject();
            kelvin.add(command.has("color_temp_kelvin") ? "color_temp_kelvin" : "color_temp", temperature.deepCopy());
            Integer read = ValueCodecs.LightState.DEFAULT.merge(kelvin).kelvin();
            return read == null ? OptionalInt.empty() : OptionalInt.of(Colors.kelvin(read));
        }

        // A colour scaled by the command's brightness (0-255), if it has a readable one.
        private static int scaled(int rgb, JsonObject command) {
            Optional<BigDecimal> brightness = command.has("brightness") ? Coerce.numeric(command.get("brightness")) : Optional.empty();
            if (brightness.isEmpty()) return rgb;
            double factor = Math.clamp(brightness.get().setScale(0, RoundingMode.HALF_UP).intValue(), 0, 255) / 255.0;
            return Colors.rgb((int) Math.round(((rgb >> 16) & 0xFF) * factor), (int) Math.round(((rgb >> 8) & 0xFF) * factor),
                    (int) Math.round((rgb & 0xFF) * factor));
        }
    }

    /** The beam as Home Assistant's light: {@code state}, {@code brightness}, {@code color_mode}, {@code color}, {@code effect}. */
    public static final Field<Beam> LIGHT = Field.inline("light", Beam.MAP_CODEC, Beam.DEFAULT, Access.READ_WRITE, PortType.LIGHT);
    /** The dye the beam has, by its name (e.g. {@code light_blue}). */
    public static final Field<DyeColor> DYE = Field.enumOf("dye", DyeColor.class, DyeColor.WHITE, Access.READ);
    /** Whether a beacon's beam reaches the block. */
    public static final Field<Status> BEACON = Field.enumOf("beacon", Status.class, Status.NO_BEACON, Access.READ);
    /**
     * Shows an alarm panel's state: every Home Assistant alarm state, so the Alarm Panel's {@code state} out-port and a
     * Home Assistant automation's state both fit.
     */
    public static final Action ALARM_ACTION = new Action(ALARM, PortType.enumOf(HaEntity.AlarmControlPanel.STATES), false);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "signal_beacon"))
            .model("Signal Beacon").unloaded(UnloadedPolicy.QUEUE).groupable(true).noV1Topic()
            .field(LIGHT).field(DYE).field(BEACON).field(CommonFields.LOCATION)
            // The light's on/off alone, as Home Assistant and a switch-like wire send it: ON, OFF or TOGGLE.
            .action(Action.state()).action(ALARM_ACTION)
            // Wires, Home Assistant and commands send alarm states; the Controls tab keeps its rows for the light.
            .withoutControl(ALARM)
            // A right-click switches it on or off.
            .use(Action.STATE)
            .entity(Ha.light("light", LIGHT).colorModes(COLOR_MODE).effects(Effect.names()).name(null))
            .entity(Ha.sensor("dye", DYE).name("Beam colour"))
            .entity(Ha.sensor("beacon", BEACON).name("Beacon").diagnostic())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            .preview(SignalBeacon::preview)
            .build();

    private SignalBeacon() {
    }

    /** The colour of the beam a dye gives, as vanilla's beacon draws it: the dye's texture colour without its alpha. */
    public static int beamColor(DyeColor dye) {
        return dye.getTextureDiffuseColor() & 0xFFFFFF;
    }

    /**
     * The dye whose beam colour is nearest to {@code rgb} ({@code 0xRRGGBB}). Colours are compared in the HSV cone: hue
     * and saturation as a point on the unit disc, the value (brightness) along the axis, counted {@value #VALUE_WEIGHT}
     * as much. So Home Assistant's bright pure colours, which the darker dyes never reach, still find their hue (pure
     * green is lime, pure blue is blue, cyan is cyan), dark and bright dyes of one hue part by brightness (brown and
     * orange, green and lime), and greys by brightness alone. Below {@value #DARK_KNEE} of full brightness the hue fades
     * out towards the axis, so a nearly black colour is black whatever its hue. A dye's own beam colour is that dye.
     */
    public static DyeColor nearest(int rgb) {
        double[] asked = cone(rgb);
        DyeColor best = DyeColor.WHITE;
        double bestDistance = Double.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            double[] candidate = cone(beamColor(dye));
            double dx = asked[0] - candidate[0], dy = asked[1] - candidate[1], dz = asked[2] - candidate[2];
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = dye;
            }
        }
        return best;
    }

    // A colour in the HSV cone: saturation along the hue's angle, and the value weighted.
    private static double[] cone(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0, g = ((rgb >> 8) & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double chroma = max - min;
        double saturation = max == 0 ? 0 : chroma / max;
        double hue = 0;
        if (chroma > 0) {
            if (max == r) hue = ((g - b) / chroma) % 6;
            else if (max == g) hue = (b - r) / chroma + 2;
            else hue = (r - g) / chroma + 4;
        }
        double angle = hue * Math.PI / 3;
        double radius = saturation * Math.min(1, max / DARK_KNEE);
        return new double[]{radius * Math.cos(angle), radius * Math.sin(angle), VALUE_WEIGHT * max};
    }

    /** A dye named by a JSON string (its serialized name, without regard to case; a space stands for {@code _}). */
    public static Optional<DyeColor> dyeNamed(JsonElement json) {
        if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) return Optional.empty();
        String name = json.getAsString().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return Stream.of(DyeColor.values()).filter(dye -> dye.getSerializedName().equals(name)).findFirst();
    }

    /**
     * What an alarm state shows: {@code disarmed} a steady {@link #DISARMED_DYE} beam; {@code arming}, {@code pending}
     * and {@code disarming} a blinking {@link #PENDING_DYE} one; every armed state a steady {@link #ARMED_DYE} one, and
     * {@code triggered} a blinking one. Always on. Empty for a value that is no Home Assistant alarm state.
     */
    public static Optional<Beam> alarm(JsonElement state) {
        if (state == null || !state.isJsonPrimitive()) return Optional.empty();
        return switch (state.getAsString().trim().toLowerCase(Locale.ROOT)) {
            case "disarmed" -> Optional.of(new Beam(true, DISARMED_DYE, Effect.OFF));
            case "arming", "pending", "disarming" -> Optional.of(new Beam(true, PENDING_DYE, Effect.BLINK));
            case "armed_home", "armed_away", "armed_night", "armed_vacation", "armed_custom_bypass" -> Optional.of(new Beam(true, ARMED_DYE, Effect.OFF));
            case "triggered" -> Optional.of(new Beam(true, ARMED_DYE, Effect.BLINK));
            default -> Optional.empty();
        };
    }

    /** Whether a command has anything a Signal Beacon takes: a light key or an alarm state. */
    public static boolean knows(CommandReader command) {
        return command.has(ALARM) || command.keys().stream().anyMatch(key -> Beam.KEYS.contains(key) || key.equals("color_temp")
                || key.equals("color_temp_kelvin") || key.equals("transition"));
    }

    /**
     * The beam after a command: an {@code alarm} state first (an unreadable one is ignored here, see {@link #alarm}),
     * then the light keys over it.
     */
    public static Beam apply(Beam beam, JsonObject command) {
        Beam next = alarm(command.get(ALARM)).orElse(beam);
        return next.merge(command);
    }

    /** The full state JSON of a Signal Beacon, e.g. for a record created before its block entity loaded. */
    public static JsonObject state(Beam beam, Status status, GlobalPos pos) {
        return write(new StateWriter(), beam, status).put(CommonFields.LOCATION, pos).json();
    }

    /** Writes every readable field but the location. */
    public static StateWriter write(StateWriter out, Beam beam, Status status) {
        return out.put(LIGHT, beam).put(DYE, beam.dye()).put(BEACON, status);
    }

    // An unloaded Signal Beacon's state after a command: the command applied like a loaded one applies it.
    private static JsonObject preview(JsonObject state, CommandReader command) {
        Beam beam = apply(LIGHT.parse(state).orElse(Beam.DEFAULT), command.json());
        Beam.KEYS.forEach(state::remove);
        new StateWriter().put(LIGHT, beam).put(DYE, beam.dye()).json().entrySet().forEach(entry -> state.add(entry.getKey(), entry.getValue()));
        return state;
    }
}

package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DefaultWiring;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.EventSpec;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.StatusLine;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.timer.core.CronTimerSchedule;
import art.rehra.mineqtt.device.timer.core.DayTimeSchedule;
import art.rehra.mineqtt.device.timer.core.IntervalSchedule;
import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.device.timer.core.TimerSchedule;
import art.rehra.mineqtt.device.timer.core.TimerState;
import art.rehra.mineqtt.device.timer.core.TimerStatus;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.util.GameClock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The four timers as devices (M4 plan D2): the Interval Timer, the Daylight Timer ({@code game_time_timer}), the Alarm
 * Clock and the Cron Timer, one type each from one factory. They are the flows' first trigger sources: each fire is a
 * {@code fired} event with its {@code source} ({@code schedule} or {@code manual}), and a press on {@code trigger}
 * ("Trigger now") fires at once, also while disabled. {@code enabled} arms the schedule; the {@code state} action
 * switches it as Home Assistant's switch does, and lets a lever arm a timer through the default wiring.
 * <p>
 * Every timer publishes {@code enabled}, {@code next_fire} (an ISO-8601 time with offset, or {@code ""}) and its
 * schedule: the interval and its unit, the moment of the day ({@code trigger_at}, never {@code trigger}, which is the
 * action) and its {@code time}, the alarm's {@code time}, the {@code cron} expression. An unloaded timer does not tick, so
 * it is unavailable and takes no command. Its v1 slots never published, so adopting a v1 timer makes no Raw Link from
 * them; a Raw Link set since publishes {@value #RAW_PAYLOAD} on each fire, never retained.
 */
public final class Timers {
    /** The attribute of {@code fired}: what fired it. */
    public static final String SOURCE = "source";
    /** The longest interval, in its unit; a larger v1 value is clamped when it is adopted. */
    public static final int MAX_INTERVAL = 1_000_000;
    /** The HH:MM Home Assistant's text entity checks a time against before it sends it (D2). */
    public static final String TIME_PATTERN = "^([01]\\d|2[0-3]):[0-5]\\d$";
    /** The time a new Daylight Timer and Alarm Clock fire at: sunrise. */
    public static final String DEFAULT_TIME = "06:00";
    /** The shortest time between two {@code fired} events on the action topic and the Raw Link (D3); the bus carries every one. */
    public static final Duration FIRED_MIN_INTERVAL = Duration.ofMillis(250);
    /** What the Raw Link publishes on each fire (D2): the rising edge of a pulse, as wireless redstone reads it. */
    public static final String RAW_PAYLOAD = "ON";

    /** What fired a timer: its schedule, or a press on {@code trigger}. */
    public enum Source implements StringRepresentable {
        SCHEDULE, MANUAL;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The unit of an Interval Timer's interval, with its length in ticks. */
    public enum Unit implements StringRepresentable {
        TICKS(1, "ticks"),
        SECONDS(20, "s"),
        MINUTES(1_200, "min"),
        HOURS(72_000, "h");

        private final int ticks;
        private final String symbol;

        Unit(int ticks, String symbol) {
            this.ticks = ticks;
            this.symbol = symbol;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** An interval of {@code value} of this unit in ticks. */
        public long ticks(long value) {
            return value * ticks;
        }

        /** How a status line names {@code value} of this unit: "1 tick", "20 ticks", "5 s". */
        public String describe(long value) {
            return this == TICKS && value == 1 ? "tick" : symbol;
        }

        /** v1's unit index (0 ticks, 1 seconds, 2 minutes, 3 hours); ticks for any other. */
        public static Unit ofV1(int index) {
            return index >= 0 && index < values().length ? values()[index] : TICKS;
        }
    }

    /** When a Daylight Timer fires: at one of the day's moments, or at its own {@code time}. */
    public enum TriggerAt implements StringRepresentable {
        SUNRISE(GameClock.Moment.SUNRISE),
        NOON(GameClock.Moment.NOON),
        SUNSET(GameClock.Moment.SUNSET),
        MIDNIGHT(GameClock.Moment.MIDNIGHT),
        CUSTOM(null);

        private final @Nullable GameClock.Moment moment;

        TriggerAt(@Nullable GameClock.Moment moment) {
            this.moment = moment;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The moment's time of day in ticks; empty for {@link #CUSTOM}, which fires at the timer's own time. */
        public Optional<Integer> ticks() {
            return Optional.ofNullable(moment).map(GameClock.Moment::ticks);
        }

        /** v1's trigger mode index, which listed the moments in this order; sunrise for any other, as v1 read it. */
        public static TriggerAt ofV1(int index) {
            return index >= 0 && index < values().length ? values()[index] : SUNRISE;
        }
    }

    // HH:MM only, so both Home Assistant's pattern and MineQTT refuse 25:00 or 6:00.
    private static final Codec<String> TIME_CODEC = Codec.STRING.comapFlatMap(text -> GameClock.isHhMm(text.strip()) ? DataResult.success(text.strip())
            : DataResult.error(() -> "Not a time as HH:MM: " + text), time -> time);
    // An expression that parses, or an empty one for no schedule (CronTimerSchedule.problem).
    private static final Codec<String> CRON_CODEC = Codec.STRING.comapFlatMap(text -> CronTimerSchedule.problem(text)
            .<DataResult<String>>map(problem -> DataResult.error(() -> problem)).orElseGet(() -> DataResult.success(text.strip())), cron -> cron);

    public static final Field<Boolean> ENABLED = Field.bool("enabled", true, Access.READ_WRITE);
    /** The time of the next fire, or {@code ""} when none is known (D2). */
    public static final Field<String> NEXT_FIRE = Field.text("next_fire", Access.READ);
    public static final Field<Integer> INTERVAL = Field.intRange("interval", 1, MAX_INTERVAL, 20, null, Access.READ_WRITE);
    public static final Field<Unit> UNIT = Field.enumOf("unit", Unit.class, Access.READ_WRITE);
    public static final Field<TriggerAt> TRIGGER_AT = Field.enumOf("trigger_at", TriggerAt.class, Access.READ_WRITE);
    /** The time of day as HH:MM: the Daylight Timer's when it fires at a custom time, the Alarm Clock's always. */
    public static final Field<String> TIME = new Field<>("time", TIME_CODEC, DEFAULT_TIME, Access.READ_WRITE, false, PortType.text(5));
    public static final Field<String> CRON = new Field<>("cron", CRON_CODEC, "", Access.READ_WRITE, false, PortType.text(CronTimerSchedule.MAX_LENGTH));
    /** "Trigger now": fires the timer at once; also its button's key (D2). */
    public static final Action TRIGGER = Action.press(DefaultWiring.TRIGGER);
    public static final EventSpec FIRED = EventSpec.of(DefaultWiring.FIRED)
            .attribute(SOURCE, PortType.enumOf(Arrays.stream(Source.values()).map(Source::getSerializedName).toList()))
            .minInterval(FIRED_MIN_INTERVAL);

    private static final Map<TimerKind, DeviceType> TYPES = new EnumMap<>(TimerKind.class);
    public static final DeviceType INTERVAL_TIMER = register(TimerKind.INTERVAL, "Interval Timer");
    public static final DeviceType DAYLIGHT_TIMER = register(TimerKind.GAME_TIME, "Daylight Timer");
    public static final DeviceType ALARM_CLOCK = register(TimerKind.ALARM, "Alarm Clock");
    public static final DeviceType CRON_TIMER = register(TimerKind.CRON, "Cron Timer");

    private Timers() {
    }

    /**
     * What a timer is set to, as its block entity keeps it in the {@code state} compound (D4, D17): only the keys of its
     * kind matter, the others keep their defaults. The time of day is kept in ticks, so a v1 time off a minute boundary
     * stays exact until it is edited; the state shows it as HH:MM.
     *
     * @param interval  the Interval Timer's interval in its unit, 1 to {@value #MAX_INTERVAL}
     * @param timeTicks the Daylight Timer's custom time or the Alarm Clock's time, 0 to 23999
     * @param cron      the Cron Timer's expression as given, also one that does not parse
     */
    public record Settings(boolean enabled, int interval, Unit unit, TriggerAt triggerAt, int timeTicks, String cron) {
        /** A new timer: enabled, every 20 ticks, at sunrise, at 06:00, without a cron schedule. */
        public static final Settings DEFAULT = new Settings(true, 20, Unit.TICKS, TriggerAt.SUNRISE, 0, "");
        /** The settings in the block entity's {@code state} compound; an unreadable value keeps its default. */
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.lenientOptionalFieldOf(ENABLED.key(), true).forGetter(Settings::enabled),
                Codec.INT.lenientOptionalFieldOf(INTERVAL.key(), 20).forGetter(Settings::interval),
                UNIT.codec().lenientOptionalFieldOf(UNIT.key(), Unit.TICKS).forGetter(Settings::unit),
                TRIGGER_AT.codec().lenientOptionalFieldOf(TRIGGER_AT.key(), TriggerAt.SUNRISE).forGetter(Settings::triggerAt),
                Codec.INT.lenientOptionalFieldOf("time_ticks", 0).forGetter(Settings::timeTicks),
                Codec.STRING.lenientOptionalFieldOf(CRON.key(), "").forGetter(Settings::cron)
        ).apply(instance, Settings::new));

        public Settings {
            interval = Math.clamp(interval, 1, MAX_INTERVAL);
            timeTicks = GameClock.timeOfDay(timeTicks);
            cron = cron == null ? "" : cron.strip();
        }

        /** The time of day as HH:MM, as the state shows it. */
        public String time() {
            return GameClock.format(timeTicks);
        }

        /** When a timer of {@code kind} with these settings fires. */
        public TimerSchedule schedule(TimerKind kind) {
            return switch (kind) {
                case INTERVAL -> new IntervalSchedule(unit.ticks(interval));
                case GAME_TIME -> new DayTimeSchedule(triggerAt.ticks().orElse(timeTicks));
                case ALARM -> new DayTimeSchedule(timeTicks);
                case CRON -> CronTimerSchedule.of(cron);
            };
        }

        public Settings withEnabled(boolean enabled) {
            return new Settings(enabled, interval, unit, triggerAt, timeTicks, cron);
        }

        public Settings withInterval(int interval, Unit unit) {
            return new Settings(enabled, interval, unit, triggerAt, timeTicks, cron);
        }

        public Settings withTriggerAt(TriggerAt triggerAt) {
            return new Settings(enabled, interval, unit, triggerAt, timeTicks, cron);
        }

        public Settings withTimeTicks(int timeTicks) {
            return new Settings(enabled, interval, unit, triggerAt, timeTicks, cron);
        }

        public Settings withCron(String cron) {
            return new Settings(enabled, interval, unit, triggerAt, timeTicks, cron);
        }

        /** Writes the state of a timer of {@code kind}: enabled, its next fire, its schedule; not the location. */
        public StateWriter write(TimerKind kind, String nextFire, StateWriter out) {
            out.put(ENABLED, enabled).put(NEXT_FIRE, nextFire);
            return switch (kind) {
                case INTERVAL -> out.put(INTERVAL, interval).put(UNIT, unit);
                case GAME_TIME -> out.put(TRIGGER_AT, triggerAt).put(TIME, time());
                case ALARM -> out.put(TIME, time());
                case CRON -> out.put(CRON, cron);
            };
        }
    }

    /** A timer's counters ({@link TimerState}) in the block entity's {@code timer} compound; an unreadable value keeps its default. */
    public static final Codec<TimerState> COUNTERS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.lenientOptionalFieldOf("tick_counter", TimerState.NEW.tickCounter()).forGetter(TimerState::tickCounter),
            Codec.LONG.lenientOptionalFieldOf("last_trigger_day", TimerState.NEW.lastTriggerDay()).forGetter(TimerState::lastTriggerDay),
            Codec.LONG.lenientOptionalFieldOf("last_trigger_tick", TimerState.NEW.lastTriggerTick()).forGetter(TimerState::lastTriggerTick),
            Codec.LONG.lenientOptionalFieldOf("last_trigger_time", TimerState.NEW.lastTriggerTime()).forGetter(TimerState::lastTriggerTime)
    ).apply(instance, TimerState::new));

    /** The device type of a kind of timer. */
    public static DeviceType type(TimerKind kind) {
        return TYPES.get(kind);
    }

    /** The four types, in the order of {@link TimerKind}. */
    public static List<DeviceType> all() {
        return List.copyOf(TYPES.values());
    }

    /** {@code {"event_type":"fired","source":"schedule"}}. */
    public static JsonObject fired(Source source) {
        JsonObject event = FIRED.payload();
        event.addProperty(SOURCE, source.getSerializedName());
        return event;
    }

    private static DeviceType register(TimerKind kind, String model) {
        DeviceType type = build(kind, model);
        TYPES.put(kind, type);
        return type;
    }

    private static DeviceType build(TimerKind kind, String model) {
        DeviceType.Builder builder = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, kind.id()))
                // An unloaded timer does not tick; a trigger queued for later would be a stale world edit.
                .model(model).unloaded(UnloadedPolicy.UNAVAILABLE).noV1Topic()
                .field(ENABLED).field(NEXT_FIRE);
        switch (kind) {
            case INTERVAL -> builder.field(INTERVAL).field(UNIT).setting(new Setting<>(INTERVAL)).setting(new Setting<>(UNIT));
            case GAME_TIME -> builder.field(TRIGGER_AT).field(TIME).setting(new Setting<>(TRIGGER_AT)).setting(new Setting<>(TIME));
            case ALARM -> builder.field(TIME).setting(new Setting<>(TIME));
            case CRON -> builder.field(CRON).setting(new Setting<>(CRON));
        }
        builder.field(CommonFields.LOCATION)
                // Home Assistant's switch sends {"state":"ON"}, which arms the timer: build() requires the action.
                .action(Action.state()).action(TRIGGER)
                .event(FIRED)
                .entity(Ha.switchOf("enabled", ENABLED).on(true).off(false).name(null))
                .entity(Ha.event("fired", FIRED.type()))
                .entity(Ha.button(TRIGGER.key()).name("Trigger now"))
                .entity(Ha.sensor("next_fire", NEXT_FIRE).deviceClass("timestamp")
                        .valueTemplate("{{ value_json.next_fire if value_json.next_fire else None }}"));
        switch (kind) {
            case INTERVAL -> builder.entity(Ha.number("interval", INTERVAL).range(1, MAX_INTERVAL).config())
                    .entity(Ha.select("unit", UNIT).config());
            case GAME_TIME -> builder.entity(Ha.select("trigger_at", TRIGGER_AT).config())
                    .entity(Ha.text("time", TIME).maxLength(5).pattern(TIME_PATTERN).config());
            case ALARM -> builder.entity(Ha.text("time", TIME).maxLength(5).pattern(TIME_PATTERN).config());
            case CRON -> builder.entity(Ha.text("cron", CRON).maxLength(CronTimerSchedule.MAX_LENGTH).config());
        }
        return builder.entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
                // One topic mechanism (M2 plan §3.8 #9): an output of events, a payload per fire, never retained.
                .rawLink(RawLinkSpec.events())
                .status(status(kind))
                .build();
    }

    /**
     * The Controls tab's status line of a kind (D5): "Disabled" for a disabled timer, else "Every 5 s, next in 3 min",
     * "Next 06:00, in 3 min" with the game time of the moment, or the cron timer's next fire in the server's zone; "No
     * schedule" and "Invalid cron" for an empty or broken expression, also while disabled.
     */
    private static StatusLine status(TimerKind kind) {
        return (state, now) -> {
            String nextFire = text(state, NEXT_FIRE);
            if (kind == TimerKind.CRON) {
                String cron = text(state, CRON);
                CronTimerSchedule schedule = CronTimerSchedule.of(cron);
                if (cron.isBlank() || !schedule.valid()) return TimerStatus.cron(cron, schedule.valid(), nextFire, now, ZoneId.systemDefault());
                if (!enabled(state)) return TimerStatus.DISABLED;
                return TimerStatus.cron(cron, true, nextFire, now, ZoneId.systemDefault());
            }
            if (!enabled(state)) return TimerStatus.DISABLED;
            return switch (kind) {
                case INTERVAL -> {
                    int interval = read(state, INTERVAL);
                    yield TimerStatus.every(interval, read(state, UNIT).describe(interval), nextFire, now);
                }
                case GAME_TIME -> TimerStatus.moment(read(state, TRIGGER_AT).ticks().map(GameClock::format).orElse(text(state, TIME)), nextFire, now);
                case ALARM -> TimerStatus.moment(text(state, TIME), nextFire, now);
                case CRON -> throw new IllegalStateException("handled above");
            };
        };
    }

    private static boolean enabled(JsonObject state) {
        return read(state, ENABLED);
    }

    // A text field as the state holds it, unparsed: a kept v1 cron expression that does not parse is still shown.
    private static String text(JsonObject state, Field<String> field) {
        JsonElement value = state.get(field.key());
        return value != null && value.isJsonPrimitive() ? value.getAsString() : field.defaultValue();
    }

    private static <T> T read(JsonObject state, Field<T> field) {
        JsonElement value = state.get(field.key());
        return value == null ? field.defaultValue() : field.parse(value).orElse(field.defaultValue());
    }
}

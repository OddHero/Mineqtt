package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.EventSpec;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.ha.HaEntity;
import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Alarm Panel ({@code alarm_panel}): a keypad on the wall and a Home Assistant {@code alarm_control_panel} that ties
 * other devices into one alarm system through wires. Sensors wired to {@code trigger_home} (the perimeter: a Smart Lock's
 * {@code attempt}, a door's Vibration Sensor) trigger it in both armed modes, sensors wired to {@code trigger_away} (the
 * inside) only while armed away; {@code triggered} wired to a Speaker's {@code state} sounds the siren.
 * <p>
 * <b>States</b> (Home Assistant's names): {@code disarmed}; {@code arming} for the exit delay, then {@code armed_home} or
 * {@code armed_away}; a trigger while armed makes it {@code pending} for the entry delay, then {@code triggered} for the
 * siren duration, after which it is armed again as before (0: triggered until disarmed). Only a disarmed panel arms, so
 * nobody without the code turns armed away into armed home and its inside sensors off.
 * <p>
 * <b>The code</b>: 4 to 8 digits, set on the keypad tab by players who may configure the panel while it is disarmed, and
 * kept only as a salted PBKDF2-HMAC-SHA256 hash ({@link CodeHash}) in the block; never in the state, the registry, a log
 * or a message. A panel without a code does not arm. Arming needs no code (the keypad, Home Assistant and wires arm it);
 * disarming needs it everywhere but on a wire, whose maker needed configure rights on the panel, as setting the code
 * does. Wrong codes are counted and limited per panel ({@link Guard}): after {@value #TRIES_BEFORE_LOCKOUT} in a row it
 * takes no code for 30 s, then 60 s, doubling up to 15 minutes; a right code ends that. Each wrong entry publishes an
 * anonymous {@code wrong_code} event: never the code, never who.
 */
public final class AlarmPanel {
    public static final int TICKS_PER_SECOND = 20;
    /** The longest exit or entry delay: ten minutes. */
    public static final int MAX_DELAY = 600;
    public static final int DEFAULT_EXIT_DELAY = 30;
    public static final int DEFAULT_ENTRY_DELAY = 30;
    /** The longest siren duration, an hour; 0 keeps it triggered until disarmed. */
    public static final int MAX_SIREN = 3600;
    public static final int DEFAULT_SIREN = 180;
    /** A code's length in digits. */
    public static final int MIN_CODE_LENGTH = 4;
    public static final int MAX_CODE_LENGTH = 8;
    /** Wrong codes in a row before the panel stops taking codes for a while. */
    public static final int TRIES_BEFORE_LOCKOUT = 3;
    public static final int FIRST_LOCKOUT_SECONDS = 30;
    public static final int MAX_LOCKOUT_SECONDS = 900;
    /** The largest wrong-code count kept; it stops there. */
    public static final int MAX_COUNT = 1_000_000_000;
    /** The shortest time between two published wrong-code events of one panel. */
    public static final Duration WRONG_CODE_INTERVAL = Duration.ofSeconds(1);
    /** The longest trigger source kept: a device model. */
    public static final int MAX_SOURCE_LENGTH = 32;

    /** The in-ports (actions) and the event types. */
    public static final String ARM_AWAY = "arm_away";
    public static final String ARM_HOME = "arm_home";
    public static final String DISARM = "disarm";
    public static final String TRIGGER_AWAY = "trigger_away";
    public static final String TRIGGER_HOME = "trigger_home";
    public static final String ARM_EVENT = "arm";
    public static final String DISARM_EVENT = "disarm";
    public static final String TRIGGER_EVENT = "trigger";
    public static final String WRONG_CODE_EVENT = "wrong_code";
    public static final String MODE_KEY = "mode";
    public static final String ZONE_KEY = "zone";
    public static final String SOURCE_KEY = "source";
    /** The secret command key: the code typed on the keypad or in Home Assistant. */
    public static final String CODE = HaEntity.AlarmControlPanel.CODE;
    /** Home Assistant's command key ({@link HaEntity.AlarmControlPanel#commandTemplate()}). */
    public static final String ACTION = HaEntity.AlarmControlPanel.ACTION;
    /** What a trigger from Home Assistant reports as its source. */
    public static final String SOURCE_HOME_ASSISTANT = "Home Assistant";
    /** What a wire's trigger reports when the device it came from is not known. */
    public static final String SOURCE_WIRE = "Wire";

    /** The panel's state; each name is a Home Assistant alarm state. */
    public enum AlarmState implements StringRepresentable {
        DISARMED, ARMING, ARMED_HOME, ARMED_AWAY, PENDING, TRIGGERED;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The armed mode a panel is in, arms to or returns to after it was triggered; NONE for none (disarmed). */
    public enum Mode implements StringRepresentable {
        NONE, HOME, AWAY;

        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The armed state of this mode; DISARMED for NONE. */
        public AlarmState armedState() {
            return switch (this) {
                case NONE -> AlarmState.DISARMED;
                case HOME -> AlarmState.ARMED_HOME;
                case AWAY -> AlarmState.ARMED_AWAY;
            };
        }
    }

    /** Which sensors triggered it: the perimeter ({@code trigger_home}), the inside ({@code trigger_away}), or a panic from Home Assistant. */
    public enum Zone implements StringRepresentable {
        HOME, AWAY, PANIC;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the block's light shows ({@code AlarmPanelBlock#STATUS}). */
    public enum Light implements StringRepresentable {
        DISARMED, ARMING, ARMED, PENDING, TRIGGERED;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Light of(AlarmState state) {
            return switch (state) {
                case DISARMED -> DISARMED;
                case ARMING -> ARMING;
                case ARMED_HOME, ARMED_AWAY -> ARMED;
                case PENDING -> PENDING;
                case TRIGGERED -> TRIGGERED;
            };
        }
    }

    public static final Field<AlarmState> STATE = Field.enumOf("state", AlarmState.class, AlarmState.DISARMED, Access.READ);
    /** Armed: armed home or away, pending, or triggered while armed. */
    public static final Field<Boolean> ARMED = Field.bool("armed", Access.READ);
    /** Triggered: the siren should sound. */
    public static final Field<Boolean> TRIGGERED = Field.bool("triggered", Access.READ);
    /** Whole seconds left of the exit delay, the entry delay or the siren; 0 without a countdown. */
    public static final Field<Integer> TIME_LEFT = Field.intRange("time_left", 0, MAX_SIREN, 0, "s", Access.READ);
    /** The model of the device that last triggered it ({@code Vibration Sensor}), {@code Home Assistant}; empty before. */
    public static final Field<String> TRIGGER_SOURCE = Field.text("trigger_source", Access.READ);
    /** Wrong codes entered since it was placed. */
    public static final Field<Integer> WRONG_CODES = Field.intRange("wrong_codes", 0, MAX_COUNT, 0, null, Access.READ);
    /** Whole seconds left in which it takes no code; 0 when it takes one. */
    public static final Field<Integer> LOCKOUT = Field.intRange("lockout", 0, MAX_LOCKOUT_SECONDS, 0, "s", Access.READ);
    /** Whether a code is set; it arms only then. Never the code. */
    public static final Field<Boolean> CODE_SET = Field.bool("code_set", Access.READ);
    public static final Field<Integer> EXIT_DELAY = Field.intRange("exit_delay", 0, MAX_DELAY, DEFAULT_EXIT_DELAY, "s", Access.READ_WRITE);
    public static final Field<Integer> ENTRY_DELAY = Field.intRange("entry_delay", 0, MAX_DELAY, DEFAULT_ENTRY_DELAY, "s", Access.READ_WRITE);
    public static final Field<Integer> SIREN_DURATION = Field.intRange("siren_duration", 0, MAX_SIREN, DEFAULT_SIREN, "s", Access.READ_WRITE);

    public static final Action ARM_AWAY_ACTION = Action.edge(ARM_AWAY);
    public static final Action ARM_HOME_ACTION = Action.edge(ARM_HOME);
    public static final Action DISARM_ACTION = Action.edge(DISARM);
    public static final Action TRIGGER_AWAY_ACTION = Action.edge(TRIGGER_AWAY);
    public static final Action TRIGGER_HOME_ACTION = Action.edge(TRIGGER_HOME);

    public static final EventSpec ARM = EventSpec.of(ARM_EVENT).attribute(MODE_KEY, PortType.enumOf(List.of("home", "away")));
    public static final EventSpec DISARMED_EVENT = EventSpec.of(DISARM_EVENT);
    public static final EventSpec TRIGGER = EventSpec.of(TRIGGER_EVENT)
            .attribute(ZONE_KEY, PortType.enumOf(Arrays.stream(Zone.values()).map(Zone::getSerializedName).toList()))
            .attribute(SOURCE_KEY, PortType.text(MAX_SOURCE_LENGTH));
    public static final EventSpec WRONG_CODE = EventSpec.of(WRONG_CODE_EVENT).minInterval(WRONG_CODE_INTERVAL);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "alarm_panel"))
            // Never queued: a queued command is saved in the registry, and Home Assistant's commands carry the code.
            .model("Alarm Panel").unloaded(UnloadedPolicy.UNAVAILABLE).noV1Topic()
            // Owner only until the owner says otherwise, as the Smart Lock: CLAIM lets everybody use it where no claim mod runs.
            .defaultAccess(AccessMode.PRIVATE)
            .field(STATE).field(ARMED).field(TRIGGERED).field(TIME_LEFT).field(TRIGGER_SOURCE).field(WRONG_CODES).field(LOCKOUT).field(CODE_SET)
            .field(EXIT_DELAY).field(ENTRY_DELAY).field(SIREN_DURATION).field(CommonFields.LOCATION)
            .setting(new Setting<>(EXIT_DELAY)).setting(new Setting<>(ENTRY_DELAY)).setting(new Setting<>(SIREN_DURATION))
            // Wires fire them; the keypad tab arms and disarms by hand, Home Assistant through its alarm panel.
            .action(ARM_AWAY_ACTION).action(ARM_HOME_ACTION).action(DISARM_ACTION).action(TRIGGER_AWAY_ACTION).action(TRIGGER_HOME_ACTION)
            .withoutControl(ARM_AWAY).withoutControl(ARM_HOME).withoutControl(DISARM).withoutControl(TRIGGER_AWAY).withoutControl(TRIGGER_HOME)
            .secret(CODE)
            .event(ARM).event(DISARMED_EVENT).event(TRIGGER).event(WRONG_CODE)
            .entity(Ha.alarmControlPanel("alarm", STATE).name(null))
            .entity(Ha.event("alarm_event", ARM_EVENT, DISARM_EVENT, TRIGGER_EVENT, WRONG_CODE_EVENT).name("Alarm"))
            .entity(Ha.binarySensor("triggered", TRIGGERED).name("Triggered").deviceClass("safety"))
            .entity(Ha.sensor("trigger_source", TRIGGER_SOURCE).name("Trigger source"))
            .entity(Ha.sensor("wrong_codes", WRONG_CODES).name("Wrong codes").stateClass("total_increasing"))
            .entity(Ha.sensor("time_left", TIME_LEFT).name("Time left").unit("s").deviceClass("duration"))
            .entity(Ha.number("exit_delay", EXIT_DELAY).name("Exit delay").range(0, MAX_DELAY).unit("s").config())
            .entity(Ha.number("entry_delay", ENTRY_DELAY).name("Entry delay").range(0, MAX_DELAY).unit("s").config())
            .entity(Ha.number("siren_duration", SIREN_DURATION).name("Siren duration").range(0, MAX_SIREN).unit("s").config())
            .entity(Ha.sensor("lockout", LOCKOUT).name("Lockout").unit("s").deviceClass("duration").diagnostic())
            .entity(Ha.binarySensor("code_set", CODE_SET).name("Code set").diagnostic())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            .minInterval(Duration.ofSeconds(1)).deadband(TIME_LEFT, 10)
            .status(AlarmPanel::statusLine)
            .build();

    private AlarmPanel() {
    }

    // ---- the code ----

    /** Why {@code code} cannot be a panel's code, or empty when it can: 4 to 8 digits 0-9. */
    public static Optional<String> codeProblem(String code) {
        if (code == null || code.length() < MIN_CODE_LENGTH || code.length() > MAX_CODE_LENGTH) {
            return Optional.of("a code has " + MIN_CODE_LENGTH + " to " + MAX_CODE_LENGTH + " digits");
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') return Optional.of("a code has digits only");
        }
        return Optional.empty();
    }

    /**
     * A code as only its hash keeps it: PBKDF2-HMAC-SHA256 over the digits with a random per-code salt of
     * {@value #SALT_BYTES} bytes and {@code iterations} rounds, {@value #HASH_BITS} bits. Matching hashes the entry the
     * same way and compares in constant time. The hash runs on the server thread, so the rounds are moderate; the
     * per-panel lockout is what stops guessing in game and from Home Assistant.
     */
    public record CodeHash(byte[] salt, byte[] hash, int iterations) {
        public static final int SALT_BYTES = 16;
        public static final int HASH_BITS = 256;
        /** The rounds a new code is hashed with; a stored hash keeps its own, so this may rise later. */
        public static final int ITERATIONS = 60_000;
        public static final int MIN_ITERATIONS = 10_000;
        public static final int MAX_ITERATIONS = 2_000_000;
        private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final Codec<byte[]> BASE64 = Codec.STRING.comapFlatMap(text -> {
            try {
                return DataResult.success(Base64.getDecoder().decode(text));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "not base64");
            }
        }, bytes -> Base64.getEncoder().encodeToString(bytes));
        public static final Codec<CodeHash> CODEC = RecordCodecBuilder.<CodeHash>create(instance -> instance.group(
                BASE64.fieldOf("salt").forGetter(CodeHash::salt),
                BASE64.fieldOf("hash").forGetter(CodeHash::hash),
                Codec.INT.fieldOf("iterations").forGetter(CodeHash::iterations)
        ).apply(instance, CodeHash::new)).validate(CodeHash::valid);

        public CodeHash {
            salt = salt.clone();
            hash = hash.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        /** The hash of a code with a fresh random salt. */
        public static CodeHash of(String code) {
            if (codeProblem(code).isPresent()) throw new IllegalArgumentException("not a code");
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            return new CodeHash(salt, derive(code, salt, ITERATIONS), ITERATIONS);
        }

        /** Whether {@code code} is the code; compares the hashes in constant time. An entry that is no code never matches. */
        public boolean matches(String code) {
            if (codeProblem(code).isPresent()) return false;
            return MessageDigest.isEqual(hash, derive(code, salt, iterations));
        }

        private static byte[] derive(String code, byte[] salt, int iterations) {
            char[] chars = code.toCharArray();
            PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, HASH_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Every Java has " + ALGORITHM, e);
            } finally {
                spec.clearPassword();
                Arrays.fill(chars, '\0');
            }
        }

        private static DataResult<CodeHash> valid(CodeHash code) {
            if (code.salt.length != SALT_BYTES || code.hash.length != HASH_BITS / 8) return DataResult.error(() -> "a code hash of the wrong size");
            if (code.iterations < MIN_ITERATIONS || code.iterations > MAX_ITERATIONS) return DataResult.error(() -> "a code hash of " + code.iterations + " rounds");
            return DataResult.success(code);
        }

        /** Never the hash or the salt: a log line or an exception names a code hash at most. */
        @Override
        public String toString() {
            return "CodeHash[" + iterations + " rounds]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CodeHash code && iterations == code.iterations && Arrays.equals(salt, code.salt) && Arrays.equals(hash, code.hash);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(hash) + iterations;
        }
    }

    /** What came of a code entry. */
    public enum Verdict {
        /** The code is right. */
        RIGHT,
        /** The code is wrong; counted. */
        WRONG,
        /** No code was given (Home Assistant sends {@code null} when nobody typed one); not counted. */
        MISSING,
        /** The panel takes no code now; not checked, not counted. */
        LOCKED_OUT,
        /** The panel has no code; nothing to check. */
        NO_CODE
    }

    /**
     * The per-panel limit on wrong codes, across the keypad and Home Assistant: after {@value #TRIES_BEFORE_LOCKOUT}
     * wrong codes in a row the panel takes none for {@link #lockoutSeconds} of the lockouts so far (30 s, 60 s, 120 s,
     * ... at most 15 minutes); a right code ends the streak and the doubling. Saved with the panel, so neither an unload
     * nor a restart ends a lockout; it counts down only while the panel is loaded.
     *
     * @param streak      wrong codes in a row since the last right one or lockout
     * @param lockouts    lockouts since the last right code
     * @param lockoutLeft ticks left in which it takes no code
     * @param wrongCodes  wrong codes since the panel was placed
     */
    public record Guard(int streak, int lockouts, int lockoutLeft, int wrongCodes) {
        public static final Guard NONE = new Guard(0, 0, 0, 0);
        public static final Codec<Guard> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.lenientOptionalFieldOf("streak", 0).forGetter(Guard::streak),
                Codec.INT.lenientOptionalFieldOf("lockouts", 0).forGetter(Guard::lockouts),
                Codec.INT.lenientOptionalFieldOf("lockout_left", 0).forGetter(Guard::lockoutLeft),
                Codec.INT.lenientOptionalFieldOf(WRONG_CODES.key(), 0).forGetter(Guard::wrongCodes)
        ).apply(instance, Guard::new));

        public Guard {
            streak = Math.clamp(streak, 0, TRIES_BEFORE_LOCKOUT);
            lockouts = Math.clamp(lockouts, 0, 64);
            lockoutLeft = Math.clamp(lockoutLeft, 0, MAX_LOCKOUT_SECONDS * TICKS_PER_SECOND);
            wrongCodes = Math.clamp(wrongCodes, 0, MAX_COUNT);
        }

        public boolean lockedOut() {
            return lockoutLeft > 0;
        }

        /** Whole seconds left of the lockout. */
        public int lockoutSeconds() {
            return (lockoutLeft + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
        }

        /** A wrong code: counted; the third in a row starts the next, longer lockout. */
        public Guard wrong() {
            int count = Math.min(wrongCodes + 1, MAX_COUNT);
            if (streak + 1 < TRIES_BEFORE_LOCKOUT) return new Guard(streak + 1, lockouts, lockoutLeft, count);
            int next = lockouts + 1;
            return new Guard(0, next, lockoutSeconds(next) * TICKS_PER_SECOND, count);
        }

        /** A right code: the streak and the doubling end. */
        public Guard right() {
            return new Guard(0, 0, 0, wrongCodes);
        }

        public Guard tick() {
            return lockoutLeft > 0 ? new Guard(streak, lockouts, lockoutLeft - 1, wrongCodes) : this;
        }

        /** A placed copy: nothing counted, no lockout. */
        public Guard placed() {
            return NONE;
        }

        /** The length of the {@code lockout}th lockout: 30 s, doubling, at most 15 minutes. */
        public static int lockoutSeconds(int lockout) {
            if (lockout <= 0) return 0;
            long seconds = (long) FIRST_LOCKOUT_SECONDS << Math.min(lockout - 1, 20);
            return (int) Math.min(seconds, MAX_LOCKOUT_SECONDS);
        }
    }

    /**
     * Checks a code entry against the panel's code under its guard: without a code there is nothing to check, a locked out
     * panel checks nothing, an entry without digits is missing, else the hash decides. Only {@link Verdict#WRONG} is
     * counted ({@link Guard#wrong()}) and only {@link Verdict#RIGHT} ends the streak; the caller applies both.
     */
    public static Verdict check(Optional<CodeHash> code, Guard guard, Optional<String> entry) {
        if (code.isEmpty()) return Verdict.NO_CODE;
        if (guard.lockedOut()) return Verdict.LOCKED_OUT;
        if (entry.isEmpty() || entry.get().isEmpty()) return Verdict.MISSING;
        return code.get().matches(entry.get()) ? Verdict.RIGHT : Verdict.WRONG;
    }

    /** The code a command carries: its {@code code} as text; empty for none or {@code null}. Never logged. */
    public static Optional<String> entry(CommandReader command) {
        JsonElement code = command.json().get(CODE);
        if (code == null || code.isJsonNull() || !code.isJsonPrimitive()) return Optional.empty();
        String text = code.getAsString().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    // ---- the state machine ----

    /** The delays: the exit delay before it is armed, the entry delay before a trigger sounds, and the siren's time (0: until disarmed). */
    public record Settings(int exitDelay, int entryDelay, int siren) {
        public static final Settings DEFAULT = new Settings(DEFAULT_EXIT_DELAY, DEFAULT_ENTRY_DELAY, DEFAULT_SIREN);
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EXIT_DELAY.codec().lenientOptionalFieldOf(EXIT_DELAY.key(), DEFAULT_EXIT_DELAY).forGetter(Settings::exitDelay),
                ENTRY_DELAY.codec().lenientOptionalFieldOf(ENTRY_DELAY.key(), DEFAULT_ENTRY_DELAY).forGetter(Settings::entryDelay),
                SIREN_DURATION.codec().lenientOptionalFieldOf(SIREN_DURATION.key(), DEFAULT_SIREN).forGetter(Settings::siren)
        ).apply(instance, Settings::new));

        public Settings {
            exitDelay = Math.clamp(exitDelay, 0, MAX_DELAY);
            entryDelay = Math.clamp(entryDelay, 0, MAX_DELAY);
            siren = Math.clamp(siren, 0, MAX_SIREN);
        }

        /** The settings a command sets ({@code exit_delay}, {@code entry_delay}, {@code siren_duration}, clamped), else these. */
        public Settings apply(CommandReader command) {
            return new Settings(command.get(EXIT_DELAY).orElse(exitDelay), command.get(ENTRY_DELAY).orElse(entryDelay),
                    command.get(SIREN_DURATION).orElse(siren));
        }
    }

    /**
     * Where the alarm stands: its state, the armed mode it is in, arms to or returns to after the siren ({@link Mode#NONE}
     * for disarmed), the ticks left of its countdown (exit delay, entry delay or siren; 0 for none, which in
     * {@code triggered} means until disarmed), and the model of the device that last triggered it and its zone (both kept
     * after a disarm).
     */
    public record Alarm(AlarmState state, Mode mode, int left, String source, Zone zone) {
        public static final Alarm DISARMED = new Alarm(AlarmState.DISARMED, Mode.NONE, 0, "", Zone.HOME);
        private static final Codec<Zone> ZONE_CODEC = StringRepresentable.fromEnum(Zone::values);
        public static final Codec<Alarm> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                STATE.codec().lenientOptionalFieldOf(STATE.key(), AlarmState.DISARMED).forGetter(Alarm::state),
                Mode.CODEC.lenientOptionalFieldOf(MODE_KEY, Mode.NONE).forGetter(Alarm::mode),
                Codec.INT.lenientOptionalFieldOf("left", 0).forGetter(Alarm::left),
                Codec.STRING.lenientOptionalFieldOf(TRIGGER_SOURCE.key(), "").forGetter(Alarm::source),
                ZONE_CODEC.lenientOptionalFieldOf(ZONE_KEY, Zone.HOME).forGetter(Alarm::zone)
        ).apply(instance, Alarm::new));

        public Alarm {
            left = Math.clamp(left, 0, MAX_SIREN * TICKS_PER_SECOND);
            source = cut(ValueCodecs.sanitizeText(source));
            // A saved state that cannot be: an armed state without its mode is taken as its own mode.
            if (state == AlarmState.ARMED_HOME) mode = Mode.HOME;
            if (state == AlarmState.ARMED_AWAY) mode = Mode.AWAY;
            if (state == AlarmState.DISARMED) mode = Mode.NONE;
            if ((state == AlarmState.ARMING || state == AlarmState.PENDING) && mode == Mode.NONE) mode = Mode.AWAY;
            if (state != AlarmState.ARMING && state != AlarmState.PENDING && state != AlarmState.TRIGGERED) left = 0;
        }

        /** Armed: in an armed state, pending, or triggered while armed (not a panic while disarmed). */
        public boolean armed() {
            return switch (state) {
                case ARMED_HOME, ARMED_AWAY, PENDING -> true;
                case TRIGGERED -> mode != Mode.NONE;
                case DISARMED, ARMING -> false;
            };
        }

        public boolean triggered() {
            return state == AlarmState.TRIGGERED;
        }

        /** Whole seconds left of the countdown, rounded up. */
        public int secondsLeft() {
            return (left + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
        }

        /** Arms a disarmed panel: arming for the exit delay, or armed at once without one. Empty unless disarmed. */
        public Optional<Alarm> arm(Mode to, Settings settings) {
            if (state != AlarmState.DISARMED || to == Mode.NONE) return Optional.empty();
            if (settings.exitDelay() == 0) return Optional.of(new Alarm(to.armedState(), to, 0, source, zone));
            return Optional.of(new Alarm(AlarmState.ARMING, to, settings.exitDelay() * TICKS_PER_SECOND, source, zone));
        }

        /** Disarmed, whatever it was; the last trigger source stays. */
        public Alarm disarm() {
            return new Alarm(AlarmState.DISARMED, Mode.NONE, 0, source, zone);
        }

        /**
         * A sensor of {@code zone} fired: an armed panel whose mode watches that zone (home: the perimeter in both modes;
         * away: the inside only while armed away) becomes pending for the entry delay, or triggered at once without one.
         * Anything else (disarmed, arming, already pending or triggered) stays: a second trigger does not restart the
         * delay. Empty when it changes nothing.
         */
        public Optional<Alarm> trigger(Zone zone, String from, Settings settings) {
            boolean watches = state == AlarmState.ARMED_AWAY || state == AlarmState.ARMED_HOME && zone == Zone.HOME;
            if (!watches) return Optional.empty();
            if (settings.entryDelay() == 0) return Optional.of(sound(mode, from, zone, settings));
            return Optional.of(new Alarm(AlarmState.PENDING, mode, settings.entryDelay() * TICKS_PER_SECOND, from, zone));
        }

        /**
         * A panic from Home Assistant: triggered at once, in any state but triggered, returning afterwards to the armed
         * mode it was in (disarmed when it was disarmed or still arming). Empty while triggered.
         */
        public Optional<Alarm> panic(String from, Settings settings) {
            if (state == AlarmState.TRIGGERED) return Optional.empty();
            Mode back = state == AlarmState.ARMED_HOME || state == AlarmState.ARMED_AWAY || state == AlarmState.PENDING ? mode : Mode.NONE;
            return Optional.of(sound(back, from, Zone.PANIC, settings));
        }

        private static Alarm sound(Mode back, String from, Zone zone, Settings settings) {
            return new Alarm(AlarmState.TRIGGERED, back, settings.siren() * TICKS_PER_SECOND, from, zone);
        }

        /**
         * One tick: the exit delay ends in the armed state, the entry delay in triggered (for the siren's time), the siren's
         * time in the armed mode it was in before (or disarmed after a panic while disarmed). Triggered without a time stays.
         */
        public Alarm tick(Settings settings) {
            if (left <= 0) return this;
            int next = left - 1;
            if (next > 0) return new Alarm(state, mode, next, source, zone);
            return switch (state) {
                case ARMING -> new Alarm(mode.armedState(), mode, 0, source, zone);
                case PENDING -> sound(mode, source, zone, settings);
                case TRIGGERED -> new Alarm(mode.armedState(), mode, 0, source, zone);
                default -> new Alarm(state, mode, 0, source, zone);
            };
        }

        /** A placed copy is disarmed and has triggered nothing. */
        public Alarm placed() {
            return DISARMED;
        }
    }

    /** What a command asks of the panel, read by {@link #request}. */
    public sealed interface Request {
    }

    /** Arm in a mode; needs no code. */
    public record Arm(Mode mode) implements Request {
    }

    /** Disarm; needs the code but on a wire. */
    public record Disarm() implements Request {
    }

    /** A sensor of a zone fired (wires only). */
    public record Trigger(Zone zone) implements Request {
    }

    /** Home Assistant's panic: triggered at once. */
    public record Panic() implements Request {
    }

    /** A command the panel does not take, and why. */
    public record Refused(String reason) implements Request {
    }

    /**
     * What a command asks, by where it came from: Home Assistant's {@code {"action":"ARM_AWAY|ARM_HOME|DISARM|TRIGGER",
     * "code":...}} from Home Assistant and from commands ({@code PLAYER}); the in-ports ({@code arm_away},
     * {@code arm_home}, {@code disarm}, {@code trigger_away}, {@code trigger_home}) fire on {@code true}, {@code ON},
     * {@code TOGGLE} or {@code PRESS} ({@link Action#fires}). The triggers come from wires only (Home Assistant panics
     * with TRIGGER); arm and disarm from wires, Home Assistant, the keypad and commands. Empty for a command that asks
     * none of them (settings only, or a {@code false} on an in-port).
     */
    public static Optional<Request> request(CommandReader command, CommandSource source) {
        boolean wire = source == CommandSource.BINDING;
        boolean person = source == CommandSource.HA || source == CommandSource.PLAYER;
        Optional<String> action = command.text(ACTION).map(text -> text.trim().toUpperCase(Locale.ROOT));
        if (action.isPresent()) {
            if (!person) return Optional.of(new Refused("the action command is Home Assistant's"));
            return Optional.of(switch (action.get()) {
                case HaEntity.AlarmControlPanel.ARM_AWAY -> new Arm(Mode.AWAY);
                case HaEntity.AlarmControlPanel.ARM_HOME -> new Arm(Mode.HOME);
                case HaEntity.AlarmControlPanel.DISARM -> new Disarm();
                case HaEntity.AlarmControlPanel.TRIGGER -> new Panic();
                default -> new Refused("not an alarm action: " + ValueCodecs.sanitizeText(cut(action.get())));
            });
        }
        if (fires(command, TRIGGER_AWAY) || fires(command, TRIGGER_HOME)) {
            if (!wire) return Optional.of(new Refused("its triggers are for wires; Home Assistant triggers it with TRIGGER"));
            return Optional.of(new Trigger(fires(command, TRIGGER_HOME) ? Zone.HOME : Zone.AWAY));
        }
        if (!wire && !person) {
            if (command.has(ARM_AWAY) || command.has(ARM_HOME) || command.has(DISARM)) return Optional.of(new Refused("not from " + source));
            return Optional.empty();
        }
        if (fires(command, DISARM)) return Optional.of(new Disarm());
        if (fires(command, ARM_AWAY)) return Optional.of(new Arm(Mode.AWAY));
        if (fires(command, ARM_HOME)) return Optional.of(new Arm(Mode.HOME));
        return Optional.empty();
    }

    private static boolean fires(CommandReader command, String key) {
        return command.has(key) && Action.fires(command.json().get(key));
    }

    /** Whether a command is one the panel knows at all: a request key, a setting, or a code alone. */
    public static boolean known(CommandReader command) {
        return command.has(ACTION) || command.has(ARM_AWAY) || command.has(ARM_HOME) || command.has(DISARM) || command.has(TRIGGER_AWAY)
                || command.has(TRIGGER_HOME) || command.has(EXIT_DELAY.key()) || command.has(ENTRY_DELAY.key()) || command.has(SIREN_DURATION.key());
    }

    // ---- events and state ----

    /** The arm event: the mode, never who. */
    public static JsonObject armEvent(Mode mode) {
        JsonObject event = ARM.payload();
        event.addProperty(MODE_KEY, mode.getSerializedName());
        return event;
    }

    /** The trigger event: the zone and the model of the device that fired it, never who. */
    public static JsonObject triggerEvent(Zone zone, String source) {
        JsonObject event = TRIGGER.payload();
        event.addProperty(ZONE_KEY, zone.getSerializedName());
        event.addProperty(SOURCE_KEY, cut(source));
        return event;
    }

    /** A trigger source as kept: at most {@value #MAX_SOURCE_LENGTH} code points. */
    public static String cut(String text) {
        if (text == null) return "";
        return text.codePointCount(0, text.length()) <= MAX_SOURCE_LENGTH ? text : text.substring(0, text.offsetByCodePoints(0, MAX_SOURCE_LENGTH));
    }

    /** Writes every readable field but the location: never the code, only whether one is set. */
    public static StateWriter write(StateWriter out, Alarm alarm, Settings settings, Guard guard, boolean codeSet) {
        return out.put(STATE, alarm.state()).put(ARMED, alarm.armed()).put(TRIGGERED, alarm.triggered()).put(TIME_LEFT, alarm.secondsLeft())
                .put(TRIGGER_SOURCE, alarm.source()).put(WRONG_CODES, guard.wrongCodes()).put(LOCKOUT, guard.lockoutSeconds()).put(CODE_SET, codeSet)
                .put(EXIT_DELAY, settings.exitDelay()).put(ENTRY_DELAY, settings.entryDelay()).put(SIREN_DURATION, settings.siren());
    }

    // The Controls tab's foot: what the panel does now.
    private static String statusLine(JsonObject state, Instant now) {
        String name = Optional.ofNullable(state.get(STATE.key())).map(JsonElement::getAsString).orElse("");
        int left = Optional.ofNullable(state.get(TIME_LEFT.key())).flatMap(Coerce::numeric).map(java.math.BigDecimal::intValue).orElse(0);
        int lockout = Optional.ofNullable(state.get(LOCKOUT.key())).flatMap(Coerce::numeric).map(java.math.BigDecimal::intValue).orElse(0);
        boolean codeSet = Optional.ofNullable(state.get(CODE_SET.key())).flatMap(Coerce::bool).orElse(false);
        String line = switch (name) {
            case "arming" -> "Arming, " + left + " s";
            case "armed_home" -> "Armed home";
            case "armed_away" -> "Armed away";
            case "pending" -> "Entry delay, " + left + " s";
            case "triggered" -> "Triggered" + (left > 0 ? ", " + left + " s" : "");
            default -> codeSet ? "Disarmed" : "Disarmed, no code set";
        };
        return lockout > 0 ? line + " (locked " + lockout + " s)" : line;
    }
}

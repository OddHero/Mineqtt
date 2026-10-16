package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.types.RedstoneOutput.Switch;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.security.TokenBucket;
import art.rehra.mineqtt.telemetry.core.BroadcastText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The Speaker ({@code speaker}): Home Assistant's voice in the world. Its siren plays one of a fixed list of tones
 * ({@link Tone}) from vanilla sounds on a repeating pattern at the block, until its duration ends or it is turned off;
 * its announcements show short plain text to the players near it, only while the server allows it
 * ({@code speaker.allowAnnouncements}).
 * <p>
 * Everything happens on the server: the server plays the sounds at the block (under the client's Jukebox/Note Blocks
 * volume), and every player within hearing hears them. A sound never emits a game event, so a Vibration Sensor does not
 * hear the Speaker it sets off. Limits: at most one sound every {@value #MIN_SOUND_GAP_TICKS} ticks whatever the
 * commands, at most {@value #MAX_DURATION} seconds a run, a range capped by {@code speaker.maxRange}, announcements cleaned
 * ({@link BroadcastText#clean(String, int)}), at most {@value #MAX_ANNOUNCEMENT} characters, and a burst of
 * {@value #ANNOUNCEMENT_BURST} then one every {@value #ANNOUNCEMENT_SECONDS} seconds per Speaker ({@link Announcer}).
 * Nothing it gets ever runs a command or becomes a chat component other than literal text.
 * <p>
 * A run is not saved: the Speaker stops when its chunk unloads and is off after a load.
 */
public final class Speaker {
    public static final int MAX_VOLUME = 100;
    public static final int DEFAULT_VOLUME = 100;
    public static final int MIN_DURATION = 1;
    /** Ten minutes: the longest a siren sounds, whatever a command asks. */
    public static final int MAX_DURATION = 600;
    public static final int DEFAULT_DURATION = 30;
    public static final int MIN_RANGE = 1;
    /** The farthest any Speaker carries; the server's {@code speaker.maxRange} may cap it lower. */
    public static final int MAX_RANGE = StaticDefaults.MAX_SPEAKER_MAX_RANGE;
    /** Vanilla's hearing distance of a sound at volume 1. */
    public static final int VANILLA_RANGE = 16;
    public static final int DEFAULT_RANGE = VANILLA_RANGE;
    /** The fewest ticks between two sounds of one Speaker: at most four a second, also when commands toggle it fast. */
    public static final int MIN_SOUND_GAP_TICKS = 5;
    /** The longest announcement shown, in characters (code points). */
    public static final int MAX_ANNOUNCEMENT = 120;
    public static final int ANNOUNCEMENT_BURST = 2;
    public static final int ANNOUNCEMENT_SECONDS = 2;
    /** Seconds {@code duration_left} may drift before its change is published on its own. */
    public static final int DURATION_LEFT_DEADBAND = 10;
    public static final int TICKS_PER_SECOND = 20;
    /** The action that announces, also the key of the Home Assistant notify entity. */
    public static final String ANNOUNCE = "announce";
    /** Home Assistant's siren level, 0 to 1, which a command may carry instead of {@code volume}. */
    public static final String VOLUME_LEVEL = "volume_level";

    /** What came of an announcement, as {@code last_result} says it. */
    public static final String ANNOUNCED = "announced";
    public static final String REFUSED_DISABLED = "refused: announcements are off on this server (speaker.allowAnnouncements)";
    public static final String REFUSED_EMPTY = "refused: empty announcement";
    public static final String REFUSED_RATE = "refused: rate limited";
    public static final String REFUSED_NOT_TEXT = "refused: an announcement is text";

    /** The vanilla sounds a tone is made of; the block entity maps them onto sound events. */
    public enum Instrument {
        /** The note block's bit (emerald block below). */
        BIT,
        /** The note block's chime (packed ice below). */
        CHIME,
        /** The note block's bell (gold block below). */
        BELL,
        /** The note block's pling (glowstone below). */
        PLING,
        /** The village bell, at its own pitch. */
        VILLAGE_BELL
    }

    /**
     * One sound of a tone's pattern.
     *
     * @param at   the tick within the pattern it plays at
     * @param note the note block note, 0 to 24 (12 is the sound's own pitch)
     */
    public record Note(int at, Instrument instrument, int note) {
        public Note {
            if (at < 0 || note < 0 || note > 24) throw new IllegalArgumentException("Invalid note " + at + " " + note);
        }

        /** The pitch the note plays at, as a note block's: 2^((note - 12) / 12), 0.5 to 2. */
        public float pitch() {
            return Speaker.pitch(note);
        }
    }

    /**
     * The tones: a fixed list, so Home Assistant's {@code available_tones} never changes. Each is a pattern of notes that
     * repeats every {@link #period()} ticks while the siren sounds.
     */
    public enum Tone implements StringRepresentable {
        /** A two-tone alarm, high and low, four sounds a second. */
        ALARM(10, new Note(0, Instrument.BIT, 18), new Note(5, Instrument.BIT, 13)),
        /** A rising three-note chime every two seconds. */
        CHIME(40, new Note(0, Instrument.CHIME, 12), new Note(5, Instrument.CHIME, 16), new Note(10, Instrument.CHIME, 19)),
        /** Ding-dong every two seconds. */
        DOORBELL(40, new Note(0, Instrument.BELL, 16), new Note(10, Instrument.BELL, 12)),
        /** One short beep a second. */
        BEEP(20, new Note(0, Instrument.PLING, 18)),
        /** The village bell every one and a half seconds. */
        BELL(30, new Note(0, Instrument.VILLAGE_BELL, 12));

        private final int period;
        private final List<Note> notes;

        Tone(int period, Note... notes) {
            this.period = period;
            this.notes = List.of(notes);
        }

        public int period() {
            return period;
        }

        public List<Note> notes() {
            return notes;
        }

        /** The notes due at a run's tick {@code runTick} (0 when it started). */
        public List<Note> notesAt(long runTick) {
            long at = Math.floorMod(runTick, (long) period);
            return notes.stream().filter(note -> note.at() == at).toList();
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether the siren sounds. */
    public static final Field<Switch> STATE = Field.onOff("state", Switch.class, Access.READ);
    /** The tone it plays; a command's tone becomes it. */
    public static final Field<Tone> TONE = Field.enumOf("tone", Tone.class, Tone.CHIME, Access.READ_WRITE);
    /** How loud, in percent of its range ({@link #soundVolume}); a command's volume becomes it. */
    public static final Field<Integer> VOLUME = Field.intRange("volume", 0, MAX_VOLUME, DEFAULT_VOLUME, "%", Access.READ_WRITE);
    /** Seconds the siren sounds when it is turned on without a duration. */
    public static final Field<Integer> DURATION = Field.intRange("duration", MIN_DURATION, MAX_DURATION, DEFAULT_DURATION, "s", Access.READ_WRITE);
    /** Seconds the siren still sounds; 0 while off. */
    public static final Field<Integer> DURATION_LEFT = Field.intRange("duration_left", 0, MAX_DURATION, 0, "s", Access.READ);
    /** How far it carries at full volume, sound and announcements, in blocks; {@code speaker.maxRange} caps it. */
    public static final Field<Integer> RANGE = Field.intRange("range", MIN_RANGE, MAX_RANGE, DEFAULT_RANGE, "blocks", Access.READ_WRITE);
    /** The last announcement shown, as players saw it; empty before the first. */
    public static final Field<String> LAST_ANNOUNCEMENT = Field.text("last_announcement", Access.READ);
    /** What came of the last announcement: {@value #ANNOUNCED} or a refusal with its reason. */
    public static final Field<String> LAST_RESULT = Field.text("last_result", Access.READ);
    /** The announcement: plain text, which the server cleans and caps. */
    public static final Action ANNOUNCE_ACTION = new Action(ANNOUNCE, PortType.text(MAX_ANNOUNCEMENT), false);

    private static final String LAST_ANNOUNCEMENT_TEMPLATE = "{{ value_json.last_announcement if value_json.last_announcement else None }}";

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "speaker"))
            .model("Speaker").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1)).noV1Topic()
            .deadband(DURATION_LEFT, DURATION_LEFT_DEADBAND)
            .field(STATE).field(TONE).field(VOLUME).field(DURATION).field(DURATION_LEFT).field(RANGE).field(LAST_ANNOUNCEMENT).field(LAST_RESULT)
            .field(CommonFields.LOCATION)
            .setting(new Setting<>(TONE)).setting(new Setting<>(VOLUME)).setting(new Setting<>(DURATION)).setting(new Setting<>(RANGE))
            // Home Assistant's siren sends {"state":"ON",...}: ON, OFF or TOGGLE; a wire's boolean follows, an event toggles.
            .action(Action.state()).action(ANNOUNCE_ACTION)
            // Announcements come from Home Assistant, wires and commands; the Controls tab keeps its rows for the siren.
            .withoutControl(ANNOUNCE)
            // A right-click turns the siren on or off.
            .use(Action.STATE)
            .entity(Ha.siren("siren", STATE, TONE, VOLUME).name(null))
            // Always discovered, so discovery never changes with the server's key; refused while it is off (last_result).
            .entity(Ha.notify(ANNOUNCE).name("Announcement"))
            .entity(Ha.select("tone", TONE).config())
            .entity(Ha.number("volume", VOLUME).range(0, MAX_VOLUME).unit("%").slider().config())
            .entity(Ha.number("duration", DURATION).range(MIN_DURATION, MAX_DURATION).unit("s").config())
            .entity(Ha.number("range", RANGE).range(MIN_RANGE, MAX_RANGE).unit("blocks").config())
            .entity(Ha.sensor("duration_left", DURATION_LEFT).name("Time left").deviceClass("duration").unit("s"))
            .entity(Ha.sensor("last_announcement", LAST_ANNOUNCEMENT).name("Last announcement").valueTemplate(LAST_ANNOUNCEMENT_TEMPLATE))
            .entity(Ha.sensor("last_result", LAST_RESULT).name("Last result").diagnostic())
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            .powerOn(Speaker::poweredOn)
            .build();

    private Speaker() {
    }

    /** A note block's pitch for {@code note} (0 to 24): 2^((note - 12) / 12). */
    public static float pitch(int note) {
        return (float) Math.pow(2, (Math.clamp(note, 0, 24) - 12) / 12.0);
    }

    /** The range a Speaker carries: its own, capped by the server's {@code speaker.maxRange}. */
    public static int effectiveRange(int range, int serverMax) {
        return Math.clamp(Math.min(range, serverMax), MIN_RANGE, MAX_RANGE);
    }

    /**
     * The volume the sound is played at: vanilla hears a sound of volume {@code v} within 16 × max(1, v) blocks, so full
     * volume at range {@code r} is r / 16, which a range of 48 makes 3. Volume 0 plays nothing.
     *
     * @param volume percent, 0 to 100
     * @param range  the effective range in blocks
     */
    public static float soundVolume(int volume, int range) {
        return Math.clamp(volume, 0, MAX_VOLUME) / (float) MAX_VOLUME * Math.clamp(range, MIN_RANGE, MAX_RANGE) / VANILLA_RANGE;
    }

    /** The ticks a run lasts: {@code seconds} as asked or the setting, clamped to 1 to {@value #MAX_DURATION} seconds. */
    public static int runTicks(Optional<Integer> seconds, int defaultSeconds) {
        return Math.clamp(seconds.orElse(defaultSeconds), MIN_DURATION, MAX_DURATION) * TICKS_PER_SECOND;
    }

    /** {@code duration_left} of a run with {@code ticksLeft} ticks: whole seconds, rounded up, so a sounding siren never says 0. */
    public static int durationLeft(int ticksLeft) {
        return Math.clamp((ticksLeft + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND, 0, MAX_DURATION);
    }

    /** Whether a sound may play {@code sinceLastSound} ticks after the last one ({@value #MIN_SOUND_GAP_TICKS} at least). */
    public static boolean soundDue(long sinceLastSound) {
        return sinceLastSound >= MIN_SOUND_GAP_TICKS;
    }

    /** Home Assistant's {@code volume_level} (0 to 1, a number or numeric text) as percent; empty for anything else. */
    public static Optional<Integer> volumePercent(JsonElement level) {
        return Coerce.numeric(level).map(value -> value.max(BigDecimal.ZERO).min(BigDecimal.ONE).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue());
    }

    /**
     * An announcement's text as it arrived: a JSON string, or a number or boolean as text, since a {@code /set/announce}
     * payload that looks like one is read as one. Empty for an object or an array, which is no announcement.
     */
    public static Optional<String> announcementText(JsonElement value) {
        return value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    /** What an announcement becomes: the text players see, or why nobody sees it. */
    public sealed interface Announcement {
    }

    /** The cleaned text to show. */
    public record Shown(String text) implements Announcement {
    }

    /** Why nothing is shown, as {@code last_result} says it. */
    public record Refused(String reason) implements Announcement {
    }

    /**
     * The gate of a Speaker's announcements: the server's key first, then the text cleaned as the bridge's broadcast
     * text and cut to {@value #MAX_ANNOUNCEMENT} characters, then the rate. A refusal by key or for empty text takes no
     * place in the rate. Server thread only; one per Speaker, not saved.
     */
    public static final class Announcer {
        private final TokenBucket bucket;

        /** @param millis a monotonic clock in milliseconds */
        public Announcer(LongSupplier millis) {
            this.bucket = new TokenBucket(ANNOUNCEMENT_BURST, 1.0 / ANNOUNCEMENT_SECONDS, () -> millis.getAsLong() * 1_000_000L);
        }

        /**
         * @param allowed {@code speaker.allowAnnouncements}
         */
        public Announcement announce(String raw, boolean allowed) {
            if (!allowed) return new Refused(REFUSED_DISABLED);
            String text = BroadcastText.clean(raw, MAX_ANNOUNCEMENT);
            if (text.isEmpty()) return new Refused(REFUSED_EMPTY);
            if (!bucket.tryTake()) return new Refused(REFUSED_RATE);
            return new Shown(text);
        }
    }

    /**
     * A Speaker's settings and what its announcements did; a run is kept beside it in the block entity and never saved.
     */
    public record Settings(Tone tone, int volume, int duration, int range, String lastAnnouncement, String lastResult) {
        public static final Settings DEFAULT = new Settings(Tone.CHIME, DEFAULT_VOLUME, DEFAULT_DURATION, DEFAULT_RANGE, "", "");
        /** The Speaker in the block entity's {@code state} compound; unreadable values keep their default. */
        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TONE.codec().lenientOptionalFieldOf(TONE.key(), Tone.CHIME).forGetter(Settings::tone),
                VOLUME.codec().lenientOptionalFieldOf(VOLUME.key(), DEFAULT_VOLUME).forGetter(Settings::volume),
                DURATION.codec().lenientOptionalFieldOf(DURATION.key(), DEFAULT_DURATION).forGetter(Settings::duration),
                RANGE.codec().lenientOptionalFieldOf(RANGE.key(), DEFAULT_RANGE).forGetter(Settings::range),
                Codec.STRING.lenientOptionalFieldOf(LAST_ANNOUNCEMENT.key(), "").forGetter(Settings::lastAnnouncement),
                Codec.STRING.lenientOptionalFieldOf(LAST_RESULT.key(), "").forGetter(Settings::lastResult)
        ).apply(instance, Settings::new));

        public Settings {
            volume = Math.clamp(volume, 0, MAX_VOLUME);
            duration = Math.clamp(duration, MIN_DURATION, MAX_DURATION);
            range = Math.clamp(range, MIN_RANGE, MAX_RANGE);
            lastAnnouncement = BroadcastText.clean(lastAnnouncement, MAX_ANNOUNCEMENT);
            lastResult = ValueCodecs.sanitizeText(lastResult);
        }

        /**
         * The settings after a command: {@code tone}, {@code volume} (or Home Assistant's {@code volume_level}, which
         * wins) and {@code range}; {@code duration} only when the command does not turn the siren on, as a siren's
         * duration is that run's length.
         *
         * @param turnsOn whether the command turns the siren on
         */
        public Settings apply(CommandReader command, boolean turnsOn) {
            Optional<Integer> level = Optional.ofNullable(command.json().get(VOLUME_LEVEL)).flatMap(Speaker::volumePercent);
            return new Settings(command.get(TONE).orElse(tone), level.or(() -> command.get(VOLUME)).orElse(volume),
                    turnsOn ? duration : command.get(DURATION).orElse(duration), command.get(RANGE).orElse(range), lastAnnouncement, lastResult);
        }

        /** The settings after an announcement: shown, it is the last announcement; refused, only the result changes. */
        public Settings announced(Announcement announcement) {
            return switch (announcement) {
                case Shown shown -> new Settings(tone, volume, duration, range, shown.text(), ANNOUNCED);
                case Refused refused -> new Settings(tone, volume, duration, range, lastAnnouncement, refused.reason());
            };
        }

        /** A placed copy (M2 plan D14): the settings stay, what it announced is gone. */
        public Settings placed() {
            return new Settings(tone, volume, duration, range, "", "");
        }

        /**
         * Writes every readable field but the location.
         *
         * @param ticksLeft the ticks the current run still sounds; 0 while off
         */
        public StateWriter write(StateWriter out, boolean on, int ticksLeft) {
            return out.put(STATE, on ? Switch.ON : Switch.OFF).put(TONE, tone).put(VOLUME, volume).put(DURATION, duration)
                    .put(DURATION_LEFT, on ? durationLeft(ticksLeft) : 0).put(RANGE, range).put(LAST_ANNOUNCEMENT, lastAnnouncement)
                    .put(LAST_RESULT, lastResult);
        }
    }

    /**
     * What a command's {@code state} asks: on, off, the opposite of {@code on} for {@code TOGGLE}; empty without a
     * readable state.
     */
    public static Optional<Boolean> switchTo(CommandReader command, boolean on) {
        return command.text(STATE.key()).map(String::trim)
                .flatMap(text -> text.equalsIgnoreCase(Coerce.TOGGLE) ? Optional.of(!on) : ValueCodecs.bool(text));
    }

    // An unloaded Speaker's state when its server starts: a run is never saved, so it is off.
    private static JsonObject poweredOn(JsonObject state) {
        if (!state.has(STATE.key())) return state;
        state.addProperty(STATE.key(), Switch.OFF.getSerializedName());
        state.addProperty(DURATION_LEFT.key(), 0);
        return state;
    }
}

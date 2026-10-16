package art.rehra.mineqtt.device;

import art.rehra.mineqtt.blocks.entities.SpeakerBlockEntity;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.types.Speaker;
import art.rehra.mineqtt.device.types.Speaker.Announcement;
import art.rehra.mineqtt.device.types.Speaker.Announcer;
import art.rehra.mineqtt.device.types.Speaker.Instrument;
import art.rehra.mineqtt.device.types.Speaker.Note;
import art.rehra.mineqtt.device.types.Speaker.Refused;
import art.rehra.mineqtt.device.types.Speaker.Settings;
import art.rehra.mineqtt.device.types.Speaker.Shown;
import art.rehra.mineqtt.device.types.Speaker.Tone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free regression checks of the Speaker, run by Gradle's verifySpeaker task: the fixed tones and their
 * patterns (never more than four sounds a second), the pitches, how volume and range become a vanilla sound's volume and
 * hearing distance under the server's cap, the duration's clamps and countdown, Home Assistant's volume level, the
 * announcement gate (the server's key, the cleaning, the 120-character cap, the rate), the settings a command changes,
 * the switch, the saved form and the state an unloaded Speaker starts with.
 */
public final class SpeakerCheck {
    private static final int TICKS_PER_SECOND = 20;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        tones();
        volumeAndRange();
        duration();
        announcements();
        commands();
        saved();
        System.out.println("Speaker checks passed.");
    }

    /**
     * The tones are a fixed list in Home Assistant's order; each pattern plays at least once, every note within its
     * period, and no two notes, the pattern's repeat included, closer than the sound gap: at most four sounds a second.
     */
    private static void tones() {
        check(Arrays.stream(Tone.values()).map(Tone::getSerializedName).toList().equals(List.of("alarm", "chime", "doorbell", "beep", "bell")),
                "The tones are alarm, chime, doorbell, beep and bell, in this order");
        check(Speaker.TONE.defaultValue() == Tone.CHIME, "A Speaker starts with the chime");
        for (Tone tone : Tone.values()) {
            List<Note> notes = tone.notes();
            check(!notes.isEmpty() && notes.stream().allMatch(note -> note.at() < tone.period()), tone + ": notes within the period");
            for (int i = 0; i < notes.size(); i++) {
                int next = i + 1 < notes.size() ? notes.get(i + 1).at() : notes.getFirst().at() + tone.period();
                check(next - notes.get(i).at() >= Speaker.MIN_SOUND_GAP_TICKS, tone + ": at most one sound every " + Speaker.MIN_SOUND_GAP_TICKS + " ticks");
            }
            int played = 0;
            for (long tick = 0; tick < TICKS_PER_SECOND * 10L; tick++) played += tone.notesAt(tick).size();
            check(played <= 10 * TICKS_PER_SECOND / Speaker.MIN_SOUND_GAP_TICKS && played >= 10 * TICKS_PER_SECOND / tone.period(),
                    tone + ": " + played + " sounds in ten seconds, at most four a second");
            check(tone.notesAt(0).equals(List.of(notes.getFirst())) && tone.notesAt(tone.period()).equals(tone.notesAt(0)),
                    tone + ": the first note plays when the siren starts, and the pattern repeats");
        }
        check(Tone.ALARM.notesAt(5).size() == 1 && Tone.ALARM.notesAt(5).getFirst().note() < Tone.ALARM.notesAt(0).getFirst().note(),
                "The alarm alternates high and low");
        check(Math.abs(Speaker.pitch(12) - 1) < 1e-6 && Math.abs(Speaker.pitch(0) - 0.5) < 1e-6 && Math.abs(Speaker.pitch(24) - 2) < 1e-6
                        && Math.abs(Speaker.pitch(99) - 2) < 1e-6, "Pitches as a note block's: 12 is 1, 0 is 0.5, 24 and beyond 2");
        for (Instrument instrument : Instrument.values()) {
            SoundEvent sound = SpeakerBlockEntity.soundEvent(instrument);
            check(sound != null && sound.fixedRange().isEmpty(), instrument + ": a vanilla sound whose range grows with its volume");
        }
        check(Speaker.soundDue(Speaker.MIN_SOUND_GAP_TICKS) && !Speaker.soundDue(Speaker.MIN_SOUND_GAP_TICKS - 1), "The sound gap");
    }

    /**
     * Full volume carries exactly the effective range (vanilla's 16 blocks at volume 1, 16 × volume above it), the
     * server's speaker.maxRange caps it, 48 blocks at most; a lower volume is quieter; volume 0 plays nothing.
     */
    private static void volumeAndRange() {
        SoundEvent bell = SpeakerBlockEntity.soundEvent(Instrument.BELL);
        for (int range : List.of(16, 24, 32, 48)) {
            float volume = Speaker.soundVolume(100, range);
            check(Math.abs(bell.getRange(volume) - range) < 1e-4, "At full volume a range of " + range + " is heard " + range + " blocks away: " + bell.getRange(volume));
        }
        check(Math.abs(Speaker.soundVolume(100, 48) - 3) < 1e-6 && Math.abs(Speaker.soundVolume(100, 16) - 1) < 1e-6
                        && Math.abs(Speaker.soundVolume(50, 16) - 0.5) < 1e-6 && Speaker.soundVolume(0, 48) == 0,
                "Volume scales the sound within its range; 48 blocks is volume 3, volume 0 is silent");
        check(Speaker.soundVolume(250, 99) == Speaker.soundVolume(100, Speaker.MAX_RANGE) && Speaker.soundVolume(-5, 16) == 0,
                "Out-of-range volumes and ranges are clamped");
        check(Speaker.effectiveRange(48, StaticDefaults.DEFAULT_SPEAKER_MAX_RANGE) == 32 && Speaker.effectiveRange(8, 32) == 8
                        && Speaker.effectiveRange(48, 48) == 48 && Speaker.effectiveRange(100, 100) == Speaker.MAX_RANGE && Speaker.effectiveRange(5, 0) == 1,
                "The server's cap (32 by default) wins over a Speaker's range, never beyond 48 blocks");
        check(StaticDefaults.MAX_SPEAKER_MAX_RANGE == Speaker.MAX_RANGE && Speaker.MAX_RANGE == 48 && Speaker.RANGE.defaultValue() == 16,
                "Ranges of 1 to 48 blocks, 16 by default, as vanilla hears a sound");
        check(Speaker.volumePercent(new JsonPrimitive(0.5)).equals(Optional.of(50)) && Speaker.volumePercent(new JsonPrimitive(1.7)).equals(Optional.of(100))
                        && Speaker.volumePercent(new JsonPrimitive(-1)).equals(Optional.of(0)) && Speaker.volumePercent(new JsonPrimitive("0.255")).equals(Optional.of(26))
                        && Speaker.volumePercent(new JsonPrimitive(true)).isEmpty() && Speaker.volumePercent(new JsonPrimitive("loud")).isEmpty(),
                "Home Assistant's volume level 0 to 1 is percent, clamped; anything else is no volume");
    }

    /** A run lasts the command's duration or the setting, 1 to 600 s; the countdown never reads 0 while it sounds. */
    private static void duration() {
        check(Speaker.runTicks(Optional.empty(), 30) == 30 * TICKS_PER_SECOND && Speaker.runTicks(Optional.of(5), 30) == 5 * TICKS_PER_SECOND,
                "A run is the command's duration, else the setting");
        check(Speaker.runTicks(Optional.of(0), 30) == TICKS_PER_SECOND && Speaker.runTicks(Optional.of(99_999), 30) == Speaker.MAX_DURATION * TICKS_PER_SECOND
                        && Speaker.MAX_DURATION == 600, "A run lasts 1 s to 10 min, whatever is asked");
        check(Speaker.durationLeft(600) == 30 && Speaker.durationLeft(599) == 30 && Speaker.durationLeft(1) == 1 && Speaker.durationLeft(0) == 0
                && Speaker.durationLeft(Integer.MAX_VALUE - 100) == Speaker.MAX_DURATION, "The seconds left round up and stay within 600");
        check(Speaker.TYPE.deadbands().get(Speaker.DURATION_LEFT.key()) == Speaker.DURATION_LEFT_DEADBAND,
                "The countdown is published every " + Speaker.DURATION_LEFT_DEADBAND + " s, not every second");
    }

    /**
     * The gate in its order: the server's key (off by default), then the text cleaned of formatting codes, controls,
     * bidirectional controls and runs of whitespace and cut to 120 characters, empty refused, then the rate: two at once,
     * then one every two seconds. A refusal by the key or for empty text takes no place in the rate. Text only.
     */
    private static void announcements() {
        check(!StaticDefaults.DEFAULT_SPEAKER_ALLOW_ANNOUNCEMENTS && !StaticDefaults.DEFAULT_SPEAKER_ANNOUNCE_IN_CHAT
                && StaticDefaults.DEFAULT_SPEAKER_MAX_RANGE == 32, "Announcements are off by default, in the action bar, within 32 blocks");
        AtomicLong now = new AtomicLong(1_000);
        Announcer announcer = new Announcer(now::get);
        check(announcer.announce("Washer done", false).equals(new Refused(Speaker.REFUSED_DISABLED)), "Off, nothing is shown");
        check(announcer.announce("  §c§lRed§r  alert\n\tnow\u0007 ‮evil‬ ", true).equals(new Shown("Red alert now evil")),
                "Formatting codes, controls and bidirectional controls go, whitespace collapses");
        check(announcer.announce("second", true).equals(new Shown("second")), "A second at once is shown");
        // The two announcements above took the burst; the clock has not moved.
        check(announcer.announce("third", true).equals(new Refused(Speaker.REFUSED_RATE)), "A third at once is rate limited");
        now.addAndGet(Speaker.ANNOUNCEMENT_SECONDS * 1000L);
        check(announcer.announce("§k   ", true).equals(new Refused(Speaker.REFUSED_EMPTY)), "Nothing left is refused as empty");
        check(announcer.announce("Dinner is ready", true).equals(new Shown("Dinner is ready")), "Two seconds later one more is shown; the empty one took no place");
        check(announcer.announce("again", true) instanceof Refused, "and then the rate holds again");

        Announcer fresh = new Announcer(now::get);
        for (int i = 0; i < 5; i++) check(fresh.announce("off", false) instanceof Refused, "Refused while off");
        check(fresh.announce("on", true) instanceof Shown && fresh.announce("on", true) instanceof Shown, "Refusals by the key take no place in the rate");

        Announcer longText = new Announcer(now::get);
        String text = "a".repeat(300);
        Announcement cut = longText.announce(text, true);
        check(cut instanceof Shown shown && shown.text().length() == Speaker.MAX_ANNOUNCEMENT && Speaker.MAX_ANNOUNCEMENT == 120,
                "An announcement is cut to 120 characters");
        Announcement emoji = longText.announce("🔊".repeat(200), true);
        check(emoji instanceof Shown shown && shown.text().codePointCount(0, shown.text().length()) == Speaker.MAX_ANNOUNCEMENT
                && Character.isSurrogatePair(shown.text().charAt(shown.text().length() - 2), shown.text().charAt(shown.text().length() - 1)),
                "The cut counts code points and never splits a surrogate pair");
        check(Speaker.announcementText(new JsonPrimitive(42)).equals(Optional.of("42")) && Speaker.announcementText(new JsonPrimitive(true)).equals(Optional.of("true"))
                        && Speaker.announcementText(JsonParser.parseString("{\"text\":\"x\"}")).isEmpty()
                        && Speaker.announcementText(JsonParser.parseString("[\"x\"]")).isEmpty(),
                "A /set/announce payload read as a number or a boolean is still text; an object or an array (a chat component) is none");
        check(Speaker.ANNOUNCE_ACTION.parse("{\"text\":\"x\",\"clickEvent\":{}}").map(json -> json.getAsString().startsWith("{")).orElse(false),
                "A player's or a command's text stays the literal text, never a component");
        // The JSON a component would need never reaches the screen as one: it is text like any other.
        Announcement component = new Announcer(now::get).announce("{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}", true);
        check(component instanceof Shown shown && shown.text().contains("run_command"), "A component's JSON is shown as its text, nothing runs");
    }

    /**
     * Home Assistant's siren command: the state and duration for the run, the tone and the volume level for the Speaker
     * (what HA shows); a duration without the state is the setting; TOGGLE flips; a wire's boolean switches.
     */
    private static void commands() {
        Settings settings = Settings.DEFAULT;
        CommandReader siren = new CommandReader(json("{\"state\":\"ON\",\"tone\":\"alarm\",\"duration\":5,\"volume_level\":0.5}"));
        check(Speaker.switchTo(siren, false).equals(Optional.of(true)), "ON turns it on");
        Settings after = settings.apply(siren, true);
        check(after.tone() == Tone.ALARM && after.volume() == 50 && after.duration() == Speaker.DEFAULT_DURATION && after.range() == Speaker.DEFAULT_RANGE,
                "A siren command sets the tone and the volume; its duration is the run's, the setting stays: " + after);
        check(Speaker.runTicks(siren.get(Speaker.DURATION), after.duration()) == 5 * TICKS_PER_SECOND, "and the run lasts its 5 s");
        CommandReader setting = new CommandReader(json("{\"duration\":90,\"range\":64,\"volume\":30,\"tone\":\"horn\"}"));
        Settings changed = settings.apply(setting, false);
        check(changed.duration() == 90 && changed.range() == Speaker.MAX_RANGE && changed.volume() == 30 && changed.tone() == Tone.CHIME,
                "Without turning on, duration is the setting; the range is clamped to 48, an unknown tone ignored: " + changed);
        check(settings.apply(new CommandReader(json("{\"volume\":80,\"volume_level\":0.2}")), false).volume() == 20, "Home Assistant's volume level wins");
        check(Speaker.switchTo(new CommandReader(json("{\"state\":\"TOGGLE\"}")), true).equals(Optional.of(false))
                        && Speaker.switchTo(new CommandReader(json("{\"state\":\"TOGGLE\"}")), false).equals(Optional.of(true))
                        && Speaker.switchTo(new CommandReader(json("{\"state\":true}")), false).equals(Optional.of(true))
                        && Speaker.switchTo(new CommandReader(json("{\"state\":\"OFF\"}")), true).equals(Optional.of(false))
                        && Speaker.switchTo(new CommandReader(json("{\"state\":\"loud\"}")), false).isEmpty()
                        && Speaker.switchTo(new CommandReader(json("{\"tone\":\"bell\"}")), false).isEmpty(),
                "ON, OFF, TOGGLE and a wire's boolean switch it; anything else does not");
        check(Speaker.TYPE.action(Speaker.ANNOUNCE).map(action -> action.parse("Washer done").equals(Optional.of(new JsonPrimitive("Washer done")))).orElse(false)
                        && Speaker.TYPE.action(Speaker.ANNOUNCE).map(action -> action.parse("x".repeat(121)).isEmpty()).orElse(false),
                "The announce action takes text of up to 120 characters from a player or a command");
    }

    /** The settings and what its announcements did are saved; a run is not, so an unloaded Speaker starts off. */
    private static void saved() {
        Settings settings = new Settings(Tone.DOORBELL, 40, 120, 24, "Washer done", Speaker.ANNOUNCED);
        Tag tag = Settings.CODEC.encodeStart(NbtOps.INSTANCE, settings).getOrThrow();
        check(Settings.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().equals(settings), "Saved and loaded unchanged");
        check(tag instanceof CompoundTag compound && !compound.contains("state") && !compound.contains("duration_left"), "A run is never saved");
        CompoundTag broken = new CompoundTag();
        broken.putString("tone", "siren");
        broken.putInt("volume", 400);
        broken.putInt("duration", 0);
        broken.putInt("range", 99);
        broken.putString("last_announcement", "§cred");
        Settings lenient = Settings.CODEC.parse(NbtOps.INSTANCE, broken).getOrThrow();
        check(lenient.tone() == Tone.CHIME && lenient.volume() == 100 && lenient.duration() == 1 && lenient.range() == 48 && lenient.lastAnnouncement().equals("red"),
                "Unreadable values take their defaults, numbers are clamped, saved text is cleaned again: " + lenient);
        check(settings.placed().equals(new Settings(Tone.DOORBELL, 40, 120, 24, "", "")), "A placed copy keeps its settings and has announced nothing");
        check(settings.announced(new Refused(Speaker.REFUSED_RATE)).lastAnnouncement().equals("Washer done")
                        && settings.announced(new Refused(Speaker.REFUSED_RATE)).lastResult().equals(Speaker.REFUSED_RATE)
                        && settings.announced(new Shown("Hi")).lastAnnouncement().equals("Hi"),
                "A refusal keeps the last announcement and says why");
        JsonObject on = settings.write(new StateWriter(), true, 61).json();
        check(on.get("state").getAsString().equals("ON") && on.get("duration_left").getAsInt() == 4 && on.get("tone").getAsString().equals("doorbell")
                && on.get("volume").getAsInt() == 40 && on.get("last_announcement").getAsString().equals("Washer done"), "The state while it sounds: " + on);
        check(settings.write(new StateWriter(), false, 61).json().get("duration_left").getAsInt() == 0, "Off, no time is left");
        JsonObject started = Speaker.TYPE.poweredOn(on);
        check(started.get("state").getAsString().equals("OFF") && started.get("duration_left").getAsInt() == 0 && on.get("state").getAsString().equals("ON"),
                "An unloaded Speaker starts off when its server starts, as a run is never resumed");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

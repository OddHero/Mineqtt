package art.rehra.mineqtt.telemetry.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The bridge's world telemetry (M4 plan D6), run by Gradle's verifyTelemetry task, Minecraft-free: the payload, the
 * clock's time of day in 15-minute steps, the day, {@code is_day} at its boundaries, the weather, tps and mspt (the tick
 * rate cap, a frozen server, rounding), the deadbands, the significant keys and their 2 s floor, the interval and its
 * clamp, the name policy (nobody named without consent, the admin veto, {@code telemetry.players=off}) and
 * {@code telemetry.enabled=false}; the player events (M4 plan D7): their shapes, anonymous join and leave, death and
 * advancement only with consent, the advancement filter, the death's message key, and the caps per player and bridge; the
 * broadcast text (M4 plan D8): the 1 KiB cap before decoding, strict UTF-8, formatting codes, control characters and bidi
 * controls removed, whitespace collapsed, the 256 character cut, JSON-looking text kept literal, and its rate.
 */
public final class TelemetryCheck {
    private static final long DAY = 24_000;

    public static void main(String[] args) {
        Locale.setDefault(Locale.GERMANY);
        payload();
        clock();
        weather();
        load();
        throttle();
        names();
        events();
        eventCaps();
        broadcastText();
        broadcastRate();
        System.out.println("Telemetry checks passed.");
    }

    // D7: the event shapes, anonymous join and leave, death and advancement only for a player who may be named.
    private static void events() {
        UUID steve = UUID.randomUUID();
        NamePolicy consenting = NamePolicy.of(steve::equals, true, true);
        check(PlayerEventMessage.join(steve, "Steve", NamePolicy.NOBODY).toJson().toString().equals("{\"event_type\":\"join\"}"),
                "A join without consent is anonymous: no player key");
        check(PlayerEventMessage.leave(steve, "Steve", NamePolicy.NOBODY).toJson().toString().equals("{\"event_type\":\"leave\"}"),
                "and so is a leave");
        check(PlayerEventMessage.join(steve, "Steve", consenting).toJson().toString().equals("{\"event_type\":\"join\",\"player\":\"Steve\"}"),
                "A consenting player's join names them");
        check(PlayerEventMessage.join(steve, "Steve", NamePolicy.of(steve::equals, false, true)).toJson().toString().equals("{\"event_type\":\"join\"}"),
                "publishPlayerNames=false keeps a consenting player's join anonymous");

        check(PlayerEventMessage.death(steve, "Steve", "fall", NamePolicy.NOBODY).isEmpty(), "No death without consent");
        check(PlayerEventMessage.death(steve, "Steve", "fall", NamePolicy.of(steve::equals, true, false)).isEmpty(), "nor with telemetry.players=off");
        JsonObject death = PlayerEventMessage.death(steve, "Steve", "mob", consenting).orElseThrow().toJson();
        check(death.toString().equals("{\"event_type\":\"death\",\"player\":\"Steve\",\"cause\":\"death.attack.mob\"}"),
                "A death carries the message key, never the rendered message that names the killer: " + death);

        check(PlayerEventMessage.announces(true, true, false), "An advancement with a display that announces it is announced");
        check(!PlayerEventMessage.announces(false, true, false), "A recipe unlock (no display) is not");
        check(!PlayerEventMessage.announces(true, false, false), "nor one that does not announce to chat");
        check(!PlayerEventMessage.announces(true, true, true), "nor a hidden one");
        check(PlayerEventMessage.advancement(steve, "Steve", "minecraft:story/mine_stone", true, true, false, NamePolicy.NOBODY).isEmpty(),
                "No advancement without consent");
        check(PlayerEventMessage.advancement(steve, "Steve", "minecraft:recipes/misc/stick", false, false, false, consenting).isEmpty(),
                "No recipe unlock, even with consent");
        check(PlayerEventMessage.advancement(steve, "Steve", "minecraft:story/hidden", true, true, true, consenting).isEmpty(),
                "No hidden advancement, even with consent");
        JsonObject advancement = PlayerEventMessage.advancement(steve, "Steve", "minecraft:story/mine_stone", true, true, false, consenting)
                .orElseThrow().toJson();
        check(advancement.toString().equals("{\"event_type\":\"advancement\",\"player\":\"Steve\",\"advancement\":\"minecraft:story/mine_stone\"}"),
                "An announced advancement of a consenting player: " + advancement);
        check(List.of(PlayerEventMessage.Type.values()).stream().map(PlayerEventMessage.Type::key).toList()
                .equals(List.of("join", "leave", "death", "advancement")), "The event types the entity declares");
    }

    // D7's caps: 1 per 5 s per player for join and leave, 1 per s for death and advancement, 10 per second for the bridge.
    private static void eventCaps() {
        long[] now = {1_000};
        EventLimiter limiter = new EventLimiter(() -> now[0]);
        UUID steve = UUID.randomUUID();
        NamePolicy consenting = NamePolicy.of(steve::equals, true, true);
        PlayerEventMessage join = PlayerEventMessage.join(steve, "Steve", NamePolicy.NOBODY);
        PlayerEventMessage leave = PlayerEventMessage.leave(steve, "Steve", NamePolicy.NOBODY);
        check(limiter.admit(join) == EventLimiter.Verdict.PUBLISH && limiter.admit(leave) == EventLimiter.Verdict.PUBLISH,
                "A join and the leave after it are both published");
        now[0] += 4_999;
        check(limiter.admit(join) == EventLimiter.Verdict.PLAYER_CAP, "A rejoin within 5 s is not");
        now[0] += 1;
        check(limiter.admit(join) == EventLimiter.Verdict.PUBLISH, "after 5 s it is");
        PlayerEventMessage death = PlayerEventMessage.death(steve, "Steve", "fall", consenting).orElseThrow();
        check(limiter.admit(death) == EventLimiter.Verdict.PUBLISH, "A death is published");
        now[0] += 999;
        check(limiter.admit(death) == EventLimiter.Verdict.PLAYER_CAP, "a second within a second is not");
        now[0] += 1;
        check(limiter.admit(death) == EventLimiter.Verdict.PUBLISH, "one a second later is");
        check(limiter.admit(PlayerEventMessage.join(UUID.randomUUID(), "Alex", NamePolicy.NOBODY)) == EventLimiter.Verdict.PUBLISH,
                "Another player is capped on their own");
        check(limiter.dropped() == 0, "Player caps are not counted as drops");

        now[0] += 10_000;
        int published = 0;
        for (int i = 0; i < 15; i++) {
            if (limiter.admit(PlayerEventMessage.join(UUID.randomUUID(), "P" + i, NamePolicy.NOBODY)) == EventLimiter.Verdict.PUBLISH) published++;
        }
        check(published == EventLimiter.BRIDGE_PER_SECOND && limiter.dropped() == 5, "The bridge publishes 10 at once and counts the 5 over its cap");
        now[0] += 100;
        check(limiter.admit(PlayerEventMessage.join(UUID.randomUUID(), "Q", NamePolicy.NOBODY)) == EventLimiter.Verdict.PUBLISH,
                "and one more each tenth of a second");
        check(limiter.admit(PlayerEventMessage.join(UUID.randomUUID(), "R", NamePolicy.NOBODY)) == EventLimiter.Verdict.BRIDGE_CAP
                && limiter.dropped() == 6, "but not two");

        // Many players over time: the remembered times are pruned, a rejoin after its interval still passes.
        EventLimiter many = new EventLimiter(() -> now[0]);
        for (int i = 0; i < 1_000; i++) {
            now[0] += 200;
            check(many.admit(PlayerEventMessage.join(UUID.randomUUID(), "M" + i, NamePolicy.NOBODY)) == EventLimiter.Verdict.PUBLISH,
                    "A join every 0.2 s of new players is always published");
        }
    }

    private static WorldReading reading(int players, List<String> names, float rate, boolean frozen, long nanos, long ticks, boolean rain, boolean thunder) {
        return new WorldReading(players, names, rate, frozen, nanos, ticks, rain, thunder);
    }

    private static WorldReading at(long ticks) {
        return reading(0, List.of(), 20, false, 0, ticks, false, false);
    }

    private static TelemetrySnapshot.Details details(WorldReading reading) {
        return TelemetrySnapshot.of(reading, true, 0, 0).details().orElseThrow();
    }

    // D6's example payload, key for key, and the earlier keys alone with telemetry.enabled off.
    private static void payload() {
        WorldReading reading = reading(2, List.of("Steve"), 20, false, 11_200_000, 312 * DAY + 1_250, true, false);
        JsonObject json = TelemetrySnapshot.of(reading, true, 0, 0).toJson();
        check(json.toString().equals("{\"players_online\":2,\"players\":[\"Steve\"],\"tps\":20.0,\"mspt\":11.2,\"time_of_day\":\"07:15\","
                + "\"day\":312,\"is_day\":true,\"weather\":\"rain\",\"dropped_inbound\":0,\"dropped_outbound\":0}"), "D6's payload: " + json);
        check(json.equals(JsonParser.parseString(json.toString())), "It is valid JSON");

        TelemetrySnapshot off = TelemetrySnapshot.of(reading, false, 3, 4);
        check(off.details().isEmpty() && off.toJson().keySet().equals(Set.of("players_online", "dropped_inbound", "dropped_outbound"))
                        && off.toJson().toString().equals("{\"players_online\":2,\"dropped_inbound\":3,\"dropped_outbound\":4}"),
                "telemetry.enabled=false keeps only the player count and the drop counters: " + off.toJson());

        TelemetrySnapshot empty = TelemetrySnapshot.of(reading(5, List.of(), 20, false, 0, 0, false, false), true, 0, 0);
        check(empty.toJson().has("players") && empty.toJson().getAsJsonArray("players").isEmpty() && empty.toJson().get("players_online").getAsInt() == 5,
                "players is always present, empty when nobody may be named, while the count stays");
        List<String> sorted = details(reading(3, List.of("steve", "Alex", "alex"), 20, false, 0, 0, false, false)).players();
        check(sorted.equals(List.of("Alex", "alex", "steve")), "Names are sorted ignoring case, so join order changes nothing: " + sorted);
    }

    // The overworld clock: 06:00 at tick 0, whole days, 15-minute steps, is_day by the clock alone.
    private static void clock() {
        check(details(at(0)).timeOfDay().equals("06:00") && details(at(0)).day() == 0, "Tick 0 is 06:00 on day 0");
        check(details(at(DAY - 1)).timeOfDay().equals("05:45") && details(at(DAY - 1)).day() == 0, "The last tick of a day is 05:45 (rounded down), day 0");
        check(details(at(DAY)).timeOfDay().equals("06:00") && details(at(DAY)).day() == 1, "A day later: 06:00 on day 1");
        check(details(at(312 * DAY + 1_250)).timeOfDay().equals("07:15"), "1250 ticks after sunrise are 07:15");
        check(details(at(249)).timeOfDay().equals("06:00") && details(at(250)).timeOfDay().equals("06:15"),
                "249 ticks (14.9 game minutes) still read 06:00, 250 read 06:15");
        check(details(at(6_000)).timeOfDay().equals("12:00") && details(at(18_000)).timeOfDay().equals("00:00"), "Noon and midnight");
        check(details(at(-1)).day() == -1 && details(at(-1)).timeOfDay().equals("05:45"), "A clock before tick 0 still reads a valid time");
        // 05:59 is tick 23983.3 (the first tick of that minute is 23984), 17:59 is tick 11983.3.
        check(!details(at(23_984)).isDay(), "05:59 is night");
        check(details(at(DAY)).isDay(), "06:00 is day");
        check(details(at(11_984)).isDay() && details(at(11_999)).isDay(), "17:59 is day");
        check(!details(at(12_000)).isDay(), "18:00 is night");
        check(details(at(3 * DAY + 11_999)).isDay() && !details(at(3 * DAY + 12_000)).isDay(), "on any day");
    }

    private static void weather() {
        check(Weather.of(false, false) == Weather.CLEAR && Weather.of(true, false) == Weather.RAIN && Weather.of(true, true) == Weather.THUNDER
                && Weather.of(false, true) == Weather.THUNDER, "Thunder wins over rain, rain over clear");
        check(List.of(Weather.values()).stream().map(Weather::key).toList().equals(List.of("clear", "rain", "thunder")),
                "The published values are the enum sensor's options");
        check(details(reading(0, List.of(), 20, false, 0, 0, true, true)).weather() == Weather.THUNDER, "A thunderstorm reads thunder");
    }

    // tps: the tick rate caps it, a frozen server reads 0, one decimal; mspt the average tick in ms.
    private static void load() {
        check(TelemetrySnapshot.tps(20, false, 5_000_000) == 20.0, "A server that keeps up runs at its rate, not faster");
        check(TelemetrySnapshot.tps(20, false, 100_000_000) == 10.0, "100 ms ticks give 10 tps");
        check(TelemetrySnapshot.tps(10, false, 50_000_000) == 10.0 && TelemetrySnapshot.tps(10, false, 5_000_000) == 10.0,
                "/tick rate 10 caps it at 10");
        check(TelemetrySnapshot.tps(60, false, 25_000_000) == 40.0, "/tick rate 60 with 25 ms ticks gives 40");
        check(TelemetrySnapshot.tps(20, true, 5_000_000) == 0.0, "/tick freeze reads 0");
        check(TelemetrySnapshot.tps(20, false, 0) == 20.0, "Before the first tick it reads the rate");
        check(TelemetrySnapshot.tps(20, false, 52_631_579) == 19.0 && TelemetrySnapshot.tps(20, false, 51_000_000) == 19.6, "One decimal");
        check(TelemetrySnapshot.mspt(11_249_999) == 11.2 && TelemetrySnapshot.mspt(11_250_000) == 11.3 && TelemetrySnapshot.mspt(0) == 0.0,
                "mspt has one decimal");
        TelemetrySnapshot.Details frozen = details(reading(0, List.of(), 20, true, 7_000_000, 0, false, false));
        check(frozen.tps() == 0.0 && frozen.mspt() == 7.0, "A frozen server keeps its mspt and reads 0 tps");
    }

    private static TelemetrySnapshot snapshot(int players, double tps, double mspt, long ticks, Weather weather) {
        return new TelemetrySnapshot(players, 0, 0, Optional.of(new TelemetrySnapshot.Details(List.of(), tps, mspt,
                details(at(ticks)).timeOfDay(), details(at(ticks)).day(), details(at(ticks)).isDay(), weather)));
    }

    private static void throttle() {
        check(TelemetryThrottle.intervalMillis(10) == 10_000 && TelemetryThrottle.intervalMillis(5) == 5_000
                && TelemetryThrottle.intervalMillis(300) == 300_000, "The interval in milliseconds");
        check(TelemetryThrottle.intervalMillis(4) == 5_000 && TelemetryThrottle.intervalMillis(0) == 5_000 && TelemetryThrottle.intervalMillis(-7) == 5_000
                && TelemetryThrottle.intervalMillis(301) == 300_000 && TelemetryThrottle.intervalMillis(Integer.MAX_VALUE) == 300_000,
                "The interval is clamped to 5-300 s");

        TelemetrySnapshot base = snapshot(2, 20.0, 5.0, 0, Weather.CLEAR);
        check(!TelemetryThrottle.changed(base, snapshot(2, 20.0, 5.0, 0, Weather.CLEAR)), "Unchanged is no change");
        check(!TelemetryThrottle.changed(base, snapshot(2, 19.6, 5.0, 0, Weather.CLEAR)), "0.4 tps is within the deadband");
        check(TelemetryThrottle.changed(base, snapshot(2, 19.5, 5.0, 0, Weather.CLEAR)), "0.5 tps is a change");
        check(TelemetryThrottle.changed(base, snapshot(2, 20.5, 5.0, 0, Weather.CLEAR)), "in both directions");
        check(!TelemetryThrottle.changed(base, snapshot(2, 20.0, 5.9, 0, Weather.CLEAR)), "0.9 ms is within the MSPT deadband");
        check(TelemetryThrottle.changed(base, snapshot(2, 20.0, 6.0, 0, Weather.CLEAR)), "1 ms is a change");
        check(!TelemetryThrottle.changed(base, snapshot(2, 20.0, 5.0, 249, Weather.CLEAR)), "Less than a time step is no change");
        check(TelemetryThrottle.changed(base, snapshot(2, 20.0, 5.0, 250, Weather.CLEAR))
                && !TelemetryThrottle.significant(base, snapshot(2, 20.0, 5.0, 250, Weather.CLEAR)), "A time step is a change, not a significant one");
        check(TelemetryThrottle.changed(base, snapshot(2, 20.0, 5.0, DAY, Weather.CLEAR)), "A new day (the same time) is a change");
        TelemetrySnapshot dropped = new TelemetrySnapshot(2, 1, 0, base.details());
        check(TelemetryThrottle.changed(base, dropped) && !TelemetryThrottle.significant(base, dropped), "A drop counter is a change, not a significant one");

        check(TelemetryThrottle.significant(base, snapshot(3, 20.0, 5.0, 0, Weather.CLEAR)), "players_online is significant");
        TelemetrySnapshot named = new TelemetrySnapshot(2, 0, 0, Optional.of(new TelemetrySnapshot.Details(List.of("Steve"), 20.0, 5.0, "06:00", 0, true, Weather.CLEAR)));
        check(TelemetryThrottle.significant(base, named), "players is significant");
        check(TelemetryThrottle.significant(base, snapshot(2, 20.0, 5.0, 12_000, Weather.CLEAR)), "is_day is significant");
        check(TelemetryThrottle.significant(base, snapshot(2, 20.0, 5.0, 0, Weather.RAIN)), "weather is significant");
        TelemetrySnapshot off = new TelemetrySnapshot(2, 0, 0, Optional.empty());
        check(TelemetryThrottle.significant(base, off) && TelemetryThrottle.significant(off, base), "telemetry.enabled toggled is significant");
        check(!TelemetryThrottle.changed(off, new TelemetrySnapshot(2, 0, 0, Optional.empty())), "Without the world keys only the count and counters change");

        // due: significant at the next sample after 2 s, anything else after the interval, nothing unchanged.
        TelemetrySnapshot joined = snapshot(3, 20.0, 5.0, 0, Weather.CLEAR);
        check(!TelemetryThrottle.due(base, 0, joined, 1_999, 10), "A significant change waits for the 2 s floor");
        check(TelemetryThrottle.due(base, 0, joined, 2_000, 10), "and goes at 2 s");
        TelemetrySnapshot slower = snapshot(2, 19.0, 5.0, 0, Weather.CLEAR);
        check(!TelemetryThrottle.due(base, 0, slower, 9_999, 10) && TelemetryThrottle.due(base, 0, slower, 10_000, 10), "Other changes wait for the interval");
        check(!TelemetryThrottle.due(base, 0, slower, 29_999, 30) && TelemetryThrottle.due(base, 0, slower, 30_000, 30), "the configured one");
        check(TelemetryThrottle.due(base, 0, slower, 5_000, 1), "clamped to 5 s");
        check(!TelemetryThrottle.due(base, 0, base, 1_000_000, 10), "Unchanged is never due");
        check(!TelemetryThrottle.due(base, 0, snapshot(2, 19.6, 5.4, 0, Weather.CLEAR), 1_000_000, 10), "Changes within the deadbands are never due");
    }

    // D9: nobody is named without consent; publishPlayerNames=false and telemetry.players=off veto everyone.
    private static void names() {
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        check(!NamePolicy.NOBODY.mayName(steve), "Without consent nobody is named");
        NamePolicy steveConsents = NamePolicy.of(steve::equals, true, true);
        check(steveConsents.mayName(steve) && !steveConsents.mayName(alex), "A consenting player is named, another is not");
        check(!NamePolicy.of(steve::equals, false, true).mayName(steve), "publishPlayerNames=false vetoes a consenting player");
        check(!NamePolicy.of(steve::equals, true, false).mayName(steve), "telemetry.players=off vetoes a consenting player");
        check(!NamePolicy.of(player -> false, true, true).mayName(steve), "The admin settings never name a player who does not consent");

        // The policy reads the consent live (the registry behind /mineqtt privacy share), so names and the death and
        // advancement events follow a choice at once and stop with share off.
        Set<UUID> consenting = new HashSet<>();
        NamePolicy live = NamePolicy.of(consenting::contains, true, true);
        check(!live.mayName(steve) && PlayerEventMessage.death(steve, "Steve", "fall", live).isEmpty(), "Before share on: no name, no death");
        consenting.add(steve);
        check(PlayerEventMessage.join(steve, "Steve", live).toJson().toString().equals("{\"event_type\":\"join\",\"player\":\"Steve\"}")
                        && PlayerEventMessage.death(steve, "Steve", "fall", live).isPresent()
                        && PlayerEventMessage.advancement(steve, "Steve", "minecraft:story/mine_stone", true, true, false, live).isPresent(),
                "After share on: named on joins, deaths and announced advancements");
        consenting.remove(steve);
        check(PlayerEventMessage.leave(steve, "Steve", live).toJson().toString().equals("{\"event_type\":\"leave\"}")
                        && PlayerEventMessage.advancement(steve, "Steve", "minecraft:story/mine_stone", true, true, false, live).isEmpty(),
                "After share off: anonymous again, no advancement");
    }

    // D8: what a broadcast request shows.
    private static void broadcastText() {
        check(shown("\u00a74Hi\u202e").equals("Hi"), "A formatting code and a bidi override are removed");
        check(shown("\u00a7lBold \u00a7r\u00a7kx").equals("Bold x"), "Every code goes with the character after it, also at the end");
        check(shown("Trailing \u00a7").equals("Trailing"), "A section sign at the end goes alone");
        check(shown("a\u202ab\u202bc\u202cd\u202de\u2066f\u2067g\u2068h\u2069i\u200ej\u200fk\u061cl").equals("abcdefghijkl"),
                "Embeddings, overrides, isolates and the bidi marks are removed");
        check(shown("bell\u0007 nul\u0000 del\u007f").equals("bell nul del"), "Control characters are removed");
        check(shown("  two\t\tlines\nhere\u00a0and\u2028there  ").equals("two lines here and there"),
                "Runs of whitespace become one space, none at either end");
        String json = "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/stop\"}}";
        check(shown(json).equals(json), "JSON-looking text stays the literal text it is");
        check(shown("/op Steve").equals("/op Steve"), "and so does a command: it is shown, never run");

        check(shown("x".repeat(300)).equals("x".repeat(BroadcastText.MAX_LENGTH)), "A longer text is cut to 256 characters");
        String cut = shown("x".repeat(57) + "\ud83d\ude00".repeat(200));
        check(cut.codePointCount(0, cut.length()) == BroadcastText.MAX_LENGTH && !Character.isHighSurrogate(cut.charAt(cut.length() - 1)),
                "The cut counts characters, not UTF-16 units, and never splits one");
        check(shown("x".repeat(255) + " y").equals("x".repeat(255)), "A space that only the cut would follow is left out");

        check(BroadcastText.parse("\u00e9".repeat(512).getBytes(StandardCharsets.UTF_8)) instanceof BroadcastText.Accepted, "1024 bytes are taken");
        check(BroadcastText.parse("a".repeat(1025).getBytes(StandardCharsets.UTF_8)).equals(new BroadcastText.Refused("message over 1024 bytes")),
                "1025 bytes are refused before decoding");
        check(BroadcastText.parse(new byte[]{'H', (byte) 0xc3, 'i'}).equals(new BroadcastText.Refused("message is not UTF-8")),
                "A message that is not UTF-8 is refused");
        check(BroadcastText.parse("\u00a7\u00a7 \u202e\t".getBytes(StandardCharsets.UTF_8)).equals(new BroadcastText.Refused("empty message")),
                "Nothing left after cleaning shows nothing");
    }

    // D8: a burst of 3, then 1 per second.
    private static void broadcastRate() {
        long[] now = {5_000};
        BroadcastText.Limiter limiter = new BroadcastText.Limiter(() -> now[0]);
        int admitted = 0;
        for (int i = 0; i < 5; i++) if (limiter.admit()) admitted++;
        check(admitted == BroadcastText.BURST, "Five at once admit the burst of 3, got " + admitted);
        now[0] += 500;
        check(!limiter.admit(), "Half a second later none");
        now[0] += 500;
        check(limiter.admit() && !limiter.admit(), "A second later one");
        now[0] += 60_000;
        admitted = 0;
        for (int i = 0; i < 5; i++) if (limiter.admit()) admitted++;
        check(admitted == BroadcastText.BURST, "A quiet minute fills the burst again, no more");
    }

    private static String shown(String text) {
        BroadcastText.Result result = BroadcastText.parse(text.getBytes(StandardCharsets.UTF_8));
        check(result instanceof BroadcastText.Accepted, "Expected the text to be shown: " + text + " gave " + result);
        return ((BroadcastText.Accepted) result).text();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

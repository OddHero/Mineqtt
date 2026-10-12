package art.rehra.mineqtt.telemetry.core;

import art.rehra.mineqtt.util.GameClock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The bridge's telemetry (M4 plan D6), published retained on {@code <base>/<bridge>/bridge}:
 * <pre>{"players_online":2,"players":["Steve"],"tps":20.0,"mspt":11.2,"time_of_day":"07:15","day":312,"is_day":true,
 * "weather":"rain","dropped_inbound":0,"dropped_outbound":0}</pre>
 * With {@code telemetry.enabled=false} only the keys the bridge had before remain: {@code players_online} and the two
 * drop counters ({@link #details()} empty).
 *
 * @param droppedOutbound the MQTT service's dropped outbound messages plus the bridge's own dropped events
 */
public record TelemetrySnapshot(int playersOnline, long droppedInbound, long droppedOutbound, Optional<Details> details) {
    /** Steps of {@code time_of_day}, in game minutes: a new value about every 12.5 s of real time. */
    public static final int TIME_STEP_MINUTES = 15;

    /**
     * The world part.
     *
     * @param players   the names the name policy allows, sorted ignoring case; present and possibly empty
     * @param tps       ticks per second, one decimal: 0 while frozen, at most the tick rate
     * @param mspt      vanilla's average milliseconds per tick, one decimal
     * @param timeOfDay {@code HH:MM}, 06:00 at tick 0, rounded down to {@value #TIME_STEP_MINUTES} game minutes
     * @param day       whole overworld days
     * @param isDay     06:00 up to 18:00 by the clock alone, so rain does not make it night
     */
    public record Details(List<String> players, double tps, double mspt, String timeOfDay, long day, boolean isDay, Weather weather) {
        public Details {
            players = List.copyOf(players);
        }
    }

    /**
     * The snapshot of one reading.
     *
     * @param worldKeys {@code telemetry.enabled}: false keeps only the player count and the drop counters
     */
    public static TelemetrySnapshot of(WorldReading reading, boolean worldKeys, long droppedInbound, long droppedOutbound) {
        if (!worldKeys) return new TelemetrySnapshot(reading.playersOnline(), droppedInbound, droppedOutbound, Optional.empty());
        List<String> players = new ArrayList<>(reading.players());
        players.sort(Comparator.comparing((String name) -> name.toLowerCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder()));
        long ticks = reading.overworldTicks();
        Details details = new Details(players, tps(reading.tickRate(), reading.frozen(), reading.averageTickNanos()),
                mspt(reading.averageTickNanos()), GameClock.formatRoundedDown(ticks, TIME_STEP_MINUTES), GameClock.day(ticks),
                GameClock.isDay(ticks), Weather.of(reading.raining(), reading.thundering()));
        return new TelemetrySnapshot(reading.playersOnline(), droppedInbound, droppedOutbound, Optional.of(details));
    }

    /**
     * Ticks per second, one decimal: 0 while the tick manager is frozen, else what the average tick time allows, but
     * never more than the tick rate (a server that keeps up runs at its rate, not faster).
     */
    public static double tps(float tickRate, boolean frozen, long averageTickNanos) {
        if (frozen) return 0;
        double rate = averageTickNanos > 0 ? Math.min(tickRate, 1_000_000_000.0 / averageTickNanos) : tickRate;
        return oneDecimal(rate);
    }

    /** Milliseconds per tick, one decimal. */
    public static double mspt(long averageTickNanos) {
        return oneDecimal(Math.max(0, averageTickNanos) / 1_000_000.0);
    }

    private static double oneDecimal(double value) {
        return Math.round(value * 10) / 10.0;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("players_online", playersOnline);
        details.ifPresent(d -> {
            JsonArray names = new JsonArray();
            d.players().forEach(names::add);
            json.add("players", names);
            json.addProperty("tps", d.tps());
            json.addProperty("mspt", d.mspt());
            json.addProperty("time_of_day", d.timeOfDay());
            json.addProperty("day", d.day());
            json.addProperty("is_day", d.isDay());
            json.addProperty("weather", d.weather().key());
        });
        json.addProperty("dropped_inbound", droppedInbound);
        json.addProperty("dropped_outbound", droppedOutbound);
        return json;
    }
}

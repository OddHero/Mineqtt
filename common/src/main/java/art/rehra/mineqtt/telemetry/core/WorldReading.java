package art.rehra.mineqtt.telemetry.core;

import java.util.List;

/**
 * What the server reads off the world for one telemetry sample (M4 plan D6), as plain values: the bridge turns it into a
 * {@link TelemetrySnapshot}. Taken on the server thread.
 *
 * @param playersOnline    the player count, which is not personal and always published
 * @param players          the names the name policy allows ({@link NamePolicy}), in any order; empty without consent
 * @param tickRate         the target ticks per second ({@code /tick rate})
 * @param frozen           whether the tick manager is frozen ({@code /tick freeze})
 * @param averageTickNanos vanilla's rolling average tick time, 0 before the first tick
 * @param overworldTicks   the overworld clock's total ticks ({@code runtime.WorldClock})
 * @param raining          whether it rains in the overworld
 * @param thundering       whether it thunders in the overworld
 */
public record WorldReading(int playersOnline, List<String> players, float tickRate, boolean frozen, long averageTickNanos,
                           long overworldTicks, boolean raining, boolean thundering) {
    public WorldReading {
        players = List.copyOf(players);
    }
}

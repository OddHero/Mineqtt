package art.rehra.mineqtt.device.timer.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * What a timer reads at one tick: the game time (its pulses), the overworld clock (the Daylight Timer and the Alarm
 * Clock), the wall clock in the server's zone (the cron timer and every next fire), the server's tick rate, which turns
 * ticks into the wall-clock time of a next fire, and whether the overworld clock runs at all, and how fast.
 *
 * @param gameTime     the level's game time, which only moves forward
 * @param clockTicks   the overworld clock's total ticks ({@code runtime.WorldClock}), which {@code /time set} moves
 * @param wallClock    the real time now
 * @param zone         the server's zone, in which a cron expression is read and a next fire is written
 * @param tickRate     the ticks per second the server aims for ({@code /tick rate})
 * @param frozen       whether the server's ticks are frozen ({@code /tick freeze}), so no tick-based fire comes
 * @param clockRunning whether the overworld clock advances ({@link ClockWatch}): with {@code gamerule advance_time false}
 *                     or {@code /time pause} it stands, so no moment of the day comes
 * @param clockRate    the overworld clock's ticks per server tick ({@code /time rate}, positive), which turns the clock
 *                     ticks until a moment of the day into server ticks
 */
public record TimerNow(long gameTime, long clockTicks, Instant wallClock, ZoneId zone, float tickRate, boolean frozen, boolean clockRunning,
                       float clockRate) {
    /** The ticks of a second as the cron grammar counts them: 0 to 19 of the game time. */
    public static final int TICKS_PER_SECOND = 20;

    public TimerNow {
        if (!(clockRate > 0) || !Float.isFinite(clockRate)) throw new IllegalArgumentException("Not a clock rate: " + clockRate);
    }

    /** A reading with the overworld clock at its normal rate. */
    public TimerNow(long gameTime, long clockTicks, Instant wallClock, ZoneId zone, float tickRate, boolean frozen, boolean clockRunning) {
        this(gameTime, clockTicks, wallClock, zone, tickRate, frozen, clockRunning, 1);
    }

    /** A reading with the overworld clock running at its normal rate unless the ticks are frozen. */
    public TimerNow(long gameTime, long clockTicks, Instant wallClock, ZoneId zone, float tickRate, boolean frozen) {
        this(gameTime, clockTicks, wallClock, zone, tickRate, frozen, !frozen, 1);
    }

    /** The server ticks the overworld clock takes for this many of its own ticks at its rate. */
    public long serverTicksFor(long clockTicks) {
        return clockRate == 1 ? clockTicks : (long) Math.ceil(clockTicks / (double) clockRate);
    }

    /** The wall clock in the server's zone. */
    public ZonedDateTime local() {
        return wallClock.atZone(zone);
    }

    /** The tick within the second, 0 to 19, as the v1 cron timer took it from the game time. */
    public int tickInSecond() {
        return (int) Math.floorMod(gameTime, (long) TICKS_PER_SECOND);
    }
}

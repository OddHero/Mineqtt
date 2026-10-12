package art.rehra.mineqtt.device.timer.core;

/**
 * Whether the overworld clock runs, and how fast, as the server reports it once per tick (M4 plan D2). The clock runs
 * while {@code gamerule advance_time} is true, the clock is not paused ({@code /time pause}) and the server's ticks are
 * not frozen ({@code /tick freeze}); its rate ({@code /time rate}) is the clock ticks it advances per server tick, so a
 * slowed clock (a rate below 1, which leaves the clock standing on some ticks) still runs. A stopped clock brings no
 * Daylight Timer or Alarm Clock fire, so they publish no next fire, and their 60 s resync stops moving one forward.
 * Pure; the runtime feeds it at the start of each real tick (never from the pump of a paused server, which runs no
 * tick).
 */
public final class ClockWatch {
    private boolean running = true;
    private float rate = 1;

    /**
     * Whether a clock in this state runs.
     *
     * @param advanceTime {@code gamerule advance_time}
     * @param paused      whether the clock is paused ({@code /time pause})
     * @param frozen      whether the server's ticks are frozen
     */
    public static boolean runs(boolean advanceTime, boolean paused, boolean frozen) {
        return advanceTime && !paused && !frozen;
    }

    /**
     * One server tick.
     *
     * @param advanceTime {@code gamerule advance_time}
     * @param paused      whether the clock is paused ({@code /time pause})
     * @param frozen      whether the server's ticks are frozen
     * @param rate        the clock ticks per server tick ({@code /time rate}); anything but a positive finite rate counts as 1
     * @return whether {@link #running()} or {@link #rate()} changed with this tick
     */
    public boolean tick(boolean advanceTime, boolean paused, boolean frozen, float rate) {
        boolean now = runs(advanceTime, paused, frozen);
        float speed = rate > 0 && Float.isFinite(rate) ? rate : 1;
        if (now == running && speed == this.rate) return false;
        running = now;
        this.rate = speed;
        return true;
    }

    /** Whether the clock runs; true until a tick says otherwise. */
    public boolean running() {
        return running;
    }

    /** The clock ticks per server tick while it runs; 1 until a tick says otherwise. */
    public float rate() {
        return rate;
    }
}

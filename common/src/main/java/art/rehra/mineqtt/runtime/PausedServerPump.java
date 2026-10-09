package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.mqtt.core.MqttLog;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Keeps MQTT moving while an empty dedicated server is paused (M2 plan D5). Such a server returns from its tick before
 * {@code SERVER_PRE}, but its main loop keeps running queued tasks between the ticks, so a {@link #wake()} posts one
 * pump task there, which does the work of a tick. Posts are deduplicated: one pump waits at a time.
 * <p>
 * Wakes come from the MQTT client threads (the service's inbound listener) and from one daemon thread of this class,
 * "MineQTT pause wake", which handles the timed work no message announces (discovery and outbox rate limits, throttled
 * states, the Home Assistant birth jitter). Every {@code periodMillis} it wakes while the server is paused and work is
 * pending, and once when a pause begins, so the pause is noted and the work queued since the last tick is judged by the
 * pump itself. A pump that posts itself again was rejected: the main loop runs queued tasks for its whole time window,
 * so it would spin an idle server. The thread never touches game or runtime state; it only reads the two suppliers,
 * which must be safe on any thread.
 */
final class PausedServerPump implements AutoCloseable {
    /** How often the wake thread looks, and how long no tick ran at least before a server counts as paused. */
    static final long PERIOD_MILLIS = 250;
    private static final long PAUSE_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(PERIOD_MILLIS);

    private final Consumer<Runnable> post;
    private final Runnable pump;
    private final BooleanSupplier paused;
    private final BooleanSupplier pendingWork;
    private final MqttLog log;
    private final AtomicBoolean posted = new AtomicBoolean();
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;
    // Wake thread only.
    private boolean wasPaused;

    /**
     * Starts the wake thread.
     *
     * @param post        queues a task for the server thread between ticks; must never run it on the calling thread
     * @param pump        the work of a tick, run by the posted task on the server thread
     * @param paused      any thread: whether the server is paused for being empty ({@link #shouldPump})
     * @param pendingWork any thread: whether a pump would have something to do
     */
    PausedServerPump(Consumer<Runnable> post, Runnable pump, BooleanSupplier paused, BooleanSupplier pendingWork, MqttLog log,
                     long periodMillis) {
        this.post = post;
        this.pump = pump;
        this.paused = paused;
        this.pendingWork = pendingWork;
        this.log = log;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "MineQTT pause wake");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::scan, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Whether a server should be pumped: a dedicated server that pauses when empty, with no players, whose last tick
     * started more than {@link #PERIOD_MILLIS} or two tick lengths ago, and MineQTT not stopped. The tick length keeps a
     * server whose {@code /tick rate} is below 4 from counting as paused between its ticks during the countdown to the
     * pause. The singleplayer pause is never pumped.
     */
    static boolean shouldPump(boolean dedicated, int pauseWhenEmptySeconds, int players, long lastTickNanos, long tickNanos,
                              long nowNanos, boolean stopped) {
        return dedicated && pauseWhenEmptySeconds > 0 && players == 0 && nowNanos - lastTickNanos > Math.max(PAUSE_GAP_NANOS, 2 * tickNanos)
                && !stopped;
    }

    /** Any thread: posts one pump while the server is paused, unless one is already waiting. */
    void wake() {
        if (closed || !paused.getAsBoolean() || !posted.compareAndSet(false, true)) return;
        post.accept(this::runPump);
    }

    /** Stops the wake thread; later wakes and pumps that are still queued do nothing. */
    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }

    private void runPump() {
        // Cleared first, so what arrives while the pump runs posts the next one.
        posted.set(false);
        if (!closed) pump.run();
    }

    private void scan() {
        try {
            boolean pausedNow = paused.getAsBoolean();
            boolean began = pausedNow && !wasPaused;
            wasPaused = pausedNow;
            if (began || pausedNow && pendingWork.getAsBoolean()) wake();
        } catch (RuntimeException e) {
            // An exception would end the schedule for good.
            log.error("MineQTT pause wake failed", e);
        }
    }
}

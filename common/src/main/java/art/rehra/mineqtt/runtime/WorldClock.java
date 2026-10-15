package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.device.timer.core.ClockWatch;
import art.rehra.mineqtt.util.GameClock;
import net.minecraft.server.MinecraftServer;
//? if >=26.1 {
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.WorldClocks;
//?}
import net.minecraft.world.level.Level;
//? if >=1.21.11 {
import net.minecraft.world.level.gamerules.GameRules;
//?} else {
/*import net.minecraft.world.level.GameRules;
*///?}

import java.util.Optional;

/**
 * The one clock the timers and the world telemetry read (M4 plan D2, D6): the overworld's total ticks, whatever
 * dimension the reader is in, so a timer in the Nether fires at the overworld's sunrise. {@link GameClock} turns the
 * value into a time of day, a day number and {@code HH:MM}.
 * <p>
 * World clocks, with {@code /time pause} and {@code /time rate}, are new in 26.1; before, the overworld's day time is the
 * clock, and it never pauses and runs at rate 1.
 */
public final class WorldClock {
    private WorldClock() {
    }

    /**
     * The overworld clock's total ticks: {@code Level.getOverworldClockTime()}, which reads the {@code minecraft:overworld}
     * world clock as the v1 timers did ({@code clockManager().getTotalTicks(WorldClocks.OVERWORLD)}), so a timer's
     * {@code lastTriggerDay} keeps its meaning. Without that clock it is 0, where the v1 lookup fell back to the game time;
     * every 26.2 world has the clock.
     */
    public static long overworldTicks(Level level) {
        //? if >=26.1 {
        return level.getOverworldClockTime();
        //?} else {
        /*// A dimension other than the overworld shares the overworld's day time.
        return level.getDayTime();
        *///?}
    }

    /** The overworld's time of day, 0 to 23999 ({@link GameClock#timeOfDay}). */
    public static int timeOfDay(Level level) {
        return GameClock.timeOfDay(overworldTicks(level));
    }

    /** Whether the game rule lets the day advance ({@code advance_time}, {@code doDaylightCycle} before 1.21.11). */
    public static boolean advances(MinecraftServer server) {
        //? if >=26.1 {
        return server.getGlobalGameRules().get(GameRules.ADVANCE_TIME);
        //?} else if >=1.21.11 {
        /*return server.overworld().getGameRules().get(GameRules.ADVANCE_TIME);
        *///?} else {
        /*return server.overworld().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        *///?}
    }

    /** Whether the overworld clock is paused ({@code /time pause}); false without that clock, which every 26.2 world has. */
    public static boolean paused(MinecraftServer server) {
        //? if >=26.1 {
        return overworldState(server).map(ClockState::paused).orElse(false);
        //?} else {
        /*return false;
        *///?}
    }

    /** The overworld clock's ticks per server tick ({@code /time rate}); 1 without that clock. */
    public static float clockRate(MinecraftServer server) {
        //? if >=26.1 {
        return overworldState(server).map(ClockState::rate).orElse(1f);
        //?} else {
        /*return 1f;
        *///?}
    }

    //? if >=26.1 {
    // The overworld clock's saved state on a server: its ticks, whether it is paused and its rate. Empty without that clock.
    private static Optional<ClockState> overworldState(MinecraftServer server) {
        return server.registryAccess().get(WorldClocks.OVERWORLD)
                .map(clock -> server.clockManager().packState().clocks().get(clock));
    }
    //?}

    /**
     * Whether the overworld clock runs on the level's server, as the runtime's {@link ClockWatch} read it at the start of
     * the last tick; true where MineQTT is not running, so a timer then behaves as before.
     */
    public static boolean running(Level level) {
        MineqttRuntime runtime = runtime(level);
        return runtime == null || runtime.clock().running();
    }

    /** The overworld clock's ticks per server tick ({@code /time rate}) as the runtime read it; 1 where MineQTT is not running. */
    public static float rate(Level level) {
        MineqttRuntime runtime = runtime(level);
        return runtime == null ? 1 : runtime.clock().rate();
    }

    private static MineqttRuntime runtime(Level level) {
        return level.getServer() == null ? null : MineqttRuntime.find(level.getServer());
    }
}

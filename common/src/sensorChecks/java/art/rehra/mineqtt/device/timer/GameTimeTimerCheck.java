package art.rehra.mineqtt.device.timer;

import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.device.types.Timers.TriggerAt;
import art.rehra.mineqtt.util.GameClock;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.Optional;

/**
 * Regression checks for the Daylight Timer's time conversion, trigger presets and pulse duration, run by Gradle's
 * verifyGameTimeTimer task. The Daylight Timer is a device since M4 (plan D2); its presets are its {@code trigger_at}
 * values, its formatting is {@link GameClock}'s and its pulse is its kind's, with the assertions the v1 block entity had.
 */
public final class GameTimeTimerCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // 1. Verify pulse duration is 30 ticks (wooden button duration)
        check(TimerKind.GAME_TIME.pulseTicks() == 30, "The game time kind's pulse must be 30 ticks (button length)");

        // 2. Verify daylight cycle presets
        check(TriggerAt.SUNRISE.ticks().equals(Optional.of(0)), "Sunrise preset must be tick 0 (06:00)");
        check(TriggerAt.NOON.ticks().equals(Optional.of(6000)), "Noon preset must be tick 6000 (12:00)");
        check(TriggerAt.SUNSET.ticks().equals(Optional.of(12000)), "Sunset preset must be tick 12000 (18:00)");
        check(TriggerAt.MIDNIGHT.ticks().equals(Optional.of(18000)), "Midnight preset must be tick 18000 (00:00)");
        check(TriggerAt.CUSTOM.ticks().isEmpty(), "Custom preset has no tick of its own; the timer's time is used");
        // v1 stored the preset by its index, in this order.
        check(TriggerAt.ofV1(0) == TriggerAt.SUNRISE && TriggerAt.ofV1(3) == TriggerAt.MIDNIGHT && TriggerAt.ofV1(4) == TriggerAt.CUSTOM
                && TriggerAt.ofV1(9) == TriggerAt.SUNRISE, "v1 indices map to the presets, an unknown one to sunrise as v1 read it");

        // 3. Verify daylight time formatting
        check(GameClock.format(0).equals("06:00"), "Tick 0 formats to 06:00 (sunrise)");
        check(GameClock.format(6000).equals("12:00"), "Tick 6000 formats to 12:00 (noon)");
        check(GameClock.format(12000).equals("18:00"), "Tick 12000 formats to 18:00 (sunset)");
        check(GameClock.format(18000).equals("00:00"), "Tick 18000 formats to 00:00 (midnight)");

        System.out.println("Game Time Timer regression checks passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

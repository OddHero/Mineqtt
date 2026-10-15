package art.rehra.mineqtt.device.timer;

import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.util.GameClock;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.Optional;

/**
 * Regression checks for the Alarm Clock's time conversion and pulse duration, run by Gradle's verifyAlarmClock task. The
 * alarm clock is a device since M4 (plan D2); its HH:MM conversions are {@link GameClock}'s and its pulse is its kind's,
 * with the assertions the v1 block entity had.
 */
public final class AlarmClockCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // 1. Verify pulse duration is 30 ticks (wooden button duration)
        check(TimerKind.ALARM.pulseTicks() == 30, "The alarm kind's pulse must be 30 ticks (button length)");

        // 2. Verify time parsing and formatting (Minecraft daylight cycle)
        // In Minecraft: 06:00 = tick 0 (dawn), 12:00 = tick 6000 (noon), 18:00 = tick 12000 (dusk), 00:00 = tick 18000 (midnight)
        check(GameClock.parse("06:00").equals(Optional.of(0)), "06:00 should be tick 0 (sunrise)");
        check(GameClock.parse("12:00").equals(Optional.of(6000)), "12:00 should be tick 6000 (noon)");
        check(GameClock.parse("18:00").equals(Optional.of(12000)), "18:00 should be tick 12000 (sunset)");
        check(GameClock.parse("00:00").equals(Optional.of(18000)), "00:00 should be tick 18000 (midnight)");

        check(GameClock.format(0).equals("06:00"), "Tick 0 formats to 06:00 (sunrise)");
        check(GameClock.format(6000).equals("12:00"), "Tick 6000 formats to 12:00 (noon)");
        check(GameClock.format(12000).equals("18:00"), "Tick 12000 formats to 18:00 (sunset)");
        check(GameClock.format(18000).equals("00:00"), "Tick 18000 formats to 00:00 (midnight)");

        // Comprehensive roundtrip test across all 24 hours
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m += 5) {
                String t = String.format("%02d:%02d", h, m);
                int ticks = GameClock.parse(t).orElseThrow(() -> new AssertionError("Must parse " + t));
                check(ticks >= 0 && ticks < 24000, "Ticks must be in valid range [0, 23999]");
                String formatted = GameClock.format(ticks);
                check(formatted.equals(t), "Roundtrip time format for " + t + " got " + formatted);
            }
        }

        // 3. Validation: what is no time is refused
        check(GameClock.parse("24:00").isEmpty(), "Should fail on hour 24");
        check(GameClock.parse("12:60").isEmpty(), "Should fail on minute 60");
        check(GameClock.parse("invalid").isEmpty(), "Should fail on invalid format");

        System.out.println("Alarm Clock regression checks passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

package art.rehra.mineqtt.blocks.entities;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Regression checks for AlarmClockBlockEntity time conversion and pulse duration.
 */
public final class AlarmClockBlockEntityTest {
    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // 1. Verify pulse duration is 30 ticks (wooden button duration)
        check(AlarmClockBlockEntity.PULSE_DURATION == 30, "Pulse duration must be 30 ticks (button length)");

        // 2. Verify time parsing and formatting (Minecraft daylight cycle)
        // In Minecraft: 06:00 = tick 0 (dawn), 12:00 = tick 6000 (noon), 18:00 = tick 12000 (dusk), 00:00 = tick 18000 (midnight)
        check(AlarmClockBlockEntity.parseAlarmTime("06:00") == 0, "06:00 should be tick 0 (sunrise)");
        check(AlarmClockBlockEntity.parseAlarmTime("12:00") == 6000, "12:00 should be tick 6000 (noon)");
        check(AlarmClockBlockEntity.parseAlarmTime("18:00") == 12000, "18:00 should be tick 12000 (sunset)");
        check(AlarmClockBlockEntity.parseAlarmTime("00:00") == 18000, "00:00 should be tick 18000 (midnight)");

        check(AlarmClockBlockEntity.formatAlarmTime(0).equals("06:00"), "Tick 0 formats to 06:00 (sunrise)");
        check(AlarmClockBlockEntity.formatAlarmTime(6000).equals("12:00"), "Tick 6000 formats to 12:00 (noon)");
        check(AlarmClockBlockEntity.formatAlarmTime(12000).equals("18:00"), "Tick 12000 formats to 18:00 (sunset)");
        check(AlarmClockBlockEntity.formatAlarmTime(18000).equals("00:00"), "Tick 18000 formats to 00:00 (midnight)");

        // Comprehensive roundtrip test across all 24 hours
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m += 5) {
                String t = String.format("%02d:%02d", h, m);
                int ticks = AlarmClockBlockEntity.parseAlarmTime(t);
                check(ticks >= 0 && ticks < 24000, "Ticks must be in valid range [0, 23999]");
                String formatted = AlarmClockBlockEntity.formatAlarmTime(ticks);
                check(formatted.equals(t), "Roundtrip time format for " + t + " got " + formatted);
            }
        }

        // 3. Validation exceptions
        try {
            AlarmClockBlockEntity.parseAlarmTime("24:00");
            check(false, "Should fail on hour 24");
        } catch (IllegalArgumentException expected) {}

        try {
            AlarmClockBlockEntity.parseAlarmTime("12:60");
            check(false, "Should fail on minute 60");
        } catch (IllegalArgumentException expected) {}

        try {
            AlarmClockBlockEntity.parseAlarmTime("invalid");
            check(false, "Should fail on invalid format");
        } catch (IllegalArgumentException expected) {}

        System.out.println("Alarm Clock regression checks passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

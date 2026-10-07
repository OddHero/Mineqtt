package art.rehra.mineqtt.blocks.entities;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Regression checks for GameTimeTimerBlockEntity time conversion, trigger presets, and pulse duration.
 */
public final class GameTimeTimerBlockEntityTest {
    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // 1. Verify pulse duration is 30 ticks (wooden button duration)
        check(GameTimeTimerBlockEntity.PULSE_DURATION == 30, "Pulse duration must be 30 ticks (button length)");

        // 2. Verify daylight cycle presets
        check(GameTimeTrigger.SUNRISE.getDefaultTicks() == 0, "Sunrise preset must be tick 0 (06:00)");
        check(GameTimeTrigger.NOON.getDefaultTicks() == 6000, "Noon preset must be tick 6000 (12:00)");
        check(GameTimeTrigger.SUNSET.getDefaultTicks() == 12000, "Sunset preset must be tick 12000 (18:00)");
        check(GameTimeTrigger.MIDNIGHT.getDefaultTicks() == 18000, "Midnight preset must be tick 18000 (00:00)");
        check(GameTimeTrigger.CUSTOM.getDefaultTicks() == -1, "Custom preset must be -1");

        // 3. Verify daylight time formatting
        check(GameTimeTimerBlockEntity.formatGameTime(0).equals("06:00"), "Tick 0 formats to 06:00 (sunrise)");
        check(GameTimeTimerBlockEntity.formatGameTime(6000).equals("12:00"), "Tick 6000 formats to 12:00 (noon)");
        check(GameTimeTimerBlockEntity.formatGameTime(12000).equals("18:00"), "Tick 12000 formats to 18:00 (sunset)");
        check(GameTimeTimerBlockEntity.formatGameTime(18000).equals("00:00"), "Tick 18000 formats to 00:00 (midnight)");

        System.out.println("Game Time Timer regression checks passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

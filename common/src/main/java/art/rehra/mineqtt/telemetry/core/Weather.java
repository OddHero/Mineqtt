package art.rehra.mineqtt.telemetry.core;

import java.util.Locale;

/** The overworld's weather as the bridge publishes it (M4 plan D6), the options of its {@code weather} enum sensor. */
public enum Weather {
    CLEAR,
    RAIN,
    THUNDER;

    /** Thunder wins over rain, rain over clear. */
    public static Weather of(boolean raining, boolean thundering) {
        return thundering ? THUNDER : raining ? RAIN : CLEAR;
    }

    /** {@code clear}, {@code rain} or {@code thunder}: the published value. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}

package art.rehra.mineqtt.mqtt.core;

import java.util.Locale;

/** The MQTT protocol spoken to the broker. A plain config value: MineQTT never auto-detects it. */
public enum ProtocolVersion {
    MQTT_5("5"),
    MQTT_3_1_1("3.1.1");

    private final String configValue;

    ProtocolVersion(String configValue) {
        this.configValue = configValue;
    }

    /** The spelling used in config files and status output. */
    public String configValue() {
        return configValue;
    }

    /** Parses a config value: {@code 5} (also {@code 5.0}) or {@code 3.1.1}. */
    public static ProtocolVersion parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "5", "5.0" -> MQTT_5;
            case "3.1.1" -> MQTT_3_1_1;
            default -> throw new IllegalArgumentException("Unknown MQTT protocol '" + value + "', expected 5 or 3.1.1");
        };
    }
}

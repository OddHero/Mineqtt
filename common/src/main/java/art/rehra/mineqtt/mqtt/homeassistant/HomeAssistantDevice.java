package art.rehra.mineqtt.mqtt.homeassistant;

/**
 * Base interface for Home Assistant discoverable devices.
 * Each device type (light, sensor, switch, etc.) implements this interface.
 */
public interface HomeAssistantDevice {

    /**
     * Get the component type for this device (e.g., "light", "sensor", "switch").
     */
    String getComponentType();

    /**
     * Get the unique identifier for this device.
     * Derived from the MQTT topic.
     */
    String getUniqueId();

    /**
     * Get the MQTT command topic for this device.
     */
    String getCommandTopic();

    /**
     * Build the discovery topic for this device.
     * Format: <prefix>/<component>/<unique_id>/config
     */
    default String getDiscoveryTopic(String prefix) {
        return prefix + "/" + getComponentType() + "/" + getUniqueId() + "/config";
    }

    /**
     * Convert device configuration to JSON payload for discovery.
     */
    String toJson();
}


package art.rehra.mineqtt.config;

import java.nio.file.Path;

/**
 * Platform-agnostic interface for config management
 */
public interface ConfigHandler {
    /**
     * Load configuration values from platform-specific storage, migrating a v1 file to v2
     */
    void loadConfig();

    /**
     * Save configuration values to platform-specific storage
     */
    void saveConfig();

    /**
     * Check if configuration is valid: a blank broker is valid (MQTT disabled), a malformed one is not
     */
    boolean isConfigValid();

    /**
     * Reset configuration to default values
     */
    void resetToDefaults();

    /**
     * The config file, for log messages that tell the user where to change a setting
     */
    Path configPath();
}

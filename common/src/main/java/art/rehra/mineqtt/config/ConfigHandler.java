package art.rehra.mineqtt.config;

/**
 * Platform-agnostic interface for config management
 */
public interface ConfigHandler {
    /**
     * Load configuration values from platform-specific storage
     */
    void loadConfig();

    /**
     * Save configuration values to platform-specific storage
     */
    void saveConfig();

    /**
     * Check if configuration is valid
     */
    boolean isConfigValid();

    /**
     * Reset configuration to default values
     */
    void resetToDefaults();
}

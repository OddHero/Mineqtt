package art.rehra.mineqtt.config;

/**
 * Platform-agnostic interface for config management
 */
public interface ConfigHandler {
    void loadConfig();
    void saveConfig();
    void resetConfig();
}

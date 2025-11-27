package art.rehra.mineqtt.mqtt.homeassistant.devices;

import art.rehra.mineqtt.mqtt.homeassistant.HomeAssistantDevice;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Home Assistant MQTT Light device (JSON schema).
 * Supports RGB, HS, XY color modes, brightness, and color temperature.
 */
public class HomeAssistantLight implements HomeAssistantDevice {

    private final String uniqueId;
    private final String commandTopic;
    private String name;

    // Optional fields
    private String stateTopic;
    private boolean supportsBrightness = true;
    private int brightnessScale = 255;
    private String[] supportedColorModes = {"rgb", "hs", "xy"};
    private boolean supportsColorTemp = true;
    private int minMireds = 153;
    private int maxMireds = 500;

    // Device info
    private String deviceName = "MineQTT Device";
    private String deviceModel = "RGB LED Block";
    private String deviceManufacturer = "MineQTT";
    private String deviceSwVersion = "1.0";
    private String suggestedArea;

    public HomeAssistantLight(String topic) {
        this.uniqueId = sanitizeForUniqueId(topic);
        this.commandTopic = topic;
        this.stateTopic = topic + "/state";  // Auto-set state topic
        this.name = "RGB LED " + topic;
    }

    /**
     * Sanitize topic for use as unique_id (alphanumeric, underscore, hyphen only).
     */
    private String sanitizeForUniqueId(String topic) {
        return topic.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String getComponentType() {
        return "light";
    }

    @Override
    public String getUniqueId() {
        return uniqueId;
    }

    @Override
    public String getCommandTopic() {
        return commandTopic;
    }

    // Fluent setters for optional configuration

    public HomeAssistantLight setName(String name) {
        this.name = name;
        return this;
    }

    public HomeAssistantLight setStateTopic(String stateTopic) {
        this.stateTopic = stateTopic;
        return this;
    }

    public HomeAssistantLight setBrightness(boolean supports, int scale) {
        this.supportsBrightness = supports;
        this.brightnessScale = scale;
        return this;
    }

    public HomeAssistantLight setSupportedColorModes(String... modes) {
        this.supportedColorModes = modes;
        return this;
    }

    public HomeAssistantLight setColorTemp(boolean supports, int minMireds, int maxMireds) {
        this.supportsColorTemp = supports;
        this.minMireds = minMireds;
        this.maxMireds = maxMireds;
        return this;
    }

    public HomeAssistantLight setDeviceInfo(String name, String model, String manufacturer, String swVersion) {
        this.deviceName = name;
        this.deviceModel = model;
        this.deviceManufacturer = manufacturer;
        this.deviceSwVersion = swVersion;
        return this;
    }

    public HomeAssistantLight setSuggestedArea(String area) {
        this.suggestedArea = area;
        return this;
    }

    @Override
    public String toJson() {
        JsonObject config = new JsonObject();

        // Basic configuration
        config.addProperty("name", name);
        config.addProperty("unique_id", uniqueId);
        config.addProperty("schema", "json");
        config.addProperty("command_topic", commandTopic);

        // Request Home Assistant to publish commands with retain flag
        config.addProperty("retain", true);

        // State topic for feedback
        config.addProperty("state_topic", stateTopic);

        // Brightness support
        if (supportsBrightness) {
            config.addProperty("brightness", true);
            config.addProperty("brightness_scale", brightnessScale);
        }

        // Supported color modes
        JsonArray colorModes = new JsonArray();
        for (String mode : supportedColorModes) {
            colorModes.add(mode);
        }
        config.add("supported_color_modes", colorModes);

        // Color temperature support
        if (supportsColorTemp) {
            config.addProperty("color_temp", true);
            config.addProperty("min_mireds", minMireds);
            config.addProperty("max_mireds", maxMireds);
        }

        // Device information (shared by all blocks with same topic)
        JsonObject device = new JsonObject();
        device.addProperty("identifiers", uniqueId);
        device.addProperty("name", deviceName);
        device.addProperty("model", deviceModel);
        device.addProperty("manufacturer", deviceManufacturer);
        device.addProperty("sw_version", deviceSwVersion);
        if (suggestedArea != null) {
            device.addProperty("suggested_area", suggestedArea);
        }
        config.add("device", device);

        // Origin information
        JsonObject origin = new JsonObject();
        origin.addProperty("name", "MineQTT");
        origin.addProperty("sw", "1.0");
        origin.addProperty("support_url", "https://github.com/yourusername/mineqtt");
        config.add("origin", origin);

        return config.toString();
    }
}


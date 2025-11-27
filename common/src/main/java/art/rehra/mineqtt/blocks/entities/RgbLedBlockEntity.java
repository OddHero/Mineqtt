package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.mqtt.BlockStateManager;
import art.rehra.mineqtt.mqtt.BlockStatePersistence;
import art.rehra.mineqtt.mqtt.homeassistant.HomeAssistantDiscoveryManager;
import art.rehra.mineqtt.mqtt.homeassistant.devices.HomeAssistantLight;
import art.rehra.mineqtt.ui.RgbLedBlockMenu;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class RgbLedBlockEntity extends MqttSubscriberBlockEntity {

    private int red = 0;
    private int green = 0;
    private int blue = 0;
    private boolean lit = false;

    // Target color (full brightness 0-15) - what color was set
    private int targetRed = 0;
    private int targetGreen = 0;
    private int targetBlue = 0;

    // Brightness level (0-255 HA scale, stored for scaling)
    private int brightness = 255;

    // Flag to track if we've done initial state sync
    private boolean initialStateSynced = false;

    public RgbLedBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.RGB_LED_BLOCK.get(), pos, blockState);
    }

    public int getRed() { return red; }
    public int getGreen() { return green; }
    public int getBlue() { return blue; }
    public boolean isLit() { return lit; }

    public int getLightLevel() {
        if (!lit) return 0;
        return Math.max(Math.max(red, green), blue);
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("Red", red);
        output.putInt("Green", green);
        output.putInt("Blue", blue);
        output.putByte("Lit", (byte) (lit ? 1 : 0));
        output.putInt("TargetRed", targetRed);
        output.putInt("TargetGreen", targetGreen);
        output.putInt("TargetBlue", targetBlue);
        output.putInt("Brightness", brightness);
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);

        // Load from NBT first
        this.red = input.getIntOr("Red", 0);
        this.green = input.getIntOr("Green", 0);
        this.blue = input.getIntOr("Blue", 0);
        this.lit = input.getByteOr("Lit", (byte) 0) != 0;
        this.targetRed = input.getIntOr("TargetRed", 0);
        this.targetGreen = input.getIntOr("TargetGreen", 0);
        this.targetBlue = input.getIntOr("TargetBlue", 0);
        this.brightness = input.getIntOr("Brightness", 255);

        // Try to restore from BlockStateManager (has priority over NBT)
        // This runs AFTER the block entity is fully constructed and level may be available
        if (this.level != null && !this.level.isClientSide) {
            BlockStatePersistence.BlockState savedState = BlockStateManager.getBlockState(
                this.level.dimension(), this.worldPosition
            );
            if (savedState != null) {
                this.red = savedState.red;
                this.green = savedState.green;
                this.blue = savedState.blue;
                this.targetRed = savedState.targetRed;
                this.targetGreen = savedState.targetGreen;
                this.targetBlue = savedState.targetBlue;
                this.brightness = savedState.brightness;
                this.lit = savedState.lit;

                MineQTT.LOGGER.info("Restored RGB LED state from persistence: R=" + red + " G=" + green + " B=" + blue + " Brightness=" + brightness);
            }
        }
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/rgb/default";
    }


    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = this.saveWithFullMetadata(registries);
        // Ensure RGB values are in the tag for client sync
        tag.putInt("Red", red);
        tag.putInt("Green", green);
        tag.putInt("Blue", blue);
        tag.putByte("Lit", (byte) (lit ? 1 : 0));
        return tag;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.rgb_led");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new RgbLedBlockMenu(containerId, inventory, this, player, this.worldPosition);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new RgbLedBlockMenu(containerId, inventory, this, null, this.worldPosition);
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        if (this.level == null || !(this.level.getBlockState(this.worldPosition).getBlock() instanceof RgbLedBlock)) {
            return;
        }

        message = message.trim();

        // Try to parse as Home Assistant JSON schema first
        if (message.startsWith("{")) {
            if (parseHomeAssistantJson(message)) {
                return;
            }
        }

        // Fall back to simple command parsing
        parseSimpleCommand(message);
    }

    private boolean parseHomeAssistantJson(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            boolean stateChanged = false;

            // Parse state field: "ON" or "OFF"
            if (json.has("state")) {
                String state = json.get("state").getAsString();
                this.lit = state.equalsIgnoreCase("ON");
                stateChanged = true;
            }

            // Parse brightness field (0-255)
            if (json.has("brightness")) {
                this.brightness = Math.max(0, Math.min(255, json.get("brightness").getAsInt()));
                // Only apply brightness if we have valid target colors
                // If all target colors are 0, don't apply (brightness-only message on fresh block)
                if (this.targetRed > 0 || this.targetGreen > 0 || this.targetBlue > 0) {
                    applyBrightness();
                    stateChanged = true;
                }
            }

            // Parse color object with various modes
            if (json.has("color")) {
                JsonObject color = json.getAsJsonObject("color");

                // RGB mode: color.r, color.g, color.b (0-255)
                if (color.has("r") || color.has("g") || color.has("b")) {
                    if (color.has("r")) {
                        int r = Math.max(0, Math.min(255, color.get("r").getAsInt()));
                        this.targetRed = (r * 15) / 255;
                    }
                    if (color.has("g")) {
                        int g = Math.max(0, Math.min(255, color.get("g").getAsInt()));
                        this.targetGreen = (g * 15) / 255;
                    }
                    if (color.has("b")) {
                        int b = Math.max(0, Math.min(255, color.get("b").getAsInt()));
                        this.targetBlue = (b * 15) / 255;
                    }
                    // Apply current brightness to new color
                    applyBrightness();
                    stateChanged = true;
                }

                // HS mode: color.h (0-360), color.s (0-100)
                else if (color.has("h") && color.has("s")) {
                    float h = Math.max(0, Math.min(360, color.get("h").getAsFloat()));
                    float s = Math.max(0, Math.min(100, color.get("s").getAsFloat())) / 100.0f;
                    int[] rgb = hsvToRgb(h, s, 1.0f);
                    this.targetRed = (rgb[0] * 15) / 255;
                    this.targetGreen = (rgb[1] * 15) / 255;
                    this.targetBlue = (rgb[2] * 15) / 255;
                    // Apply current brightness to new color
                    applyBrightness();
                    stateChanged = true;
                }

                // XY mode: color.x, color.y (CIE 1931 color space)
                else if (color.has("x") && color.has("y")) {
                    float x = Math.max(0, Math.min(1, color.get("x").getAsFloat()));
                    float y = Math.max(0, Math.min(1, color.get("y").getAsFloat()));
                    int[] rgb = xyToRgb(x, y);
                    this.targetRed = (rgb[0] * 15) / 255;
                    this.targetGreen = (rgb[1] * 15) / 255;
                    this.targetBlue = (rgb[2] * 15) / 255;
                    // Apply current brightness to new color
                    applyBrightness();
                    stateChanged = true;
                }
            }

            // Parse color_temp field (mireds or kelvin)
            if (json.has("color_temp")) {
                int colorTemp = Math.max(153, Math.min(500, json.get("color_temp").getAsInt()));
                int[] rgb = colorTempToRgb(colorTemp);
                this.targetRed = (rgb[0] * 15) / 255;
                this.targetGreen = (rgb[1] * 15) / 255;
                this.targetBlue = (rgb[2] * 15) / 255;
                // Apply current brightness to new color
                applyBrightness();
                stateChanged = true;
            }

            // Parse effect field (for future use)
            if (json.has("effect")) {
                String effect = json.get("effect").getAsString();
                // TODO: Implement effects like "colorloop", "flash", etc.
                MineQTT.LOGGER.info("Effect received (not yet implemented): " + effect);
            }

            // Parse transition field (for future use)
            if (json.has("transition")) {
                int transition = json.get("transition").getAsInt();
                // TODO: Implement smooth transitions over time
                MineQTT.LOGGER.debug("Transition time: " + transition + " seconds");
            }

            if (stateChanged) {
                this.setChanged();
                updateBlockLight();
                MineQTT.LOGGER.info("RGB LED updated via Home Assistant JSON: R=" + red + " G=" + green + " B=" + blue + " LIT=" + lit + " Brightness=" + brightness);
                return true;
            }

        } catch (Exception e) {
            MineQTT.LOGGER.warn("Failed to parse Home Assistant JSON: " + message, e);
        }
        return false;
    }

    /**
     * Apply current brightness level to target colors to get actual RGB values.
     * This ensures colors are scaled by the brightness setting.
     */
    private void applyBrightness() {
        float brightnessFactor = this.brightness / 255.0f;
        this.red = Math.round(this.targetRed * brightnessFactor);
        this.green = Math.round(this.targetGreen * brightnessFactor);
        this.blue = Math.round(this.targetBlue * brightnessFactor);

        // Clamp values to valid range
        this.red = Math.max(0, Math.min(15, this.red));
        this.green = Math.max(0, Math.min(15, this.green));
        this.blue = Math.max(0, Math.min(15, this.blue));

        // Save state and publish to MQTT
        saveStateAndPublish();
    }

    /**
     * Save current state to persistence and publish to MQTT state topic.
     */
    private void saveStateAndPublish() {
        if (this.level != null && !this.level.isClientSide) {
            String topic = getCombinedTopic();
            if (topic != null && !topic.isEmpty()) {
                // Save to persistence
                BlockStateManager.updateBlockState(
                    this.level.dimension(), this.worldPosition,
                    topic, red, green, blue, targetRed, targetGreen, targetBlue,
                    brightness, lit
                );

                // Publish state to MQTT
                publishStateToMqtt(topic);
            }
        }
    }

    /**
     * Publish current state to MQTT state topic.
     * Format matches Home Assistant JSON schema.
     */
    private void publishStateToMqtt(String baseTopic) {
        if (MineQTT.mqttClient == null || !MineQTT.mqttClient.getState().isConnected()) {
            return;
        }

        String stateTopic = baseTopic + "/state";

        // Build state JSON
        JsonObject state = new JsonObject();
        state.addProperty("state", lit ? "ON" : "OFF");
        state.addProperty("brightness", brightness);

        // Add color in RGB format (convert 0-15 target to 0-255)
        JsonObject color = new JsonObject();
        color.addProperty("r", (targetRed * 255) / 15);
        color.addProperty("g", (targetGreen * 255) / 15);
        color.addProperty("b", (targetBlue * 255) / 15);
        state.add("color", color);

        String payload = state.toString();

        try {
            MineQTT.mqttClient.publishWith()
                .topic(stateTopic)
                .payload(payload.getBytes())
                .retain(true)
                .qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
                .send();

            MineQTT.LOGGER.debug("Published state to " + stateTopic + ": " + payload);
        } catch (Exception e) {
            MineQTT.LOGGER.error("Failed to publish state to MQTT", e);
        }
    }

    // Convert HSV to RGB (H: 0-360, S: 0-1, V: 0-1)
    private int[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60.0f) % 2 - 1));
        float m = v - c;

        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }

        return new int[]{
            Math.round((r + m) * 255),
            Math.round((g + m) * 255),
            Math.round((b + m) * 255)
        };
    }

    // Convert XY (CIE 1931) to RGB (simplified conversion)
    private int[] xyToRgb(float x, float y) {
        float z = 1.0f - x - y;
        float Y = 1.0f;
        float X = (Y / y) * x;
        float Z = (Y / y) * z;

        // XYZ to RGB matrix (sRGB D65)
        float r = X * 3.2406f - Y * 1.5372f - Z * 0.4986f;
        float g = -X * 0.9689f + Y * 1.8758f + Z * 0.0415f;
        float b = X * 0.0557f - Y * 0.2040f + Z * 1.0570f;

        // Gamma correction
        r = r > 0.0031308f ? 1.055f * (float)Math.pow(r, 1/2.4) - 0.055f : 12.92f * r;
        g = g > 0.0031308f ? 1.055f * (float)Math.pow(g, 1/2.4) - 0.055f : 12.92f * g;
        b = b > 0.0031308f ? 1.055f * (float)Math.pow(b, 1/2.4) - 0.055f : 12.92f * b;

        return new int[]{
            Math.max(0, Math.min(255, Math.round(r * 255))),
            Math.max(0, Math.min(255, Math.round(g * 255))),
            Math.max(0, Math.min(255, Math.round(b * 255)))
        };
    }

    // Convert color temperature (mireds) to RGB
    private int[] colorTempToRgb(int mireds) {
        // Convert mireds to Kelvin
        float kelvin = 1000000.0f / mireds;
        float temp = kelvin / 100.0f;

        float r, g, b;

        // Red calculation
        if (temp <= 66) {
            r = 255;
        } else {
            r = temp - 60;
            r = 329.698727446f * (float)Math.pow(r, -0.1332047592);
            r = Math.max(0, Math.min(255, r));
        }

        // Green calculation
        if (temp <= 66) {
            g = temp;
            g = 99.4708025861f * (float)Math.log(g) - 161.1195681661f;
        } else {
            g = temp - 60;
            g = 288.1221695283f * (float)Math.pow(g, -0.0755148492);
        }
        g = Math.max(0, Math.min(255, g));

        // Blue calculation
        if (temp >= 66) {
            b = 255;
        } else if (temp <= 19) {
            b = 0;
        } else {
            b = temp - 10;
            b = 138.5177312231f * (float)Math.log(b) - 305.0447927307f;
            b = Math.max(0, Math.min(255, b));
        }

        return new int[]{Math.round(r), Math.round(g), Math.round(b)};
    }

    private void parseSimpleCommand(String message) {
        // Check for ON/OFF commands
        if (message.equalsIgnoreCase("ON") || message.equalsIgnoreCase("1") || message.equalsIgnoreCase("TRUE")) {
            this.lit = true;
            this.setChanged();
            updateBlockLight();
            MineQTT.LOGGER.info("RGB LED turned ON");
            return;
        } else if (message.equalsIgnoreCase("OFF") || message.equalsIgnoreCase("0") || message.equalsIgnoreCase("FALSE")) {
            this.lit = false;
            this.setChanged();
            updateBlockLight();
            MineQTT.LOGGER.info("RGB LED turned OFF");
            return;
        }

        // Parse RGB color from message
        int[] rgb = parseRgbFromMessage(message);

        if (rgb != null) {
            // Convert 0-255 to 0-15 for light level
            this.red = (rgb[0] * 15) / 255;
            this.green = (rgb[1] * 15) / 255;
            this.blue = (rgb[2] * 15) / 255;
            this.lit = true; // Auto turn on

            this.setChanged();
            updateBlockLight();
            MineQTT.LOGGER.info("RGB LED updated: R=" + red + " G=" + green + " B=" + blue + " (auto ON, light=" + getLightLevel() + ")");
        }
    }

    private void updateBlockLight() {
        if (this.level == null) return;

        BlockState currentState = this.level.getBlockState(this.worldPosition);
        if (currentState.getBlock() instanceof RgbLedBlock) {
            int lightLevel = getLightLevel();
            BlockState newState = currentState.setValue(RgbLedBlock.LIGHT_LEVEL, lightLevel);
            this.level.setBlock(this.worldPosition, newState, Block.UPDATE_ALL);

            // Force sync block entity data to client for color tinting
            if (!this.level.isClientSide) {
                this.level.sendBlockUpdated(this.worldPosition, currentState, newState, Block.UPDATE_CLIENTS);
            } else {
                // On client, request chunk re-render to update color
                this.level.sendBlockUpdated(this.worldPosition, currentState, newState, Block.UPDATE_ALL);
            }
        }
    }

    /**
     * Parse RGB values from various message formats.
     * Supports: "rgb(255,128,64)", "#FF8040", "255,128,64"
     * Returns array [r, g, b] or null if parsing fails
     */
    private int[] parseRgbFromMessage(String message) {
        try {
            message = message.trim();

            // Format: rgb(r,g,b)
            if (message.toLowerCase().startsWith("rgb(") && message.endsWith(")")) {
                String values = message.substring(4, message.length() - 1);
                return parseRgbValues(values);
            }

            // Format: #RRGGBB
            if (message.startsWith("#") && message.length() == 7) {
                int r = Integer.parseInt(message.substring(1, 3), 16);
                int g = Integer.parseInt(message.substring(3, 5), 16);
                int b = Integer.parseInt(message.substring(5, 7), 16);
                return new int[]{r, g, b};
            }

            // Format: r,g,b
            if (message.contains(",")) {
                return parseRgbValues(message);
            }

            MineQTT.LOGGER.warn("Could not parse RGB from message: " + message);
            return null;
        } catch (Exception e) {
            MineQTT.LOGGER.error("Error parsing RGB message: " + message, e);
            return null;
        }
    }

    private int[] parseRgbValues(String values) {
        String[] parts = values.split(",");
        if (parts.length == 3) {
            int r = Math.max(0, Math.min(255, Integer.parseInt(parts[0].trim())));
            int g = Math.max(0, Math.min(255, Integer.parseInt(parts[1].trim())));
            int b = Math.max(0, Math.min(255, Integer.parseInt(parts[2].trim())));
            return new int[]{r, g, b};
        }
        return null;
    }

    /**
     * Publish Home Assistant MQTT discovery configuration.
     * Uses the centralized discovery manager.
     */
    private void publishHomeAssistantDiscovery() {
        String topic = getCombinedTopic();
        if (topic == null || topic.isEmpty()) {
            return;
        }

        // Create block position identifier: "dimension:x:y:z"
        String blockId = getBlockPositionId();

        // Determine area based on dimension
        String suggestedArea = HomeAssistantDiscoveryManager.getSuggestedAreaFromBlockPosition(blockId);

        // Create Home Assistant Light device
        HomeAssistantLight light = new HomeAssistantLight(topic)
            .setName("RGB LED " + topic)
            .setBrightness(true, 255)
            .setSupportedColorModes("rgb", "hs", "xy")
            .setColorTemp(true, 153, 500)
            .setDeviceInfo("MineQTT RGB LED", "RGB LED Block", "MineQTT", "1.0")
            .setSuggestedArea(suggestedArea);

        // Register with discovery manager
        HomeAssistantDiscoveryManager.registerDevice(topic, blockId, light);
    }

    /**
     * Remove this device from Home Assistant.
     * Uses the centralized discovery manager.
     */
    private void removeFromHomeAssistant() {
        String topic = getCombinedTopic();
        if (topic == null || topic.isEmpty()) {
            return;
        }

        String blockId = getBlockPositionId();
        HomeAssistantDiscoveryManager.unregisterDevice(topic, blockId);
    }

    /**
     * Get unique identifier for this block position.
     */
    private String getBlockPositionId() {
        if (this.level == null) {
            return worldPosition.toShortString();
        }
        return level.dimension().location() + ":" + worldPosition.toShortString();
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof RgbLedBlockEntity rgbLedBlockEntity) {

                // On first tick, publish state to MQTT if we have a saved state
                if (!rgbLedBlockEntity.initialStateSynced && !level.isClientSide) {
                    rgbLedBlockEntity.initialStateSynced = true;

                    // Save current state to BlockStateManager (in case it was loaded from NBT)
                    String topic = rgbLedBlockEntity.getCombinedTopic();
                    if (topic != null && !topic.isEmpty()) {
                        BlockStateManager.updateBlockState(
                            level.dimension(), blockPos,
                            topic,
                            rgbLedBlockEntity.red, rgbLedBlockEntity.green, rgbLedBlockEntity.blue,
                            rgbLedBlockEntity.targetRed, rgbLedBlockEntity.targetGreen, rgbLedBlockEntity.targetBlue,
                            rgbLedBlockEntity.brightness, rgbLedBlockEntity.lit
                        );
                    }

                    // Update light level based on loaded state
                    rgbLedBlockEntity.updateBlockLight();

                    // Publish current state to MQTT so HA knows the state
                    if (topic != null && !topic.isEmpty()) {
                        rgbLedBlockEntity.publishStateToMqtt(topic);
                    }
                }

                String newCombinedTopic = rgbLedBlockEntity.getCombinedTopic();
                String oldTopic = rgbLedBlockEntity.topic;

                boolean topicChanged = !newCombinedTopic.equals(oldTopic);

                if (topicChanged) {
                    rgbLedBlockEntity.setTopic(newCombinedTopic);

                    // Update subscription
                    rgbLedBlockEntity.updateSubscription(newCombinedTopic);

                    String blockId = rgbLedBlockEntity.getBlockPositionId();

                    // If disabled, reset to black and turn off
                    if (!rgbLedBlockEntity.isEnabled()) {
                        rgbLedBlockEntity.red = 0;
                        rgbLedBlockEntity.green = 0;
                        rgbLedBlockEntity.blue = 0;
                        rgbLedBlockEntity.lit = false;
                        rgbLedBlockEntity.setChanged();

                        // Update light level in block state
                        BlockState currentState = level.getBlockState(blockPos);
                        if (currentState.getBlock() instanceof RgbLedBlock) {
                            BlockState newState = currentState.setValue(RgbLedBlock.LIGHT_LEVEL, 0);
                            level.setBlock(blockPos, newState, Block.UPDATE_ALL);
                        }

                        // Unregister from discovery (will remove if last block)
                        HomeAssistantDiscoveryManager.unregisterDevice(oldTopic, blockId);

                        MineQTT.LOGGER.info("RGB LED disabled - no base path item in first slot");
                    } else {
                        // Create device configuration with area
                        String suggestedArea = HomeAssistantDiscoveryManager.getSuggestedAreaFromBlockPosition(blockId);

                        HomeAssistantLight light = new HomeAssistantLight(newCombinedTopic)
                            .setName("RGB LED " + newCombinedTopic)
                            .setBrightness(true, 255)
                            .setSupportedColorModes("rgb", "hs", "xy")
                            .setColorTemp(true, 153, 500)
                            .setDeviceInfo("MineQTT RGB LED", "RGB LED Block", "MineQTT", "1.0")
                            .setSuggestedArea(suggestedArea);

                        // Update topic in discovery manager (handles old topic cleanup)
                        HomeAssistantDiscoveryManager.updateDeviceTopic(oldTopic, newCombinedTopic, blockId, light);

                        // Check if there's an existing state for this topic (from another block)
                        BlockStatePersistence.BlockState existingState = BlockStateManager.getStateForTopic(newCombinedTopic);
                        if (existingState != null && !level.isClientSide) {
                            // Apply existing state from another block on same topic
                            rgbLedBlockEntity.targetRed = existingState.targetRed;
                            rgbLedBlockEntity.targetGreen = existingState.targetGreen;
                            rgbLedBlockEntity.targetBlue = existingState.targetBlue;
                            rgbLedBlockEntity.brightness = existingState.brightness;
                            rgbLedBlockEntity.lit = existingState.lit;

                            // Recalculate display colors
                            float brightnessFactor = rgbLedBlockEntity.brightness / 255.0f;
                            rgbLedBlockEntity.red = Math.max(0, Math.min(15, Math.round(rgbLedBlockEntity.targetRed * brightnessFactor)));
                            rgbLedBlockEntity.green = Math.max(0, Math.min(15, Math.round(rgbLedBlockEntity.targetGreen * brightnessFactor)));
                            rgbLedBlockEntity.blue = Math.max(0, Math.min(15, Math.round(rgbLedBlockEntity.targetBlue * brightnessFactor)));

                            rgbLedBlockEntity.setChanged();

                            // Update light level
                            BlockState currentState = level.getBlockState(blockPos);
                            if (currentState.getBlock() instanceof RgbLedBlock) {
                                int lightLevel = rgbLedBlockEntity.getLightLevel();
                                BlockState newState = currentState.setValue(RgbLedBlock.LIGHT_LEVEL, lightLevel);
                                level.setBlock(blockPos, newState, Block.UPDATE_ALL);
                            }

                            MineQTT.LOGGER.info("New RGB LED inherited state from existing block on topic: " + newCombinedTopic);
                        }
                    }
                }
                if (level.isClientSide && rgbLedBlockEntity.lit && rgbLedBlockEntity.getLightLevel() > 0) {
                    // Spawn particles every 10 ticks (0.5 seconds)
                    if (level.getGameTime() % 10 == 0) {
                        spawnColoredParticles(level, blockPos, rgbLedBlockEntity);
                    }
                }
            }
        }

        private void spawnColoredParticles(Level level, BlockPos pos, RgbLedBlockEntity entity) {
            // Convert 0-15 range to 0-255 for particle colors
            int r = (entity.getRed() * 255) / 15;
            int g = (entity.getGreen() * 255) / 15;
            int b = (entity.getBlue() * 255) / 15;

            // Don't spawn particles if color is black
            if (r == 0 && g == 0 && b == 0) return;

            // Pack RGB into single int (0xRRGGBB)
            int packedColor = (r << 16) | (g << 8) | b;

            // Spawn particles around the bulb
            double centerX = pos.getX() + 0.5;
            double centerY = pos.getY() + 0.6; // Slightly above center
            double centerZ = pos.getZ() + 0.5;

            // Spawn 2-3 particles per tick in a small radius
            int particleCount = level.random.nextInt(2) + 2;
            for (int i = 0; i < particleCount; i++) {
                double offsetX = (level.random.nextDouble() - 0.5) * 0.3;
                double offsetY = (level.random.nextDouble() - 0.5) * 0.3;
                double offsetZ = (level.random.nextDouble() - 0.5) * 0.3;

                // Small upward velocity for glow effect
                double velocityY = level.random.nextDouble() * 0.02;

                level.addParticle(
                    new net.minecraft.core.particles.DustParticleOptions(
                        packedColor,
                        0.8f // Particle size
                    ),
                    centerX + offsetX,
                    centerY + offsetY,
                    centerZ + offsetZ,
                    0, velocityY, 0
                );
            }
        }
    }
}


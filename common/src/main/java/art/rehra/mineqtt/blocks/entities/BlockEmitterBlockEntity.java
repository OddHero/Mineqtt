package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.BlockEmitterBlock;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.legacy.LegacySubscriptionFacade;
import art.rehra.mineqtt.mqtt.ICallbackTarget;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.BlockEmitterTab;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Places the block an MQTT message names in front of it. That accepts block ids, states and block entity data from
 * the broker, so it is off unless the server enables {@code blockPlacer.enabled} (design §8.3): while off, the block
 * neither subscribes nor decodes or places anything.
 * <p>
 * It receives the messages of its combined topic through the {@link LegacySubscriptionFacade}. Only the server block
 * entity subscribes: loading the data picks the topic, and the subscription opens once the block entity is in a server
 * level.
 */
public class BlockEmitterBlockEntity extends BaseMqttBlockEntity implements ICallbackTarget {
    private static final String DISABLED_STATUS = "Disabled: blockPlacer.enabled is off on this server";

    // The topic this block subscribes while the placer is enabled; still saved under its v1 key.
    private String currentSubscribedTopic = "";

    private String lastReceivedPayload = "";
    private String lastStatus = "Ready";
    private long lastExecutionTime = 0;
    // Whether the subscription follows an enabled placer; the ticker opens or closes it when the config changes.
    private boolean placerEnabled = MineQTTConfig.blockPlacerEnabled;

    public BlockEmitterBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.BLOCK_EMITTER_BLOCK.get(), pos, blockState);
    }

    public static boolean applyPayload(Level level, BlockPos targetPos, String message) {
        if (level == null || level.isClientSide()) {
            return false;
        }
        if (!level.hasChunkAt(targetPos)) {
            MineQTT.LOGGER.warn("Target chunk not loaded at {} for block emitter payload", targetPos);
            return false;
        }
        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        try {
            BlockState currentState = level.getBlockState(targetPos);
            ParsedPayload parsed = parsePayload(message, currentState);
            if (parsed == null || parsed.newState() == null) {
                return false;
            }

            boolean stateChanged = currentState != parsed.newState();
            if (stateChanged || parsed.isFullData()) {
                level.setBlock(targetPos, parsed.newState(), Block.UPDATE_ALL);
            }

            if (parsed.blockEntityData() != null) {
                BlockEntity be = level.getBlockEntity(targetPos);
                if (be != null) {
                    try {
                        be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                                new net.minecraft.util.ProblemReporter.Collector(),
                                level.registryAccess(),
                                parsed.blockEntityData()
                        ));
                        be.setChanged();
                    } catch (Exception e) {
                        MineQTT.LOGGER.warn("Failed to apply block entity NBT at {}: {}", targetPos, e.getMessage());
                    }
                }
            }
            return true;
        } catch (Exception e) {
            MineQTT.LOGGER.warn("Error processing block emitter payload: {}", e.getMessage());
            return false;
        }
    }

    /** Decodes a payload; nothing is decoded while the block placer is disabled. */
    public static ParsedPayload parsePayload(String message, BlockState currentTargetState) {
        if (!MineQTTConfig.blockPlacerEnabled || message == null || message.trim().isEmpty()) {
            return null;
        }

        String trimmed = message.trim();
        JsonElement element = null;
        try {
            element = JsonParser.parseString(trimmed);
        } catch (JsonParseException e) {
            // Not valid JSON, will treat as plain string fallback
        }

        if (element != null && element.isJsonObject()) {
            JsonObject jsonObject = element.getAsJsonObject();
            if (jsonObject.has("id")) {
                // Full Data JSON mode: {"id": "minecraft:chest", "properties": {...}, "block_entity": {...}}
                String idStr = jsonObject.get("id").getAsString();
                Block block = findBlock(idStr);
                if (block == null) {
                    MineQTT.LOGGER.warn("Unknown block id in emitter payload: {}", idStr);
                    return null;
                }

                BlockState newState = block.defaultBlockState();
                if (jsonObject.has("properties") && jsonObject.get("properties").isJsonObject()) {
                    newState = applyProperties(newState, jsonObject.getAsJsonObject("properties"));
                }

                CompoundTag compoundTag = null;
                if (jsonObject.has("block_entity") && !jsonObject.get("block_entity").isJsonNull() && jsonObject.get("block_entity").isJsonObject()) {
                    JsonElement beJson = jsonObject.get("block_entity");
                    try {
                        Tag tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, beJson);
                        if (tag instanceof CompoundTag ct) {
                            compoundTag = ct;
                        }
                    } catch (Exception e) {
                        MineQTT.LOGGER.warn("Failed to parse block entity JSON to NBT: {}", e.getMessage());
                    }
                }

                return new ParsedPayload(newState, compoundTag, true);
            } else {
                // Properties JSON mode: {"facing": "north", "powered": "true"}
                if (currentTargetState == null) {
                    return null;
                }
                BlockState newState = applyProperties(currentTargetState, jsonObject);
                return new ParsedPayload(newState, null, false);
            }
        } else {
            // Plain string or JSON string primitive mode: "minecraft:stone" or "Stone"
            String strValue = trimmed;
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                strValue = element.getAsString();
            } else if (strValue.startsWith("\"") && strValue.endsWith("\"") && strValue.length() >= 2) {
                strValue = strValue.substring(1, strValue.length() - 1);
            }

            Block block = findBlock(strValue);
            if (block != null) {
                return new ParsedPayload(block.defaultBlockState(), null, false);
            } else {
                MineQTT.LOGGER.warn("Unknown block in emitter payload: {}", strValue);
                return null;
            }
        }
    }

    public static BlockState applyProperties(BlockState state, JsonObject properties) {
        if (state == null || properties == null) {
            return state;
        }
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            String propName = entry.getKey();
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            String valStr = entry.getValue().getAsString();
            Property<?> property = state.getBlock().getStateDefinition().getProperty(propName);
            if (property != null) {
                state = setPropertyValue(state, property, valStr);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String valueStr) {
        Optional<T> value = property.getValue(valueStr);
        if (value.isPresent()) {
            return state.setValue(property, value.get());
        }
        return state;
    }

    public static Block findBlock(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        Identifier id = Identifier.tryParse(trimmed);
        if (id != null) {
            Optional<Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.get(id);
            if (holder.isPresent()) {
                return holder.get().value();
            }
        }
        for (Block b : BuiltInRegistries.BLOCK) {
            if (b.getName().getString().equalsIgnoreCase(trimmed)) {
                return b;
            }
            Identifier key = BuiltInRegistries.BLOCK.getKey(b);
            if (key.toString().equalsIgnoreCase(trimmed) || key.getPath().equalsIgnoreCase(trimmed)) {
                return b;
            }
        }
        return null;
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/default";
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.block_emitter");
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> list = super.getTabs();
        list.add(new BlockEmitterTab());
        return list;
    }

    public String getLastReceivedPayload() {
        return lastReceivedPayload != null ? lastReceivedPayload : "";
    }

    public void setLastReceivedPayload(String payload) {
        this.lastReceivedPayload = payload != null ? payload : "";
        markUpdated();
    }

    public String getLastStatus() {
        return lastStatus != null ? lastStatus : "Ready";
    }

    public void setLastStatus(String status) {
        this.lastStatus = status != null ? status : "Ready";
        markUpdated();
    }

    public long getLastExecutionTime() {
        return lastExecutionTime;
    }

    public void setLastExecutionTime(long time) {
        this.lastExecutionTime = time;
        markUpdated();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("currentSubscribedTopic", this.currentSubscribedTopic);
        output.putString("lastReceivedPayload", this.lastReceivedPayload);
        output.putString("lastStatus", this.lastStatus);
        output.putLong("lastExecutionTime", this.lastExecutionTime);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // The slots decide the topic. A block loading with its chunk has no level yet and subscribes from setLevel;
        // a live one (data replaced by a command) moves its subscription at once.
        updateSubscription(getCombinedTopic());
        this.lastReceivedPayload = input.getString("lastReceivedPayload").orElse("");
        this.lastStatus = input.getString("lastStatus").orElse("Ready");
        this.lastExecutionTime = input.getLong("lastExecutionTime").orElse(0L);
    }

    public boolean triggerTest(String testPayload) {
        if (this.level == null || this.level.isClientSide()) {
            return false;
        }
        if (!MineQTTConfig.blockPlacerEnabled) {
            setLastStatus(DISABLED_STATUS);
            return false;
        }
        if (testPayload == null || testPayload.isBlank()) {
            testPayload = !this.lastReceivedPayload.isBlank() ? this.lastReceivedPayload : "minecraft:stone";
        }
        this.lastReceivedPayload = testPayload;
        this.lastExecutionTime = this.level.getGameTime();

        BlockState state = this.getBlockState();
        if (!state.hasProperty(BlockEmitterBlock.FACING)) {
            this.lastStatus = "Error: Missing FACING";
            markUpdated();
            return false;
        }

        Direction facing = state.getValue(BlockEmitterBlock.FACING);
        BlockPos targetPos = this.worldPosition.relative(facing);
        boolean success = applyPayload(this.level, targetPos, testPayload);
        this.lastStatus = success ? "Success: Emitted payload" : "Error: Invalid payload or target";
        markUpdated();
        return success;
    }

    /** Chunk load and placement: the first moment the block entity knows its level. */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        resubscribe();
    }

    /** A block entity added back to its chunk after a removal. */
    @Override
    public void clearRemoved() {
        super.clearRemoved();
        resubscribe();
    }

    /** Unload or removal. The topic is kept, so a block entity added back to its chunk subscribes it again. */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (!currentSubscribedTopic.isEmpty()) {
            LegacySubscriptionFacade.unsubscribe(currentSubscribedTopic, this);
        }
    }

    /** Whether the block subscribes its topic: while it has one and the server enables the block placer. */
    private boolean listens() {
        return MineQTTConfig.blockPlacerEnabled && isEnabled();
    }

    private void updateSubscription(String newCombinedTopic) {
        if (newCombinedTopic.equals(currentSubscribedTopic)) return;
        if (!currentSubscribedTopic.isEmpty()) {
            LegacySubscriptionFacade.unsubscribe(currentSubscribedTopic, this);
        }
        currentSubscribedTopic = newCombinedTopic;
        if (listens() && !newCombinedTopic.isEmpty()) {
            LegacySubscriptionFacade.subscribe(newCombinedTopic, this);
        }
    }

    /** Subscribes the current topic again; subscribing a topic the block already has does nothing. */
    private void resubscribe() {
        if (listens() && !currentSubscribedTopic.isEmpty()) {
            LegacySubscriptionFacade.subscribe(currentSubscribedTopic, this);
        }
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        if (this.level == null || this.level.isClientSide() || !MineQTTConfig.blockPlacerEnabled) {
            return;
        }

        this.lastReceivedPayload = message != null ? message : "";
        this.lastExecutionTime = this.level.getGameTime();

        BlockState state = this.getBlockState();
        if (!state.hasProperty(BlockEmitterBlock.FACING)) {
            MineQTT.LOGGER.warn("BlockEmitterBlockEntity at {} has no FACING property", this.worldPosition);
            this.lastStatus = "Error: Missing FACING";
            markUpdated();
            return;
        }

        Direction facing = state.getValue(BlockEmitterBlock.FACING);
        BlockPos targetPos = this.worldPosition.relative(facing);
        boolean success = applyPayload(this.level, targetPos, message);
        this.lastStatus = success ? "Success: Emitted payload" : "Error: Invalid payload or target";
        markUpdated();
    }

    /** Opens or closes the subscription when {@code blockPlacer.enabled} changed since the last tick. */
    private void followPlacerConfig() {
        if (placerEnabled == MineQTTConfig.blockPlacerEnabled) return;
        placerEnabled = MineQTTConfig.blockPlacerEnabled;
        if (placerEnabled) {
            resubscribe();
        } else if (!currentSubscribedTopic.isEmpty()) {
            LegacySubscriptionFacade.unsubscribe(currentSubscribedTopic, this);
        }
        setLastStatus(placerEnabled ? "Ready" : DISABLED_STATUS);
    }

    public record ParsedPayload(BlockState newState, CompoundTag blockEntityData, boolean isFullData) {
    }

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof BlockEmitterBlockEntity emitterBlockEntity) {
                String newCombinedTopic = emitterBlockEntity.getCombinedTopic();
                boolean topicChanged = !newCombinedTopic.equals(emitterBlockEntity.topic);

                if (topicChanged) {
                    emitterBlockEntity.setTopic(newCombinedTopic);
                    emitterBlockEntity.updateSubscription(newCombinedTopic);
                }
                emitterBlockEntity.followPlacerConfig();
            }
        }
    }
}

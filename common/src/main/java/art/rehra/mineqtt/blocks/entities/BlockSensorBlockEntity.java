package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.BlockSensorBlock;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.BlockSensorTab;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

public class BlockSensorBlockEntity extends MqttPublisherBlockEntity {
    private DataMode dataMode = DataMode.FULL_DATA;
    private BlockState observedState;
    private BlockPos observedPos;
    private String lastPayload;
    private String lastPublishedTopic;
    private boolean observationRequested = true;
    private boolean wasConnected;
    public BlockSensorBlockEntity(BlockPos pos, BlockState state) {
        super(MineqttBlockEntityTypes.BLOCK_SENSOR_BLOCK.get(), pos, state);
    }

    public static String createPayload(Level level, BlockPos targetPos, BlockState target, DataMode mode) {
        BlockEntity targetEntity = mode == DataMode.FULL_DATA ? level.getBlockEntity(targetPos) : null;
        CompoundTag entityData = targetEntity == null ? null : targetEntity.saveWithFullMetadata(level.registryAccess());
        String dimension = mode == DataMode.FULL_DATA ? level.dimension().identifier().toString() : "";
        return createPayload(target, targetPos, mode, dimension, entityData);
    }

    static String createPayload(BlockState target, BlockPos targetPos, DataMode mode, String dimension, CompoundTag entityData) {
        String id = BuiltInRegistries.BLOCK.getKey(target.getBlock()).toString();
        String name = target.getBlock().getName().getString();
        if (mode == DataMode.BLOCK_ID) return id;
        if (mode == DataMode.NAME) return name;

        JsonObject properties = new JsonObject();
        for (Property<?> property : target.getProperties()) {
            properties.addProperty(property.getName(), propertyValue(target, property));
        }
        if (mode == DataMode.PROPERTIES) return properties.toString();

        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("name", name);
        data.add("properties", properties);
        JsonObject position = new JsonObject();
        position.addProperty("x", targetPos.getX());
        position.addProperty("y", targetPos.getY());
        position.addProperty("z", targetPos.getZ());
        data.add("position", position);
        data.addProperty("dimension", dimension);
        data.add("block_entity", entityData == null ? com.google.gson.JsonNull.INSTANCE
                : NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, entityData));
        return data.toString();
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public static void tick(Level level, BlockPos pos, BlockState state, BlockSensorBlockEntity sensor) {
        if (level.isClientSide()) return;
        String topic = sensor.getCombinedTopic();
        sensor.setTopic(topic);
        boolean connected = MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected();
        if (!sensor.isEnabled() || topic.isEmpty() || !connected) {
            // Publish a fresh snapshot when enabled or reconnected, even if the block stayed the same.
            sensor.wasConnected = false;
            sensor.requestObservation();
            return;
        }

        BlockPos targetPos = pos.relative(state.getValue(BlockSensorBlock.FACING));
        // Do not load a neighboring chunk or report an unloaded block as air.
        if (!level.hasChunkAt(targetPos)) return;
        BlockState target = level.getBlockState(targetPos);
        boolean stateChanged = target != sensor.observedState || !targetPos.equals(sensor.observedPos);
        boolean topicChanged = !topic.equals(sensor.lastPublishedTopic);
        boolean force = sensor.observationRequested || !sensor.wasConnected || topicChanged;
        // State updates are checked each tick; block entity data is sampled twice per second.
        if (!force && !stateChanged && (sensor.dataMode != DataMode.FULL_DATA || level.getGameTime() % 10 != 0)) return;

        String payload = createPayload(level, targetPos, target, sensor.dataMode);
        if (force || stateChanged || !payload.equals(sensor.lastPayload)) {
            sensor.publish(payload);
            sensor.lastPayload = payload;
            sensor.lastPublishedTopic = topic;
        }
        sensor.observedState = target;
        sensor.observedPos = targetPos;
        sensor.observationRequested = false;
        sensor.wasConnected = true;
    }

    @Override
    protected String getDefaultTopic() {
        return "/mineqtt/default";
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.block_sensor");
    }

    public DataMode getDataMode() {
        return dataMode;
    }

    public void setDataMode(DataMode mode) {
        if (dataMode != mode) {
            dataMode = mode;
            requestObservation();
            markUpdated();
        }
    }

    public void requestObservation() {
        observationRequested = true;
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new BlockSensorTab());
        return tabs;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("BlockSensorDataMode", dataMode.id());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        dataMode = DataMode.fromId(input.getString("BlockSensorDataMode").orElse("full_data"));
        requestObservation();
    }

    public enum DataMode {
        FULL_DATA("full_data"), BLOCK_ID("block_id"), NAME("name"), PROPERTIES("properties");

        private final String id;

        DataMode(String id) {
            this.id = id;
        }

        public static DataMode fromId(String id) {
            for (DataMode mode : values()) if (mode.id.equals(id)) return mode;
            return FULL_DATA;
        }

        public String id() {
            return id;
        }

        public Component title() {
            return Component.translatable("mineqtt.block_sensor.mode." + id);
        }
    }
}

package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.device.CommandReader;
import art.rehra.mineqtt.device.CommandResult;
import art.rehra.mineqtt.device.CommandSource;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.tabs.RgbLedStatusTab;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;
import java.util.Set;

/**
 * The Smart Lamp ({@link SmartLamp}): an 8-bit colour or colour temperature with a brightness, which also sets the
 * block light. Its state is in the {@code mineqtt} compound; v1 data is read on load and adopted when the device
 * attaches. The two item slots edit the Raw Link with v1 topic rules (design §6.9).
 * <p>
 * The server ticker follows slot edits and keeps the {@code light_level} block state in step; the client ticker only
 * spawns particles. Nothing here touches MQTT on the client.
 */
public class RgbLedBlockEntity extends BaseMqttBlockEntity implements DeviceHost {
    // A lamp that is off still shows its colour, dimmed to this share.
    private static final float OFF_TINT = 0.3f;
    private static final Set<String> LIGHT_KEYS = Set.of("state", "brightness", "color", "color_temp", "color_temp_kelvin");

    private final DeviceLink link = new DeviceLink(SmartLamp.TYPE);
    private LightState light = LightState.DEFAULT;

    public RgbLedBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.RGB_LED_BLOCK.get(), pos, blockState);
    }

    public LightState light() {
        return light;
    }

    public int getLightLevel() {
        return SmartLamp.lightLevel(light);
    }

    /**
     * The colour the block is tinted with: the colour scaled by the emitted light level, so the tint changes in the
     * same 16 steps as the light; dimmed while off.
     */
    public int tint() {
        float factor = light.on() ? getLightLevel() / 15.0f : OFF_TINT;
        return (scale(light.color().r(), factor) << 16) | (scale(light.color().g(), factor) << 8) | scale(light.color().b(), factor);
    }

    @Override
    public DeviceType deviceType() {
        return SmartLamp.TYPE;
    }

    @Override
    public DeviceLink deviceLink() {
        return link;
    }

    @Override
    public void writeState(StateWriter out) {
        out.put(SmartLamp.LIGHT, light)
                .put(SmartLamp.LIGHT_LEVEL, getLightLevel())
                .put(CommonFields.LOCATION, GlobalPos.of(level.dimension(), worldPosition));
    }

    @Override
    public CommandResult applyCommand(CommandReader in, CommandSource source) {
        if (in.keys().stream().noneMatch(LIGHT_KEYS::contains)) return CommandResult.rejected("not a light command: " + in.keys());
        LightState next = light.merge(in.json());
        if (next.equals(light)) return CommandResult.unchanged();
        light = next;
        setChanged();
        updateLightLevel();
        return CommandResult.applied();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveLamp(link, light, output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        light = loadLamp(link, input);
        // v1 synced the topic to the slots only in its ticker; the adopted link is the slots' topic, not an edit.
        if (link.awaitingAdoption()) topic = getCombinedTopic();
        // A new colour at the same light level changes no block state; the client redraws the tint itself.
        if (level != null && level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
    }

    @Override
    protected String getDefaultTopic() {
        return "";
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.mineqtt.rgb_led");
    }

    @Override
    public List<MqttTab> getTabs() {
        List<MqttTab> tabs = super.getTabs();
        tabs.add(new RgbLedStatusTab());
        return tabs;
    }

    @Override
    public int getContainerSize() {
        return 2;
    }

    /**
     * A lamp's keys besides the slots: the link with the light as its {@code state}, or the v1 keys while its v1 data
     * waits for adoption, so it is still v1 data when it loads again.
     */
    public static void saveLamp(DeviceLink link, LightState light, ValueOutput output) {
        if (link.awaitingAdoption()) {
            LegacyNbtV1.writeLamp(output, light);
        } else {
            link.save(output).store("state", SmartLamp.LIGHT.codec(), light);
        }
    }

    /** Loads a lamp's link and returns its light; v1 data, also a placed v1 item's, is kept for adoption. */
    public static LightState loadLamp(DeviceLink link, ValueInput input) {
        return link.load(input, LegacyNbtV1.hasLampKeys(input))
                .map(data -> data.read("state", SmartLamp.LIGHT.codec()).orElse(LightState.DEFAULT))
                .orElseGet(() -> LegacyNbtV1.lampState(input));
    }

    // The block light follows the brightness; changing it keeps this block entity.
    private void updateLightLevel() {
        if (level == null) return;
        BlockState state = getBlockState();
        int lightLevel = getLightLevel();
        if (state.getBlock() instanceof RgbLedBlock && state.getValue(RgbLedBlock.LIGHT_LEVEL) != lightLevel) {
            level.setBlock(worldPosition, state.setValue(RgbLedBlock.LIGHT_LEVEL, lightLevel), Block.UPDATE_ALL);
        } else {
            // Same light level: the clients still need the new tint.
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private static int scale(int channel, float factor) {
        return Math.round(channel * factor);
    }

    /** Slot edits and the block light. */
    public static class ServerTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof RgbLedBlockEntity lamp) || !(level instanceof ServerLevel serverLevel)) return;
            lamp.followSlots(serverLevel, lamp.link);
            // v1 lamps derived the light level from the colour; the brightness decides it now.
            if (state.getValue(RgbLedBlock.LIGHT_LEVEL) != lamp.getLightLevel()) lamp.updateLightLevel();
        }
    }

    /** Coloured particles around a lit bulb; never MQTT. */
    public static class ClientTicker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
            if (!(blockEntity instanceof RgbLedBlockEntity lamp) || lamp.getLightLevel() == 0) return;
            // Every 10 ticks (0.5 seconds), unless the colour is black.
            int color = lamp.tint();
            if (level.getGameTime() % 10 != 0 || color == 0) return;
            double centerX = pos.getX() + 0.5;
            double centerY = pos.getY() + 0.6;
            double centerZ = pos.getZ() + 0.5;
            int particleCount = level.getRandom().nextInt(2) + 2;
            for (int i = 0; i < particleCount; i++) {
                double offsetX = (level.getRandom().nextDouble() - 0.5) * 0.3;
                double offsetY = (level.getRandom().nextDouble() - 0.5) * 0.3;
                double offsetZ = (level.getRandom().nextDouble() - 0.5) * 0.3;
                // Small upward velocity for a glow effect.
                double velocityY = level.getRandom().nextDouble() * 0.02;
                level.addParticle(new DustParticleOptions(color, 0.8f), centerX + offsetX, centerY + offsetY, centerZ + offsetZ, 0, velocityY, 0);
            }
        }
    }
}

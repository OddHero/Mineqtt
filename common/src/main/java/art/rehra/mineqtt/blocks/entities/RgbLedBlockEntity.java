package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.ui.RgbLedBlockMenu;
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
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        this.red = input.getIntOr("Red",0);
        this.green = input.getIntOr("Green",0);
        this.blue = input.getIntOr("Blue",0);
        this.blue = input.getIntOr("Blue",0);
        this.lit = input.getByteOr("Lit", (byte) 0) != 0;
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
        return this.saveWithFullMetadata(registries);
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

    public static class Ticker<T extends BlockEntity> implements BlockEntityTicker<T> {
        @Override
        public void tick(Level level, BlockPos blockPos, BlockState blockState, T blockEntity) {
            if (blockEntity instanceof RgbLedBlockEntity rgbLedBlockEntity) {
                String newCombinedTopic = rgbLedBlockEntity.getCombinedTopic();

                boolean topicChanged = !newCombinedTopic.equals(rgbLedBlockEntity.topic);

                if (topicChanged) {
                    rgbLedBlockEntity.setTopic(newCombinedTopic);

                    // Update subscription
                    rgbLedBlockEntity.updateSubscription(newCombinedTopic);

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

                        MineQTT.LOGGER.info("RGB LED disabled - no base path item in first slot");
                    }
                }
            }
        }
    }
}


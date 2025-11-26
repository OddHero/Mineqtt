package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.mqtt.SubscriptionManager;
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

    public RgbLedBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.RGB_LED_BLOCK.get(), pos, blockState);
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
        // Parse RGB color from message
        // Expected format: "rgb(255,128,64)" or "#FF8040" or "255,128,64"
        int[] rgb = parseRgbFromMessage(message);

        if (rgb != null) {
            BlockState currentState = this.level.getBlockState(this.worldPosition);
            if (currentState.getBlock() instanceof RgbLedBlock) {
                // Convert 0-255 to 0-15 for redstone signal
                int red = (rgb[0] * 15) / 255;
                int green = (rgb[1] * 15) / 255;
                int blue = (rgb[2] * 15) / 255;

                BlockState newState = currentState
                        .setValue(RgbLedBlock.RED, red)
                        .setValue(RgbLedBlock.GREEN, green)
                        .setValue(RgbLedBlock.BLUE, blue);

                this.level.setBlock(this.worldPosition, newState, Block.UPDATE_ALL);
                MineQTT.LOGGER.info("RGB LED updated: R=" + red + " G=" + green + " B=" + blue);
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

                    // If disabled, reset to black (0, 0, 0)
                    if (!rgbLedBlockEntity.isEnabled()) {
                        BlockState currentState = level.getBlockState(blockPos);
                        if (currentState.getBlock() instanceof RgbLedBlock) {
                            BlockState newState = currentState
                                    .setValue(RgbLedBlock.RED, 0)
                                    .setValue(RgbLedBlock.GREEN, 0)
                                    .setValue(RgbLedBlock.BLUE, 0);
                            level.setBlock(blockPos, newState, Block.UPDATE_ALL);
                            MineQTT.LOGGER.info("RGB LED disabled - no base path item in first slot");
                        }
                    }
                }
            }
        }
    }
}


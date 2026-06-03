package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.RedstonePublisherBlock;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Status + payload display for blocks publishing ON/OFF on a redstone signal.
 */
public class PublisherValuesTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;

    public PublisherValuesTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        if (!(be instanceof BaseMqttBlockEntity mqtt)) return;

        int x = guiLeft + 8;

        // Slot labels — centered above their 16px slots (slots at y=36..52).
        GuiText.drawCentered(g, "ON", guiLeft + 61, guiTop + 26, 24, 0xFF555555);
        GuiText.drawCentered(g, "OFF", guiLeft + 97, guiTop + 26, 24, 0xFF555555);

        // Status line — single row above the slot labels.
        boolean isPowered = false;
        try {
            var state = level.getBlockState(menu.blockPos);
            if (state.hasProperty(RedstonePublisherBlock.POWERED)) {
                isPowered = state.getValue(RedstonePublisherBlock.POWERED);
            }
        } catch (Throwable ignored) {
        }

        boolean enabled = mqtt.isEnabled();
        String status = enabled ? (isPowered ? "Publishing" : "Ready") : "Disabled";
        int color = enabled ? (isPowered ? 0xFF0088FF : 0xFF00AA00) : 0xFF666666;
        GuiText.drawTruncated(g, "Status: " + status, x, guiTop + 18, CONTENT_W, color, mouseX, mouseY);

        if (enabled) {
            ItemStack onStack = mqtt.getItem(2);
            ItemStack offStack = mqtt.getItem(3);
            String onValue = onStack.isEmpty() ? "true" : BaseMqttBlockEntity.parseItemStackTopic(onStack);
            String offValue = offStack.isEmpty() ? "false" : BaseMqttBlockEntity.parseItemStackTopic(offStack);
            String signal = isPowered ? "ON → '" + onValue + "'" : "OFF → '" + offValue + "'";
            // Render the signal + payload lines BELOW the slots (y=52) so they
            // never overlap the slot labels or the slot icons themselves.
            int by = guiTop + 58;
            GuiText.drawAutoScaled(g, signal, x, by, CONTENT_W, 10, 0xFF888888, mouseX, mouseY);
            GuiText.drawAutoScaled(g, "ON  payload: '" + onValue + "'", x, by + 14, CONTENT_W, 10, 0xFF555555, mouseX, mouseY);
            GuiText.drawAutoScaled(g, "OFF payload: '" + offValue + "'", x, by + 26, CONTENT_W, 10, 0xFF555555, mouseX, mouseY);
        }
    }
}

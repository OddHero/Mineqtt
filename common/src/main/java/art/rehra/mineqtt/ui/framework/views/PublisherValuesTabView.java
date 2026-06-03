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
        int y = guiTop + 18;

        // Slot labels — centered above their 16px slots.
        GuiText.drawCentered(g, "ON", guiLeft + 62, guiTop + 26, 16, 0xFF555555);
        GuiText.drawCentered(g, "OFF", guiLeft + 98, guiTop + 26, 16, 0xFF555555);

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
        GuiText.drawTruncated(g, "Status: " + status, x, y, CONTENT_W, color, mouseX, mouseY);

        if (enabled) {
            ItemStack onStack = mqtt.getItem(2);
            ItemStack offStack = mqtt.getItem(3);
            String onValue = onStack.isEmpty() ? "true" : BaseMqttBlockEntity.parseItemStackTopic(onStack);
            String offValue = offStack.isEmpty() ? "false" : BaseMqttBlockEntity.parseItemStackTopic(offStack);
            String signal = isPowered ? "ON → '" + onValue + "'" : "OFF → '" + offValue + "'";
            // Auto-scale the dynamic payload lines so long values shrink before truncating.
            GuiText.drawAutoScaled(g, signal, x, y + 12, CONTENT_W, 10, 0xFF888888, mouseX, mouseY);
            GuiText.drawAutoScaled(g, "ON  payload: '" + onValue + "'", x, y + 60, CONTENT_W, 10, 0xFF555555, mouseX, mouseY);
            GuiText.drawAutoScaled(g, "OFF payload: '" + offValue + "'", x, y + 72, CONTENT_W, 10, 0xFF555555, mouseX, mouseY);
        }
    }
}

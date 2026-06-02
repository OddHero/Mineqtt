package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.RedstonePublisherBlock;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Status + payload display for blocks publishing ON/OFF on a redstone signal.
 */
public class PublisherValuesTabView implements MqttTabView {

    private final TabbedMqttScreen screen;

    public PublisherValuesTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        if (!(be instanceof BaseMqttBlockEntity mqtt)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;
        g.drawString(font, "ON value", guiLeft + 60, guiTop + 26, 0xFF555555, false);
        g.drawString(font, "OFF value", guiLeft + 94, guiTop + 26, 0xFF555555, false);

        // Read powered state if available
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
        g.drawString(font, "Status: " + status, x, y, color, false);

        if (enabled) {
            ItemStack onStack = mqtt.getItem(2);
            ItemStack offStack = mqtt.getItem(3);
            String onValue = onStack.isEmpty() ? "true" : BaseMqttBlockEntity.parseItemStackTopic(onStack);
            String offValue = offStack.isEmpty() ? "false" : BaseMqttBlockEntity.parseItemStackTopic(offStack);
            String signal = isPowered ? "ON → '" + onValue + "'" : "OFF → '" + offValue + "'";
            g.drawString(font, signal, x, y + 12, 0xFF888888, false);
            g.drawString(font, "ON  payload: '" + onValue + "'", x, y + 60, 0xFF555555, false);
            g.drawString(font, "OFF payload: '" + offValue + "'", x, y + 72, 0xFF555555, false);
        }
    }
}

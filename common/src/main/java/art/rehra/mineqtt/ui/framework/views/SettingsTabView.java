package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Renders topic configuration info above the two frequency slots.
 */
public class SettingsTabView implements MqttTabView {

    /**
     * Inner usable width of the GUI panel for label/text rendering.
     */
    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16; // 8px padding each side
    private final TabbedMqttScreen screen;

    public SettingsTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof BaseMqttBlockEntity mqtt)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;
        String topic = mqtt.getCombinedTopic();
        boolean enabled = mqtt.isEnabled();

        if (enabled && !topic.isEmpty()) {
            GuiText.drawTruncated(g, "Topic:", x, y, CONTENT_W, 0xFF555555);
            y += 10;
            // Auto-scale the topic so long paths shrink before being truncated.
            // Reserve ~2 lines of vertical space (textbox area).
            GuiText.drawAutoScaled(g, topic, x + 4, y, CONTENT_W - 4, 20, 0xFF0088FF, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, "No topic configured", x, y, CONTENT_W, 0xFF666666, mouseX, mouseY);
            y += 10;
            GuiText.drawTruncated(g, "Place item in left slot to enable", x, y, CONTENT_W, 0xFF888888, mouseX, mouseY);
        }

        // Slot labels — centered above each 16px slot.
        GuiText.drawCentered(g, "Base", guiLeft + 62, guiTop + 26, 16, 0xFF555555);
        GuiText.drawCentered(g, "Sub", guiLeft + 98, guiTop + 26, 16, 0xFF555555);
    }
}

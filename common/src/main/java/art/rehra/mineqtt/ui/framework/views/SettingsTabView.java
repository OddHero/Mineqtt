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
        String topic;
        boolean enabled;

        if (menu.blockPos != null) {
            var be = menu.player.level().getBlockEntity(menu.blockPos);
            if (!(be instanceof BaseMqttBlockEntity mqtt)) return;
            topic = mqtt.getCombinedTopic();
            enabled = mqtt.isEnabled();
        } else {
            // Cyberdeck (item-based)
            var baseStack = menu.container.getItem(0);
            var subStack = menu.container.getItem(1);
            String base = BaseMqttBlockEntity.parseItemStackTopic(baseStack);
            String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
            topic = sub.isEmpty() ? base : base + "/" + sub;
            enabled = !baseStack.isEmpty();
        }

        int x = guiLeft + 8;
        // Slot labels — centered above each 16px slot (slots at y=36..52).
        // Use 18px label width (>16) so localized labels are slightly less likely
        // to truncate; the labels are short fixed strings.
        GuiText.drawCentered(g, "Base", guiLeft + 61, guiTop + 26, 24, 0xFF555555);
        GuiText.drawCentered(g, "Sub", guiLeft + 97, guiTop + 26, 24, 0xFF555555);

        // Topic info — rendered BELOW the slots so it never overlaps them.
        // Available vertical band: y=56 .. PLAYER_INV_Y(110) - 1.
        int topicY = guiTop + 58;

        if (enabled && !topic.isEmpty()) {
            GuiText.drawTruncated(g, "Topic:", x, topicY, CONTENT_W, 0xFF555555);
            // Auto-scale the topic so long paths shrink before being truncated.
            // Reserve a generous textbox area below the "Topic:" label.
            GuiText.drawAutoScaled(g, topic, x + 4, topicY + 10, CONTENT_W - 4, 30, 0xFF0088FF, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, "No topic configured", x, topicY, CONTENT_W, 0xFF666666, mouseX, mouseY);
            GuiText.drawTruncated(g, "Place item in left slot to enable", x, topicY + 10, CONTENT_W, 0xFF888888, mouseX, mouseY);
        }
    }
}

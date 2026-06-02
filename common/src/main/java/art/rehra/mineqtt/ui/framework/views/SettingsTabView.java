package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Renders topic configuration info above the two frequency slots.
 */
public class SettingsTabView implements MqttTabView {

    private final TabbedMqttScreen screen;

    public SettingsTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof BaseMqttBlockEntity mqtt)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;
        String topic = mqtt.getCombinedTopic();
        boolean enabled = mqtt.isEnabled();

        if (enabled && !topic.isEmpty()) {
            g.drawString(font, "Topic:", x, y, 0xFF555555, false);
            y += 10;
            List<FormattedCharSequence> lines = font.split(Component.literal(topic), 160);
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x + 4, y, 0xFF0088FF, false);
                y += 10;
            }
        } else {
            g.drawString(font, "No topic configured", x, y, 0xFF666666, false);
            y += 10;
            g.drawString(font, "Place item in left slot to enable", x, y, 0xFF888888, false);
        }

        // Slot labels above the two frequency slots
        g.drawString(font, "Base", guiLeft + 62, guiTop + 26, 0xFF555555, false);
        g.drawString(font, "Sub", guiLeft + 98, guiTop + 26, 0xFF555555, false);
    }
}

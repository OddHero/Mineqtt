package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Motion sensor status + filter description.
 */
public class MotionSensorTabView implements MqttTabView {

    private final TabbedMqttScreen screen;

    public MotionSensorTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof MotionSensorBlockEntity sensor)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;
        boolean detected = sensor.isMotionDetected();
        g.drawString(font, detected ? "Motion: YES" : "Motion: NO", x, y, detected ? 0xFF00FF00 : 0xFF666666, false);
        int count = Math.max(0, sensor.getLastCount());
        String c = "Mobs: " + count;
        g.drawString(font, c, guiLeft + 168 - font.width(c), y, 0xFF555555, false);

        g.drawString(font, "Spawn-egg filters (empty = all):", x, guiTop + 56, 0xFF555555, false);
    }
}

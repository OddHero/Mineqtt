package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Motion sensor status + filter description.
 */
public class MotionSensorTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;

    public MotionSensorTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof MotionSensorBlockEntity sensor)) return;

        int leftX = guiLeft + 8;
        int rightX = guiLeft + TabbedMqttMenu.GUI_WIDTH - 8; // right edge of content area
        int y = guiTop + 18;

        boolean detected = sensor.isMotionDetected();
        // Reserve ~half the width for each — left for motion status, right for the mob count.
        int half = CONTENT_W / 2;
        GuiText.drawTruncated(g, detected ? "Motion: YES" : "Motion: NO",
                leftX, y, half, detected ? 0xFF00AA00 : 0xFF666666, mouseX, mouseY);
        int count = Math.max(0, sensor.getLastCount());
        GuiText.drawRight(g, "Mobs: " + count, rightX, y, half, 0xFF555555, mouseX, mouseY);

        // Filter description rendered below the 5 slot row (slots at y=36, h=16).
        GuiText.drawTruncated(g, "Spawn-egg filters (empty = all):",
                leftX, guiTop + 56, CONTENT_W, 0xFF555555, mouseX, mouseY);
    }
}

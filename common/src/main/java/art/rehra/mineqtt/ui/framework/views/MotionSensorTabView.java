package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.MotionSensorBlockEntity;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Presence sensor status, its detection settings (set from Home Assistant or MQTT in M1) and the filter description.
 */
public class MotionSensorTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;

    public MotionSensorTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof MotionSensorBlockEntity sensor)) return;
        Presence presence = sensor.getPresence();

        int leftX = guiLeft + 8;
        int rightX = guiLeft + TabbedMqttMenu.GUI_WIDTH - 8; // right edge of content area
        int y = guiTop + 18;

        // Reserve ~half the width for each — left for occupancy, right for the count.
        int half = CONTENT_W / 2;
        GuiText.drawTruncated(g, presence.occupancy() ? "Occupied: YES" : "Occupied: NO",
                leftX, y, half, presence.occupancy() ? 0xFF00AA00 : 0xFF666666, mouseX, mouseY);
        GuiText.drawRight(g, "Count: " + presence.count(), rightX, y, half, 0xFF555555, mouseX, mouseY);

        // Filter description and settings rendered below the 5 slot row (slots at y=36, h=16).
        GuiText.drawTruncated(g, "Spawn-egg filters for mobs (empty = all)",
                leftX, guiTop + 56, CONTENT_W, 0xFF555555, mouseX, mouseY);
        GuiText.drawAutoScaled(g, "Detects " + presence.detect().getSerializedName() + " | range " + presence.range()
                + " | clear delay " + presence.clearDelay() + " s", leftX, guiTop + 70, CONTENT_W, 10, 0xFF888888, mouseX, mouseY);
    }
}

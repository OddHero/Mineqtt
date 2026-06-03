package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphics;

/**
 * RGB LED status — shows lit state, RGB values, and the current colour swatch.
 */
public class RgbLedStatusTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;

    public RgbLedStatusTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof RgbLedBlockEntity led)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;

        boolean lit = led.isLit();
        GuiText.drawTruncated(g, lit ? "State: ON" : "State: OFF",
                x, y, CONTENT_W, lit ? 0xFF00AA00 : 0xFF666666, mouseX, mouseY);
        y += 12;

        int r255 = (led.getRed() * 255) / 15;
        int g255 = (led.getGreen() * 255) / 15;
        int b255 = (led.getBlue() * 255) / 15;
        GuiText.drawAutoScaled(g, String.format("R: %d  G: %d  B: %d", r255, g255, b255),
                x, y, CONTENT_W, 10, 0xFF333333, mouseX, mouseY);
        y += 14;

        int color = 0xFF000000 | (r255 << 16) | (g255 << 8) | b255;
        g.fill(x, y, x + CONTENT_W, y + 22, color);
        g.renderOutline(x, y, CONTENT_W, 22, 0xFF333333);
    }
}

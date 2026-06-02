package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * RGB LED status — shows lit state, RGB values, and the current colour swatch.
 */
public class RgbLedStatusTabView implements MqttTabView {

    private final TabbedMqttScreen screen;

    public RgbLedStatusTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof RgbLedBlockEntity led)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;

        boolean lit = led.isLit();
        g.drawString(font, lit ? "State: ON" : "State: OFF", x, y, lit ? 0xFF00AA00 : 0xFF666666, false);
        y += 12;

        int r255 = (led.getRed() * 255) / 15;
        int g255 = (led.getGreen() * 255) / 15;
        int b255 = (led.getBlue() * 255) / 15;
        g.drawString(font, String.format("R: %d  G: %d  B: %d", r255, g255, b255), x, y, 0xFF333333, false);
        y += 14;

        int color = 0xFF000000 | (r255 << 16) | (g255 << 8) | b255;
        g.fill(x, y, x + 160, y + 22, color);
        g.renderOutline(x, y, 160, 22, 0xFF333333);
    }
}

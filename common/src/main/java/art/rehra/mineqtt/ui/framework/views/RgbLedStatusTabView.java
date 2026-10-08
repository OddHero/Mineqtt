package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.RgbLedBlockEntity;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Smart Lamp status — shows the on state, brightness, colour (or colour temperature) and the current colour swatch.
 */
public class RgbLedStatusTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;

    public RgbLedStatusTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var be = menu.player.level().getBlockEntity(menu.blockPos);
        if (!(be instanceof RgbLedBlockEntity led)) return;
        ValueCodecs.LightState light = led.light();

        int x = guiLeft + 8;
        int y = guiTop + 18;

        GuiText.drawTruncated(g, (light.on() ? "State: ON" : "State: OFF") + "  Brightness: " + light.brightness() + "  Light: " + led.getLightLevel(),
                x, y, CONTENT_W, light.on() ? 0xFF00AA00 : 0xFF666666, mouseX, mouseY);
        y += 12;

        String color = light.kelvin() != null
                ? light.kelvin() + " K"
                : String.format("R: %d  G: %d  B: %d", light.color().r(), light.color().g(), light.color().b());
        GuiText.drawAutoScaled(g, color, x, y, CONTENT_W, 10, 0xFF333333, mouseX, mouseY);
        y += 14;

        g.fill(x, y, x + CONTENT_W, y + 22, 0xFF000000 | light.color().packed());
        g.outline(x, y, CONTENT_W, 22, 0xFF333333);
    }
}

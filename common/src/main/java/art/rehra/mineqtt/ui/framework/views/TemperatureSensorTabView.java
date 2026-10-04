package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.TemperatureSensorBlockEntity;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tab view for the Temperature Sensor: displays real-time temperature readout,
 * visual temperature scale, and MQTT broadcast status.
 */
public class TemperatureSensorTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;

    public TemperatureSensorTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return Collections.emptyList();
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        if (!(be instanceof TemperatureSensorBlockEntity sensor)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;

        double tempC = sensor.getTemperature();
        double tempF = (tempC * 9.0 / 5.0) + 32.0;
        boolean enabled = sensor.isEnabled();
        String topic = sensor.getCombinedTopic();
        int intervalTicks = sensor.getUpdateIntervalTicks();
        int intervalSeconds = Math.max(1, intervalTicks / 20);

        // Header / Label
        GuiText.drawTruncated(g, Component.translatable("mineqtt.temperature_sensor.current"), x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;

        // Large / highlighted temperature readout
        String tempText = String.format(Locale.ROOT, "%.1f \u00B0C   (%.1f \u00B0F)", tempC, tempF);
        int tempColor = getTemperatureColor(tempC);
        GuiText.drawAutoScaled(g, tempText, x, y, CONTENT_W, 14, tempColor, mouseX, mouseY);
        y += 16;

        // Visual thermometer scale / bar (-40°C to 100°C)
        int barW = CONTENT_W - 8;
        int barH = 8;
        int barX = x + 4;
        g.fill(barX, y, barX + barW, y + barH, 0xFF222222);

        double normalized = Math.max(0.0, Math.min(1.0, (tempC - (-40.0)) / 140.0));
        int filledW = (int) (barW * normalized);
        if (filledW > 0) {
            g.fill(barX, y, barX + filledW, y + barH, tempColor);
        }
        g.outline(barX, y, barW, barH, 0xFF444444);
        y += barH + 12;

        // MQTT Topic Info
        GuiText.drawTruncated(g, Component.translatable("mineqtt.tab.settings"), x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;

        if (enabled && !topic.isEmpty()) {
            GuiText.drawTruncated(g, "Topic: " + topic, x, y, CONTENT_W, 0xFF00AA00, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.temperature_sensor.disabled"), x, y, CONTENT_W, 0xFF888888, mouseX, mouseY);
        }
        y += 14;

        // Publish interval & status
        String intervalStr = String.format(Locale.ROOT, "Interval: %d ticks (%ds)", intervalTicks, intervalSeconds);
        GuiText.drawTruncated(g, intervalStr, x, y, CONTENT_W, 0xFF888888, mouseX, mouseY);
        y += 12;

        String statusStr = enabled ? "Broadcasting live readings" : "Idle (configure topic in MQTT Settings)";
        int statusColor = enabled ? 0xFF55FF55 : 0xFF666666;
        GuiText.drawTruncated(g, statusStr, x, y, CONTENT_W, statusColor, mouseX, mouseY);
    }

    private int getTemperatureColor(double tempC) {
        if (tempC < 0) {
            return 0xFF55AAFF; // Freezing blue
        } else if (tempC < 15) {
            return 0xFF55FFFF; // Cool cyan
        } else if (tempC < 30) {
            return 0xFF55FF55; // Comfortable green
        } else if (tempC < 50) {
            return 0xFFFFAA00; // Warm orange
        } else {
            return 0xFFFF5555; // Hot red
        }
    }
}

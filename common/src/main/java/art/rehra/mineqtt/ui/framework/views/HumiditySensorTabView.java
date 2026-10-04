package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.HumiditySensorBlockEntity;
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
 * Tab view for the Humidity Sensor: displays real-time relative humidity readout,
 * moisture level gauge, and MQTT broadcast status.
 */
public class HumiditySensorTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;

    public HumiditySensorTabView(TabbedMqttScreen screen) {
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
        if (!(be instanceof HumiditySensorBlockEntity sensor)) return;

        int x = guiLeft + 8;
        int y = guiTop + 18;

        double humidity = sensor.getHumidity();
        boolean enabled = sensor.isEnabled();
        String topic = sensor.getCombinedTopic();
        int intervalTicks = sensor.getUpdateIntervalTicks();
        int intervalSeconds = Math.max(1, intervalTicks / 20);

        // Header / Label
        GuiText.drawTruncated(g, Component.translatable("mineqtt.humidity_sensor.current"), x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;

        // Large / highlighted humidity readout
        String humText = String.format(Locale.ROOT, "%.1f %%  RH", humidity);
        int humColor = getHumidityColor(humidity);
        GuiText.drawAutoScaled(g, humText, x, y, CONTENT_W, 14, humColor, mouseX, mouseY);
        y += 16;

        // Visual moisture level gauge / bar (0% to 100%)
        int barW = CONTENT_W - 8;
        int barH = 8;
        int barX = x + 4;
        g.fill(barX, y, barX + barW, y + barH, 0xFF222222);

        double normalized = Math.max(0.0, Math.min(1.0, humidity / 100.0));
        int filledW = (int) (barW * normalized);
        if (filledW > 0) {
            g.fill(barX, y, barX + filledW, y + barH, humColor);
        }
        g.outline(barX, y, barW, barH, 0xFF444444);
        y += barH + 6;

        // Condition indicator text
        String conditionText = getConditionText(humidity);
        GuiText.drawTruncated(g, conditionText, x, y, CONTENT_W, 0xFF888888, mouseX, mouseY);
        y += 14;

        // MQTT Topic Info
        GuiText.drawTruncated(g, Component.translatable("mineqtt.tab.settings"), x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;

        if (enabled && !topic.isEmpty()) {
            GuiText.drawTruncated(g, "Topic: " + topic, x, y, CONTENT_W, 0xFF00AA00, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.humidity_sensor.disabled"), x, y, CONTENT_W, 0xFF888888, mouseX, mouseY);
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

    private int getHumidityColor(double humidity) {
        if (humidity < 30.0) {
            return 0xFFFFAA55; // Dry / low humidity (amber)
        } else if (humidity <= 65.0) {
            return 0xFF55CCFF; // Comfortable (cyan/sky blue)
        } else {
            return 0xFF3388EE; // Humid (deep blue)
        }
    }

    private String getConditionText(double humidity) {
        if (humidity < 30.0) {
            return "Moisture: Low (Dry)";
        } else if (humidity <= 65.0) {
            return "Moisture: Moderate (Optimal)";
        } else {
            return "Moisture: High (Humid / Damp)";
        }
    }
}

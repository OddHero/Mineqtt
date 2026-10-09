package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.AlarmClockBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab view for the Alarm Clock: simple single-alarm UI with hour/minute inputs,
 * a set button, and the current Minecraft time display.
 */
public class AlarmClockTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();

    private EditBox hourInput;
    private EditBox minuteInput;
    private Button setButton;
    private Button clearButton;

    private String validationError = "";

    public AlarmClockTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        int inputX = guiLeft + 8;
        int inputY = guiTop + 18;
        int inputW = 40;
        int inputH = 14;

        // Read current alarm from block entity to pre-fill inputs
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        String initialHour = "09";
        String initialMinute = "00";
        if (be instanceof AlarmClockBlockEntity alarmBe && alarmBe.hasAlarm()) {
            String formatted = AlarmClockBlockEntity.formatAlarmTime(alarmBe.getAlarmTime());
            String[] parts = formatted.split(":");
            if (parts.length == 2) {
                initialHour = parts[0];
                initialMinute = parts[1];
            }
        }

        // Hour input
        hourInput = new EditBox(Minecraft.getInstance().font, inputX, inputY, inputW, inputH, Component.empty());
        hourInput.setHint(Component.literal("HH"));
        hourInput.setMaxLength(2);
        hourInput.setValue(initialHour);
        hourInput.setResponder(text -> validateTime());
        widgets.add(hourInput);

        // Minute input
        minuteInput = new EditBox(Minecraft.getInstance().font, inputX + inputW + 6, inputY, inputW, inputH, Component.empty());
        minuteInput.setHint(Component.literal("MM"));
        minuteInput.setMaxLength(2);
        minuteInput.setValue(initialMinute);
        minuteInput.setResponder(text -> validateTime());
        widgets.add(minuteInput);

        // Set and clear buttons share the rest of the row, up to the panel's right padding
        int buttonsX = inputX + inputW * 2 + 6 + 4;
        int right = inputX + CONTENT_W;
        int buttonW = (right - buttonsX - 2) / 2;
        setButton = Button.builder(Component.translatable("mineqtt.alarm_clock.apply"), b -> setAlarm())
                .bounds(buttonsX, inputY, buttonW, inputH)
                .build();
        widgets.add(setButton);

        clearButton = Button.builder(Component.translatable("mineqtt.alarm_clock.clear"), b -> clearAlarm())
                .bounds(right - buttonW, inputY, buttonW, inputH)
                .build();
        widgets.add(clearButton);

        this.validationError = "";
    }

    private void validateTime() {
        this.validationError = "";
        try {
            String h = hourInput.getValue().trim();
            String m = minuteInput.getValue().trim();
            if (h.isEmpty() || m.isEmpty()) return;
            int hours = Integer.parseInt(h);
            int minutes = Integer.parseInt(m);
            if (hours < 0 || hours > 23) {
                this.validationError = "mineqtt.alarm_clock.error.hours";
            } else if (minutes < 0 || minutes > 59) {
                this.validationError = "mineqtt.alarm_clock.error.minutes";
            }
        } catch (NumberFormatException e) {
            this.validationError = "mineqtt.alarm_clock.error.invalid";
        }
    }

    private void setAlarm() {
        validateTime();
        if (!this.validationError.isEmpty()) return;

        String h = hourInput.getValue().trim();
        String m = minuteInput.getValue().trim();
        if (h.isEmpty() || m.isEmpty()) {
            this.validationError = "mineqtt.alarm_clock.error.empty";
            return;
        }

        try {
            int hours = Integer.parseInt(h);
            int minutes = Integer.parseInt(m);
            String timeStr = String.format("%02d:%02d", hours, minutes);
            int minecraftTicks = AlarmClockBlockEntity.parseAlarmTime(timeStr);

            NetworkManager.sendToServer(new MineqttNetworking.SetSingleAlarmPayload(
                    screen.getMenu().blockPos, minecraftTicks));
            this.validationError = "";
        } catch (NumberFormatException e) {
            this.validationError = "mineqtt.alarm_clock.error.invalid";
        } catch (IllegalArgumentException e) {
            this.validationError = "mineqtt.alarm_clock.error.invalid";
        }
    }

    private void clearAlarm() {
        NetworkManager.sendToServer(new MineqttNetworking.SetSingleAlarmPayload(
                screen.getMenu().blockPos, -1));
    }

    @Override
    public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 40; // Below the input fields

        // Draw colon separator between hour and minute inputs
        g.text(Minecraft.getInstance().font, ":", guiLeft + 8 + 40 + 3, guiTop + 20, 0xFFAAAAAA, false);

        // Validation error
        if (!this.validationError.isEmpty()) {
            Component errorComp = Component.translatable("mineqtt.alarm_clock.error")
                    .append(": ")
                    .append(Component.translatable(this.validationError));
            GuiText.drawTruncated(g, errorComp, x, y, CONTENT_W, 0xFFFF5555, mouseX, mouseY);
            y += 12;
        }

        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        try {
            long dayTime = AlarmClockBlockEntity.getWorldDayTime(level);
            int timeOfDay = (int) (dayTime % 24000);
            if (timeOfDay < 0) {
                timeOfDay += 24000;
            }
            String timeStr = AlarmClockBlockEntity.formatAlarmTime(timeOfDay);
            Component timeComp = Component.translatable("mineqtt.alarm_clock.time")
                    .append(": " + timeStr + " (tick " + timeOfDay + ")");
            GuiText.drawTruncated(g, timeComp, x, y, CONTENT_W, 0xFFAAAAAA, mouseX, mouseY);
            y += 12;

            // Display current alarm and countdown
            var be = level.getBlockEntity(menu.blockPos);
            if (be instanceof AlarmClockBlockEntity alarmBe) {
                if (alarmBe.hasAlarm()) {
                    String alarmTimeStr = AlarmClockBlockEntity.formatAlarmTime(alarmBe.getAlarmTime());
                    Component alarmComp = Component.translatable("mineqtt.alarm_clock.alarms")
                            .append(": " + alarmTimeStr + " (tick " + alarmBe.getAlarmTime() + ")");
                    GuiText.drawTruncated(g, alarmComp, x, y, CONTENT_W, 0xFF55FF55, mouseX, mouseY);
                    y += 12;

                    long remainingTicks = alarmBe.getRemainingTicks(level);
                    String remainingStr = formatRemaining(remainingTicks);
                    Component countdownComp = Component.translatable("mineqtt.alarm_clock.countdown")
                            .append(": " + remainingStr + " (" + remainingTicks + " ticks)");
                    GuiText.drawTruncated(g, countdownComp, x, y, CONTENT_W, 0xFF888888, mouseX, mouseY);
                    y += 12;
                } else {
                    Component noAlarmsComp = Component.translatable("mineqtt.alarm_clock.no_alarms");
                    GuiText.drawTruncated(g, noAlarmsComp, x, y, CONTENT_W, 0xFF666666, mouseX, mouseY);
                    y += 12;
                }

                // Power status
                var state = level.getBlockState(menu.blockPos);
                if (state.hasProperty(art.rehra.mineqtt.blocks.AlarmClockBlock.POWER)) {
                    int power = state.getValue(art.rehra.mineqtt.blocks.AlarmClockBlock.POWER);
                    String powerKey = power > 0 ? "mineqtt.alarm_clock.powered" : "mineqtt.alarm_clock.unpowered";
                    int color = power > 0 ? 0xFF55FF55 : 0xFF666666;
                    Component statusComp = Component.translatable("mineqtt.alarm_clock.status")
                            .append(": ")
                            .append(Component.translatable(powerKey));
                    GuiText.drawTruncated(g, statusComp, x, y, CONTENT_W, color, mouseX, mouseY);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Formats remaining ticks to a human-readable countdown string.
     */
    private String formatRemaining(long ticks) {
        long seconds = ticks / 20;
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            long remainingMinutes = minutes % 60;
            return String.format("%dh %dm %ds", hours, remainingMinutes, remainingSeconds);
        }
        return String.format("%dm %ds", minutes, remainingSeconds);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hourInput != null && hourInput.isVisible()) {
            hourInput.setFocused(hourInput.isMouseOver(mouseX, mouseY));
        }
        if (minuteInput != null && minuteInput.isVisible()) {
            minuteInput.setFocused(minuteInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

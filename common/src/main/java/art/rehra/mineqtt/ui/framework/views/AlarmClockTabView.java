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
 * Tab view for the Alarm Clock: provides hour/minute input fields,
 * an add button, a scrollable list of alarms with remove/toggle buttons,
 * and the current Minecraft time display.
 */
public class AlarmClockTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();

    private EditBox hourInput;
    private EditBox minuteInput;
    private Button addButton;
    private Button applyButton;

    private String validationError = "";
    private int scrollOffset = 0;

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

        // Hour input
        hourInput = new EditBox(Minecraft.getInstance().font, inputX, inputY, inputW, inputH, Component.empty());
        hourInput.setHint(Component.literal("HH"));
        hourInput.setMaxLength(2);
        hourInput.setValue("09");
        hourInput.setResponder(text -> validateTime());
        widgets.add(hourInput);

        // Colon separator drawn in renderContent

        // Minute input
        minuteInput = new EditBox(Minecraft.getInstance().font, inputX + inputW + 6, inputY, inputW, inputH, Component.empty());
        minuteInput.setHint(Component.literal("MM"));
        minuteInput.setMaxLength(2);
        minuteInput.setValue("00");
        minuteInput.setResponder(text -> validateTime());
        widgets.add(minuteInput);

        // Add alarm button
        addButton = Button.builder(Component.translatable("mineqtt.alarm_clock.add"), b -> addAlarm())
                .bounds(inputX + (inputW + 6) * 2 + 4, inputY, 50, inputH)
                .build();
        widgets.add(addButton);

        // Apply button (applies all changes)
        applyButton = Button.builder(Component.translatable("mineqtt.alarm_clock.apply"), b -> {
            // No-op: changes are applied immediately via network packets
        }).bounds(inputX + (inputW + 6) * 2 + 58, inputY, 50, inputH)
                .build();
        applyButton.active = false; // Disabled - changes are immediate
        widgets.add(applyButton);

        this.validationError = "";
        this.scrollOffset = 0;
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

    private void addAlarm() {
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

            NetworkManager.sendToServer(new MineqttNetworking.AddAlarmPayload(
                    screen.getMenu().blockPos, minecraftTicks));
            this.validationError = "";
        } catch (NumberFormatException e) {
            this.validationError = "mineqtt.alarm_clock.error.invalid";
        } catch (IllegalArgumentException e) {
            this.validationError = "mineqtt.alarm_clock.error.invalid";
        }
    }

    @Override
    public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 46; // Below the input fields

        // Draw colon separator between hour and minute inputs
        g.text(Minecraft.getInstance().font, ":", guiLeft + 8 + 40 + 3, guiTop + 20, 0xFFAAAAAA, false);

        // Validation error
        if (!this.validationError.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.alarm_clock.error: " + this.validationError, x, y, CONTENT_W, 0xFFFF5555);
            y += 12;
        }

        // Current Minecraft time
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        try {
            long gameTime = level.getGameTime();
            int timeOfDay = (int) (gameTime % 24000);
            String timeStr = AlarmClockBlockEntity.formatAlarmTime(timeOfDay);
            GuiText.drawTruncated(g, "mineqtt.alarm_clock.time: " + timeStr, x, y, CONTENT_W, 0xFFAAAAAA);
            y += 12;

            // Display alarm list
            var be = level.getBlockEntity(menu.blockPos);
            if (be instanceof AlarmClockBlockEntity alarmBe) {
                List<Integer> times = alarmBe.getAlarmTimes();
                List<Boolean> enabled = alarmBe.getAlarmEnabled();

                if (times.isEmpty()) {
                    y += 4;
                    GuiText.drawTruncated(g, "mineqtt.alarm_clock.no_alarms", x, y, CONTENT_W, 0xFF666666);
                    y += 12;
                } else {
                    y += 4;
                    GuiText.drawTruncated(g, "mineqtt.alarm_clock.alarms: " + times.size(), x, y, CONTENT_W, 0xFFAAAAAA);
                    y += 12;

                    // Draw each alarm with remove and toggle buttons
                    int listStartY = y;
                    int listMaxH = guiTop + TabbedMqttMenu.GUI_HEIGHT - 60 - listStartY;
                    int itemH = 18;
                    int visibleItems = listMaxH / itemH;
                    int totalItems = times.size();

                    for (int i = 0; i < totalItems && i < visibleItems; i++) {
                        int idx = i + scrollOffset;
                        if (idx >= totalItems) break;

                        String alarmTime = AlarmClockBlockEntity.formatAlarmTime(times.get(idx));
                        boolean isEnabled = enabled.get(idx);
                        String status = isEnabled ? "mineqtt.alarm_clock.enabled" : "mineqtt.alarm_clock.disabled";
                        int color = isEnabled ? 0xFF55FF55 : 0xFF666666;

                        // Alarm time and status
                        GuiText.drawTruncated(g, "  " + alarmTime + " - " + status, x, y, CONTENT_W - 80, color);

                        // Toggle button
                        int toggleX = guiLeft + CONTENT_W - 76;
                        Button toggleBtn = Button.builder(
                                Component.literal(isEnabled ? "✓" : "✗"),
                                b -> {
                                    NetworkManager.sendToServer(new MineqttNetworking.ToggleAlarmPayload(
                                            screen.getMenu().blockPos, idx));
                                })
                                .bounds(toggleX, y - 4, 20, 14)
                                .build();
                        widgets.add(toggleBtn);

                        // Remove button
                        int removeX = toggleX + 24;
                        Button removeBtn = Button.builder(
                                Component.literal("X"),
                                b -> {
                                    NetworkManager.sendToServer(new MineqttNetworking.RemoveAlarmPayload(
                                            screen.getMenu().blockPos, idx));
                                })
                                .bounds(removeX, y - 4, 20, 14)
                                .build();
                        widgets.add(removeBtn);

                        y += itemH;
                    }
                }

                // Power status
                var state = level.getBlockState(menu.blockPos);
                if (state.hasProperty(art.rehra.mineqtt.blocks.AlarmClockBlock.POWER)) {
                    int power = state.getValue(art.rehra.mineqtt.blocks.AlarmClockBlock.POWER);
                    y += 10;
                    String powerStr = power > 0 ? "mineqtt.alarm_clock.powered" : "mineqtt.alarm_clock.unpowered";
                    int color = power > 0 ? 0xFF55FF55 : 0xFF666666;
                    GuiText.drawTruncated(g, "mineqtt.alarm_clock.status: " + powerStr, x, y, CONTENT_W, color);
                }
            }
        } catch (Throwable ignored) {
        }
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

package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.GameTimeTimerBlockEntity;
import art.rehra.mineqtt.blocks.entities.GameTimeTrigger;
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
 * Tab view for the Game Time Timer: provides trigger mode selector buttons,
 * custom time input, current game time display, and next trigger countdown.
 */
public class GameTimeTimerTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();

    private EditBox customTimeInput;
    private Button applyButton;
    private Button btnSunrise;
    private Button btnNoon;
    private Button btnSunset;
    private Button btnMidnight;
    private Button btnCustom;

    private int currentTriggerModeIndex = 0;
    private int currentCustomTime = 6000;
    private String validationError = "";

    private static final String[] MODE_LABELS = {"Sunrise", "Noon", "Sunset", "Midnight", "Custom"};

    public GameTimeTimerTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        // Read current configuration from the server-side block entity
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        if (be instanceof GameTimeTimerBlockEntity gameTimeBe) {
            this.currentTriggerModeIndex = gameTimeBe.getTriggerModeIndex();
            this.currentCustomTime = gameTimeBe.getCustomTime();
        }

        int inputX = guiLeft + 8;
        int inputY = guiTop + 18;

        // Trigger mode selector buttons
        int btnW = (CONTENT_W - 12) / 5;
        int btnH = 20;

        btnSunrise = createModeButton(0, guiLeft + 8, inputY, btnW, btnH);
        btnNoon = createModeButton(1, guiLeft + 8 + btnW + 3, inputY, btnW, btnH);
        btnSunset = createModeButton(2, guiLeft + 8 + (btnW + 3) * 2, inputY, btnW, btnH);
        btnMidnight = createModeButton(3, guiLeft + 8 + (btnW + 3) * 3, inputY, btnW, btnH);
        btnCustom = createModeButton(4, guiLeft + 8 + (btnW + 3) * 4, inputY, btnW, btnH);

        widgets.add(btnSunrise);
        widgets.add(btnNoon);
        widgets.add(btnSunset);
        widgets.add(btnMidnight);
        widgets.add(btnCustom);

        updateModeButtons();

        // Custom time input field (below mode buttons)
        int customY = inputY + btnH + 6;
        int inputW = CONTENT_W - 80;
        int inputH = 14;

        customTimeInput = new EditBox(Minecraft.getInstance().font, inputX, customY, inputW, inputH, Component.empty());
        customTimeInput.setHint(Component.literal("0-23999"));
        customTimeInput.setMaxLength(5);
        customTimeInput.setValue(String.valueOf(currentCustomTime));
        customTimeInput.setResponder(this::onCustomTimeChanged);
        customTimeInput.visible = (currentTriggerModeIndex == GameTimeTrigger.CUSTOM.ordinal());
        widgets.add(customTimeInput);

        // Apply button for custom time
        applyButton = Button.builder(Component.translatable("mineqtt.game_time_timer.apply"), b -> applyCustomTime())
                .bounds(guiLeft + CONTENT_W - 68, customY, 72, inputH)
                .build();
        applyButton.visible = (currentTriggerModeIndex == GameTimeTrigger.CUSTOM.ordinal());
        widgets.add(applyButton);

        this.validationError = "";
    }

    private Button createModeButton(int modeIndex, int x, int y, int w, int h) {
        return Button.builder(
                Component.literal(MODE_LABELS[modeIndex]),
                b -> {
                    this.currentTriggerModeIndex = modeIndex;
                    updateModeButtons();
                    // Show/hide custom time input
                    boolean isCustom = modeIndex == GameTimeTrigger.CUSTOM.ordinal();
                    if (customTimeInput != null) customTimeInput.visible = isCustom;
                    if (applyButton != null) applyButton.visible = isCustom;
                    // Send the trigger mode change to server
                    NetworkManager.sendToServer(new MineqttNetworking.SetGameTimeTimerPayload(
                            screen.getMenu().blockPos, modeIndex, currentCustomTime));
                })
                .bounds(x, y, w, h)
                .build();
    }

    private void updateModeButtons() {
        updateButtonStyle(btnSunrise, 0);
        updateButtonStyle(btnNoon, 1);
        updateButtonStyle(btnSunset, 2);
        updateButtonStyle(btnMidnight, 3);
        updateButtonStyle(btnCustom, 4);
    }

    private void updateButtonStyle(Button button, int modeIndex) {
        if (modeIndex == currentTriggerModeIndex) {
            button.active = false;
        } else {
            button.active = true;
        }
    }

    private void onCustomTimeChanged(String text) {
        if (text == null || text.isBlank()) {
            this.validationError = "mineqtt.game_time_timer.error.zero";
            return;
        }
        try {
            int value = Integer.parseInt(text);
            if (value < 0 || value >= 24000) {
                this.validationError = "mineqtt.game_time_timer.error.range";
            } else {
                this.validationError = "";
            }
        } catch (NumberFormatException e) {
            this.validationError = "mineqtt.game_time_timer.error.invalid";
        }
    }

    private void applyCustomTime() {
        String text = customTimeInput.getValue().trim();
        if (text.isEmpty()) return;

        try {
            int value = Integer.parseInt(text);
            if (value < 0 || value >= 24000) {
                this.validationError = "mineqtt.game_time_timer.error.range";
                return;
            }

            NetworkManager.sendToServer(new MineqttNetworking.SetGameTimeTimerPayload(
                    screen.getMenu().blockPos, currentTriggerModeIndex, value));
            this.currentCustomTime = value;
            this.validationError = "";
        } catch (NumberFormatException e) {
            this.validationError = "mineqtt.game_time_timer.error.invalid";
        }
    }

    @Override
    public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 60; // Below the mode buttons and custom input

        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();

        // Current game time display
        try {
            long gameTime = level.getGameTime();
            int timeOfDay = (int) (gameTime % 24000);
            String timeStr = formatGameTime(timeOfDay);
            GuiText.drawTruncated(g, "mineqtt.game_time_timer.time: " + timeStr + " (tick " + timeOfDay + ")", x, y, CONTENT_W, 0xFFAAAAAA);
            y += 12;

            // Trigger mode display
            var be = level.getBlockEntity(menu.blockPos);
            if (be instanceof GameTimeTimerBlockEntity gameTimeBe) {
                GameTimeTrigger mode = gameTimeBe.getTriggerMode();
                int targetTicks = gameTimeBe.getTargetTicks();
                String targetStr = formatGameTime(targetTicks);

                y += 4;
                GuiText.drawTruncated(g, "mineqtt.game_time_timer.mode: " + MODE_LABELS[mode.ordinal()] + " (tick " + targetTicks + " / " + targetStr + ")", x, y, CONTENT_W, 0xFFAAAAAA);
                y += 12;

                // Next trigger countdown
                long remaining = gameTimeBe.getRemainingTicks(level);
                String remainingStr = formatRemaining(remaining);
                GuiText.drawTruncated(g, "mineqtt.game_time_timer.countdown: " + remainingStr + " (" + remaining + " ticks)", x, y, CONTENT_W, 0xFF888888);
                y += 12;

                // Validation error
                if (!this.validationError.isEmpty()) {
                    GuiText.drawTruncated(g, "mineqtt.game_time_timer.error: " + this.validationError, x, y, CONTENT_W, 0xFFFF5555);
                    y += 12;
                }

                // Power status
                var state = level.getBlockState(menu.blockPos);
                if (state.hasProperty(art.rehra.mineqtt.blocks.GameTimeTimerBlock.POWER)) {
                    int power = state.getValue(art.rehra.mineqtt.blocks.GameTimeTimerBlock.POWER);
                    String powerStr = power > 0 ? "mineqtt.game_time_timer.powered" : "mineqtt.game_time_timer.unpowered";
                    int color = power > 0 ? 0xFF55FF55 : 0xFF666666;
                    GuiText.drawTruncated(g, "mineqtt.game_time_timer.status: " + powerStr, x, y, CONTENT_W, color);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Formats Minecraft ticks to a human-readable HH:MM string.
     */
    private String formatGameTime(int minecraftTicks) {
        int totalSeconds = minecraftTicks * 86400 / 24000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        return String.format("%02d:%02d", hours, minutes);
    }

    /**
     * Formats remaining ticks to a human-readable string.
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
        if (customTimeInput != null && customTimeInput.isVisible()) {
            customTimeInput.setFocused(customTimeInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

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
    private static final int LINE_PITCH = 10;
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
        int btnH = 16;

        btnSunrise = createModeButton(0, "mineqtt.game_time_timer.mode.sunrise", guiLeft + 8, inputY, btnW, btnH);
        btnNoon = createModeButton(1, "mineqtt.game_time_timer.mode.noon", guiLeft + 8 + btnW + 3, inputY, btnW, btnH);
        btnSunset = createModeButton(2, "mineqtt.game_time_timer.mode.sunset", guiLeft + 8 + (btnW + 3) * 2, inputY, btnW, btnH);
        btnMidnight = createModeButton(3, "mineqtt.game_time_timer.mode.midnight", guiLeft + 8 + (btnW + 3) * 3, inputY, btnW, btnH);
        btnCustom = createModeButton(4, "mineqtt.game_time_timer.mode.custom", guiLeft + 8 + (btnW + 3) * 4, inputY, btnW, btnH);

        widgets.add(btnSunrise);
        widgets.add(btnNoon);
        widgets.add(btnSunset);
        widgets.add(btnMidnight);
        widgets.add(btnCustom);

        updateModeButtons();

        // Custom time input field (below mode buttons)
        int customY = inputY + btnH + 4;
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

    private Button createModeButton(int modeIndex, String translationKey, int x, int y, int w, int h) {
        return Button.builder(
                Component.translatable(translationKey),
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
        if (button != null) {
            button.active = (modeIndex != currentTriggerModeIndex);
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
        int y = guiTop + 56; // Below the mode buttons and custom input; the last line ends above the inventory label

        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();

        try {
            // Current game time display
            long dayTime = GameTimeTimerBlockEntity.getWorldDayTime(level);
            int timeOfDay = (int) (dayTime % 24000);
            if (timeOfDay < 0) {
                timeOfDay += 24000;
            }
            String timeStr = GameTimeTimerBlockEntity.formatGameTime(timeOfDay);
            Component timeComp = Component.translatable("mineqtt.game_time_timer.time")
                    .append(": " + timeStr + " (tick " + timeOfDay + ")");
            GuiText.drawTruncated(g, timeComp, x, y, CONTENT_W, 0xFFAAAAAA, mouseX, mouseY);
            y += LINE_PITCH;

            // Trigger mode display
            var be = level.getBlockEntity(menu.blockPos);
            if (be instanceof GameTimeTimerBlockEntity gameTimeBe) {
                GameTimeTrigger mode = gameTimeBe.getTriggerMode();
                int targetTicks = gameTimeBe.getTargetTicks();
                String targetStr = GameTimeTimerBlockEntity.formatGameTime(targetTicks);

                Component modeComp = Component.translatable("mineqtt.game_time_timer.mode")
                        .append(": ")
                        .append(Component.translatable(getModeTranslationKey(mode)))
                        .append(" (tick " + targetTicks + " / " + targetStr + ")");
                GuiText.drawTruncated(g, modeComp, x, y, CONTENT_W, 0xFFAAAAAA, mouseX, mouseY);
                y += LINE_PITCH;

                // Next trigger countdown, or in its place why the typed custom time is not taken
                if (this.validationError.isEmpty()) {
                    long remaining = gameTimeBe.getRemainingTicks(level);
                    String remainingStr = formatRemaining(remaining);
                    Component countdownComp = Component.translatable("mineqtt.game_time_timer.countdown")
                            .append(": " + remainingStr + " (" + remaining + " ticks)");
                    GuiText.drawTruncated(g, countdownComp, x, y, CONTENT_W, 0xFF888888, mouseX, mouseY);
                } else {
                    Component errorComp = Component.translatable("mineqtt.game_time_timer.error")
                            .append(": ")
                            .append(Component.translatable(this.validationError));
                    GuiText.drawTruncated(g, errorComp, x, y, CONTENT_W, 0xFFFF5555, mouseX, mouseY);
                }
                y += LINE_PITCH;

                // Power status
                var state = level.getBlockState(menu.blockPos);
                if (state.hasProperty(art.rehra.mineqtt.blocks.GameTimeTimerBlock.POWER)) {
                    int power = state.getValue(art.rehra.mineqtt.blocks.GameTimeTimerBlock.POWER);
                    String powerKey = power > 0 ? "mineqtt.game_time_timer.powered" : "mineqtt.game_time_timer.unpowered";
                    int color = power > 0 ? 0xFF55FF55 : 0xFF666666;
                    Component statusComp = Component.translatable("mineqtt.game_time_timer.status")
                            .append(": ")
                            .append(Component.translatable(powerKey));
                    GuiText.drawTruncated(g, statusComp, x, y, CONTENT_W, color, mouseX, mouseY);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String getModeTranslationKey(GameTimeTrigger mode) {
        return switch (mode) {
            case SUNRISE -> "mineqtt.game_time_timer.mode.sunrise";
            case NOON -> "mineqtt.game_time_timer.mode.noon";
            case SUNSET -> "mineqtt.game_time_timer.mode.sunset";
            case MIDNIGHT -> "mineqtt.game_time_timer.mode.midnight";
            case CUSTOM -> "mineqtt.game_time_timer.mode.custom";
        };
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

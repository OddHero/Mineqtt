package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.IntervalTimerBlockEntity;
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
 * Tab view for the Interval Timer: provides an interval input field,
 * unit selector buttons, and timer status information.
 */
public class IntervalTimerTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();

    private EditBox intervalInput;
    private Button applyButton;
    private Button btnTicks;
    private Button btnSeconds;
    private Button btnMinutes;
    private Button btnHours;

    private long currentIntervalValue = 20;
    private int currentUnitIndex = 0;
    private String validationError = "";

    private static final String[] UNIT_LABELS = {"Ticks", "Seconds", "Minutes", "Hours"};

    public IntervalTimerTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        // Read current configuration from the server-side block entity
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        if (be instanceof IntervalTimerBlockEntity intervalBe) {
            this.currentIntervalValue = intervalBe.getIntervalValue();
            this.currentUnitIndex = intervalBe.getUnitIndex();
        }

        int inputX = guiLeft + 8;
        int inputY = guiTop + 18;
        int inputW = CONTENT_W - 76;
        int inputH = 14;

        // Interval value input field
        intervalInput = new EditBox(Minecraft.getInstance().font, inputX, inputY, inputW, inputH, Component.empty());
        intervalInput.setHint(Component.literal("20"));
        intervalInput.setMaxLength(10);
        intervalInput.setValue(String.valueOf(currentIntervalValue));
        intervalInput.setResponder(this::onValueChanged);
        widgets.add(intervalInput);

        // Apply button
        applyButton = Button.builder(Component.translatable("mineqtt.interval_timer.apply"), b -> applyInterval())
                .bounds(guiLeft + CONTENT_W - 72, inputY, 72, inputH)
                .build();
        widgets.add(applyButton);

        // Unit selector buttons - compact, below input
        int btnY = inputY + 18;
        int btnW = (CONTENT_W - 12) / 4;
        int btnH = 14;

        btnTicks = createUnitButton(0, guiLeft + 8, btnY, btnW, btnH);
        btnSeconds = createUnitButton(1, guiLeft + 8 + btnW + 3, btnY, btnW, btnH);
        btnMinutes = createUnitButton(2, guiLeft + 8 + (btnW + 3) * 2, btnY, btnW, btnH);
        btnHours = createUnitButton(3, guiLeft + 8 + (btnW + 3) * 3, btnY, btnW, btnH);

        widgets.add(btnTicks);
        widgets.add(btnSeconds);
        widgets.add(btnMinutes);
        widgets.add(btnHours);

        updateUnitButtons();
        this.validationError = "";
    }

    private Button createUnitButton(int unitIndex, int x, int y, int w, int h) {
        return Button.builder(
                Component.literal(UNIT_LABELS[unitIndex]),
                b -> {
                    this.currentUnitIndex = unitIndex;
                    updateUnitButtons();
                })
                .bounds(x, y, w, h)
                .build();
    }

    private void updateUnitButtons() {
        updateButtonStyle(btnTicks, 0);
        updateButtonStyle(btnSeconds, 1);
        updateButtonStyle(btnMinutes, 2);
        updateButtonStyle(btnHours, 3);
    }

    private void updateButtonStyle(Button button, int unitIndex) {
        if (unitIndex == currentUnitIndex) {
            button.active = false;
        } else {
            button.active = true;
        }
    }

    private void onValueChanged(String text) {
        if (text == null || text.isBlank()) {
            this.validationError = "mineqtt.interval_timer.error.zero";
            return;
        }
        try {
            long value = Long.parseLong(text);
            if (value <= 0) {
                this.validationError = "mineqtt.interval_timer.error.zero";
            } else if (value > 2147483647L) {
                this.validationError = "mineqtt.interval_timer.error.too_large";
            } else {
                this.validationError = "";
            }
        } catch (NumberFormatException e) {
            this.validationError = "mineqtt.interval_timer.error.invalid";
        }
    }

    private void applyInterval() {
        String text = intervalInput.getValue().trim();
        if (text.isEmpty()) return;

        try {
            long value = Long.parseLong(text);
            if (value <= 0) {
                this.validationError = "mineqtt.interval_timer.error.zero";
                return;
            }
            if (value > 2147483647L) {
                this.validationError = "mineqtt.interval_timer.error.too_large";
                return;
            }

            NetworkManager.sendToServer(new MineqttNetworking.SetIntervalTimerPayload(
                    screen.getMenu().blockPos, value, currentUnitIndex));
            this.currentIntervalValue = value;
            this.validationError = "";
        } catch (NumberFormatException e) {
            this.validationError = "mineqtt.interval_timer.error.invalid";
        }
    }

    @Override
    public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 46; // Below the unit selector buttons

        // Current interval display - compact
        long intervalTicks = computeIntervalTicks(currentIntervalValue, currentUnitIndex);
        String unitLabel = UNIT_LABELS[currentUnitIndex];
        GuiText.drawTruncated(g, "mineqtt.interval_timer.config: " + currentIntervalValue + " " + unitLabel.toLowerCase() + " (" + intervalTicks + " ticks)", x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;

        // Validation error
        if (!this.validationError.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.interval_timer.error: " + this.validationError, x, y, CONTENT_W, 0xFFFF5555);
            y += 12;
        }

        // Timer progress info - compact
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        try {
            var be = level.getBlockEntity(menu.blockPos);
            if (be instanceof IntervalTimerBlockEntity intervalBe) {
                long tickCounter = intervalBe.getTickCounter();
                long remaining = intervalBe.getRemainingTicks();
                long interval = intervalBe.getIntervalTicks();

                if (interval > 0) {
                    double progress = (double) tickCounter / interval * 100.0;
                    GuiText.drawTruncated(g, "mineqtt.interval_timer.progress: " + tickCounter + "/" + interval + " (" + String.format("%.1f", progress) + "%)  mineqtt.interval_timer.remaining: " + remaining + " ticks", x, y, CONTENT_W, 0xFF888888);
                } else {
                    GuiText.drawTruncated(g, "mineqtt.interval_timer.progress: " + tickCounter, x, y, CONTENT_W, 0xFF888888);
                }
                y += 12;

                // Power status
                var state = level.getBlockState(menu.blockPos);
                if (state.hasProperty(art.rehra.mineqtt.blocks.IntervalTimerBlock.POWER)) {
                    int power = state.getValue(art.rehra.mineqtt.blocks.IntervalTimerBlock.POWER);
                    String powerStr = power > 0 ? "mineqtt.interval_timer.powered" : "mineqtt.interval_timer.unpowered";
                    int color = power > 0 ? 0xFF55FF55 : 0xFF666666;
                    GuiText.drawTruncated(g, "mineqtt.interval_timer.status: " + powerStr, x, y, CONTENT_W, color);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private long computeIntervalTicks(long value, int unitIndex) {
        switch (unitIndex) {
            case IntervalTimerBlockEntity.UNIT_SECONDS: return value * IntervalTimerBlockEntity.TICKS_PER_SECOND;
            case IntervalTimerBlockEntity.UNIT_MINUTES: return value * IntervalTimerBlockEntity.TICKS_PER_MINUTE;
            case IntervalTimerBlockEntity.UNIT_HOURS:   return value * IntervalTimerBlockEntity.TICKS_PER_HOUR;
            case IntervalTimerBlockEntity.UNIT_TICKS:
            default:                                    return value;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (intervalInput != null && intervalInput.isVisible()) {
            intervalInput.setFocused(intervalInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

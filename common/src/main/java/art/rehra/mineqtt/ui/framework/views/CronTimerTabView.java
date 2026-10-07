package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import art.rehra.mineqtt.util.CronSchedule;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab view for the Cron Timer: provides a single input field for the cron
 * expression, validation feedback, and next trigger time display.
 */
public class CronTimerTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int INPUT_H = 14;

    private final TabbedMqttScreen screen;
    private final List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();

    private EditBox expressionInput;
    private Button applyButton;
    private Button clearButton;
    private String validationError = "";

    public CronTimerTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        // Read current expression from the server-side block entity
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        String currentExpression = "";
        if (be instanceof art.rehra.mineqtt.blocks.entities.CronTimerBlockEntity cronBe) {
            currentExpression = cronBe.getCronExpression();
        }

        int inputX = guiLeft + 8;
        int inputY = guiTop + 10;
        int inputW = CONTENT_W - 110;
        int btnW = 50;

        // Single expression input field
        expressionInput = new EditBox(Minecraft.getInstance().font, inputX, inputY, inputW, INPUT_H, Component.empty());
        expressionInput.setHint(Component.literal("* * * * *"));
        expressionInput.setMaxLength(64);
        expressionInput.setValue(currentExpression);
        expressionInput.setResponder(text -> validateExpression());
        widgets.add(expressionInput);

        // Apply button
        applyButton = Button.builder(Component.translatable("mineqtt.cron_timer.apply"), b -> applyExpression())
                .bounds(inputX + inputW + 4, inputY, btnW, INPUT_H)
                .build();
        widgets.add(applyButton);

        // Clear button
        clearButton = Button.builder(Component.translatable("mineqtt.cron_timer.clear"), b -> clearExpression())
                .bounds(inputX + inputW + 4 + btnW + 4, inputY, btnW, INPUT_H)
                .build();
        widgets.add(clearButton);

        this.validationError = "";
        validateExpression();
    }

    private void validateExpression() {
        String expr = expressionInput.getValue().trim();
        if (expr.isEmpty()) {
            this.validationError = "";
            return;
        }
        if (CronSchedule.isValid(expr)) {
            this.validationError = "";
        } else {
            try {
                CronSchedule.parse(expr);
            } catch (IllegalArgumentException e) {
                this.validationError = e.getMessage();
            }
        }
    }

    private void applyExpression() {
        String expression = expressionInput.getValue().trim();
        if (!expression.isEmpty()) {
            NetworkManager.sendToServer(new MineqttNetworking.SetCronExpressionPayload(
                    screen.getMenu().blockPos, expression));
            this.validationError = "";
        }
    }

    private void clearExpression() {
        expressionInput.setValue("");
        NetworkManager.sendToServer(new MineqttNetworking.SetCronExpressionPayload(
                screen.getMenu().blockPos, ""));
        this.validationError = "";
    }

    @Override
    public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 28; // Below the input + buttons

        // Field reference below input
        GuiText.drawTruncated(g, "mineqtt.cron_timer.fields", x, y, CONTENT_W, 0xFF888888);
        y += 12;

        // Power status
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        try {
            var state = level.getBlockState(menu.blockPos);
            if (state.hasProperty(art.rehra.mineqtt.blocks.CronTimerBlock.POWER)) {
                int power = state.getValue(art.rehra.mineqtt.blocks.CronTimerBlock.POWER);
                String powerStr = power > 0 ? "mineqtt.cron_timer.powered" : "mineqtt.cron_timer.unpowered";
                int color = power > 0 ? 0xFF55FF55 : 0xFF666666;
                GuiText.drawTruncated(g, "mineqtt.cron_timer.status: " + powerStr, x, y, CONTENT_W, color);
                y += 10;
            }
        } catch (Throwable ignored) {
        }

        // Validation error
        if (!this.validationError.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.cron_timer.error: " + this.validationError, x, y, CONTENT_W, 0xFFFF5555);
            y += 10;
        }

        // Next trigger time
        String expr = expressionInput != null ? expressionInput.getValue().trim() : "";
        if (!expr.isEmpty() && CronSchedule.isValid(expr)) {
            try {
                CronSchedule schedule = CronSchedule.parse(expr);
                String next = CronSchedule.describeNextTrigger(schedule, expr);
                GuiText.drawTruncated(g, "mineqtt.cron_timer.next: " + next, x, y, CONTENT_W, 0xFF55FF55);
                y += 10;

                LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
                String timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                GuiText.drawTruncated(g, "mineqtt.cron_timer.time: " + timeStr, x, y, CONTENT_W, 0xFF888888);
            } catch (IllegalArgumentException ignored) {
            }
        } else if (!expr.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.cron_timer.invalid", x, y, CONTENT_W, 0xFFFF5555);
        } else {
            GuiText.drawTruncated(g, "mineqtt.cron_timer.no_expression", x, y, CONTENT_W, 0xFF666666);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (expressionInput != null && expressionInput.isVisible()) {
            expressionInput.setFocused(expressionInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

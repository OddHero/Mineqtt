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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab view for the Cron Timer: provides a cron expression input field,
 * validation feedback, and schedule status information.
 */
public class CronTimerTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();

    private EditBox cronInput;
    private Button applyButton;
    private String currentExpression = "";
    private String validationError = "";

    public CronTimerTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        // Read current expression from the server-side block entity via the menu container
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        if (be instanceof art.rehra.mineqtt.blocks.entities.CronTimerBlockEntity cronBe) {
            this.currentExpression = cronBe.getCronExpression();
        }

        // Cron expression input field
        int inputX = guiLeft + 8;
        int inputY = guiTop + 18;
        int inputW = CONTENT_W - 80;
        int inputH = 14;

        cronInput = new EditBox(Minecraft.getInstance().font, inputX, inputY, inputW, inputH, Component.empty());
        cronInput.setHint(Component.literal("0 9 * * 1-5"));
        cronInput.setMaxLength(64);
        cronInput.setValue(currentExpression);
        cronInput.setResponder(this::onExpressionChanged);
        widgets.add(cronInput);

        // Apply button
        applyButton = Button.builder(Component.translatable("mineqtt.cron_timer.apply"), b -> applyExpression())
                .bounds(guiLeft + CONTENT_W - 68, inputY, 72, inputH)
                .build();
        widgets.add(applyButton);

        // Clear button
        Button clearButton = Button.builder(Component.translatable("mineqtt.cron_timer.clear"), b -> {
            cronInput.setValue("");
            onExpressionChanged("");
        }).bounds(guiLeft + CONTENT_W - 68, inputY + 18, 72, 14).build();
        widgets.add(clearButton);

        this.validationError = "";
    }

    private void onExpressionChanged(String text) {
        if (text == null || text.isBlank()) {
            this.validationError = "";
            return;
        }
        if (CronSchedule.isValid(text)) {
            this.validationError = "";
        } else {
            try {
                CronSchedule.parse(text);
            } catch (IllegalArgumentException e) {
                this.validationError = e.getMessage();
            }
        }
    }

    private void applyExpression() {
        String expression = cronInput.getValue().trim();
        NetworkManager.sendToServer(new MineqttNetworking.SetCronExpressionPayload(
                screen.getMenu().blockPos, expression));
        this.currentExpression = expression;
        this.validationError = "";
    }

    @Override
    public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 46; // Below the input field and buttons

        // Cron field explanation
        GuiText.drawTruncated(g, "mineqtt.cron_timer.fields", x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;
        GuiText.drawTruncated(g, "mineqtt.cron_timer.field_minute", x, y, CONTENT_W, 0xFF888888);
        y += 10;
        GuiText.drawTruncated(g, "mineqtt.cron_timer.field_hour", x, y, CONTENT_W, 0xFF888888);
        y += 10;
        GuiText.drawTruncated(g, "mineqtt.cron_timer.field_day", x, y, CONTENT_W, 0xFF888888);
        y += 10;
        GuiText.drawTruncated(g, "mineqtt.cron_timer.field_month", x, y, CONTENT_W, 0xFF888888);
        y += 10;
        GuiText.drawTruncated(g, "mineqtt.cron_timer.field_weekday", x, y, CONTENT_W, 0xFF888888);
        y += 14;

        // Current expression
        String expr = cronInput.getValue().trim();
        if (!expr.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.cron_timer.expression: " + expr, x, y, CONTENT_W, 0xFFAAAAAA);
            y += 12;
        }

        // Validation error
        if (!this.validationError.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.cron_timer.error: " + this.validationError, x, y, CONTENT_W, 0xFFFF5555);
            y += 12;
        }

        // Schedule status
        if (CronSchedule.isValid(expr)) {
            try {
                CronSchedule schedule = CronSchedule.parse(expr);
                String next = CronSchedule.describeNextTrigger(schedule, expr);
                GuiText.drawTruncated(g, "mineqtt.cron_timer.next: " + next, x, y, CONTENT_W, 0xFF55FF55);
                y += 12;

                // Current server time
                LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
                String timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                GuiText.drawTruncated(g, "mineqtt.cron_timer.time: " + timeStr, x, y, CONTENT_W, 0xFF888888);
            } catch (IllegalArgumentException ignored) {
            }
        } else if (!expr.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.cron_timer.invalid", x, y, CONTENT_W, 0xFFFF5555);
        } else {
            GuiText.drawTruncated(g, "mineqtt.cron_timer.no_expression", x, y, CONTENT_W, 0xFF666666);
        }

        // Power status
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        try {
            var state = level.getBlockState(menu.blockPos);
            if (state.hasProperty(art.rehra.mineqtt.blocks.CronTimerBlock.POWER)) {
                int power = state.getValue(art.rehra.mineqtt.blocks.CronTimerBlock.POWER);
                y += 14;
                String powerStr = power > 0 ? "mineqtt.cron_timer.powered" : "mineqtt.cron_timer.unpowered";
                int color = power > 0 ? 0xFF55FF55 : 0xFF666666;
                GuiText.drawTruncated(g, "mineqtt.cron_timer.status: " + powerStr, x, y, CONTENT_W, color);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (cronInput != null && cronInput.isVisible()) {
            cronInput.setFocused(cronInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

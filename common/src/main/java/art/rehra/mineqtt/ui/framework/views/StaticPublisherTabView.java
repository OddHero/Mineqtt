package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.StaticPublisherBlock;
import art.rehra.mineqtt.blocks.entities.StaticPublisherBlockEntity;
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
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab view for the Static Publisher: provides a message input field,
 * publish status, and test-publish button.
 */
public class StaticPublisherTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<GuiEventListener> widgets = new ArrayList<>();

    private EditBox messageInput;
    private Button applyButton;
    private Button testButton;

    public StaticPublisherTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        // Read current message from the block entity
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        String currentMessage = "";
        if (be instanceof StaticPublisherBlockEntity staticBe) {
            currentMessage = staticBe.getStaticMessage();
        }

        // Message input field
        int inputX = guiLeft + 8;
        int inputY = guiTop + 18;
        int inputW = CONTENT_W - 80;
        int inputH = 14;

        messageInput = new EditBox(Minecraft.getInstance().font, inputX, inputY, inputW, inputH, Component.empty());
        messageInput.setHint(Component.literal("Enter message to publish"));
        messageInput.setMaxLength(2048);
        messageInput.setValue(currentMessage);
        widgets.add(messageInput);

        // Apply button
        applyButton = Button.builder(Component.translatable("mineqtt.static_publisher.apply"), b -> applyMessage())
                .bounds(guiLeft + CONTENT_W - 68, inputY, 72, inputH)
                .build();
        widgets.add(applyButton);

        // Test publish button
        testButton = Button.builder(Component.translatable("mineqtt.static_publisher.test"), b -> testPublish())
                .bounds(guiLeft + CONTENT_W - 68, inputY + 18, 72, 14)
                .build();
        widgets.add(testButton);
    }

    private void applyMessage() {
        String message = messageInput.getValue();
        NetworkManager.sendToServer(new MineqttNetworking.SetStaticMessagePayload(
                screen.getMenu().blockPos, message));
    }

    private void testPublish() {
        // Send the current message as a test publish
        String message = messageInput.getValue();
        NetworkManager.sendToServer(new MineqttNetworking.TestStaticPublishPayload(
                screen.getMenu().blockPos, message));
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 52; // Below the input field and buttons

        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);

        // Configured message preview
        String configuredMessage = "";
        String lastStatus = "";
        if (be instanceof StaticPublisherBlockEntity staticBe) {
            configuredMessage = staticBe.getStaticMessage();
            lastStatus = staticBe.getLastPublishStatus();
        }

        // Message preview
        GuiText.drawTruncated(g, "mineqtt.static_publisher.message", x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;
        if (configuredMessage.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.static_publisher.no_message", x, y, CONTENT_W, 0xFF666666, mouseX, mouseY);
        } else {
            GuiText.drawAutoScaled(g, configuredMessage, x, y, CONTENT_W, 60, 0xFF0088FF, mouseX, mouseY);
        }
        y += 16;

        // Last publish status
        GuiText.drawTruncated(g, "mineqtt.static_publisher.status", x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;
        if (lastStatus.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.static_publisher.not_published", x, y, CONTENT_W, 0xFF666666, mouseX, mouseY);
        } else if (lastStatus.startsWith("Error")) {
            GuiText.drawTruncated(g, lastStatus, x, y, CONTENT_W, 0xFFFF5555, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, lastStatus, x, y, CONTENT_W, 0xFF55FF55, mouseX, mouseY);
        }
        y += 14;

        // Power status
        boolean isPowered = false;
        try {
            var state = level.getBlockState(menu.blockPos);
            if (state.hasProperty(StaticPublisherBlock.POWERED)) {
                isPowered = state.getValue(StaticPublisherBlock.POWERED);
            }
        } catch (Throwable ignored) {
        }

        String powerStr = isPowered ? "mineqtt.static_publisher.powered" : "mineqtt.static_publisher.unpowered";
        int color = isPowered ? 0xFF55FF55 : 0xFF666666;
        GuiText.drawTruncated(g, "mineqtt.static_publisher.power: " + powerStr, x, y, CONTENT_W, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (messageInput != null && messageInput.isVisible()) {
            messageInput.setFocused(messageInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

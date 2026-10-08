package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.TopicRelayBlock;
import art.rehra.mineqtt.blocks.entities.TopicRelayBlockEntity;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
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
 * Tab view for the Topic Relay block: provides source/target topic input fields,
 * cached message display, and relay status.
 */
public class TopicRelayTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<GuiEventListener> widgets = new ArrayList<>();

    private EditBox sourceTopicInput;
    private EditBox targetTopicInput;
    private Button applySourceButton;
    private Button applyTargetButton;
    private Button testRelayButton;

    public TopicRelayTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        // Read current configuration from the block entity
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        String currentSourceTopic = "";
        String currentTargetTopic = "";
        if (be instanceof TopicRelayBlockEntity relayBe) {
            currentSourceTopic = relayBe.getSourceTopic();
            currentTargetTopic = relayBe.getTargetTopic();
        }

        int inputX = guiLeft + 8;
        int inputW = CONTENT_W - 80;
        int inputH = 14;

        // Source topic input field
        int sourceY = guiTop + 18;
        sourceTopicInput = new EditBox(Minecraft.getInstance().font, inputX, sourceY, inputW, inputH, Component.empty());
        sourceTopicInput.setHint(Component.translatable("mineqtt.topic_relay.source_hint"));
        sourceTopicInput.setMaxLength(TopicAcl.MAX_TOPIC_BYTES);
        sourceTopicInput.setValue(currentSourceTopic);
        widgets.add(sourceTopicInput);

        // Apply source button
        applySourceButton = Button.builder(Component.translatable("mineqtt.topic_relay.apply_source"), b -> applySourceTopic())
                .bounds(guiLeft + CONTENT_W - 68, sourceY, 72, inputH)
                .build();
        widgets.add(applySourceButton);

        // Target topic input field
        int targetY = guiTop + 40;
        targetTopicInput = new EditBox(Minecraft.getInstance().font, inputX, targetY, inputW, inputH, Component.empty());
        targetTopicInput.setHint(Component.translatable("mineqtt.topic_relay.target_hint"));
        targetTopicInput.setMaxLength(TopicAcl.MAX_TOPIC_BYTES);
        targetTopicInput.setValue(currentTargetTopic);
        widgets.add(targetTopicInput);

        // Apply target button
        applyTargetButton = Button.builder(Component.translatable("mineqtt.topic_relay.apply_target"), b -> applyTargetTopic())
                .bounds(guiLeft + CONTENT_W - 68, targetY, 72, inputH)
                .build();
        widgets.add(applyTargetButton);

        // Test relay button
        testRelayButton = Button.builder(Component.translatable("mineqtt.topic_relay.test_relay"), b -> testRelay())
                .bounds(guiLeft + CONTENT_W - 68, targetY + 18, 72, 14)
                .build();
        widgets.add(testRelayButton);
    }

    private void applySourceTopic() {
        String topic = sourceTopicInput.getValue().trim();
        if (!topic.isEmpty()) {
            NetworkManager.sendToServer(new MineqttNetworking.SetTopicRelaySourcePayload(
                    screen.getMenu().blockPos, topic));
        }
    }

    private void applyTargetTopic() {
        String topic = targetTopicInput.getValue().trim();
        if (!topic.isEmpty()) {
            NetworkManager.sendToServer(new MineqttNetworking.SetTopicRelayTargetPayload(
                    screen.getMenu().blockPos, topic));
        }
    }

    private void testRelay() {
        NetworkManager.sendToServer(new MineqttNetworking.TestTopicRelayPayload(
                screen.getMenu().blockPos));
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 56; // Compact - below the input fields and buttons

        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);

        // Read state from block entity
        String cachedMessage = "";
        String lastRelayStatus = "";
        boolean hasReceivedMessage = false;
        String sourceTopic = "";
        if (be instanceof TopicRelayBlockEntity relayBe) {
            cachedMessage = relayBe.getCachedMessage();
            lastRelayStatus = relayBe.getLastRelayStatus();
            hasReceivedMessage = relayBe.hasReceivedMessage();
            sourceTopic = relayBe.getSourceTopic();
        }

        // Source topic + subscription + received status - combined on fewer lines
        String subStatus = sourceTopic.isEmpty() ? "mineqtt.topic_relay.not_subscribed" : "mineqtt.topic_relay.subscribed";
        int subColor = sourceTopic.isEmpty() ? 0xFF666666 : 0xFF55FF55;
        String receivedStr = hasReceivedMessage ? "mineqtt.topic_relay.message_received" : "mineqtt.topic_relay.no_message_yet";
        int receivedColor = hasReceivedMessage ? 0xFF55FF55 : 0xFF666666;

        // Source topic
        if (sourceTopic.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.topic_relay.source_topic: " + "mineqtt.topic_relay.not_configured", x, y, CONTENT_W, 0xFF666666);
        } else {
            GuiText.drawTruncated(g, "mineqtt.topic_relay.source_topic: " + sourceTopic, x, y, CONTENT_W, 0xFF55FF55);
        }
        y += 12;

        // Subscription + received on one line
        GuiText.drawTruncated(g, "mineqtt.topic_relay.subscription: " + subStatus + "  |  mineqtt.topic_relay.received: " + receivedStr, x, y, CONTENT_W, subColor);
        y += 14;

        // Cached message - truncated to 2 lines max
        if (!cachedMessage.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.topic_relay.cached_message", x, y, CONTENT_W, 0xFFAAAAAA);
            y += 10;
            GuiText.drawAutoScaled(g, cachedMessage, x, y, CONTENT_W, 24, 0xFF0088FF, mouseX, mouseY);
            y += 16;
        }

        // Last relay status
        if (!lastRelayStatus.isEmpty()) {
            int statusColor;
            if (lastRelayStatus.startsWith("Error")) statusColor = 0xFFFF5555;
            else if (lastRelayStatus.startsWith("Relayed")) statusColor = 0xFF55FF55;
            else statusColor = 0xFFAAAAAA;
            GuiText.drawTruncated(g, "mineqtt.topic_relay.relay_status: " + lastRelayStatus, x, y, CONTENT_W, statusColor);
            y += 12;
        }

        // Power status
        boolean isPowered = false;
        try {
            var state = level.getBlockState(menu.blockPos);
            if (state.hasProperty(TopicRelayBlock.POWERED)) {
                isPowered = state.getValue(TopicRelayBlock.POWERED);
            }
        } catch (Throwable ignored) {
        }

        String powerStr = isPowered ? "mineqtt.topic_relay.powered" : "mineqtt.topic_relay.unpowered";
        int color = isPowered ? 0xFF55FF55 : 0xFF666666;
        GuiText.drawTruncated(g, "mineqtt.topic_relay.power: " + powerStr, x, y, CONTENT_W, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (sourceTopicInput != null && sourceTopicInput.isVisible()) {
            sourceTopicInput.setFocused(sourceTopicInput.isMouseOver(mouseX, mouseY));
        }
        if (targetTopicInput != null && targetTopicInput.isVisible()) {
            targetTopicInput.setFocused(targetTopicInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

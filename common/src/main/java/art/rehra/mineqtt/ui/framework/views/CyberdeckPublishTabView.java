package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.List;

public class CyberdeckPublishTabView implements MqttTabView {
    private final TabbedMqttScreen screen;
    private EditBox topicField;
    private EditBox payloadField;
    private Button sendButton;

    public CyberdeckPublishTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        topicField = new EditBox(screen.getFont(), guiLeft + 50, guiTop + 18, 118, 16, Component.literal("Topic"));
        topicField.setMaxLength(256);
        payloadField = new EditBox(screen.getFont(), guiLeft + 50, guiTop + 42, 118, 16, Component.literal("Payload"));
        payloadField.setMaxLength(1024);
        sendButton = Button.builder(Component.literal("Send"), b -> onSendClicked())
                .pos(guiLeft + 10, guiTop + 66).size(50, 20).build();

        // Default topic from slots
        TabbedMqttMenu menu = screen.getMenu();
        var baseStack = menu.container.getItem(0);
        var subStack = menu.container.getItem(1);
        String base = BaseMqttBlockEntity.parseItemStackTopic(baseStack);
        String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
        String topic = sub.isEmpty() ? base : base + "/" + sub;
        if (!baseStack.isEmpty()) topicField.setValue(topic);
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return List.of(topicField, payloadField, sendButton);
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        g.drawString(screen.getFont(), "Topic:", guiLeft + 8, guiTop + 22, 0xFF555555, false);
        g.drawString(screen.getFont(), "Payload:", guiLeft + 8, guiTop + 46, 0xFF555555, false);
    }

    private void onSendClicked() {
        String topic = topicField.getValue().trim();
        String payload = payloadField.getValue();

        if (topic.isEmpty()) {
            TabbedMqttMenu menu = screen.getMenu();
            var baseStack = menu.container.getItem(0);
            var subStack = menu.container.getItem(1);
            String base = BaseMqttBlockEntity.parseItemStackTopic(baseStack);
            String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
            topic = sub.isEmpty() ? base : base + "/" + sub;
        }

        if (!topic.isBlank()) {
            NetworkManager.sendToServer(new MineqttNetworking.CyberdeckPublishPayload(topic, payload));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (topicField.keyPressed(keyCode, scanCode, modifiers) || topicField.canConsumeInput()) return true;
        return payloadField.keyPressed(keyCode, scanCode, modifiers) || payloadField.canConsumeInput();
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (topicField.charTyped(c, modifiers)) return true;
        return payloadField.charTyped(c, modifiers);
    }
}

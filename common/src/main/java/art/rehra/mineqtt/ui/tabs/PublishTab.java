package art.rehra.mineqtt.ui.tabs;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class PublishTab implements CyberdeckTab {
    private final CyberdeckScreen screen;
    private EditBox topicField;
    private EditBox payloadField;
    private Button sendButton;

    public PublishTab(CyberdeckScreen screen) {
        this.screen = screen;
    }

    @Override
    public void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        guiGraphics.drawString(screen.getFont(), "Topic:", 8, 60, 0xFF555555, false);
        guiGraphics.drawString(screen.getFont(), "Payload:", 8, 84, 0xFF555555, false);

        var baseStack = this.screen.getMenu().container.getItem(0);
        var subStack = this.screen.getMenu().container.getItem(1);
        String base = BaseMqttBlockEntity.parseItemStackTopic(baseStack);
        String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
        String topic = sub.isEmpty() ? base : base + "/" + sub;

        if (!baseStack.isEmpty()) topicField.setValue(topic);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    @Override
    public void onInit() {
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();

        topicField = new EditBox(screen.getFont(), left + 50, top + 56, 156, 16, Component.literal("Topic"));
        topicField.setMaxLength(256);
        payloadField = new EditBox(screen.getFont(), left + 50, top + 80, 156, 16, Component.literal("Payload"));
        payloadField.setMaxLength(1024);
        sendButton = Button.builder(Component.literal("Send"), b -> onSendClicked())
                .pos(left + 10, top + 104).size(50, 20).build();

        screen.addWidget(topicField);
        screen.addWidget(payloadField);
        screen.addButton(sendButton);

        setPublishControlsVisible(false);
    }

    @Override
    public void onActivated() {
        setPublishControlsVisible(true);
    }

    @Override
    public void onDeactivated() {
        setPublishControlsVisible(false);
    }

    private void setPublishControlsVisible(boolean visible) {
        if (topicField != null) topicField.visible = visible;
        if (payloadField != null) payloadField.visible = visible;
        if (sendButton != null) sendButton.visible = visible;
    }

    @SuppressWarnings("removal")
    private void onSendClicked() {
        String topic = topicField.getValue() != null ? topicField.getValue().trim() : "";
        String payload = payloadField.getValue() != null ? payloadField.getValue() : "";

        if (topic.isEmpty()) {
            // Derive from cyberdeck slots if manual topic is empty
            var baseStack = screen.getMenu().container.getItem(0);
            var subStack = screen.getMenu().container.getItem(1);
            String base = BaseMqttBlockEntity.parseItemStackTopic(baseStack);
            String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
            topic = sub.isEmpty() ? base : base + "/" + sub;
        }

        if (topic == null || topic.isBlank()) {
            // Nothing to publish to
            return;
        }

        final String finalTopic = topic;
        final String finalPayload = payload != null ? payload : "";

        // Send as a networking packet instead of a command to avoid slashes issues
        if (screen.getMinecraft() != null && screen.getMinecraft().level != null) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), screen.getMinecraft().level.registryAccess());
            buf.writeUtf(finalTopic, 512);
            buf.writeUtf(finalPayload, 2048);
            dev.architectury.networking.NetworkManager.sendToServer(art.rehra.mineqtt.network.MineqttNetworking.CYBERDECK_PUBLISH, buf);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // esc >
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            screen.getMinecraft().setScreen(null);
            return true;
        }

        if (this.topicField != null && (this.topicField.keyPressed(keyCode, scanCode, modifiers) || this.topicField.canConsumeInput())) {
            return true;
        }
        return this.payloadField != null && (this.payloadField.keyPressed(keyCode, scanCode, modifiers) || this.payloadField.canConsumeInput());
    }
}

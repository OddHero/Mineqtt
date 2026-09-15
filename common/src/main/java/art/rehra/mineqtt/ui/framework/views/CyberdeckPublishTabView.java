package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.items.CyberdeckItem;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class CyberdeckPublishTabView implements MqttTabView {
    private final TabbedMqttScreen screen;
    private EditBox topicField;
    private EditBox payloadField;
    private Button sendButton;

    public CyberdeckPublishTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    private static String computeDefaultTopic(TabbedMqttMenu menu) {
        var baseStack = menu.container.getItem(0);
        var subStack = menu.container.getItem(1);
        String base = baseStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(baseStack);
        String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
        String topic = sub.isEmpty() ? base : base + "/" + sub;
        if (isHeldCyberdeckPrivate()) {
            String owner = Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.getGameProfile().name() : "";
            if (!owner.isEmpty()) {
                topic = topic.isEmpty() ? owner : owner + "/" + topic;
            }
        }
        return topic;
    }

    private static boolean isHeldCyberdeckPrivate() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var stack = mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof CyberdeckItem)) stack = mc.player.getOffhandItem();
        if (!(stack.getItem() instanceof CyberdeckItem)) return false;
        return CyberdeckDataUtil.isPrivate(stack);
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return List.of(topicField, payloadField, sendButton);
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        g.text(screen.getFont(), "Topic:", guiLeft + 8, guiTop + 22, 0xFF555555, false);
        g.text(screen.getFont(), "Payload:", guiLeft + 8, guiTop + 46, 0xFF555555, false);
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        topicField = new EditBox(screen.getFont(), guiLeft + 50, guiTop + 18, 118, 16, Component.literal("Topic"));
        topicField.setMaxLength(256);
        payloadField = new EditBox(screen.getFont(), guiLeft + 50, guiTop + 42, 118, 16, Component.literal("Payload"));
        payloadField.setMaxLength(1024);
        sendButton = Button.builder(Component.literal("Send"), b -> onSendClicked())
                .pos(guiLeft + 10, guiTop + 66).size(50, 20).build();

        // Default topic from slots (with optional private-mode username prefix).
        topicField.setValue(computeDefaultTopic(screen.getMenu()));
    }

    private void onSendClicked() {
        String topic = topicField.getValue().trim();
        String payload = payloadField.getValue();

        // The server re-applies the private-mode username prefix in private mode,
        // so strip it from the displayed topic before sending to avoid duplication.
        boolean privateMode = isHeldCyberdeckPrivate();
        if (privateMode && Minecraft.getInstance().player != null) {
            String owner = Minecraft.getInstance().player.getGameProfile().name();
            if (topic.equals(owner)) {
                topic = "";
            } else if (topic.startsWith(owner + "/")) {
                topic = topic.substring(owner.length() + 1);
            }
        }

        // Allow sending an empty topic when private-mode is on; the server will
        // prefix with the player's username (becoming just the username).
        if (!topic.isBlank() || privateMode) {
            NetworkManager.sendToServer(new MineqttNetworking.CyberdeckPublishPayload(topic, payload));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        KeyEvent event = new KeyEvent(keyCode, scanCode, modifiers);
        if (topicField.keyPressed(event) || topicField.canConsumeInput()) return true;
        return payloadField.keyPressed(event) || payloadField.canConsumeInput();
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        CharacterEvent event = new CharacterEvent(c);
        if (topicField.charTyped(event)) return true;
        return payloadField.charTyped(event);
    }

    @Override
    public boolean onShiftClickItem(ItemStack stack) {
        if (stack.isEmpty() || payloadField == null) return false;
        // Use the stack's display name (respects custom names / item display name).
        payloadField.setValue(stack.getHoverName().getString());
        payloadField.moveCursorToEnd(false);
        return true;
    }
}

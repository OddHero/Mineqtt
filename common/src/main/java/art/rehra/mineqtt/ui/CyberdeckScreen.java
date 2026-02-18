package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.network.MineqttNetworking;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class CyberdeckScreen extends AbstractContainerScreen<CyberdeckMenu> {

    // Temporary: reuse existing publisher background until a custom cyberdeck texture is added
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "textures/gui/cyberdeck/background.png");
    private static final int MAX_VISIBLE_TOPICS = 8;
    private static final java.util.Map<String, String> DISCOVERED_TOPICS = new java.util.TreeMap<>();
    private Tab activeTab = Tab.EXPLORER;
    // Publisher controls
    private EditBox topicField;
    private EditBox payloadField;
    private Button sendButton;
    private Button listenBtn;
    private Button explorerTabBtn;
    private Button publishTabBtn;
    private int scrollOffset = 0;

    public CyberdeckScreen(CyberdeckMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.font = Minecraft.getInstance().font;
        this.imageWidth = 176;
        this.imageHeight = 222;
    }

    public static void updateTopic(String topic, String payload) {
        DISCOVERED_TOPICS.put(topic, payload);
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - this.imageWidth) / 2;
        int top = (this.height - this.imageHeight) / 2;

        // Tabs
        explorerTabBtn = Button.builder(Component.literal("Explorer"), b -> switchTab(Tab.EXPLORER))
                .pos(left + 8, top + 6).size(70, 20).build();
        publishTabBtn = Button.builder(Component.literal("Publish"), b -> switchTab(Tab.PUBLISH))
                .pos(left + 98, top + 6).size(70, 20).build();
        addRenderableWidget(explorerTabBtn);
        addRenderableWidget(publishTabBtn);

        // Publisher controls
        topicField = new EditBox(this.font, left + 10, top + 56, 156, 16, Component.literal("Topic"));
        topicField.setMaxLength(256);
        payloadField = new EditBox(this.font, left + 10, top + 80, 156, 16, Component.literal("Payload"));
        payloadField.setMaxLength(1024);
        sendButton = Button.builder(Component.literal("Send"), b -> onSendClicked())
                .pos(left + 10, top + 104).size(50, 20).build();

        boolean isListening = CyberdeckDataUtil.isListening(this.menu.itemStack);
        listenBtn = Button.builder(Component.literal(isListening ? "Listen: ON" : "Listen: OFF"), b -> onListenClicked())
                .pos(left + 80, top + 33).size(80, 20).build();

        addRenderableWidget(topicField);
        addRenderableWidget(payloadField);
        addRenderableWidget(sendButton);
        addRenderableWidget(listenBtn);
    }

    @SuppressWarnings("removal")
    private void onListenClicked() {
        boolean currentState = CyberdeckDataUtil.isListening(this.menu.itemStack);
        boolean newState = !currentState;

        // Update local state for immediate feedback
        CyberdeckDataUtil.setListening(this.menu.itemStack, newState);
        listenBtn.setMessage(Component.literal(newState ? "Listen: ON" : "Listen: OFF"));

        // Send to server
        if (this.minecraft != null && this.minecraft.level != null) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.minecraft.level.registryAccess());
            buf.writeBoolean(newState);
            NetworkManager.sendToServer(MineqttNetworking.CYBERDECK_LISTEN_TOGGLE, buf);
        }
    }

    private void switchTab(Tab tab) {
        this.activeTab = tab;
    }

    @SuppressWarnings("removal")
    private void onSendClicked() {
        String topic = topicField.getValue() != null ? topicField.getValue().trim() : "";
        String payload = payloadField.getValue() != null ? payloadField.getValue() : "";

        if (topic.isEmpty()) {
            // Derive from cyberdeck slots if manual topic is empty
            var baseStack = this.menu.container.getItem(0);
            var subStack = this.menu.container.getItem(1);
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
        if (this.minecraft != null && this.minecraft.level != null) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.minecraft.level.registryAccess());
            buf.writeUtf(finalTopic, 512);
            buf.writeUtf(finalPayload, 2048);
            NetworkManager.sendToServer(MineqttNetworking.CYBERDECK_PUBLISH, buf);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component title = Component.literal("Cyberdeck");
        int titleWidth = this.font.width(title);
        int titleX = (this.imageWidth - titleWidth) / 2;
        guiGraphics.drawString(this.font, title, titleX, 6, 0x404040, false);

        if (activeTab == Tab.EXPLORER) {
            topicField.visible = false;
            payloadField.visible = false;
            sendButton.visible = false;
            listenBtn.visible = true;

            int y = 55;
            int startIdx = scrollOffset;
            int count = 0;

            var entries = new java.util.ArrayList<>(DISCOVERED_TOPICS.entrySet());
            for (int i = startIdx; i < entries.size() && count < MAX_VISIBLE_TOPICS; i++) {
                var entry = entries.get(i);
                String line = entry.getKey() + ": " + entry.getValue();
                if (line.length() > 30) line = line.substring(0, 27) + "...";
                guiGraphics.drawString(this.font, line, 8, y, 0xFF333333, false);
                y += 10;
                count++;
            }
            if (entries.isEmpty()) {
                guiGraphics.drawString(this.font, "No topics discovered yet.", 8, 30, 0xFF888888, false);
            }
        } else {
            topicField.visible = true;
            payloadField.visible = true;
            sendButton.visible = true;
            listenBtn.visible = false;
            guiGraphics.drawString(this.font, "Manual Publish", 8, 30, 0xFF555555, false);
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int guiLeft = (this.width - this.imageWidth) / 2;
        int guiTop = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, guiLeft, guiTop, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.topicField.keyPressed(keyCode, scanCode, modifiers) || this.topicField.canConsumeInput()) {
            return true;
        }
        if (this.payloadField.keyPressed(keyCode, scanCode, modifiers) || this.payloadField.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == Tab.EXPLORER) {
            scrollOffset = (int) Math.max(0, scrollOffset - scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private enum Tab {
        EXPLORER, PUBLISH
    }
}

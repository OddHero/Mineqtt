package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.tabs.CyberdeckTab;
import art.rehra.mineqtt.ui.tabs.ExplorerTab;
import art.rehra.mineqtt.ui.tabs.PublishTab;
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
    private static final java.util.Map<String, String> DISCOVERED_TOPICS = new java.util.TreeMap<>();
    private Tab activeTab = Tab.EXPLORER;
    // Publisher controls (owned by screen, toggled by PublishTab)
    private EditBox topicField;
    private EditBox payloadField;
    private Button sendButton;
    // Explorer-specific control is managed in ExplorerTab
    private Button explorerTabBtn;
    private Button publishTabBtn;

    // Tabs
    private CyberdeckTab explorerTab;
    private CyberdeckTab publishTab;

    public CyberdeckScreen(CyberdeckMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.font = Minecraft.getInstance().font;
        this.imageWidth = 256;
        this.imageHeight = 256;
    }

    public static void updateTopic(String topic, String payload) {
        DISCOVERED_TOPICS.put(topic, payload);
    }

    public static String getDiscoveredPayload(String topic) {
        return DISCOVERED_TOPICS.get(topic);
    }

    @Override
    protected void init() {
        super.init();
        int left = getGuiLeft();
        int top = getGuiTop();

        // Tabs
        explorerTabBtn = Button.builder(Component.literal("Explorer"), b -> switchTab(Tab.EXPLORER))
                .pos(left + 8, top + 6).size(70, 20).build();
        publishTabBtn = Button.builder(Component.literal("Publish"), b -> switchTab(Tab.PUBLISH))
                .pos(left + 98, top + 6).size(70, 20).build();
        addRenderableWidget(explorerTabBtn);
        addRenderableWidget(publishTabBtn);

        // Publisher controls (managed by PublishTab visibility)
        topicField = new EditBox(this.font, left + 10, top + 56, 156, 16, Component.literal("Topic"));
        topicField.setMaxLength(256);
        payloadField = new EditBox(this.font, left + 10, top + 80, 156, 16, Component.literal("Payload"));
        payloadField.setMaxLength(1024);
        sendButton = Button.builder(Component.literal("Send"), b -> onSendClicked())
                .pos(left + 10, top + 104).size(50, 20).build();
        addRenderableWidget(topicField);
        addRenderableWidget(payloadField);
        addRenderableWidget(sendButton);
        setPublishControlsVisible(false);

        // Initialize tab instances
        explorerTab = new ExplorerTab(this);
        publishTab = new PublishTab(this);
        explorerTab.onInit();
        publishTab.onInit();

        // Activate default tab
        switchTab(Tab.EXPLORER);
    }


    private void switchTab(Tab tab) {
        // Deactivate current
        CyberdeckTab current = getActiveTab();
        if (current != null) current.onDeactivated();
        this.activeTab = tab;
        // Activate new
        CyberdeckTab next = getActiveTab();
        if (next != null) next.onActivated();
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

        CyberdeckTab tab = getActiveTab();
        if (tab != null) {
            tab.renderLabels(guiGraphics, mouseX, mouseY);
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
        CyberdeckTab tab = getActiveTab();
        if (tab != null && tab.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (this.topicField != null && (this.topicField.keyPressed(keyCode, scanCode, modifiers) || this.topicField.canConsumeInput())) {
            return true;
        }
        if (this.payloadField != null && (this.payloadField.keyPressed(keyCode, scanCode, modifiers) || this.payloadField.canConsumeInput())) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        CyberdeckTab tab = getActiveTab();
        if (tab != null && tab.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
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

    // Helper: determine current active tab instance
    private CyberdeckTab getActiveTab() {
        return switch (activeTab) {
            case EXPLORER -> explorerTab;
            case PUBLISH -> publishTab;
        };
    }

    // Helper: gui left/top
    public int getGuiLeft() {
        return (this.width - this.imageWidth) / 2;
    }

    public int getGuiTop() {
        return (this.height - this.imageHeight) / 2;
    }

    // Expose minimal getters for tabs
    public void setPublishControlsVisible(boolean visible) {
        if (topicField != null) topicField.visible = visible;
        if (payloadField != null) payloadField.visible = visible;
        if (sendButton != null) sendButton.visible = visible;
    }

    public EditBox getTopicField() {
        return this.topicField;
    }

    public EditBox getPayloadField() {
        return this.payloadField;
    }

    public Button getSendButton() {
        return this.sendButton;
    }

    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    public Minecraft getMinecraft() {
        return this.minecraft;
    }

    public CyberdeckMenu getMenu() {
        return this.menu;
    }

    public void addButton(Button button) {
        this.addRenderableWidget(button);
    }
}

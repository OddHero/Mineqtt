package art.rehra.mineqtt.ui.tabs;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;

public class ExplorerTab implements CyberdeckTab {
    private final CyberdeckScreen screen;
    private Button listenBtn;

    public ExplorerTab(CyberdeckScreen screen) {
        this.screen = screen;
    }

    @Override
    public void onInit() {
        int left = this.screen.getGuiLeft();
        int top = this.screen.getGuiTop();

        boolean isListening = CyberdeckDataUtil.isListening(this.screen.getMenu().itemStack);
        listenBtn = Button.builder(Component.literal(isListening ? "Listen: ON" : "Listen: OFF"), b -> onListenClicked())
                .pos(left + 80, top + 33).size(80, 20).build();
        this.screen.addButton(listenBtn);
        listenBtn.visible = false; // will be made visible on activation
    }

    @Override
    public void onActivated() {
        if (listenBtn != null) listenBtn.visible = true;
        // Hide publish-specific widgets via screen helpers
        this.screen.setPublishControlsVisible(false);
    }

    @Override
    public void onDeactivated() {
        if (listenBtn != null) listenBtn.visible = false;
    }

    @Override
    public void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Title handled by screen
        // Show only the payload for the topic derived from item stacks
        var baseStack = this.screen.getMenu().container.getItem(0);
        var subStack = this.screen.getMenu().container.getItem(1);
        String base = BaseMqttBlockEntity.parseItemStackTopic(baseStack);
        String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
        String topic = sub.isEmpty() ? base : base + "/" + sub;

        // Look up latest payload for this topic
        String payload = CyberdeckScreen.getDiscoveredPayload(topic);

        int x = 8;
        int y = 54;
        guiGraphics.drawString(this.screen.getFont(), "Explorer", x, y, 0xFF555555, false);
        y += 12;
        guiGraphics.drawString(this.screen.getFont(), "Topic:", x, y, 0xFF333333, false);
        guiGraphics.drawString(this.screen.getFont(), topic, x + 40, y, 0xFF000000, false);
        y += 12;
        guiGraphics.drawString(this.screen.getFont(), "Payload:", x, y, 0xFF333333, false);

        if (payload == null) {
            guiGraphics.drawString(this.screen.getFont(), "<no data received yet>", x + 48, y, 0xFF888888, false);
        } else {
            // Truncate long payload for now (could be enhanced to wrap)
            String display = payload.length() > 80 ? payload.substring(0, 77) + "..." : payload;
            guiGraphics.drawString(this.screen.getFont(), display, x + 48, y, 0xFF000000, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // No scrollable list anymore
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    private void onListenClicked() {
        boolean currentState = CyberdeckDataUtil.isListening(this.screen.getMenu().itemStack);
        boolean newState = !currentState;

        // Update local state for immediate feedback
        CyberdeckDataUtil.setListening(this.screen.getMenu().itemStack, newState);
        if (listenBtn != null) {
            listenBtn.setMessage(Component.literal(newState ? "Listen: ON" : "Listen: OFF"));
        }

        // Send to server
        if (this.screen.getMinecraft() != null && this.screen.getMinecraft().level != null) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.screen.getMinecraft().level.registryAccess());
            buf.writeBoolean(newState);
            NetworkManager.sendToServer(MineqttNetworking.CYBERDECK_LISTEN_TOGGLE, buf);
        }
    }
}

package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class CyberdeckExplorerTabView implements MqttTabView {
    private final TabbedMqttScreen screen;
    private Button listenBtn;

    public CyberdeckExplorerTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        TabbedMqttMenu menu = screen.getMenu();
        // We need the item stack. We can assume it's CyberdeckMenu if we are here.
        // Actually, we can just check if menu.blockPos is null.
        if (menu.blockPos == null) {
            // Find the cyberdeck in player inventory or hand
            ItemStack cyberdeck = ItemStack.EMPTY;
            if (menu.player.getMainHandItem().getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem) {
                cyberdeck = menu.player.getMainHandItem();
            } else if (menu.player.getOffhandItem().getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem) {
                cyberdeck = menu.player.getOffhandItem();
            }

            if (!cyberdeck.isEmpty()) {
                boolean isListening = CyberdeckDataUtil.isListening(cyberdeck);
                final ItemStack finalCyberdeck = cyberdeck;
                listenBtn = Button.builder(Component.literal(isListening ? "Listen: ON" : "Listen: OFF"), b -> {
                    boolean currentState = CyberdeckDataUtil.isListening(finalCyberdeck);
                    boolean newState = !currentState;
                    CyberdeckDataUtil.setListening(finalCyberdeck, newState);
                    b.setMessage(Component.literal(newState ? "Listen: ON" : "Listen: OFF"));
                    NetworkManager.sendToServer(new MineqttNetworking.CyberdeckListenTogglePayload(newState));
                }).pos(guiLeft + 88, guiTop + 16).size(80, 20).build();
            }
        }
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return listenBtn != null ? List.of(listenBtn) : List.of();
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        var baseStack = menu.container.getItem(0);
        var subStack = menu.container.getItem(1);
        String base = BaseMqttBlockEntity.parseItemStackTopic(baseStack);
        String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
        String topic = sub.isEmpty() ? base : base + "/" + sub;

        // In the new framework, we might need a way to get the discovered payloads.
        // CyberdeckScreen had a static map.
        String payload = CyberdeckScreen.getDiscoveredPayload(topic);

        int contentW = TabbedMqttMenu.GUI_WIDTH - 16;
        int x = guiLeft + 8;
        int y = guiTop + 40;

        g.drawString(screen.getFont(), "Topic:", x, y, 0xFF555555, false);
        GuiText.drawAutoScaled(g, topic, x + 40, y, contentW - 40, 10, 0xFF000000, mouseX, mouseY);

        y += 15;
        g.drawString(screen.getFont(), "Payload:", x, y, 0xFF555555, false);
        if (payload == null) {
            GuiText.drawTruncated(g, "<no data received>", x + 48, y, contentW - 48, 0xFF888888, mouseX, mouseY);
        } else {
            GuiText.drawAutoScaled(g, payload, x + 48, y, contentW - 48, 10, 0xFF000000, mouseX, mouseY);
        }
    }
}

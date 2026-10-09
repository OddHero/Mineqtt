package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.List;

public class CyberdeckExplorerTabView implements MqttTabView {
    private final TabbedMqttScreen screen;
    private Button listenBtn;

    public CyberdeckExplorerTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        if (screen.getMenu() instanceof CyberdeckMenu menu) {
            listenBtn = Button.builder(listenLabel(menu.listening()), b -> {
                boolean listening = !menu.listening();
                menu.setListening(listening);
                b.setMessage(listenLabel(listening));
                NetworkManager.sendToServer(new MineqttNetworking.CyberdeckListenTogglePayload(listening));
            }).pos(guiLeft + 88, guiTop + 16).size(80, 20).build();
        }
    }

    private static Component listenLabel(boolean listening) {
        return Component.translatable(listening ? "mineqtt.tab.cyberdeck_explorer.listen.on" : "mineqtt.tab.cyberdeck_explorer.listen.off");
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return listenBtn != null ? List.of(listenBtn) : List.of();
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
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

        g.text(screen.getFont(), "Topic:", x, y, 0xFF555555, false);
        GuiText.drawAutoScaled(g, topic, x + 40, y, contentW - 40, 10, 0xFF000000, mouseX, mouseY);

        y += 15;
        g.text(screen.getFont(), "Payload:", x, y, 0xFF555555, false);
        if (payload == null) {
            GuiText.drawTruncated(g, "<no data received>", x + 48, y, contentW - 48, 0xFF888888, mouseX, mouseY);
        } else {
            GuiText.drawAutoScaled(g, payload, x + 48, y, contentW - 48, 10, 0xFF000000, mouseX, mouseY);
        }
    }
}

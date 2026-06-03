package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Map;
import java.util.TreeMap;

public class CyberdeckScreen extends TabbedMqttScreen {
    private static final Map<String, String> DISCOVERED_TOPICS = new TreeMap<>();

    public CyberdeckScreen(AbstractContainerMenu menu, Inventory playerInventory, Component title) {
        super((TabbedMqttMenu) menu, playerInventory, title);
    }

    public static void updateTopic(String topic, String payload) {
        DISCOVERED_TOPICS.put(topic, payload);
    }

    public static String getDiscoveredPayload(String topic) {
        return DISCOVERED_TOPICS.get(topic);
    }

    public int getLeftPos() {
        return leftPos;
    }

    public int getTopPos() {
        return topPos;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}

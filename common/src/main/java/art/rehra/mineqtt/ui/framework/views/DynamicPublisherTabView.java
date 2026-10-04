package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.DynamicPublisherBlock;
import art.rehra.mineqtt.blocks.entities.DynamicDataType;
import art.rehra.mineqtt.blocks.entities.DynamicPublisherBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab view for the Dynamic Publisher: provides a data type selector,
 * value preview, publish status, and test-publish button.
 */
public class DynamicPublisherTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<GuiEventListener> widgets = new ArrayList<>();

    public DynamicPublisherTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        // Read current data type from the block entity
        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        int currentDataType = 0;
        if (be instanceof DynamicPublisherBlockEntity dynamicBe) {
            currentDataType = dynamicBe.getDataTypeOrdinal();
        }

        int buttonW = 72;
        int buttonH = 14;
        int gap = 4;
        int totalButtons = DynamicDataType.values().length;
        int totalWidth = totalButtons * buttonW + (totalButtons - 1) * gap;
        int startX = guiLeft + (CONTENT_W - totalWidth) / 2;

        // Data type selector buttons
        int rowY = guiTop + 18;
        for (int i = 0; i < DynamicDataType.values().length; i++) {
            final int index = i;
            DynamicDataType dataType = DynamicDataType.values()[i];
            int btnX = startX + i * (buttonW + gap);

            Button button = Button.builder(
                    Component.translatable(dataType.getTranslationKey()),
                    b -> setDataType(index))
                    .bounds(btnX, rowY, buttonW, buttonH)
                    .build();
            widgets.add(button);
        }

        // Test publish button
        int testBtnX = guiLeft + CONTENT_W - 72;
        int testBtnY = guiTop + 18;
        Button testButton = Button.builder(Component.translatable("mineqtt.dynamic_publisher.test"), b -> testPublish())
                .bounds(testBtnX, testBtnY, 72, 14)
                .build();
        widgets.add(testButton);
    }

    private void setDataType(int ordinal) {
        NetworkManager.sendToServer(new MineqttNetworking.SetDynamicDataTypePayload(
                screen.getMenu().blockPos, ordinal));
    }

    private void testPublish() {
        NetworkManager.sendToServer(new MineqttNetworking.TestDynamicPublishPayload(
                screen.getMenu().blockPos));
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 44; // Below the buttons

        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);

        // Data type info
        DynamicDataType dataType = DynamicDataType.SERVER_TIME;
        String lastStatus = "";
        if (be instanceof DynamicPublisherBlockEntity dynamicBe) {
            dataType = dynamicBe.getDataType();
            lastStatus = dynamicBe.getLastPublishStatus();
        }

        // Data type label
        GuiText.drawTruncated(g, "mineqtt.dynamic_publisher.data_type", x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;
        GuiText.drawTruncated(g, Component.translatable(dataType.getTranslationKey()).getString(), x, y, CONTENT_W, 0xFF0088FF, mouseX, mouseY);
        y += 16;

        // Value preview
        GuiText.drawTruncated(g, "mineqtt.dynamic_publisher.preview", x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;
        String preview = "";
        if (be instanceof DynamicPublisherBlockEntity dynamicBe) {
            preview = dynamicBe.generateCurrentValue();
        }
        if (preview.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.dynamic_publisher.no_preview", x, y, CONTENT_W, 0xFF666666, mouseX, mouseY);
        } else {
            GuiText.drawAutoScaled(g, preview, x, y, CONTENT_W, 60, 0xFF0088FF, mouseX, mouseY);
        }
        y += 16;

        // Last publish status
        GuiText.drawTruncated(g, "mineqtt.dynamic_publisher.status", x, y, CONTENT_W, 0xFFAAAAAA);
        y += 12;
        if (lastStatus.isEmpty()) {
            GuiText.drawTruncated(g, "mineqtt.dynamic_publisher.not_published", x, y, CONTENT_W, 0xFF666666, mouseX, mouseY);
        } else if (lastStatus.startsWith("Error")) {
            GuiText.drawTruncated(g, lastStatus, x, y, CONTENT_W, 0xFFFF5555, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, lastStatus, x, y, CONTENT_W, 0xFF55FF55, mouseX, mouseY);
        }
        y += 14;

        // Power status
        boolean isPowered = false;
        try {
            var state = level.getBlockState(menu.blockPos);
            if (state.hasProperty(DynamicPublisherBlock.POWERED)) {
                isPowered = state.getValue(DynamicPublisherBlock.POWERED);
            }
        } catch (Throwable ignored) {
        }

        String powerStr = isPowered ? "mineqtt.dynamic_publisher.powered" : "mineqtt.dynamic_publisher.unpowered";
        int color = isPowered ? 0xFF55FF55 : 0xFF666666;
        GuiText.drawTruncated(g, "mineqtt.dynamic_publisher.power: " + powerStr, x, y, CONTENT_W, color);
    }
}

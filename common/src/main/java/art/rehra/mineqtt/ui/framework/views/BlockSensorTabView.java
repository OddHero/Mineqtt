package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity;
import art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity.DataMode;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class BlockSensorTabView implements MqttTabView {
    private final TabbedMqttScreen screen;
    private final List<Button> buttons = new ArrayList<>();

    public BlockSensorTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    private BlockSensorBlockEntity sensor() {
        return screen.getMenu().container instanceof BlockSensorBlockEntity sensor ? sensor : null;
    }

    @Override
    public void init(TabbedMqttScreen screen, int left, int top) {
        buttons.clear();
        for (DataMode mode : DataMode.values()) {
            Button button = Button.builder(mode.title(), b -> {
                NetworkManager.sendToServer(new MineqttNetworking.SetBlockSensorModePayload(screen.getMenu().blockPos, mode.id()));
            }).bounds(left + 8, top + 28 + mode.ordinal() * 17, 160, 16).build();
            button.setTooltip(Tooltip.create(Component.translatable("mineqtt.block_sensor.mode." + mode.id() + ".tooltip")));
            buttons.add(button);
        }
    }

    @Override
    public List<Button> widgets() {
        return buttons;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY, float partialTick) {
        BlockSensorBlockEntity sensor = sensor();
        if (sensor == null) return;
        GuiText.drawTruncated(g, Component.translatable("mineqtt.block_sensor.send"), left + 8, top + 18, 160, 0xFF333333);
        for (DataMode mode : DataMode.values()) {
            buttons.get(mode.ordinal()).active = mode != sensor.getDataMode();
        }
    }
}

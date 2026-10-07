package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.BlockEmitterBlock;
import art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab view for the Block Emitter block: provides target block offset preview,
 * last received/executed payload display, execution status, and manual test emit trigger.
 */
public class BlockEmitterTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private final TabbedMqttScreen screen;
    private final List<GuiEventListener> widgets = new ArrayList<>();

    private EditBox testPayloadInput;
    private Button testButton;

    public BlockEmitterTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    private BlockEmitterBlockEntity emitter() {
        return screen.getMenu().container instanceof BlockEmitterBlockEntity emitter ? emitter : null;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();

        TabbedMqttMenu menu = screen.getMenu();
        var level = menu.player.level();
        var be = level.getBlockEntity(menu.blockPos);
        String currentPayload = "";
        if (be instanceof BlockEmitterBlockEntity emitterBe) {
            currentPayload = emitterBe.getLastReceivedPayload();
        }

        int inputX = guiLeft + 8;
        int inputY = guiTop + 18;
        int buttonW = 68;
        int inputW = CONTENT_W - buttonW - 4;
        int inputH = 14;

        testPayloadInput = new EditBox(Minecraft.getInstance().font, inputX, inputY, inputW, inputH, Component.empty());
        testPayloadInput.setHint(Component.translatable("mineqtt.block_emitter.test_hint"));
        testPayloadInput.setMaxLength(2048);
        testPayloadInput.setValue(currentPayload);
        widgets.add(testPayloadInput);

        testButton = Button.builder(Component.translatable("mineqtt.block_emitter.test_button"), b -> testEmit())
                .bounds(guiLeft + CONTENT_W - buttonW, inputY, buttonW, inputH)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.block_emitter.test_tooltip")))
                .build();
        widgets.add(testButton);
    }

    private void testEmit() {
        String payload = testPayloadInput.getValue().trim();
        if (payload.isEmpty()) {
            payload = "minecraft:stone";
        }
        NetworkManager.sendToServer(new MineqttNetworking.TriggerBlockEmitterTestPayload(
                screen.getMenu().blockPos, payload));
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        int x = guiLeft + 8;
        int y = guiTop + 34;

        TabbedMqttMenu menu = screen.getMenu();
        Level level = menu.player.level();
        BlockPos pos = menu.blockPos;
        BlockState state = level.getBlockState(pos);

        Direction facing = state.hasProperty(BlockEmitterBlock.FACING)
                ? state.getValue(BlockEmitterBlock.FACING)
                : Direction.NORTH;
        BlockPos targetPos = pos.relative(facing);
        BlockState targetState = level.getBlockState(targetPos);
        String targetName = targetState.getBlock().getName().getString();

        // Target and Facing preview - compact single line
        String facingName = facing.getName().toUpperCase();
        String targetText = Component.translatable("mineqtt.block_emitter.facing").getString() + ": "
                + facingName + " -> " + targetName;
        GuiText.drawTruncated(g, targetText, x, y, CONTENT_W, 0xFF333333, mouseX, mouseY);
        y += 12;

        // Block Entity values
        BlockEmitterBlockEntity emitter = emitter();
        String lastPayload = emitter != null ? emitter.getLastReceivedPayload() : "";
        String lastStatus = emitter != null ? emitter.getLastStatus() : "Ready";

        // Last Received Payload - compact, max 2 lines
        if (!lastPayload.isEmpty()) {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.block_emitter.last_payload"), x, y, CONTENT_W, 0xFFAAAAAA);
            y += 10;
            GuiText.drawAutoScaled(g, lastPayload, x, y, CONTENT_W, 24, 0xFF0088FF, mouseX, mouseY);
            y += 14;
        }

        // Execution Status - combined with value
        int statusColor = (lastStatus.startsWith("Success") || lastStatus.equals("Ready")) ? 0xFF55FF55 : 0xFFFF5555;
        GuiText.drawTruncated(g, Component.translatable("mineqtt.block_emitter.status") + ": " + lastStatus, x, y, CONTENT_W, statusColor);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (testPayloadInput != null && testPayloadInput.isVisible()) {
            testPayloadInput.setFocused(testPayloadInput.isMouseOver(mouseX, mouseY));
        }
        return false;
    }
}

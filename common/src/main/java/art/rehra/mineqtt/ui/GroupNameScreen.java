package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.network.MineqttNetworking.GroupNamePayload;
import art.rehra.mineqtt.network.MineqttNetworking.GroupPromptPayload;
import art.rehra.mineqtt.ui.framework.GuiText;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * The name prompt of the Configurator's Group mode (M2 plan §4.2 M2-17): the device a player clicked first is in no
 * group, so it and the device they clicked second make a new one, which they name here. Create (or Enter) sends the
 * name; Cancel or Escape closes it, and nothing is made. Client only; the server checks the name and the answer against
 * its own prompt.
 */
public class GroupNameScreen extends Screen {
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 82;
    private static final int MARGIN = 8;
    private static final int FIELD_Y = 30;
    private static final int FIELD_H = 18;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PANEL_OUTLINE = 0xFF3A3A3A;
    private static final int TITLE_COLOR = 0xFF404040;
    private static final int HINT_COLOR = 0xFF555555;

    private final GroupPromptPayload prompt;
    private int left;
    private int top;
    private EditBox nameField;
    private Button createButton;

    public GroupNameScreen(GroupPromptPayload prompt) {
        super(Component.translatable("mineqtt.group_name.title", prompt.source(), prompt.target()));
        this.prompt = prompt;
    }

    /** Opens the prompt the server sent (client). */
    public static void open(GroupPromptPayload prompt) {
        Minecraft.getInstance().gui.setScreen(new GroupNameScreen(prompt));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int inner = PANEL_W - 2 * MARGIN;
        nameField = addRenderableWidget(new EditBox(font, left + MARGIN, top + FIELD_Y, inner, FIELD_H, Component.translatable("mineqtt.group_name.field")));
        nameField.setMaxLength(GroupRules.MAX_NAME_LENGTH);
        nameField.setHint(Component.translatable("mineqtt.group_name.hint").withStyle(EditBox.DEFAULT_HINT_STYLE));
        setInitialFocus(nameField);
        int y = top + FIELD_Y + FIELD_H + GAP + 2;
        int buttonW = (inner - GAP) / 2;
        createButton = addRenderableWidget(Button.builder(Component.translatable("mineqtt.group_name.create"), button -> confirm())
                .bounds(left + MARGIN, y, buttonW, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + PANEL_W - MARGIN - buttonW, y, buttonW, BUTTON_H).build());
    }

    /** What the server asked. */
    public GroupPromptPayload prompt() {
        return prompt;
    }

    /** The name typed so far. */
    public String name() {
        return nameField.getValue();
    }

    // A name the server would refuse as blank is not sent.
    private boolean named() {
        return GroupRules.name(nameField.getValue()).isSuccess();
    }

    private void confirm() {
        if (!named()) return;
        NetworkManager.sendToServer(new GroupNamePayload(prompt.offer(), nameField.getValue().strip()));
        onClose();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, PANEL_OUTLINE);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, PANEL_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        createButton.active = named();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int inner = PANEL_W - 2 * MARGIN;
        GuiText.drawTruncated(g, getTitle(), left + MARGIN, top + 6, inner, TITLE_COLOR, mouseX, mouseY);
        GuiText.drawTruncated(g, Component.translatable("mineqtt.group_name.prompt"), left + MARGIN, top + 18, inner, HINT_COLOR, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            confirm();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

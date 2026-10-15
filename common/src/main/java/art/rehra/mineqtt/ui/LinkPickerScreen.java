package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.items.ConfiguratorLink;
import art.rehra.mineqtt.network.MineqttNetworking.LinkChoicePayload;
import art.rehra.mineqtt.network.MineqttNetworking.LinkPickerPayload;
import art.rehra.mineqtt.ui.framework.GuiText;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * The port picker of the Configurator's Link mode (M2 plan §4.2 M2-16): the wires the server offers from the device a
 * player clicked first to the one they clicked second, or its group, each a pair of ports (the default pair first and
 * selected), with the mappings that can join them. Link makes the selected wire; a pair that is wired already offers
 * Unlink instead. Cancel or Escape closes it without a change, and the device clicked first stays picked. Client only;
 * the server checks every pick against its own offer.
 */
public class LinkPickerScreen extends Screen {
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 132;
    private static final int MARGIN = 8;
    private static final int LIST_Y = 30;
    private static final int ROW_H = 12;
    private static final int VISIBLE_ROWS = 6;
    private static final int LIST_H = VISIBLE_ROWS * ROW_H + 2;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;
    private static final int SCROLLBAR_W = 3;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PANEL_OUTLINE = 0xFF3A3A3A;
    private static final int TITLE_COLOR = 0xFF404040;
    private static final int HINT_COLOR = 0xFF555555;
    private static final int LIST_BORDER = 0xFFA0A0A0;
    private static final int LIST_COLOR = 0xFF000000;
    private static final int HOVER_COLOR = 0xFF2A2A2A;
    private static final int SELECTED_COLOR = 0xFF505050;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TAG_COLOR = 0xFFA0A0A0;
    private static final int LINKED_COLOR = 0xFF55FF55;
    private static final int TOOLTIP_W = 200;

    private final LinkPickerPayload picker;
    private int left;
    private int top;
    private int selected;
    private int scroll;
    // The mapping each choice shows, an index into its mappings.
    private final int[] mappings;
    private Button mappingButton;
    private Button linkButton;

    public LinkPickerScreen(LinkPickerPayload picker) {
        super(Component.translatable("mineqtt.link_picker.title", picker.source(), picker.target()));
        this.picker = picker;
        this.mappings = new int[picker.choices().size()];
    }

    /** Opens the picker the server sent (client). */
    public static void open(LinkPickerPayload picker) {
        if (!picker.choices().isEmpty()) Screens.open(new LinkPickerScreen(picker));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int y = top + LIST_Y + LIST_H + GAP + 2;
        int inner = PANEL_W - 2 * MARGIN;
        int small = (inner - 2 * GAP) / 4;
        int wide = inner - 2 * small - 2 * GAP;
        mappingButton = addRenderableWidget(Button.builder(Component.empty(), button -> stepMapping())
                .bounds(left + MARGIN, y, wide, BUTTON_H).build());
        linkButton = addRenderableWidget(Button.builder(Component.empty(), button -> confirm())
                .bounds(left + MARGIN + wide + GAP, y, small, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + PANEL_W - MARGIN - small, y, small, BUTTON_H).build());
        showSelection();
    }

    /** What the server offered. */
    public LinkPickerPayload picker() {
        return picker;
    }

    /** The index of the selected choice. */
    public int selected() {
        return selected;
    }

    private LinkPickerPayload.Choice choice() {
        return picker.choices().get(selected);
    }

    private Mapping mapping() {
        return choice().mappings().get(mappings[selected]);
    }

    // The buttons follow the selected choice: its mapping, and Link or Unlink.
    private void showSelection() {
        LinkPickerPayload.Choice choice = choice();
        mappingButton.setMessage(Component.translatable("mineqtt.link_picker.mapping", ConfiguratorLink.mappingName(mapping())));
        // A wired pair is removed whatever its mapping shows, and the server takes only the mappings the choice lists.
        mappingButton.active = !choice.wired() && choice.mappings().size() > 1;
        mappingButton.setTooltip(Tooltip.create(Component.translatable("mineqtt.link_picker.mapping." + mapping().name().toLowerCase(Locale.ROOT) + ".tooltip")));
        linkButton.setMessage(Component.translatable(choice.wired() ? "mineqtt.link_picker.unlink" : "mineqtt.link_picker.link"));
        linkButton.setTooltip(choice.wired() ? Tooltip.create(Component.translatable("mineqtt.link_picker.unlink.tooltip")) : null);
    }

    private void stepMapping() {
        mappings[selected] = (mappings[selected] + 1) % choice().mappings().size();
        showSelection();
    }

    private void confirm() {
        NetworkManager.sendToServer(new LinkChoicePayload(picker.offer(), selected, mapping(), choice().wired()));
        onClose();
    }

    private void select(int index) {
        selected = Mth.clamp(index, 0, picker.choices().size() - 1);
        scroll = Mth.clamp(scroll, selected - VISIBLE_ROWS + 1, selected);
        showSelection();
    }

    private int maxScroll() {
        return Math.max(0, picker.choices().size() - VISIBLE_ROWS);
    }

    // The choice under the mouse, or -1.
    private int rowAt(double mouseX, double mouseY) {
        int x = left + MARGIN + 1;
        int y = top + LIST_Y + 1;
        if (mouseX < x || mouseX >= left + PANEL_W - MARGIN - 1 || mouseY < y || mouseY >= y + VISIBLE_ROWS * ROW_H) return -1;
        int index = scroll + (int) ((mouseY - y) / ROW_H);
        return index < picker.choices().size() ? index : -1;
    }

    /** The text a choice's row shows: {@code powered → state}, a group's port as {@code Lamps.state}. */
    private static Component label(LinkPickerPayload.Choice choice) {
        String to = choice.group().isEmpty() ? choice.to() : choice.group() + "." + choice.to();
        return Component.translatable("mineqtt.link_picker.pair", choice.from(), to);
    }

    private static Component tooltip(LinkPickerPayload picker, LinkPickerPayload.Choice choice) {
        String target = choice.group().isEmpty() ? picker.target() : choice.group();
        Component pair = Component.translatable("mineqtt.link_picker.pair.tooltip", picker.source(), choice.from(), choice.fromKind(), target, choice.to(), choice.toKind());
        if (!choice.group().isEmpty()) pair = Component.translatable("mineqtt.link_picker.group.tooltip", pair, choice.group());
        return choice.wired() ? Component.translatable("mineqtt.link_picker.wired.tooltip", pair) : pair;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, PANEL_OUTLINE);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, PANEL_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int inner = PANEL_W - 2 * MARGIN;
        GuiText.drawTruncated(g, getTitle(), left + MARGIN, top + 6, inner, TITLE_COLOR, mouseX, mouseY);
        GuiText.drawTruncated(g, Component.translatable("mineqtt.link_picker.hint"), left + MARGIN, top + 18, inner, HINT_COLOR, mouseX, mouseY);

        int x = left + MARGIN;
        int y = top + LIST_Y;
        g.fill(x, y, x + inner, y + LIST_H, LIST_BORDER);
        g.fill(x + 1, y + 1, x + inner - 1, y + LIST_H - 1, LIST_COLOR);
        boolean scrolls = maxScroll() > 0;
        int rowW = inner - 2 - (scrolls ? SCROLLBAR_W + 1 : 0);
        int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < VISIBLE_ROWS && scroll + row < picker.choices().size(); row++) {
            int index = scroll + row;
            LinkPickerPayload.Choice choice = picker.choices().get(index);
            int rowY = y + 1 + row * ROW_H;
            if (index == selected) {
                g.fill(x + 1, rowY, x + 1 + rowW, rowY + ROW_H, SELECTED_COLOR);
            } else if (index == hovered) {
                g.fill(x + 1, rowY, x + 1 + rowW, rowY + ROW_H, HOVER_COLOR);
            }
            Component tag = choice.wired() ? Component.translatable("mineqtt.link_picker.tag.linked")
                    : choice.isDefault() ? Component.translatable("mineqtt.link_picker.tag.default") : Component.empty();
            int tagW = tag.getString().isEmpty() ? 0 : font.width(tag) + GAP;
            GuiText.drawTruncated(g, label(choice), x + 4, rowY + 2, rowW - 6 - tagW, TEXT_COLOR);
            if (tagW > 0) GuiText.drawRight(g, tag.getString(), x + 1 + rowW - 3, rowY + 2, tagW, choice.wired() ? LINKED_COLOR : TAG_COLOR);
        }
        if (scrolls) {
            int trackX = x + inner - 1 - SCROLLBAR_W;
            int trackH = VISIBLE_ROWS * ROW_H;
            int thumbH = Math.max(8, trackH * VISIBLE_ROWS / picker.choices().size());
            int thumbY = y + 1 + (trackH - thumbH) * scroll / maxScroll();
            g.fill(trackX, y + 1, trackX + SCROLLBAR_W, y + 1 + trackH, HOVER_COLOR);
            g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, LIST_BORDER);
        }
        if (hovered >= 0) g.setTooltipForNextFrame(font, font.split(tooltip(picker, picker.choices().get(hovered)), TOOLTIP_W), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int row = rowAt(event.x(), event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && row >= 0) {
            select(row);
            if (doubleClick) confirm();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (dy != 0 && maxScroll() > 0) {
            scroll = Mth.clamp(scroll - (int) Math.signum(dy), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_UP -> select(selected - 1);
            case InputConstants.KEY_DOWN -> select(selected + 1);
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> confirm();
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

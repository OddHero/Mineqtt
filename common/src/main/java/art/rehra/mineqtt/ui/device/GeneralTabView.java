package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Draws the {@link GeneralTab} from the device's {@link DeviceView}: a name field with a live preview of the slug and the
 * state topic the name gives, the topic with a copy button, the room (M4 plan D11) with what Home Assistant keeps when it
 * differs, the group, and a button that steps the Home Assistant
 * exposure through on, off and default next to whether Home Assistant discovers the device now. The name is sent with
 * Enter or Save, never on its own; the server answers with a new view, which a name the player is still typing survives.
 * A control the view's grants do not allow is read-only, and says why on hover. For a type that can be grouped the group
 * is a button that turns the tab to its group pages ({@link GroupPages}, M2-17).
 */
public class GeneralTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int ROW_H = 14;
    private static final int NAME_Y = 17;
    private static final int SLUG_Y = 34;
    private static final int TOPIC_Y = 44;
    // The topic wraps onto a second line rather than shrinking out of legibility.
    private static final int TOPIC_H = 18;
    // The device's room (M4 plan D11), the area Home Assistant is told for it.
    private static final int ROOM_Y = 63;
    // The group row also holds the topic's copy button, right under the room line.
    private static final int GROUP_Y = 73;
    private static final int EXPOSE_Y = 88;
    private static final int GAP = 4;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int VALUE_COLOR = 0xFF303030;
    private static final int PREVIEW_COLOR = 0xFF0066CC;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int DISCOVERED_COLOR = 0xFF2E7D32;
    // A room whose area Home Assistant does not take for the device, which it created before.
    private static final int KEPT_AREA_COLOR = 0xFFB26A00;
    private static final long COPIED_MILLIS = 1500;

    private final TabbedMqttScreen screen;
    private final GeneralTab tab;
    private EditBox nameField;
    private Button saveButton;
    private Button copyButton;
    private Button exposeButton;
    // The group pages and the button that opens them, for a type that can be grouped.
    private @Nullable Button groupButton;
    private @Nullable GroupPages groupPages;
    private int valueX;
    private int fieldW;
    // The view the widgets were last brought up to date with, and the name the field showed then.
    private @Nullable DeviceView synced;
    private String syncedName = "";
    private long copiedUntil;

    public GeneralTabView(TabbedMqttScreen screen) {
        this.screen = screen;
        this.tab = (GeneralTab) screen.getMenu().tabs.stream().filter(GeneralTab.class::isInstance).findFirst().orElseThrow();
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        Font font = GuiText.font();
        int labelW = Stream.of("name", "slug", "topic", "room", "group")
                .mapToInt(label -> font.width(Component.translatable("mineqtt.device.general." + label))).max().orElse(0);
        valueX = guiLeft + 8 + labelW + GAP + 2;
        int right = guiLeft + 8 + CONTENT_W;

        Component save = Component.translatable("mineqtt.device.general.save");
        int saveW = font.width(save) + 10;
        saveButton = Button.builder(save, button -> commitName()).bounds(right - saveW, guiTop + NAME_Y, saveW, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.device.general.save.tooltip"))).build();
        fieldW = right - saveW - GAP - valueX;
        nameField = new EditBox(font, valueX, guiTop + NAME_Y, fieldW, ROW_H, Component.translatable("mineqtt.device.general.name"));
        nameField.setMaxLength(tab.name().maxLength());
        nameField.setTextColorUneditable(MUTED_COLOR);

        Component copy = Component.translatable("mineqtt.device.general.copy");
        int copyW = Math.max(font.width(copy), font.width(Component.translatable("mineqtt.device.general.copied"))) + 10;
        copyButton = Button.builder(copy, button -> copyTopic()).bounds(right - copyW, guiTop + GROUP_Y, copyW, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.device.general.copy.tooltip"))).build();

        int exposeW = tab.expose().values().stream()
                .mapToInt(value -> font.width(Component.translatable("mineqtt.device.general.expose." + value))).max().orElse(0) + 12;
        exposeButton = Button.builder(Component.empty(), button -> stepExpose()).bounds(guiLeft + 8, guiTop + EXPOSE_Y, exposeW, ROW_H).build();

        groupButton = null;
        groupPages = null;
        if (tab.grouping()) {
            groupButton = Button.builder(Component.empty(), button -> showGroup(true)).bounds(valueX, guiTop + GROUP_Y, copyButton.getX() - GAP - valueX, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.device.general.group.tooltip"))).build();
            groupPages = new GroupPages((DeviceMenu) screen.getMenu());
            groupPages.init(guiLeft, guiTop, () -> showGroup(false));
        }

        synced = null;
        view().ifPresent(this::sync);
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        List<GuiEventListener> widgets = new ArrayList<>(List.of(nameField, saveButton, copyButton, exposeButton));
        if (groupButton != null) widgets.add(groupButton);
        if (groupPages != null) widgets.addAll(groupPages.widgets());
        return widgets;
    }

    // Whether the group pages show instead of the General page.
    private boolean onGroupPages() {
        return groupPages != null && groupPages.showing();
    }

    /** Turns the tab to the group pages, or back to the General page; a field being typed in lets go of the keys. */
    private void showGroup(boolean group) {
        if (groupPages == null) return;
        groupPages.show(group ? GroupPages.Page.GROUP : null);
        if (group) nameField.setFocused(false);
        showGeneral();
    }

    // The General page's widgets show while it does; Save only to a player who may configure the device.
    private void showGeneral() {
        boolean general = !onGroupPages();
        nameField.visible = general;
        saveButton.visible = general && synced != null && tab.name().editable(synced.grants());
        copyButton.visible = general;
        exposeButton.visible = general;
        if (groupButton != null) groupButton.visible = general;
    }

    private Optional<DeviceView> view() {
        return screen.getMenu().deviceView();
    }

    /**
     * Brings the widgets up to date with a new view: the name the device has now replaces the field's text unless the
     * player changed it since the last view, and the grants decide what is read-only.
     */
    private void sync(DeviceView view) {
        if (synced == null || nameField.getValue().equals(syncedName)) {
            nameField.setValue(view.name());
            nameField.moveCursorToStart(false);
        }
        synced = view;
        syncedName = shown(view.name());
        boolean configure = tab.name().editable(view.grants());
        nameField.setEditable(configure);
        // Nor does it take the focus, so no cursor suggests it would.
        nameField.active = configure;
        // Without the Save button the field takes its place.
        nameField.setWidth(configure ? fieldW : fieldW + saveButton.getWidth() + GAP);
        nameField.setTooltip(configure ? null : Tooltip.create(Component.translatable("mineqtt.device.general.read_only")));
        showGeneral();
        exposeButton.setMessage(Component.translatable("mineqtt.device.general.expose." + DeviceTabs.exposeValue(view)));
        exposeButton.active = tab.expose().editable(view.grants());
        exposeButton.setTooltip(Tooltip.create(Component.translatable(exposeButton.active ? "mineqtt.device.general.expose.tooltip"
                : configure ? "mineqtt.device.general.expose.denied" : "mineqtt.device.general.read_only")));
    }

    /**
     * A name as the field shows it. Only the screen keeps names to the field's length: one set by command, a copy's
     * " (copy)" or a v1 topic can be longer, and the field shows its start.
     */
    private String shown(String name) {
        int max = tab.name().maxLength();
        return name.length() > max ? name.substring(0, max) : name;
    }

    // What the player typed: the device's name while the field still shows it, though cut to the field's length.
    private String typedName(DeviceView view) {
        String value = nameField.getValue();
        return value.equals(shown(view.name())) ? view.name() : value;
    }

    // The name as the server would keep it, when it differs from the device's and is not blank.
    private Optional<String> pendingName(DeviceView view) {
        String name = DeviceTabs.name(typedName(view));
        return name.isEmpty() || name.equals(view.name()) ? Optional.empty() : Optional.of(name);
    }

    private void commitName() {
        DeviceView view = synced;
        if (view == null || !tab.name().editable(view.grants())) return;
        pendingName(view).ifPresent(name -> {
            // The field shows the name as sent, so the server's answer replaces it.
            nameField.setValue(name);
            syncedName = name;
            NetworkManager.sendToServer(new DeviceConfigPayload(view.pos(), tab.name().key(), StringTag.valueOf(name)));
        });
    }

    private void stepExpose() {
        DeviceView view = synced;
        if (view == null || !tab.expose().editable(view.grants())) return;
        String next = tab.expose().next(DeviceTabs.exposeValue(view));
        NetworkManager.sendToServer(new DeviceConfigPayload(view.pos(), tab.expose().key(), StringTag.valueOf(next)));
    }

    // The device's state topic, never the preview of a name not saved yet, which nothing publishes to.
    private void copyTopic() {
        if (synced == null) return;
        synced.registration().topic(synced.slug()).ifPresent(topic -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(topic);
            copiedUntil = Util.getMillis() + COPIED_MILLIS;
        });
    }

    // The state topic the slug in the field gives: the one the device has, or the one it gets when the name is saved.
    private Optional<String> topic(DeviceView view) {
        return DeviceTabs.slugPreview(typedName(view), view).flatMap(slug -> view.registration().topic(slug));
    }

    // A player who may not configure the device sees why the fields do not take their typing, on every page.
    @Override
    public Optional<Component> titleNote() {
        return synced != null && !tab.name().editable(synced.grants())
                ? Optional.of(Component.translatable("mineqtt.device.general.read_only.short")) : Optional.empty();
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        Optional<DeviceView> current = view();
        if (current.isEmpty()) return;
        DeviceView view = current.get();
        if (view != synced) sync(view);
        if (onGroupPages()) {
            groupPages.render(g, view, mouseX, mouseY);
            return;
        }

        int x = guiLeft + 8;
        int valueW = guiLeft + 8 + CONTENT_W - valueX;
        boolean pending = pendingName(view).isPresent();
        saveButton.active = pending;
        int valueColor = pending ? PREVIEW_COLOR : VALUE_COLOR;

        label(g, "name", x, guiTop + NAME_Y + 3);
        label(g, "slug", x, guiTop + SLUG_Y);
        Optional<String> slug = DeviceTabs.slugPreview(typedName(view), view);
        if (slug.isPresent()) {
            GuiText.drawTruncated(g, slug.get(), valueX, guiTop + SLUG_Y, valueW, valueColor, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.general.slug.automatic"), valueX, guiTop + SLUG_Y, valueW, valueColor, mouseX, mouseY);
        }
        if (pending && hovered(mouseX, mouseY, valueX, guiTop + SLUG_Y, valueW)) {
            g.setTooltipForNextFrame(GuiText.font(), Component.translatable("mineqtt.device.general.slug.preview"), mouseX, mouseY);
        }

        label(g, "topic", x, guiTop + TOPIC_Y);
        Optional<String> topic = topic(view);
        int topicW = valueW;
        if (topic.isPresent()) {
            drawTopic(g, topic.get(), valueX, guiTop + TOPIC_Y, topicW, valueColor, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, Component.translatable(view.registration().topicRoot().isEmpty()
                    ? "mineqtt.device.general.topic.unregistered" : "mineqtt.device.general.topic.unknown"), valueX, guiTop + TOPIC_Y, topicW, MUTED_COLOR);
        }
        copyButton.active = view.registration().topic(view.slug()).isPresent();
        copyButton.setMessage(Component.translatable(Util.getMillis() < copiedUntil ? "mineqtt.device.general.copied" : "mineqtt.device.general.copy"));

        // The room, and when Home Assistant keeps another area for the device (it applies one only when it creates a
        // device), a mark and what to change there on hover.
        label(g, "room", x, guiTop + ROOM_Y);
        Optional<Component> roomHint = DeviceTabs.roomHint(view);
        Component room = DeviceTabs.roomValue(view);
        int roomColor = roomHint.isPresent() ? KEPT_AREA_COLOR : view.registration().room().isPresent() ? VALUE_COLOR : MUTED_COLOR;
        GuiText.drawTruncated(g, roomHint.isPresent() ? Component.translatable("mineqtt.device.general.room.differs", room) : room,
                valueX, guiTop + ROOM_Y, valueW, roomColor);
        if (hovered(mouseX, mouseY, valueX, guiTop + ROOM_Y, valueW)) {
            g.setTooltipForNextFrame(GuiText.font(), roomHint.orElse(Component.translatable("mineqtt.device.general.room.tooltip")), mouseX, mouseY);
        }

        label(g, "group", x, guiTop + GROUP_Y + 3);
        int groupW = copyButton.getX() - GAP - valueX;
        if (groupButton != null) {
            groupButton.setMessage(Component.translatable("mineqtt.device.general.group.open", view.registration().group()
                    .<Component>map(Component::literal).orElse(Component.translatable("mineqtt.device.general.group.none"))));
        } else if (view.registration().group().isPresent()) {
            GuiText.drawTruncated(g, view.registration().group().get(), valueX, guiTop + GROUP_Y + 3, groupW, VALUE_COLOR, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.general.group.none"), valueX, guiTop + GROUP_Y + 3, groupW, MUTED_COLOR);
        }

        int discoveredX = exposeButton.getX() + exposeButton.getWidth() + GAP + 2;
        boolean discovered = view.registration().discovered();
        int discoveredW = x + CONTENT_W - discoveredX;
        GuiText.drawAutoScaled(g, Component.translatable(discovered ? "mineqtt.device.general.discovered" : "mineqtt.device.general.not_discovered").getString(),
                discoveredX, guiTop + EXPOSE_Y, discoveredW, ROW_H, discovered ? DISCOVERED_COLOR : MUTED_COLOR, mouseX, mouseY);
        if (hovered(mouseX, mouseY, discoveredX, guiTop + EXPOSE_Y + 3, discoveredW)) {
            g.setTooltipForNextFrame(GuiText.font(), Component.translatable("mineqtt.device.general.discovery.tooltip"), mouseX, mouseY);
        }
    }

    /**
     * A topic on up to two lines that break after a {@code /}, so its levels stay whole; one that does not fit that way
     * wraps anywhere and shrinks, with the full topic on hover.
     */
    static void drawTopic(GuiGraphicsExtractor g, String topic, int x, int y, int width, int color, int mouseX, int mouseY) {
        Font font = GuiText.font();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String level : topic.split("(?<=/)")) {
            if (!line.isEmpty() && font.width(line + level) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(level);
        }
        lines.add(line.toString());
        if (lines.size() * font.lineHeight > TOPIC_H || lines.stream().anyMatch(text -> font.width(text) > width)) {
            GuiText.drawWrappedScaled(g, topic, x, y, width, TOPIC_H, color, mouseX, mouseY);
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x, y + i * font.lineHeight, color, false);
        }
    }

    private static void label(GuiGraphicsExtractor g, String key, int x, int y) {
        GuiText.drawTruncated(g, Component.translatable("mineqtt.device.general." + key), x, y, CONTENT_W, LABEL_COLOR);
    }

    private static boolean hovered(int mouseX, int mouseY, int x, int y, int width) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + GuiText.font().lineHeight;
    }

    /** Enter saves the name; while the field has the focus it takes every other key but Escape, which closes the screen. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (onGroupPages()) return groupPages.keyPressed(keyCode, scanCode, modifiers);
        if (nameField == null || !nameField.isFocused() || keyCode == InputConstants.KEY_ESCAPE) return false;
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            commitName();
            return true;
        }
        return nameField.keyPressed(new KeyEvent(keyCode, scanCode, modifiers)) || nameField.canConsumeInput();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A click elsewhere ends the typing, so the screen's keys work again (the inventory key closes it).
        if (nameField != null && !nameField.isMouseOver(mouseX, mouseY)) nameField.setFocused(false);
        if (groupPages != null) groupPages.mouseClicked(mouseX, mouseY);
        return false;
    }

    // The wheel scrolls the join page.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        return groupPages != null && groupPages.mouseScrolled(dy);
    }
}

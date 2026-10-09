package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceLinks;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.items.ConfiguratorLink;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceUnlinkPayload;
import art.rehra.mineqtt.ui.device.widgets.IntSlider;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Draws the {@link AdvancedTab} from the device's {@link DeviceView}: the Raw Link's text field with the mode it sends
 * in (a channel the server resolves, a private channel, or a topic used as typed), the topic the link listens on or
 * sends to as the server resolved it, or why it is refused, an output's retain (its type's default shown) and QoS, and
 * the custom payloads. A text field is sent with Enter or Save, never on its own, as on the General tab; a new view
 * replaces what a field shows unless the player changed it since. The switch and the slider send at once. A change the
 * server refuses shows its reason in place of the topic until the tab sends again, the mode replaces the field's text,
 * or the link changes by any other path ({@link DeviceMenu#refusal}). A control the view's grants do not allow is
 * read-only, and says why on hover.
 * <p>
 * The Links button at the foot turns the tab to the device's links ({@link DeviceMenu#links}, M2-16): every wire from or
 * to the device, or to its group, one row each with the port at this end and the device and port at the other, its
 * mapping on hover, and a button that removes it for a player who may configure the device. The list scrolls with the
 * mouse wheel, as the device screen keeps one height for every tab.
 */
public class AdvancedTabView implements MqttTabView {

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int FIRST_ROW_Y = 17;
    private static final int ROW_H = 14;
    private static final int ROW_GAP = 2;
    private static final int GAP = 4;
    // The topic takes up to two lines rather than shrinking out of legibility, as on the General tab.
    private static final int STATUS_H = 18;
    private static final int QOS_W = 50;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int VALUE_COLOR = 0xFF303030;
    private static final int PREVIEW_COLOR = 0xFF0066CC;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int REFUSED_COLOR = 0xFFAA0000;
    // The links page: a header row, then rows of the Raw Link page's height and pitch, as many as end by y 100 (P1).
    private static final int LINK_ROWS = 4;
    private static final int LINK_ROW_PITCH = ROW_H + ROW_GAP;
    private static final int REMOVE_W = 14;
    private static final int SCROLLBAR_W = 3;
    private static final int SCROLL_TRACK = 0xFF8B8B8B;
    private static final int SCROLL_THUMB = 0xFF555555;
    private static final int TOOLTIP_W = 200;

    /** The tab's two pages: the Raw Link editor, and the device's links. */
    private enum Page {
        RAW_LINK,
        LINKS
    }

    private final TabbedMqttScreen screen;
    private final AdvancedTab tab;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<EditBox> fields = new ArrayList<>();
    private int left;
    private int offLabelX;
    private int statusY;
    private int top;
    private Page page = Page.RAW_LINK;
    private int linkScroll;
    private AdvancedTab.Mode mode = AdvancedTab.Mode.CHANNEL;
    private @Nullable Button modeButton;
    private @Nullable EditBox linkField;
    private @Nullable Button saveButton;
    private @Nullable Button retainButton;
    private @Nullable IntSlider qosSlider;
    private @Nullable EditBox onField;
    private @Nullable EditBox offField;
    private @Nullable Button linksButton;
    private @Nullable Button backButton;
    // The Raw Link page's widgets, hidden on the links page, and the links page's remove buttons, one per visible row.
    private final List<AbstractWidget> rawWidgets = new ArrayList<>();
    private final List<Button> removeButtons = new ArrayList<>();
    // The view the widgets were last brought up to date with, and what each field showed of it then.
    private @Nullable DeviceView synced;
    // The links list the remove buttons were last brought up to date with.
    private @Nullable DeviceLinks syncedLinks;
    private String syncedLink = "";
    private String syncedOn = "";
    private String syncedOff = "";

    public AdvancedTabView(TabbedMqttScreen screen) {
        this.screen = screen;
        this.tab = (AdvancedTab) screen.getMenu().tabs.stream().filter(AdvancedTab.class::isInstance).findFirst().orElseThrow();
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        widgets.clear();
        fields.clear();
        rawWidgets.clear();
        removeButtons.clear();
        modeButton = null;
        linkField = null;
        saveButton = null;
        retainButton = null;
        qosSlider = null;
        onField = null;
        offField = null;
        Font font = GuiText.font();
        left = guiLeft + 8;
        top = guiTop;
        int right = left + CONTENT_W;
        int y = guiTop + FIRST_ROW_Y;
        if (tab.spec().isPresent()) {
            int modeW = List.of(AdvancedTab.Mode.values()).stream().mapToInt(value -> font.width(modeLabel(value))).max().orElse(0) + 10;
            modeButton = add(Button.builder(Component.empty(), button -> stepMode()).bounds(left, y, modeW, ROW_H).build());
            // The topic gets the rest of the row; Save sits at the foot of the form, as it saves every field.
            int fieldX = left + modeW + GAP;
            linkField = field(fieldX, y, right - fieldX, mode.key(), "mineqtt.device.advanced.link");
            y += ROW_H + ROW_GAP + 1;
            statusY = y;
            y += STATUS_H + ROW_GAP;
            Optional<DeviceTabs.Control> retain = tab.control(LinkSetting.RAW_RETAIN);
            Optional<DeviceTabs.Control> qos = tab.control(LinkSetting.RAW_QOS);
            if (qos.isPresent()) {
                // An output of events is never retained: its QoS has the row to itself.
                int qosW = retain.isPresent() ? QOS_W : CONTENT_W;
                if (retain.isPresent()) {
                    retainButton = add(Button.builder(Component.empty(), button -> stepRetain()).bounds(left, y, CONTENT_W - GAP - QOS_W, ROW_H).build());
                }
                DeviceTabs.Control control = qos.get();
                qosSlider = add(new IntSlider(right - qosW, y, qosW, ROW_H, control.min(), control.max(), 1, control.min(),
                        number -> Component.translatable("mineqtt.device.advanced.qos", number), number -> send(control, control.leveled(number))));
                y += ROW_H + ROW_GAP;
            }
            if (tab.control(LinkSetting.RAW_ON).isPresent()) {
                int onW = font.width(Component.translatable("mineqtt.device.advanced.on"));
                int offW = font.width(Component.translatable("mineqtt.device.advanced.off"));
                int payloadW = (CONTENT_W - onW - offW - 2 * 3 - GAP) / 2;
                onField = field(left + onW + 3, y, payloadW, LinkSetting.RAW_ON.key(), "mineqtt.device.advanced.on");
                offField = field(right - payloadW, y, payloadW, LinkSetting.RAW_OFF.key(), "mineqtt.device.advanced.off");
                offLabelX = offField.getX() - 3 - offW;
                onField.setHint(Component.translatable("mineqtt.device.advanced.payload.default").withStyle(EditBox.DEFAULT_HINT_STYLE));
                offField.setHint(Component.translatable("mineqtt.device.advanced.payload.default").withStyle(EditBox.DEFAULT_HINT_STYLE));
                y += ROW_H + ROW_GAP;
            }
            Component save = Component.translatable("mineqtt.device.general.save");
            int saveW = font.width(save) + 10;
            saveButton = add(Button.builder(save, button -> commitAll()).bounds(right - saveW, y, saveW, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.device.advanced.save.tooltip"))).build());
        } else {
            statusY = y;
            y += GuiText.font().lineHeight + ROW_GAP;
        }
        rawWidgets.addAll(widgets);
        // Room for the largest count it shows, so the button never changes its width.
        int linksW = font.width(Component.translatable("mineqtt.device.advanced.links.count", DeviceLinks.MAX_ROWS + "+")) + 10;
        linksButton = add(Button.builder(Component.empty(), button -> showPage(Page.LINKS)).bounds(left, y, linksW, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.device.advanced.links.tooltip"))).build());
        rawWidgets.add(linksButton);

        int headerY = guiTop + FIRST_ROW_Y;
        Component back = Component.translatable("mineqtt.device.links.back");
        backButton = add(Button.builder(back, button -> showPage(Page.RAW_LINK)).bounds(left, headerY, font.width(back) + 10, ROW_H).build());
        for (int row = 0; row < LINK_ROWS; row++) {
            int index = row;
            int rowY = headerY + (row + 1) * LINK_ROW_PITCH;
            removeButtons.add(add(Button.builder(Component.translatable("mineqtt.device.links.remove"), button -> remove(index))
                    .bounds(right - SCROLLBAR_W - 2 - REMOVE_W, rowY, REMOVE_W, ROW_H).build()));
        }
        synced = null;
        view().ifPresent(this::sync);
        showPage(page);
    }

    /** Shows one of the tab's pages: the other's widgets hide, and a field being typed in lets go of the keys. */
    private void showPage(Page shown) {
        page = shown;
        rawWidgets.forEach(widget -> widget.visible = page == Page.RAW_LINK);
        if (saveButton != null) saveButton.visible = saveVisible();
        fields.forEach(field -> field.setFocused(false));
        if (backButton != null) backButton.visible = page == Page.LINKS;
        showRemoveButtons();
    }

    // Save shows on the Raw Link page to a player who may configure the device.
    private boolean saveVisible() {
        return page == Page.RAW_LINK && saveButton != null && synced != null && control(mode.key()).editable(synced.grants());
    }

    private List<DeviceLinks.Row> linkRows() {
        return menu().links().map(DeviceLinks::rows).orElse(List.of());
    }

    // The remove buttons stand by the rows shown; active for a player who may configure the device.
    private void showRemoveButtons() {
        List<DeviceLinks.Row> rows = linkRows();
        linkScroll = Mth.clamp(linkScroll, 0, Math.max(0, rows.size() - LINK_ROWS));
        boolean configure = synced != null && synced.grants().configure();
        for (int row = 0; row < removeButtons.size(); row++) {
            Button button = removeButtons.get(row);
            button.visible = page == Page.LINKS && linkScroll + row < rows.size();
            button.active = configure;
            button.setTooltip(Tooltip.create(Component.translatable(configure ? "mineqtt.device.links.remove.tooltip" : "mineqtt.device.general.read_only")));
        }
    }

    // Removes the link a remove button stands by; the server's new list takes the row away.
    private void remove(int row) {
        List<DeviceLinks.Row> rows = linkRows();
        DeviceView view = synced;
        if (view == null || !view.grants().configure() || linkScroll + row >= rows.size()) return;
        menu().forgetRefusal();
        NetworkManager.sendToServer(new DeviceUnlinkPayload(view.pos(), rows.get(linkScroll + row).wire()));
    }

    private <W extends AbstractWidget> W add(W widget) {
        widgets.add(widget);
        return widget;
    }

    private EditBox field(int x, int y, int width, String key, String narration) {
        EditBox field = add(new EditBox(GuiText.font(), x, y, width, ROW_H, Component.translatable(narration)));
        field.setMaxLength(control(key).maxLength());
        field.setTextColorUneditable(MUTED_COLOR);
        fields.add(field);
        return field;
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return widgets;
    }

    private Optional<DeviceView> view() {
        return screen.getMenu().deviceView();
    }

    private DeviceTabs.Control control(String key) {
        return tab.controls().stream().filter(control -> control.key().equals(key)).findFirst().orElseThrow();
    }

    private static Component modeLabel(AdvancedTab.Mode mode) {
        return Component.translatable("mineqtt.device.advanced.mode." + modeKey(mode));
    }

    private static String modeKey(AdvancedTab.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Brings the widgets up to date with a new view: what the link has now replaces what a field shows unless the player
     * changed it since the last view, and the grants decide what is read-only. The editor opens in the mode of the link.
     */
    private void sync(DeviceView view) {
        if (linkField == null) {
            synced = view;
            showRemoveButtons();
            return;
        }
        boolean first = synced == null;
        if (first) mode = AdvancedTab.Mode.of(view);
        syncedLink = follow(linkField, first, syncedLink, mode.text(view.raw()));
        if (onField != null && offField != null) {
            syncedOn = follow(onField, first, syncedOn, view.raw().flatMap(DeviceView.Raw::on).orElse(""));
            syncedOff = follow(offField, first, syncedOff, view.raw().flatMap(DeviceView.Raw::off).orElse(""));
        }
        synced = view;
        boolean configure = control(mode.key()).editable(view.grants());
        boolean linked = view.raw().isPresent();
        Tooltip readOnly = configure ? null : Tooltip.create(Component.translatable("mineqtt.device.general.read_only"));
        showMode();
        modeButton.active = configure;
        linkField.setEditable(configure);
        // Nor does it take the focus, so no cursor suggests it would.
        linkField.active = configure;
        linkField.setTooltip(readOnly);
        saveButton.visible = saveVisible();
        // The link's other settings change a link that is there.
        Tooltip needsLink = !configure ? readOnly : linked ? null : Tooltip.create(Component.translatable("mineqtt.device.advanced.needs_link"));
        for (AbstractWidget widget : Arrays.asList(retainButton, qosSlider, onField, offField)) {
            if (widget == null) continue;
            widget.active = configure && linked;
            widget.setTooltip(needsLink);
            if (widget instanceof EditBox field) field.setEditable(configure && linked);
        }
        if (retainButton != null && needsLink == null) retainButton.setTooltip(retainTooltip(view));
        if (qosSlider != null && !qosSlider.held()) qosSlider.show(view.raw().map(DeviceView.Raw::qos).orElse(0));
        showRemoveButtons();
    }

    // Shows a view's value unless the player changed the field since the last one; returns the value it synced to.
    private static String follow(EditBox field, boolean first, String synced, String value) {
        if (first || field.getValue().equals(synced)) {
            field.setValue(value);
            field.moveCursorToStart(false);
        }
        return value;
    }

    private void showMode() {
        modeButton.setMessage(modeLabel(mode));
        modeButton.setTooltip(Tooltip.create(Component.translatable("mineqtt.device.advanced.mode." + modeKey(mode) + ".tooltip")));
        linkField.setHint(Component.translatable("mineqtt.device.advanced.hint." + modeKey(mode)).withStyle(EditBox.DEFAULT_HINT_STYLE));
    }

    // The field shows what the link has in the other mode: its channel, or its topic to type over; a refused text is gone.
    private void stepMode() {
        DeviceView view = synced;
        if (view == null) return;
        menu().forgetRefusal();
        mode = mode.next();
        syncedLink = mode.text(view.raw());
        linkField.setValue(syncedLink);
        linkField.moveCursorToStart(false);
        showMode();
    }

    private void stepRetain() {
        DeviceView view = synced;
        if (view == null || view.raw().isEmpty()) return;
        DeviceTabs.Control retain = control(LinkSetting.RAW_RETAIN.key());
        send(retain, retain.toggled(!AdvancedTab.retain(view.raw().get(), tab.spec().orElseThrow())));
    }

    private Tooltip retainTooltip(DeviceView view) {
        DeviceView.Raw raw = view.raw().orElseThrow();
        boolean retain = AdvancedTab.retain(raw, tab.spec().orElseThrow());
        Component text = Component.translatable("mineqtt.device.advanced.retain.tooltip",
                CommonComponents.optionStatus(tab.spec().orElseThrow().retainByDefault()));
        // Events and toggle payloads carry no level, so they are never retained (M2 plan D2, D11).
        if (!raw.edges().level()) {
            text = Component.translatable("mineqtt.device.advanced.retain.rising");
        } else if (retain && !raw.retains()) {
            text = Component.translatable("mineqtt.device.advanced.retain.toggle");
        }
        return Tooltip.create(text);
    }

    private void commitAll() {
        commitLink();
        commitPayload(onField, LinkSetting.RAW_ON);
        commitPayload(offField, LinkSetting.RAW_OFF);
    }

    /**
     * Sends the link field's text, without the spaces around it, in the mode it shows; nothing when the link has it
     * already ({@link AdvancedTab.Mode#changes}: the same channel in the other of Channel and Private mode is a change).
     * The field keeps what was sent: the server stores it as sent, and a refused text stays to be corrected.
     */
    private void commitLink() {
        DeviceView view = synced;
        if (view == null || linkField == null) return;
        String text = linkField.getValue().strip();
        if (!mode.changes(text, view)) return;
        linkField.setValue(text);
        send(control(mode.key()), new JsonPrimitive(text));
    }

    private void commitPayload(@Nullable EditBox field, LinkSetting<String> setting) {
        DeviceView view = synced;
        if (view == null || field == null || view.raw().isEmpty() || !pendingPayload(field, view, setting)) return;
        send(control(setting.key()), new JsonPrimitive(field.getValue()));
    }

    private void send(DeviceTabs.Control control, JsonElement value) {
        DeviceView view = synced;
        if (view == null || !control.editable(view.grants())) return;
        // A new change answers for itself.
        menu().forgetRefusal();
        NetworkManager.sendToServer(control.send(view.pos(), value));
    }

    private DeviceMenu menu() {
        return (DeviceMenu) screen.getMenu();
    }

    private boolean pendingLink(DeviceView view) {
        return linkField != null && mode.changes(linkField.getValue().strip(), view);
    }

    private static boolean pendingPayload(@Nullable EditBox field, DeviceView view, LinkSetting<String> setting) {
        if (field == null) return false;
        Optional<String> payload = view.raw().flatMap(setting == LinkSetting.RAW_ON ? DeviceView.Raw::on : DeviceView.Raw::off);
        return !field.getValue().equals(payload.orElse(""));
    }

    // A player who may not configure the device sees why the fields do not take their typing, nor the links their removal.
    @Override
    public Optional<Component> titleNote() {
        return synced != null && !synced.grants().configure()
                ? Optional.of(Component.translatable("mineqtt.device.general.read_only.short")) : Optional.empty();
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        Optional<DeviceView> current = view();
        if (current.isEmpty()) return;
        DeviceView view = current.get();
        if (view != synced) sync(view);
        Optional<DeviceLinks> links = menu().links();
        if (links.orElse(null) != syncedLinks) {
            syncedLinks = links.orElse(null);
            showRemoveButtons();
        }
        linksButton.setMessage(links.isEmpty() || links.get().total() == 0 ? Component.translatable("mineqtt.device.advanced.links.none")
                : Component.translatable("mineqtt.device.advanced.links.count", links.get().total()));
        if (page == Page.LINKS) {
            renderLinks(g, view, links, mouseX, mouseY);
            return;
        }
        Font font = GuiText.font();

        if (linkField != null) {
            boolean pendingLink = pendingLink(view);
            boolean pendingOn = pendingPayload(onField, view, LinkSetting.RAW_ON);
            boolean pendingOff = pendingPayload(offField, view, LinkSetting.RAW_OFF);
            linkField.setTextColor(pendingLink ? PREVIEW_COLOR : EditBox.DEFAULT_TEXT_COLOR);
            if (onField != null) onField.setTextColor(pendingOn ? PREVIEW_COLOR : EditBox.DEFAULT_TEXT_COLOR);
            if (offField != null) offField.setTextColor(pendingOff ? PREVIEW_COLOR : EditBox.DEFAULT_TEXT_COLOR);
            saveButton.active = pendingLink || pendingOn || pendingOff;
            if (retainButton != null) {
                Component status = view.raw().map(raw -> CommonComponents.optionStatus(AdvancedTab.retain(raw, tab.spec().orElseThrow())))
                        .orElse(CommonComponents.optionStatus(tab.spec().orElseThrow().retainByDefault()));
                boolean own = view.raw().flatMap(DeviceView.Raw::retain).isPresent();
                retainButton.setMessage(Component.translatable(own ? "mineqtt.device.advanced.retain" : "mineqtt.device.advanced.retain.default", status));
            }
            if (onField != null) {
                g.text(font, Component.translatable("mineqtt.device.advanced.on"), left, onField.getY() + 3, LABEL_COLOR, false);
                g.text(font, Component.translatable("mineqtt.device.advanced.off"), offLabelX, offField.getY() + 3, LABEL_COLOR, false);
            }
        }
        renderStatus(g, view, mouseX, mouseY);
    }

    /**
     * The links page: a header with the count, or why a removal was refused, then a row per link in view, the port
     * at this end and the device and port at the other, with its mapping and group on hover.
     */
    private void renderLinks(GuiGraphicsExtractor g, DeviceView view, Optional<DeviceLinks> links, int mouseX, int mouseY) {
        Font font = GuiText.font();
        int headerY = top + FIRST_ROW_Y;
        int headerX = backButton.getX() + backButton.getWidth() + GAP;
        int headerW = left + CONTENT_W - headerX;
        Optional<DeviceMenu.Refusal> refusal = menu().refusal().filter(refused -> refused.key().equals(DeviceRuntime.LINKS_KEY));
        if (refusal.isPresent()) {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.refused", refusal.get().reason()), headerX, headerY + 3, headerW, REFUSED_COLOR, mouseX, mouseY);
        } else {
            int total = links.map(DeviceLinks::total).orElse(0);
            int listed = links.map(list -> list.rows().size()).orElse(0);
            // Beyond what a list carries, /mineqtt wires list shows the rest.
            Component header = total > listed ? Component.translatable("mineqtt.device.links.header.capped", total, listed)
                    : Component.translatable("mineqtt.device.links.header", total);
            GuiText.drawTruncated(g, header, headerX, headerY + 3, headerW, LABEL_COLOR, mouseX, mouseY);
        }
        List<DeviceLinks.Row> rows = linkRows();
        int listY = headerY + LINK_ROW_PITCH;
        if (rows.isEmpty()) {
            GuiText.drawWrapped(g, Component.translatable("mineqtt.device.links.empty").getString(), left, listY + 3, CONTENT_W, MUTED_COLOR);
            return;
        }
        int textW = CONTENT_W - SCROLLBAR_W - 2 - REMOVE_W - GAP;
        for (int row = 0; row < LINK_ROWS && linkScroll + row < rows.size(); row++) {
            DeviceLinks.Row link = rows.get(linkScroll + row);
            int rowY = listY + row * LINK_ROW_PITCH;
            Component text = Component.translatable(link.outgoing() ? "mineqtt.device.links.out" : "mineqtt.device.links.in", link.port(), link.peer());
            GuiText.drawTruncated(g, text, left, rowY + 3, textW, link.enabled() ? VALUE_COLOR : MUTED_COLOR);
            if (mouseX >= left && mouseX < left + textW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                g.setTooltipForNextFrame(font, font.split(linkTooltip(view, link), TOOLTIP_W), mouseX, mouseY);
            }
        }
        if (rows.size() > LINK_ROWS) {
            int trackX = left + CONTENT_W - SCROLLBAR_W;
            int trackH = LINK_ROWS * LINK_ROW_PITCH - ROW_GAP;
            int thumbH = Math.max(6, trackH * LINK_ROWS / rows.size());
            int thumbY = listY + (trackH - thumbH) * linkScroll / (rows.size() - LINK_ROWS);
            g.fill(trackX, listY, trackX + SCROLLBAR_W, listY + trackH, SCROLL_TRACK);
            g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, SCROLL_THUMB);
        }
    }

    // A link on hover: both ends with their devices, the mapping, and the group it reaches through.
    private static Component linkTooltip(DeviceView view, DeviceLinks.Row link) {
        String own = view.name() + "." + link.port();
        Component text = Component.translatable("mineqtt.device.links.row.tooltip", link.outgoing() ? own : link.peer(), link.outgoing() ? link.peer() : own,
                ConfiguratorLink.mappingName(link.mapping()));
        if (!link.group().isEmpty()) {
            text = Component.translatable(link.outgoing() ? "mineqtt.device.links.row.to_group" : "mineqtt.device.links.row.via_group", text, link.group());
        }
        return link.enabled() ? text : Component.translatable("mineqtt.device.links.row.disabled", text);
    }

    /**
     * Under the field: the topic the link listens on or sends to, as the server resolved it; why the link does nothing;
     * or, after a change the server refused, why it did, while the link is still the one it was refused against.
     */
    private void renderStatus(GuiGraphicsExtractor g, DeviceView view, int mouseX, int mouseY) {
        Optional<DeviceMenu.Refusal> refusal = menu().refusal()
                .filter(refused -> tab.controls().stream().anyMatch(control -> control.key().equals(refused.key())));
        if (tab.spec().isEmpty()) {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.advanced.no_raw_link"), left, statusY, CONTENT_W, MUTED_COLOR, mouseX, mouseY);
        } else if (refusal.isPresent()) {
            GuiText.drawWrappedScaled(g, Component.translatable("mineqtt.device.refused", refusal.get().reason()).getString(), left, statusY,
                    CONTENT_W, STATUS_H, REFUSED_COLOR, mouseX, mouseY);
        } else if (view.raw().isEmpty()) {
            GuiText.drawWrappedScaled(g, Component.translatable("mineqtt.device.advanced.none").getString(), left, statusY,
                    CONTENT_W, STATUS_H, MUTED_COLOR, mouseX, mouseY);
        } else if (view.problem().isPresent()) {
            GuiText.drawWrappedScaled(g, Component.translatable("mineqtt.device.advanced.refused", view.problem().get()).getString(), left, statusY,
                    CONTENT_W, STATUS_H, REFUSED_COLOR, mouseX, mouseY);
        } else {
            DeviceView.Raw raw = view.raw().get();
            String label = raw.direction() == RawLinkSpec.Direction.IN ? "mineqtt.device.advanced.listens"
                    : raw.edges().level() ? "mineqtt.device.advanced.sends" : "mineqtt.device.advanced.sends.rising";
            drawTopic(g, Component.translatable(label).getString(),
                    raw.topic(), left, statusY, CONTENT_W, mouseX, mouseY);
        }
    }

    /**
     * A label and a topic on up to two lines that break after a {@code /}, the second one under the label; a topic that
     * does not fit that way wraps anywhere and shrinks next to the label, with the full topic on hover.
     */
    private static void drawTopic(GuiGraphicsExtractor g, String label, String topic, int x, int y, int width, int mouseX, int mouseY) {
        Font font = GuiText.font();
        int labelW = font.width(label) + GAP;
        g.text(font, label, x, y, LABEL_COLOR, false);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String level : topic.split("(?<=/)")) {
            int room = lines.isEmpty() ? width - labelW : width;
            if (!line.isEmpty() && font.width(line + level) > room) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(level);
        }
        lines.add(line.toString());
        boolean fits = lines.size() * font.lineHeight <= STATUS_H && font.width(lines.getFirst()) <= width - labelW
                && lines.stream().skip(1).allMatch(text -> font.width(text) <= width);
        if (!fits) {
            GuiText.drawWrappedScaled(g, topic, x + labelW, y, width - labelW, STATUS_H, VALUE_COLOR, mouseX, mouseY);
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), i == 0 ? x + labelW : x, y + i * font.lineHeight, VALUE_COLOR, false);
        }
    }

    /** Enter sends the field that has the focus; while one has it, it takes every other key but Escape, which closes the screen. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) return false;
        for (EditBox field : fields) {
            if (!field.isFocused()) continue;
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                if (field == linkField) commitLink();
                else commitPayload(field, field == onField ? LinkSetting.RAW_ON : LinkSetting.RAW_OFF);
                return true;
            }
            return field.keyPressed(new KeyEvent(keyCode, scanCode, modifiers)) || field.canConsumeInput();
        }
        return false;
    }

    // The wheel scrolls the links, a row a notch.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (page != Page.LINKS || dy == 0) return false;
        linkScroll -= (int) Math.signum(dy);
        showRemoveButtons();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // A click elsewhere ends the typing, so the screen's keys work again (the inventory key closes it).
        for (EditBox field : fields) {
            if (!field.isMouseOver(mouseX, mouseY)) field.setFocused(false);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // A release beside the slider does not reach it; it stops dragging either way.
        if (qosSlider != null) qosSlider.release();
        return false;
    }
}

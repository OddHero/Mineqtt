package art.rehra.mineqtt.ui;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinResultsPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinSearchPayload;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.TwinPicker;
import art.rehra.mineqtt.ui.device.TwinTab;
import art.rehra.mineqtt.ui.device.TwinTabView;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Twin tab's entity picker (M3 plan D4, D19), a client screen over the device's screen, whose menu stays open
 * underneath, so every payload of the picker passes the device screen's guard. A focused search field and a domain chip
 * limited to the binding key's domains ask the server (both debounced to {@value TwinPicker#DEBOUNCE_MILLIS} ms, the
 * last search always sent), which answers with at most {@value TwinPicker#MAX_ROWS} entities the player may bind, grouped
 * by area; the client never holds the catalog. Only the answer to the search sent last is shown (the answer repeats its
 * query and domain); one the server dropped over the rate is sent again after {@value TwinPicker#RESEND_MILLIS} ms, at
 * most {@value TwinPicker#MAX_RESENDS} times. Each row shows the entity's name, its area and id in grey, its value with
 * a colour swatch, and the control and stale badges; six are visible, the wheel and the arrow keys move through them.
 * A query spelled like an entity id that is not listed offers it as "not seen yet". Enter or Bind binds the selected
 * entity as the binding key's {@code DeviceConfigC2S}, with "Name the block after it" renaming the block once the bind
 * shows (on while the block keeps its default name); before a first bind it warns that the block's Home Assistant
 * entities go. Escape or Cancel goes back to the Twin tab without a change.
 * <p>
 * For a target (M3-14) it lists only entities Minecraft may control (the server's control filter), renames nothing and
 * warns of nothing; opened without a key for a type with several targets (the Wall Switch in Bind mode) it first asks
 * which: Left or Right.
 */
public class TwinPickerScreen extends Screen {
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 204;
    private static final int MARGIN = 8;
    private static final int SEARCH_Y = 18;
    private static final int FIELD_H = 16;
    private static final int DOMAIN_W = 72;
    private static final int LIST_Y = 38;
    private static final int ROW_H = 20;
    private static final int VISIBLE_ROWS = 6;
    private static final int LIST_H = VISIBLE_ROWS * ROW_H + 2;
    private static final int WARNING_Y = LIST_Y + LIST_H + 4;
    private static final int BUTTONS_Y = WARNING_Y + 12;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;
    private static final int SCROLLBAR_W = 3;
    private static final int SWATCH = 6;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PANEL_OUTLINE = 0xFF3A3A3A;
    private static final int TITLE_COLOR = 0xFF404040;
    private static final int WARNING_COLOR = 0xFF9A3B00;
    private static final int LIST_BORDER = 0xFFA0A0A0;
    private static final int LIST_COLOR = 0xFF000000;
    private static final int HOVER_COLOR = 0xFF2A2A2A;
    private static final int SELECTED_COLOR = 0xFF505050;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int GREY_COLOR = 0xFFA0A0A0;
    private static final int NOTICE_COLOR = 0xFFE0E0E0;
    private static final int CONTROL_COLOR = 0xFF55FF55;
    private static final int STALE_COLOR = 0xFFFFCC55;
    private static final int UNAVAILABLE_COLOR = 0xFFFF7777;
    private static final int TOOLTIP_W = 220;

    private final TabbedMqttScreen parent;
    private final DeviceMenu menu;
    private final TwinTab tab;
    // The binding key the pick is for; "" while the player still chooses which target (M3-14's Left/Right step).
    private String key;
    private List<String> domains;
    private int left;
    private int top;
    private EditBox search;
    private Button domainButton;
    private Button renameButton;
    private Button bindButton;
    private String domain = "";
    private boolean rename;
    // What was typed last, and when: a search goes out once the typing rested for the debounce.
    private String typed = "";
    private long typedAt;
    private @Nullable String sentQuery;
    private @Nullable String sentDomain;
    // When the search shown for was sent, and how often it was sent again while no answer came.
    private long sentAt;
    private int resends;
    // Every search sent, resends included (tests count them).
    private int searches;
    private List<Entry> entries = List.of();
    private @Nullable TwinResultsPayload shown;
    private int selected;
    private int scroll;

    /**
     * One row of the list: an entity of the answer, or the free-text entity not seen yet.
     *
     * @param row null for the free-text row
     */
    private record Entry(String entityId, @Nullable TwinPicker.Row row) {
    }

    /**
     * @param key the binding key the pick is for ({@code twin.entity}, a target's key), or {@code ""} to ask which target
     *            first
     */
    public TwinPickerScreen(TabbedMqttScreen parent, DeviceMenu menu, TwinTab tab, String key) {
        super(Component.translatable("mineqtt.twin.picker.title", parent.getMenu() instanceof DeviceMenu device ? device.title() : parent.getTitle()));
        this.parent = parent;
        this.menu = menu;
        this.tab = tab;
        this.key = tab.pickKeys().contains(key) ? key : "";
        this.domains = TwinPicker.domains(tab.adapter(), this.key);
        this.rename = this.key.equals(LinkSetting.TWIN_ENTITY.key()) && keepsDefaultName(menu);
    }

    // The title: the device, and for a target which one.
    private Component title(String key) {
        Component device = parent.getMenu() instanceof DeviceMenu screen ? screen.title() : parent.getTitle();
        return TwinPicker.targetName(tab.adapter(), key)
                .<Component>map(name -> Component.translatable("mineqtt.twin.picker.title.target", device, TwinTabView.targetLabel(name)))
                .orElseGet(() -> Component.translatable("mineqtt.twin.picker.title", device));
    }

    /** Whether the picker still asks which target the pick is for (the Left/Right step). */
    public boolean choosesTarget() {
        return key.isEmpty();
    }

    // Whether the block still has its type's default name, so it may be named after what it binds (D19).
    private static boolean keepsDefaultName(DeviceMenu menu) {
        Optional<DeviceView> view = menu.deviceView();
        if (view.isEmpty() || view.get().diagnostics().id().isEmpty()) return false;
        DeviceType type = DeviceTypes.get(view.get().type());
        return type != null && type.defaultName(view.get().diagnostics().id().get()).equals(view.get().name());
    }

    /** The binding key the pick is for. */
    public String key() {
        return key;
    }

    /** The entities the list shows now, in order; the free-text row last when it is offered. */
    public List<String> shownEntities() {
        return entries.stream().map(Entry::entityId).toList();
    }

    /** The answer the list shows, if one arrived for the current search. */
    public Optional<TwinResultsPayload> shownResults() {
        return Optional.ofNullable(shown);
    }

    /** The warning line above the buttons, if one shows: an MQTT device the bind replaces, or the discovery warning. */
    public Optional<String> warningText() {
        return warning().map(Component::getString);
    }

    /** The domain chip the picker rests on; {@code ""} for every domain of the key. */
    public String domain() {
        return domain;
    }

    /** How many searches the picker sent since it opened, resends included (client, for tests). */
    public int searchesSent() {
        return searches;
    }

    /** Whether the block is renamed after the entity once bound. */
    public boolean renames() {
        return rename;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int inner = PANEL_W - 2 * MARGIN;
        if (key.isEmpty()) {
            initTargetStep(inner);
            return;
        }
        search = new EditBox(font, left + MARGIN, top + SEARCH_Y, inner - DOMAIN_W - GAP, FIELD_H, Component.translatable("mineqtt.twin.picker.search"));
        search.setMaxLength(TwinPicker.MAX_QUERY_LENGTH);
        search.setHint(Component.translatable("mineqtt.twin.picker.search").withStyle(EditBox.DEFAULT_HINT_STYLE));
        search.setValue(typed);
        search.setResponder(text -> {
            typed = text;
            typedAt = Util.getMillis();
        });
        addRenderableWidget(search);
        domainButton = addRenderableWidget(Button.builder(Component.empty(), button -> stepDomain())
                .bounds(left + PANEL_W - MARGIN - DOMAIN_W, top + SEARCH_Y, DOMAIN_W, FIELD_H).build());
        int small = 54;
        int wide = inner - 2 * small - 2 * GAP;
        renameButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            rename = !rename;
            showButtons();
        }).bounds(left + MARGIN, top + BUTTONS_Y, wide, BUTTON_H).build());
        renameButton.visible = key.equals(LinkSetting.TWIN_ENTITY.key());
        bindButton = addRenderableWidget(Button.builder(Component.translatable("mineqtt.twin.picker.bind"), button -> bindSelected())
                .bounds(left + MARGIN + wide + GAP, top + BUTTONS_Y, small, BUTTON_H).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + PANEL_W - MARGIN - small, top + BUTTONS_Y, small, BUTTON_H).build());
        setInitialFocus(search);
        showButtons();
    }

    // M3-14's Left/Right step: one button per target, then the search for the one chosen.
    private void initTargetStep(int inner) {
        List<String> keys = tab.pickKeys().stream().filter(candidate -> TwinPicker.target(tab.adapter(), candidate)).toList();
        int y = top + LIST_Y + 24;
        for (String candidate : keys) {
            String name = TwinPicker.targetName(tab.adapter(), candidate).orElseThrow();
            Component current = menu.twinView().flatMap(view -> view.entity(candidate)).map(entity -> (Component) Component.literal(entity.label()))
                    .orElseGet(() -> Component.translatable("mineqtt.twin.target.none"));
            addRenderableWidget(Button.builder(TwinTabView.targetLabel(name), button -> chooseTarget(candidate))
                    .tooltip(Tooltip.create(Component.translatable("mineqtt.twin.picker.step.tooltip", TwinTabView.targetLabel(name), current)))
                    .bounds(left + MARGIN, y, inner, BUTTON_H).build());
            y += BUTTON_H + GAP;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + PANEL_W - MARGIN - 54, top + BUTTONS_Y, 54, BUTTON_H).build());
    }

    private void chooseTarget(String chosen) {
        key = chosen;
        domains = TwinPicker.domains(tab.adapter(), chosen);
        rename = false;
        sentQuery = null;
        sentDomain = null;
        resends = 0;
        typedAt = 0;
        rebuildWidgets();
    }

    private void showButtons() {
        domainButton.setMessage(domain.isEmpty() ? Component.translatable("mineqtt.twin.picker.domain.all")
                : Component.translatableWithFallback("mineqtt.twin.domain." + domain, domain));
        domainButton.active = domains.size() > 1;
        domainButton.setTooltip(Tooltip.create(Component.translatable("mineqtt.twin.picker.domain.tooltip")));
        renameButton.setMessage(CommonComponents.optionNameValue(Component.translatable("mineqtt.twin.picker.rename"), CommonComponents.optionStatus(rename)));
        renameButton.setTooltip(Tooltip.create(Component.translatable("mineqtt.twin.picker.rename.tooltip")));
        bindButton.active = selectedEntry().isPresent();
    }

    private void stepDomain() {
        List<String> chips = new ArrayList<>();
        chips.add("");
        chips.addAll(domains);
        domain = chips.get((chips.indexOf(domain) + 1) % chips.size());
        // A chip searches after the debounce, as typing does: stepping through the chips sends the one it rests on.
        typedAt = Util.getMillis();
        showButtons();
    }

    @Override
    public void tick() {
        // The device's screen closed underneath (out of reach, the device gone): nothing to bind any more.
        if (minecraft.player == null || minecraft.player.containerMenu != menu) {
            Screens.open(null);
            return;
        }
        if (key.isEmpty()) return;
        String query = typed.strip();
        long now = Util.getMillis();
        boolean due = !query.equals(sentQuery) || !domain.equals(sentDomain);
        if (due && now - typedAt >= TwinPicker.DEBOUNCE_MILLIS) {
            sentQuery = query;
            sentDomain = domain;
            sentAt = now;
            resends = 0;
            searches++;
            NetworkManager.sendToServer(new TwinSearchPayload(menu.blockPos, key, query, domain));
        } else if (!due && sentQuery != null && TwinPicker.resendDue(answered(), resends, now - sentAt)) {
            // No answer came: the server dropped it over the rate. The same search once more.
            sentAt = now;
            resends++;
            searches++;
            NetworkManager.sendToServer(new TwinSearchPayload(menu.blockPos, key, sentQuery, sentDomain));
        }
        menu.twinResults().filter(results -> results != shown && answers(results)).ifPresent(this::show);
    }

    // Whether an answer is the one to the search sent last: its key, query and domain; an older one is dropped.
    private boolean answers(TwinResultsPayload results) {
        return sentQuery != null && sentDomain != null && TwinPicker.answers(key, sentQuery, sentDomain, results.key(), results.query(), results.domain());
    }

    // Whether the answer to the search sent last is shown.
    private boolean answered() {
        return shown != null && answers(shown);
    }

    // A new answer: its rows, and the free-text row when the query names an entity it does not list.
    private void show(TwinResultsPayload results) {
        String before = selectedEntry().map(Entry::entityId).orElse(null);
        shown = results;
        List<Entry> next = new ArrayList<>();
        results.rows().forEach(row -> next.add(new Entry(row.entityId(), row)));
        // Only a binder gets the free-text row, as only they get rows.
        if (results.notice() != TwinPicker.Notice.NO_BIND && results.notice() != TwinPicker.Notice.OFF) {
            TwinPicker.freeText(results.query(), results.rows(), domains).ifPresent(entity -> next.add(new Entry(entity, null)));
        }
        entries = List.copyOf(next);
        int keep = before == null ? -1 : shownEntities().indexOf(before);
        selected = keep >= 0 ? keep : 0;
        scroll = Mth.clamp(scroll, 0, maxScroll());
        showButtons();
    }

    private Optional<Entry> selectedEntry() {
        return !key.isEmpty() && selected >= 0 && selected < entries.size() ? Optional.of(entries.get(selected)) : Optional.empty();
    }

    private void bindSelected() {
        selectedEntry().ifPresent(this::bind);
    }

    // The pick goes out as the binding key's setting; the block is renamed once the server shows it bound.
    private void bind(Entry entry) {
        menu.forgetRefusal();
        menu.renameOnBind(rename && entry.row() != null && key.equals(LinkSetting.TWIN_ENTITY.key())
                ? new DeviceMenu.PendingRename(key, entry.entityId(), TwinPicker.blockName(entry.row())) : null);
        NetworkManager.sendToServer(new DeviceConfigPayload(menu.blockPos, key, StringTag.valueOf(entry.entityId())));
        onClose();
    }

    /** Back to the device's screen; nothing changed unless a pick went out. */
    @Override
    public void onClose() {
        Screens.open(minecraft.player != null && minecraft.player.containerMenu == menu ? parent : null);
    }

    private void select(int index) {
        if (entries.isEmpty()) return;
        selected = Mth.clamp(index, 0, entries.size() - 1);
        scroll = Mth.clamp(scroll, selected - VISIBLE_ROWS + 1, selected);
        showButtons();
    }

    private int maxScroll() {
        return Math.max(0, entries.size() - VISIBLE_ROWS);
    }

    // The entry under the mouse, or -1.
    private int rowAt(double mouseX, double mouseY) {
        int x = left + MARGIN + 1;
        int y = top + LIST_Y + 1;
        if (mouseX < x || mouseX >= left + PANEL_W - MARGIN - 1 || mouseY < y || mouseY >= y + VISIBLE_ROWS * ROW_H) return -1;
        int index = scroll + (int) ((mouseY - y) / ROW_H);
        return index < entries.size() ? index : -1;
    }

    // What the list shows without rows: why, or D4's help.
    private Component notice() {
        if (shown == null) return Component.translatable("mineqtt.twin.picker.searching");
        return switch (shown.notice()) {
            case OFF -> Component.translatable("mineqtt.twin.picker.notice.off", shown.detail());
            case NO_BIND -> Component.translatable("mineqtt.twin.picker.notice.no_bind");
            case EMPTY -> Component.translatable("mineqtt.twin.picker.notice.empty");
            case NO_CONTROL -> Component.translatable("mineqtt.twin.picker.notice.no_control");
            case NO_MATCH, NONE -> Component.translatable("mineqtt.twin.picker.notice.no_match");
        };
    }

    // The warning before a bind: an MQTT device source it replaces, or before a first bind (D6) that
    // the block's Home Assistant entities go while it mirrors.
    private Optional<Component> warning() {
        if (!key.equals(LinkSetting.TWIN_ENTITY.key())) return Optional.empty();
        Optional<TwinView.Device> device = menu.twinView().flatMap(TwinView::device);
        if (device.isPresent()) {
            return Optional.of(Component.translatable("mineqtt.twin.picker.warning.replace", device.get().device(), device.get().presetLabel()));
        }
        boolean bound = menu.twinView().map(TwinView::bound).orElse(false);
        boolean discovered = menu.deviceView().map(view -> view.registration().discovered()).orElse(false);
        return !bound && discovered ? Optional.of(Component.translatable("mineqtt.twin.picker.warning.discovery")) : Optional.empty();
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
        GuiText.drawTruncated(g, title(key), left + MARGIN, top + 6, inner, TITLE_COLOR, mouseX, mouseY);
        if (key.isEmpty()) {
            GuiText.drawWrapped(g, Component.translatable("mineqtt.twin.picker.step").getString(), left + MARGIN, top + LIST_Y, inner, TITLE_COLOR);
            return;
        }

        int x = left + MARGIN;
        int y = top + LIST_Y;
        g.fill(x, y, x + inner, y + LIST_H, LIST_BORDER);
        g.fill(x + 1, y + 1, x + inner - 1, y + LIST_H - 1, LIST_COLOR);
        if (entries.isEmpty()) {
            GuiText.drawWrapped(g, notice().getString(), x + 5, y + 5, inner - 10, NOTICE_COLOR);
        } else {
            boolean scrolls = maxScroll() > 0;
            int rowW = inner - 2 - (scrolls ? SCROLLBAR_W + 1 : 0);
            int hovered = rowAt(mouseX, mouseY);
            for (int row = 0; row < VISIBLE_ROWS && scroll + row < entries.size(); row++) {
                int index = scroll + row;
                int rowY = y + 1 + row * ROW_H;
                if (index == selected) {
                    g.fill(x + 1, rowY, x + 1 + rowW, rowY + ROW_H, SELECTED_COLOR);
                } else if (index == hovered) {
                    g.fill(x + 1, rowY, x + 1 + rowW, rowY + ROW_H, HOVER_COLOR);
                }
                renderEntry(g, entries.get(index), x + 1, rowY, rowW);
            }
            if (scrolls) {
                int trackX = x + inner - 1 - SCROLLBAR_W;
                int trackH = VISIBLE_ROWS * ROW_H;
                int thumbH = Math.max(8, trackH * VISIBLE_ROWS / entries.size());
                int thumbY = y + 1 + (trackH - thumbH) * scroll / maxScroll();
                g.fill(trackX, y + 1, trackX + SCROLLBAR_W, y + 1 + trackH, HOVER_COLOR);
                g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, LIST_BORDER);
            }
            if (hovered >= 0) {
                g.setTooltipForNextFrame(font, font.split(entryTooltip(entries.get(hovered)), TOOLTIP_W), mouseX, mouseY);
            }
        }
        warning().ifPresent(text -> GuiText.drawTruncated(g, text, left + MARGIN, top + WARNING_Y, inner, WARNING_COLOR, mouseX, mouseY));
    }

    // Two lines: the swatch, the name and the badges; the area and id in grey, the value at the right.
    private void renderEntry(GuiGraphicsExtractor g, Entry entry, int x, int y, int width) {
        TwinPicker.Row row = entry.row();
        if (row == null) {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.twin.picker.free_text", entry.entityId()), x + 4, y + 2, width - 8, TEXT_COLOR);
            GuiText.drawTruncated(g, Component.translatable("mineqtt.twin.picker.free_text.hint"), x + 4, y + 11, width - 8, GREY_COLOR);
            return;
        }
        int textX = x + 4;
        if (row.rgb().isPresent()) {
            g.fill(textX, y + 2, textX + SWATCH, y + 2 + SWATCH, LIST_BORDER);
            g.fill(textX + 1, y + 3, textX + SWATCH - 1, y + 1 + SWATCH, 0xFF000000 | row.rgb().getAsInt());
            textX += SWATCH + 3;
        }
        List<Component> badges = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (row.control()) {
            badges.add(Component.translatable("mineqtt.twin.picker.badge.control"));
            colors.add(CONTROL_COLOR);
        }
        if (row.unavailable()) {
            badges.add(Component.translatable("mineqtt.twin.picker.badge.unavailable"));
            colors.add(UNAVAILABLE_COLOR);
        } else if (row.stale()) {
            badges.add(Component.translatable("mineqtt.twin.picker.badge.stale"));
            colors.add(STALE_COLOR);
        }
        int right = x + width - 3;
        for (int i = badges.size() - 1; i >= 0; i--) {
            int badgeW = font.width(badges.get(i));
            g.text(font, badges.get(i), right - badgeW, y + 2, colors.get(i), false);
            right -= badgeW + GAP;
        }
        GuiText.drawTruncated(g, Component.literal(row.label()), textX, y + 2, right - textX, TEXT_COLOR);
        int valueW = Math.min(font.width(row.value()), width / 3);
        if (!row.value().isEmpty()) GuiText.drawRight(g, row.value(), x + width - 3, y + 11, valueW, GREY_COLOR);
        String where = row.area().isEmpty() ? row.entityId() : row.area() + " · " + row.entityId();
        GuiText.drawTruncated(g, where, x + 4, y + 11, width - 8 - valueW - GAP, GREY_COLOR);
    }

    private static Component entryTooltip(Entry entry) {
        TwinPicker.Row row = entry.row();
        if (row == null) return Component.translatable("mineqtt.twin.picker.free_text.tooltip", entry.entityId());
        Component control = Component.translatable(row.control() ? "mineqtt.twin.picker.control.yes" : "mineqtt.twin.picker.control.no");
        return Component.translatable("mineqtt.twin.picker.row.tooltip", row.label(), row.entityId(), row.area().isEmpty() ? "-" : row.area(),
                row.value(), control);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int row = rowAt(event.x(), event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && row >= 0) {
            select(row);
            if (doubleClick) bindSelected();
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
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> bindSelected();
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

    /** The picker for the device whose screen is open, if one shows (client, for tests). */
    public static Optional<TwinPickerScreen> open(Minecraft minecraft) {
        return Screens.current() instanceof TwinPickerScreen picker ? Optional.of(picker) : Optional.empty();
    }
}

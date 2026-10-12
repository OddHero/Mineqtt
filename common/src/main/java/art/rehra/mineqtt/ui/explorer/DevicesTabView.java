package art.rehra.mineqtt.ui.explorer;

import art.rehra.mineqtt.network.MineqttNetworking.ExplorerActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.ExplorerQueryPayload;
import art.rehra.mineqtt.network.MineqttNetworking.PrivacyChoicePayload;
import art.rehra.mineqtt.presence.Consent;
import art.rehra.mineqtt.presence.PrivacyCommands;
import art.rehra.mineqtt.presence.PrivacyPolicy;
import art.rehra.mineqtt.rooms.RoomSwitch;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Draws the {@link DevicesTab} of the Configurator's explorer (M4 plan D13). The search field asks the server, debounced
 * to {@value DevicesTab#DEBOUNCE_MILLIS} ms, for the page of devices whose name, slug or model contains what was typed;
 * the room button beside it steps the filter through the rooms the player's devices are in, and the page buttons step
 * through the pages of {@value ExplorerPage#PAGE_SIZE}. Only the answer to the search sent last is shown; one the server
 * dropped over its rate is sent again ({@link DevicesTab#resendDue}), and the page shown is asked for again every
 * {@value DevicesTab#REFRESH_MILLIS} ms and at once after an action.
 * <p>
 * The list shows, on the first page of an unfiltered search, the player's You row with their privacy choices (D9), then
 * the devices grouped under a header per room. Each device shows its name and how it is available, then its model with
 * up to three values and how far away it is in which direction; its slug and what is wrong with it show on hover. A
 * click selects a device, which then offers Use, Locate and Open at its right ({@link ExplorerActions}); a room's header
 * offers All on and All off. The wheel scrolls the list.
 */
public class DevicesTabView implements MqttTabView {

    private static final int GAP = 4;
    private static final int BUTTON_GAP = 2;
    private static final int BUTTON_PADDING = 6;
    private static final int LINE_H = 9;
    private static final int SCROLLBAR_W = 3;
    private static final int TOOLTIP_W = 200;
    /** At most this many buttons at the right of one entry. */
    private static final int MAX_ACTIONS = 3;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int VALUE_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int ONLINE_COLOR = 0xFF2E7D32;
    private static final int WARNING_COLOR = 0xFF9A6700;
    private static final int REFUSED_COLOR = 0xFFAA0000;
    private static final int HOVER_COLOR = 0x22000000;
    private static final int SELECTED_COLOR = 0x33000000;
    private static final int ROOM_COLOR = 0xFF1F4E79;
    private static final int SCROLL_TRACK = 0xFF8B8B8B;
    private static final int SCROLL_THUMB = 0xFF555555;

    private final TabbedMqttScreen screen;
    private int left;
    private int top;
    private EditBox search;
    private Button roomButton;
    private Button previous;
    private Button next;
    // The buttons of each visible entry, placed and labelled for what the entry offers.
    private final Button[][] actionButtons = new Button[DevicesTab.VISIBLE_ROWS][MAX_ACTIONS];
    // What was typed last, and when: a search goes out once the typing rested for the debounce.
    private String typed = "";
    private long typedAt;
    // The room filter; "" for every room.
    private String room = "";
    // The page asked for; the server's answer may correct it to the last page.
    private int page;
    private @Nullable String sentQuery;
    private String sentRoom = "";
    private int sentPage;
    private long sentAt;
    private int resends;
    private @Nullable ExplorerPage shown;
    private List<Entry> entries = List.of();
    private @Nullable UUID selected;
    private int scroll;

    /** What one line of the list shows. */
    private sealed interface Entry permits YouEntry, RoomEntry, RowEntry {
    }

    /** The player's own row. */
    private record YouEntry(ExplorerPage.You you) implements Entry {
    }

    /** The header over the devices of a room; {@code null} over those in no room. */
    private record RoomEntry(@Nullable ExplorerPage.RoomRef room) implements Entry {
    }

    /** A device or a group. */
    private record RowEntry(ExplorerPage.Row row) implements Entry {
    }

    /** A button at the right of an entry. */
    private record Act(Component label, Component tooltip, boolean active, Runnable run) {
    }

    public DevicesTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    private CyberdeckMenu menu() {
        return (CyberdeckMenu) screen.getMenu();
    }

    /** The page the tab shows: the answer to the search sent last, once a frame took it; empty before. */
    public Optional<ExplorerPage> shown() {
        return Optional.ofNullable(shown);
    }

    /** The device or group selected, whose actions show at its right; empty when none is. */
    public Optional<UUID> selected() {
        return Optional.ofNullable(selected);
    }

    /** Where the row of a device or group shows: its visible slot from the top, 0 to 2; empty when it is not in view. */
    public OptionalInt slotOf(UUID id) {
        for (int slot = 0; slot < DevicesTab.VISIBLE_ROWS; slot++) {
            if (entryAt(slot) instanceof RowEntry row && row.row().id().equals(id)) return OptionalInt.of(slot);
        }
        return OptionalInt.empty();
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        left = guiLeft + 8;
        top = guiTop;
        // Back on the tab, it shows the search it had and its page until the next answer.
        menu().explorerPage().ifPresent(last -> {
            typed = last.query();
            room = last.room();
            page = last.page();
            show(last);
        });
        int y = guiTop + DevicesTab.SEARCH_Y;
        search = new EditBox(GuiText.font(), left, y, DevicesTab.SEARCH_W, DevicesTab.SEARCH_H, Component.translatable("mineqtt.explorer.search"));
        search.setMaxLength(ExplorerPage.MAX_QUERY_LENGTH);
        search.setHint(Component.translatable("mineqtt.explorer.search").withStyle(EditBox.DEFAULT_HINT_STYLE));
        search.setValue(typed);
        search.setResponder(text -> {
            if (text.strip().equals(typed.strip())) {
                typed = text;
                return;
            }
            typed = text;
            typedAt = Util.getMillis();
            page = 0;
        });
        int x = left + DevicesTab.SEARCH_W + DevicesTab.SEARCH_GAP;
        roomButton = Button.builder(roomLabel(), button -> stepRoom())
                .bounds(x, y, DevicesTab.ROOM_BUTTON_W, DevicesTab.SEARCH_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.explorer.room_filter"))).build();
        x += DevicesTab.ROOM_BUTTON_W + DevicesTab.SEARCH_GAP;
        previous = Button.builder(Component.literal("<"), button -> step(-1))
                .bounds(x, y, DevicesTab.PAGE_BUTTON_W, DevicesTab.SEARCH_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.explorer.previous"))).build();
        next = Button.builder(Component.literal(">"), button -> step(1))
                .bounds(x + DevicesTab.PAGE_BUTTON_W + DevicesTab.SEARCH_GAP, y, DevicesTab.PAGE_BUTTON_W, DevicesTab.SEARCH_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.explorer.next"))).build();
        for (int slot = 0; slot < DevicesTab.VISIBLE_ROWS; slot++) {
            for (int index = 0; index < MAX_ACTIONS; index++) {
                int s = slot;
                int i = index;
                Button button = Button.builder(Component.empty(), b -> press(s, i)).bounds(left, guiTop, 20, DevicesTab.ACTION_H).build();
                button.visible = false;
                actionButtons[slot][index] = button;
            }
        }
        showButtons();
        layout();
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        List<GuiEventListener> widgets = new ArrayList<>(List.of(search, roomButton, previous, next));
        for (Button[] slot : actionButtons) widgets.addAll(List.of(slot));
        return widgets;
    }

    // A page button: the page next to the one shown, asked for at once.
    private void step(int delta) {
        if (shown == null) return;
        page = Mth.clamp(shown.page() + delta, 0, shown.pages() - 1);
        typedAt = 0;
        scroll = 0;
    }

    // The room button: the next room of those the player's devices are in, after the last every room again.
    private void stepRoom() {
        if (shown == null) return;
        List<String> names = shown.roomNames();
        int at = -1;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(room)) at = i;
        }
        room = at + 1 < names.size() && (at >= 0 || room.isEmpty()) ? names.get(at + 1) : "";
        page = 0;
        typedAt = 0;
        scroll = 0;
        roomButton.setMessage(roomLabel());
    }

    private Component roomLabel() {
        return room.isEmpty() ? Component.translatable("mineqtt.explorer.room.all") : Component.literal(room);
    }

    private void showButtons() {
        previous.active = shown != null && shown.page() > 0;
        next.active = shown != null && shown.page() < shown.pages() - 1;
        roomButton.active = shown != null && (!shown.rooms().isEmpty() || !room.isEmpty());
        roomButton.setMessage(roomLabel());
    }

    // Sends the search when it is due, again when the server dropped it, and again to refresh the page shown.
    private void poll() {
        String query = typed.strip();
        long now = Util.getMillis();
        boolean due = sentQuery == null || !query.equals(sentQuery) || !room.equals(sentRoom) || page != sentPage;
        if (due && now - typedAt >= DevicesTab.DEBOUNCE_MILLIS) {
            send(query, room, page, now);
            resends = 0;
        } else if (!due && DevicesTab.resendDue(answered(), resends, now - sentAt)) {
            send(sentQuery, sentRoom, sentPage, now);
            resends++;
        } else if (!due && DevicesTab.refreshDue(answered(), now - sentAt)) {
            send(sentQuery, sentRoom, sentPage, now);
            resends = 0;
        }
        Optional<ExplorerPage> arrived = menu().explorerPage();
        if (arrived.isPresent() && arrived.get() != shown && answers(arrived.get())) {
            ExplorerPage answer = arrived.get();
            // The server shows the last page for one beyond it: the tab rests there.
            if (answer.page() != sentPage) {
                page = answer.page();
                sentPage = answer.page();
            }
            if (shown == null || !shown.query().equals(answer.query()) || !shown.room().equals(answer.room()) || shown.page() != answer.page()) scroll = 0;
            show(answer);
        }
        layout();
    }

    // Takes an answer: the entries it lists, the selection kept while its device is still listed.
    private void show(ExplorerPage answer) {
        shown = answer;
        List<Entry> list = new ArrayList<>();
        if (answer.page() == 0 && answer.query().isEmpty() && answer.room().isEmpty()) list.add(new YouEntry(answer.you()));
        boolean anyRoom = answer.rows().stream().anyMatch(row -> row.room() >= 0);
        int lastRoom = Integer.MIN_VALUE;
        for (ExplorerPage.Row row : answer.rows()) {
            if (anyRoom && row.room() != lastRoom) {
                list.add(new RoomEntry(answer.roomOf(row).orElse(null)));
                lastRoom = row.room();
            }
            list.add(new RowEntry(row));
        }
        entries = List.copyOf(list);
        if (selected != null && answer.rows().stream().noneMatch(row -> row.id().equals(selected))) selected = null;
        if (search != null) showButtons();
    }

    private void send(String query, String room, int page, long now) {
        sentQuery = query;
        sentRoom = room;
        sentPage = page;
        sentAt = now;
        NetworkManager.sendToServer(new ExplorerQueryPayload(query, room, page));
    }

    // After an action, the page shown is asked for again at once, so it shows what the action changed.
    private void refreshSoon() {
        sentAt = Util.getMillis() - DevicesTab.REFRESH_MILLIS;
    }

    // Whether an answer is the one to the search sent last; the page may be the server's correction of it.
    private boolean answers(ExplorerPage answer) {
        return sentQuery != null && answer.query().equals(sentQuery) && answer.room().equals(sentRoom)
                && (answer.page() == sentPage || answer.page() < sentPage && answer.page() == answer.pages() - 1);
    }

    // Whether the answer to the search sent last is shown.
    private boolean answered() {
        return shown != null && answers(shown) && shown.page() == sentPage;
    }

    private @Nullable Entry entryAt(int slot) {
        int index = scroll + slot;
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    // What an entry offers at its right.
    private List<Act> acts(@Nullable Entry entry) {
        return switch (entry) {
            case YouEntry you -> youActs(you.you());
            case RoomEntry header when header.room() != null -> roomActs(header.room());
            case RowEntry row when row.row().kind() == ExplorerPage.Kind.DEVICE && row.row().id().equals(selected) -> deviceActs(row.row());
            case null, default -> List.of();
        };
    }

    // The You row's choices (D9), those the chat prompt offers; the one the player made is pressed.
    private List<Act> youActs(ExplorerPage.You you) {
        List<Act> acts = new ArrayList<>();
        for (PrivacyCommands.Choice choice : PrivacyCommands.offered(you.offered())) {
            String key = switch (choice) {
                case ON -> "mineqtt.privacy.share_presence";
                case FULL -> "mineqtt.privacy.share_all";
                case OFF -> "mineqtt.privacy.dont_share";
            };
            acts.add(new Act(Component.translatable("mineqtt.explorer.you.choice." + choice.word()), Component.translatable(key + ".hover"),
                    you.choice() != choice.consent(), () -> {
                NetworkManager.sendToServer(new PrivacyChoicePayload(choice));
                refreshSoon();
            }));
        }
        return acts;
    }

    // A selected device's actions (D13): Use where its type has one, Locate, Open.
    private List<Act> deviceActs(ExplorerPage.Row row) {
        return List.of(
                new Act(Component.translatable("mineqtt.explorer.action.use"), Component.translatable(row.usable()
                        ? "mineqtt.explorer.action.use.tooltip" : "mineqtt.explorer.action.use.none"), row.usable(), () -> act(row, ExplorerActions.Kind.USE)),
                new Act(Component.translatable("mineqtt.explorer.action.locate"), Component.translatable("mineqtt.explorer.action.locate.tooltip"), true,
                        () -> act(row, ExplorerActions.Kind.LOCATE)),
                new Act(Component.translatable("mineqtt.explorer.action.open"), Component.translatable("mineqtt.explorer.action.open.tooltip",
                        (int) C2SGuard.CONFIGURATOR_RANGE), true, () -> act(row, ExplorerActions.Kind.OPEN)));
    }

    // A room's All on and All off (D1): the lamps, plugs and outputs of it the player finds.
    private List<Act> roomActs(ExplorerPage.RoomRef room) {
        return List.of(
                new Act(Component.translatable("mineqtt.explorer.room.all_on"), Component.translatable("mineqtt.explorer.room.all_on.tooltip", RoomSwitch.MAX_DEVICES),
                        true, () -> act(room.id(), ExplorerActions.Kind.ROOM_ON)),
                new Act(Component.translatable("mineqtt.explorer.room.all_off"), Component.translatable("mineqtt.explorer.room.all_off.tooltip", RoomSwitch.MAX_DEVICES),
                        true, () -> act(room.id(), ExplorerActions.Kind.ROOM_OFF)));
    }

    private void act(ExplorerPage.Row row, ExplorerActions.Kind kind) {
        act(row.id(), kind);
    }

    private void act(UUID target, ExplorerActions.Kind kind) {
        NetworkManager.sendToServer(new ExplorerActionPayload(target, kind));
        refreshSoon();
    }

    private void press(int slot, int index) {
        List<Act> acts = acts(entryAt(slot));
        if (index < acts.size() && acts.get(index).active()) acts.get(index).run().run();
    }

    // Places each visible entry's buttons at its right, labelled for what it offers; the others hide.
    private void layout() {
        if (search == null) return;
        Font font = GuiText.font();
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - DevicesTab.VISIBLE_ROWS));
        int rightX = left + rowWidth();
        for (int slot = 0; slot < DevicesTab.VISIBLE_ROWS; slot++) {
            List<Act> acts = acts(entryAt(slot));
            int x = rightX;
            int y = top + DevicesTab.LIST_Y + slot * DevicesTab.ROW_PITCH + (DevicesTab.ROW_PITCH - DevicesTab.ACTION_H) / 2;
            for (int index = MAX_ACTIONS - 1; index >= 0; index--) {
                Button button = actionButtons[slot][index];
                if (index >= acts.size()) {
                    button.visible = false;
                    button.active = false;
                    continue;
                }
                Act act = acts.get(index);
                int width = font.width(act.label()) + BUTTON_PADDING;
                x -= width;
                button.setX(x);
                button.setY(y);
                button.setSize(width, DevicesTab.ACTION_H);
                button.setMessage(act.label());
                button.setTooltip(Tooltip.create(act.tooltip()));
                button.visible = true;
                button.active = act.active();
                x -= BUTTON_GAP;
            }
        }
    }

    // How wide an entry's buttons are, with the gap before them; 0 without any.
    private int actsWidth(int slot) {
        int width = 0;
        for (Button button : actionButtons[slot]) {
            if (button.visible) width += button.getWidth() + BUTTON_GAP;
        }
        return width == 0 ? 0 : width + GAP - BUTTON_GAP;
    }

    private static int rowWidth() {
        return DevicesTab.CONTENT_W - SCROLLBAR_W - 2;
    }

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        poll();
        Font font = GuiText.font();
        int headerY = guiTop + DevicesTab.HEADER_Y;
        int listY = guiTop + DevicesTab.LIST_Y;
        if (shown == null) {
            boolean gaveUp = sentQuery != null && resends >= DevicesTab.MAX_RESENDS && Util.getMillis() - sentAt >= DevicesTab.RESEND_MILLIS;
            GuiText.drawTruncated(g, Component.translatable(gaveUp ? "mineqtt.explorer.no_answer" : "mineqtt.explorer.searching"), left, headerY,
                    DevicesTab.CONTENT_W, MUTED_COLOR);
            return;
        }
        GuiText.drawTruncated(g, header(shown), left, headerY, DevicesTab.CONTENT_W, LABEL_COLOR);
        if (entries.isEmpty() || entries.size() == 1 && entries.getFirst() instanceof YouEntry) {
            int emptyY = entries.isEmpty() ? listY : listY + DevicesTab.ROW_PITCH;
            if (!entries.isEmpty()) renderEntry(g, font, 0, listY, mouseX, mouseY);
            GuiText.drawWrapped(g, empty(shown).getString(), left, emptyY, DevicesTab.CONTENT_W, MUTED_COLOR);
            return;
        }
        for (int slot = 0; slot < DevicesTab.VISIBLE_ROWS && scroll + slot < entries.size(); slot++) {
            renderEntry(g, font, slot, listY + slot * DevicesTab.ROW_PITCH, mouseX, mouseY);
        }
        if (entries.size() > DevicesTab.VISIBLE_ROWS) {
            int trackX = left + DevicesTab.CONTENT_W - SCROLLBAR_W;
            int trackH = DevicesTab.VISIBLE_ROWS * DevicesTab.ROW_PITCH;
            int thumbH = Math.max(6, trackH * DevicesTab.VISIBLE_ROWS / entries.size());
            int thumbY = listY + (trackH - thumbH) * scroll / (entries.size() - DevicesTab.VISIBLE_ROWS);
            g.fill(trackX, listY, trackX + SCROLLBAR_W, listY + trackH, SCROLL_TRACK);
            g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, SCROLL_THUMB);
        }
    }

    private void renderEntry(GuiGraphicsExtractor g, Font font, int slot, int rowY, int mouseX, int mouseY) {
        Entry entry = entryAt(slot);
        if (entry == null) return;
        int rowW = rowWidth();
        int textW = rowW - actsWidth(slot);
        boolean hovered = mouseX >= left && mouseX < left + textW && mouseY >= rowY && mouseY < rowY + DevicesTab.ROW_PITCH;
        switch (entry) {
            case YouEntry you -> {
                GuiText.drawTruncated(g, Component.translatable("mineqtt.explorer.you"), left, rowY, textW, VALUE_COLOR);
                GuiText.drawTruncated(g, youStatus(you.you()), left, rowY + LINE_H, textW, MUTED_COLOR);
                if (hovered) g.setTooltipForNextFrame(font, youTooltip(font, you.you()), mouseX, mouseY);
            }
            case RoomEntry header -> {
                Component name = header.room() == null ? Component.translatable("mineqtt.explorer.room.none") : Component.literal(header.room().name());
                GuiText.drawTruncated(g, name, left, rowY, textW, ROOM_COLOR);
                if (header.room() != null) {
                    GuiText.drawTruncated(g, Component.translatable(header.room().devices() == 1 ? "mineqtt.explorer.room.devices.one" : "mineqtt.explorer.room.devices",
                            header.room().devices()), left, rowY + LINE_H, textW, MUTED_COLOR);
                }
                g.fill(left - 1, rowY + DevicesTab.ROW_PITCH - 1, left + rowW, rowY + DevicesTab.ROW_PITCH, ROOM_COLOR);
            }
            case RowEntry rowEntry -> {
                ExplorerPage.Row row = rowEntry.row();
                if (row.id().equals(selected)) {
                    g.fill(left - 1, rowY, left + rowW, rowY + DevicesTab.ROW_PITCH, SELECTED_COLOR);
                } else if (hovered) {
                    g.fill(left - 1, rowY, left + rowW, rowY + DevicesTab.ROW_PITCH, HOVER_COLOR);
                }
                Component word = row.kind() == ExplorerPage.Kind.GROUP ? Component.translatable("mineqtt.explorer.group")
                        : Component.translatable("mineqtt.explorer.availability." + key(row.availability()));
                Component where = where(row);
                boolean acting = textW < rowW;
                int wordW = acting ? 0 : Math.min(font.width(word), textW / 2);
                int whereW = acting ? 0 : Math.min(font.width(where), textW / 2);
                if (!acting) {
                    GuiText.drawRight(g, word.getString(), left + textW, rowY, wordW, row.kind() == ExplorerPage.Kind.GROUP ? MUTED_COLOR : color(row.availability()));
                    GuiText.drawRight(g, where.getString(), left + textW, rowY + LINE_H, whereW, MUTED_COLOR);
                }
                GuiText.drawTruncated(g, Component.literal(row.name()), left, rowY, textW - wordW - (acting ? 0 : GAP), VALUE_COLOR);
                GuiText.drawTruncated(g, summary(row), left, rowY + LINE_H, textW - whereW - (acting ? 0 : GAP), MUTED_COLOR);
                if (hovered) g.setTooltipForNextFrame(font, tooltip(font, row), mouseX, mouseY);
            }
        }
    }

    // How many devices the search found, whose, and the page when there are several.
    private static Component header(ExplorerPage page) {
        String scope = page.scope() == ExplorerPage.Scope.ALL ? "all" : "own";
        Component count = Component.translatable(page.total() == 1 ? "mineqtt.explorer.header." + scope + ".one" : "mineqtt.explorer.header." + scope,
                page.total());
        return page.pages() > 1 ? Component.translatable("mineqtt.explorer.header.paged", count, page.page() + 1, page.pages()) : count;
    }

    // Why the page lists nothing.
    private static Component empty(ExplorerPage page) {
        if (page.scope() == ExplorerPage.Scope.NONE) return Component.translatable("mineqtt.explorer.empty.none");
        if (!page.query().isEmpty()) return Component.translatable("mineqtt.explorer.empty.search", page.query());
        if (!page.room().isEmpty()) return Component.translatable("mineqtt.explorer.empty.room", page.room());
        return Component.translatable(page.scope() == ExplorerPage.Scope.ALL ? "mineqtt.explorer.empty.all" : "mineqtt.explorer.empty.own");
    }

    // The You row's second line: what is shared about the player now.
    private static Component youStatus(ExplorerPage.You you) {
        if (you.offered() == PrivacyPolicy.Shared.NOTHING) return Component.translatable("mineqtt.explorer.you.server_shares_nothing");
        return switch (you.shared()) {
            case NOTHING -> Component.translatable(you.choice() == Consent.UNSET
                    ? "mineqtt.explorer.you.not_chosen" : "mineqtt.explorer.you.nothing");
            case PRESENCE -> Component.translatable(you.host() && you.choice() == Consent.UNSET
                    ? "mineqtt.explorer.you.host" : "mineqtt.explorer.you.presence");
            case FULL -> Component.translatable("mineqtt.explorer.you.full");
        };
    }

    // The lines of /mineqtt privacy, and for an operator the presence devices of the players who share them.
    private static List<FormattedCharSequence> youTooltip(Font font, ExplorerPage.You you) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String line : you.report()) lines.addAll(font.split(Component.literal(line), TOOLTIP_W));
        if (!you.presence().isEmpty()) {
            lines.addAll(font.split(Component.translatable("mineqtt.explorer.you.presence_devices"), TOOLTIP_W));
            for (String line : you.presence()) lines.addAll(font.split(Component.literal("- " + line), TOOLTIP_W));
            if (you.morePresence() > 0) lines.addAll(font.split(Component.translatable("mineqtt.explorer.you.more", you.morePresence()), TOOLTIP_W));
        }
        return lines;
    }

    // The row's second line: its model, then its values; a group says how many members it has.
    private static Component summary(ExplorerPage.Row row) {
        List<String> parts = new ArrayList<>();
        if (row.kind() == ExplorerPage.Kind.GROUP) {
            parts.add(Component.translatable(row.members() == 1 ? "mineqtt.explorer.group.members.one" : "mineqtt.explorer.group.members", row.members()).getString());
            if (!row.model().isEmpty()) parts.add(row.model());
        } else {
            parts.add(row.model());
        }
        parts.addAll(row.values());
        return Component.literal(String.join(" · ", parts));
    }

    // How far away and in which direction, or the dimension it is in; nothing for a group.
    private static Component where(ExplorerPage.Row row) {
        if (row.kind() == ExplorerPage.Kind.GROUP) return Component.empty();
        if (!row.near()) return Component.translatable("mineqtt.explorer.elsewhere", row.dimension());
        if (row.bearing() == ExplorerPage.Bearing.HERE) return Component.translatable("mineqtt.explorer.distance.here", row.distance());
        return Component.translatable("mineqtt.explorer.distance", row.distance(),
                Component.translatable("mineqtt.explorer.bearing." + row.bearing().name().toLowerCase(Locale.ROOT)));
    }

    private static List<FormattedCharSequence> tooltip(Font font, ExplorerPage.Row row) {
        if (row.kind() == ExplorerPage.Kind.GROUP) {
            List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.translatable("mineqtt.explorer.group.tooltip", row.name(), row.slug(),
                    summary(row)), TOOLTIP_W));
            if (!row.values().isEmpty()) lines.addAll(font.split(Component.literal(String.join(" · ", row.values())), TOOLTIP_W));
            return lines;
        }
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.translatable("mineqtt.explorer.row.tooltip", row.name(), row.slug(),
                row.model(), Component.translatable("mineqtt.explorer.availability." + key(row.availability()) + ".tooltip")), TOOLTIP_W));
        if (!row.values().isEmpty()) lines.addAll(font.split(Component.literal(String.join(" · ", row.values())), TOOLTIP_W));
        lines.addAll(font.split(row.near() ? Component.translatable("mineqtt.explorer.row.near", row.dimension(), where(row))
                : Component.translatable("mineqtt.explorer.elsewhere", row.dimension()), TOOLTIP_W));
        if (!row.detail().isEmpty()) lines.addAll(font.split(Component.literal(row.detail()), TOOLTIP_W));
        lines.addAll(font.split(Component.translatable("mineqtt.explorer.row.select"), TOOLTIP_W));
        return lines;
    }

    private static String key(ExplorerPage.Availability availability) {
        return availability.name().toLowerCase(Locale.ROOT);
    }

    private static int color(ExplorerPage.Availability availability) {
        return switch (availability) {
            case ONLINE -> ONLINE_COLOR;
            case NO_BROKER -> WARNING_COLOR;
            case QUEUED, UNAVAILABLE -> MUTED_COLOR;
            case FAULTED, UNKNOWN_TYPE -> REFUSED_COLOR;
        };
    }

    /**
     * A click on a device selects it, so its actions show at its right, and a click on the selected one lets it go; a
     * click on a button is the button's.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || shown == null) return false;
        int listY = top + DevicesTab.LIST_Y;
        if (mouseX < left || mouseX >= left + rowWidth() || mouseY < listY || mouseY >= listY + DevicesTab.VISIBLE_ROWS * DevicesTab.ROW_PITCH) return false;
        for (Button[] slot : actionButtons) {
            for (AbstractWidget widget : slot) {
                if (widget.visible && mouseX >= widget.getX() && mouseX < widget.getX() + widget.getWidth()
                        && mouseY >= widget.getY() && mouseY < widget.getY() + widget.getHeight()) return false;
            }
        }
        int slot = (int) ((mouseY - listY) / DevicesTab.ROW_PITCH);
        if (!(entryAt(slot) instanceof RowEntry row) || row.row().kind() != ExplorerPage.Kind.DEVICE) return false;
        selected = row.row().id().equals(selected) ? null : row.row().id();
        layout();
        return true;
    }

    /** The search field takes every key but Escape, which closes the screen, while it has the focus. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE || !search.isFocused()) return false;
        return search.keyPressed(new KeyEvent(keyCode, scanCode, modifiers)) || search.canConsumeInput();
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return search.isFocused() && search.charTyped(new CharacterEvent(c));
    }

    // The wheel scrolls the list, an entry a notch.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (dy == 0 || shown == null) return false;
        scroll -= (int) Math.signum(dy);
        layout();
        return true;
    }
}

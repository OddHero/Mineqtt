package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.compat.Inputs;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceGroups;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceGroupPayload;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The General tab's group pages (M2 plan §4.2 M2-17), for a device whose type can be grouped, drawn from the device's
 * {@link DeviceGroups} and {@link DeviceView}. The group page names the device's group with its member count and
 * topic; its name field renames the group, or names a new one the device makes while it is in none; Leave takes the
 * device out, and a button shows or hides the group's members to Home Assistant. The join page lists the groups the
 * device may join, one row each with a Join button, scrolling with the mouse wheel, as the device screen keeps one height
 * for every tab. What the player may not do is inactive and says why on hover; a change the server refuses shows its
 * reason in the header until the page sends again ({@link DeviceMenu#refusal}).
 */
final class GroupPages {

    /** The two pages. */
    enum Page {
        GROUP,
        JOIN
    }

    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    private static final int FIRST_ROW_Y = 17;
    private static final int ROW_H = 14;
    // The rows of the Controls tab: 14 px at a pitch of 16, the fifth ending at y 95, clear of the panel's foot (P1).
    private static final int PITCH = 16;
    private static final int JOIN_ROWS = 4;
    private static final int GAP = 4;
    private static final int SCROLLBAR_W = 3;
    private static final int LABEL_COLOR = 0xFF555555;
    private static final int VALUE_COLOR = 0xFF303030;
    private static final int PREVIEW_COLOR = 0xFF0066CC;
    private static final int MUTED_COLOR = 0xFF777777;
    private static final int REFUSED_COLOR = 0xFFAA0000;
    private static final int SCROLL_TRACK = 0xFF8B8B8B;
    private static final int SCROLL_THUMB = 0xFF555555;

    private final DeviceMenu menu;
    private final List<AbstractWidget> groupWidgets = new ArrayList<>();
    private final List<AbstractWidget> joinWidgets = new ArrayList<>();
    private final List<Button> joinButtons = new ArrayList<>();
    private int left;
    private int top;
    private @Nullable Page page;
    private int joinScroll;
    private EditBox nameField;
    private Button nameButton;
    private Button leaveButton;
    private Button membersButton;
    private Button joinPageButton;
    // What the widgets were last brought up to date with, and the name the field showed then.
    private @Nullable DeviceGroups synced;
    private @Nullable DeviceView syncedView;
    private String syncedName = "";

    GroupPages(DeviceMenu menu) {
        this.menu = menu;
    }

    /**
     * Lays the pages out; their widgets start hidden.
     *
     * @param back what the group page's back button does: it returns to the General page
     */
    void init(int guiLeft, int guiTop, Runnable back) {
        groupWidgets.clear();
        joinWidgets.clear();
        joinButtons.clear();
        Font font = GuiText.font();
        left = guiLeft + 8;
        top = guiTop;
        int right = left + CONTENT_W;
        int y = guiTop + FIRST_ROW_Y;
        Component backLabel = Component.translatable("mineqtt.device.group.back");
        groupWidgets.add(Button.builder(backLabel, button -> back.run()).bounds(left, y, font.width(backLabel) + 10, ROW_H).build());

        y += PITCH;
        int nameW = List.of("mineqtt.device.group.rename", "mineqtt.device.group.create").stream()
                .mapToInt(key -> font.width(Component.translatable(key))).max().orElse(0) + 10;
        nameButton = add(groupWidgets, Button.builder(Component.empty(), button -> commitName()).bounds(right - nameW, y, nameW, ROW_H).build());
        nameField = new EditBox(font, left, y, CONTENT_W - nameW - GAP, ROW_H, Component.translatable("mineqtt.device.group.name"));
        nameField.setMaxLength(GroupRules.MAX_NAME_LENGTH);
        nameField.setTextColorUneditable(MUTED_COLOR);
        groupWidgets.add(nameField);

        y += PITCH;
        Component leave = Component.translatable("mineqtt.device.group.leave");
        leaveButton = add(groupWidgets, Button.builder(leave, button -> send(new GroupRules.Leave())).bounds(left, y, font.width(leave) + 12, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.device.group.leave.tooltip"))).build());
        int membersW = List.of("shown", "hidden").stream()
                .mapToInt(state -> font.width(Component.translatable("mineqtt.device.group.members." + state))).max().orElse(0) + 12;
        membersButton = add(groupWidgets, Button.builder(Component.empty(), button -> stepMembers()).bounds(right - membersW, y, membersW, ROW_H).build());

        y += PITCH;
        joinPageButton = add(groupWidgets, Button.builder(Component.empty(), button -> show(Page.JOIN)).bounds(left, y, CONTENT_W, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("mineqtt.device.group.join.tooltip"))).build());

        int headerY = guiTop + FIRST_ROW_Y;
        Component joinBack = Component.translatable("mineqtt.device.group.join.back");
        joinWidgets.add(Button.builder(joinBack, button -> show(Page.GROUP)).bounds(left, headerY, font.width(joinBack) + 10, ROW_H).build());
        Component join = Component.translatable("mineqtt.device.group.join.row");
        int joinW = font.width(join) + 10;
        for (int row = 0; row < JOIN_ROWS; row++) {
            int index = row;
            joinButtons.add(add(joinWidgets, Button.builder(join, button -> join(index))
                    .bounds(right - SCROLLBAR_W - 2 - joinW, headerY + (row + 1) * PITCH, joinW, ROW_H).build()));
        }
        synced = null;
        syncedView = null;
        show(null);
    }

    private static <T extends AbstractWidget> T add(List<AbstractWidget> widgets, T widget) {
        widgets.add(widget);
        return widget;
    }

    /** Every widget of both pages, for the screen to register. */
    List<AbstractWidget> widgets() {
        List<AbstractWidget> all = new ArrayList<>(groupWidgets);
        all.addAll(joinWidgets);
        return all;
    }

    /** Shows one of the pages, or neither (null) while the General page shows; a field being typed in lets go of the keys. */
    void show(@Nullable Page shown) {
        page = shown;
        groupWidgets.forEach(widget -> widget.visible = page == Page.GROUP);
        joinWidgets.forEach(widget -> widget.visible = page == Page.JOIN);
        if (page != Page.GROUP) nameField.setFocused(false);
        update();
    }

    /** Whether a group page shows. */
    boolean showing() {
        return page != null;
    }

    // Brings the widgets up to date with the last group page and view, and the join rows with the scroll.
    private void update() {
        DeviceGroups groups = synced;
        DeviceView view = syncedView;
        boolean configure = view != null && view.grants().configure();
        Optional<DeviceGroups.Own> own = groups == null ? Optional.empty() : groups.own();
        boolean manages = own.map(DeviceGroups.Own::manages).orElse(true);
        nameButton.setMessage(Component.translatable(own.isPresent() ? "mineqtt.device.group.rename" : "mineqtt.device.group.create"));
        boolean naming = configure && manages;
        nameField.setEditable(naming);
        nameField.active = naming;
        nameField.setHint(own.isPresent() ? Component.empty()
                : Component.translatable("mineqtt.device.group.name.hint").withStyle(EditBox.DEFAULT_HINT_STYLE));
        Tooltip denied = !configure ? Tooltip.create(Component.translatable("mineqtt.device.general.read_only"))
                : Tooltip.create(Component.translatable("mineqtt.device.group.not_managed"));
        nameField.setTooltip(naming ? null : denied);
        nameButton.setTooltip(naming ? Tooltip.create(Component.translatable(own.isPresent()
                ? "mineqtt.device.group.rename.tooltip" : "mineqtt.device.group.create.tooltip")) : denied);

        leaveButton.visible = page == Page.GROUP && own.isPresent();
        leaveButton.active = configure;
        membersButton.visible = page == Page.GROUP && own.isPresent();
        boolean exposes = view != null && DeviceConfig.permits(LinkSetting.Gate.EXPOSE, view.grants());
        membersButton.active = exposes && manages;
        membersButton.setMessage(Component.translatable(own.map(DeviceGroups.Own::exposeMembers).orElse(false)
                ? "mineqtt.device.group.members.shown" : "mineqtt.device.group.members.hidden"));
        membersButton.setTooltip(Tooltip.create(Component.translatable(!configure ? "mineqtt.device.general.read_only"
                : !exposes ? "mineqtt.device.general.expose.denied" : !manages ? "mineqtt.device.group.not_managed"
                : "mineqtt.device.group.members.tooltip")));

        int total = groups == null ? 0 : groups.total();
        // In a group it sits under Leave; in none, under the name of a new group, with the explanation below it.
        joinPageButton.setY(top + FIRST_ROW_Y + (own.isPresent() ? 3 : 2) * PITCH);
        joinPageButton.setMessage(Component.translatable(own.isPresent() ? "mineqtt.device.group.join.other" : "mineqtt.device.group.join.page", total));
        joinPageButton.active = configure && total > 0;

        List<DeviceGroups.Joinable> rows = joinable();
        joinScroll = Mth.clamp(joinScroll, 0, Math.max(0, rows.size() - JOIN_ROWS));
        for (int row = 0; row < joinButtons.size(); row++) {
            Button button = joinButtons.get(row);
            button.visible = page == Page.JOIN && joinScroll + row < rows.size();
            button.active = configure;
            button.setTooltip(Tooltip.create(Component.translatable(configure ? "mineqtt.device.group.join.row.tooltip" : "mineqtt.device.general.read_only")));
        }
    }

    private List<DeviceGroups.Joinable> joinable() {
        return synced == null ? List.of() : synced.joinable();
    }

    // A new group page or view: the field follows the group's name unless the player changed it since the last one.
    private void sync(DeviceGroups groups, DeviceView view) {
        String name = groups.own().map(DeviceGroups.Own::name).orElse("");
        if (synced == null || nameField.getValue().equals(syncedName)) {
            nameField.setValue(name);
            nameField.moveCursorToStart(false);
        }
        synced = groups;
        syncedView = view;
        syncedName = name;
        update();
    }

    // The name as the server would keep it, when it is one and differs from the group's.
    private Optional<String> pendingName() {
        Optional<String> name = GroupRules.name(nameField.getValue()).result();
        String current = synced == null ? "" : synced.own().map(DeviceGroups.Own::name).orElse("");
        return name.filter(typed -> !typed.equals(current));
    }

    // Renames the group, or makes a new one with the device while it is in none.
    private void commitName() {
        if (synced == null || syncedView == null || !nameField.active) return;
        pendingName().ifPresent(name -> {
            // The field shows the name as sent, so the server's answer replaces it.
            nameField.setValue(name);
            syncedName = name;
            send(synced.own().isPresent() ? new GroupRules.Rename(name) : new GroupRules.Create(name));
        });
    }

    private void stepMembers() {
        if (synced == null) return;
        synced.own().ifPresent(own -> send(new GroupRules.ExposeMembers(!own.exposeMembers())));
    }

    // Joins the group a row's button stands by and returns to the group page, which shows the device's new group.
    private void join(int row) {
        List<DeviceGroups.Joinable> rows = joinable();
        if (joinScroll + row >= rows.size()) return;
        send(new GroupRules.Join(rows.get(joinScroll + row).id()));
        show(Page.GROUP);
    }

    private void send(GroupRules.Change change) {
        DeviceView view = syncedView;
        if (view == null || !GroupRules.permits(change, view.grants())) return;
        menu.forgetRefusal();
        NetworkManager.sendToServer(new DeviceGroupPayload(view.pos(), change));
    }

    /** Draws the page that shows. */
    void draw(GuiGraphicsExtractor g, DeviceView view, int mouseX, int mouseY) {
        Optional<DeviceGroups> groups = menu.groups();
        if (groups.isPresent() && (groups.get() != synced || view != syncedView)) sync(groups.get(), view);
        if (page == Page.JOIN) {
            renderJoin(g, mouseX, mouseY);
            return;
        }
        Optional<DeviceGroups.Own> own = synced == null ? Optional.empty() : synced.own();
        boolean pending = pendingName().isPresent();
        nameButton.active = nameField.active && pending;
        nameField.setTextColor(pending && own.isPresent() ? PREVIEW_COLOR : EditBox.DEFAULT_TEXT_COLOR);
        int headerX = groupWidgets.getFirst().getX() + groupWidgets.getFirst().getWidth() + GAP;
        Component header = own.map(group -> Component.translatable("mineqtt.device.group.header", group.members()))
                .orElse(Component.translatable("mineqtt.device.group.header.none"));
        renderHeader(g, header, headerX, mouseX, mouseY);
        if (own.isPresent()) {
            // On up to two lines that break after a /, as on the General page, ending by y 100.
            int infoY = top + FIRST_ROW_Y + 4 * PITCH + 2;
            Font font = GuiText.font();
            Component label = Component.translatable("mineqtt.device.group.topic");
            int labelW = font.width(label) + GAP;
            g.text(font, label, left, infoY, LABEL_COLOR, false);
            GeneralTabView.drawTopic(g, own.get().topic(), left + labelW, infoY, CONTENT_W - labelW, VALUE_COLOR, mouseX, mouseY);
        } else {
            // Up to three lines, ending by y 95.
            GuiText.drawWrapped(g, Component.translatable("mineqtt.device.group.explain").getString(), left, top + FIRST_ROW_Y + 3 * PITCH + 3, CONTENT_W, MUTED_COLOR);
        }
    }

    // The join page: the groups the device may join, a row each with its member count and a Join button.
    private void renderJoin(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        AbstractWidget back = joinWidgets.getFirst();
        int total = synced == null ? 0 : synced.total();
        List<DeviceGroups.Joinable> rows = joinable();
        Component header = total > rows.size() ? Component.translatable("mineqtt.device.group.join.header.capped", total, rows.size())
                : Component.translatable("mineqtt.device.group.join.header", total);
        renderHeader(g, header, back.getX() + back.getWidth() + GAP, mouseX, mouseY);
        int listY = top + FIRST_ROW_Y + PITCH;
        if (rows.isEmpty()) {
            GuiText.drawWrapped(g, Component.translatable("mineqtt.device.group.join.empty").getString(), left, listY + 3, CONTENT_W, MUTED_COLOR);
            return;
        }
        int textW = joinButtons.getFirst().getX() - GAP - left;
        for (int row = 0; row < JOIN_ROWS && joinScroll + row < rows.size(); row++) {
            DeviceGroups.Joinable group = rows.get(joinScroll + row);
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.group.join.entry", group.name(), group.members()), left, listY + row * PITCH + 3,
                    textW, VALUE_COLOR, mouseX, mouseY);
        }
        if (rows.size() > JOIN_ROWS) {
            int trackX = left + CONTENT_W - SCROLLBAR_W;
            int trackH = JOIN_ROWS * PITCH - (PITCH - ROW_H);
            int thumbH = Math.max(6, trackH * JOIN_ROWS / rows.size());
            int thumbY = listY + (trackH - thumbH) * joinScroll / (rows.size() - JOIN_ROWS);
            g.fill(trackX, listY, trackX + SCROLLBAR_W, listY + trackH, SCROLL_TRACK);
            g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, SCROLL_THUMB);
        }
    }

    // The header right of the back button: what the page shows, or why the server refused the last change from it.
    private void renderHeader(GuiGraphicsExtractor g, Component header, int x, int mouseX, int mouseY) {
        int y = top + FIRST_ROW_Y + 3;
        int width = left + CONTENT_W - x;
        Optional<DeviceMenu.Refusal> refusal = menu.refusal().filter(refused -> refused.key().equals(DeviceRuntime.GROUP_KEY));
        if (refusal.isPresent()) {
            GuiText.drawTruncated(g, Component.translatable("mineqtt.device.refused", refusal.get().reason()), x, y, width, REFUSED_COLOR, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, header, x, y, width, LABEL_COLOR, mouseX, mouseY);
        }
    }

    /** Enter sends the name field while it has the focus; it takes every other key but Escape, which closes the screen. */
    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (page != Page.GROUP || !nameField.isFocused() || keyCode == InputConstants.KEY_ESCAPE) return false;
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            commitName();
            return true;
        }
        return Inputs.keyPressed(nameField, keyCode, scanCode, modifiers) || nameField.canConsumeInput();
    }

    /** The wheel scrolls the join page, a row a notch. */
    boolean mouseScrolled(double dy) {
        if (page != Page.JOIN || dy == 0) return false;
        joinScroll -= (int) Math.signum(dy);
        update();
        return true;
    }

    /** A click elsewhere ends the typing, so the screen's keys work again (the inventory key closes it). */
    void mouseClicked(double mouseX, double mouseY) {
        if (!nameField.isMouseOver(mouseX, mouseY)) nameField.setFocused(false);
    }
}

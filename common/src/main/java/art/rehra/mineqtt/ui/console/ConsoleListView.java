package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * A Bridge Console tab that lists rows of its {@link ConsoleView}: a header row, then rows of 14 px at a pitch of 16, as
 * many as end by y 100 (P1), as the links page of a device's Advanced tab. The mouse wheel scrolls a row a notch, with a
 * thin scrollbar at the right; a row's details show on hover.
 *
 * @param <R> a row
 */
abstract class ConsoleListView<R> implements MqttTabView {

    protected static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    protected static final int LABEL_COLOR = 0xFF555555;
    protected static final int VALUE_COLOR = 0xFF303030;
    protected static final int MUTED_COLOR = 0xFF777777;
    protected static final int ONLINE_COLOR = 0xFF2E7D32;
    protected static final int WARNING_COLOR = 0xFF9A6700;
    protected static final int REFUSED_COLOR = 0xFFAA0000;
    private static final int HEADER_Y = 17;
    private static final int ROW_H = 14;
    private static final int ROW_PITCH = 16;
    private static final int ROWS = 4;
    private static final int GAP = 4;
    private static final int SCROLLBAR_W = 3;
    private static final int SCROLL_TRACK = 0xFF8B8B8B;
    private static final int SCROLL_THUMB = 0xFF555555;
    private static final int TOOLTIP_W = 200;

    private final TabbedMqttScreen screen;
    private int left;
    private int scroll;

    protected ConsoleListView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        left = guiLeft + 8;
    }

    protected ConsoleMenu menu() {
        return (ConsoleMenu) screen.getMenu();
    }

    /** The rows the view lists. */
    protected abstract List<R> rows(ConsoleView view);

    /** The header: how many rows there are, also beyond those the view carries. */
    protected abstract Component header(ConsoleView view);

    /** The colour of the header. */
    protected abstract int headerColor(ConsoleView view);

    /** What the tab says without rows. */
    protected abstract Component empty(ConsoleView view);

    /** A row's text at the left. */
    protected abstract Component name(R row);

    /** A row's word at the right, with its colour. */
    protected abstract Component word(R row);

    protected abstract int wordColor(R row);

    /** A row's details on hover. */
    protected abstract Component details(R row);

    @Override
    public void renderContent(GuiGraphicsExtractor g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        Font font = GuiText.font();
        ConsoleView view = menu().view();
        List<R> rows = rows(view);
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - ROWS));
        int headerY = guiTop + HEADER_Y;
        GuiText.drawTruncated(g, header(view), left, headerY + 3, CONTENT_W, headerColor(view), mouseX, mouseY);
        int listY = headerY + ROW_PITCH;
        if (rows.isEmpty()) {
            GuiText.drawWrapped(g, empty(view).getString(), left, listY + 3, CONTENT_W, MUTED_COLOR);
            return;
        }
        int rowW = CONTENT_W - SCROLLBAR_W - 2;
        for (int i = 0; i < ROWS && scroll + i < rows.size(); i++) {
            R row = rows.get(scroll + i);
            int rowY = listY + i * ROW_PITCH;
            Component word = word(row);
            int wordW = Math.min(font.width(word), rowW / 2);
            GuiText.drawRight(g, word.getString(), left + rowW, rowY + 3, wordW, wordColor(row));
            GuiText.drawTruncated(g, name(row), left, rowY + 3, rowW - wordW - GAP, VALUE_COLOR);
            if (mouseX >= left && mouseX < left + rowW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                g.setTooltipForNextFrame(font, font.split(details(row), TOOLTIP_W), mouseX, mouseY);
            }
        }
        if (rows.size() > ROWS) {
            int trackX = left + CONTENT_W - SCROLLBAR_W;
            int trackH = ROWS * ROW_PITCH - (ROW_PITCH - ROW_H);
            int thumbH = Math.max(6, trackH * ROWS / rows.size());
            int thumbY = listY + (trackH - thumbH) * scroll / (rows.size() - ROWS);
            g.fill(trackX, listY, trackX + SCROLLBAR_W, listY + trackH, SCROLL_TRACK);
            g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, SCROLL_THUMB);
        }
    }

    /** A lang key for a count: its {@code .one} variant for exactly one. */
    protected static String counted(String key, long count) {
        return count == 1 ? key + ".one" : key;
    }

    // The wheel scrolls the list, a row a notch.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (dy == 0) return false;
        scroll -= (int) Math.signum(dy);
        return true;
    }
}

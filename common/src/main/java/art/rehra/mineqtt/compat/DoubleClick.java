package art.rehra.mineqtt.compat;

import net.minecraft.util.Util;

/**
 * Tells a second click on the same row from a first one. Minecraft passes a screen whether a click is a double click from
 * 1.21.9 on; before, a list screen tells it itself, with the mouse handler's 250 ms.
 */
public final class DoubleClick {
    private static final long WINDOW_MS = 250;
    private long lastAt;
    private int lastRow = -1;

    /** Whether this click on {@code row} completes a double click; a third click starts the next pair. */
    public boolean click(int row) {
        long now = Util.getMillis();
        boolean doubleClick = row == lastRow && now - lastAt < WINDOW_MS;
        lastRow = doubleClick ? -1 : row;
        lastAt = now;
        return doubleClick;
    }
}

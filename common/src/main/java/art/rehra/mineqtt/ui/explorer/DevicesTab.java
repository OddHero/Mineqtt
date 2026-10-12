package art.rehra.mineqtt.ui.explorer;

import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Configurator explorer's Devices tab (M4 plan D13), first and where the explorer opens: the devices the player may
 * configure (every one and the groups for an operator), searched, filtered by room and paged on the server
 * ({@link ExplorerDirectory}), grouped by room, each with how it is available, up to three of its values and how far away
 * it is in which direction. A selected device offers Use, Locate and Open ({@link ExplorerActions}), a room's header All
 * on and All off; the You row on the first page shows what the server shares about the player and their choices (D9). No
 * slots.
 * <p>
 * Its layout, above the explorer's inventory: the search field with the room filter and the page buttons at its right,
 * a header line, then {@value #VISIBLE_ROWS} entries of two lines that end by y {@value #CONTENT_BOTTOM}, where the
 * inventory's label starts (P1). An entry's buttons, {@value #ACTION_H} px high, sit at its right.
 */
public class DevicesTab extends MqttTab {

    public static final String ID = "cyberdeck_devices";
    public static final int SEARCH_Y = 17;
    public static final int SEARCH_H = 14;
    public static final int PAGE_BUTTON_W = 14;
    /** The room filter's button between the search field and the page buttons. */
    public static final int ROOM_BUTTON_W = 52;
    /** The buttons of a list entry (a device's actions, the You row's choices, a room's switches) sit at its right. */
    public static final int ACTION_H = 16;
    public static final int HEADER_Y = 34;
    public static final int LIST_Y = 44;
    public static final int ROW_PITCH = 18;
    public static final int VISIBLE_ROWS = 3;
    public static final int CONTENT_BOTTOM = LIST_Y + VISIBLE_ROWS * ROW_PITCH;
    /** The content's width: the panel less vanilla's margins. */
    public static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    /** A search goes out once the typing rested this long; a page button's at once. */
    public static final long DEBOUNCE_MILLIS = 250;
    /** A search the server dropped over its rate, so no answer came, goes out again after this long, at most {@value #MAX_RESENDS} times. */
    public static final long RESEND_MILLIS = 1000;
    public static final int MAX_RESENDS = 3;
    /** The page shown is asked for again this often while the tab is open, so availability and values stay current. */
    public static final long REFRESH_MILLIS = 2000;

    /** The gap between the search row's field and buttons. */
    public static final int SEARCH_GAP = 2;
    /** The search field's width: what the room filter and the page buttons leave of the content's width. */
    public static final int SEARCH_W = CONTENT_W - ROOM_BUTTON_W - 2 * PAGE_BUTTON_W - 3 * SEARCH_GAP;

    public DevicesTab() {
        super(ID);
    }

    /** Whether the tab sends its last search again: no answer came within {@value #RESEND_MILLIS} ms, at most {@value #MAX_RESENDS} times. */
    public static boolean resendDue(boolean answered, int resends, long sinceSentMillis) {
        return !answered && resends < MAX_RESENDS && sinceSentMillis >= RESEND_MILLIS;
    }

    /** Whether the tab asks for the page it shows again: its answer came and {@value #REFRESH_MILLIS} ms passed since it was sent. */
    public static boolean refreshDue(boolean answered, long sinceSentMillis) {
        return answered && sinceSentMillis >= REFRESH_MILLIS;
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.SPYGLASS);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.cyberdeck_devices");
    }
}

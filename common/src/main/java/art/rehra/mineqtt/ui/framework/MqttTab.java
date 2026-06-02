package art.rehra.mineqtt.ui.framework;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Server-safe descriptor of a single tab inside a {@link TabbedMqttMenu}.
 *
 * <p>A tab contributes its own set of slots to the underlying container menu and
 * carries the metadata required to render its tab-bar entry (icon + title). The
 * actual on-screen content rendering lives in a parallel client-only
 * {@link MqttTabView} registered under the same id in {@link MqttTabViews}.</p>
 */
public abstract class MqttTab {

    private final String id;

    protected MqttTab(String id) {
        this.id = id;
    }

    /**
     * Stable identifier — also used to look up the client view and to persist the last-opened tab.
     */
    public final String id() {
        return id;
    }

    /**
     * Icon item rendered inside the tab button (similar to creative-menu category icons).
     */
    public abstract ItemStack icon();

    /**
     * Title shown above the tab content.
     */
    public abstract Component title();

    /**
     * Adds the slots this tab needs to the given menu. The slots must be wrapped
     * in {@link TabbedSlot} so they are only active when this tab is selected.
     */
    public void buildSlots(TabbedMqttMenu menu, Container container) {
        // Default: no extra slots
    }
}

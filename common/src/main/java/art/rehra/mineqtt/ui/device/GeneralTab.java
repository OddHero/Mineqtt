package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The General tab of every device (M2 plan §4.2 M2-8): its name with a live preview of the slug and the state topic that
 * follow it, its Home Assistant exposure, its group and the topic to copy. The name and the exposure are the device's
 * {@link LinkSetting#NAME} and {@link LinkSetting#EXPOSE}; a player without their gate sees them read-only. A type that
 * can be grouped (design D1: actuators only) also has the group pages (M2-17), where a player joins the device to a
 * group, takes it out, makes a new group with it, renames its group and shows or hides that group's members, each a
 * {@link GroupRules.Change} behind its gate ({@link GroupRules#permits}). No slots.
 */
public class GeneralTab extends MqttTab {

    public static final String ID = "general";

    private final DeviceTabs.Control name;
    private final DeviceTabs.Control expose;
    private final boolean grouping;

    public GeneralTab(DeviceType type) {
        super(ID);
        this.name = DeviceTabs.Control.of(type.linkSetting(LinkSetting.NAME.key()).orElseThrow());
        this.expose = DeviceTabs.Control.of(type.linkSetting(LinkSetting.EXPOSE.key()).orElseThrow());
        this.grouping = type.groupable();
    }

    /** Whether the tab has the group pages: only a type that can be grouped does. */
    public boolean grouping() {
        return grouping;
    }

    /** The device's name: a text field. */
    public DeviceTabs.Control name() {
        return name;
    }

    /** The device's Home Assistant exposure: on, off or default. */
    public DeviceTabs.Control expose() {
        return expose;
    }

    public List<DeviceTabs.Control> controls() {
        return List.of(name, expose);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.NAME_TAG);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.general");
    }
}

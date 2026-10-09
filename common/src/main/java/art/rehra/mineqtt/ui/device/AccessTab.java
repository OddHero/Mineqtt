package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The Access tab of every device (M2 plan §4.2 M2-11, design §8.7): who owns it, its access mode (private, claim or
 * public) and what the claim at the device means on this server. The mode is the device's {@link LinkSetting#ACCESS},
 * which only the owner or an operator may change, so no mode locks the owner out. A device without an owner can be
 * claimed by a player who may configure it, who then owns it. No slots.
 */
public class AccessTab extends MqttTab {

    public static final String ID = "access";
    /** The keys a refusal from this tab names: the mode, and a claim. */
    public static final List<String> REFUSAL_KEYS = List.of(LinkSetting.ACCESS.key(), DeviceRuntime.CLAIM_KEY);

    private final DeviceTabs.Control access;

    public AccessTab(DeviceType type) {
        super(ID);
        this.access = DeviceTabs.Control.of(type.linkSetting(LinkSetting.ACCESS.key()).orElseThrow());
    }

    /** The device's access mode: a button that steps through private, claim and public. */
    public DeviceTabs.Control access() {
        return access;
    }

    public List<DeviceTabs.Control> controls() {
        return List.of(access);
    }

    /** Whether the player the view was built for may claim the device: it has no owner and they may configure it. */
    public static boolean claimable(DeviceView view) {
        return DeviceAccess.mayClaim(view.grants(), view.ownership().owner());
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.IRON_DOOR);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.access");
    }
}

package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The Diagnostics tab of every device (M2 plan §4.2 M2-11, design §6.9): whether it runs (online, without a broker,
 * faulted with a Retry button, or not running here), its UUID, the last MQTT message it received and the last it sent,
 * with their age and size, and how many went each way. The payloads show only to a player who may configure the device;
 * everyone else sees topic, size and age ({@link DeviceView.Diagnostics}). A device that still keeps the items of its old
 * configuration slots (M2-12) has an Eject button for them. No slots.
 */
public class DiagnosticsTab extends MqttTab {

    public static final String ID = "diagnostics";
    /** The keys a refusal from this tab names: a retry or an eject. */
    public static final List<String> REFUSAL_KEYS = List.of(DeviceRuntime.RETRY_KEY, DeviceRuntime.EJECT_KEY);
    private static final long SECOND = 1000;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    public DiagnosticsTab() {
        super(ID);
    }

    /**
     * Whether the player the view was built for may retry the device: it is faulted and they may configure it. This is the
     * screen's {@code /mineqtt devices retry}, an operator's command; the server attaches a device a screen retried at
     * most once a second.
     */
    public static boolean retryable(DeviceView view) {
        return view.diagnostics().availability() == DeviceView.Availability.FAULTED && view.grants().configure();
    }

    /**
     * Whether the player the view was built for may eject the items the device's old configuration slots held (M2-12):
     * it keeps some and they may configure it, as moving those slots' items needed.
     */
    public static boolean ejectable(DeviceView view) {
        return view.legacyItems() > 0 && view.grants().configure();
    }

    /** How long ago a message went, in its largest whole unit: {@code just now}, {@code 12 s ago} up to {@code 3 d ago}. */
    public static Component age(long millis) {
        if (millis < SECOND) return Component.translatable("mineqtt.device.diagnostics.age.now");
        if (millis < MINUTE) return Component.translatable("mineqtt.device.diagnostics.age.seconds", millis / SECOND);
        if (millis < HOUR) return Component.translatable("mineqtt.device.diagnostics.age.minutes", millis / MINUTE);
        if (millis < DAY) return Component.translatable("mineqtt.device.diagnostics.age.hours", millis / HOUR);
        return Component.translatable("mineqtt.device.diagnostics.age.days", millis / DAY);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.SPYGLASS);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.diagnostics");
    }
}

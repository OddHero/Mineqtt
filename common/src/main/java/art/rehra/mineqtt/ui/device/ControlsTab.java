package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.stream.Stream;

/**
 * The Controls tab of a device (M2 plan §4.2 M2-9, design §6.9): one control per in-port of its type, each with the
 * widget of its kind. Actions and commands to writable fields come first and need USE (design §8.7: toggle, press, the
 * Controls tab); the type's settings follow and need CONFIGURE. A player shown a control they may not use sees it
 * read-only. A type without in-ports, such as the Redstone Input, has no Controls tab. No slots.
 */
public class ControlsTab extends MqttTab {

    public static final String ID = "controls";
    /** The rows between the title row and the panel's foot. */
    public static final int MAX_ROWS = 5;

    private final List<DeviceTabs.Control> controls;

    public ControlsTab(DeviceType type) {
        super(ID);
        this.controls = controls(type);
    }

    /**
     * The controls of a device of {@code type}, in the order the tab shows them: actions, commands, settings. A type the
     * tab cannot show is refused, so {@code verifyDeviceTabs} fails for it rather than its screen: more controls than its
     * {@value #MAX_ROWS} rows hold (it does not scroll yet).
     */
    public static List<DeviceTabs.Control> controls(DeviceType type) {
        List<Port> in = type.ports().stream().filter(port -> port.direction() == Port.Direction.IN).toList();
        List<DeviceTabs.Control> controls = Stream.of(Port.Origin.ACTION, Port.Origin.FIELD, Port.Origin.SETTING)
                .flatMap(origin -> in.stream().filter(port -> port.origin() == origin))
                .map(DeviceTabs.Control::of).toList();
        int rows = controls.stream().mapToInt(control -> control.widget().rows()).sum();
        if (rows > MAX_ROWS) throw new IllegalArgumentException(type.id() + ": " + rows + " rows of controls do not fit the Controls tab's " + MAX_ROWS);
        return controls;
    }

    public List<DeviceTabs.Control> controls() {
        return controls;
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.LEVER);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.controls");
    }
}

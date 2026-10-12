package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.HaEntity;
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
 * <p>
 * Two M4 rules (plan D5): a type whose Home Assistant switch reads a writable BOOL field (a timer's {@code enabled})
 * shows that field's toggle and not the {@code state} action as well, which commands the same field; and a type with a
 * status line ({@link DeviceType#status()}) draws it on the last row, which its controls then leave free.
 */
public class ControlsTab extends MqttTab {

    public static final String ID = "controls";
    /** The rows between the title row and the panel's foot. */
    public static final int MAX_ROWS = 5;

    private final List<DeviceTabs.Control> controls;
    private final boolean statusLine;

    public ControlsTab(DeviceType type) {
        super(ID);
        this.controls = controls(type);
        this.statusLine = type.status().isPresent();
    }

    /**
     * The controls of a device of {@code type}, in the order the tab shows them: actions, commands, settings, without the
     * {@code state} action when the type's switch reads a writable BOOL field ({@link #collapsesState}). A type the tab
     * cannot show is refused, so {@code verifyDeviceTabs} fails for it rather than its screen: more controls than its
     * {@value #MAX_ROWS} rows hold, or than its first {@value #MAX_ROWS} - 1 when it has a status line (it does not scroll
     * yet).
     */
    public static List<DeviceTabs.Control> controls(DeviceType type) {
        boolean collapse = collapsesState(type);
        List<Port> in = type.ports().stream().filter(port -> port.direction() == Port.Direction.IN)
                .filter(port -> !(collapse && port.origin() == Port.Origin.ACTION && port.key().equals(Action.STATE))).toList();
        List<DeviceTabs.Control> controls = Stream.of(Port.Origin.ACTION, Port.Origin.FIELD, Port.Origin.SETTING)
                .flatMap(origin -> in.stream().filter(port -> port.origin() == origin))
                .map(DeviceTabs.Control::of).toList();
        int rows = controls.stream().mapToInt(control -> control.widget().rows()).sum();
        int available = type.status().isPresent() ? MAX_ROWS - 1 : MAX_ROWS;
        if (rows > available) {
            throw new IllegalArgumentException(type.id() + ": " + rows + " rows of controls do not fit the Controls tab's " + available
                    + (type.status().isPresent() ? " beside its status line" : ""));
        }
        return controls;
    }

    /**
     * Whether the tab shows the type's {@code state} action as the toggle of the field its Home Assistant switch reads
     * (M4 plan D5): the switch reads a BOOL field the player may write, so that field's toggle already switches it. A
     * switch over another kind (the Redstone Output's and the Smart Plug's power level) keeps its {@code state} row.
     */
    public static boolean collapsesState(DeviceType type) {
        if (type.action(Action.STATE).isEmpty()) return false;
        return type.entities().stream().anyMatch(entity -> entity instanceof HaEntity.Switch<?> switchEntity
                && switchEntity.field().kind() instanceof PortType.Bool
                && type.port(Port.Direction.IN, switchEntity.field().key()).isPresent());
    }

    public List<DeviceTabs.Control> controls() {
        return controls;
    }

    /** Whether the type draws a status line on the tab's last row ({@link DeviceType#status()}). */
    public boolean statusLine() {
        return statusLine;
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

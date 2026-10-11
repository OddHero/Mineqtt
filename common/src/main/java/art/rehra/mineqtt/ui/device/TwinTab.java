package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Twin tab of a device whose type takes part in the Home Assistant twin (M3 plan D19), after Controls: its binding,
 * laid out in at most {@value #MAX_ROWS} rows at y 17, 33, 49, 65 and 81, the last ending by y 95 (the panel's foot is
 * {@link TabbedMqttMenu#GUI_HEIGHT_NO_INVENTORY}). Every editable cell is one of the type's twin {@link LinkSetting}s, so
 * the server judges what it sends exactly as {@code /mineqtt devices setting} (the command twin), and the twin gate
 * ({@code mineqtt:twin.bind}, {@code twin.allowedEntities}, {@code mineqtt:twin.control}) on top of CONFIGURE.
 * <ol>
 *     <li>the source: This block, Home Assistant or an MQTT device (M3-17); for Home Assistant the entity, which the
 *     entity picker chooses, for an MQTT device the preset it is read through, a cycle of the presets the server lists;</li>
 *     <li>the entity's name, id and area, or the MQTT device's name, a text field (the details only reach a player who
 *     may configure it, D13 #7);</li>
 *     <li>control, with invert where the type reads on/off; or the path and decimals of a type without control;</li>
 *     <li>the rest of how it reads: path, range, unavailable;</li>
 *     <li>the health of its source and control, the control blueprint's last answer, and Resume while paused.</li>
 * </ol>
 * A type with targets only (M3-14: the Redstone Input, the Wall Switch's Left and Right) has a row per target instead:
 * the entity it switches, which the picker chooses among the entities Minecraft may control, a button that removes it,
 * and below it the entity's name, id and area; the health line stays last.
 * Server-safe; {@code TwinTabView} draws it. No slots.
 */
public class TwinTab extends MqttTab {

    public static final String ID = "twin";
    /** The rows between the title row and the panel's foot. */
    public static final int MAX_ROWS = 5;
    /** The first row's top, below the title row. */
    public static final int FIRST_ROW_Y = 17;
    /** From one row's top to the next one's. */
    public static final int PITCH = 16;
    /** A row's height. */
    public static final int ROW_H = 14;
    /** The width every row shares, the panel's less its margins. */
    public static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16;
    /** Between two cells of a row. */
    public static final int GAP = 4;
    /** The lowest a row may end (P1): the panel's foot less vanilla's margin. */
    public static final int CONTENT_BOTTOM = 100;
    // The keys a row of settings starts with: what the type does with its source, before how it reads it.
    private static final List<String> LEAD_KEYS = List.of(LinkSetting.TWIN_CONTROL.key(), LinkSetting.TWIN_INVERT.key());
    private static final List<String> LEAD_KEYS_READ_ONLY = List.of(LinkSetting.TWIN_PATH.key(), LinkSetting.TWIN_DECIMALS.key());
    // The keys the source rows place, which no settings row repeats: the entity, the MQTT device and its preset (M3-17).
    private static final Set<String> SOURCE_KEYS = Set.of(LinkSetting.TWIN_ENTITY.key(), LinkSetting.TWIN_DEVICE.key(), LinkSetting.TWIN_PRESET.key());

    /**
     * What the source cycle shows (M3-17): This block (unbound), Home Assistant or an MQTT device. A bound device shows
     * its source; an unbound one shows what the player stepped the cycle to, This block until they do.
     */
    public enum SourceMode {
        THIS_BLOCK,
        HOME_ASSISTANT,
        MQTT_DEVICE;

        /** The source a view shows: its bound source, or This block while unbound. */
        public static SourceMode of(TwinView view) {
            if (view.boundToDevice()) return MQTT_DEVICE;
            return view.bound() ? HOME_ASSISTANT : THIS_BLOCK;
        }

        /** The next value of the cycle: This block, Home Assistant, MQTT device, and This block again. */
        public SourceMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** One cell of a row, with its share of the row's width. */
    public sealed interface Cell permits Source, Pick, Clear, Info, Preset, Device, BySource, Setting, Health {
        int weight();
    }

    /**
     * The source cycle: This block (unbound), Home Assistant or MQTT device (M3-17). Choosing Home Assistant opens the
     * entity picker; choosing an MQTT device shows its preset and name in the source rows; This block unbinds.
     */
    public record Source(int weight) implements Cell {
    }

    /** The preset an MQTT device source is read through ({@code twin.preset}), a cycle of the presets the server lists (M3-17). */
    public record Preset(int weight) implements Cell {
    }

    /** The name of an MQTT device source ({@code twin.device} with the preset shown), a text field Enter sends (M3-17). */
    public record Device(int weight) implements Cell {
    }

    /**
     * One place for the Home Assistant cell or the MQTT device cell, whichever the source cycle shows (M3-17): the entity's
     * pick and the preset in the first row, the entity's details and the device's name in the second. Both have the
     * place's width.
     */
    public record BySource(Cell homeAssistant, Cell mqttDevice) implements Cell {
        public BySource {
            if (homeAssistant.weight() != mqttDevice.weight()) throw new IllegalArgumentException("Both cells of a place share its width");
            if (homeAssistant instanceof BySource || mqttDevice instanceof BySource) throw new IllegalArgumentException("A place holds plain cells");
        }

        @Override
        public int weight() {
            return homeAssistant.weight();
        }
    }

    /** The entity a binding key holds, which the entity picker chooses ({@code twin.entity}, a target's key). */
    public record Pick(String key, int weight) implements Cell {
    }

    /** Removes the entity a target holds (M3-14), as {@code /mineqtt devices bind <device> target <name> none}. */
    public record Clear(String key, int weight) implements Cell {
    }

    /** The name, id and area of the entity a binding key holds, as text. */
    public record Info(String key, int weight) implements Cell {
    }

    /** One twin setting with its control: a toggle, a cycle or a text field. */
    public record Setting(DeviceTabs.Control control, int weight) implements Cell {
    }

    /** The health line; Resume at its end while the circuit breaker pauses the device. */
    public record Health(int weight) implements Cell {
    }

    /** One row of cells; an empty row leaves its place free. */
    public record Row(List<Cell> cells) {
        public Row {
            cells = List.copyOf(cells);
        }

        /** The x of each cell from the row's left and its width: the row's width shared by weight, gaps between. */
        public List<int[]> spans() {
            int total = cells.stream().mapToInt(Cell::weight).sum();
            int free = CONTENT_W - GAP * Math.max(0, cells.size() - 1);
            List<int[]> spans = new ArrayList<>();
            int x = 0;
            int used = 0;
            for (int i = 0; i < cells.size(); i++) {
                int width = i == cells.size() - 1 ? free - used : free * cells.get(i).weight() / total;
                spans.add(new int[]{x, width});
                x += width + GAP;
                used += width;
            }
            return spans;
        }
    }

    private final TwinAdapter adapter;
    private final List<Row> rows;
    private final List<DeviceTabs.Control> controls;
    private final Optional<DeviceTabs.Control> resume;

    public TwinTab(DeviceType type) {
        super(ID);
        this.adapter = type.twin().orElseThrow(() -> new IllegalArgumentException(type.id() + " takes no part in the twin"));
        this.controls = LinkSetting.twin(adapter).stream().<DeviceTabs.Control>map(DeviceTabs.Control::of).toList();
        this.resume = control(LinkSetting.TWIN_RESUME.key());
        this.rows = rows(type);
    }

    /**
     * The rows of a device of {@code type}, in the order the tab shows them; the health line is the last row. A type
     * the tab cannot show is refused, so {@code verifyDeviceTabs} fails for it rather than its screen: more rows than
     * {@value #MAX_ROWS}.
     */
    private List<Row> rows(DeviceType type) {
        List<Row> rows = new ArrayList<>();
        if (adapter.hasSource()) {
            rows.add(new Row(List.of(new Source(9), new BySource(new Pick(LinkSetting.TWIN_ENTITY.key(), 7), new Preset(7)))));
            rows.add(new Row(List.of(new BySource(new Info(LinkSetting.TWIN_ENTITY.key(), 1), new Device(1)))));
        }
        // M3-14: a row per target, the entity it switches and the button that removes it, its details below.
        for (String name : adapter.targets().keySet()) {
            String key = LinkSetting.TWIN_PREFIX + name;
            rows.add(new Row(List.of(new Pick(key, 8), new Clear(key, 1))));
            rows.add(new Row(List.of(new Info(key, 1))));
        }
        // The settings that shape the source, in the order LinkSetting.twin lists them: a lead row, then the rest.
        List<DeviceTabs.Control> settings = controls.stream().filter(control -> !SOURCE_KEYS.contains(control.key())
                && !control.key().equals(LinkSetting.TWIN_RESUME.key())
                && !adapter.targets().containsKey(control.key().substring(LinkSetting.TWIN_PREFIX.length()))).toList();
        List<String> lead = settings.stream().anyMatch(control -> LEAD_KEYS.contains(control.key())) ? LEAD_KEYS : LEAD_KEYS_READ_ONLY;
        List<Cell> first = new ArrayList<>();
        List<Cell> rest = new ArrayList<>();
        for (String key : lead) {
            settings.stream().filter(control -> control.key().equals(key)).findFirst().ifPresent(control -> first.add(new Setting(control, weight(control))));
        }
        for (DeviceTabs.Control control : settings) {
            if (!lead.contains(control.key())) rest.add(new Setting(control, weight(control)));
        }
        if (!first.isEmpty()) rows.add(new Row(first));
        if (!rest.isEmpty()) rows.add(new Row(rest));
        while (rows.size() < MAX_ROWS - 1) rows.add(new Row(List.of()));
        rows.add(new Row(List.of(new Health(1))));
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException(type.id() + ": " + rows.size() + " rows do not fit the Twin tab's " + MAX_ROWS);
        return List.copyOf(rows);
    }

    // A path is the widest text, and "Places: auto" needs as much; a toggle, a cycle and a number share the rest alike.
    private static int weight(DeviceTabs.Control control) {
        return control.key().equals(LinkSetting.TWIN_PATH.key()) || control.key().equals(LinkSetting.TWIN_DECIMALS.key()) ? 3 : 2;
    }

    /** The adapter the tab shows the binding of. */
    public TwinAdapter adapter() {
        return adapter;
    }

    /** The rows, the health line last. */
    public List<Row> rows() {
        return rows;
    }

    /** The top of row {@code index} within the panel. */
    public static int rowY(int index) {
        return FIRST_ROW_Y + index * PITCH;
    }

    /** Every control of the tab: the type's twin link settings, in the order {@link LinkSetting#twin} lists them. */
    public List<DeviceTabs.Control> controls() {
        return controls;
    }

    /** The control of a binding key, when the type has it. */
    public Optional<DeviceTabs.Control> control(String key) {
        return controls.stream().filter(control -> control.key().equals(key)).findFirst();
    }

    /** Resume, for a type the circuit breaker can pause. */
    public Optional<DeviceTabs.Control> resume() {
        return resume;
    }

    /** The binding keys the entity picker chooses for, in the tab's order. */
    public List<String> pickKeys() {
        return TwinPicker.keys(adapter);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.ENDER_EYE);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.twin");
    }
}

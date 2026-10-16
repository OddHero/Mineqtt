package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.CheckItems;
import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.ContainerObserver;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.EntityObserver;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.ui.device.LightControl.HueSaturation;
import art.rehra.mineqtt.ui.explorer.DevicesTab;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The generated device screen (M2 plan §4.2 M2-8 to M2-11), run by Gradle's verifyDeviceTabs task (bootstrapped): every
 * registered type gets its tabs and their controls from its declaration, each control's widget comes from its kind and
 * its permission and payload from what declared it, and what a control sends is exactly what the server's validator
 * and the C2S guard take from a player with that permission, its bounds included. Also the slug and exposure the
 * General tab previews, the light picker's colours, the Advanced tab's modes and retain display, the channels items
 * and renamed paper fill in, who may claim a device and retry it, and the ages the Diagnostics tab shows. No block
 * entity or screen is constructed.
 */
public final class DeviceTabsCheck {
    private static final UUID DEVICE = UUID.fromString("3f9c0000-0000-4000-8000-000000000002");
    private static final BlockPos POS = new BlockPos(3, -60, 4);
    private static final DeviceView.Grants USE_ONLY = new DeviceView.Grants(true, false, false, false);
    private static final DeviceView.Grants CONFIGURE_ONLY = new DeviceView.Grants(false, true, false, false);
    private static final DeviceView.Grants USE_AND_CONFIGURE = new DeviceView.Grants(true, true, false, false);

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DeviceTypes.registerBuiltIn();
        // The controls of each registered type as the tab shows them: key, widget, gate, payload.
        Map<DeviceType, List<String>> expected = new HashMap<>(Map.of(
                RedstoneOutput.TYPE, List.of("state TOGGLE USE ACTION", "power SLIDER USE CONFIG", "mode CYCLE CONFIGURE CONFIG",
                        "power_on_behaviour CYCLE CONFIGURE CONFIG"),
                SmartLamp.TYPE, List.of("state TOGGLE USE ACTION", "light LIGHT USE CONFIG"),
                PresenceSensor.TYPE, List.of("detect CYCLE CONFIGURE CONFIG", "range SLIDER CONFIGURE CONFIG", "clear_delay SLIDER CONFIGURE CONFIG",
                        "filter TEXT_FIELD CONFIGURE CONFIG"),
                RedstoneInput.TYPE, List.of(),
                // The Output's logic with an on/off preset (M2-18): its level slider stays, as a command that only switches.
                SmartPlug.TYPE, List.of("state TOGGLE USE ACTION", "power SLIDER USE CONFIG", "power_on_behaviour CYCLE CONFIGURE CONFIG"),
                // Its readings are out-ports only (M2-19).
                ClimateSensor.TYPE, List.of("interval SLIDER CONFIGURE CONFIG", "raw_value CYCLE CONFIGURE CONFIG"),
                // A button per press action (M2-22).
                WallSwitch.TYPE, List.of("press_left PRESS USE ACTION", "press_right PRESS USE ACTION"),
                // Its message as a command, its look as settings: 4 of the 5 rows (M3-15); its twin keys go to the Twin tab (D19).
                DisplayPanel.TYPE, List.of("message TEXT_FIELD USE CONFIG", "color CYCLE CONFIGURE CONFIG", "glow TOGGLE CONFIGURE CONFIG",
                        "size CYCLE CONFIGURE CONFIG"),
                // What counts as a change and what the Raw Link sends; its readings are out-ports only (M4-9).
                BlockObserver.TYPE, List.of("change_on CYCLE CONFIGURE CONFIG", "payload CYCLE CONFIGURE CONFIG"),
                // Place and clear by hand, the block as a setting (M4-10).
                BlockPlacer.TYPE, List.of("place PRESS USE ACTION", "clear PRESS USE ACTION", "block TEXT_FIELD CONFIGURE CONFIG")));
        // M4-2 (plan D5): Trigger now, the Enabled toggle with the state row folded into it, then the schedule; at most four
        // rows, the status line takes the fifth.
        List<String> timerRows = List.of("trigger PRESS USE ACTION", "enabled TOGGLE USE CONFIG");
        // Its readings are out-ports only, and it has no settings: no Controls tab, as the Redstone Input.
        expected.put(ContainerObserver.TYPE, List.of());
        expected.put(Timers.INTERVAL_TIMER, rows(timerRows, "interval NUMBER_FIELD CONFIGURE CONFIG", "unit CYCLE CONFIGURE CONFIG"));
        expected.put(Timers.DAYLIGHT_TIMER, rows(timerRows, "trigger_at CYCLE CONFIGURE CONFIG", "time TEXT_FIELD CONFIGURE CONFIG"));
        expected.put(Timers.ALARM_CLOCK, rows(timerRows, "time TEXT_FIELD CONFIGURE CONFIG"));
        expected.put(Timers.CRON_TIMER, rows(timerRows, "cron TEXT_FIELD CONFIGURE CONFIG"));
        // What it looks at, how far and what the Raw Link sends; its readings are out-ports only.
        expected.put(EntityObserver.TYPE, List.of("detect CYCLE CONFIGURE CONFIG", "range SLIDER CONFIGURE CONFIG", "payload CYCLE CONFIGURE CONFIG"));
        for (DeviceType timer : Timers.all()) {
            check(new ControlsTab(timer).statusLine() && ControlsTab.controls(timer).size() <= ControlsTab.MAX_ROWS - 1,
                    timer + ": at most four rows of controls beside its status line");
        }
        check(ControlsTab.controls(DisplayPanel.TYPE).stream().mapToInt(control -> control.widget().rows()).sum() == 4 && ControlsTab.MAX_ROWS == 5,
                "The panel's Controls tab takes 4 of its 5 rows");
        check(expected.keySet().containsAll(DeviceTypes.all()), "Every registered type has its expected controls listed here");
        // The Advanced tab's controls of each registered type: its Raw Link's link settings.
        List<String> valueLink = List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.retain TOGGLE CONFIGURE CONFIG", "raw.qos SLIDER CONFIGURE CONFIG");
        List<String> inputLink = List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.on TEXT_FIELD CONFIGURE CONFIG", "raw.off TEXT_FIELD CONFIGURE CONFIG");
        Map<DeviceType, List<String>> advanced = new HashMap<>(Map.of(
                RedstoneOutput.TYPE, inputLink,
                SmartLamp.TYPE, inputLink,
                SmartPlug.TYPE, inputLink,
                RedstoneInput.TYPE, List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                        "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.retain TOGGLE CONFIGURE CONFIG", "raw.qos SLIDER CONFIGURE CONFIG",
                        "raw.on TEXT_FIELD CONFIGURE CONFIG", "raw.off TEXT_FIELD CONFIGURE CONFIG"),
                PresenceSensor.TYPE, valueLink,
                ClimateSensor.TYPE, valueLink,
                BlockObserver.TYPE, valueLink,
                // A block in: no retain, QoS or custom payloads (M4-10).
                BlockPlacer.TYPE, List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                        "raw.topic TEXT_FIELD CONFIGURE CONFIG"),
                // Events are never retained: QoS only (M2-22).
                WallSwitch.TYPE, List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                        "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.qos SLIDER CONFIGURE CONFIG"),
                // Text in: no retain, QoS or custom payloads (M3-15).
                DisplayPanel.TYPE, List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                        "raw.topic TEXT_FIELD CONFIGURE CONFIG")));
        // The timers' links carry their fires: events, never retained, QoS only (M4-4).
        // No Raw Link: the tab says so and has no controls.
        advanced.put(ContainerObserver.TYPE, List.of());
        Timers.all().forEach(timer -> advanced.put(timer, List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.qos SLIDER CONFIGURE CONFIG")));
        advanced.put(EntityObserver.TYPE, valueLink);
        check(advanced.keySet().containsAll(DeviceTypes.all()), "Every registered type has its expected Advanced controls listed here");
        // The Twin tab of each type with a twin adapter (M3 plan D19): its rows top to bottom, each cell as kind:key; the
        // health line is always the fifth row.
        Map<DeviceType, List<String>> twin = Map.of(
                // M3-17: the source cycle's Home Assistant cells or MQTT device cells share the first two rows' places.
                SmartLamp.TYPE, List.of(SOURCE_ROW, DETAILS_ROW, "twin.control", "twin.unavailable", "health"),
                SmartPlug.TYPE, List.of(SOURCE_ROW, DETAILS_ROW, "twin.control", "twin.unavailable", "health"),
                // Control and invert lead, then how it reads: path, range, unavailable.
                RedstoneOutput.TYPE, List.of(SOURCE_ROW, DETAILS_ROW, "twin.control twin.invert", "twin.path twin.min twin.max twin.unavailable", "health"),
                // Read-only: path and decimals lead.
                DisplayPanel.TYPE, List.of(SOURCE_ROW, DETAILS_ROW, "twin.path twin.decimals", "twin.min twin.max twin.unavailable", "health"),
                // Targets only (M3-14): a row per target, the entity it switches and its remover, its details below.
                RedstoneInput.TYPE, List.of("pick:twin.target clear:twin.target", "info:twin.target", "", "", "health"),
                WallSwitch.TYPE, List.of("pick:twin.left clear:twin.left", "info:twin.left", "pick:twin.right clear:twin.right", "info:twin.right", "health"));
        check(twin.keySet().equals(DeviceTypes.all().stream().filter(type -> type.twin().isPresent()).collect(Collectors.toSet())),
                "Every registered type with a twin adapter has its expected Twin tab listed here, and no other: " + twin.keySet());
        int controls = 0;
        for (DeviceType type : DeviceTypes.all()) {
            controls += tabs(type, expected.get(type));
            controls += advancedTab(type, advanced.get(type));
            controls += accessTab(type);
            if (type.twin().isPresent()) controls += twinTab(type, twin.get(type));
        }
        twinTabLimits();
        check(DeviceTypes.all().stream().filter(type -> new GeneralTab(type).grouping()).map(DeviceType::id).collect(Collectors.toSet())
                .equals(Set.of(RedstoneOutput.TYPE.id(), SmartLamp.TYPE.id(), SmartPlug.TYPE.id())),
                "Of the registered types only the output, the lamp and the plug have group pages");
        groupPages();
        claimable();
        diagnosticsTab();
        advancedModes();
        ejectButton();
        retainShown();
        paperChannels();
        widgets();
        controlsTabLimits();
        m4Controls();
        cronSchedule();
        explorerLayout();
        lightPicker();
        slugPreview();
        exposeValue();
        roomLine();
        System.out.println("Device tab checks passed: " + DeviceTypes.all().size() + " types, " + controls + " controls.");
    }

    // The tabs of a type and the controls of each, derived from its declaration; returns how many controls it has.
    private static int tabs(DeviceType type, List<String> expected) {
        List<MqttTab> tabs = DeviceTabs.forType(type);
        boolean inPorts = type.ports().stream().anyMatch(port -> port.direction() == Port.Direction.IN);
        List<String> ids = new ArrayList<>(List.of(GeneralTab.ID));
        if (inPorts) ids.add(ControlsTab.ID);
        // M4-5 (D5): the Cron Timer's Schedule tab after Controls, the one custom tab.
        if (CronScheduleTab.appliesTo(type)) ids.add(CronScheduleTab.ID);
        // M3-13 (D19): the Twin tab after Controls, for a type that takes part in the twin.
        if (type.twin().isPresent()) ids.add(TwinTab.ID);
        ids.addAll(List.of(AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID));
        check(tabs.stream().map(MqttTab::id).toList().equals(ids), type.id() + ": the generated tabs are " + ids
                + ", Controls exactly when it has in-ports, Schedule for the Cron Timer, Twin exactly when it has a twin adapter, Advanced, Access and Diagnostics always: " + tabs);
        check(TabbedMqttScreen.tabWidth(tabs.size()) * tabs.size() <= TabbedMqttMenu.GUI_WIDTH && TabbedMqttScreen.tabWidth(tabs.size()) >= 18,
                type.id() + ": its " + tabs.size() + " tab buttons fit the panel's width");
        check(DeviceTabs.forViewer(type, false).stream().map(MqttTab::id).toList().equals(ids),type.id() + ": a player who may build gets every generated tab");
        List<String> controlsOnly = DeviceTabs.forViewer(type, true).stream().map(MqttTab::id).toList();
        check(controlsOnly.equals(inPorts ? List.of(ControlsTab.ID) : List.of()),
                type.id() + ": a player who may not build gets the Controls tab alone, or no screen without one (P2): " + controlsOnly);
        GeneralTab general = (GeneralTab) tabs.getFirst();
        check(general.controls().stream().map(DeviceTabs.Control::key).toList().equals(List.of("name", "expose")), type.id() + ": General has name and expose");
        for (DeviceTabs.Control control : general.controls()) {
            LinkSetting<?> setting = type.linkSetting(control.key()).orElseThrow(() -> new AssertionError(type.id() + " declares no " + control.key()));
            check(control.kind().equals(setting.kind()) && control.gate() == setting.gate() && control.payload() == DeviceTabs.Control.Payload.CONFIG,
                    type.id() + ": " + control.key() + " takes kind and gate from its declaration and is a setting");
            permissions(type, control);
        }
        DeviceTabs.Control name = general.name();
        check(name.editable(new DeviceView.Grants(true, true, false, false)) && !name.editable(new DeviceView.Grants(true, false, true, true)),
                type.id() + ": the name needs CONFIGURE");
        DeviceTabs.Control expose = general.expose();
        check(!expose.editable(new DeviceView.Grants(true, true, false, false)) && !expose.editable(new DeviceView.Grants(true, false, true, true))
                && expose.editable(DeviceView.Grants.ALL), type.id() + ": the exposure needs CONFIGURE and mayExpose, as /mineqtt devices expose does");
        check(name.widget() == DeviceTabs.Widget.TEXT_FIELD && name.maxLength() == LinkSetting.MAX_NAME_LENGTH,
                type.id() + ": the name is a text field of " + LinkSetting.MAX_NAME_LENGTH + " characters");
        check(expose.widget() == DeviceTabs.Widget.CYCLE && expose.values().equals(LinkSetting.EXPOSE_VALUES),
                type.id() + ": the exposure steps through " + LinkSetting.EXPOSE_VALUES);
        generalBounds(type, general);
        // M2-17: the group pages only for a type that can be grouped (design D1: actuators), never for a sensor or an input.
        check(general.grouping() == type.groupable(), type.id() + ": General has the group pages exactly when the type can be grouped");

        List<DeviceTabs.Control> controls = inPorts ? ((ControlsTab) tabs.get(1)).controls() : ControlsTab.controls(type);
        List<String> shown = controls.stream().map(control -> control.key() + " " + control.widget() + " " + control.gate() + " " + control.payload()).toList();
        check(shown.equals(expected), type.id() + ": the Controls tab shows " + expected + ", not " + shown);
        for (DeviceTabs.Control control : controls) {
            Port port = type.port(Port.Direction.IN, control.key()).orElseThrow(() -> new AssertionError(type.id() + ": no in-port " + control.key()));
            check(control.kind().equals(port.kind()), type.id() + ": " + control.key() + " has its port's kind");
            LinkSetting.Gate gate = port.origin() == Port.Origin.SETTING ? LinkSetting.Gate.CONFIGURE : LinkSetting.Gate.USE;
            DeviceTabs.Control.Payload payload = port.origin() == Port.Origin.ACTION ? DeviceTabs.Control.Payload.ACTION : DeviceTabs.Control.Payload.CONFIG;
            check(control.gate() == gate && control.payload() == payload, type.id() + ": " + control.key() + " is a " + port.origin() + ", so " + gate + " by " + payload);
            permissions(type, control);
            controlBounds(type, control);
        }
        return general.controls().size() + controls.size();
    }

    /**
     * For every combination of grants, a control is editable exactly when the server takes what it sends from that
     * player, and the C2S guard lets the payload through for the control's gate: USE for a command or an action,
     * CONFIGURE for a setting, so a player who may only use a device never reaches the setting validator.
     */
    private static void permissions(DeviceType type, DeviceTabs.Control control) {
        CustomPacketPayload sent = control.send(POS, sample(control));
        for (DeviceView.Grants grants : allGrants()) {
            check(control.editable(grants) == taken(type, sent, grants), type.id() + ": " + control.key() + " is editable with " + grants + " exactly when the server takes it");
        }
        MineqttPermission guard = switch (sent) {
            case DeviceConfigPayload config -> DeviceConfig.permission(type, config.key());
            // The action payload's guard always asks for USE.
            default -> MineqttPermission.USE;
        };
        check(guard == (control.gate() == LinkSetting.Gate.USE ? MineqttPermission.USE : MineqttPermission.CONFIGURE),
                type.id() + ": the guard asks " + guard + " for " + control.key() + ", gated " + control.gate());
    }

    /**
     * The Advanced tab of a type: one control per link setting of its Raw Link, with the kind and gate of its
     * declaration, editable exactly when the server takes what it sends, and within the bounds the server checks.
     * Returns how many controls it has.
     */
    private static int advancedTab(DeviceType type, List<String> expected) {
        AdvancedTab tab = DeviceTabs.forType(type).stream().filter(AdvancedTab.class::isInstance).map(AdvancedTab.class::cast).findFirst().orElseThrow();
        check(tab.spec().equals(type.rawLink()), type.id() + ": the Advanced tab edits the type's Raw Link");
        List<String> shown = tab.controls().stream().map(control -> control.key() + " " + control.widget() + " " + control.gate() + " " + control.payload()).toList();
        check(shown.equals(expected), type.id() + ": the Advanced tab shows " + expected + ", not " + shown);
        for (DeviceTabs.Control control : tab.controls()) {
            LinkSetting<?> setting = type.linkSetting(control.key()).orElseThrow(() -> new AssertionError(type.id() + " declares no " + control.key()));
            check(control.kind().equals(setting.kind()) && control.gate() == setting.gate() && control.payload() == DeviceTabs.Control.Payload.CONFIG,
                    type.id() + ": " + control.key() + " takes kind and gate from its declaration and is a link setting");
            check(tab.control(setting).equals(Optional.of(control)), type.id() + ": the tab finds its control of " + control.key());
            permissions(type, control);
            advancedBounds(type, control);
        }
        return tab.controls().size();
    }

    /**
     * What each Advanced control sends is taken whole: a topic or channel of as many bytes as the field holds, a payload
     * of 1 KiB, empty text (no link, or the type's own payload), retain on and off, both QoS levels; and nothing beyond.
     */
    private static void advancedBounds(DeviceType type, DeviceTabs.Control control) {
        String what = type.id() + ": " + control.key();
        switch (control.widget()) {
            case TEXT_FIELD -> {
                boolean payload = control.key().equals(LinkSetting.RAW_ON.key()) || control.key().equals(LinkSetting.RAW_OFF.key());
                int max = payload ? LinkSetting.MAX_PAYLOAD_BYTES : LinkSetting.MAX_TOPIC_BYTES;
                check(control.maxLength() == max, what + ": the field holds " + max + " characters, the server's bytes: " + control.maxLength());
                String longest = "a".repeat(max);
                check(DeviceConfig.parse(type, control.key(), ((DeviceConfigPayload) control.send(POS, new JsonPrimitive(longest))).value(), CONFIGURE_ONLY)
                        .result().equals(Optional.of(textChange(control.key(), longest))), what + " takes " + max + " bytes as typed");
                check(!taken(type, control.send(POS, new JsonPrimitive(longest + "a")), CONFIGURE_ONLY), what + " refuses one byte more");
                check(!taken(type, control.send(POS, new JsonPrimitive("ä".repeat(max / 2 + 1))), CONFIGURE_ONLY),
                        what + " refuses more bytes than the server's cap, though in fewer characters than the field holds");
                check(DeviceConfig.parse(type, control.key(), ((DeviceConfigPayload) control.send(POS, new JsonPrimitive(""))).value(), CONFIGURE_ONLY)
                        .result().equals(Optional.of(textChange(control.key(), ""))), what + ": an empty field removes the link or the payload");
                if (!payload) check(!taken(type, control.send(POS, new JsonPrimitive("home/+/door")), CONFIGURE_ONLY), what + " refuses a wildcard");
            }
            case TOGGLE -> {
                check(control.key().equals(LinkSetting.RAW_RETAIN.key()), what + " is the only switch of the Advanced tab");
                for (boolean on : new boolean[]{true, false}) {
                    DataResult<DeviceConfig.Change> change = DeviceConfig.parse(type, control.key(), ((DeviceConfigPayload) control.send(POS, control.toggled(on))).value(), CONFIGURE_ONLY);
                    check(change.result().equals(Optional.of(new DeviceConfig.RawRetain(on))), what + " switches retain " + (on ? "on" : "off") + ": " + change);
                }
            }
            case SLIDER -> {
                check(control.key().equals(LinkSetting.RAW_QOS.key()) && control.min() == 0 && control.max() == 1, what + " slides over QoS 0 and 1");
                for (int qos = 0; qos <= 1; qos++) {
                    DataResult<DeviceConfig.Change> change = DeviceConfig.parse(type, control.key(), ((DeviceConfigPayload) control.send(POS, control.leveled(qos))).value(), CONFIGURE_ONLY);
                    check(change.result().equals(Optional.of(new DeviceConfig.RawQos(qos))), what + " takes QoS " + qos + ": " + change);
                }
                check(!taken(type, control.send(POS, new JsonPrimitive(2)), CONFIGURE_ONLY), what + " refuses QoS 2");
            }
            default -> throw new AssertionError(what + ": the Advanced tab has no " + control.widget());
        }
    }

    /**
     * The Access tab of a type (M2-11): its one control is the link's access mode, a cycle over private, claim and public
     * that only the owner or an operator may work (the {@code access} grant), whatever else the player may do, and every
     * mode it steps to is taken as that mode. Returns how many controls it has.
     */
    private static int accessTab(DeviceType type) {
        AccessTab tab = DeviceTabs.forType(type).stream().filter(AccessTab.class::isInstance).map(AccessTab.class::cast).findFirst().orElseThrow();
        DeviceTabs.Control access = tab.access();
        LinkSetting<?> setting = type.linkSetting(LinkSetting.ACCESS.key()).orElseThrow(() -> new AssertionError(type.id() + " declares no access"));
        check(tab.controls().equals(List.of(access)) && access.kind().equals(setting.kind()) && access.gate() == LinkSetting.Gate.ACCESS
                        && access.payload() == DeviceTabs.Control.Payload.CONFIG && access.widget() == DeviceTabs.Widget.CYCLE,
                type.id() + ": the Access tab's one control is the access mode, a cycle gated ACCESS from its declaration");
        List<String> modes = Arrays.stream(AccessMode.values()).map(AccessMode::getSerializedName).toList();
        check(access.values().equals(modes), type.id() + ": the access mode steps through " + modes);
        for (AccessMode mode : AccessMode.values()) {
            DataResult<DeviceConfig.Change> change = DeviceConfig.parse(type, access.key(),
                    ((DeviceConfigPayload) access.send(POS, new JsonPrimitive(mode.getSerializedName()))).value(), DeviceView.Grants.ALL);
            check(change.result().equals(Optional.of(new DeviceConfig.Access(mode))), type.id() + ": the button's " + mode + " is taken as " + mode + ": " + change);
        }
        check(access.next("private").equals("claim") && access.next("claim").equals("public") && access.next("public").equals("private"),
                type.id() + ": the button steps private, claim, public and round");
        check(!access.editable(new DeviceView.Grants(true, true, true, false)) && access.editable(new DeviceView.Grants(false, false, false, true)),
                type.id() + ": only the access grant (owner or operator) changes the mode, not configuring the device");
        permissions(type, access);
        return tab.controls().size();
    }

    /**
     * What the group pages offer a player is what the server takes from them, as far as the device goes: every change
     * needs CONFIGURE on it, showing a group's members also the right to expose devices, as the device's own exposure does.
     */
    private static void groupPages() {
        List<GroupRules.Change> changes = List.of(new GroupRules.Join(DEVICE), new GroupRules.Leave(), new GroupRules.Create("Lamps"),
                new GroupRules.Rename("Lamps"), new GroupRules.ExposeMembers(true));
        for (GroupRules.Change change : changes) {
            LinkSetting.Gate gate = change instanceof GroupRules.ExposeMembers ? LinkSetting.Gate.EXPOSE : LinkSetting.Gate.CONFIGURE;
            check(change.gate() == gate, change + " is gated " + gate);
            for (DeviceView.Grants grants : allGrants()) {
                boolean expected = grants.configure() && (gate != LinkSetting.Gate.EXPOSE || grants.expose());
                check(GroupRules.permits(change, grants) == expected, change + " is offered with " + grants + " exactly when " + gate + " allows it");
            }
        }
    }

    /**
     * Who may claim a device (M2-11): only one without an owner, not even a v1 owner name still to resolve, and only a
     * player who may configure it.
     */
    private static void claimable() {
        for (DeviceView.Grants grants : allGrants()) {
            for (DeviceView.Owner owner : DeviceView.Owner.values()) {
                DeviceView view = withOwnership(viewOf(RedstoneOutput.TYPE, Optional.empty()), grants, owner);
                check(AccessTab.claimable(view) == (owner == DeviceView.Owner.NOBODY && grants.configure()),
                        "A device of " + owner + " is claimable with " + grants + " exactly when it has no owner and the grants configure it");
            }
        }
        check(AccessTab.REFUSAL_KEYS.equals(List.of(LinkSetting.ACCESS.key(), "claim")), "The Access tab shows refusals of the mode and of a claim");
    }

    /** The Diagnostics tab (M2-11): Retry only for a faulted device and a player who may configure it, and its ages. */
    private static void diagnosticsTab() {
        for (DeviceView.Availability availability : DeviceView.Availability.values()) {
            for (boolean configure : new boolean[]{true, false}) {
                DeviceView view = withDiagnostics(viewOf(RedstoneOutput.TYPE, Optional.empty()), new DeviceView.Grants(true, configure, false, false),
                        new DeviceView.Diagnostics(Optional.of(DEVICE), availability, "", Optional.empty(), Optional.empty(), 0, 0, 0, 0, 0));
                check(DiagnosticsTab.retryable(view) == (availability == DeviceView.Availability.FAULTED && configure),
                        "Retry with " + availability + (configure ? " for a configurer" : " for a user") + " only when faulted and configurable");
            }
        }
        check(DiagnosticsTab.REFUSAL_KEYS.equals(List.of("retry", "eject")), "The Diagnostics tab shows refusals of a retry and an eject");
        check(age(999).equals("mineqtt.device.diagnostics.age.now []"), "Under a second is just now: " + age(999));
        check(age(1_000).equals("mineqtt.device.diagnostics.age.seconds [1]") && age(59_999).equals("mineqtt.device.diagnostics.age.seconds [59]"),
                "Seconds up to a minute: " + age(59_999));
        check(age(60_000).equals("mineqtt.device.diagnostics.age.minutes [1]") && age(3_599_999).equals("mineqtt.device.diagnostics.age.minutes [59]"),
                "Minutes up to an hour: " + age(3_599_999));
        check(age(3_600_000).equals("mineqtt.device.diagnostics.age.hours [1]") && age(86_399_999).equals("mineqtt.device.diagnostics.age.hours [23]"),
                "Hours up to a day: " + age(86_399_999));
        check(age(3 * 86_400_000L).equals("mineqtt.device.diagnostics.age.days [3]"), "Then days: " + age(3 * 86_400_000L));
    }

    // An age as its lang key and arguments.
    private static String age(long millis) {
        TranslatableContents contents = (TranslatableContents) DiagnosticsTab.age(millis).getContents();
        return contents.getKey() + " " + Arrays.toString(contents.getArgs());
    }

    // The change a text control of the Advanced tab makes with this text: empty removes the link or the custom payload.
    private static DeviceConfig.Change textChange(String key, String text) {
        return switch (key) {
            case "raw.channel" -> new DeviceConfig.RawChannel(text, false);
            case "raw.private" -> new DeviceConfig.RawChannel(text, true);
            case "raw.topic" -> new DeviceConfig.RawTopic(text);
            case "raw.on" -> new DeviceConfig.RawPayload(true, text.isEmpty() ? null : text);
            case "raw.off" -> new DeviceConfig.RawPayload(false, text.isEmpty() ? null : text);
            default -> throw new AssertionError(key + " is no text link setting");
        };
    }

    /**
     * The mode a Raw Link opens the editor in, what its field shows in each mode, and which texts change the link: the
     * same channel in the other of Channel and Private mode does (M2-12), the topic a channel resolved to does not.
     */
    private static void advancedModes() {
        DeviceView.Raw channel = raw("mineqtt/shared/garage", Optional.of("garage"), Optional.empty());
        DeviceView.Raw privateChannel = raw("mineqtt/world_7f3a/raw/players/" + DEVICE + "/garage", Optional.of("garage"), Optional.empty());
        DeviceView.Raw typed = raw("zigbee2mqtt/door/set", Optional.empty(), Optional.empty());
        check(AdvancedTab.Mode.of(Optional.empty(), false) == AdvancedTab.Mode.CHANNEL && AdvancedTab.Mode.CHANNEL.text(Optional.empty()).isEmpty()
                && AdvancedTab.Mode.TOPIC.text(Optional.empty()).isEmpty(), "Without a link the editor opens on an empty channel");
        check(AdvancedTab.Mode.of(Optional.of(channel), false) == AdvancedTab.Mode.CHANNEL && AdvancedTab.Mode.CHANNEL.text(Optional.of(channel)).equals("garage")
                && AdvancedTab.Mode.TOPIC.text(Optional.of(channel)).equals("mineqtt/shared/garage"),
                "A channel link opens on its channel, and shows the topic it resolved to in Topic mode");
        check(AdvancedTab.Mode.of(Optional.of(privateChannel), true) == AdvancedTab.Mode.PRIVATE && AdvancedTab.Mode.PRIVATE.text(Optional.of(privateChannel)).equals("garage"),
                "A private channel opens in Private mode on its channel");
        check(AdvancedTab.Mode.of(Optional.of(typed), false) == AdvancedTab.Mode.TOPIC && AdvancedTab.Mode.of(Optional.of(typed), true) == AdvancedTab.Mode.TOPIC
                && AdvancedTab.Mode.TOPIC.text(Optional.of(typed)).equals("zigbee2mqtt/door/set") && AdvancedTab.Mode.CHANNEL.text(Optional.of(typed)).isEmpty(),
                "A typed topic (or a v1 topic) opens on the topic, and has no channel");
        check(AdvancedTab.Mode.CHANNEL.key().equals(LinkSetting.RAW_CHANNEL.key()) && AdvancedTab.Mode.PRIVATE.key().equals(LinkSetting.RAW_PRIVATE.key())
                && AdvancedTab.Mode.TOPIC.key().equals(LinkSetting.RAW_TOPIC.key()), "Channel mode sends raw.channel, Private mode raw.private, Topic mode raw.topic");
        check(AdvancedTab.Mode.CHANNEL.next() == AdvancedTab.Mode.PRIVATE && AdvancedTab.Mode.PRIVATE.next() == AdvancedTab.Mode.TOPIC
                && AdvancedTab.Mode.TOPIC.next() == AdvancedTab.Mode.CHANNEL, "The mode button steps through all three");

        DeviceView base = viewOf(RedstoneOutput.TYPE, Optional.empty());
        DeviceView shared = withLink(base, Optional.of(channel), false, 0);
        DeviceView ownChannel = withLink(base, Optional.of(privateChannel), true, 0);
        check(!AdvancedTab.Mode.CHANNEL.changes("garage", shared) && AdvancedTab.Mode.CHANNEL.changes("hall", shared)
                && AdvancedTab.Mode.PRIVATE.changes("garage", shared) && AdvancedTab.Mode.CHANNEL.changes("garage", ownChannel)
                && !AdvancedTab.Mode.PRIVATE.changes("garage", ownChannel), "The same channel made private, or no longer private, is a change");
        check(!AdvancedTab.Mode.TOPIC.changes("mineqtt/shared/garage", shared) && AdvancedTab.Mode.TOPIC.changes("home/garage", shared)
                && AdvancedTab.Mode.CHANNEL.changes("", shared) && !AdvancedTab.Mode.PRIVATE.changes("", base),
                "The topic a channel resolved to changes nothing; an empty text removes a link that is there");
    }

    /** The Eject button of the Diagnostics tab: for a player who may configure a device that keeps legacy items. */
    private static void ejectButton() {
        DeviceView base = viewOf(PresenceSensor.TYPE, Optional.empty());
        DeviceView keeps = withLink(base, Optional.empty(), false, 16);
        check(DiagnosticsTab.ejectable(keeps) && !DiagnosticsTab.ejectable(base), "A device that keeps legacy items can be ejected, one without cannot");
        for (DeviceView.Grants grants : allGrants()) {
            check(DiagnosticsTab.ejectable(withDiagnostics(keeps, grants, keeps.diagnostics())) == grants.configure(),
                    grants + ": ejecting needs CONFIGURE, as moving the old slots' items did");
        }
        check(DiagnosticsTab.REFUSAL_KEYS.contains(DeviceRuntime.EJECT_KEY), "A refused eject shows on the Diagnostics tab");
    }

    /** The retain switch shows the link's own override, else the type's default. */
    private static void retainShown() {
        RawLinkSpec input = RedstoneInput.TYPE.rawLink().orElseThrow();
        RawLinkSpec presence = PresenceSensor.TYPE.rawLink().orElseThrow();
        DeviceView.Raw followsType = raw("t", Optional.empty(), Optional.empty());
        check(AdvancedTab.retain(followsType, input) && !AdvancedTab.retain(followsType, presence),
                "Without an override a Redstone Input shows retain on (wireless redstone, M2 plan D2) and a Presence Sensor off");
        check(!AdvancedTab.retain(raw("t", Optional.empty(), Optional.of(false)), input) && AdvancedTab.retain(raw("t", Optional.empty(), Optional.of(true)), presence),
                "An override wins over the type's default");
    }

    /**
     * The channel a renamed paper fills in when used on a device: the server takes it as a channel from a player who may
     * configure the device, and only from one.
     */
    private static void paperChannels() {
        // An item stack needs its item's default components, which a server binds when it loads its data packs.
        CheckItems.bindCommonComponents(Items.PAPER, Items.STONE);
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.set(DataComponents.CUSTOM_NAME, Component.literal("garage/door"));
        ItemStack namedStone = new ItemStack(Items.STONE);
        namedStone.set(DataComponents.CUSTOM_NAME, Component.literal("garage"));
        check(AdvancedTab.paperChannel(paper).equals(Optional.of("garage/door")) && AdvancedTab.paperChannel(new ItemStack(Items.PAPER)).isEmpty()
                && AdvancedTab.paperChannel(namedStone).isEmpty() && AdvancedTab.paperChannel(ItemStack.EMPTY).isEmpty(),
                "Only a renamed paper fills in a channel when used on a device");
        ItemStack blank = new ItemStack(Items.PAPER);
        blank.set(DataComponents.CUSTOM_NAME, Component.literal("  "));
        ItemStack spaced = new ItemStack(Items.PAPER);
        spaced.set(DataComponents.CUSTOM_NAME, Component.literal(" garage "));
        check(AdvancedTab.paperChannel(blank).isEmpty()
                && AdvancedTab.paperChannel(spaced).equals(Optional.of("garage")), "A blank paper name fills in no channel (an empty one removes the link), and the spaces around a name go");
        StringTag channel = StringTag.valueOf(AdvancedTab.paperChannel(paper).orElseThrow());
        for (DeviceType type : DeviceTypes.all()) {
            if (type.rawLink().isEmpty()) {
                check(DeviceConfig.parse(type, LinkSetting.RAW_CHANNEL.key(), channel, CONFIGURE_ONLY).isError(), type.id() + ": a type without a Raw Link takes no channel");
                continue;
            }
            check(DeviceConfig.parse(type, LinkSetting.RAW_CHANNEL.key(), channel, CONFIGURE_ONLY).result().equals(Optional.of(new DeviceConfig.RawChannel("garage/door", false)))
                            && DeviceConfig.parse(type, LinkSetting.RAW_PRIVATE.key(), channel, CONFIGURE_ONLY).result()
                            .equals(Optional.of(new DeviceConfig.RawChannel("garage/door", true)))
                            && DeviceConfig.parse(type, LinkSetting.RAW_CHANNEL.key(), channel, USE_ONLY).isError(),
                    type.id() + ": the paper's channel is taken from a player who may configure the device, and only from one");
        }
    }

    private static DeviceView.Raw raw(String topic, Optional<String> channel, Optional<Boolean> retain) {
        return new DeviceView.Raw(topic, channel, RawLinkSpec.Direction.OUT, 0, retain, retain.orElse(false), Optional.empty(), Optional.empty(), Optional.empty(), RawLink.Edges.BOTH);
    }

    // What the widgets of General send stays within the bounds the server checks: names up to the field's length, every value of the cycle.
    private static void generalBounds(DeviceType type, GeneralTab general) {
        DeviceTabs.Control name = general.name();
        String longest = "ä".repeat(name.maxLength());
        DataResult<DeviceConfig.Change> taken = DeviceConfig.parse(type, name.key(), StringTag.valueOf(longest), DeviceView.Grants.ALL);
        check(taken.result().filter(change -> change.equals(new DeviceConfig.Rename(longest))).isPresent(), type.id() + ": a name as long as the field is taken");
        check(DeviceConfig.parse(type, name.key(), StringTag.valueOf(longest + "x"), DeviceView.Grants.ALL).isError(),
                type.id() + ": a name longer than the field is refused, so the field's length is the server's");
        check(DeviceConfig.parse(type, name.key(), StringTag.valueOf("   "), DeviceView.Grants.ALL).isError(), type.id() + ": a blank name is refused");

        DeviceTabs.Control expose = general.expose();
        List<Optional<Boolean>> sent = new ArrayList<>();
        String value = DeviceTabs.exposeValue(viewOf(type, Optional.empty()));
        for (int i = 0; i < expose.values().size(); i++) {
            value = expose.next(value);
            DataResult<DeviceConfig.Change> change = DeviceConfig.parse(type, expose.key(), StringTag.valueOf(value), DeviceView.Grants.ALL);
            check(change.isSuccess(), type.id() + ": the exposure value " + value + " is taken");
            Optional<Boolean> parsed = Optional.ofNullable(((DeviceConfig.Expose) change.getOrThrow()).expose());
            check(DeviceTabs.exposeValue(viewOf(type, parsed)).equals(value), type.id() + ": " + value + " shows as itself once applied");
            sent.add(parsed);
        }
        check(sent.equals(List.of(Optional.of(true), Optional.of(false), Optional.empty())), type.id() + ": from default the button steps on, off, default: " + sent);
    }

    /**
     * What a control of the Controls tab sends is taken whole, never clamped by the server: a slider's ends (and nothing
     * beyond them), every value of a cycle, on and off; and it reads its value back from the device's state.
     */
    private static void controlBounds(DeviceType type, DeviceTabs.Control control) {
        String what = type.id() + ": " + control.key();
        switch (control.widget()) {
            case SLIDER -> {
                check(taken(type, control.send(POS, control.leveled(control.min())), USE_AND_CONFIGURE)
                        && taken(type, control.send(POS, control.leveled(control.max())), USE_AND_CONFIGURE), what + " takes both ends of its slider");
                check(!taken(type, control.send(POS, new JsonPrimitive(control.min() - 1)), USE_AND_CONFIGURE)
                        && !taken(type, control.send(POS, new JsonPrimitive(control.max() + 1)), USE_AND_CONFIGURE), what + " refuses what is beyond them");
                check(control.leveled(control.max() + 5).equals(new JsonPrimitive(control.max())), what + ": the slider keeps what it sends within its ends");
                check(control.level(state(control.key(), new JsonPrimitive(control.max()))) == control.max()
                        && control.level(new JsonObject()) == control.min(), what + " stands where the state has it, else at its lowest");
                check(control.send(POS, control.leveled(control.max())) instanceof DeviceConfigPayload config && config.value() instanceof IntTag,
                        what + " sends a whole number as an NBT int");
            }
            case NUMBER_FIELD -> {
                // M4 plan D5: a typed whole number within the bounds, taken whole; the field sends nothing else.
                for (int number : new int[]{control.min(), control.max()}) {
                    JsonElement typed = control.typedNumber(String.valueOf(number)).orElseThrow(() -> new AssertionError(what + " takes " + number));
                    check(taken(type, control.send(POS, typed), USE_AND_CONFIGURE), what + " takes " + number);
                }
                check(control.typedNumber(String.valueOf((long) control.min() - 1)).isEmpty() && control.typedNumber(String.valueOf((long) control.max() + 1)).isEmpty()
                        && control.typedNumber("").isEmpty() && control.typedNumber("12a").isEmpty() && control.typedNumber("1.5").isEmpty()
                        && control.typedNumber("99999999999").isEmpty(), what + " sends nothing beyond its bounds and nothing that is no whole number");
                check(!taken(type, control.send(POS, new JsonPrimitive((long) control.max() + 1)), USE_AND_CONFIGURE), what + ": the server refuses what is beyond them");
                check(control.level(state(control.key(), new JsonPrimitive(control.max()))) == control.max(), what + " shows the state's number");
                check(control.send(POS, control.typedNumber(String.valueOf(control.max())).orElseThrow()) instanceof DeviceConfigPayload config
                        && config.value() instanceof IntTag, what + " sends a whole number as an NBT int");
            }
            case CYCLE -> {
                for (String value : control.values()) {
                    check(taken(type, control.send(POS, new JsonPrimitive(value)), USE_AND_CONFIGURE), what + " takes its value " + value);
                    check(control.selected(state(control.key(), new JsonPrimitive(value.toUpperCase()))).equals(value), what + " shows " + value + " in any case");
                }
                check(control.selected(new JsonObject()).equals(control.values().getFirst()), what + " shows its first value for a state without one");
            }
            case TOGGLE -> {
                check(taken(type, control.send(POS, control.toggled(true)), USE_AND_CONFIGURE)
                        && taken(type, control.send(POS, control.toggled(false)), USE_AND_CONFIGURE), what + " switches on and off");
                check(control.on(state(control.key(), new JsonPrimitive("ON"))) && !control.on(state(control.key(), new JsonPrimitive("OFF")))
                        && !control.on(new JsonObject()), what + " shows ON, OFF and off for a state without it");
            }
            case LIGHT -> {
                for (JsonObject command : List.of(LightControl.brightness(LightControl.MIN_BRIGHTNESS), LightControl.brightness(LightControl.MAX_BRIGHTNESS),
                        LightControl.color(new HueSaturation(200, 40)), LightControl.kelvin(ValueCodecs.MIN_KELVIN), LightControl.kelvin(ValueCodecs.MAX_KELVIN))) {
                    DataResult<DeviceConfig.Change> change = DeviceConfig.parse(type, control.key(), ((DeviceConfigPayload) control.send(POS, command)).value(), USE_ONLY);
                    check(change.result().filter(sent -> sent.equals(new DeviceConfig.Command(command))).isPresent(),
                            what + " sends " + command + " through NBT and the light gets it as sent, nothing filled in: " + change);
                }
            }
            case TEXT_FIELD -> {
                if (control.key().equals("message")) {
                    // The Display Panel's message (M3-15): any text up to its length, the empty text clears it.
                    check(taken(type, control.send(POS, new JsonPrimitive("Washer done!")), USE_ONLY) && taken(type, control.send(POS, new JsonPrimitive("")), USE_ONLY)
                            && taken(type, control.send(POS, new JsonPrimitive("€".repeat(control.maxLength()))), USE_ONLY), what + " takes text up to its length");
                    check(!taken(type, control.send(POS, new JsonPrimitive("x".repeat(control.maxLength() + 1))), USE_AND_CONFIGURE),
                            what + " refuses a text over " + control.maxLength() + " characters");
                    break;
                }
                if (control.key().equals("time")) {
                    // A timer's time of day (M4 plan D2): HH:MM only, as Home Assistant's pattern has it.
                    check(taken(type, control.send(POS, new JsonPrimitive("07:15")), USE_AND_CONFIGURE) && taken(type, control.send(POS, new JsonPrimitive("00:00")), USE_AND_CONFIGURE)
                            && taken(type, control.send(POS, new JsonPrimitive("23:59")), USE_AND_CONFIGURE), what + " takes a time as HH:MM");
                    for (String refused : List.of("25:00", "6:00", "12:60", "", "noon", "x".repeat(control.maxLength() + 1))) {
                        check(!taken(type, control.send(POS, new JsonPrimitive(refused)), USE_AND_CONFIGURE), what + " refuses '" + refused + "'");
                    }
                    check(control.text(state(control.key(), new JsonPrimitive("18:30"))).equals("18:30"), what + " shows the state's time");
                    break;
                }
                if (control.key().equals("cron")) {
                    // The Cron Timer's expression (D2): one that parses, or nothing for no schedule; at most 64 characters.
                    check(taken(type, control.send(POS, new JsonPrimitive("0 */5 * * * * *")), USE_AND_CONFIGURE)
                            && taken(type, control.send(POS, new JsonPrimitive("")), USE_AND_CONFIGURE), what + " takes an expression and the empty text");
                    for (String refused : List.of("* * * *", "0 0 0 25 * * *", "every minute", "0 " + "1,".repeat(32) + "1 * * * * *")) {
                        check(!taken(type, control.send(POS, new JsonPrimitive(refused)), USE_AND_CONFIGURE), what + " refuses '" + refused + "'");
                    }
                    check(control.text(state(control.key(), new JsonPrimitive("0 0 0 12 * * *"))).equals("0 0 0 12 * * *"), what + " shows the state's expression");
                    break;
                }
                if (control.key().equals("block")) {
                    // The Block Placer's block (M4-10): the screen sends text up to its length; the placer itself judges the block
                    // (PlacerRules) and says why in its last result, as it does for Home Assistant and its Raw Link.
                    check(taken(type, control.send(POS, new JsonPrimitive("minecraft:redstone_lamp[lit=true]")), USE_AND_CONFIGURE)
                            && !taken(type, control.send(POS, new JsonPrimitive("minecraft:white_wool")), USE_ONLY), what + " takes a block behind CONFIGURE");
                    check(!taken(type, control.send(POS, new JsonPrimitive("x".repeat(control.maxLength() + 1))), USE_AND_CONFIGURE),
                            what + " refuses a text over " + control.maxLength() + " characters");
                    break;
                }
                // The Presence Sensor's mob filter: ids, or nothing for every mob; unknown mobs are refused, not dropped.
                check(taken(type, control.send(POS, new JsonPrimitive("zombie, pig")), USE_AND_CONFIGURE)
                        && taken(type, control.send(POS, new JsonPrimitive("")), USE_AND_CONFIGURE), what + " takes ids and the empty text");
                check(!taken(type, control.send(POS, new JsonPrimitive("minecraft:ghost")), USE_AND_CONFIGURE)
                        && !taken(type, control.send(POS, new JsonPrimitive("x".repeat(control.maxLength() + 1))), USE_AND_CONFIGURE),
                        what + " refuses an unknown id and a text over " + control.maxLength() + " characters");
                check(control.text(state(control.key(), new JsonPrimitive("minecraft:pig"))).equals("minecraft:pig") && control.text(new JsonObject()).isEmpty(),
                        what + " shows the state's text, else nothing");
            }
            case PRESS -> {
                // The Wall Switch's buttons: PRESS from a player who may use it, nothing else.
                check(control.pressed().equals(new JsonPrimitive(Coerce.PRESS)) && taken(type, control.send(POS, control.pressed()), USE_ONLY)
                        && !taken(type, control.send(POS, control.pressed()), DeviceView.Grants.NONE), what + " presses for a player who may use the device, and only for one");
                check(!taken(type, control.send(POS, new JsonPrimitive("ON")), USE_ONLY), what + " takes nothing but a press");
            }
        }
    }

    private static void widgets() {
        check(DeviceTabs.Widget.of(PortType.text(8)).equals(Optional.of(DeviceTabs.Widget.TEXT_FIELD)), "A text kind is a text field");
        check(DeviceTabs.Widget.of(PortType.enumOf(List.of("a", "b"))).equals(Optional.of(DeviceTabs.Widget.CYCLE)), "An enum kind is a cycle button");
        check(DeviceTabs.Widget.of(PortType.BOOL).equals(Optional.of(DeviceTabs.Widget.TOGGLE)), "A bool kind is a switch");
        check(DeviceTabs.Widget.of(PortType.LEVEL).equals(Optional.of(DeviceTabs.Widget.SLIDER))
                && DeviceTabs.Widget.of(PortType.integer(1, 8)).equals(Optional.of(DeviceTabs.Widget.SLIDER)), "A level and a whole number are sliders");
        check(DeviceTabs.Widget.of(PortType.LIGHT).equals(Optional.of(DeviceTabs.Widget.LIGHT)), "A light kind is the light picker");
        check(DeviceTabs.Widget.of(PortType.event("press")).equals(Optional.of(DeviceTabs.Widget.PRESS)), "An event action is a button");
        check(DeviceTabs.Widget.of(PortType.number(0, 1, false, null)).isEmpty() && DeviceTabs.Widget.of(PortType.COLOR).isEmpty()
                && DeviceTabs.Widget.of(PortType.JSON).isEmpty(), "No generated tab has a widget for fractions, a bare colour or JSON yet");
        check(DeviceTabs.Widget.LIGHT.rows() == 4 && DeviceTabs.Widget.SLIDER.rows() == 1, "The light picker takes four rows, any other widget one");
        try {
            new DeviceTabs.Control("color", PortType.COLOR, LinkSetting.Gate.USE, DeviceTabs.Control.Payload.CONFIG);
            throw new AssertionError("A control without a widget must not be built");
        } catch (IllegalArgumentException expected) {
            // Only kinds with a widget become controls.
        }
        try {
            new DeviceTabs.Control("state", PortType.BOOL, LinkSetting.Gate.CONFIGURE, DeviceTabs.Control.Payload.ACTION);
            throw new AssertionError("An action control needs USE");
        } catch (IllegalArgumentException expected) {
            // Actions are USE, design §8.7.
        }
        DeviceTabs.Control cycle = new DeviceTabs.Control("mode", PortType.enumOf(List.of("a", "b", "c")), LinkSetting.Gate.CONFIGURE, DeviceTabs.Control.Payload.CONFIG);
        check(cycle.next("a").equals("b") && cycle.next("c").equals("a") && cycle.next("unknown").equals("a") && cycle.next("B").equals("c"),
                "A cycle steps on, wraps, starts over from an unknown value and reads any case");
        DeviceTabs.Control level = new DeviceTabs.Control("power", PortType.LEVEL, LinkSetting.Gate.USE, DeviceTabs.Control.Payload.CONFIG);
        check(level.min() == 0 && level.max() == PortType.MAX_LEVEL && level.unit().isEmpty(), "A level slides from 0 to 15");
        DeviceTabs.Control range = DeviceTabs.Control.of(PresenceSensor.TYPE.port(Port.Direction.IN, "range").orElseThrow());
        check(range.min() == 1 && range.max() == 8 && range.unit().equals(Optional.of("blocks")), "The range slides from 1 to 8 blocks");
        check(DeviceTabs.tag(JsonParser.parseString("{\"state\":\"ON\",\"brightness\":128,\"color\":{\"r\":1,\"g\":2,\"b\":3}}"))
                        .equals(compound()), "A light command becomes a compound of strings and ints");
    }

    private static CompoundTag compound() {
        CompoundTag color = new CompoundTag();
        color.putInt("r", 1);
        color.putInt("g", 2);
        color.putInt("b", 3);
        CompoundTag light = new CompoundTag();
        light.putString("state", "ON");
        light.putInt("brightness", 128);
        light.put("color", color);
        return light;
    }

    /**
     * The Controls tab shows what its widgets can: a press action as a button that fires it, a text setting as a text
     * field (M2-12); a type with more rows of controls than the tab holds is refused, here rather than when its screen
     * opens.
     */
    private static void controlsTabLimits() {
        Field<Boolean> powered = Field.bool("powered", Access.READ);
        DeviceType press = DeviceType.builder(id("press")).model("Press").field(powered).entity(Ha.binarySensor("powered", powered))
                .entity(Ha.button("trigger_now")).action(Action.press("trigger_now")).build();
        DeviceTabs.Control trigger = ControlsTab.controls(press).getFirst();
        check(trigger.widget() == DeviceTabs.Widget.PRESS && trigger.payload() == DeviceTabs.Control.Payload.ACTION && trigger.gate() == LinkSetting.Gate.USE,
                "A press action is a button that sends the action with USE");
        check(taken(press, trigger.send(POS, trigger.pressed()), USE_ONLY) && !taken(press, trigger.send(POS, trigger.pressed()), CONFIGURE_ONLY),
                "The button's PRESS is taken from a player who may use the device, and only from one");
        permissions(press, trigger);

        Field<String> label = Field.text("label", Access.READ_WRITE);
        DeviceType text = DeviceType.builder(id("text")).model("Text").field(powered).field(label).entity(Ha.binarySensor("powered", powered))
                .setting(new Setting<>(label)).build();
        DeviceTabs.Control field = ControlsTab.controls(text).getFirst();
        check(field.widget() == DeviceTabs.Widget.TEXT_FIELD && field.gate() == LinkSetting.Gate.CONFIGURE && field.maxLength() == ValueCodecs.MAX_TEXT_LENGTH,
                "A text setting is a text field of its length, behind CONFIGURE");

        DeviceType.Builder many = DeviceType.builder(id("many")).model("Many").field(powered).entity(Ha.binarySensor("powered", powered));
        for (int i = 0; i <= ControlsTab.MAX_ROWS; i++) {
            Field<Boolean> option = Field.bool("option_" + i, Access.READ_WRITE);
            many.field(option).setting(new Setting<>(option));
        }
        DeviceType crowded = many.build();
        refused(() -> ControlsTab.controls(crowded), "More controls than the tab's rows");
        int lamp = ControlsTab.controls(SmartLamp.TYPE).stream().mapToInt(control -> control.widget().rows()).sum();
        check(lamp == ControlsTab.MAX_ROWS, "The Smart Lamp's switch and light picker fill the tab's " + ControlsTab.MAX_ROWS + " rows (" + lamp + ")");
    }

    /**
     * M4-1 (plan D5) on a synthetic timer-like type: a number field for a range a slider cannot pick from, the
     * {@code state} row folded into the toggle of the BOOL field the switch reads, and the status line on the last row.
     * No shipped type changes: their controls are the expected ones in {@code main}.
     */
    private static void m4Controls() {
        // The widget follows the range: a slider up to NUMBER_FIELD_STEPS steps, a number field beyond.
        check(DeviceTabs.Widget.of(PortType.integer(1, 1_000_000)).equals(Optional.of(DeviceTabs.Widget.NUMBER_FIELD)), "1 to 1 000 000 is a number field");
        check(DeviceTabs.Widget.of(PortType.integer(0, DeviceTabs.Widget.NUMBER_FIELD_STEPS)).equals(Optional.of(DeviceTabs.Widget.SLIDER))
                && DeviceTabs.Widget.of(PortType.integer(0, DeviceTabs.Widget.NUMBER_FIELD_STEPS + 1)).equals(Optional.of(DeviceTabs.Widget.NUMBER_FIELD)),
                "The bound is " + DeviceTabs.Widget.NUMBER_FIELD_STEPS + " steps");
        check(DeviceTabs.Widget.of(PortType.integer(1, 3600)).equals(Optional.of(DeviceTabs.Widget.SLIDER))
                && DeviceTabs.Widget.of(PortType.number(0, 3600, true, "s")).equals(Optional.of(DeviceTabs.Widget.SLIDER)),
                "The Climate Sensor's interval and the Presence Sensor's clear delay keep their sliders");
        check(DeviceTabs.Widget.NUMBER_FIELD.rows() == 1, "A number field takes one row");

        Field<Boolean> enabled = Field.bool("enabled", true, Access.READ_WRITE);
        Field<Integer> interval = Field.intRange("interval", 1, 1_000_000, 20, "ticks", Access.READ_WRITE);
        Field<TestUnit> unit = Field.enumOf("unit", TestUnit.class, Access.READ_WRITE);
        Field<String> nextFire = Field.text("next_fire", Access.READ);
        DeviceType.Builder timer = DeviceType.builder(id("timer")).model("Timer").unloaded(UnloadedPolicy.UNAVAILABLE)
                .field(enabled).field(interval).field(unit).field(nextFire)
                .entity(Ha.switchOf("enabled", enabled).on(true).off(false).name(null))
                .entity(Ha.button("trigger").name("Trigger now"))
                .entity(Ha.sensor("next_fire", nextFire).deviceClass("timestamp"))
                .action(Action.state()).action(Action.press("trigger"))
                .setting(new Setting<>(interval)).setting(new Setting<>(unit))
                .preview((state, command) -> state);
        DeviceType withStatus = timer.status((state, now) -> state.has("next_fire") && !state.get("next_fire").getAsString().isEmpty() ? "Next soon" : "Disabled").build();
        check(ControlsTab.collapsesState(withStatus), "A switch over a writable BOOL field folds the state action into the field's toggle");
        List<String> shown = ControlsTab.controls(withStatus).stream().map(control -> control.key() + " " + control.widget() + " " + control.gate() + " " + control.payload()).toList();
        check(shown.equals(List.of("trigger PRESS USE ACTION", "enabled TOGGLE USE CONFIG", "interval NUMBER_FIELD CONFIGURE CONFIG", "unit CYCLE CONFIGURE CONFIG")),
                "The timer's tab: Trigger now, Enabled, the interval as a number field and the unit, no state row: " + shown);
        check(new ControlsTab(withStatus).statusLine() && !new ControlsTab(SmartLamp.TYPE).statusLine(), "Only a type with a status line draws one");
        DeviceTabs.Control toggle = ControlsTab.controls(withStatus).get(1);
        check(taken(withStatus, toggle.send(POS, toggle.toggled(true)), USE_ONLY) && taken(withStatus, toggle.send(POS, toggle.toggled(false)), USE_ONLY),
                "The Enabled toggle switches the field with USE, as the state action did");
        check(DeviceConfig.action(withStatus, Action.STATE, "ON", USE_ONLY).isSuccess(), "The state action itself still works for commands and wires");
        DeviceTabs.Control number = ControlsTab.controls(withStatus).get(2);
        controlBounds(withStatus, number);
        permissions(withStatus, number);
        check(number.min() == 1 && number.max() == 1_000_000 && number.unit().equals(Optional.of("ticks")) && number.typedNumber(" 42 ").equals(Optional.of(new JsonPrimitive(42))),
                "The number field takes 1 to 1 000 000 ticks, spaces around it ignored");
        for (DeviceType type : DeviceTypes.all()) {
            check(ControlsTab.collapsesState(type) == Timers.all().contains(type),
                    type.id() + ": only the timers fold their state row (the Output's and the Plug's switches read a level)");
        }

        // The status line takes the last row: four rows of controls fit beside it, five do not.
        Field<Boolean> extra = Field.bool("extra", Access.READ_WRITE);
        refused(() -> ControlsTab.controls(DeviceType.builder(id("timer2")).model("Timer").unloaded(UnloadedPolicy.UNAVAILABLE)
                .field(enabled).field(interval).field(unit).field(nextFire).field(extra)
                .entity(Ha.switchOf("enabled", enabled).on(true).off(false)).entity(Ha.button("trigger"))
                .action(Action.state()).action(Action.press("trigger"))
                .setting(new Setting<>(interval)).setting(new Setting<>(unit)).setting(new Setting<>(extra))
                .status((state, now) -> "x").build()), "Five rows of controls beside a status line");
        check(ControlsTab.controls(DeviceType.builder(id("timer3")).model("Timer").unloaded(UnloadedPolicy.UNAVAILABLE)
                .field(enabled).field(interval).field(unit).field(nextFire).field(extra)
                .entity(Ha.switchOf("enabled", enabled).on(true).off(false)).entity(Ha.button("trigger"))
                .action(Action.state()).action(Action.press("trigger"))
                .setting(new Setting<>(interval)).setting(new Setting<>(unit)).setting(new Setting<>(extra)).build()).size() == 5,
                "Without a status line the same five rows fit");
    }

    /**
     * M4-16 (plan D13): the Configurator explorer's Devices tab fits above the explorer's inventory: the search field and
     * its two page buttons fill the content's width, the header comes below them, and its rows of two lines end by the
     * inventory's label (P1). A search waits for the typing to rest, one without an answer goes out again a few times,
     * and the page shown is asked for again only once its answer came.
     */
    private static void explorerLayout() {
        int fieldW = DevicesTab.SEARCH_W;
        check(fieldW >= 70 && fieldW + DevicesTab.ROOM_BUTTON_W + 2 * DevicesTab.PAGE_BUTTON_W + 3 * DevicesTab.SEARCH_GAP == DevicesTab.CONTENT_W,
                "The search field (" + fieldW + " px), the room filter and the page buttons fill the content's width");
        check(DevicesTab.ACTION_H <= DevicesTab.ROW_PITCH && DevicesTab.ACTION_H >= 14,
                "An entry's buttons fit its height and show their label");
        check(DevicesTab.SEARCH_Y + DevicesTab.SEARCH_H < DevicesTab.HEADER_Y && DevicesTab.HEADER_Y + 9 <= DevicesTab.LIST_Y,
                "The header sits between the search row and the rows");
        check(DevicesTab.ROW_PITCH >= 2 * 9 && DevicesTab.VISIBLE_ROWS >= 3, "Three rows of two lines show at once");
        int inventoryLabelY = TabbedMqttMenu.GUI_HEIGHT - 94;
        check(DevicesTab.CONTENT_BOTTOM <= inventoryLabelY && DevicesTab.CONTENT_BOTTOM == DevicesTab.LIST_Y + DevicesTab.VISIBLE_ROWS * DevicesTab.ROW_PITCH,
                "The rows end by y " + DevicesTab.CONTENT_BOTTOM + ", where the inventory's label starts at " + inventoryLabelY + " (P1)");
        check(new DevicesTab().id().equals(DevicesTab.ID) && !DevicesTab.ID.equals(CyberdeckExplorerTab.ID), "The Devices tab is not the Traffic tab");
        check(!DevicesTab.resendDue(false, 0, DevicesTab.RESEND_MILLIS - 1) && DevicesTab.resendDue(false, 0, DevicesTab.RESEND_MILLIS)
                        && !DevicesTab.resendDue(true, 0, DevicesTab.RESEND_MILLIS * 10) && !DevicesTab.resendDue(false, DevicesTab.MAX_RESENDS, DevicesTab.RESEND_MILLIS * 10),
                "A search without an answer goes out again after a second, at most three times, an answered one never");
        check(!DevicesTab.refreshDue(false, DevicesTab.REFRESH_MILLIS * 10) && !DevicesTab.refreshDue(true, DevicesTab.REFRESH_MILLIS - 1)
                        && DevicesTab.refreshDue(true, DevicesTab.REFRESH_MILLIS) && DevicesTab.DEBOUNCE_MILLIS < DevicesTab.RESEND_MILLIS,
                "The page shown is asked for again every two seconds once answered");
    }

    /**
     * M4-5 (plan D5): only the Cron Timer has the Schedule tab; it sends its expression as the cron setting through the
     * device config payload, which the server takes from a player who may configure the timer and only from one; its
     * boxes split and join an expression, each box is checked alone, the presets parse, and the preview gives the next
     * three fire times in the server's zone.
     */
    private static void cronSchedule() {
        for (DeviceType type : DeviceTypes.all()) {
            check(CronScheduleTab.appliesTo(type) == (type == Timers.CRON_TIMER), type.id() + ": the Schedule tab is the Cron Timer's alone");
        }
        CronScheduleTab tab = new CronScheduleTab(Timers.CRON_TIMER);
        check(tab.control().key().equals("cron") && tab.control().gate() == LinkSetting.Gate.CONFIGURE && tab.control().payload() == DeviceTabs.Control.Payload.CONFIG,
                "it sets the cron setting, behind CONFIGURE, as a config payload");
        CustomPacketPayload sent = tab.control().send(POS, new JsonPrimitive("0 */5 * * * * *"));
        check(sent instanceof DeviceConfigPayload config && config.key().equals("cron") && config.value().equals(StringTag.valueOf("0 */5 * * * * *")),
                "Set sends the joined expression through the device config payload, the Controls tab's text row's: " + sent);
        check(taken(Timers.CRON_TIMER, sent, CONFIGURE_ONLY) && !taken(Timers.CRON_TIMER, sent, USE_ONLY), "the server takes it from a player who may configure the timer, and only from one");
        check(taken(Timers.CRON_TIMER, tab.control().send(POS, new JsonPrimitive("")), CONFIGURE_ONLY), "Clear sends the empty expression, no schedule");
        check(!taken(Timers.CRON_TIMER, tab.control().send(POS, new JsonPrimitive("0 0 0 25 * * *")), CONFIGURE_ONLY), "an expression that does not parse is refused all the same");
        check(DeviceTabs.forViewer(Timers.CRON_TIMER, true).stream().map(MqttTab::id).toList().equals(List.of(ControlsTab.ID)),
                "a player who may only use the timer does not get it");
        refused(() -> new CronScheduleTab(Timers.ALARM_CLOCK), "A Schedule tab of a timer without a cron expression");

        check(CronScheduleTab.split("0 */5 * * * * *").equals(List.of("0", "*/5", "*", "*", "*", "*", "*")) && CronScheduleTab.split("").equals(List.of("", "", "", "", "", "", ""))
                && CronScheduleTab.split(" 1  2 ").equals(List.of("1", "2", "", "", "", "", "")), "An expression splits into its seven boxes");
        check(CronScheduleTab.join(List.of("0", "*/5", "*", "*", "*", "*", "*")).equals("0 */5 * * * * *") && CronScheduleTab.join(List.of("", " ", "", "", "", "", "")).isEmpty(),
                "The boxes join into the expression, empty boxes into none");
        check(CronScheduleTab.fieldProblem(0, "19").isEmpty() && CronScheduleTab.fieldProblem(0, "20").isPresent() && CronScheduleTab.fieldProblem(3, "23").isEmpty()
                && CronScheduleTab.fieldProblem(3, "24").isPresent() && CronScheduleTab.fieldProblem(6, "1-5").isEmpty() && CronScheduleTab.fieldProblem(1, "*/5").isEmpty()
                && CronScheduleTab.fieldProblem(4, "0").isPresent() && CronScheduleTab.fieldProblem(2, "").isPresent() && CronScheduleTab.fieldProblem(2, "1 2").isPresent()
                && CronScheduleTab.fieldProblem(5, "x").isPresent(), "Each box is checked alone, by its field's range");
        java.util.Set<String> expressions = new java.util.HashSet<>();
        for (CronScheduleTab.Preset preset : CronScheduleTab.PRESETS) {
            check(art.rehra.mineqtt.device.timer.core.CronTimerSchedule.problem(preset.expression()).isEmpty() && expressions.add(preset.expression()),
                    "Preset " + preset.key() + " parses and is no other's: " + preset.expression());
            for (int i = 0; i < CronScheduleTab.FIELDS.size(); i++) {
                check(CronScheduleTab.fieldProblem(i, CronScheduleTab.split(preset.expression()).get(i)).isEmpty(), "and so does each of its boxes");
            }
        }
        java.time.ZoneId berlin = java.time.ZoneId.of("Europe/Berlin");
        java.time.Instant noon = java.time.ZonedDateTime.of(2026, 10, 11, 12, 0, 0, 0, berlin).toInstant();
        check(CronScheduleTab.nextFires("0 */5 * * * * *", berlin, noon, CronScheduleTab.PREVIEW).equals(List.of(
                        java.time.ZonedDateTime.of(2026, 10, 11, 12, 0, 5, 0, berlin), java.time.ZonedDateTime.of(2026, 10, 11, 12, 0, 10, 0, berlin),
                        java.time.ZonedDateTime.of(2026, 10, 11, 12, 0, 15, 0, berlin))),
                "The preview gives the next three fire times");
        check(CronScheduleTab.nextFires("0 0 30 7 * * 1-5", berlin, noon, 3).getFirst().equals(java.time.ZonedDateTime.of(2026, 10, 12, 7, 30, 0, 0, berlin)),
                "On a Sunday the weekday preset fires next on Monday");
        check(CronScheduleTab.nextFires("0 0 0 0 31 2 *", berlin, noon, 3).isEmpty() && CronScheduleTab.nextFires("bad", berlin, noon, 3).isEmpty()
                && CronScheduleTab.nextFires("", berlin, noon, 3).isEmpty(), "No preview for a never matching, a broken or an empty expression");
        check(CronScheduleTab.zoneOf("2026-10-11T18:00:00+02:00").equals(java.time.ZoneOffset.ofHours(2)) && CronScheduleTab.zoneOf("").equals(java.time.ZoneId.systemDefault()),
                "The preview reads the server's zone from the timer's next fire, else the client's");
    }

    // A timer's rows: the shared ones, then its schedule's.
    private static List<String> rows(List<String> shared, String... schedule) {
        List<String> rows = new ArrayList<>(shared);
        rows.addAll(List.of(schedule));
        return rows;
    }

    /** A unit enum for {@link #m4Controls}, as a timer's would be. */
    private enum TestUnit implements net.minecraft.util.StringRepresentable {
        TICKS, SECONDS;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The light picker: hue and saturation round-trip through the lamp's 8-bit colour, and every command merges as meant. */
    private static void lightPicker() {
        check(new HueSaturation(0, 100).rgb().equals(new Rgb(255, 0, 0)) && new HueSaturation(120, 100).rgb().equals(new Rgb(0, 255, 0))
                && new HueSaturation(240, 100).rgb().equals(new Rgb(0, 0, 255)), "The primary hues at full saturation");
        check(new HueSaturation(77, 0).rgb().equals(Rgb.WHITE), "No saturation is white, whatever the hue");
        check(HueSaturation.of(new Rgb(0, 0, 0)).equals(new HueSaturation(0, 0)) && HueSaturation.of(new Rgb(128, 128, 128)).saturation() == 0,
                "Black and grey have no saturation");
        for (int hue = 0; hue <= LightControl.MAX_HUE; hue += 7) {
            for (int saturation : new int[]{20, 55, 100}) {
                HueSaturation picked = new HueSaturation(hue, saturation);
                HueSaturation back = HueSaturation.of(picked.rgb());
                int hueOff = Math.min(Math.abs(back.hue() - hue), 360 - Math.abs(back.hue() - hue));
                check(hueOff <= 2 && Math.abs(back.saturation() - saturation) <= 1, picked + " reads back from its colour as " + back);
            }
        }
        check(new HueSaturation(400, 150).equals(new HueSaturation(LightControl.MAX_HUE, LightControl.MAX_SATURATION)), "Hue and saturation keep their ranges");

        LightState off = new LightState(false, 30, new Rgb(0, 0, 255), null);
        LightState bright = off.merge(LightControl.brightness(200));
        check(bright.on() && bright.brightness() == 200 && bright.color().equals(off.color()), "Brightness switches the light on and keeps its colour: " + bright);
        LightState red = off.merge(LightControl.color(new HueSaturation(0, 100)));
        check(red.on() && red.color().equals(new Rgb(255, 0, 0)) && red.kelvin() == null && red.brightness() == 30, "A colour switches to rgb mode: " + red);
        LightState warm = red.merge(LightControl.kelvin(2700));
        check(warm.kelvin() == 2700 && warm.colorMode().equals("color_temp"), "A temperature switches to color_temp mode: " + warm);
        check(LightControl.brightness(0).get("brightness").getAsInt() == LightControl.MIN_BRIGHTNESS
                && LightControl.kelvin(99999).get("color_temp_kelvin").getAsInt() == ValueCodecs.MAX_KELVIN, "Commands stay within the picker's ends");
        check(LightControl.brightness(new LightState(true, 0, Rgb.WHITE, null)) == LightControl.MIN_BRIGHTNESS, "A light of brightness 0 shows the lowest step");
        check(LightControl.percent(LightControl.MAX_BRIGHTNESS) == 100 && LightControl.percent(128) == 50, "Brightness reads as a percentage");
        JsonObject dimBlue = LightControl.merge(LightControl.brightness(64), LightControl.color(new HueSaturation(240, 100)));
        check(dimBlue.has("brightness") && ValueCodecs.LightState.commandProblem(dimBlue).isEmpty()
                && off.merge(dimBlue).equals(off.merge(LightControl.brightness(64)).merge(LightControl.color(new HueSaturation(240, 100)))),
                "A brightness and a colour picked in turn merge into one command that does both: " + dimBlue);
        JsonObject dimWarm = LightControl.merge(dimBlue, LightControl.kelvin(2700));
        check(!dimWarm.has("color") && ValueCodecs.LightState.commandProblem(dimWarm).isEmpty() && off.merge(dimWarm).equals(off.merge(dimBlue).merge(LightControl.kelvin(2700))),
                "A temperature picked after a colour replaces it in the merged command: " + dimWarm);
        JsonObject dimRed = LightControl.merge(dimWarm, LightControl.color(new HueSaturation(0, 100)));
        check(!dimRed.has("color_temp_kelvin") && dimRed.get("brightness").getAsInt() == 64 && ValueCodecs.LightState.commandProblem(dimRed).isEmpty(),
                "A colour picked after a temperature replaces it, and the brightness stays: " + dimRed);

        JsonObject lampState = SmartLamp.state(warm, GlobalPos.of(Level.OVERWORLD, POS));
        check(LightControl.light(lampState).equals(warm), "The picker reads the lamp's light from its state JSON");
        check(LightControl.light(new JsonObject()).equals(LightState.DEFAULT), "A state without a light shows the default light");
        for (JsonObject refusedCommand : List.of(new JsonObject(), json("{\"brightness\":256}"), json("{\"color_temp_kelvin\":1000}"),
                json("{\"color_mode\":\"rgb\"}"), json("{\"state\":1}"), json("{\"state\":\"dim\"}"), json("{\"color\":{\"r\":300,\"g\":0,\"b\":0}}"),
                json("{\"color\":{\"r\":1,\"g\":2,\"b\":3},\"color_temp_kelvin\":3000}"), json("{\"brightness\":12.5}"))) {
            check(ValueCodecs.LightState.commandProblem(refusedCommand).isPresent(), "A light command " + refusedCommand + " is refused");
        }
        check(ValueCodecs.LightState.commandProblem(json("{\"state\":\"toggle\"}")).isEmpty()
                && ValueCodecs.LightState.commandProblem(json("{\"state\":true}")).isEmpty(), "A light command may toggle or carry a JSON boolean");
    }

    // The slug a typed name gives, as the server derives it, and the device's own while the name is its own or blank.
    private static void slugPreview() {
        DeviceView view = viewOf(SmartLamp.TYPE, Optional.empty());
        check(DeviceTabs.slugPreview(view.name(), view).equals(Optional.of(view.slug())), "Its own name keeps its slug");
        check(DeviceTabs.slugPreview("  " + view.name() + " ", view).equals(Optional.of(view.slug())), "Its own name with spaces keeps its slug");
        check(DeviceTabs.slugPreview("   ", view).equals(Optional.of(view.slug())), "A blank name, which is refused, keeps the slug");
        check(DeviceTabs.slugPreview("Kitchen Lamp", view).equals(Optional.of("kitchen_lamp")), "A name gives its slug");
        check(DeviceTabs.slugPreview("Café \u0007Lamp", view).equals(Optional.of("cafe_lamp")), "Accents and control characters are dropped as the server drops them");
        check(DeviceTabs.slugPreview("Bridge", view).equals(Optional.of("bridge_2")), "A reserved slug is suffixed");
        check(DeviceTabs.slugPreview("厨房", view).isEmpty(), "A name without a usable character leaves the slug to the server");
        check(DeviceTabs.name("  \"Garage\"  ").equals("\"Garage\""), "The name keeps the quotes a player types (F7) and loses the spaces");
    }

    private static void exposeValue() {
        check(DeviceTabs.exposeValue(viewOf(SmartLamp.TYPE, Optional.of(true))).equals("on"), "An exposed device shows on");
        check(DeviceTabs.exposeValue(viewOf(SmartLamp.TYPE, Optional.of(false))).equals("off"), "A hidden device shows off");
        check(DeviceTabs.exposeValue(viewOf(SmartLamp.TYPE, Optional.empty())).equals("default"), "A device that follows ha.expose shows default");
    }

    // A sample of what each widget sends; any value the widget can send would do.
    private static JsonElement sample(DeviceTabs.Control control) {
        // A twin key takes only what its reader parses (M3-7): an entity id, a value path, a plain number.
        if (control.key().equals(LinkSetting.TWIN_ENTITY.key()) || control.key().equals("twin.target") || control.key().equals("twin.left")
                || control.key().equals("twin.right")) {
            return new JsonPrimitive("light.kitchen");
        }
        if (control.key().equals(LinkSetting.TWIN_PATH.key())) return new JsonPrimitive("attributes.brightness");
        // M3-17: a preset and a device name, or a preset alone.
        if (control.key().equals(LinkSetting.TWIN_DEVICE.key())) return new JsonPrimitive("mineqtt:zigbee2mqtt_light Porch");
        if (control.key().equals(LinkSetting.TWIN_PRESET.key())) return new JsonPrimitive("mineqtt:tasmota_power");
        if (control.key().equals(LinkSetting.TWIN_MIN.key()) || control.key().equals(LinkSetting.TWIN_MAX.key())) return new JsonPrimitive("12.5");
        // M4-2: a timer's time of day and its cron expression.
        if (control.key().equals("time")) return new JsonPrimitive("07:15");
        if (control.key().equals("cron")) return new JsonPrimitive("0 */5 * * * * *");
        return switch (control.widget()) {
            // A name, a channel, a topic, a payload and a mob filter alike.
            case TEXT_FIELD -> new JsonPrimitive("zombie");
            case CYCLE -> new JsonPrimitive(control.values().getFirst());
            case TOGGLE -> control.toggled(true);
            case SLIDER -> control.leveled(control.max());
            case NUMBER_FIELD -> control.typedNumber(String.valueOf(control.max())).orElseThrow();
            case LIGHT -> LightControl.color(new HueSaturation(30, 80));
            case PRESS -> control.pressed();
        };
    }

    /**
     * The Twin tab of a type with a twin adapter (M3 plan D19): its rows as expected, at most five ending by y 100, each
     * row's cells side by side within the panel's width; every twin link setting of the type has exactly one place on
     * it (the source and its pick, a setting's cell, Resume on the health line), with the kind and gate of its
     * declaration, editable exactly when the server takes what it sends (the 16 grants; the TWIN and TWIN_CONTROL gates
     * are CONFIGURE there, the runtime adds the twin rights), and its value within the bounds the server checks. Returns
     * how many controls it has.
     */
    // The first two rows of a type with a source (M3-17): the cycle beside the entity's pick or the MQTT device's preset,
    // then the entity's details or the device's name.
    private static final String SOURCE_ROW = "source pick:twin.entity|preset:twin.preset";
    private static final String DETAILS_ROW = "info:twin.entity|device:twin.device";

    private static int twinTab(DeviceType type, List<String> expected) {
        TwinTab tab = DeviceTabs.forType(type).stream().filter(TwinTab.class::isInstance).map(TwinTab.class::cast).findFirst().orElseThrow();
        List<String> shown = tab.rows().stream().map(row -> row.cells().stream().map(DeviceTabsCheck::cell).collect(Collectors.joining(" "))).toList();
        check(shown.equals(expected), type.id() + ": the Twin tab's rows are " + expected + ", not " + shown);
        check(tab.rows().size() <= TwinTab.MAX_ROWS && TwinTab.rowY(tab.rows().size() - 1) + TwinTab.ROW_H <= TwinTab.CONTENT_BOTTOM
                        && TwinTab.CONTENT_BOTTOM <= TabbedMqttMenu.GUI_HEIGHT_NO_INVENTORY,
                type.id() + ": " + tab.rows().size() + " rows end by y " + TwinTab.CONTENT_BOTTOM + " (P1)");
        check(tab.rows().getLast().cells().equals(List.of(new TwinTab.Health(1))), type.id() + ": the health line is the last row");
        for (TwinTab.Row row : tab.rows()) {
            int end = 0;
            for (int i = 0; i < row.cells().size(); i++) {
                int[] span = row.spans().get(i);
                // A target's remover is one character wide (×); every other cell takes a word.
                int least = row.cells().get(i) instanceof TwinTab.Clear ? 14 : 30;
                check(span[0] >= end && span[1] >= least, type.id() + ": a cell of " + cells(row) + " starts after the one before it and is at least "
                        + least + " px wide: " + span[1]);
                end = span[0] + span[1] + TwinTab.GAP;
            }
            row.cells().stream().filter(TwinTab.Clear.class::isInstance).map(TwinTab.Clear.class::cast).forEach(clear -> check(
                    row.cells().contains(new TwinTab.Pick(clear.key(), 8)) && TwinPicker.target(type.twin().orElseThrow(), clear.key()),
                    type.id() + ": a remover sits next to its target's pick: " + cells(row)));
            check(row.cells().isEmpty() || end - TwinTab.GAP == TwinTab.CONTENT_W, type.id() + ": the cells of " + cells(row) + " fill the row");
        }
        List<String> keys = LinkSetting.of(type).stream().map(LinkSetting::key).filter(key -> key.startsWith(LinkSetting.TWIN_PREFIX)).toList();
        check(tab.controls().stream().map(DeviceTabs.Control::key).toList().equals(keys), type.id() + ": the Twin tab has every twin key of the type: " + keys);
        List<String> placed = new ArrayList<>();
        for (TwinTab.Row row : tab.rows()) {
            for (TwinTab.Cell cell : row.cells()) {
                if (cell instanceof TwinTab.BySource place) {
                    check(place.homeAssistant().weight() == place.mqttDevice().weight() && !(place.homeAssistant() instanceof TwinTab.BySource),
                            type.id() + ": both cells of a place share its width: " + cells(row));
                    placed(place.homeAssistant()).ifPresent(placed::add);
                    placed(place.mqttDevice()).ifPresent(placed::add);
                } else {
                    placed(cell).ifPresent(placed::add);
                }
            }
        }
        tab.resume().ifPresent(resume -> placed.add(resume.key()));
        // The MQTT device's preset and name have their places too (TwinTab.NOT_ON_TAB is gone); every twin key has one.
        check(placed.stream().sorted().toList().equals(keys.stream().sorted().toList()), type.id() + ": each twin key has exactly one place: " + placed);
        check(type.twin().orElseThrow().hasSource() == (placed.contains(LinkSetting.TWIN_DEVICE.key()) && placed.contains(LinkSetting.TWIN_PRESET.key())),
                type.id() + ": a type with a source places the MQTT device's name and preset (M3-17), a type with targets only neither");
        check(tab.resume().isPresent() == LinkSetting.pausable(type.twin().orElseThrow()),
                type.id() + ": Resume exactly where the breaker can pause it (control or targets)");
        tab.resume().ifPresent(resume -> check(resume.widget() == DeviceTabs.Widget.PRESS && resume.gate() == LinkSetting.Gate.CONFIGURE
                        && taken(type, resume.send(POS, resume.pressed()), CONFIGURE_ONLY) && !taken(type, resume.send(POS, resume.pressed()), USE_ONLY),
                type.id() + ": Resume is a press that needs CONFIGURE, as /mineqtt devices bind <device> resume"));
        for (DeviceTabs.Control control : tab.controls()) {
            LinkSetting<?> setting = type.linkSetting(control.key()).orElseThrow(() -> new AssertionError(type.id() + " declares no " + control.key()));
            check(control.kind().equals(setting.kind()) && control.gate() == setting.gate() && control.payload() == DeviceTabs.Control.Payload.CONFIG,
                    type.id() + ": " + control.key() + " takes kind and gate from its declaration and is a link setting");
            check(control.gate() != LinkSetting.Gate.USE, type.id() + ": no twin key is a player's to change who may only use the device");
            permissions(type, control);
        }
        List<String> picked = new ArrayList<>();
        if (type.twin().orElseThrow().hasSource()) picked.add(LinkSetting.TWIN_ENTITY.key());
        type.twin().orElseThrow().targets().keySet().forEach(name -> picked.add(LinkSetting.TWIN_PREFIX + name));
        check(tab.pickKeys().equals(picked), type.id() + ": the picker chooses its source and each target (M3-14): " + tab.pickKeys());
        for (String key : tab.pickKeys()) {
            boolean target = TwinPicker.target(type.twin().orElseThrow(), key);
            check(TwinPicker.domains(type.twin().orElseThrow(), key).equals(target ? type.twin().orElseThrow().targetDomains() : type.twin().orElseThrow().sourceDomains())
                            && !TwinPicker.domains(type.twin().orElseThrow(), key).isEmpty(),
                    type.id() + ": " + key + " picks from its " + (target ? "target" : "source") + " domains");
        }
        return tab.controls().size();
    }

    // The twin key a cell sets, if it sets one: the pick its key, the MQTT device's preset and name theirs (M3-17).
    private static Optional<String> placed(TwinTab.Cell cell) {
        return switch (cell) {
            case TwinTab.Pick pick -> Optional.of(pick.key());
            case TwinTab.Preset preset -> Optional.of(LinkSetting.TWIN_PRESET.key());
            case TwinTab.Device device -> Optional.of(LinkSetting.TWIN_DEVICE.key());
            case TwinTab.Setting setting -> Optional.of(setting.control().key());
            default -> Optional.empty();
        };
    }

    private static String cell(TwinTab.Cell cell) {
        return switch (cell) {
            case TwinTab.Source source -> "source";
            case TwinTab.Pick pick -> "pick:" + pick.key();
            case TwinTab.Clear clear -> "clear:" + clear.key();
            case TwinTab.Info info -> "info:" + info.key();
            case TwinTab.Preset preset -> "preset:" + LinkSetting.TWIN_PRESET.key();
            case TwinTab.Device device -> "device:" + LinkSetting.TWIN_DEVICE.key();
            case TwinTab.BySource place -> cell(place.homeAssistant()) + "|" + cell(place.mqttDevice());
            case TwinTab.Setting setting -> setting.control().key();
            case TwinTab.Health health -> "health";
        };
    }

    private static String cells(TwinTab.Row row) {
        return row.cells().stream().map(DeviceTabsCheck::cell).toList().toString();
    }

    /**
     * What the Twin tab and its picker share (M3 plan D4, D19): a type whose twin rows would not fit is refused, rows are
     * grouped by area in ranking order, the free-text row offers an entity id of the key's domains that no row lists, and
     * the block name a pick gives is a valid device name.
     */
    private static void twinTabLimits() {
        TwinPicker.Row kitchen = row("light.kitchen", "Kitchen Light", "Kitchen");
        TwinPicker.Row porch = row("light.porch", "Porch", "");
        TwinPicker.Row fridge = row("switch.fridge", "Fridge", "Kitchen");
        TwinPicker.Row hall = row("light.hall", "Hall", "Hallway");
        check(TwinPicker.grouped(List.of(kitchen, porch, hall, fridge)).equals(List.of(kitchen, fridge, hall, porch)),
                "Rows are grouped by area in the order of each area's best row, keeping their ranking, rows without an area last");
        check(TwinPicker.grouped(List.of(row("light.a", "A", "kitchen"), row("light.b", "B", "Kitchen"))).size() == 2, "Areas group case-blind");
        List<String> lamp = TwinPicker.domains(SmartLamp.TYPE.twin().orElseThrow(), LinkSetting.TWIN_ENTITY.key());
        check(lamp.equals(SmartLamp.TYPE.twin().orElseThrow().sourceDomains()) && lamp.getFirst().equals("light"), "The lamp picks from its source domains, light first");
        check(TwinPicker.domains(SmartLamp.TYPE.twin().orElseThrow(), "twin.unavailable").isEmpty(), "A key the picker does not choose for has no domains");
        check(TwinPicker.searched(SmartLamp.TYPE.twin().orElseThrow(), LinkSetting.TWIN_ENTITY.key(), "switch").equals(List.of("switch"))
                        && TwinPicker.searched(SmartLamp.TYPE.twin().orElseThrow(), LinkSetting.TWIN_ENTITY.key(), "sensor").equals(lamp)
                        && TwinPicker.searched(SmartLamp.TYPE.twin().orElseThrow(), LinkSetting.TWIN_ENTITY.key(), "").equals(lamp),
                "A domain chip narrows the search to one of the key's domains; another domain never widens it");
        check(TwinPicker.freeText("light.xyz", List.of(kitchen), lamp).equals(Optional.of("light.xyz")), "An unlisted entity id of the key's domains is offered");
        check(TwinPicker.freeText(" light.kitchen ", List.of(kitchen), lamp).isEmpty(), "A listed one is not offered twice");
        check(TwinPicker.freeText("sensor.temperature", List.of(), lamp).isEmpty() && TwinPicker.freeText("kitchen", List.of(), lamp).isEmpty()
                        && TwinPicker.freeText("Light.Kitchen", List.of(), lamp).isEmpty() && TwinPicker.freeText("light.kitchen lamp", List.of(), lamp).isEmpty(),
                "Nothing is offered for another domain, a word, capitals or spaces");
        // M3-14: the Wall Switch picks Left and Right separately, from the target domains; the Input its one target.
        check(TwinPicker.keys(WallSwitch.TWIN).equals(List.of("twin.left", "twin.right")) && TwinPicker.keys(RedstoneInput.TWIN).equals(List.of("twin.target"))
                        && TwinPicker.targetName(WallSwitch.TWIN, "twin.left").equals(Optional.of("left"))
                        && TwinPicker.targetName(SmartLamp.TYPE.twin().orElseThrow(), "twin.entity").isEmpty(),
                "The Wall Switch picks left and right, the Input its target, a mirror's source is no target");
        check(TwinPicker.domains(WallSwitch.TWIN, "twin.left").containsAll(List.of("scene", "script", "button", "light"))
                        && !TwinPicker.domains(WallSwitch.TWIN, "twin.left").contains("binary_sensor")
                        && !TwinPicker.domains(RedstoneInput.TWIN, "twin.target").contains("sensor"),
                "A target picks from the domains it can switch, never a sensor");
        check(TwinPicker.blockName(kitchen).equals("Kitchen Light") && TwinPicker.blockName(row("light.x", "", "")).equals("light.x")
                        && TwinPicker.blockName(row("light.y", "  " + "N".repeat(70), "")).length() <= LinkSetting.MAX_NAME_LENGTH,
                "A pick names the block after the entity's name, else its id, within the name's length");
        check(LinkSetting.NAME.parse(new JsonPrimitive(TwinPicker.blockName(kitchen))).isSuccess(), "The name a pick gives is one the server takes");
        java.util.LinkedHashSet<String> recent = new java.util.LinkedHashSet<>();
        for (int i = 0; i < TwinPicker.MAX_RECENT + 3; i++) TwinPicker.remember(recent, "light.l" + i);
        TwinPicker.remember(recent, "light.l5");
        check(recent.size() == TwinPicker.MAX_RECENT && recent.iterator().next().equals("light.l3") && List.copyOf(recent).getLast().equals("light.l5"),
                "A player's recent bindings keep the newest " + TwinPicker.MAX_RECENT + ", a repeat moving to the end: " + recent);
    }

    private static TwinPicker.Row row(String id, String name, String area) {
        return new TwinPicker.Row(id, name, area, "on", java.util.OptionalInt.empty(), false, false, false);
    }

    // Whether the server's validator takes the payload from a player with these grants, as its handler would.
    private static boolean taken(DeviceType type, CustomPacketPayload payload, DeviceView.Grants grants) {
        return switch (payload) {
            case DeviceConfigPayload config -> DeviceConfig.parse(type, config.key(), config.value(), grants).isSuccess();
            case DeviceActionPayload action -> DeviceConfig.action(type, action.action(), action.value(), grants).isSuccess();
            default -> throw new AssertionError("Not a device payload: " + payload);
        };
    }

    private static JsonObject state(String key, JsonElement value) {
        JsonObject state = new JsonObject();
        state.add(key, value);
        return state;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /**
     * M4 plan D11: the General tab's room line names the device's room, and says which area Home Assistant may show when
     * the device was first created there with another area than its discovery suggests now (Home Assistant keeps the area
     * a device has); a device Home Assistant does not hold says nothing. The view carries both, capped, through its codec; a controls-only view
     * neither.
     */
    private static void roomLine() {
        check(DeviceView.Registration.keptArea(false, "Hall", "Kitchen").isEmpty(), "Nothing kept for a device Home Assistant does not hold");
        check(DeviceView.Registration.keptArea(true, "Kitchen", "Kitchen").isEmpty() && DeviceView.Registration.keptArea(true, "", null).isEmpty(),
                "Nothing to say while the kept area is the suggested one");
        check(DeviceView.Registration.keptArea(true, "Hall", "Kitchen").equals(Optional.of("Hall")), "A device created in the hall keeps the hall");
        check(DeviceView.Registration.keptArea(true, "Hall", null).equals(Optional.of("Hall")), "and keeps it when its room is gone");
        check(DeviceView.Registration.keptArea(true, "", "Kitchen").equals(Optional.of("")), "A device created in no room keeps none");
        check(DeviceView.Registration.keptArea(true, null, "Kitchen").equals(Optional.of("")), "A device discovered before rooms reads as created in none");
        check(DeviceView.Registration.keptArea(true, null, null).isEmpty(), "and says nothing while it is in no room");
        check(DeviceView.Registration.keptArea(true, "Kitchen", "kitchen").isEmpty() && DeviceView.Registration.keptArea(true, "Living Room", "livingroom").isEmpty(),
                "Areas compare as Home Assistant compares them, case and spaces ignored");
        check(DeviceView.Registration.keptArea(true, "Kitchen", "Cookhouse").equals(Optional.of("Kitchen")),
                "Hidden, renamed and exposed again: the first area stays, as 2026.10 restores it (2025.2 takes the new one, so the hint says may)");

        DeviceView plain = viewOf(SmartLamp.TYPE, Optional.empty());
        check(plain.registration().room().isEmpty() && plain.registration().keptArea().isEmpty(), "A registration of three parts has no room");
        check(DeviceTabs.roomValue(plain).getContents() instanceof TranslatableContents none && none.getKey().equals("mineqtt.device.general.room.none")
                && DeviceTabs.roomHint(plain).isEmpty(), "No room: none, and no hint");
        DeviceView kitchen = withRegistration(plain, new DeviceView.Registration("mineqtt/world_7f3a", true, Optional.empty(), Optional.of("Kitchen"), Optional.empty()));
        check(DeviceTabs.roomValue(kitchen).getString().equals("Kitchen") && DeviceTabs.roomHint(kitchen).isEmpty(), "The room by name, no hint while Home Assistant agrees");
        DeviceView hall = withRegistration(plain, new DeviceView.Registration("mineqtt/world_7f3a", true, Optional.empty(), Optional.of("Kitchen"), Optional.of("Hall")));
        check(DeviceTabs.roomHint(hall).map(Component::getContents).filter(TranslatableContents.class::isInstance).map(TranslatableContents.class::cast)
                .filter(hint -> hint.getKey().equals("mineqtt.device.general.room.kept") && List.of(hint.getArgs()).equals(List.of("Hall"))).isPresent(),
                "Home Assistant keeps Hall: the hint names it");
        DeviceView noArea = withRegistration(plain, new DeviceView.Registration("mineqtt/world_7f3a", true, Optional.empty(), Optional.of("Kitchen"), Optional.of("")));
        check(DeviceTabs.roomHint(noArea).map(Component::getContents).filter(TranslatableContents.class::isInstance)
                .map(contents -> ((TranslatableContents) contents).getKey()).equals(Optional.of("mineqtt.device.general.room.kept_none")), "Home Assistant keeps no area");
        DeviceView roomless = withRegistration(plain, new DeviceView.Registration("mineqtt/world_7f3a", true, Optional.empty(), Optional.empty(), Optional.of("Hall")));
        check(DeviceTabs.roomValue(roomless).getContents() instanceof TranslatableContents && DeviceTabs.roomHint(roomless).isPresent(),
                "A removed room: none, and the hall Home Assistant keeps");
        JsonObject lang = englishLang();
        for (String key : List.of("room", "room.none", "room.differs", "room.tooltip", "room.kept", "room.kept_none")) {
            check(lang.has("mineqtt.device.general." + key), "The room line's text mineqtt.device.general." + key + " is translated");
        }

        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        DeviceView.STREAM_CODEC.encode(buf, hall);
        check(DeviceView.STREAM_CODEC.decode(buf).equals(hall), "The room and the kept area go through the view's codec");
        DeviceView user = withOwnership(hall, new DeviceView.Grants(true, false, false, false), DeviceView.Owner.NOBODY).redacted();
        check(user.registration().room().isEmpty() && user.registration().keptArea().isEmpty(), "A controls-only view shows no room");
    }

    private static JsonObject englishLang() {
        try (java.io.InputStream in = DeviceTabsCheck.class.getResourceAsStream("/assets/mineqtt/lang/en_us.json")) {
            check(in != null, "The English lang file is on the classpath");
            return JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError("The English lang file could not be read", e);
        }
    }

    private static DeviceView withRegistration(DeviceView view, DeviceView.Registration registration) {
        return new DeviceView(view.pos(), view.type(), view.name(), view.slug(), view.ownerName(), view.access(), view.expose(), registration, view.grants(),
                view.state(), view.raw(), view.privateMode(), view.status(), view.problem(), view.ownership(), view.diagnostics(), view.legacyItems(),
                view.controlsOnly());
    }

    private static DeviceView viewOf(DeviceType type, Optional<Boolean> expose) {
        return new DeviceView(BlockPos.ZERO, type.id(), type.defaultName(DEVICE), type.defaultSlug(DEVICE), "", AccessMode.CLAIM, expose,
                new DeviceView.Registration("mineqtt/world_7f3a", false, Optional.empty()), DeviceView.Grants.ALL, "{}", Optional.empty(), false,
                type.defaultSlug(DEVICE), Optional.empty(), new DeviceView.Ownership(DeviceView.Owner.NOBODY, false), DeviceView.Diagnostics.notRunning(DEVICE), 0, false);
    }

    private static DeviceView withOwnership(DeviceView view, DeviceView.Grants grants, DeviceView.Owner owner) {
        return new DeviceView(view.pos(), view.type(), view.name(), view.slug(), owner == DeviceView.Owner.NOBODY ? "" : "Steve", view.access(), view.expose(),
                view.registration(), grants, view.state(), view.raw(), view.privateMode(), view.status(), view.problem(),
                new DeviceView.Ownership(owner, view.ownership().claimMod()), view.diagnostics(), view.legacyItems(), view.controlsOnly());
    }

    private static DeviceView withDiagnostics(DeviceView view, DeviceView.Grants grants, DeviceView.Diagnostics diagnostics) {
        return new DeviceView(view.pos(), view.type(), view.name(), view.slug(), view.ownerName(), view.access(), view.expose(),
                view.registration(), grants, view.state(), view.raw(), view.privateMode(), view.status(), view.problem(), view.ownership(), diagnostics,
                view.legacyItems(), view.controlsOnly());
    }

    // The view with this Raw Link, private channel or not, and legacy item count.
    private static DeviceView withLink(DeviceView view, Optional<DeviceView.Raw> raw, boolean privateMode, int legacyItems) {
        return new DeviceView(view.pos(), view.type(), view.name(), view.slug(), view.ownerName(), view.access(), view.expose(),
                view.registration(), view.grants(), view.state(), raw, privateMode, view.status(), view.problem(), view.ownership(), view.diagnostics(),
                legacyItems, view.controlsOnly());
    }

    private static List<DeviceView.Grants> allGrants() {
        List<DeviceView.Grants> all = new ArrayList<>();
        for (int bits = 0; bits < 16; bits++) {
            all.add(new DeviceView.Grants((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0));
        }
        return all;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "check_" + path);
    }

    private static void refused(Runnable build, String what) {
        try {
            build.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("FAILED: refused: " + what);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAILED: " + what);
    }
}

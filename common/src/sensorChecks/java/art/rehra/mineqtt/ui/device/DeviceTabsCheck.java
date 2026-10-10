package art.rehra.mineqtt.ui.device;

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
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.ui.device.LightControl.HueSaturation;
import art.rehra.mineqtt.ui.framework.MqttTab;
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

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DeviceTypes.registerBuiltIn();
        // The controls of each registered type as the tab shows them: key, widget, gate, payload.
        Map<DeviceType, List<String>> expected = Map.of(
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
                WallSwitch.TYPE, List.of("press_left PRESS USE ACTION", "press_right PRESS USE ACTION"));
        check(expected.keySet().containsAll(DeviceTypes.all()), "Every registered type has its expected controls listed here");
        // The Advanced tab's controls of each registered type: its Raw Link's link settings.
        List<String> valueLink = List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.retain TOGGLE CONFIGURE CONFIG", "raw.qos SLIDER CONFIGURE CONFIG");
        List<String> inputLink = List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.on TEXT_FIELD CONFIGURE CONFIG", "raw.off TEXT_FIELD CONFIGURE CONFIG");
        Map<DeviceType, List<String>> advanced = Map.of(
                RedstoneOutput.TYPE, inputLink,
                SmartLamp.TYPE, inputLink,
                SmartPlug.TYPE, inputLink,
                RedstoneInput.TYPE, List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                        "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.retain TOGGLE CONFIGURE CONFIG", "raw.qos SLIDER CONFIGURE CONFIG",
                        "raw.on TEXT_FIELD CONFIGURE CONFIG", "raw.off TEXT_FIELD CONFIGURE CONFIG"),
                PresenceSensor.TYPE, valueLink,
                ClimateSensor.TYPE, valueLink,
                // Events are never retained: QoS only (M2-22).
                WallSwitch.TYPE, List.of("raw.channel TEXT_FIELD CONFIGURE CONFIG", "raw.private TEXT_FIELD CONFIGURE CONFIG",
                        "raw.topic TEXT_FIELD CONFIGURE CONFIG", "raw.qos SLIDER CONFIGURE CONFIG"));
        check(advanced.keySet().containsAll(DeviceTypes.all()), "Every registered type has its expected Advanced controls listed here");
        int controls = 0;
        for (DeviceType type : DeviceTypes.all()) {
            controls += tabs(type, expected.get(type));
            controls += advancedTab(type, advanced.get(type));
            controls += accessTab(type);
        }
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
        lightPicker();
        slugPreview();
        exposeValue();
        System.out.println("Device tab checks passed: " + DeviceTypes.all().size() + " types, " + controls + " controls.");
    }

    // The tabs of a type and the controls of each, derived from its declaration; returns how many controls it has.
    private static int tabs(DeviceType type, List<String> expected) {
        List<MqttTab> tabs = DeviceTabs.forType(type);
        boolean inPorts = type.ports().stream().anyMatch(port -> port.direction() == Port.Direction.IN);
        List<String> ids = inPorts ? List.of(GeneralTab.ID, ControlsTab.ID, AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID)
                : List.of(GeneralTab.ID, AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID);
        check(tabs.stream().map(MqttTab::id).toList().equals(ids), type.id() + ": the generated tabs are " + ids
                + ", Controls exactly when it has in-ports, Advanced, Access and Diagnostics always: " + tabs);
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
        Items.PAPER.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        Items.STONE.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
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
        return switch (control.widget()) {
            // A name, a channel, a topic, a payload and a mob filter alike.
            case TEXT_FIELD -> new JsonPrimitive("zombie");
            case CYCLE -> new JsonPrimitive(control.values().getFirst());
            case TOGGLE -> control.toggled(true);
            case SLIDER -> control.leveled(control.max());
            case LIGHT -> LightControl.color(new HueSaturation(30, 80));
            case PRESS -> control.pressed();
        };
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

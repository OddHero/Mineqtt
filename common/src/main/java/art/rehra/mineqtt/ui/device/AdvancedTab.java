package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.ui.framework.MqttTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * The Advanced tab of every device (M2 plan §4.2 M2-10, design §6.9): the editor of its Raw Link, the extra topic kept
 * for wireless redstone and v1 automations (design D2). The link's topic comes from a channel, an item or paper name the
 * server resolves for the device's owner ({@code RawTopics.resolve}), from a private channel, which resolves into the
 * owner's private subtree as v1's private mode did, or from a topic used as typed, which the ACL judges
 * for the player and the owner, an operator's bypass included. An output also sets whether it publishes retained (its
 * type's default shown) and its QoS; a type with custom payloads sets them too. Every control is one of the type's
 * {@link LinkSetting}s, so a player without CONFIGURE sees them read-only. Below them, the device's links: the wires of
 * the Configurator's Link mode (M2-16), the seed of the node editor (M2 plan §3.8 #8). No slots.
 */
public class AdvancedTab extends MqttTab {

    public static final String ID = "advanced";
    // The Raw Link's link settings, in the order the tab shows them; a type has those its Raw Link uses (LinkSetting.of).
    private static final List<LinkSetting<?>> RAW_SETTINGS = List.of(LinkSetting.RAW_CHANNEL, LinkSetting.RAW_PRIVATE, LinkSetting.RAW_TOPIC,
            LinkSetting.RAW_RETAIN, LinkSetting.RAW_QOS, LinkSetting.RAW_ON, LinkSetting.RAW_OFF);

    private final Optional<RawLinkSpec> spec;
    private final List<DeviceTabs.Control> controls;

    public AdvancedTab(DeviceType type) {
        super(ID);
        this.spec = type.rawLink();
        this.controls = controls(type);
    }

    /**
     * Where the editor's text field sends what the player typed: a channel resolved for the owner, a private channel
     * resolved into the owner's private subtree (v1's private mode, M2-12), or a topic used as typed.
     */
    public enum Mode {
        CHANNEL(LinkSetting.RAW_CHANNEL),
        PRIVATE(LinkSetting.RAW_PRIVATE),
        TOPIC(LinkSetting.RAW_TOPIC);

        private final LinkSetting<String> setting;

        Mode(LinkSetting<String> setting) {
            this.setting = setting;
        }

        /**
         * The mode a link shows in: a topic without a channel was typed (or is a v1 topic), a channel of a device in
         * private mode is private, anything else a channel.
         */
        public static Mode of(Optional<DeviceView.Raw> raw, boolean privateMode) {
            if (raw.isPresent() && raw.get().channel().isEmpty()) return TOPIC;
            return privateMode ? PRIVATE : CHANNEL;
        }

        /** The mode a view's link shows in. */
        public static Mode of(DeviceView view) {
            return of(view.raw(), view.privateMode());
        }

        /** The key the field sends in this mode. */
        public String key() {
            return setting.key();
        }

        /** Whether the field holds a channel: an item or paper literal, private or not. */
        public boolean channel() {
            return this != TOPIC;
        }

        /** What the field shows in this mode for a link: its channel or its topic; empty without one. */
        public String text(Optional<DeviceView.Raw> raw) {
            return raw.flatMap(link -> channel() ? link.channel() : Optional.of(link.topic())).orElse("");
        }

        /**
         * Whether sending {@code text} in this mode changes the link of a view: another text, or the same channel made
         * private or no longer private. An empty text removes the link, whatever the mode.
         */
        public boolean changes(String text, DeviceView view) {
            if (!text.equals(text(view.raw()))) return true;
            Mode shown = of(view);
            return !text.isEmpty() && channel() && shown.channel() && this != shown;
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * The controls of a device of {@code type}, in the order the tab shows them: the Raw Link's channel, private channel
     * and topic, an output's retain and QoS, the custom on and off payloads; none for a type without a Raw Link.
     */
    public static List<DeviceTabs.Control> controls(DeviceType type) {
        return RAW_SETTINGS.stream().filter(setting -> type.linkSetting(setting.key()).isPresent())
                .<DeviceTabs.Control>map(DeviceTabs.Control::of).toList();
    }

    /** The Raw Link of the type: how it uses it; empty when it has none. */
    public Optional<RawLinkSpec> spec() {
        return spec;
    }

    public List<DeviceTabs.Control> controls() {
        return controls;
    }

    /** The control of a link setting, when the type has it. */
    public Optional<DeviceTabs.Control> control(LinkSetting<?> setting) {
        return controls.stream().filter(control -> control.key().equals(setting.key())).findFirst();
    }

    /** Whether an output publishes retained: its own override, else the type's default. */
    public static boolean retain(DeviceView.Raw raw, RawLinkSpec spec) {
        return raw.retain().orElse(spec.retainByDefault());
    }

    /**
     * The channel a renamed paper fills in when it is used on a device (design §7.4): its name, without the spaces
     * around it, as the tab sends a typed one. Empty for anything else, which uses the device as usual, and for a blank
     * name (which commands can give), since filling a channel never removes the link.
     */
    public static Optional<String> paperChannel(ItemStack stack) {
        if (!stack.is(Items.PAPER) || stack.getCustomName() == null) return Optional.empty();
        String name = stack.getCustomName().getString().strip();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.PAPER);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.advanced");
    }
}

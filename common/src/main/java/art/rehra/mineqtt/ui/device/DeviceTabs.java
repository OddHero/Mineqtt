package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.device.Slugs;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.ui.framework.MqttTab;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The generated device screen (design §6.9, M2 plan §3.8 #8): its tabs and their controls derive from the device's
 * {@link DeviceType}, never from the block. A control is a setting of the type or of the link, a writable field or an
 * action, with the widget its {@link PortType} picks, the gate a player must pass to change it and the payload and
 * bounds of what the client may send, all taken from the declaration the server validates against
 * ({@link DeviceConfig}). Server-safe; the client draws each tab with the view registered under its id.
 */
public final class DeviceTabs {
    private DeviceTabs() {
    }

    /** The widget a control's kind picks. */
    public enum Widget {
        /** A text field of at most the kind's length. */
        TEXT_FIELD,
        /** A button that steps through the kind's values. */
        CYCLE,
        /** A button that switches on and off. */
        TOGGLE,
        /** A slider over the kind's whole numbers. */
        SLIDER,
        /** Brightness, hue, saturation and colour temperature of a JSON-schema light. */
        LIGHT,
        /** A button that fires an event action. */
        PRESS;

        /** How many rows of a tab the widget takes: the light picker four, any other one. */
        public int rows() {
            return this == LIGHT ? 4 : 1;
        }

        /**
         * The widget of a kind; empty for a kind no generated tab offers a widget for: a colour of its own, JSON, and a
         * number with fractions.
         */
        public static Optional<Widget> of(PortType kind) {
            return switch (kind) {
                case PortType.Text _ -> Optional.of(TEXT_FIELD);
                case PortType.Enum _ -> Optional.of(CYCLE);
                case PortType.Bool _ -> Optional.of(TOGGLE);
                case PortType.Level _ -> Optional.of(SLIDER);
                case PortType.Number number -> number.integral() ? Optional.of(SLIDER) : Optional.empty();
                case PortType.Light _ -> Optional.of(LIGHT);
                case PortType.Event _ -> Optional.of(PRESS);
                case PortType.Color _, PortType.Json _ -> Optional.empty();
            };
        }
    }

    /**
     * One control of a generated tab.
     *
     * @param key     the key the client sends: a setting, a writable field or an action of the type, or a link setting
     * @param kind    what it holds, which picks the widget and bounds the value
     * @param gate    who may change it; the server judges it again
     * @param payload how the client sends it
     */
    public record Control(String key, PortType kind, LinkSetting.Gate gate, Payload payload) {
        /** The payload a control sends its value with. */
        public enum Payload {
            /** {@code DeviceConfigC2S}: a setting, a link setting or a command to a writable field. */
            CONFIG,
            /** {@code DeviceActionC2S}: an action the type declares. */
            ACTION
        }

        public Control {
            if (Widget.of(kind).isEmpty()) throw new IllegalArgumentException("No widget shows a " + kind.describe() + " control: " + key);
            if (payload == Payload.ACTION && gate != LinkSetting.Gate.USE) throw new IllegalArgumentException("An action needs USE: " + key);
        }

        public static Control of(LinkSetting<?> setting) {
            return new Control(setting.key(), setting.kind(), setting.gate(), Payload.CONFIG);
        }

        /**
         * The control of an in-port of the device: a setting needs CONFIGURE, a command to a writable field or an action
         * USE (design §8.7). Out-ports and events are no controls.
         */
        public static Control of(Port port) {
            if (port.direction() != Port.Direction.IN) throw new IllegalArgumentException("An out-port is no control: " + port.key());
            return switch (port.origin()) {
                case SETTING -> new Control(port.key(), port.kind(), LinkSetting.Gate.CONFIGURE, Payload.CONFIG);
                case FIELD -> new Control(port.key(), port.kind(), LinkSetting.Gate.USE, Payload.CONFIG);
                case ACTION -> new Control(port.key(), port.kind(), LinkSetting.Gate.USE, Payload.ACTION);
                case EVENT -> throw new IllegalArgumentException("An event is no control: " + port.key());
            };
        }

        public Widget widget() {
            return Widget.of(kind).orElseThrow();
        }

        /** Whether the player the view was built for may change it; a control they may not change is shown read-only. */
        public boolean editable(DeviceView.Grants grants) {
            return DeviceConfig.permits(gate, grants);
        }

        /** The longest text a {@link Widget#TEXT_FIELD} takes, in code points. */
        public int maxLength() {
            return ((PortType.Text) kind).maxLength();
        }

        /** The values a {@link Widget#CYCLE} steps through, in order. */
        public List<String> values() {
            return ((PortType.Enum) kind).values();
        }

        /** The value a {@link Widget#CYCLE} shows after {@code current}: the next one, the first after the last or an unknown one. */
        public String next(String current) {
            List<String> values = values();
            return values.get((indexOf(current) + 1) % values.size());
        }

        /** The lowest value of a {@link Widget#SLIDER}. */
        public int min() {
            return kind instanceof PortType.Number number ? (int) number.min() : 0;
        }

        /** The highest value of a {@link Widget#SLIDER}. */
        public int max() {
            return kind instanceof PortType.Number number ? (int) number.max() : PortType.MAX_LEVEL;
        }

        /** What a {@link Widget#SLIDER}'s number counts, e.g. {@code s} or {@code blocks}; empty for a level or a bare number. */
        public Optional<String> unit() {
            return kind instanceof PortType.Number number ? Optional.ofNullable(number.unit()) : Optional.empty();
        }

        /** Whether a {@link Widget#TOGGLE} shows on in a device's state; off when the state has no readable value. */
        public boolean on(JsonObject state) {
            JsonElement value = state.get(key);
            return value != null && Coerce.bool(value).orElse(false);
        }

        /** Where a {@link Widget#SLIDER} stands in a device's state: its value within the bounds, else the lowest. */
        public int level(JsonObject state) {
            JsonElement value = state.get(key);
            Optional<BigDecimal> number = value == null ? Optional.empty() : Coerce.decimal(value);
            return number.map(decimal -> Math.clamp(decimal.intValue(), min(), max())).orElse(min());
        }

        /** The value a {@link Widget#CYCLE} shows in a device's state, as the kind spells it; the first one when it is unknown. */
        public String selected(JsonObject state) {
            JsonElement value = state.get(key);
            String name = value != null && value.isJsonPrimitive() ? value.getAsString() : "";
            List<String> values = values();
            return values.get(Math.max(indexOf(name), 0));
        }

        /** The text a {@link Widget#TEXT_FIELD} shows of a state: its value as text; empty when it has none. */
        public String text(JsonObject state) {
            JsonElement value = state.get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        }

        /** The value a {@link Widget#TOGGLE} sends: {@code ON} or {@code OFF}, never a toggle, so a lagging view cannot flip it twice. */
        public JsonElement toggled(boolean on) {
            return new JsonPrimitive(on ? "ON" : "OFF");
        }

        /** The value a {@link Widget#SLIDER} sends, kept within its bounds: the server refuses rather than clamps. */
        public JsonElement leveled(int value) {
            return new JsonPrimitive(Math.clamp(value, min(), max()));
        }

        /** The value a {@link Widget#PRESS} sends. */
        public JsonElement pressed() {
            return new JsonPrimitive(Coerce.PRESS);
        }

        /** The payload that sends {@code value} for this control of the device at {@code pos}. */
        public CustomPacketPayload send(BlockPos pos, JsonElement value) {
            return switch (payload) {
                case CONFIG -> new DeviceConfigPayload(pos, key, tag(value));
                case ACTION -> new DeviceActionPayload(pos, key, value.getAsString());
            };
        }

        private int indexOf(String value) {
            List<String> values = values();
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).equalsIgnoreCase(value)) return i;
            }
            return -1;
        }
    }

    /**
     * The generated tabs of a device of {@code type}, in tab-bar order: General, Controls when it has any, Twin when its
     * type takes part in the Home Assistant twin (M3 plan D19), then Advanced, which every device has for its links,
     * Access and Diagnostics.
     */
    public static List<MqttTab> forType(DeviceType type) {
        List<MqttTab> tabs = new ArrayList<>(List.of(new GeneralTab(type)));
        if (!ControlsTab.controls(type).isEmpty()) tabs.add(new ControlsTab(type));
        if (type.twin().isPresent()) tabs.add(new TwinTab(type));
        tabs.add(new AdvancedTab(type));
        tabs.add(new AccessTab(type));
        tabs.add(new DiagnosticsTab());
        return List.copyOf(tabs);
    }

    /**
     * The tabs of one player's screen of a device of {@code type}: the generated ones ({@link #forType}), or for a player
     * who may only use it (P2) its Controls tab alone; none for such a player when the type has no controls, so no screen
     * opens for them.
     *
     * @param controlsOnly whether the player may only use the device ({@link DeviceView#controlsOnly()})
     */
    public static List<MqttTab> forViewer(DeviceType type, boolean controlsOnly) {
        if (!controlsOnly) return forType(type);
        return ControlsTab.controls(type).isEmpty() ? List.of() : List.of(new ControlsTab(type));
    }

    /** A name as the server keeps it ({@link LinkSetting#NAME}): sanitised and stripped. */
    public static String name(String typed) {
        return ValueCodecs.sanitizeText(typed).strip();
    }

    /**
     * The slug a device gets when renamed to {@code typed}, as far as the client can tell: its own slug for its own name
     * or a blank one (refused), else the slug derived from the name, suffixed when it is reserved. The server also
     * suffixes a slug another device holds. Empty when the name gives no slug, so the server derives one from the type
     * and the device id.
     */
    public static Optional<String> slugPreview(String typed, DeviceView view) {
        String name = name(typed);
        if (name.isEmpty() || name.equals(view.name())) return Optional.of(view.slug());
        if (Slugs.fromName(name).isEmpty()) return Optional.empty();
        return Optional.of(Slugs.unique(name, slug -> false));
    }

    /** The exposure value a view shows: {@code on}, {@code off} or {@code default} ({@link LinkSetting#EXPOSE_VALUES}). */
    public static String exposeValue(DeviceView view) {
        return view.expose().map(expose -> expose ? "on" : "off").orElse("default");
    }

    /**
     * A control's value as the NBT of {@code DeviceConfigC2S}: whole numbers as ints (NBT's own conversion would make
     * every number a double), objects as compounds. The server reads it back as the same JSON.
     */
    static Tag tag(JsonElement value) {
        if (value.isJsonObject()) {
            CompoundTag compound = new CompoundTag();
            value.getAsJsonObject().entrySet().forEach(entry -> compound.put(entry.getKey(), tag(entry.getValue())));
            return compound;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return ByteTag.valueOf(primitive.getAsBoolean());
        if (primitive.isNumber()) {
            BigDecimal number = primitive.getAsBigDecimal();
            return number.stripTrailingZeros().scale() <= 0 ? IntTag.valueOf(number.intValueExact()) : DoubleTag.valueOf(number.doubleValue());
        }
        return StringTag.valueOf(primitive.getAsString());
    }
}

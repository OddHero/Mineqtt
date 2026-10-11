package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.ValueMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the Configurator's Copy-Paste mode copied from a device (design §7.4, M2 plan §4.2 M2-17): its Raw Link settings,
 * its type's settings, its Home Assistant exposure and its access mode, as the changes a player would send from the
 * device's screen, in the order a paste applies them. Pasting sends each one to another device of the same type as the
 * pasting player ({@link DeviceRuntime#paste}), so each is checked for them: CONFIGURE for the settings and the Raw Link,
 * the typed topic against the ACL for them and the device's owner, EXPOSE for the exposure and ACCESS for the access
 * mode, which goes last, as it changes who may configure the device. The twin binding is copied when the copier may
 * configure the device ({@link #of(DeviceType, DeviceView, Binding)}); each of its keys passes the twin gate again for the
 * paster. Never copied: the name (unique per device), the device's state, its group and its wires.
 * <p>
 * A Raw Link is copied as the device has it: its channel (private or not) or its typed topic, then whether it publishes
 * retained (its override, else its type's default, so the target ends up with the same), its QoS and custom payloads,
 * payloads it lacks as empty ones. A device without a Raw Link copies none, so a paste leaves the target's link as it is.
 *
 * @param type    the type of the device it was copied from; it pastes only onto devices of that type
 * @param source  the name of that device
 * @param entries the link setting or setting keys with their values, in the order they apply
 */
public record DeviceClipboard(Identifier type, String source, List<Entry> entries) {
    private static final String RAW_PREFIX = "raw.";

    public DeviceClipboard {
        entries = List.copyOf(entries);
    }

    /**
     * One change a paste sends.
     *
     * @param key   a link setting's key ({@link LinkSetting}) or a type setting's ({@link Setting})
     * @param value its value, as {@code DeviceConfigC2S} carries it
     */
    public record Entry(String key, JsonElement value) {
        /** Whether it changes the Raw Link. */
        public boolean raw() {
            return key.startsWith(RAW_PREFIX);
        }

        /** Whether it sets up the Raw Link itself (a channel or a topic), which the link's other settings need first. */
        public boolean link() {
            return key.equals(LinkSetting.RAW_CHANNEL.key()) || key.equals(LinkSetting.RAW_PRIVATE.key()) || key.equals(LinkSetting.RAW_TOPIC.key());
        }

        /** Whether it sets the twin binding's source, which the rows that shape the source and control need first. */
        public boolean twinSource() {
            return key.equals(LinkSetting.TWIN_ENTITY.key()) || key.equals(LinkSetting.TWIN_DEVICE.key());
        }

        /** Whether it shapes the binding's source or turns its control on or off, which needs the source. */
        public boolean twinShape() {
            return SOURCE_SHAPES.contains(key);
        }
    }

    private static final List<String> SOURCE_SHAPES = List.of(LinkSetting.TWIN_PATH.key(), LinkSetting.TWIN_INVERT.key(), LinkSetting.TWIN_MIN.key(),
            LinkSetting.TWIN_MAX.key(), LinkSetting.TWIN_DECIMALS.key(), LinkSetting.TWIN_UNAVAILABLE.key(), LinkSetting.TWIN_CONTROL.key());

    /** Copies a device of {@code type} as its screen shows it in {@code view}, without its twin binding. */
    public static DeviceClipboard of(DeviceType type, DeviceView view) {
        return of(type, view, null);
    }

    /**
     * Copies a device of {@code type} as its screen shows it in {@code view}, and its twin binding (M3 plan D13 #6), which
     * the view does not carry: each binding key goes through the twin gate again for the paster ({@link TwinRules#edit}),
     * the source before the rows that shape it and control last. A refused binding copies as what it names, so the paste
     * judges it anew. A device without a binding copies none, so a paste leaves the target's binding as it is.
     *
     * @param binding the device's binding, for a copier who may configure it; null to copy none
     */
    public static DeviceClipboard of(DeviceType type, DeviceView view, @Nullable Binding binding) {
        List<Entry> entries = new ArrayList<>();
        view.raw().ifPresent(raw -> {
            LinkSetting<String> link = raw.channel().isEmpty() ? LinkSetting.RAW_TOPIC : view.privateMode() ? LinkSetting.RAW_PRIVATE : LinkSetting.RAW_CHANNEL;
            add(type, entries, link, new JsonPrimitive(raw.channel().orElse(raw.topic())));
            // What the source does, also when it follows its type's default: a target's own override does not survive.
            type.rawLink().ifPresent(spec -> add(type, entries, LinkSetting.RAW_RETAIN, new JsonPrimitive(raw.retain().orElse(spec.retainByDefault()))));
            add(type, entries, LinkSetting.RAW_QOS, new JsonPrimitive(raw.qos()));
            add(type, entries, LinkSetting.RAW_ON, new JsonPrimitive(raw.on().orElse("")));
            add(type, entries, LinkSetting.RAW_OFF, new JsonPrimitive(raw.off().orElse("")));
        });
        JsonObject state = JsonParser.parseString(view.state()).getAsJsonObject();
        for (Setting<?> setting : type.settings()) {
            JsonElement value = state.get(setting.key());
            if (value != null) entries.add(new Entry(setting.key(), value.deepCopy()));
        }
        if (binding != null) twin(type, entries, binding);
        add(type, entries, LinkSetting.EXPOSE, new JsonPrimitive(view.expose().map(expose -> expose ? "on" : "off").orElse("default")));
        add(type, entries, LinkSetting.ACCESS, new JsonPrimitive(view.access().getSerializedName()));
        return new DeviceClipboard(type.id(), view.name(), entries);
    }

    // The binding keys the type has, in the order LinkSetting.twin lists them: the source (an entity, or an MQTT device
    // through its preset, M3-17), its shaping rows, control, targets.
    private static void twin(DeviceType type, List<Entry> entries, Binding binding) {
        Binding.Settings settings = binding.settings();
        Optional<Binding.PresetRef> preset = binding.preset();
        if (preset.isPresent()) add(type, entries, LinkSetting.TWIN_DEVICE, new JsonPrimitive(preset.get().setting()));
        else add(type, entries, LinkSetting.TWIN_ENTITY, new JsonPrimitive(binding.sourceEntity().orElse("")));
        if (binding.bound()) {
            add(type, entries, LinkSetting.TWIN_PATH, new JsonPrimitive(settings.path() == null ? "" : settings.path().toString()));
            add(type, entries, LinkSetting.TWIN_INVERT, new JsonPrimitive(settings.invert()));
            add(type, entries, LinkSetting.TWIN_MIN, new JsonPrimitive(settings.min() == null ? "" : settings.min().toPlainString()));
            add(type, entries, LinkSetting.TWIN_MAX, new JsonPrimitive(settings.max() == null ? "" : settings.max().toPlainString()));
            add(type, entries, LinkSetting.TWIN_DECIMALS, new JsonPrimitive(settings.decimals() == ValueMap.AUTO_DECIMALS ? "auto"
                    : Integer.toString(settings.decimals())));
            add(type, entries, LinkSetting.TWIN_UNAVAILABLE, new JsonPrimitive(settings.unavailable().key()));
            add(type, entries, LinkSetting.TWIN_CONTROL, new JsonPrimitive(binding.control()));
        }
        type.twin().ifPresent(adapter -> adapter.targets().forEach((name, port) -> {
            Endpoint target = binding.targets().get(port);
            add(type, entries, LinkSetting.target(name), new JsonPrimitive(target instanceof Endpoint.HaEntityRef entity ? entity.entityId() : ""));
        }));
    }

    // A link setting the type has; the Raw Link's retain and QoS only for outputs, the payloads only with custom payloads.
    private static void add(DeviceType type, List<Entry> entries, LinkSetting<?> setting, JsonElement value) {
        if (type.linkSetting(setting.key()).isPresent()) entries.add(new Entry(setting.key(), value));
    }
}

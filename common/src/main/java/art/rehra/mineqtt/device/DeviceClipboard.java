package art.rehra.mineqtt.device;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Configurator's Copy-Paste mode copied from a device (design §7.4, M2 plan §4.2 M2-17): its Raw Link settings,
 * its type's settings, its Home Assistant exposure and its access mode, as the changes a player would send from the
 * device's screen, in the order a paste applies them. Pasting sends each one to another device of the same type as the
 * pasting player ({@link DeviceRuntime#paste}), so each is checked for them: CONFIGURE for the settings and the Raw Link,
 * the typed topic against the ACL for them and the device's owner, EXPOSE for the exposure and ACCESS for the access
 * mode, which goes last, as it changes who may configure the device. Never copied: the name (unique per device), the
 * device's state, its group and its wires.
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
    }

    /** Copies a device of {@code type} as its screen shows it in {@code view}. */
    public static DeviceClipboard of(DeviceType type, DeviceView view) {
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
        add(type, entries, LinkSetting.EXPOSE, new JsonPrimitive(view.expose().map(expose -> expose ? "on" : "off").orElse("default")));
        add(type, entries, LinkSetting.ACCESS, new JsonPrimitive(view.access().getSerializedName()));
        return new DeviceClipboard(type.id(), view.name(), entries);
    }

    // A link setting the type has; the Raw Link's retain and QoS only for outputs, the payloads only with custom payloads.
    private static void add(DeviceType type, List<Entry> entries, LinkSetting<?> setting, JsonElement value) {
        if (type.linkSetting(setting.key()).isPresent()) entries.add(new Entry(setting.key(), value));
    }
}

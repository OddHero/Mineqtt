package art.rehra.mineqtt.device;

import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.twin.core.ValueMap;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a device's Twin tab shows one player (M3 plan D19): its binding to a Home Assistant entity or to an MQTT device
 * through a preset (M3-17), the health of its source and its control, and what the player may do with it. The server
 * sends it apart from the {@link DeviceView}, which has little room left (M2-11, M2-16), when the screen opens and
 * whenever the device's view changes. Binding details (entity ids, names, areas, an MQTT device's name, preset and
 * topic, the last answer of the control blueprint, a refusal naming an entity) reach only a viewer who may configure the
 * device (D13 #7, {@link #details()}); anyone else learns only that it is bound, to Home Assistant or to an MQTT device,
 * and how it is doing.
 *
 * @param details    whether the viewer may configure the device and so sees the binding's entities
 * @param source     the entity it mirrors, if bound to one; blank names for a viewer without details
 * @param device     the MQTT device it mirrors through a preset, if bound to one (M3-17); blank for a viewer without details
 * @param control    whether using it asks Home Assistant to change its source
 * @param settings   how it reads its source
 * @param targets    the entity each target switches, by the target's name ({@code target}, {@code left}); set ones only
 * @param health     how its source or its control is doing; empty while it has no binding
 * @param refused    why the rules refuse its binding now, for a viewer with details; empty otherwise
 * @param pending    whether a request waits for Home Assistant
 * @param paused     whether the circuit breaker paused its control
 * @param answer     the control blueprint's last answer to one of its requests, for a viewer with details
 * @param off        why the twin is off on this server (no allowed entities, no statestream base); empty while it is on
 * @param mayBind    whether the viewer holds {@code mineqtt:twin.bind}
 * @param mayControl whether the viewer holds {@code mineqtt:twin.control}
 * @param catalog    whether Home Assistant's catalog decides what may be bound (labels, M3 plan D3)
 * @param wires      the wires into the ports its source owns: with control they switch the source (D6)
 * @param pick       the binding key the player's picker should open for, once (the Configurator's Bind mode): a key of
 *                   the type, or {@code ""} to ask which of its targets first
 * @param rateRefused how many of its requests the rates refused since it was loaded or resumed;
 *                    names nothing, so every viewer sees it
 * @param rateLast    the last of them, its entity or topic and the rate's reason, for a viewer with details; {@code ""}
 *                    otherwise or while none was refused
 */
public record TwinView(BlockPos pos, boolean details, Optional<Entity> source, Optional<Device> device, boolean control, Settings settings,
                       Map<String, Entity> targets,
                       Optional<TwinHealth> health, String refused, boolean pending, boolean paused, Optional<String> answer, String off,
                       boolean mayBind, boolean mayControl, boolean catalog, int wires, Optional<String> pick, long rateRefused, String rateLast) {
    /** The longest friendly name a view carries (D4's picker row). */
    public static final int MAX_NAME_LENGTH = 64;
    /** The longest area name a view carries (D4's picker row). */
    public static final int MAX_AREA_LENGTH = 32;
    /** The longest answer status, as {@code TwinRequests} keeps it. */
    public static final int MAX_ANSWER_LENGTH = 32;
    /** The longest refusal or off reason. */
    public static final int MAX_REASON_LENGTH = Binding.MAX_REFUSAL_LENGTH;
    /** The longest preset id a view carries (M3-17). */
    public static final int MAX_PRESET_LENGTH = BindingPreset.MAX_ID_LENGTH;
    /** The longest MQTT device name a view carries (M3-17). */
    public static final int MAX_DEVICE_LENGTH = BindingPreset.MAX_DEVICE_BYTES;
    /** The longest state topic a view carries (M3-17). */
    public static final int MAX_TOPIC_LENGTH = 256;
    /** The longest preset kind a view carries (M3-17). */
    public static final int MAX_KIND_LENGTH = 32;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_SETTING_LENGTH = 64;

    public static final StreamCodec<ByteBuf, TwinView> STREAM_CODEC = StreamCodec.of(TwinView::write, TwinView::read);

    /**
     * An entity as the tab shows it.
     *
     * @param id   its entity id; {@code ""} for a viewer without details
     * @param name its friendly name as Home Assistant shares it; {@code ""} when unknown
     * @param area its area; {@code ""} when none or unknown
     */
    public record Entity(String id, String name, String area) {
        /** An entity the viewer may not see. */
        public static final Entity HIDDEN = new Entity("", "", "");

        public Entity {
            id = cap(id, Binding.MAX_REFERENCE_LENGTH);
            name = cap(name, MAX_NAME_LENGTH);
            area = cap(area, MAX_AREA_LENGTH);
        }

        /** What a row shows: the friendly name, else the id. */
        public String label() {
            return name.isEmpty() ? id : name;
        }
    }

    /**
     * An MQTT device as the tab shows it (M3-17): the preset it is read and switched through, and the device's name in its
     * topics.
     *
     * @param preset the preset's id, {@code mineqtt:zigbee2mqtt_light}; {@code ""} for a viewer without details
     * @param name   the preset's name as its file gives it; {@code ""} when the preset is not loaded, or hidden
     * @param device the device name ({@code {device}} in the preset's topics); {@code ""} when hidden
     * @param topic  the state topic it reads; {@code ""} when hidden
     * @param kind   the Home Assistant kind the preset makes it look like ({@code light}); {@code ""} when hidden
     */
    public record Device(String preset, String name, String device, String topic, String kind) {
        /** An MQTT device the viewer may not see. */
        public static final Device HIDDEN = new Device("", "", "", "", "");

        public Device {
            preset = cap(preset, MAX_PRESET_LENGTH);
            name = cap(name, MAX_NAME_LENGTH);
            device = cap(device, MAX_DEVICE_LENGTH);
            topic = cap(topic, MAX_TOPIC_LENGTH);
            kind = cap(kind, MAX_KIND_LENGTH);
        }

        /** What the preset cell shows: the preset's name, else its id. */
        public String presetLabel() {
            return name.isEmpty() ? preset : name;
        }
    }

    /**
     * How a binding reads its source, as the Twin tab's rows show and send them ({@link LinkSetting}'s text forms).
     *
     * @param path        the value path, {@code ""} for the type's default
     * @param min         {@code twin.min} as a plain number, {@code ""} for the entity's own
     * @param max         {@code twin.max} as a plain number, {@code ""} for the entity's own
     * @param decimals    one of {@link LinkSetting#TWIN_DECIMALS_VALUES}
     * @param unavailable {@code hold} or {@code off}
     */
    public record Settings(String path, boolean invert, String min, String max, String decimals, String unavailable) {
        public static final Settings DEFAULT = of(Binding.Settings.DEFAULT);

        public Settings {
            path = cap(path, MAX_SETTING_LENGTH);
            min = cap(min, MAX_SETTING_LENGTH);
            max = cap(max, MAX_SETTING_LENGTH);
            decimals = cap(decimals, MAX_SETTING_LENGTH);
            unavailable = cap(unavailable, MAX_SETTING_LENGTH);
        }

        public static Settings of(Binding.Settings settings) {
            return new Settings(settings.path() == null ? "" : settings.path().toString(), settings.invert(),
                    settings.min() == null ? "" : settings.min().toPlainString(), settings.max() == null ? "" : settings.max().toPlainString(),
                    settings.decimals() == ValueMap.AUTO_DECIMALS ? "auto" : Integer.toString(settings.decimals()), settings.unavailable().key());
        }
    }

    public TwinView {
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        if (targets.size() > Binding.MAX_TARGETS) throw new IllegalArgumentException("A twin view has more than " + Binding.MAX_TARGETS + " targets");
        refused = cap(refused, MAX_REASON_LENGTH);
        answer = answer.map(status -> cap(status, MAX_ANSWER_LENGTH));
        off = cap(off, MAX_REASON_LENGTH);
        if (wires < 0) throw new IllegalArgumentException("A twin view has a negative wire count");
        if (rateRefused < 0) throw new IllegalArgumentException("A twin view has a negative refusal count");
        rateLast = cap(rateLast, MAX_REASON_LENGTH);
        if (!details && !rateLast.isEmpty()) throw new IllegalArgumentException("A twin view without details names no refused request");
        if (source.isPresent() && device.isPresent()) throw new IllegalArgumentException("A twin view has one source: an entity or an MQTT device");
        if (!details && (source.filter(entity -> !entity.equals(Entity.HIDDEN)).isPresent() || device.filter(known -> !known.equals(Device.HIDDEN)).isPresent()
                || targets.values().stream().anyMatch(entity -> !entity.equals(Entity.HIDDEN)) || !refused.isEmpty() || answer.isPresent())) {
            throw new IllegalArgumentException("A twin view without details names no entity and no MQTT device");
        }
    }

    /** Whether the device mirrors a source: a Home Assistant entity or an MQTT device (M3-17). */
    public boolean bound() {
        return source.isPresent() || device.isPresent();
    }

    /** Whether the device mirrors an MQTT device through a preset (M3-17). */
    public boolean boundToDevice() {
        return device.isPresent();
    }

    /** The entity a binding key shows: the source for {@code twin.entity}, else the target the key names; empty when unset. */
    public Optional<Entity> entity(String key) {
        if (key.equals(LinkSetting.TWIN_ENTITY.key())) return source;
        if (!key.startsWith(LinkSetting.TWIN_PREFIX)) return Optional.empty();
        return Optional.ofNullable(targets.get(key.substring(LinkSetting.TWIN_PREFIX.length())));
    }

    /** This view without the picker request, as the tab keeps it after opening the picker. */
    public TwinView withoutPick() {
        return pick.isEmpty() ? this : new TwinView(pos, details, source, device, control, settings, targets, health, refused, pending, paused, answer, off,
                mayBind, mayControl, catalog, wires, Optional.empty(), rateRefused, rateLast);
    }

    /** {@code text}, or its start when it has more than {@code max} characters; never splits a surrogate pair. */
    static String cap(String text, int max) {
        if (text.length() <= max) return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static final StreamCodec<ByteBuf, String> ID = ByteBufCodecs.stringUtf8(Binding.MAX_REFERENCE_LENGTH);
    private static final StreamCodec<ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH);
    private static final StreamCodec<ByteBuf, String> AREA = ByteBufCodecs.stringUtf8(MAX_AREA_LENGTH);
    private static final StreamCodec<ByteBuf, String> KEY = ByteBufCodecs.stringUtf8(MAX_KEY_LENGTH);
    private static final StreamCodec<ByteBuf, String> SETTING = ByteBufCodecs.stringUtf8(MAX_SETTING_LENGTH);
    private static final StreamCodec<ByteBuf, String> REASON = ByteBufCodecs.stringUtf8(MAX_REASON_LENGTH);
    private static final StreamCodec<ByteBuf, String> ANSWER = ByteBufCodecs.stringUtf8(MAX_ANSWER_LENGTH);
    private static final StreamCodec<ByteBuf, String> PRESET = ByteBufCodecs.stringUtf8(MAX_PRESET_LENGTH);
    private static final StreamCodec<ByteBuf, String> DEVICE = ByteBufCodecs.stringUtf8(MAX_DEVICE_LENGTH);
    private static final StreamCodec<ByteBuf, String> TOPIC = ByteBufCodecs.stringUtf8(MAX_TOPIC_LENGTH);
    private static final StreamCodec<ByteBuf, String> KIND = ByteBufCodecs.stringUtf8(MAX_KIND_LENGTH);

    private static void writeEntity(ByteBuf buf, Entity entity) {
        ID.encode(buf, entity.id());
        NAME.encode(buf, entity.name());
        AREA.encode(buf, entity.area());
    }

    private static Entity readEntity(ByteBuf buf) {
        return new Entity(ID.decode(buf), NAME.decode(buf), AREA.decode(buf));
    }

    private static void writeDevice(ByteBuf buf, Device device) {
        PRESET.encode(buf, device.preset());
        NAME.encode(buf, device.name());
        DEVICE.encode(buf, device.device());
        TOPIC.encode(buf, device.topic());
        KIND.encode(buf, device.kind());
    }

    private static Device readDevice(ByteBuf buf) {
        return new Device(PRESET.decode(buf), NAME.decode(buf), DEVICE.decode(buf), TOPIC.decode(buf), KIND.decode(buf));
    }

    private static void write(ByteBuf buf, TwinView view) {
        BlockPos.STREAM_CODEC.encode(buf, view.pos);
        buf.writeBoolean(view.details);
        buf.writeBoolean(view.source.isPresent());
        view.source.ifPresent(entity -> writeEntity(buf, entity));
        buf.writeBoolean(view.device.isPresent());
        view.device.ifPresent(device -> writeDevice(buf, device));
        buf.writeBoolean(view.control);
        Settings settings = view.settings;
        SETTING.encode(buf, settings.path());
        buf.writeBoolean(settings.invert());
        SETTING.encode(buf, settings.min());
        SETTING.encode(buf, settings.max());
        SETTING.encode(buf, settings.decimals());
        SETTING.encode(buf, settings.unavailable());
        VarInt.write(buf, view.targets.size());
        view.targets.forEach((name, entity) -> {
            KEY.encode(buf, name);
            writeEntity(buf, entity);
        });
        VarInt.write(buf, view.health.map(health -> health.ordinal() + 1).orElse(0));
        REASON.encode(buf, view.refused);
        buf.writeBoolean(view.pending);
        buf.writeBoolean(view.paused);
        buf.writeBoolean(view.answer.isPresent());
        view.answer.ifPresent(status -> ANSWER.encode(buf, status));
        REASON.encode(buf, view.off);
        buf.writeBoolean(view.mayBind);
        buf.writeBoolean(view.mayControl);
        buf.writeBoolean(view.catalog);
        VarInt.write(buf, view.wires);
        buf.writeBoolean(view.pick.isPresent());
        view.pick.ifPresent(key -> KEY.encode(buf, key));
        VarLong.write(buf, view.rateRefused);
        REASON.encode(buf, view.rateLast);
    }

    private static TwinView read(ByteBuf buf) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
        boolean details = buf.readBoolean();
        Optional<Entity> source = buf.readBoolean() ? Optional.of(readEntity(buf)) : Optional.empty();
        Optional<Device> device = buf.readBoolean() ? Optional.of(readDevice(buf)) : Optional.empty();
        boolean control = buf.readBoolean();
        Settings settings = new Settings(SETTING.decode(buf), buf.readBoolean(), SETTING.decode(buf), SETTING.decode(buf), SETTING.decode(buf),
                SETTING.decode(buf));
        int count = VarInt.read(buf);
        if (count < 0 || count > Binding.MAX_TARGETS) throw new DecoderException("A twin view has " + count + " targets");
        Map<String, Entity> targets = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) targets.put(KEY.decode(buf), readEntity(buf));
        int health = VarInt.read(buf);
        if (health < 0 || health > TwinHealth.values().length) throw new DecoderException("A twin view has an unknown health");
        String refused = REASON.decode(buf);
        boolean pending = buf.readBoolean();
        boolean paused = buf.readBoolean();
        Optional<String> answer = buf.readBoolean() ? Optional.of(ANSWER.decode(buf)) : Optional.empty();
        String off = REASON.decode(buf);
        boolean mayBind = buf.readBoolean();
        boolean mayControl = buf.readBoolean();
        boolean catalog = buf.readBoolean();
        int wires = VarInt.read(buf);
        Optional<String> pick = buf.readBoolean() ? Optional.of(KEY.decode(buf)) : Optional.empty();
        long rateRefused = VarLong.read(buf);
        String rateLast = REASON.decode(buf);
        try {
            return new TwinView(pos, details, source, device, control, settings, targets, health == 0 ? Optional.empty() : Optional.of(TwinHealth.values()[health - 1]),
                    refused, pending, paused, answer, off, mayBind, mayControl, catalog, wires, pick, rateRefused, rateLast);
        } catch (IllegalArgumentException e) {
            throw new DecoderException(e.getMessage());
        }
    }
}

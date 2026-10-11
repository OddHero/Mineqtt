package art.rehra.mineqtt.device;

import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.twin.BindingCodecs;
import art.rehra.mineqtt.twin.core.Binding;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * What the registry knows about one device, loaded or not: where it is, its name and slug (the registry is
 * authoritative for both), its owner and access mode, its group, the commands queued while it is unloaded, the
 * state it last published, its Home Assistant discovery (what was last published, and a v1 unique_id it keeps) and a copy
 * of its twin binding. Position, slug and group change only through {@link DeviceRegistry}, which keeps its indexes in
 * step. Server thread only.
 * <p>
 * <b>The codec is at its limit.</b> A DFU {@code RecordCodecBuilder} takes at most 16 fields in one {@code group()}, and
 * {@link #CODEC} has 16 (M3 plan D14). A field is one {@link MapCodec}, not necessarily one key: the Home Assistant
 * discovery state is one field of two flat keys ({@code discoveryHash}, {@code discoveryCleanup}, M3-7b), so the
 * compound stays flat and a record without the new key saves as before. A later key joins such a group (or a new one
 * built from neighbouring fields); every later twin field nests inside the {@code twin} compound ({@link BindingCodecs}).
 */
public final class DeviceRecord {
    // The discovery state: two flat keys of the record's own compound, one field of CODEC's group().
    private record DiscoveryState(Optional<String> hash, boolean cleanup) {
    }

    private static final MapCodec<DiscoveryState> DISCOVERY = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.optionalFieldOf("discoveryHash").forGetter(DiscoveryState::hash),
            // Written only while true: a record without a pending cleanup saves exactly as before M3-7b.
            Codec.BOOL.optionalFieldOf("discoveryCleanup", false).forGetter(DiscoveryState::cleanup)
    ).apply(instance, DiscoveryState::new));

    public static final Codec<DeviceRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(DeviceRecord::id),
            Identifier.CODEC.fieldOf("type").forGetter(DeviceRecord::type),
            GlobalPos.CODEC.fieldOf("pos").forGetter(DeviceRecord::pos),
            Codec.STRING.fieldOf("name").forGetter(DeviceRecord::name),
            Codec.STRING.fieldOf("slug").forGetter(DeviceRecord::slug),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(r -> Optional.ofNullable(r.owner)),
            AccessMode.CODEC.optionalFieldOf("access", AccessMode.DEFAULT).forGetter(DeviceRecord::access),
            Codec.BOOL.optionalFieldOf("expose").forGetter(r -> Optional.ofNullable(r.expose)),
            UUIDUtil.CODEC.optionalFieldOf("group").forGetter(r -> Optional.ofNullable(r.group)),
            JsonCodecs.OBJECT.optionalFieldOf("pending").forGetter(r -> Optional.ofNullable(r.pending)),
            JsonCodecs.OBJECT.optionalFieldOf("lastState").forGetter(r -> Optional.ofNullable(r.lastState)),
            DISCOVERY.forGetter(r -> new DiscoveryState(Optional.ofNullable(r.discoveryHash), r.discoveryCleanup)),
            Codec.STRING.optionalFieldOf("legacyUniqueId").forGetter(r -> Optional.ofNullable(r.legacyUniqueId)),
            Codec.STRING.optionalFieldOf("legacyTopic").forGetter(r -> Optional.ofNullable(r.legacyTopic)),
            Codec.STRING.optionalFieldOf("v1Topic").forGetter(r -> Optional.ofNullable(r.v1Topic)),
            // The 16th field, the last one DFU allows: see the class note.
            BindingCodecs.CODEC.lenientOptionalFieldOf("twin").forGetter(r -> Optional.ofNullable(r.twin))
    ).apply(instance, (id, type, pos, name, slug, owner, access, expose, group, pending, lastState, discovery, legacyUniqueId, legacyTopic, v1Topic,
                       twin) -> {
        DeviceRecord record = new DeviceRecord(id, type, pos, name, slug);
        record.owner = owner.orElse(null);
        record.access = access;
        record.expose = expose.orElse(null);
        record.group = group.orElse(null);
        record.pending = pending.orElse(null);
        record.lastState = lastState.orElse(null);
        record.discoveryHash = discovery.hash().orElse(null);
        record.discoveryCleanup = discovery.cleanup();
        record.legacyUniqueId = legacyUniqueId.orElse(null);
        record.legacyTopic = legacyTopic.orElse(null);
        record.v1Topic = v1Topic.orElse(null);
        record.twin = twin.orElse(null);
        return record;
    }));

    private final UUID id;
    private final Identifier type;
    private final GlobalPos pos;
    private String name;
    private String slug;
    private @Nullable UUID owner;
    private AccessMode access = AccessMode.DEFAULT;
    private @Nullable Boolean expose;
    private @Nullable UUID group;
    private @Nullable JsonObject pending;
    private @Nullable JsonObject lastState;
    private @Nullable String discoveryHash;
    private boolean discoveryCleanup;
    private @Nullable String legacyUniqueId;
    private @Nullable String legacyTopic;
    private @Nullable String v1Topic;
    private @Nullable Binding twin;

    public DeviceRecord(UUID id, Identifier type, GlobalPos pos, String name, String slug) {
        this.id = id;
        this.type = type;
        this.pos = pos;
        this.name = name;
        this.slug = slug;
    }

    public UUID id() {
        return id;
    }

    /** The device's object id in Home Assistant and the stem of its entity unique_ids: {@code mineqtt_<uuid32>}. */
    public String objectId() {
        return objectId(id);
    }

    public static String objectId(UUID id) {
        return "mineqtt_" + id.toString().replace("-", "");
    }

    public Identifier type() {
        return type;
    }

    public GlobalPos pos() {
        return pos;
    }

    public String name() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    public String slug() {
        return slug;
    }

    void setSlug(String slug) {
        this.slug = slug;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    /** The device's access mode, mirrored from its block entity so it also applies while the device is unloaded. */
    public AccessMode access() {
        return access;
    }

    public void setAccess(AccessMode access) {
        this.access = access;
    }

    /** The explicit Home Assistant exposure; null follows {@code ha.expose}. */
    public @Nullable Boolean expose() {
        return expose;
    }

    public void setExpose(@Nullable Boolean expose) {
        this.expose = expose;
    }

    public @Nullable UUID group() {
        return group;
    }

    void setGroup(@Nullable UUID group) {
        this.group = group;
    }

    /** The partial command queued while the device is unloaded; applied on its next attach. */
    public @Nullable JsonObject pending() {
        return pending;
    }

    public void setPending(@Nullable JsonObject pending) {
        this.pending = pending == null || pending.isEmpty() ? null : pending;
    }

    /** The state the device last published; what an unloaded device publishes. */
    public @Nullable JsonObject lastState() {
        return lastState;
    }

    public void setLastState(@Nullable JsonObject lastState) {
        this.lastState = lastState;
    }

    /** The hash of the discovery message last published for the device; null when none is retained. */
    public @Nullable String discoveryHash() {
        return discoveryHash;
    }

    public void setDiscoveryHash(@Nullable String discoveryHash) {
        this.discoveryHash = discoveryHash;
    }

    /**
     * Whether Home Assistant may still hold entities this device no longer declares (M3-7b): its binding changed while
     * Home Assistant was offline or not known to be online, and Home Assistant applies a removal only to a component it
     * discovered in its current run. The next Home Assistant birth then publishes the full device once and the removal
     * after it ({@code DiscoveryPublisher}). Stored, so a server restart keeps it.
     */
    public boolean discoveryCleanup() {
        return discoveryCleanup;
    }

    public void setDiscoveryCleanup(boolean discoveryCleanup) {
        this.discoveryCleanup = discoveryCleanup;
    }

    /**
     * The unique_id v1 gave the device's light ({@code sanitize(topic)}); its primary entity keeps it, so Home
     * Assistant keeps the entity and its history. Null for devices that did not exist in v1.
     */
    public @Nullable String legacyUniqueId() {
        return legacyUniqueId;
    }

    public void setLegacyUniqueId(@Nullable String legacyUniqueId) {
        this.legacyUniqueId = legacyUniqueId;
    }

    /**
     * The v1 topic the v1 topic states listed this device's position under (design §9.2), until its lamp takes the
     * record over; only a lamp whose own v1 topic is this one does. Null for every other device.
     */
    public @Nullable String legacyTopic() {
        return legacyTopic;
    }

    public void setLegacyTopic(@Nullable String legacyTopic) {
        this.legacyTopic = legacyTopic;
    }

    /**
     * The v1 topic a v1 lamp or remote was adopted with, which M2 plan D15 matches a v1 remote's lamps by: a lamp keeps
     * it, so a remote finds it also while it is unloaded; a remote keeps it until it is wired to those lamps or its v1
     * commands are dropped. Null for every other device.
     */
    public @Nullable String v1Topic() {
        return v1Topic;
    }

    public void setV1Topic(@Nullable String v1Topic) {
        this.v1Topic = v1Topic;
    }

    /**
     * The device's twin binding as its block last had it (M3 plan D14): the block is authoritative, the copy serves what
     * needs it while the device is unloaded ({@code devices list}, links, status, and the route of a wire's message into
     * an unloaded mirrored device). Null for an unbound device.
     */
    public @Nullable Binding twin() {
        return twin;
    }

    public void setTwin(@Nullable Binding twin) {
        this.twin = twin;
    }
}

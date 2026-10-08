package art.rehra.mineqtt.device;

import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
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
 * state it last published and its Home Assistant discovery (what was last published, and a v1 unique_id it keeps). Position, slug and group change only through {@link DeviceRegistry}, which keeps its
 * indexes in step. Server thread only.
 */
public final class DeviceRecord {
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
            Codec.STRING.optionalFieldOf("discoveryHash").forGetter(r -> Optional.ofNullable(r.discoveryHash)),
            Codec.STRING.optionalFieldOf("legacyUniqueId").forGetter(r -> Optional.ofNullable(r.legacyUniqueId)),
            Codec.STRING.optionalFieldOf("legacyTopic").forGetter(r -> Optional.ofNullable(r.legacyTopic))
    ).apply(instance, (id, type, pos, name, slug, owner, access, expose, group, pending, lastState, discoveryHash, legacyUniqueId, legacyTopic) -> {
        DeviceRecord record = new DeviceRecord(id, type, pos, name, slug);
        record.owner = owner.orElse(null);
        record.access = access;
        record.expose = expose.orElse(null);
        record.group = group.orElse(null);
        record.pending = pending.orElse(null);
        record.lastState = lastState.orElse(null);
        record.discoveryHash = discoveryHash.orElse(null);
        record.legacyUniqueId = legacyUniqueId.orElse(null);
        record.legacyTopic = legacyTopic.orElse(null);
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
    private @Nullable String legacyUniqueId;
    private @Nullable String legacyTopic;

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
}

package art.rehra.mineqtt.device;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Actuators of one type that share one state and are one Home Assistant device (design D1). A command to the group
 * fans out to every member; joining means inheriting the group's state. Members and slug change only through
 * {@link DeviceRegistry}. Server thread only.
 */
public final class DeviceGroup {
    public static final Codec<DeviceGroup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(DeviceGroup::id),
            Codec.STRING.fieldOf("name").forGetter(DeviceGroup::name),
            Codec.STRING.fieldOf("slug").forGetter(DeviceGroup::slug),
            Identifier.CODEC.optionalFieldOf("memberType").forGetter(g -> Optional.ofNullable(g.memberType)),
            UUIDUtil.CODEC_LINKED_SET.optionalFieldOf("members", Set.of()).forGetter(DeviceGroup::members),
            JsonCodecs.OBJECT.optionalFieldOf("state").forGetter(g -> Optional.of(g.state).filter(s -> !s.isEmpty())),
            Codec.BOOL.optionalFieldOf("exposeMembers", false).forGetter(DeviceGroup::exposeMembers),
            Codec.STRING.optionalFieldOf("discoveryHash").forGetter(g -> Optional.ofNullable(g.discoveryHash)),
            Codec.STRING.optionalFieldOf("legacyUniqueId").forGetter(g -> Optional.ofNullable(g.legacyUniqueId)),
            Codec.STRING.optionalFieldOf("legacyTopic").forGetter(g -> Optional.ofNullable(g.legacyTopic))
    ).apply(instance, (id, name, slug, memberType, members, state, exposeMembers, discoveryHash, legacyUniqueId, legacyTopic) -> {
        DeviceGroup group = new DeviceGroup(id, name, slug);
        group.memberType = memberType.orElse(null);
        group.members.addAll(members);
        group.state = state.orElseGet(JsonObject::new);
        group.exposeMembers = exposeMembers;
        group.discoveryHash = discoveryHash.orElse(null);
        group.legacyUniqueId = legacyUniqueId.orElse(null);
        group.legacyTopic = legacyTopic.orElse(null);
        return group;
    }));

    private final UUID id;
    private String name;
    private String slug;
    private @Nullable Identifier memberType;
    private final Set<UUID> members = new LinkedHashSet<>();
    private JsonObject state = new JsonObject();
    private boolean exposeMembers;
    private @Nullable String discoveryHash;
    private @Nullable String legacyUniqueId;
    private @Nullable String legacyTopic;

    public DeviceGroup(UUID id, String name, String slug) {
        this.id = id;
        this.name = name;
        this.slug = slug;
    }

    public UUID id() {
        return id;
    }

    public String objectId() {
        return DeviceRecord.objectId(id);
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

    /** The type every member has; null until the first member joins. */
    public @Nullable Identifier memberType() {
        return memberType;
    }

    void setMemberType(@Nullable Identifier memberType) {
        this.memberType = memberType;
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    Set<UUID> mutableMembers() {
        return members;
    }

    /** The writable fields every member shares; empty until the group has a state. */
    public JsonObject state() {
        return state;
    }

    public void setState(JsonObject state) {
        this.state = state;
    }

    /** Whether members are discovered as devices of their own next to the group (default: hidden). */
    public boolean exposeMembers() {
        return exposeMembers;
    }

    /** The hash of the discovery message last published for the group; null when none is retained. */
    public @Nullable String discoveryHash() {
        return discoveryHash;
    }

    public void setDiscoveryHash(@Nullable String discoveryHash) {
        this.discoveryHash = discoveryHash;
    }

    /** The unique_id v1 gave the light of the topic this group replaces; its primary entity keeps it. */
    public @Nullable String legacyUniqueId() {
        return legacyUniqueId;
    }

    public void setLegacyUniqueId(@Nullable String legacyUniqueId) {
        this.legacyUniqueId = legacyUniqueId;
    }

    /**
     * The v1 topic whose lamps this group replaces ({@code legacy:<topic>}, design §9.2): the group follows it as its
     * Raw Link, once for all members, echoes its state to {@code <topic>/state}, and a lamp linked to the topic joins
     * the group. Null for groups that did not exist in v1.
     */
    public @Nullable String legacyTopic() {
        return legacyTopic;
    }

    public void setLegacyTopic(@Nullable String legacyTopic) {
        this.legacyTopic = legacyTopic;
    }
}

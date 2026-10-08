package art.rehra.mineqtt.device;

import art.rehra.mineqtt.config.ConfigValidation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Everything MineQTT knows about a world that is not chunk data (design §6.7): the bridge identity, every device
 * with its position and slug, the groups, and how far the migration of the world's v1 data got. Saved to {@code devices.nbt} by {@link RegistryStore}; every change
 * marks it dirty. Server thread only.
 */
public final class DeviceRegistry {
    /**
     * The version of the file layout. A file with a newer schema is not read. {@code schema}, {@code bridgeId} and
     * {@code worldUuid} keep their keys and types in every schema, so an older MineQTT still finds the bridge identity.
     */
    public static final int SCHEMA = 1;
    // What v1 made of a topic for its unique_id; the migration publishes on topics built from it.
    private static final Pattern LEGACY_UNIQUE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final String generatedBridgeId;
    private final UUID worldUuid;
    private final Map<UUID, DeviceRecord> records = new LinkedHashMap<>();
    private final Map<String, DeviceRecord> slugs = new HashMap<>();
    private final Map<GlobalPos, DeviceRecord> positions = new HashMap<>();
    // Dimension -> packed chunk position -> the devices in that chunk, for the prune pass of a chunk that loads.
    private final Map<ResourceKey<Level>, Map<Long, Set<DeviceRecord>>> chunks = new HashMap<>();
    private final Map<UUID, DeviceGroup> groups = new LinkedHashMap<>();
    private final Map<String, DeviceGroup> groupSlugs = new HashMap<>();
    // v1 unique_ids whose old discovery message was replaced by the device discovery (design §9.3).
    private final Set<String> legacyDiscoveryMigrated = new LinkedHashSet<>();
    private boolean legacyImportDone;
    private boolean v1StatusCleared;
    private boolean dirty;

    public DeviceRegistry(String generatedBridgeId, UUID worldUuid) {
        if (!ConfigValidation.isValidBridgeId(generatedBridgeId) || generatedBridgeId.isEmpty()) {
            throw new IllegalArgumentException("Invalid bridge id: " + generatedBridgeId);
        }
        this.generatedBridgeId = generatedBridgeId;
        this.worldUuid = worldUuid;
    }

    /** The bridge id generated for this world on its first start; used when the config sets none. */
    public String generatedBridgeId() {
        return generatedBridgeId;
    }

    /** The world's identity in Home Assistant (the bridge device) and in {@code bridge/info}. */
    public UUID worldUuid() {
        return worldUuid;
    }

    public Collection<DeviceRecord> records() {
        return Collections.unmodifiableCollection(records.values());
    }

    public @Nullable DeviceRecord record(UUID id) {
        return records.get(id);
    }

    public @Nullable DeviceRecord bySlug(String slug) {
        return slugs.get(slug);
    }

    public @Nullable DeviceRecord at(GlobalPos pos) {
        return positions.get(pos);
    }

    /** The devices registered in one chunk. */
    public List<DeviceRecord> inChunk(ResourceKey<Level> dimension, ChunkPos chunk) {
        Map<Long, Set<DeviceRecord>> byChunk = chunks.get(dimension);
        Set<DeviceRecord> inChunk = byChunk == null ? null : byChunk.get(chunk.pack());
        return inChunk == null ? List.of() : List.copyOf(inChunk);
    }

    /** {@code wanted}, or the first free {@code wanted_2}, {@code wanted_3}, … among the other devices. */
    public String uniqueSlug(String wanted, @Nullable UUID self) {
        return Slugs.unique(wanted, slug -> {
            DeviceRecord holder = slugs.get(slug);
            return holder != null && !holder.id().equals(self);
        });
    }

    /**
     * @throws IllegalArgumentException when the id, slug or position is already registered, or the slug is not a
     *                                  device slug
     */
    public void add(DeviceRecord record) {
        if (records.containsKey(record.id())) throw new IllegalArgumentException("Device already registered: " + record.id());
        if (!Slugs.isDeviceSlug(record.slug()) || slugs.containsKey(record.slug())) {
            throw new IllegalArgumentException("Slug not available: " + record.slug());
        }
        if (positions.containsKey(record.pos())) throw new IllegalArgumentException("Position already registered: " + record.pos());
        records.put(record.id(), record);
        slugs.put(record.slug(), record);
        index(record);
        markDirty();
    }

    /** @throws IllegalArgumentException when the slug is taken by another device or is not a device slug */
    public void rename(DeviceRecord record, String name, String slug) {
        DeviceRecord holder = slugs.get(slug);
        if (!Slugs.isDeviceSlug(slug) || (holder != null && holder != record)) {
            throw new IllegalArgumentException("Slug not available: " + slug);
        }
        slugs.remove(record.slug());
        record.setName(name);
        record.setSlug(slug);
        slugs.put(slug, record);
        markDirty();
    }

    /** Forgets a device, also as a group member. */
    public @Nullable DeviceRecord remove(UUID id) {
        DeviceRecord record = records.remove(id);
        if (record == null) return null;
        leaveGroup(record);
        slugs.remove(record.slug());
        unindex(record);
        markDirty();
        return record;
    }

    public Collection<DeviceGroup> groups() {
        return Collections.unmodifiableCollection(groups.values());
    }

    public @Nullable DeviceGroup group(UUID id) {
        return groups.get(id);
    }

    public @Nullable DeviceGroup groupBySlug(String slug) {
        return groupSlugs.get(slug);
    }

    /** A new, empty group; its slug comes from the name and is unique among the groups. */
    public DeviceGroup createGroup(UUID id, String name) {
        String slug = Slugs.unique(Slugs.fromName(name), groupSlugs::containsKey);
        DeviceGroup group = new DeviceGroup(id, name, slug);
        groups.put(id, group);
        groupSlugs.put(slug, group);
        markDirty();
        return group;
    }

    /** Deletes a group; its members stay as devices of their own. */
    public void removeGroup(DeviceGroup group) {
        if (groups.remove(group.id()) == null) return;
        groupSlugs.remove(group.slug());
        for (UUID member : group.members()) {
            DeviceRecord record = records.get(member);
            if (record != null) record.setGroup(null);
        }
        markDirty();
    }

    /**
     * Makes {@code record} a member of {@code group}, leaving its previous group. The first member decides the
     * group's member type.
     *
     * @throws IllegalArgumentException when the device's type differs from the group's member type
     */
    public void joinGroup(DeviceGroup group, DeviceRecord record) {
        if (group.memberType() != null && !group.memberType().equals(record.type())) {
            throw new IllegalArgumentException("Group " + group.slug() + " holds " + group.memberType() + " devices, not " + record.type());
        }
        leaveGroup(record);
        group.setMemberType(record.type());
        group.mutableMembers().add(record.id());
        record.setGroup(group.id());
        markDirty();
    }

    /** Takes {@code record} out of its group, if it has one. An emptied group keeps its member type and state. */
    public void leaveGroup(DeviceRecord record) {
        if (record.group() == null) return;
        DeviceGroup group = groups.get(record.group());
        if (group != null) group.mutableMembers().remove(record.id());
        record.setGroup(null);
        markDirty();
    }

    /** Whether the v1 discovery message of {@code legacyUniqueId} was replaced and cleared (§9.3 step 4). */
    public boolean legacyDiscoveryMigrated(String legacyUniqueId) {
        return legacyDiscoveryMigrated.contains(legacyUniqueId);
    }

    public void markLegacyDiscoveryMigrated(String legacyUniqueId) {
        if (legacyDiscoveryMigrated.add(legacyUniqueId)) markDirty();
    }

    /** Whether the v1 per-topic states ({@code mineqtt_topic_states.json}) were imported; they are read once (§9.2). */
    public boolean legacyImportDone() {
        return legacyImportDone;
    }

    public void setLegacyImportDone() {
        if (!legacyImportDone) markDirty();
        legacyImportDone = true;
    }

    /** Whether the retained {@code Offline} v1 left on its status topic was cleared (§9.3). */
    public boolean v1StatusCleared() {
        return v1StatusCleared;
    }

    public void setV1StatusCleared() {
        if (!v1StatusCleared) markDirty();
        v1StatusCleared = true;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    void markSaved() {
        dirty = false;
    }

    /** The registry as the tag {@link RegistryStore} writes. */
    public CompoundTag encode() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        tag.putString("bridgeId", generatedBridgeId);
        tag.store("worldUuid", UUIDUtil.CODEC, worldUuid);
        tag.put("devices", encodeAll(DeviceRecord.CODEC, records.values()));
        tag.put("groups", encodeAll(DeviceGroup.CODEC, groups.values()));
        ListTag migrated = new ListTag();
        legacyDiscoveryMigrated.forEach(id -> migrated.add(StringTag.valueOf(id)));
        tag.put("legacyDiscoveryMigrated", migrated);
        tag.putBoolean("legacyImportDone", legacyImportDone);
        tag.putBoolean("v1StatusCleared", v1StatusCleared);
        return tag;
    }

    /**
     * Reads a tag written by {@link #encode()}. A device or group that cannot be read, or that clashes with one read
     * before it (same id, slug or position), is skipped and reported; the rest of the registry survives it.
     *
     * @param problems receives one message per skipped or repaired entry
     * @throws IllegalArgumentException when the tag is not a registry at all, or is of a newer schema
     */
    public static DeviceRegistry decode(CompoundTag tag, Consumer<String> problems) {
        int schema = tag.getInt("schema").orElseThrow(() -> new IllegalArgumentException("No schema version"));
        if (schema < 1 || schema > SCHEMA) throw new IllegalArgumentException("Unsupported schema version " + schema + " (this MineQTT reads up to " + SCHEMA + ")");
        String bridgeId = tag.getString("bridgeId").orElseThrow(() -> new IllegalArgumentException("No bridge id"));
        UUID worldUuid = tag.read("worldUuid", UUIDUtil.CODEC).orElseThrow(() -> new IllegalArgumentException("No world UUID"));
        DeviceRegistry registry = new DeviceRegistry(bridgeId, worldUuid);
        for (DeviceRecord record : decodeAll(DeviceRecord.CODEC, tag.getListOrEmpty("devices"), "device", problems)) {
            if (registry.records.containsKey(record.id()) || registry.positions.containsKey(record.pos())) {
                problems.accept("Skipped device " + record.id() + ": its id or position is registered twice");
                continue;
            }
            if (!Slugs.isDeviceSlug(record.slug()) || registry.slugs.containsKey(record.slug())) {
                String slug = registry.uniqueSlug(Slugs.fromName(record.slug()), record.id());
                problems.accept("Device " + record.id() + " had slug '" + record.slug() + "', which is taken or invalid; it is now '" + slug + "'");
                record.setSlug(slug);
            }
            if (!isLegacyUniqueId(record.legacyUniqueId())) {
                problems.accept("Device " + record.id() + " had v1 unique_id '" + record.legacyUniqueId() + "', which v1 never produced; its v1 discovery is not migrated");
                record.setLegacyUniqueId(null);
            }
            registry.add(record);
        }
        for (DeviceGroup group : decodeAll(DeviceGroup.CODEC, tag.getListOrEmpty("groups"), "group", problems)) {
            if (registry.groups.containsKey(group.id())) {
                problems.accept("Skipped group " + group.id() + ": registered twice");
                continue;
            }
            if (!Slugs.isDeviceSlug(group.slug()) || registry.groupSlugs.containsKey(group.slug())) {
                String slug = Slugs.unique(Slugs.fromName(group.slug()), registry.groupSlugs::containsKey);
                problems.accept("Group " + group.id() + " had slug '" + group.slug() + "', which is taken or invalid; it is now '" + slug + "'");
                group.setSlug(slug);
            }
            if (!isLegacyUniqueId(group.legacyUniqueId())) {
                problems.accept("Group " + group.id() + " had v1 unique_id '" + group.legacyUniqueId() + "', which v1 never produced; its v1 discovery is not migrated");
                group.setLegacyUniqueId(null);
            }
            registry.groups.put(group.id(), group);
            registry.groupSlugs.put(group.slug(), group);
        }
        registry.repairMemberships(problems);
        for (Tag id : tag.getListOrEmpty("legacyDiscoveryMigrated")) {
            id.asString().filter(text -> !text.isEmpty()).ifPresent(registry.legacyDiscoveryMigrated::add);
        }
        registry.legacyImportDone = tag.getBooleanOr("legacyImportDone", false);
        registry.v1StatusCleared = tag.getBooleanOr("v1StatusCleared", false);
        registry.dirty = false;
        return registry;
    }

    /** Whether {@code tag} was written by a newer MineQTT, whose layout this one cannot read. */
    public static boolean isNewerSchema(CompoundTag tag) {
        return tag.getIntOr("schema", 0) > SCHEMA;
    }

    /**
     * A registry with only the bridge identity of {@code tag}, which every schema keeps, and nothing else; empty when
     * the tag holds no valid identity.
     */
    public static Optional<DeviceRegistry> identityOf(CompoundTag tag) {
        Optional<String> bridgeId = tag.getString("bridgeId").filter(id -> !id.isEmpty() && ConfigValidation.isValidBridgeId(id));
        Optional<UUID> worldUuid = tag.read("worldUuid", UUIDUtil.CODEC);
        return bridgeId.isPresent() && worldUuid.isPresent() ? Optional.of(new DeviceRegistry(bridgeId.get(), worldUuid.get())) : Optional.empty();
    }

    /** Group membership is stored on both sides; the group's member list wins. */
    private void repairMemberships(Consumer<String> problems) {
        Set<UUID> claimed = new LinkedHashSet<>();
        for (DeviceGroup group : groups.values()) {
            for (UUID member : new ArrayList<>(group.members())) {
                DeviceRecord record = records.get(member);
                boolean valid = record != null && claimed.add(member) && record.type().equals(group.memberType());
                if (!valid) {
                    group.mutableMembers().remove(member);
                    problems.accept("Removed device " + member + " from group " + group.slug() + ": unknown, of another type or in two groups");
                } else {
                    record.setGroup(group.id());
                }
            }
        }
        for (DeviceRecord record : records.values()) {
            if (record.group() != null && !claimed.contains(record.id())) {
                problems.accept("Device " + record.slug() + " named a group that does not list it; it is in no group now");
                record.setGroup(null);
            }
        }
    }

    private void index(DeviceRecord record) {
        positions.put(record.pos(), record);
        chunks.computeIfAbsent(record.pos().dimension(), d -> new HashMap<>())
                .computeIfAbsent(ChunkPos.pack(record.pos().pos()), c -> new LinkedHashSet<>()).add(record);
    }

    private void unindex(DeviceRecord record) {
        positions.remove(record.pos());
        Map<Long, Set<DeviceRecord>> byChunk = chunks.get(record.pos().dimension());
        if (byChunk == null) return;
        long chunk = ChunkPos.pack(record.pos().pos());
        Set<DeviceRecord> inChunk = byChunk.get(chunk);
        if (inChunk != null && inChunk.remove(record) && inChunk.isEmpty()) byChunk.remove(chunk);
    }

    private static boolean isLegacyUniqueId(@Nullable String legacyUniqueId) {
        return legacyUniqueId == null || LEGACY_UNIQUE_ID.matcher(legacyUniqueId).matches();
    }

    private static <T> ListTag encodeAll(Codec<T> codec, Collection<T> values) {
        ListTag list = new ListTag();
        for (T value : values) {
            list.add(codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow());
        }
        return list;
    }

    private static <T> List<T> decodeAll(Codec<T> codec, ListTag list, String what, Consumer<String> problems) {
        List<T> values = new ArrayList<>();
        for (Tag entry : list) {
            DataResult<T> result = codec.parse(NbtOps.INSTANCE, entry);
            result.ifSuccess(values::add).ifError(error -> problems.accept("Skipped unreadable " + what + ": " + error.message()));
        }
        return values;
    }
}

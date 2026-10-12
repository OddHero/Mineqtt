package art.rehra.mineqtt.device;

import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.presence.PlayerEntry;
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.rooms.RoomIndex;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
import java.util.HashSet;
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
 * with its position and slug, the groups, the wires between their ports (M2 plan §3.2), the rooms (M4 plan D11), each
 * owner's literal set ({@link RawTopics}), how far the migration of the world's v1 data got and each player's privacy
 * choice ({@link PlayerEntry}, M4 plan D9). Saved to {@code devices.nbt} by {@link RegistryStore}; every change marks it
 * dirty. Server thread only.
 * <p>
 * A structural change (a device, group, wire or room added or removed, a slug, name or membership changed, a room edited, a
 * literal trusted, a player's consent or presence device changed) is
 * also marked as such (M2 plan D7): chunk data cannot rebuild it, so it is saved within a second instead of waiting for
 * the next autosave. Everything else (states, pending commands, discovery hashes, copies the attach takes from the block
 * entity again, migration flags) is only dirty.
 * <p>
 * A device whose block was broken ({@link #removeBroken}) is gone at once, but its chunk is saved without the block
 * only later (vanilla saves a changed chunk at most once every 10 s, else at unload or the level save). Until the level
 * save a structural save still writes the record as it was, with its wires, so a crash restores block, record and wires
 * together; the level save writes the registry without them.
 * <p>
 * A wire's ends name devices and groups by id, never by position. Removing a device or a group removes its wires;
 * unloading one never does.
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
    // The wires by id, in the order they were made, and by the port they leave.
    private final Map<UUID, Wire> wires = new LinkedHashMap<>();
    private final Map<Endpoint, List<Wire>> wiresFrom = new HashMap<>();
    // The rooms by id, in the order they were made, and which room each device is in, built again on every room edit.
    private final Map<UUID, Room> rooms = new LinkedHashMap<>();
    private RoomIndex roomIndex = RoomIndex.EMPTY;
    // Owner -> the slot literals their new links use as written (M2 plan D1); only trusted server paths add to it.
    private final Map<UUID, Set<String>> ownerLiterals = new LinkedHashMap<>();
    // v1 unique_ids whose old discovery message was replaced by the device discovery (design §9.3).
    private final Set<String> legacyDiscoveryMigrated = new LinkedHashSet<>();
    // The players' privacy choices by UUID (M4 plan D9), in the order they were first stored.
    private final Map<UUID, PlayerEntry> players = new LinkedHashMap<>();
    // Copies of the devices broken since the last level save, by id, as they were before their removal; at most one per
    // position and slug, the newest.
    private final Map<UUID, DeviceRecord> broken = new LinkedHashMap<>();
    // The wires those devices had, by wire id, each with the device it was broken with; written only along with that
    // device's copy, so a device registered again under its id brings back none of the wires its removal dropped.
    private final Map<UUID, BrokenWire> brokenWires = new LinkedHashMap<>();
    private boolean legacyImportDone;
    private boolean v1StatusCleared;
    private boolean bridgeBroadcastDiscovered;
    private boolean dirty;
    private boolean structureChanged;

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

    /**
     * The devices an owner has, loaded or not, which count against {@code limits.maxDevicesPerPlayer}; 0 for no owner. A
     * device whose v1 owner name is not resolved yet counts for nobody.
     */
    public int ownedBy(@Nullable UUID owner) {
        if (owner == null) return 0;
        return (int) records.values().stream().filter(record -> owner.equals(record.owner())).count();
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
        markStructureChanged();
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
        markStructureChanged();
    }

    /**
     * Forgets a device whose block was broken, also as a group member. Until the next level save a structural save
     * writes it as it was, with its membership ({@link #encodeKeepingBroken()}): the saved chunk may still hold the
     * block, and a crash before that chunk is saved again restores both.
     */
    public @Nullable DeviceRecord removeBroken(UUID id) {
        DeviceRecord record = records.get(id);
        if (record == null) return null;
        DeviceRecord copy = DeviceRecord.CODEC.parse(NbtOps.INSTANCE, DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, record).getOrThrow()).getOrThrow();
        broken.values().removeIf(other -> other.id().equals(id) || other.pos().equals(copy.pos()) || other.slug().equals(copy.slug()));
        broken.put(id, copy);
        // Wires from an earlier break of this id were dropped when it was registered again.
        brokenWires.values().removeIf(entry -> entry.device().equals(id));
        wiresOf(id).forEach(wire -> brokenWires.put(wire.id(), new BrokenWire(wire, id)));
        return remove(id);
    }

    /** Forgets a device, also as a group member, and its wires. */
    public @Nullable DeviceRecord remove(UUID id) {
        DeviceRecord record = records.remove(id);
        if (record == null) return null;
        leaveGroup(record);
        slugs.remove(record.slug());
        unindex(record);
        removeWiresOf(id);
        markStructureChanged();
        return record;
    }

    public Collection<DeviceGroup> groups() {
        return Collections.unmodifiableCollection(groups.values());
    }

    public @Nullable DeviceGroup group(UUID id) {
        return groups.get(id);
    }

    /** Whether a device or a group has this id; both are discovered as {@code mineqtt_<uuid32>}. */
    public boolean contains(UUID id) {
        return records.containsKey(id) || groups.containsKey(id);
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
        markStructureChanged();
        return group;
    }

    /** A free slug for a group named {@code name}: its own slug counts as free, so a group keeps it when the name gives it again. */
    public String uniqueGroupSlug(String name, DeviceGroup group) {
        return Slugs.unique(Slugs.fromName(name), slug -> {
            DeviceGroup holder = groupSlugs.get(slug);
            return holder != null && holder != group;
        });
    }

    /** @throws IllegalArgumentException when another group has the slug, or it is not a device slug */
    public void renameGroup(DeviceGroup group, String name, String slug) {
        DeviceGroup holder = groupSlugs.get(slug);
        if (!Slugs.isDeviceSlug(slug) || (holder != null && holder != group)) {
            throw new IllegalArgumentException("Group slug not available: " + slug);
        }
        groupSlugs.remove(group.slug());
        group.setName(name);
        group.setSlug(slug);
        groupSlugs.put(slug, group);
        markStructureChanged();
    }

    /** Deletes a group and its wires; its members stay as devices of their own. */
    public void removeGroup(DeviceGroup group) {
        if (groups.remove(group.id()) == null) return;
        groupSlugs.remove(group.slug());
        removeWiresOf(group.id());
        for (UUID member : group.members()) {
            DeviceRecord record = records.get(member);
            if (record != null) record.setGroup(null);
        }
        markStructureChanged();
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
        markStructureChanged();
    }

    /** Takes {@code record} out of its group, if it has one. An emptied group keeps its member type and state. */
    public void leaveGroup(DeviceRecord record) {
        if (record.group() == null) return;
        DeviceGroup group = groups.get(record.group());
        if (group != null) group.mutableMembers().remove(record.id());
        record.setGroup(null);
        markStructureChanged();
    }

    /** Every wire, in the order they were made. */
    public Collection<Wire> wires() {
        return Collections.unmodifiableCollection(wires.values());
    }

    public @Nullable Wire wire(UUID id) {
        return wires.get(id);
    }

    /** The wires that leave {@code source}, disabled ones included, in the order they were made. */
    public List<Wire> wiresFrom(Endpoint source) {
        List<Wire> from = wiresFrom.get(source);
        return from == null ? List.of() : Collections.unmodifiableList(from);
    }

    /** The wires with an end at the device or group {@code node}, in the order they were made. */
    public List<Wire> wiresOf(UUID node) {
        return wires.values().stream().filter(wire -> node.equals(node(wire.from())) || node.equals(node(wire.to()))).toList();
    }

    /**
     * Adds a wire. Whether its ports exist and fit is the caller's to judge; the registry only keeps its ends on devices
     * and groups it holds.
     *
     * @throws IllegalArgumentException when the id is taken, or an end names no registered device or group
     */
    public void addWire(Wire wire) {
        if (wires.containsKey(wire.id())) throw new IllegalArgumentException("Wire already registered: " + wire.id());
        if (!holds(wire.from()) || !holds(wire.to())) throw new IllegalArgumentException("Wire " + wire.describe() + " names a node that is not registered");
        wires.put(wire.id(), wire);
        wiresFrom.computeIfAbsent(wire.from(), source -> new ArrayList<>()).add(wire);
        markStructureChanged();
    }

    public @Nullable Wire removeWire(UUID id) {
        Wire wire = wires.remove(id);
        if (wire == null) return null;
        List<Wire> from = wiresFrom.get(wire.from());
        if (from != null && from.remove(wire) && from.isEmpty()) wiresFrom.remove(wire.from());
        markStructureChanged();
        return wire;
    }

    /** The device or group an endpoint belongs to; null for one that names no node (a Home Assistant or topic end). */
    public static @Nullable UUID node(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.PortRef port -> port.node();
            case Endpoint.GroupPortRef port -> port.group();
            case Endpoint.TopicRef _, Endpoint.HaEntityRef _ -> null;
        };
    }

    // Whether an endpoint names a device or a group of this registry.
    private boolean holds(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.PortRef port -> records.containsKey(port.node());
            case Endpoint.GroupPortRef port -> groups.containsKey(port.group());
            case Endpoint.TopicRef _, Endpoint.HaEntityRef _ -> false;
        };
    }

    private void removeWiresOf(UUID node) {
        wiresOf(node).forEach(wire -> removeWire(wire.id()));
    }

    /** Every room, in the order they were made. */
    public Collection<Room> rooms() {
        return Collections.unmodifiableCollection(rooms.values());
    }

    public @Nullable Room room(UUID id) {
        return rooms.get(id);
    }

    /** Which room each device is in (M4 plan D11), as the rooms are now. */
    public RoomIndex roomIndex() {
        return roomIndex;
    }

    /** The room a device is in ({@link RoomIndex#roomOf}). */
    public Optional<Room> roomOf(DeviceRecord record) {
        return roomIndex.roomOf(record);
    }

    /**
     * Adds a room. Whether its name, size and owner's count are allowed is the caller's to judge ({@code RoomRules}).
     *
     * @throws IllegalArgumentException when the id is taken
     */
    public void addRoom(Room room) {
        if (rooms.containsKey(room.id())) throw new IllegalArgumentException("Room already registered: " + room.id());
        rooms.put(room.id(), room);
        roomsChanged();
    }

    /**
     * Replaces a room with its renamed or resized self, keeping its place in the order.
     *
     * @throws IllegalArgumentException when no room has its id
     */
    public void replaceRoom(Room room) {
        if (!rooms.containsKey(room.id())) throw new IllegalArgumentException("No room " + room.id());
        rooms.put(room.id(), room);
        roomsChanged();
    }

    public @Nullable Room removeRoom(UUID id) {
        Room room = rooms.remove(id);
        if (room != null) roomsChanged();
        return room;
    }

    private void roomsChanged() {
        roomIndex = new RoomIndex(rooms.values());
        markStructureChanged();
    }

    /** The literals an owner's new links use as written; empty for no owner. */
    public Set<String> ownerLiterals(@Nullable UUID owner) {
        Set<String> literals = owner == null ? null : ownerLiterals.get(owner);
        return literals == null ? Set.of() : Collections.unmodifiableSet(literals);
    }

    /**
     * Adds a literal to an owner's set. Only trusted server paths call it: v1 adoption for its grandfathered topics, and
     * the one-time M1 re-resolution for the topics it keeps. A structural change: the block entity marks its link as
     * settled, so a literal a crash lost would never be added again.
     *
     * @return whether the set did not hold it yet
     */
    public boolean addOwnerLiteral(UUID owner, String literal) {
        if (!ownerLiterals.computeIfAbsent(owner, key -> new LinkedHashSet<>()).add(literal)) return false;
        markStructureChanged();
        return true;
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

    /**
     * Whether Home Assistant may still hold the bridge's broadcast text entity (M4 plan D8): set when the bridge was
     * discovered with it, cleared once its removal went out while Home Assistant was online, so a restart before that
     * removes it again. A structural change: a crash must not forget an entity Home Assistant keeps.
     */
    public boolean bridgeBroadcastDiscovered() {
        return bridgeBroadcastDiscovered;
    }

    public void setBridgeBroadcastDiscovered(boolean discovered) {
        if (bridgeBroadcastDiscovered == discovered) return;
        bridgeBroadcastDiscovered = discovered;
        markStructureChanged();
    }

    /** Every player entry (M4 plan D9), in the order they were first stored. */
    public Collection<PlayerEntry> players() {
        return Collections.unmodifiableCollection(players.values());
    }

    /** The entry of a player; empty for a player whose consent was never stored, which counts as unset. */
    public Optional<PlayerEntry> player(UUID player) {
        return Optional.ofNullable(players.get(player));
    }

    /** The entry whose presence device has this slug under {@code player/}. */
    public Optional<PlayerEntry> playerBySlug(String slug) {
        return players.values().stream().filter(entry -> entry.slug().filter(slug::equals).isPresent()).findFirst();
    }

    /**
     * Stores a player's entry, replacing the one they had. A changed consent, prompt, name or presence device is a
     * structural change (no chunk data holds it, and a consent withdrawn must survive a crash); a new last seen day alone
     * waits for the autosave.
     *
     * @throws IllegalArgumentException when another player's presence device has the entry's slug
     */
    public void putPlayer(PlayerEntry entry) {
        entry.slug().flatMap(this::playerBySlug).filter(holder -> !holder.uuid().equals(entry.uuid())).ifPresent(holder -> {
            throw new IllegalArgumentException("Player slug not available: " + entry.slug().get());
        });
        PlayerEntry previous = players.put(entry.uuid(), entry);
        if (entry.equals(previous)) return;
        if (previous != null && previous.withLastSeenDay(entry.lastSeenDay()).equals(entry)) {
            markDirty();
        } else {
            markStructureChanged();
        }
    }

    /** A change that can wait for the next autosave. */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Whether a structural change awaits its save, which is due within a second ({@link RegistryStore#saveStructure}). */
    public boolean structureChanged() {
        return structureChanged;
    }

    private void markStructureChanged() {
        dirty = true;
        structureChanged = true;
    }

    /** {@link #encode()} was written: the level is saved, so the broken devices are left out from now on. */
    void markSaved() {
        dirty = false;
        structureChanged = false;
        broken.clear();
        brokenWires.clear();
    }

    /**
     * {@link #encodeKeepingBroken()} was written. A file that still holds broken devices is not the registry, so it stays
     * dirty until the level save writes it without them.
     */
    void markStructureSaved() {
        structureChanged = false;
        if (broken.isEmpty()) dirty = false;
    }

    /** The registry as the tag {@link RegistryStore} writes when the level is saved. */
    public CompoundTag encode() {
        return encode(false);
    }

    /**
     * The tag of a structural save: {@link #encode()} with the devices broken since the last level save, each in its
     * group again while that group still takes it, with each of its wires whose other end is in the file too. One whose
     * id, position or slug a device holds now is left out with its wires: the device that holds it wins.
     */
    public CompoundTag encodeKeepingBroken() {
        return encode(true);
    }

    private CompoundTag encode(boolean keepBroken) {
        ListTag devices = encodeAll(DeviceRecord.CODEC, records.values());
        // Group id -> its members with the broken devices it takes back.
        Map<UUID, Set<UUID>> members = new HashMap<>();
        // The broken devices written, whose wires are written with them.
        Set<UUID> keptDevices = new HashSet<>();
        if (keepBroken) {
            for (DeviceRecord record : broken.values()) {
                if (records.containsKey(record.id()) || positions.containsKey(record.pos()) || slugs.containsKey(record.slug())) continue;
                keptDevices.add(record.id());
                CompoundTag device = (CompoundTag) DeviceRecord.CODEC.encodeStart(NbtOps.INSTANCE, record).getOrThrow();
                DeviceGroup group = record.group() == null ? null : groups.get(record.group());
                if (group != null && record.type().equals(group.memberType())) {
                    members.computeIfAbsent(group.id(), id -> new LinkedHashSet<>(group.members())).add(record.id());
                } else {
                    device.remove("group");
                }
                devices.add(device);
            }
        }
        ListTag groupList = new ListTag();
        for (DeviceGroup group : groups.values()) {
            CompoundTag encoded = (CompoundTag) DeviceGroup.CODEC.encodeStart(NbtOps.INSTANCE, group).getOrThrow();
            Set<UUID> kept = members.get(group.id());
            if (kept != null) encoded.store("members", UUIDUtil.CODEC_LINKED_SET, kept);
            groupList.add(encoded);
        }
        ListTag wireList = encodeAll(WireCodecs.WIRE, wires.values());
        for (BrokenWire entry : brokenWires.values()) {
            Wire wire = entry.wire();
            if (keptDevices.contains(entry.device()) && !wires.containsKey(wire.id()) && written(wire.from(), keptDevices)
                    && written(wire.to(), keptDevices)) {
                wireList.add(WireCodecs.WIRE.encodeStart(NbtOps.INSTANCE, wire).getOrThrow());
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        tag.putString("bridgeId", generatedBridgeId);
        tag.store("worldUuid", UUIDUtil.CODEC, worldUuid);
        tag.put("devices", devices);
        tag.put("groups", groupList);
        tag.put("wires", wireList);
        tag.put("rooms", encodeAll(Room.CODEC, rooms.values()));
        tag.put("ownerLiterals", encodeAll(OwnerLiterals.CODEC, ownerLiterals.entrySet().stream()
                .map(entry -> new OwnerLiterals(entry.getKey(), List.copyOf(entry.getValue()))).toList()));
        ListTag migrated = new ListTag();
        legacyDiscoveryMigrated.forEach(id -> migrated.add(StringTag.valueOf(id)));
        tag.put("legacyDiscoveryMigrated", migrated);
        tag.putBoolean("legacyImportDone", legacyImportDone);
        tag.putBoolean("v1StatusCleared", v1StatusCleared);
        tag.put("players", encodeAll(PlayerEntry.CODEC, players.values()));
        // Only while set, so a world that never turned broadcast text on saves as before.
        if (bridgeBroadcastDiscovered) tag.putBoolean("bridgeBroadcastDiscovered", true);
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
        // Optional: a registry M1 saved has no wires.
        for (Wire wire : decodeAll(WireCodecs.WIRE, tag.getListOrEmpty("wires"), "wire", problems)) {
            if (registry.wires.containsKey(wire.id())) {
                problems.accept("Skipped wire " + wire.id() + ": registered twice");
            } else if (!registry.holds(wire.from()) || !registry.holds(wire.to())) {
                problems.accept("Skipped wire " + wire.describe() + ": a device or group it connects is not registered");
            } else {
                registry.addWire(wire);
            }
        }
        // Optional: a registry before M4 has no rooms. Their size is not judged again, so a lowered rooms.maxSize keeps them.
        for (Room room : decodeAll(Room.CODEC, tag.getListOrEmpty("rooms"), "room", problems)) {
            if (registry.rooms.containsKey(room.id())) {
                problems.accept("Skipped room " + room.id() + ": registered twice");
            } else if (registry.rooms.size() >= StaticDefaults.MAX_ROOMS_PER_WORLD) {
                problems.accept("Skipped room " + room.id() + ": a world holds at most " + StaticDefaults.MAX_ROOMS_PER_WORLD + " rooms");
            } else {
                registry.rooms.put(room.id(), room);
            }
        }
        registry.roomIndex = new RoomIndex(registry.rooms.values());
        // Optional: a registry M1 saved has no literal sets.
        for (OwnerLiterals owner : decodeAll(OwnerLiterals.CODEC, tag.getListOrEmpty("ownerLiterals"), "owner literal set", problems)) {
            owner.literals().forEach(literal -> registry.addOwnerLiteral(owner.owner(), literal));
        }
        for (Tag id : tag.getListOrEmpty("legacyDiscoveryMigrated")) {
            id.asString().filter(text -> !text.isEmpty()).ifPresent(registry.legacyDiscoveryMigrated::add);
        }
        registry.legacyImportDone = tag.getBooleanOr("legacyImportDone", false);
        registry.v1StatusCleared = tag.getBooleanOr("v1StatusCleared", false);
        registry.bridgeBroadcastDiscovered = tag.getBooleanOr("bridgeBroadcastDiscovered", false);
        // Optional: a registry before M4 has no player entries, so every consent reads as unset.
        for (PlayerEntry entry : decodeAll(PlayerEntry.CODEC, tag.getListOrEmpty("players"), "player entry", problems)) {
            if (registry.players.containsKey(entry.uuid())) {
                problems.accept("Skipped player entry " + entry.uuid() + ": stored twice");
                continue;
            }
            if (entry.slug().flatMap(registry::playerBySlug).isPresent()) {
                problems.accept("Player " + entry.uuid() + " had presence slug '" + entry.slug().get() + "', which another player has; their device gets a new one");
                entry = entry.withSlug(Optional.empty());
            }
            registry.players.put(entry.uuid(), entry);
        }
        registry.markSaved();
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

    // Whether a wire end is in the file encode writes: a device or group this registry holds, or a broken device it keeps.
    private boolean written(Endpoint endpoint, Set<UUID> keptDevices) {
        return holds(endpoint) || endpoint instanceof Endpoint.PortRef port && keptDevices.contains(port.node());
    }

    private static boolean isLegacyUniqueId(@Nullable String legacyUniqueId) {
        return legacyUniqueId == null || LEGACY_UNIQUE_ID.matcher(legacyUniqueId).matches();
    }

    // A wire a device had when it was broken, and that device.
    private record BrokenWire(Wire wire, UUID device) {
    }

    private record OwnerLiterals(UUID owner, List<String> literals) {
        static final Codec<OwnerLiterals> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(OwnerLiterals::owner),
                Codec.STRING.listOf().fieldOf("literals").forGetter(OwnerLiterals::literals)
        ).apply(instance, OwnerLiterals::new));
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

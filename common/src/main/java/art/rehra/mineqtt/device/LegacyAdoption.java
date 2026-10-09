package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The decisions of a v1 device's adoption (design §9.2), pure over the registry so the migration check runs them
 * without a server: which pre-created record a v1 lamp takes over, which v1 light it keeps otherwise, which legacy
 * group it joins, when that group follows the v1 topic for it, which topic joins its owner's literal set, and which
 * lamps a v1 remote commanded, or whether that cannot be told yet. {@link DeviceRuntime} acts on them.
 */
public final class LegacyAdoption {
    private LegacyAdoption() {
    }

    /**
     * Whether a v1 device takes over the record the v1 topic states pre-created at its position: one of its type, not
     * attached, imported for the device's own v1 topic. v1 kept a lamp's position under every topic it ever had, so a
     * record imported for another topic is stale and is forgotten instead.
     *
     * @param listed   the record at the device's position, if any
     * @param attached whether that record's device is attached
     */
    static boolean takesOver(@Nullable DeviceRecord listed, DeviceType type, String v1Topic, boolean attached) {
        return listed != null && !attached && listed.type().equals(type.id()) && v1Topic.equals(listed.legacyTopic());
    }

    /**
     * The unique_id of the Home Assistant light v1 discovered for a v1 device's topic, which a v1 device the topic
     * states did not list keeps: null when its type echoes no v1 state (it had no v1 light), when a legacy group
     * replaces the topic (the device joins it), or when another device or group holds the unique_id.
     */
    static @Nullable String keptUniqueId(DeviceRegistry registry, DeviceType type, String v1Topic) {
        if (v1Topic.isEmpty() || !keptStatePerTopic(type)) return null;
        if (legacyGroup(registry, v1Topic, type) != null) return null;
        String uniqueId = LegacyNbtV1.legacyUniqueId(v1Topic);
        return uniqueIdTaken(registry, uniqueId) ? null : uniqueId;
    }

    /** Whether a device or group already holds a v1 light's unique_id; each keeps a unique_id only once. */
    public static boolean uniqueIdTaken(DeviceRegistry registry, String uniqueId) {
        return registry.records().stream().anyMatch(record -> uniqueId.equals(record.legacyUniqueId()))
                || registry.groups().stream().anyMatch(group -> uniqueId.equals(group.legacyUniqueId()));
    }

    /**
     * The legacy group of a v1 topic that devices of the type can join; null when there is none. Legacy groups hold the
     * lamps of a v1 topic state, so only a type that kept one state per topic joins one, also a group whose member type
     * is not known yet.
     */
    static @Nullable DeviceGroup legacyGroup(DeviceRegistry registry, String topic, DeviceType type) {
        if (!keptStatePerTopic(type)) return null;
        for (DeviceGroup group : registry.groups()) {
            if (topic.equals(group.legacyTopic()) && (group.memberType() == null || group.memberType().equals(type.id()))) return group;
        }
        return null;
    }

    /**
     * The legacy group a device in no group joins because its usable Raw Link follows the group's v1 topic (v1 kept one
     * state per topic); null when it stays on its own. The caller still checks the topic for the device's owner.
     */
    static @Nullable DeviceGroup groupToJoin(DeviceRegistry registry, DeviceRecord record, @Nullable RawLink raw, DeviceType type) {
        if (record.group() != null || raw == null || !raw.active()) return null;
        return legacyGroup(registry, raw.topic(), type);
    }

    /**
     * What settling a grandfather flag did.
     *
     * @param link  the link to keep, marked as settled
     * @param added the literal that joined the owner's literal set, or null
     */
    record Settlement(RawLink link, @Nullable String added) {
    }

    /**
     * Decides once whether a v1 topic the migrator grandfathered joins its owner's literal set (M2 plan D1), and marks the
     * link ({@link RawLink#resolvedV()}) so it is never decided again. Trusted data (v1 data adopted on chunk load, a
     * device this registry knew) adds it once the flag is bound to the v1 owner, so the owner's new links on the same item
     * or paper use it as written and keep talking to their old v1 devices; until then nothing is decided. Untrusted data
     * (a structure, a lost registry) adds nothing. A player's claim settles the flag itself, without adding it
     * ({@link DeviceLink#claim}).
     *
     * @return null when there is nothing to decide
     */
    static @Nullable Settlement settleGrandfathered(DeviceRegistry registry, @Nullable RawLink raw, boolean trusted) {
        if (raw == null || raw.grandfathered() == null || raw.channel() != null || raw.resolved()) return null;
        if (!trusted) return new Settlement(raw.markResolved(), null);
        TopicAcl.Grandfather grandfather = raw.grandfathered();
        if (grandfather.owner() == null) return null;
        boolean added = registry.addOwnerLiteral(grandfather.owner(), grandfather.topic());
        return new Settlement(raw.markResolved(), added ? grandfather.topic() : null);
    }

    /**
     * A loaded Smart Lamp, as a v1 remote's topic is matched against it (M2 plan D15).
     *
     * @param topic the topic its Raw Link listens on; null without one
     */
    public record Lamp(UUID id, @Nullable UUID owner, String ownerName, @Nullable String topic) {
    }

    /**
     * The v1 topic a v1 device's record keeps for M2 plan D15 ({@link DeviceRecord#v1Topic}): a lamp's, which a v1
     * remote on it commanded, and a remote's, until it is wired; null for another type or an empty topic.
     */
    static @Nullable String keptV1Topic(DeviceType type, String v1Topic) {
        return v1Topic.isEmpty() || !(type.remote() || keptStatePerTopic(type)) ? null : v1Topic;
    }

    /**
     * What a v1 remote's topic commanded on this server (M2 plan D15), pure: the {@code state} in-ports a Wall Switch
     * adopting the remote is wired to. The legacy group of the topic, when one of its lamps has the remote's owner: a
     * loaded one, or an unloaded one whose record names the owner's UUID. Besides, each loaded lamp of the remote's owner
     * whose Raw Link listens on the topic, and each unloaded lamp adopted with the topic as its v1 topic whose record names
     * the owner's UUID. Nothing for an empty topic or a remote without an owner.
     *
     * @param owner     the remote's owner's UUID, null while it is not known
     * @param ownerName the remote's v1 owner name
     * @param loaded    the loaded Smart Lamps
     */
    public static List<Endpoint> remoteTargets(DeviceRegistry registry, String topic, @Nullable UUID owner, String ownerName, List<Lamp> loaded) {
        if (topic.isEmpty() || (owner == null && ownerName.isEmpty())) return List.of();
        Set<UUID> loadedIds = loaded.stream().map(Lamp::id).collect(Collectors.toSet());
        for (DeviceGroup group : registry.groups()) {
            if (!topic.equals(group.legacyTopic()) || !SmartLamp.TYPE.id().equals(group.memberType())) continue;
            boolean loadedMember = loaded.stream().anyMatch(lamp -> group.members().contains(lamp.id()) && sameOwner(owner, ownerName, lamp.owner(), lamp.ownerName()));
            boolean unloadedMember = owner != null && group.members().stream().filter(member -> !loadedIds.contains(member)).map(registry::record)
                    .anyMatch(member -> member != null && owner.equals(member.owner()));
            if (loadedMember || unloadedMember) return List.of(Endpoint.groupPort(group.id(), Action.STATE));
        }
        List<Endpoint> targets = new ArrayList<>();
        loaded.stream().filter(lamp -> topic.equals(lamp.topic()) && sameOwner(owner, ownerName, lamp.owner(), lamp.ownerName()))
                .forEach(lamp -> targets.add(Endpoint.port(lamp.id(), Action.STATE)));
        if (owner == null) return targets;
        for (DeviceRecord record : registry.records()) {
            if (!loadedIds.contains(record.id()) && isLamp(record) && topic.equals(record.v1Topic()) && owner.equals(record.owner())) {
                targets.add(Endpoint.port(record.id(), Action.STATE));
            }
        }
        return targets;
    }

    /**
     * Whether a v1 remote's topic may still command lamps that {@link #remoteTargets} cannot tell yet (M2 plan D15), so
     * the remote waits for them instead of having its v1 commands dropped: an unloaded lamp the v1 topic states listed on
     * the topic that has not loaded since (its record knows neither its owner nor its v1 topic), or an unloaded lamp
     * adopted with the topic whose owner is not compared, since its record or the remote names no owner's UUID (an
     * unloaded record keeps no owner name). Members of a legacy group the remote is wired to anyway do not count. Both are
     * told once such a lamp loads, or an owner becomes known.
     *
     * @param targets what {@link #remoteTargets} found
     */
    public static boolean remoteUndecided(DeviceRegistry registry, String topic, @Nullable UUID owner, String ownerName, List<Lamp> loaded,
                                          List<Endpoint> targets) {
        if (topic.isEmpty() || (owner == null && ownerName.isEmpty())) return false;
        Set<UUID> loadedIds = loaded.stream().map(Lamp::id).collect(Collectors.toSet());
        for (DeviceRecord record : registry.records()) {
            if (loadedIds.contains(record.id()) || !isLamp(record)) continue;
            if (record.group() != null && targets.contains(Endpoint.groupPort(record.group(), Action.STATE))) continue;
            if (topic.equals(record.legacyTopic())) return true;
            if (topic.equals(record.v1Topic()) && (owner == null || record.owner() == null)) return true;
        }
        return false;
    }

    private static boolean isLamp(DeviceRecord record) {
        return SmartLamp.TYPE.id().equals(record.type());
    }

    /**
     * Whether two devices have the same owner: by UUID when both know it, else by the v1 owner name, by which v1 told its
     * owners apart (an offline-mode server stores no UUID for a name until its player is seen).
     */
    static boolean sameOwner(@Nullable UUID owner, String ownerName, @Nullable UUID other, String otherName) {
        if (owner != null && other != null) return owner.equals(other);
        return !ownerName.isEmpty() && ownerName.equals(otherName);
    }

    /** Whether v1 kept the type's state per topic and echoed it to {@code <topic>/state}: the lamp, which had a v1 light. */
    private static boolean keptStatePerTopic(DeviceType type) {
        return type.rawLink().map(RawLinkSpec::echoes).orElse(false);
    }

    /** Whether a device leaves its legacy group after its Raw Link changed: it no longer follows the group's topic. */
    static boolean leavesLegacyGroup(@Nullable DeviceGroup group, @Nullable RawLink raw) {
        return group != null && group.legacyTopic() != null && (raw == null || !group.legacyTopic().equals(raw.topic()));
    }

    /**
     * Whether the device's legacy group follows its Raw Link topic for it (§6.6: subscribed once, by the group), so the
     * device neither subscribes nor echoes itself.
     */
    static boolean followedByGroup(DeviceRegistry registry, @Nullable DeviceRecord record, RawLink raw) {
        DeviceGroup group = record == null || record.group() == null ? null : registry.group(record.group());
        return group != null && raw.topic().equals(group.legacyTopic());
    }
}

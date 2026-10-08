package art.rehra.mineqtt.device;

import art.rehra.mineqtt.legacy.LegacyNbtV1;
import org.jetbrains.annotations.Nullable;

/**
 * The decisions of a v1 device's adoption (design §9.2), pure over the registry so the migration check runs them
 * without a server: which pre-created record a v1 lamp takes over, which v1 light it keeps otherwise, which legacy
 * group it joins, and when that group follows the v1 topic for it. {@link DeviceRuntime} acts on them.
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

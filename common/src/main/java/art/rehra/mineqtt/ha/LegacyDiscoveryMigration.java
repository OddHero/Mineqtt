package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.TopicFilters;
import com.google.gson.JsonObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Moves the v1 discovery of migrated lamps over to device discovery without losing their Home Assistant entities
 * (design §9.3). v1 published {@code homeassistant/light/<sanitize(topic)>/config} retained; for every device or group
 * whose {@code legacyUniqueId} is not in the registry's {@code legacyDiscoveryMigrated}:
 * <ol>
 *     <li>{@code {"migrate_discovery": true}} goes to the old topic, retained,</li>
 *     <li>two seconds later the device discovery, whose primary entity carries the v1 unique_id,</li>
 *     <li>two seconds later an empty retained message clears the old topic,</li>
 *     <li>and once the broker acknowledged that clear, the unique_id is marked migrated and the device checked again,
 *     since a change after step 2 (a rename, a group, the exposure) could not be published while the sequence ran.</li>
 * </ol>
 * A device that is not exposed only gets its old message cleared. The sequences run after the state that follows a
 * connect (§4.2 step 6), only while connected; a connection lost on the way leaves the unique_id unmarked and every
 * sequence starts over after the next connect. Each step is idempotent. Also once per world: the {@code Offline} v1
 * left retained on its status topic is cleared. Server thread only.
 */
public final class LegacyDiscoveryMigration {
    /** v1 published its discovery under this prefix, whatever the config said. */
    public static final String V1_PREFIX = "homeassistant";
    /** The pause between two steps of one sequence. */
    static final long STEP_MILLIS = 2_000;

    private enum Step {
        MIGRATE_SENT,
        DISCOVERY_SENT,
        CLEAR_SENT
    }

    private static final class Sequence {
        final DiscoveryPublisher.Target target;
        final String legacyUniqueId;
        Step step;
        long dueMillis;

        Sequence(DiscoveryPublisher.Target target, String legacyUniqueId) {
            this.target = target;
            this.legacyUniqueId = legacyUniqueId;
        }
    }

    private final DiscoveryPublisher discovery;
    private final DeviceRegistry registry;
    private final MqttService mqtt;
    private final LongSupplier clock;
    // By v1 unique_id: the sequences of the current connection.
    private final Map<String, Sequence> running = new LinkedHashMap<>();
    private final Set<DiscoveryPublisher.Target> waiting = new LinkedHashSet<>();
    private boolean started;
    private boolean invalidStatusTopicLogged;

    LegacyDiscoveryMigration(DiscoveryPublisher discovery, DeviceRegistry registry, MqttService mqtt, LongSupplier clock) {
        this.discovery = discovery;
        this.registry = registry;
        this.mqtt = mqtt;
        this.clock = clock;
    }

    /** The topic v1 published a light's discovery on. */
    public static String v1Topic(String legacyUniqueId) {
        return V1_PREFIX + "/light/" + legacyUniqueId + "/config";
    }

    /** The devices and groups whose v1 discovery is not migrated yet. */
    public long pending() {
        return registry.records().stream().map(DeviceRecord::legacyUniqueId).filter(this::isPending).count()
                + registry.groups().stream().map(DeviceGroup::legacyUniqueId).filter(this::isPending).count();
    }

    /** Whether a step is waiting for its time or a token; a sent clear waits for its acknowledgement instead. */
    boolean hasPendingWork() {
        return started && (!waiting.isEmpty() || running.values().stream().anyMatch(sequence -> sequence.step != Step.CLEAR_SENT));
    }

    /** After every CONNACK: the sequences of the lost connection are abandoned; the v1 status is cleared once. */
    void connected() {
        running.clear();
        waiting.clear();
        started = false;
        clearV1Status();
    }

    /** §4.2 step 6: every pending device and group is migrated from now on, within the discovery rate limit. */
    void start() {
        started = true;
        registry.records().stream().filter(record -> isPending(record.legacyUniqueId()))
                .forEach(record -> waiting.add(DiscoveryPublisher.Target.device(record.id())));
        registry.groups().stream().filter(group -> isPending(group.legacyUniqueId()))
                .forEach(group -> waiting.add(DiscoveryPublisher.Target.group(group.id())));
    }

    /** A device or group that needs migrating turned up (e.g. a lamp adopted after the connect). */
    void request(DiscoveryPublisher.Target target) {
        if (started) waiting.add(target);
    }

    /** The device or group is gone; its old topic is cleared by whoever forgot it. */
    void forget(String legacyUniqueId) {
        running.remove(legacyUniqueId);
    }

    void tick() {
        if (!started || !mqtt.state().isConnected()) return;
        long now = clock.getAsLong();
        for (Sequence sequence : List.copyOf(running.values())) {
            if (sequence.step == Step.CLEAR_SENT || now < sequence.dueMillis) continue;
            if (!discovery.tryTakeToken()) return;
            if (sequence.step == Step.MIGRATE_SENT) {
                discovery.publishMigrated(sequence.target);
                sequence.step = Step.DISCOVERY_SENT;
                sequence.dueMillis = now + STEP_MILLIS;
            } else {
                sendClear(sequence);
            }
        }
        for (Iterator<DiscoveryPublisher.Target> targets = waiting.iterator(); targets.hasNext(); ) {
            DiscoveryPublisher.Target target = targets.next();
            String legacyUniqueId = discovery.pendingLegacyId(target);
            if (legacyUniqueId == null || running.containsKey(legacyUniqueId)) {
                targets.remove();
                continue;
            }
            if (!discovery.tryTakeToken()) return;
            targets.remove();
            Sequence sequence = new Sequence(target, legacyUniqueId);
            running.put(legacyUniqueId, sequence);
            if (discovery.exposed(target)) {
                JsonObject migrate = new JsonObject();
                migrate.addProperty("migrate_discovery", true);
                mqtt.publish(Outbound.state(v1Topic(legacyUniqueId), migrate));
                sequence.step = Step.MIGRATE_SENT;
                sequence.dueMillis = now + STEP_MILLIS;
            } else {
                sendClear(sequence);
            }
        }
    }

    private void sendClear(Sequence sequence) {
        sequence.step = Step.CLEAR_SENT;
        mqtt.publish(Outbound.clear(v1Topic(sequence.legacyUniqueId)).whenAcknowledged(() -> {
            // A clear of an abandoned sequence proves nothing about the one that replaced it.
            if (running.get(sequence.legacyUniqueId) != sequence) return;
            running.remove(sequence.legacyUniqueId);
            registry.markLegacyDiscoveryMigrated(sequence.legacyUniqueId);
            // Changes made after step 2 were held back while the sequence ran; unchanged, this publishes nothing.
            discovery.refresh(sequence.target);
            MineQTT.LOGGER.info("Migrated the v1 Home Assistant discovery of {}", sequence.legacyUniqueId);
        }));
    }

    private void clearV1Status() {
        String topic = MineQTTConfig.legacyV1StatusTopic;
        if (registry.v1StatusCleared() || topic.isEmpty()) return;
        if (!TopicFilters.isValidTopic(topic)) {
            if (!invalidStatusTopicLogged) MineQTT.LOGGER.warn("Not clearing the v1 status topic '{}': it is not a valid topic", topic);
            invalidStatusTopicLogged = true;
            return;
        }
        mqtt.publish(Outbound.clear(topic).whenAcknowledged(registry::setV1StatusCleared));
    }

    private boolean isPending(String legacyUniqueId) {
        return legacyUniqueId != null && !registry.legacyDiscoveryMigrated(legacyUniqueId);
    }
}

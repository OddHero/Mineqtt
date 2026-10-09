package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.runtime.TopicScheme;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Home Assistant device discovery (design §4): one retained {@code <prefix>/device/<objectId>/config} per exposed
 * device and group, and one for the bridge. Server thread only.
 * <ul>
 *     <li>The payloads are generated from the device type's declaration ({@link #deviceConfig}, {@link #groupConfig});
 *     no device writes discovery JSON by hand.</li>
 *     <li>Everything goes through a queue released at {@value #MAX_PER_SECOND} messages per second. A change (attach,
 *     group membership, exposure) is published only when the payload's hash differs from the one last published; a
 *     republish (connect, Home Assistant's birth, a request) sends every payload again.</li>
 *     <li>Exposure (§8.8): nothing with {@code ha.expose} NONE; otherwise a device's own {@code expose} wins, else
 *     ALL exposes and OPT_IN does not. Members of a group are hidden unless the group exposes them. Under OPT_IN a group
 *     is discovered only for a member someone exposed, or as the light of the v1 topic it replaces, so no player without
 *     {@code mayExpose} makes a new Home Assistant device by making a group. A device or group that is no longer exposed
 *     gets its retained message cleared.</li>
 *     <li>A device or group that still has a v1 discovery message (its {@code legacyUniqueId} is not migrated yet) is
 *     left to {@link LegacyDiscoveryMigration}: publishing the new message before {@code migrate_discovery} would
 *     give Home Assistant the same unique_id twice.</li>
 *     <li>A migrated v1 lamp or legacy group keeps its v1 unique_id on its primary entity and, for good, as its second
 *     device identifier (§4.1).</li>
 *     <li>Records of a type this MineQTT does not know are never published nor cleared.</li>
 * </ul>
 */
public final class DiscoveryPublisher {
    public static final String MANUFACTURER = "MineQTT";
    public static final String URL = "https://github.com/OddHero/Mineqtt";
    /** Design §3.2: discovery is released at no more than 50 messages per second. */
    static final int MAX_PER_SECOND = 50;
    // Jinja resolves value_json.<key> to a dict method first; these keys need the subscript form.
    private static final Set<String> DICT_METHODS = Set.of("clear", "copy", "fromkeys", "get", "items", "keys", "pop",
            "popitem", "setdefault", "update", "values");

    /** What every payload says about this MineQTT and its world. */
    public record Identity(String worldName, UUID worldUuid, String version) {
        /** The bridge's device identifier, which every device names as its {@code via_device}. */
        public String bridgeObjectId() {
            return BridgeDevice.objectId(worldUuid);
        }
    }

    /** One discovery message: the bridge, a device or a group. */
    public record Target(Kind kind, @Nullable UUID id) {
        public enum Kind {
            BRIDGE,
            DEVICE,
            GROUP
        }

        public static final Target BRIDGE = new Target(Kind.BRIDGE, null);

        public static Target device(UUID id) {
            return new Target(Kind.DEVICE, id);
        }

        public static Target group(UUID id) {
            return new Target(Kind.GROUP, id);
        }
    }

    // A device or a group as discovery sees it; type is null when unknown, legacyUniqueId only while not migrated.
    private record Subject(String objectId, @Nullable DeviceType type, boolean exposed, @Nullable String legacyUniqueId,
                           @Nullable String hash, Consumer<String> setHash, Supplier<JsonObject> config) {
    }

    private final DeviceRegistry registry;
    private final MqttService mqtt;
    private final Supplier<TopicScheme> topics;
    private final Identity identity;
    private final boolean dedicatedServer;
    private final LongSupplier clock;
    private final LegacyDiscoveryMigration migration;
    // Target -> whether a republish asked for it (sent even when unchanged).
    private final Map<Target, Boolean> queue = new LinkedHashMap<>();
    private String prefix;
    private @Nullable String bridgeHash;
    private double tokens = MAX_PER_SECOND;
    private long refilledAt;
    private boolean stopped;

    /**
     * @param topics          the bridge's topics, which move when the bridge id changes
     * @param dedicatedServer resolves {@code ha.expose} AUTO
     * @param clock           milliseconds, for the rate limit and the migration's delays
     */
    public DiscoveryPublisher(DeviceRegistry registry, MqttService mqtt, Supplier<TopicScheme> topics, Identity identity,
                              boolean dedicatedServer, LongSupplier clock) {
        this.registry = registry;
        this.mqtt = mqtt;
        this.topics = topics;
        this.identity = identity;
        this.dedicatedServer = dedicatedServer;
        this.clock = clock;
        this.prefix = MineQTTConfig.discoveryPrefix;
        this.refilledAt = clock.getAsLong();
        this.migration = new LegacyDiscoveryMigration(this, registry, mqtt, clock);
    }

    // ---- payloads ----

    /** The device discovery message of a device (§4.1). */
    public static JsonObject deviceConfig(DeviceType type, DeviceRecord record, TopicScheme topics, Identity identity) {
        String state = topics.device(record.slug());
        List<String> availability = List.of(topics.bridgeState(), topics.availability(record.slug()));
        return config(record.objectId(), record.name(), type.model(), type, identity, availability, type.entities(),
                new EntityTopics(state, state + "/set", topics.action(record.slug())), record.legacyUniqueId());
    }

    /**
     * The device discovery message of a group: the member type's command entities on the group's topics. A group has
     * no availability of its own, only the bridge's.
     */
    public static JsonObject groupConfig(DeviceType type, DeviceGroup group, TopicScheme topics, Identity identity) {
        String state = topics.group(group.slug());
        return config(group.objectId(), group.name(), type.model() + " group", type, identity, List.of(topics.bridgeState()),
                groupEntities(type), new EntityTopics(state, state + "/set", null), group.legacyUniqueId());
    }

    /** {@code {"name":"MineQTT","sw":...,"url":...}}, the origin of every message. */
    static JsonObject origin(String version) {
        JsonObject origin = new JsonObject();
        origin.addProperty("name", MANUFACTURER);
        origin.addProperty("sw", version);
        origin.addProperty("url", URL);
        return origin;
    }

    // The topics an entity reads and commands; action is null for groups, which have no events.
    private record EntityTopics(String state, String set, @Nullable String action) {
        String set(String key) {
            return set + "/" + key;
        }
    }

    private static List<HaEntity<?>> groupEntities(DeviceType type) {
        return type.entities().stream().filter(entity -> entity.commands() && !(entity instanceof HaEntity.DeviceTrigger)).toList();
    }

    private static JsonObject config(String objectId, String name, String model, DeviceType type, Identity identity,
                                     List<String> availability, List<HaEntity<?>> entities, EntityTopics entityTopics,
                                     @Nullable String legacyUniqueId) {
        JsonObject device = new JsonObject();
        // v1 named its light's device after the unique_id: carrying it makes Home Assistant reuse that device entry
        // (device id, area, device automations) instead of leaving it empty. HA never drops a merged identifier.
        device.add("ids", strings(legacyUniqueId == null ? List.of(objectId) : List.of(objectId, legacyUniqueId)));
        device.addProperty("name", name);
        device.addProperty("mf", MANUFACTURER);
        device.addProperty("mdl", model);
        device.addProperty("mdl_id", type.id().toString());
        device.addProperty("sw", identity.version());
        device.addProperty("via_device", identity.bridgeObjectId());
        JsonObject config = new JsonObject();
        config.add("dev", device);
        config.add("o", origin(identity.version()));
        JsonArray avty = new JsonArray();
        for (String topic : availability) {
            JsonObject entry = new JsonObject();
            entry.addProperty("t", topic);
            avty.add(entry);
        }
        config.add("avty", avty);
        config.addProperty("avty_mode", "all");
        config.addProperty("qos", 1);
        HaEntity<?> primary = entities.stream().filter(entity -> entity.meta().name() == null).findFirst()
                .orElse(entities.isEmpty() ? null : entities.getFirst());
        JsonObject components = new JsonObject();
        for (HaEntity<?> entity : entities) {
            String uniqueId = entity == primary && legacyUniqueId != null ? legacyUniqueId : objectId + "_" + entity.key();
            components.add(entity.key(), component(entity, uniqueId, entityTopics));
        }
        config.add("cmps", components);
        return config;
    }

    private static JsonObject component(HaEntity<?> entity, String uniqueId, EntityTopics topics) {
        JsonObject component = new JsonObject();
        component.addProperty("p", entity.platform());
        if (!(entity instanceof HaEntity.DeviceTrigger)) {
            component.addProperty("unique_id", uniqueId);
            component.add("name", entity.meta().name() == null ? JsonNull.INSTANCE : new JsonPrimitive(entity.meta().name()));
        }
        switch (entity) {
            case HaEntity.Light light -> {
                component.addProperty("schema", "json");
                component.addProperty("state_topic", topics.state());
                component.addProperty("command_topic", topics.set());
                component.addProperty("brightness", light.brightness());
                if (light.brightness()) component.addProperty("brightness_scale", 255);
                component.add("supported_color_modes", strings(light.colorModes()));
                if (light.colorModes().contains("color_temp")) {
                    component.addProperty("color_temp_kelvin", true);
                    component.addProperty("min_kelvin", light.minKelvin());
                    component.addProperty("max_kelvin", light.maxKelvin());
                }
                if (!light.effects().isEmpty()) {
                    component.addProperty("effect", true);
                    component.add("effect_list", strings(light.effects()));
                }
                // No MineQTT light flashes. Home Assistant 2026.10 assumes a JSON light does unless told, 2025.2 knows no
                // such key and drops it. Both assume transitions, which the lamp fades.
                component.addProperty("flash", false);
            }
            case HaEntity.Switch<?> switchEntity -> {
                // Switch commands use the state key (z2m style); the type maps ON and OFF onto its field.
                component.addProperty("state_topic", topics.state());
                component.addProperty("command_topic", topics.set());
                component.addProperty("value_template", "{{ 'OFF' if " + jsonPath(switchEntity.field().key()) + " == "
                        + literal(offValue(switchEntity)) + " else 'ON' }}");
                component.addProperty("payload_on", "{\"state\":\"ON\"}");
                component.addProperty("payload_off", "{\"state\":\"OFF\"}");
                component.addProperty("state_on", "ON");
                component.addProperty("state_off", "OFF");
                if (switchEntity.deviceClass() != null) component.addProperty("device_class", switchEntity.deviceClass());
            }
            case HaEntity.Number number -> {
                component.addProperty("state_topic", topics.state());
                component.addProperty("value_template", valueTemplate(number.field()));
                component.addProperty("command_topic", topics.set(number.field().key()));
                component.addProperty("min", number.min());
                component.addProperty("max", number.max());
                component.addProperty("step", number.step());
                if (number.sliderMode()) component.addProperty("mode", "slider");
                if (number.unit() != null) component.addProperty("unit_of_measurement", number.unit());
            }
            case HaEntity.BinarySensor binarySensor -> {
                component.addProperty("state_topic", topics.state());
                component.addProperty("value_template", "{{ 'ON' if " + jsonPath(binarySensor.field().key()) + " else 'OFF' }}");
                if (binarySensor.deviceClass() != null) component.addProperty("device_class", binarySensor.deviceClass());
            }
            case HaEntity.Sensor sensor -> {
                component.addProperty("state_topic", topics.state());
                component.addProperty("value_template", sensor.valueTemplate() != null ? sensor.valueTemplate() : valueTemplate(sensor.field()));
                if (sensor.unit() != null) component.addProperty("unit_of_measurement", sensor.unit());
                if (sensor.deviceClass() != null) component.addProperty("device_class", sensor.deviceClass());
                if (sensor.stateClass() != null) component.addProperty("state_class", sensor.stateClass());
            }
            case HaEntity.Select select -> {
                component.addProperty("state_topic", topics.state());
                component.addProperty("value_template", valueTemplate(select.field()));
                component.addProperty("command_topic", topics.set(select.field().key()));
                component.add("options", strings(select.options()));
            }
            case HaEntity.Event event -> {
                component.addProperty("state_topic", topics.action());
                component.add("event_types", strings(event.eventTypes()));
                if (event.deviceClass() != null) component.addProperty("device_class", event.deviceClass());
            }
            case HaEntity.Button button -> {
                component.addProperty("command_topic", topics.set(button.key()));
                component.addProperty("payload_press", "PRESS");
            }
            case HaEntity.Text text -> {
                component.addProperty("state_topic", topics.state());
                component.addProperty("value_template", valueTemplate(text.field()));
                component.addProperty("command_topic", topics.set(text.field().key()));
                component.addProperty("max", text.maxLength());
            }
            case HaEntity.Notify notify -> component.addProperty("command_topic", topics.set(notify.key()));
            case HaEntity.DeviceTrigger trigger -> {
                component.addProperty("automation_type", "trigger");
                component.addProperty("topic", topics.action());
                component.addProperty("type", trigger.type());
                component.addProperty("subtype", trigger.subtype());
                component.addProperty("payload", trigger.payload());
                component.addProperty("value_template", "{{ value_json.event_type }}_{{ " + jsonPath(trigger.attribute()) + " }}");
            }
        }
        // Commands are never retained (design D3).
        if (entity.commands()) component.addProperty("retain", false);
        if (!(entity instanceof HaEntity.DeviceTrigger)) {
            switch (entity.meta().category()) {
                case CONFIG -> component.addProperty("entity_category", "config");
                case DIAGNOSTIC -> component.addProperty("entity_category", "diagnostic");
                case NONE -> {
                }
            }
            if (!entity.meta().enabledByDefault()) component.addProperty("enabled_by_default", false);
        }
        return component;
    }

    private static String valueTemplate(Field<?> field) {
        if (field == CommonFields.LOCATION) {
            return "{{ value_json.location.dimension }} {{ value_json.location.pos | join(',') }}";
        }
        return "{{ " + jsonPath(field.key()) + " }}";
    }

    private static String jsonPath(String key) {
        return DICT_METHODS.contains(key) ? "value_json['" + key + "']" : "value_json." + key;
    }

    private static <T> JsonElement offValue(HaEntity.Switch<T> switchEntity) {
        return switchEntity.field().codec().encodeStart(JsonOps.INSTANCE, switchEntity.off()).getOrThrow();
    }

    // A JSON scalar as a Jinja literal.
    private static String literal(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return "'" + value.getAsString().replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        return value.toString();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /** The hash stored in the registry for a payload. */
    static String hash(JsonObject config) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(config.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }

    // ---- publishing ----

    /**
     * After every CONNACK, before the republish: the v1 migrations of the lost connection are abandoned (they start
     * again after the state, {@link #startMigrations()}), and the v1 status is cleared once.
     */
    public void connected() {
        if (!stopped) migration.connected();
    }

    /** The bridge, every device and every group, sent even when unchanged. */
    public void republishAll() {
        if (stopped) return;
        enqueue(Target.BRIDGE, true);
        registry.records().forEach(record -> enqueue(Target.device(record.id()), true));
        registry.groups().forEach(group -> enqueue(Target.group(group.id()), true));
    }

    /** §4.2 step 6, once the state after a connect is out: the pending v1 discovery migrations. */
    public void startMigrations() {
        if (!stopped) migration.start();
    }

    /** A device changed (attach, exposure, group membership): published when its payload changed. */
    public void refresh(DeviceRecord record) {
        enqueue(Target.device(record.id()), false);
    }

    public void refresh(DeviceGroup group) {
        enqueue(Target.group(group.id()), false);
    }

    /** A renamed device (§3.1 step 2): published at once, between the clears and the state on the new topic. */
    public void publishNow(DeviceRecord record) {
        if (stopped) return;
        queue.remove(Target.device(record.id()));
        process(Target.device(record.id()), false);
    }

    /** A renamed group (M2-17): published at once, between the clear of its old state and the state on its new topic. */
    public void publishNow(DeviceGroup group) {
        if (stopped) return;
        queue.remove(Target.group(group.id()));
        process(Target.group(group.id()), false);
    }

    /** A device is gone: its retained discovery, and a v1 message still waiting for its migration, are cleared. */
    public void removed(DeviceRecord record) {
        queue.remove(Target.device(record.id()));
        clearRemoved(record.objectId(), record.legacyUniqueId());
    }

    public void removed(DeviceGroup group) {
        queue.remove(Target.group(group.id()));
        clearRemoved(group.objectId(), group.legacyUniqueId());
    }

    /**
     * {@code /mineqtt discovery clear}: clears the retained discovery of the bridge, every device and every group.
     * Nothing is published again until the next republish or change.
     *
     * @return the number of retained topics cleared
     */
    public int clearAll() {
        if (stopped) return 0;
        queue.clear();
        List<String> objectIds = new ArrayList<>();
        objectIds.add(identity.bridgeObjectId());
        bridgeHash = null;
        for (DeviceRecord record : registry.records()) {
            objectIds.add(record.objectId());
            record.setDiscoveryHash(null);
        }
        for (DeviceGroup group : registry.groups()) {
            objectIds.add(group.objectId());
            group.setDiscoveryHash(null);
        }
        registry.markDirty();
        objectIds.forEach(objectId -> mqtt.publish(Outbound.clear(TopicScheme.discoveryConfig(prefix, objectId))));
        return objectIds.size();
    }

    /**
     * After a config change: a new discovery prefix clears every message under the old one and publishes them all
     * under the new one; otherwise the exposure may have changed, so every message is checked again.
     */
    public void configChanged() {
        if (stopped) return;
        if (!prefix.equals(MineQTTConfig.discoveryPrefix)) {
            if (bridgeHash != null) mqtt.publish(Outbound.clear(TopicScheme.discoveryConfig(prefix, identity.bridgeObjectId())));
            bridgeHash = null;
            for (DeviceRecord record : registry.records()) {
                if (record.discoveryHash() != null) mqtt.publish(Outbound.clear(TopicScheme.discoveryConfig(prefix, record.objectId())));
                record.setDiscoveryHash(null);
            }
            for (DeviceGroup group : registry.groups()) {
                if (group.discoveryHash() != null) mqtt.publish(Outbound.clear(TopicScheme.discoveryConfig(prefix, group.objectId())));
                group.setDiscoveryHash(null);
            }
            registry.markDirty();
            prefix = MineQTTConfig.discoveryPrefix;
            republishAll();
            return;
        }
        enqueue(Target.BRIDGE, false);
        registry.records().forEach(this::refresh);
        registry.groups().forEach(this::refresh);
    }

    /**
     * {@code SERVER_POST}: releases queued messages within the rate limit and advances the v1 migrations. While the
     * broker is not connected the queue waits: the republish after the next connect sends everything anyway.
     */
    public void tick() {
        if (stopped || !mqtt.state().isConnected()) return;
        for (Iterator<Map.Entry<Target, Boolean>> waiting = queue.entrySet().iterator(); waiting.hasNext(); ) {
            if (!hasToken()) break;
            Map.Entry<Target, Boolean> next = waiting.next();
            waiting.remove();
            if (process(next.getKey(), next.getValue())) takeToken();
        }
        migration.tick();
    }

    /** Whether every queued message is out (§4.2 step 5 waits for it). */
    public boolean idle() {
        return queue.isEmpty();
    }

    /** Whether queued messages or v1 migration steps wait for the next tick. */
    public boolean hasPendingWork() {
        return !stopped && (!queue.isEmpty() || migration.hasPendingWork());
    }

    /** {@code SERVER_STOPPING}: nothing is published any more. */
    public void stop() {
        stopped = true;
        queue.clear();
    }

    /** The devices and groups whose discovery is retained, for {@code /mineqtt status}. */
    public long discovered() {
        return registry.records().stream().filter(record -> record.discoveryHash() != null).count()
                + registry.groups().stream().filter(group -> group.discoveryHash() != null).count();
    }

    public LegacyDiscoveryMigration migration() {
        return migration;
    }

    /** Whether the device with this record would be discovered now; never when its type is unknown to this MineQTT. */
    public boolean exposed(DeviceRecord record) {
        if (exposure() == HaExpose.NONE || DeviceTypes.get(record.type()) == null) return false;
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        if (group != null && !group.exposeMembers()) return false;
        return record.expose() != null ? record.expose() : exposure() == HaExpose.ALL;
    }

    /**
     * Whether the group would be discovered now: it has members of a known type with something to command, and under
     * OPT_IN a member whose own exposure is on (a player with {@code mayExpose} or a command turned it on) or a v1 topic
     * it replaces, whose light v1 discovered.
     */
    public boolean exposed(DeviceGroup group) {
        DeviceType type = group.memberType() == null ? null : DeviceTypes.get(group.memberType());
        if (exposure() == HaExpose.NONE || type == null || groupEntities(type).isEmpty()) return false;
        return exposure() == HaExpose.ALL || group.legacyTopic() != null || group.members().stream()
                .map(registry::record).anyMatch(member -> member != null && Boolean.TRUE.equals(member.expose()));
    }

    // ---- for the migration ----

    /** The v1 unique_id of a device or group of a known type whose old discovery still needs migrating; else null. */
    @Nullable String pendingLegacyId(Target target) {
        Subject subject = subject(target);
        return subject == null || subject.type() == null ? null : subject.legacyUniqueId();
    }

    boolean exposed(Target target) {
        Subject subject = subject(target);
        return subject != null && subject.exposed();
    }

    /** Step 2 of a migration: the new message, also when unchanged and although the v1 one is not cleared yet. */
    void publishMigrated(Target target) {
        Subject subject = subject(target);
        if (subject != null && subject.type() != null) publish(subject, true);
    }

    /** After a migration: whatever changed while its sequence ran (and was not published then) goes out now. */
    void refresh(Target target) {
        enqueue(target, false);
    }

    /**
     * Takes one message from the rate limit, which the v1 migrations and the ghost cleanup share; false when the second's
     * budget is spent.
     */
    public boolean tryTakeToken() {
        if (!hasToken()) return false;
        takeToken();
        return true;
    }

    // ---- internals ----

    private HaExpose exposure() {
        return MineQTTConfig.haExpose(dedicatedServer);
    }

    private void enqueue(Target target, boolean force) {
        if (!stopped) queue.merge(target, force, Boolean::logicalOr);
    }

    // True when a message was published.
    private boolean process(Target target, boolean force) {
        if (target.kind() == Target.Kind.BRIDGE) return processBridge(force);
        Subject subject = subject(target);
        if (subject == null || subject.type() == null) return false;
        if (subject.legacyUniqueId() != null) {
            migration.request(target);
            return false;
        }
        return publish(subject, force);
    }

    private boolean processBridge(boolean force) {
        String topic = TopicScheme.discoveryConfig(prefix, identity.bridgeObjectId());
        if (exposure() == HaExpose.NONE) {
            // Not persisted, so a republish clears it in case an earlier run published it.
            if (bridgeHash == null && !force) return false;
            mqtt.publish(Outbound.clear(topic));
            bridgeHash = null;
            return true;
        }
        JsonObject config = BridgeDevice.discovery(topics.get(), identity);
        String hash = hash(config);
        if (!force && hash.equals(bridgeHash)) return false;
        Outbound message = Outbound.state(topic, config);
        mqtt.publish(force ? message.asRefresh() : message);
        bridgeHash = hash;
        return true;
    }

    private boolean publish(Subject subject, boolean force) {
        String topic = TopicScheme.discoveryConfig(prefix, subject.objectId());
        if (!subject.exposed()) {
            if (subject.hash() == null) return false;
            mqtt.publish(Outbound.clear(topic));
            subject.setHash().accept(null);
            registry.markDirty();
            return true;
        }
        JsonObject config = subject.config().get();
        String hash = hash(config);
        if (!force && hash.equals(subject.hash())) return false;
        Outbound message = Outbound.state(topic, config);
        mqtt.publish(force ? message.asRefresh() : message);
        if (!hash.equals(subject.hash())) {
            subject.setHash().accept(hash);
            registry.markDirty();
        }
        return true;
    }

    private void clearRemoved(String objectId, @Nullable String legacyUniqueId) {
        if (stopped) return;
        mqtt.publish(Outbound.clear(TopicScheme.discoveryConfig(prefix, objectId)));
        if (legacyUniqueId != null && !registry.legacyDiscoveryMigrated(legacyUniqueId)) {
            migration.forget(legacyUniqueId);
            mqtt.publish(Outbound.clear(LegacyDiscoveryMigration.v1Topic(legacyUniqueId)));
        }
    }

    private @Nullable Subject subject(Target target) {
        return switch (target.kind()) {
            case BRIDGE -> null;
            case DEVICE -> {
                DeviceRecord record = registry.record(target.id());
                yield record == null ? null : subject(record);
            }
            case GROUP -> {
                DeviceGroup group = registry.group(target.id());
                yield group == null ? null : subject(group);
            }
        };
    }

    private Subject subject(DeviceRecord record) {
        DeviceType type = DeviceTypes.get(record.type());
        return new Subject(record.objectId(), type, exposed(record), pendingLegacy(record.legacyUniqueId()), record.discoveryHash(),
                record::setDiscoveryHash, () -> deviceConfig(type, record, topics.get(), identity));
    }

    private Subject subject(DeviceGroup group) {
        DeviceType type = group.memberType() == null ? null : DeviceTypes.get(group.memberType());
        return new Subject(group.objectId(), type, exposed(group), pendingLegacy(group.legacyUniqueId()), group.discoveryHash(),
                group::setDiscoveryHash, () -> groupConfig(type, group, topics.get(), identity));
    }

    private @Nullable String pendingLegacy(@Nullable String legacyUniqueId) {
        return legacyUniqueId == null || registry.legacyDiscoveryMigrated(legacyUniqueId) ? null : legacyUniqueId;
    }

    private boolean hasToken() {
        long now = clock.getAsLong();
        tokens = Math.min(MAX_PER_SECOND, tokens + (now - refilledAt) * MAX_PER_SECOND / 1000.0);
        refilledAt = now;
        return tokens >= 1;
    }

    private void takeToken() {
        tokens -= 1;
    }
}

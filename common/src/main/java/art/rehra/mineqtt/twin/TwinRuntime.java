package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.ha.BirthWatcher;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.SharedSubscriptions;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.CatalogParser;
import art.rehra.mineqtt.twin.core.EntityDocument;
import art.rehra.mineqtt.twin.core.EntityDocuments;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import art.rehra.mineqtt.twin.core.EntitySearch;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.core.HaDomains;
import art.rehra.mineqtt.twin.core.StatestreamTopic;
import art.rehra.mineqtt.twin.core.StatestreamValue;
import art.rehra.mineqtt.twin.core.TwinCoalescer;
import art.rehra.mineqtt.twin.core.TwinHealth;
import com.google.gson.JsonElement;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The read side of the Home Assistant twin on one server (M3 plan §3.4 step 1, D1, D3, D9, D17), owned by the
 * {@code MineqttRuntime}: the statestream subscriptions of the loaded devices that mirror an entity, the shared
 * {@link EntityDocuments} they fold into, and when a changed entity is applied.
 * <ul>
 *     <li><b>Subscriptions.</b> A device that mirrors an entity holds that entity's statestream leaves
 *     ({@link TwinAdapter#leaves}: its state, {@code last_updated} and exactly the attributes it reads, never {@code +}),
 *     each a {@link SubOptions#MIRROR} subscription the ACL allows ({@link TopicAcl#checkMirror}), shared by every device
 *     that needs it ({@link SharedSubscriptions}), from its attach to its detach. The last device to let go of an entity
 *     closes its leaves and forgets its document.</li>
 *     <li><b>Applying.</b> A changed entity waits for its burst to settle and applies at most 4 times a second
 *     ({@link TwinCoalescer}); a device that starts to mirror an entity already known gets it at once. Applying hands the
 *     document to the devices ({@link Devices#twinApply}), which emit it over their derived wires in a {@code TWIN}
 *     extent. Reading never sends anything: this class has no way to publish.</li>
 *     <li><b>Freshness.</b> Home Assistant's birth ({@link BirthWatcher}'s listener calls {@link #haBirth}) makes every
 *     value retained until a live one arrives (D3).</li>
 *     <li><b>The catalog</b> ({@link HaEntityCatalog}, M3-11): the catalog blueprint's topics fold into the same documents,
 *     which switches the read gate to catalog mode (an entity must be labelled in Home Assistant, D3), replaces stale
 *     retained values by the snapshot, and says which entities exist; without it a statestream scan lists them. A bind
 *     to an entity the catalog does not share is refused ({@link #shareProblem}); one that leaves it reads nothing and
 *     shows {@code NOT_SHARED}.</li>
 *     <li><b>MQTT device presets</b> (M3-17): a device that mirrors a preset source holds its state topic, one
 *     {@link SubOptions#MIRROR} subscription the ACL allows for the device's owner ({@link TopicAcl#checkTopicSource}),
 *     shared by the devices on that topic. The last payload is kept per topic, apart from the Home Assistant documents
 *     and their read gate, and each device reads it through its own preset ({@link BindingPreset#document}), applied
 *     like an entity: after its burst settles, at most 4 times a second.</li>
 * </ul>
 * {@link #drain} runs after each inbound drain, also in a paused server's pump. Server thread only.
 */
public final class TwinRuntime {
    // Leaves the ACL refused, logged once each per run.
    private static final int MAX_LOGGED_REFUSALS = 256;

    /** Where applied entities go: the device runtime. */
    public interface Devices {
        /** Emits {@code document} to the devices that mirror {@code entityId}, in a {@code TWIN} extent. */
        void twinApply(String entityId, EntityDocument document);

        /** The health of the devices that mirror these entities may have changed (D6). */
        void twinHealthChanged(Collection<String> entityIds);

        /** Emits the new payload of a preset source's state topic to the devices that mirror it, in a {@code TWIN} extent (M3-17). */
        default void topicApply(String topic, TopicValue value) {
        }

        /** The health of the devices that mirror these state topics may have changed. */
        default void topicHealthChanged(Collection<String> topics) {
        }
    }

    /**
     * The last payload on a preset source's state topic (M3-17), as it came.
     *
     * @param receivedAt when it came, in the runtime's clock
     */
    public record TopicValue(byte[] payload, boolean retained, long receivedAt) {
        /** A short fingerprint of the payload, which the bus's change filter compares instead of the payload itself. */
        public String digest() {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(payload);
                return java.util.HexFormat.of().formatHex(hash, 0, 16) + (payload.length == 0 ? "-cleared" : "");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is missing", e);
            }
        }
    }

    /**
     * What one device holds.
     *
     * @param topics its leaves' topics under the base it subscribed with
     */
    private record Holding(String entityId, List<String> leaves, Set<String> topics) {
    }

    /**
     * What {@code /mineqtt twin status} shows.
     *
     * @param devices       devices mirroring an entity (source holdings)
     * @param targetDevices devices whose targets switch entities (M3-14)
     * @param catalog       what the catalog blueprint published, and the fallback scan
     * @param topicDevices  devices mirroring an MQTT device preset's state topic (M3-17)
     * @param topics        the state topics they hold
     * @param refusedTopics state topics the ACL refused
     */
    public record Status(String base, int devices, int targetDevices, int entities, int documents, int subscriptions, int refusedLeaves,
                         EntityDocuments.Counters counters, long applied, Map<TwinHealth, Integer> health, CatalogStatus catalog,
                         int topicDevices, int topics, int refusedTopics) {
    }

    /**
     * The catalog's side of the status (D3, D4).
     *
     * @param open      whether the catalog's topics are subscribed
     * @param status    the catalog blueprint's last status; null when none (statestream-only mode)
     * @param stale     whether that status is older than 30 min (the automation may be off)
     * @param entities  entities the catalog lists
     * @param refusals  domain lists refused, and why
     * @param scanOpen  whether the fallback scan is subscribed
     * @param scanned   entities the scan saw
     * @param scanOverflow entities the scan left out (over {@code twin.catalogMax})
     * @param drifting  mirrored entities Home Assistant changes but statestream does not publish
     */
    public record CatalogStatus(boolean open, @Nullable CatalogParser.Status status, boolean stale, int entities,
                                Map<String, CatalogParser.Refusal> refusals, boolean scanOpen, int scanned, long scanOverflow,
                                List<String> drifting) {
    }

    private final MqttService mqtt;
    private final Supplier<TopicAcl> acl;
    private final boolean dedicated;
    private final LongSupplier clock;
    // Real time, for the catalog status's age (its timestamp is Home Assistant's wall clock).
    private final LongSupplier wallClock = System::currentTimeMillis;
    private final TwinCoalescer coalescer = new TwinCoalescer();
    private final Map<UUID, Holding> holdings = new HashMap<>();
    // device -> the holder key under which each entity its targets switch is held (M3-14); those holdings are in `holdings`
    private final Map<UUID, Map<String, UUID>> targetHolders = new HashMap<>();
    // entity -> the devices holding it
    private final Map<String, Set<UUID>> holders = new HashMap<>();
    // Entities a device started to mirror while their document was known: applied at the next drain.
    private final Set<String> fresh = new LinkedHashSet<>();
    private final Set<String> loggedRefusals = new HashSet<>();
    // Preset sources (M3-17): device -> its state topic, topic -> the devices on it, topic -> its last payload.
    private final Map<UUID, String> topicHoldings = new HashMap<>();
    private final Map<String, Set<UUID>> topicHolders = new HashMap<>();
    private final Map<String, TopicValue> topicValues = new HashMap<>();
    private final TwinCoalescer topicCoalescer = new TwinCoalescer();
    private final Set<String> freshTopics = new LinkedHashSet<>();
    private final SharedSubscriptions<UUID> topicSubscriptions;
    private int refusedTopics;
    private Devices devices = new Devices() {
        @Override
        public void twinApply(String entityId, EntityDocument document) {
        }

        @Override
        public void twinHealthChanged(Collection<String> entityIds) {
        }
    };
    private String base;
    private EntityDocuments documents;
    private SharedSubscriptions<UUID> leaves;
    private final HaEntityCatalog catalog;
    // What the catalog was last set up with: a reload changing any of it sets it up again.
    private String catalogSetup;
    private long drains;
    private long applied;
    private int refusedLeaves;
    private boolean stopped;

    /**
     * @param acl   the current ACL, which follows config reloads
     * @param clock milliseconds
     */
    public TwinRuntime(MqttService mqtt, Supplier<TopicAcl> acl, boolean dedicated, LongSupplier clock) {
        this.mqtt = mqtt;
        this.acl = acl;
        this.dedicated = dedicated;
        this.clock = clock;
        this.base = MineQTTConfig.twinStatestreamBase;
        this.documents = newDocuments();
        this.leaves = new SharedSubscriptions<>(mqtt, SubOptions.MIRROR, this::received);
        this.topicSubscriptions = new SharedSubscriptions<>(mqtt, SubOptions.MIRROR, this::receivedTopic);
        this.catalog = new HaEntityCatalog(mqtt, new HaEntityCatalog.Host() {
            @Override
            public String base() {
                return base;
            }

            @Override
            public boolean twinOn() {
                return MineQTTConfig.twinOffReason(TwinRuntime.this.dedicated).isEmpty();
            }

            @Override
            public boolean catalogEnabled() {
                return MineQTTConfig.twinCatalog;
            }

            @Override
            public boolean catalogMode() {
                return documents.catalogMode();
            }

            @Override
            public int maxEntities() {
                return MineQTTConfig.twinCatalogMax;
            }

            @Override
            public boolean mayMirror(String filter) {
                TopicAcl.Decision decision = TwinRuntime.this.acl.get().checkMirror(TopicAcl.Subject.CONSOLE, filter);
                if (!decision.allowed()) MineQTT.LOGGER.warn("The Home Assistant twin does not subscribe to {}: {}", filter, decision.message());
                return decision.allowed();
            }
        }, this::received);
        this.catalogSetup = catalogSetup();
    }

    /**
     * After every CONNACK (D4): the first one schedules the catalog's own SUBSCRIBE 5 s later and the fallback scan's 10 s
     * later.
     */
    public void connected() {
        if (!stopped) catalog.connected(clock.getAsLong());
    }

    /**
     * Home Assistant announced a (re)start ({@link BirthWatcher}'s listener, D3): every value becomes retained until a live
     * value or a live catalog arrives, since statestream's listener attaches only after Home Assistant has started.
     */
    public void haBirth() {
        if (stopped) return;
        Set<String> changed = documents.haBirth();
        if (!changed.isEmpty()) devices.twinHealthChanged(changed.stream().filter(holders::containsKey).toList());
    }

    /**
     * Why {@code entityId} may not be bound now, or empty (D3, M3-11): in catalog mode an entity the catalog does not list
     * is not shared by Home Assistant's labels (also when its domain's list was refused). Without a catalog every entity
     * {@code twin.allowedEntities} lets through may be bound and shows {@code WAITING} until it is seen.
     */
    public Optional<String> shareProblem(String entityId) {
        return documents.shareProblem(entityId);
    }

    /**
     * The entities a search finds (D4, {@code /mineqtt twin entities}, the picker): from the catalog while one is received,
     * else from the fallback scan and what mirrored devices read; only entities the read gate lets through, of readable
     * domains, ranked by {@link EntitySearch}.
     *
     * @param domains the domains the device can use, most preferred first; empty for any
     */
    public List<EntitySearch.Candidate> entities(String query, List<String> domains, Set<String> recent, int limit) {
        return EntitySearch.search(readableCandidates(), query, domains, recent, limit);
    }

    /** Whether any entity may be listed at all (D4's empty-catalog help shows otherwise). */
    public boolean anyEntities() {
        return !readableCandidates().isEmpty();
    }

    /**
     * What a listing shows of {@code entityId} (its name, area and state), if the read gate lets it through and the
     * catalog, the scan or a mirroring device knows it; the Twin tab names bound entities with it.
     */
    public Optional<EntitySearch.Candidate> known(String entityId) {
        return Optional.ofNullable(candidates().get(entityId)).filter(candidate -> !EntitySearch.readable(List.of(candidate), documents, allowed()).isEmpty());
    }

    /** Whether Home Assistant's catalog decides what may be bound now (D3): its status was received. */
    public boolean catalogMode() {
        return documents.catalogMode();
    }

    // The entities a listing may show, the read gate applied.
    private List<EntitySearch.Candidate> readableCandidates() {
        return EntitySearch.readable(candidates().values(), documents, allowed());
    }

    // From the catalog while one is received, else from the fallback scan and what mirrored devices read.
    private Map<String, EntitySearch.Candidate> candidates() {
        return EntitySearch.candidates(documents, catalog.scan(), holders.keySet());
    }

    /** Whether a mirrored entity drifts (D3): Home Assistant changes it, statestream does not publish it. */
    public boolean drifting(String entityId) {
        return holders.containsKey(entityId) && documents.drifting(entityId);
    }

    /** Where applied entities go; set once by the device runtime. */
    public void setDevices(Devices devices) {
        this.devices = Objects.requireNonNull(devices, "devices");
    }

    /**
     * {@code device} mirrors {@code entityId} now, reading these statestream leaves; a holding it had before is replaced.
     * Its leaves are subscribed where the ACL allows them, and an entity already known is applied at the next drain.
     */
    public void hold(UUID device, String entityId, List<String> leafNames) {
        if (stopped) return;
        Holding before = holdings.get(device);
        Holding next = new Holding(entityId, List.copyOf(leafNames), topics(entityId, leafNames));
        if (next.equals(before)) return;
        if (before != null) {
            for (String topic : before.topics()) {
                if (!next.topics().contains(topic)) leaves.release(device, topic);
            }
            if (!before.entityId().equals(entityId)) letGo(device, before.entityId());
        }
        holdings.put(device, next);
        boolean newHolder = holders.computeIfAbsent(entityId, key -> new LinkedHashSet<>()).add(device);
        for (String topic : next.topics()) subscribe(device, topic);
        if (newHolder && documents.document(entityId).isPresent() && !coalescer.pending(entityId)) fresh.add(entityId);
    }

    /**
     * A binding of {@code entityId} changed how it reads (e.g. {@code twin.unavailable}): the entity is applied again at the
     * next drain, if it is held and known, so the device shows the document as it reads it now.
     */
    public void reapply(String entityId) {
        if (!stopped && holders.containsKey(entityId) && documents.document(entityId).isPresent() && !coalescer.pending(entityId)) fresh.add(entityId);
    }

    /** {@code device} mirrors nothing any more: detached, unbound or refused. Nothing when it held nothing. */
    public void release(UUID device) {
        releaseTopic(device);
        releaseEntity(device);
    }

    /** {@code device} mirrors no MQTT device preset's state topic any more (M3-17). */
    public void releaseTopic(UUID device) {
        String topic = topicHoldings.get(device);
        if (topic != null) letGoTopic(device, topic);
    }

    /** {@code device} mirrors no Home Assistant entity any more. */
    public void releaseEntity(UUID device) {
        Holding before = holdings.remove(device);
        if (before == null) return;
        leaves.releaseAll(device);
        letGo(device, before.entityId());
    }

    // A device no longer mirrors an entity; its last holder forgets it, as nothing keeps it current any more.
    private void letGo(UUID device, String entityId) {
        Set<UUID> devices = holders.get(entityId);
        if (devices == null || !devices.remove(device) || !devices.isEmpty()) return;
        holders.remove(entityId);
        coalescer.forget(entityId);
        fresh.remove(entityId);
        documents.forget(entityId);
    }

    /**
     * {@code device}'s targets switch these entities now (M3-14): each entity's state and {@code last_updated} are held as
     * a mirror's are, so a toggle knows the entity's state, the dedupe sees a LIVE value and a request ends when the entity
     * changes (D6, D11). An empty set lets go of them all.
     */
    public void holdTargets(UUID device, Set<String> entityIds) {
        Map<String, UUID> before = targetHolders.getOrDefault(device, Map.of());
        Map<String, UUID> next = new LinkedHashMap<>();
        if (!stopped) {
            for (String entity : entityIds) {
                next.put(entity, before.getOrDefault(entity, UUID.nameUUIDFromBytes(("target|" + device + "|" + entity).getBytes(StandardCharsets.UTF_8))));
            }
        }
        before.forEach((entity, holder) -> {
            if (!next.containsKey(entity)) release(holder);
        });
        next.forEach((entity, holder) -> hold(holder, entity, targetLeaves(entity)));
        if (next.isEmpty()) targetHolders.remove(device);
        else targetHolders.put(device, next);
    }

    // A target entity's state and timestamp, where its domain may be read.
    private static List<String> targetLeaves(String entityId) {
        int dot = entityId.indexOf('.');
        return HaDomains.domain(dot < 0 ? entityId : entityId.substring(0, dot))
                .map(domain -> List.of(HaDomains.STATE_LEAF, StatestreamTopic.LAST_UPDATED).stream().filter(domain::readableLeaf).toList())
                .orElse(List.of());
    }

    /**
     * {@code device} mirrors the state topic of an MQTT device preset now (M3-17), for its owner {@code owner}; a topic it
     * held before is let go. The topic is subscribed if the ACL lets the owner's device mirror it; a payload already known
     * is applied at the next drain.
     */
    public void holdTopic(UUID device, String topic, TopicAcl.Subject owner) {
        if (stopped) return;
        String before = topicHoldings.get(device);
        if (topic.equals(before)) return;
        if (before != null) letGoTopic(device, before);
        TopicAcl.Decision decision = acl.get().checkTopicSource(owner, topic);
        if (!decision.allowed()) {
            if (loggedRefusals.size() < MAX_LOGGED_REFUSALS && loggedRefusals.add("topic|" + topic)) {
                refusedTopics++;
                MineQTT.LOGGER.warn("A device does not mirror the MQTT topic {}: {}", topic, decision.message());
            }
            return;
        }
        topicHoldings.put(device, topic);
        boolean newHolder = topicHolders.computeIfAbsent(topic, key -> new LinkedHashSet<>()).add(device);
        topicSubscriptions.hold(device, topic);
        if (newHolder && topicValues.containsKey(topic) && !topicCoalescer.pending(topic)) freshTopics.add(topic);
    }

    /** A binding of {@code topic} changed how it reads: the topic applies again at the next drain, if it is held and known. */
    public void reapplyTopic(String topic) {
        if (!stopped && topicHolders.containsKey(topic) && topicValues.containsKey(topic) && !topicCoalescer.pending(topic)) freshTopics.add(topic);
    }

    // A device no longer mirrors a topic; its last holder forgets its payload.
    private void letGoTopic(UUID device, String topic) {
        topicHoldings.remove(device);
        topicSubscriptions.release(device, topic);
        Set<UUID> devices = topicHolders.get(topic);
        if (devices == null || !devices.remove(device) || !devices.isEmpty()) return;
        topicHolders.remove(topic);
        topicValues.remove(topic);
        topicCoalescer.forget(topic);
        freshTopics.remove(topic);
    }

    /** The state topic {@code device} mirrors now, if it holds one. */
    public Optional<String> heldTopic(UUID device) {
        return Optional.ofNullable(topicHoldings.get(device));
    }

    /**
     * The document {@code preset} makes of the last payload on {@code topic} (M3-17); empty while nothing came, or for a
     * topic nobody holds.
     */
    public Optional<EntityDocument> topicDocument(String topic, BindingPreset preset) {
        TopicValue value = topicValues.get(topic);
        return value == null ? Optional.empty() : Optional.of(preset.document(topic, value.payload(), value.retained(), value.receivedAt()));
    }

    /** The entity {@code device} mirrors now, if it holds one. */
    public Optional<String> held(UUID device) {
        return Optional.ofNullable(holdings.get(device)).map(Holding::entityId);
    }

    /**
     * The step after each inbound drain: opens the catalog's subscriptions when due, then applies the entities whose burst
     * settled within the rate, and those a device just started to mirror.
     */
    public void drain() {
        if (stopped) return;
        long now = clock.getAsLong();
        catalog.tick(now);
        Set<String> due = new LinkedHashSet<>(coalescer.due(drains, now));
        for (String entity : fresh) {
            if (due.add(entity)) coalescer.appliedNow(entity, now);
        }
        fresh.clear();
        Set<String> dueTopics = new LinkedHashSet<>(topicCoalescer.due(drains, now));
        for (String topic : freshTopics) {
            if (dueTopics.add(topic)) topicCoalescer.appliedNow(topic, now);
        }
        freshTopics.clear();
        drains++;
        List<String> changed = new ArrayList<>();
        for (String entity : due) {
            if (!holders.containsKey(entity)) continue;
            changed.add(entity);
            Optional<EntityDocument> document = documents.document(entity);
            if (document.isEmpty() || !readable(entity)) continue;
            applied++;
            devices.twinApply(entity, document.get());
        }
        if (!changed.isEmpty()) devices.twinHealthChanged(changed);
        List<String> changedTopics = new ArrayList<>();
        for (String topic : dueTopics) {
            TopicValue value = topicValues.get(topic);
            if (!topicHolders.containsKey(topic) || value == null) continue;
            changedTopics.add(topic);
            applied++;
            devices.topicApply(topic, value);
        }
        if (!changedTopics.isEmpty()) devices.topicHealthChanged(changedTopics);
    }

    /**
     * Whether a drain has work: an entity waits for its burst to settle or for the rate, a device for its first value, or
     * a catalog subscription to open.
     */
    public boolean pending() {
        return !stopped && (coalescer.pending() || !fresh.isEmpty() || catalog.pending() || topicCoalescer.pending() || !freshTopics.isEmpty());
    }

    /** The document of {@code entityId}, if one is known (only for entities a device mirrors, or the catalog lists). */
    public Optional<EntityDocument> document(String entityId) {
        return documents.document(entityId);
    }

    /**
     * Whether the read gate lets {@code entityId} through now (D3): {@code twin.allowedEntities}, and the catalog while
     * one is received.
     */
    public boolean readable(String entityId) {
        return documents.readable(entityId, allowed());
    }

    /**
     * What the twin knows of {@code entityId} for a write-back (D11): the freshness, state and whole of its document, or
     * {@link HaCalls.Mirror#NONE} when none is known (nobody loaded mirrors it) or the read gate refuses it now.
     */
    public HaCalls.Mirror mirror(String entityId) {
        Optional<EntityDocument> document = documents.document(entityId);
        if (document.isEmpty() || !readable(entityId)) return HaCalls.Mirror.NONE;
        return mirror(document.get());
    }

    /**
     * What the twin knows of a binding's source for a write-back: {@link #mirror(String)} of its entity, or for an MQTT
     * device preset the document its preset makes of the last payload (M3-17; {@link HaCalls.Mirror#NONE} before any).
     */
    public HaCalls.Mirror mirror(Binding binding) {
        Optional<String> entity = binding.sourceEntity();
        if (entity.isPresent()) return mirror(entity.get());
        return presetDocument(binding).map(TwinRuntime::mirror).orElse(HaCalls.Mirror.NONE);
    }

    private static HaCalls.Mirror mirror(EntityDocument known) {
        return new HaCalls.Mirror(known.freshness() == EntityDocument.Freshness.LIVE ? HaCalls.Mirror.Freshness.LIVE : HaCalls.Mirror.Freshness.RETAINED,
                known.state().wire(), known.toJson());
    }

    /** The document of a preset source's last payload through its loaded preset, if both are there. */
    public Optional<EntityDocument> presetDocument(Binding binding) {
        Optional<Binding.PresetRef> ref = binding.preset();
        if (ref.isEmpty()) return Optional.empty();
        Optional<BindingPreset> preset = BindingPresets.get(ref.get().id());
        return preset.flatMap(found -> topicDocument(binding.sourceTopic().orElseThrow(), found));
    }

    /**
     * How a binding's source is doing now (D6); an MQTT device preset's from its last payload (M3-17), which no read gate
     * holds back (its topic passed the ACL); {@link TwinHealth#WAITING} for any other source.
     */
    public TwinHealth health(Binding binding) {
        Optional<String> entity = binding.sourceEntity();
        if (entity.isPresent()) return TwinHealth.of(binding.active(), documents.document(entity.get()), readable(entity.get()));
        if (binding.preset().isPresent()) return TwinHealth.of(binding.active(), presetDocument(binding), true);
        return binding.active() ? TwinHealth.WAITING : TwinHealth.REFUSED;
    }

    /**
     * After a config reload, before the bindings are judged again: a new statestream base or entity limit starts the store
     * anew and subscribes every holding again under the new base. The entities known so far are forgotten; the broker's
     * retained replay brings them back.
     */
    public void configChanged() {
        if (stopped) return;
        String nextBase = MineQTTConfig.twinStatestreamBase;
        boolean sameStore = nextBase.equals(base) && documents.limits().maxEntities() == MineQTTConfig.twinCatalogMax;
        if (!sameStore) restartStore(nextBase);
        // The catalog follows the base, the limit, the twin turned on or off and twin.catalog.
        String setup = catalogSetup();
        if (!setup.equals(catalogSetup)) {
            catalogSetup = setup;
            catalog.configChanged();
            // twin.catalog off: nothing reads the catalog's topics any more, so a status and lists kept from before would
            // gate every entity on a frozen catalog and keep the fallback scan closed. The gate goes to statestream-only
            // mode (the scan opens at the next drain), and every held entity is judged again, as after a status.
            if (!MineQTTConfig.twinCatalog && documents.catalogMode()) {
                documents.clearCatalog();
                holders.keySet().forEach(entity -> coalescer.changed(entity, drains));
            }
        }
    }

    // What the catalog is set up from.
    private String catalogSetup() {
        return base + "|" + MineQTTConfig.twinCatalogMax + "|" + MineQTTConfig.twinOffReason(dedicated).isEmpty() + "|" + MineQTTConfig.twinCatalog;
    }

    // A new base or entity limit: a new store, and every holding subscribed again under the new base.
    private void restartStore(String nextBase) {
        MineQTT.LOGGER.info("The Home Assistant twin reads under '{}' now (was '{}'); subscribing {} devices again", nextBase, base, holdings.size());
        leaves.clear();
        coalescer.clear();
        fresh.clear();
        // The refused state topics of preset sources stay logged: they do not depend on the base.
        loggedRefusals.removeIf(refusal -> !refusal.startsWith("topic|"));
        refusedLeaves = 0;
        base = nextBase;
        documents = newDocuments();
        Map<UUID, Holding> again = new HashMap<>(holdings);
        holdings.clear();
        holders.clear();
        again.forEach((device, holding) -> hold(device, holding.entityId(), holding.leaves()));
    }

    /** {@code SERVER_STOPPING}: closes every subscription. */
    public void stop() {
        stopped = true;
        catalog.stop();
        leaves.clear();
        topicSubscriptions.clear();
        topicHoldings.clear();
        topicHolders.clear();
        topicValues.clear();
        topicCoalescer.clear();
        freshTopics.clear();
        holdings.clear();
        targetHolders.clear();
        holders.clear();
        coalescer.clear();
        fresh.clear();
    }

    /**
     * What {@code /mineqtt twin status} shows.
     *
     * @param bindings the working and refused bindings of the loaded devices, for the health counts
     */
    public Status status(Collection<Binding> bindings) {
        Map<TwinHealth, Integer> health = new TreeMap<>();
        for (Binding binding : bindings) health.merge(health(binding), 1, Integer::sum);
        CatalogParser.Status catalogStatus = documents.status().orElse(null);
        int listed = documents.catalog().values().stream().mapToInt(Map::size).sum();
        List<String> drifting = holders.keySet().stream().filter(documents::drifting).sorted().toList();
        CatalogStatus catalogSide = new CatalogStatus(catalog.catalogOpen(), catalogStatus,
                catalogStatus != null && catalogStatus.stale(Instant.ofEpochMilli(wallClock.getAsLong())), listed, documents.catalogRefusals(),
                catalog.scanOpen(), catalog.scan().size(), catalog.scan().overflow(), drifting);
        int targetHoldings = targetHolders.values().stream().mapToInt(Map::size).sum();
        return new Status(base, holdings.size() - targetHoldings, targetHolders.size(), holders.size(), documents.size(), leaves.filters().size(), refusedLeaves,
                documents.counters(), applied, health, catalogSide, topicHoldings.size(), topicHolders.size(), refusedTopics);
    }

    /** The topics of {@code entityId}'s leaves under the current base; none while the twin has no base. */
    private Set<String> topics(String entityId, List<String> leafNames) {
        Set<String> topics = new LinkedHashSet<>();
        if (base.isEmpty()) return topics;
        String prefix = documents.base() + "/" + entityId.replace('.', '/') + "/";
        for (String leaf : leafNames) topics.add(prefix + leaf);
        return topics;
    }

    // One leaf for one device, if the ACL lets the server mirror it: nothing filters a mirror's messages afterwards.
    private void subscribe(UUID device, String topic) {
        TopicAcl.Decision decision = acl.get().checkMirror(TopicAcl.Subject.CONSOLE, topic);
        if (!decision.allowed()) {
            // Each refused topic counts once per base, however often a device holds it again.
            if (loggedRefusals.size() < MAX_LOGGED_REFUSALS && loggedRefusals.add(topic)) {
                refusedLeaves++;
                MineQTT.LOGGER.warn("The Home Assistant twin does not subscribe to {}: {}", topic, decision.message());
            }
            return;
        }
        leaves.hold(device, topic);
    }

    // A preset source's state topic got a payload: kept as it came; the topic waits to be applied.
    private void receivedTopic(Inbound message) {
        if (stopped || !topicHolders.containsKey(message.topic())) return;
        topicValues.put(message.topic(), new TopicValue(message.payload().clone(), message.retained(), clock.getAsLong()));
        topicCoalescer.changed(message.topic(), drains);
    }

    // A statestream leaf arrived: folded into its entity's document; a held entity that changed waits to be applied.
    private void received(Inbound message) {
        if (stopped) return;
        EntityDocuments.Update update = documents.accept(message.topic(), message.payload(), message.retained(), clock.getAsLong());
        if (update.kind() == EntityDocuments.Kind.STATUS) {
            // The read gate may have changed for every entity.
            holders.keySet().forEach(entity -> coalescer.changed(entity, drains));
            return;
        }
        for (String entity : update.changed()) {
            if (holders.containsKey(entity)) coalescer.changed(entity, drains);
        }
    }

    private EntityGlobs allowed() {
        return MineQTTConfig.twinAllowedEntities(dedicated);
    }

    // The store under the current base; a placeholder base while the twin has none, which no subscription reaches.
    private EntityDocuments newDocuments() {
        return new EntityDocuments(base.isEmpty() ? "mineqtt_twin_off" : base, new EntityDocuments.Limits(MineQTTConfig.twinCatalogMax,
                EntityDocuments.Limits.DEFAULT.maxAttributes()));
    }
}

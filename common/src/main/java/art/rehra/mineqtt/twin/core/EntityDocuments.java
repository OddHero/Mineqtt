package art.rehra.mineqtt.twin.core;

import art.rehra.mineqtt.twin.core.EntityDocument.Freshness;
import art.rehra.mineqtt.twin.core.EntityDocument.Origin;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The twin's store of Home Assistant entities (M3 plan D1, D3): it folds statestream topics and the catalog blueprint's
 * lists into one {@link EntityDocument} per entity. Single-threaded (the server thread); time comes from the caller,
 * so the fold is deterministic.
 * <p>
 * <b>Topics are independent values.</b> Statestream has no atomic update: {@code state} comes first, then the
 * timestamps, then the attributes, and the topics of several entities interleave (cap §8). Each topic updates its
 * leaf of the entity's statestream layer. A live {@code state} starts a new update, so it forgets that layer's
 * timestamps until its own arrive. Retained messages are replays of what the broker kept, and they come in the order
 * the subscriptions were made, not cap §8's: a retained {@code state} keeps the stored timestamps (the broker holds one
 * {@code last_updated} per entity, and it belongs to the retained state), and a retained leaf that repeats the stored
 * value changes nothing. Coalescing the burst into one block change is the runtime's job (D9).
 * <p>
 * <b>Two layers per entity, one winner</b> ({@link #merge}): the statestream layer and the catalog entry. A live
 * statestream value always applies; a catalog entry replaces the statestream value when its {@code last_updated} is
 * newer; a retained statestream value with an older or missing {@code last_updated} loses to the catalog (the stale
 * retained value after a Home Assistant restart, cap §5). Keeping both layers instead of dropping a message on arrival
 * matters because a retained {@code state} may reach the store before its own {@code last_updated} does.
 * <p>
 * <b>Freshness.</b> The statestream layer is live when its last new state or timestamp was delivered live (not as a
 * stored retained message); a retained attribute leaf never downgrades it. A statestream value that wins a tie with a
 * live catalog entry is live too, since the catalog proves it is current. The catalog layer is live when its last list
 * was delivered live. {@link #haBirth} makes every layer retained, because statestream's listener attaches only after
 * Home Assistant has started (cap §5); the next live value or live catalog makes it live again.
 * <p>
 * <b>Bounded.</b> At most {@link Limits#maxEntities} entities and {@link Limits#maxAttributes} attributes per entity,
 * and no leaf over {@link #MAX_PAYLOAD_BYTES}. What does not fit is counted and ignored, as are topics of another
 * shape and {@code _}-domains ({@link Counters}).
 */
public final class EntityDocuments {
    /** The mirror subscription cap; a larger leaf is refused even if the subscription let it through. */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    /**
     * Drift (D3): an entity whose catalog entry changed this many times with no live statestream value in between is
     * changing in Home Assistant without statestream publishing it (it is not in the include). A change is a newer
     * {@code last_updated} with another state or other attributes: Home Assistant re-dates every restored entity at its
     * start without a state change, and statestream, which attaches only after the start, publishes nothing for it, so a
     * re-dating alone is no sign of a missing include.
     */
    public static final int DRIFT_CYCLES = 2;

    /**
     * How much the store holds.
     *
     * @param maxEntities   entities from statestream and the catalog together ({@code twin.catalogMax})
     * @param maxAttributes attributes per entity; a climate entity publishes 26 topics (cap §3)
     */
    public record Limits(int maxEntities, int maxAttributes) {
        public static final Limits DEFAULT = new Limits(2000, 64);

        public Limits {
            if (maxEntities <= 0 || maxAttributes <= 0) throw new IllegalArgumentException("Limits must be positive");
        }
    }

    /** What a message was. */
    public enum Kind {
        /** A leaf of an entity. */
        ENTITY,
        /** A catalog list. */
        CATALOG,
        /** The catalog status: the read gate may have changed for every entity. */
        STATUS,
        /** Not a twin topic; counted. */
        IGNORED,
        /** A twin topic that was refused: too large, malformed, or over a limit; counted. */
        REFUSED
    }

    /**
     * The result of one message.
     *
     * @param changed the entities whose document or catalog membership changed, sorted
     */
    public record Update(Kind kind, Set<String> changed) {
        public Update {
            Objects.requireNonNull(kind, "kind");
            changed = Collections.unmodifiableSet(new TreeSet<>(changed));
        }
    }

    /**
     * One source's side of {@link #merge}.
     *
     * @param present     whether this source has anything for the entity
     * @param live        whether its last message was delivered live since the last Home Assistant birth
     * @param lastUpdated its {@code last_updated}, or null when missing
     */
    public record Layer(boolean present, boolean live, Instant lastUpdated) {
        public static final Layer ABSENT = new Layer(false, false, null);

        public static Layer of(boolean live, Instant lastUpdated) {
            return new Layer(true, live, lastUpdated);
        }
    }

    /**
     * Counted and ignored, since the store was made.
     *
     * @param outsideBase       topics not below the base
     * @param shape             topics below the base that are not three levels deep
     * @param reservedDomain    {@code _}-domain topics other than the catalog and its status
     * @param invalidId         three-level topics whose domain or object id Home Assistant does not make
     * @param rawAttributes     attribute payloads that were not strict JSON, kept as raw text
     * @param badTimestamps     timestamp payloads that did not parse, ignored
     * @param oversize          leaves over {@link #MAX_PAYLOAD_BYTES}
     * @param entityOverflow    entities not added because the store was full
     * @param attributeOverflow attributes not added because their entity was full
     * @param catalogRefused    catalog lists refused as too large or malformed
     * @param catalogSkipped    catalog entries skipped inside lists that were read
     * @param badStatus         status payloads that were not a status
     */
    public record Counters(long outsideBase, long shape, long reservedDomain, long invalidId, long rawAttributes, long badTimestamps,
                           long oversize, long entityOverflow, long attributeOverflow, long catalogRefused, long catalogSkipped,
                           long badStatus) {
        public long ignoredTopics() {
            return outsideBase + shape + reservedDomain + invalidId;
        }
    }

    private final String base;
    private final Limits limits;
    private final Map<String, Entity> entities = new HashMap<>();
    // domain -> entity id -> entry, as the last list of that domain had them
    private final Map<String, Map<String, CatalogEntry>> catalog = new TreeMap<>();
    // The catalog's area names, worked out again after the catalog changed; null until then.
    private List<String> areas;
    private final Map<String, CatalogParser.Refusal> refusals = new TreeMap<>();
    private CatalogParser.Status status;
    private long outsideBase, shape, reservedDomain, invalidId, rawAttributes, badTimestamps, oversize, entityOverflow,
            attributeOverflow, catalogRefused, catalogSkipped, badStatus;

    /**
     * @param base the statestream base ({@code twin.statestreamBase}); normalised by {@link StatestreamTopic#normalizeBase}
     */
    public EntityDocuments(String base, Limits limits) {
        this.base = StatestreamTopic.normalizeBase(base);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Which source wins for one entity (D3), pure:
     * <ol>
     *     <li>a source that is alone wins; with neither the result is null;</li>
     *     <li>a catalog entry without {@code last_updated} never replaces a statestream value;</li>
     *     <li>a statestream value without {@code last_updated} wins when live ("a live value always applies") and loses
     *     when retained;</li>
     *     <li>otherwise the catalog wins only when its {@code last_updated} is strictly newer.</li>
     * </ol>
     */
    public static Origin merge(Layer stream, Layer catalog) {
        if (!stream.present()) return catalog.present() ? Origin.CATALOG : null;
        if (!catalog.present() || catalog.lastUpdated() == null) return Origin.STATESTREAM;
        if (stream.lastUpdated() == null) return stream.live() ? Origin.STATESTREAM : Origin.CATALOG;
        return HaTime.newer(catalog.lastUpdated(), stream.lastUpdated()) ? Origin.CATALOG : Origin.STATESTREAM;
    }

    /**
     * An attribute leaf's payload (D1 #4): strict JSON ({@code null}, booleans, {@code 1e+21}, {@code \}{@code uXXXX}
     * escapes, nested lists and objects), or the raw text as a JSON string when it is not JSON.
     */
    public static JsonElement decodeAttribute(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        return strictJson(text).orElseGet(() -> new JsonPrimitive(text));
    }

    /** {@code text} as strict JSON (RFC 8259: no NaN, no comments, no single quotes, nothing after the value); empty otherwise. */
    public static Optional<JsonElement> strictJson(String text) {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            JsonElement json = JsonParser.parseReader(reader);
            return reader.peek() == JsonToken.END_DOCUMENT ? Optional.of(json) : Optional.empty();
        } catch (JsonParseException | IOException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    public String base() {
        return base;
    }

    public Limits limits() {
        return limits;
    }

    /**
     * Folds one received message.
     *
     * @param retained whether the broker delivered it as a stored retained message
     * @param now      the caller's clock in milliseconds, stored as {@link EntityDocument#receivedAt}
     */
    public Update accept(String topic, byte[] payload, boolean retained, long now) {
        Objects.requireNonNull(payload, "payload");
        return switch (StatestreamTopic.parse(base, topic)) {
            case StatestreamTopic.Entity leaf -> acceptLeaf(leaf, payload, retained, now);
            case StatestreamTopic.Catalog list -> acceptCatalog(CatalogParser.parseDomain(list.domain(), payload), retained, now);
            case StatestreamTopic.Status ignored -> acceptStatus(payload);
            case StatestreamTopic.Ignored ignored -> {
                switch (ignored.reason()) {
                    case OUTSIDE_BASE -> outsideBase++;
                    case SHAPE -> shape++;
                    case RESERVED_DOMAIN -> reservedDomain++;
                    case INVALID_ID -> invalidId++;
                }
                yield new Update(Kind.IGNORED, Set.of());
            }
        };
    }

    /**
     * Home Assistant announced a (re)start: every value becomes {@link Freshness#RETAINED} until a live value or a live
     * catalog arrives. Returns the entities whose document changed.
     */
    public Set<String> haBirth() {
        Set<String> changed = new TreeSet<>();
        for (Entity entity : entities.values()) {
            entity.streamLive = false;
            entity.catalogLive = false;
            if (entity.refresh()) changed.add(entity.id);
        }
        return Collections.unmodifiableSet(changed);
    }

    /** Forgets every document, the catalog and the status (a new connection or base); the counters stay. */
    public void clear() {
        entities.clear();
        catalog.clear();
        areas = null;
        refusals.clear();
        status = null;
    }

    /**
     * {@code twin.catalog} was turned off: forgets the catalog layer, the refusals and the status, so the read gate leaves
     * catalog mode for statestream-only mode (D3). The statestream layer stays; an entity only the catalog knew is
     * forgotten. The counters stay.
     *
     * @return the entities whose document changed
     */
    public Set<String> clearCatalog() {
        Set<String> changed = new TreeSet<>();
        for (Iterator<Entity> it = entities.values().iterator(); it.hasNext(); ) {
            Entity entity = it.next();
            if (entity.catalog == null) continue;
            entity.catalog = null;
            entity.catalogLive = false;
            entity.driftMoves = 0;
            entity.refresh();
            changed.add(entity.id);
            if (!entity.streamPresent) it.remove();
        }
        catalog.clear();
        areas = null;
        refusals.clear();
        status = null;
        return Collections.unmodifiableSet(changed);
    }

    /**
     * Nobody subscribes to {@code entityId}'s statestream leaves any more (M3 plan D17): its statestream layer is
     * forgotten, since nothing would keep it current, and the entity with it unless the catalog lists it. A new
     * subscription gets the broker's retained replay.
     *
     * @return whether its document changed
     */
    public boolean forget(String entityId) {
        Entity entity = entities.get(entityId);
        if (entity == null || !entity.streamPresent) return false;
        if (entity.catalog == null) {
            entities.remove(entityId);
            return true;
        }
        entity.streamPresent = false;
        entity.streamLive = false;
        entity.state = null;
        entity.attributes.clear();
        entity.lastUpdated = null;
        entity.lastChanged = null;
        return entity.refresh();
    }

    public Optional<EntityDocument> document(String entityId) {
        Entity entity = entities.get(entityId);
        return entity == null ? Optional.empty() : Optional.ofNullable(entity.document);
    }

    /** {@link Freshness#NONE} when nothing is known. */
    public Freshness freshness(String entityId) {
        return document(entityId).map(EntityDocument::freshness).orElse(Freshness.NONE);
    }

    /** The entity's entry in the current catalog: present exactly while Home Assistant's labels share it. */
    public Optional<CatalogEntry> catalogEntry(String entityId) {
        int dot = entityId.indexOf('.');
        if (dot < 0) return Optional.empty();
        Map<String, CatalogEntry> domain = catalog.get(entityId.substring(0, dot));
        return domain == null ? Optional.empty() : Optional.ofNullable(domain.get(entityId));
    }

    /** Every entry of the current catalog, by domain then list order. */
    public Map<String, Map<String, CatalogEntry>> catalog() {
        Map<String, Map<String, CatalogEntry>> copy = new TreeMap<>();
        catalog.forEach((domain, entries) -> copy.put(domain, Collections.unmodifiableMap(new LinkedHashMap<>(entries))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * The distinct area names of the current catalog's entries (M4 plan D11, {@link AreaNames#distinct}), for the rooms'
     * spelling of Home Assistant's areas; empty without a catalog.
     */
    public List<String> areas() {
        if (areas == null) areas = AreaNames.distinct(catalogAreas(entry -> true));
        return areas;
    }

    /** {@link #areas()} of the entries the read gate lets through ({@link #readable}): what a player may be shown. */
    public List<String> areas(EntityGlobs allowed) {
        return AreaNames.distinct(catalogAreas(entry -> readable(entry.entityId(), allowed)));
    }

    private List<String> catalogAreas(Predicate<CatalogEntry> filter) {
        List<String> names = new ArrayList<>();
        catalog.values().forEach(entries -> entries.values().stream().filter(filter).forEach(entry -> names.add(entry.area())));
        return names;
    }

    /** Whether a catalog status has been received, so the read gate needs catalog membership (D3). */
    public boolean catalogMode() {
        return status != null;
    }

    public Optional<CatalogParser.Status> status() {
        return Optional.ofNullable(status);
    }

    /** The domains whose last list was refused, and why; such a domain shares no entity until a list is read. */
    public Map<String, CatalogParser.Refusal> catalogRefusals() {
        return Collections.unmodifiableMap(new TreeMap<>(refusals));
    }

    /**
     * The read gate (D3): {@code allowed} must match, and in catalog mode the entity must also be in the catalog. In
     * statestream-only mode (no status received) any entity the statestream include publishes may be read.
     */
    public boolean readable(String entityId, EntityGlobs allowed) {
        if (!allowed.matches(entityId)) return false;
        return !catalogMode() || catalogEntry(entityId).isPresent();
    }

    /**
     * Why {@code entityId} may not be newly bound now, or empty (D3, M3-11): in catalog mode an entity the catalog does not
     * list is not shared by Home Assistant's labels, also when its domain's list was refused. Without a catalog status
     * nothing is refused here ({@code twin.allowedEntities} is judged elsewhere).
     */
    public Optional<String> shareProblem(String entityId) {
        if (!catalogMode() || catalogEntry(entityId).isPresent()) return Optional.empty();
        String domain = entityId.substring(0, Math.max(0, entityId.indexOf('.')));
        CatalogParser.Refusal refusal = refusals.get(domain);
        if (refusal != null) {
            return Optional.of(entityId + " is not shared by Home Assistant: its catalog list of " + domain + " was refused ("
                    + (refusal == CatalogParser.Refusal.TOO_LARGE ? "larger than 64 KiB" : "not a JSON list") + ")");
        }
        return Optional.of(entityId + " is not shared by Home Assistant: label it MineQTT read (or MineQTT control) there");
    }

    /**
     * Whether {@code entityId} drifts (D3): its catalog entry changed {@value #DRIFT_CYCLES} times (a newer
     * {@code last_updated} with another state or other attributes) while no live statestream value arrived, so Home
     * Assistant changes it but statestream does not publish it. Meaningful only for an entity whose statestream leaves are
     * subscribed (one a device mirrors); a live value ends it. A re-dating with the same state and attributes (every
     * restored entity at a Home Assistant start) is no change. Never for an entity of a domain that changes only when
     * triggered ({@link HaDomains#infersDrift}: scenes, scripts, buttons), whose state is the time it was last triggered.
     */
    public boolean drifting(String entityId) {
        Entity entity = entities.get(entityId);
        int dot = entityId.indexOf('.');
        return entity != null && entity.driftMoves >= DRIFT_CYCLES && dot > 0 && HaDomains.infersDrift(entityId.substring(0, dot));
    }

    /** The entities known, sorted. */
    public Set<String> entityIds() {
        return Collections.unmodifiableSet(new TreeSet<>(entities.keySet()));
    }

    public int size() {
        return entities.size();
    }

    public Counters counters() {
        return new Counters(outsideBase, shape, reservedDomain, invalidId, rawAttributes, badTimestamps, oversize, entityOverflow,
                attributeOverflow, catalogRefused, catalogSkipped, badStatus);
    }

    private Update acceptLeaf(StatestreamTopic.Entity leaf, byte[] payload, boolean retained, long now) {
        if (payload.length > MAX_PAYLOAD_BYTES) {
            oversize++;
            return new Update(Kind.REFUSED, Set.of());
        }
        Entity entity = entity(leaf.entityId());
        if (entity == null) return new Update(Kind.REFUSED, Set.of());
        // A retained leaf is a replay of what the broker kept, in whatever order the subscriptions were made: one that
        // repeats the stored value says nothing new, so it neither restarts the update nor makes the entity retained.
        boolean replay = retained && entity.streamPresent;
        switch (leaf.kind()) {
            case STATE -> {
                StatestreamValue state = StatestreamValue.ofPayload(payload);
                if (replay && state.equals(entity.state)) return unchanged();
                entity.state = state;
                if (!retained) {
                    // A new live update: its timestamps follow, and until they do the old ones would misdate it. A
                    // retained state keeps them: the broker holds one last_updated per entity, which belongs to the
                    // retained state, and its replay may come before the state's (cap §8 orders live publishes only).
                    entity.lastUpdated = null;
                    entity.lastChanged = null;
                }
            }
            case LAST_UPDATED -> {
                Instant parsed = timestamp(payload, entity.lastUpdated);
                if (replay && Objects.equals(parsed, entity.lastUpdated)) return unchanged();
                entity.lastUpdated = parsed;
            }
            case LAST_CHANGED -> {
                Instant parsed = timestamp(payload, entity.lastChanged);
                if (replay && Objects.equals(parsed, entity.lastChanged)) return unchanged();
                entity.lastChanged = parsed;
            }
            case ATTRIBUTE -> {
                if (payload.length == 0) {
                    // Someone cleared the retained topic; Home Assistant itself never publishes an empty attribute.
                    if (replay && !entity.attributes.containsKey(leaf.leaf())) return unchanged();
                    entity.attributes.remove(leaf.leaf());
                } else if (!entity.attributes.containsKey(leaf.leaf()) && entity.attributes.size() >= limits.maxAttributes()) {
                    attributeOverflow++;
                    return new Update(Kind.REFUSED, Set.of());
                } else {
                    String text = new String(payload, StandardCharsets.UTF_8);
                    Optional<JsonElement> json = strictJson(text);
                    if (json.isEmpty()) rawAttributes++;
                    JsonElement value = json.orElseGet(() -> new JsonPrimitive(text));
                    if (replay && value.equals(entity.attributes.get(leaf.leaf()))) return unchanged();
                    entity.attributes.put(leaf.leaf(), value);
                }
            }
        }
        entity.streamPresent = true;
        if (!retained) {
            entity.streamLive = true;
            // Statestream publishes it: no drift.
            if (leaf.kind() != StatestreamTopic.LeafKind.ATTRIBUTE) entity.driftMoves = 0;
        } else if (leaf.kind() != StatestreamTopic.LeafKind.ATTRIBUTE) {
            // A retained state or timestamp that differs from the stored one: the layer now holds broker data. A
            // retained attribute does not decide: statestream republishes every attribute with each state, so the
            // broker's copy of a leaf this layer had not read yet belongs to the same update as the live state.
            entity.streamLive = false;
        }
        entity.streamAt = now;
        return new Update(Kind.ENTITY, entity.refresh() ? Set.of(entity.id) : Set.of());
    }

    private static Update unchanged() {
        return new Update(Kind.ENTITY, Set.of());
    }

    private Update acceptCatalog(CatalogParser.DomainList list, boolean retained, long now) {
        if (list.refused()) {
            catalogRefused++;
            refusals.put(list.domain(), list.refusal());
        } else {
            catalogSkipped += list.skipped();
            refusals.remove(list.domain());
        }
        // A refused list shares nothing: failing closed keeps an unlabelled entity out of reach even when the list is broken.
        Map<String, CatalogEntry> previous = catalog.getOrDefault(list.domain(), Map.of());
        Map<String, CatalogEntry> next = new LinkedHashMap<>();
        Set<String> changed = new TreeSet<>();
        for (CatalogEntry entry : list.entries()) {
            Entity entity = entity(entry.entityId());
            if (entity == null) continue;
            next.put(entry.entityId(), entry);
            // A newer last_updated than the list before had, with another state or other attributes: Home Assistant changed
            // it since (drift counts these). A newer last_updated alone is a re-dating, as at every Home Assistant start.
            if (entity.catalog != null && entry.lastUpdated() != null && entity.catalog.lastUpdated() != null
                    && HaTime.newer(entry.lastUpdated(), entity.catalog.lastUpdated()) && changedContent(entity.catalog, entry)) {
                entity.driftMoves = Math.min(entity.driftMoves + 1, DRIFT_CYCLES);
            }
            entity.catalog = entry;
            entity.catalogLive = !retained;
            entity.catalogAt = now;
            if (entity.refresh() | !previous.containsKey(entry.entityId())) changed.add(entry.entityId());
        }
        for (String id : previous.keySet()) {
            if (next.containsKey(id)) continue;
            Entity entity = entities.get(id);
            entity.catalog = null;
            entity.catalogLive = false;
            entity.refresh();
            changed.add(id);
            if (!entity.streamPresent) entities.remove(id);
        }
        if (next.isEmpty()) catalog.remove(list.domain());
        else catalog.put(list.domain(), next);
        areas = null;
        return new Update(list.refused() ? Kind.REFUSED : Kind.CATALOG, changed);
    }

    // Whether a catalog entry says something else of the entity than the one before: its state or its attributes.
    private static boolean changedContent(CatalogEntry before, CatalogEntry after) {
        return !before.state().equals(after.state()) || !before.attributes().equals(after.attributes());
    }

    private Update acceptStatus(byte[] payload) {
        if (payload.length == 0) {
            // The status was cleared: the catalog blueprint is gone, so the gate falls back to statestream-only mode.
            status = null;
            return new Update(Kind.STATUS, Set.of());
        }
        Optional<CatalogParser.Status> parsed = CatalogParser.parseStatus(payload);
        if (parsed.isEmpty()) {
            badStatus++;
            return new Update(Kind.REFUSED, Set.of());
        }
        status = parsed.get();
        return new Update(Kind.STATUS, Set.of());
    }

    // The entity's slot, made when there is room; null (and counted) when the store is full.
    private Entity entity(String id) {
        Entity entity = entities.get(id);
        if (entity != null) return entity;
        if (entities.size() >= limits.maxEntities()) {
            entityOverflow++;
            return null;
        }
        entity = new Entity(id);
        entities.put(id, entity);
        return entity;
    }

    // A timestamp leaf: zero length clears it, a parse failure keeps what was there and is counted.
    private Instant timestamp(byte[] payload, Instant current) {
        if (payload.length == 0) return null;
        Optional<Instant> parsed = HaTime.parse(payload);
        if (parsed.isEmpty()) badTimestamps++;
        return parsed.orElse(current);
    }

    private static final class Entity {
        final String id;
        // The statestream layer.
        boolean streamPresent;
        boolean streamLive;
        long streamAt;
        StatestreamValue state;
        final Map<String, JsonElement> attributes = new LinkedHashMap<>();
        Instant lastUpdated;
        Instant lastChanged;
        // The catalog layer.
        CatalogEntry catalog;
        boolean catalogLive;
        long catalogAt;
        // Catalog moves since the last live statestream state or timestamp, at most DRIFT_CYCLES.
        int driftMoves;
        // The merged document, or null when neither layer has anything.
        EntityDocument document;

        Entity(String id) {
            this.id = id;
        }

        // Rebuilds the document; true when it says something different from before.
        boolean refresh() {
            Origin origin = merge(streamPresent ? Layer.of(streamLive, lastUpdated) : Layer.ABSENT,
                    catalog != null ? Layer.of(catalogLive, catalog.lastUpdated()) : Layer.ABSENT);
            EntityDocument next = origin == null ? null : switch (origin) {
                case STATESTREAM -> new EntityDocument(id, state == null ? StatestreamValue.UNKNOWN : state, attributes, lastUpdated, lastChanged,
                        streamAt, streamLive() ? Freshness.LIVE : Freshness.RETAINED, Origin.STATESTREAM);
                case CATALOG -> new EntityDocument(id, catalog.state(), catalog.attributes(), catalog.lastUpdated(), null, catalogAt,
                        catalogLive ? Freshness.LIVE : Freshness.RETAINED, Origin.CATALOG);
            };
            boolean changed = next == null ? document != null : !next.sameContent(document);
            document = next;
            return changed;
        }

        // Whether the statestream value is current: delivered live, or vouched for by a live catalog entry of the same
        // update (a tie keeps statestream for its full attribute set, but the live catalog proves it is not stale; an
        // idle entity's retained value and the catalog after ha_sync always tie). An undated catalog vouches for nothing.
        private boolean streamLive() {
            return streamLive || catalog != null && catalogLive && catalog.lastUpdated() != null
                    && !HaTime.newer(lastUpdated, catalog.lastUpdated());
        }
    }
}

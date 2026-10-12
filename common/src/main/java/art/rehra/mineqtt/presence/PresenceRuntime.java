package art.rehra.mineqtt.presence;

import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.Slugs;
import art.rehra.mineqtt.ha.DiscoveryPublisher;
import art.rehra.mineqtt.ha.PresenceDevice;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.security.TokenBucket;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The presence devices of players who consent (M4 plan D10): one Home Assistant device "Player Steve" per player under
 * the presence rule ({@link PrivacyPolicy#presence}), its state {@code {"presence":"ON"}} on
 * {@code <base>/<bridge>/player/<slug>}. A device exists while its player's registry entry has a slug.
 * <ul>
 *     <li><b>Created</b> when a player under the rule joins, or consents while online, unless
 *     {@code telemetry.maxPresenceDevices} devices exist: then nobody new gets one (no eviction, so Home Assistant's
 *     history does not churn) and the operators are told once.</li>
 *     <li><b>State</b>: ON at the join, OFF at the quit. Every republish (each CONNACK, Home Assistant's birth) publishes
 *     every device's state as its player is now, so a crash never leaves a stale retained ON; a graceful stop publishes
 *     OFF for the players online before the bridge goes offline.</li>
 *     <li><b>Full sharing</b> ({@link PrivacyPolicy#full}: consent {@code full}, {@code telemetry.players=full}): the
 *     state of a player online adds health, dimension and position ({@link FullSample}), sampled once a second and
 *     published per player at most every 5 s, after 4 blocks or a health change of 1, at once on a dimension change;
 *     these updates share a budget of {@value #FULL_UPDATES_PER_SECOND} per second for the bridge. The device gets three
 *     more sensors. When full sharing ends (the player's choice, the server's mode), the state drops the reading and the
 *     sensors are removed in Home Assistant through the discovery's component removal; the entry remembers that they
 *     were discovered ({@link PlayerEntry#fullDiscovered}) until a removal ran while Home Assistant was online, so a
 *     restart issues it again. The quit's OFF carries no reading: no last-known position is kept.</li>
 *     <li><b>Removed</b> (an empty retained discovery and an empty retained state, kept through disconnects) on
 *     {@code share off}, {@code /mineqtt players forget}, after {@code telemetry.forgetAfterDays} unseen (checked at the
 *     start and once per game day), and all at once when the settings no longer allow presence
 *     ({@code telemetry.players=off}, {@code publishPlayerNames=false}); the consents stay. A forgotten player online gets
 *     a device again only at their next join.</li>
 *     <li>Discovered only while {@code ha.expose} is not {@code NONE}; the discovery shares the discovery rate limit. The
 *     ghost cleanup asks {@link #knows} which presence configs, and {@link #knowsSlug} which retained states, belong to a
 *     player now. When the topics move, the states under the old root are cleared ({@link #topicsChanged}).</li>
 * </ul>
 * Server thread only.
 */
public final class PresenceRuntime implements PlayerPrivacy.Listener {
    /** How often players unseen for {@code telemetry.forgetAfterDays} are looked for: one game day at 20 ticks per second. */
    public static final long FORGET_CHECK_MILLIS = 20 * 60 * 1000;
    /** How often the players who share everything are read. */
    public static final long SAMPLE_MILLIS = 1_000;
    /** The full-sharing updates of all players together, per second (D10). */
    public static final int FULL_UPDATES_PER_SECOND = 20;

    /** What the runtime asks of the game. */
    public interface Game {
        /** Whether this player owns the integrated server, so their unset consent counts as presence. */
        boolean host(UUID player);

        /** The player's health, dimension and block position now; empty when they are not online. */
        Optional<FullSample> sample(UUID player);

        /** Tells the operators online, and the log. */
        void tellOps(String text);
    }

    /** The discovery's removal of components an object dropped ({@code DiscoveryPublisher.componentsRemoved}). */
    @FunctionalInterface
    public interface Removals {
        void componentsRemoved(String objectId, Map<String, JsonObject> removed, Supplier<Optional<JsonObject>> current);
    }

    private final DeviceRegistry registry;
    private final MqttService mqtt;
    private final Supplier<TopicScheme> topics;
    private final DiscoveryPublisher.Identity identity;
    private final boolean dedicatedServer;
    private final BooleanSupplier rateLimit;
    private final Removals removals;
    private final BooleanSupplier haOnline;
    private final LongSupplier clock;
    private final LongSupplier today;
    private final Game game;
    private final MqttLog log;
    private final TokenBucket fullUpdates;
    // The players online, with their names.
    private final Map<UUID, String> online = new LinkedHashMap<>();
    // Players whose discovery waits for the rate limit, with whether it is a refresh (sent even when unchanged).
    private final Map<UUID, Boolean> discoveries = new LinkedHashMap<>();
    // The full reading last published per player online, and when.
    private final Map<UUID, FullSample> lastFull = new HashMap<>();
    private final Map<UUID, Long> lastFullAt = new HashMap<>();
    // The discovery prefix and exposure the devices were last discovered under.
    private String prefix;
    private boolean exposed;
    private boolean capTold;
    private long forgetCheckedAt;
    private long sampledAt;
    private boolean stopped;

    /**
     * @param dedicatedServer resolves {@code ha.expose} AUTO
     * @param rateLimit       takes one message from the discovery rate limit ({@code DiscoveryPublisher.tryTakeToken})
     * @param haOnline        whether Home Assistant is known to be online, so a component removal runs now
     * @param clock           milliseconds
     * @param today           the UTC day ({@code LocalDate.toEpochDay})
     */
    public PresenceRuntime(DeviceRegistry registry, MqttService mqtt, Supplier<TopicScheme> topics, DiscoveryPublisher.Identity identity,
                           boolean dedicatedServer, BooleanSupplier rateLimit, Removals removals, BooleanSupplier haOnline, LongSupplier clock,
                           LongSupplier today, Game game, MqttLog log) {
        this.registry = registry;
        this.mqtt = mqtt;
        this.topics = topics;
        this.identity = identity;
        this.dedicatedServer = dedicatedServer;
        this.rateLimit = rateLimit;
        this.removals = removals;
        this.haOnline = haOnline;
        this.clock = clock;
        this.today = today;
        this.game = game;
        this.log = log;
        this.fullUpdates = new TokenBucket(FULL_UPDATES_PER_SECOND, FULL_UPDATES_PER_SECOND, () -> clock.getAsLong() * 1_000_000L);
        this.prefix = MineQTTConfig.discoveryPrefix;
        this.exposed = discoverable();
        this.forgetCheckedAt = clock.getAsLong();
        this.sampledAt = clock.getAsLong();
    }

    /** {@code mineqtt_player_<32hex>} of a player in this world ({@link PresenceDevice#objectId}). */
    public String objectId(UUID player) {
        return PresenceDevice.objectId(identity.worldUuid(), player);
    }

    /**
     * At the start, before the first connect: devices the settings or consents no longer allow are removed (their clears
     * wait for the broker), the sensors of a full sharing that ended are removed again (the last removal may not have
     * reached Home Assistant), and players unseen for too long are forgotten.
     */
    public void start() {
        reconcileAll();
        forgetUnseen();
    }

    /** A player joined, after their privacy entry was updated ({@link PlayerPrivacy#joined}). */
    public void joined(UUID player, String name) {
        if (stopped) return;
        online.put(player, name);
        reconcile(player);
    }

    /**
     * A player left: their device goes OFF; no position or other data of theirs is kept. A consenting player was seen
     * until now, so {@code telemetry.forgetAfterDays} counts from the quit, not from the join of a long session.
     */
    public void left(UUID player) {
        if (stopped) return;
        online.remove(player);
        forgetReading(player);
        registry.player(player).filter(entry -> entry.lastSeenDay() > 0 && effective(entry).consents())
                .ifPresent(entry -> registry.putPlayer(entry.withLastSeenDay(today.getAsLong())));
        registry.player(player).flatMap(PlayerEntry::slug).ifPresent(slug -> publishState(slug, false, Optional.empty(), false));
    }

    /** A player chose ({@link PlayerPrivacy#choose}): a withdrawn consent removes the device, a new one creates it. */
    @Override
    public void chosen(PlayerPrivacy.Change change) {
        if (stopped) return;
        removeOld(change);
        reconcile(change.after().uuid());
    }

    /** An operator forgot the player ({@link PlayerPrivacy#forget}): the device goes, and comes back only at a join. */
    @Override
    public void forgotten(PlayerPrivacy.Change change) {
        if (stopped) return;
        removeOld(change);
    }

    /**
     * After a config change: devices the settings no longer allow are removed (all of them for {@code telemetry.players=off}
     * or {@code publishPlayerNames=false}), a mode narrowed from {@code full} removes the full sensors, players online who
     * are allowed now get a device, a new discovery prefix moves every config, and {@code ha.expose} NONE clears them (the
     * states stay).
     */
    public void configChanged() {
        if (stopped) return;
        boolean nowExposed = discoverable();
        String nowPrefix = MineQTTConfig.discoveryPrefix;
        if (exposed && (!nowExposed || !nowPrefix.equals(prefix))) clearConfigs();
        boolean rediscover = nowExposed && (!exposed || !nowPrefix.equals(prefix));
        prefix = nowPrefix;
        exposed = nowExposed;
        reconcileAll();
        if (rediscover) devices().forEach(entry -> discoveries.put(entry.uuid(), true));
    }

    /**
     * Every device's discovery and state as its player is now: after each CONNACK (offline players OFF, so a crash leaves
     * no stale ON), for Home Assistant's birth and the republish requests. A removal of full sensors still owed to a Home
     * Assistant that is online now is issued again.
     */
    public void republish() {
        if (stopped) return;
        for (PlayerEntry entry : devices()) {
            if (exposed) discoveries.put(entry.uuid(), true);
            if (entry.fullDiscovered() && !full(entry) && haOnline.getAsBoolean()) removeFull(entry);
            boolean present = online.containsKey(entry.uuid());
            publishState(entry.slug().orElseThrow(), present, present ? reading(entry) : Optional.empty(), true);
        }
    }

    /**
     * {@code SERVER_POST}: once a second the readings of players who share everything, published as {@link FullSample#due}
     * and the shared budget allow; waiting discovery within the shared rate limit while connected; once per game day the
     * players unseen for too long are forgotten.
     */
    public void tick() {
        if (stopped) return;
        long now = clock.getAsLong();
        if (now - forgetCheckedAt >= FORGET_CHECK_MILLIS) {
            forgetCheckedAt = now;
            forgetUnseen();
        }
        if (now - sampledAt >= SAMPLE_MILLIS) {
            sampledAt = now;
            sample(now);
        }
        if (!mqtt.state().isConnected()) return;
        for (Iterator<Map.Entry<UUID, Boolean>> waiting = discoveries.entrySet().iterator(); waiting.hasNext(); ) {
            Map.Entry<UUID, Boolean> next = waiting.next();
            Optional<PlayerEntry> entry = registry.player(next.getKey()).filter(player -> player.slug().isPresent());
            if (entry.isEmpty() || !exposed) {
                waiting.remove();
                continue;
            }
            if (!rateLimit.getAsBoolean()) return;
            waiting.remove();
            boolean full = full(entry.get());
            Outbound message = Outbound.state(TopicScheme.discoveryConfig(prefix, objectId(entry.get().uuid())), discovery(entry.get()));
            mqtt.publish(next.getValue() ? message.asRefresh() : message);
            if (full && !entry.get().fullDiscovered()) registry.putPlayer(entry.get().withFullDiscovered(true));
        }
    }

    /**
     * {@code /mineqtt discovery clear}: clears every presence device's retained discovery, as the discovery publisher
     * clears the bridge's and the devices'; the states stay. Nothing is discovered again until the next republish or a
     * player's join or choice.
     *
     * @return the number of retained topics cleared
     */
    public int clearDiscovery() {
        if (stopped) return 0;
        return clearConfigs();
    }

    // Clears every device's config under the current prefix and drops the discoveries waiting for the rate limit.
    private int clearConfigs() {
        List<PlayerEntry> cleared = devices();
        for (PlayerEntry entry : cleared) {
            mqtt.publish(Outbound.clear(TopicScheme.discoveryConfig(prefix, objectId(entry.uuid()))));
            // A cleared config has no sensors left to remove.
            if (entry.fullDiscovered()) registry.putPlayer(entry.withFullDiscovered(false));
        }
        discoveries.clear();
        return cleared.size();
    }

    /** Whether discovery waits for a tick, so a paused server is pumped for it. */
    public boolean hasPendingWork() {
        return !stopped && !discoveries.isEmpty() && mqtt.state().isConnected();
    }

    /** {@code SERVER_STOPPING}, before the bridge's offline: the devices of the players online go OFF. */
    public void stop() {
        if (stopped) return;
        for (UUID player : online.keySet()) {
            registry.player(player).flatMap(PlayerEntry::slug).ifPresent(slug -> publishState(slug, false, Optional.empty(), false));
        }
        stopped = true;
        discoveries.clear();
        lastFull.clear();
        lastFullAt.clear();
    }

    /** Whether a player has the presence device of this object id now; the ghost cleanup clears any other. */
    public boolean knows(String objectId) {
        return devices().stream().anyMatch(entry -> objectId(entry.uuid()).equals(objectId));
    }

    /** Whether a player has the presence device with this slug now; the ghost cleanup clears any other retained state. */
    public boolean knowsSlug(String slug) {
        return registry.playerBySlug(slug).isPresent();
    }

    /**
     * The topics moved (a new base topic or bridge id, after a reconnect): every device's retained state under the old
     * root is cleared, since its topic names the player and a later {@code share off} or forget clears only the new one.
     * The next republish publishes the states under the new root. A clear goes to the broker the bridge is connected to
     * now; a retained state left on a broker the bridge no longer uses stays there.
     */
    public void topicsChanged(TopicScheme before) {
        if (stopped || before == null || before.root().equals(topics.get().root())) return;
        for (PlayerEntry entry : devices()) mqtt.publish(Outbound.clear(before.player(entry.slug().orElseThrow())));
    }

    /** The slug of a player's presence device, if they have one: their own {@code player/} topic in the explorer. */
    public Optional<String> slugOf(UUID player) {
        return registry.player(player).flatMap(PlayerEntry::slug);
    }

    /** How a player's device is published, for {@code /mineqtt privacy}: its Home Assistant name and topic. */
    public Optional<String> describe(UUID player) {
        return registry.player(player).filter(entry -> entry.slug().isPresent())
                .map(entry -> "\"" + PresenceDevice.name(entry.describe()) + "\" (" + topics.get().player(entry.slug().get()) + ")");
    }

    /** The discovery message of a player's device as it is now, with the full sensors while the player shares everything. */
    public JsonObject discovery(PlayerEntry entry) {
        return PresenceDevice.discovery(topics.get(), identity, objectId(entry.uuid()), entry.describe(), entry.slug().orElseThrow(), full(entry));
    }

    /** The entries that have a device now. */
    public List<PlayerEntry> devices() {
        return registry.players().stream().filter(entry -> entry.slug().isPresent()).toList();
    }

    /**
     * Whether a player last seen on {@code lastSeenDay} is forgotten today under {@code telemetry.forgetAfterDays}
     * ({@code forgetAfterDays} 0 never forgets; an entry without a day has nothing to forget).
     */
    public static boolean expired(long lastSeenDay, long today, int forgetAfterDays) {
        return forgetAfterDays > 0 && lastSeenDay > 0 && today - lastSeenDay > forgetAfterDays;
    }

    // Whether the player is under the presence rule now.
    private boolean allowed(PlayerEntry entry) {
        return PrivacyPolicy.current().presence(effective(entry));
    }

    // Whether the player shares health, dimension and position now.
    private boolean full(PlayerEntry entry) {
        return PrivacyPolicy.current().full(effective(entry));
    }

    private Consent effective(PlayerEntry entry) {
        return entry.consent().effective(game.host(entry.uuid()));
    }

    // The device follows the player's entry: removed when not allowed; the full sensors removed when full sharing ended;
    // created for a player online who is allowed, and an online player's device discovered again (its name may have
    // changed) and ON, with a reading while they share everything.
    private void reconcile(UUID player) {
        Optional<PlayerEntry> stored = registry.player(player);
        if (stored.isEmpty()) return;
        PlayerEntry entry = stored.get();
        boolean allowed = allowed(entry);
        if (entry.slug().isPresent() && !allowed) {
            remove(entry);
            return;
        }
        if (entry.slug().isPresent() && entry.fullDiscovered() && !full(entry)) removeFull(entry);
        if (online.containsKey(player) && allowed) {
            if (registry.player(player).orElseThrow().slug().isEmpty() && !create(entry, online.get(player))) return;
            PlayerEntry device = registry.player(player).orElseThrow();
            if (exposed) discoveries.putIfAbsent(player, false);
            publishState(device.slug().orElseThrow(), true, reading(device), false);
        }
    }

    private void reconcileAll() {
        for (PlayerEntry entry : devices()) {
            if (!allowed(entry)) {
                remove(entry);
            } else if (entry.fullDiscovered() && !full(entry) && !online.containsKey(entry.uuid())) {
                // The players online are reconciled below, which removes their full sensors.
                removeFull(entry);
            }
        }
        for (UUID player : new ArrayList<>(online.keySet())) reconcile(player);
    }

    // A new device for a player online, unless the cap is reached; its slug is player_<name>, unique under player/.
    private boolean create(PlayerEntry entry, String name) {
        int max = MineQTTConfig.telemetryMaxPresenceDevices;
        if (devices().size() >= max) {
            if (!capTold) {
                capTold = true;
                game.tellOps("MineQTT: " + max + " presence devices exist (telemetry.maxPresenceDevices), so players who consent from now on get none");
            }
            return false;
        }
        String slug = Slugs.unique("player_" + name, taken -> registry.playerBySlug(taken).isPresent());
        registry.putPlayer(entry.seen(name, today.getAsLong()).withSlug(Optional.of(slug)));
        log.info("Presence device " + slug + " created for a consenting player");
        return true;
    }

    // Clears the device's discovery and state and takes its slug from the entry.
    private void remove(PlayerEntry entry) {
        clear(entry.uuid(), entry.slug().orElseThrow());
        registry.putPlayer(entry.withSlug(Optional.empty()));
    }

    // The device the change took the slug from (share off, forget) is cleared.
    private void removeOld(PlayerPrivacy.Change change) {
        Optional<String> before = change.before().slug();
        if (before.isPresent() && !before.equals(change.after().slug())) clear(change.before().uuid(), before.get());
    }

    /**
     * Full sharing ended for a device that was discovered with its sensors: Home Assistant removes them (the discovery's
     * component removal, which waits for Home Assistant's birth while it is not known to be online). The entry keeps
     * the mark until a removal was issued while Home Assistant was online, so a restart before that issues it again.
     */
    private void removeFull(PlayerEntry entry) {
        forgetReading(entry.uuid());
        if (!exposed) {
            registry.putPlayer(entry.withFullDiscovered(false));
            return;
        }
        UUID player = entry.uuid();
        String objectId = objectId(player);
        removals.componentsRemoved(objectId, PresenceDevice.fullComponents(objectId, topics.get().player(entry.slug().orElseThrow())), () -> {
            Optional<PlayerEntry> now = registry.player(player).filter(device -> device.slug().isPresent());
            return exposed && now.isPresent() ? Optional.of(discovery(now.get())) : Optional.empty();
        });
        if (haOnline.getAsBoolean()) registry.putPlayer(entry.withFullDiscovered(false));
    }

    // Empty retained messages, kept through disconnects, so neither Home Assistant nor the broker keeps the device.
    private void clear(UUID player, String slug) {
        discoveries.remove(player);
        forgetReading(player);
        mqtt.publish(Outbound.clear(TopicScheme.discoveryConfig(prefix, objectId(player))));
        mqtt.publish(Outbound.clear(topics.get().player(slug)));
        log.info("Presence device " + slug + " removed");
    }

    // A reading of a player online who shares everything, kept as the one last published; empty otherwise.
    private Optional<FullSample> reading(PlayerEntry entry) {
        if (!full(entry)) {
            forgetReading(entry.uuid());
            return Optional.empty();
        }
        Optional<FullSample> sample = game.sample(entry.uuid());
        sample.ifPresent(reading -> {
            lastFull.put(entry.uuid(), reading);
            lastFullAt.put(entry.uuid(), clock.getAsLong());
        });
        return sample;
    }

    // The readings of the players online who share everything, each published when due and within the shared budget.
    private void sample(long now) {
        for (UUID player : online.keySet()) {
            Optional<PlayerEntry> entry = registry.player(player).filter(device -> device.slug().isPresent());
            if (entry.isEmpty() || !full(entry.get())) {
                forgetReading(player);
                continue;
            }
            Optional<FullSample> sample = game.sample(player);
            if (sample.isEmpty()) continue;
            Optional<FullSample> last = Optional.ofNullable(lastFull.get(player));
            if (!FullSample.due(last, lastFullAt.getOrDefault(player, 0L), sample.get(), now) || !fullUpdates.tryTake()) continue;
            lastFull.put(player, sample.get());
            lastFullAt.put(player, now);
            publishState(entry.get().slug().orElseThrow(), true, sample, false);
        }
    }

    private void forgetReading(UUID player) {
        lastFull.remove(player);
        lastFullAt.remove(player);
    }

    private void publishState(String slug, boolean present, Optional<FullSample> reading, boolean refresh) {
        Outbound message = Outbound.state(topics.get().player(slug), PresenceDevice.state(present, reading));
        mqtt.publish(refresh ? message.asRefresh() : message);
    }

    // Players offline and unseen for more than telemetry.forgetAfterDays lose their device and name; their choice stays.
    private void forgetUnseen() {
        long day = today.getAsLong();
        int days = MineQTTConfig.telemetryForgetAfterDays;
        for (PlayerEntry entry : List.copyOf(registry.players())) {
            if (online.containsKey(entry.uuid()) || !expired(entry.lastSeenDay(), day, days)) continue;
            entry.slug().ifPresent(slug -> clear(entry.uuid(), slug));
            registry.putPlayer(entry.forgotten());
        }
    }

    private boolean discoverable() {
        return MineQTTConfig.haExpose(dedicatedServer) != HaExpose.NONE;
    }
}

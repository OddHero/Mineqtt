package art.rehra.mineqtt.device;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.security.AclContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * The devices of one server (design §6.5, §6.6): attaches and detaches device block entities, routes the
 * {@code /set} commands of devices and groups, queues commands for unloaded devices, publishes state and
 * availability within each type's limits, and prunes registry records whose block is gone. Server thread only.
 * <ul>
 *     <li>Attach runs one tick after {@code setLevel}, so a placement has applied its item data and
 *     {@code setPlacedBy} first. Blocks that load before the server has started wait for it.</li>
 *     <li>Commands use four broker subscriptions, whatever the number of devices; a loaded device applies a command,
 *     an unloaded {@link UnloadedPolicy#QUEUE} device queues it, an unloaded sensor ignores it.</li>
 *     <li>Devices power on with their server: the records of queueing devices when the runtime starts, each block
 *     entity at its first attach.</li>
 *     <li>After {@code SERVER_STOPPING} nothing is published any more; detach and destroy only update the registry,
 *     which is saved at {@code SERVER_STOPPED}.</li>
 *     <li>v1 data is adopted at attach (§9): a device the v1 topic states pre-created for its v1 topic takes over its
 *     record, others keep their v1 light's unique_id or join the legacy group of their topic ({@link LegacyAdoption});
 *     the v1 topic becomes a grandfathered Raw Link whose owner name is resolved off the server thread. A legacy group
 *     follows its v1 topic once for all members and echoes its state.</li>
 * </ul>
 * Discovery follows every change of identity, exposure and group membership through the runtime's
 * {@link art.rehra.mineqtt.ha.DiscoveryPublisher}.
 */
public final class DeviceRuntime {
    private static final String ONLINE = "online";
    private static final String OFFLINE = "offline";
    private static final int MAX_LOGGED_PROBLEMS = 256;
    private static final Pattern FIELD_KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    // Device block entities that registered while their server was starting.
    private static final Map<MinecraftServer, Set<BlockEntity>> beforeStart = new IdentityHashMap<>();

    private final MineqttRuntime runtime;
    private final DeviceRegistry registry;
    private final Map<UUID, BlockEntity> attached = new HashMap<>();
    private final Set<BlockEntity> attachQueue = new LinkedHashSet<>();
    private final Set<ChunkRef> pruneQueue = new LinkedHashSet<>();
    private final Map<UUID, Subscription> rawSubscriptions = new HashMap<>();
    // Groups that replace a v1 topic follow it once for all their members.
    private final Map<UUID, Subscription> groupRawSubscriptions = new HashMap<>();
    private final Map<UUID, StateThrottle> throttles = new HashMap<>();
    private final List<Subscription> commandSubscriptions = new ArrayList<>();
    private final Set<UUID> resolvingOwners = new HashSet<>();
    // Devices that attached in this server run; the first attach of a registered device is its power-on.
    private final Set<UUID> poweredOn = new HashSet<>();
    // v1 owner names the profile lookup found nobody for; until a restart only an online player of that name resolves them.
    private final Set<String> unknownOwners = new HashSet<>();
    private final Set<String> loggedProblems = new HashSet<>();
    private TopicScheme commandTopics;
    private boolean stopping;

    private record ChunkRef(ResourceKey<Level> dimension, long chunk) {
    }

    /**
     * Powers on the unloaded devices, opens the command subscriptions and the v1 topics of legacy groups, and takes over
     * the block entities that loaded before the server started.
     */
    public DeviceRuntime(MineqttRuntime runtime, DeviceRegistry registry) {
        this.runtime = runtime;
        this.registry = registry;
        powerOnRecords();
        Set<BlockEntity> waiting = beforeStart.remove(runtime.server());
        if (waiting != null) attachQueue.addAll(waiting);
        topicsChanged();
        registry.groups().forEach(this::openGroupRaw);
        runtime.mqtt().onConnected(this::connected);
    }

    /**
     * Devices start with their server, also those whose chunk is not loaded: a queueing device's record shows the state
     * it starts in, so its state is published and the commands queued for it are diffed from there. Its block entity
     * powers on the same way when it attaches. A group whose first member started in another state follows it.
     */
    private void powerOnRecords() {
        Set<UUID> changed = new HashSet<>();
        for (DeviceRecord record : registry.records()) {
            DeviceType type = DeviceTypes.get(record.type());
            if (type == null || type.unloadedPolicy() != UnloadedPolicy.QUEUE || record.lastState() == null) continue;
            JsonObject started = type.poweredOn(record.lastState());
            if (started.equals(record.lastState())) continue;
            record.setLastState(started);
            changed.add(record.id());
        }
        if (changed.isEmpty()) return;
        for (DeviceGroup group : registry.groups()) {
            DeviceType type = group.memberType() == null ? null : DeviceTypes.get(group.memberType());
            DeviceRecord representative = group.members().stream().map(registry::record).filter(Objects::nonNull).findFirst().orElse(null);
            if (type != null && representative != null && changed.contains(representative.id())) {
                group.setState(type.writableState(effectiveState(representative, type)));
            }
        }
        registry.markDirty();
    }

    /** From {@link DeviceLink#onSetLevel}: attaches the block entity on the next tick of its server. */
    static void scheduleAttach(ServerLevel level, BlockEntity blockEntity) {
        requireHost(blockEntity);
        DeviceRuntime devices = of(level.getServer());
        if (devices != null) {
            if (!devices.stopping) devices.attachQueue.add(blockEntity);
        } else {
            beforeStart.computeIfAbsent(level.getServer(), server -> new LinkedHashSet<>()).add(blockEntity);
        }
    }

    /** From {@link DeviceLink}'s removal hooks. */
    static void removed(ServerLevel level, BlockEntity blockEntity, DeviceLink.Removal removal) {
        DeviceRuntime devices = of(level.getServer());
        if (devices != null) {
            devices.onRemoved(blockEntity, removal);
        } else {
            Set<BlockEntity> waiting = beforeStart.get(level.getServer());
            if (waiting != null) waiting.remove(blockEntity);
        }
    }

    /** {@code SERVER_STOPPED}: drops what a server that never started left waiting. */
    public static void serverStopped(MinecraftServer server) {
        beforeStart.remove(server);
    }

    static void ifAttached(BlockEntity blockEntity, BiConsumer<DeviceRuntime, DeviceHost> action) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) return;
        DeviceRuntime devices = of(level.getServer());
        DeviceHost host = requireHost(blockEntity);
        UUID id = host.deviceLink().id();
        if (devices != null && id != null && devices.attached.get(id) == blockEntity) action.accept(devices, host);
    }

    static Optional<String> refusal(TopicAcl.Decision decision) {
        return decision.allowed() ? Optional.empty() : Optional.of(decision.message());
    }

    private static @Nullable DeviceRuntime of(MinecraftServer server) {
        return MineqttRuntime.current().filter(running -> running.server() == server).map(MineqttRuntime::devices).orElse(null);
    }

    private static DeviceHost requireHost(BlockEntity blockEntity) {
        if (blockEntity instanceof DeviceHost host) return host;
        throw new IllegalArgumentException(blockEntity.getClass().getName() + " is not a DeviceHost");
    }

    public DeviceRegistry registry() {
        return registry;
    }

    public boolean isAttached(UUID id) {
        return attached.containsKey(id);
    }

    public int attachedCount() {
        return attached.size();
    }

    /** {@code SERVER_PRE}, before the inbound messages: attaches and prunes. */
    public void tickStart() {
        if (stopping) return;
        for (BlockEntity blockEntity : List.copyOf(attachQueue)) {
            Boolean ready = readyToAttach(blockEntity);
            if (ready == null) continue;
            attachQueue.remove(blockEntity);
            if (ready) attach(blockEntity);
        }
        for (ChunkRef ref : List.copyOf(pruneQueue)) {
            pruneQueue.remove(ref);
            ServerLevel level = runtime.server().getLevel(ref.dimension());
            LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(ChunkPos.getX(ref.chunk()), ChunkPos.getZ(ref.chunk()));
            if (chunk == null) continue;
            for (DeviceRecord record : registry.inChunk(ref.dimension(), chunk.getPos())) {
                if (isStale(record, chunk)) prune(record);
            }
        }
    }

    /** {@code SERVER_POST}, before the outbound flush: publishes states whose minimum interval is over. */
    public void tickEnd() {
        if (stopping || throttles.isEmpty()) return;
        long now = Util.getMillis();
        throttles.forEach((id, throttle) -> {
            JsonObject due = throttle.due(now);
            DeviceRecord record = registry.record(id);
            if (due != null && record != null) mqtt().publish(Outbound.state(topics().device(record.slug()), due));
        });
    }

    /** {@code SERVER_STOPPING}: nothing is published any more; removals only update the registry. */
    public void stop() {
        stopping = true;
        attachQueue.clear();
        commandSubscriptions.forEach(Subscription::close);
        commandSubscriptions.clear();
        rawSubscriptions.values().forEach(Subscription::close);
        rawSubscriptions.clear();
        groupRawSubscriptions.values().forEach(Subscription::close);
        groupRawSubscriptions.clear();
    }

    /** After a reconnect: the bridge id or base topic may have changed, which moves the command filters. */
    public void topicsChanged() {
        if (stopping || topics().equals(commandTopics)) return;
        commandSubscriptions.forEach(Subscription::close);
        commandSubscriptions.clear();
        commandTopics = topics();
        for (String filter : commandTopics.commandFilters()) {
            commandSubscriptions.add(mqtt().subscribe(filter, SubOptions.COMMAND, this::onCommand));
        }
    }

    /** A chunk reached FULL: its registry records are checked against its block entities on the next tick. */
    public void chunkLoaded(ServerLevel level, ChunkPos chunk) {
        if (!stopping && !registry.inChunk(level.dimension(), chunk).isEmpty()) pruneQueue.add(new ChunkRef(level.dimension(), chunk.pack()));
    }

    /**
     * {@code /mineqtt devices prune}: forgets every device whose chunk is loaded but whose block is gone.
     *
     * @return the number of devices forgotten
     */
    public int pruneLoaded() {
        int pruned = 0;
        for (DeviceRecord record : List.copyOf(registry.records())) {
            ServerLevel level = runtime.server().getLevel(record.pos().dimension());
            if (level == null || attached.containsKey(record.id())) continue;
            ChunkPos chunkPos = ChunkPos.containing(record.pos().pos());
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
            if (chunk != null && isStale(record, chunk)) {
                prune(record);
                pruned++;
            }
        }
        return pruned;
    }

    /**
     * {@code /mineqtt devices remove}: forgets an unloaded device and clears its retained topics. A block that still
     * exists registers again when its chunk loads.
     *
     * @return false for a loaded device, which is removed by breaking its block
     */
    public boolean remove(DeviceRecord record) {
        if (attached.containsKey(record.id())) return false;
        forget(record);
        return true;
    }

    /**
     * Renames a device (§3.1): the old state and availability are cleared, discovery is republished, then the state is
     * published on the new slug, which is derived from the name. The unique_ids do not change.
     */
    public void rename(DeviceRecord record, String newName) {
        String name = ValueCodecs.sanitizeText(newName).strip();
        DeviceType type = DeviceTypes.get(record.type());
        String wanted = Slugs.fromName(name);
        String slug = registry.uniqueSlug(wanted.isEmpty() && type != null ? type.defaultSlug(record.id()) : wanted, record.id());
        String oldSlug = record.slug();
        registry.rename(record, name.isEmpty() ? record.name() : name, slug);
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity != null && requireHost(blockEntity).deviceLink().assign(record.id(), record.name(), record.slug())) blockEntity.setChanged();
        if (stopping) return;
        boolean moved = !oldSlug.equals(slug);
        if (moved) {
            mqtt().publish(Outbound.clear(topics().device(oldSlug)));
            mqtt().publish(Outbound.clear(topics().availability(oldSlug)));
        }
        runtime.discovery().publishNow(record);
        if (moved && type != null) publishAll(record, type);
    }

    /**
     * Availability and state of every device and the state of every group, sent even when unchanged (§4.2 step 5).
     * Loaded devices publish from their block entity, unloaded queueing devices from their record.
     */
    public void republish() {
        if (stopping) return;
        for (DeviceRecord record : registry.records()) {
            DeviceType type = DeviceTypes.get(record.type());
            if (type != null) publishAll(record, type);
        }
        for (DeviceGroup group : registry.groups()) {
            if (!group.state().isEmpty()) mqtt().publish(Outbound.state(topics().group(group.slug()), group.state()).asRefresh());
        }
    }

    /**
     * Clears the retained discovery messages of every device and group and of the bridge. With {@code oldBridge},
     * also the retained bridge, device and group topics left under that bridge id.
     *
     * @return the number of retained topics cleared
     */
    public int clearRetained(@Nullable String oldBridge) {
        if (stopping) return 0;
        int cleared = runtime.discovery().clearAll();
        List<String> topicsToClear = new ArrayList<>();
        if (oldBridge != null) {
            TopicScheme old = new TopicScheme(topics().base(), oldBridge);
            topicsToClear.add(old.bridgeState());
            topicsToClear.add(old.bridgeInfo());
            topicsToClear.add(old.bridgeTelemetry());
            for (DeviceRecord record : registry.records()) {
                topicsToClear.add(old.device(record.slug()));
                topicsToClear.add(old.availability(record.slug()));
            }
            registry.groups().forEach(group -> topicsToClear.add(old.group(group.slug())));
        }
        topicsToClear.forEach(topic -> mqtt().publish(Outbound.clear(topic)));
        return cleared + topicsToClear.size();
    }

    public DeviceGroup createGroup(String name) {
        return registry.createGroup(UUID.randomUUID(), ValueCodecs.sanitizeText(name).strip());
    }

    /**
     * Adds a device to a group. It inherits the group's state; the first member gives an empty group its state.
     *
     * @return why the device cannot join; empty when it joined
     */
    public Optional<String> join(DeviceGroup group, DeviceRecord record) {
        DeviceType type = DeviceTypes.get(record.type());
        if (type == null || !type.groupable()) return Optional.of(record.type() + " devices cannot be grouped");
        if (group.memberType() != null && !group.memberType().equals(record.type())) {
            return Optional.of("group " + group.slug() + " holds " + group.memberType() + " devices");
        }
        registry.joinGroup(group, record);
        // A legacy group whose listed positions were all ambiguous has a member type, and so follows its topic, from now on.
        if (group.legacyTopic() != null && !groupRawSubscriptions.containsKey(group.id())) openGroupRaw(group);
        if (group.state().isEmpty()) {
            group.setState(type.writableState(effectiveState(record, type)));
        } else {
            command(record, type, group.state(), CommandSource.GROUP);
        }
        publishGroupState(group);
        // A legacy group follows its v1 topic for the member from now on.
        refreshRaw(record);
        // A member is hidden unless the group exposes its members; the group has a member type from now on.
        runtime.discovery().refresh(record);
        runtime.discovery().refresh(group);
        return Optional.empty();
    }

    public void leave(DeviceRecord record) {
        registry.leaveGroup(record);
        refreshRaw(record);
        runtime.discovery().refresh(record);
    }

    /** Deletes a group and clears its retained state and discovery; the members stay as devices of their own. */
    public void deleteGroup(DeviceGroup group) {
        List<DeviceRecord> members = group.members().stream().map(registry::record).filter(Objects::nonNull).toList();
        registry.removeGroup(group);
        closeGroupRaw(group.id());
        // Members of a legacy group follow the v1 topic each on their own again.
        members.forEach(this::refreshRaw);
        if (stopping) return;
        mqtt().publish(Outbound.clear(topics().group(group.slug())));
        runtime.discovery().removed(group);
        members.forEach(runtime.discovery()::refresh);
    }

    /**
     * A one-line summary of a loaded device for its screen and {@code /mineqtt devices info}: slug, Home Assistant
     * exposure, availability and what its Raw Link does.
     */
    public Optional<String> status(UUID id) {
        BlockEntity blockEntity = attached.get(id);
        DeviceRecord record = registry.record(id);
        if (blockEntity == null || record == null) return Optional.empty();
        DeviceLink link = requireHost(blockEntity).deviceLink();
        String availability = mqtt().state().isConnected() ? ONLINE : "broker not connected";
        return Optional.of(record.slug() + " | HA " + (runtime.discovery().exposed(record) ? "exposed" : "hidden") + " | "
                + availability + " | Raw " + rawStatus(record, link));
    }

    void publishState(DeviceHost host, boolean force) {
        UUID id = host.deviceLink().id();
        DeviceRecord record = id == null ? null : registry.record(id);
        if (record == null) return;
        JsonObject state = stateOf(host);
        if (!state.equals(record.lastState())) {
            record.setLastState(state);
            registry.markDirty();
        }
        if (stopping) return;
        StateThrottle throttle = throttles.computeIfAbsent(id, key -> StateThrottle.of(host.deviceType()));
        long now = Util.getMillis();
        if (force) {
            throttle.markPublished(state, now);
        } else if (throttle.offer(state, now) != StateThrottle.Verdict.PUBLISH) {
            return;
        }
        Outbound message = Outbound.state(topics().device(record.slug()), state);
        mqtt().publish(force ? message.asRefresh() : message);
        echoState(host, record, state);
    }

    /** From {@link DeviceLink#rawChanged}: the Raw Link of a loaded device was edited. */
    void rawChanged(DeviceHost host) {
        DeviceRecord record = registry.record(host.deviceLink().id());
        if (record == null) return;
        RawLink raw = host.deviceLink().raw();
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        // v1 kept one state per topic: a lamp leaves the legacy group of the topic it left, and joins the one it links to.
        if (LegacyAdoption.leavesLegacyGroup(group, raw)) {
            leave(record);
        } else {
            refreshRaw(record);
        }
        joinLegacyGroup(record, host);
    }

    void publishEvent(DeviceHost host, JsonObject event) {
        DeviceRecord record = registry.record(host.deviceLink().id());
        if (!stopping && record != null) mqtt().publish(Outbound.event(topics().action(record.slug()), event));
    }

    void publishRaw(DeviceHost host, String payload) {
        DeviceLink link = host.deviceLink();
        RawLink raw = link.raw();
        if (stopping || raw == null || raw.direction() != RawLinkSpec.Direction.OUT || !raw.active()) return;
        Optional<String> refusal = rawRefusal(link, raw);
        if (refusal.isPresent()) {
            // Not logged while the device's v1 owner is still looked up; the grandfather flag holds once it is known.
            if (!resolvingOwners.contains(link.id())) logOnce("raw-out:" + link.id(), "Device {} does not publish to its Raw Link '{}': {}", link.slug(), raw.topic(), refusal.get());
            return;
        }
        mqtt().publish(Outbound.raw(raw.topic(), ValueCodecs.utf8(payload), raw.qos(), raw.retain(), link.id()));
    }

    // True: attach now. False: drop it (removed, replaced, another server). Null: its chunk is not FULL yet.
    private @Nullable Boolean readyToAttach(BlockEntity blockEntity) {
        if (blockEntity.isRemoved() || !(blockEntity.getLevel() instanceof ServerLevel level) || level.getServer() != runtime.server()) return false;
        ChunkPos chunkPos = ChunkPos.containing(blockEntity.getBlockPos());
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
        if (chunk == null) return null;
        return chunk.getBlockEntities().get(blockEntity.getBlockPos()) == blockEntity;
    }

    /**
     * §6.5 attach: v1 data adopted, identity (minted or deduplicated), registry, queued commands, Raw Link,
     * availability, state, and discovery when it changed.
     */
    private void attach(BlockEntity blockEntity) {
        DeviceHost host = requireHost(blockEntity);
        DeviceLink link = host.deviceLink();
        DeviceType type = host.deviceType();
        GlobalPos pos = GlobalPos.of(blockEntity.getLevel().dimension(), blockEntity.getBlockPos());
        // §9: v1 data adopted on chunk load. A device the v1 topic states pre-created for its v1 topic takes that
        // record's identity; a record they listed here for another topic is forgotten below.
        LegacyNbtV1.Snapshot v1 = link.takePendingV1();
        DeviceRecord listed = registry.at(pos);
        DeviceRecord imported = v1 != null && LegacyAdoption.takesOver(listed, type, v1.combinedTopic(), listed != null && attached.containsKey(listed.id()))
                ? listed : null;
        if (v1 != null) {
            link.adopt(v1, true);
            if (imported != null) link.assign(imported.id(), imported.name(), imported.slug());
            blockEntity.setChanged();
        }
        UUID id = link.id();
        boolean copy = false;
        if (id == null) {
            id = UUID.randomUUID();
        } else {
            BlockEntity live = attached.get(id);
            DeviceRecord known = registry.record(id);
            // Devices never move: a block that is moved arrives without a record, as its removal forgot it. A known id
            // at another position is a copy (/clone, a structure, a schematic), also while the original is unloaded.
            boolean elsewhere = (live != null && live != blockEntity) || (known != null && !known.pos().equals(pos));
            if (elsewhere || (known != null && !known.type().equals(type.id()))) {
                copy = elsewhere;
                id = UUID.randomUUID();
            }
        }
        DeviceRecord record = registry.record(id);
        // A device new to the registry (placed, copied, moved) does not start with the server.
        boolean registered = record != null;
        boolean placed = link.placedByPlayer();
        // v1 data adopted on chunk load is the one untrusted path that keeps its grandfathering (§8.6).
        if (record == null && !placed && v1 == null) {
            // A copy is no v1 snapshot adopted on chunk load, so it is not grandfathered (§8.6).
            if (copy && link.raw() != null) link.setRaw(link.raw().withoutGrandfather());
            TopicAcl.Subject owner = AclContext.owner(runtime.server(), link.owner(), link.ownerName());
            link.revalidateUnseen(AclContext.mayExpose(runtime.server(), link.owner(), link.ownerName()), raw -> rawRefusal(owner, raw));
        }
        link.clearPlacedByPlayer();
        DeviceRecord previous = registry.at(pos);
        if (previous != null && !previous.id().equals(id)) {
            if (previous.legacyTopic() != null) {
                MineQTT.LOGGER.info("Device {} at {} was imported for the v1 topic {}, which the device there does not follow; forgetting it",
                        previous.slug(), pos, previous.legacyTopic());
                forgetImported(previous);
            } else {
                MineQTT.LOGGER.info("Device {} at {} was replaced by another device; forgetting it", previous.slug(), pos);
                forget(previous);
            }
        }
        if (record == null) {
            // Block entity data may have been edited (/data); the name becomes the Home Assistant device name. A v1
            // device is named after its topic, as the v1 topic states' devices are.
            String nbtName = ValueCodecs.sanitizeText(link.name()).strip();
            String v1Name = v1 == null ? "" : LegacyNbtV1.topicName(v1.combinedTopic());
            String name = !nbtName.isEmpty() ? (copy ? nbtName + " (copy)" : nbtName) : v1Name.isEmpty() ? type.defaultName(id) : v1Name;
            String wanted = copy || !v1Name.isEmpty() ? Slugs.fromName(name) : link.slug();
            record = new DeviceRecord(id, type.id(), pos, name, registry.uniqueSlug(wanted.isEmpty() ? type.defaultSlug(id) : wanted, id));
            registry.add(record);
            runtime.bridgeInfoChanged();
            if (v1 != null) adoptLegacyDiscovery(record, link, v1);
        } else if (imported != null) {
            record.setLegacyTopic(null);
            // The v1 light this device replaces stays in Home Assistant (§8.8), also when its unique_id went to another.
            if (Boolean.TRUE.equals(record.expose())) link.setExpose(true);
        }
        record.setOwner(link.owner());
        record.setAccess(link.access());
        record.setExpose(link.expose());
        registry.markDirty();
        if (link.assign(id, record.name(), record.slug())) blockEntity.setChanged();
        attached.put(id, blockEntity);
        link.setAttached(true);
        if (poweredOn.add(id) && registered) host.powerOn();
        JsonObject pending = record.pending();
        if (pending != null) {
            record.setPending(null);
            host.applyCommand(new CommandReader(pending), CommandSource.HA);
        }
        if (link.owner() == null && !link.ownerName().isEmpty()) resolveOwner(id, link.ownerName());
        // §9.2: a v1 lamp missing from the topic states, or a placed lamp, linked to a legacy group's topic joins it.
        if (v1 != null || placed) joinLegacyGroup(record, host);
        openRaw(host, type);
        if (!stopping) mqtt().publish(Outbound.state(topics().availability(record.slug()), ONLINE));
        publishState(host, true);
        if (!stopping) runtime.discovery().refresh(record);
    }

    /**
     * A v1 device that the topic states did not list for its topic keeps the Home Assistant light v1 discovered for that
     * topic, unless a legacy group replaces it or another device already holds its unique_id.
     */
    private void adoptLegacyDiscovery(DeviceRecord record, DeviceLink link, LegacyNbtV1.Snapshot v1) {
        String uniqueId = LegacyAdoption.keptUniqueId(registry, link.type(), v1.combinedTopic());
        if (uniqueId == null) return;
        record.setLegacyUniqueId(uniqueId);
        link.setExpose(true);
    }

    /**
     * Joins the legacy group of the device's Raw Link topic, if it is in no group, one exists for its type and the
     * topic is allowed for the device's owner (a grandfathered v1 topic is).
     */
    private void joinLegacyGroup(DeviceRecord record, DeviceHost host) {
        RawLink raw = host.deviceLink().raw();
        DeviceGroup group = LegacyAdoption.groupToJoin(registry, record, raw, host.deviceType());
        if (group == null) return;
        Optional<String> refusal = rawRefusal(host.deviceLink(), raw);
        if (refusal.isPresent()) {
            logOnce("legacy-join:" + record.id(), "Device {} does not join the legacy group {} of '{}': {}", record.slug(), group.slug(), raw.topic(), refusal.get());
        } else if (join(group, record).isEmpty()) {
            MineQTT.LOGGER.info("Device {} joined the legacy group {} of the v1 topic {}", record.slug(), group.slug(), raw.topic());
        }
    }

    /**
     * The owner of v1 data is only a name (§8.7). It is looked up off the server thread, since the profile cache may ask
     * Mojang's servers, and bound to the device once found; the name stays until then.
     */
    private void resolveOwner(UUID id, String name) {
        if (!resolvingOwners.add(id)) return;
        MinecraftServer server = runtime.server();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            ownerResolved(id, name, online.getUUID());
            return;
        }
        // The profile cache keeps no misses, so a name that is nobody's would ask Mojang's servers at every attach.
        if (unknownOwners.contains(name)) {
            resolvingOwners.remove(id);
            return;
        }
        CompletableFuture.supplyAsync(() -> server.services().nameToIdCache().get(name), Util.nonCriticalIoPool())
                .whenCompleteAsync((found, error) -> {
                    if (error != null) {
                        resolvingOwners.remove(id);
                        MineQTT.LOGGER.warn("Could not look up the owner {} of device {}", name, id, error);
                    } else if (found.isEmpty()) {
                        resolvingOwners.remove(id);
                        unknownOwners.add(name);
                        logOnce("owner:" + name, "No player is called {}, the owner of v1 devices; they have no owner until one is set", name);
                    } else {
                        ownerResolved(id, name, found.get().id());
                    }
                }, server);
    }

    private void ownerResolved(UUID id, String name, UUID owner) {
        resolvingOwners.remove(id);
        // The block entity owns the owner; an unloaded device asks again when it attaches.
        BlockEntity blockEntity = attached.get(id);
        if (stopping || blockEntity == null || registry.record(id) == null) return;
        DeviceHost host = requireHost(blockEntity);
        DeviceLink link = host.deviceLink();
        if (!name.equals(link.ownerName()) || !link.resolveOwner(owner)) return;
        blockEntity.setChanged();
        MineQTT.LOGGER.info("Device {} belongs to {} ({})", link.slug(), name, owner);
        ownerChanged(host);
    }

    /**
     * A loaded device got its first owner (a v1 owner name resolved, or a player claimed it): the record, the Raw Link
     * and the state echo follow.
     */
    void ownerChanged(DeviceHost host) {
        DeviceRecord record = registry.record(host.deviceLink().id());
        if (record == null) return;
        record.setOwner(host.deviceLink().owner());
        registry.markDirty();
        refreshRaw(record);
        // An output publishes on change; what it published while it had no owner was refused.
        String rawValue = host.rawValue();
        if (rawValue != null) publishRaw(host, rawValue);
        // The state echo waited for the owner too.
        publishState(host, true);
    }

    private void onRemoved(BlockEntity blockEntity, DeviceLink.Removal removal) {
        attachQueue.remove(blockEntity);
        DeviceHost host = requireHost(blockEntity);
        UUID id = host.deviceLink().id();
        if (id == null || attached.get(id) != blockEntity) return;
        attached.remove(id);
        host.deviceLink().setAttached(false);
        closeRaw(id);
        DeviceRecord record = registry.record(id);
        if (record == null) return;
        if (removal == DeviceLink.Removal.DESTROY) {
            forget(record);
            return;
        }
        // Unload: the record keeps the newest state for a queueing device; a sensor goes offline.
        record.setLastState(stateOf(host));
        registry.markDirty();
        throttles.remove(id);
        if (!stopping && host.deviceType().unloadedPolicy() == UnloadedPolicy.UNAVAILABLE) {
            mqtt().publish(Outbound.state(topics().availability(record.slug()), OFFLINE));
        }
    }

    /** Destroy: the record goes, with its group membership and its retained topics. */
    private void forget(DeviceRecord record) {
        BlockEntity live = attached.remove(record.id());
        if (live != null) requireHost(live).deviceLink().setAttached(false);
        registry.remove(record.id());
        throttles.remove(record.id());
        poweredOn.remove(record.id());
        closeRaw(record.id());
        runtime.bridgeInfoChanged();
        if (stopping) return;
        runtime.discovery().removed(record);
        mqtt().publish(Outbound.clear(topics().device(record.slug())));
        mqtt().publish(Outbound.clear(topics().availability(record.slug())));
    }

    // A record the v1 topic states listed at a position whose device follows another topic. A legacy group that loses
    // its last member this way replaces no v1 light: it is deleted, which also clears that light's retained discovery.
    private void forgetImported(DeviceRecord record) {
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        forget(record);
        if (group != null && group.legacyTopic() != null && group.members().isEmpty()) deleteGroup(group);
    }

    private void prune(DeviceRecord record) {
        MineQTT.LOGGER.info("Forgetting device {} ({}): its block at {} is gone", record.slug(), record.type(), record.pos());
        forget(record);
    }

    // The record's block is gone: the loaded chunk holds no device block entity with its id at its position. A v1 block
    // entity of the record's type still to be adopted takes the record over when it attaches (§9.2).
    private boolean isStale(DeviceRecord record, LevelChunk chunk) {
        if (attached.containsKey(record.id())) return false;
        BlockEntity blockEntity = chunk.getBlockEntities().get(record.pos().pos());
        if (!(blockEntity instanceof DeviceHost host)) return true;
        if (host.deviceLink().awaitingAdoption()) return !host.deviceType().id().equals(record.type());
        return !record.id().equals(host.deviceLink().id());
    }

    /** §4.2 step 3, on every CONNACK: a sensor that is not loaded must not keep a retained {@code online}. */
    private void connected() {
        for (DeviceRecord record : registry.records()) {
            DeviceType type = DeviceTypes.get(record.type());
            if (type != null && type.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE && !attached.containsKey(record.id())) {
                mqtt().publish(Outbound.state(topics().availability(record.slug()), OFFLINE));
            }
        }
    }

    // Availability and state, sent even when unchanged.
    private void publishAll(DeviceRecord record, DeviceType type) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity != null) {
            mqtt().publish(Outbound.state(topics().availability(record.slug()), ONLINE).asRefresh());
            publishState(requireHost(blockEntity), true);
        } else if (type.unloadedPolicy() == UnloadedPolicy.QUEUE) {
            mqtt().publish(Outbound.state(topics().availability(record.slug()), ONLINE).asRefresh());
            if (record.lastState() != null) {
                mqtt().publish(Outbound.state(topics().device(record.slug()), effectiveState(record, type)).asRefresh());
            }
        } else {
            mqtt().publish(Outbound.state(topics().availability(record.slug()), OFFLINE).asRefresh());
        }
    }

    private void onCommand(Inbound message) {
        String root = commandTopics.root() + "/";
        // An empty payload is someone clearing a retained command; it commands nothing.
        if (!message.topic().startsWith(root) || message.payload().length == 0) return;
        String[] levels = message.topic().substring(root.length()).split("/", -1);
        boolean group = levels[0].equals("group");
        int slugIndex = group ? 1 : 0;
        // The filters overlap (<root>/group/set matches <root>/+/set, <root>/group/set/set two filters): only
        // <slug>/set[/<field>] and group/<slug>/set[/<field>] are commands, and a reserved slug (set too) names nothing.
        if (levels.length < slugIndex + 2 || levels.length > slugIndex + 3 || !levels[slugIndex + 1].equals("set")) return;
        String slug = levels[slugIndex];
        String field = levels.length > slugIndex + 2 ? levels[slugIndex + 2] : null;
        if (Slugs.RESERVED.contains(slug)) return;
        Optional<JsonObject> command = field == null ? message.json().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
                : FIELD_KEY.matcher(field).matches() ? Optional.of(single(field, ValueCodecs.plainValue(message.text()))) : Optional.empty();
        if (command.isEmpty()) {
            logOnce("command:" + message.topic(), "Ignoring a command on {}: {}", message.topic(),
                    field == null ? "/set takes a JSON object, /set/<field> a plain value" : "invalid field name");
            return;
        }
        if (group) {
            DeviceGroup target = registry.groupBySlug(slug);
            if (target != null) groupCommand(target, command.get());
        } else {
            DeviceRecord record = registry.bySlug(slug);
            DeviceType type = record == null ? null : DeviceTypes.get(record.type());
            if (type != null) command(record, type, command.get(), CommandSource.HA);
        }
    }

    // §6.6: a loaded device applies it, an unloaded queueing device queues it, an unloaded sensor ignores it.
    private void command(DeviceRecord record, DeviceType type, JsonObject command, CommandSource source) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity != null) {
            DeviceHost host = requireHost(blockEntity);
            CommandResult result = host.applyCommand(new CommandReader(command), source);
            switch (result.outcome()) {
                case APPLIED -> publishState(host, false);
                case REJECTED -> MineQTT.LOGGER.debug("Device {} rejected {}: {}", record.slug(), command, result.detail());
                case UNCHANGED -> {
                }
            }
        } else if (type.unloadedPolicy() == UnloadedPolicy.QUEUE) {
            queue(record, type, command);
        }
    }

    /**
     * Queues a command for an unloaded device. The pending command holds the absolute target of every writable field
     * the commands changed, so toggles are resolved now and the published state shows the target.
     */
    private void queue(DeviceRecord record, DeviceType type, JsonObject command) {
        JsonObject last = record.lastState() == null ? new JsonObject() : record.lastState();
        JsonObject target = type.preview(effectiveState(record, type), command);
        record.setPending(type.difference(last, target));
        registry.markDirty();
        if (!stopping) mqtt().publish(Outbound.state(topics().device(record.slug()), target));
    }

    /**
     * §6.6 groups: toggles are resolved against the group's state so every member gets the same target, the command
     * fans out to the members, and the group publishes once.
     */
    private void groupCommand(DeviceGroup group, JsonObject command) {
        DeviceType type = group.memberType() == null ? null : DeviceTypes.get(group.memberType());
        if (type == null) return;
        JsonObject resolved = type.resolveToggles(command, group.state());
        DeviceRecord representative = null;
        for (UUID member : group.members()) {
            DeviceRecord record = registry.record(member);
            if (record == null) continue;
            command(record, type, resolved, CommandSource.GROUP);
            if (representative == null) representative = record;
        }
        group.setState(type.writableState(representative != null ? effectiveState(representative, type) : type.preview(group.state(), resolved)));
        registry.markDirty();
        publishGroupState(group);
        echoGroupState(group, type);
    }

    private void publishGroupState(DeviceGroup group) {
        if (!stopping) mqtt().publish(Outbound.state(topics().group(group.slug()), group.state()));
    }

    // What the device shows now: a loaded one its block entity's state, an unloaded one its record with the queue.
    private JsonObject effectiveState(DeviceRecord record, DeviceType type) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity != null) return stateOf(requireHost(blockEntity));
        JsonObject last = record.lastState() == null ? new JsonObject() : record.lastState();
        return record.pending() == null ? last.deepCopy() : type.preview(last, record.pending());
    }

    private static JsonObject stateOf(DeviceHost host) {
        StateWriter writer = new StateWriter();
        host.writeState(writer);
        return writer.json();
    }

    private void openRaw(DeviceHost host, DeviceType type) {
        DeviceLink link = host.deviceLink();
        UUID id = link.id();
        closeRaw(id);
        RawLink raw = link.raw();
        RawLinkSpec spec = type.rawLink().orElse(null);
        if (stopping || raw == null || spec == null || spec.direction() != RawLinkSpec.Direction.IN
                || raw.direction() != RawLinkSpec.Direction.IN || !raw.active() || followedByGroup(registry.record(id), raw)) {
            return;
        }
        Optional<String> refusal = rawRefusal(link, raw);
        if (refusal.isPresent()) {
            // A device whose v1 owner is still being looked up tries again once the owner is known.
            if (!resolvingOwners.contains(id)) logOnce("raw-in:" + id, "Device {} does not follow its Raw Link '{}': {}", link.slug(), raw.topic(), refusal.get());
            return;
        }
        rawSubscriptions.put(id, mqtt().subscribe(raw.topic(), SubOptions.raw(spec.acceptRetained(), id), message -> {
            BlockEntity blockEntity = attached.get(id);
            if (blockEntity == null) return;
            DeviceHost target = requireHost(blockEntity);
            RawLink current = target.deviceLink().raw();
            ValueCodecs.CustomPayloads custom = current == null ? ValueCodecs.CustomPayloads.NONE : current.customPayloads();
            spec.grammar().command(message.text(), custom).ifPresent(command -> {
                CommandResult result = target.applyCommand(new CommandReader(command), CommandSource.RAW);
                if (result.outcome() == CommandResult.Outcome.APPLIED) publishState(target, false);
            });
        }));
    }

    private void closeRaw(UUID id) {
        Subscription subscription = rawSubscriptions.remove(id);
        if (subscription != null) subscription.close();
    }

    // Opens or closes a loaded device's Raw Link input after its link or its group changed.
    private void refreshRaw(DeviceRecord record) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity != null) openRaw(requireHost(blockEntity), requireHost(blockEntity).deviceType());
    }

    private boolean followedByGroup(@Nullable DeviceRecord record, RawLink raw) {
        return LegacyAdoption.followedByGroup(registry, record, raw);
    }

    private String rawStatus(DeviceRecord record, DeviceLink link) {
        RawLink raw = link.raw();
        if (raw == null) return "none";
        if (!raw.active()) return "disabled: " + raw.disabledReason();
        if (followedByGroup(record, raw)) return raw.topic() + " via group " + registry.group(record.group()).slug();
        Optional<String> refusal = rawRefusal(link, raw);
        if (refusal.isPresent()) return raw.topic() + " refused: " + refusal.get();
        return raw.topic() + (raw.grandfathered() != null ? " (v1, grandfathered)" : "");
    }

    /**
     * The v1 state echo of a migrated input (§6.6): the echo keys of its state to {@code <topic>/state}, at QoS 1 and not
     * retained as in v1. A legacy group echoes once for its members.
     */
    private void echoState(DeviceHost host, DeviceRecord record, JsonObject state) {
        DeviceLink link = host.deviceLink();
        RawLink raw = link.raw();
        RawLinkSpec spec = host.deviceType().rawLink().orElse(null);
        if (stopping || raw == null || spec == null || !raw.stateEcho() || !raw.active() || followedByGroup(record, raw)) return;
        Optional<String> refusal = refusal(runtime.acl().checkPublish(AclContext.owner(runtime.server(), link.owner(), link.ownerName()),
                raw.echoTopic(), raw.echoGrandfather()));
        if (refusal.isPresent()) {
            if (!resolvingOwners.contains(link.id())) logOnce("echo:" + link.id(), "Device {} does not echo its state to '{}': {}", link.slug(), raw.echoTopic(), refusal.get());
            return;
        }
        mqtt().publish(echo(raw.echoTopic(), spec.echo(state), link.id()));
    }

    private void echoGroupState(DeviceGroup group, DeviceType type) {
        RawLinkSpec spec = type.rawLink().orElse(null);
        if (stopping || group.legacyTopic() == null || spec == null || !spec.echoes()) return;
        String topic = group.legacyTopic() + RawLink.ECHO_SUFFIX;
        if (runtime.acl().checkLegacy(topic, true).allowed()) mqtt().publish(echo(topic, spec.echo(group.state()), group.id()));
    }

    // Latest wins per topic like a state, but never retained and dropped while offline, as v1 sent it.
    private static Outbound echo(String topic, JsonObject state, UUID origin) {
        return new Outbound(topic, ValueCodecs.utf8(state.toString()), 1, false, OfflinePolicy.DROP, Outbound.Delivery.STATE, origin);
    }

    /**
     * A legacy group follows its v1 topic once for all members (§6.6), as v1 data under the legacy rules: the hard deny
     * applies, the allowlist does not.
     */
    private void openGroupRaw(DeviceGroup group) {
        String topic = group.legacyTopic();
        DeviceType type = group.memberType() == null ? null : DeviceTypes.get(group.memberType());
        RawLinkSpec spec = type == null ? null : type.rawLink().orElse(null);
        if (stopping || topic == null || spec == null || spec.direction() != RawLinkSpec.Direction.IN) return;
        TopicAcl.Decision decision = runtime.acl().checkLegacy(topic, false);
        if (!decision.allowed()) {
            logOnce("group-raw:" + group.id(), "Group {} does not follow its v1 topic '{}': {}", group.slug(), topic, decision.message());
            return;
        }
        UUID id = group.id();
        groupRawSubscriptions.put(id, mqtt().subscribe(topic, SubOptions.raw(spec.acceptRetained(), id), message -> {
            DeviceGroup current = registry.group(id);
            if (current == null) return;
            spec.grammar().command(message.text(), ValueCodecs.CustomPayloads.NONE).ifPresent(command -> groupCommand(current, command));
        }));
    }

    private void closeGroupRaw(UUID id) {
        Subscription subscription = groupRawSubscriptions.remove(id);
        if (subscription != null) subscription.close();
    }

    // A Raw Link topic is checked like a player's publish, for the device's owner.
    private Optional<String> rawRefusal(DeviceLink link, RawLink raw) {
        return rawRefusal(AclContext.owner(runtime.server(), link.owner(), link.ownerName()), raw);
    }

    private Optional<String> rawRefusal(TopicAcl.Subject owner, RawLink raw) {
        return refusal(runtime.acl().checkPublish(owner, raw.topic(), raw.grandfathered()));
    }

    private static JsonObject single(String key, JsonElement value) {
        JsonObject json = new JsonObject();
        json.add(key, value);
        return json;
    }

    private void logOnce(String key, String format, Object... args) {
        if (loggedProblems.size() < MAX_LOGGED_PROBLEMS && loggedProblems.add(key)) MineQTT.LOGGER.warn(format, args);
    }

    private MqttService mqtt() {
        return runtime.mqtt();
    }

    private TopicScheme topics() {
        return runtime.topics();
    }
}

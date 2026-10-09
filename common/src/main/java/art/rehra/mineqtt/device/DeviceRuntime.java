package art.rehra.mineqtt.device;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.FlowMessage;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.PortType;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.legacy.LegacyConverterBlock;
import art.rehra.mineqtt.legacy.LegacyNbtV1;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.security.NoticeGate;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The devices of one server (design §6.5, §6.6): attaches and detaches device block entities, routes the
 * {@code /set} commands of devices and groups, queues commands for unloaded devices, publishes state and
 * availability within each type's limits, and prunes registry records whose block is gone. Server thread only.
 * <ul>
 *     <li>Attach runs one tick after {@code setLevel}, so a placement has applied its item data and
 *     {@code setPlacedBy} first. Blocks that load before the server has started wait for it.</li>
 *     <li>Commands use four broker subscriptions, whatever the number of devices. Every command reaches its device
 *     through {@link #command} (M2 plan §3.8 #2): a loaded device applies it, an unloaded {@link UnloadedPolicy#QUEUE}
 *     device queues it, an unloaded sensor drops it, and a group fans it out to its members.</li>
 *     <li>Players change settings and perform actions through {@link #configure} and {@link #action}, from a device's
 *     screen or a command twin; every open screen of a device that changed gets its new {@link DeviceView} at the end of
 *     the tick.</li>
 *     <li>Devices power on with their server: the records of queueing devices when the runtime starts, each block
 *     entity at its first attach.</li>
 *     <li>After {@code SERVER_STOPPING} nothing is published any more; detach and destroy only update the registry,
 *     which is saved at {@code SERVER_STOPPED}.</li>
 *     <li>v1 data is adopted at attach (§9): a device the v1 topic states pre-created for its v1 topic takes over its
 *     record, others keep their v1 light's unique_id or join the legacy group of their topic ({@link LegacyAdoption});
 *     the v1 topic becomes a grandfathered Raw Link whose owner name is resolved off the server thread. A legacy group
 *     follows its v1 topic once for all members and echoes its state.</li>
 *     <li>A Raw Link M1 saved is re-resolved once at its attach ({@link RawTopics#reResolveM1}). A link with an item or
 *     paper channel is resolved again at every attach, when the device gets its owner and when the owner's literal set
 *     gains the channel. Its owner hears about a move when they are online, join or open a device screen.</li>
 *     <li>A refused or moved Raw Link reaches the players (M2 plan D1): a chat line to the player who just edited the
 *     device, else to its owner (at once, or when they join or open a device screen), the owner's action bar, and the
 *     status line of every open screen of the device. The same refusal is told once. A config reload judges every
 *     attached Raw Link again ({@link #aclChanged}).</li>
 *     <li>Wireless redstone is retained (M2 plan D2): a retaining Raw Link output publishes its current level again after
 *     every CONNACK, once its value is known after the attach and when it gets an owner. When it leaves its topic
 *     (destroyed, retargeted) it publishes its off payload there, and every other retaining output of this server on
 *     that topic its own level ({@link RawRetain}). Unloading publishes nothing, and neither does forgetting a device
 *     that is not loaded: its level stays on the broker.</li>
 *     <li>Each device's attach, power-on and prune runs in isolation (M2 plan D7, {@link AttachQueue}): a device whose
 *     work throws is logged once with the stack trace and marked faulted and offline, and the other devices carry on. It
 *     is tried again when its chunk loads again or with {@code /mineqtt devices retry}, also from its screen.</li>
 *     <li>Each device's MQTT traffic is kept for its Diagnostics tab ({@link DeviceTraffic}, M2-11): the last message in
 *     and out, how many went each way, and how many it received but dropped (no command, or refused).</li>
 *     <li>Local bindings are wires in the registry, carried by the flows {@link MessageBus} (M2 plan D9, §3.3): every
 *     changed readable field of a device and every event it fires is emitted on its out-port, on the stack of whatever
 *     changed it, and the bus is drained after the inbound messages, before the outbound flush and in the paused-server
 *     pump. A message reaches its target through {@link #command} as a {@link CommandSource#BINDING} command, so it
 *     needs no broker; an unloaded actuator queues it.</li>
 * </ul>
 * Discovery follows every change of identity, exposure and group membership through the runtime's
 * {@link art.rehra.mineqtt.ha.DiscoveryPublisher}.
 */
public final class DeviceRuntime {
    private static final String ONLINE = "online";
    private static final String OFFLINE = "offline";
    // The status line of a device that does not run on this server.
    private static final String NOT_RUNNING = "Not registered yet";
    private static final int MAX_LOGGED_PROBLEMS = 256;
    private static final int MAX_NOTICES_PER_OWNER = 8;
    private static final int MAX_SHOWN_JSON = 256;
    // How often a screen's Retry attaches a faulted device again: a held button must not rerun a failing attach every tick.
    private static final long SCREEN_RETRY_INTERVAL_MILLIS = 1000;
    /** What a refused claim or retry from a device's screen names as its key ({@code DeviceRefusedS2C}). */
    public static final String CLAIM_KEY = "claim";
    public static final String RETRY_KEY = "retry";
    public static final String EJECT_KEY = "eject";
    /** What a refused removal from a device's links list names as its key ({@code DeviceRefusedS2C}). */
    public static final String LINKS_KEY = "links";
    /** What a refused change from a device's group page names as its key ({@code DeviceRefusedS2C}). */
    public static final String GROUP_KEY = "group";
    /**
     * How many ticks a device that adopted a v1 remote and found nothing it commanded waits, while it is loaded, for lamps
     * the registry does not know yet before its v1 commands are dropped (M2 plan D15).
     */
    public static final int REMOTE_WIRE_WAIT_TICKS = 1200;
    // What remotesToWire holds for a device that waits for lamps it may have commanded to load, or for an owner's UUID.
    private static final int REMOTE_UNDECIDED = -1;
    // Block entities that registered while their server was starting: devices to attach, legacy blocks to convert, other
    // blocks whose owner to look up.
    private static final Map<MinecraftServer, Set<BlockEntity>> beforeStart = new IdentityHashMap<>();

    private final MineqttRuntime runtime;
    private final DeviceRegistry registry;
    private final Map<UUID, BlockEntity> attached = new HashMap<>();
    private final AttachQueue<BlockEntity> attachQueue = new AttachQueue<>();
    // Legacy blocks that become their successor on the next tick, before that tick's attaches (LegacyConverterBlock).
    private final AttachQueue<BlockEntity> conversions = new AttachQueue<>();
    private final Set<ChunkRef> pruneQueue = new LinkedHashSet<>();
    // Attached devices whose record keeps the topic of the v1 remote they adopted (M2 plan D15), with the ticks they still
    // wait for unknown lamps, or REMOTE_UNDECIDED: those are matched again only after remotesChanged.
    private final Map<UUID, Integer> remotesToWire = new LinkedHashMap<>();
    // A lamp attached, an owner became known or a record went since the waiting remotes were last matched.
    private boolean remotesChanged;
    // Devices whose attach, power-on or prune threw, by position: offline until an attach there succeeds.
    private final Map<GlobalPos, Fault> faults = new LinkedHashMap<>();
    // When a screen last queued the retry of each faulted position; only those of the last interval are kept.
    private final Map<GlobalPos, Long> screenRetries = new HashMap<>();
    private final Map<UUID, Subscription> rawSubscriptions = new HashMap<>();
    // Groups that replace a v1 topic follow it once for all their members.
    private final Map<UUID, Subscription> groupRawSubscriptions = new HashMap<>();
    private final Map<UUID, StateThrottle> throttles = new HashMap<>();
    private final List<Subscription> commandSubscriptions = new ArrayList<>();
    private final Set<UUID> resolvingOwners = new HashSet<>();
    // Loaded blocks that are no devices, with a v1 owner name to resolve, and those whose lookup is under way.
    private final AttachQueue<BaseMqttBlockEntity> ownerLookups = new AttachQueue<>();
    private final Set<BaseMqttBlockEntity> resolvingLegacyOwners = new HashSet<>();
    // Devices that attached in this server run; the first attach of a registered device is its power-on.
    private final Set<UUID> poweredOn = new HashSet<>();
    // v1 owner names the profile lookup found nobody for; until a restart only an online player of exactly that name resolves them.
    private final Set<String> unknownOwners = new HashSet<>();
    // v1 owner names whose profile lookup is under way, with what waits for its result.
    private final Map<String, List<Consumer<@Nullable UUID>>> ownerLookupsByName = new HashMap<>();
    private final Set<String> loggedProblems = new HashSet<>();
    // Notices for owners who were offline, sent when they join or open a device screen.
    private final Map<UUID, OwnerNotices> ownerNotices = new HashMap<>();
    // The Raw Link problem of each attached device as last told, so the same refusal is told once.
    private final NoticeGate<UUID> rawProblems = new NoticeGate<>();
    // Loaded devices whose open screens get a new view at the end of the tick, once however often they changed.
    private final Set<UUID> staleViews = new LinkedHashSet<>();
    // Devices one of whose links changed this tick (a wire made or removed, a peer renamed, a group joined): their open
    // screens get the new list at the end of the tick.
    private final Set<UUID> staleLinks = new LinkedHashSet<>();
    // A group was made, removed, renamed, joined or left this tick: every open device screen gets its group page anew at
    // the end of the tick, as the groups each may join changed too.
    private boolean staleGroups;
    // The broker connection's status when the views were last sent: they show it in the status line and the Diagnostics tab.
    private ConnectionState.@Nullable Status viewedConnection;
    // What each device sent and received since the server started, for its Diagnostics tab; forgotten with its record.
    private final Map<UUID, DeviceTraffic> traffic = new HashMap<>();
    // Carries what devices emit over their wires; in-flight messages are not kept across a restart (M2 plan §3.6).
    private final MessageBus bus;
    private TopicScheme commandTopics;
    private boolean stopping;

    private record ChunkRef(ResourceKey<Level> dimension, long chunk) {
        static ChunkRef of(GlobalPos pos) {
            return new ChunkRef(pos.dimension(), ChunkPos.containing(pos.pos()).pack());
        }
    }

    /** The step of a device's work that threw (M2 plan D7). */
    public enum Stage {
        ATTACH, POWER_ON, PRUNE;

        public String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Why a device is faulted: the step that threw and the exception, without its stack trace. */
    public record Fault(Stage stage, String reason) {
        public String text() {
            return stage.label() + " failed: " + reason;
        }
    }

    /** How {@link #command} delivers a command (M2 plan §3.3), decided by {@link #route}. */
    public enum Route {
        /** A loaded device applies it. */
        APPLY,
        /** An unloaded {@link UnloadedPolicy#QUEUE} device queues it as its pending target, which its state shows. */
        QUEUE,
        /** A group fans it out to its members, each routed on its own. */
        FAN_OUT,
        /**
         * Nobody takes it: an unloaded sensor, a device or group of a type this MineQTT does not know, a group that has
         * no member type yet, or an id that names nothing.
         */
        DROP
    }

    /**
     * What {@link #command} did with a command.
     *
     * @param result what a loaded device did with it; null for every other route
     */
    public record Delivery(Route route, @Nullable CommandResult result) {
    }

    /**
     * Who changes a setting of a device or performs one of its actions (M2-6): what they may do, who they are to the ACL,
     * which judges a topic they type, and the player, who hears what came of a Raw Link change.
     *
     * @param player null for the console or a command block
     */
    public record Editor(DeviceView.Grants grants, TopicAcl.Subject subject, @Nullable ServerPlayer player) {
        /** A player at the device's screen, with what they may do there. */
        public static Editor of(ServerPlayer player, BlockEntity blockEntity) {
            return new Editor(DeviceAccess.grants(player, blockEntity), AclContext.of(player), player);
        }
    }

    /** What {@link #configure}, {@link #action} or {@link #set} did, and a sentence that says so. */
    public record Configured(Result result, String detail) {
        public enum Result {
            /** The device took the change. */
            APPLIED,
            /** The device already was as asked. */
            UNCHANGED,
            /** The device is not loaded; the change waits in its record for its next attach. */
            QUEUED,
            /** Not changed: not allowed, not valid, or nobody takes it. */
            REFUSED
        }

        static Configured applied(String detail) {
            return new Configured(Result.APPLIED, detail);
        }

        static Configured unchanged(String detail) {
            return new Configured(Result.UNCHANGED, detail);
        }

        static Configured refused(String detail) {
            return new Configured(Result.REFUSED, detail);
        }
    }

    /** What {@link #retry} did. */
    public enum RetryOutcome {
        /** Nothing is faulted there. */
        NOT_FAULTED,
        /** Its chunk is not loaded; the device is tried again when it loads. */
        NOT_LOADED,
        /** Queued for the next tick; the fault stays until it attaches or is checked. */
        QUEUED,
        /** No device stands there any more; the fault is cleared. */
        GONE
    }

    // The first notices for an offline owner, and how many more were left out.
    private static final class OwnerNotices {
        private final List<Component> lines = new ArrayList<>();
        private int more;
    }

    /**
     * Powers on the unloaded devices, opens the command subscriptions and the v1 topics of legacy groups, and takes over
     * the block entities that loaded before the server started.
     */
    public DeviceRuntime(MineqttRuntime runtime, DeviceRegistry registry) {
        this.runtime = runtime;
        this.registry = registry;
        this.bus = new MessageBus(new RegistryWiring(registry), this::deliverWire, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
                MineQTT.LOGGER.warn("Wire {} is part of a loop without a delay: a message from {} had travelled {} wires and was dropped "
                        + "(told once per wire; /mineqtt status counts every one)", describe(wire), describe(message.source()), message.hops());
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                MineQTT.LOGGER.error("Delivering {} over wire {} failed", message.payload(), describe(wire), e);
            }
        }, System::nanoTime);
        powerOnRecords();
        Set<BlockEntity> waiting = beforeStart.remove(runtime.server());
        if (waiting != null) {
            for (BlockEntity blockEntity : waiting) {
                if (blockEntity.getBlockState().getBlock() instanceof LegacyConverterBlock) {
                    conversions.add(blockEntity);
                } else if (blockEntity instanceof BaseMqttBlockEntity block) {
                    ownerLookups.add(block);
                } else {
                    attachQueue.add(blockEntity);
                }
            }
        }
        topicsChanged();
        registry.groups().forEach(this::openGroupRaw);
        runtime.mqtt().onConnected(this::connected);
    }

    /**
     * Devices start with their server, also those whose chunk is not loaded: a queueing device's record shows the state
     * it starts in, so its state is published and the commands queued for it are diffed from there. Its block entity
     * powers on the same way when it attaches. A group whose first member started in another state follows it. What a
     * device started differently in leaves on its out-ports and reaches its wires' targets at the first drain.
     */
    private void powerOnRecords() {
        Set<UUID> changed = new HashSet<>();
        for (DeviceRecord record : registry.records()) {
            DeviceType type = DeviceTypes.get(record.type());
            if (type == null || type.unloadedPolicy() != UnloadedPolicy.QUEUE || record.lastState() == null) continue;
            boolean startedChanged = AttachQueue.isolate(record, powered -> {
                JsonObject before = powered.lastState();
                JsonObject started = type.poweredOn(before);
                if (started.equals(before)) return false;
                powered.setLastState(started);
                // Its wires carry the change (an output whose power-on behaviour is off), as the attach will not see one.
                emitChanges(powered.id(), type, before, started);
                return true;
            }, (failed, e) -> fault(failed.pos(), Stage.POWER_ON, e));
            if (startedChanged) changed.add(record.id());
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
            if (!devices.stopping) {
                devices.attachQueue.add(blockEntity);
                devices.runtime.workQueued();
            }
        } else {
            beforeStart.computeIfAbsent(level.getServer(), server -> new LinkedHashSet<>()).add(blockEntity);
        }
    }

    /**
     * From the block entity of a {@link LegacyConverterBlock}: the block becomes its successor on the next tick of its
     * server ({@link LegacyConverterBlock#replace}), before the attaches of that tick, so the successor attaches with the
     * converted data in the same tick. Blocks that load before the server has started wait for it.
     */
    public static void scheduleConversion(ServerLevel level, BlockEntity blockEntity) {
        DeviceRuntime devices = of(level.getServer());
        if (devices != null) {
            if (!devices.stopping) {
                devices.conversions.add(blockEntity);
                devices.runtime.workQueued();
            }
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

    /**
     * From {@link DeviceLink#viewChanged}: the open screens of an attached device get its new view at the end of the tick
     * ({@link #viewChanged(UUID)}). A device that does not run on this server (not attached yet, faulted, MineQTT not
     * started) is in no tick's views, so its viewers get the view of its block entity's values at once.
     */
    static void viewChanged(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) return;
        DeviceRuntime devices = of(level.getServer());
        UUID id = requireHost(blockEntity).deviceLink().id();
        if (devices != null && id != null && devices.attached.get(id) == blockEntity) {
            devices.viewChanged(id);
            return;
        }
        for (ServerPlayer viewer : MineqttNetworking.viewers(blockEntity)) {
            MineqttNetworking.sendDeviceView(viewer, view(viewer, blockEntity));
        }
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

    /**
     * How many MQTT messages a device received, of those dropped, and sent since the server started (M2-11), as
     * {@code devices info} shows them; its Diagnostics tab shows the last ones too.
     */
    public String trafficSummary(UUID id) {
        DeviceTraffic kept = traffic.get(id);
        return kept == null ? "none yet" : kept.summary();
    }

    /** Why the device at a position is faulted (M2 plan D7); empty while it is not. */
    public Optional<Fault> fault(GlobalPos pos) {
        return Optional.ofNullable(faults.get(pos));
    }

    /** The faulted devices by position, oldest first. */
    public Map<GlobalPos, Fault> faults() {
        return Collections.unmodifiableMap(faults);
    }

    /**
     * {@code /mineqtt devices retry}: a faulted device whose chunk is loaded is queued again, so its attach, or the check
     * of its record after a failed prune, runs on the next tick. The fault stays until then, and comes back with the
     * new failure if it throws again.
     */
    public RetryOutcome retry(GlobalPos pos) {
        Fault fault = faults.get(pos);
        if (fault == null) return RetryOutcome.NOT_FAULTED;
        // A prune that threw after its record left the registry has nothing left to check.
        if (fault.stage() == Stage.PRUNE && registry.at(pos) == null) {
            faults.remove(pos);
            return RetryOutcome.GONE;
        }
        ServerLevel level = runtime.server().getLevel(pos.dimension());
        ChunkPos chunkPos = ChunkPos.containing(pos.pos());
        LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
        if (chunk == null) return RetryOutcome.NOT_LOADED;
        BlockEntity blockEntity = chunk.getBlockEntities().get(pos.pos());
        if (fault.stage() == Stage.PRUNE) {
            pruneQueue.add(ChunkRef.of(pos));
        } else if (blockEntity instanceof DeviceHost) {
            attachQueue.add(blockEntity);
        } else {
            // No device stands there any more: a record left at the position is forgotten by the check of its chunk.
            faults.remove(pos);
            if (registry.at(pos) != null) {
                pruneQueue.add(ChunkRef.of(pos));
                runtime.workQueued();
            }
            return RetryOutcome.GONE;
        }
        runtime.workQueued();
        return RetryOutcome.QUEUED;
    }

    // A screen's retry: a position still faulted that a screen retried within the interval counts as queued already.
    private RetryOutcome screenRetry(GlobalPos pos) {
        long now = Util.getMillis();
        screenRetries.values().removeIf(at -> now - at >= SCREEN_RETRY_INTERVAL_MILLIS);
        if (screenRetries.containsKey(pos) && faults.containsKey(pos)) return RetryOutcome.QUEUED;
        RetryOutcome outcome = retry(pos);
        if (outcome == RetryOutcome.QUEUED) screenRetries.put(pos, now);
        return outcome;
    }

    /** {@code SERVER_PRE}, before the inbound messages: converts legacy blocks, attaches and prunes. */
    public void tickStart() {
        if (stopping) return;
        conversions.drain(blockEntity -> {
            Boolean ready = readyToAttach(blockEntity);
            if (ready == null) return false;
            if (ready) LegacyConverterBlock.replace((ServerLevel) blockEntity.getLevel(), blockEntity);
            return true;
        }, (blockEntity, e) -> MineQTT.LOGGER.error("Could not convert the legacy block at {}; it converts when its chunk loads again",
                blockEntity.getBlockPos().toShortString(), e));
        attachQueue.drain(blockEntity -> {
            Boolean ready = readyToAttach(blockEntity);
            if (ready == null) return false;
            if (ready) attach(blockEntity);
            return true;
        }, this::attachFailed);
        wireRemotes();
        ownerLookups.drain(this::lookUpLegacyOwner, (block, e) -> MineQTT.LOGGER.error("Could not look up the owner {} of the block at {}",
                block.getOwnerName(), block.getBlockPos().toShortString(), e));
        for (ChunkRef ref : List.copyOf(pruneQueue)) {
            pruneQueue.remove(ref);
            ServerLevel level = runtime.server().getLevel(ref.dimension());
            LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(ChunkPos.getX(ref.chunk()), ChunkPos.getZ(ref.chunk()));
            if (chunk == null) continue;
            for (DeviceRecord record : registry.inChunk(ref.dimension(), chunk.getPos())) {
                pruneIfStale(record, chunk);
            }
        }
    }

    /**
     * Whether the next tick has legacy blocks to convert, devices to attach or to wire to their v1 remote's lamps, owners
     * to look up, chunks to prune, held states to publish or messages to carry over wires, including values the bus holds
     * back until their port's rate allows them.
     */
    public boolean hasPendingWork() {
        return !stopping && (!conversions.isEmpty() || !attachQueue.isEmpty() || !ownerLookups.isEmpty() || !pruneQueue.isEmpty() || bus.hasQueued()
                || remotesDue() || throttles.values().stream().anyMatch(StateThrottle::hasDeferred));
    }

    /**
     * The drain points of the message bus (M2 plan §3.3): after the inbound messages, so MQTT input reaches its wires in
     * the same tick, and at {@link #tickEnd}, so what block tickers emitted this tick leaves before the flush. Both run in
     * the paused-server pump as well.
     */
    public void drainBus() {
        if (!stopping) bus.drain();
    }

    /**
     * {@code SERVER_POST}, before the outbound flush: carries the messages emitted this tick over their wires, publishes
     * states whose minimum interval is over, and sends the open screens of the devices that changed this tick their new
     * view. A change of the broker connection changes the view of every attached device; nothing else would tell the open
     * screen of an idle one.
     */
    public void tickEnd() {
        if (stopping) return;
        drainBus();
        if (!throttles.isEmpty()) {
            long now = Util.getMillis();
            throttles.forEach((id, throttle) -> {
                JsonObject due = throttle.due(now);
                DeviceRecord record = registry.record(id);
                if (due != null && record != null) publish(id, Outbound.state(topics().device(record.slug()), due));
            });
        }
        ConnectionState.Status connection = mqtt().state().status();
        if (connection != viewedConnection) {
            viewedConnection = connection;
            staleViews.addAll(attached.keySet());
        }
        sendViews();
    }

    /**
     * {@code SERVER_STOPPING}: nothing is published or carried over wires any more; removals only update the registry.
     * Messages in flight are dropped (M2 plan §3.6).
     */
    public void stop() {
        stopping = true;
        conversions.clear();
        attachQueue.clear();
        ownerLookups.clear();
        staleViews.clear();
        staleLinks.clear();
        staleGroups = false;
        bus.clear();
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
        if (stopping || registry.inChunk(level.dimension(), chunk).isEmpty()) return;
        pruneQueue.add(new ChunkRef(level.dimension(), chunk.pack()));
        runtime.workQueued();
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
            if (chunk != null && pruneIfStale(record, chunk)) pruned++;
        }
        return pruned;
    }

    /**
     * {@code /mineqtt devices remove}: forgets an unloaded device and clears its retained device topics. A retained level
     * on its Raw Link stays, as the registry does not know the link of an unloaded device. A block that still exists
     * registers again when its chunk loads.
     *
     * @return false for a loaded device, which is removed by breaking its block
     */
    public boolean remove(DeviceRecord record) {
        if (attached.containsKey(record.id())) return false;
        forget(record, false);
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
        // The links lists of the devices it is wired to name it.
        linksChanged(registry.wiresOf(record.id()));
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
        if (blockEntity != null) pushStatus(record, blockEntity);
    }

    /**
     * {@code /mineqtt devices expose} (M2 plan D6), loaded or not: the device's own Home Assistant exposure, null to
     * follow {@code ha.expose}. Its discovery is published, or cleared when it is hidden now, and open screens show the
     * new status. The registry keeps it for an unloaded device; the block takes it at its next attach. Under OPT_IN the
     * device's group is discovered for its exposed members, so it follows too.
     */
    public void setExpose(DeviceRecord record, @Nullable Boolean expose) {
        record.setExpose(expose);
        registry.markDirty();
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity != null) {
            requireHost(blockEntity).deviceLink().setExpose(expose);
            blockEntity.setChanged();
        }
        if (stopping) return;
        runtime.discovery().refresh(record);
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        if (group != null) runtime.discovery().refresh(group);
        if (blockEntity != null) pushStatus(record, blockEntity);
    }

    /**
     * {@code DeviceConfigC2S}: a player changes a setting of the device whose screen they have open, or commands one of
     * its writable fields. A refusal is told to them, in chat and on the screen; what changed reaches every open screen
     * of the device with its next view.
     */
    public static void configure(ServerPlayer player, BlockEntity blockEntity, String key, Tag value) {
        fromScreen(player, blockEntity, key, (devices, record, editor) ->
                devices.configure(record, key, NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, value), editor));
    }

    /** {@code DeviceActionC2S}: a player performs an action of the device whose screen they have open. */
    public static void action(ServerPlayer player, BlockEntity blockEntity, String key, String value) {
        fromScreen(player, blockEntity, key, (devices, record, editor) -> devices.action(record, key, value, editor));
    }

    /** {@code DeviceClaimC2S}: a player claims the device whose screen they have open ({@link #claim(DeviceRecord, Editor)}). */
    public static void claim(ServerPlayer player, BlockEntity blockEntity) {
        fromScreen(player, blockEntity, CLAIM_KEY, (devices, record, editor) -> devices.claim(record, editor));
    }

    /**
     * {@code DeviceRetryC2S}: a player retries the faulted device whose screen they have open, as
     * {@code /mineqtt devices retry} does ({@link #retry(GlobalPos)}). A faulted device is not attached, so this is the one
     * change from a screen that does not need a running device. Queued, the screen hears nothing until the device
     * attaches or faults again, which sends it the new view; anything else is refused on the screen. A device a screen
     * retried within the last second is not queued again: a held button or a flood of payloads attaches a failing device,
     * with its log line and offline publish, at most once a second.
     */
    public static void retry(ServerPlayer player, BlockEntity blockEntity) {
        DeviceRuntime devices = of(player.level().getServer());
        GlobalPos pos = GlobalPos.of(blockEntity.getLevel().dimension(), blockEntity.getBlockPos());
        RetryOutcome outcome = devices == null ? RetryOutcome.NOT_FAULTED : devices.screenRetry(pos);
        // The screen shows the reason in its status row; the chat line names the device too.
        String refusal = switch (outcome) {
            case QUEUED -> null;
            case NOT_FAULTED -> "Not faulted";
            case NOT_LOADED -> "Its chunk is not loaded; it is retried when the chunk loads";
            case GONE -> "No device is left here";
        };
        if (refusal == null) return;
        String slug = requireHost(blockEntity).deviceLink().slug();
        player.sendSystemMessage(Component.translatable("mineqtt.device.refused", slug.isEmpty() ? refusal : slug + ": " + refusal));
        MineqttNetworking.sendDeviceView(player, view(player, blockEntity));
        MineqttNetworking.sendDeviceRefused(player, blockEntity.getBlockPos(), RETRY_KEY, refusal);
    }

    /**
     * {@code limits.maxDevicesPerPlayer} at placement (M2-26): whether {@code player} may place another device. Operators
     * may always; anyone else may while they own fewer devices than the limit ({@link #ownedDevices}), or when the limit is
     * 0. A refused player hears why in the action bar. Without a running MineQTT on the player's server nothing is
     * counted, so nothing is refused. A claim is checked likewise ({@link #claim(DeviceRecord, Editor)}).
     */
    public static boolean mayPlaceDevice(ServerPlayer player) {
        int limit = MineQTTConfig.limitsMaxDevicesPerPlayer;
        DeviceRuntime devices = of(player.level().getServer());
        if (devices == null || limit <= 0) return true;
        int owned = devices.ownedDevices(player.getUUID());
        if (!deviceLimitReached(owned, limit, AclContext.isOp(player.permissions()))) return true;
        Component refusal = limit == 1 ? Component.translatable("mineqtt.device.limit.one", owned)
                : Component.translatable("mineqtt.device.limit", limit, owned);
        player.sendSystemMessage(refusal, true);
        return false;
    }

    /**
     * Whether a player who owns {@code owned} devices may place no other one: the limit is set (above 0), they own as many
     * or more (an operator may have lowered it), and they are no operator.
     */
    public static boolean deviceLimitReached(int owned, int limit, boolean op) {
        return !op && limit > 0 && owned >= limit;
    }

    /**
     * The devices a player owns: the registered ones, loaded or not ({@link DeviceRegistry#ownedBy}), and the ones they
     * placed that attach on the next tick, so two placements within one tick count both.
     */
    public int ownedDevices(UUID owner) {
        return registry.ownedBy(owner) + attachQueue.count(blockEntity -> {
            DeviceLink link = requireHost(blockEntity).deviceLink();
            return !blockEntity.isRemoved() && link.placedByPlayer() && owner.equals(link.owner());
        });
    }

    /**
     * {@code DeviceEjectC2S}: a player who may configure the device takes the items its old configuration slots held
     * (M2-12) into their inventory; what does not fit drops at their feet. Like a retry it needs no attached device, as
     * the items are the block's. A refusal is told in chat and on the screen.
     */
    public static void eject(ServerPlayer player, BlockEntity blockEntity) {
        if (!(blockEntity instanceof DeviceBlockEntity device)) return;
        String refusal = ejectRefusal(device.deviceLink());
        if (refusal == null) {
            List<ItemStack> items = device.ejectLegacyItems();
            int count = items.stream().mapToInt(ItemStack::getCount).sum();
            items.forEach(stack -> player.getInventory().placeItemBackInInventory(stack));
            player.sendSystemMessage(Component.translatable("mineqtt.device.eject.done", count), true);
            return;
        }
        String slug = device.deviceLink().slug();
        player.sendSystemMessage(Component.translatable("mineqtt.device.refused", slug.isEmpty() ? refusal : slug + ": " + refusal));
        MineqttNetworking.sendDeviceView(player, view(player, blockEntity));
        MineqttNetworking.sendDeviceRefused(player, blockEntity.getBlockPos(), EJECT_KEY, refusal);
    }

    /**
     * {@code /mineqtt devices eject}: the legacy items of a loaded device drop where it stands, as if it was broken.
     */
    public Configured eject(DeviceRecord record) {
        if (!(attached.get(record.id()) instanceof DeviceBlockEntity device)) {
            return Configured.refused(record.slug() + " is not loaded; its items are kept in its block");
        }
        String refusal = ejectRefusal(device.deviceLink());
        if (refusal != null) return Configured.refused(record.slug() + ": " + refusal);
        List<ItemStack> items = device.ejectLegacyItems();
        BlockPos pos = device.getBlockPos();
        items.forEach(stack -> Containers.dropItemStack(device.getLevel(), pos.getX(), pos.getY(), pos.getZ(), stack));
        return Configured.applied(record.slug() + " dropped " + items.stream().mapToInt(ItemStack::getCount).sum() + " items at "
                + pos.toShortString());
    }

    /** How many items the old configuration slots of a loaded device still hold (M2-12); 0 while it is not loaded. */
    public int legacyItems(UUID id) {
        BlockEntity blockEntity = attached.get(id);
        return blockEntity == null ? 0 : requireHost(blockEntity).deviceLink().legacyItems().stream().mapToInt(item -> item.stack().getCount()).sum();
    }

    /**
     * Why a device's legacy items cannot be ejected now, or null when they can: it holds none, or its v1 data waits for
     * adoption, whose slots are its topic until then.
     */
    public static @Nullable String ejectRefusal(DeviceLink link) {
        if (link.awaitingAdoption()) return "Its v1 slots become its Raw Link when it attaches; eject the items after that";
        if (link.legacyItems().isEmpty()) return "It holds no items";
        return null;
    }

    /**
     * A renamed paper used on a device with a Raw Link (M2 plan §4.2 M2-10, design §7.4): its name becomes the link's
     * channel, as the Advanced tab's channel field would set it, behind the same CONFIGURE gate; a device with a private
     * channel gets a private one. The paper is not consumed. The player hears in the action bar what came of it.
     */
    public static void paperChannel(ServerPlayer player, BlockEntity blockEntity, String channel) {
        LinkSetting<String> setting = requireHost(blockEntity).isPrivateMode() ? LinkSetting.RAW_PRIVATE : LinkSetting.RAW_CHANNEL;
        Configured result = byPlayer(player, blockEntity, (devices, record, editor) ->
                devices.configure(record, setting.key(), new JsonPrimitive(channel), editor));
        String answer = switch (result.result()) {
            // A Raw Link edit is never queued; the detail would say so.
            case APPLIED, QUEUED -> "mineqtt.raw_link.paper.applied";
            case UNCHANGED -> "mineqtt.raw_link.paper.unchanged";
            case REFUSED -> "mineqtt.device.refused";
        };
        player.sendSystemMessage(Component.translatable(answer, result.detail()), true);
    }

    /**
     * A plain right-click on a device with a natural use (design §7.4): the type's {@link DeviceType#use() use} action,
     * as the Controls tab would send it, so it needs USE and reaches the device through {@link #command}. Only a
     * refusal is answered, in the action bar; the device shows what it did.
     */
    public static void use(ServerPlayer player, BlockEntity blockEntity) {
        Action action = requireHost(blockEntity).deviceType().use().orElseThrow();
        Configured result = byPlayer(player, blockEntity, (devices, record, editor) ->
                devices.action(record, action.key(), action.handValue().orElseThrow(), editor));
        if (result.result() == Configured.Result.REFUSED) {
            player.sendSystemMessage(Component.translatable("mineqtt.device.refused", result.detail()), true);
        }
    }

    /**
     * Whether a device that a player uses by hand without {@link #use} (the Wall Switch's buttons) runs on this server's
     * runtime; one that is not attached, faulted included, answers the player in the action bar as {@link #use} does.
     */
    public static boolean running(ServerPlayer player, BlockEntity blockEntity) {
        Configured result = byPlayer(player, blockEntity, (devices, record, editor) -> Configured.applied(record.slug()));
        if (result.result() != Configured.Result.REFUSED) return true;
        player.sendSystemMessage(Component.translatable("mineqtt.device.refused", result.detail()), true);
        return false;
    }

    /**
     * A name tag used on a device (M2 plan §4.2 M2-13, design §7.4): its name becomes the device's, as the General
     * tab's name field would set it, behind the same CONFIGURE gate, with the topic move of a rename (§3.1). The name
     * tag is not used up. The player hears in the action bar the new name and state topic, or why not.
     */
    public static void nameTag(ServerPlayer player, BlockEntity blockEntity, String name) {
        Configured result = byPlayer(player, blockEntity, (devices, record, editor) ->
                devices.configure(record, LinkSetting.NAME.key(), new JsonPrimitive(name), editor));
        DeviceLink link = requireHost(blockEntity).deviceLink();
        DeviceRuntime devices = of(player.level().getServer());
        Component answer = switch (result.result()) {
            // A rename is never queued; the detail would say so.
            case APPLIED, QUEUED -> Component.translatable("mineqtt.device.name_tag.applied", link.name(), devices.topics().device(link.slug()));
            case UNCHANGED -> Component.translatable("mineqtt.device.name_tag.unchanged", link.name());
            case REFUSED -> Component.translatable("mineqtt.device.refused", result.detail());
        };
        player.sendSystemMessage(answer, true);
    }

    // A change from a device's screen: a refusal is told in chat, and on the screen where the player made the change.
    // The screen gets the device's view first, with every change applied before the refused one (the end of the tick
    // would bring it after the refusal), so the refusal answers that view and a later view with another Raw Link ends it.
    private static void fromScreen(ServerPlayer player, BlockEntity blockEntity, String key, ScreenChange change) {
        Configured result = byPlayer(player, blockEntity, change);
        if (result.result() != Configured.Result.REFUSED) return;
        player.sendSystemMessage(Component.translatable("mineqtt.device.refused", result.detail()));
        MineqttNetworking.sendDeviceView(player, view(player, blockEntity));
        MineqttNetworking.sendDeviceRefused(player, blockEntity.getBlockPos(), key, result.detail());
    }

    // A player's change of a device attached to this server's runtime.
    private static Configured byPlayer(ServerPlayer player, BlockEntity blockEntity, ScreenChange change) {
        DeviceRuntime devices = of(player.level().getServer());
        UUID id = requireHost(blockEntity).deviceLink().id();
        DeviceRecord record = devices == null || id == null || devices.attached.get(id) != blockEntity ? null : devices.registry.record(id);
        return record == null ? Configured.refused("the device is not running") : change.apply(devices, record, Editor.of(player, blockEntity));
    }

    @FunctionalInterface
    private interface ScreenChange {
        Configured apply(DeviceRuntime devices, DeviceRecord record, Editor editor);
    }

    /**
     * Changes one setting of a device (M2-6), validated by {@link DeviceConfig#parse}: the screen's
     * {@code DeviceConfigC2S} and {@code /mineqtt devices setting} and {@code raw}. A setting of the type, like a command
     * to one of its writable fields (M2-9), reaches the device as a player's command through {@link #command}, so an
     * unloaded actuator queues it. Name and exposure change
     * in the registry, loaded or not. The access mode and the Raw Link live in the block, so they change only while it
     * is loaded; a Raw Link edit ends in {@link #rawEdited}, so a retaining output leaves its old topic off or clears it.
     */
    public Configured configure(DeviceRecord record, String key, JsonElement value, Editor editor) {
        DeviceType type = DeviceTypes.get(record.type());
        if (type == null) return Configured.refused(record.slug() + " has a type unknown to this MineQTT");
        DataResult<DeviceConfig.Change> parsed = DeviceConfig.parse(type, key, value, editor.grants());
        Optional<DataResult.Error<DeviceConfig.Change>> error = parsed.error();
        if (error.isPresent()) return Configured.refused(record.slug() + ": " + error.get().message());
        Configured result = switch (parsed.getOrThrow()) {
            case DeviceConfig.Command setting -> set(record, setting.command());
            case DeviceConfig.Rename rename -> {
                if (rename.name().equals(record.name())) yield Configured.unchanged(record.slug() + " already is called " + record.name());
                String oldSlug = record.slug();
                rename(record, rename.name());
                yield Configured.applied("Renamed " + oldSlug + " to " + record.name() + " (" + topics().device(record.slug()) + ")");
            }
            case DeviceConfig.Expose expose -> {
                String setting = expose.expose() == null ? "follows ha.expose" : expose.expose() ? "is exposed" : "is hidden";
                if (Objects.equals(record.expose(), expose.expose())) yield Configured.unchanged(record.slug() + " already " + setting);
                setExpose(record, expose.expose());
                yield Configured.applied(record.slug() + " " + setting + "; Home Assistant "
                        + (runtime.discovery().exposed(record) ? "discovers it" : "does not discover it"));
            }
            case DeviceConfig.Access access -> setAccess(record, access.mode());
            case DeviceConfig.RawChannel change -> editRaw(record, change, editor);
            case DeviceConfig.RawTopic change -> editRaw(record, change, editor);
            case DeviceConfig.RawRetain change -> editRaw(record, change, editor);
            case DeviceConfig.RawQos change -> editRaw(record, change, editor);
            case DeviceConfig.RawPayload change -> editRaw(record, change, editor);
        };
        viewChanged(record.id());
        return result;
    }

    /**
     * Performs an action the device's type declares (M2 plan §3.8 #1): the screen's {@code DeviceActionC2S} and
     * {@code /mineqtt devices action}. It reaches the device as a player's command ({@link #set}).
     */
    public Configured action(DeviceRecord record, String key, String value, Editor editor) {
        DeviceType type = DeviceTypes.get(record.type());
        if (type == null) return Configured.refused(record.slug() + " has a type unknown to this MineQTT");
        DataResult<JsonObject> command = DeviceConfig.action(type, key, value, editor.grants());
        Optional<DataResult.Error<JsonObject>> error = command.error();
        if (error.isPresent()) return Configured.refused(record.slug() + ": " + error.get().message());
        Configured result = set(record, command.getOrThrow());
        viewChanged(record.id());
        return result;
    }

    /**
     * A player's command to a device (M2-5, M2-6): {@code /mineqtt devices set}, settings and actions. It goes through
     * {@link #command}, so a loaded device applies it, an unloaded actuator queues it and an unloaded sensor ignores it,
     * as for Home Assistant; a faulted device counts as unloaded. The result says which.
     */
    public Configured set(DeviceRecord record, JsonObject command) {
        Delivery delivery = command(record.id(), command, CommandSource.PLAYER, null);
        String shown = shown(command.toString());
        // A faulted device is offline although its chunk may be loaded, so the reply names the fault and the retry instead.
        String offline = fault(record.pos()).map(fault -> " is faulted (" + fault.text() + "; /mineqtt devices retry " + record.slug() + ")")
                .orElse(" is not loaded");
        return switch (delivery.route()) {
            case APPLY -> switch (delivery.result().outcome()) {
                case APPLIED -> Configured.applied(record.slug() + " applied " + shown);
                case UNCHANGED -> Configured.unchanged(record.slug() + " already is as " + shown + " asks");
                case REJECTED -> Configured.refused(record.slug() + " rejected " + shown + ": " + delivery.result().detail());
            };
            // The queue holds the difference to the last state, so a command that changes nothing leaves it empty.
            case QUEUE -> record.pending() == null ? Configured.unchanged(record.slug() + offline + "; it already is as " + shown + " asks")
                    : new Configured(Configured.Result.QUEUED, record.slug() + offline + "; queued for when it attaches: " + shown(record.pending().toString()));
            case DROP -> Configured.refused(record.slug() + (DeviceTypes.get(record.type()) == null
                    ? " has a type unknown to this MineQTT" : offline + ", and a sensor takes no commands while it is not attached"));
            case FAN_OUT -> throw new IllegalStateException("A device command fanned out: " + record.slug());
        };
    }

    /**
     * A player claims a device that has no owner (M2-11): the screen's {@code DeviceClaimC2S} and
     * {@code /mineqtt devices claim}. They need CONFIGURE on it ({@link DeviceAccess#mayClaim}), and a v1 owner name
     * still to resolve counts as an owner. A claim counts against {@code limits.maxDevicesPerPlayer} as a placement does
     * ({@link #deviceLimitReached}), so a player at the limit may not claim either. They own it from then on: the record,
     * the Raw Link and the state echo follow ({@link #ownerChanged}), and the owner may set its access mode.
     */
    public Configured claim(DeviceRecord record, Editor editor) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity == null) return Configured.refused(record.slug() + " is not loaded; its owner is kept in its block");
        ServerPlayer player = editor.player();
        if (player == null) return Configured.refused("Only a player can claim a device");
        DeviceLink link = requireHost(blockEntity).deviceLink();
        DeviceView.Owner owner = DeviceAccess.owner(link.owner(), link.ownerName(), runtime.server().usesAuthentication());
        if (owner != DeviceView.Owner.NOBODY) return Configured.refused(record.slug() + " already belongs to " + link.ownerName());
        if (!DeviceAccess.mayClaim(editor.grants(), owner)) return Configured.refused(record.slug() + ": " + DeviceConfig.refusal(LinkSetting.Gate.CONFIGURE));
        int limit = MineQTTConfig.limitsMaxDevicesPerPlayer;
        int owned = ownedDevices(player.getUUID());
        if (deviceLimitReached(owned, limit, editor.subject().op())) {
            return Configured.refused(record.slug() + ": this server allows " + (limit == 1 ? "one device" : limit + " devices")
                    + " per player, and you own " + owned);
        }
        String name = player.getGameProfile().name();
        if (!link.claim(blockEntity, player.getUUID(), name)) return Configured.refused(record.slug() + " already has an owner");
        pushStatus(record, blockEntity);
        viewChanged(record.id());
        return Configured.applied(record.slug() + " belongs to " + name + " now");
    }

    // The access mode lives in the block's link and is copied into the record, which access checks of an unloaded device read.
    // The device's open screens follow it.
    private Configured setAccess(DeviceRecord record, AccessMode mode) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity == null) return Configured.refused(record.slug() + " is not loaded; its access mode is kept in its block");
        DeviceLink link = requireHost(blockEntity).deviceLink();
        if (link.access() == mode) return Configured.unchanged(record.slug() + " already is " + mode.getSerializedName());
        link.setAccess(mode);
        blockEntity.setChanged();
        record.setAccess(mode);
        registry.markDirty();
        pushStatus(record, blockEntity);
        // Who may change its group, which needs CONFIGURE on every member, follows who may configure it.
        groupsChanged();
        if (blockEntity instanceof DeviceBlockEntity device) device.accessChanged();
        return Configured.applied(record.slug() + " is " + mode.getSerializedName() + " now");
    }

    /**
     * Edits the Raw Link of a loaded device. A channel is resolved for the owner as v1's item and paper topics were
     * ({@link RawTopics#resolve}); a private channel into the owner's private subtree, which needs an owner, else it is refused until a v1 owner name
     * resolves; a typed topic
     * is used as typed when the ACL allows it for the editor and the owner ({@link DeviceConfig#typedTopicProblem}), and
     * refused otherwise. A channel sets the device's private mode ({@link DeviceLink#privateMode()}); a typed topic or a
     * removed link clears it. Retain, QoS and the custom payloads change on the link as it is. The editor hears what came
     * of it, and a link taken away turns its output off ({@link DeviceHost#rawRemoved}).
     */
    private Configured editRaw(DeviceRecord record, DeviceConfig.Change change, Editor editor) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity == null) return Configured.refused(record.slug() + " is not loaded; its Raw Link is kept in its block");
        DeviceHost host = requireHost(blockEntity);
        DeviceLink link = host.deviceLink();
        RawLinkSpec spec = host.deviceType().rawLink().orElseThrow();
        RawLink before = link.raw();
        boolean needsLink = !(change instanceof DeviceConfig.RawChannel) && !(change instanceof DeviceConfig.RawTopic);
        if (before == null && needsLink) return Configured.refused(record.slug() + " has no Raw Link; give it a channel or a topic first");
        if (change instanceof DeviceConfig.RawTopic typed && !typed.topic().isEmpty()) {
            Optional<String> problem = DeviceConfig.typedTopicProblem(runtime.acl(), editor, link.owner(), link.ownerName(),
                    runtime.server()::getProfilePermissions, typed.topic());
            if (problem.isPresent()) return Configured.refused(record.slug() + " may not use the topic " + typed.topic() + ": " + problem.get());
        }
        // A v1 owner name still to resolve is an owner: the channel is stored refused until it resolves (ownerChanged).
        if (change instanceof DeviceConfig.RawChannel channel && channel.privateMode() && !channel.channel().isEmpty()
                && link.owner() == null && link.ownerName().isEmpty()) {
            return Configured.refused(record.slug() + " has no owner, whose private channels it could use; claim it first");
        }
        // A channel says whether it is private; a typed topic or a removed link is not.
        boolean privateAfter = switch (change) {
            case DeviceConfig.RawChannel channel -> channel.privateMode() && !channel.channel().isEmpty();
            case DeviceConfig.RawTopic typed -> false;
            default -> link.privateMode();
        };
        RawLink after = switch (change) {
            case DeviceConfig.RawChannel channel -> channel.channel().isEmpty() ? null : resolvedChannel(host, before, spec, channel.channel(), privateAfter);
            case DeviceConfig.RawTopic typed -> typed.topic().isEmpty() ? null : RawLink.typed(typed.topic(), spec.direction(), before);
            case DeviceConfig.RawRetain retain -> before.withRetain(retain.retain());
            case DeviceConfig.RawQos qos -> before.withQos(qos.qos());
            case DeviceConfig.RawPayload payload -> before.withCustomPayloads(payload.on()
                    ? new ValueCodecs.CustomPayloads(payload.payload(), before.off()) : new ValueCodecs.CustomPayloads(before.on(), payload.payload()));
            default -> throw new IllegalArgumentException("Not a Raw Link change: " + change);
        };
        if (Objects.equals(before, after) && privateAfter == link.privateMode()) {
            return Configured.unchanged(record.slug() + "'s Raw Link already is " + rawStatus(record, link));
        }
        // The editor hears about a refusal or a move into the shared namespace.
        if (editor.player() != null && blockEntity instanceof DeviceBlockEntity device) device.setLastEditor(editor.player());
        link.setRaw(after);
        link.setPrivateMode(privateAfter);
        blockEntity.setChanged();
        if (change instanceof DeviceConfig.RawPayload) {
            rawPayloadsChanged(host, before);
            settle(record, host, true);
        } else {
            rawEdited(host, before);
        }
        if (after == null) host.rawRemoved();
        return Configured.applied(record.slug() + "'s Raw Link: " + rawStatus(record, link));
    }

    // A channel resolved for the device's owner now, in their private subtree or not; the payloads, QoS, retain override
    // and state echo of the old link stay.
    private RawLink resolvedChannel(DeviceHost host, @Nullable RawLink before, RawLinkSpec spec, String channel, boolean privateMode) {
        DeviceLink link = host.deviceLink();
        RawTopics.Resolution resolution = RawTopics.resolve(channel, privateMode, runtime.acl(), link.owner(), registry.ownerLiterals(link.owner()));
        return before == null ? resolution.link(spec.direction(), false) : before.resolvedTo(resolution);
    }

    /** A command or state as chat replies show it: long ones are cut, the broker has the full JSON. */
    public static String shown(String json) {
        return json.length() <= MAX_SHOWN_JSON ? json : json.substring(0, MAX_SHOWN_JSON) + "...";
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

    /** A new, empty group; its slug comes from the name. */
    public DeviceGroup createGroup(String name) {
        DeviceGroup group = registry.createGroup(UUID.randomUUID(), ValueCodecs.sanitizeText(name).strip());
        groupsChanged();
        return group;
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
        // The wires into the group are its links now.
        staleLinks.add(record.id());
        groupsChanged();
        // A legacy group whose listed positions were all ambiguous has a member type, and so follows its topic, from now on.
        if (group.legacyTopic() != null && !groupRawSubscriptions.containsKey(group.id())) openGroupRaw(group);
        if (group.state().isEmpty()) {
            group.setState(type.writableState(effectiveState(record, type)));
        } else {
            command(record.id(), group.state(), CommandSource.GROUP, null);
        }
        publishGroupState(group);
        // A legacy group follows its v1 topic for the member from now on.
        refreshRaw(record);
        // A member is hidden unless the group exposes its members; the group has a member type from now on.
        runtime.discovery().refresh(record);
        runtime.discovery().refresh(group);
        // Its screens show the group and whether Home Assistant discovers it.
        viewChanged(record.id());
        return Optional.empty();
    }

    public void leave(DeviceRecord record) {
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        registry.leaveGroup(record);
        if (!stopping) staleLinks.add(record.id());
        groupsChanged();
        refreshRaw(record);
        runtime.discovery().refresh(record);
        // Under OPT_IN the group may have lost the exposed member it was discovered for.
        if (group != null) runtime.discovery().refresh(group);
        viewChanged(record.id());
    }

    /** Deletes a group and its wires and clears its retained state and discovery; the members stay as devices of their own. */
    public void deleteGroup(DeviceGroup group) {
        List<DeviceRecord> members = group.members().stream().map(registry::record).filter(Objects::nonNull).toList();
        List<Wire> wires = registry.wiresOf(group.id());
        linksChanged(wires);
        registry.removeGroup(group);
        forgetWires(group.id(), wires);
        closeGroupRaw(group.id());
        groupsChanged();
        // Members of a legacy group follow the v1 topic each on their own again.
        members.forEach(this::refreshRaw);
        members.forEach(member -> viewChanged(member.id()));
        if (stopping) return;
        mqtt().publish(Outbound.clear(topics().group(group.slug())));
        runtime.discovery().removed(group);
        members.forEach(runtime.discovery()::refresh);
    }

    /**
     * Renames a group (M2 plan §4.2 M2-17): the slug follows the name, so a group whose slug changes clears its old
     * retained state, publishes its discovery with the new name and topics at once (its unique_ids do not change), then
     * its state on the new topic, as a renamed device does (§3.1). The links lists and the screens of its members show
     * the new name.
     */
    public void renameGroup(DeviceGroup group, String newName) {
        String sanitized = ValueCodecs.sanitizeText(newName).strip();
        String name = sanitized.isEmpty() ? group.name() : sanitized;
        String oldSlug = group.slug();
        String slug = registry.uniqueGroupSlug(name, group);
        registry.renameGroup(group, name, slug);
        // The links lists of the devices its wires reach name it, and its members' General tabs show it.
        linksChanged(registry.wiresOf(group.id()));
        group.members().forEach(this::viewChanged);
        groupsChanged();
        if (stopping) return;
        boolean moved = !oldSlug.equals(slug);
        if (moved) mqtt().publish(Outbound.clear(topics().group(oldSlug)));
        runtime.discovery().publishNow(group);
        // A group without a state yet has none to retain, as at a republish.
        if (moved && !group.state().isEmpty()) publishGroupState(group);
    }

    /**
     * Shows or hides the members of a group to Home Assistant (design D1, §12 Q9): shown, each member is discovered as a
     * device of its own beside the group, as its own exposure allows; hidden, only the group is. Their screens follow.
     */
    public void setExposeMembers(DeviceGroup group, boolean exposeMembers) {
        group.setExposeMembers(exposeMembers);
        registry.markDirty();
        groupsChanged();
        for (UUID member : group.members()) {
            DeviceRecord record = registry.record(member);
            if (record == null) continue;
            if (!stopping) runtime.discovery().refresh(record);
            viewChanged(member);
        }
    }

    // Every open device screen gets its group page anew at the end of the tick.
    private void groupsChanged() {
        if (!stopping) staleGroups = true;
    }

    /**
     * {@code DeviceGroupC2S}: a player changes the group of the device whose screen they have open, from its General tab's
     * group page ({@link #changeGroup}). A refusal is told to them, in chat and on the page; what changed reaches every
     * open screen's group page at the end of the tick.
     */
    public static void group(ServerPlayer player, BlockEntity blockEntity, GroupRules.Change change) {
        fromScreen(player, blockEntity, GROUP_KEY, (devices, record, editor) ->
                devices.changeGroup(record, change, devices.configurer(player), editor.grants().expose()));
    }

    /**
     * Changes a device's group for a player (M2 plan §4.2 M2-17): the General tab's group page and the Configurator's
     * Group mode. The player needs CONFIGURE on the device; joining a group, renaming the device's group and showing its
     * members need CONFIGURE on every member as well ({@link GroupRules}), and showing them the right to expose devices.
     * A group that replaces a v1 topic takes a device only when its owner may use that topic ({@link #topicRefusal}). A
     * group the device leaves, by leaving it or by joining or creating another, goes when it has no devices left
     * ({@link GroupRules#dropsWhenEmptied}), so no player keeps groups without devices.
     *
     * @param configures whether the player may configure the device with this id ({@link #configurer})
     * @param mayExpose  whether the player may expose devices to Home Assistant ({@code AclContext.mayExpose})
     */
    public Configured changeGroup(DeviceRecord record, GroupRules.Change change, Predicate<UUID> configures, boolean mayExpose) {
        DeviceType type = DeviceTypes.get(record.type());
        if (type == null) return Configured.refused(record.slug() + " has a type unknown to this MineQTT");
        if (!configures.test(record.id())) return Configured.refused(DeviceConfig.refusal(LinkSetting.Gate.CONFIGURE));
        DeviceGroup own = record.group() == null ? null : registry.group(record.group());
        return switch (change) {
            case GroupRules.Join join -> {
                DeviceGroup group = registry.group(join.group());
                if (group == null) yield Configured.refused("That group is gone");
                Optional<String> problem = GroupRules.joinProblem(group, record.type(), type.groupable(), record.id(), configures, topicRefusal(record));
                if (problem.isPresent()) yield Configured.refused(record.name() + " cannot join: " + problem.get());
                boolean inherits = !group.state().isEmpty();
                join(group, record);
                yield Configured.applied(record.name() + " joined " + group.name() + (inherits ? " and took over its state" : "") + dropIfEmptied(own));
            }
            case GroupRules.Leave _ -> {
                if (own == null) yield Configured.unchanged(record.name() + " is in no group");
                leave(record);
                yield Configured.applied(record.name() + " left " + own.name() + dropIfEmptied(own));
            }
            case GroupRules.Create create -> {
                if (!type.groupable()) yield Configured.refused(record.type() + " devices cannot be grouped");
                DataResult<String> name = GroupRules.name(create.name());
                if (name.error().isPresent()) yield Configured.refused(name.error().get().message());
                DeviceGroup group = createGroup(name.getOrThrow());
                join(group, record);
                yield Configured.applied("Created group " + group.name() + " (" + topics().group(group.slug()) + ") with " + record.name()
                        + dropIfEmptied(own));
            }
            case GroupRules.Rename rename -> {
                if (own == null) yield Configured.refused(record.name() + " is in no group");
                if (!GroupRules.manages(own, configures)) yield Configured.refused("You may not configure every device of group " + own.name());
                DataResult<String> name = GroupRules.name(rename.name());
                if (name.error().isPresent()) yield Configured.refused(name.error().get().message());
                if (name.getOrThrow().equals(own.name())) yield Configured.unchanged("The group already is called " + own.name());
                renameGroup(own, name.getOrThrow());
                yield Configured.applied("Renamed the group to " + own.name() + " (" + topics().group(own.slug()) + ")");
            }
            case GroupRules.ExposeMembers expose -> {
                if (own == null) yield Configured.refused(record.name() + " is in no group");
                if (!mayExpose) yield Configured.refused(DeviceConfig.refusal(LinkSetting.Gate.EXPOSE));
                if (!GroupRules.manages(own, configures)) yield Configured.refused("You may not configure every device of group " + own.name());
                String shown = expose.expose() ? "shows its members to Home Assistant" : "hides its members from Home Assistant";
                if (own.exposeMembers() == expose.expose()) yield Configured.unchanged("Group " + own.name() + " already " + shown);
                setExposeMembers(own, expose.expose());
                yield Configured.applied("Group " + own.name() + " " + shown);
            }
        };
    }

    // A group a player's change left without devices goes, unless it replaces a v1 topic; the sentence says so.
    private String dropIfEmptied(@Nullable DeviceGroup group) {
        if (group == null || registry.group(group.id()) == null || !GroupRules.dropsWhenEmptied(group)) return "";
        deleteGroup(group);
        return "; group " + group.name() + " had no devices left and is gone";
    }

    /**
     * Why the owner of a device may not use a topic, judged as their own Raw Link on it would be ({@link RawTopics#problem}):
     * the hard denies, the raw allowlist, their private subtree, and a v1 topic grandfathered for them (one of their
     * literals, or their device's own Raw Link on it). A legacy group follows its v1 topic under the legacy rules alone,
     * so a player joins a device to one only when its owner passes this. A device not loaded here passes nothing.
     */
    private Function<String, Optional<String>> topicRefusal(DeviceRecord record) {
        return topic -> {
            BlockEntity blockEntity = attached.get(record.id());
            if (blockEntity == null) return Optional.of(record.name() + " is not loaded");
            DeviceLink link = requireHost(blockEntity).deviceLink();
            UUID owner = link.owner();
            RawLink raw = link.raw();
            TopicAcl.Grandfather grandfather = raw != null && raw.grandfathered() != null && raw.topic().equals(topic) ? raw.grandfathered()
                    : owner != null && registry.ownerLiterals(owner).contains(topic) ? new TopicAcl.Grandfather(topic, owner) : null;
            return refusal(runtime.acl().checkPublish(AclContext.owner(runtime.server(), owner, link.ownerName()), topic, grandfather));
        };
    }

    /**
     * The group page of a device as one player sees it ({@link DeviceGroups}): its group, whether they may rename it,
     * and the groups it may join.
     *
     * @param configures whether the player may configure the device with this id ({@link #configurer})
     */
    public DeviceGroups groups(DeviceRecord record, Predicate<UUID> configures) {
        DeviceType type = DeviceTypes.get(record.type());
        boolean groupable = type != null && type.groupable();
        DeviceGroup own = record.group() == null ? null : registry.group(record.group());
        Optional<DeviceGroups.Own> shown = Optional.ofNullable(own).map(group -> DeviceGroups.Own.of(group.id(), group.name(), topics().group(group.slug()),
                group.members().size(), group.exposeMembers(), GroupRules.manages(group, configures)));
        List<DeviceGroups.Joinable> joinable = !groupable ? List.of() : GroupRules.joinable(registry, record, configures, topicRefusal(record)).stream()
                .map(group -> DeviceGroups.Joinable.of(group.id(), group.name(), group.members().size())).toList();
        return DeviceGroups.of(record.pos().pos(), groupable, shown, joinable);
    }

    /**
     * Sends a player whose screen of a device just opened its group page, right after the open data; nothing to offer for
     * a device that does not run on this server.
     */
    public static void sendGroups(ServerPlayer player, BlockEntity blockEntity) {
        DeviceRuntime devices = of(player.level().getServer());
        UUID id = requireHost(blockEntity).deviceLink().id();
        DeviceRecord record = devices == null || id == null || devices.attached.get(id) != blockEntity ? null : devices.registry.record(id);
        MineqttNetworking.sendDeviceGroups(player, record == null ? DeviceGroups.none(blockEntity.getBlockPos())
                : devices.groups(record, devices.configurer(player)));
    }

    /**
     * What a paste changed on a device ({@link #paste}).
     *
     * @param refused why each refused change was, as the player is told
     */
    public record Pasted(int changed, int unchanged, List<String> refused) {
    }

    /**
     * The Configurator's Copy-Paste mode (M2 plan §4.2 M2-17): sends a device each change the clipboard holds, in its
     * order, as the editor would send it from the device's screen ({@link #configure}), so each is checked for them. A
     * setting the device has already is not sent, so a paste never refuses what it would not change (the exposure of a
     * player who may not expose devices, say). When the Raw Link's channel or topic is refused, its other settings are
     * not sent, as they would change the link the device has instead.
     *
     * @param current what the device has now, copied as the editor sees it
     */
    public Pasted paste(DeviceRecord record, DeviceClipboard clipboard, DeviceClipboard current, Editor editor) {
        if (!record.type().equals(clipboard.type())) return new Pasted(0, 0, List.of(record.name() + " is no " + clipboard.type() + " device"));
        int changed = 0;
        int unchanged = 0;
        List<String> refused = new ArrayList<>();
        boolean linkRefused = false;
        for (DeviceClipboard.Entry entry : clipboard.entries()) {
            if (linkRefused && entry.raw()) continue;
            if (current.entries().contains(entry)) {
                unchanged++;
                continue;
            }
            Configured result = configure(record, entry.key(), entry.value(), editor);
            switch (result.result()) {
                case APPLIED, QUEUED -> changed++;
                case UNCHANGED -> unchanged++;
                case REFUSED -> {
                    refused.add(result.detail());
                    linkRefused = entry.link();
                }
            }
        }
        return new Pasted(changed, unchanged, refused);
    }

    /**
     * Makes a wire (M2 plan D9): the Configurator's Link mode and its twin {@code /mineqtt wires add}. It is made when its
     * ends are ports of registered devices (or a group, as the target) that its mapping can join, its owner may configure
     * both ends, and the owner's wires stay within {@code limits.maxWiresPerPlayer} ({@link LinkRules#allowed}). A
     * {@link Mapping#FOLLOW FOLLOW} or {@link Mapping#INVERT INVERT} wire tells its target what the source holds now, so
     * the target follows from the start, unless the source is offline ({@link #seeds}); a {@link Mapping#PULSE_TOGGLE PULSE_TOGGLE} wire waits for the next edge, as a
     * toggle now would flip its target.
     *
     * @param configures whether the wire's owner may configure the device with this id ({@link #configurer}); not asked
     *                   for a wire of the console
     * @return why it was not made; empty when it was
     */
    public Optional<String> addWire(Wire wire, Predicate<UUID> configures) {
        Optional<String> problem = LinkRules.allowed(registry, wire, configures, MineQTTConfig.limitsMaxWiresPerPlayer);
        if (problem.isPresent()) return problem;
        registry.addWire(wire);
        linksChanged(List.of(wire));
        seed(wire);
        return Optional.empty();
    }

    // A new following wire's target takes the source's current value, through the command path as any wire's message.
    private void seed(Wire wire) {
        if (stopping || wire.mapping() == Mapping.PULSE_TOGGLE || !(wire.from() instanceof Endpoint.PortRef source)) return;
        DeviceRecord record = registry.record(source.node());
        DeviceType type = record == null ? null : DeviceTypes.get(record.type());
        Optional<Port> out = port(wire.from(), Port.Direction.OUT);
        Optional<Port> in = port(wire.to(), Port.Direction.IN);
        if (type == null || out.isEmpty() || in.isEmpty() || !seeds(type, attached.containsKey(record.id()), faults.containsKey(record.pos()))) return;
        JsonElement value = type.outputs(effectiveState(record, type)).get(source.port());
        if (value == null) return;
        wire.mapping().apply(value, null, out.get().kind(), in.get().kind(), in.get().toggles())
                .flatMap(mapped -> RegistryWiring.command(registry, wire.to(), mapped))
                .ifPresent(command -> command(command.target(), command.command(), CommandSource.BINDING, source.node()));
    }

    /**
     * Whether a new following wire's source has a value to seed its target with, a pure function: a loaded device has,
     * and so has an unloaded {@link UnloadedPolicy#QUEUE} device, in its record. An unloaded sensor counts as offline and
     * a faulted device as unavailable, so what they stored is stale; the wire carries their next real change instead.
     */
    static boolean seeds(DeviceType type, boolean attached, boolean faulted) {
        return attached || type.unloadedPolicy() == UnloadedPolicy.QUEUE && !faulted;
    }

    /**
     * Removes a wire ({@code /mineqtt wires remove}, the Configurator's unlink, a device's links list): what its target
     * took last and its loop report go with it.
     */
    public @Nullable Wire removeWire(UUID id) {
        Wire wire = registry.wire(id);
        if (wire == null) return null;
        linksChanged(List.of(wire));
        registry.removeWire(id);
        bus.forgetWire(id);
        return wire;
    }

    /**
     * Whether a player may configure a device, loaded or not ({@link DeviceAccess}), as {@link #addWire} asks of a
     * player's wire; a spectator may configure nothing.
     */
    public Predicate<UUID> configurer(ServerPlayer player) {
        return id -> {
            if (player.isSpectator()) return false;
            BlockEntity blockEntity = attached.get(id);
            if (blockEntity != null) return DeviceAccess.check(player, blockEntity, MineqttPermission.CONFIGURE);
            DeviceRecord record = registry.record(id);
            return record != null && DeviceAccess.check(player, record, MineqttPermission.CONFIGURE);
        };
    }

    // These wires' devices list them: their open screens get the new list at the end of the tick. Asked before a removal.
    private void linksChanged(Collection<Wire> wires) {
        if (stopping) return;
        wires.forEach(wire -> staleLinks.addAll(LinkRules.devices(registry, wire)));
    }

    /**
     * The links of a device as its screen lists them ({@link DeviceLinks}): the wires that start or end at it or end at
     * its group, in the order they were made, with the devices' and groups' names.
     */
    public DeviceLinks links(DeviceRecord record) {
        List<DeviceLinks.Row> rows = registry.wires().stream().filter(wire -> LinkRules.touches(registry, wire, record.id()))
                .map(wire -> row(wire, record.id())).toList();
        return DeviceLinks.of(record.pos().pos(), rows);
    }

    private DeviceLinks.Row row(Wire wire, UUID device) {
        boolean outgoing = device.equals(DeviceRegistry.node(wire.from()));
        Endpoint own = outgoing ? wire.from() : wire.to();
        String group = wire.to() instanceof Endpoint.GroupPortRef port
                ? Optional.ofNullable(registry.group(port.group())).map(DeviceGroup::name).orElse(port.group().toString()) : "";
        return DeviceLinks.Row.of(wire.id(), outgoing, RegistryWiring.key(own), label(outgoing ? wire.to() : wire.from()), group,
                wire.mapping(), wire.enabled());
    }

    /**
     * Sends a player whose screen of a device just opened its links, right after the open data; none for a device that
     * does not run on this server.
     */
    public static void sendLinks(ServerPlayer player, BlockEntity blockEntity) {
        DeviceRuntime devices = of(player.level().getServer());
        UUID id = requireHost(blockEntity).deviceLink().id();
        DeviceRecord record = devices == null || id == null || devices.attached.get(id) != blockEntity ? null : devices.registry.record(id);
        MineqttNetworking.sendDeviceLinks(player, record == null ? DeviceLinks.none(blockEntity.getBlockPos()) : devices.links(record));
    }

    /**
     * {@code DeviceUnlinkC2S}: a player removes one of the links of the device whose screen they have open (its Advanced
     * tab). Configuring this device is enough for a wire that starts or ends at it, so an owner can always cut a wire to
     * or from their device, whoever made it and wherever its other end is; a wire into its group also needs CONFIGURE on
     * the wire's source or on every member ({@link LinkRules#mayRemove}). A refusal is told to them, in chat and on the
     * screen.
     */
    public static void unlink(ServerPlayer player, BlockEntity blockEntity, UUID wire) {
        fromScreen(player, blockEntity, LINKS_KEY, (devices, record, editor) -> devices.unlink(record, wire, editor, devices.configurer(player)));
    }

    private Configured unlink(DeviceRecord record, UUID id, Editor editor, Predicate<UUID> configures) {
        if (!editor.grants().configure()) return Configured.refused(DeviceConfig.refusal(LinkSetting.Gate.CONFIGURE));
        Wire wire = registry.wire(id);
        if (wire == null || !LinkRules.touches(registry, wire, record.id())) return Configured.refused("that wire is gone");
        if (!LinkRules.mayRemove(registry, wire, record.id(), configures)) {
            return Configured.refused("that wire drives the whole group: you may not configure its source or every member");
        }
        String described = describe(wire);
        removeWire(id);
        return Configured.applied("Removed wire " + described);
    }

    /** The port an endpoint names, of its device's type or of its group's member type; empty when it names none. */
    public Optional<Port> port(Endpoint endpoint, Port.Direction direction) {
        return RegistryWiring.port(registry, endpoint, direction);
    }

    /** A wire as players read it, with slugs for ids: {@code garage_input.powered -> group.lamps.state (follow)}. */
    public String describe(Wire wire) {
        return describe(wire.from()) + " -> " + describe(wire.to()) + " (" + wire.mapping().name().toLowerCase(Locale.ROOT) + ")"
                + (wire.enabled() ? "" : ", disabled");
    }

    /** An endpoint as {@code /mineqtt wires} takes it: {@code <slug>.<port>} or {@code group.<slug>.<port>}. */
    public String describe(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.PortRef port -> Optional.ofNullable(registry.record(port.node())).map(DeviceRecord::slug).orElse(port.node().toString())
                    + "." + port.port();
            case Endpoint.GroupPortRef port -> "group." + Optional.ofNullable(registry.group(port.group())).map(DeviceGroup::slug).orElse(port.group().toString())
                    + "." + port.port();
            case Endpoint.TopicRef _, Endpoint.HaEntityRef _ -> endpoint.describe();
        };
    }

    /**
     * An endpoint as players read it in chat and on screens, with names for ids: {@code Garage Input.powered}, or a group's
     * name and port; a device or group that is gone shows its id.
     */
    public String label(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.PortRef port -> Optional.ofNullable(registry.record(port.node())).map(DeviceRecord::name).orElse(port.node().toString())
                    + "." + port.port();
            case Endpoint.GroupPortRef port -> Optional.ofNullable(registry.group(port.group())).map(DeviceGroup::name).orElse(port.group().toString())
                    + "." + port.port();
            case Endpoint.TopicRef _, Endpoint.HaEntityRef _ -> endpoint.describe();
        };
    }

    /** What the message bus did since the server started (M2 plan §3.3 "dropped and counted"). */
    public FlowCounters flowCounters() {
        Map<MessageBus.Drop, Long> dropped = new EnumMap<>(MessageBus.Drop.class);
        for (MessageBus.Drop reason : MessageBus.Drop.values()) {
            if (bus.dropped(reason) > 0) dropped.put(reason, bus.dropped(reason));
        }
        return new FlowCounters(bus.emitted(), bus.delivered(), bus.suppressed(), bus.queued(), Collections.unmodifiableMap(dropped));
    }

    /**
     * The message bus's counters: messages emitted, deliveries a target took, values a wire did not pass on as
     * unchanged, messages waiting for a drain, and the drops by reason (only reasons that occurred).
     */
    public record FlowCounters(long emitted, long delivered, long unchanged, int queued, Map<MessageBus.Drop, Long> dropped) {
        /** The line {@code /mineqtt status} shows. */
        public String summary() {
            List<String> drops = dropped.entrySet().stream().map(drop -> drop.getValue() + " " + drop.getKey().name().toLowerCase(Locale.ROOT)).toList();
            return "emitted " + emitted + ", delivered " + delivered + ", unchanged " + unchanged + ", queued " + queued
                    + "; dropped: " + (drops.isEmpty() ? "none" : String.join(", ", drops));
        }
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
        // A v1 owner name an online-mode server has not resolved yet grants nothing (M2 plan D7); offline mode derives it.
        String owner = link.owner() == null && !link.ownerName().isEmpty() && runtime.server().usesAuthentication() ? " | owner not verified yet" : "";
        return Optional.of(record.slug() + " | HA " + (runtime.discovery().exposed(record) ? "exposed" : "hidden") + " | "
                + availability + " | Raw " + rawStatus(record, link) + owner);
    }

    /**
     * Why the Raw Link of a loaded device does nothing: it was stored refused, or the ACL refuses its topic for the owner
     * now. Empty while it works, while a legacy group follows its topic, and for a device without a link.
     */
    public Optional<String> rawProblem(UUID id) {
        BlockEntity blockEntity = attached.get(id);
        DeviceRecord record = registry.record(id);
        if (blockEntity == null || record == null) return Optional.empty();
        return rawProblem(record, requireHost(blockEntity).deviceLink());
    }

    /**
     * The ACL changed (a config reload, M2 plan D1): every attached Raw Link is judged again, without a restart. A link
     * with a channel is resolved again, so it moves, is refused or opens again; any other link stays on its topic and
     * opens or closes as the ACL allows it now, as do the v1 topics of legacy groups. New refusals and moves reach the
     * players, open screens show the new status, and an output that works again publishes its current value.
     */
    public void aclChanged() {
        if (stopping) return;
        // A topic refused, allowed and refused again is logged again.
        loggedProblems.clear();
        for (BlockEntity blockEntity : List.copyOf(attached.values())) {
            DeviceHost host = requireHost(blockEntity);
            DeviceLink link = host.deviceLink();
            DeviceRecord record = registry.record(link.id());
            if (record == null || link.raw() == null) continue;
            boolean hadProblem = rawProblems.problem(record.id()) != null;
            RawLink before = link.raw();
            boolean published = false;
            if (reResolve(blockEntity, record, host)) {
                published = rawChanged(host, before);
            } else {
                if (verdictChanged(rawSubscriptions.containsKey(record.id()), rawProblem(record, link).isPresent())) refreshRaw(record);
                settle(record, host, true);
            }
            String rawValue = rawLevel(host);
            if (!published && hadProblem && rawValue != null && rawProblem(record, link).isEmpty()) publishRaw(host, rawValue);
        }
        for (DeviceGroup group : registry.groups()) {
            if (group.legacyTopic() == null) continue;
            if (verdictChanged(groupRawSubscriptions.containsKey(group.id()), !runtime.acl().checkLegacy(group.legacyTopic(), false).allowed())) {
                closeGroupRaw(group.id());
                openGroupRaw(group);
            }
        }
    }

    /**
     * Whether {@link #aclChanged} opens or closes a subscription: only when the ACL's verdict on it changed, an open
     * one now refused or a closed one now allowed, so a retained value is not delivered again needlessly.
     *
     * @param subscribed the subscription is open
     * @param refused    the ACL refuses its topic now
     */
    public static boolean verdictChanged(boolean subscribed, boolean refused) {
        return subscribed == refused;
    }

    void publishState(DeviceHost host, boolean force) {
        UUID id = host.deviceLink().id();
        DeviceRecord record = id == null ? null : registry.record(id);
        if (record == null) return;
        JsonObject state = stateOf(host);
        JsonObject before = record.lastState();
        if (!state.equals(before)) {
            record.setLastState(state);
            registry.markDirty();
        }
        if (stopping) return;
        // The out-ports carry every change at once; the throttle below only paces MQTT.
        emitChanges(record.id(), host.deviceType(), before, state);
        // Open screens follow the state at once; the throttle only paces MQTT.
        staleViews.add(id);
        StateThrottle throttle = throttles.computeIfAbsent(id, key -> StateThrottle.of(host.deviceType()));
        long now = Util.getMillis();
        if (force) {
            throttle.markPublished(state, now);
        } else {
            StateThrottle.Verdict verdict = throttle.offer(state, now);
            // A held state is published by a later tick, or by a pump while the server is paused.
            if (verdict == StateThrottle.Verdict.DEFER) runtime.workQueued();
            if (verdict != StateThrottle.Verdict.PUBLISH) return;
        }
        Outbound message = Outbound.state(topics().device(record.slug()), state);
        publish(id, force ? message.asRefresh() : message);
        echoState(host, record, state);
    }

    /**
     * From {@link #editRaw}: the device's config or a paper changed the Raw Link of a loaded device. Besides
     * a refusal, the player who made the change hears where an item or paper topic went that is not usable as written.
     *
     * @param before the link until the change, which judges what the device leaves on its old topic
     */
    void rawEdited(DeviceHost host, @Nullable RawLink before) {
        DeviceRecord record = registry.record(host.deviceLink().id());
        if (record == null) return;
        rawChanged(host, before);
        RawLink raw = host.deviceLink().raw();
        ServerPlayer editor = online(host.recentEditor());
        if (editor != null && raw != null && RawTopics.usesSharedForm(raw)) {
            editor.sendSystemMessage(Component.translatable("mineqtt.raw_link.shared", record.name(), raw.channel(), raw.topic()));
        }
    }

    /**
     * The Raw Link of a loaded device changed: its subscription, its legacy group, the players and a retained level
     * ({@link #publishRetainPlan}) follow.
     *
     * @param before the link until the change
     * @return whether the device published its current level
     */
    boolean rawChanged(DeviceHost host, @Nullable RawLink before) {
        DeviceRecord record = registry.record(host.deviceLink().id());
        if (record == null) return false;
        RawLink raw = host.deviceLink().raw();
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        // v1 kept one state per topic: a lamp leaves the legacy group of the topic it left, and joins the one it links to.
        if (LegacyAdoption.leavesLegacyGroup(group, raw)) {
            leave(record);
        } else {
            refreshRaw(record);
        }
        joinLegacyGroup(record, host);
        settle(record, host, true);
        return publishRetainPlan(host, RawRetain.plan(before, raw, host.deviceType().rawLink().orElseThrow()));
    }

    /**
     * From {@link #editRaw}: the device's config changed only the custom payloads of a loaded device's Raw
     * Link, so a retained level is published again in their form, and open screens show the new payloads.
     */
    void rawPayloadsChanged(DeviceHost host, RawLink before) {
        UUID id = host.deviceLink().id();
        if (registry.record(id) != null) {
            publishRetainPlan(host, RawRetain.plan(before, host.deviceLink().raw(), host.deviceType().rawLink().orElseThrow()));
            viewChanged(id);
        }
    }

    /**
     * From {@link DeviceLink#rawValueKnown}: a device read its current value after its attach (the Redstone Input its
     * signal, which may have changed while it was unloaded). A retaining output publishes it once.
     */
    void rawValueKnown(DeviceHost host) {
        publishLevel(host);
    }

    /**
     * Publishes an event on the device's action topic and emits it on the event's out-port.
     *
     * @throws IllegalArgumentException for an event its type does not declare (M2 plan §3.8 #5): a bug in the device
     */
    void publishEvent(DeviceHost host, JsonObject event) {
        host.deviceType().eventProblem(event).ifPresent(problem -> {
            throw new IllegalArgumentException("Device " + host.deviceLink().slug() + " may not publish " + event + ": " + problem);
        });
        DeviceRecord record = registry.record(host.deviceLink().id());
        if (stopping || record == null) return;
        publish(record.id(), Outbound.event(topics().action(record.slug()), event));
        String type = event.get(PortType.Event.TYPE_KEY).getAsString();
        emit(FlowMessage.Kind.EVENT, Endpoint.port(record.id(), type), type, event);
    }

    /**
     * Emits each readable field that changed from {@code before} to {@code after} on its out-port (M2 plan §3.1). It runs
     * on the stack of whatever changed the device, so a change a wire's message caused carries that message's hops on
     * and a loop without a delay is cut. A device whose state is unchanged since its last run emits nothing when it
     * attaches again.
     *
     * @param before the state last recorded; null for a device that has none yet
     */
    private void emitChanges(UUID id, DeviceType type, @Nullable JsonObject before, JsonObject after) {
        type.changedOutputs(before, after).forEach((key, value) -> emit(FlowMessage.Kind.VALUE, Endpoint.port(id, key), key, value));
    }

    private void emit(FlowMessage.Kind kind, Endpoint source, String topic, JsonElement payload) {
        // A paused server is pumped to carry it; a tick's own end recomputes the flag anyway.
        if (bus.emit(kind, source, topic, payload, runtime.server().overworld().getGameTime())) runtime.workQueued();
    }

    /**
     * The bus's sink: a message reaches a wire's target as a {@link CommandSource#BINDING} command through
     * {@link #command}, as {@code {"<port>": value}} or, for an inline field's port, as the value's own keys. A group fans
     * it out to its members, except the one the message came from. Taken unless nobody takes it (an unloaded sensor, an
     * unknown type) or the device rejects it.
     */
    private boolean deliverWire(Wire wire, FlowMessage message) {
        Optional<RegistryWiring.Command> command = RegistryWiring.command(registry, wire.to(), message.payload());
        return command.isPresent()
                && taken(command(command.get().target(), command.get().command(), CommandSource.BINDING, DeviceRegistry.node(message.source())));
    }

    /** Whether a wire's target took its message: every route but {@link Route#DROP}, unless the device rejected it. */
    static boolean taken(Delivery delivery) {
        return delivery.route() != Route.DROP && (delivery.result() == null || delivery.result().outcome() != CommandResult.Outcome.REJECTED);
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
        byte[] bytes = ValueCodecs.utf8(payload);
        boolean retain = host.deviceType().rawLink().map(raw::effectiveRetain).orElse(false);
        publish(link.id(), retain ? Outbound.rawRetained(raw.topic(), bytes, raw.qos(), link.id()) : Outbound.raw(raw.topic(), bytes, raw.qos(), link.id()));
    }

    /** From {@link DeviceLink#publishRawEdge}: an edge of an output's level, published when its link publishes it. */
    void publishRawEdge(DeviceHost host, boolean rising, String payload) {
        if (publishesEdge(host.deviceLink().raw(), rising)) publishRaw(host, payload);
    }

    /**
     * Whether an output publishes an edge to its Raw Link: not without a link, and not the falling edge of a link of rising
     * edges only (M2 plan D11, v1's Static Publisher).
     */
    static boolean publishesEdge(@Nullable RawLink raw, boolean rising) {
        return raw != null && raw.edges().publishes(rising);
    }

    /**
     * The value an output publishes again on its own ({@link DeviceHost#rawValue()}): null when it does not know it, and
     * for a link of rising edges only, which carries events and no level (M2 plan D11).
     */
    static @Nullable String rawLevel(DeviceHost host) {
        RawLink raw = host.deviceLink().raw();
        return raw == null || !raw.edges().level() ? null : host.rawValue();
    }

    /**
     * Publishes a retaining output's current level again (M2 plan D2), so the broker holds the live value: after every
     * CONNACK (a broker restarted without persistence holds nothing), once the device knows its value after the attach,
     * and for the other outputs on a topic one of them left.
     *
     * @return whether it was published; not for a device without a known value or a link that does not retain
     */
    private boolean publishLevel(DeviceHost host) {
        RawLink raw = host.deviceLink().raw();
        RawLinkSpec spec = host.deviceType().rawLink().orElse(null);
        String value = rawLevel(host);
        if (stopping || spec == null || value == null || !RawRetain.retains(raw, spec)) return false;
        publishRaw(host, value);
        return true;
    }

    /**
     * Publishes what a change of a retaining output's link leaves on the broker ({@link RawRetain#plan}). The off payload
     * and the clear are judged by the ACL as the old link, grandfather flag included, and kept through any outage, since
     * nothing corrects them later; the current level is judged by the link now. A topic left off or cleared is taken
     * over by every other retaining output of this server on it, which publishes its own level in the same tick; outputs
     * on other servers restore theirs at their next edge or CONNACK, and the last writer wins.
     *
     * @return whether the device published its current level
     */
    private boolean publishRetainPlan(DeviceHost host, List<RawRetain.Step> steps) {
        if (stopping) return false;
        DeviceLink link = host.deviceLink();
        boolean published = false;
        for (RawRetain.Step step : steps) {
            if (step.kind() == RawRetain.Kind.LEVEL) {
                published |= publishLevel(host);
                continue;
            }
            // The device may not publish to a topic the ACL refuses for the old link: its levels were refused there too, or
            // the refusal is why the link moved away.
            if (rawRefusal(link, step.judgedBy()).isPresent()) continue;
            publish(link.id(), Outbound.rawRetainedFinal(step.topic(), ValueCodecs.utf8(step.payload()), step.judgedBy().qos(), link.id()));
            othersTakeOver(link.id(), step.topic());
        }
        return published;
    }

    // The other retaining outputs on a topic a device left off or cleared publish their own level; the last writer wins.
    private void othersTakeOver(UUID left, String topic) {
        for (BlockEntity blockEntity : attached.values()) {
            DeviceHost other = requireHost(blockEntity);
            RawLink raw = other.deviceLink().raw();
            if (!left.equals(other.deviceLink().id()) && raw != null && raw.topic().equals(topic)) publishLevel(other);
        }
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
     * availability, state, and discovery when it changed. An attach that throws hands back what it used up
     * ({@link #undoAttach}) before the attach guard faults the device, so its retry starts from the same data.
     */
    private void attach(BlockEntity blockEntity) {
        DeviceLink link = requireHost(blockEntity).deviceLink();
        GlobalPos pos = GlobalPos.of(blockEntity.getLevel().dimension(), blockEntity.getBlockPos());
        LegacyNbtV1.Snapshot v1 = link.takePendingV1();
        boolean placed = link.placedByPlayer();
        DeviceRecord listed = registry.at(pos);
        String listedTopic = listed == null ? null : listed.legacyTopic();
        try {
            attach(blockEntity, pos, v1, placed, listed);
        } catch (RuntimeException e) {
            try {
                undoAttach(blockEntity, pos, v1, placed, listed, listedTopic);
            } catch (RuntimeException undoFailure) {
                e.addSuppressed(undoFailure);
            }
            throw e;
        }
    }

    /**
     * The attach itself.
     *
     * @param v1     the v1 data it adopts, taken from the block entity
     * @param placed whether a player placed the device and its data was checked then
     * @param listed the record at the position before the attach
     */
    private void attach(BlockEntity blockEntity, GlobalPos pos, LegacyNbtV1.@Nullable Snapshot v1, boolean placed, @Nullable DeviceRecord listed) {
        DeviceHost host = requireHost(blockEntity);
        DeviceLink link = host.deviceLink();
        DeviceType type = host.deviceType();
        // §9: v1 data adopted on chunk load. A device the v1 topic states pre-created for its v1 topic takes that
        // record's identity; a record they listed here for another topic is forgotten below.
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
                forget(previous, false);
            }
        }
        if (record == null) {
            // Block entity data may have been edited (/data); the name becomes the Home Assistant device name. A v1
            // device is named after its topic, as the v1 topic states' devices are.
            String nbtName = ValueCodecs.sanitizeText(link.name()).strip();
            String v1Name = v1 == null || type.remote() ? "" : LegacyNbtV1.topicName(v1.combinedTopic());
            String name = !nbtName.isEmpty() ? (copy ? nbtName + " (copy)" : nbtName) : v1Name.isEmpty() ? type.defaultName(id) : v1Name;
            String wanted = copy || v1 != null ? Slugs.fromName(name) : link.slug();
            record = new DeviceRecord(id, type.id(), pos, name, registry.uniqueSlug(wanted.isEmpty() ? type.defaultSlug(id) : wanted, id));
            registry.add(record);
            runtime.bridgeInfoChanged();
            if (v1 != null) adoptLegacyDiscovery(record, link, v1);
        } else if (imported != null) {
            record.setLegacyTopic(null);
        }
        record.setOwner(link.owner());
        record.setAccess(link.access());
        if (mergeExpose(record, link, registered)) blockEntity.setChanged();
        registry.markDirty();
        if (link.assign(id, record.name(), record.slug())) blockEntity.setChanged();
        attached.put(id, blockEntity);
        link.setAttached(true);
        if (poweredOn.add(id) && registered) host.powerOn();
        JsonObject pending = record.pending();
        if (pending != null) {
            // The second half of command's QUEUE route: the queued target lands with the block entity.
            record.setPending(null);
            host.applyCommand(new CommandReader(pending), CommandSource.HA);
        }
        // What a registered device published to before; a copy or a placement never published to its copied topic.
        RawLink stored = registered ? link.raw() : null;
        // Only data this registry knew, or v1 data adopted on chunk load, is trusted to fill the owner's literal set. Both
        // run before the owner lookup, which settles a grandfather flag of trusted data once the v1 owner is known.
        boolean trusted = registered || v1 != null;
        boolean moved = reResolveM1(blockEntity, record, host, trusted);
        settleGrandfathered(blockEntity, link, trusted);
        if (link.owner() == null && !link.ownerName().isEmpty()) resolveOwner(id, link.ownerName());
        moved |= reResolve(blockEntity, record, host);
        if (moved && LegacyAdoption.leavesLegacyGroup(record.group() == null ? null : registry.group(record.group()), link.raw())) leave(record);
        // §9.2: a v1 lamp missing from the topic states, or a placed lamp, linked to a legacy group's topic joins it, as
        // does a device whose link now resolves to one.
        if (v1 != null || placed || moved) joinLegacyGroup(record, host);
        // D15: a v1 lamp keeps its v1 topic for the remotes that commanded it, a v1 remote until it is wired to them.
        if (v1 != null) record.setV1Topic(LegacyAdoption.keptV1Topic(type, v1.combinedTopic()));
        if (type.remote() && record.v1Topic() != null) remotesToWire.put(id, REMOTE_WIRE_WAIT_TICKS);
        if (type.id().equals(SmartLamp.TYPE.id())) remotesChanged = true;
        openRaw(host, type);
        if (!stopping) mqtt().publish(Outbound.state(topics().availability(record.slug()), ONLINE));
        publishState(host, true);
        if (!stopping) runtime.discovery().refresh(record);
        // A link that became refused here is told; one that was refused already, e.g. before a restart, is only recorded.
        settle(record, host, moved);
        // A registered output whose link moved here leaves its old topic off.
        if (registered && moved) publishRetainPlan(host, RawRetain.plan(stored, link.raw(), type.rawLink().orElseThrow()));
        if (faults.remove(pos) != null) MineQTT.LOGGER.info("Device {} at {} attached and is no longer faulted", record.slug(), pos);
    }

    // Something a waiting remote may have waited for happened outside the attach drain: a paused server is pumped for it.
    private void remotesChanged() {
        remotesChanged = true;
        if (!remotesToWire.isEmpty()) runtime.workQueued();
    }

    // Whether a waiting remote is due to be matched on the next tick.
    private boolean remotesDue() {
        return remotesChanged && !remotesToWire.isEmpty() || remotesToWire.values().stream().anyMatch(ticks -> ticks != REMOTE_UNDECIDED);
    }

    /**
     * Wires the devices that adopted a v1 remote to the lamps the remote commanded (M2 plan D15,
     * {@link LegacyAdoption#remoteTargets}), after each tick's attaches, so the lamps loaded with them have attached too: a
     * wire from the switch's {@code press} to each lamp's or the legacy group's {@code state}, toggling as
     * {@link DefaultWiring} pairs them, owned by the remote's owner (the console's while their UUID is not known). A
     * migration is trusted, so neither CONFIGURE nor the wire limit is asked. The remote's topic stays in its record
     * ({@link DeviceRecord#v1Topic}) until this is settled, so a device that unloads first, a restart or a failed match
     * goes on at its next attach. While lamps it may have commanded have not loaded since v1, or an owner's UUID is
     * missing to compare one ({@link LegacyAdoption#remoteUndecided}), it waits for a lamp to attach, an owner to become
     * known or a record to go. A remote that commanded nothing known waits {@link #REMOTE_WIRE_WAIT_TICKS} loaded ticks
     * for lamps the registry does not know yet, as a world loads around its spawn, then has its v1 commands dropped with a
     * log line.
     */
    private void wireRemotes() {
        boolean changed = remotesChanged;
        remotesChanged = false;
        if (remotesToWire.isEmpty()) return;
        List<LegacyAdoption.Lamp> lamps = null;
        for (Map.Entry<UUID, Integer> entry : List.copyOf(remotesToWire.entrySet())) {
            UUID id = entry.getKey();
            if (!changed && entry.getValue() == REMOTE_UNDECIDED) continue;
            BlockEntity blockEntity = attached.get(id);
            DeviceRecord record = registry.record(id);
            if (blockEntity == null || record == null || record.v1Topic() == null) {
                remotesToWire.remove(id);
                continue;
            }
            if (lamps == null) lamps = loadedLamps();
            try {
                matchRemote(requireHost(blockEntity), record, entry.getValue(), lamps);
            } catch (RuntimeException e) {
                remotesToWire.remove(id);
                MineQTT.LOGGER.error("Could not wire the v1 remote {} to the lamps of its topic '{}'; it tries again when it loads",
                        record.slug(), record.v1Topic(), e);
            }
        }
    }

    // The loaded Smart Lamps, as a v1 remote's topic is matched against them.
    private List<LegacyAdoption.Lamp> loadedLamps() {
        List<LegacyAdoption.Lamp> lamps = new ArrayList<>();
        for (BlockEntity blockEntity : attached.values()) {
            DeviceLink link = requireHost(blockEntity).deviceLink();
            if (!link.type().id().equals(SmartLamp.TYPE.id())) continue;
            lamps.add(new LegacyAdoption.Lamp(link.id(), link.owner(), link.ownerName(), link.raw() == null ? null : link.raw().topic()));
        }
        return lamps;
    }

    // Matches one device that adopted a v1 remote: wires what it commanded and keeps waiting while that cannot be told in
    // full. Once settled, or at the end of its wait for unknown lamps, its record forgets the topic.
    private void matchRemote(DeviceHost host, DeviceRecord record, int waiting, List<LegacyAdoption.Lamp> lamps) {
        DeviceLink link = host.deviceLink();
        String topic = record.v1Topic();
        List<Endpoint> targets = LegacyAdoption.remoteTargets(registry, topic, link.owner(), link.ownerName(), lamps);
        wireRemote(host, topic, targets);
        if (LegacyAdoption.remoteUndecided(registry, topic, link.owner(), link.ownerName(), lamps, targets)) {
            if (waiting != REMOTE_UNDECIDED) {
                MineQTT.LOGGER.info("Device {} was a v1 remote on '{}'; it waits for lamps there that have not loaded since v1, or for an owner's UUID",
                        record.slug(), topic);
            }
            remotesToWire.put(record.id(), REMOTE_UNDECIDED);
            return;
        }
        int left = (waiting == REMOTE_UNDECIDED ? REMOTE_WIRE_WAIT_TICKS : waiting) - 1;
        if (targets.isEmpty() && left > 0) {
            remotesToWire.put(record.id(), left);
            return;
        }
        remotesToWire.remove(record.id());
        record.setV1Topic(null);
        registry.markDirty();
        if (targets.isEmpty()) {
            MineQTT.LOGGER.info("Device {} was a v1 remote on '{}'; no lamp of its owner here listens there, so its v1 commands are dropped",
                    record.slug(), topic);
        }
    }

    // Wires a switch to what its v1 remote commanded, once per lamp or group.
    private void wireRemote(DeviceHost host, String topic, List<Endpoint> targets) {
        if (targets.isEmpty()) return;
        DeviceLink link = host.deviceLink();
        DefaultWiring.Pair pair = DefaultWiring.of(host.deviceType(), SmartLamp.TYPE).orElseThrow();
        Endpoint from = Endpoint.port(link.id(), pair.from());
        for (Endpoint to : targets) {
            if (registry.wiresFrom(from).stream().anyMatch(wire -> wire.to().equals(to))) continue;
            Optional<String> problem = RegistryWiring.problem(registry, from, to, pair.mapping());
            if (problem.isPresent()) {
                MineQTT.LOGGER.info("Device {} was a v1 remote on '{}'; it is not wired to {}: {}", link.slug(), topic, describe(to), problem.get());
                continue;
            }
            Wire wire = new Wire(UUID.randomUUID(), from, to, pair.mapping(), Scope.WIRELESS, link.owner() != null ? link.owner() : Util.NIL_UUID, true);
            registry.addWire(wire);
            linksChanged(List.of(wire));
            MineQTT.LOGGER.info("Device {} was a v1 remote on '{}'; wired {}", link.slug(), topic, describe(wire));
        }
    }

    /**
     * Hands back what a failed attach used up (M2 plan D7), so its retry, or the next chunk load, attaches the device as
     * the failed attempt would have. The v1 data waits for adoption again: the block entity saves it as v1 data, the
     * prune check sees a block still to adopt, and the retry adopts it as trusted v1 data with its name, Home Assistant
     * light and legacy group. A placement is still one. A record the v1 topic states pre-created keeps its v1 topic, so
     * the retry takes it over again. A record the attempt created is dropped, so the retry is again the first attach of a
     * device new to the registry; the id the attempt gave the block entity stays, and the retry reuses it.
     */
    private void undoAttach(BlockEntity blockEntity, GlobalPos pos, LegacyNbtV1.@Nullable Snapshot v1, boolean placed,
                            @Nullable DeviceRecord listed, @Nullable String listedTopic) {
        requireHost(blockEntity).deviceLink().handBack(v1, placed);
        blockEntity.setChanged();
        if (listed != null && registry.record(listed.id()) == listed && listedTopic != null) {
            // An imported record gets its lamp's v1 topic only when the lamp takes it over.
            listed.setLegacyTopic(listedTopic);
            listed.setV1Topic(null);
        }
        DeviceRecord created = registry.at(pos);
        if (created == null || created == listed) return;
        registry.remove(created.id());
        poweredOn.remove(created.id());
        // A device the attempt attached may have announced itself online; a faulted device is offline.
        if (attached.get(created.id()) == blockEntity && !stopping) {
            mqtt().publish(Outbound.state(topics().availability(created.slug()), OFFLINE));
        }
        runtime.bridgeInfoChanged();
    }

    /**
     * The attach of a device threw (M2 plan D7): it is detached again and faulted; {@link #undoAttach} already handed
     * back the data it used up. A retry neither queues again a pending command the attach took nor powers on again a
     * device whose power-on ran: either may be what threw.
     */
    private void attachFailed(BlockEntity blockEntity, RuntimeException failure) {
        DeviceLink link = requireHost(blockEntity).deviceLink();
        UUID id = link.id();
        if (id != null && attached.get(id) == blockEntity) {
            attached.remove(id);
            closeRaw(id);
            throttles.remove(id);
            rawProblems.forget(id);
        }
        link.setAttached(false);
        fault(GlobalPos.of(blockEntity.getLevel().dimension(), blockEntity.getBlockPos()), Stage.ATTACH, failure);
        // Its open screens show the fault, and a retry that failed again. Building their views runs the device's code
        // again, which may throw as its attach did; that must not reach the tick either.
        AttachQueue.isolate(blockEntity, faulted -> {
            viewChanged(faulted);
            return true;
        }, (faulted, e) -> MineQTT.LOGGER.error("Could not show the fault of the device at {} to its open screens", faulted.getBlockPos().toShortString(), e));
    }

    /**
     * Marks the device at a position faulted and its record offline. The stack trace is logged once: the same failure
     * again, after its chunk loaded again or a retry, is logged as one line.
     */
    private void fault(GlobalPos pos, Stage stage, RuntimeException failure) {
        Fault fault = new Fault(stage, reason(failure));
        Fault before = faults.put(pos, fault);
        DeviceRecord record = registry.at(pos);
        String device = record == null ? "at " + pos : record.slug() + " at " + pos;
        if (fault.equals(before)) {
            MineQTT.LOGGER.warn("Device {} is still faulted: {}", device, fault.text());
        } else {
            MineQTT.LOGGER.error("Device {} faulted: its {} failed; it stays offline until its chunk loads again or /mineqtt devices retry",
                    device, stage.label(), failure);
        }
        if (record != null && !stopping) mqtt().publish(Outbound.state(topics().availability(record.slug()), OFFLINE));
    }

    // The exception's type and message, as status and devices info show it.
    private static String reason(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : ValueCodecs.sanitizeText(failure.getMessage()).strip();
        return failure.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }

    /**
     * The attach's merge of the device's own Home Assistant exposure. A device new to the registry gives its link's
     * value to the record. For a registered device the registry wins: {@code /mineqtt devices expose} may have changed
     * it while it was unloaded, and the v1 light a pre-created record replaces stays in Home Assistant (§8.8), also
     * when its unique_id went to another.
     *
     * @return whether the link changed, so the block entity needs saving
     */
    static boolean mergeExpose(DeviceRecord record, DeviceLink link, boolean registered) {
        if (!registered) {
            record.setExpose(link.expose());
            return false;
        }
        if (Objects.equals(link.expose(), record.expose())) return false;
        link.setExpose(record.expose());
        return true;
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
     * The one-time re-resolution of a Raw Link M1 saved (M2 plan D1). For a device this registry knew, it stays while
     * the ACL accepts it for the owner, the operator bypass included, and joins the owner's literal set; otherwise, and
     * for any copy, structure or placement, it is resolved like a new link and the owner hears that it moved.
     *
     * @return whether the link moved
     */
    private boolean reResolveM1(BlockEntity blockEntity, DeviceRecord record, DeviceHost host, boolean trusted) {
        DeviceLink link = host.deviceLink();
        RawLink raw = link.raw();
        // Settled grandfathered v1 topics and links with a channel are no M1 links; this spares their owner's permission lookup.
        if (raw == null || raw.resolved() || raw.grandfathered() != null || raw.channel() != null) return false;
        TopicAcl.Decision withBypass = trusted
                ? runtime.acl().checkPublish(AclContext.owner(runtime.server(), link.owner(), link.ownerName()), raw.topic()) : null;
        RawTopics.ReResolution result = RawTopics.reResolveM1(raw, host.isPrivateMode(), withBypass, runtime.acl(), link.owner(),
                registry.ownerLiterals(link.owner()));
        if (result.pass() == RawTopics.Pass.NONE) return false;
        link.setRaw(result.link());
        blockEntity.setChanged();
        if (result.remember() != null && registry.addOwnerLiteral(link.owner(), result.remember())) ownerLiteralAdded(link.owner(), result.remember());
        if (result.pass() != RawTopics.Pass.REMAPPED) return false;
        reportResolved(record, host, raw, result.link(), withBypass);
        return true;
    }

    /**
     * Settles a grandfathered v1 topic once ({@link LegacyAdoption#settleGrandfathered}); when it joins the owner's
     * literal set, the owner's loaded links on that literal follow it.
     */
    private void settleGrandfathered(BlockEntity blockEntity, DeviceLink link, boolean trusted) {
        LegacyAdoption.Settlement settled = LegacyAdoption.settleGrandfathered(registry, link.raw(), trusted);
        if (settled == null) return;
        link.setRaw(settled.link());
        blockEntity.setChanged();
        if (settled.added() != null) ownerLiteralAdded(settled.link().grandfathered().owner(), settled.added());
    }

    /**
     * Resolves a Raw Link with a channel (an item or paper literal) again for the device's owner now
     * ({@link RawTopics#recheck}): at every attach, when the device gets its owner, when the owner's literal set gains the
     * channel and when the ACL changes. The rules are pure, so the link changes only when the owner, their literal set or
     * the ACL did, and the result never depends on the order devices attach in; a copy is resolved for its new owner, and
     * a refusal heals once the configuration allows the topic. A move is logged and told, a refusal is logged here and
     * told when the device settles.
     *
     * @return whether the link changed
     */
    private boolean reResolve(BlockEntity blockEntity, DeviceRecord record, DeviceHost host) {
        DeviceLink link = host.deviceLink();
        RawLink raw = link.raw();
        // A device whose v1 owner is still looked up is resolved once the owner is known (ownerChanged).
        if (raw == null || resolvingOwners.contains(record.id())) return false;
        RawLink resolved = RawTopics.recheck(raw, host.isPrivateMode(), runtime.acl(), link.owner(), registry.ownerLiterals(link.owner()));
        if (resolved.equals(raw)) return false;
        link.setRaw(resolved);
        blockEntity.setChanged();
        reportResolved(record, host, raw, resolved, null);
        return true;
    }

    /**
     * An owner's literal set gained a literal: their loaded devices whose channel it is resolve again, the unloaded ones
     * at their next attach.
     */
    private void ownerLiteralAdded(UUID owner, String literal) {
        for (BlockEntity blockEntity : List.copyOf(attached.values())) {
            DeviceHost host = requireHost(blockEntity);
            DeviceLink link = host.deviceLink();
            RawLink raw = link.raw();
            DeviceRecord record = registry.record(link.id());
            if (record == null || raw == null || !owner.equals(link.owner()) || !literal.equals(raw.channel())) continue;
            if (reResolve(blockEntity, record, host)) rawChanged(host, raw);
        }
    }

    /**
     * Logs a resolved Raw Link that moved or became refused, and tells about a move; a refusal is told when the device
     * settles ({@link #settle}), once.
     *
     * @param refused the ACL's refusal of the old topic that made an M1 link move, or null
     */
    private void reportResolved(DeviceRecord record, DeviceHost host, RawLink before, RawLink after, TopicAcl.@Nullable Decision refused) {
        if (!after.active()) {
            if (Objects.equals(before.disabledReason(), after.disabledReason())) return;
            MineQTT.LOGGER.warn("Raw Link of device {} on '{}' is refused: {}", record.slug(), after.channel(), after.disabledReason());
        } else if (!before.topic().equals(after.topic())) {
            if (refused != null) {
                MineQTT.LOGGER.info("Raw Link of device {} moved from '{}' to '{}': {}", record.slug(), before.topic(), after.topic(), refused.message());
                tell(host, Component.translatable("mineqtt.raw_link.moved", record.name(), before.topic(), after.topic()));
            } else {
                MineQTT.LOGGER.info("Raw Link of device {} moved from '{}' to '{}'", record.slug(), before.topic(), after.topic());
                tell(host, Component.translatable("mineqtt.raw_link.resolved", record.name(), before.topic(), after.topic()));
            }
        }
    }

    /**
     * After a device's Raw Link changed or was judged again: records its problem, tells a new one, and refreshes the
     * status line of every open screen of the device. A refusal while the device's v1 owner is looked up is not
     * recorded: it ends once the owner is known.
     *
     * @param announce false when the device only settles as it already was, e.g. at its attach after a restart
     */
    private void settle(DeviceRecord record, DeviceHost host, boolean announce) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity == null) return;
        DeviceLink link = host.deviceLink();
        Optional<String> problem = rawProblem(record, link);
        if (!resolvingOwners.contains(record.id()) && rawProblems.update(record.id(), problem.orElse(null)) && announce) {
            RawLink raw = link.raw();
            tell(host, Component.translatable("mineqtt.raw_link.refused", record.name(), raw.channel() != null ? raw.channel() : raw.topic(),
                    problem.get()));
        }
        pushStatus(record, blockEntity, problem);
    }

    // Refreshes the status line and the Raw Link problem of every open screen of a loaded device.
    private void pushStatus(DeviceRecord record, BlockEntity blockEntity) {
        if (stopping) return;
        pushStatus(record, blockEntity, rawProblem(record, requireHost(blockEntity).deviceLink()));
    }

    private void pushStatus(DeviceRecord record, BlockEntity blockEntity, Optional<String> problem) {
        if (stopping) return;
        status(record.id()).ifPresent(status -> MineqttNetworking.sendDeviceStatus(blockEntity, status, problem.orElse(null)));
        staleViews.add(record.id());
    }

    /**
     * Something a device's view shows changed: every player with its screen open gets its new {@link DeviceView} at the
     * end of the tick, built for them, since what they may change differs. A screen that opens gets its first view with
     * its open data ({@link #view}).
     */
    public void viewChanged(UUID id) {
        if (!stopping) staleViews.add(id);
    }

    // The views of the devices that changed this tick, and the links lists that changed, to the players whose open screen
    // is theirs; nothing is built for a device whose screen nobody has open.
    private void sendViews() {
        for (UUID id : staleViews) {
            BlockEntity blockEntity = attached.get(id);
            if (blockEntity == null || registry.record(id) == null) continue;
            for (ServerPlayer viewer : MineqttNetworking.viewers(blockEntity)) {
                MineqttNetworking.sendDeviceView(viewer, view(viewer, blockEntity));
            }
        }
        staleViews.clear();
        for (UUID id : staleLinks) {
            BlockEntity blockEntity = attached.get(id);
            DeviceRecord record = registry.record(id);
            if (blockEntity == null || record == null) continue;
            List<ServerPlayer> viewers = MineqttNetworking.viewers(blockEntity);
            if (viewers.isEmpty()) continue;
            DeviceLinks links = links(record);
            viewers.forEach(viewer -> MineqttNetworking.sendDeviceLinks(viewer, links));
        }
        staleLinks.clear();
        if (!staleGroups) return;
        staleGroups = false;
        // Built per player, as who may change which group depends on them.
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            if (!(player.containerMenu instanceof DeviceMenu menu)) continue;
            BlockEntity blockEntity = player.level().getBlockEntity(menu.blockPos);
            if (blockEntity instanceof DeviceHost && menu.isFor(blockEntity)) sendGroups(player, blockEntity);
        }
    }

    /**
     * What a device's screen shows one player (M2-6, M2-7): the view that goes into the screen's open data, and to its
     * open screens whenever the device changed. A device that does not run on this server (not attached yet, faulted, or
     * MineQTT not started) still shows its block entity's values, with the status line "Not registered yet".
     */
    public static DeviceView view(ServerPlayer viewer, BlockEntity blockEntity) {
        DeviceHost host = requireHost(blockEntity);
        DeviceLink link = host.deviceLink();
        MinecraftServer server = viewer.level().getServer();
        DeviceRuntime devices = of(server);
        UUID id = link.id();
        DeviceRecord record = devices == null || id == null || devices.attached.get(id) != blockEntity ? null : devices.registry.record(id);
        Optional<Fault> fault = devices == null ? Optional.empty() : devices.fault(GlobalPos.of(blockEntity.getLevel().dimension(), blockEntity.getBlockPos()));
        String status = record != null ? devices.status(id).orElse(NOT_RUNNING) : fault.map(faulted -> "Faulted: " + faulted.text()).orElse(NOT_RUNNING);
        String problem = record == null ? null : devices.rawProblem(record, link).orElse(null);
        DeviceView.Registration registration = record == null ? DeviceView.Registration.NONE : devices.registration(record);
        DeviceView.Grants grants = DeviceAccess.grants(viewer, blockEntity);
        DeviceView.Ownership ownership = new DeviceView.Ownership(DeviceAccess.owner(link.owner(), link.ownerName(), server.usesAuthentication()),
                MineQTT.permissionManager.hasClaimMod());
        DeviceView.Diagnostics diagnostics = devices == null ? DeviceView.Diagnostics.notRunning(id) : devices.diagnostics(id, record != null, fault, grants);
        return DeviceView.of(blockEntity.getBlockPos(), link, registration, host.isPrivateMode(), stateOf(host), status, problem, grants,
                ownership, diagnostics);
    }

    /**
     * What a device's Diagnostics tab shows one viewer: whether it runs, and its traffic, the payloads only for a viewer
     * who may configure it (M2-11).
     *
     * @param id       the device's id; null before its first attach
     * @param attached whether it runs on this server
     * @param fault    why it does not, when its attach threw
     */
    private DeviceView.Diagnostics diagnostics(@Nullable UUID id, boolean attached, Optional<Fault> fault, DeviceView.Grants grants) {
        ConnectionState connection = mqtt().state();
        DeviceView.Availability availability;
        String detail = "";
        if (attached) {
            availability = connection.isConnected() ? DeviceView.Availability.ONLINE : DeviceView.Availability.DISCONNECTED;
            if (!connection.isConnected()) detail = connection.status().name().toLowerCase(Locale.ROOT);
        } else if (fault.isPresent()) {
            availability = DeviceView.Availability.FAULTED;
            detail = fault.get().text();
        } else {
            availability = DeviceView.Availability.NOT_RUNNING;
        }
        DeviceTraffic kept = id == null ? null : traffic.get(id);
        MqttService.Stats stats = mqtt().stats();
        return (kept == null ? new DeviceTraffic() : kept).diagnostics(id, availability, detail, grants.configure(), Util.getMillis(),
                stats.droppedInbound(), stats.droppedOutbound());
    }

    // What the bridge knows of a registered device for its screen: the root of its topics, its discovery and its group.
    private DeviceView.Registration registration(DeviceRecord record) {
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        return new DeviceView.Registration(topics().root(), runtime.discovery().exposed(record), Optional.ofNullable(group).map(DeviceGroup::name));
    }

    /**
     * Tells the players about a device's Raw Link (M2 plan D1): a chat line to the player who just edited the device,
     * else to its owner ({@link #notifyOwner}), and the owner's action bar while they are online.
     */
    private void tell(DeviceHost host, Component notice) {
        UUID owner = host.deviceLink().owner();
        ServerPlayer editor = online(host.recentEditor());
        if (editor != null) {
            editor.sendSystemMessage(notice);
        } else {
            notifyOwner(owner, notice);
        }
        ServerPlayer ownerOnline = online(owner);
        if (ownerOnline != null) ownerOnline.sendSystemMessage(notice, true);
    }

    private @Nullable ServerPlayer online(@Nullable UUID player) {
        return player == null ? null : runtime.server().getPlayerList().getPlayer(player);
    }

    /**
     * Tells a device's owner about a change of their device: at once while they are online, else when they join or open
     * a device screen ({@link #deliverNotices}). The notices wait until the server stops.
     */
    private void notifyOwner(@Nullable UUID owner, Component notice) {
        if (owner == null) return;
        ServerPlayer online = online(owner);
        if (online != null) {
            online.sendSystemMessage(notice);
            return;
        }
        OwnerNotices waiting = ownerNotices.computeIfAbsent(owner, key -> new OwnerNotices());
        if (waiting.lines.size() < MAX_NOTICES_PER_OWNER) {
            waiting.lines.add(notice);
        } else {
            waiting.more++;
        }
    }

    /** Sends a player the notices about their devices that waited for them: when they join and when they open a device screen. */
    public void deliverNotices(ServerPlayer player) {
        OwnerNotices waiting = ownerNotices.remove(player.getUUID());
        if (waiting == null) return;
        waiting.lines.forEach(player::sendSystemMessage);
        if (waiting.more > 0) player.sendSystemMessage(Component.translatable("mineqtt.raw_link.more_notices", waiting.more));
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
     * The owner of v1 data is only a name (§8.7). It is bound to the device once {@link #lookUpOwner} finds it, at an
     * attach, or once its owner joins ({@link #playerJoined}); the name stays until then.
     */
    private void resolveOwner(UUID id, String name) {
        if (!resolvingOwners.add(id)) return;
        lookUpOwner(name, owner -> {
            resolvingOwners.remove(id);
            if (owner != null) ownerResolved(id, name, owner);
        });
    }

    /**
     * A player joined: the loaded devices whose v1 owner name is exactly theirs are bound to them (M2 plan D7). It is
     * how an offline-mode server, which stores no UUID derived from a name, binds an owner who was not online when the
     * device attached.
     */
    public void playerJoined(ServerPlayer player) {
        String name = player.getGameProfile().name();
        List<UUID> named = new ArrayList<>();
        attached.forEach((id, blockEntity) -> {
            DeviceLink link = requireHost(blockEntity).deviceLink();
            if (link.owner() == null && link.ownerName().equals(name)) named.add(id);
        });
        named.forEach(id -> ownerResolved(id, name, player.getUUID()));
    }

    /**
     * Looks up the UUID of a v1 owner name (§8.7, M2 plan D7) and hands it to {@code done} on the server thread, or null
     * when it is not known yet. An online player of exactly that name answers at once ({@link AclContext#onlineOwner}).
     * A server without authentication finds nobody else: the UUID derived from the name ({@link AclContext#offlineOwner})
     * is what its owner matches meanwhile, but it is not stored, since behind a proxy that forwards online UUIDs nobody
     * carries it; the owner is bound once they are online. An online-mode server asks its profile cache off the server
     * thread, since the cache may ask Mojang's servers, once per name however many blocks wait for it.
     */
    private void lookUpOwner(String name, Consumer<@Nullable UUID> done) {
        MinecraftServer server = runtime.server();
        UUID online = AclContext.onlineOwner(name, server.getPlayerList().getPlayers().stream()
                .map(player -> new NameAndId(player.getGameProfile())).toList());
        // The profile cache keeps no misses, so a name that is nobody's would ask Mojang's servers at every attach.
        if (online != null || !server.usesAuthentication() || unknownOwners.contains(name)) {
            done.accept(online);
            return;
        }
        List<Consumer<@Nullable UUID>> waiting = ownerLookupsByName.get(name);
        if (waiting != null) {
            waiting.add(done);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(done);
        ownerLookupsByName.put(name, waiting);
        CompletableFuture.supplyAsync(() -> server.services().nameToIdCache().get(name), Util.nonCriticalIoPool())
                .whenCompleteAsync((found, error) -> {
                    if (error != null) {
                        logOnce("owner-lookup:" + name, "Could not look up {}, the owner of v1 blocks; asking again at their next load", name, error);
                    } else if (found.isEmpty()) {
                        unknownOwners.add(name);
                        logOnce("owner:" + name, "No player is called {}, the owner of v1 blocks; they have no owner until one is set", name);
                    }
                    UUID owner = error == null ? found.map(NameAndId::id).orElse(null) : null;
                    ownerLookupsByName.remove(name).forEach(callback -> callback.accept(owner));
                }, server);
    }

    /**
     * From {@link BaseMqttBlockEntity#setLevel}: a block that is no device has its v1 owner name resolved to its owner
     * UUID on the next tick of its server (M2 plan D7), at every load until it is.
     */
    public static void scheduleOwnerLookup(ServerLevel level, BaseMqttBlockEntity block) {
        DeviceRuntime devices = of(level.getServer());
        if (devices != null) {
            if (!devices.stopping) {
                devices.ownerLookups.add(block);
                devices.runtime.workQueued();
            }
        } else {
            beforeStart.computeIfAbsent(level.getServer(), server -> new LinkedHashSet<>()).add(block);
        }
    }

    /**
     * Resolves the v1 owner name of a loaded block that is no device to its owner UUID now: the access check asks, so an
     * online owner of exactly that name is bound before their click is judged. Nothing happens while MineQTT is not
     * running on the block's server, or while a lookup for the block is under way.
     */
    public static void resolveLegacyOwner(BaseMqttBlockEntity block) {
        if (!(block.getLevel() instanceof ServerLevel level)) return;
        DeviceRuntime devices = of(level.getServer());
        if (devices != null && !devices.stopping) devices.lookUpLegacyOwner(block);
    }

    /** @return true: the block is done with, found or not; it asks again at its next load */
    private boolean lookUpLegacyOwner(BaseMqttBlockEntity block) {
        String name = block.getOwnerName();
        if (block.isRemoved() || block.getOwnerUuid() != null || name.isEmpty() || !resolvingLegacyOwners.add(block)) return true;
        lookUpOwner(name, owner -> {
            resolvingLegacyOwners.remove(block);
            if (owner != null && !stopping && !block.isRemoved() && block.ownerResolved(name, owner)) {
                MineQTT.LOGGER.info("Block at {} belongs to {} ({})", block.getBlockPos().toShortString(), name, owner);
            }
        });
        return true;
    }

    private void ownerResolved(UUID id, String name, UUID owner) {
        // The block entity owns the owner; an unloaded device asks again when it attaches.
        BlockEntity blockEntity = attached.get(id);
        if (stopping || blockEntity == null || registry.record(id) == null) return;
        DeviceHost host = requireHost(blockEntity);
        DeviceLink link = host.deviceLink();
        if (!name.equals(link.ownerName()) || !link.resolveOwner(owner)) return;
        blockEntity.setChanged();
        MineQTT.LOGGER.info("Device {} belongs to {} ({})", link.slug(), name, owner);
        // A grandfather flag that waited for its v1 owner now binds them; their links on that literal follow it. The
        // attach already settled the flag of untrusted data, so this adds nothing for it.
        settleGrandfathered(blockEntity, link, true);
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
        // A v1 remote waiting for an owner's UUID (M2 plan D15) compares it now.
        remotesChanged();
        // A link with a channel resolves for its new owner; any other is judged for them.
        RawLink before = host.deviceLink().raw();
        boolean published = false;
        if (reResolve(attached.get(record.id()), record, host)) {
            published = rawChanged(host, before);
        } else {
            refreshRaw(record);
            settle(record, host, true);
        }
        // An output publishes on change; what it published while it had no owner was refused.
        String rawValue = rawLevel(host);
        if (!published && rawValue != null) publishRaw(host, rawValue);
        // The state echo waited for the owner too.
        publishState(host, true);
    }

    private void onRemoved(BlockEntity blockEntity, DeviceLink.Removal removal) {
        attachQueue.remove(blockEntity);
        DeviceHost host = requireHost(blockEntity);
        UUID id = host.deviceLink().id();
        if (id == null || attached.get(id) != blockEntity) {
            // A faulted device that is broken goes like a vanished block: the check of its chunk forgets its record. One
            // that unloads stays faulted until its chunk loads again.
            GlobalPos pos = GlobalPos.of(blockEntity.getLevel().dimension(), blockEntity.getBlockPos());
            if (removal == DeviceLink.Removal.DESTROY && faults.remove(pos) != null && registry.at(pos) != null) {
                pruneQueue.add(ChunkRef.of(pos));
                runtime.workQueued();
            }
            return;
        }
        attached.remove(id);
        host.deviceLink().setAttached(false);
        closeRaw(id);
        rawProblems.forget(id);
        // A v1 remote not wired yet goes on at its next attach; its record keeps the topic.
        remotesToWire.remove(id);
        DeviceRecord record = registry.record(id);
        if (record == null) return;
        if (removal == DeviceLink.Removal.DESTROY) {
            // A retaining output leaves its topic off; an unload publishes nothing.
            host.deviceType().rawLink().ifPresent(spec -> publishRetainPlan(host, RawRetain.destroyed(host.deviceLink().raw(), spec)));
            forget(record, true);
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

    /**
     * Destroy: the record goes, with its group membership, its wires and its retained topics. The record of a block broken
     * in a loaded chunk stays in the file until the level save ({@link DeviceRegistry#removeBroken}); should its group be
     * gone by then, a crash restores it in none. A group made in the game goes with its last member
     * ({@link GroupRules#dropsWhenEmptied}), as when a player takes that member out.
     */
    private void forget(DeviceRecord record, boolean blockBroken) {
        BlockEntity live = attached.remove(record.id());
        if (live != null) requireHost(live).deviceLink().setAttached(false);
        faults.remove(record.pos());
        List<Wire> wires = registry.wiresOf(record.id());
        linksChanged(wires);
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        if (blockBroken) {
            registry.removeBroken(record.id());
        } else {
            registry.remove(record.id());
        }
        forgetWires(record.id(), wires);
        throttles.remove(record.id());
        poweredOn.remove(record.id());
        traffic.remove(record.id());
        closeRaw(record.id());
        rawProblems.forget(record.id());
        // M2 plan D15: a v1 remote that goes waits no more, and one that waited for this lamp to load is matched again.
        remotesToWire.remove(record.id());
        remotesChanged();
        runtime.bridgeInfoChanged();
        if (group != null) {
            groupsChanged();
            if (GroupRules.dropsWhenEmptied(group)) {
                deleteGroup(group);
            } else {
                // Under OPT_IN the group may have lost the exposed member it was discovered for.
                runtime.discovery().refresh(group);
            }
        }
        if (stopping) return;
        runtime.discovery().removed(record);
        mqtt().publish(Outbound.clear(topics().device(record.slug())));
        mqtt().publish(Outbound.clear(topics().availability(record.slug())));
    }

    // A device or group left the registry with its wires: the bus forgets what they carried and the node's port rates.
    private void forgetWires(UUID node, List<Wire> wires) {
        wires.forEach(wire -> bus.forgetWire(wire.id()));
        bus.forgetNode(node);
    }

    // A record the v1 topic states listed at a position whose device follows another topic. A legacy group that loses
    // its last member this way replaces no v1 light: it is deleted, which also clears that light's retained discovery.
    private void forgetImported(DeviceRecord record) {
        DeviceGroup group = record.group() == null ? null : registry.group(record.group());
        forget(record, false);
        if (group != null && group.legacyTopic() != null && group.members().isEmpty()) deleteGroup(group);
    }

    /**
     * Forgets a record whose block is gone from its loaded chunk, in isolation (M2 plan D7). A check that passes ends a
     * fault of an earlier one.
     *
     * @return whether the record was forgotten
     */
    private boolean pruneIfStale(DeviceRecord record, LevelChunk chunk) {
        return AttachQueue.isolate(record, checked -> {
            if (!isStale(checked, chunk)) {
                faults.computeIfPresent(checked.pos(), (pos, fault) -> fault.stage() == Stage.PRUNE ? null : fault);
                return false;
            }
            MineQTT.LOGGER.info("Forgetting device {} ({}): its block at {} is gone", checked.slug(), checked.type(), checked.pos());
            forget(checked, false);
            return true;
        }, (failed, e) -> fault(failed.pos(), Stage.PRUNE, e));
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

    /**
     * §4.2 step 3, on every CONNACK: a sensor that is not loaded, or a faulted device, must not keep a retained
     * {@code online}. Every retaining Raw Link output publishes its level again (M2 plan D2): a broker restarted without
     * persistence holds nothing.
     */
    private void connected() {
        for (DeviceRecord record : registry.records()) {
            DeviceType type = DeviceTypes.get(record.type());
            boolean offline = type != null && (type.unloadedPolicy() == UnloadedPolicy.UNAVAILABLE || faults.containsKey(record.pos()));
            if (offline && !attached.containsKey(record.id())) {
                mqtt().publish(Outbound.state(topics().availability(record.slug()), OFFLINE));
            }
        }
        attached.values().forEach(blockEntity -> publishLevel(requireHost(blockEntity)));
    }

    // Availability and state, sent even when unchanged. A faulted device stays offline.
    private void publishAll(DeviceRecord record, DeviceType type) {
        BlockEntity blockEntity = attached.get(record.id());
        if (blockEntity != null) {
            mqtt().publish(Outbound.state(topics().availability(record.slug()), ONLINE).asRefresh());
            publishState(requireHost(blockEntity), true);
        } else if (type.unloadedPolicy() == UnloadedPolicy.QUEUE && !faults.containsKey(record.pos())) {
            mqtt().publish(Outbound.state(topics().availability(record.slug()), ONLINE).asRefresh());
            if (record.lastState() != null) {
                publish(record.id(), Outbound.state(topics().device(record.slug()), effectiveState(record, type)).asRefresh());
            }
        } else {
            mqtt().publish(Outbound.state(topics().availability(record.slug()), OFFLINE).asRefresh());
        }
    }

    private void onCommand(Inbound message) {
        String root = commandTopics.root() + "/";
        if (!message.topic().startsWith(root)) return;
        String[] levels = message.topic().substring(root.length()).split("/", -1);
        boolean group = levels[0].equals("group");
        int slugIndex = group ? 1 : 0;
        // The filters overlap (<root>/group/set matches <root>/+/set, <root>/group/set/set two filters): only
        // <slug>/set[/<field>] and group/<slug>/set[/<field>] are commands, and a reserved slug (set too) names nothing.
        if (levels.length < slugIndex + 2 || levels.length > slugIndex + 3 || !levels[slugIndex + 1].equals("set")) return;
        String slug = levels[slugIndex];
        String field = levels.length > slugIndex + 2 ? levels[slugIndex + 2] : null;
        if (Slugs.RESERVED.contains(slug)) return;
        // An empty payload is someone clearing a retained command; it commands nothing, and is no malformed command.
        boolean cleared = message.payload().length == 0;
        Optional<JsonObject> command = cleared ? Optional.empty()
                : field == null ? message.json().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
                : Endpoint.isPortKey(field) ? Optional.of(single(field, ValueCodecs.plainValue(message.text()))) : Optional.empty();
        if (command.isEmpty() && !cleared) {
            logOnce("command:" + message.topic(), "Ignoring a command on {}: {}", message.topic(),
                    field == null ? "/set takes a JSON object, /set/<field> a plain value" : "invalid field name");
        }
        UUID target = group ? Optional.ofNullable(registry.groupBySlug(slug)).map(DeviceGroup::id).orElse(null)
                : Optional.ofNullable(registry.bySlug(slug)).map(DeviceRecord::id).orElse(null);
        if (target == null) return;
        if (command.isPresent()) {
            command(target, command.get(), CommandSource.HA, null, message);
        } else {
            ignored(target, message);
        }
    }

    /**
     * The one way a command reaches a device (M2 plan §3.8 #2, §6.6): Home Assistant's {@code /set} topics, groups,
     * Raw Link inputs, the screens and their command twins, wires, and later timers. A loaded device applies
     * it, an unloaded {@link UnloadedPolicy#QUEUE} device queues it through its type's preview, and an unloaded sensor
     * drops it. A group resolves the command's toggles against its own state, so every member gets the same target, fans
     * it out to its members as a {@link CommandSource#GROUP} command, whatever its own source, and publishes once.
     *
     * @param target the id of a device or a group
     * @param origin the device whose message this delivers (a wire's source end), which a group's fan-out of a
     *               {@link CommandSource#BINDING} command skips ({@link #skipsMember}); null for Home Assistant, the Raw
     *               Link, groups and players
     */
    public Delivery command(UUID target, JsonObject command, CommandSource source, @Nullable UUID origin) {
        return command(target, command, source, origin, null);
    }

    /**
     * {@link #command} of an MQTT message, which every device it reaches counts as received, and as dropped when it does
     * nothing there (the device refused it, or an unloaded sensor took no command), for its Diagnostics tab.
     *
     * @param message the message the command came in; null for a command from the game
     */
    private Delivery command(UUID target, JsonObject command, CommandSource source, @Nullable UUID origin, @Nullable Inbound message) {
        DeviceRecord record = registry.record(target);
        if (record != null) return deliver(record, DeviceTypes.get(record.type()), command, source, origin, message);
        DeviceGroup group = registry.group(target);
        if (group == null) return new Delivery(Route.DROP, null);
        DeviceType type = group.memberType() == null ? null : DeviceTypes.get(group.memberType());
        Route route = route(true, type, false);
        if (route == Route.FAN_OUT) fanOut(group, type, command, source, origin, message);
        return new Delivery(route, null);
    }

    /**
     * Whether a group's fan-out skips a member: a wire's message never goes back into the device it came from (M2 plan
     * §3.5), so a member wired to its own group does not echo its change into itself.
     *
     * @param origin the device whose message the command delivers; null for any other command
     */
    static boolean skipsMember(CommandSource source, @Nullable UUID origin, UUID member) {
        return source == CommandSource.BINDING && member.equals(origin);
    }

    /**
     * {@link #command}'s routing table, a pure function: a group with a known member type fans out (its members are
     * routed on their own), a loaded device applies, an unloaded {@link UnloadedPolicy#QUEUE} device queues, and the
     * rest drops: an unloaded sensor, and a device or group whose type this MineQTT does not know.
     *
     * @param type the device's type, or the group's member type; null when unknown, or for a group without a member type
     * @param attached whether the device is loaded; ignored for a group
     */
    static Route route(boolean group, @Nullable DeviceType type, boolean attached) {
        if (type == null) return Route.DROP;
        if (group) return Route.FAN_OUT;
        if (attached) return Route.APPLY;
        return type.unloadedPolicy() == UnloadedPolicy.QUEUE ? Route.QUEUE : Route.DROP;
    }

    private Delivery deliver(DeviceRecord record, @Nullable DeviceType type, JsonObject command, CommandSource source, @Nullable UUID origin,
                             @Nullable Inbound message) {
        if (message != null) received(record.id(), message);
        BlockEntity blockEntity = attached.get(record.id());
        Route route = route(false, type, blockEntity != null);
        if (route == Route.APPLY) {
            DeviceHost host = requireHost(blockEntity);
            CommandResult result = host.applyCommand(new CommandReader(command), source);
            switch (result.outcome()) {
                case APPLIED -> publishState(host, false);
                case REJECTED -> {
                    MineQTT.LOGGER.debug("Device {} rejected {} ({}{}): {}", record.slug(), command, source,
                            origin == null ? "" : " from " + origin, result.detail());
                    if (message != null) traffic(record.id()).dropped();
                }
                case UNCHANGED -> {
                }
            }
            return new Delivery(route, result);
        }
        if (route == Route.QUEUE) queue(record, type, command);
        if (route == Route.DROP && message != null) traffic(record.id()).dropped();
        return new Delivery(route, null);
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
        if (!stopping) publish(record.id(), Outbound.state(topics().device(record.slug()), target));
    }

    // §6.6 groups: command's fan-out to the members of a group whose member type is known.
    private void fanOut(DeviceGroup group, DeviceType type, JsonObject command, CommandSource source, @Nullable UUID origin, @Nullable Inbound message) {
        JsonObject resolved = type.resolveToggles(command, group.state());
        DeviceRecord representative = null;
        DeviceRecord skipped = null;
        for (UUID member : group.members()) {
            DeviceRecord record = registry.record(member);
            if (record == null) continue;
            if (skipsMember(source, origin, member)) {
                skipped = record;
                continue;
            }
            deliver(record, type, resolved, CommandSource.GROUP, origin, message);
            if (representative == null) representative = record;
        }
        // A wire's message that reached no member, as it came from the only one, leaves the group showing that member.
        if (representative == null) representative = skipped;
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
            // An empty payload clears a retained level; it commands nothing, also not as a custom payload.
            if (message.payload().length == 0) {
                ignored(id, message);
                return;
            }
            RawLink current = requireHost(blockEntity).deviceLink().raw();
            ValueCodecs.CustomPayloads custom = current == null ? ValueCodecs.CustomPayloads.NONE : current.customPayloads();
            Optional<JsonObject> command = spec.grammar().command(message.text(), custom);
            if (command.isPresent()) {
                command(id, command.get(), CommandSource.RAW, null, message);
            } else {
                ignored(id, message);
            }
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
        Optional<String> problem = rawProblem(record, link);
        if (problem.isPresent()) return raw.topic() + " refused: " + problem.get();
        if (followedByGroup(record, raw)) return raw.topic() + " via group " + registry.group(record.group()).slug();
        RawLinkSpec spec = link.type().rawLink().orElse(null);
        String retained = (spec != null && RawRetain.retains(raw, spec) ? ", retained" : "") + (raw.edges().level() ? "" : ", rising edges only");
        if (raw.grandfathered() == null) return raw.topic() + retained;
        // A grandfathered channel came from the owner's literal set; without a channel it is a v1 topic as adopted.
        return raw.topic() + (raw.channel() != null ? " (kept for its owner)" : " (v1, grandfathered)") + retained;
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
        publish(link.id(), echo(raw.echoTopic(), spec.echo(state), link.id()));
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
            // An empty payload clears a retained level and commands nothing, as on a device's own Raw Link.
            Optional<JsonObject> command = message.payload().length == 0 ? Optional.empty()
                    : spec.grammar().command(message.text(), ValueCodecs.CustomPayloads.NONE);
            if (command.isPresent()) {
                command(id, command.get(), CommandSource.RAW, null, message);
            } else {
                ignored(id, message);
            }
        }));
    }

    private void closeGroupRaw(UUID id) {
        Subscription subscription = groupRawSubscriptions.remove(id);
        if (subscription != null) subscription.close();
    }

    private Optional<String> rawProblem(DeviceRecord record, DeviceLink link) {
        RawLink raw = link.raw();
        if (raw == null || (raw.active() && followedByGroup(record, raw))) return Optional.empty();
        return RawTopics.problem(raw, runtime.acl(), AclContext.owner(runtime.server(), link.owner(), link.ownerName()));
    }

    // A Raw Link topic is checked like a player's publish, for the device's owner.
    private Optional<String> rawRefusal(DeviceLink link, RawLink raw) {
        return rawRefusal(AclContext.owner(runtime.server(), link.owner(), link.ownerName()), raw);
    }

    private Optional<String> rawRefusal(TopicAcl.Subject owner, RawLink raw) {
        return refusal(runtime.acl().checkPublish(owner, raw.topic(), raw.grandfathered()));
    }

    // A message to a device, or to every member of a group, that commands nothing: received and dropped.
    private void ignored(UUID target, Inbound message) {
        DeviceGroup group = registry.group(target);
        for (UUID id : group == null ? List.of(target) : group.members()) {
            if (registry.record(id) == null) continue;
            received(id, message);
            traffic(id).dropped();
        }
    }

    private void received(UUID id, Inbound message) {
        traffic(id).received(message.topic(), message.payload(), Util.getMillis());
        viewChanged(id);
    }

    // Publishes one of a device's own messages (its state, an event, its Raw Link level or its state echo) and keeps it.
    private void publish(UUID id, Outbound message) {
        mqtt().publish(message);
        traffic(id).sent(message.topic(), message.payload(), Util.getMillis());
        viewChanged(id);
    }

    private DeviceTraffic traffic(UUID id) {
        return traffic.computeIfAbsent(id, key -> new DeviceTraffic());
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

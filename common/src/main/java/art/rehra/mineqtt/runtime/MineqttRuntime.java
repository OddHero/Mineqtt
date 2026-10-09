package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.RegistryStore;
import art.rehra.mineqtt.ha.BirthWatcher;
import art.rehra.mineqtt.ha.BridgeDevice;
import art.rehra.mineqtt.ha.DiscoveryPublisher;
import art.rehra.mineqtt.ha.GhostCleanup;
import art.rehra.mineqtt.legacy.LegacySubscriptionFacade;
import art.rehra.mineqtt.legacy.LegacyTopicStateImport;
import art.rehra.mineqtt.mqtt.CyberdeckSessionManager;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.HiveMqttService;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.ui.console.BridgeConsole;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Everything MineQTT runs for one server: the world's device registry with its bridge identity, the devices, the
 * MQTT connection with the bridge's {@code state} and {@code info} topics, and the Home Assistant integration. It
 * exists from {@code SERVER_STARTED} to {@code SERVER_STOPPED} ({@link ServerHooks}); all instance methods run on the
 * server thread.
 * <p>
 * After every CONNACK (design §4.2): the bridge goes {@code online}, unloaded sensors {@code offline}, then discovery
 * is republished; one second after its last message availability and state follow, then the v1 discovery
 * migrations run. Meanwhile the ghost cleanup (§4.5) scans the retained discovery for five seconds. Home Assistant's
 * birth and the republish requests repeat discovery, availability and state.
 * <p>
 * An empty dedicated server that paused runs no ticks, so a {@link PausedServerPump} does their work between them
 * when MQTT messages arrive or timed work is due (M2 plan D5). Block entity tickers, scheduled ticks and light still
 * wait until the server resumes, unless {@code server.keepTickingWhenEmpty} keeps it from pausing at all.
 */
public final class MineqttRuntime {
    public static final String ONLINE = "online";
    public static final String OFFLINE = "offline";
    // §4.2 step 5: availability and state follow one second after the last discovery message.
    private static final long STATE_AFTER_DISCOVERY_MILLIS = 1_000;

    private static volatile MineqttRuntime running;

    private final MinecraftServer server;
    private final RegistryStore store;
    private final DeviceRegistry registry;
    private final MqttLog log = new Slf4jMqttLog(MineQTT.LOGGER);
    private final ReplaceableMqttService mqtt;
    private final DeviceRuntime devices;
    private final DiscoveryPublisher.Identity identity;
    private final DiscoveryPublisher discovery;
    private final GhostCleanup ghosts;
    private final BridgeDevice bridge;
    private final BirthWatcher birth;
    private final BridgeConsole console;
    private final boolean dedicated;
    private final PausedServerPump pump;
    // Written by the tick, read by the MQTT client threads and the wake thread.
    private volatile long lastTickNanos = System.nanoTime();
    private volatile long tickNanos;
    private volatile int playersAtLastTick;
    // Whether a pump would find work of the server thread; set by producers, recomputed exactly at every tick's end.
    private volatile boolean workPending;
    private boolean pauseLogged;
    // Whether server.keepTickingWhenEmpty overrode pause-when-empty-seconds when that was last logged.
    private boolean pauseOverrideLogged;
    // What the current connection was built from; read by its connect listener.
    private MineQTTConfig.ConnectionSettings connectedWith;
    private TopicScheme topics;
    private TopicAcl acl;
    private ProtocolVersion protocol;
    private String broker = "none";
    private RepublishStep republishStep = RepublishStep.NONE;
    private long stateDueMillis;
    private boolean migrateAfterState;
    private volatile boolean stopping;

    private enum RepublishStep {
        NONE,
        /** Discovery is being released; availability and state wait for it. */
        DISCOVERY,
        /** Availability and state are due at {@code stateDueMillis}. */
        STATE
    }

    private MineqttRuntime(MinecraftServer server, RegistryStore store, DeviceRegistry registry) {
        this.server = server;
        this.store = store;
        this.registry = registry;
        this.mqtt = new ReplaceableMqttService(createService());
        mqtt.onConnected(this::publishBridge);
        this.devices = new DeviceRuntime(this, registry);
        this.identity = new DiscoveryPublisher.Identity(server.getWorldData().getLevelName(), registry.worldUuid(),
                Platform.getMod(MineQTT.MOD_ID).getVersion());
        this.discovery = new DiscoveryPublisher(registry, mqtt, () -> topics, identity, server.isDedicatedServer(), Util::getMillis);
        this.ghosts = new GhostCleanup(mqtt, () -> MineQTTConfig.discoveryPrefix, identity.bridgeObjectId(),
                registry::contains, discovery::tryTakeToken, Util::getMillis, log);
        this.bridge = new BridgeDevice(mqtt, () -> topics, server::getPlayerCount, Util::getMillis, this::republish);
        this.birth = new BirthWatcher(mqtt, this::republish, Util::getMillis, RandomGenerator.getDefault());
        this.console = new BridgeConsole(this);
        // After the bridge's online and the devices' forced offline, which were registered first.
        mqtt.onConnected(this::connected);
        this.dedicated = server.isDedicatedServer();
        // Queued, never run in place: a wake from inside a slow tick's flush must not run the pump within it.
        this.pump = new PausedServerPump(task -> server.schedule(server.wrapRunnable(task)), this::pausedPump,
                () -> paused(playersAtLastTick), this::pendingWork, log, PausedServerPump.PERIOD_MILLIS);
        mqtt.onInboundAvailable(pump::wake);
    }

    /**
     * The runtime of {@code server}.
     *
     * @throws IllegalStateException when MineQTT is not running on that server (before {@code SERVER_STARTED} or
     *                               after {@code SERVER_STOPPED})
     */
    public static MineqttRuntime of(MinecraftServer server) {
        MineqttRuntime runtime = find(server);
        if (runtime == null) throw new IllegalStateException("MineQTT is not running on this server");
        return runtime;
    }

    /** The runtime of the server that is running, for code that has no server at hand. */
    public static Optional<MineqttRuntime> current() {
        return Optional.ofNullable(running);
    }

    /**
     * Any thread: applies a changed config on the server thread. The connection is rebuilt when the settings it was
     * built from changed; everything subscribed stays subscribed.
     */
    public static void configChanged() {
        MineqttRuntime runtime = running;
        if (runtime != null) runtime.server.execute(runtime::applyConfig);
    }

    static MineqttRuntime find(MinecraftServer server) {
        MineqttRuntime runtime = running;
        return runtime != null && runtime.server == server ? runtime : null;
    }

    /**
     * {@code SERVER_STARTED}: loads the device registry, which holds the world's bridge identity, and imports the v1
     * lamp states once, then connects. Both happen before the connection exists, so the broker can never overwrite them
     * (design D3).
     */
    static MineqttRuntime start(MinecraftServer server) {
        RegistryStore store = RegistryStore.of(server);
        DeviceRegistry registry = store.load(server.getWorldData().getLevelName(), RandomGenerator.getDefault());
        LegacyTopicStateImport.run(server, registry);
        store.saveIfDirty(registry);
        MineqttRuntime runtime = new MineqttRuntime(server, store, registry);
        // A server that never reached SERVER_STOPPING left its runtime behind.
        MineqttRuntime previous = running;
        if (previous != null) previous.pump.close();
        running = runtime;
        runtime.logPauseOverride();
        String incomplete = store.incompleteReason();
        if (incomplete != null) {
            MineQTT.LOGGER.warn("The Home Assistant ghost cleanup is off for this run: the device registry may lack devices that exist, as {}", incomplete);
        }
        return runtime;
    }

    public MinecraftServer server() {
        return server;
    }

    /** The service stays the same object for the whole server run, also when a config change reconnects it. */
    public MqttService mqtt() {
        return mqtt;
    }

    public TopicScheme topics() {
        return topics;
    }

    /** Checks every topic a player or an unmigrated block uses; follows config changes. */
    public TopicAcl acl() {
        return acl;
    }

    public DeviceRuntime devices() {
        return devices;
    }

    public DeviceRegistry registry() {
        return registry;
    }

    public DiscoveryPublisher discovery() {
        return discovery;
    }

    /** Follows Home Assistant's birth topic; the Bridge Console shows what it last heard there. */
    public BirthWatcher birth() {
        return birth;
    }

    /** The server side of every Bridge Console's screen. */
    public BridgeConsole console() {
        return console;
    }

    public ProtocolVersion protocol() {
        return protocol;
    }

    /** The configured broker for {@code /mineqtt status}, credentials masked; "none" when MQTT is disabled. */
    public String broker() {
        return broker;
    }

    /** Whether the bridge id is the generated one, not set in the config. */
    public boolean bridgeIdGenerated() {
        return MineQTTConfig.bridgeId.isEmpty();
    }

    /**
     * The {@code pause-when-empty-seconds} a dedicated server pauses after: 0 (never pauses) when
     * {@code server.keepTickingWhenEmpty} overrides it, and on any other server.
     */
    public int pauseWhenEmptySeconds() {
        return pauseOverridden() ? 0 : configuredPauseWhenEmptySeconds();
    }

    /** {@code pause-when-empty-seconds} as {@code server.properties} sets it; 0 on any other server. */
    public int configuredPauseWhenEmptySeconds() {
        return dedicated ? ((DedicatedServer) server).pauseWhenEmptySeconds() : 0;
    }

    /**
     * Whether {@code server.keepTickingWhenEmpty} keeps this server ticking although {@code server.properties} would
     * pause it. Its mixin is server-only, so the client game test's in-process dedicated server never applies it.
     */
    public boolean pauseOverridden() {
        return MineQTTConfig.serverKeepTickingWhenEmpty && Platform.getEnv() == EnvType.SERVER && configuredPauseWhenEmptySeconds() > 0;
    }

    /** Server thread: whether the server is paused for being empty, so MQTT is handled between ticks. */
    public boolean pausedWhileEmpty() {
        return paused(server.getPlayerCount());
    }

    /**
     * Any thread: something was queued for the server thread outside a tick (a command, a device placed or a chunk
     * loaded), so a paused server is pumped for it.
     */
    public void workQueued() {
        workPending = true;
    }

    /** {@code /mineqtt reconnect}: closes the connection and builds it again from the current config. */
    public void reconnect() {
        if (stopping) return;
        MineQTT.LOGGER.info("Reconnecting to MQTT: {}", MineQTTConfig.connectionSettings());
        mqtt.replace(this::createService);
        devices.topicsChanged();
        bridge.topicsChanged();
    }

    /**
     * Discovery, availability and state of the bridge and every device and group, as after a connect but without the
     * v1 migrations: Home Assistant's birth, {@code bridge/request/republish} and {@code /mineqtt discovery republish}.
     */
    public void republish() {
        startRepublish(false);
    }

    /** The device count in {@code bridge/info} changed. */
    public void bridgeInfoChanged() {
        if (!stopping && mqtt.state().isConnected()) mqtt.publish(Outbound.state(topics.bridgeInfo(), bridgeInfo()));
    }

    /**
     * {@code SERVER_PRE}: attaches devices, runs the handlers of the messages received since the last tick, then carries
     * what they emitted over the devices' wires.
     */
    void tickStart() {
        lastTickNanos = System.nanoTime();
        tickNanos = server.tickRateManager().nanosecondsPerTick();
        playersAtLastTick = server.getPlayerCount();
        pauseLogged = false;
        handleInbound();
    }

    /**
     * {@code SERVER_POST}, and the end of a pump: discovery, ghost clears and telemetry within their limits, the steps
     * after a connect, the devices' end of tick (the message bus drain among it), the open Bridge Consoles (once a
     * second), the save of a structural registry change (at most once a second), then the flush.
     */
    void tickEnd() {
        birth.tick();
        discovery.tick();
        ghosts.tick();
        long now = Util.getMillis();
        if (republishStep == RepublishStep.DISCOVERY && discovery.idle()) {
            republishStep = RepublishStep.STATE;
            stateDueMillis = now + STATE_AFTER_DISCOVERY_MILLIS;
        } else if (republishStep == RepublishStep.STATE && now >= stateDueMillis) {
            republishStep = RepublishStep.NONE;
            devices.republish();
            if (migrateAfterState) discovery.startMigrations();
            migrateAfterState = false;
        }
        bridge.tick();
        devices.tickEnd();
        console.tick();
        store.saveStructure(registry);
        mqtt.flushOutbound();
        workPending = ownerWorkPending();
    }

    /** {@code SERVER_LEVEL_SAVE} of the overworld (autosave, {@code /save-all}): saves the registry if it changed. */
    void levelSaved(ServerLevel level) {
        if (level.dimension() == Level.OVERWORLD) store.saveIfDirty(registry);
    }

    /**
     * {@code SERVER_STOPPING}: publishes the bridge's retained {@code offline}, waits up to 2 s for it and
     * disconnects. Later publishes and subscriptions (block entities saved during the shutdown) do nothing.
     */
    void stop() {
        stopping = true;
        pump.close();
        devices.stop();
        discovery.stop();
        ghosts.stop();
        bridge.stop();
        birth.stop();
        mqtt.close();
    }

    /** {@code SERVER_STOPPED}: saves the registry with what the final chunk saves changed. */
    void stopped() {
        pump.close();
        store.saveIfDirty(registry);
        if (running == this) running = null;
    }

    // Attaches, then the inbound messages, then what they emitted on the devices' wires (M2 plan §3.3 drain point 1).
    private void handleInbound() {
        devices.tickStart();
        mqtt.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
        devices.drainBus();
    }

    /**
     * The pump task on a paused server: the work of {@link #tickStart()} and {@link #tickEnd()}. It is a task of the main
     * loop, so it never runs during a tick, and a tick that started since the wake makes it do nothing.
     */
    private void pausedPump() {
        if (running != this || !pausedWhileEmpty()) return;
        if (!pauseLogged) {
            pauseLogged = true;
            MineQTT.LOGGER.info("Server paused while empty (pause-when-empty-seconds={}): MQTT is handled between ticks; "
                    + "scheduled ticks and light updates wait until it resumes", pauseWhenEmptySeconds());
        }
        handleInbound();
        tickEnd();
    }

    /** At the start, and when a config change turned {@code server.keepTickingWhenEmpty} on or off for this server. */
    private void logPauseOverride() {
        boolean overridden = pauseOverridden();
        if (overridden == pauseOverrideLogged) return;
        pauseOverrideLogged = overridden;
        if (overridden) {
            MineQTT.LOGGER.info("server.keepTickingWhenEmpty overrides pause-when-empty-seconds={}: the server keeps ticking with nobody online",
                    configuredPauseWhenEmptySeconds());
        } else {
            MineQTT.LOGGER.info("server.keepTickingWhenEmpty no longer overrides pause-when-empty-seconds={}", configuredPauseWhenEmptySeconds());
        }
    }

    private boolean paused(int players) {
        return PausedServerPump.shouldPump(dedicated, pauseWhenEmptySeconds(), players, lastTickNanos, tickNanos, System.nanoTime(), stopping);
    }

    /** Any thread: whether a pump would have something to do. */
    private boolean pendingWork() {
        return workPending || mqtt.hasPendingWork();
    }

    /**
     * Server thread: the work a pump would do besides the MQTT queues: attaches, prunes, held states and wire messages of
     * devices, discovery and the v1 migrations (only while connected, a CONNACK wakes them), the ghost cleanup's scan and
     * clears, the steps after a connect, the birth delay and a structural registry change still to save. The bridge
     * telemetry is not counted; any pump samples it.
     */
    private boolean ownerWorkPending() {
        boolean connected = mqtt.state().isConnected();
        return devices.hasPendingWork() || birth.pending() || ghosts.pending() || store.structureSavePending(registry)
                || connected && discovery.hasPendingWork()
                || republishStep == RepublishStep.STATE || republishStep == RepublishStep.DISCOVERY && (connected || discovery.idle());
    }

    private void applyConfig() {
        if (stopping || running != this) return;
        TopicAcl.Rules aclBefore = acl.rules();
        if (MineQTTConfig.connectionSettings().equals(connectedWith)) {
            // The ACL keys never need a reconnect.
            acl = createAcl();
        } else {
            MineQTT.LOGGER.info("MQTT connection settings changed, reconnecting: {}", MineQTTConfig.connectionSettings());
            mqtt.replace(this::createService);
            devices.topicsChanged();
            bridge.topicsChanged();
        }
        // Raw Links and legacy subscriptions follow the new ACL without a restart (M2 plan D1). A save that left the ACL
        // as it was (another key, or NeoForge's second reload of the same save) judges nothing again.
        if (!acl.rules().equals(aclBefore)) {
            devices.aclChanged();
            LegacySubscriptionFacade.aclChanged();
        }
        discovery.configChanged();
        birth.configChanged();
        CyberdeckSessionManager.scopeChanged(server);
        logPauseOverride();
        // A reload from NeoForge's file watcher runs between the ticks of a paused server too, and the discovery refresh
        // it queued publishes nothing yet.
        workQueued();
    }

    /** A connected service, or a disabled one (local loopback only) when no valid broker is configured. */
    private MqttService createService() {
        connectedWith = MineQTTConfig.connectionSettings();
        topics = new TopicScheme(MineQTTConfig.baseTopic,
                MineQTTConfig.bridgeId.isEmpty() ? registry.generatedBridgeId() : MineQTTConfig.bridgeId);
        acl = createAcl();
        protocol = MineQTTConfig.protocol;
        broker = "none";
        HiveMqttService.Options options = HiveMqttService.Options.defaults(Outbound.state(topics.bridgeState(), OFFLINE));
        try {
            Optional<BrokerConfig> config = MineQTTConfig.brokerConfig("mineqtt_" + topics.bridgeId());
            if (config.isPresent()) {
                broker = config.get().redacted();
                MineQTT.LOGGER.info("MQTT bridge {} publishes under {}/{}", topics.bridgeId(), topics.base(), topics.bridgeId());
                return HiveMqttService.connect(config.get(), options, log);
            }
            MineQTT.LOGGER.warn("MQTT is disabled: no broker is configured. Set \"broker\" in {}", MineQTT.getConfigHandler().configPath());
        } catch (IllegalArgumentException e) {
            MineQTT.LOGGER.error("MQTT is disabled: invalid broker settings in {}: {}", MineQTT.getConfigHandler().configPath(), e.getMessage());
        } catch (RuntimeException e) {
            // Never leave the runtime without a service: the replaced one is already closed.
            MineQTT.LOGGER.error("MQTT is disabled: the connection could not be set up", e);
        }
        return HiveMqttService.disabled(options, log);
    }

    private TopicAcl createAcl() {
        boolean dedicated = server.isDedicatedServer();
        // There is no statestream base before the twin (M3).
        return new TopicAcl(new TopicAcl.Rules(topics.base(), topics.bridgeId(), MineQTTConfig.discoveryPrefix, null,
                MineQTTConfig.rawAllow(topics.bridgeId()), MineQTTConfig.opsBypassAllowlist(dedicated), MineQTTConfig.aclExplorerBrokerWide));
    }

    /**
     * §4.2 steps 4-6 and the ghost cleanup (§4.5) after every CONNACK, once the bridge is online and unloaded sensors
     * are offline.
     */
    private void connected() {
        discovery.connected();
        if (store.incompleteReason() == null) ghosts.connected();
        startRepublish(true);
    }

    private void startRepublish(boolean afterConnect) {
        if (stopping) return;
        discovery.republishAll();
        bridge.publishTelemetry();
        republishStep = RepublishStep.DISCOVERY;
        migrateAfterState |= afterConnect;
    }

    /** After every CONNACK: the bridge is online, and its info may have changed while it was not. */
    private void publishBridge() {
        mqtt.publish(Outbound.state(topics.bridgeState(), ONLINE));
        mqtt.publish(Outbound.state(topics.bridgeInfo(), bridgeInfo()));
    }

    private JsonObject bridgeInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("version", identity.version());
        info.addProperty("mc", Platform.getMinecraftVersion());
        info.addProperty("loader", Platform.isFabric() ? "fabric" : "neoforge");
        info.addProperty("world", identity.worldName());
        info.addProperty("worldUuid", registry.worldUuid().toString());
        info.addProperty("protocol", protocol.configValue());
        info.addProperty("devices", registry.records().size());
        return info;
    }
}

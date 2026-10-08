package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.RegistryStore;
import art.rehra.mineqtt.ha.BirthWatcher;
import art.rehra.mineqtt.ha.BridgeDevice;
import art.rehra.mineqtt.ha.DiscoveryPublisher;
import art.rehra.mineqtt.legacy.LegacyTopicStateImport;
import art.rehra.mineqtt.mqtt.CyberdeckSessionManager;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.HiveMqttService;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import net.minecraft.server.MinecraftServer;
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
 * migrations run. Home Assistant's birth and the republish requests repeat discovery, availability and state.
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
    private final BridgeDevice bridge;
    private final BirthWatcher birth;
    // What the current connection was built from; read by its connect listener.
    private MineQTTConfig.ConnectionSettings connectedWith;
    private TopicScheme topics;
    private TopicAcl acl;
    private ProtocolVersion protocol;
    private String broker = "none";
    private RepublishStep republishStep = RepublishStep.NONE;
    private long stateDueMillis;
    private boolean migrateAfterState;
    private boolean stopping;

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
        this.bridge = new BridgeDevice(mqtt, () -> topics, server::getPlayerCount, Util::getMillis, this::republish);
        this.birth = new BirthWatcher(mqtt, this::republish, Util::getMillis, RandomGenerator.getDefault());
        // After the bridge's online and the devices' forced offline, which were registered first.
        mqtt.onConnected(this::connected);
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
        running = runtime;
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

    /** {@code SERVER_PRE}: attaches devices, then runs the handlers of the messages received since the last tick. */
    void tickStart() {
        devices.tickStart();
        mqtt.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
    }

    /** {@code SERVER_POST}: discovery and telemetry within their limits, the steps after a connect, then the flush. */
    void tickEnd() {
        birth.tick();
        discovery.tick();
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
        mqtt.flushOutbound();
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
        devices.stop();
        discovery.stop();
        bridge.stop();
        birth.stop();
        mqtt.close();
    }

    /** {@code SERVER_STOPPED}: saves the registry with what the final chunk saves changed. */
    void stopped() {
        store.saveIfDirty(registry);
        if (running == this) running = null;
    }

    private void applyConfig() {
        if (stopping || running != this) return;
        if (MineQTTConfig.connectionSettings().equals(connectedWith)) {
            // The ACL keys never need a reconnect.
            acl = createAcl();
        } else {
            MineQTT.LOGGER.info("MQTT connection settings changed, reconnecting: {}", MineQTTConfig.connectionSettings());
            mqtt.replace(this::createService);
            devices.topicsChanged();
            bridge.topicsChanged();
        }
        discovery.configChanged();
        birth.configChanged();
        CyberdeckSessionManager.scopeChanged(server);
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

    /** §4.2 steps 4-6 after every CONNACK, once the bridge is online and unloaded sensors are offline. */
    private void connected() {
        discovery.connected();
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

package art.rehra.mineqtt.fabric.config;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.AutoBoolean;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public class MineQTTConfigScreen {
    private static final String[] PROTOCOLS = {ProtocolVersion.MQTT_5.configValue(), ProtocolVersion.MQTT_3_1_1.configValue()};

    public static Screen createConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.mineqtt.title"))
                .setSavingRunnable(() -> {
                    // The entries check each value alone; rules across values (the bridge id under the base topic) apply here.
                    MineQTTConfig.sanitize().forEach(problem -> MineQTT.LOGGER.warn("Config: {}", problem));
                    MineQTT.getConfigHandler().saveConfig();
                    // An integrated server reconnects when the connection settings changed.
                    MineqttRuntime.configChanged();
                });

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // MQTT Connection Category
        ConfigCategory connectionCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.connection"));

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.broker"), MineQTTConfig.broker)
                .setDefaultValue(StaticDefaults.DEFAULT_BROKER)
                .setTooltip(Component.translatable("config.mineqtt.broker.tooltip"))
                .setErrorSupplier(value -> ConfigValidation.brokerError(value).map(Component::literal))
                .setSaveConsumer(value -> MineQTTConfig.broker = value.strip())
                .build());

        connectionCategory.addEntry(entryBuilder.startSelector(Component.translatable("config.mineqtt.protocol"), PROTOCOLS, MineQTTConfig.protocol.configValue())
                .setDefaultValue(StaticDefaults.DEFAULT_PROTOCOL.configValue())
                .setTooltip(Component.translatable("config.mineqtt.protocol.tooltip"))
                .setNameProvider(Component::literal)
                .setSaveConsumer(value -> MineQTTConfig.protocol = ProtocolVersion.parse(value))
                .build());

        connectionCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.keep_alive_sec"), MineQTTConfig.keepAliveSec)
                .setDefaultValue(StaticDefaults.DEFAULT_KEEP_ALIVE_SEC)
                .setTooltip(Component.translatable("config.mineqtt.keep_alive_sec.tooltip"))
                .setMin(0)
                .setMax(65_535)
                .setSaveConsumer(value -> MineQTTConfig.keepAliveSec = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.username"), MineQTTConfig.username)
                .setDefaultValue(StaticDefaults.DEFAULT_USERNAME)
                .setTooltip(Component.translatable("config.mineqtt.username.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.username = value)
                .build());

        boolean passwordFromEnvironment = MineQTTConfig.passwordFromEnvironment();
        Component passwordTooltip = passwordFromEnvironment
                ? Component.translatable("config.mineqtt.password.environment")
                : Component.translatable("config.mineqtt.password.tooltip");
        PasswordListEntry passwordEntry = new PasswordListEntry(Component.translatable("config.mineqtt.password"), MineQTTConfig.password,
                entryBuilder.getResetButtonKey(), () -> StaticDefaults.DEFAULT_PASSWORD, value -> MineQTTConfig.password = value,
                () -> Optional.of(new Component[]{passwordTooltip}));
        passwordEntry.setEditable(!passwordFromEnvironment);
        connectionCategory.addEntry(passwordEntry);

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.tls_ca_file"), MineQTTConfig.tlsCaFile)
                .setDefaultValue(StaticDefaults.DEFAULT_TLS_CA_FILE)
                .setTooltip(Component.translatable("config.mineqtt.tls_ca_file.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.tlsCaFile = value.strip())
                .build());

        connectionCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.tls_insecure_skip_verify"), MineQTTConfig.tlsInsecureSkipVerify)
                .setDefaultValue(StaticDefaults.DEFAULT_TLS_INSECURE_SKIP_VERIFY)
                .setTooltip(Component.translatable("config.mineqtt.tls_insecure_skip_verify.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.tlsInsecureSkipVerify = value)
                .build());

        // Topics and Home Assistant Category
        ConfigCategory topicCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.topics"));

        topicCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.bridge_id"), MineQTTConfig.bridgeId)
                .setDefaultValue(StaticDefaults.DEFAULT_BRIDGE_ID)
                .setTooltip(Component.translatable("config.mineqtt.bridge_id.tooltip"))
                .setErrorSupplier(error(ConfigValidation::isValidBridgeId, "config.mineqtt.error.bridge_id"))
                .setSaveConsumer(value -> MineQTTConfig.bridgeId = value)
                .build());

        topicCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.base_topic"), MineQTTConfig.baseTopic)
                .setDefaultValue(StaticDefaults.DEFAULT_BASE_TOPIC)
                .setTooltip(Component.translatable("config.mineqtt.base_topic.tooltip"))
                .setErrorSupplier(error(ConfigValidation::isValidConfigTopic, "config.mineqtt.error.topic"))
                .setSaveConsumer(value -> MineQTTConfig.baseTopic = value)
                .build());

        topicCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.discovery_prefix"), MineQTTConfig.discoveryPrefix)
                .setDefaultValue(StaticDefaults.DEFAULT_DISCOVERY_PREFIX)
                .setTooltip(Component.translatable("config.mineqtt.discovery_prefix.tooltip"))
                .setErrorSupplier(error(ConfigValidation::isValidConfigTopic, "config.mineqtt.error.topic"))
                .setSaveConsumer(value -> MineQTTConfig.discoveryPrefix = value)
                .build());

        topicCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.ha_birth_topic"), MineQTTConfig.haBirthTopic)
                .setDefaultValue(StaticDefaults.DEFAULT_HA_BIRTH_TOPIC)
                .setTooltip(Component.translatable("config.mineqtt.ha_birth_topic.tooltip"))
                .setErrorSupplier(error(ConfigValidation::isValidConfigTopic, "config.mineqtt.error.topic"))
                .setSaveConsumer(value -> MineQTTConfig.haBirthTopic = value)
                .build());

        topicCategory.addEntry(entryBuilder.startEnumSelector(Component.translatable("config.mineqtt.ha_expose"), HaExpose.class, MineQTTConfig.haExpose)
                .setDefaultValue(StaticDefaults.DEFAULT_HA_EXPOSE)
                .setTooltip(Component.translatable("config.mineqtt.ha_expose.tooltip"))
                .setEnumNameProvider(value -> Component.translatable("config.mineqtt.ha_expose." + value.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(value -> MineQTTConfig.haExpose = value)
                .build());

        // Security Category
        ConfigCategory securityCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.security"));

        securityCategory.addEntry(entryBuilder.startStrList(Component.translatable("config.mineqtt.acl_raw_allow"), MineQTTConfig.aclRawAllow)
                .setDefaultValue(StaticDefaults.DEFAULT_ACL_RAW_ALLOW)
                .setTooltip(Component.translatable("config.mineqtt.acl_raw_allow.tooltip"))
                .setCellErrorSupplier(error(ConfigValidation::isValidRawAllowFilter, "config.mineqtt.error.filter"))
                .setSaveConsumer(value -> MineQTTConfig.aclRawAllow = value.stream().map(String::strip).filter(filter -> !filter.isEmpty()).toList())
                .build());

        securityCategory.addEntry(entryBuilder.startEnumSelector(Component.translatable("config.mineqtt.acl_ops_bypass_allowlist"), AutoBoolean.class, MineQTTConfig.aclOpsBypassAllowlist)
                .setDefaultValue(StaticDefaults.DEFAULT_ACL_OPS_BYPASS_ALLOWLIST)
                .setTooltip(Component.translatable("config.mineqtt.acl_ops_bypass_allowlist.tooltip"))
                .setEnumNameProvider(value -> Component.translatable("config.mineqtt.auto_boolean." + value.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(value -> MineQTTConfig.aclOpsBypassAllowlist = value)
                .build());

        securityCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.acl_explorer_broker_wide"), MineQTTConfig.aclExplorerBrokerWide)
                .setDefaultValue(StaticDefaults.DEFAULT_ACL_EXPLORER_BROKER_WIDE)
                .setTooltip(Component.translatable("config.mineqtt.acl_explorer_broker_wide.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.aclExplorerBrokerWide = value)
                .build());

        securityCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.block_placer_enabled"), MineQTTConfig.blockPlacerEnabled)
                .setDefaultValue(StaticDefaults.DEFAULT_BLOCK_PLACER_ENABLED)
                .setTooltip(Component.translatable("config.mineqtt.block_placer_enabled.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.blockPlacerEnabled = value)
                .build());

        securityCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.limits_max_devices_per_player"), MineQTTConfig.limitsMaxDevicesPerPlayer)
                .setDefaultValue(StaticDefaults.DEFAULT_LIMITS_MAX_DEVICES_PER_PLAYER)
                .setTooltip(Component.translatable("config.mineqtt.limits_max_devices_per_player.tooltip"))
                .setMin(0)
                .setSaveConsumer(value -> MineQTTConfig.limitsMaxDevicesPerPlayer = value)
                .build());

        securityCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.limits_max_wires_per_player"), MineQTTConfig.limitsMaxWiresPerPlayer)
                .setDefaultValue(StaticDefaults.DEFAULT_LIMITS_MAX_WIRES_PER_PLAYER)
                .setTooltip(Component.translatable("config.mineqtt.limits_max_wires_per_player.tooltip"))
                .setMin(0)
                .setSaveConsumer(value -> MineQTTConfig.limitsMaxWiresPerPlayer = value)
                .build());

        // Home Assistant Twin Category (M3 plan D13)
        ConfigCategory twinCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.twin"));

        twinCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.twin_statestream_base"), MineQTTConfig.twinStatestreamBase)
                .setDefaultValue(StaticDefaults.DEFAULT_TWIN_STATESTREAM_BASE)
                .setTooltip(Component.translatable("config.mineqtt.twin_statestream_base.tooltip"))
                .setErrorSupplier(value -> ConfigValidation.statestreamBaseError(ConfigValidation.normalizeStatestreamBase(value),
                        MineQTTConfig.discoveryPrefix, MineQTTConfig.baseTopic).map(Component::literal))
                .setSaveConsumer(value -> MineQTTConfig.twinStatestreamBase = ConfigValidation.normalizeStatestreamBase(value))
                .build());

        twinCategory.addEntry(entryBuilder.startStrList(Component.translatable("config.mineqtt.twin_allowed_entities"), MineQTTConfig.twinAllowedEntities)
                .setDefaultValue(StaticDefaults.DEFAULT_TWIN_ALLOWED_ENTITIES)
                .setTooltip(Component.translatable("config.mineqtt.twin_allowed_entities.tooltip"))
                .setCellErrorSupplier(value -> ConfigValidation.allowedEntityError(value).map(problem -> Component.translatable("config.mineqtt.error.allowed_entity")))
                .setSaveConsumer(value -> MineQTTConfig.twinAllowedEntities = value.stream().map(String::strip).filter(entry -> !entry.isEmpty()).toList())
                .build());

        twinCategory.addEntry(entryBuilder.startStrList(Component.translatable("config.mineqtt.twin_ha_action_allowlist"), MineQTTConfig.twinHaActionAllowlist)
                .setDefaultValue(StaticDefaults.DEFAULT_TWIN_HA_ACTION_ALLOWLIST)
                .setTooltip(Component.translatable("config.mineqtt.twin_ha_action_allowlist.tooltip"))
                .setCellErrorSupplier(error(ConfigValidation::isValidHaActionDomain, "config.mineqtt.error.ha_action_domain"))
                .setSaveConsumer(value -> MineQTTConfig.twinHaActionAllowlist = value.stream().map(String::strip).filter(domain -> !domain.isEmpty()).toList())
                .build());

        twinCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.twin_max_requests_per_second"), MineQTTConfig.twinMaxRequestsPerSecond)
                .setDefaultValue(StaticDefaults.DEFAULT_TWIN_MAX_REQUESTS_PER_SECOND)
                .setTooltip(Component.translatable("config.mineqtt.twin_max_requests_per_second.tooltip"))
                .setMin(1)
                .setMax(StaticDefaults.MAX_TWIN_REQUESTS_PER_SECOND)
                .setSaveConsumer(value -> MineQTTConfig.twinMaxRequestsPerSecond = value)
                .build());

        twinCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.twin_catalog"), MineQTTConfig.twinCatalog)
                .setDefaultValue(StaticDefaults.DEFAULT_TWIN_CATALOG)
                .setTooltip(Component.translatable("config.mineqtt.twin_catalog.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.twinCatalog = value)
                .build());

        twinCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.twin_catalog_max"), MineQTTConfig.twinCatalogMax)
                .setDefaultValue(StaticDefaults.DEFAULT_TWIN_CATALOG_MAX)
                .setTooltip(Component.translatable("config.mineqtt.twin_catalog_max.tooltip"))
                .setMin(1)
                .setMax(StaticDefaults.MAX_TWIN_CATALOG_MAX)
                .setSaveConsumer(value -> MineQTTConfig.twinCatalogMax = value)
                .build());

        // World Category (M4 plan D16): telemetry, players, the Block Placer and Observer, rooms
        ConfigCategory worldCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.world"));

        worldCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.telemetry_enabled"), MineQTTConfig.telemetryEnabled)
                .setDefaultValue(StaticDefaults.DEFAULT_TELEMETRY_ENABLED)
                .setTooltip(Component.translatable("config.mineqtt.telemetry_enabled.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.telemetryEnabled = value)
                .build());

        worldCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.telemetry_interval_seconds"), MineQTTConfig.telemetryIntervalSeconds)
                .setDefaultValue(StaticDefaults.DEFAULT_TELEMETRY_INTERVAL_SECONDS)
                .setTooltip(Component.translatable("config.mineqtt.telemetry_interval_seconds.tooltip"))
                .setMin(StaticDefaults.MIN_TELEMETRY_INTERVAL_SECONDS)
                .setMax(StaticDefaults.MAX_TELEMETRY_INTERVAL_SECONDS)
                .setSaveConsumer(value -> MineQTTConfig.telemetryIntervalSeconds = value)
                .build());

        worldCategory.addEntry(entryBuilder.startEnumSelector(Component.translatable("config.mineqtt.telemetry_players"), TelemetryPlayers.class, MineQTTConfig.telemetryPlayers)
                .setDefaultValue(StaticDefaults.DEFAULT_TELEMETRY_PLAYERS)
                .setTooltip(Component.translatable("config.mineqtt.telemetry_players.tooltip"))
                .setEnumNameProvider(value -> Component.translatable("config.mineqtt.telemetry_players." + value.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(value -> MineQTTConfig.telemetryPlayers = value)
                .build());

        worldCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.telemetry_publish_player_names"), MineQTTConfig.telemetryPublishPlayerNames)
                .setDefaultValue(StaticDefaults.DEFAULT_TELEMETRY_PUBLISH_PLAYER_NAMES)
                .setTooltip(Component.translatable("config.mineqtt.telemetry_publish_player_names.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.telemetryPublishPlayerNames = value)
                .build());

        worldCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.telemetry_allow_broadcast"), MineQTTConfig.telemetryAllowBroadcast)
                .setDefaultValue(StaticDefaults.DEFAULT_TELEMETRY_ALLOW_BROADCAST)
                .setTooltip(Component.translatable("config.mineqtt.telemetry_allow_broadcast.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.telemetryAllowBroadcast = value)
                .build());

        worldCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.telemetry_max_presence_devices"), MineQTTConfig.telemetryMaxPresenceDevices)
                .setDefaultValue(StaticDefaults.DEFAULT_TELEMETRY_MAX_PRESENCE_DEVICES)
                .setTooltip(Component.translatable("config.mineqtt.telemetry_max_presence_devices.tooltip"))
                .setMin(StaticDefaults.MIN_TELEMETRY_MAX_PRESENCE_DEVICES)
                .setMax(StaticDefaults.MAX_TELEMETRY_MAX_PRESENCE_DEVICES)
                .setSaveConsumer(value -> MineQTTConfig.telemetryMaxPresenceDevices = value)
                .build());

        worldCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.telemetry_forget_after_days"), MineQTTConfig.telemetryForgetAfterDays)
                .setDefaultValue(StaticDefaults.DEFAULT_TELEMETRY_FORGET_AFTER_DAYS)
                .setTooltip(Component.translatable("config.mineqtt.telemetry_forget_after_days.tooltip"))
                .setMin(0)
                .setMax(StaticDefaults.MAX_TELEMETRY_FORGET_AFTER_DAYS)
                .setSaveConsumer(value -> MineQTTConfig.telemetryForgetAfterDays = value)
                .build());

        worldCategory.addEntry(entryBuilder.startEnumSelector(Component.translatable("config.mineqtt.block_placer_players_may_configure"), AutoBoolean.class,
                        MineQTTConfig.blockPlacerPlayersMayConfigure)
                .setDefaultValue(StaticDefaults.DEFAULT_BLOCK_PLACER_PLAYERS_MAY_CONFIGURE)
                .setTooltip(Component.translatable("config.mineqtt.block_placer_players_may_configure.tooltip"))
                .setEnumNameProvider(value -> Component.translatable("config.mineqtt.auto_boolean." + value.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(value -> MineQTTConfig.blockPlacerPlayersMayConfigure = value)
                .build());

        worldCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.block_placer_consume_items"), MineQTTConfig.blockPlacerConsumeItems)
                .setDefaultValue(StaticDefaults.DEFAULT_BLOCK_PLACER_CONSUME_ITEMS)
                .setTooltip(Component.translatable("config.mineqtt.block_placer_consume_items.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.blockPlacerConsumeItems = value)
                .build());

        worldCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.block_placer_container_unlocks"),
                        MineQTTConfig.blockPlacerContainerUnlocks)
                .setDefaultValue(StaticDefaults.DEFAULT_BLOCK_PLACER_CONTAINER_UNLOCKS)
                .setTooltip(Component.translatable("config.mineqtt.block_placer_container_unlocks.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.blockPlacerContainerUnlocks = value)
                .build());

        worldCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.block_observer_export_block_entity_data"),
                        MineQTTConfig.blockObserverExportBlockEntityData)
                .setDefaultValue(StaticDefaults.DEFAULT_BLOCK_OBSERVER_EXPORT_BLOCK_ENTITY_DATA)
                .setTooltip(Component.translatable("config.mineqtt.block_observer_export_block_entity_data.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.blockObserverExportBlockEntityData = value)
                .build());

        worldCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.entity_observer_export_entity_data"),
                        MineQTTConfig.entityObserverExportEntityData)
                .setDefaultValue(StaticDefaults.DEFAULT_ENTITY_OBSERVER_EXPORT_ENTITY_DATA)
                .setTooltip(Component.translatable("config.mineqtt.entity_observer_export_entity_data.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.entityObserverExportEntityData = value)
                .build());

        worldCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.rooms_max_size"), MineQTTConfig.roomsMaxSize)
                .setDefaultValue(StaticDefaults.DEFAULT_ROOMS_MAX_SIZE)
                .setTooltip(Component.translatable("config.mineqtt.rooms_max_size.tooltip"))
                .setMin(StaticDefaults.MIN_ROOMS_MAX_SIZE)
                .setMax(StaticDefaults.MAX_ROOMS_MAX_SIZE)
                .setSaveConsumer(value -> MineQTTConfig.roomsMaxSize = value)
                .build());

        worldCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.limits_max_rooms_per_player"), MineQTTConfig.limitsMaxRoomsPerPlayer)
                .setDefaultValue(StaticDefaults.DEFAULT_LIMITS_MAX_ROOMS_PER_PLAYER)
                .setTooltip(Component.translatable("config.mineqtt.limits_max_rooms_per_player.tooltip"))
                .setMin(0)
                .setMax(StaticDefaults.MAX_ROOMS_PER_WORLD)
                .setSaveConsumer(value -> MineQTTConfig.limitsMaxRoomsPerPlayer = value)
                .build());

        // Dedicated Server Category
        ConfigCategory serverCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.server"));

        serverCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.server_keep_ticking_when_empty"), MineQTTConfig.serverKeepTickingWhenEmpty)
                .setDefaultValue(StaticDefaults.DEFAULT_SERVER_KEEP_TICKING_WHEN_EMPTY)
                .setTooltip(Component.translatable("config.mineqtt.server_keep_ticking_when_empty.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.serverKeepTickingWhenEmpty = value)
                .build());

        return builder.build();
    }

    private static Function<String, Optional<Component>> error(Predicate<Object> valid, String translationKey) {
        return value -> valid.test(value) ? Optional.empty() : Optional.of(Component.translatable(translationKey));
    }
}

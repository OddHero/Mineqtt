package art.rehra.mineqtt.fabric.config;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.AutoBoolean;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
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

        // General Settings Category
        ConfigCategory generalCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.general"));

        generalCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.allow_item_nether_portal_teleport"), MineQTTConfig.allowItemNetherPortalTeleport)
                .setDefaultValue(StaticDefaults.DEFAULT_ALLOW_ITEM_NETHER_PORTAL_TELEPORT)
                .setTooltip(Component.translatable("config.mineqtt.allow_item_nether_portal_teleport.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.allowItemNetherPortalTeleport = value)
                .build());

        // Goal Settings Category
        ConfigCategory goalCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.goal"));

        goalCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.zombie_goal_enabled"), MineQTTConfig.zombieGoalEnabled)
                .setDefaultValue(StaticDefaults.DEFAULT_ZOMBIE_GOAL_ENABLED)
                .setTooltip(Component.translatable("config.mineqtt.zombie_goal_enabled.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.zombieGoalEnabled = value)
                .build());

        goalCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.goal_x"), MineQTTConfig.goalX)
                .setDefaultValue(StaticDefaults.DEFAULT_GOAL_X)
                .setTooltip(Component.translatable("config.mineqtt.goal_x.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.goalX = value)
                .build());

        goalCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.goal_y"), MineQTTConfig.goalY)
                .setDefaultValue(StaticDefaults.DEFAULT_GOAL_Y)
                .setTooltip(Component.translatable("config.mineqtt.goal_y.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.goalY = value)
                .build());

        goalCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.goal_z"), MineQTTConfig.goalZ)
                .setDefaultValue(StaticDefaults.DEFAULT_GOAL_Z)
                .setTooltip(Component.translatable("config.mineqtt.goal_z.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.goalZ = value)
                .build());

        return builder.build();
    }

    private static Function<String, Optional<Component>> error(Predicate<Object> valid, String translationKey) {
        return value -> valid.test(value) ? Optional.empty() : Optional.of(Component.translatable(translationKey));
    }
}

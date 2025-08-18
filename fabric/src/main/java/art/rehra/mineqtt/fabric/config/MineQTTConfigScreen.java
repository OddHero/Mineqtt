package art.rehra.mineqtt.fabric.config;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MineQTTConfigScreen {

    public static Screen createConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.mineqtt.title"))
                .setSavingRunnable(() -> {
                    MineQTT.getConfigHandler().saveConfig();
                    MineQTT.initializeMqttClient();
                });

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // MQTT Connection Category
        ConfigCategory connectionCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.connection"));

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.broker_host"), MineQTTConfig.brokerHost)
                .setTooltip(Component.translatable("config.mineqtt.broker_host.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.brokerHost = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.broker_port"), MineQTTConfig.brokerPort)
                .setTooltip(Component.translatable("config.mineqtt.broker_port.tooltip"))
                .setMin(1)
                .setMax(65535)
                .setSaveConsumer(value -> MineQTTConfig.brokerPort = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.use_authentication"), MineQTTConfig.useAuthentication)
                .setTooltip(Component.translatable("config.mineqtt.use_authentication.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.useAuthentication = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.username"), MineQTTConfig.username)
                .setTooltip(Component.translatable("config.mineqtt.username.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.username = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.password"), MineQTTConfig.password)
                .setTooltip(Component.translatable("config.mineqtt.password.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.password = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.connection_timeout"), MineQTTConfig.connectionTimeout)
                .setTooltip(Component.translatable("config.mineqtt.connection_timeout.tooltip"))
                .setMin(1)
                .setMax(60)
                .setSaveConsumer(value -> MineQTTConfig.connectionTimeout = value)
                .build());

        // Topics Category
        ConfigCategory topicsCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.topics"));

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.status_topic"), MineQTTConfig.statusTopic)
                .setTooltip(Component.translatable("config.mineqtt.status_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.statusTopic = value)
                .build());

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.player_events_topic"), MineQTTConfig.playerEventsTopic)
                .setTooltip(Component.translatable("config.mineqtt.player_events_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.playerEventsTopic = value)
                .build());

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.chat_topic"), MineQTTConfig.chatTopic)
                .setTooltip(Component.translatable("config.mineqtt.chat_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.chatTopic = value)
                .build());

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.block_events_topic"), MineQTTConfig.blockEventsTopic)
                .setTooltip(Component.translatable("config.mineqtt.block_events_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.blockEventsTopic = value)
                .build());

        // Publishing Settings Category
        ConfigCategory publishingCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.publishing"));

        publishingCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.publish_player_join"), MineQTTConfig.publishPlayerJoin)
                .setTooltip(Component.translatable("config.mineqtt.publish_player_join.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.publishPlayerJoin = value)
                .build());

        publishingCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.publish_player_leave"), MineQTTConfig.publishPlayerLeave)
                .setTooltip(Component.translatable("config.mineqtt.publish_player_leave.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.publishPlayerLeave = value)
                .build());

        publishingCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.publish_chat_messages"), MineQTTConfig.publishChatMessages)
                .setTooltip(Component.translatable("config.mineqtt.publish_chat_messages.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.publishChatMessages = value)
                .build());

        publishingCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.publish_block_break"), MineQTTConfig.publishBlockBreak)
                .setTooltip(Component.translatable("config.mineqtt.publish_block_break.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.publishBlockBreak = value)
                .build());

        publishingCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.publish_block_place"), MineQTTConfig.publishBlockPlace)
                .setTooltip(Component.translatable("config.mineqtt.publish_block_place.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.publishBlockPlace = value)
                .build());

        // Messages Category
        ConfigCategory messagesCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.messages"));

        messagesCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.online_message"), MineQTTConfig.onlineMessage)
                .setTooltip(Component.translatable("config.mineqtt.online_message.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.onlineMessage = value)
                .build());

        messagesCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.offline_message"), MineQTTConfig.offlineMessage)
                .setTooltip(Component.translatable("config.mineqtt.offline_message.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.offlineMessage = value)
                .build());

        // Debug Category
        ConfigCategory debugCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.debug"));

        debugCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.enable_debug_logging"), MineQTTConfig.enableDebugLogging)
                .setTooltip(Component.translatable("config.mineqtt.enable_debug_logging.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.enableDebugLogging = value)
                .build());

        debugCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.show_mqtt_status_in_chat"), MineQTTConfig.showMqttStatusInChat)
                .setTooltip(Component.translatable("config.mineqtt.show_mqtt_status_in_chat.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.showMqttStatusInChat = value)
                .build());

        return builder.build();
    }
}

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
                });

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // MQTT Connection Category
        ConfigCategory connectionCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.connection"));

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.broker_url"), MineQTTConfig.brokerUrl)
                .setTooltip(Component.translatable("config.mineqtt.broker_url.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.brokerUrl = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.client_id"), MineQTTConfig.clientId)
                .setTooltip(Component.translatable("config.mineqtt.client_id.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.clientId = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.username"), MineQTTConfig.username)
                .setTooltip(Component.translatable("config.mineqtt.username.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.username = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.password"), MineQTTConfig.password)
                .setTooltip(Component.translatable("config.mineqtt.password.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.password = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.auto_reconnect"), MineQTTConfig.autoReconnect)
                .setTooltip(Component.translatable("config.mineqtt.auto_reconnect.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.autoReconnect = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.connection_timeout"), MineQTTConfig.connectionTimeout)
                .setTooltip(Component.translatable("config.mineqtt.connection_timeout.tooltip"))
                .setMin(1)
                .setMax(300)
                .setSaveConsumer(value -> MineQTTConfig.connectionTimeout = value)
                .build());

        connectionCategory.addEntry(entryBuilder.startIntField(Component.translatable("config.mineqtt.keep_alive"), MineQTTConfig.keepAlive)
                .setTooltip(Component.translatable("config.mineqtt.keep_alive.tooltip"))
                .setMin(1)
                .setMax(3600)
                .setSaveConsumer(value -> MineQTTConfig.keepAlive = value)
                .build());

        // Topics Category
        ConfigCategory topicsCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.topics"));

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.player_join_topic"), MineQTTConfig.playerJoinTopic)
                .setTooltip(Component.translatable("config.mineqtt.player_join_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.playerJoinTopic = value)
                .build());

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.player_leave_topic"), MineQTTConfig.playerLeaveTopic)
                .setTooltip(Component.translatable("config.mineqtt.player_leave_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.playerLeaveTopic = value)
                .build());

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.chat_topic"), MineQTTConfig.chatTopic)
                .setTooltip(Component.translatable("config.mineqtt.chat_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.chatTopic = value)
                .build());

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.block_break_topic"), MineQTTConfig.blockBreakTopic)
                .setTooltip(Component.translatable("config.mineqtt.block_break_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.blockBreakTopic = value)
                .build());

        topicsCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.block_place_topic"), MineQTTConfig.blockPlaceTopic)
                .setTooltip(Component.translatable("config.mineqtt.block_place_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.blockPlaceTopic = value)
                .build());

        // Feature Toggles Category
        ConfigCategory featuresCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.features"));

        featuresCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.enable_player_events"), MineQTTConfig.enablePlayerEvents)
                .setTooltip(Component.translatable("config.mineqtt.enable_player_events.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.enablePlayerEvents = value)
                .build());

        featuresCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.enable_chat_events"), MineQTTConfig.enableChatEvents)
                .setTooltip(Component.translatable("config.mineqtt.enable_chat_events.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.enableChatEvents = value)
                .build());

        featuresCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.enable_block_events"), MineQTTConfig.enableBlockEvents)
                .setTooltip(Component.translatable("config.mineqtt.enable_block_events.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.enableBlockEvents = value)
                .build());

        featuresCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.enable_debugging"), MineQTTConfig.enableDebugging)
                .setTooltip(Component.translatable("config.mineqtt.enable_debugging.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.enableDebugging = value)
                .build());

        // Messages Category
        ConfigCategory messagesCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.messages"));

        messagesCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.include_coordinates"), MineQTTConfig.includeCoordinates)
                .setTooltip(Component.translatable("config.mineqtt.include_coordinates.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.includeCoordinates = value)
                .build());

        messagesCategory.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.mineqtt.include_timestamp"), MineQTTConfig.includeTimestamp)
                .setTooltip(Component.translatable("config.mineqtt.include_timestamp.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.includeTimestamp = value)
                .build());

        messagesCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.message_format"), MineQTTConfig.messageFormat)
                .setTooltip(Component.translatable("config.mineqtt.message_format.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.messageFormat = value)
                .build());

        return builder.build();
    }
}

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

        connectionCategory.addEntry(entryBuilder.startLongField(Component.translatable("config.mineqtt.connection_timeout"), MineQTTConfig.connectionTimeout)
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

        ConfigCategory topicCategory = builder.getOrCreateCategory(Component.translatable("config.mineqtt.category.topics"));

        topicCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.base_topic"), MineQTTConfig.baseTopic)
                .setTooltip(Component.translatable("config.mineqtt.base_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.baseTopic = value)
                .build());

        topicCategory.addEntry(entryBuilder.startStrField(Component.translatable("config.mineqtt.status_topic"), MineQTTConfig.statusTopic)
                .setTooltip(Component.translatable("config.mineqtt.status_topic.tooltip"))
                .setSaveConsumer(value -> MineQTTConfig.statusTopic = value)
                .build());

        return builder.build();
    }
}

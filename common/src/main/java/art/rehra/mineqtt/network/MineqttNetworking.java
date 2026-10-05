package art.rehra.mineqtt.network;

import art.rehra.mineqtt.MineQTT;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

public final class MineqttNetworking {
    public static final Identifier CYBERDECK_PUBLISH_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_publish");
    public static final Identifier CYBERDECK_LISTEN_TOGGLE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_listen_toggle");
    public static final Identifier CYBERDECK_TOPIC_UPDATE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_topic_update");
    public static final Identifier LIGHT_REMOTE_COMMAND_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "light_remote_command");
    public static final Identifier SET_BLOCK_SENSOR_MODE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_block_sensor_mode");
    public static final Identifier SET_ACTIVE_TAB_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_active_tab");
    public static final Identifier SET_PRIVATE_MODE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_private_mode");
    public static final Identifier SET_CRON_EXPRESSION_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_cron_expression");
    public static final Identifier SET_INTERVAL_TIMER_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_interval_timer");
    public static final Identifier ADD_ALARM_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "add_alarm");
    public static final Identifier REMOVE_ALARM_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "remove_alarm");
    public static final Identifier TOGGLE_ALARM_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "toggle_alarm");
    public static final Identifier SET_GAME_TIME_TIMER_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_game_time_timer");
    public static final Identifier SET_STATIC_MESSAGE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_static_message");
    public static final Identifier TEST_STATIC_PUBLISH_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "test_static_publish");
    public static final Identifier SET_TOPIC_RELAY_SOURCE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_topic_relay_source");
    public static final Identifier SET_TOPIC_RELAY_TARGET_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_topic_relay_target");
    public static final Identifier TEST_TOPIC_RELAY_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "test_topic_relay");
    public static final Identifier SET_DYNAMIC_DATA_TYPE_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_dynamic_data_type");
    public static final Identifier TEST_DYNAMIC_PUBLISH_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "test_dynamic_publish");
    public static final Identifier TRIGGER_BLOCK_EMITTER_TEST_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "trigger_block_emitter_test");

    @Deprecated
    public static final Identifier CYBERDECK_PUBLISH = CYBERDECK_PUBLISH_ID;
    @Deprecated
    public static final Identifier CYBERDECK_LISTEN_TOGGLE = CYBERDECK_LISTEN_TOGGLE_ID;
    @Deprecated
    public static final Identifier CYBERDECK_TOPIC_UPDATE = CYBERDECK_TOPIC_UPDATE_ID;

    private MineqttNetworking() {
    }

    /**
     * Prefixes {@code topic} with the player's username when the held cyberdeck
     * has private-mode enabled. If the topic is empty, returns just the username.
     * Returns {@code topic} unchanged when private-mode is off or no cyberdeck is held.
     */
    private static String applyCyberdeckPrivatePrefix(net.minecraft.server.level.ServerPlayer sp, String topic) {
        net.minecraft.world.item.ItemStack stack = sp.getMainHandItem();
        if (!(stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem)) {
            stack = sp.getOffhandItem();
        }
        if (!(stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem)) return topic;
        if (!art.rehra.mineqtt.items.CyberdeckDataUtil.isPrivate(stack)) return topic;
        String owner = sp.getGameProfile().name();
        if (topic == null || topic.isBlank()) return owner;
        return owner + "/" + topic;
    }

    public static void init() {
        // C2S: Cyberdeck manual publish (topic, payload)
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CyberdeckPublishPayload.TYPE, CyberdeckPublishPayload.CODEC, (payload, context) -> {
            String payloadStr = payload.payload();

            context.queue(() -> {
                String topic = payload.topic();
                if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp) {
                    topic = applyCyberdeckPrivatePrefix(sp, topic);
                }
                if (MineQTT.mqttClient == null || !MineQTT.mqttClient.getState().isConnected()) {
                    MineQTT.LOGGER.warn("[Cyberdeck] MQTT client not connected; cannot publish to {}", topic);
                    return;
                }
                try {
                    byte[] payloadBytes = payloadStr.getBytes(StandardCharsets.UTF_8);
                    MineQTT.LOGGER.debug("[Cyberdeck] Publishing to {}: '{}' ({} bytes)", topic, payloadStr, payloadBytes.length);

                    MineQTT.mqttClient.toAsync().publish(Mqtt3Publish.builder()
                            .topic(topic)
                            .payload(payloadBytes)
                            .build());
                    MineQTT.LOGGER.debug("[Cyberdeck] Published to {} by {}", topic, context.getPlayer().getGameProfile().name());
                } catch (Exception e) {
                    MineQTT.LOGGER.error("[Cyberdeck] Failed publishing to {}: {}", topic, e.getMessage());
                }
            });
        });

        // C2S: Cyberdeck listen toggle (boolean)
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CyberdeckListenTogglePayload.TYPE, CyberdeckListenTogglePayload.CODEC, (payload, context) -> {
            boolean listening = payload.listening();
            context.queue(() -> {
                if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player) {
                    art.rehra.mineqtt.mqtt.CyberdeckSessionManager.toggleListening(player, listening);

                    // Update the item data component so it persists
                    net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
                    if (!(stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem)) {
                        stack = player.getOffhandItem();
                    }
                    if (stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem) {
                        art.rehra.mineqtt.items.CyberdeckDataUtil.setListening(stack, listening);
                    }

                    MineQTT.LOGGER.debug("[Cyberdeck] Player {} is now {}listening", player.getGameProfile().name(), listening ? "" : "not ");
                }
            });
        });

        // S2C: Cyberdeck topic update (topic, payload)
        // Architectury 17+ on Fabric requires the codec to be registered for S2C on the server
        // so that sendToPlayer can find it. registerReceiver with Side.S2C on the server
        // is NOT supported on Fabric (throws AbstractMethodError).
        // We use registerS2CPayloadType on the server (which is safe on all loaders) 
        // and registerReceiver only on the client.
        // C2S: Light Remote command (blockPos, jsonPayload)
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, LightRemoteCommandPayload.TYPE, LightRemoteCommandPayload.CODEC, (payload, context) -> {
            var posOpt = payload.pos();
            String jsonPayload = payload.jsonPayload();

            context.queue(() -> {
                if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    var level = serverPlayer.level();
                    if (posOpt.isPresent()) {
                        if (level.getBlockEntity(posOpt.get()) instanceof art.rehra.mineqtt.blocks.entities.LightRemoteBlockEntity lightRemote) {
                            lightRemote.publishLightCommand(jsonPayload);
                            MineQTT.LOGGER.debug("[LightRemote] Player {} published light command to {}", serverPlayer.getGameProfile().name(), posOpt.get());
                        }
                    } else if (serverPlayer.containerMenu instanceof art.rehra.mineqtt.ui.CyberdeckMenu cyberdeckMenu) {
                        // For Cyberdeck (item-based GUI), publish directly via the shared MQTT client.
                        // The topic is derived from the two frequency slots (base + sub), matching
                        // the convention used by BaseMqttBlockEntity and CyberdeckPublishPayload.
                        var baseStack = cyberdeckMenu.container.getItem(0);
                        var subStack = cyberdeckMenu.container.getItem(1);
                        String base = baseStack.isEmpty() ? "" : art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity.parseItemStackTopic(baseStack);
                        String sub = subStack.isEmpty() ? "" : art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity.parseItemStackTopic(subStack);
                        String topic = sub.isEmpty() ? base : base + "/" + sub;
                        topic = applyCyberdeckPrivatePrefix(serverPlayer, topic);
                        if (topic == null || topic.isBlank()) {
                            MineQTT.LOGGER.warn("[LightRemote] Cyberdeck has no configured topic; cannot publish light command.");
                            return;
                        }
                        if (MineQTT.mqttClient == null || !MineQTT.mqttClient.getState().isConnected()) {
                            MineQTT.LOGGER.warn("[LightRemote] MQTT client not connected; cannot publish to {}", topic);
                            return;
                        }
                        try {
                            byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
                            MineQTT.mqttClient.toAsync().publish(Mqtt3Publish.builder()
                                    .topic(topic)
                                    .payload(payloadBytes)
                                    .build());
                            MineQTT.LOGGER.debug("[LightRemote] Player {} published light command from Cyberdeck to {}: {}",
                                    serverPlayer.getGameProfile().name(), topic, jsonPayload);
                        } catch (Exception e) {
                            MineQTT.LOGGER.error("[LightRemote] Failed publishing from Cyberdeck to {}: {}", topic, e.getMessage());
                        }
                    }
                }
            });
        });

        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetBlockSensorModePayload.TYPE, SetBlockSensorModePayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!(player.containerMenu instanceof art.rehra.mineqtt.ui.framework.TabbedMqttMenu menu)
                        || !payload.pos().equals(menu.blockPos) || !menu.stillValid(player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                if (!(menu.container instanceof art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity sensor)
                        || player.level().getBlockEntity(payload.pos()) != sensor) return;
                // Reject unknown network values; missing/old save values still use the default.
                for (var mode : art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity.DataMode.values()) {
                    if (mode.id().equals(payload.mode())) {
                        sensor.setDataMode(mode);
                        return;
                    }
                }
            });
        });

        // C2S: remember the last tab opened in a tabbed MQTT-block screen
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetActiveTabPayload.TYPE, SetActiveTabPayload.CODEC, (payload, context) -> {
            var posOpt = payload.pos();
            String tabId = payload.tabId();
            context.queue(() -> {
                if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp) {
                    if (posOpt.isPresent()) {
                        var be = sp.level().getBlockEntity(posOpt.get());
                        if (be instanceof art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity mqtt) {
                            mqtt.setLastTabId(tabId);
                        }
                    } else if (sp.containerMenu instanceof art.rehra.mineqtt.ui.framework.TabbedMqttMenu menu) {
                        menu.setActiveTab(tabId);
                    }
                }
            });
        });

        // C2S: toggle private-mode (topic prefixed with owner username) for a tabbed MQTT block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetPrivateModePayload.TYPE, SetPrivateModePayload.CODEC, (payload, context) -> {
            var posOpt = payload.pos();
            boolean enabled = payload.enabled();
            context.queue(() -> {
                if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp && posOpt.isEmpty()) {
                    // Cyberdeck (item-based): persist on the held cyberdeck stack.
                    net.minecraft.world.item.ItemStack stack = sp.getMainHandItem();
                    if (!(stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem)) {
                        stack = sp.getOffhandItem();
                    }
                    if (stack.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem) {
                        art.rehra.mineqtt.items.CyberdeckDataUtil.setPrivate(stack, enabled);
                    }
                } else if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp && posOpt.isPresent()) {
                    var be = sp.level().getBlockEntity(posOpt.get());
                    if (be instanceof art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity mqtt) {
                        // Only the owner (or anyone, if no owner recorded yet) may toggle private mode.
                        String owner = mqtt.getOwnerName();
                        String playerName = sp.getGameProfile().name();
                        if (owner.isEmpty() || owner.equals(playerName)) {
                            if (owner.isEmpty()) {
                                mqtt.setOwnerName(playerName);
                            }
                            mqtt.setPrivateMode(enabled);
                        } else {
                            MineQTT.LOGGER.debug("[PrivateMode] {} tried to toggle private mode on a block owned by {}", playerName, owner);
                        }
                    }
                }
            });
        });

        // C2S: set cron expression for a Cron Timer block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetCronExpressionPayload.TYPE, SetCronExpressionPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.CronTimerBlockEntity cronTimer) {
                    cronTimer.setCronExpression(payload.expression());
                    MineQTT.LOGGER.debug("[CronTimer] Player {} set cron expression: {}", player.getGameProfile().name(), payload.expression());
                }
            });
        });

        // C2S: set interval for an Interval Timer block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetIntervalTimerPayload.TYPE, SetIntervalTimerPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.IntervalTimerBlockEntity intervalTimer) {
                    intervalTimer.setInterval(payload.value(), payload.unitIndex());
                    MineQTT.LOGGER.debug("[IntervalTimer] Player {} set interval: {} unit {}", player.getGameProfile().name(), payload.value(), payload.unitIndex());
                }
            });
        });

        // C2S: add alarm to an Alarm Clock block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, AddAlarmPayload.TYPE, AddAlarmPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.AlarmClockBlockEntity alarmClock) {
                    alarmClock.addAlarm(payload.minecraftTicks());
                    MineQTT.LOGGER.debug("[AlarmClock] Player {} added alarm at {} ticks", player.getGameProfile().name(), payload.minecraftTicks());
                }
            });
        });

        // C2S: remove alarm from an Alarm Clock block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, RemoveAlarmPayload.TYPE, RemoveAlarmPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.AlarmClockBlockEntity alarmClock) {
                    alarmClock.removeAlarm(payload.index());
                    MineQTT.LOGGER.debug("[AlarmClock] Player {} removed alarm at index {}", player.getGameProfile().name(), payload.index());
                }
            });
        });

        // C2S: toggle alarm enabled state in an Alarm Clock block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ToggleAlarmPayload.TYPE, ToggleAlarmPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.AlarmClockBlockEntity alarmClock) {
                    alarmClock.toggleAlarm(payload.index());
                    MineQTT.LOGGER.debug("[AlarmClock] Player {} toggled alarm at index {}", player.getGameProfile().name(), payload.index());
                }
            });
        });

        // C2S: set trigger mode and custom time for a Game Time Timer block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetGameTimeTimerPayload.TYPE, SetGameTimeTimerPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.GameTimeTimerBlockEntity gameTimeTimer) {
                    gameTimeTimer.setTriggerModeIndex(payload.triggerModeIndex());
                    gameTimeTimer.setCustomTime(payload.customTime());
                    MineQTT.LOGGER.debug("[GameTimeTimer] Player {} set trigger mode: {} custom time: {}", player.getGameProfile().name(), payload.triggerModeIndex(), payload.customTime());
                }
            });
        });

        // C2S: set static message for a Static Publisher block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetStaticMessagePayload.TYPE, SetStaticMessagePayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.StaticPublisherBlockEntity staticPublisher) {
                    staticPublisher.setStaticMessage(payload.message());
                    MineQTT.LOGGER.debug("[StaticPublisher] Player {} set static message at {}", player.getGameProfile().name(), payload.pos());
                }
            });
        });

        // C2S: test publish for a Static Publisher block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TestStaticPublishPayload.TYPE, TestStaticPublishPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.StaticPublisherBlockEntity staticPublisher) {
                    // Temporarily set the message and publish
                    String prevMessage = staticPublisher.getStaticMessage();
                    staticPublisher.setStaticMessage(payload.message());
                    staticPublisher.publishStaticMessage();
                    MineQTT.LOGGER.debug("[StaticPublisher] Player {} test-published at {}", player.getGameProfile().name(), payload.pos());
                }
            });
        });

        // C2S: set source topic for a Topic Relay block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetTopicRelaySourcePayload.TYPE, SetTopicRelaySourcePayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.TopicRelayBlockEntity topicRelay) {
                    topicRelay.setSourceTopic(payload.topic());
                    MineQTT.LOGGER.debug("[TopicRelay] Player {} set source topic: {} at {}", player.getGameProfile().name(), payload.topic(), payload.pos());
                }
            });
        });

        // C2S: set target topic for a Topic Relay block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetTopicRelayTargetPayload.TYPE, SetTopicRelayTargetPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.TopicRelayBlockEntity topicRelay) {
                    topicRelay.setTopic(payload.topic());
                    MineQTT.LOGGER.debug("[TopicRelay] Player {} set target topic: {} at {}", player.getGameProfile().name(), payload.topic(), payload.pos());
                }
            });
        });

        // C2S: test relay for a Topic Relay block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TestTopicRelayPayload.TYPE, TestTopicRelayPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.TopicRelayBlockEntity topicRelay) {
                    topicRelay.relayCachedMessage();
                    MineQTT.LOGGER.debug("[TopicRelay] Player {} test-relayed at {}", player.getGameProfile().name(), payload.pos());
                }
            });
        });

        // C2S: set data type for a Dynamic Publisher block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SetDynamicDataTypePayload.TYPE, SetDynamicDataTypePayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.DynamicPublisherBlockEntity dynamicPublisher) {
                    dynamicPublisher.setDataTypeOrdinal(payload.dataTypeOrdinal());
                    MineQTT.LOGGER.debug("[DynamicPublisher] Player {} set data type: {} at {}", player.getGameProfile().name(), payload.dataTypeOrdinal(), payload.pos());
                }
            });
        });

        // C2S: test publish for a Dynamic Publisher block
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TestDynamicPublishPayload.TYPE, TestDynamicPublishPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.DynamicPublisherBlockEntity dynamicPublisher) {
                    dynamicPublisher.publishDynamicMessage();
                    MineQTT.LOGGER.debug("[DynamicPublisher] Player {} test-published at {}", player.getGameProfile().name(), payload.pos());
                }
            });
        });

        // C2S: trigger test block emitter payload
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TriggerBlockEmitterTestPayload.TYPE, TriggerBlockEmitterTestPayload.CODEC, (payload, context) -> {
            context.queue(() -> {
                if (!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                if (!MineQTT.permissionManager.canInteract(player, payload.pos(), art.rehra.mineqtt.integrations.MineqttPermission.INTERACT))
                    return;
                var be = player.level().getBlockEntity(payload.pos());
                if (be instanceof art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity blockEmitter) {
                    blockEmitter.triggerTest(payload.payload());
                    MineQTT.LOGGER.debug("[BlockEmitter] Player {} tested emitter payload '{}' at {}", player.getGameProfile().name(), payload.payload(), payload.pos());
                }
            });
        });

        if (dev.architectury.platform.Platform.getEnv() == net.fabricmc.api.EnvType.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC, ClientPacketHandler::handleTopicUpdate);
        } else {
            NetworkManager.registerS2CPayloadType(CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC);
        }
    }

    public record CyberdeckPublishPayload(String topic, String payload) implements CustomPacketPayload {
        public static final Type<CyberdeckPublishPayload> TYPE = new Type<>(CYBERDECK_PUBLISH_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, CyberdeckPublishPayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(512), CyberdeckPublishPayload::topic,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(2048), CyberdeckPublishPayload::payload,
                CyberdeckPublishPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CyberdeckListenTogglePayload(boolean listening) implements CustomPacketPayload {
        public static final Type<CyberdeckListenTogglePayload> TYPE = new Type<>(CYBERDECK_LISTEN_TOGGLE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, CyberdeckListenTogglePayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.BOOL, CyberdeckListenTogglePayload::listening,
                CyberdeckListenTogglePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CyberdeckTopicUpdatePayload(String topic, String payload) implements CustomPacketPayload {
        public static final Type<CyberdeckTopicUpdatePayload> TYPE = new Type<>(CYBERDECK_TOPIC_UPDATE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, CyberdeckTopicUpdatePayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(512), CyberdeckTopicUpdatePayload::topic,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(2048), CyberdeckTopicUpdatePayload::payload,
                CyberdeckTopicUpdatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LightRemoteCommandPayload(java.util.Optional<net.minecraft.core.BlockPos> pos,
                                            String jsonPayload) implements CustomPacketPayload {
        public static final Type<LightRemoteCommandPayload> TYPE = new Type<>(LIGHT_REMOTE_COMMAND_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LightRemoteCommandPayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.optional(net.minecraft.core.BlockPos.STREAM_CODEC), LightRemoteCommandPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(4096), LightRemoteCommandPayload::jsonPayload,
                LightRemoteCommandPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetActiveTabPayload(java.util.Optional<net.minecraft.core.BlockPos> pos,
                                      String tabId) implements CustomPacketPayload {
        public static final Type<SetActiveTabPayload> TYPE = new Type<>(SET_ACTIVE_TAB_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetActiveTabPayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.optional(net.minecraft.core.BlockPos.STREAM_CODEC), SetActiveTabPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(64), SetActiveTabPayload::tabId,
                SetActiveTabPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetPrivateModePayload(java.util.Optional<net.minecraft.core.BlockPos> pos,
                                        boolean enabled) implements CustomPacketPayload {
        public static final Type<SetPrivateModePayload> TYPE = new Type<>(SET_PRIVATE_MODE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetPrivateModePayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.optional(net.minecraft.core.BlockPos.STREAM_CODEC), SetPrivateModePayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.BOOL, SetPrivateModePayload::enabled,
                SetPrivateModePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetBlockSensorModePayload(net.minecraft.core.BlockPos pos,
                                            String mode) implements CustomPacketPayload {
        public static final Type<SetBlockSensorModePayload> TYPE = new Type<>(SET_BLOCK_SENSOR_MODE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetBlockSensorModePayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetBlockSensorModePayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(32), SetBlockSensorModePayload::mode,
                SetBlockSensorModePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetCronExpressionPayload(net.minecraft.core.BlockPos pos,
                                           String expression) implements CustomPacketPayload {
        public static final Type<SetCronExpressionPayload> TYPE = new Type<>(SET_CRON_EXPRESSION_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetCronExpressionPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetCronExpressionPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(64), SetCronExpressionPayload::expression,
                SetCronExpressionPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetIntervalTimerPayload(net.minecraft.core.BlockPos pos,
                                          long value,
                                          int unitIndex) implements CustomPacketPayload {
        public static final Type<SetIntervalTimerPayload> TYPE = new Type<>(SET_INTERVAL_TIMER_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetIntervalTimerPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetIntervalTimerPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.LONG, SetIntervalTimerPayload::value,
                net.minecraft.network.codec.ByteBufCodecs.INT, SetIntervalTimerPayload::unitIndex,
                SetIntervalTimerPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AddAlarmPayload(net.minecraft.core.BlockPos pos,
                                  int minecraftTicks) implements CustomPacketPayload {
        public static final Type<AddAlarmPayload> TYPE = new Type<>(ADD_ALARM_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, AddAlarmPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, AddAlarmPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.INT, AddAlarmPayload::minecraftTicks,
                AddAlarmPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RemoveAlarmPayload(net.minecraft.core.BlockPos pos,
                                     int index) implements CustomPacketPayload {
        public static final Type<RemoveAlarmPayload> TYPE = new Type<>(REMOVE_ALARM_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, RemoveAlarmPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, RemoveAlarmPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.INT, RemoveAlarmPayload::index,
                RemoveAlarmPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ToggleAlarmPayload(net.minecraft.core.BlockPos pos,
                                     int index) implements CustomPacketPayload {
        public static final Type<ToggleAlarmPayload> TYPE = new Type<>(TOGGLE_ALARM_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAlarmPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, ToggleAlarmPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.INT, ToggleAlarmPayload::index,
                ToggleAlarmPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetGameTimeTimerPayload(net.minecraft.core.BlockPos pos,
                                          int triggerModeIndex,
                                          int customTime) implements CustomPacketPayload {
        public static final Type<SetGameTimeTimerPayload> TYPE = new Type<>(SET_GAME_TIME_TIMER_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetGameTimeTimerPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetGameTimeTimerPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.INT, SetGameTimeTimerPayload::triggerModeIndex,
                net.minecraft.network.codec.ByteBufCodecs.INT, SetGameTimeTimerPayload::customTime,
                SetGameTimeTimerPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetStaticMessagePayload(net.minecraft.core.BlockPos pos,
                                          String message) implements CustomPacketPayload {
        public static final Type<SetStaticMessagePayload> TYPE = new Type<>(SET_STATIC_MESSAGE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetStaticMessagePayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetStaticMessagePayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(2048), SetStaticMessagePayload::message,
                SetStaticMessagePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TestStaticPublishPayload(net.minecraft.core.BlockPos pos,
                                           String message) implements CustomPacketPayload {
        public static final Type<TestStaticPublishPayload> TYPE = new Type<>(TEST_STATIC_PUBLISH_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TestStaticPublishPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, TestStaticPublishPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(2048), TestStaticPublishPayload::message,
                TestStaticPublishPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetTopicRelaySourcePayload(net.minecraft.core.BlockPos pos,
                                             String topic) implements CustomPacketPayload {
        public static final Type<SetTopicRelaySourcePayload> TYPE = new Type<>(SET_TOPIC_RELAY_SOURCE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetTopicRelaySourcePayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetTopicRelaySourcePayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(512), SetTopicRelaySourcePayload::topic,
                SetTopicRelaySourcePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetTopicRelayTargetPayload(net.minecraft.core.BlockPos pos,
                                             String topic) implements CustomPacketPayload {
        public static final Type<SetTopicRelayTargetPayload> TYPE = new Type<>(SET_TOPIC_RELAY_TARGET_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetTopicRelayTargetPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetTopicRelayTargetPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(512), SetTopicRelayTargetPayload::topic,
                SetTopicRelayTargetPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TestTopicRelayPayload(net.minecraft.core.BlockPos pos) implements CustomPacketPayload {
        public static final Type<TestTopicRelayPayload> TYPE = new Type<>(TEST_TOPIC_RELAY_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TestTopicRelayPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, TestTopicRelayPayload::pos,
                TestTopicRelayPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetDynamicDataTypePayload(net.minecraft.core.BlockPos pos,
                                            int dataTypeOrdinal) implements CustomPacketPayload {
        public static final Type<SetDynamicDataTypePayload> TYPE = new Type<>(SET_DYNAMIC_DATA_TYPE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetDynamicDataTypePayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetDynamicDataTypePayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.INT, SetDynamicDataTypePayload::dataTypeOrdinal,
                SetDynamicDataTypePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TestDynamicPublishPayload(net.minecraft.core.BlockPos pos) implements CustomPacketPayload {
        public static final Type<TestDynamicPublishPayload> TYPE = new Type<>(TEST_DYNAMIC_PUBLISH_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TestDynamicPublishPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, TestDynamicPublishPayload::pos,
                TestDynamicPublishPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TriggerBlockEmitterTestPayload(net.minecraft.core.BlockPos pos,
                                                 String payload) implements CustomPacketPayload {
        public static final Type<TriggerBlockEmitterTestPayload> TYPE = new Type<>(TRIGGER_BLOCK_EMITTER_TEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TriggerBlockEmitterTestPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, TriggerBlockEmitterTestPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(2048), TriggerBlockEmitterTestPayload::payload,
                TriggerBlockEmitterTestPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static class ClientPacketHandler {
        public static void handleTopicUpdate(CyberdeckTopicUpdatePayload payload, NetworkManager.PacketContext context) {
            String topic = payload.topic();
            String payloadStr = payload.payload();
            context.queue(() -> {
                art.rehra.mineqtt.ui.CyberdeckScreen.updateTopic(topic, payloadStr);
            });
        }
    }
}

package art.rehra.mineqtt.network;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.AlarmClockBlockEntity;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.blocks.entities.BlockEmitterBlockEntity;
import art.rehra.mineqtt.blocks.entities.BlockSensorBlockEntity;
import art.rehra.mineqtt.blocks.entities.CronTimerBlockEntity;
import art.rehra.mineqtt.blocks.entities.DynamicPublisherBlockEntity;
import art.rehra.mineqtt.blocks.entities.GameTimeTimerBlockEntity;
import art.rehra.mineqtt.blocks.entities.IntervalTimerBlockEntity;
import art.rehra.mineqtt.blocks.entities.LightRemoteBlockEntity;
import art.rehra.mineqtt.blocks.entities.StaticPublisherBlockEntity;
import art.rehra.mineqtt.blocks.entities.TopicRelayBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.mqtt.CyberdeckSessionManager;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

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
    public static final Identifier SET_SINGLE_ALARM_ID = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "set_single_alarm");
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
     * Registers the payloads. Every client-to-server payload is handled on the server thread and passes
     * {@link C2SGuard} first; the client is never trusted (design §8.5).
     */
    public static void init() {
        // Cyberdeck manual publish (topic, payload): full topic ACL, rate-limited, never retained.
        receive(CyberdeckPublishPayload.TYPE, CyberdeckPublishPayload.CODEC, (player, payload) ->
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu ->
                        CyberdeckSessionManager.publish(player, menu, payload.topic(), payload.payload())));

        // Cyberdeck explorer on/off, remembered on the Cyberdeck so it resumes when the player joins again.
        receive(CyberdeckListenTogglePayload.TYPE, CyberdeckListenTogglePayload.CODEC, (player, payload) ->
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> {
                    CyberdeckSessionManager.toggleListening(player, payload.listening());
                    CyberdeckDataUtil.setListening(menu.itemStack, payload.listening());
                    MineQTT.LOGGER.debug("[Cyberdeck] Player {} is now {}listening", player.getGameProfile().name(), payload.listening() ? "" : "not ");
                }));

        // Light Remote command: from the block to its topic, or from the Cyberdeck to the topic of its two slots.
        receive(LightRemoteCommandPayload.TYPE, LightRemoteCommandPayload.CODEC, (player, payload) -> {
            if (payload.pos().isPresent()) {
                C2SGuard.require(player, payload, payload.pos().get(), LightRemoteBlockEntity.class, MineqttPermission.USE)
                        .filter(lightRemote -> C2SGuard.fitsPayload(payload.jsonPayload()))
                        .ifPresent(lightRemote -> {
                            lightRemote.publishLightCommand(payload.jsonPayload());
                            MineQTT.LOGGER.debug("[LightRemote] Player {} published light command to {}", player.getGameProfile().name(), payload.pos().get());
                        });
            } else {
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> {
                    String base = menu.container.getItem(0).isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(menu.container.getItem(0));
                    String sub = menu.container.getItem(1).isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(menu.container.getItem(1));
                    CyberdeckSessionManager.publish(player, menu, sub.isEmpty() ? base : base + "/" + sub, payload.jsonPayload());
                });
            }
        });

        receive(SetBlockSensorModePayload.TYPE, SetBlockSensorModePayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), BlockSensorBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(sensor -> {
                    // Reject unknown network values; missing/old save values still use the default.
                    for (var mode : BlockSensorBlockEntity.DataMode.values()) {
                        if (mode.id().equals(payload.mode())) {
                            sensor.setDataMode(mode);
                            return;
                        }
                    }
                }));

        // Remember the last tab opened in a tabbed screen; only tabs the screen has are accepted.
        receive(SetActiveTabPayload.TYPE, SetActiveTabPayload.CODEC, (player, payload) -> {
            if (payload.pos().isPresent()) {
                C2SGuard.require(player, payload, payload.pos().get(), BaseMqttBlockEntity.class, MineqttPermission.USE).ifPresent(mqtt -> {
                    TabbedMqttMenu menu = (TabbedMqttMenu) player.containerMenu;
                    if (menu.tabs.stream().noneMatch(tab -> tab.id().equals(payload.tabId()))) return;
                    menu.setActiveTab(payload.tabId());
                    mqtt.setLastTabId(payload.tabId());
                });
            } else {
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> menu.setActiveTab(payload.tabId()));
            }
        });

        // Private mode (topic prefixed with the owner's username) of a block, or of the held Cyberdeck.
        receive(SetPrivateModePayload.TYPE, SetPrivateModePayload.CODEC, (player, payload) -> {
            if (payload.pos().isPresent()) {
                C2SGuard.require(player, payload, payload.pos().get(), BaseMqttBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(mqtt -> {
                    // A block without a recorded owner (world generation, pre-feature blocks) becomes the player's; a
                    // device also gets them as its owner, which private mode needs.
                    if (mqtt.getOwnerName().isEmpty()) {
                        mqtt.setOwnerName(player.getGameProfile().name());
                        if (mqtt instanceof DeviceHost host) host.deviceLink().claim(mqtt, player.getUUID(), player.getGameProfile().name());
                    }
                    mqtt.setPrivateMode(payload.enabled());
                });
            } else {
                C2SGuard.requireCyberdeck(player, payload).ifPresent(menu -> CyberdeckDataUtil.setPrivate(menu.itemStack, payload.enabled()));
            }
        });

        receive(SetCronExpressionPayload.TYPE, SetCronExpressionPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), CronTimerBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(cronTimer -> {
                    cronTimer.setCronExpression(payload.expression());
                    MineQTT.LOGGER.debug("[CronTimer] Player {} set cron expression: {}", player.getGameProfile().name(), payload.expression());
                }));

        receive(SetIntervalTimerPayload.TYPE, SetIntervalTimerPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), IntervalTimerBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(intervalTimer -> {
                    intervalTimer.setInterval(payload.value(), payload.unitIndex());
                    MineQTT.LOGGER.debug("[IntervalTimer] Player {} set interval: {} unit {}", player.getGameProfile().name(), payload.value(), payload.unitIndex());
                }));

        // minecraftTicks=-1 clears the alarm.
        receive(SetSingleAlarmPayload.TYPE, SetSingleAlarmPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), AlarmClockBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(alarmClock -> {
                    alarmClock.setAlarm(payload.minecraftTicks());
                    MineQTT.LOGGER.debug("[AlarmClock] Player {} set alarm to {} ticks", player.getGameProfile().name(), payload.minecraftTicks());
                }));

        receive(SetGameTimeTimerPayload.TYPE, SetGameTimeTimerPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), GameTimeTimerBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(gameTimeTimer -> {
                    gameTimeTimer.setTriggerModeIndex(payload.triggerModeIndex());
                    gameTimeTimer.setCustomTime(payload.customTime());
                    MineQTT.LOGGER.debug("[GameTimeTimer] Player {} set trigger mode: {} custom time: {}", player.getGameProfile().name(), payload.triggerModeIndex(), payload.customTime());
                }));

        receive(SetStaticMessagePayload.TYPE, SetStaticMessagePayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), StaticPublisherBlockEntity.class, MineqttPermission.CONFIGURE)
                        .filter(staticPublisher -> C2SGuard.fitsPayload(payload.message()))
                        .ifPresent(staticPublisher -> {
                            staticPublisher.setStaticMessage(payload.message());
                            MineQTT.LOGGER.debug("[StaticPublisher] Player {} set static message at {}", player.getGameProfile().name(), payload.pos());
                        }));

        // The test button saves the message it publishes.
        receive(TestStaticPublishPayload.TYPE, TestStaticPublishPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), StaticPublisherBlockEntity.class, MineqttPermission.CONFIGURE)
                        .filter(staticPublisher -> C2SGuard.fitsPayload(payload.message()))
                        .ifPresent(staticPublisher -> {
                            staticPublisher.setStaticMessage(payload.message());
                            staticPublisher.publishStaticMessage();
                            MineQTT.LOGGER.debug("[StaticPublisher] Player {} test-published at {}", player.getGameProfile().name(), payload.pos());
                        }));

        receive(SetTopicRelaySourcePayload.TYPE, SetTopicRelaySourcePayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), TopicRelayBlockEntity.class, MineqttPermission.CONFIGURE)
                        .filter(topicRelay -> C2SGuard.fitsTopic(payload.topic()))
                        .ifPresent(topicRelay -> {
                            topicRelay.setSourceTopic(payload.topic());
                            MineQTT.LOGGER.debug("[TopicRelay] Player {} set source topic: {} at {}", player.getGameProfile().name(), payload.topic(), payload.pos());
                        }));

        receive(SetTopicRelayTargetPayload.TYPE, SetTopicRelayTargetPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), TopicRelayBlockEntity.class, MineqttPermission.CONFIGURE)
                        .filter(topicRelay -> C2SGuard.fitsTopic(payload.topic()))
                        .ifPresent(topicRelay -> {
                            topicRelay.setTopic(payload.topic());
                            MineQTT.LOGGER.debug("[TopicRelay] Player {} set target topic: {} at {}", player.getGameProfile().name(), payload.topic(), payload.pos());
                        }));

        receive(TestTopicRelayPayload.TYPE, TestTopicRelayPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), TopicRelayBlockEntity.class, MineqttPermission.USE).ifPresent(topicRelay -> {
                    topicRelay.relayCachedMessage();
                    MineQTT.LOGGER.debug("[TopicRelay] Player {} test-relayed at {}", player.getGameProfile().name(), payload.pos());
                }));

        receive(SetDynamicDataTypePayload.TYPE, SetDynamicDataTypePayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), DynamicPublisherBlockEntity.class, MineqttPermission.CONFIGURE).ifPresent(dynamicPublisher -> {
                    dynamicPublisher.setDataTypeOrdinal(payload.dataTypeOrdinal());
                    MineQTT.LOGGER.debug("[DynamicPublisher] Player {} set data type: {} at {}", player.getGameProfile().name(), payload.dataTypeOrdinal(), payload.pos());
                }));

        receive(TestDynamicPublishPayload.TYPE, TestDynamicPublishPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), DynamicPublisherBlockEntity.class, MineqttPermission.USE).ifPresent(dynamicPublisher -> {
                    dynamicPublisher.publishDynamicMessage();
                    MineQTT.LOGGER.debug("[DynamicPublisher] Player {} test-published at {}", player.getGameProfile().name(), payload.pos());
                }));

        // The test places any block with any data, so beyond CONFIGURE it is for operators only.
        receive(TriggerBlockEmitterTestPayload.TYPE, TriggerBlockEmitterTestPayload.CODEC, (player, payload) ->
                C2SGuard.require(player, payload, payload.pos(), BlockEmitterBlockEntity.class, MineqttPermission.CONFIGURE)
                        .filter(blockEmitter -> C2SGuard.fitsPayload(payload.payload()))
                        .ifPresent(blockEmitter -> {
                            if (!AclContext.isOp(player.permissions())) {
                                player.sendSystemMessage(Component.translatable("mineqtt.block_emitter.test_ops_only"), true);
                                return;
                            }
                            blockEmitter.triggerTest(payload.payload());
                            MineQTT.LOGGER.debug("[BlockEmitter] Player {} tested emitter payload '{}' at {}", player.getGameProfile().name(), payload.payload(), payload.pos());
                        }));

        // S2C: Cyberdeck topic update (topic, payload)
        // Architectury 17+ on Fabric requires the codec to be registered for S2C on the server
        // so that sendToPlayer can find it. registerReceiver with Side.S2C on the server
        // is NOT supported on Fabric (throws AbstractMethodError).
        // We use registerS2CPayloadType on the server (which is safe on all loaders)
        // and registerReceiver only on the client.
        if (dev.architectury.platform.Platform.getEnv() == net.fabricmc.api.EnvType.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC, ClientPacketHandler::handleTopicUpdate);
        } else {
            NetworkManager.registerS2CPayloadType(CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC);
        }
    }

    /** A client-to-server payload, handled on the server thread for a player of this server. */
    private static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                                BiConsumer<ServerPlayer, T> handler) {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, type, codec, (payload, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) handler.accept(player, payload);
        }));
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
        /** Longer topics cannot be sent; the server skips them. */
        public static final int MAX_TOPIC_CHARS = 512;
        /** Longer payloads are cut by the server. */
        public static final int MAX_PAYLOAD_CHARS = 2048;
        public static final Type<CyberdeckTopicUpdatePayload> TYPE = new Type<>(CYBERDECK_TOPIC_UPDATE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, CyberdeckTopicUpdatePayload> CODEC = StreamCodec.composite(
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(MAX_TOPIC_CHARS), CyberdeckTopicUpdatePayload::topic,
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(MAX_PAYLOAD_CHARS), CyberdeckTopicUpdatePayload::payload,
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
                net.minecraft.network.codec.ByteBufCodecs.stringUtf8(128), SetCronExpressionPayload::expression,
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

    public record SetSingleAlarmPayload(net.minecraft.core.BlockPos pos,
                                        int minecraftTicks) implements CustomPacketPayload {
        public static final Type<SetSingleAlarmPayload> TYPE = new Type<>(SET_SINGLE_ALARM_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSingleAlarmPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, SetSingleAlarmPayload::pos,
                net.minecraft.network.codec.ByteBufCodecs.INT, SetSingleAlarmPayload::minecraftTicks,
                SetSingleAlarmPayload::new
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

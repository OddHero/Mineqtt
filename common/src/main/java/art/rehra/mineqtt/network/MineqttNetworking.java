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

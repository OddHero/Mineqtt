package art.rehra.mineqtt.network;

import art.rehra.mineqtt.MineQTT;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

public final class MineqttNetworking {
    public static final ResourceLocation CYBERDECK_PUBLISH_ID = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_publish");
    public static final ResourceLocation CYBERDECK_LISTEN_TOGGLE_ID = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_listen_toggle");
    public static final ResourceLocation CYBERDECK_TOPIC_UPDATE_ID = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_topic_update");

    @Deprecated
    public static final ResourceLocation CYBERDECK_PUBLISH = CYBERDECK_PUBLISH_ID;
    @Deprecated
    public static final ResourceLocation CYBERDECK_LISTEN_TOGGLE = CYBERDECK_LISTEN_TOGGLE_ID;
    @Deprecated
    public static final ResourceLocation CYBERDECK_TOPIC_UPDATE = CYBERDECK_TOPIC_UPDATE_ID;

    private MineqttNetworking() {
    }

    public static void init() {
        // C2S: Cyberdeck manual publish (topic, payload)
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CyberdeckPublishPayload.TYPE, CyberdeckPublishPayload.CODEC, (payload, context) -> {
            String topic = payload.topic();
            String payloadStr = payload.payload();

            context.queue(() -> {
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
                    MineQTT.LOGGER.debug("[Cyberdeck] Published to {} by {}", topic, context.getPlayer().getGameProfile().getName());
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

                    MineQTT.LOGGER.debug("[Cyberdeck] Player {} is now {}listening", player.getGameProfile().getName(), listening ? "" : "not ");
                }
            });
        });

        // S2C: Cyberdeck topic update (topic, payload)
        // Architectury 17+ on Fabric requires both sides to register the codec,
        // but Side.S2C registerReceiver throws AbstractMethodError on server.
        // Side.C2S registration on both sides is the safe way for C2S.
        // For S2C, we use Side.S2C only on client, and on server we need to register the payload separately.
        if (dev.architectury.platform.Platform.getEnv() == net.fabricmc.api.EnvType.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC, ClientPacketHandler::handleTopicUpdate);
        } else {
            // Registration on server to avoid NPE during sendToPlayer
            try {
                // We use Side.C2S here on server as a workaround because S2C registration on server
                // causes AbstractMethodError on Fabric Architectury 17.0.8,
                // but the codec must still be registered for sendToPlayer to work.
                NetworkManager.registerReceiver(NetworkManager.Side.C2S, CyberdeckTopicUpdatePayload.TYPE, CyberdeckTopicUpdatePayload.CODEC, (payload, context) -> {
                });
            } catch (Exception e) {
                MineQTT.LOGGER.error("[MineQTT] Failed to register topic update codec on server: {}", e.getMessage());
            }
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

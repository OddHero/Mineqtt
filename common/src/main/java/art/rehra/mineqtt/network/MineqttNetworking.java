package art.rehra.mineqtt.network;

import art.rehra.mineqtt.MineQTT;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

public final class MineqttNetworking {
    public static final ResourceLocation CYBERDECK_PUBLISH = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_publish");
    public static final ResourceLocation CYBERDECK_LISTEN_TOGGLE = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_listen_toggle");
    public static final ResourceLocation CYBERDECK_TOPIC_UPDATE = ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, "cyberdeck_topic_update");

    private MineqttNetworking() {
    }

    @SuppressWarnings("removal")
    public static void init() {
        // C2S: Cyberdeck manual publish (topic, payload)
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CYBERDECK_PUBLISH, (RegistryFriendlyByteBuf buf, NetworkManager.PacketContext context) -> {
            String topic = buf.readUtf(512);
            String payload = buf.readUtf(2048);

            context.queue(() -> {
                if (MineQTT.mqttClient == null || !MineQTT.mqttClient.getState().isConnected()) {
                    MineQTT.LOGGER.warn("[Cyberdeck] MQTT client not connected; cannot publish to {}", topic);
                    return;
                }
                try {
                    byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                    MineQTT.LOGGER.debug("[Cyberdeck] Publishing to {}: '{}' ({} bytes)", topic, payload, payloadBytes.length);

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
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CYBERDECK_LISTEN_TOGGLE, (RegistryFriendlyByteBuf buf, NetworkManager.PacketContext context) -> {
            boolean listening = buf.readBoolean();
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
        if (dev.architectury.platform.Platform.getEnv() == net.fabricmc.api.EnvType.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, CYBERDECK_TOPIC_UPDATE, (RegistryFriendlyByteBuf buf, NetworkManager.PacketContext context) -> {
                String topic = buf.readUtf();
                String payload = buf.readUtf();
                context.queue(() -> {
                    art.rehra.mineqtt.ui.CyberdeckScreen.updateTopic(topic, payload);
                });
            });
        }
    }
}

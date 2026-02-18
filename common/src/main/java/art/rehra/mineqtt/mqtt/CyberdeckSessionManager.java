package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CyberdeckSessionManager {
    private static final Map<UUID, Set<String>> playerDiscoveredTopics = new ConcurrentHashMap<>();
    private static final Set<UUID> activeListeners = ConcurrentHashMap.newKeySet();
    private static boolean isGlobalSubscribed = false;

    public static void toggleListening(ServerPlayer player, boolean listening) {
        if (listening) {
            activeListeners.add(player.getUUID());
            ensureGlobalSubscription();
        } else {
            activeListeners.remove(player.getUUID());
            checkGlobalUnsubscribe();
        }
    }

    public static boolean isListening(ServerPlayer player) {
        return activeListeners.contains(player.getUUID());
    }

    private static void ensureGlobalSubscription() {
        if (!isGlobalSubscribed && MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.LOGGER.info("[Cyberdeck] Starting global # subscription for active sessions");
            MineQTT.mqttClient.subscribeWith()
                    .topicFilter("#")
                    .callback(publish -> {
                        String topic = publish.getTopic().toString();
                        String payload = new String(publish.getPayloadAsBytes());
                        broadcastToListeners(topic, payload);
                    })
                    .send();
            isGlobalSubscribed = true;
        }
    }

    private static void checkGlobalUnsubscribe() {
        if (isGlobalSubscribed && activeListeners.isEmpty() && MineQTT.mqttClient != null && MineQTT.mqttClient.getState().isConnected()) {
            MineQTT.LOGGER.info("[Cyberdeck] Stopping global # subscription (no active sessions)");
            MineQTT.mqttClient.unsubscribeWith()
                    .topicFilter("#")
                    .send();
            isGlobalSubscribed = false;
        }
    }

    private static void broadcastToListeners(String topic, String payload) {
        // Broadcast discovery and payload updates to all active listeners
        for (UUID playerUuid : activeListeners) {
            playerDiscoveredTopics.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet()).add(topic);
        }

        if (art.rehra.mineqtt.MineQTT.currentServer != null) {
            final String fTopic = topic;
            final String fPayload = payload;
            art.rehra.mineqtt.MineQTT.currentServer.execute(() -> {
                handleDiscovery(fTopic, fPayload, art.rehra.mineqtt.MineQTT.currentServer);
            });
        }
    }

    public static void handleDiscovery(String topic, String payload, net.minecraft.server.MinecraftServer server) {
        for (UUID uuid : activeListeners) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                broadcastToPlayer(player, topic, payload);
            }
        }
    }

    @SuppressWarnings("removal")
    public static void broadcastToPlayer(ServerPlayer player, String topic, String payload) {
        if (!isListening(player)) return;

        net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        buf.writeUtf(topic);
        buf.writeUtf(payload);

        dev.architectury.networking.NetworkManager.sendToPlayer(player, art.rehra.mineqtt.network.MineqttNetworking.CYBERDECK_TOPIC_UPDATE, buf);
    }

    public static void tick(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            if (activeListeners.contains(uuid)) {
                // Check if player still has a cyberdeck in inventory
                boolean hasDeck = false;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (player.getInventory().getItem(i).getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem) {
                        hasDeck = true;
                        break;
                    }
                }

                if (!hasDeck) {
                    toggleListening(player, false);
                }
            }
        }
    }

    public static void onPlayerLoggedOut(ServerPlayer player) {
        activeListeners.remove(player.getUUID());
        playerDiscoveredTopics.remove(player.getUUID());
        checkGlobalUnsubscribe();
    }
}

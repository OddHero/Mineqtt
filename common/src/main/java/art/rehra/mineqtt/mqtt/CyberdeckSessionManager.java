package art.rehra.mineqtt.mqtt;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.items.CyberdeckItem;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.network.MineqttNetworking.CyberdeckTopicUpdatePayload;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.security.RateLimit;
import art.rehra.mineqtt.security.TokenBucket;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The server side of the Configurator's explorer (the Cyberdeck's, design §8.4): its listen sessions and its publishes.
 * <ul>
 *     <li>A player has at most one listen session. It subscribes the player's {@link TopicAcl#listenScope}: this
 *     server's namespace plus the raw allowlist filters they may listen on, or the whole broker for an admin when
 *     {@code acl.explorerBrokerWide} is set. The scope follows the player's rights: it is built again on a config reload
 *     and whenever the player gains or loses operator or admin rights. Every message is then judged for that player
 *     ({@link TopicAcl#explorerExposure}): other players' private topics are hidden, a device's topics are shown only to
 *     players who may configure it, groups and unregistered devices only to operators who may build (P2), a player's
 *     presence device only to admins and that player (M4 plan D10), and discovery
 *     payloads are cut to their size. At most
 *     {@value #FORWARDED_PER_SECOND} messages per second reach a player.</li>
 *     <li>A publish passes the full {@link TopicAcl} for the player, is never retained and is limited to
 *     {@value #PUBLISHES_PER_SECOND} per second. In private mode the topic lies in the player's own subtree,
 *     {@code <base>/<bridgeId>/raw/players/<uuid>/<topic>}.</li>
 * </ul>
 * Whether a player listens and publishes privately is kept here, not on the item (M2-13): a player who leaves while
 * listening listens again when they join with a Configurator, as long as the server runs; private mode lasts as long.
 * Server thread only.
 */
public final class CyberdeckSessionManager {
    public static final int FORWARDED_PER_SECOND = 50;
    public static final int PUBLISHES_PER_SECOND = 2;
    // Retained state included; this server's own publishes arrive through the loopback, also with the broker down.
    private static final SubOptions LISTEN_OPTIONS = new SubOptions(1, true, true, SubOptions.MIRROR.maxPayloadBytes(), null);
    private static final String ELLIPSIS = "…";

    private static final Map<UUID, Session> sessions = new HashMap<>();
    // Players who left while listening, and players who publish into their private subtree.
    private static final Set<UUID> resumeOnJoin = new HashSet<>();
    private static final Set<UUID> privatePublishers = new HashSet<>();
    private static final RateLimit publishes = new RateLimit(PUBLISHES_PER_SECOND);

    private CyberdeckSessionManager() {
    }

    private static final class Session {
        private final UUID player;
        private final TokenBucket forwarded = new TokenBucket(FORWARDED_PER_SECOND, FORWARDED_PER_SECOND, System::nanoTime);
        private final List<Subscription> subscriptions = new ArrayList<>();
        // The rights the subscriptions were built for; null while none could be opened.
        private @Nullable TopicAcl.Subject subject;
        // The message forwarded last: a message two of the session's filters match is handed over once per filter.
        private Inbound last;

        private Session(UUID player) {
            this.player = player;
        }
    }

    public static void toggleListening(ServerPlayer player, boolean listening) {
        if (listening) {
            if (sessions.containsKey(player.getUUID())) return;
            Session session = new Session(player.getUUID());
            sessions.put(player.getUUID(), session);
            open(session, player);
        } else {
            Session session = sessions.remove(player.getUUID());
            if (session != null) close(session);
        }
    }

    public static boolean isListening(ServerPlayer player) {
        return sessions.containsKey(player.getUUID());
    }

    /** Whether the player's explorer publishes into their private subtree. */
    public static boolean isPrivate(ServerPlayer player) {
        return privatePublishers.contains(player.getUUID());
    }

    public static void setPrivate(ServerPlayer player, boolean privateMode) {
        if (privateMode) {
            privatePublishers.add(player.getUUID());
        } else {
            privatePublishers.remove(player.getUUID());
        }
    }

    /**
     * Publishes from the player's open explorer.
     *
     * @param topic the topic the player entered; in private mode it is relative to the player's own subtree
     */
    public static void publish(ServerPlayer player, String topic, String payload) {
        Optional<String> refusal = publishRefusal(player, topic, payload);
        refusal.ifPresent(reason -> player.sendSystemMessage(Component.translatable("mineqtt.cyberdeck.publish_refused", topic, reason), true));
    }

    /** The ACL changed (a config reload): every session listens on the scope its player has now. */
    public static void scopeChanged(MinecraftServer server) {
        for (Session session : sessions.values()) {
            reopen(session, server.getPlayerList().getPlayer(session.player));
        }
    }

    /**
     * Ends the session of a player who no longer carries a Cyberdeck, and reopens the session of a player whose rights
     * changed ({@code /deop}, a permissions mod), so a scope never outlives the rights it was granted for.
     */
    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Session session = sessions.get(player.getUUID());
            if (session == null) continue;
            if (!carriesCyberdeck(player)) {
                toggleListening(player, false);
            } else if (!AclContext.of(player).equals(session.subject) && runtime(server).isPresent()) {
                reopen(session, player);
            }
        }
    }

    public static void onPlayerLoggedIn(ServerPlayer player) {
        // A player who was listening when they left listens again, if they still carry a Configurator.
        if (resumeOnJoin.remove(player.getUUID()) && carriesCyberdeck(player)) {
            toggleListening(player, true);
            MineQTT.LOGGER.debug("[Cyberdeck] Resumed listening for player {}", player.getGameProfile().name());
        }
    }

    public static void onPlayerLoggedOut(ServerPlayer player) {
        if (isListening(player)) resumeOnJoin.add(player.getUUID());
        toggleListening(player, false);
        publishes.forget(player.getUUID());
    }

    /** Forgets the sessions and the players' explorer state of a stopping server. */
    public static void onServerStopping() {
        sessions.values().forEach(CyberdeckSessionManager::close);
        sessions.clear();
        resumeOnJoin.clear();
        privatePublishers.clear();
        publishes.clear();
    }

    private static Optional<String> publishRefusal(ServerPlayer player, String topic, String payload) {
        if (!C2SGuard.fitsPayload(payload)) return Optional.of("the payload is larger than " + C2SGuard.MAX_PAYLOAD_BYTES + " bytes");
        Optional<MineqttRuntime> running = runtime(player.level().getServer());
        if (running.isEmpty()) return Optional.of("MineQTT is not running");
        TopicAcl acl = running.get().acl();
        boolean privateMode = isPrivate(player);
        if (topic.isBlank() && !privateMode) return Optional.of("no topic is set");
        String target = privateMode ? acl.privateTopic(player.getUUID(), topic) : topic;
        TopicAcl.Decision decision = acl.checkPublish(AclContext.of(player), target);
        if (!decision.allowed()) return Optional.of(decision.message());
        if (!publishes.tryTake(player.getUUID())) return Optional.of("at most " + PUBLISHES_PER_SECOND + " messages per second");
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        running.get().mqtt().publish(Outbound.raw(target, bytes, 0, player.getUUID()));
        MineQTT.LOGGER.debug("[Cyberdeck] {} published {} bytes to {}", player.getGameProfile().name(), bytes.length, target);
        return Optional.empty();
    }

    private static void open(Session session, ServerPlayer player) {
        Optional<MineqttRuntime> running = runtime(player.level().getServer());
        if (running.isEmpty()) return;
        MineqttRuntime runtime = running.get();
        session.subject = AclContext.of(player);
        List<String> scope = runtime.acl().listenScope(session.subject);
        for (String filter : scope) {
            session.subscriptions.add(runtime.mqtt().subscribe(filter, LISTEN_OPTIONS, message -> forward(runtime, session, message)));
        }
        MineQTT.LOGGER.debug("[Cyberdeck] {} listens on {}", player.getGameProfile().name(), scope);
    }

    /** Subscribes the scope the player has now. The service keeps the broker subscriptions of filters both scopes share. */
    private static void reopen(Session session, @Nullable ServerPlayer player) {
        List<Subscription> previous = new ArrayList<>(session.subscriptions);
        session.subscriptions.clear();
        session.subject = null;
        if (player != null) open(session, player);
        previous.forEach(Subscription::close);
    }

    private static void close(Session session) {
        session.subscriptions.forEach(Subscription::close);
        session.subscriptions.clear();
        session.subject = null;
        session.last = null;
    }

    private static void forward(MineqttRuntime runtime, Session session, Inbound message) {
        if (message == session.last) return;
        session.last = message;
        ServerPlayer player = runtime.server().getPlayerList().getPlayer(session.player);
        if (player == null || message.topic().length() > CyberdeckTopicUpdatePayload.MAX_TOPIC_CHARS) return;
        TopicAcl.Subject subject = AclContext.of(player);
        TopicAcl.Exposure exposure = runtime.acl().explorerExposure(subject, message.topic());
        // An operator who may not build configures nothing (P2), so they see what any other player sees.
        boolean operator = subject.op() && DeviceAccess.mayBuild(player);
        boolean visible = switch (exposure.kind()) {
            case SHOW, REDACT -> true;
            case HIDE -> false;
            case DEVICE -> {
                DeviceRecord record = runtime.registry().bySlug(exposure.slug());
                yield record == null ? operator : DeviceAccess.check(player, record, MineqttPermission.CONFIGURE);
            }
            case GROUP -> operator;
            // A player's presence device (M4 plan D10): admins and that player only.
            case PLAYER -> AclContext.mayReadPlayerTopic(subject, exposure.slug(), runtime.presence()::slugOf);
        };
        if (!visible || !session.forwarded.tryTake()) return;
        String payload = exposure.kind() == TopicAcl.Exposure.Kind.REDACT
                ? "<" + message.payload().length + " bytes>"
                : truncate(message.text(), CyberdeckTopicUpdatePayload.MAX_PAYLOAD_CHARS);
        NetworkManager.sendToPlayer(player, new CyberdeckTopicUpdatePayload(message.topic(), payload));
    }

    /** Cuts text to the payload codec's limit without splitting a surrogate pair. */
    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int end = maxChars - ELLIPSIS.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + ELLIPSIS;
    }

    private static Optional<MineqttRuntime> runtime(MinecraftServer server) {
        return MineqttRuntime.current().filter(running -> running.server() == server);
    }

    private static boolean carriesCyberdeck(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() instanceof CyberdeckItem) return true;
        }
        return false;
    }
}

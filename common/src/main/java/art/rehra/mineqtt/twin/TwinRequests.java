package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.flow.core.Root;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.Call;
import art.rehra.mineqtt.twin.core.HaCalls;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * The write-back to Home Assistant on one server (M3 plan D10, D11): what a mirrored device's forwarded command, or a
 * target, becomes on {@code <base>/<bridge>/bridge/ha_request}, and what comes back on {@code ha_response}. Every
 * request passes, in this order:
 * <ol>
 *     <li>the root gate ({@link TwinGate}): only a player or the world may cause one; anything else counts as refused
 *     for its root and builds nothing;</li>
 *     <li>the adapter's translation through {@link HaCalls} ({@link TwinAdapter#forward}), which refuses locally what
 *     cannot be sent (a toggle of an unavailable entity, a read-only domain) and skips what the LIVE mirror already shows
 *     (dedupe);</li>
 *     <li>{@code twin.haActionAllowlist};</li>
 *     <li>the circuit breaker: a device whose control is paused sends nothing until a player resumes it;</li>
 *     <li>the connection: a request is never kept for later ({@link Outbound#event}, dropped while offline), so the
 *     player hears it was not sent;</li>
 *     <li>the rates, each over the last second: {@value #DEVICE_PER_SECOND} per device, {@value #PLAYER_PER_SECOND} per
 *     player and {@code twin.maxRequestsPerSecond} for the bridge. A value with data (a brightness, a colour, a number)
 *     over a rate waits instead, newest wins per device and entity (a Wall Switch's two targets wait apart), and is
 *     sent when the rates allow, and so does an explicit state the world asked for (a redstone edge's {@code turn_on}),
 *     so a burst of edges ends with the entity in the world's last state ({@link #holds}; a later
 *     edge to the state the entity already shows drops the waiting one, {@link #supersede}); a player's switch, a toggle and a press over a rate are
 *     refused, counted per device and logged once per burst ({@link #rateRefusals});</li>
 *     <li>the breaker's count: a device that would send more than {@value #BREAKER_LIMIT} requests the world caused
 *     within {@value #BREAKER_WINDOW_MILLIS} ms has its control paused instead; a world-caused request a rate refused
 *     counts as such an attempt, so a target the rates keep refusing pauses too; a player's own commands are not
 *     counted ({@link #countsForBreaker}).</li>
 * </ol>
 * A device that mirrors an MQTT device preset (M3-17) is forwarded the same way, with the same gate, translation,
 * breaker, connection check and rates; its call is rendered as the preset's command ({@link BindingPreset#render}) and
 * published on the preset's command topic, which the ACL must allow the device's owner at every send (an admin adds
 * e.g. {@code zigbee2mqtt/+/set} to {@code acl.rawAllow}); {@code twin.haActionAllowlist} is Home Assistant's gate and
 * does not apply. Such a request waits until the state topic brings a new payload.
 * <p>
 * A sent request waits ({@link PendingRequests}) until its entity is applied again, the control blueprint refuses it,
 * or it times out. {@code ha_response} is advisory: any broker client can publish it, so it only ends the wait and tells
 * the player why; nothing here can change a device. Minecraft-free; server thread only.
 */
public final class TwinRequests {
    /** Requests a device may send within one second. */
    public static final int DEVICE_PER_SECOND = 4;
    /** Requests a player may cause within one second. */
    public static final int PLAYER_PER_SECOND = 4;
    /** The window the rates count over, in milliseconds. */
    public static final long WINDOW_MILLIS = 1_000;
    /**
     * A device that would send more requests the world caused than this within {@link #BREAKER_WINDOW_MILLIS} has its
     * control paused ({@link #countsForBreaker}).
     */
    public static final int BREAKER_LIMIT = 8;
    public static final long BREAKER_WINDOW_MILLIS = 10_000;
    /** How long {@code /mineqtt twin ping} waits for the control blueprint's {@code pong}, in milliseconds. */
    public static final long PING_TIMEOUT_MILLIS = 5_000;
    /** The largest {@code ha_response} read; a larger one is dropped. */
    public static final int MAX_RESPONSE_BYTES = 1024;
    /** The longest id an answer may carry: the control blueprint keeps the first 16 characters of a request's id. */
    public static final int MAX_ID_LENGTH = 16;
    /** The length of the ids MineQTT gives its requests, in hex digits. */
    public static final int ID_LENGTH = 12;
    /** The longest device and player names a request carries in its {@code origin}. */
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_PLAYER_NAME_LENGTH = 32;
    /** The request format's version, {@code "v"}. */
    public static final int VERSION = 1;
    /** What a preset device's waiting requests are keyed by, before the state topic; no entity id has the bar. */
    public static final String TOPIC_KEY_PREFIX = "topic|";
    private static final String HOME_ASSISTANT = " in Home Assistant";
    /** How the bridge subscribes to {@code ha_response}: never a retained answer, never one over 1 KiB. */
    public static final SubOptions RESPONSES = new SubOptions(1, false, false, MAX_RESPONSE_BYTES, null);
    /** What the control blueprint answers: it ran the action, or why not ({@code pong} answers a ping). */
    public static final String ACCEPTED = "accepted";
    public static final String PONG = "pong";
    public static final Set<String> REFUSALS = Set.of("bad_request", "domain_mismatch", "no_such_entity", "domain_not_allowed",
            "action_not_allowed", "not_labelled");

    /** Where requests go: the server's MQTT connection. */
    public interface Transport {
        boolean connected();

        void publish(Outbound message);
    }

    /** Who hears what came of requests later: the device runtime. Nothing here can change a device. */
    public interface Listener {
        /** A request of {@code device} started or stopped waiting: the device's clients show it as pending or not. */
        void pendingChanged(UUID device, boolean pending);

        /** The breaker paused the device's control, or a player resumed it. */
        void pausedChanged(UUID device, boolean paused);

        /** Something to tell the player who caused a request, and for a pause the device's owner. */
        void notice(Notice notice);

        /**
         * A rate refused one of the device's requests, so its count grew ({@link #rateRefusals}): the device's Twin tab shows
         * the count.
         */
        default void rateRefused(UUID device) {
        }
    }

    /** Who caused a request; a request the world caused has none. */
    public record Requester(UUID id, String name) {
        public Requester {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * The device a request comes from.
     *
     * @param objectId its Home Assistant object id, {@code mineqtt_<uuid32>}
     * @param player   who caused it; null for the world (a lever, a wire) and the console
     */
    public record Origin(UUID device, String objectId, String name, @Nullable Requester player) {
        public Origin {
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A later message about a request.
     *
     * @param player who caused the request; null when the world did
     * @param detail the English sentence for the log; the lang key and its arguments are the runtime's
     */
    public record Notice(Kind kind, UUID device, String deviceName, String entityId, @Nullable Requester player, String detail) {
        public enum Kind {
            /** Its entity did not change within {@link PendingRequests#TIMEOUT_MILLIS}. */
            TIMEOUT,
            /** The control blueprint refused it; {@code detail} is its status. */
            REFUSED,
            /** The breaker paused the device's control. */
            PAUSED
        }
    }

    /** What became of a request. */
    public sealed interface Result permits Sent, Held, Skipped, Refused {
        /** Whether the device took it: sent, waiting for the rate, or not needed. A wire's message counts as delivered. */
        default boolean taken() {
            return !(this instanceof Refused);
        }
    }

    /** Published; {@code id} is what the answer names. */
    public record Sent(String id, String action) implements Result {
    }

    /** Over a rate: waits, newest wins per device and entity, and is sent when the rates allow. */
    public record Held(String action) implements Result {
    }

    /** Nothing to send: the LIVE mirror already shows it (dedupe), or the value asks for nothing. */
    public record Skipped(String reason) implements Result {
    }

    /** Not sent, and why. */
    public record Refused(Refusal refusal, String reason) implements Result {
        public Refused {
            Objects.requireNonNull(refusal, "refusal");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why a request was not sent. */
    public enum Refusal {
        /** Its root may not cause a request (D10): incoming state, a {@code /set} topic, a raw topic, a restart. */
        ROOT,
        /** {@link HaCalls} cannot make a call of it: nothing known to toggle, unavailable, read-only, a bad value. */
        LOCAL,
        /** Its domain is not in {@code twin.haActionAllowlist}. */
        NOT_ALLOWED,
        /** The breaker paused the device's control. */
        PAUSED,
        /** A switch over a rate. */
        RATE,
        /** The bridge is not connected to the broker. */
        OFFLINE
    }

    /** The control blueprint's last answer for a device, for its info and the twin status. */
    public record Answer(String status, String entityId, long at) {
    }

    /**
     * The requests of a device the rates refused, for its info, its status line and the Twin tab's
     * Diagnostics: how many since it was loaded or resumed, and the last one.
     *
     * @param entityId the entity (or a preset device's key, {@link #topicKey}) the last refused request was for
     * @param reason   why, as the player hears it
     * @param at       when, in this runtime's clock
     */
    public record RateRefusals(long refused, String entityId, String reason, long at) {
        /** The entity or topic the last one was for, as players read it. */
        public String target() {
            return entityId.startsWith(TOPIC_KEY_PREFIX) ? "topic " + entityId.substring(TOPIC_KEY_PREFIX.length()) : entityId;
        }
    }

    /** What {@code /mineqtt twin status} shows. */
    public record Status(long sent, long held, long skipped, Map<Refusal, Long> refused, Map<Root, Long> roots, int pending, int waiting,
                         int paused, Map<String, Long> answers, long unmatched, long droppedAnswers) {
    }

    // A request over a rate that waits for it, with the allowlist or ACL check it passed (asked again when it is sent) and
    // the message it becomes once sent (given its id).
    private record HeldRequest(Origin origin, String entityId, Call call, Supplier<Optional<String>> allowed, Function<String, Outbound> message) {
    }

    // What a held request waits in: one slot per device and entity (a preset device's key), so a device with two targets
    // (a Wall Switch's left and right) never loses one target's request to the other's.
    private record HeldKey(UUID device, String entityId) {
    }

    /**
     * Where a preset device's commands go (M3-17).
     *
     * @param commandTopic the preset's command topic for the device
     * @param acl          why the ACL refuses the device's owner that topic now, or empty
     */
    public record PresetTarget(String stateTopic, String commandTopic, BindingPreset preset, Function<String, Optional<String>> acl) {
        public PresetTarget {
            Objects.requireNonNull(stateTopic, "stateTopic");
            Objects.requireNonNull(commandTopic, "commandTopic");
            Objects.requireNonNull(preset, "preset");
            Objects.requireNonNull(acl, "acl");
        }

        /** The key its waiting requests have. */
        public String key() {
            return topicKey(stateTopic);
        }
    }

    // The ping waiting for its pong.
    private record Ping(String id, long sentAt, Consumer<String> answer) {
    }

    private final Transport transport;
    private final Supplier<TopicScheme> topics;
    private final Supplier<? extends Collection<String>> allowedDomains;
    private final IntSupplier bridgePerSecond;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final MqttLog log;
    private Listener listener = new Listener() {
        @Override
        public void pendingChanged(UUID device, boolean pending) {
        }

        @Override
        public void pausedChanged(UUID device, boolean paused) {
        }

        @Override
        public void notice(Notice notice) {
        }
    };
    private final PendingRequests pending = new PendingRequests();
    private final Map<UUID, ArrayDeque<Long>> deviceWindows = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> playerWindows = new HashMap<>();
    private final ArrayDeque<Long> bridgeWindow = new ArrayDeque<>();
    private final Map<UUID, ArrayDeque<Long>> breakerWindows = new HashMap<>();
    private final Set<UUID> paused = new HashSet<>();
    private final Map<HeldKey, HeldRequest> held = new LinkedHashMap<>();
    private final Map<UUID, Answer> answers = new HashMap<>();
    private final Map<UUID, RateRefusals> rateRefusals = new HashMap<>();
    // Devices whose last request was refused by a rate, nothing sent since: their next refusal is not logged again.
    private final Set<UUID> rateBursts = new HashSet<>();
    private final Map<Refusal, Long> refused = new EnumMap<>(Refusal.class);
    private final Map<Root, Long> roots = new EnumMap<>(Root.class);
    private final Map<String, Long> answerCounts = new TreeMap<>();
    private @Nullable Ping ping;
    private long sent;
    private long heldCount;
    private long skipped;
    private long unmatched;
    private long droppedAnswers;
    private boolean stopped;

    /**
     * @param allowedDomains  {@code twin.haActionAllowlist}, read at every request so a config reload takes effect at once
     * @param bridgePerSecond {@code twin.maxRequestsPerSecond}, likewise
     * @param clock           milliseconds
     */
    public TwinRequests(Transport transport, Supplier<TopicScheme> topics, Supplier<? extends Collection<String>> allowedDomains, IntSupplier bridgePerSecond,
                        LongSupplier clock, RandomGenerator random, MqttLog log) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.topics = Objects.requireNonNull(topics, "topics");
        this.allowedDomains = Objects.requireNonNull(allowedDomains, "allowedDomains");
        this.bridgePerSecond = Objects.requireNonNull(bridgePerSecond, "bridgePerSecond");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Who hears what came of requests; set once by the device runtime. */
    public void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * A command forwarded to a mirrored device's source (D11): the root gate first, then the adapter's calls, each sent
     * as its own request.
     *
     * @param mirror what the source shows now, {@link HaCalls.Mirror#NONE} when nothing is known (an unloaded device)
     * @param root   the root the command runs in
     * @return a refusal if any call was refused, else what the first call came to ({@link Skipped} when there was none)
     */
    public Result forward(Origin origin, String entityId, TwinAdapter adapter, Binding binding, JsonObject command, HaCalls.Mirror mirror, Root root) {
        Optional<Refused> gated = gate(root);
        if (gated.isPresent()) return gated.get();
        Result result = null;
        List<HaCalls.Outcome> outcomes = adapter.forward(command, mirror, binding);
        supersede(origin, entityId, outcomes, root);
        for (HaCalls.Outcome outcome : outcomes) {
            Result next = outcome(origin, entityId, outcome, root);
            if (result == null || next instanceof Refused || result instanceof Skipped && !(next instanceof Skipped)) result = next;
        }
        return result != null ? result : skipped("nothing in it is sent to Home Assistant");
    }

    /**
     * A command forwarded to a mirrored MQTT device through its preset (M3-17): the root gate first, then the adapter's
     * calls, as for an entity, each rendered as the preset's command and sent to {@code target}.
     *
     * @param mirror what the preset makes of the state topic's last payload, {@link HaCalls.Mirror#NONE} before any
     */
    public Result forwardPreset(Origin origin, PresetTarget target, TwinAdapter adapter, Binding binding, JsonObject command, HaCalls.Mirror mirror, Root root) {
        Optional<Refused> gated = gate(root);
        if (gated.isPresent()) return gated.get();
        Result result = null;
        List<HaCalls.Outcome> outcomes = adapter.forward(command, mirror, binding);
        supersede(origin, target.key(), outcomes, root);
        for (HaCalls.Outcome outcome : outcomes) {
            Result next = switch (outcome) {
                case HaCalls.Send(Call call) -> sendPreset(origin, target, call, root);
                case HaCalls.Skip skip -> skipped(skip.reason());
                // The service-call table speaks of Home Assistant; a preset device's state comes from its own topic.
                case HaCalls.Refused(HaCalls.Reason reason, String detail) -> refused(Refusal.LOCAL, binding.describeSource() + ": "
                        + (detail.endsWith(HOME_ASSISTANT) ? detail.substring(0, detail.length() - HOME_ASSISTANT.length()) + " on its topic" : detail));
            };
            if (result == null || next instanceof Refused || result instanceof Skipped && !(next instanceof Skipped)) result = next;
        }
        return result != null ? result : skipped("nothing in it is sent to the MQTT device");
    }

    /**
     * One call to a preset device (M3-17): the gate, the preset's command for it, the ACL for the command topic, the
     * breaker, the connection and the rates, in that order.
     */
    public Result sendPreset(Origin origin, PresetTarget target, Call call, Root root) {
        Optional<Refused> gated = gate(root);
        if (gated.isPresent()) return gated.get();
        if (stopped) return refused(Refusal.OFFLINE, "not sent: the server is stopping");
        BindingPreset.Rendered rendered = target.preset().render(call);
        if (rendered instanceof BindingPreset.Refused(String reason)) return refused(Refusal.LOCAL, reason);
        BindingPreset.Command command = (BindingPreset.Command) rendered;
        Supplier<Optional<String>> allowed = () -> target.acl().apply(target.commandTopic()).map(reason -> "not sent: " + reason);
        Optional<String> denied = allowed.get();
        if (denied.isPresent()) return refused(Refusal.NOT_ALLOWED, denied.get());
        boolean retain = target.preset().commandRetain();
        return dispatch(origin, target.key(), call, allowed, id -> new Outbound(target.commandTopic(), command.bytes(), 1, retain, OfflinePolicy.DROP,
                Outbound.Delivery.EDGE, origin.device()));
    }

    /** The key a preset device's waiting requests have: {@value #TOPIC_KEY_PREFIX} and its state topic. */
    public static String topicKey(String stateTopic) {
        return TOPIC_KEY_PREFIX + stateTopic;
    }

    /**
     * What {@link HaCalls} made of a value for {@code entityId} becomes: a call is {@link #send sent}, a skip (dedupe) is
     * {@link Skipped} (and supersedes a stale state that waited for the rate, {@link #supersede}), a refusal is refused
     * locally with its reason.
     */
    public Result request(Origin origin, String entityId, HaCalls.Outcome outcome, Root root) {
        supersede(origin, entityId, List.of(outcome), root);
        return outcome(origin, entityId, outcome, root);
    }

    private Result outcome(Origin origin, String entityId, HaCalls.Outcome outcome, Root root) {
        return switch (outcome) {
            case HaCalls.Send(Call call) -> send(origin, entityId, call, root);
            case HaCalls.Skip skip -> skipped(skip.reason());
            case HaCalls.Refused(HaCalls.Reason reason, String detail) -> refused(Refusal.LOCAL, entityId + ": " + detail);
        };
    }

    /**
     * A value the world caused that the LIVE mirror already shows (every outcome a dedupe {@link
     * HaCalls.Skip}) is the world's newest state, so a request still waiting for the rate for the same entity that would
     * leave another state is dropped; sent late, it would leave the entity opposite to the world (a lever flipped back to
     * the state the entity already has). A waiting value with data (a brightness) survives a skipped "on", which it does
     * not contradict. Nothing reaches {@link #dispatch} for a skip, so this is the only place it is dropped.
     */
    private void supersede(Origin origin, String key, List<HaCalls.Outcome> outcomes, Root root) {
        if (!countsForBreaker(origin) || !TwinGate.mayRequest(root) || outcomes.isEmpty()) return;
        boolean on = false;
        for (HaCalls.Outcome outcome : outcomes) {
            if (!(outcome instanceof HaCalls.Skip skip) || skip.already().isEmpty()) return;
            on = skip.already().get();
        }
        HeldKey slot = new HeldKey(origin.device(), key);
        HeldRequest waiting = held.get(slot);
        if (waiting != null && (!on || !waits(waiting.call()))) held.remove(slot);
    }

    /**
     * One call to {@code entityId}, from a forward or a target ({@link HaSink}): the gate, the allowlist, the breaker,
     * the connection and the rates, in that order.
     */
    public Result send(Origin origin, String entityId, Call call, Root root) {
        Optional<Refused> gated = gate(root);
        if (gated.isPresent()) return gated.get();
        if (stopped) return refused(Refusal.OFFLINE, "not sent: the server is stopping");
        Supplier<Optional<String>> allowed = () -> allowedDomains.get().contains(call.domain()) ? Optional.empty()
                : Optional.of(call.domain() + " entities may not be controlled on this server (twin.haActionAllowlist)");
        Optional<String> denied = allowed.get();
        if (denied.isPresent()) return refused(Refusal.NOT_ALLOWED, denied.get());
        return dispatch(origin, entityId, call, allowed, id -> Outbound.event(topics.get().haRequest(), request(id, call, entityId, origin)));
    }

    // The breaker, the connection and the rates of one request whose message is `message` (given its id). `allowed` is
    // the allowlist or ACL check it passed, asked again before a held request is sent (a config reload may have closed it).
    private Result dispatch(Origin origin, String entityId, Call call, Supplier<Optional<String>> allowed, Function<String, Outbound> message) {
        if (paused.contains(origin.device())) return refused(Refusal.PAUSED, pausedReason(origin.name()));
        if (!transport.connected()) return refused(Refusal.OFFLINE, "not sent: the bridge is offline");
        long now = clock.getAsLong();
        Optional<String> overRate = overRate(origin, now);
        if (overRate.isPresent()) {
            if (!holds(call, origin)) return rateRefused(origin, entityId, overRate.get(), now);
            held.put(new HeldKey(origin.device(), entityId), new HeldRequest(origin, entityId, call, allowed, message));
            heldCount++;
            return new Held(call.action());
        }
        // A newer value for the same entity replaces one that waited.
        held.remove(new HeldKey(origin.device(), entityId));
        return publish(origin, entityId, call, message, now);
    }

    /**
     * The root gate ({@link TwinGate}), counted: a refusal for a root that may not cause a request, before anything is
     * built for it; empty for a root that may.
     */
    public Optional<Refused> gate(Root root) {
        if (TwinGate.mayRequest(root)) return Optional.empty();
        roots.merge(root, 1L, Long::sum);
        return Optional.of(refused(Refusal.ROOT, "not sent: " + TwinGate.refusal(root).orElseThrow()));
    }

    /**
     * {@code ha_response}: an answer of the control blueprint, advisory (D11). A retained, oversized or malformed answer,
     * or one whose id names no waiting request, is dropped. {@code accepted} keeps the request waiting for its entity, a
     * refusal ends the wait and tells the player why, {@code pong} answers a ping. Nothing here changes a device.
     */
    public void response(byte[] payload, boolean retained) {
        if (stopped) return;
        if (retained || payload.length > MAX_RESPONSE_BYTES) {
            droppedAnswers++;
            return;
        }
        JsonObject answer;
        try {
            JsonElement json = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            if (!json.isJsonObject()) throw new JsonParseException("not an object");
            answer = json.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            droppedAnswers++;
            return;
        }
        Optional<String> id = text(answer, "id", MAX_ID_LENGTH);
        // Any broker client may answer, so the status is kept only as safe text.
        Optional<String> status = text(answer, "status", 32).map(text -> Coerce.sanitize(text, 32)).filter(text -> !text.isEmpty());
        if (id.isEmpty() || status.isEmpty()) {
            droppedAnswers++;
            return;
        }
        Ping waiting = ping;
        if (waiting != null && waiting.id().equals(id.get())) {
            if (!status.get().equals(PONG)) return;
            ping = null;
            count(status.get());
            int blueprint = answer.has("blueprint") && answer.get("blueprint").isJsonPrimitive() && answer.get("blueprint").getAsJsonPrimitive().isNumber()
                    ? answer.get("blueprint").getAsInt() : 0;
            answer(waiting, "pong: the control blueprint answers" + (blueprint > 0 ? " (blueprint version " + blueprint + ")" : ""));
            return;
        }
        Optional<PendingRequests.Pending> request = pending.get(id.get());
        if (request.isEmpty()) {
            unmatched++;
            return;
        }
        PendingRequests.Pending known = request.get();
        count(status.get());
        answers.put(known.device(), new Answer(status.get(), known.entityId(), clock.getAsLong()));
        if (status.get().equals(ACCEPTED)) {
            pending.accept(known.id());
            return;
        }
        if (status.get().equals(PONG)) return;
        pending.remove(known.id());
        stoppedWaiting(known.device());
        String reason = REFUSALS.contains(status.get()) ? status.get() : "answered " + status.get();
        log.debug("Home Assistant refused " + known.action() + " for " + known.entityId() + " from device " + known.deviceName() + ": " + reason);
        listener.notice(new Notice(Notice.Kind.REFUSED, known.device(), known.deviceName(), known.entityId(), known.player(), status.get()));
    }

    /** An entity was applied again: the requests waiting for it are done (D6). */
    public void applied(String entityId) {
        for (PendingRequests.Pending done : pending.applied(entityId)) stoppedWaiting(done.device());
    }

    /**
     * Once per tick and pump: sends the values that waited for the rates, ends the requests that timed out (their player
     * hears "no change from Home Assistant") and the ping that got no answer.
     */
    public void tick() {
        if (stopped) return;
        long now = clock.getAsLong();
        Iterator<HeldRequest> waiting = held.values().iterator();
        while (waiting.hasNext()) {
            HeldRequest request = waiting.next();
            UUID device = request.origin().device();
            if (paused.contains(device) || !transport.connected()) {
                // A value that waited is never sent late: not after a pause, not after the broker came back.
                waiting.remove();
                continue;
            }
            // The allowlist or the ACL is asked again at every send (M3-17 decision 3): a reload may have closed it meanwhile.
            Optional<String> denied = request.allowed().get();
            if (denied.isPresent()) {
                waiting.remove();
                refused(Refusal.NOT_ALLOWED, denied.get());
                log.debug("Device " + request.origin().name() + " did not send " + request.call().action() + " for "
                        + new RateRefusals(0, request.entityId(), "", now).target() + " that waited for the rate: " + denied.get());
                continue;
            }
            if (overRate(request.origin(), now).isPresent()) continue;
            waiting.remove();
            publish(request.origin(), request.entityId(), request.call(), request.message(), now);
        }
        for (PendingRequests.Pending expired : pending.expired(now)) {
            stoppedWaiting(expired.device());
            listener.notice(new Notice(Notice.Kind.TIMEOUT, expired.device(), expired.deviceName(), expired.entityId(), expired.player(),
                    "no change from Home Assistant"));
        }
        Ping unanswered = ping;
        if (unanswered != null && now - unanswered.sentAt() >= PING_TIMEOUT_MILLIS) {
            ping = null;
            answer(unanswered, "no answer from the control blueprint within " + PING_TIMEOUT_MILLIS / 1000
                    + " s: is it installed, and does its request topic match " + topics.get().haRequest() + "?");
        }
    }

    /** Whether a tick has work: values waiting for the rates, requests waiting for their entity, or a ping. */
    public boolean pending() {
        return !stopped && (!held.isEmpty() || !pending.isEmpty() || ping != null);
    }

    /** Whether a request of {@code device} waits for its entity: its clients show it as pending. */
    public boolean pending(UUID device) {
        return pending.pending(device);
    }

    /** Whether the breaker paused the control of {@code device}. */
    public boolean paused(UUID device) {
        return paused.contains(device);
    }

    /**
     * A player resumed the control of {@code device} (the Twin tab's Resume, {@code /mineqtt devices bind <d> resume}):
     * its breaker starts counting anew.
     *
     * @return false when it was not paused
     */
    public boolean resume(UUID device) {
        if (!paused.remove(device)) return false;
        breakerWindows.remove(device);
        deviceWindows.remove(device);
        rateRefusals.remove(device);
        rateBursts.remove(device);
        listener.pausedChanged(device, false);
        return true;
    }

    /** The control blueprint's last answer for {@code device}. */
    public Optional<Answer> answer(UUID device) {
        return Optional.ofNullable(answers.get(device));
    }

    /** The requests of {@code device} the rates refused since it was loaded or resumed, if any. */
    public Optional<RateRefusals> rateRefusals(UUID device) {
        return Optional.ofNullable(rateRefusals.get(device));
    }

    /**
     * The device's binding changed (control off, unbound, another entity, refused by a reload): a value it held for the
     * rates is dropped, never sent to the entity it no longer forwards to.
     */
    public void dropHeld(UUID device) {
        held.keySet().removeIf(key -> key.device().equals(device));
    }

    /** The device went: everything kept for it goes too. */
    public void forget(UUID device) {
        pending.forget(device);
        dropHeld(device);
        paused.remove(device);
        deviceWindows.remove(device);
        breakerWindows.remove(device);
        answers.remove(device);
        rateRefusals.remove(device);
        rateBursts.remove(device);
    }

    /**
     * {@code /mineqtt twin ping}: asks the control blueprint to answer {@code pong}. {@code answer} hears the pong, or
     * after {@value #PING_TIMEOUT_MILLIS} ms that none came.
     *
     * @return why no ping was sent; empty when it was
     */
    public Optional<String> ping(Consumer<String> answer) {
        if (stopped || !transport.connected()) return Optional.of("not sent: the bridge is offline");
        if (ping != null) return Optional.of("a ping is still waiting for its answer");
        String id = newId();
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("id", id);
        json.addProperty("ping", true);
        transport.publish(Outbound.event(topics.get().haRequest(), json));
        ping = new Ping(id, clock.getAsLong(), Objects.requireNonNull(answer, "answer"));
        return Optional.empty();
    }

    /**
     * Asks the catalog blueprint to publish the entity catalog anew ({@code ha_sync}, D3): never retained, dropped while
     * offline.
     *
     * @param reason why, as the catalog blueprint may log it: {@code command}, {@code connected}, {@code birth}
     * @return false while offline
     */
    public boolean sync(String reason) {
        if (stopped || !transport.connected()) return false;
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("reason", Coerce.sanitize(reason, 32));
        transport.publish(Outbound.event(topics.get().haSync(), json));
        return true;
    }

    /** What {@code /mineqtt twin status} shows. */
    public Status status() {
        return new Status(sent, heldCount, skipped, Collections.unmodifiableMap(new EnumMap<>(refused)), Collections.unmodifiableMap(new EnumMap<>(roots)),
                pending.size(), held.size(), paused.size(), Collections.unmodifiableMap(new TreeMap<>(answerCounts)), unmatched, droppedAnswers);
    }

    /** {@code SERVER_STOPPING}: nothing is sent any more, and nothing waits. */
    public void stop() {
        stopped = true;
        pending.clear();
        held.clear();
        ping = null;
    }

    /**
     * The request a call becomes (D11): {@code {"v":1,"id","action","target":{"entity_id"},"data","origin":{"device",
     * "name","player"}}}. {@code data} is the call's own, built from typed values only; {@code origin.player} only when a
     * player caused it.
     */
    public static JsonObject request(String id, Call call, String entityId, Origin origin) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("id", id);
        json.addProperty("action", call.action());
        json.add("target", call.target(entityId));
        json.add("data", call.data());
        JsonObject from = new JsonObject();
        from.addProperty("device", origin.objectId());
        from.addProperty("name", Coerce.sanitize(origin.name(), MAX_NAME_LENGTH));
        if (origin.player() != null) from.addProperty("player", Coerce.sanitize(origin.player().name(), MAX_PLAYER_NAME_LENGTH));
        json.add("origin", from);
        return json;
    }

    /**
     * Whether a call over a rate waits for it instead of being dropped (D11): a value with data, which the newest one
     * replaces (a brightness, a colour, a number, an option). A switch, a toggle and a press are dropped, so a burst of
     * clicks never fires later.
     */
    public static boolean waits(Call call) {
        JsonObject data = call.data();
        data.remove("transition");
        return !data.isEmpty();
    }

    /**
     * Whether a call over a rate waits instead of being refused: a value with data ({@link #waits}), and an explicit state
     * the world caused (a redstone edge's {@code turn_on}/{@code turn_off}, a wire's desired value),
     * which the world's next state replaces, so a burst of edges ends with the entity as the world left it instead of the
     * two disagreeing. A player's switch is still refused (a stale click never fires later), and so is anything that is
     * not idempotent: a toggle, a press, a scene or a script.
     */
    public static boolean holds(Call call, Origin origin) {
        return waits(call) || countsForBreaker(origin) && explicitState(call);
    }

    /** Whether a call sets a state that sending it again does not change: not a toggle, a press, a scene or a script. */
    public static boolean explicitState(Call call) {
        String service = call.service();
        return !service.equals("toggle") && !service.equals("press") && !call.domain().equals("scene") && !call.domain().equals("script");
    }

    // A request a rate refused: counted and kept for the device's info; the first of a burst is logged. One the world caused
    // counts towards the breaker as an attempt, so a target the rates keep refusing pauses as a looping one does.
    private Result rateRefused(Origin origin, String entityId, String reason, long now) {
        UUID device = origin.device();
        RateRefusals before = rateRefusals.get(device);
        rateRefusals.put(device, new RateRefusals(before == null ? 1 : before.refused() + 1, entityId, reason, now));
        listener.rateRefused(device);
        boolean world = countsForBreaker(origin);
        if (rateBursts.add(device)) {
            String target = new RateRefusals(0, entityId, reason, now).target();
            String text = "Device " + origin.name() + " did not ask to change " + target + ": " + reason + (world
                    ? "; the entity and the game may disagree until the next change (more refusals of this burst are counted, not logged)" : "");
            if (world) log.warn(text);
            else log.debug(text);
        }
        if (world) {
            ArrayDeque<Long> breaker = breakerWindows.computeIfAbsent(device, key -> new ArrayDeque<>());
            prune(breaker, now - BREAKER_WINDOW_MILLIS);
            if (breaker.size() >= BREAKER_LIMIT) {
                trip(origin, entityId);
                return refused(Refusal.PAUSED, pausedReason(origin.name()));
            }
            breaker.addLast(now);
        }
        return refused(Refusal.RATE, "not sent: " + reason);
    }

    // The published request, after the breaker's count.
    private Result publish(Origin origin, String entityId, Call call, Function<String, Outbound> message, long now) {
        UUID device = origin.device();
        if (countsForBreaker(origin)) {
            ArrayDeque<Long> breaker = breakerWindows.computeIfAbsent(device, key -> new ArrayDeque<>());
            prune(breaker, now - BREAKER_WINDOW_MILLIS);
            if (breaker.size() >= BREAKER_LIMIT) {
                trip(origin, entityId);
                return refused(Refusal.PAUSED, pausedReason(origin.name()));
            }
            breaker.addLast(now);
        }
        deviceWindows.computeIfAbsent(device, key -> new ArrayDeque<>()).addLast(now);
        // A sent request ends a burst of rate refusals: the next refusal is logged again.
        rateBursts.remove(device);
        if (origin.player() != null) playerWindows.computeIfAbsent(origin.player().id(), key -> new ArrayDeque<>()).addLast(now);
        bridgeWindow.addLast(now);
        String id = newId();
        transport.publish(message.apply(id));
        sent++;
        boolean wasPending = pending.pending(device);
        pending.add(new PendingRequests.Pending(id, device, origin.name(), entityId, call.action(), origin.player(), now, false))
                .ifPresent(evicted -> {
                    if (!evicted.device().equals(device)) stoppedWaiting(evicted.device());
                });
        if (!wasPending) listener.pendingChanged(device, true);
        return new Sent(id, call.action());
    }

    /**
     * Whether a request counts towards the circuit breaker (D10): one the world caused (a lever, a wire, a
     * repeater loop) does; a player's own command does not. A player cannot loop, and their input is capped by the device
     * and player rates already, while dragging a picker sends 4 a second for as long as it lasts, which an 8-in-10-s
     * breaker would pause within about 2 s. A paused device still refuses a player's command until it is resumed.
     */
    public static boolean countsForBreaker(Origin origin) {
        return origin.player() == null;
    }

    // The breaker trips: the device's control pauses until a player resumes it, and what waited for the rates is dropped.
    private void trip(Origin origin, String entityId) {
        UUID device = origin.device();
        if (!paused.add(device)) return;
        dropHeld(device);
        String target = entityId.startsWith(TOPIC_KEY_PREFIX) ? "its MQTT device on " + entityId.substring(TOPIC_KEY_PREFIX.length())
                : "Home Assistant to change " + entityId;
        log.warn("Device " + origin.name() + " asked " + target + " more than " + BREAKER_LIMIT + " times in "
                + BREAKER_WINDOW_MILLIS / 1000 + " s: its control is paused until a player resumes it (a loop through delayed redstone or a "
                + "Home Assistant automation?)");
        listener.pausedChanged(device, true);
        listener.notice(new Notice(Notice.Kind.PAUSED, device, origin.name(), entityId, origin.player(), pausedReason(origin.name())));
    }

    // Why a rate refuses a request now, if one does; nothing is counted.
    private Optional<String> overRate(Origin origin, long now) {
        ArrayDeque<Long> device = deviceWindows.get(origin.device());
        if (device != null && prune(device, now - WINDOW_MILLIS) >= DEVICE_PER_SECOND) {
            return Optional.of(origin.name() + " sends at most " + DEVICE_PER_SECOND + " requests a second");
        }
        if (origin.player() != null) {
            ArrayDeque<Long> player = playerWindows.get(origin.player().id());
            if (player != null && prune(player, now - WINDOW_MILLIS) >= PLAYER_PER_SECOND) {
                return Optional.of("you may send at most " + PLAYER_PER_SECOND + " requests a second");
            }
        }
        int bridge = Math.max(1, bridgePerSecond.getAsInt());
        if (prune(bridgeWindow, now - WINDOW_MILLIS) >= bridge) {
            return Optional.of("this server sends at most " + bridge + " requests a second to Home Assistant (twin.maxRequestsPerSecond)");
        }
        return Optional.empty();
    }

    // Tells whoever pinged, and the log, what came of the ping.
    private void answer(Ping ping, String text) {
        log.info("Home Assistant control blueprint ping: " + text);
        ping.answer().accept(text);
    }

    // Drops the times before `after`; the count that is left.
    private static int prune(ArrayDeque<Long> window, long after) {
        while (!window.isEmpty() && window.peekFirst() <= after) window.removeFirst();
        return window.size();
    }

    private void stoppedWaiting(UUID device) {
        if (!pending.pending(device)) listener.pendingChanged(device, false);
    }


    private Refused refused(Refusal refusal, String reason) {
        refused.merge(refusal, 1L, Long::sum);
        return new Refused(refusal, reason);
    }

    private Skipped skipped(String reason) {
        skipped++;
        return new Skipped(reason);
    }

    private void count(String status) {
        // Only the statuses the blueprint knows are counted by name, so a forged status cannot grow the map.
        String key = status.equals(ACCEPTED) || status.equals(PONG) || REFUSALS.contains(status) ? status : "other";
        answerCounts.merge(key, 1L, Long::sum);
    }

    private static String pausedReason(String device) {
        return device + "'s control is paused after more than " + BREAKER_LIMIT + " requests in " + BREAKER_WINDOW_MILLIS / 1000
                + " s; resume it on its Twin tab or with /mineqtt devices bind <device> resume";
    }

    private String newId() {
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) id.append(Character.forDigit(random.nextInt(16), 16));
        return id.toString();
    }

    // A string member of at most `max` characters; empty when missing, of another type or longer.
    private static Optional<String> text(JsonObject json, String key, int max) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return Optional.empty();
        String text = value.getAsString();
        return text.isEmpty() || text.length() > max ? Optional.empty() : Optional.of(text);
    }
}

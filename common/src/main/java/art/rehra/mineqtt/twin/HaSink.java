package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.MessageBus;
import art.rehra.mineqtt.flow.core.Root;
import art.rehra.mineqtt.twin.core.HaCalls;
import com.google.gson.JsonElement;

import java.util.Objects;
import java.util.Optional;

/**
 * The bus's sink for Home Assistant ends (M3 plan §3.4 step 3): a value that reaches an entity's in-port over a wire,
 * and a mirrored device's forwarded command, become requests through {@link TwinRequests}, and what became of them
 * becomes the bus's {@link MessageBus.Outcome}. The root gate comes first ({@link TwinGate}): a message whose chain
 * started with incoming state, a {@code /set} topic, a raw topic or a restart is refused for its root
 * ({@link MessageBus.Outcome#ROOT}) before anything is built, then {@link HaCalls} translates the value, then the
 * allowlist, the breaker, the connection and the rates.
 * <p>
 * Target wires carry the targets of the Redstone Input and the Wall Switch (M3-14), whose adapters translate the
 * out-port's value (the edge mapping, a press); the forward path is the mirrored devices' write-back. Minecraft-free.
 */
public final class HaSink {
    private final TwinRequests requests;

    public HaSink(TwinRequests requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    /**
     * A value at the in-port {@code target} names, from {@code origin}'s out-port.
     *
     * @param mirror what the entity shows now, {@link HaCalls.Mirror#NONE} when nothing is known
     */
    public TwinRequests.Result deliver(TwinRequests.Origin origin, Endpoint.HaEntityRef target, JsonElement value, HaCalls.Mirror mirror, Root root) {
        Optional<TwinRequests.Refused> gated = requests.gate(root);
        if (gated.isPresent()) return gated.get();
        String domain = TwinAdapter.domainOf(target.entityId());
        return requests.request(origin, target.entityId(), HaCalls.resolve(domain, target.port(), value, mirror), root);
    }

    /**
     * A value leaving {@code origin}'s out-port {@code outPort} over a target wire (M3-14): gate first, then the device
     * type's translation of that target ({@link TwinAdapter#targetCall}: the Redstone Input's edge rules, the Wall
     * Switch's press), then the requests.
     *
     * @param mirror what the target entity shows now, {@link HaCalls.Mirror#NONE} when nothing is known
     */
    public TwinRequests.Result deliver(TwinRequests.Origin origin, TwinAdapter adapter, String outPort, Endpoint.HaEntityRef target, JsonElement value,
                                       HaCalls.Mirror mirror, Root root) {
        Optional<TwinRequests.Refused> gated = requests.gate(root);
        if (gated.isPresent()) return gated.get();
        return requests.request(origin, target.entityId(), adapter.targetCall(outPort, target, value, mirror), root);
    }

    /**
     * What a request's fate is to the bus: taken when it was sent, waits for the rate or was not needed (dedupe);
     * {@link MessageBus.Outcome#ROOT} when its root may not cause one; refused otherwise.
     */
    public static MessageBus.Outcome outcome(TwinRequests.Result result) {
        return switch (result) {
            case TwinRequests.Sent ignored -> MessageBus.Outcome.TAKEN;
            case TwinRequests.Held ignored -> MessageBus.Outcome.TAKEN;
            case TwinRequests.Skipped ignored -> MessageBus.Outcome.TAKEN;
            case TwinRequests.Refused refused -> refused.refusal() == TwinRequests.Refusal.ROOT ? MessageBus.Outcome.ROOT : MessageBus.Outcome.REFUSED;
        };
    }
}

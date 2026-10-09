package art.rehra.mineqtt.flow.core;

import art.rehra.mineqtt.security.TokenBucket;
import com.google.gson.JsonElement;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The flows message bus (M2 plan §3.3, §3.5), one per server, owned by the device runtime. A port's new value or event is
 * only {@link #emit enqueued}; {@link #drain} carries it over the source's wires later, at the tick's drain points, so
 * no device ever calls another on the stack. FIFO, so a message emitted while one is delivered queues behind the ones
 * already waiting.
 * <p>
 * <b>Hops.</b> A message counts the wires it travelled over. Whatever a target emits while a message is delivered to it
 * carries that message's hops, so a zero-delay cycle is cut after {@link #HOP_TTL} wires, however it closes (a wire back,
 * or redstone through the world). Hops never go down a device's command path: the bus knows which delivery is running.
 * Messages with the same id are never deduplicated, so a diamond's two branches both arrive.
 * <p>
 * <b>Limits.</b> Each source port may emit {@link #RATE_PER_SECOND} messages a second after a burst of
 * {@link #RATE_BURST}. Beyond that an event is dropped, while a value is held back until the port may emit again, the
 * newest replacing an older one, so the targets still end up with the port's last value. One drain carries messages
 * over at most {@link #DRAIN_WIRES} wires or for {@link #DRAIN_NANOS}, checked after every wire, so a message with many
 * wires may be left halfway; it and the rest stay queued in order. Beyond {@link #CAPACITY} queued messages the oldest
 * is dropped. A wire passes a value on only when it differs from the last one its target took; events always pass.
 * Every drop is counted by its {@link Drop} reason.
 * <p>
 * Minecraft-free; server thread only.
 */
public final class MessageBus {
    /** The most wires one chain of messages travels over. */
    public static final int HOP_TTL = 16;
    public static final int RATE_PER_SECOND = 20;
    public static final int RATE_BURST = 40;
    /** The most wires one drain carries messages over; a message that has no wire left counts as one. */
    public static final int DRAIN_WIRES = 2000;
    public static final long DRAIN_NANOS = 2_000_000L;
    public static final int CAPACITY = 10_000;

    /** Why a message, or its delivery over one wire, was dropped. */
    public enum Drop {
        /** Its payload is nested too deep or too large ({@link FlowMessage#payloadProblem}). */
        PAYLOAD,
        /** Its source port emitted faster than its rate allows: an event, or a held-back value a newer one replaced. */
        RATE,
        /** The queue was full, and it was the oldest message. */
        OVERFLOW,
        /** It travelled over {@link #HOP_TTL} wires already: a loop without delay. */
        HOPS,
        /** An end of the wire names no port any more, or the ports' kinds no longer fit the wire's mapping. */
        UNROUTABLE,
        /** Its payload does not fit its source port's kind, or does not convert to the target's. */
        VALUE,
        /** The target did not take it, like an unloaded sensor. */
        REFUSED,
        /** Delivering it threw. */
        FAILED
    }

    /** The wires and ports the bus routes over: the device registry and the device types. */
    public interface Wiring {
        /**
         * The wires whose {@link Wire#from} is {@code source}, disabled ones included, in a stable order. The bus routes
         * a message over a copy, so a delivery may remove wires; one removed meanwhile still carries that message. A
         * message a drain left halfway goes on in a later drain, over those of its remaining wires that are still here.
         */
        List<Wire> from(Endpoint source);

        /** The out-port {@code source} names, or empty when it names none. */
        Optional<? extends PortSpec> out(Endpoint source);

        /** The in-port {@code target} names, or empty when it names none. */
        Optional<? extends PortSpec> in(Endpoint target);
    }

    /** Where a message arrives: the device runtime's command path. */
    @FunctionalInterface
    public interface Sink {
        /**
         * Delivers {@code message} to {@code wire}'s target; its payload is already what the target port takes. A group
         * target fans it out to every member except the device {@code message.source()} names, on any of its ports, as a
         * wire's message never goes back into the device it came from. A group counts as one wire for the drain budget.
         *
         * @return whether the target took it
         */
        boolean deliver(Wire wire, FlowMessage message);
    }

    /** What the runtime logs. */
    public interface Listener {
        /** A loop was cut on {@code wire}; told once per wire until it is {@linkplain #forgetWire forgotten}. */
        void loopCut(Wire wire, FlowMessage message);

        /** Delivering {@code message} over {@code wire} threw; the drain went on. */
        void failed(Wire wire, FlowMessage message, RuntimeException e);
    }

    /**
     * A queued message.
     *
     * @param wiresLeft the ids of the wires still to travel, when a drain ran out of budget halfway through the message;
     *                  null for every wire of its source
     */
    private record Pending(FlowMessage message, Set<UUID> wiresLeft) {
    }

    private final Wiring wiring;
    private final Sink sink;
    private final Listener listener;
    private final LongSupplier nanoClock;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Map<Endpoint, TokenBucket> rates = new HashMap<>();
    // The newest value of each port that emitted faster than its rate, until the port may emit again.
    private final Map<Endpoint, FlowMessage> held = new LinkedHashMap<>();
    // Per wire, the last value its target took, for the change filter; on a pulse-toggle wire also the last value that
    // was no rising edge, which the next edge is measured from. A wire whose target did not take a value remembers none.
    private final Map<UUID, JsonElement> lastValues = new HashMap<>();
    private final Set<UUID> loopsReported = new HashSet<>();
    private final long[] drops = new long[Drop.values().length];
    private long emitted;
    private long delivered;
    private long suppressed;
    private boolean draining;
    // The message being delivered, whose hops a message emitted meanwhile takes over; null outside a delivery.
    private FlowMessage delivering;

    /** @param nanoClock a monotonic clock in nanoseconds, such as {@code System::nanoTime}, for the rates and the drain budget */
    public MessageBus(Wiring wiring, Sink sink, Listener listener, LongSupplier nanoClock) {
        this.wiring = wiring;
        this.sink = sink;
        this.listener = listener;
        this.nanoClock = nanoClock;
    }

    /**
     * Queues a new value or event of {@code source}. A port without an enabled wire emits nothing, and uses none of its
     * rate. Beyond its rate, an event is dropped and a value is held back until the port may emit again; a newer value
     * replaces the one held back, whether it is held back too or queued. Emitted while a message is delivered, the new
     * message carries that message's hops, also when it is held back; otherwise none.
     *
     * @param topic the message's topic; null for none
     * @return whether it was queued or held back; false when no enabled wire leaves {@code source}, or it was dropped
     */
    public boolean emit(FlowMessage.Kind kind, Endpoint source, String topic, JsonElement payload, long gameTime) {
        if (wiring.from(source).stream().noneMatch(Wire::enabled)) return false;
        boolean allowed = rate(source).tryTake();
        if (!allowed && kind == FlowMessage.Kind.EVENT) {
            count(Drop.RATE);
            return false;
        }
        if (FlowMessage.payloadProblem(payload).isPresent()) {
            count(Drop.PAYLOAD);
            return false;
        }
        int hops = delivering == null ? 0 : delivering.hops();
        FlowMessage message = new FlowMessage(UUID.randomUUID(), kind, payload.deepCopy(), topic, source, hops, gameTime);
        // Only a port's newest value matters.
        if (kind == FlowMessage.Kind.VALUE && held.remove(source) != null) count(Drop.RATE);
        if (allowed) {
            enqueue(message);
        } else {
            held.put(source, message);
        }
        return true;
    }

    /**
     * Queues the held-back values whose ports may emit again, then carries queued messages over their wires, oldest
     * first, until the queue is empty, {@link #DRAIN_WIRES} wires were travelled or {@link #DRAIN_NANOS} have passed. The
     * budget is checked after every wire, so a message it runs out on stays first in the queue with the wires still to
     * go. At least one wire is travelled, so the queue always moves. Messages emitted meanwhile join the queue and may be
     * carried in the same drain.
     *
     * @return the wires travelled, a message that had no wire left counting as one
     * @throws IllegalStateException when called from a delivery
     */
    public int drain() {
        if (draining) throw new IllegalStateException("The message bus is already draining");
        draining = true;
        try {
            releaseHeld();
            long start = nanoClock.getAsLong();
            int travelled = 0;
            while (withinBudget(travelled, start)) {
                Pending pending = queue.pollFirst();
                if (pending == null) break;
                travelled = route(pending, travelled, start);
            }
            return travelled;
        } finally {
            draining = false;
        }
    }

    private void releaseHeld() {
        for (Iterator<FlowMessage> values = held.values().iterator(); values.hasNext(); ) {
            FlowMessage message = values.next();
            if (rate(message.source()).tryTake()) {
                values.remove();
                enqueue(message);
            }
        }
    }

    private boolean withinBudget(int travelled, long start) {
        return travelled < DRAIN_WIRES && (travelled == 0 || nanoClock.getAsLong() - start < DRAIN_NANOS);
    }

    // Carries a message over its wires while the budget lasts; returns the wires this drain travelled so far.
    private int route(Pending pending, int travelled, long start) {
        FlowMessage message = pending.message();
        // A copy: a delivery may remove wires (a device destroyed by what it was told).
        List<Wire> wires = wiring.from(message.source()).stream()
                .filter(wire -> pending.wiresLeft() == null || pending.wiresLeft().contains(wire.id()))
                .toList();
        if (wires.isEmpty()) return travelled + 1;
        Optional<? extends PortSpec> from = wiring.out(message.source());
        for (int i = 0; i < wires.size(); i++) {
            // The drain's own check granted the first wire.
            if (i > 0 && !withinBudget(travelled, start)) {
                Set<UUID> left = new HashSet<>();
                for (Wire wire : wires.subList(i, wires.size())) left.add(wire.id());
                queue.addFirst(new Pending(message, left));
                return travelled;
            }
            route(message, wires.get(i), from);
            travelled++;
        }
        return travelled;
    }

    private void route(FlowMessage message, Wire wire, Optional<? extends PortSpec> from) {
        // A wire never delivers back to the port that sent the message.
        if (!wire.enabled() || !wire.from().equals(message.source()) || wire.to().equals(message.source())) return;
        if (message.hops() >= HOP_TTL) {
            count(Drop.HOPS);
            if (loopsReported.add(wire.id())) listener.loopCut(wire, message);
            return;
        }
        Optional<? extends PortSpec> to = wiring.in(wire.to());
        if (from.isEmpty() || to.isEmpty() || !wire.mapping().compatible(from.get().kind(), to.get().kind(), to.get().toggles())) {
            count(Drop.UNROUTABLE);
            return;
        }
        if (from.get().kind().problem(message.payload()).isPresent()) {
            count(Drop.VALUE);
            return;
        }
        boolean value = message.kind() == FlowMessage.Kind.VALUE;
        JsonElement previous = value ? lastValues.get(wire.id()) : null;
        if (value && message.payload().equals(previous)) {
            suppressed++;
            return;
        }
        Optional<JsonElement> mapped = wire.mapping().apply(message.payload(), previous, from.get().kind(), to.get().kind(), to.get().toggles());
        if (mapped.isEmpty()) {
            // A value that is no rising edge on a pulse-toggle wire is no drop: it fit, and nothing is to be done but to
            // measure the next edge from it.
            if (wire.mapping() == Mapping.PULSE_TOGGLE) {
                lastValues.put(wire.id(), message.payload());
            } else {
                count(Drop.VALUE);
            }
            return;
        }
        if (deliver(wire, message.traversed(mapped.get()))) {
            if (value) lastValues.put(wire.id(), message.payload());
        } else {
            // The target may hold anything now, so the next value passes, even an equal one sent again.
            lastValues.remove(wire.id());
        }
    }

    // Whether the target took it.
    private boolean deliver(Wire wire, FlowMessage message) {
        delivering = message;
        try {
            if (sink.deliver(wire, message)) {
                delivered++;
                return true;
            }
            count(Drop.REFUSED);
        } catch (RuntimeException e) {
            count(Drop.FAILED);
            listener.failed(wire, message, e);
        } finally {
            delivering = null;
        }
        return false;
    }

    private void enqueue(FlowMessage message) {
        queue.addLast(new Pending(message, null));
        emitted++;
        if (queue.size() > CAPACITY) {
            queue.removeFirst();
            count(Drop.OVERFLOW);
        }
    }

    private TokenBucket rate(Endpoint source) {
        return rates.computeIfAbsent(source, s -> new TokenBucket(RATE_BURST, RATE_PER_SECOND, nanoClock));
    }

    /** Whether messages wait for a drain: queued, or values held back until their ports may emit again. */
    public boolean hasQueued() {
        return !queue.isEmpty() || !held.isEmpty();
    }

    /** The messages queued; held-back values are not. */
    public int queued() {
        return queue.size();
    }

    /**
     * A wire was removed, disabled or suspended: its last value and whether its loop was reported go with it, so once it
     * carries messages again, its first value passes whatever its target was told meanwhile.
     */
    public void forgetWire(UUID wire) {
        lastValues.remove(wire);
        loopsReported.remove(wire);
    }

    /** A device or group was removed: the rates and held-back values of its ports go with it. */
    public void forgetNode(UUID node) {
        rates.keySet().removeIf(endpoint -> onNode(endpoint, node));
        held.keySet().removeIf(endpoint -> onNode(endpoint, node));
    }

    /** Drops every queued and held-back message and what the wires remember; messages in flight are not kept (M2 plan §3.6). */
    public void clear() {
        queue.clear();
        held.clear();
        rates.clear();
        lastValues.clear();
        loopsReported.clear();
    }

    /** The messages queued so far, held-back values once they were released. */
    public long emitted() {
        return emitted;
    }

    /** The deliveries a target took. */
    public long delivered() {
        return delivered;
    }

    /** The values a wire did not pass on because they equalled the last one its target took. */
    public long suppressed() {
        return suppressed;
    }

    public long dropped(Drop reason) {
        return drops[reason.ordinal()];
    }

    private void count(Drop reason) {
        drops[reason.ordinal()]++;
    }

    private static boolean onNode(Endpoint endpoint, UUID node) {
        return switch (endpoint) {
            case Endpoint.PortRef port -> port.node().equals(node);
            case Endpoint.GroupPortRef port -> port.group().equals(node);
            case Endpoint.TopicRef _, Endpoint.HaEntityRef _ -> false;
        };
    }
}

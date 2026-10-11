package art.rehra.mineqtt.flow.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The flows message bus (M2 plan §3.3, §3.5) and the coercion table (§3.1), Minecraft-free: FIFO order, a zero-delay
 * cycle cut at the hop TTL, diamond fan-in, the drain budget per wire and its carry-over (also halfway through a message),
 * the queue cap, the rate cap (events dropped, the last value held back), the change filter for values (never events,
 * and never for a value the target did not take), no delivery back to the source port, wire mappings, routing drops,
 * payload caps and root provenance (M3 plan D10: ambient outside a delivery, inherited inside one, kept through holds
 * and carry-over, never cleared by a nested extent, refusals counted).
 */
public final class MessageBusCheck {
    private static final UUID OWNER = new UUID(0, 1);
    private static final PortSpec BOOL = spec(PortType.BOOL, false);
    private static final PortSpec SWITCH = spec(PortType.BOOL, true);
    private static final PortSpec LEVEL = spec(PortType.LEVEL, false);
    private static final PortSpec EVENT = spec(PortType.event("press"), false);
    private static final JsonElement PRESS = json("{\"event_type\":\"press\"}");

    static void main(String[] args) {
        fifo();
        hopCycle();
        diamond();
        drainBudget();
        overflow();
        rateCap();
        changeFilter();
        noDeliveryBackToSource();
        mappings();
        routingDrops();
        payloadCaps();
        roots();
        twinWires();
        records();
        coercion();
        System.out.println("Message bus checks passed.");
    }

    // Messages leave in the order they were emitted; what a delivery emits queues behind the ones already waiting.
    private static void fifo() {
        Harness h = new Harness();
        Endpoint[] sources = new Endpoint[5];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = h.node("s" + i, "out");
            h.wire(sources[i], h.node("t" + i, "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        }
        Endpoint relay = h.node("relay", "in");
        Endpoint relayOut = h.node("relay", "out");
        h.wire(sources[0], relay, Mapping.FOLLOW, LEVEL, LEVEL);
        h.wire(relayOut, h.node("end", "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        h.react(relay, relayOut);
        for (int i = 0; i < sources.length; i++) check(h.emit(FlowMessage.Kind.VALUE, sources[i], new JsonPrimitive(i + 1)), "Emit " + i);
        check(h.bus.queued() == 5 && h.deliveries.isEmpty(), "Emitting only queues");
        check(h.bus.drain() == 7, "One drain carries the five messages and the relay's over their seven wires");
        check(h.targets().equals(List.of("t0.in", "relay.in", "t1.in", "t2.in", "t3.in", "t4.in", "end.in")),
                "FIFO, and the relay's message after the five already queued: " + h.targets());
        check(!h.bus.hasQueued() && h.bus.emitted() == 6 && h.bus.delivered() == 7, "All delivered");
    }

    // An A <-> B cycle without delay stops at the hop TTL, is counted, and is reported once per wire.
    private static void hopCycle() {
        Harness h = new Harness();
        Endpoint aOut = h.node("a", "pressed");
        Endpoint aIn = h.node("a", "trigger");
        Endpoint bOut = h.node("b", "pressed");
        Endpoint bIn = h.node("b", "trigger");
        Wire ab = h.wire(aOut, bIn, Mapping.FOLLOW, EVENT, EVENT);
        h.wire(bOut, aIn, Mapping.FOLLOW, EVENT, EVENT);
        h.react(aIn, aOut);
        h.react(bIn, bOut);
        check(h.emit(FlowMessage.Kind.EVENT, aOut, PRESS), "Emit outside a drain");
        check(h.bus.drain() == MessageBus.HOP_TTL + 1, "Sixteen deliveries emit sixteen more messages; the last is cut");
        check(h.deliveries.size() == MessageBus.HOP_TTL, "The cycle delivers exactly " + MessageBus.HOP_TTL + " times: " + h.deliveries.size());
        for (int i = 0; i < h.deliveries.size(); i++) {
            check(h.deliveries.get(i).message().hops() == i + 1, "Delivery " + i + " carries its hops: " + h.deliveries.get(i).message().hops());
        }
        check(h.bus.dropped(MessageBus.Drop.HOPS) == 1 && h.loopCuts.equals(List.of(ab.id())), "The cut is counted and reported on A -> B: " + h.loopCuts);
        check(!h.bus.hasQueued(), "The cycle is over");
        h.deliveries.clear();
        check(h.emit(FlowMessage.Kind.EVENT, aOut, PRESS) && h.bus.drain() == MessageBus.HOP_TTL + 1, "A second press runs the cycle again");
        check(h.deliveries.getFirst().message().hops() == 1, "A message emitted outside a delivery starts at no hops");
        check(h.bus.dropped(MessageBus.Drop.HOPS) == 2 && h.loopCuts.size() == 1, "Counted again, but reported once per wire");
        h.bus.forgetWire(ab.id());
        check(h.emit(FlowMessage.Kind.EVENT, aOut, PRESS) && h.bus.drain() > 0 && h.loopCuts.size() == 2, "A forgotten wire is reported again");
    }

    // A -> B -> D and A -> C -> D: D gets both branches, though they come from one message.
    private static void diamond() {
        Harness h = new Harness();
        Endpoint a = h.node("a", "out");
        Endpoint d = h.node("d", "in");
        for (String branch : List.of("b", "c")) {
            h.wire(a, h.node(branch, "in"), Mapping.FOLLOW, BOOL, BOOL);
            h.wire(h.node(branch, "out"), d, Mapping.FOLLOW, BOOL, BOOL);
            h.react(h.node(branch, "in"), h.node(branch, "out"));
        }
        h.emit(FlowMessage.Kind.VALUE, a, new JsonPrimitive(true));
        h.bus.drain();
        List<Delivery> atD = h.deliveries.stream().filter(delivery -> delivery.wire().to().equals(d)).toList();
        check(atD.size() == 2 && atD.stream().allMatch(delivery -> delivery.message().hops() == 2), "Both branches reach D after two wires: " + atD);
        check(!atD.get(0).message().source().equals(atD.get(1).message().source()), "One from each branch");
    }

    // One drain travels at most 2000 wires or 2 ms, checked after every wire; the rest stays queued in order for the next.
    private static void drainBudget() {
        Harness h = new Harness();
        int count = MessageBus.DRAIN_WIRES + 500;
        Endpoint target = h.node("target", "in");
        for (int i = 0; i < count; i++) h.wire(h.node("s" + i, "out"), target, Mapping.FOLLOW, LEVEL, LEVEL);
        for (int i = 0; i < count; i++) h.emit(FlowMessage.Kind.VALUE, h.node("s" + i, "out"), new JsonPrimitive(i % 16));
        check(h.bus.drain() == MessageBus.DRAIN_WIRES && h.bus.queued() == 500, "The wire budget leaves 500: " + h.bus.queued());
        check(h.bus.drain() == 500 && !h.bus.hasQueued(), "The next drain handles the rest");
        for (int i = 0; i < count; i++) {
            check(h.deliveries.get(i).wire().from().equals(h.node("s" + i, "out")), "Carried over in order at " + i);
        }
        // A clock that moves 1 ms per reading: the drain stops once 2 ms have passed, after at least one message.
        h.deliveries.clear();
        for (int i = 0; i < 5; i++) h.emit(FlowMessage.Kind.VALUE, h.node("s" + i, "out"), new JsonPrimitive(15));
        h.clock.step = 1_000_000L;
        check(h.bus.drain() == 2 && h.bus.queued() == 3, "The time budget stops the drain: " + h.bus.queued());
        h.clock.step = MessageBus.DRAIN_NANOS * 10;
        check(h.bus.drain() == 1 && h.bus.queued() == 2, "A drain over its time at once still handles one message");
        h.clock.step = 0;
        h.bus.drain();
        check(h.targetsFrom().equals(List.of("s0.out", "s1.out", "s2.out", "s3.out", "s4.out")), "Still in order: " + h.targetsFrom());

        // One message over more wires than the budget: the drain stops halfway, and the next one carries it over the
        // rest, in wire order, before the message queued behind it.
        Harness fan = new Harness();
        Endpoint hub = fan.node("hub", "out");
        int wires = MessageBus.DRAIN_WIRES + 500;
        for (int i = 0; i < wires; i++) fan.wire(hub, fan.node("t" + i, "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        Endpoint behind = fan.node("behind", "out");
        fan.wire(behind, fan.node("last", "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        fan.emit(FlowMessage.Kind.VALUE, hub, new JsonPrimitive(3));
        fan.emit(FlowMessage.Kind.VALUE, behind, new JsonPrimitive(3));
        check(fan.bus.drain() == MessageBus.DRAIN_WIRES && fan.deliveries.size() == MessageBus.DRAIN_WIRES && fan.bus.queued() == 2,
                "The wire budget stops a message halfway: " + fan.deliveries.size());
        check(fan.bus.drain() == 501 && !fan.bus.hasQueued(), "The next drain goes on over its other 500 wires, then the message behind it");
        for (int i = 0; i < wires; i++) {
            check(fan.label(fan.deliveries.get(i).wire().to()).equals("t" + i + ".in") && fan.deliveries.get(i).message().hops() == 1, "In wire order at " + i);
        }
        check(fan.label(fan.deliveries.getLast().wire().to()).equals("last.in") && fan.deliveries.size() == wires + 1, "The message behind it comes last");

        // The clock is read after every wire: at 1 ms per reading a five-wire message stops after two. When it goes on,
        // a wire removed meanwhile is left out.
        Harness slow = new Harness();
        Endpoint source = slow.node("source", "out");
        List<Wire> fanned = new ArrayList<>();
        for (int i = 0; i < 5; i++) fanned.add(slow.wire(source, slow.node("t" + i, "in"), Mapping.FOLLOW, LEVEL, LEVEL));
        slow.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(7));
        slow.clock.step = 1_000_000L;
        check(slow.bus.drain() == 2 && slow.bus.queued() == 1, "The time budget stops a message halfway: " + slow.targets());
        slow.wires.get(source).remove(fanned.get(3));
        slow.clock.step = 0;
        check(slow.bus.drain() == 2 && slow.targets().equals(List.of("t0.in", "t1.in", "t2.in", "t4.in")), "Only the wires still there: " + slow.targets());
    }

    // Beyond 10 000 queued messages the oldest is dropped and counted.
    private static void overflow() {
        Harness h = new Harness();
        Endpoint target = h.node("target", "in");
        int count = MessageBus.CAPACITY + 2;
        for (int i = 0; i < count; i++) {
            Endpoint source = h.node("s" + i, "out");
            h.wire(source, target, Mapping.FOLLOW, LEVEL, LEVEL);
            h.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(1));
        }
        check(h.bus.queued() == MessageBus.CAPACITY && h.bus.dropped(MessageBus.Drop.OVERFLOW) == 2, "Capped at the capacity, two dropped");
        h.bus.drain();
        check(h.deliveries.getFirst().wire().from().equals(h.node("s2", "out")), "The two oldest were dropped");
    }

    // A source port may send a burst of 40, then 20 a second; ports without a wire use none of it.
    private static void rateCap() {
        Harness h = new Harness();
        Endpoint source = h.node("fast", "out");
        Endpoint other = h.node("other", "out");
        for (int i = 0; i < 100; i++) check(!h.emit(FlowMessage.Kind.EVENT, source, PRESS), "No wire, nothing emitted");
        check(h.bus.dropped(MessageBus.Drop.RATE) == 0 && h.bus.emitted() == 0, "A port without a wire is not rate limited");
        h.wire(source, h.node("sink", "trigger"), Mapping.FOLLOW, EVENT, EVENT);
        h.wire(other, h.node("sink", "trigger"), Mapping.FOLLOW, EVENT, EVENT);
        int queued = 0;
        for (int i = 0; i < MessageBus.RATE_BURST + 1; i++) {
            if (h.emit(FlowMessage.Kind.EVENT, source, PRESS)) queued++;
        }
        check(queued == MessageBus.RATE_BURST && h.bus.dropped(MessageBus.Drop.RATE) == 1, "A burst of 40, the 41st dropped: " + queued);
        check(h.emit(FlowMessage.Kind.EVENT, other, PRESS), "Another port has its own rate");
        h.clock.now += 1_000_000_000L / MessageBus.RATE_PER_SECOND;
        check(h.emit(FlowMessage.Kind.EVENT, source, PRESS) && !h.emit(FlowMessage.Kind.EVENT, source, PRESS), "One more after 50 ms");
        h.bus.forgetNode(UUID.nameUUIDFromBytes("fast".getBytes()));
        check(h.emit(FlowMessage.Kind.EVENT, source, PRESS), "A forgotten node starts with a full burst");
        Wire disabled = h.wire(h.node("off", "out"), h.node("sink", "trigger"), Mapping.FOLLOW, EVENT, EVENT, false);
        check(!h.emit(FlowMessage.Kind.EVENT, disabled.from(), PRESS), "A port whose only wire is disabled emits nothing");

        // Beyond its rate a port's value is held back, the newest replacing an older one, and queued once the port may
        // emit again, so the target ends up with the port's last value.
        Harness values = new Harness();
        Endpoint dial = values.node("dial", "level");
        values.wire(dial, values.node("lamp", "brightness"), Mapping.FOLLOW, LEVEL, LEVEL);
        for (int i = 0; i < MessageBus.RATE_BURST; i++) check(values.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(i % 2 + 1)), "Value " + i + " of the burst");
        check(values.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(5)) && values.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(0)),
                "Values beyond the rate are held back");
        check(values.bus.dropped(MessageBus.Drop.RATE) == 1 && values.bus.queued() == MessageBus.RATE_BURST && values.bus.hasQueued(),
                "Only the replaced one is dropped, and the held one waits");
        check(values.bus.drain() == MessageBus.RATE_BURST && values.bus.hasQueued() && values.payloads().getLast().equals("2"),
                "Held while the port has no token: " + values.payloads().getLast());
        values.clock.now += 1_000_000_000L / MessageBus.RATE_PER_SECOND;
        check(values.bus.drain() == 1 && !values.bus.hasQueued() && values.payloads().getLast().equals("0"), "Then the last value arrives: " + values.payloads().getLast());
        values.deliveries.clear();
        check(values.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(7)), "Held again");
        values.clock.now += 1_000_000_000L / MessageBus.RATE_PER_SECOND;
        check(values.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(9)) && values.bus.dropped(MessageBus.Drop.RATE) == 2,
                "A newer value the rate lets through replaces the held one too");
        values.bus.drain();
        check(values.payloads().equals(List.of("9")) && !values.bus.hasQueued(), "Only the newer one arrives: " + values.payloads());
        check(values.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(3)) && values.bus.hasQueued() && values.bus.queued() == 0, "Held once more");
        values.bus.forgetNode(UUID.nameUUIDFromBytes("dial".getBytes()));
        check(!values.bus.hasQueued(), "A forgotten node's held value goes with it");
    }

    // A wire passes a value on only when it changed; events always pass; each wire remembers its own.
    private static void changeFilter() {
        Harness h = new Harness();
        Endpoint source = h.node("input", "powered");
        Wire first = h.wire(source, h.node("lamp", "state"), Mapping.FOLLOW, BOOL, SWITCH);
        for (boolean value : new boolean[]{true, true, false, false, true}) {
            h.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(value));
            h.bus.drain();
        }
        check(h.payloads().equals(List.of("true", "false", "true")) && h.bus.suppressed() == 2, "Repeated values are suppressed: " + h.payloads());
        Wire second = h.wire(source, h.node("output", "state"), Mapping.FOLLOW, BOOL, SWITCH);
        h.deliveries.clear();
        h.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(true));
        h.bus.drain();
        check(h.deliveries.size() == 1 && h.deliveries.getFirst().wire().equals(second), "A new wire carries the value the old one suppresses");
        h.bus.forgetWire(first.id());
        h.deliveries.clear();
        h.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(true));
        h.bus.drain();
        check(h.deliveries.size() == 1 && h.deliveries.getFirst().wire().equals(first), "A forgotten wire carries it again");

        Endpoint button = h.node("button", "press");
        h.wire(button, h.node("bell", "ring"), Mapping.FOLLOW, EVENT, EVENT);
        h.deliveries.clear();
        for (int i = 0; i < 3; i++) h.emit(FlowMessage.Kind.EVENT, button, PRESS);
        h.bus.drain();
        check(h.deliveries.size() == 3, "Identical events are never suppressed: " + h.deliveries.size());

        // A value its target did not take is not remembered, so the same value sent again passes; one that does not
        // convert is not remembered either.
        Harness refused = new Harness();
        Endpoint input = refused.node("input", "powered");
        Endpoint sensor = refused.node("sensor", "detect");
        refused.wire(input, sensor, Mapping.FOLLOW, BOOL, BOOL);
        refused.refusing.add(sensor);
        refused.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(true));
        refused.bus.drain();
        refused.refusing.clear();
        refused.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(true));
        refused.bus.drain();
        check(refused.bus.dropped(MessageBus.Drop.REFUSED) == 1 && refused.bus.delivered() == 1 && refused.bus.suppressed() == 0,
                "A refused value is delivered when sent again");
        refused.throwing.add(sensor);
        refused.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(false));
        refused.bus.drain();
        refused.throwing.clear();
        refused.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(false));
        refused.bus.drain();
        check(refused.bus.dropped(MessageBus.Drop.FAILED) == 1 && refused.bus.delivered() == 2 && refused.bus.suppressed() == 0,
                "So is one whose delivery threw");
        refused.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(false));
        refused.bus.drain();
        check(refused.bus.suppressed() == 1 && refused.bus.delivered() == 2, "The one taken is remembered");
        refused.refusing.add(sensor);
        refused.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(true));
        refused.bus.drain();
        refused.refusing.clear();
        refused.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(false));
        refused.bus.drain();
        check(refused.bus.dropped(MessageBus.Drop.REFUSED) == 2 && refused.bus.delivered() == 3 && refused.bus.suppressed() == 1,
                "After a refusal the target may hold anything, so even the value it took before passes again");
        Endpoint words = refused.node("words", "out");
        refused.wire(words, refused.node("lamp", "state"), Mapping.FOLLOW, spec(PortType.text(8), false), SWITCH);
        for (int i = 0; i < 2; i++) {
            refused.emit(FlowMessage.Kind.VALUE, words, new JsonPrimitive("maybe"));
            refused.bus.drain();
        }
        check(refused.bus.dropped(MessageBus.Drop.VALUE) == 2 && refused.bus.suppressed() == 1, "A value that does not convert is never carried");

        // A pulse-toggle wire whose rising edge was refused toggles when the source sends its on value again.
        Harness pulse = new Harness();
        Endpoint powered = pulse.node("input", "powered");
        Endpoint lamp = pulse.node("lamp", "state");
        pulse.wire(powered, lamp, Mapping.PULSE_TOGGLE, BOOL, SWITCH);
        pulse.refusing.add(lamp);
        pulse.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive(true));
        pulse.bus.drain();
        pulse.refusing.clear();
        for (boolean value : new boolean[]{true, true, false, true}) {
            pulse.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive(value));
            pulse.bus.drain();
        }
        check(pulse.bus.dropped(MessageBus.Drop.REFUSED) == 1 && pulse.bus.delivered() == 2 && pulse.bus.suppressed() == 1,
                "The refused edge comes again, a repeated on does not, the next rising edge does");
    }

    // A wire back to the very port that sent the message carries nothing; the source's other wires do.
    private static void noDeliveryBackToSource() {
        Harness h = new Harness();
        Endpoint state = h.node("output", "state");
        h.wire(state, state, Mapping.FOLLOW, SWITCH, SWITCH);
        h.wire(state, h.node("lamp", "state"), Mapping.FOLLOW, SWITCH, SWITCH);
        h.emit(FlowMessage.Kind.VALUE, state, new JsonPrimitive(true));
        h.bus.drain();
        check(h.targets().equals(List.of("lamp.state")), "Only the other wire delivers: " + h.targets());

        // A delivery that removes the source's wires (a device destroyed by what it was told) does not break the routing.
        Harness removing = new Harness();
        Endpoint source = removing.node("input", "powered");
        removing.wire(source, removing.node("a", "state"), Mapping.FOLLOW, BOOL, SWITCH);
        removing.wire(source, removing.node("b", "state"), Mapping.FOLLOW, BOOL, SWITCH);
        removing.unwiring = true;
        removing.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(true));
        removing.bus.drain();
        check(removing.targets().equals(List.of("a.state", "b.state")) && removing.wires.get(source).isEmpty(), "Routed over the wires it started with");
    }

    private static void mappings() {
        expectMapped(Mapping.INVERT, new JsonPrimitive(true), null, PortType.BOOL, SWITCH, new JsonPrimitive(false), "INVERT on -> off");
        expectMapped(Mapping.INVERT, new JsonPrimitive(4), null, PortType.LEVEL, LEVEL, new JsonPrimitive(11), "INVERT level 4 -> 11");
        expectMapped(Mapping.INVERT, new JsonPrimitive(0), null, PortType.LEVEL, BOOL, new JsonPrimitive(true), "INVERT level 0 -> on");
        expectMapped(Mapping.INVERT, new JsonPrimitive(true), null, PortType.BOOL, LEVEL, new JsonPrimitive(0), "INVERT on -> level 0");
        expectMapped(Mapping.PULSE_TOGGLE, new JsonPrimitive(true), null, PortType.BOOL, SWITCH, new JsonPrimitive(Coerce.TOGGLE), "A first on toggles");
        expectMapped(Mapping.PULSE_TOGGLE, new JsonPrimitive(true), new JsonPrimitive(false), PortType.BOOL, SWITCH, new JsonPrimitive(Coerce.TOGGLE), "Off -> on toggles");
        expectMapped(Mapping.PULSE_TOGGLE, new JsonPrimitive(false), new JsonPrimitive(true), PortType.BOOL, SWITCH, null, "On -> off does nothing");
        expectMapped(Mapping.PULSE_TOGGLE, new JsonPrimitive(5), new JsonPrimitive(0), PortType.LEVEL, SWITCH, new JsonPrimitive(Coerce.TOGGLE), "Level 0 -> 5 toggles");
        expectMapped(Mapping.PULSE_TOGGLE, new JsonPrimitive(9), new JsonPrimitive(5), PortType.LEVEL, SWITCH, null, "Level 5 -> 9 does nothing");
        expectMapped(Mapping.PULSE_TOGGLE, PRESS, PRESS, PortType.event("press"), SWITCH, new JsonPrimitive(Coerce.TOGGLE), "Every event toggles");
        expectMapped(Mapping.FOLLOW, PRESS, null, PortType.event("press"), SWITCH, new JsonPrimitive(Coerce.TOGGLE), "FOLLOW from an event toggles too");
        check(!Mapping.INVERT.compatible(PortType.event("press"), PortType.BOOL, true) && !Mapping.INVERT.compatible(PortType.BOOL, PortType.text(8), false)
                && !Mapping.INVERT.compatible(PortType.COLOR, PortType.LEVEL, false), "INVERT only into a bool or level, never from an event");
        check(!Mapping.PULSE_TOGGLE.compatible(PortType.BOOL, PortType.BOOL, false) && !Mapping.PULSE_TOGGLE.compatible(PortType.BOOL, PortType.LEVEL, true)
                && !Mapping.PULSE_TOGGLE.compatible(PortType.text(8), PortType.BOOL, true), "PULSE_TOGGLE only into a toggling switch state, from a bool, level or event");

        // Through the bus: a pulse-toggle wire toggles on rising edges, and its falling edges are no drops.
        Harness h = new Harness();
        Endpoint input = h.node("input", "powered");
        h.wire(input, h.node("lamp", "state"), Mapping.PULSE_TOGGLE, BOOL, SWITCH);
        for (boolean value : new boolean[]{true, false, true, false}) {
            h.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(value));
            h.bus.drain();
        }
        check(h.payloads().equals(List.of("\"TOGGLE\"", "\"TOGGLE\"")) && totalDrops(h.bus) == 0, "Two rising edges, two toggles: " + h.payloads());

        // A Wall Switch's press (M2-22): every press toggles the lamp, the same press of the same button again too, as
        // events are never taken for an unchanged value.
        Harness presses = new Harness();
        Endpoint press = presses.node("switch", "press");
        presses.wire(press, presses.node("lamp", "state"), Mapping.PULSE_TOGGLE, spec(PortType.event("press"), false), SWITCH);
        for (String button : new String[]{"left", "left", "right"}) {
            JsonObject event = new JsonObject();
            event.addProperty(PortType.Event.TYPE_KEY, "press");
            event.addProperty("button", button);
            presses.emit(FlowMessage.Kind.EVENT, press, event);
            presses.bus.drain();
        }
        check(presses.payloads().equals(List.of("\"TOGGLE\"", "\"TOGGLE\"", "\"TOGGLE\"")) && presses.bus.suppressed() == 0 && totalDrops(presses.bus) == 0,
                "Three presses, three toggles: " + presses.payloads());
    }

    private static void expectMapped(Mapping mapping, JsonElement value, JsonElement previous, PortType from, PortSpec to, JsonElement expected, String message) {
        Optional<JsonElement> mapped = mapping.apply(value, previous, from, to.kind(), to.toggles());
        check(expected == null ? mapped.isEmpty() : mapped.isPresent() && mapped.get().equals(expected), message + ": " + mapped);
    }

    // Every way a delivery fails is counted by its reason, and the drain goes on.
    private static void routingDrops() {
        Harness h = new Harness();
        Endpoint source = h.node("source", "out");
        h.wire(source, h.node("gone", "in"), Mapping.FOLLOW, LEVEL, null);
        h.wire(source, h.node("text", "in"), Mapping.FOLLOW, LEVEL, spec(PortType.text(8), false));
        h.wire(source, h.node("enum", "in"), Mapping.FOLLOW, LEVEL, spec(PortType.enumOf(List.of("a")), false));
        h.wire(source, h.node("refuses", "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        h.wire(source, h.node("throws", "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        h.wire(source, h.node("off", "in"), Mapping.FOLLOW, LEVEL, LEVEL, false);
        h.wire(source, h.node("last", "in"), Mapping.FOLLOW, LEVEL, BOOL);
        h.refusing.add(h.node("refuses", "in"));
        h.throwing.add(h.node("throws", "in"));
        h.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(7));
        h.bus.drain();
        check(h.targets().equals(List.of("text.in", "refuses.in", "last.in")), "Delivered where it could be: " + h.targets());
        check(h.deliveries.getFirst().message().payload().equals(new JsonPrimitive("7")) && h.deliveries.getLast().message().payload().equals(new JsonPrimitive(true)),
                "Coerced for each target");
        check(h.bus.dropped(MessageBus.Drop.UNROUTABLE) == 2, "A missing port and an incompatible pair are unroutable");
        check(h.bus.dropped(MessageBus.Drop.REFUSED) == 1 && h.bus.dropped(MessageBus.Drop.FAILED) == 1 && h.failures.size() == 1, "Refused and failed are counted");
        check(h.bus.delivered() == 2, "Only the taken deliveries count as delivered");

        h.deliveries.clear();
        h.emit(FlowMessage.Kind.VALUE, source, new JsonPrimitive(16));
        h.bus.drain();
        check(h.deliveries.isEmpty() && h.bus.dropped(MessageBus.Drop.VALUE) == 4
                && h.bus.dropped(MessageBus.Drop.UNROUTABLE) == 4, "A value that does not fit its source port is dropped on every routable wire");
        Endpoint text = h.node("words", "out");
        h.wire(text, h.node("lamp", "state"), Mapping.FOLLOW, spec(PortType.text(8), false), SWITCH);
        h.emit(FlowMessage.Kind.VALUE, text, new JsonPrimitive("maybe"));
        h.bus.drain();
        check(h.deliveries.isEmpty() && h.bus.dropped(MessageBus.Drop.VALUE) == 5, "Text that names no boolean does not convert");
        Endpoint unknown = h.node("unknown", "out");
        h.wire(unknown, h.node("lamp", "state"), Mapping.FOLLOW, null, SWITCH);
        h.emit(FlowMessage.Kind.VALUE, unknown, new JsonPrimitive(true));
        h.bus.drain();
        check(h.bus.dropped(MessageBus.Drop.UNROUTABLE) == 5, "A source that names no port is unroutable");

        // A delivery may not drain the bus again; that throws inside the delivery, which counts it as failed.
        Harness nested = new Harness();
        Endpoint from = nested.node("a", "out");
        nested.wire(from, nested.node("b", "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        nested.reentrant = true;
        nested.emit(FlowMessage.Kind.VALUE, from, new JsonPrimitive(1));
        nested.bus.drain();
        check(nested.failures.size() == 1 && nested.failures.getFirst() instanceof IllegalStateException, "No drain inside a delivery: " + nested.failures);
    }

    // Payloads of at most 16 KiB and 16 levels; a deeper or larger one is dropped and counted.
    private static void payloadCaps() {
        check(FlowMessage.payloadProblem(nested(FlowMessage.MAX_DEPTH)).isEmpty(), "16 levels fit");
        check(FlowMessage.payloadProblem(nested(FlowMessage.MAX_DEPTH + 1)).isPresent(), "17 levels do not");
        check(FlowMessage.payloadProblem(nested(100_000)).isPresent(), "An absurdly deep value is refused without serializing it");
        // A JSON string of n characters serializes to n + 2 bytes.
        check(FlowMessage.payloadProblem(new JsonPrimitive("x".repeat(FlowMessage.MAX_PAYLOAD_BYTES - 2))).isEmpty(), "16 KiB fit");
        check(FlowMessage.payloadProblem(new JsonPrimitive("x".repeat(FlowMessage.MAX_PAYLOAD_BYTES - 1))).isPresent(), "One byte more does not");
        check(FlowMessage.payloadProblem(new JsonPrimitive("é".repeat(FlowMessage.MAX_PAYLOAD_BYTES / 2))).isPresent(), "Bytes are counted in UTF-8");
        Harness h = new Harness();
        Endpoint source = h.node("source", "out");
        h.wire(source, h.node("sink", "in"), Mapping.FOLLOW, spec(PortType.JSON, false), spec(PortType.JSON, false));
        check(!h.emit(FlowMessage.Kind.VALUE, source, nested(FlowMessage.MAX_DEPTH + 1)) && h.bus.dropped(MessageBus.Drop.PAYLOAD) == 1, "The bus refuses it");
        JsonObject payload = json("{\"a\":[1,2]}").getAsJsonObject();
        h.emit(FlowMessage.Kind.VALUE, source, payload);
        payload.addProperty("a", 3);
        h.bus.drain();
        check(h.payloads().equals(List.of("{\"a\":[1,2]}")), "The bus keeps its own copy of a payload: " + h.payloads());
    }

    // M3 plan D10: every message carries the root of its chain, through hops, holds and carry-over.
    /**
     * M3-8: a {@link Scope#TWIN} wire, derived from a binding (M3 plan §3.3), carries its entity's document as it is,
     * whatever the kinds of its ends (a switch's bool state into a lamp's light: no mapping could carry that), with the
     * root it was emitted in, through the change filter, hop by hop like any wire. What the target emits keeps the root
     * TWIN. A document that is no object and an end that names no port are dropped.
     */
    private static void twinWires() {
        Harness h = new Harness();
        Endpoint.HaEntityRef entity = new Endpoint.HaEntityRef("switch.porch", "state");
        Endpoint lamp = h.node("lamp", "light");
        Endpoint lampOut = h.node("lamp", "light_out");
        Wire twin = new Wire(UUID.nameUUIDFromBytes("twin".getBytes()), entity, lamp, Mapping.FOLLOW, Scope.TWIN, OWNER, true);
        h.wires.computeIfAbsent(entity, key -> new ArrayList<>()).add(twin);
        h.outs.put(entity, BOOL);
        h.ins.put(lamp, spec(PortType.LIGHT, false));
        h.wire(lampOut, h.node("door", "in"), Mapping.FOLLOW, spec(PortType.LIGHT, false), spec(PortType.LIGHT, false));
        h.react(lamp, lampOut);
        JsonElement on = json("{\"state\":\"on\",\"attributes\":{},\"last_changed\":null,\"last_updated\":\"2026-10-11T00:00:00+00:00\"}");
        h.bus.withRoot(Root.TWIN, () -> h.bus.emit(FlowMessage.Kind.VALUE, entity, "switch.porch", on, 0));
        h.bus.drain();
        check(h.targets().equals(List.of("lamp.light", "door.in")) && h.payloads().getFirst().equals(on.toString()),
                "A twin wire delivers the document as it is, across kinds no mapping joins: " + h.targets() + " " + h.payloads());
        check(h.roots().equals(List.of(Root.TWIN, Root.TWIN)) && h.deliveries.get(1).message().hops() == 2,
                "Its delivery and what the target emits carry TWIN, hop by hop: " + h.roots());
        h.deliveries.clear();
        long suppressed = h.bus.suppressed();
        h.bus.withRoot(Root.TWIN, () -> h.bus.emit(FlowMessage.Kind.VALUE, entity, "switch.porch", on, 0));
        h.bus.drain();
        check(h.deliveries.isEmpty() && h.bus.suppressed() == suppressed + 1, "The same document again passes the change filter as unchanged");
        h.bus.withRoot(Root.TWIN, () -> h.bus.emit(FlowMessage.Kind.VALUE, entity, "switch.porch", new JsonPrimitive("on"), 0));
        long value = h.bus.dropped(MessageBus.Drop.VALUE);
        h.bus.drain();
        check(h.bus.dropped(MessageBus.Drop.VALUE) == value + 1 && h.deliveries.isEmpty(), "A document that is no object is dropped as VALUE");
        h.ins.remove(lamp);
        long unroutable = h.bus.dropped(MessageBus.Drop.UNROUTABLE);
        h.bus.withRoot(Root.TWIN, () -> h.bus.emit(FlowMessage.Kind.VALUE, entity, "switch.porch", json("{\"state\":\"off\"}"), 0));
        h.bus.drain();
        check(h.bus.dropped(MessageBus.Drop.UNROUTABLE) == unroutable + 1 && h.deliveries.isEmpty(), "A twin wire to a port that is gone is unroutable");

        // M3-14: a target wire from a device's out-port to an entity's in-port carries the out-port's value as it is (a
        // bool edge, an event), checked against the out-port's kind; the sink translates it.
        Endpoint powered = h.node("input", "powered");
        Endpoint.HaEntityRef scene = new Endpoint.HaEntityRef("scene.movie", "activate");
        Wire target = new Wire(UUID.nameUUIDFromBytes("target".getBytes()), powered, scene, Mapping.FOLLOW, Scope.TWIN, OWNER, true);
        h.wires.computeIfAbsent(powered, key -> new ArrayList<>()).add(target);
        h.outs.put(powered, BOOL);
        h.ins.put(scene, spec(PortType.event("activate"), false));
        h.deliveries.clear();
        h.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive(true));
        h.bus.drain();
        check(h.payloads().equals(List.of("true")) && h.roots().equals(List.of(Root.WORLD)), "A target wire delivers the edge as it is: " + h.payloads());
        value = h.bus.dropped(MessageBus.Drop.VALUE);
        h.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive("TRUE?"));
        h.bus.drain();
        check(h.bus.dropped(MessageBus.Drop.VALUE) == value + 1 && h.payloads().size() == 1, "A value that does not fit the out-port is dropped as VALUE");
        h.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive(true));
        h.bus.drain();
        check(h.payloads().size() == 1, "The same edge again passes the change filter as unchanged");

        // M3-17: a preset's state topic is the source of a twin wire too; it carries a payload's fingerprint as an object,
        // keeps the TWIN root, filters an unchanged fingerprint and drops anything that is no object.
        Endpoint.TopicRef topic = new Endpoint.TopicRef("zigbee2mqtt/m3bulb", "");
        Endpoint plug = h.node("plug", "power");
        Wire fromTopic = new Wire(UUID.nameUUIDFromBytes("topic".getBytes()), topic, plug, Mapping.FOLLOW, Scope.TWIN, OWNER, true);
        h.wires.computeIfAbsent(topic, key -> new ArrayList<>()).add(fromTopic);
        h.outs.put(topic, spec(PortType.text(255), false));
        h.ins.put(plug, spec(PortType.LEVEL, false));
        h.deliveries.clear();
        JsonElement fingerprint = json("{\"payload\":\"3f9c\"}");
        h.bus.withRoot(Root.TWIN, () -> h.bus.emit(FlowMessage.Kind.VALUE, topic, "zigbee2mqtt/m3bulb", fingerprint, 0));
        h.bus.drain();
        check(h.targets().equals(List.of("plug.power")) && h.roots().equals(List.of(Root.TWIN)) && h.payloads().getFirst().equals(fingerprint.toString()),
                "A preset topic's twin wire delivers its object in TWIN, across kinds: " + h.targets() + " " + h.roots());
        h.bus.withRoot(Root.TWIN, () -> h.bus.emit(FlowMessage.Kind.VALUE, topic, "zigbee2mqtt/m3bulb", fingerprint, 0));
        h.bus.drain();
        check(h.deliveries.size() == 1, "The same fingerprint again passes the change filter as unchanged");
        value = h.bus.dropped(MessageBus.Drop.VALUE);
        h.bus.withRoot(Root.TWIN, () -> h.bus.emit(FlowMessage.Kind.VALUE, topic, "zigbee2mqtt/m3bulb", new JsonPrimitive("ON"), 0));
        h.bus.drain();
        check(h.bus.dropped(MessageBus.Drop.VALUE) == value + 1 && h.deliveries.size() == 1, "A preset topic's payload that is no object is dropped as VALUE");
    }

    private static void roots() {
        // Outside a delivery a message takes the extent's root, or WORLD when none is open.
        Harness h = new Harness();
        Endpoint input = h.node("input", "powered");
        h.wire(input, h.node("lamp", "state"), Mapping.FOLLOW, BOOL, SWITCH);
        check(h.bus.currentRoot().isEmpty(), "No root is current outside an extent");
        h.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(true));
        h.bus.drain();
        check(h.roots().equals(List.of(Root.WORLD)), "A block ticker's or neighbour update's message is WORLD: " + h.roots());
        for (Root root : Root.values()) {
            h.deliveries.clear();
            boolean value = root.ordinal() % 2 == 1;
            long emittedBefore = h.bus.emitted();
            long rootBefore = h.bus.emitted(root);
            h.bus.withRoot(root, () -> {
                check(h.bus.currentRoot().equals(Optional.of(root)), "The extent's root is current: " + root);
                h.emit(FlowMessage.Kind.VALUE, input, new JsonPrimitive(value));
            });
            check(h.bus.emitted() == emittedBefore + 1 && h.bus.emitted(root) == rootBefore + 1, "The message counts for " + root);
            check(h.bus.currentRoot().isEmpty(), "None is current after the extent of " + root);
            h.bus.drain();
            check(h.roots().equals(List.of(root)), "A message emitted in an extent of " + root + " carries it: " + h.roots());
        }
        check(h.bus.withRoot(Root.PLAYER, () -> h.bus.currentRoot().orElseThrow()) == Root.PLAYER, "An extent may return a value");
        try {
            h.bus.withRoot(Root.HA, () -> {
                throw new IllegalStateException("A command threw");
            });
            throw new AssertionError("The extent swallowed the exception");
        } catch (IllegalStateException expected) {
            check(h.bus.currentRoot().isEmpty(), "A throwing extent restores the root before it");
        }

        // Nested extents: the inner root, unless the outer came from outside the game; another outside root replaces it.
        check(nestedRoot(h, Root.WORLD, Root.PLAYER) == Root.PLAYER && nestedRoot(h, Root.PLAYER, Root.WORLD) == Root.WORLD
                && nestedRoot(h, Root.PLAYER, Root.TWIN) == Root.TWIN && nestedRoot(h, Root.WORLD, Root.STARTUP) == Root.STARTUP,
                "Inside an in-game extent the inner root is current");
        for (Root outer : List.of(Root.TWIN, Root.HA, Root.RAW, Root.STARTUP)) {
            check(nestedRoot(h, outer, Root.PLAYER) == outer && nestedRoot(h, outer, Root.WORLD) == outer,
                    "A nested in-game extent never clears " + outer);
        }
        check(nestedRoot(h, Root.TWIN, Root.HA) == Root.HA && nestedRoot(h, Root.STARTUP, Root.TWIN) == Root.TWIN, "An outside root replaces another");
        check(Root.nested(null, Root.RAW) == Root.RAW && Root.PLAYER.inGame() && Root.WORLD.inGame()
                && !Root.RAW.inGame() && !Root.HA.inGame() && !Root.TWIN.inGame() && !Root.STARTUP.inGame(), "Only players and the world are in game");

        // Inside a delivery a target's emission inherits the delivered message's root, whatever extent the drain runs
        // in; an extent the target opens nests in it, so it cannot clear an outside root either.
        Harness chain = new Harness();
        Endpoint start = chain.node("start", "out");
        Endpoint relayIn = chain.node("relay", "in");
        Endpoint relayOut = chain.node("relay", "out");
        Endpoint end = chain.node("end", "in");
        chain.wire(start, relayIn, Mapping.FOLLOW, LEVEL, LEVEL);
        chain.wire(relayOut, end, Mapping.FOLLOW, LEVEL, LEVEL);
        chain.react(relayIn, relayOut);
        chain.bus.withRoot(Root.TWIN, () -> {
            chain.emit(FlowMessage.Kind.VALUE, start, new JsonPrimitive(3));
        });
        chain.bus.withRoot(Root.PLAYER, () -> {
            chain.bus.drain();
        });
        check(chain.roots().equals(List.of(Root.TWIN, Root.TWIN)) && chain.deliveries.getLast().message().hops() == 2,
                "A relay's message inherits TWIN, not the drain's PLAYER, with its hops: " + chain.roots());
        check(chain.bus.currentRoot().isEmpty(), "No root is current after the drain");
        chain.deliveries.clear();
        chain.reactRoots.put(relayIn, Root.PLAYER);
        chain.bus.withRoot(Root.HA, () -> {
            chain.emit(FlowMessage.Kind.VALUE, start, new JsonPrimitive(4));
        });
        chain.bus.drain();
        check(chain.roots().equals(List.of(Root.HA, Root.HA)), "A PLAYER extent inside an HA delivery stays HA: " + chain.roots());
        chain.deliveries.clear();
        chain.reactRoots.put(relayIn, Root.TWIN);
        chain.emit(FlowMessage.Kind.VALUE, start, new JsonPrimitive(5));
        chain.bus.drain();
        check(chain.roots().equals(List.of(Root.WORLD, Root.TWIN)), "A TWIN extent inside a WORLD delivery is TWIN: " + chain.roots());

        // A value held back by the rate keeps the root it was emitted with, and a newer one replaces it with its own.
        Harness held = new Harness();
        Endpoint dial = held.node("dial", "level");
        held.wire(dial, held.node("lamp", "brightness"), Mapping.FOLLOW, LEVEL, LEVEL);
        for (int i = 0; i < MessageBus.RATE_BURST; i++) held.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(i % 2 + 1));
        held.bus.withRoot(Root.RAW, () -> {
            check(held.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(9)), "Held back");
        });
        held.bus.drain();
        held.deliveries.clear();
        held.clock.now += 1_000_000_000L / MessageBus.RATE_PER_SECOND;
        held.bus.withRoot(Root.PLAYER, () -> {
            held.bus.drain();
        });
        check(held.payloads().equals(List.of("9")) && held.roots().equals(List.of(Root.RAW)), "Released later, it is still RAW: " + held.roots());
        held.deliveries.clear();
        held.bus.withRoot(Root.HA, () -> {
            held.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(10));
        });
        held.emit(FlowMessage.Kind.VALUE, dial, new JsonPrimitive(11));
        held.clock.now += 1_000_000_000L / MessageBus.RATE_PER_SECOND;
        held.bus.drain();
        check(held.payloads().equals(List.of("11")) && held.roots().equals(List.of(Root.WORLD)), "The newest value replaces it with its root: " + held.roots());

        // A message a drain leaves halfway keeps its root for the rest of its wires.
        Harness fan = new Harness();
        Endpoint hub = fan.node("hub", "out");
        int wires = MessageBus.DRAIN_WIRES + 5;
        for (int i = 0; i < wires; i++) fan.wire(hub, fan.node("t" + i, "in"), Mapping.FOLLOW, LEVEL, LEVEL);
        fan.bus.withRoot(Root.STARTUP, () -> {
            fan.emit(FlowMessage.Kind.VALUE, hub, new JsonPrimitive(2));
        });
        check(fan.bus.drain() == MessageBus.DRAIN_WIRES && fan.bus.queued() == 1, "Left halfway");
        check(fan.bus.drain() == 5 && fan.deliveries.size() == wires && fan.roots().stream().allMatch(root -> root == Root.STARTUP),
                "Its other wires carry STARTUP too");

        // A root changes nothing about the hop TTL.
        Harness cycle = new Harness();
        Endpoint aOut = cycle.node("a", "pressed");
        Endpoint aIn = cycle.node("a", "trigger");
        Endpoint bOut = cycle.node("b", "pressed");
        Endpoint bIn = cycle.node("b", "trigger");
        cycle.wire(aOut, bIn, Mapping.FOLLOW, EVENT, EVENT);
        cycle.wire(bOut, aIn, Mapping.FOLLOW, EVENT, EVENT);
        cycle.react(aIn, aOut);
        cycle.react(bIn, bOut);
        cycle.bus.withRoot(Root.TWIN, () -> {
            cycle.emit(FlowMessage.Kind.EVENT, aOut, PRESS);
        });
        check(cycle.bus.drain() == MessageBus.HOP_TTL + 1 && cycle.deliveries.size() == MessageBus.HOP_TTL && cycle.bus.dropped(MessageBus.Drop.HOPS) == 1,
                "A TWIN cycle is cut at the same hop");
        check(cycle.roots().stream().allMatch(root -> root == Root.TWIN), "Every hop of it is TWIN");

        // A target that refuses a message for its root counts it as ROOT, not as refused, and remembers no value.
        Harness gate = new Harness();
        Endpoint powered = gate.node("input", "powered");
        Endpoint mirrored = gate.node("mirror", "state");
        gate.wire(powered, mirrored, Mapping.FOLLOW, BOOL, SWITCH);
        gate.rootRefusing.addAll(EnumSet.of(Root.RAW, Root.HA, Root.TWIN, Root.STARTUP));
        gate.bus.withRoot(Root.TWIN, () -> {
            gate.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive(true));
        });
        gate.bus.drain();
        check(gate.bus.dropped(MessageBus.Drop.ROOT) == 1 && gate.bus.dropped(MessageBus.Drop.REFUSED) == 0 && gate.bus.delivered() == 0,
                "Refused for its root and counted as such");
        gate.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive(true));
        gate.bus.drain();
        check(gate.bus.delivered() == 1 && gate.bus.suppressed() == 0 && gate.roots().getLast() == Root.WORLD,
                "The same value from the world passes, as the refused one was not remembered");
        gate.bus.withRoot(Root.STARTUP, () -> {
            gate.emit(FlowMessage.Kind.VALUE, powered, new JsonPrimitive(false));
        });
        gate.bus.drain();
        check(gate.bus.dropped(MessageBus.Drop.ROOT) == 2 && totalDrops(gate.bus) == 2, "Every refusal for a root counts");
    }

    // The root a message emitted in an extent of `inner`, opened inside one of `outer`, carries.
    private static Root nestedRoot(Harness h, Root outer, Root inner) {
        return h.bus.withRoot(outer, () -> h.bus.withRoot(inner, () -> h.bus.currentRoot().orElseThrow()));
    }

    private static void records() {
        UUID node = new UUID(1, 2);
        check(Endpoint.port(node, "state").equals(new Endpoint.PortRef(node, "state")), "Endpoints are values");
        check(!Endpoint.port(node, "state").equals(Endpoint.groupPort(node, "state")), "A group port is not a device port");
        check(Endpoint.isPortKey("power_on_behaviour") && !Endpoint.isPortKey("Power") && !Endpoint.isPortKey("1x") && !Endpoint.isPortKey("a".repeat(65)),
                "Port keys are written like field keys");
        fails(() -> Endpoint.port(node, "a.b"), "A port key with a dot");
        fails(() -> new Endpoint.TopicRef("", ""), "An empty topic");
        fails(() -> new Endpoint.TopicRef("a/b", null), "A topic end without a path");
        Endpoint.TopicRef whole = new Endpoint.TopicRef("zigbee2mqtt/porch", "");
        Endpoint.TopicRef path = new Endpoint.TopicRef("zigbee2mqtt/porch", "color.x");
        check(whole.describe().equals("topic zigbee2mqtt/porch") && path.describe().equals("topic zigbee2mqtt/porch color.x") && !whole.equals(path),
                "A topic end names its topic and path: " + path.describe());
        Endpoint.HaEntityRef brightness = new Endpoint.HaEntityRef("light.kitchen_ceiling", "brightness");
        check(brightness.describe().equals("entity light.kitchen_ceiling.brightness")
                && !brightness.equals(new Endpoint.HaEntityRef("light.kitchen_ceiling", "state")), "A Home Assistant end names its entity and port");
        check(new Endpoint.HaEntityRef("sensor.outdoor_temperature", "attr_unit_of_measurement").port().equals("attr_unit_of_measurement")
                && new Endpoint.HaEntityRef("input_number.m3_level", "value").entityId().equals("input_number.m3_level"), "Generic reads are ports too");
        for (String entity : new String[]{"", "light", "light.", ".kitchen", "Light.kitchen", "light.kitchen.x", "light/kitchen", "_x.y", "light.küche"}) {
            fails(() -> new Endpoint.HaEntityRef(entity, "state"), "The entity id '" + entity + "'");
        }
        fails(() -> new Endpoint.HaEntityRef("light.kitchen", "Brightness"), "A port that is no port key");
        fails(() -> new Endpoint.HaEntityRef("light.kitchen", "attr_" + "a".repeat(60)), "A port longer than a port key");
        check(!whole.reserved() && !brightness.reserved() && !Endpoint.port(node, "x").reserved() && !Endpoint.groupPort(node, "x").reserved(),
                "No endpoint is reserved any more");
        fails(() -> new Wire(UUID.randomUUID(), Endpoint.port(node, "a"), null, Mapping.FOLLOW, Scope.WIRELESS, OWNER, true), "A wire without a target");
        fails(() -> new FlowMessage(UUID.randomUUID(), FlowMessage.Kind.VALUE, new JsonPrimitive(1), null, Endpoint.port(node, "a"), -1, Root.WORLD, 0), "Negative hops");
        fails(() -> new FlowMessage(UUID.randomUUID(), FlowMessage.Kind.VALUE, new JsonPrimitive(1), null, Endpoint.port(node, "a"), 0, null, 0), "No root");
        Wire wire = new Wire(new UUID(0, 9), Endpoint.port(node, "powered"), Endpoint.groupPort(node, "state"), Mapping.PULSE_TOGGLE, Scope.WIRELESS, OWNER, true);
        check(wire.describe().equals(node + ".powered -> group " + node + ".state (pulse_toggle)"), "A wire describes itself: " + wire.describe());
    }

    // The M2 plan §3.1 coercion table.
    private static void coercion() {
        PortType number = PortType.number(-100, 100, false, null);
        PortType text = PortType.text(8);
        expectCoerced(new JsonPrimitive(true), PortType.BOOL, PortType.LEVEL, false, new JsonPrimitive(15), "BOOL true -> LEVEL 15");
        expectCoerced(new JsonPrimitive("off"), PortType.BOOL, PortType.LEVEL, false, new JsonPrimitive(0), "BOOL off -> LEVEL 0");
        expectCoerced(new JsonPrimitive(0), PortType.LEVEL, PortType.BOOL, false, new JsonPrimitive(false), "LEVEL 0 -> false");
        expectCoerced(new JsonPrimitive(7), PortType.LEVEL, PortType.BOOL, false, new JsonPrimitive(true), "LEVEL 7 -> true");
        expectCoerced(new JsonPrimitive(7.6), number, PortType.LEVEL, false, new JsonPrimitive(8), "NUMBER 7.6 -> LEVEL 8");
        expectCoerced(new JsonPrimitive(99), number, PortType.LEVEL, false, new JsonPrimitive(15), "NUMBER 99 -> LEVEL 15");
        expectCoerced(new JsonPrimitive(-3), number, PortType.LEVEL, false, new JsonPrimitive(0), "NUMBER -3 -> LEVEL 0");
        expectCoerced(new JsonPrimitive(new BigDecimal("1e300")), PortType.number(0, Double.MAX_VALUE, false, null), PortType.LEVEL, false,
                new JsonPrimitive(15), "A huge number is clamped before it is rounded");
        expectCoerced(PRESS, PortType.event("press"), PortType.BOOL, true, new JsonPrimitive("TOGGLE"), "EVENT -> switch state toggles");
        expectCoerced(PRESS, PortType.event("press"), PortType.event("trigger_now"), false, new JsonPrimitive("PRESS"), "EVENT -> action fires");
        expectCoerced(new JsonPrimitive("on"), text, PortType.BOOL, false, new JsonPrimitive(true), "TEXT on -> true");
        check(Coerce.coerce(new JsonPrimitive("maybe"), text, PortType.BOOL, false).isEmpty(), "TEXT maybe -> nothing");
        expectCoerced(json("{\"r\":0,\"g\":0,\"b\":0}"), PortType.COLOR, PortType.BOOL, false, new JsonPrimitive(false), "COLOR black -> false");
        expectCoerced(json("{\"r\":0,\"g\":1,\"b\":0}"), PortType.COLOR, PortType.BOOL, false, new JsonPrimitive(true), "COLOR not black -> true");
        expectCoerced(new JsonPrimitive(15), PortType.LEVEL, text, false, new JsonPrimitive("15"), "LEVEL -> TEXT");
        expectCoerced(json("{\"a\":1}"), PortType.JSON, text, false, new JsonPrimitive("{\"a\":1}"), "JSON -> TEXT");
        expectCoerced(new JsonPrimitive("§cab\u0007cdefghijk"), PortType.text(64), text, false, new JsonPrimitive("abcdefgh"), "TEXT is sanitised and cut");
        expectCoerced(new JsonPrimitive(250), number, PortType.number(0, 10, true, null), false, null, "NUMBER 250 does not fit its source");
        expectCoerced(new JsonPrimitive(42.4), number, PortType.number(0, 10, true, null), false, new JsonPrimitive(10), "NUMBER -> NUMBER is clamped");
        expectCoerced(new JsonPrimitive("B"), PortType.enumOf(List.of("a", "b")), PortType.enumOf(List.of("A", "b", "c")), false, new JsonPrimitive("b"),
                "ENUM -> ENUM in the target's spelling");
        expectCoerced(new JsonPrimitive(16), PortType.LEVEL, PortType.BOOL, false, null, "A value that does not fit its source kind is dropped");
        check(!Coerce.compatible(PortType.event("press"), PortType.BOOL, false), "An event does not toggle a plain bool");
        check(!Coerce.compatible(PortType.LEVEL, PortType.enumOf(List.of("a")), false) && !Coerce.compatible(text, PortType.LEVEL, false)
                && !Coerce.compatible(PortType.event("press"), text, false) && !Coerce.compatible(PortType.BOOL, PortType.event("press"), false)
                && !Coerce.compatible(PortType.LEVEL, number, false) && !Coerce.compatible(PortType.BOOL, PortType.LIGHT, false), "Incompatible pairs");
        check(Coerce.compatible(PortType.enumOf(List.of("a", "b")), PortType.enumOf(List.of("A", "b", "c")), false)
                && !Coerce.compatible(PortType.enumOf(List.of("a", "d")), PortType.enumOf(List.of("a", "b")), false), "ENUM -> ENUM only into a superset");
    }

    private static void expectCoerced(JsonElement value, PortType from, PortType to, boolean toggles, JsonElement expected, String message) {
        Optional<JsonElement> coerced = Coerce.coerce(value, from, to, toggles);
        check(expected == null ? coerced.isEmpty() : coerced.isPresent() && coerced.get().equals(expected), message + ": " + coerced);
    }

    private static long totalDrops(MessageBus bus) {
        long total = 0;
        for (MessageBus.Drop drop : MessageBus.Drop.values()) total += bus.dropped(drop);
        return total;
    }

    private static JsonElement nested(int depth) {
        JsonElement value = new JsonPrimitive(1);
        for (int i = 0; i < depth; i++) {
            JsonArray array = new JsonArray();
            array.add(value);
            value = array;
        }
        return value;
    }

    private static PortSpec spec(PortType kind, boolean toggles) {
        return new Spec(kind, toggles);
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static void fails(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        throw new AssertionError(message + " was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Spec(PortType kind, boolean toggles) implements PortSpec {
    }

    private record Delivery(Wire wire, FlowMessage message) {
    }

    private static final class Clock {
        private long now = 1_000_000_000L;
        private long step;

        long read() {
            now += step;
            return now;
        }
    }

    /**
     * A bus over an in-memory wiring. Nodes are named; a node's id is derived from its name. A reacting in-port emits on
     * its node's out-port when a message arrives, as a device publishes its new state, so emissions happen inside a
     * delivery.
     */
    private static final class Harness {
        final Clock clock = new Clock();
        final Map<Endpoint, List<Wire>> wires = new HashMap<>();
        final Map<Endpoint, PortSpec> outs = new HashMap<>();
        final Map<Endpoint, PortSpec> ins = new HashMap<>();
        final Map<Endpoint, Endpoint> reactions = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        final List<Endpoint> refusing = new ArrayList<>();
        final List<Endpoint> throwing = new ArrayList<>();
        // Roots every target refuses, as a mirrored device's gate does (M3 plan D10).
        final Set<Root> rootRefusing = EnumSet.noneOf(Root.class);
        // In-ports that react in an extent of their own root, as a command from another source would.
        final Map<Endpoint, Root> reactRoots = new HashMap<>();
        final List<Delivery> deliveries = new ArrayList<>();
        final List<UUID> loopCuts = new ArrayList<>();
        final List<RuntimeException> failures = new ArrayList<>();
        boolean reentrant;
        boolean unwiring;
        final MessageBus bus = new MessageBus(new MessageBus.Wiring() {
            @Override
            public List<Wire> from(Endpoint source) {
                return wires.getOrDefault(source, List.of());
            }

            @Override
            public Optional<? extends PortSpec> out(Endpoint source) {
                return Optional.ofNullable(outs.get(source));
            }

            @Override
            public Optional<? extends PortSpec> in(Endpoint target) {
                return Optional.ofNullable(ins.get(target));
            }
        }, this::deliver, new MessageBus.Listener() {
            @Override
            public void loopCut(Wire wire, FlowMessage message) {
                loopCuts.add(wire.id());
            }

            @Override
            public void failed(Wire wire, FlowMessage message, RuntimeException e) {
                failures.add(e);
            }
        }, clock::read);

        Endpoint node(String name, String port) {
            UUID id = UUID.nameUUIDFromBytes(name.getBytes());
            names.put(id, name);
            return Endpoint.port(id, port);
        }

        Wire wire(Endpoint from, Endpoint to, Mapping mapping, PortSpec out, PortSpec in) {
            return wire(from, to, mapping, out, in, true);
        }

        // A null spec leaves the port undeclared.
        Wire wire(Endpoint from, Endpoint to, Mapping mapping, PortSpec out, PortSpec in, boolean enabled) {
            Wire wire = new Wire(UUID.randomUUID(), from, to, mapping, Scope.WIRELESS, OWNER, enabled);
            wires.computeIfAbsent(from, f -> new ArrayList<>()).add(wire);
            if (out != null) outs.put(from, out);
            if (in != null) ins.put(to, in);
            return wire;
        }

        void react(Endpoint in, Endpoint out) {
            reactions.put(in, out);
        }

        boolean emit(FlowMessage.Kind kind, Endpoint source, JsonElement payload) {
            return bus.emit(kind, source, ((Endpoint.PortRef) source).port(), payload, 0);
        }

        private MessageBus.Outcome deliver(Wire wire, FlowMessage message) {
            if (reentrant) bus.drain();
            if (throwing.contains(wire.to())) throw new IllegalStateException("A device threw");
            deliveries.add(new Delivery(wire, message));
            if (unwiring) wires.get(wire.from()).clear();
            if (refusing.contains(wire.to())) return MessageBus.Outcome.REFUSED;
            if (rootRefusing.contains(message.root())) return MessageBus.Outcome.ROOT;
            Endpoint reaction = reactions.get(wire.to());
            if (reaction != null) {
                Runnable react = () -> bus.emit(message.kind(), reaction, ((Endpoint.PortRef) reaction).port(), message.payload(), message.gameTime());
                Root own = reactRoots.get(wire.to());
                if (own == null) {
                    react.run();
                } else {
                    bus.withRoot(own, react);
                }
            }
            return MessageBus.Outcome.TAKEN;
        }

        List<Root> roots() {
            return deliveries.stream().map(delivery -> delivery.message().root()).toList();
        }

        List<String> targets() {
            return deliveries.stream().map(delivery -> label(delivery.wire().to())).toList();
        }

        List<String> targetsFrom() {
            return deliveries.stream().map(delivery -> label(delivery.wire().from())).toList();
        }

        List<String> payloads() {
            return deliveries.stream().map(delivery -> delivery.message().payload().toString()).toList();
        }

        private String label(Endpoint endpoint) {
            Endpoint.PortRef port = (Endpoint.PortRef) endpoint;
            return names.get(port.node()) + "." + port.port();
        }
    }
}

package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.DefaultWiring;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.TimerWiringProbe;
import art.rehra.mineqtt.device.EventSpec;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.BlockPlacer;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.ha.Ha;
import art.rehra.mineqtt.util.GameClock;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.RegistryStore;
import art.rehra.mineqtt.device.Slugs;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.HiveMqttService;
import art.rehra.mineqtt.mqtt.core.InboundHandler;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The runtime pieces that need no running server: bridge id generation, the bridge topics, and how
 * {@link ReplaceableMqttService} carries subscriptions, connect listeners and the inbound listener over to a replacement
 * connection, what a config reload does to Raw Links (the grandfathered v1 topics of the former Block Sensor and Block
 * Emitter among them) and which subscriptions of devices and legacy groups it opens or closes, and when the pump of a paused server ({@link PausedServerPump}) runs. Since M4-1 also the
 * default wiring's order (M4 plan D15) over the shipped and synthetic device types, which needs the game bootstrapped,
 * and the day clock ({@link GameClock}) the timers and the telemetry share.
 */
public final class RuntimeWiringCheck {
    private static final Slf4jMqttLog LOG = new Slf4jMqttLog(LoggerFactory.getLogger("mineqtt-check"));
    private static final String BRIDGE = "world_7f3a";

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DeviceTypes.registerBuiltIn();
        defaultWiring();
        gameClock();
        slugs();
        bridgeIds();
        topics();
        replacementOrder();
        deliveryAcrossReplacement();
        closedService();
        aclChanged();
        pauseTable();
        inboundListener();
        pauseWake();
        System.out.println("Runtime wiring checks passed.");
    }

    private static void slugs() {
        check(Slugs.fromName("New World").equals("new_world"), "Spaces become one underscore: " + Slugs.fromName("New World"));
        check(Slugs.fromName("Café Ünïcödé").equals("cafe_unicode"), "Accents are dropped: " + Slugs.fromName("Café Ünïcödé"));
        check(Slugs.fromName("  --My World!--  ").equals("my_world"), "Separators at the ends are trimmed: " + Slugs.fromName("  --My World!--  "));
        check(Slugs.fromName("世界").isEmpty(), "Text without usable characters gives an empty slug");
        String longSlug = Slugs.fromName("a".repeat(47) + " b" + "c".repeat(20));
        check(longSlug.length() <= Slugs.MAX_LENGTH && !longSlug.endsWith("_"), "Long names are cut without a trailing separator: " + longSlug);
    }

    private static void bridgeIds() {
        String id = RegistryStore.generateBridgeId("New World", new Random(1));
        check(id.matches("new_world_[0-9a-f]{4}"), "The bridge id is slug(levelName)_<4 hex>: " + id);
        check(id.equals(RegistryStore.generateBridgeId("New World", new Random(1))), "The suffix comes from the random generator");
        check(RegistryStore.generateBridgeId("世界", new Random(2)).matches("world_[0-9a-f]{4}"), "A name without usable characters falls back to world");
        for (String name : List.of("world", "Ünïcödé Wörld", "x".repeat(200), "...", "a/b+c#d")) {
            for (int seed = 0; seed < 50; seed++) {
                String generated = RegistryStore.generateBridgeId(name, new Random(seed));
                check(ConfigValidation.isValidBridgeId(generated) && !generated.isEmpty(), "A generated bridge id is valid: " + generated);
            }
        }
    }

    private static void topics() {
        TopicScheme topics = new TopicScheme("mineqtt", "world_7f3a");
        check(topics.bridgeState().equals("mineqtt/world_7f3a/bridge/state"), "Bridge state topic: " + topics.bridgeState());
        check(topics.bridgeInfo().equals("mineqtt/world_7f3a/bridge/info"), "Bridge info topic: " + topics.bridgeInfo());
    }

    /** The old connection closes before its replacement exists, which then gets every listener and open subscription. */
    private static void replacementOrder() {
        List<String> events = new ArrayList<>();
        RecordingService first = new RecordingService("first", events);
        ReplaceableMqttService mqtt = new ReplaceableMqttService(first);
        Runnable listener = () -> {
        };
        mqtt.onConnected(listener);
        Subscription kept = mqtt.subscribe("a/#", SubOptions.COMMAND, message -> {
        });
        Subscription dropped = mqtt.subscribe("b", SubOptions.COMMAND, message -> {
        });
        dropped.close();
        check(first.listeners.equals(List.of(listener)), "A listener reaches the current service");

        RecordingService[] second = new RecordingService[1];
        mqtt.replace(() -> {
            events.add("create second");
            return second[0] = new RecordingService("second", events);
        });
        check(events.indexOf("close first") >= 0 && events.indexOf("close first") < events.indexOf("create second"),
                "The old service closes before the replacement is created: " + events);
        check(second[0].listeners.equals(List.of(listener)), "Connect listeners move to the replacement");
        check(second[0].filters.equals(List.of("a/#")), "Only open subscriptions move to the replacement: " + second[0].filters);
        check(kept.filter().equals("a/#"), "A subscription keeps its filter");

        kept.close();
        check(second[0].closedFilters.equals(List.of("a/#")), "Closing a moved subscription closes it on the replacement");
        check(first.closedFilters.equals(List.of("b")), "The old service only saw its own unsubscribe: " + first.closedFilters);
    }

    /** Real disabled services: the local loopback keeps delivering to a subscription after the service is replaced. */
    private static void deliveryAcrossReplacement() {
        HiveMqttService.Options options = HiveMqttService.Options.defaults(null);
        ReplaceableMqttService mqtt = new ReplaceableMqttService(HiveMqttService.disabled(options, LOG));
        List<String> received = new ArrayList<>();
        mqtt.subscribe("mineqtt/shared/#", SubOptions.raw(true, UUID.randomUUID()), message -> received.add(message.text()));

        publishAndDeliver(mqtt, "before");
        mqtt.replace(() -> HiveMqttService.disabled(options, LOG));
        publishAndDeliver(mqtt, "after");
        check(received.equals(List.of("before", "after")), "The subscription survives the replacement: " + received);
        check(mqtt.state().status() == ConnectionState.Status.DISABLED, "The state is the replacement's: " + mqtt.state());
    }

    private static void closedService() {
        HiveMqttService.Options options = HiveMqttService.Options.defaults(null);
        ReplaceableMqttService mqtt = new ReplaceableMqttService(HiveMqttService.disabled(options, LOG));
        List<String> received = new ArrayList<>();
        mqtt.close();
        Subscription late = mqtt.subscribe("mineqtt/shared/#", SubOptions.raw(true, UUID.randomUUID()), message -> received.add(message.text()));
        publishAndDeliver(mqtt, "ignored");
        late.close();
        check(received.isEmpty(), "A closed service delivers nothing");
        check(mqtt.state().status() == ConnectionState.Status.STOPPED, "A closed service is stopped: " + mqtt.state());
        mqtt.replace(() -> {
            throw new AssertionError("A closed service is never replaced");
        });
    }

    /**
     * What a config reload does to the Raw Links of attached devices ({@code DeviceRuntime.aclChanged}, M2 plan D1): a
     * link with a channel is resolved again, so it is refused once {@code mineqtt/shared/#} leaves the allowlist and
     * opens again when it returns; a grandfathered v1 topic stays where it is, and the ACL judges it each time it is used.
     */
    private static void aclChanged() {
        UUID steve = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        TopicAcl.Subject owner = new TopicAcl.Subject(steve, false, false);
        TopicAcl defaults = acl(TopicAcl.defaultRawAllow("mineqtt", BRIDGE), "homeassistant");
        TopicAcl withoutShared = acl(List.of("mineqtt/" + BRIDGE + "/raw/#"), "homeassistant");

        RawLink garage = RawTopics.resolve("garage", false, defaults, steve, Set.of()).link(RawLinkSpec.Direction.OUT, false);
        check(garage.active() && garage.topic().equals("mineqtt/shared/garage") && RawTopics.problem(garage, defaults, owner).isEmpty(),
                "Paper 'garage' works on its shared form: " + garage);
        RawLink refused = RawTopics.recheck(garage, false, withoutShared, steve, Set.of());
        check(!refused.active() && refused.channel().equals("garage") && RawTopics.problem(refused, withoutShared, owner)
                        .filter(problem -> problem.contains(TopicAcl.Decision.NOT_ALLOWLISTED.message())).isPresent(),
                "Without mineqtt/shared/# the reload disables the link with the reason: " + refused);
        check(RawTopics.recheck(refused, false, withoutShared, steve, Set.of()).equals(refused), "Judged again under the same ACL, nothing changes");
        check(RawTopics.recheck(refused, false, defaults, steve, Set.of()).equals(garage), "Restoring the allowlist opens the link again as it was");

        TopicAcl home = acl(List.of("mineqtt/shared/#", "home/#"), "homeassistant");
        RawLink door = RawTopics.resolve("home/door", false, defaults, steve, Set.of()).link(RawLinkSpec.Direction.IN, false);
        RawLink verbatim = RawTopics.recheck(door, false, home, steve, Set.of());
        check(door.topic().equals("mineqtt/shared/home/door") && verbatim.topic().equals("home/door") && verbatim.active(),
                "A literal the reload allowlists moves to the literal: " + verbatim);

        String v1Topic = "zigbee2mqtt/x/set";
        RawLink v1 = new RawLink(v1Topic, RawLinkSpec.Direction.IN, null, null, 0, null, new TopicAcl.Grandfather(v1Topic, steve),
                false, null, RawTopics.VERSION, null, RawLink.Edges.BOTH);
        check(RawTopics.recheck(v1, false, withoutShared, steve, Set.of()) == v1 && RawTopics.problem(v1, withoutShared, owner).isEmpty(),
                "A grandfathered v1 topic stays and keeps working whatever the allowlist says");
        TopicAcl zigbeeDiscovery = acl(TopicAcl.defaultRawAllow("mineqtt", BRIDGE), "zigbee2mqtt");
        check(RawTopics.problem(v1, zigbeeDiscovery, owner).filter(problem -> problem.equals(TopicAcl.Decision.DENY_DISCOVERY.message())).isPresent(),
                "A reload that hard-denies its topic refuses it: " + RawTopics.problem(v1, zigbeeDiscovery, owner));
        check(RawTopics.problem(v1, defaults, owner).isEmpty(), "Changed back, it works again");

        // Which subscriptions the reload opens or closes: Raw Link inputs and the v1 topics of legacy groups.
        check(DeviceRuntime.verdictChanged(true, true), "An open subscription the reload refuses closes");
        check(DeviceRuntime.verdictChanged(false, false), "A closed subscription the reload allows opens");
        check(!DeviceRuntime.verdictChanged(true, false), "An open subscription that stays allowed is kept, no retained value again");
        check(!DeviceRuntime.verdictChanged(false, true), "A refused subscription that stays refused stays closed");
        migratedLinks(steve, owner, defaults, withoutShared);
    }

    /**
     * The v1 topics of the Block Sensor and the Block Emitter, which are the grandfathered Raw Links of the Block Observer
     * and the Block Placer since M4-9 and M4-10 (adopted as {@code DeviceLink.adopt} does: the slots' literal, no
     * channel), under a config reload ({@code DeviceRuntime.aclChanged}, M4 plan D19): they stay on their topic, the
     * allowlist never refuses them, and the hard deny does, closing the placer's subscription until a later reload
     * allows the topic again. The placer never takes a retained command when it opens again.
     */
    private static void migratedLinks(UUID steve, TopicAcl.Subject owner, TopicAcl defaults, TopicAcl withoutShared) {
        RawLinkSpec placerSpec = BlockPlacer.TYPE.rawLink().orElseThrow();
        RawLinkSpec observerSpec = BlockObserver.TYPE.rawLink().orElseThrow();
        check(placerSpec.direction() == RawLinkSpec.Direction.IN && !placerSpec.acceptRetained(), "The placer's link is an input that ignores retained values");
        check(observerSpec.direction() == RawLinkSpec.Direction.OUT, "The observer's link is an output");
        RawLink placer = new RawLink("garage/door", placerSpec.direction(), null, null, 0, null, new TopicAcl.Grandfather("garage/door", steve),
                false, null, 0, null, RawLink.Edges.BOTH);
        RawLink observer = new RawLink("garage/watch", observerSpec.direction(), null, null, 0, Boolean.FALSE,
                new TopicAcl.Grandfather("garage/watch", steve), false, null, 0, null, RawLink.Edges.BOTH);
        TopicAcl garageStatestream = new TopicAcl(new TopicAcl.Rules("mineqtt", BRIDGE, "homeassistant", "garage",
                TopicAcl.defaultRawAllow("mineqtt", BRIDGE), false, false));
        for (RawLink link : List.of(placer, observer)) {
            String which = link == placer ? "placer" : "observer";
            check(RawTopics.recheck(link, false, withoutShared, steve, Set.of()) == link && RawTopics.problem(link, withoutShared, owner).isEmpty(),
                    "A migrated " + which + " stays on its v1 topic and works whatever the allowlist says");
            check(RawTopics.recheck(link, false, garageStatestream, steve, Set.of()) == link
                            && RawTopics.problem(link, garageStatestream, owner).equals(Optional.of(TopicAcl.Decision.DENY_STATESTREAM.message())),
                    "A reload that puts the statestream namespace over a migrated " + which + "'s topic refuses it: "
                            + RawTopics.problem(link, garageStatestream, owner));
            check(RawTopics.problem(link, defaults, owner).isEmpty(), "Changed back, the " + which + " works again");
            check(RawTopics.problem(link, defaults, new TopicAcl.Subject(UUID.randomUUID(), false, false)).isPresent(),
                    "The " + which + "'s exemption is its owner's alone");
        }
        // The placer's subscription across the two reloads, as DeviceRuntime.aclChanged decides it.
        boolean subscribed = RawTopics.problem(placer, defaults, owner).isEmpty();
        check(subscribed, "The placer subscribes under the defaults");
        check(!DeviceRuntime.verdictChanged(subscribed, RawTopics.problem(placer, withoutShared, owner).isPresent()),
                "Losing the shared namespace leaves its subscription open, no retained value again");
        check(DeviceRuntime.verdictChanged(subscribed, RawTopics.problem(placer, garageStatestream, owner).isPresent()),
                "The statestream reload closes it");
        subscribed = false;
        check(!DeviceRuntime.verdictChanged(subscribed, RawTopics.problem(placer, garageStatestream, owner).isPresent()),
                "Judged again under the same ACL, it stays closed");
        check(DeviceRuntime.verdictChanged(subscribed, RawTopics.problem(placer, defaults, owner).isPresent()), "Restored, it opens again");
    }

    /**
     * {@code shouldPump}: only an empty dedicated server that pauses, whose ticks stopped (for longer than 250 ms or two
     * ticks at a slow tick rate), and only until MineQTT stops.
     */
    private static void pauseTable() {
        long now = TimeUnit.SECONDS.toNanos(1_000);
        long tick = TimeUnit.MILLISECONDS.toNanos(50);
        long stale = now - TimeUnit.MILLISECONDS.toNanos(PausedServerPump.PERIOD_MILLIS + 1);
        long recent = now - TimeUnit.MILLISECONDS.toNanos(PausedServerPump.PERIOD_MILLIS);
        check(PausedServerPump.shouldPump(true, 60, 0, stale, tick, now, false), "An empty dedicated server without ticks is pumped");
        check(!PausedServerPump.shouldPump(false, 60, 0, stale, tick, now, false), "Never a singleplayer or LAN server");
        check(!PausedServerPump.shouldPump(true, 0, 0, stale, tick, now, false), "Not with pause-when-empty-seconds=0: that server never pauses");
        check(!PausedServerPump.shouldPump(true, 60, 1, stale, tick, now, false), "Not with a player online");
        check(!PausedServerPump.shouldPump(true, 60, 0, recent, tick, now, false), "Not while ticks still run (250 ms or less since the last)");
        check(!PausedServerPump.shouldPump(true, 60, 0, stale, tick, now, true), "Never after stop()");

        long slowTick = TimeUnit.MILLISECONDS.toNanos(500);
        long betweenSlowTicks = now - TimeUnit.MILLISECONDS.toNanos(600);
        check(!PausedServerPump.shouldPump(true, 60, 0, betweenSlowTicks, slowTick, now, false),
                "Not between the ticks of /tick rate 2 (two ticks or less since the last)");
        check(PausedServerPump.shouldPump(true, 60, 0, now - 2 * slowTick - 1, slowTick, now, false),
                "A server at /tick rate 2 is pumped once two ticks passed without one");
    }

    /**
     * The inbound listener of real disabled services, told by local copies: once per burst, again after a drain, carried
     * over to a replacement and silent once closed. Pending work shows until the flush and the drain have run.
     */
    private static void inboundListener() {
        HiveMqttService.Options options = HiveMqttService.Options.defaults(null);
        ReplaceableMqttService mqtt = new ReplaceableMqttService(HiveMqttService.disabled(options, LOG));
        mqtt.subscribe("mineqtt/shared/#", SubOptions.raw(true, UUID.randomUUID()), message -> {
        });
        AtomicInteger wakes = new AtomicInteger();
        mqtt.onInboundAvailable(wakes::incrementAndGet);
        check(!mqtt.hasPendingWork() && wakes.get() == 0, "An idle service has no work and wakes nobody");

        mqtt.publish(Outbound.raw("mineqtt/shared/door", "on".getBytes(StandardCharsets.UTF_8), 0, UUID.randomUUID()));
        check(mqtt.hasPendingWork() && wakes.get() == 0, "A publish is pending work until the flush, which a paused server needs a pump for");
        mqtt.flushOutbound();
        mqtt.publish(Outbound.raw("mineqtt/shared/door", "off".getBytes(StandardCharsets.UTF_8), 0, UUID.randomUUID()));
        mqtt.flushOutbound();
        check(wakes.get() == 1 && mqtt.hasPendingWork(), "Two local copies before a drain wake once: " + wakes.get());
        mqtt.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
        check(!mqtt.hasPendingWork(), "Nothing is pending after the flush and the drain");
        publishAndDeliver(mqtt, "again");
        check(wakes.get() == 2, "The drain re-arms the listener: " + wakes.get());

        mqtt.replace(() -> HiveMqttService.disabled(options, LOG));
        publishAndDeliver(mqtt, "replaced");
        check(wakes.get() == 3, "The listener survives a replacement: " + wakes.get());
        RecordingService[] recording = new RecordingService[1];
        mqtt.replace(() -> recording[0] = new RecordingService("recording", new ArrayList<>()));
        check(recording[0].inboundListener != null, "Each replacement is given the listener");

        ReplaceableMqttService closed = new ReplaceableMqttService(HiveMqttService.disabled(options, LOG));
        closed.subscribe("mineqtt/shared/#", SubOptions.raw(true, UUID.randomUUID()), message -> {
        });
        AtomicInteger closedWakes = new AtomicInteger();
        closed.onInboundAvailable(closedWakes::incrementAndGet);
        closed.close();
        publishAndDeliver(closed, "ignored");
        check(closedWakes.get() == 0 && !closed.hasPendingWork(), "A closed service takes no publish, so nothing wakes and no work is left");
    }

    /**
     * The wake thread posts a pump only while the server is paused: once when the pause begins, then only while work is
     * pending, never a second one while one waits, and nothing once closed, also not a pump that was still queued.
     */
    private static void pauseWake() {
        ConcurrentLinkedQueue<Runnable> posted = new ConcurrentLinkedQueue<>();
        AtomicBoolean paused = new AtomicBoolean();
        AtomicBoolean pending = new AtomicBoolean(true);
        AtomicInteger pumps = new AtomicInteger();
        PausedServerPump pump = new PausedServerPump(posted::add, pumps::incrementAndGet, paused::get, pending::get, LOG, 10);
        try {
            sleep(100);
            pump.wake();
            check(posted.isEmpty(), "Nothing is posted while the server ticks, work or not");

            pending.set(false);
            paused.set(true);
            awaitTrue(() -> !posted.isEmpty(), "the wake when the pause begins");
            sleep(100);
            check(posted.size() == 1, "One pump when the pause begins, none for an idle paused server: " + posted.size());
            posted.poll().run();
            sleep(100);
            check(posted.isEmpty() && pumps.get() == 1, "No work, no more pumps: " + posted.size());

            pending.set(true);
            awaitTrue(() -> !posted.isEmpty(), "a wake for pending work");
            pump.wake();
            sleep(100);
            check(posted.size() == 1, "A waiting pump is never posted twice: " + posted.size());
            posted.poll().run();
            check(pumps.get() == 2, "The posted task runs the pump");
            awaitTrue(() -> !posted.isEmpty(), "the next wake while work is still pending");

            paused.set(false);
            posted.poll().run();
            sleep(100);
            check(posted.isEmpty(), "No wakes once the server ticks again: " + posted.size());

            paused.set(true);
            awaitTrue(() -> !posted.isEmpty(), "the wake of the next pause");
        } finally {
            pump.close();
        }
        awaitTrue(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("MineQTT pause wake")),
                "the wake thread to end on close()");
        int before = pumps.get();
        posted.poll().run();
        pump.wake();
        sleep(100);
        check(posted.isEmpty() && pumps.get() == before, "A closed pump posts nothing and runs nothing: " + posted.size());
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + what);
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    private static TopicAcl acl(List<String> rawAllow, String discoveryPrefix) {
        return new TopicAcl(new TopicAcl.Rules("mineqtt", BRIDGE, discoveryPrefix, null, rawAllow, false, false));
    }

    private static void publishAndDeliver(MqttService mqtt, String payload) {
        mqtt.publish(Outbound.raw("mineqtt/shared/door", payload.getBytes(StandardCharsets.UTF_8), 0, UUID.randomUUID()));
        mqtt.flushOutbound();
        mqtt.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
    }

    /** The default pairs before M4 (M2-15), in their order; {@link #defaultWiring} proves M4's order resolves alike for them. */
    private static final List<DefaultWiring.Pair> M3_PAIRS = List.of(
            new DefaultWiring.Pair("powered", Action.STATE, Mapping.FOLLOW),
            new DefaultWiring.Pair("press", Action.STATE, Mapping.PULSE_TOGGLE),
            new DefaultWiring.Pair("occupancy", Action.STATE, Mapping.FOLLOW));

    /**
     * M4 plan D15: the default wiring's full order is in place, every resolution between the shipped types is the one the
     * M3 order gives (no shipped type has a {@code trigger} in-port or a {@code fired} out-port, and the Redstone Input's
     * {@code signal} loses to its {@code powered}) but the ones M4's types add (the Block Observer's {@code signal} into the
     * {@code power} of a Redstone Output and a Smart Plug), and on synthetic types each new pair wins where it should: a press
     * fires a timer rather than toggling its enable, a timer fires the next, a lever arms a timer, a timer toggles a lamp,
     * and a comparator level drives a Redstone Output's power.
     */
    private static void defaultWiring() {
        check(DefaultWiring.pairs().equals(List.of(
                new DefaultWiring.Pair("press", "trigger", Mapping.FOLLOW),
                new DefaultWiring.Pair("fired", "trigger", Mapping.FOLLOW),
                new DefaultWiring.Pair("powered", "state", Mapping.FOLLOW),
                new DefaultWiring.Pair("press", "state", Mapping.PULSE_TOGGLE),
                new DefaultWiring.Pair("occupancy", "state", Mapping.FOLLOW),
                new DefaultWiring.Pair("signal", "power", Mapping.FOLLOW),
                new DefaultWiring.Pair("fired", "state", Mapping.PULSE_TOGGLE))), "The D15 order, first match wins: " + DefaultWiring.pairs());
        // The resolutions M4's types add on top of the M3 order; every other pair resolves as before M4.
        Map<List<DeviceType>, DefaultWiring.Pair> m4 = Map.of(
                List.of(BlockObserver.TYPE, RedstoneOutput.TYPE), new DefaultWiring.Pair("signal", "power", Mapping.FOLLOW),
                List.of(BlockObserver.TYPE, SmartPlug.TYPE), new DefaultWiring.Pair("signal", "power", Mapping.FOLLOW));
        int resolved = 0;
        // The eight types M3 shipped, named, so a type M4 adds is judged by its own expectations rather than M3's pairs; the
        // Block Observer and Block Placer with them (their additions are in m4), the timers in shippedTimerWiring.
        List<DeviceType> m3Types = List.of(SmartLamp.TYPE, RedstoneOutput.TYPE, RedstoneInput.TYPE, PresenceSensor.TYPE, SmartPlug.TYPE, ClimateSensor.TYPE,
                WallSwitch.TYPE, DisplayPanel.TYPE);
        check(DeviceTypes.all().containsAll(m3Types), "The eight types of M3 are registered");
        List<DeviceType> judged = new ArrayList<>(m3Types);
        judged.addAll(List.of(BlockObserver.TYPE, BlockPlacer.TYPE));
        check(DeviceTypes.all().containsAll(judged), "The Block Observer and the Block Placer are registered");
        for (DeviceType from : judged) {
            for (DeviceType to : judged) {
                Optional<DefaultWiring.Pair> expected = Optional.ofNullable(m4.get(List.of(from, to)))
                        .or(() -> M3_PAIRS.stream().filter(pair -> fits(pair, from, to)).findFirst());
                check(DefaultWiring.of(from, to).equals(expected), from + " -> " + to + " resolves to " + expected + ", not " + DefaultWiring.of(from, to));
                if (expected.isPresent()) resolved++;
            }
        }
        check(resolved == 11, "Eleven pairs of shipped types have a default wire (input, presence and switch into lamp, output and plug; "
                + "the observer's level into the output's and the plug's power): " + resolved);
        check(DefaultWiring.of(BlockObserver.TYPE, RedstoneOutput.TYPE).equals(Optional.of(new DefaultWiring.Pair("signal", "power", Mapping.FOLLOW))),
                "A Block Observer's comparator level follows into a Redstone Output's power (M4-9)");
        check(DefaultWiring.of(RedstoneInput.TYPE, RedstoneOutput.TYPE).equals(Optional.of(new DefaultWiring.Pair("powered", "state", Mapping.FOLLOW)))
                && DefaultWiring.of(WallSwitch.TYPE, SmartLamp.TYPE).equals(Optional.of(new DefaultWiring.Pair("press", "state", Mapping.PULSE_TOGGLE)))
                && DefaultWiring.of(PresenceSensor.TYPE, SmartPlug.TYPE).equals(Optional.of(new DefaultWiring.Pair("occupancy", "state", Mapping.FOLLOW))),
                "The input, the switch and the presence sensor wire as they did");

        Field<Boolean> enabled = Field.bool("enabled", true, Access.READ_WRITE);
        DeviceType timer = DeviceType.builder(id("timer")).model("Timer").unloaded(UnloadedPolicy.UNAVAILABLE).field(enabled)
                .entity(Ha.switchOf("enabled", enabled).on(true).off(false).name(null)).entity(Ha.button("trigger")).entity(Ha.event("fired", "fired"))
                .action(Action.state()).action(Action.press("trigger")).event(EventSpec.of("fired").minInterval(Duration.ofMillis(250)))
                .preview((state, command) -> state).build();
        Field<Integer> signal = Field.level("signal", Access.READ);
        DeviceType observer = DeviceType.builder(id("observer")).model("Observer").unloaded(UnloadedPolicy.UNAVAILABLE).field(signal)
                .entity(Ha.sensor("signal", signal)).build();
        check(DefaultWiring.of(WallSwitch.TYPE, timer).equals(Optional.of(new DefaultWiring.Pair("press", "trigger", Mapping.FOLLOW))),
                "A Wall Switch fires a timer (Trigger now) rather than toggling its enable: " + DefaultWiring.of(WallSwitch.TYPE, timer));
        check(DefaultWiring.of(timer, timer).equals(Optional.of(new DefaultWiring.Pair("fired", "trigger", Mapping.FOLLOW))), "A timer fires the next one");
        check(DefaultWiring.of(RedstoneInput.TYPE, timer).equals(Optional.of(new DefaultWiring.Pair("powered", "state", Mapping.FOLLOW))),
                "A lever arms a timer through its state");
        check(DefaultWiring.of(timer, SmartLamp.TYPE).equals(Optional.of(new DefaultWiring.Pair("fired", "state", Mapping.PULSE_TOGGLE)))
                && DefaultWiring.of(timer, RedstoneOutput.TYPE).equals(Optional.of(new DefaultWiring.Pair("fired", "state", Mapping.PULSE_TOGGLE)))
                && DefaultWiring.of(timer, SmartPlug.TYPE).equals(Optional.of(new DefaultWiring.Pair("fired", "state", Mapping.PULSE_TOGGLE))),
                "A timer toggles a lamp, an output and a plug");
        check(DefaultWiring.of(observer, RedstoneOutput.TYPE).equals(Optional.of(new DefaultWiring.Pair("signal", "power", Mapping.FOLLOW))),
                "A comparator level follows into a Redstone Output's power");
        check(DefaultWiring.of(observer, SmartLamp.TYPE).isEmpty() && DefaultWiring.of(timer, observer).isEmpty(), "No default where no pair fits");
        shippedTimerWiring();
    }

    /**
     * M4-2: the same resolutions on the four shipped timers, from and to every shipped type. A press fires a timer, a
     * timer fires the next, a lever and a presence sensor arm one, a timer toggles a lamp, an output and a plug; nothing
     * else wires to or from a timer by default.
     */
    private static void shippedTimerWiring() {
        DefaultWiring.Pair trigger = new DefaultWiring.Pair("press", "trigger", Mapping.FOLLOW);
        DefaultWiring.Pair chain = new DefaultWiring.Pair("fired", "trigger", Mapping.FOLLOW);
        DefaultWiring.Pair arm = new DefaultWiring.Pair("powered", "state", Mapping.FOLLOW);
        DefaultWiring.Pair presence = new DefaultWiring.Pair("occupancy", "state", Mapping.FOLLOW);
        DefaultWiring.Pair toggle = new DefaultWiring.Pair("fired", "state", Mapping.PULSE_TOGGLE);
        Map<DeviceType, DefaultWiring.Pair> into = Map.of(WallSwitch.TYPE, trigger, RedstoneInput.TYPE, arm, PresenceSensor.TYPE, presence);
        Map<DeviceType, DefaultWiring.Pair> from = Map.of(SmartLamp.TYPE, toggle, RedstoneOutput.TYPE, toggle, SmartPlug.TYPE, toggle);
        for (DeviceType timer : Timers.all()) {
            for (DeviceType other : DeviceTypes.all()) {
                Optional<DefaultWiring.Pair> in = Timers.all().contains(other) ? Optional.of(chain) : Optional.ofNullable(into.get(other));
                Optional<DefaultWiring.Pair> out = Timers.all().contains(other) ? Optional.of(chain) : Optional.ofNullable(from.get(other));
                check(DefaultWiring.of(other, timer).equals(in), other + " -> " + timer + " resolves to " + in + ", not " + DefaultWiring.of(other, timer));
                check(DefaultWiring.of(timer, other).equals(out), timer + " -> " + other + " resolves to " + out + ", not " + DefaultWiring.of(timer, other));
            }
        }
        TimerWiringProbe.chainIsCut();
    }

    // Whether a pair joins an out-port of one type to an in-port of the other, as DefaultWiring.of judges it.
    private static boolean fits(DefaultWiring.Pair pair, DeviceType from, DeviceType to) {
        Optional<Port> out = from.port(Port.Direction.OUT, pair.from());
        Optional<Port> in = to.port(Port.Direction.IN, pair.to());
        return out.isPresent() && in.isPresent() && pair.mapping().compatible(out.get().kind(), in.get().kind(), in.get().toggles());
    }

    /**
     * The day clock (M4 plan D2, D6): the v1 timers' HH:MM conversions exactly (a converted timer keeps its moment), the
     * day and time of day of any clock value, negative ones included, the 15-minute rounding, {@code is_day} and the
     * distances the timers fire by.
     */
    private static void gameClock() {
        for (int ticks = 0; ticks < GameClock.TICKS_PER_DAY; ticks++) {
            String v1 = v1Format(ticks);
            check(GameClock.format(ticks).equals(v1), "HH:MM of tick " + ticks + " as v1: " + v1);
        }
        for (int minutes = 0; minutes < GameClock.MINUTES_PER_DAY; minutes++) {
            String text = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
            check(GameClock.parse(text).equals(Optional.of(v1Parse(minutes))) && GameClock.isHhMm(text), "Ticks of " + text + " as v1");
            check(GameClock.format(GameClock.parse(text).orElseThrow()).equals(text), text + " survives the round trip");
        }
        check(GameClock.parse("06:00").equals(Optional.of(0)) && GameClock.parse("12:00").equals(Optional.of(6_000)) && GameClock.parse("18:00").equals(Optional.of(12_000))
                && GameClock.parse("00:00").equals(Optional.of(18_000)), "06:00 is tick 0, 12:00 6000, 18:00 12000, 00:00 18000");
        for (String invalid : List.of("6:00", "24:00", "12:60", "12-00", " 12:00", "", "1200", "ab:cd")) {
            check(GameClock.parse(invalid).isEmpty() && !GameClock.isHhMm(invalid), "Not HH:MM: '" + invalid + "'");
        }
        check(GameClock.parse(null).isEmpty() && !GameClock.isHhMm(null), "null is no time");
        // The v1 Daylight Timer's modes (GameTimeTrigger): sunrise 0, noon 6000, sunset 12000, midnight 18000.
        check(GameClock.Moment.SUNRISE.ticks() == 0 && GameClock.Moment.NOON.ticks() == 6_000 && GameClock.Moment.SUNSET.ticks() == 12_000
                && GameClock.Moment.MIDNIGHT.ticks() == 18_000, "The four moments are the v1 trigger modes' ticks");
        check(GameClock.timeOfDay(24_000L * 312 + 1_250) == 1_250 && GameClock.day(24_000L * 312 + 1_250) == 312, "Day 312, tick 1250");
        check(GameClock.timeOfDay(-1) == 23_999 && GameClock.day(-1) == -1 && GameClock.timeOfDay(-24_000) == 0 && GameClock.day(-24_000) == -1,
                "A clock before tick 0 still reads a valid time");
        check(GameClock.formatRoundedDown(1_250, 15).equals("07:15") && GameClock.formatRoundedDown(1_249, 15).equals("07:00")
                && GameClock.formatRoundedDown(18_000, 15).equals("00:00") && GameClock.formatRoundedDown(23_999, 15).equals("05:45")
                && GameClock.formatRoundedDown(23_999, 1).equals("05:59"), "time_of_day rounds down to 15 game minutes");
        boolean refused = false;
        try {
            GameClock.formatRoundedDown(0, 7);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "A step that does not divide the day is refused");
        check(GameClock.isDay(0) && GameClock.isDay(11_999) && !GameClock.isDay(12_000) && !GameClock.isDay(23_999) && GameClock.isDay(24_000L * 5 + 6_000),
                "is_day is 06:00 up to 18:00 by the clock");
        check(GameClock.ticksUntil(23_000, 0) == 1_000 && GameClock.ticksUntil(0, 0) == 0 && GameClock.ticksUntil(1, 0) == 23_999
                && GameClock.ticksUntil(24_000L * 7 + 11_000, 12_000) == 1_000, "The ticks until the next time of day");
        check(GameClock.ticksSince(20, 0) == 20 && GameClock.ticksSince(23_990, 0) == -10 && GameClock.ticksSince(12_000, 0) == -12_000
                && GameClock.ticksSince(11_999, 0) == 11_999, "The signed distance to a target, the shorter way round the day");
    }

    // The v1 timers' HH:MM of a tick (AlarmClockBlockEntity.formatAlarmTime, GameTimeTimerBlockEntity.formatGameTime), as
    // written in 0.7.0, so the reference outlives the classes M4-3 replaces.
    private static String v1Format(int minecraftTicks) {
        int adjustedMinutes = (int) Math.round(minecraftTicks * 1440.0 / 24000.0) % 1440;
        int totalMinutes = (adjustedMinutes + 360) % 1440;
        return String.format("%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    // The v1 alarm clock's tick of a time of day in minutes since midnight (AlarmClockBlockEntity.parseAlarmTime), as in 0.7.0.
    private static int v1Parse(int totalMinutes) {
        int adjustedMinutes = (totalMinutes - 360 + 1440) % 1440;
        return (int) Math.round(adjustedMinutes * 24000.0 / 1440.0) % 24000;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("mineqtt_check", path);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    /** Records what the wrapper does to the service behind it. */
    private static final class RecordingService implements MqttService {
        private final String name;
        private final List<String> events;
        private final List<Runnable> listeners = new ArrayList<>();
        private final List<String> filters = new ArrayList<>();
        private final List<String> closedFilters = new ArrayList<>();
        private Runnable inboundListener;

        RecordingService(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public ConnectionState state() {
            return ConnectionState.DISABLED;
        }

        @Override
        public void publish(Outbound msg) {
        }

        @Override
        public Subscription subscribe(String filter, SubOptions opts, InboundHandler handler) {
            filters.add(filter);
            return new Subscription() {
                @Override
                public String filter() {
                    return filter;
                }

                @Override
                public void close() {
                    closedFilters.add(filter);
                }
            };
        }

        @Override
        public void onConnected(Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void onInboundAvailable(Runnable listener) {
            inboundListener = listener;
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }

        @Override
        public void drainInbound(long budgetNanos, int maxMessages) {
        }

        @Override
        public void flushOutbound() {
        }

        @Override
        public Stats stats() {
            return new Stats(0, 0, 0, 0);
        }

        @Override
        public void close() {
            events.add("close " + name);
        }
    }
}

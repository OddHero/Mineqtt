package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.RegistryStore;
import art.rehra.mineqtt.device.Slugs;
import art.rehra.mineqtt.legacy.LegacySubscriptionFacade;
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
import java.util.ArrayList;
import java.util.HashMap;
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
 * connection, what a config reload does to Raw Links and which subscriptions of devices, legacy groups and unmigrated
 * blocks it opens or closes, and when the pump of a paused server ({@link PausedServerPump}) runs.
 * Plain Java, no game bootstrap.
 */
public final class RuntimeWiringCheck {
    private static final Slf4jMqttLog LOG = new Slf4jMqttLog(LoggerFactory.getLogger("mineqtt-check"));
    private static final String BRIDGE = "world_7f3a";

    static void main(String[] args) {
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
        legacyRejudge();
    }

    /** The subscriptions of an unmigrated block under a changed ACL ({@code LegacySubscriptionFacade.aclChanged}). */
    private static void legacyRejudge() {
        Set<String> allowedNow = Set.of("kept", "healed");
        List<String> events = new ArrayList<>();
        Map<String, String> filters = new HashMap<>();
        filters.put("kept", "kept#1");
        filters.put("refused", "refused#1");
        filters.put("healed", null);
        filters.put("still", null);
        LegacySubscriptionFacade.rejudge(filters, allowedNow::contains, filter -> {
            events.add("open " + filter);
            return allowedNow.contains(filter) ? filter + "#2" : null;
        }, subscription -> events.add("close " + subscription));
        check(filters.get("kept").equals("kept#1"), "An open subscription the ACL still allows stays as it is: " + filters);
        check(filters.get("refused") == null && events.contains("close refused#1"), "An open subscription the ACL refuses now closes: " + events);
        check("healed#2".equals(filters.get("healed")), "A refused subscription the ACL allows now opens: " + filters);
        check(filters.get("still") == null && events.contains("open still"),
                "A refused one is judged again, so a changed reason is news, and stays closed: " + events);
        check(events.size() == 4 && !events.contains("open kept") && !events.contains("close kept#1"),
                "Only the refused and the formerly refused filters are touched: " + events);
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

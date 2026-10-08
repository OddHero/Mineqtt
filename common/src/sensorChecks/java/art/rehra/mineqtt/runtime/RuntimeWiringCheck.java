package art.rehra.mineqtt.runtime;

import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.device.RegistryStore;
import art.rehra.mineqtt.device.Slugs;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.HiveMqttService;
import art.rehra.mineqtt.mqtt.core.InboundHandler;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The runtime pieces that need no running server: bridge id generation, the bridge topics, and how
 * {@link ReplaceableMqttService} carries subscriptions and connect listeners over to a replacement connection.
 * Plain Java, no game bootstrap.
 */
public final class RuntimeWiringCheck {
    private static final Slf4jMqttLog LOG = new Slf4jMqttLog(LoggerFactory.getLogger("mineqtt-check"));

    static void main(String[] args) {
        slugs();
        bridgeIds();
        topics();
        replacementOrder();
        deliveryAcrossReplacement();
        closedService();
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

    private static void publishAndDeliver(MqttService mqtt, String payload) {
        mqtt.publish(Outbound.raw("mineqtt/shared/door", payload.getBytes(StandardCharsets.UTF_8), 0, false, UUID.randomUUID()));
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

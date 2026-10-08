package art.rehra.mineqtt.mqtt.core;

import com.google.gson.JsonObject;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientBuilder;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * {@link HiveMqttService} against a real broker behind a {@link TcpProxy}, design §10.2 scenarios 1-8 plus inbound
 * payload limits, overlapping and refused filters, retained replays and acknowledgement listeners. Without arguments it runs against
 * {@link EmbeddedMoquette}, which refuses MQTT 5, so only MQTT 3.1.1 runs there and the MQTT 5 half is reported as
 * skipped (it is E2E item 13). Given a broker URI ({@code tcp://} or {@code ws://}, via {@code -PexternalBroker}) it
 * runs both protocols against that broker, except the scenario that needs the embedded broker's ACL. The remote peer
 * is always a plain MQTT 3.1.1 client.
 */
public final class BrokerIntegrationCheck {
    private static final long TIMEOUT_MILLIS = 15_000;
    private static final SubOptions PLAIN = new SubOptions(1, false, false, 16 * 1024, null);

    /** @param refusingBroker the embedded broker whose ACL refuses wide filters, or null for an external broker */
    private record Target(String name, String host, int port, String wsPath, boolean retainFlagAtQos1, boolean retainsWill,
                          EmbeddedMoquette refusingBroker) {
    }

    /** Thrown by a scenario the target cannot run; it is reported, not failed. */
    private static final class Skipped extends Exception {
        Skipped(String reason) {
            super(reason);
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(Target target, ProtocolVersion protocol, String root) throws Exception;
    }

    private record NamedScenario(String name, Scenario scenario) {
    }

    private static final List<NamedScenario> SCENARIOS = List.of(
            new NamedScenario("1 LWT and birth", BrokerIntegrationCheck::lwtAndBirth),
            new NamedScenario("2 Retained commands", BrokerIntegrationCheck::retainedCommands),
            new NamedScenario("3 No-local plus loopback", BrokerIntegrationCheck::noLocalPlusLoopback),
            new NamedScenario("4 Ref-counting", BrokerIntegrationCheck::refCounting),
            new NamedScenario("5 Ordering", BrokerIntegrationCheck::ordering),
            new NamedScenario("6 Resubscribe", BrokerIntegrationCheck::resubscribe),
            new NamedScenario("7 Outbox", BrokerIntegrationCheck::outbox),
            new NamedScenario("8 Shutdown", BrokerIntegrationCheck::shutdown),
            new NamedScenario("9 Payload limits", BrokerIntegrationCheck::payloadLimits),
            new NamedScenario("10 Overlapping filters", BrokerIntegrationCheck::overlappingFilters),
            new NamedScenario("11 Refused covering filter", BrokerIntegrationCheck::refusedCoveringFilter),
            new NamedScenario("12 Retained replay", BrokerIntegrationCheck::retainedReplay),
            new NamedScenario("13 Acknowledgements", BrokerIntegrationCheck::acknowledgements));

    static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            // A failed scenario can leave a client reconnecting on a non-daemon thread; fail now, not at the task timeout.
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: BrokerIntegrationCheck [tcp://host:port | ws://host:port/path]");
        List<String> report = new ArrayList<>();
        mqttDisabled();
        report.add(String.format("%-10s %-28s %-46s %s", "no broker", "0 MQTT disabled", "", "PASSED"));
        protocolRefusedByConnAck();
        report.add(String.format("%-10s %-28s %-46s %s", "MQTT 5", "0 3.1.1 CONNACK refusal", "on a 3.1.1-only stub", "PASSED"));
        if (args.length == 1) {
            BrokerConfig external = BrokerConfig.parse(args[0], BrokerSettings.defaults("unused", ProtocolVersion.MQTT_5))
                    .orElseThrow(() -> new IllegalArgumentException("externalBroker is blank"));
            if (external.tls()) {
                throw new IllegalArgumentException("TLS brokers cannot sit behind the plain TCP proxy of this check; use tcp:// or ws://");
            }
            Target target = new Target("external broker " + external.uri(), external.host(), external.port(), external.wsPath(),
                    true, true, null);
            runAll(target, ProtocolVersion.MQTT_5, report);
            runAll(target, ProtocolVersion.MQTT_3_1_1, report);
        } else {
            try (EmbeddedMoquette broker = EmbeddedMoquette.start()) {
                Target target = new Target("embedded Moquette", EmbeddedMoquette.HOST, broker.port(), null,
                        EmbeddedMoquette.RETAIN_FLAG_AT_QOS_1, EmbeddedMoquette.RETAINS_WILL, broker);
                if (EmbeddedMoquette.SUPPORTS_MQTT_5) {
                    runAll(target, ProtocolVersion.MQTT_5, report);
                } else {
                    // Moquette refuses MQTT 5, which is exactly the situation the protocol hint exists for.
                    protocolMismatchHint(target);
                    report.add(line(target, ProtocolVersion.MQTT_5, "0 Protocol mismatch hint", "PASSED"));
                    for (NamedScenario scenario : SCENARIOS) {
                        report.add(line(target, ProtocolVersion.MQTT_5, scenario.name(),
                                "SKIPPED (Moquette refuses MQTT 5; run -PexternalBroker=tcp://localhost:1883, E2E item 13)"));
                    }
                }
                runAll(target, ProtocolVersion.MQTT_3_1_1, report);
            }
        }
        System.out.println("Broker integration scenarios:");
        report.forEach(entry -> System.out.println("  " + entry));
        System.out.println("Broker integration checks passed.");
    }

    private static void runAll(Target target, ProtocolVersion protocol, List<String> report) throws Exception {
        for (NamedScenario scenario : SCENARIOS) {
            long start = System.nanoTime();
            String root = root(protocol);
            try {
                scenario.scenario().run(target, protocol, root);
            } catch (Skipped skipped) {
                report.add(line(target, protocol, scenario.name(), "SKIPPED (" + skipped.getMessage() + ")"));
                continue;
            } catch (Exception | AssertionError e) {
                throw new AssertionError("Scenario " + scenario.name() + " failed for MQTT " + protocol.configValue()
                        + " against " + target.name() + ": " + e.getMessage(), e);
            } finally {
                // Every service's will and graceful stop leave a retained offline behind.
                try (Remote cleanup = new Remote(target)) {
                    cleanup.clearRetained(root + "/bridge/state");
                }
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            report.add(line(target, protocol, scenario.name(), "PASSED (" + millis + " ms)"));
        }
    }

    private static String line(Target target, ProtocolVersion protocol, String scenario, String result) {
        return String.format("MQTT %-5s %-28s %-46s %s", protocol.configValue(), scenario, "on " + target.name(), result);
    }

    // ---- scenarios ----

    /** The will fires on a cut connection, the service reconnects (also when the first attempt fails) and births again. */
    private static void lwtAndBirth(Target target, ProtocolVersion protocol, String root) throws Exception {
        String stateTopic = root + "/bridge/state";
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            remote.subscribe(stateTopic, 1);
            proxy.cut();
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                service.onConnected(() -> service.publish(Outbound.state(stateTopic, "online")));
                tickUntil(service, () -> service.state().status() == ConnectionState.Status.RECONNECTING, "the first connect attempt to fail");
                proxy.restore();
                tickUntil(service, () -> "online".equals(remote.last(stateTopic)), "the birth after the first connect");
                awaitRetained(target, stateTopic, "online");

                proxy.cut();
                tickUntil(service, () -> "offline".equals(remote.last(stateTopic)), "the broker to fire the will");
                check(service.state().status() == ConnectionState.Status.RECONNECTING, "A cut connection reconnects, state " + service.state());
                // Moquette delivers the will but never stores it as retained (see EmbeddedMoquette).
                if (target.retainsWill()) awaitRetained(target, stateTopic, "offline");

                proxy.restore();
                tickUntil(service, () -> "online".equals(remote.last(stateTopic)), "the birth after reconnecting");
                awaitRetained(target, stateTopic, "online");
            } finally {
                service.close();
            }
        }
    }

    /** A retained command never reaches a command handler: DO_NOT_SEND on MQTT 5, the isRetain() drop on 3.1.1. */
    private static void retainedCommands(Target target, ProtocolVersion protocol, String root) throws Exception {
        String command = root + "/kitchen_lamp/set";
        String mirrored = root + "/mirror/state";
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            // QoS 2, because Moquette acknowledges QoS 1 before it stores the retained message.
            remote.publish(command, "ON", 2, true);
            remote.publish(mirrored, "42", 2, true);
            // Moquette keeps the retain flag on retained-on-subscribe only at QoS 0, which the 3.1.1 drop needs.
            SubOptions commandOptions = target.retainFlagAtQos1() ? SubOptions.COMMAND
                    : new SubOptions(0, false, false, SubOptions.COMMAND.maxPayloadBytes(), null);
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder commands = new Recorder();
                Recorder mirror = new Recorder();
                service.subscribe(root + "/+/set", commandOptions, commands);
                service.subscribe(mirrored, SubOptions.MIRROR, mirror);
                connect(service);
                tickUntil(service, () -> mirror.payloads().equals(List.of("42")), "the retained value for an accepting handler");

                remote.publish(command, "OFF", 1, false);
                tickUntil(service, () -> commands.payloads().contains("OFF"), "a live command");
                tickFor(service, 300);
                check(commands.payloads().equals(List.of("OFF")), "Only the live command arrives, got " + commands.payloads());
                // MQTT 3.1.1 must have received the retained command and dropped it; MQTT 5 must never have been sent it.
                long expected = protocol == ProtocolVersion.MQTT_3_1_1 ? 3 : 2;
                check(service.receivedFromBroker() == expected, "Broker deliveries: expected " + expected + ", got " + service.receivedFromBroker());
            } finally {
                service.close();
                remote.clearRetained(command);
                remote.clearRetained(mirrored);
            }
        }
    }

    /** Own raw publishes reach other local handlers exactly once, never the origin, and the remote exactly once. */
    private static void noLocalPlusLoopback(Target target, ProtocolVersion protocol, String root) throws Exception {
        String topic = root + "/raw/door";
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            remote.subscribe(topic, 1);
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder a = new Recorder();
                Recorder b = new Recorder();
                service.subscribe(topic, SubOptions.raw(false, deviceA), a);
                service.subscribe(topic, SubOptions.raw(false, deviceB), b);
                connect(service);

                service.publish(Outbound.raw(topic, utf8("ON"), 1, false, deviceA));
                tickUntil(service, () -> b.payloads().contains("ON") && remote.payloads(topic).contains("ON"), "the raw publish to arrive");
                tickFor(service, 500);
                check(a.payloads().isEmpty(), "The origin never hears its own publish, got " + a.payloads());
                check(b.payloads().equals(List.of("ON")), "The other local device hears it exactly once, got " + b.payloads());
                check(b.received.getFirst().local() && deviceA.equals(b.received.getFirst().origin()), "The local copy names its origin");
                check(remote.payloads(topic).equals(List.of("ON")), "The remote hears it exactly once, got " + remote.payloads(topic));

                remote.publish(topic, "FOREIGN", 1, false);
                tickUntil(service, () -> a.payloads().contains("FOREIGN") && b.payloads().contains("FOREIGN"), "a foreign message");
                check(!a.received.getLast().local(), "A foreign message is not local");

                // A handler without no-local takes the broker's copy instead; on MQTT 5 this turns no-local off for the filter.
                Recorder listener = new Recorder();
                service.subscribe(topic, PLAIN, listener);
                awaitBrokerOperations(service);
                service.publish(Outbound.raw(topic, utf8("OFF"), 1, false, deviceA));
                tickUntil(service, () -> b.payloads().contains("OFF") && listener.payloads().contains("OFF")
                        && remote.payloads(topic).contains("OFF"), "the second raw publish");
                tickFor(service, 500);
                check(count(a.payloads(), "OFF") == 0, "The origin stays deaf to itself, got " + a.payloads());
                check(count(b.payloads(), "OFF") == 1, "The no-local device still hears it once, got " + b.payloads());
                check(count(listener.payloads(), "OFF") == 1, "The plain handler hears the broker's copy once, got " + listener.payloads());
                check(listener.received.getFirst().local(), "The broker's copy of an own publish is marked local");

                proxy.cut();
                tickUntil(service, () -> !service.state().isConnected(), "the connection to drop");
                service.publish(Outbound.raw(topic, utf8("LOCAL"), 1, false, deviceB));
                tickUntil(service, () -> a.payloads().contains("LOCAL"), "the loopback with the broker down");
                check(!b.payloads().contains("LOCAL"), "The origin is excluded with the broker down too");
                check(!listener.payloads().contains("LOCAL"), "A plain handler only hears own publishes through the broker");
                proxy.restore();
                connect(service);
                tickFor(service, 300);
                check(!remote.payloads(topic).contains("LOCAL"), "A raw publish made while offline is dropped, not replayed");
            } finally {
                service.close();
            }
        }
    }

    /** Two handlers on one filter: closing one keeps delivery, closing both unsubscribes at the broker. */
    private static void refCounting(Target target, ProtocolVersion protocol, String root) throws Exception {
        String topic = root + "/a/b/c";
        String marker = root + "/marker";
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder first = new Recorder();
                Recorder second = new Recorder();
                Recorder markers = new Recorder();
                Subscription one = service.subscribe(root + "/a/+/c", PLAIN, first);
                Subscription two = service.subscribe(root + "/a/+/c", PLAIN, second);
                service.subscribe(marker, PLAIN, markers);
                connect(service);

                remote.publish(topic, "1", 1, false);
                tickUntil(service, () -> first.payloads().contains("1") && second.payloads().contains("1"), "both handlers");
                one.close();
                one.close();
                awaitBrokerOperations(service);
                remote.publish(topic, "2", 1, false);
                tickUntil(service, () -> second.payloads().contains("2"), "the remaining handler");
                tickFor(service, 200);
                check(!first.payloads().contains("2"), "A closed handler receives nothing");

                two.close();
                awaitBrokerOperations(service);
                long before = service.receivedFromBroker();
                remote.publish(topic, "3", 1, false);
                remote.publish(marker, "m", 1, false);
                tickUntil(service, () -> markers.payloads().contains("m"), "the marker");
                check(service.receivedFromBroker() == before + 1, "After the last close the broker no longer sends the filter");
            } finally {
                service.close();
            }
        }
    }

    /** 1 000 messages are handled in the order they were published. */
    private static void ordering(Target target, ProtocolVersion protocol, String root) throws Exception {
        String topic = root + "/order";
        int count = 1_000;
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder recorder = new Recorder();
                service.subscribe(topic, PLAIN, recorder);
                connect(service);
                List<CompletableFuture<?>> sent = new ArrayList<>(count);
                for (int i = 0; i < count; i++) sent.add(remote.publishAsync(topic, Integer.toString(i), 1, false));
                CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                tickUntil(service, () -> recorder.received.size() >= count, count + " messages");
                tickFor(service, 200);
                check(recorder.received.size() == count, "Exactly " + count + " messages, got " + recorder.received.size());
                for (int i = 0; i < count; i++) {
                    check(recorder.received.get(i).text().equals(Integer.toString(i)), "Message " + i + " is out of order");
                    if (i > 0) check(recorder.received.get(i).seq() > recorder.received.get(i - 1).seq(), "seq follows delivery order");
                }
            } finally {
                service.close();
            }
        }
    }

    /** After a reconnect the whole filter table is subscribed again, including filters added while offline. */
    private static void resubscribe(Target target, ProtocolVersion protocol, String root) throws Exception {
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder exact = new Recorder();
                Recorder plus = new Recorder();
                Recorder deep = new Recorder();
                service.subscribe(root + "/r/one", PLAIN, exact);
                service.subscribe(root + "/r/+/two", PLAIN, plus);
                service.subscribe(root + "/r/deep/#", PLAIN, deep);
                connect(service);
                publishAll(remote, root, "before");
                tickUntil(service, () -> exact.payloads().contains("before") && plus.payloads().contains("before")
                        && deep.payloads().contains("before"), "delivery before the cut");

                proxy.cut();
                tickUntil(service, () -> !service.state().isConnected(), "the connection to drop");
                Recorder late = new Recorder();
                service.subscribe(root + "/r/late", PLAIN, late);
                proxy.restore();
                connect(service);
                long before = service.receivedFromBroker();
                publishAll(remote, root, "after");
                remote.publish(root + "/r/late", "after", 1, false);
                tickUntil(service, () -> exact.payloads().contains("after") && plus.payloads().contains("after")
                        && deep.payloads().contains("after") && late.payloads().contains("after"), "delivery after the reconnect");
                tickFor(service, 200);
                // Exactly once: a receiver or a subscription surviving the old connection would deliver twice.
                check(exact.payloads().equals(List.of("before", "after")) && plus.payloads().equals(List.of("before", "after"))
                        && deep.payloads().equals(List.of("before", "after")), "Each handler gets every message once, got "
                        + exact.payloads() + " " + plus.payloads() + " " + deep.payloads());
                check(late.payloads().equals(List.of("after")), "The handler added offline gets the message once, got " + late.payloads());
                check(service.receivedFromBroker() == before + 4, "The broker sends one copy per message, got " + (service.receivedFromBroker() - before));
            } finally {
                service.close();
            }
        }
    }

    /** A clear published while the broker is unreachable reaches it after the reconnect; an event does not. */
    private static void outbox(Target target, ProtocolVersion protocol, String root) throws Exception {
        String discovery = root + "/homeassistant/device/x/config";
        String action = root + "/x/action";
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            remote.publish(discovery, "{\"name\":\"ghost\"}", 2, true);
            remote.subscribe(action, 1);
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                connect(service);
                proxy.cut();
                tickUntil(service, () -> !service.state().isConnected(), "the connection to drop");
                service.publish(Outbound.clear(discovery));
                service.publish(Outbound.event(action, new JsonObject()));
                tickFor(service, 200);
                check(service.stats().outboxSize() == 1, "The clear waits in the outbox, got " + service.stats());
                check(service.stats().droppedOutbound() == 1, "The event is dropped and counted, got " + service.stats());

                proxy.restore();
                connect(service);
                tickUntil(service, () -> service.stats().outboxSize() == 0, "the outbox to be sent");
                awaitRetained(target, discovery, null);
                tickFor(service, 200);
                check(remote.payloads(action).isEmpty(), "An event from the offline period is never delivered");
            } finally {
                service.close();
                remote.clearRetained(discovery);
            }
        }
    }

    /** close() publishes offline, then nothing reconnects and every call is a no-op; also while reconnecting. */
    private static void shutdown(Target target, ProtocolVersion protocol, String root) throws Exception {
        String stateTopic = root + "/bridge/state";
        String after = root + "/after";
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            remote.subscribe(stateTopic, 1);
            remote.subscribe(after, 1);
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            service.onConnected(() -> service.publish(Outbound.state(stateTopic, "online")));
            connect(service);
            tickUntil(service, () -> "online".equals(remote.last(stateTopic)), "the birth");

            int accepted = proxy.accepted();
            service.close();
            check(service.state().status() == ConnectionState.Status.STOPPED, "close() stops the service, state " + service.state());
            waitUntil(() -> "offline".equals(remote.last(stateTopic)), "the graceful offline");
            awaitRetained(target, stateTopic, "offline");
            service.publish(Outbound.state(after, "late"));
            service.flushOutbound();
            service.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
            Recorder late = new Recorder();
            Subscription inert = service.subscribe(after, PLAIN, late);
            inert.close();
            service.close();
            Thread.sleep(1_500);
            check(proxy.accepted() == accepted, "No reconnect after close()");
            check(remote.payloads(after).isEmpty(), "A publish after close() is ignored");

            HiveMqttService reconnecting = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            connect(reconnecting);
            proxy.cut();
            tickUntil(reconnecting, () -> reconnecting.state().status() == ConnectionState.Status.RECONNECTING, "the connection to drop");
            reconnecting.close();
            proxy.restore();
            int acceptedWhileDown = proxy.accepted();
            Thread.sleep(1_500);
            check(proxy.accepted() == acceptedWhileDown, "close() while reconnecting cancels the reconnect");
            check(reconnecting.state().status() == ConnectionState.Status.STOPPED, "The reconnecting service is stopped");
        }
    }

    /** The global inbound limit drops before queueing; a handler's own limit drops only for that handler. */
    private static void payloadLimits(Target target, ProtocolVersion protocol, String root) throws Exception {
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            HiveMqttService service = service(target, proxy, protocol, root, 1_024);
            try {
                Recorder small = new Recorder();
                Recorder big = new Recorder();
                Recorder markers = new Recorder();
                service.subscribe(root + "/limit/small", new SubOptions(1, false, false, 100, null), small);
                service.subscribe(root + "/limit/big", PLAIN, big);
                service.subscribe(root + "/limit/marker", PLAIN, markers);
                connect(service);
                remote.publish(root + "/limit/big", "x".repeat(2_000), 1, false);
                remote.publish(root + "/limit/small", "y".repeat(500), 1, false);
                remote.publish(root + "/limit/small", "ok", 1, false);
                remote.publish(root + "/limit/big", "z".repeat(600), 1, false);
                remote.publish(root + "/limit/marker", "m", 1, false);
                tickUntil(service, () -> markers.payloads().contains("m"), "the marker");
                check(service.stats().droppedInbound() == 1, "The 2 000-byte message is dropped before the queue, got " + service.stats());
                check(small.payloads().equals(List.of("ok")), "The small handler only takes small payloads");
                check(big.received.size() == 1 && big.received.getFirst().payload().length == 600, "The big handler takes 600 bytes");
            } finally {
                service.close();
            }
        }
    }

    /**
     * Nested filters share one broker subscription, so each handler gets a message once even from a broker that sends
     * one copy per matching subscription (Mosquitto); a handler added under a wider filter still gets its retained value.
     */
    private static void overlappingFilters(Target target, ProtocolVersion protocol, String root) throws Exception {
        String topic = root + "/ov/x";
        String marker = root + "/marker";
        String kept = root + "/ov/y";
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder hash = new Recorder();
                Recorder plus = new Recorder();
                Recorder exact = new Recorder();
                Recorder markers = new Recorder();
                Subscription wide = service.subscribe(root + "/ov/#", PLAIN, hash);
                service.subscribe(root + "/ov/+", PLAIN, plus);
                service.subscribe(topic, PLAIN, exact);
                service.subscribe(marker, PLAIN, markers);
                connect(service);

                long before = service.receivedFromBroker();
                remote.publish(topic, "1", 1, false);
                remote.publish(marker, "m1", 1, false);
                tickUntil(service, () -> markers.payloads().contains("m1"), "the first marker");
                tickFor(service, 200);
                check(hash.payloads().equals(List.of("1")) && plus.payloads().equals(List.of("1")) && exact.payloads().equals(List.of("1")),
                        "Each nested handler gets the message once, got " + hash.payloads() + " " + plus.payloads() + " " + exact.payloads());
                check(service.receivedFromBroker() == before + 2, "The broker sends one copy per message, got " + (service.receivedFromBroker() - before));

                // Closing the widest filter hands its scope to the next widest without a gap or a second copy.
                wide.close();
                awaitBrokerOperations(service);
                before = service.receivedFromBroker();
                remote.publish(topic, "2", 1, false);
                remote.publish(marker, "m2", 1, false);
                tickUntil(service, () -> markers.payloads().contains("m2"), "the second marker");
                tickFor(service, 200);
                check(plus.payloads().equals(List.of("1", "2")) && exact.payloads().equals(List.of("1", "2")), "Delivery continues once each");
                check(hash.payloads().equals(List.of("1")), "The closed handler receives nothing");
                check(service.receivedFromBroker() == before + 2, "Still one copy per message, got " + (service.receivedFromBroker() - before));

                // Moquette keeps the retain flag only at QoS 0, and the plain handler must be able to tell it apart.
                remote.publish(kept, "kept", 2, true);
                // The publish also arrives live through ov/+; it is handled before the late handler exists.
                tickUntil(service, () -> plus.payloads().contains("kept"), "the live copy of the retained publish");
                Recorder late = new Recorder();
                service.subscribe(kept, new SubOptions(target.retainFlagAtQos1() ? 1 : 0, true, true, 16 * 1024, null), late);
                tickUntil(service, () -> late.payloads().contains("kept"), "the retained value for a handler under a wider filter");
                tickFor(service, 200);
                check(late.payloads().equals(List.of("kept")) && late.received.getFirst().retained(),
                        "The late handler gets the retained value once, got " + late.received.stream().map(m -> m.text() + "/retained=" + m.retained()).toList());
                check(count(plus.payloads(), "kept") == 1, "The wider plain handler does not take the retained copy");
            } finally {
                service.close();
                remote.clearRetained(kept);
            }
        }
    }

    /**
     * A wide filter the broker refuses takes nothing over: the narrower filters it covers stay subscribed on their own,
     * also after a reconnect, and the wide filter takes them over once the broker accepts it. Local routing hands the
     * wide handler whatever the narrower subscriptions bring, but nothing only the refused filter would cover.
     */
    private static void refusedCoveringFilter(Target target, ProtocolVersion protocol, String root) throws Exception {
        EmbeddedMoquette broker = target.refusingBroker();
        if (broker == null) throw new Skipped("needs the embedded broker's ACL");
        String scope = root + "/" + EmbeddedMoquette.REFUSED_LEVEL;
        String wideFilter = scope + "/#";
        Round round = new Round(scope + "/lamp/set", scope + "/plug/set", scope + "/sensor/state", root + "/marker");
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder exact = new Recorder();
                Recorder plus = new Recorder();
                Recorder wide = new Recorder();
                Recorder markers = new Recorder();
                service.subscribe(round.lamp(), PLAIN, exact);
                service.subscribe(scope + "/+/set", PLAIN, plus);
                service.subscribe(round.marker(), PLAIN, markers);
                connect(service);

                service.subscribe(wideFilter, PLAIN, wide);
                tickUntil(service, () -> service.refusedFilters().contains(wideFilter), "the broker to refuse the wide filter");
                // The narrower filters are subscribed again in reply to the refusal, so only now are they all in place.
                awaitBrokerOperations(service);
                long before = service.receivedFromBroker();
                round.publish(service, remote, markers, "1");
                check(exact.payloads().equals(List.of("lamp1")) && plus.payloads().equals(List.of("lamp1", "plug1")),
                        "The narrower filters survive the refusal, got " + exact.payloads() + " " + plus.payloads());
                check(wide.payloads().equals(List.of("lamp1", "plug1")), "The refused filter brings nothing itself, got " + wide.payloads());
                check(service.receivedFromBroker() == before + 3, "One copy per deliverable message, got " + (service.receivedFromBroker() - before));

                // The whole table goes out again after a reconnect and the broker refuses the wide filter again.
                reconnect(service, proxy);
                before = service.receivedFromBroker();
                round.publish(service, remote, markers, "2");
                check(exact.payloads().equals(List.of("lamp1", "lamp2")) && plus.payloads().equals(List.of("lamp1", "plug1", "lamp2", "plug2")),
                        "A reconnect keeps the narrower filters, got " + exact.payloads() + " " + plus.payloads());
                check(service.refusedFilters().contains(wideFilter) && !wide.payloads().contains("sensor2"), "The wide filter is still refused");
                check(service.receivedFromBroker() == before + 3, "Still one copy per message, got " + (service.receivedFromBroker() - before));

                // Once the broker accepts it, the wide filter takes the narrower ones over again.
                broker.refuseWideFilters(false);
                reconnect(service, proxy);
                tickUntil(service, () -> service.refusedFilters().isEmpty(), "the broker to accept the wide filter");
                awaitBrokerOperations(service);
                before = service.receivedFromBroker();
                round.publish(service, remote, markers, "3");
                check(count(exact.payloads(), "lamp3") == 1 && count(plus.payloads(), "lamp3") == 1 && count(plus.payloads(), "plug3") == 1,
                        "The narrower handlers still get each message once, got " + exact.payloads() + " " + plus.payloads());
                check(wide.payloads().subList(4, wide.payloads().size()).equals(List.of("lamp3", "plug3", "sensor3")),
                        "The accepted wide filter receives its whole scope, got " + wide.payloads());
                check(service.receivedFromBroker() == before + 4, "The narrower subscriptions are gone, got " + (service.receivedFromBroker() - before));
            } finally {
                broker.refuseWideFilters(true);
                service.close();
            }
        }
    }

    /** The topics of one publishing round of {@link #refusedCoveringFilter}; only {@code sensor} is outside {@code +/set}. */
    private record Round(String lamp, String plug, String sensor, String marker) {
        /** Publishes {@code lamp<n>}, {@code plug<n>}, {@code sensor<n>} and a marker, and ticks until the marker arrived. */
        void publish(HiveMqttService service, Remote remote, Recorder markers, String n) throws Exception {
            remote.publish(lamp, "lamp" + n, 1, false);
            remote.publish(plug, "plug" + n, 1, false);
            remote.publish(sensor, "sensor" + n, 1, false);
            remote.publish(marker, "m" + n, 1, false);
            tickUntil(service, () -> markers.payloads().contains("m" + n), "marker " + n);
            tickFor(service, 200);
        }
    }

    /**
     * A retained value the broker sends again for a joining handler reaches only that handler, never one that has
     * since seen a newer live message: neither through a temporary SUBSCRIBE under a wider filter nor through the
     * re-SUBSCRIBE of a shared one.
     */
    private static void retainedReplay(Target target, ProtocolVersion protocol, String root) throws Exception {
        String topic = root + "/rr/door";
        String wideFilter = root + "/rr/#";
        // Moquette keeps the retain flag only at QoS 0, and these handlers must be able to tell it apart.
        SubOptions accepting = new SubOptions(target.retainFlagAtQos1() ? 1 : 0, true, true, 16 * 1024, null);
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            remote.publish(topic, "ON", 2, true);
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                Recorder wide = new Recorder();
                Recorder first = new Recorder();
                service.subscribe(wideFilter, accepting, wide);
                service.subscribe(topic, accepting, first);
                connect(service);
                tickUntil(service, () -> wide.payloads().contains("ON") && first.payloads().contains("ON"), "the retained value");
                remote.publish(topic, "OFF", 1, false);
                tickUntil(service, () -> wide.payloads().contains("OFF") && first.payloads().contains("OFF"), "the live value");

                Recorder second = new Recorder();
                service.subscribe(topic, accepting, second);
                tickUntil(service, () -> second.payloads().contains("ON"), "the retained value for a handler under the wide filter");
                Recorder third = new Recorder();
                service.subscribe(wideFilter, accepting, third);
                tickUntil(service, () -> third.payloads().contains("ON"), "the retained value for a handler on the wide filter");
                tickFor(service, 300);
                check(wide.payloads().equals(List.of("ON", "OFF")) && first.payloads().equals(List.of("ON", "OFF")),
                        "Handlers with a newer value keep it, got " + wide.payloads() + " " + first.payloads());
                check(second.payloads().equals(List.of("ON")) && third.payloads().equals(List.of("ON")),
                        "Each joining handler gets the retained value once, got " + second.payloads() + " " + third.payloads());
                check(second.received.getFirst().retained() && third.received.getFirst().retained(), "The replay is marked retained");

                // A live publish with the retain flag is a new value for everyone.
                remote.publish(topic, "AUTO", 1, true);
                tickUntil(service, () -> wide.payloads().contains("AUTO") && first.payloads().contains("AUTO")
                        && second.payloads().contains("AUTO") && third.payloads().contains("AUTO"), "a live retained publish");
                tickFor(service, 200);
                for (Recorder recorder : List.of(wide, first, second, third)) {
                    check(count(recorder.payloads(), "AUTO") == 1, "A live retained publish arrives once, got " + recorder.payloads());
                }
            } finally {
                service.close();
                remote.clearRetained(topic);
            }
        }
    }

    /**
     * An acknowledgement listener runs on the ticking thread once the broker acknowledged its message, also when the
     * message waited in the outbox through a disconnect; never for a message coalesced into a newer one, and never
     * after close().
     */
    private static void acknowledgements(Target target, ProtocolVersion protocol, String root) throws Exception {
        String topic = root + "/ack/config";
        Thread owner = Thread.currentThread();
        List<String> acked = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port()); Remote remote = new Remote(target)) {
            HiveMqttService service = service(target, proxy, protocol, root, BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                connect(service);
                service.publish(Outbound.state(topic, "first").whenAcknowledged(() -> {
                    acked.add("first");
                    threads.add(Thread.currentThread());
                }));
                tickUntil(service, () -> acked.contains("first"), "the first acknowledgement");
                awaitRetained(target, topic, "first");
                check(threads.equals(List.of(owner)), "The listener runs on the thread that drains, got " + threads);

                service.publish(Outbound.state(topic, "coalesced").whenAcknowledged(() -> acked.add("coalesced")));
                service.publish(Outbound.state(topic, "latest").whenAcknowledged(() -> acked.add("latest")));
                tickUntil(service, () -> acked.contains("latest"), "the latest acknowledgement");

                proxy.cut();
                tickUntil(service, () -> !service.state().isConnected(), "the connection to drop");
                service.publish(Outbound.clear(topic).whenAcknowledged(() -> acked.add("clear")));
                tickFor(service, 200);
                check(!acked.contains("clear"), "Nothing is acknowledged while the broker is unreachable");
                proxy.restore();
                connect(service);
                tickUntil(service, () -> acked.contains("clear"), "the acknowledgement of the outbox entry");
                awaitRetained(target, topic, null);
                tickFor(service, 200);
                check(acked.equals(List.of("first", "latest", "clear")), "Each sent message is acknowledged once, got " + acked);
            } finally {
                service.close();
            }
            service.publish(Outbound.state(topic, "late").whenAcknowledged(() -> acked.add("late")));
            service.flushOutbound();
            service.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
            check(!acked.contains("late"), "A publish after close() is never acknowledged");
            remote.clearRetained(topic);
        }
    }

    /** Without a broker the service still routes local publishes, so links on one server keep working. */
    private static void mqttDisabled() throws InterruptedException {
        HiveMqttService service = HiveMqttService.disabled(HiveMqttService.Options.defaults(null), LOG);
        try {
            check(service.state() == ConnectionState.DISABLED, "A service without a broker is DISABLED");
            UUID deviceA = UUID.randomUUID();
            UUID deviceB = UUID.randomUUID();
            Recorder a = new Recorder();
            Recorder b = new Recorder();
            Recorder plain = new Recorder();
            service.subscribe("mineqtt/shared/door", SubOptions.raw(true, deviceA), a);
            service.subscribe("mineqtt/shared/+", SubOptions.raw(false, deviceB), b);
            service.subscribe("mineqtt/shared/door", PLAIN, plain);
            service.publish(Outbound.raw("mineqtt/shared/door", utf8("ON"), 0, false, deviceA));
            service.publish(Outbound.state("mineqtt/shared/door", "STATE"));
            tickUntil(service, () -> b.payloads().size() == 2, "the loopback without a broker");
            check(a.payloads().equals(List.of("STATE")), "The origin hears only the other publish, got " + a.payloads());
            check(b.payloads().equals(List.of("ON", "STATE")), "A wildcard raw link hears both, got " + b.payloads());
            check(!b.received.get(1).retained(), "A looped-back retained publish arrives like a live message");
            check(plain.payloads().isEmpty(), "Without a broker there is no broker copy for plain handlers");
            check(service.stats().outboxSize() == 0 && service.stats().droppedOutbound() == 0, "Nothing is kept or counted without a broker");
            List<String> acked = new ArrayList<>();
            service.publish(Outbound.clear("mineqtt/other/config").whenAcknowledged(() -> acked.add("clear")));
            tickFor(service, 100);
            check(acked.isEmpty(), "Without a broker nothing is acknowledged");
        } finally {
            service.close();
        }
        check(service.state() == ConnectionState.STOPPED, "A disabled service closes");
    }

    /** MQTT 5 against a 3.1.1-only broker fails before CONNACK and says so in the state. */
    private static void protocolMismatchHint(Target target) throws Exception {
        try (TcpProxy proxy = new TcpProxy(target.host(), target.port())) {
            HiveMqttService service = service(target, proxy, ProtocolVersion.MQTT_5, root(ProtocolVersion.MQTT_5),
                    BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            try {
                tickUntil(service, () -> service.state().detail().contains(HiveMqttService.PROTOCOL_HINT), "the MQTT 3.1.1 hint");
                check(service.state().status() == ConnectionState.Status.RECONNECTING, "A refused protocol keeps retrying");
            } finally {
                service.close();
            }
        }
    }

    /**
     * MQTT 5 against a broker that answers every CONNECT with a 3.1.1 "unacceptable protocol version" CONNACK and
     * closes, as Mosquitto 1.5 does (E2E item 4). HiveMQ reports it as a CONNACK refusal, not a closed connection.
     */
    private static void protocolRefusedByConnAck() throws Exception {
        try (ServerSocket stub = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().name("mqtt311-stub").start(() -> refuseMqtt5(stub));
            BrokerSettings settings = new BrokerSettings("it-" + shortId(), null, null, null, false, 30, ProtocolVersion.MQTT_5,
                    BrokerSettings.DEFAULT_MAX_INBOUND_BYTES);
            BrokerConfig config = BrokerConfig.parse("tcp://" + stub.getInetAddress().getHostAddress() + ":" + stub.getLocalPort(),
                    settings).orElseThrow();
            HiveMqttService.Options options = HiveMqttService.Options.defaults(Outbound.state("mineqtt-it/refused/bridge/state", "offline"))
                    .withReconnectDelays(100, 400);
            HiveMqttService service = HiveMqttService.connect(config, options, LOG);
            try {
                tickUntil(service, () -> service.state().detail().contains(HiveMqttService.PROTOCOL_HINT), "the MQTT 3.1.1 hint");
                check(service.state().status() == ConnectionState.Status.RECONNECTING, "A refused protocol keeps retrying");
            } finally {
                service.close();
            }
        }
    }

    private static void refuseMqtt5(ServerSocket stub) {
        while (!stub.isClosed()) {
            try (Socket socket = stub.accept()) {
                InputStream in = socket.getInputStream();
                in.read(); // CONNECT fixed header
                int remaining = 0;
                for (int shift = 0, next = 0x80; (next & 0x80) != 0 && next >= 0; shift += 7) {
                    next = in.read();
                    remaining |= (next & 0x7F) << shift;
                }
                in.readNBytes(remaining);
                OutputStream out = socket.getOutputStream();
                out.write(new byte[]{0x20, 0x02, 0x00, 0x01});
                out.flush();
            } catch (IOException e) {
                return; // the stub was closed
            }
        }
    }

    // ---- helpers ----

    private static HiveMqttService service(Target target, TcpProxy proxy, ProtocolVersion protocol, String root, int maxInboundBytes) {
        String uri = (target.wsPath() == null ? "tcp://" : "ws://") + proxy.host() + ":" + proxy.port()
                + (target.wsPath() == null ? "" : target.wsPath());
        BrokerSettings settings = new BrokerSettings("it-" + shortId(), null, null, null, false, 30, protocol, maxInboundBytes);
        BrokerConfig config = BrokerConfig.parse(uri, settings).orElseThrow();
        HiveMqttService.Options options = HiveMqttService.Options.defaults(Outbound.state(root + "/bridge/state", "offline"))
                .withReconnectDelays(100, 400);
        return HiveMqttService.connect(config, options, LOG);
    }

    private static void connect(HiveMqttService service) throws InterruptedException {
        tickUntil(service, () -> service.state().isConnected(), "the connection");
        awaitBrokerOperations(service);
    }

    private static void reconnect(HiveMqttService service, TcpProxy proxy) throws InterruptedException {
        proxy.cut();
        tickUntil(service, () -> !service.state().isConnected(), "the connection to drop");
        proxy.restore();
        connect(service);
    }

    private static void awaitBrokerOperations(HiveMqttService service) throws InterruptedException {
        check(service.awaitBrokerOperations(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "SUBSCRIBE/UNSUBSCRIBE acknowledgements timed out");
    }

    /** Ticks the service the way the server thread does until the condition holds. */
    private static void tickUntil(HiveMqttService service, BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (true) {
            service.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
            service.flushOutbound();
            if (condition.getAsBoolean()) return;
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Timed out waiting for " + what + ", state " + service.state());
            Thread.sleep(5);
        }
    }

    private static void tickFor(HiveMqttService service, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            service.drainInbound(MqttService.DEFAULT_DRAIN_BUDGET_NANOS, MqttService.DEFAULT_MAX_MESSAGES_PER_TICK);
            service.flushOutbound();
            Thread.sleep(5);
        }
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    /** Polls a late subscriber until the retained value is {@code expected} (null: no retained message). */
    private static void awaitRetained(Target target, String topic, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        Optional<String> actual;
        do {
            actual = retainedValue(target, topic);
            if (actual.equals(Optional.ofNullable(expected))) return;
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("Retained value of " + topic + " is " + actual.orElse("<none>") + ", expected "
                + (expected == null ? "<none>" : expected));
    }

    /** What a new subscriber receives as retained, judged by a marker published after the SUBACK. */
    private static Optional<String> retainedValue(Target target, String topic) throws Exception {
        String marker = "mineqtt-it/marker/" + shortId();
        try (Remote probe = new Remote(target)) {
            probe.subscribe(marker, 1);
            probe.subscribe(topic, 1);
            probe.publish(marker, "m", 1, false);
            waitUntil(() -> !probe.payloads(marker).isEmpty(), "the probe marker");
            List<String> retained = probe.payloads(topic);
            return retained.isEmpty() ? Optional.empty() : Optional.of(retained.getFirst());
        }
    }

    private static void publishAll(Remote remote, String root, String payload) throws Exception {
        remote.publish(root + "/r/one", payload, 1, false);
        remote.publish(root + "/r/x/two", payload, 1, false);
        remote.publish(root + "/r/deep/a/b", payload, 1, false);
    }

    private static long count(List<String> payloads, String payload) {
        return payloads.stream().filter(payload::equals).count();
    }

    /** A fresh topic root per scenario keeps runs against a shared broker independent of each other. */
    private static String root(ProtocolVersion protocol) {
        return "mineqtt-it/" + shortId() + "/" + (protocol == ProtocolVersion.MQTT_5 ? "v5" : "v311");
    }

    // Client ids stay within the 23 characters MQTT 3.1.1 guarantees every broker accepts.
    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    /** Service warnings are expected while connections are cut; they are printed but nothing else is. */
    private static final MqttLog LOG = new MqttLog() {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable cause) {
            System.out.println("  [service] " + message + (cause == null ? "" : ": " + cause));
        }

        @Override
        public void error(String message, Throwable cause) {
            System.out.println("  [service] ERROR " + message + (cause == null ? "" : ": " + cause));
        }
    };

    /** Records what a handler received; handlers run on the ticking thread. */
    private static final class Recorder implements InboundHandler {
        final List<Inbound> received = new ArrayList<>();

        @Override
        public void handle(Inbound msg) {
            received.add(msg);
        }

        List<String> payloads() {
            return received.stream().map(Inbound::text).toList();
        }
    }

    /** A plain MQTT 3.1.1 client connected straight to the broker. */
    private static final class Remote implements AutoCloseable {
        private record Message(String topic, String payload) {
        }

        private final Mqtt3AsyncClient client;
        private final List<Message> received = new CopyOnWriteArrayList<>();

        Remote(Target target) throws Exception {
            MqttClientBuilder builder = MqttClient.builder()
                    .identifier("it-r-" + shortId())
                    .serverHost(target.host())
                    .serverPort(target.port());
            if (target.wsPath() != null) builder = builder.webSocketConfig().serverPath(target.wsPath()).applyWebSocketConfig();
            client = builder.useMqttVersion3().buildAsync();
            client.publishes(MqttGlobalPublishFilter.ALL,
                    publish -> received.add(new Message(publish.getTopic().toString(), new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8))));
            client.connectWith().cleanSession(true).send().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        void subscribe(String filter, int qos) throws Exception {
            client.subscribeWith().topicFilter(filter).qos(MqttQos.fromCode(qos)).send().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        void publish(String topic, String payload, int qos, boolean retain) throws Exception {
            publishAsync(topic, payload, qos, retain).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        CompletableFuture<?> publishAsync(String topic, String payload, int qos, boolean retain) {
            return client.publishWith().topic(topic).payload(utf8(payload)).qos(MqttQos.fromCode(qos)).retain(retain).send();
        }

        void clearRetained(String topic) throws Exception {
            publish(topic, "", 2, true);
        }

        List<String> payloads(String topic) {
            return received.stream().filter(message -> message.topic().equals(topic)).map(Message::payload).toList();
        }

        String last(String topic) {
            List<String> payloads = payloads(topic);
            return payloads.isEmpty() ? null : payloads.getLast();
        }

        @Override
        public void close() throws Exception {
            client.disconnect().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}

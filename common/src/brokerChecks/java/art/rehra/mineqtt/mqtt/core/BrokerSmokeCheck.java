package art.rehra.mineqtt.mqtt.core;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.exceptions.ConnectionClosedException;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5RetainHandling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Smoke check for the brokerChecks harness: starts {@link EmbeddedMoquette}, connects bare HiveMQ clients and
 * probes which MQTT features the broker offers, then asserts them against the capabilities recorded on
 * {@link EmbeddedMoquette}, so a broker upgrade that changes them fails here instead of silently changing coverage.
 * <p>
 * Given one {@code host:port} argument it probes that broker instead and only reports. Running it against the
 * Docker Mosquitto on localhost:1883 is how the MQTT 5 probes are validated while Moquette refuses MQTT 5.
 */
public final class BrokerSmokeCheck {
    private static final long TIMEOUT_SECONDS = 10;
    private static final MqttQos QOS = MqttQos.AT_LEAST_ONCE;

    private record Capabilities(boolean retainFlagAtQos0, boolean retainFlagAtQos1, boolean retainsWill,
                                boolean mqtt5, boolean noLocal, boolean retainHandling) {}

    static void main(String[] args) throws Exception {
        int separator = args.length == 1 ? args[0].lastIndexOf(':') : -1;
        if (args.length > 1 || (args.length == 1 && separator <= 0)) {
            throw new IllegalArgumentException("Usage: BrokerSmokeCheck [host:port]");
        }
        if (args.length == 1) {
            String host = args[0].substring(0, separator);
            int port = Integer.parseInt(args[0].substring(separator + 1));
            report(args[0], probe(host, port));
            return;
        }

        try (EmbeddedMoquette broker = EmbeddedMoquette.start()) {
            Capabilities capabilities = probe(EmbeddedMoquette.HOST, broker.port());
            report("embedded Moquette", capabilities);
            expect("retain flag on QoS 0 retained delivery", capabilities.retainFlagAtQos0(), EmbeddedMoquette.RETAIN_FLAG_AT_QOS_0);
            expect("retain flag on QoS 1 retained delivery", capabilities.retainFlagAtQos1(), EmbeddedMoquette.RETAIN_FLAG_AT_QOS_1);
            expect("retained will", capabilities.retainsWill(), EmbeddedMoquette.RETAINS_WILL);
            expect("MQTT 5", capabilities.mqtt5(), EmbeddedMoquette.SUPPORTS_MQTT_5);
            expect("MQTT 5 no-local", capabilities.noLocal(), EmbeddedMoquette.SUPPORTS_NO_LOCAL);
            expect("MQTT 5 retain-handling", capabilities.retainHandling(), EmbeddedMoquette.SUPPORTS_RETAIN_HANDLING);
        }
        System.out.println("Broker smoke checks passed.");
    }

    private static Capabilities probe(String host, int port) throws InterruptedException {
        // A fresh topic root per run keeps probes against a shared broker independent of earlier runs.
        String root = "mineqtt-smoke/" + shortId();
        checkOwnPublishEcho(host, port, root);
        boolean retainFlagAtQos0 = retainFlagOnSubscribe(host, port, root, MqttQos.AT_MOST_ONCE);
        boolean retainFlagAtQos1 = retainFlagOnSubscribe(host, port, root, MqttQos.AT_LEAST_ONCE);
        boolean retainsWill = retainsWill(host, port, root);
        if (!connectsWithMqtt5(host, port)) {
            return new Capabilities(retainFlagAtQos0, retainFlagAtQos1, retainsWill, false, false, false);
        }
        return new Capabilities(retainFlagAtQos0, retainFlagAtQos1, retainsWill,
                true, honoursNoLocal(host, port, root), honoursRetainHandling(host, port, root));
    }

    /**
     * A client with a retained will loses its connection through a {@link TcpProxy}; a watcher must get the will
     * live, and a late subscriber tells whether the broker also stored it as retained.
     */
    private static boolean retainsWill(String host, int port, String root) throws InterruptedException {
        String topic = root + "/v3/will";
        String marker = root + "/v3/will-marker";
        Mqtt3BlockingClient watcher = mqtt3Client(host, port, "v3w");
        Mqtt3BlockingClient late = mqtt3Client(host, port, "v3l");
        watcher.connect();
        try (TcpProxy proxy = new TcpProxy(host, port);
             Mqtt3BlockingClient.Mqtt3Publishes received = watcher.publishes(MqttGlobalPublishFilter.ALL)) {
            watcher.subscribeWith().topicFilter(topic).qos(QOS).send();
            Mqtt3BlockingClient dying = MqttClient.builder()
                    .identifier("smoke-v3d-" + shortId())
                    .serverHost(proxy.host())
                    .serverPort(proxy.port())
                    .useMqttVersion3()
                    .willPublish().topic(topic).qos(QOS).retain(true).payload(utf8("gone")).applyWillPublish()
                    .buildBlocking();
            dying.connect();
            proxy.cut();
            Mqtt3Publish will = received.receive(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElseThrow(() -> new AssertionError("The broker never fired the will on " + topic));
            check("gone".equals(text(will.getPayloadAsBytes())), "The will payload must round-trip unchanged");

            // The retained message, if any, arrives right after the SUBACK and therefore before the marker.
            late.connect();
            try (Mqtt3BlockingClient.Mqtt3Publishes lateReceived = late.publishes(MqttGlobalPublishFilter.ALL)) {
                late.subscribeWith().topicFilter(marker).qos(QOS).send();
                late.subscribeWith().topicFilter(topic).qos(QOS).send();
                late.publishWith().topic(marker).qos(QOS).payload(utf8("marker")).send();
                Mqtt3Publish first = lateReceived.receive(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .orElseThrow(() -> new AssertionError("The will probe marker never arrived on " + marker));
                return first.getTopic().toString().equals(topic);
            } finally {
                late.disconnect();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not open the will probe proxy", e);
        } finally {
            // Unsubscribed first, so the watcher does not receive its own clear after its flow is closed.
            watcher.unsubscribeWith().topicFilter(topic).send();
            watcher.publishWith().topic(topic).qos(QOS).retain(true).send();
            watcher.disconnect();
        }
    }

    /** MQTT 3.1.1 has no no-local, so a subscriber must hear its own publish; this is what the 3.1.1 loopback filters. */
    private static void checkOwnPublishEcho(String host, int port, String root) throws InterruptedException {
        String topic = root + "/v3/echo";
        Mqtt3BlockingClient client = mqtt3Client(host, port, "v3e");
        client.connect();
        try (Mqtt3BlockingClient.Mqtt3Publishes received = client.publishes(MqttGlobalPublishFilter.ALL)) {
            client.subscribeWith().topicFilter(topic).qos(QOS).send();
            client.publishWith().topic(topic).qos(QOS).payload(utf8("echo")).send();
            Mqtt3Publish echo = received.receive(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .orElseThrow(() -> new AssertionError("MQTT 3.1.1 client never heard its own publish on " + topic));
            check("echo".equals(text(echo.getPayloadAsBytes())), "MQTT 3.1.1 payload must round-trip unchanged");
        } finally {
            client.disconnect();
        }
    }

    /**
     * Asserts that a late MQTT 3.1.1 subscriber receives the retained message and reports whether the delivery
     * carries the retain flag, which the 3.1.1 rule "drop retained commands" depends on.
     */
    private static boolean retainFlagOnSubscribe(String host, int port, String root, MqttQos subscriptionQos)
            throws InterruptedException {
        String topic = root + "/v3/retained-qos" + subscriptionQos.getCode();
        Mqtt3BlockingClient publisher = mqtt3Client(host, port, "v3p");
        Mqtt3BlockingClient subscriber = mqtt3Client(host, port, "v3s");
        publisher.connect();
        try {
            publisher.publishWith().topic(topic).qos(QOS).retain(true).payload(utf8("stale")).send();
            subscriber.connect();
            Mqtt3Publish retained;
            try (Mqtt3BlockingClient.Mqtt3Publishes received = subscriber.publishes(MqttGlobalPublishFilter.ALL)) {
                subscriber.subscribeWith().topicFilter(topic).qos(subscriptionQos).send();
                retained = received.receive(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .orElseThrow(() -> new AssertionError("MQTT 3.1.1 late subscriber never got the retained message on " + topic));
            } finally {
                subscriber.disconnect();
            }
            check("stale".equals(text(retained.getPayloadAsBytes())), "MQTT 3.1.1 retained payload must round-trip unchanged");
            return retained.isRetain();
        } finally {
            publisher.publishWith().topic(topic).qos(QOS).retain(true).send();
            publisher.disconnect();
        }
    }

    /**
     * A 3.1.1-only broker refuses with a 3.1.1 CONNACK, which HiveMQ's MQTT 5 decoder rejects as a malformed packet
     * (Moquette 0.17: "Exception while decoding CONNACK: wrong reason code"); others answer with an MQTT 5 reason
     * code or simply close the connection.
     */
    private static boolean connectsWithMqtt5(String host, int port) {
        Mqtt5BlockingClient client = mqtt5Client(host, port, "v5c");
        try {
            client.connect();
        } catch (Mqtt5DisconnectException | Mqtt5ConnAckException | ConnectionClosedException refused) {
            System.out.println("MQTT 5 CONNECT failed: " + refused);
            return false;
        }
        client.disconnect();
        return true;
    }

    /**
     * The origin subscribes with no-local and publishes; a second client then publishes a marker. The broker routes
     * the own publish before acknowledging it, so the first delivery tells whether no-local was honoured.
     */
    private static boolean honoursNoLocal(String host, int port, String root) throws InterruptedException {
        String topic = root + "/v5/no-local";
        Mqtt5BlockingClient origin = mqtt5Client(host, port, "v5o");
        Mqtt5BlockingClient remote = mqtt5Client(host, port, "v5r");
        origin.connect();
        remote.connect();
        try (Mqtt5BlockingClient.Mqtt5Publishes received = origin.publishes(MqttGlobalPublishFilter.ALL)) {
            origin.subscribeWith().topicFilter(topic).noLocal(true).qos(QOS).send();
            origin.publishWith().topic(topic).qos(QOS).payload(utf8("own")).send();
            remote.publishWith().topic(topic).qos(QOS).payload(utf8("marker")).send();
            return "marker".equals(firstPayload(received, topic));
        } finally {
            origin.disconnect();
            remote.disconnect();
        }
    }

    /**
     * A retained message exists before the subscriber subscribes with {@code DO_NOT_SEND}; a marker published after
     * the SUBACK then arrives first only if the broker withheld the retained message.
     */
    private static boolean honoursRetainHandling(String host, int port, String root) throws InterruptedException {
        String topic = root + "/v5/retain-handling";
        Mqtt5BlockingClient subscriber = mqtt5Client(host, port, "v5s");
        Mqtt5BlockingClient remote = mqtt5Client(host, port, "v5r");
        remote.connect();
        try {
            remote.publishWith().topic(topic).qos(QOS).retain(true).payload(utf8("stale")).send();
            subscriber.connect();
            try (Mqtt5BlockingClient.Mqtt5Publishes received = subscriber.publishes(MqttGlobalPublishFilter.ALL)) {
                subscriber.subscribeWith().topicFilter(topic).retainHandling(Mqtt5RetainHandling.DO_NOT_SEND).qos(QOS).send();
                remote.publishWith().topic(topic).qos(QOS).payload(utf8("marker")).send();
                return "marker".equals(firstPayload(received, topic));
            } finally {
                subscriber.disconnect();
            }
        } finally {
            remote.publishWith().topic(topic).qos(QOS).retain(true).send();
            remote.disconnect();
        }
    }

    private static String firstPayload(Mqtt5BlockingClient.Mqtt5Publishes received, String topic) throws InterruptedException {
        Mqtt5Publish first = received.receive(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow(() -> new AssertionError("No MQTT 5 delivery on " + topic));
        return text(first.getPayloadAsBytes());
    }

    private static Mqtt3BlockingClient mqtt3Client(String host, int port, String role) {
        return MqttClient.builder()
                .identifier("smoke-" + role + "-" + shortId())
                .transportConfig()
                .serverHost(host)
                .serverPort(port)
                .socketConnectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .mqttConnectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .applyTransportConfig()
                .useMqttVersion3()
                .buildBlocking();
    }

    private static Mqtt5BlockingClient mqtt5Client(String host, int port, String role) {
        return MqttClient.builder()
                .identifier("smoke-" + role + "-" + shortId())
                .transportConfig()
                .serverHost(host)
                .serverPort(port)
                .socketConnectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .mqttConnectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .applyTransportConfig()
                .useMqttVersion5()
                .buildBlocking();
    }

    private static void report(String broker, Capabilities capabilities) {
        System.out.println("Broker capabilities of " + broker + ":");
        System.out.println("  MQTT 3.1.1 own-publish echo and retained delivery: yes");
        System.out.println("  MQTT 3.1.1 retain flag on retained delivery at QoS 0: " + yesNo(capabilities.retainFlagAtQos0()));
        System.out.println("  MQTT 3.1.1 retain flag on retained delivery at QoS 1: " + yesNo(capabilities.retainFlagAtQos1()));
        System.out.println("  Retained will stored as retained message: " + yesNo(capabilities.retainsWill()));
        System.out.println("  MQTT 5: " + yesNo(capabilities.mqtt5()));
        String needsMqtt5 = capabilities.mqtt5() ? "" : " (requires MQTT 5)";
        System.out.println("  MQTT 5 no-local: " + yesNo(capabilities.noLocal()) + needsMqtt5);
        System.out.println("  MQTT 5 retain-handling DO_NOT_SEND: " + yesNo(capabilities.retainHandling()) + needsMqtt5);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    // Client ids stay within the 23 characters MQTT 3.1.1 guarantees every broker accepts.
    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void expect(String capability, boolean observed, boolean recorded) {
        check(observed == recorded, "Moquette " + capability + " is now " + yesNo(observed)
                + " but EmbeddedMoquette records " + yesNo(recorded) + "; update the recorded capabilities");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

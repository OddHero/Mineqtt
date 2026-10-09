package art.rehra.mineqtt.mqtt.core;

import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.broker.security.IAuthorizatorPolicy;
import io.moquette.broker.subscriptions.Topic;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Properties;

/**
 * In-process Moquette 0.17 broker for the brokerChecks harness, bound to loopback on a free port.
 * <p>
 * Capabilities recorded by {@link BrokerSmokeCheck}, which fails if the broker stops matching them:
 * <ul>
 *     <li>MQTT 3.1 and 3.1.1 only. {@code MQTTConnection} answers an MQTT 5 CONNECT with the 3.1.1 return code
 *     {@code CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION}, so no MQTT 5 session ever exists.</li>
 *     <li>Hence neither MQTT 5 no-local nor retain-handling ({@code DO_NOT_SEND}) can be exercised here, nor whether
 *     no-local withholds a client's own retained message on subscribe. 0.17 is the newest release on Maven Central, so
 *     scenarios that need them run against the Docker Mosquitto instead.</li>
 *     <li>3.1.1 subscribers receive their own publishes, so the 3.1.1 loopback digest window is testable here.</li>
 *     <li>A retained message sent on subscribe keeps the retain flag only when delivered at QoS 0. The QoS 1 and 2
 *     paths build it with {@code createNotRetainedPublishMessage}, against MQTT 3.1.1 §3.3.1.3. The
 *     {@code isRetain()} command drop can therefore only be exercised here through QoS 0 subscriptions.</li>
 *     <li>A will with the retain flag reaches live subscribers but is not stored as retained ({@code PostOffice.fireWill}
 *     only calls {@code publish2Subscribers}), against MQTT 3.1.1 §3.1.2.7. A retained {@code offline} left by the
 *     will can therefore only be asserted against another broker.</li>
 * </ul>
 * The broker runs an ACL that refuses subscriptions to filters ending in {@code .../}{@value #REFUSED_LEVEL}{@code /#}
 * while {@link #refuseWideFilters} is on (the default), so a check can meet a broker that denies a wide filter but
 * allows the narrower ones below it. Moquette answers such a SUBSCRIBE with the failure return code 0x80.
 * <p>
 * Not probed, but read from {@code PostOffice}: {@code receivedPublishQos1} writes the PUBACK (and
 * {@code receivedPublishQos2} the PUBREC) before {@code manageRetain} stores or clears the retained message. A completed
 * QoS 1 publish therefore does not guarantee that a SUBSCRIBE sent afterwards sees the new retained message or its
 * clear. A completed QoS 2 publish does: the PUBREL that leads to the PUBCOMP is only handled after {@code manageRetain}
 * returns. Otherwise a check must wait for the retained state to reach a subscriber instead of relying on the ack.
 */
final class EmbeddedMoquette implements AutoCloseable {
    static final String HOST = "127.0.0.1";
    static final boolean RETAIN_FLAG_AT_QOS_0 = true;
    static final boolean RETAIN_FLAG_AT_QOS_1 = false;
    static final boolean RETAINS_WILL = false;
    static final boolean SUPPORTS_MQTT_5 = false;
    static final boolean SUPPORTS_NO_LOCAL = false;
    static final boolean SUPPORTS_RETAIN_HANDLING = false;
    static final boolean SENDS_OWN_RETAINED_ON_NO_LOCAL = false;
    static final String REFUSED_LEVEL = "acl-refused";
    /** The largest packet the broker takes; Moquette's own default is 8092 bytes. */
    static final int MAX_PACKET_BYTES = 256 * 1024;

    private final Server server;
    private final int port;
    private final RefusingAcl acl;

    private EmbeddedMoquette(Server server, int port, RefusingAcl acl) {
        this.server = server;
        this.port = port;
        this.acl = acl;
    }

    static EmbeddedMoquette start() throws IOException {
        int port = freePort();
        Properties properties = new Properties();
        properties.setProperty(IConfig.HOST_PROPERTY_NAME, HOST);
        properties.setProperty(IConfig.PORT_PROPERTY_NAME, Integer.toString(port));
        properties.setProperty(IConfig.WEB_SOCKET_PORT_PROPERTY_NAME, "disabled");
        properties.setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, "true");
        // The default writes H2/segment files to ./data/ in the working directory.
        properties.setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
        // The default contacts telemetry.moquette.io and an IP lookup service on every start.
        properties.setProperty(IConfig.ENABLE_TELEMETRY_NAME, "false");
        // The default of 8092 bytes per packet closes the connection of a client that publishes a larger message, so
        // a check could not send one above the 16 KiB limits.
        properties.setProperty(IConfig.NETTY_MAX_BYTES_PROPERTY_NAME, Integer.toString(MAX_PACKET_BYTES));

        Server server = new Server();
        RefusingAcl acl = new RefusingAcl();
        // Null handlers, SSL context and authenticator keep Moquette's defaults (anonymous access, as configured).
        server.startServer(new MemoryConfig(properties), null, null, null, acl);
        return new EmbeddedMoquette(server, port, acl);
    }

    int port() {
        return port;
    }

    /** Whether SUBSCRIBEs to {@code .../}{@value #REFUSED_LEVEL}{@code /#} are refused from now on. */
    void refuseWideFilters(boolean refuse) {
        acl.refuse = refuse;
    }

    @Override
    public void close() {
        server.stopServer();
    }

    /** Allows everything except reading through a filter that ends in the refused level and {@code #}. */
    private static final class RefusingAcl implements IAuthorizatorPolicy {
        volatile boolean refuse = true;

        @Override
        public boolean canWrite(Topic topic, String user, String client) {
            return true;
        }

        @Override
        public boolean canRead(Topic topic, String user, String client) {
            return !(refuse && topic.toString().endsWith("/" + REFUSED_LEVEL + "/#"));
        }
    }

    // Moquette's own getPort() is filled asynchronously after the bind, so the port is chosen up front instead.
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}

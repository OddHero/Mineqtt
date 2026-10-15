package art.rehra.mineqtt.mqtt.core;

import com.hivemq.client.mqtt.MqttClientSslConfig;

import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Optional;

/** {@link BrokerConfig#parse}: every scheme and its defaults, host forms, rejections and credential redaction. */
public final class BrokerUriCheck {
    private static final BrokerSettings SETTINGS = BrokerSettings.defaults("mineqtt_world_7f3a", ProtocolVersion.MQTT_5);

    public static void main(String[] args) throws Exception {
        schemes();
        hostForms();
        disabled();
        rejected();
        redaction();
        protocolValues();
        tls();
        System.out.println("Broker URI checks passed.");
    }

    /** The trust configurations behind ssl:// and wss://, and a setup failure that must not throw. */
    private static void tls() throws Exception {
        Path ca = Path.of(Objects.requireNonNull(BrokerUriCheck.class.getResource("/tls/test-ca.pem")).toURI());
        X509TrustManager trustManager = (X509TrustManager) TlsSupport.trustManagerFactory(ca).getTrustManagers()[0];
        X509Certificate[] issuers = trustManager.getAcceptedIssuers();
        check(issuers.length == 1 && issuers[0].getSubjectX500Principal().getName().equals("CN=MineQTT Test CA"),
                "A PEM CA file is trusted exactly");

        Path notACertificate = Files.createTempFile("mineqtt-ca", ".pem");
        try {
            Files.writeString(notACertificate, "not a certificate");
            TlsSupport.trustManagerFactory(notACertificate);
            throw new AssertionError("A file without a certificate was accepted as CA");
        } catch (GeneralSecurityException expected) {
            // Rejected as it must be.
        } finally {
            Files.delete(notACertificate);
        }

        BrokerSettings withCa = new BrokerSettings("c", null, null, ca, false, 60, ProtocolVersion.MQTT_5, 1024);
        MqttClientSslConfig caConfig = TlsSupport.sslConfig(BrokerConfig.parse("mqtts://h", withCa).orElseThrow());
        check(caConfig.getTrustManagerFactory().isPresent() && caConfig.getHostnameVerifier().isEmpty(),
                "A CA file replaces the truststore and keeps host name verification");
        MqttClientSslConfig jvmConfig = TlsSupport.sslConfig(parse("mqtts://h"));
        check(jvmConfig.getTrustManagerFactory().isEmpty() && jvmConfig.getHostnameVerifier().isEmpty(), "Default TLS uses the JVM truststore");
        BrokerSettings insecure = new BrokerSettings("c", null, null, null, true, 60, ProtocolVersion.MQTT_5, 1024);
        MqttClientSslConfig insecureConfig = TlsSupport.sslConfig(BrokerConfig.parse("mqtts://h", insecure).orElseThrow());
        X509TrustManager trustAll = (X509TrustManager) insecureConfig.getTrustManagerFactory().orElseThrow().getTrustManagers()[0];
        trustAll.checkServerTrusted(issuers, "EC");
        check(insecureConfig.getHostnameVerifier().orElseThrow().verify("any.host", null), "insecureSkipVerify accepts any host name");

        BrokerSettings missingCa = new BrokerSettings("c", null, null, Path.of("does-not-exist.pem"), false, 60, ProtocolVersion.MQTT_5, 1024);
        HiveMqttService failed = HiveMqttService.connect(BrokerConfig.parse("mqtts://h", missingCa).orElseThrow(),
                HiveMqttService.Options.defaults(null), SILENT);
        check(failed.state().status() == ConnectionState.Status.FAILED && failed.state().detail().contains("does-not-exist.pem"),
                "An unreadable CA file fails the setup with the reason, state " + failed.state());
        failed.publish(Outbound.state("a/b", "x"));
        failed.flushOutbound();
        failed.close();
        check(failed.state().status() == ConnectionState.Status.STOPPED, "A failed service still closes");

        BrokerSettings passwordOnly = new BrokerSettings("c", null, "secret".toCharArray(), null, false, 60, ProtocolVersion.MQTT_3_1_1, 1024);
        HiveMqttService refused = HiveMqttService.connect(BrokerConfig.parse("tcp://h", passwordOnly).orElseThrow(),
                HiveMqttService.Options.defaults(null), SILENT);
        check(refused.state().status() == ConnectionState.Status.FAILED, "MQTT 3.1.1 cannot send a password without a user");
        refused.close();
    }

    private static final MqttLog SILENT = new MqttLog() {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable cause) {
        }

        @Override
        public void error(String message, Throwable cause) {
        }
    };

    private static void schemes() {
        expect("tcp://broker.lan", "broker.lan", 1883, false, null);
        expect("mqtt://broker.lan", "broker.lan", 1883, false, null);
        expect("ssl://broker.lan", "broker.lan", 8883, true, null);
        expect("mqtts://broker.lan", "broker.lan", 8883, true, null);
        expect("tls://broker.lan:9883", "broker.lan", 9883, true, null);
        expect("ws://broker.lan", "broker.lan", 80, false, "/mqtt");
        expect("wss://broker.lan", "broker.lan", 443, true, "/mqtt");
        expect("ws://broker.lan:9001/ws/custom", "broker.lan", 9001, false, "/ws/custom");
        expect("WSS://Broker.LAN:8443/", "Broker.LAN", 8443, true, "/");
        // The v1 default, which v1 passed to serverHost() and therefore never connected.
        expect("tcp://localhost:1883", "localhost", 1883, false, null);
        expect("tcp://broker.lan:1884/", "broker.lan", 1884, false, null);
        check(parse("ws://h:9001").uri().equals("ws://h:9001/mqtt"), "uri() names the default WebSocket path");
        check(parse("mqtts://h").uri().equals("ssl://h:8883"), "uri() is canonical");
    }

    private static void hostForms() {
        expect("broker", "broker", 1883, false, null);
        expect("  broker:1884  ", "broker", 1884, false, null);
        expect("192.168.1.10", "192.168.1.10", 1883, false, null);
        expect("mosquitto_1:1883", "mosquitto_1", 1883, false, null);
        expect("[::1]:1885", "::1", 1885, false, null);
        expect("[::1]", "::1", 1883, false, null);
        expect("::1", "::1", 1883, false, null);
        expect("tcp://[fe80::1%eth0]:1883", "fe80::1%eth0", 1883, false, null);
        expect("wss://[2001:db8::7]/mqtt", "2001:db8::7", 443, true, "/mqtt");
        check(parse("[::1]:1885").uri().equals("tcp://[::1]:1885"), "uri() brackets IPv6 literals");
    }

    private static void disabled() {
        check(BrokerConfig.parse(null, SETTINGS).isEmpty(), "null means MQTT disabled");
        check(BrokerConfig.parse("", SETTINGS).isEmpty(), "an empty URI means MQTT disabled");
        check(BrokerConfig.parse("   ", SETTINGS).isEmpty(), "a blank URI means MQTT disabled");
    }

    private static void rejected() {
        String credentials = rejectedWith("tcp://user:hunter2@broker.lan:1883");
        check(credentials.contains("username and password") && !credentials.contains("hunter2"),
                "Userinfo is rejected without echoing the password: " + credentials);
        String slashPassword = rejectedWith("tcp://user:pass/word@broker.lan");
        check(!slashPassword.contains("pass/word"), "A password containing '/' is not echoed either: " + slashPassword);
        check(!rejectedWith("user:secret@broker").contains("secret"), "Userinfo without a scheme is rejected too");
        rejectedWith("http://broker.lan");
        rejectedWith("tcp://broker.lan:0");
        rejectedWith("tcp://broker.lan:65536");
        rejectedWith("tcp://broker.lan:port");
        rejectedWith("tcp://broker.lan:");
        rejectedWith("tcp://broker.lan/path");
        check(!rejectedWith("ws://broker.lan/mqtt?token=secret").contains("secret"), "A query token is not echoed");
        rejectedWith("tcp://broker.lan#frag");
        rejectedWith("tcp://");
        rejectedWith("tcp://:1883");
        rejectedWith("tcp://bro ker");
        rejectedWith("[::1");
        rejectedWith("[::1]x");
        rejectedWith("[zz::1]");
        rejectedWith("tcp://[broker.lan]:1883");
    }

    private static void redaction() {
        BrokerSettings secret = new BrokerSettings("mineqtt_world_7f3a", "steve", "hunter2".toCharArray(), Path.of("certs", "ca.pem"),
                false, 30, ProtocolVersion.MQTT_3_1_1, 1024);
        BrokerConfig config = BrokerConfig.parse("mqtts://broker.lan", secret).orElseThrow();
        String redacted = config.redacted();
        check(redacted.equals("ssl://broker.lan:8883 (MQTT 3.1.1, user ***, CA ca.pem)"), "Unexpected redaction: " + redacted);
        check(config.toString().equals(redacted), "toString() is redacted()");
        check(!secret.toString().contains("hunter2") && !secret.toString().contains("steve"), "BrokerSettings.toString() masks credentials");
        check(config.keepAliveSec() == 30 && config.maxInboundBytes() == 1024 && config.protocol() == ProtocolVersion.MQTT_3_1_1,
                "Settings are carried into the config");
        check(new String(config.passwordUtf8(), StandardCharsets.UTF_8).equals("hunter2"), "Password bytes are UTF-8");

        BrokerSettings insecure = new BrokerSettings("c", null, null, null, true, 60, ProtocolVersion.MQTT_5, 1024);
        check(BrokerConfig.parse("wss://h", insecure).orElseThrow().redacted().equals("wss://h:443/mqtt (MQTT 5, TLS verification OFF)"),
                "insecureSkipVerify is visible in the redaction");
        check(BrokerConfig.parse("tcp://h", insecure).orElseThrow().redacted().equals("tcp://h:1883 (MQTT 5)"),
                "TLS flags are not shown for plain TCP");
    }

    private static void protocolValues() {
        check(ProtocolVersion.parse("5") == ProtocolVersion.MQTT_5, "5 parses");
        check(ProtocolVersion.parse(" 5.0 ") == ProtocolVersion.MQTT_5, "5.0 parses");
        check(ProtocolVersion.parse("3.1.1") == ProtocolVersion.MQTT_3_1_1, "3.1.1 parses");
        for (String invalid : new String[]{"3", "4", "v5", "", null}) {
            try {
                ProtocolVersion.parse(invalid);
                throw new AssertionError("Protocol accepted: " + invalid);
            } catch (IllegalArgumentException expected) {
                // Rejected as it must be.
            }
        }
        try {
            new BrokerSettings("c", null, null, null, false, 70_000, ProtocolVersion.MQTT_5, 1);
            throw new AssertionError("Keep alive above 65535 accepted");
        } catch (IllegalArgumentException expected) {
            // Rejected as it must be.
        }
    }

    private static void expect(String uri, String host, int port, boolean tls, String wsPath) {
        BrokerConfig config = parse(uri);
        check(config.host().equals(host), uri + ": host " + config.host() + " instead of " + host);
        check(config.port() == port, uri + ": port " + config.port() + " instead of " + port);
        check(config.tls() == tls, uri + ": tls " + config.tls() + " instead of " + tls);
        check(Objects.equals(config.wsPath(), wsPath), uri + ": ws path " + config.wsPath() + " instead of " + wsPath);
        check(config.webSocket() == (wsPath != null), uri + ": webSocket() disagrees with the path");
        check(config.clientId().equals(SETTINGS.clientId()), uri + ": client id not carried over");
    }

    private static BrokerConfig parse(String uri) {
        Optional<BrokerConfig> config = BrokerConfig.parse(uri, SETTINGS);
        check(config.isPresent(), uri + " parsed as disabled");
        return config.get();
    }

    private static String rejectedWith(String uri) {
        try {
            BrokerConfig.parse(uri, SETTINGS);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(), uri + ": rejection without a message");
            return expected.getMessage();
        }
        throw new AssertionError("Broker URI accepted: " + uri);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

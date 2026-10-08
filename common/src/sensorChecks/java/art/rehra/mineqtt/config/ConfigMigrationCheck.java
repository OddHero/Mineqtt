package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Config v2 (design §9.6): defaults, the v1 migration (Fabric's JSON file end to end, NeoForge's values through the
 * shared {@link ConfigMigration}; reading its TOML file is not covered here), the JSON form, value validation and
 * credential masking. Needs no game bootstrap: the config package is plain Java plus Gson.
 */
public final class ConfigMigrationCheck {
    private static final String SECRET_USER = "alice-user";
    private static final String SECRET_PASSWORD = "hunter2-secret";

    static void main(String[] args) {
        defaults();
        fabricV1DefaultFile();
        neoForgeV1TcpBroker();
        customisedV1Values();
        v1WithMissingKeys();
        v2WithoutVersion();
        v1Backup();
        v2RoundTrip();
        v2FromNewerVersion();
        lenientV2Reading();
        sanitize();
        serverTypeDefaults();
        credentialsNeverLogged();
        brokerUrlCredentialsNeverLogged();
        validation();
        System.out.println("Config migration checks passed.");
    }

    private static void defaults() {
        MineQTTConfig.resetToDefaults();
        check(MineQTTConfig.broker.isEmpty(), "The default broker is empty");
        check(MineQTTConfig.brokerConfig("mineqtt_test").isEmpty(), "An empty broker disables MQTT");
        check(MineQTTConfig.protocol == ProtocolVersion.MQTT_5, "MQTT 5 is the default protocol");
        check(MineQTTConfig.keepAliveSec == 60, "Keep alive defaults to 60 s");
        check(MineQTTConfig.baseTopic.equals("mineqtt"), "The base topic defaults to mineqtt");
        check(MineQTTConfig.discoveryPrefix.equals("homeassistant"), "The discovery prefix defaults to homeassistant");
        check(MineQTTConfig.haBirthTopic.equals("homeassistant/status"), "The birth topic defaults to homeassistant/status");
        check(MineQTTConfig.bridgeId.isEmpty(), "The bridge id is generated per world by default");
        check(!MineQTTConfig.blockPlacerEnabled, "The block placer is off by default");
        check(MineQTTConfig.limitsMaxDevicesPerPlayer == 0, "No device limit by default");
        check(!MineQTTConfig.aclExplorerBrokerWide, "No broker-wide explorer by default");
        check(MineQTTConfig.rawAllow("world_7f3a").equals(TopicAcl.defaultRawAllow("mineqtt", "world_7f3a")),
                "The default raw allowlist resolves to TopicAcl's default: " + MineQTTConfig.rawAllow("world_7f3a"));
        check(MineQTTConfig.sanitize().isEmpty(), "The defaults need no correction");
    }

    /** What v1 Fabric wrote on first start: the in-code defaults with the public broker and the host-based ids. */
    private static void fabricV1DefaultFile() {
        JsonConfigFormat.Result result = read("""
                {"brokerUrl": "test.mosquitto.org", "clientId": "DESKTOP-1", "username": "", "password": "",
                 "autoReconnect": true, "connectionTimeout": 30, "keepAlive": 45, "allowItemNetherPortalTeleport": false,
                 "baseTopic": "minecraft-DESKTOP-1", "statusTopic": "status",
                 "goalX": 1, "goalY": 2, "goalZ": 3, "zombieGoalEnabled": true}""");
        check(result.migrated(), "A file without configVersion is migrated");
        check(MineQTTConfig.broker.isEmpty(), "The public v1 default broker is not carried over");
        check(MineQTTConfig.baseTopic.equals("mineqtt"), "The v1 default base topic minecraft-<clientId> becomes mineqtt");
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft-DESKTOP-1/status"), "The v1 status topic is recorded: " + MineQTTConfig.legacyV1StatusTopic);
        check(MineQTTConfig.keepAliveSec == 45, "keepAlive becomes keepAliveSec");
        check(!MineQTTConfig.allowItemNetherPortalTeleport, "The portal key survives");
        check(MineQTTConfig.goalX == 1 && MineQTTConfig.goalY == 2 && MineQTTConfig.goalZ == 3 && MineQTTConfig.zombieGoalEnabled,
                "The zombie goal keys survive");
        check(result.messages().stream().anyMatch(message -> message.contains("test.mosquitto.org")), "Dropping the public broker is logged");

        JsonObject written = JsonConfigFormat.write();
        check(written.get("configVersion").getAsInt() == StaticDefaults.CONFIG_VERSION, "The migrated file is written as v2");
        for (String v1Key : List.of("brokerUrl", "clientId", "autoReconnect", "connectionTimeout", "keepAlive", "statusTopic")) {
            check(!written.has(v1Key), "The v2 file has no v1 key " + v1Key);
        }
        JsonConfigFormat.Result reread = JsonConfigFormat.read(written);
        check(!reread.migrated() && reread.messages().isEmpty(), "The written v2 file reads back cleanly: " + reread.messages());
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft-DESKTOP-1/status") && MineQTTConfig.goalZ == 3, "Values survive the round trip");
    }

    /** v1 NeoForge's default broker: a URI the v1 client passed to serverHost() and could not connect with. */
    private static void neoForgeV1TcpBroker() {
        List<String> notes = ConfigMigration.applyV1(new ConfigMigration.V1("tcp://localhost:1883", "minecraft-client1234", "", "",
                60, "minecraft", "status", true, 7, -35, 4, false));
        check(MineQTTConfig.broker.equals("tcp://localhost:1883"), "The tcp:// form is kept as the v2 broker");
        BrokerConfig broker = MineQTTConfig.brokerConfig("mineqtt_test").orElseThrow();
        check(broker.host().equals("localhost") && broker.port() == 1883 && !broker.tls(), "The old default now parses: " + broker.uri());
        check(MineQTTConfig.baseTopic.equals("mineqtt"), "The v1 file default base topic minecraft becomes mineqtt");
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft/status"), "The v1 status topic is recorded");
        check(notes.stream().anyMatch(note -> note.contains("clientId")), "Dropping clientId is logged");
        check(MineQTTConfig.sanitize().isEmpty(), "A migrated default needs no correction");
    }

    private static void customisedV1Values() {
        JsonConfigFormat.Result result = read("""
                {"brokerUrl": "broker.lan:1884", "clientId": "c", "baseTopic": "home/minecraft", "statusTopic": "state",
                 "username": "%s", "password": "%s"}""".formatted(SECRET_USER, SECRET_PASSWORD));
        check(result.migrated(), "Migrated");
        check(MineQTTConfig.broker.equals("broker.lan:1884"), "A customised broker is kept");
        check(MineQTTConfig.baseTopic.equals("home/minecraft"), "A customised base topic is kept");
        check(MineQTTConfig.legacyV1StatusTopic.equals("home/minecraft/state"), "The customised v1 status topic is recorded");
        check(MineQTTConfig.username.equals(SECRET_USER) && MineQTTConfig.password.equals(SECRET_PASSWORD), "Credentials are carried over");

        read("""
                {"brokerUrl": "TEST.MOSQUITTO.ORG ", "baseTopic": "bad/#"}""");
        check(MineQTTConfig.broker.isEmpty(), "The public broker is recognised regardless of case and spacing");
        check(MineQTTConfig.baseTopic.equals("mineqtt"), "An invalid v1 base topic falls back to the default");
        check(MineQTTConfig.legacyV1StatusTopic.isEmpty(), "An invalid v1 status topic is not recorded");
    }

    private static void v1WithMissingKeys() {
        JsonConfigFormat.Result result = read("{\"clientId\": \"c\", \"goalX\": 99}");
        check(result.migrated(), "Even a sparse v1 file is migrated");
        check(MineQTTConfig.broker.isEmpty() && MineQTTConfig.baseTopic.equals("mineqtt"), "Missing v1 keys take the v2 defaults");
        check(MineQTTConfig.goalX == 99 && MineQTTConfig.goalY == StaticDefaults.DEFAULT_GOAL_Y, "Present keys are kept, missing ones default");
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft/status"), "The v1 file default status topic is recorded");

        ConfigMigration.applyV1(new ConfigMigration.V1(null, null, null, null, null, null, null, null, null, null, null, null));
        check(MineQTTConfig.broker.isEmpty() && MineQTTConfig.keepAliveSec == 60, "An empty v1 file gives the defaults");
    }

    /** A provisioned file with v2 keys but no configVersion must not be migrated, which would reset it. */
    private static void v2WithoutVersion() {
        JsonConfigFormat.Result result = read("""
                {"broker": "mqtt://broker.lan", "username": "%s", "password": "%s", "bridgeId": "survival_01",
                 "acl": {"rawAllow": ["zigbee2mqtt/#"]}}""".formatted(SECRET_USER, SECRET_PASSWORD));
        check(!result.migrated() && result.messages().isEmpty(), "A file without v1 keys is read as v2: " + result.messages());
        check(MineQTTConfig.broker.equals("mqtt://broker.lan") && MineQTTConfig.bridgeId.equals("survival_01"), "Its broker and bridge id are kept");
        check(MineQTTConfig.username.equals(SECRET_USER) && MineQTTConfig.password.equals(SECRET_PASSWORD), "Its credentials are kept");
        check(MineQTTConfig.aclRawAllow.equals(List.of("zigbee2mqtt/#")), "Its allowlist is kept");
        check(JsonConfigFormat.write().get("configVersion").getAsInt() == StaticDefaults.CONFIG_VERSION, "A save adds configVersion");

        check(!read("{\"goalX\": 99}").migrated() && MineQTTConfig.goalX == 99, "Keys shared by v1 and v2 do not make a file v1");
    }

    private static void v1Backup() {
        try {
            Path dir = Files.createTempDirectory("mineqtt-config-check");
            Path file = Files.writeString(dir.resolve("mineqtt.json"), "first v1");
            Path backup = dir.resolve("mineqtt.json.v1.bak");
            check(ConfigMigration.backUpV1(file, backup) && Files.readString(backup).equals("first v1"), "The v1 file is backed up");
            Files.writeString(file, "second v1");
            check(!ConfigMigration.backUpV1(file, backup), "An existing backup is reported");
            check(Files.readString(backup).equals("first v1"), "An earlier backup is never overwritten");
            Files.delete(backup);
            Files.delete(file);
            Files.delete(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void v2RoundTrip() {
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.broker = "mqtts://broker.lan:8883";
        MineQTTConfig.protocol = ProtocolVersion.MQTT_3_1_1;
        MineQTTConfig.keepAliveSec = 0;
        MineQTTConfig.username = SECRET_USER;
        MineQTTConfig.password = SECRET_PASSWORD;
        MineQTTConfig.tlsCaFile = "";
        MineQTTConfig.tlsInsecureSkipVerify = true;
        MineQTTConfig.bridgeId = "survival_01";
        MineQTTConfig.baseTopic = "mc";
        MineQTTConfig.discoveryPrefix = "ha";
        MineQTTConfig.haBirthTopic = "ha/status";
        MineQTTConfig.haExpose = HaExpose.OPT_IN;
        MineQTTConfig.aclRawAllow = List.of("zigbee2mqtt/+/set", "<base>/<bridgeId>/raw/#");
        MineQTTConfig.aclOpsBypassAllowlist = AutoBoolean.FALSE;
        MineQTTConfig.aclExplorerBrokerWide = true;
        MineQTTConfig.blockPlacerEnabled = true;
        MineQTTConfig.limitsMaxDevicesPerPlayer = 12;
        MineQTTConfig.legacyV1StatusTopic = "minecraft/status";
        MineQTTConfig.allowItemNetherPortalTeleport = false;
        MineQTTConfig.goalX = -5;
        MineQTTConfig.zombieGoalEnabled = true;
        MineQTTConfig.ConnectionSettings before = MineQTTConfig.connectionSettings();
        JsonObject written = JsonConfigFormat.write();
        check(written.getAsJsonObject("tls").get("insecureSkipVerify").getAsBoolean(), "Dotted keys are nested objects");

        MineQTTConfig.resetToDefaults();
        JsonConfigFormat.Result result = JsonConfigFormat.read(written);
        check(!result.migrated() && result.messages().isEmpty(), "A v2 file is read as is: " + result.messages());
        check(MineQTTConfig.connectionSettings().equals(before), "Connection settings survive the round trip");
        check(MineQTTConfig.haExpose == HaExpose.OPT_IN && MineQTTConfig.aclOpsBypassAllowlist == AutoBoolean.FALSE, "Enums survive");
        check(MineQTTConfig.aclRawAllow.equals(List.of("zigbee2mqtt/+/set", "<base>/<bridgeId>/raw/#")), "The allowlist survives unresolved");
        check(MineQTTConfig.rawAllow("survival_01").equals(List.of("zigbee2mqtt/+/set", "mc/survival_01/raw/#")), "Placeholders resolve");
        check(MineQTTConfig.aclExplorerBrokerWide && MineQTTConfig.blockPlacerEnabled && MineQTTConfig.limitsMaxDevicesPerPlayer == 12,
                "Security keys survive");
        check(!MineQTTConfig.allowItemNetherPortalTeleport && MineQTTConfig.goalX == -5 && MineQTTConfig.zombieGoalEnabled, "Experiments survive");
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft/status"), "The migration record survives");
        BrokerConfig broker = MineQTTConfig.brokerConfig("mineqtt_survival_01").orElseThrow();
        check(broker.tls() && broker.port() == 8883 && broker.insecureSkipVerify() && broker.keepAliveSec() == 0
                && broker.protocol() == ProtocolVersion.MQTT_3_1_1, "The broker config carries every connection key");
    }

    private static void v2FromNewerVersion() {
        JsonConfigFormat.Result result = read("{\"configVersion\": 3, \"broker\": \"localhost\", \"futureKey\": 1}");
        check(!result.migrated(), "A newer file is not treated as v1");
        check(MineQTTConfig.broker.equals("localhost"), "Known keys of a newer file are read");
        check(result.messages().stream().anyMatch(message -> message.contains("newer")), "A newer file is reported");
    }

    private static void lenientV2Reading() {
        JsonConfigFormat.Result result = read("""
                {"configVersion": 2, "broker": "localhost", "protocol": "4", "keepAliveSec": "sixty",
                 "password": 1234, "tls": "none", "ha": {"expose": "sometimes"},
                 "acl": {"rawAllow": ["a/#/b", 5, "mineqtt/shared/#"], "opsBypassAllowlist": true},
                 "limits": {"maxDevicesPerPlayer": 2.5}, "goalX": 11}""");
        check(MineQTTConfig.broker.equals("localhost") && MineQTTConfig.goalX == 11, "Valid keys are read despite bad neighbours");
        check(MineQTTConfig.protocol == ProtocolVersion.MQTT_5, "An unknown protocol falls back to 5");
        check(MineQTTConfig.keepAliveSec == 60, "A non-numeric keep alive falls back");
        check(MineQTTConfig.password.isEmpty(), "A non-string password is ignored");
        check(MineQTTConfig.tlsCaFile.isEmpty() && !MineQTTConfig.tlsInsecureSkipVerify, "A non-object section gives its defaults");
        check(MineQTTConfig.haExpose == HaExpose.AUTO, "An unknown expose mode falls back to AUTO");
        check(MineQTTConfig.aclOpsBypassAllowlist == AutoBoolean.TRUE, "A JSON boolean is accepted for an auto boolean");
        check(MineQTTConfig.aclRawAllow.equals(List.of("mineqtt/shared/#")), "Invalid allowlist entries are dropped: " + MineQTTConfig.aclRawAllow);
        check(MineQTTConfig.limitsMaxDevicesPerPlayer == 0, "A fractional limit falls back");
        for (String key : List.of("protocol", "keepAliveSec", "password", "tls", "ha.expose", "acl.rawAllow", "limits.maxDevicesPerPlayer")) {
            check(result.messages().stream().anyMatch(message -> message.startsWith(key)), "A problem with " + key + " is reported: " + result.messages());
        }
        check(result.messages().stream().noneMatch(message -> message.contains("1234")), "The password value is never repeated");
    }

    private static void sanitize() {
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.broker = " tcp://user:" + SECRET_PASSWORD + "@broker.lan ";
        MineQTTConfig.bridgeId = "World 1";
        MineQTTConfig.baseTopic = "a/+/b";
        MineQTTConfig.discoveryPrefix = "$SYS";
        MineQTTConfig.haBirthTopic = "homeassistant//status";
        MineQTTConfig.keepAliveSec = 70_000;
        MineQTTConfig.limitsMaxDevicesPerPlayer = -1;
        MineQTTConfig.tlsCaFile = "does/not/exist.pem";
        List<String> problems = MineQTTConfig.sanitize();
        check(MineQTTConfig.broker.equals("tcp://user:" + SECRET_PASSWORD + "@broker.lan"), "A malformed broker is kept for the user to fix");
        check(problems.stream().noneMatch(problem -> problem.contains(SECRET_PASSWORD)), "Broker problems never echo URI credentials");
        check(MineQTTConfig.bridgeId.isEmpty(), "An invalid bridge id falls back to a generated one");
        check(MineQTTConfig.baseTopic.equals("mineqtt") && MineQTTConfig.discoveryPrefix.equals("homeassistant")
                && MineQTTConfig.haBirthTopic.equals("homeassistant/status"), "Invalid topics fall back");
        check(MineQTTConfig.keepAliveSec == 60 && MineQTTConfig.limitsMaxDevicesPerPlayer == 0, "Out-of-range numbers fall back");
        check(problems.size() == 8, "Every problem is reported once: " + problems);
        try {
            MineQTTConfig.brokerConfig("mineqtt_test");
            throw new AssertionError("A malformed broker must not produce a connection");
        } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains(SECRET_PASSWORD), "The connect error does not echo credentials");
        }

        MineQTTConfig.resetToDefaults();
        MineQTTConfig.protocol = ProtocolVersion.MQTT_3_1_1;
        MineQTTConfig.password = SECRET_PASSWORD;
        check(MineQTTConfig.sanitize().stream().anyMatch(problem -> problem.startsWith("password")), "A 3.1.1 password without username is reported");
    }

    private static void serverTypeDefaults() {
        MineQTTConfig.resetToDefaults();
        check(MineQTTConfig.haExpose(false) == HaExpose.ALL && MineQTTConfig.haExpose(true) == HaExpose.OPT_IN, "AUTO expose follows the server type");
        check(MineQTTConfig.opsBypassAllowlist(false) && !MineQTTConfig.opsBypassAllowlist(true), "AUTO op bypass follows the server type");
        MineQTTConfig.haExpose = HaExpose.NONE;
        MineQTTConfig.aclOpsBypassAllowlist = AutoBoolean.TRUE;
        check(MineQTTConfig.haExpose(false) == HaExpose.NONE && MineQTTConfig.opsBypassAllowlist(true), "Explicit values win");
    }

    private static void credentialsNeverLogged() {
        JsonConfigFormat.Result migrated = read("""
                {"brokerUrl": "localhost", "username": "%s", "password": "%s"}""".formatted(SECRET_USER, SECRET_PASSWORD));
        for (String message : migrated.messages()) {
            check(!message.contains(SECRET_USER) && !message.contains(SECRET_PASSWORD), "A migration message leaks a credential: " + message);
        }
        String settings = MineQTTConfig.connectionSettings().toString();
        check(!settings.contains(SECRET_USER) && !settings.contains(SECRET_PASSWORD), "ConnectionSettings masks credentials: " + settings);
        BrokerConfig broker = MineQTTConfig.brokerConfig("mineqtt_test").orElseThrow();
        check(!broker.redacted().contains(SECRET_USER) && !broker.redacted().contains(SECRET_PASSWORD), "The logged broker masks credentials");
        check(broker.username().equals(SECRET_USER) && broker.password() != null, "Credentials still reach the client");

        check(new String(MineQTTConfig.effectivePassword("from-env")).equals("from-env"), "The environment password wins");
        check(new String(MineQTTConfig.effectivePassword("")).equals(SECRET_PASSWORD), "An empty environment variable is ignored");
        MineQTTConfig.password = "";
        check(MineQTTConfig.effectivePassword(null) == null, "No password at all sends none");
    }

    /** A v1 brokerUrl with credentials never connected, but it must not leak them into the migration log. */
    private static void brokerUrlCredentialsNeverLogged() {
        JsonConfigFormat.Result migrated = read("""
                {"brokerUrl": "tcp://%s:%s@broker.lan:1883"}""".formatted(SECRET_USER, SECRET_PASSWORD));
        check(migrated.migrated(), "Migrated");
        for (String message : migrated.messages()) {
            check(!message.contains(SECRET_USER) && !message.contains(SECRET_PASSWORD), "A migration message leaks URI credentials: " + message);
        }
        check(migrated.messages().stream().anyMatch(message -> message.startsWith("brokerUrl -> broker")), "The carried-over broker is still noted");
        String settings = MineQTTConfig.connectionSettings().toString();
        check(!settings.contains(SECRET_USER) && !settings.contains(SECRET_PASSWORD), "ConnectionSettings masks URI credentials: " + settings);

        List<String> notes = ConfigMigration.applyV1(new ConfigMigration.V1("broker.lan:1884", null, null, null, null, null, null, null,
                null, null, null, null));
        check(notes.contains("brokerUrl -> broker: tcp://broker.lan:1884"), "A valid broker is noted in canonical form: " + notes);
    }

    private static void validation() {
        check(ConfigValidation.isValidProtocol("5") && ConfigValidation.isValidProtocol("5.0") && ConfigValidation.isValidProtocol("3.1.1"),
                "Protocol spellings");
        check(!ConfigValidation.isValidProtocol("4") && !ConfigValidation.isValidProtocol(5), "Invalid protocols");
        check(ConfigValidation.isValidBridgeId("") && ConfigValidation.isValidBridgeId("world_7f3a"), "Valid bridge ids");
        check(!ConfigValidation.isValidBridgeId("World") && !ConfigValidation.isValidBridgeId("a/b") && !ConfigValidation.isValidBridgeId("a".repeat(65)),
                "Invalid bridge ids");
        check(ConfigValidation.isValidBroker("") && ConfigValidation.isValidBroker("wss://broker.lan/mqtt"), "Valid brokers");
        check(!ConfigValidation.isValidBroker("ftp://broker.lan") && !ConfigValidation.isValidBroker(null), "Invalid brokers");
        check(ConfigValidation.isValidRawAllowFilter("<base>/<bridgeId>/raw/#") && ConfigValidation.isValidRawAllowFilter("#"),
                "Valid allowlist filters");
        check(!ConfigValidation.isValidRawAllowFilter("a/#/b") && !ConfigValidation.isValidRawAllowFilter(""), "Invalid allowlist filters");
        Optional<String> error = ConfigValidation.brokerError("tcp://broker.lan:99999");
        check(error.isPresent() && error.get().contains("port"), "A broker error explains itself: " + error);
    }

    private static JsonConfigFormat.Result read(String json) {
        return JsonConfigFormat.read(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

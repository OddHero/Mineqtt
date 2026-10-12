package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import art.rehra.mineqtt.twin.core.HaDomains;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Config v2 (design §9.6): defaults, the v1 migration (Fabric's JSON file end to end, NeoForge's values through the
 * shared {@link ConfigMigration}; reading its TOML file is not covered here), the JSON form, value validation and
 * credential masking. Needs no game bootstrap: the config package is plain Java plus Gson.
 */
public final class ConfigMigrationCheck {
    private static final String SECRET_USER = "alice-user";
    private static final String SECRET_PASSWORD = "hunter2-secret";
    /** The zombie goal and item portal keys, removed in Milestone 2. */
    private static final List<String> REMOVED_KEYS = List.of("allowItemNetherPortalTeleport", "goalX", "goalY", "goalZ", "zombieGoalEnabled");

    static void main(String[] args) {
        defaults();
        fabricV1DefaultFile();
        neoForgeV1TcpBroker();
        customisedV1Values();
        v1WithMissingKeys();
        v2WithoutVersion();
        keysAddedAfterM1();
        keysRemovedInM2();
        v1Backup();
        v2RoundTrip();
        v2FromNewerVersion();
        lenientV2Reading();
        sanitize();
        serverTypeDefaults();
        twinKeys();
        worldKeys();
        narrowingPlayerKeys();
        neoForgeParity();
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
        check(MineQTTConfig.limitsMaxWiresPerPlayer == 256, "A player may own 256 wires by default");
        check(!MineQTTConfig.aclExplorerBrokerWide, "No broker-wide explorer by default");
        check(!MineQTTConfig.serverKeepTickingWhenEmpty, "An empty dedicated server pauses by default");
        check(MineQTTConfig.rawAllow("world_7f3a").equals(TopicAcl.defaultRawAllow("mineqtt", "world_7f3a")),
                "The default raw allowlist resolves to TopicAcl's default: " + MineQTTConfig.rawAllow("world_7f3a"));
        check(MineQTTConfig.twinStatestreamBase.equals("ha_statestream"), "The statestream base defaults to ha_statestream");
        check(MineQTTConfig.twinAllowedEntities.equals(List.of("AUTO")), "The allowed entities default to AUTO");
        check(MineQTTConfig.twinHaActionAllowlist.equals(HaDomains.DEFAULT_CONTROL_DOMAINS),
                "The controllable domains default to the control blueprint's allowed_domains default (D15)");
        check(MineQTTConfig.twinHaActionAllowlist.size() == 15 && !MineQTTConfig.twinHaActionAllowlist.contains("lock")
                && !MineQTTConfig.twinHaActionAllowlist.contains("climate"), "lock and climate are opt-in");
        check(MineQTTConfig.twinMaxRequestsPerSecond == 20 && MineQTTConfig.twinCatalog && MineQTTConfig.twinCatalogMax == 2000,
                "20 requests/s, the catalog on, 2000 entities");
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
        check(result.messages().stream().anyMatch(message -> message.contains("test.mosquitto.org")), "Dropping the public broker is logged");

        JsonObject written = JsonConfigFormat.write();
        check(written.get("configVersion").getAsInt() == StaticDefaults.CONFIG_VERSION, "The migrated file is written as v2");
        for (String v1Key : List.of("brokerUrl", "clientId", "autoReconnect", "connectionTimeout", "keepAlive", "statusTopic")) {
            check(!written.has(v1Key), "The v2 file has no v1 key " + v1Key);
        }
        REMOVED_KEYS.forEach(key -> check(!written.has(key), "The v2 file has no removed key " + key));
        JsonConfigFormat.Result reread = JsonConfigFormat.read(written);
        check(!reread.migrated() && reread.messages().isEmpty(), "The written v2 file reads back cleanly: " + reread.messages());
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft-DESKTOP-1/status") && MineQTTConfig.keepAliveSec == 45, "Values survive the round trip");
    }

    /** v1 NeoForge's default broker: a URI the v1 client passed to serverHost() and could not connect with. */
    private static void neoForgeV1TcpBroker() {
        List<String> notes = ConfigMigration.applyV1(new ConfigMigration.V1("tcp://localhost:1883", "minecraft-client1234", "", "",
                60, "minecraft", "status"));
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
        JsonConfigFormat.Result result = read("{\"clientId\": \"c\", \"keepAlive\": 99}");
        check(result.migrated(), "Even a sparse v1 file is migrated");
        check(MineQTTConfig.broker.isEmpty() && MineQTTConfig.baseTopic.equals("mineqtt"), "Missing v1 keys take the v2 defaults");
        check(MineQTTConfig.keepAliveSec == 99 && MineQTTConfig.username.equals(StaticDefaults.DEFAULT_USERNAME), "Present keys are kept, missing ones default");
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft/status"), "The v1 file default status topic is recorded");

        ConfigMigration.applyV1(new ConfigMigration.V1(null, null, null, null, null, null, null));
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

        check(!read("{\"baseTopic\": \"home/mc\"}").migrated() && MineQTTConfig.baseTopic.equals("home/mc"), "Keys shared by v1 and v2 do not make a file v1");
    }

    /** Keys added in Milestone 2 take their defaults from v1 files and from the files Milestone 1 wrote, which lack them. */
    private static void keysAddedAfterM1() {
        MineQTTConfig.serverKeepTickingWhenEmpty = true;
        check(read("{\"brokerUrl\": \"broker.lan\", \"clientId\": \"c\"}").migrated() && !MineQTTConfig.serverKeepTickingWhenEmpty,
                "A migrated v1 file keeps ticking off");

        MineQTTConfig.serverKeepTickingWhenEmpty = true;
        // Exactly what Milestone 1 wrote for its defaults.
        JsonConfigFormat.Result m1 = read("""
                {"configVersion": 2, "broker": "", "protocol": "5", "keepAliveSec": 60, "username": "", "password": "",
                 "tls": {"caFile": "", "insecureSkipVerify": false}, "bridgeId": "", "baseTopic": "mineqtt",
                 "discoveryPrefix": "homeassistant", "haBirthTopic": "homeassistant/status", "ha": {"expose": "AUTO"},
                 "acl": {"rawAllow": ["mineqtt/shared/#", "<base>/<bridgeId>/raw/#"], "opsBypassAllowlist": "AUTO", "explorerBrokerWide": false},
                 "blockPlacer": {"enabled": false}, "limits": {"maxDevicesPerPlayer": 0}, "legacy": {"v1StatusTopic": ""},
                 "allowItemNetherPortalTeleport": true, "goalX": 7, "goalY": -35, "goalZ": 4, "zombieGoalEnabled": false}""");
        check(!m1.migrated() && m1.messages().isEmpty(), "A Milestone 1 file reads without messages: " + m1.messages());
        check(!MineQTTConfig.serverKeepTickingWhenEmpty, "A Milestone 1 file keeps ticking off");
        check(MineQTTConfig.limitsMaxWiresPerPlayer == 256, "A Milestone 1 file gets the default wire cap");
        JsonObject written = JsonConfigFormat.write();
        check(written.has("server") && !written.getAsJsonObject("server").get("keepTickingWhenEmpty").getAsBoolean(),
                "Its next save adds server.keepTickingWhenEmpty as false");
        check(written.getAsJsonObject("limits").get("maxWiresPerPlayer").getAsInt() == 256, "Its next save adds limits.maxWiresPerPlayer as 256");
        checkTwinDefaultsWritten(written, "A Milestone 1 file");

        // Exactly what MineQTT 0.6.0 (Milestone 2) wrote for its defaults: no twin section.
        MineQTTConfig.twinAllowedEntities = List.of("light.*");
        MineQTTConfig.twinCatalog = false;
        JsonConfigFormat.Result m2 = read("""
                {"configVersion": 2, "broker": "", "protocol": "5", "keepAliveSec": 60, "username": "", "password": "",
                 "tls": {"caFile": "", "insecureSkipVerify": false}, "bridgeId": "", "baseTopic": "mineqtt",
                 "discoveryPrefix": "homeassistant", "haBirthTopic": "homeassistant/status", "ha": {"expose": "AUTO"},
                 "acl": {"rawAllow": ["mineqtt/shared/#", "<base>/<bridgeId>/raw/#"], "opsBypassAllowlist": "AUTO", "explorerBrokerWide": false},
                 "blockPlacer": {"enabled": false}, "limits": {"maxDevicesPerPlayer": 0, "maxWiresPerPlayer": 256},
                 "server": {"keepTickingWhenEmpty": false}, "legacy": {"v1StatusTopic": ""}}""");
        check(!m2.migrated() && m2.messages().isEmpty(), "A 0.6.0 file reads without messages: " + m2.messages());
        check(MineQTTConfig.twinAllowedEntities.equals(List.of("AUTO")) && MineQTTConfig.twinCatalog
                && MineQTTConfig.twinStatestreamBase.equals("ha_statestream"), "A 0.6.0 file gets the twin defaults");
        check(MineQTTConfig.configVersion == StaticDefaults.CONFIG_VERSION && StaticDefaults.CONFIG_VERSION == 2,
                "configVersion stays 2: the twin keys are additive (D14)");
        checkTwinDefaultsWritten(JsonConfigFormat.write(), "A 0.6.0 file");
        check(MineQTTConfig.twinAllowedEntities(false).equals(EntityGlobs.ALL) && MineQTTConfig.twinAllowedEntities(true).isEmpty(),
                "A 0.6.0 world gets every entity on integrated servers and the twin off on dedicated servers");
    }

    private static void checkTwinDefaultsWritten(JsonObject written, String what) {
        check(written.has("twin"), what + ": the next save adds the twin section");
        JsonObject twin = written.getAsJsonObject("twin");
        check(twin.get("statestreamBase").getAsString().equals("ha_statestream"), what + ": twin.statestreamBase");
        check(twin.getAsJsonArray("allowedEntities").size() == 1 && twin.getAsJsonArray("allowedEntities").get(0).getAsString().equals("AUTO"),
                what + ": twin.allowedEntities as [\"AUTO\"]");
        check(twin.getAsJsonArray("haActionAllowlist").size() == HaDomains.DEFAULT_CONTROL_DOMAINS.size(), what + ": twin.haActionAllowlist");
        check(twin.get("maxRequestsPerSecond").getAsInt() == 20 && twin.get("catalog").getAsBoolean() && twin.get("catalogMax").getAsInt() == 2000,
                what + ": twin.maxRequestsPerSecond, twin.catalog and twin.catalogMax");
    }

    /**
     * Milestone 2 removed the zombie goal and the item portal switch with their keys, which every Milestone 1 file and
     * many v1 files have (see {@link #keysAddedAfterM1} and {@link #fabricV1DefaultFile}). They are ignored when read,
     * and the next save drops them.
     */
    private static void keysRemovedInM2() {
        JsonConfigFormat.Result result = read("""
                {"configVersion": 2, "broker": "broker.lan", "bridgeId": "survival_01", "acl": {"rawAllow": ["zigbee2mqtt/#"]},
                 "allowItemNetherPortalTeleport": false, "goalX": 100, "goalY": 64, "goalZ": -100, "zombieGoalEnabled": true}""");
        check(!result.migrated() && result.messages().isEmpty(), "A file with the removed keys loads without messages: " + result.messages());
        check(MineQTTConfig.broker.equals("broker.lan") && MineQTTConfig.bridgeId.equals("survival_01")
                && MineQTTConfig.aclRawAllow.equals(List.of("zigbee2mqtt/#")), "The keys around them are read");
        JsonObject written = JsonConfigFormat.write();
        REMOVED_KEYS.forEach(key -> check(!written.has(key), "The next save drops " + key));
        check(written.get("broker").getAsString().equals("broker.lan") && written.get("bridgeId").getAsString().equals("survival_01"),
                "The next save keeps the other keys");

        check(read("{\"configVersion\": 2, \"goalX\": \"far\", \"zombieGoalEnabled\": 3}").messages().isEmpty(),
                "A removed key with a value of the wrong type is not reported either");
        check(!read("{\"goalX\": 1, \"zombieGoalEnabled\": true}").migrated(), "The removed keys alone do not make a file v1");
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
        MineQTTConfig.limitsMaxWiresPerPlayer = 0;
        MineQTTConfig.serverKeepTickingWhenEmpty = true;
        MineQTTConfig.legacyV1StatusTopic = "minecraft/status";
        MineQTTConfig.twinStatestreamBase = "home/ha/statestream";
        MineQTTConfig.twinAllowedEntities = List.of("light.*", "sensor.outdoor_?");
        MineQTTConfig.twinHaActionAllowlist = List.of("light", "lock");
        MineQTTConfig.twinMaxRequestsPerSecond = 5;
        MineQTTConfig.twinCatalog = false;
        MineQTTConfig.twinCatalogMax = 500;
        MineQTTConfig.ConnectionSettings before = MineQTTConfig.connectionSettings();
        JsonObject written = JsonConfigFormat.write();
        check(written.getAsJsonObject("tls").get("insecureSkipVerify").getAsBoolean(), "Dotted keys are nested objects");
        check(written.getAsJsonObject("server").get("keepTickingWhenEmpty").getAsBoolean(), "server.keepTickingWhenEmpty is nested too");

        MineQTTConfig.resetToDefaults();
        JsonConfigFormat.Result result = JsonConfigFormat.read(written);
        check(!result.migrated() && result.messages().isEmpty(), "A v2 file is read as is: " + result.messages());
        check(MineQTTConfig.connectionSettings().equals(before), "Connection settings survive the round trip");
        check(MineQTTConfig.haExpose == HaExpose.OPT_IN && MineQTTConfig.aclOpsBypassAllowlist == AutoBoolean.FALSE, "Enums survive");
        check(MineQTTConfig.aclRawAllow.equals(List.of("zigbee2mqtt/+/set", "<base>/<bridgeId>/raw/#")), "The allowlist survives unresolved");
        check(MineQTTConfig.rawAllow("survival_01").equals(List.of("zigbee2mqtt/+/set", "mc/survival_01/raw/#")), "Placeholders resolve");
        check(MineQTTConfig.aclExplorerBrokerWide && MineQTTConfig.blockPlacerEnabled && MineQTTConfig.limitsMaxDevicesPerPlayer == 12
                && MineQTTConfig.limitsMaxWiresPerPlayer == 0, "Security keys survive, an unlimited wire cap too");
        check(MineQTTConfig.serverKeepTickingWhenEmpty, "The keep-ticking opt-in survives");
        check(MineQTTConfig.legacyV1StatusTopic.equals("minecraft/status"), "The migration record survives");
        check(MineQTTConfig.twinStatestreamBase.equals("home/ha/statestream") && MineQTTConfig.twinAllowedEntities.equals(List.of("light.*", "sensor.outdoor_?"))
                && MineQTTConfig.twinHaActionAllowlist.equals(List.of("light", "lock")) && MineQTTConfig.twinMaxRequestsPerSecond == 5
                && !MineQTTConfig.twinCatalog && MineQTTConfig.twinCatalogMax == 500, "The twin keys survive");
        check(written.getAsJsonObject("twin").get("catalogMax").getAsInt() == 500, "The twin keys are nested under twin");
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
                 "limits": {"maxDevicesPerPlayer": 2.5, "maxWiresPerPlayer": "many"}, "server": {"keepTickingWhenEmpty": "yes"}, "discoveryPrefix": "ha"}""");
        check(MineQTTConfig.broker.equals("localhost") && MineQTTConfig.discoveryPrefix.equals("ha"), "Valid keys are read despite bad neighbours");
        check(MineQTTConfig.protocol == ProtocolVersion.MQTT_5, "An unknown protocol falls back to 5");
        check(MineQTTConfig.keepAliveSec == 60, "A non-numeric keep alive falls back");
        check(MineQTTConfig.password.isEmpty(), "A non-string password is ignored");
        check(MineQTTConfig.tlsCaFile.isEmpty() && !MineQTTConfig.tlsInsecureSkipVerify, "A non-object section gives its defaults");
        check(MineQTTConfig.haExpose == HaExpose.AUTO, "An unknown expose mode falls back to AUTO");
        check(MineQTTConfig.aclOpsBypassAllowlist == AutoBoolean.TRUE, "A JSON boolean is accepted for an auto boolean");
        check(MineQTTConfig.aclRawAllow.equals(List.of("mineqtt/shared/#")), "Invalid allowlist entries are dropped: " + MineQTTConfig.aclRawAllow);
        check(MineQTTConfig.limitsMaxDevicesPerPlayer == 0, "A fractional limit falls back");
        check(MineQTTConfig.limitsMaxWiresPerPlayer == 256, "A non-numeric wire cap falls back");
        check(!MineQTTConfig.serverKeepTickingWhenEmpty, "A non-boolean keep-ticking opt-in falls back to off");
        for (String key : List.of("protocol", "keepAliveSec", "password", "tls", "ha.expose", "acl.rawAllow", "limits.maxDevicesPerPlayer",
                "limits.maxWiresPerPlayer", "server.keepTickingWhenEmpty")) {
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
        MineQTTConfig.limitsMaxWiresPerPlayer = -5;
        MineQTTConfig.tlsCaFile = "does/not/exist.pem";
        List<String> problems = MineQTTConfig.sanitize();
        check(MineQTTConfig.broker.equals("tcp://user:" + SECRET_PASSWORD + "@broker.lan"), "A malformed broker is kept for the user to fix");
        check(problems.stream().noneMatch(problem -> problem.contains(SECRET_PASSWORD)), "Broker problems never echo URI credentials");
        check(MineQTTConfig.bridgeId.isEmpty(), "An invalid bridge id falls back to a generated one");
        check(MineQTTConfig.baseTopic.equals("mineqtt") && MineQTTConfig.discoveryPrefix.equals("homeassistant")
                && MineQTTConfig.haBirthTopic.equals("homeassistant/status"), "Invalid topics fall back");
        check(MineQTTConfig.keepAliveSec == 60 && MineQTTConfig.limitsMaxDevicesPerPlayer == 0 && MineQTTConfig.limitsMaxWiresPerPlayer == 256,
                "Out-of-range numbers fall back");
        check(problems.size() == 9, "Every problem is reported once: " + problems);
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

        // The bridge id "shared" under the base topic "mineqtt" would reserve the shared namespace of every server.
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.bridgeId = "shared";
        List<String> shared = MineQTTConfig.sanitize();
        check(MineQTTConfig.bridgeId.isEmpty() && shared.size() == 1 && shared.getFirst().startsWith("bridgeId") && shared.getFirst().contains("mineqtt/shared"),
                "The bridge id 'shared' under 'mineqtt' is rejected: " + shared);
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.bridgeId = "shared";
        MineQTTConfig.baseTopic = "home/mc";
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.bridgeId.equals("shared"), "Under another base topic it is an ordinary bridge id");
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.bridgeId = "shared_2";
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.bridgeId.equals("shared_2"), "Only 'shared' itself is reserved");
        check(ConfigValidation.bridgeNamespaceError("shared", "mineqtt").isPresent() && ConfigValidation.bridgeNamespaceError("", "mineqtt").isEmpty(),
                "The rule across both values");
        // A base topic in the shared namespace would put every bridge namespace under it there.
        for (String base : List.of("mineqtt/shared", "mineqtt/shared/x")) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.baseTopic = base;
            MineQTTConfig.bridgeId = "abc";
            List<String> nested = MineQTTConfig.sanitize();
            check(MineQTTConfig.baseTopic.equals("mineqtt") && MineQTTConfig.bridgeId.equals("abc") && nested.size() == 1
                    && nested.getFirst().startsWith("baseTopic") && nested.getFirst().contains(base), "A base topic in the shared namespace is rejected: " + nested);
            check(ConfigValidation.baseTopicNamespaceError(base).isPresent() && ConfigValidation.bridgeNamespaceError("abc", base).isPresent(),
                    "So is any bridge namespace under it: " + base);
        }
        check(ConfigValidation.baseTopicNamespaceError("mineqtt").isEmpty() && ConfigValidation.baseTopicNamespaceError("mineqtt/sharedx").isEmpty()
                && ConfigValidation.baseTopicNamespaceError("home/mineqtt/shared").isEmpty(), "Only the shared namespace itself is reserved");
    }

    private static void serverTypeDefaults() {
        MineQTTConfig.resetToDefaults();
        check(MineQTTConfig.haExpose(false) == HaExpose.ALL && MineQTTConfig.haExpose(true) == HaExpose.OPT_IN, "AUTO expose follows the server type");
        check(MineQTTConfig.opsBypassAllowlist(false) && !MineQTTConfig.opsBypassAllowlist(true), "AUTO op bypass follows the server type");
        MineQTTConfig.haExpose = HaExpose.NONE;
        MineQTTConfig.aclOpsBypassAllowlist = AutoBoolean.TRUE;
        check(MineQTTConfig.haExpose(false) == HaExpose.NONE && MineQTTConfig.opsBypassAllowlist(true), "Explicit values win");

        // twin.allowedEntities AUTO (M3 plan D13): every entity on integrated servers, the twin off on dedicated servers.
        MineQTTConfig.resetToDefaults();
        check(MineQTTConfig.twinAllowedEntities(false).equals(EntityGlobs.ALL), "AUTO allows every entity on integrated servers");
        check(MineQTTConfig.twinAllowedEntities(true).isEmpty(), "AUTO allows none on dedicated servers");
        check(MineQTTConfig.twinOffReason(false).isEmpty(), "The twin is on by default on integrated servers");
        check(MineQTTConfig.twinOffReason(true).orElse("").equals("no allowed entities: set twin.allowedEntities"),
                "and off on dedicated servers, naming the key: " + MineQTTConfig.twinOffReason(true));
        check(MineQTTConfig.twinSummary(true).equals("off, no allowed entities: set twin.allowedEntities"),
                "The status line of a dedicated server: " + MineQTTConfig.twinSummary(true));
        check(MineQTTConfig.twinSummary(false).equals("reads all entities (AUTO) under ha_statestream, catalog on (at most 2000 entities); "
                + "controls 15 domains, at most 20 requests/s"), "The status line of an integrated server: " + MineQTTConfig.twinSummary(false));
        MineQTTConfig.twinAllowedEntities = List.of("*");
        check(MineQTTConfig.twinAllowedEntities(true).equals(EntityGlobs.ALL) && MineQTTConfig.twinOffReason(true).isEmpty(),
                "An explicit * opens a dedicated server");
        check(MineQTTConfig.twinSummary(true).startsWith("reads all entities under ha_statestream"), "without the AUTO note: " + MineQTTConfig.twinSummary(true));
        MineQTTConfig.twinAllowedEntities = List.of();
        check(MineQTTConfig.twinAllowedEntities(false).isEmpty() && MineQTTConfig.twinOffReason(false).isPresent(),
                "An empty list turns the twin off on integrated servers too");
        MineQTTConfig.twinAllowedEntities = List.of("light.*", "sensor.outdoor_temperature");
        EntityGlobs listed = MineQTTConfig.twinAllowedEntities(true);
        check(listed.matches("light.kitchen") && listed.matches("sensor.outdoor_temperature") && !listed.matches("lock.front_door"),
                "Listed globs are used as they are on either server type");
        check(MineQTTConfig.twinAllowedEntities(false).equals(listed), "The server type does not change listed globs");
        check(MineQTTConfig.twinSummary(true).startsWith("reads 2 entity patterns under ha_statestream"), MineQTTConfig.twinSummary(true));
        MineQTTConfig.twinAllowedEntities = List.of("light.porch");
        check(MineQTTConfig.twinSummary(true).startsWith("reads entities matching light.porch under"), MineQTTConfig.twinSummary(true));
        MineQTTConfig.twinAllowedEntities = List.of("Light.Porch", "light.*");
        check(MineQTTConfig.twinAllowedEntities(true).patterns().equals(List.of("light.*")), "An unsanitized invalid glob never counts");
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinStatestreamBase = "";
        check(MineQTTConfig.twinOffReason(false).orElse("").equals("no statestream base: set twin.statestreamBase"),
                "Without a statestream base the twin is off and says why");
        MineQTTConfig.twinHaActionAllowlist = List.of();
        MineQTTConfig.twinCatalog = false;
        MineQTTConfig.twinStatestreamBase = "ha_statestream";
        check(MineQTTConfig.twinSummary(false).contains("catalog off; controls no domains"), MineQTTConfig.twinSummary(false));
    }

    /** The twin keys' validation (M3 plan D13): the F1 overlap rule for the statestream base, globs and domains. */
    private static void twinKeys() {
        // The statestream base must not overlap the discovery prefix, the base topic (any bridge) or mineqtt/shared.
        for (String base : List.of("homeassistant", "homeassistant/statestream", "mineqtt", "mineqtt/x", "mineqtt/x/statestream",
                "mineqtt/shared/x", "mineqtt/shared", "homeassistant/")) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.twinStatestreamBase = base;
            List<String> problems = MineQTTConfig.sanitize();
            check(MineQTTConfig.twinStatestreamBase.equals("ha_statestream") && problems.size() == 1 && problems.getFirst().startsWith("twin.statestreamBase")
                    && problems.getFirst().contains("using 'ha_statestream'"), "An overlapping statestream base '" + base + "' is refused: " + problems);
        }
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinStatestreamBase = "mineqtt/x";
        check(MineQTTConfig.sanitize().getFirst().contains("overlaps the MineQTT base topic 'mineqtt'"), "The refusal names what it overlaps");
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.baseTopic = "home/mc";
        MineQTTConfig.twinStatestreamBase = "home";
        check(MineQTTConfig.sanitize().size() == 1 && MineQTTConfig.twinStatestreamBase.equals("ha_statestream"), "A base above a custom base topic");
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.baseTopic = "home/mc";
        MineQTTConfig.twinStatestreamBase = "mineqtt/shared/states";
        check(MineQTTConfig.sanitize().getFirst().contains("shared namespace"), "The shared namespace is refused under any base topic");
        for (String base : List.of("ha_statestream/", " ha_statestream ", "home/ha/statestream", "statestream", "mineqttx", "home/mc/x", "homeassistantx/s")) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.twinStatestreamBase = base;
            List<String> problems = MineQTTConfig.sanitize();
            check(problems.isEmpty() && MineQTTConfig.twinStatestreamBase.equals(ConfigValidation.normalizeStatestreamBase(base)),
                    "The statestream base '" + base + "' is accepted: " + problems + " -> " + MineQTTConfig.twinStatestreamBase);
        }
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinStatestreamBase = "ha_statestream/";
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.twinStatestreamBase.equals("ha_statestream"),
                "One trailing slash is dropped once, as Home Assistant does, so the ACL and the twin see the same base");
        for (String base : List.of("ha/+/s", "ha/#", "$SYS/ha", "ha//s", "ha_statestream//", "a".repeat(257))) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.twinStatestreamBase = base;
            List<String> problems = MineQTTConfig.sanitize();
            check(MineQTTConfig.twinStatestreamBase.equals("ha_statestream") && problems.size() == 1 && problems.getFirst().contains("not a valid topic"),
                    "An invalid statestream base '" + base + "' falls back: " + problems);
        }
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinStatestreamBase = "";
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.twinStatestreamBase.isEmpty(), "An empty statestream base is kept: the twin reads nothing");
        // The default itself collides only with a discovery prefix or base topic in ha_statestream: the twin then reads nothing.
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.discoveryPrefix = "ha_statestream/discovery";
        MineQTTConfig.twinStatestreamBase = "ha_statestream";
        List<String> collision = MineQTTConfig.sanitize();
        check(MineQTTConfig.twinStatestreamBase.isEmpty() && collision.size() == 1 && collision.getFirst().contains("and so does the default"),
                "When the default collides too, the twin reads nothing and says so: " + collision);
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.twinStatestreamBase.isEmpty(), "and the result is stable");

        // Allowed entities: AUTO alone, else valid globs; invalid entries are dropped, which only narrows.
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinAllowedEntities = List.of(" light.* ", "Light.Kitchen", "sensor.temp[12]", "", "light.*", "switch.plug_?");
        List<String> globs = MineQTTConfig.sanitize();
        check(MineQTTConfig.twinAllowedEntities.equals(List.of("light.*", "switch.plug_?")), "Valid globs are kept stripped, once: " + MineQTTConfig.twinAllowedEntities);
        check(globs.size() == 3 && globs.stream().allMatch(problem -> problem.startsWith("twin.allowedEntities")), "Each invalid entry is reported: " + globs);
        check(globs.stream().anyMatch(problem -> problem.contains("lowercase")), "An uppercase glob says entity ids are lowercase: " + globs);
        for (List<String> auto : List.of(List.of("auto"), List.of(" AUTO "), List.of("Auto"))) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.twinAllowedEntities = auto;
            check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.twinAllowedEntities.equals(List.of("AUTO")), "AUTO in any spelling: " + auto);
        }
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinAllowedEntities = List.of("AUTO", "light.*");
        List<String> mixed = MineQTTConfig.sanitize();
        check(MineQTTConfig.twinAllowedEntities.equals(List.of("light.*")) && mixed.size() == 1 && mixed.getFirst().contains("stands alone"),
                "AUTO next to globs is ignored and reported: " + mixed);
        check(MineQTTConfig.twinAllowedEntities(true).matches("light.porch") && !MineQTTConfig.twinAllowedEntities(true).matches("lock.door"),
                "and does not open a dedicated server beyond the globs");
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinAllowedEntities = List.of("AUTO", "AUTO");
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.twinAllowedEntities.equals(List.of("AUTO")), "A repeated AUTO is AUTO");
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinAllowedEntities = List.of("Light.*");
        MineQTTConfig.sanitize();
        check(MineQTTConfig.twinAllowedEntities.isEmpty() && MineQTTConfig.twinAllowedEntities(false).isEmpty(),
                "A list of only invalid globs allows nothing, never everything");

        // Controllable domains: what HaDomains can control; the privacy domains and read-only domains are refused.
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinHaActionAllowlist = List.of("light", " lock ", "climate", "person", "binary_sensor", "sensor", "homeassistant", "light", "zone");
        List<String> domains = MineQTTConfig.sanitize();
        check(MineQTTConfig.twinHaActionAllowlist.equals(List.of("light", "lock", "climate")), "Controllable domains are kept once: " + MineQTTConfig.twinHaActionAllowlist);
        check(domains.size() == 5 && domains.stream().allMatch(problem -> problem.startsWith("twin.haActionAllowlist")), "Each refused domain is reported: " + domains);
        check(HaDomains.DEFAULT_CONTROL_DOMAINS.stream().allMatch(ConfigValidation::isValidHaActionDomain), "Every default domain is controllable");
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinHaActionAllowlist = List.of();
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.twinHaActionAllowlist.isEmpty(), "An empty list controls nothing and is valid");

        // Numbers out of range fall back.
        for (int[] values : new int[][]{{0, 0}, {-3, 10_001}, {1_001, -1}}) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.twinMaxRequestsPerSecond = values[0];
            MineQTTConfig.twinCatalogMax = values[1];
            List<String> numbers = MineQTTConfig.sanitize();
            check(MineQTTConfig.twinMaxRequestsPerSecond == 20 && MineQTTConfig.twinCatalogMax == 2000 && numbers.size() == 2,
                    "Out-of-range twin numbers fall back: " + numbers);
        }
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.twinMaxRequestsPerSecond = 1_000;
        MineQTTConfig.twinCatalogMax = 10_000;
        check(MineQTTConfig.sanitize().isEmpty(), "The upper bounds are allowed");

        // Wrong JSON types fall back with a message, as for every other key; the two lists that grant fall back to granting
        // nothing, since their defaults grant the most (an admin narrowing to one entity must not widen to all of them).
        JsonConfigFormat.Result result = read("""
                {"configVersion": 2, "twin": {"statestreamBase": 5, "allowedEntities": "light.*", "haActionAllowlist": ["light", 3],
                 "maxRequestsPerSecond": "fast", "catalog": "yes", "catalogMax": 1.5}}""");
        check(MineQTTConfig.twinStatestreamBase.equals("ha_statestream") && MineQTTConfig.twinAllowedEntities.isEmpty()
                && MineQTTConfig.twinHaActionAllowlist.equals(List.of("light")) && MineQTTConfig.twinMaxRequestsPerSecond == 20
                && MineQTTConfig.twinCatalog && MineQTTConfig.twinCatalogMax == 2000,
                "Wrongly typed twin keys take their defaults, allowedEntities none: " + MineQTTConfig.twinAllowedEntities);
        check(MineQTTConfig.twinOffReason(false).isPresent() && MineQTTConfig.twinOffReason(true).isPresent(),
                "An allowedEntities that is no list turns the twin off on every server type");
        for (String key : List.of("twin.statestreamBase", "twin.allowedEntities", "twin.haActionAllowlist", "twin.maxRequestsPerSecond",
                "twin.catalog", "twin.catalogMax")) {
            check(result.messages().stream().anyMatch(message -> message.startsWith(key)), "A problem with " + key + " is reported: " + result.messages());
        }
        JsonConfigFormat.Result notLists = read("""
                {"configVersion": 2, "twin": {"allowedEntities": {"light.*": true}, "haActionAllowlist": "light"}}""");
        check(MineQTTConfig.twinAllowedEntities.isEmpty() && MineQTTConfig.twinHaActionAllowlist.isEmpty(),
                "A haActionAllowlist that is no list allows no domain, not all of them: " + MineQTTConfig.twinHaActionAllowlist);
        check(notLists.messages().stream().anyMatch(message -> message.startsWith("twin.allowedEntities: expected a list"))
                && notLists.messages().stream().anyMatch(message -> message.startsWith("twin.haActionAllowlist: expected a list")),
                "Both are reported: " + notLists.messages());
        read("{\"configVersion\": 2, \"twin\": {}}");
        check(MineQTTConfig.twinAllowedEntities.equals(List.of("AUTO")) && MineQTTConfig.twinHaActionAllowlist.equals(HaDomains.DEFAULT_CONTROL_DOMAINS),
                "Absent, both take their defaults");
        check(read("{\"configVersion\": 2, \"twin\": []}").messages().contains("twin: expected an object, using the defaults of its keys"),
                "A twin section that is no object is reported");
    }

    /**
     * The keys M4 adds (M4 plan D16): every default, what a 0.7.0 file (which lacks them all) reads as, the next save, a
     * round trip of other values, the bounds and wrongly typed values. configVersion stays 2: the keys are additive.
     */
    private static void worldKeys() {
        MineQTTConfig.resetToDefaults();
        checkWorldDefaults("The defaults");
        check(MineQTTConfig.blockPlacerPlayersMayConfigure(false) && !MineQTTConfig.blockPlacerPlayersMayConfigure(true),
                "AUTO lets players configure Block Placers on integrated servers only");
        MineQTTConfig.blockPlacerPlayersMayConfigure = AutoBoolean.TRUE;
        check(MineQTTConfig.blockPlacerPlayersMayConfigure(true), "TRUE opens a dedicated server");
        MineQTTConfig.blockPlacerPlayersMayConfigure = AutoBoolean.FALSE;
        check(!MineQTTConfig.blockPlacerPlayersMayConfigure(false), "FALSE closes an integrated server");

        // Exactly what MineQTT 0.7.0 (Milestone 3) wrote for its defaults: the twin section, none of the M4 keys.
        MineQTTConfig.telemetryEnabled = false;
        MineQTTConfig.telemetryPlayers = TelemetryPlayers.FULL;
        MineQTTConfig.roomsMaxSize = 9;
        JsonObject v070 = JsonParser.parseString("""
                {"configVersion": 2, "broker": "", "protocol": "5", "keepAliveSec": 60, "username": "", "password": "",
                 "tls": {"caFile": "", "insecureSkipVerify": false}, "bridgeId": "", "baseTopic": "mineqtt",
                 "discoveryPrefix": "homeassistant", "haBirthTopic": "homeassistant/status", "ha": {"expose": "AUTO"},
                 "acl": {"rawAllow": ["mineqtt/shared/#", "<base>/<bridgeId>/raw/#"], "opsBypassAllowlist": "AUTO", "explorerBrokerWide": false},
                 "blockPlacer": {"enabled": false}, "limits": {"maxDevicesPerPlayer": 0, "maxWiresPerPlayer": 256},
                 "twin": {"statestreamBase": "ha_statestream", "allowedEntities": ["AUTO"], "haActionAllowlist": [],
                          "maxRequestsPerSecond": 20, "catalog": true, "catalogMax": 2000},
                 "server": {"keepTickingWhenEmpty": false}, "legacy": {"v1StatusTopic": ""}}""").getAsJsonObject();
        HaDomains.DEFAULT_CONTROL_DOMAINS.forEach(v070.getAsJsonObject("twin").getAsJsonArray("haActionAllowlist")::add);
        JsonConfigFormat.Result read = JsonConfigFormat.read(v070);
        check(!read.migrated() && read.messages().isEmpty(), "A 0.7.0 file reads without messages: " + read.messages());
        checkWorldDefaults("A 0.7.0 file");
        check(MineQTTConfig.configVersion == 2 && StaticDefaults.CONFIG_VERSION == 2, "configVersion stays 2: the M4 keys are additive (D16)");
        check(MineQTTConfig.twinHaActionAllowlist.equals(HaDomains.DEFAULT_CONTROL_DOMAINS), "Its twin keys are read as before");
        JsonObject written = JsonConfigFormat.write();
        JsonObject telemetry = written.getAsJsonObject("telemetry");
        check(telemetry.get("enabled").getAsBoolean() && telemetry.get("intervalSeconds").getAsInt() == 10 && telemetry.get("players").getAsString().equals("PRESENCE")
                && telemetry.get("publishPlayerNames").getAsBoolean() && !telemetry.get("allowBroadcast").getAsBoolean()
                && telemetry.get("maxPresenceDevices").getAsInt() == 64 && telemetry.get("forgetAfterDays").getAsInt() == 90,
                "Its next save adds the telemetry section with the defaults: " + telemetry);
        JsonObject blockPlacer = written.getAsJsonObject("blockPlacer");
        check(!blockPlacer.get("enabled").getAsBoolean() && blockPlacer.get("playersMayConfigure").getAsString().equals("AUTO")
                && blockPlacer.get("consumeItems").getAsBoolean(), "and the two Block Placer keys, the master switch still off: " + blockPlacer);
        check(!written.getAsJsonObject("blockObserver").get("exportBlockEntityData").getAsBoolean(), "and blockObserver.exportBlockEntityData as false");
        check(written.getAsJsonObject("rooms").get("maxSize").getAsInt() == 128 && written.getAsJsonObject("limits").get("maxRoomsPerPlayer").getAsInt() == 16,
                "and rooms.maxSize and limits.maxRoomsPerPlayer");
        check(JsonConfigFormat.read(written).messages().isEmpty(), "The saved file reads back without messages");
        checkWorldDefaults("The saved 0.7.0 file");

        // A 0.6.0 or 0.7.0 config that turned the Block Placer on keeps it on: the master switch is unchanged (D12).
        check(read("{\"configVersion\": 2, \"blockPlacer\": {\"enabled\": true}}").messages().isEmpty() && MineQTTConfig.blockPlacerEnabled
                && MineQTTConfig.blockPlacerPlayersMayConfigure == AutoBoolean.AUTO && MineQTTConfig.blockPlacerConsumeItems,
                "An enabled Block Placer stays enabled and gains the defaults of the new keys");

        // Every key survives a round trip with a value other than its default.
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.telemetryEnabled = false;
        MineQTTConfig.telemetryIntervalSeconds = 300;
        MineQTTConfig.telemetryPlayers = TelemetryPlayers.FULL;
        MineQTTConfig.telemetryPublishPlayerNames = false;
        MineQTTConfig.telemetryAllowBroadcast = true;
        MineQTTConfig.telemetryMaxPresenceDevices = 1;
        MineQTTConfig.telemetryForgetAfterDays = 0;
        MineQTTConfig.blockPlacerPlayersMayConfigure = AutoBoolean.FALSE;
        MineQTTConfig.blockPlacerConsumeItems = false;
        MineQTTConfig.blockObserverExportBlockEntityData = true;
        MineQTTConfig.roomsMaxSize = 8;
        MineQTTConfig.limitsMaxRoomsPerPlayer = 0;
        JsonObject custom = JsonConfigFormat.write();
        MineQTTConfig.resetToDefaults();
        JsonConfigFormat.Result reread = JsonConfigFormat.read(custom);
        check(reread.messages().isEmpty(), "Other values read back without messages: " + reread.messages());
        check(!MineQTTConfig.telemetryEnabled && MineQTTConfig.telemetryIntervalSeconds == 300 && MineQTTConfig.telemetryPlayers == TelemetryPlayers.FULL
                && !MineQTTConfig.telemetryPublishPlayerNames && MineQTTConfig.telemetryAllowBroadcast && MineQTTConfig.telemetryMaxPresenceDevices == 1
                && MineQTTConfig.telemetryForgetAfterDays == 0, "The telemetry keys survive the round trip");
        check(MineQTTConfig.blockPlacerPlayersMayConfigure == AutoBoolean.FALSE && !MineQTTConfig.blockPlacerConsumeItems
                && MineQTTConfig.blockObserverExportBlockEntityData && MineQTTConfig.roomsMaxSize == 8 && MineQTTConfig.limitsMaxRoomsPerPlayer == 0,
                "The Block Placer, Block Observer and room keys survive the round trip");
        for (String players : List.of("off", "Presence", " FULL ")) {
            check(read("{\"configVersion\": 2, \"telemetry\": {\"players\": \"" + players + "\"}}").messages().isEmpty()
                    && MineQTTConfig.telemetryPlayers.name().equals(players.strip().toUpperCase(java.util.Locale.ROOT)), "telemetry.players in any case: " + players);
        }
        check(read("{\"configVersion\": 2, \"blockPlacer\": {\"playersMayConfigure\": true}}").messages().isEmpty()
                && MineQTTConfig.blockPlacerPlayersMayConfigure == AutoBoolean.TRUE, "A JSON boolean is accepted for blockPlacer.playersMayConfigure");

        // Bounds: the edges are accepted, one beyond falls back to the default with one message per key.
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.telemetryIntervalSeconds = 5;
        MineQTTConfig.telemetryMaxPresenceDevices = 1000;
        MineQTTConfig.telemetryForgetAfterDays = StaticDefaults.MAX_TELEMETRY_FORGET_AFTER_DAYS;
        MineQTTConfig.roomsMaxSize = 512;
        MineQTTConfig.limitsMaxRoomsPerPlayer = 512;
        check(MineQTTConfig.sanitize().isEmpty(), "The upper and lower edges of every M4 range are allowed");
        for (int[] values : new int[][]{{4, 0, -1, 7, -1}, {301, 1001, StaticDefaults.MAX_TELEMETRY_FORGET_AFTER_DAYS + 1, 513, 513},
                {0, -5, Integer.MIN_VALUE, 0, Integer.MAX_VALUE}}) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.telemetryIntervalSeconds = values[0];
            MineQTTConfig.telemetryMaxPresenceDevices = values[1];
            MineQTTConfig.telemetryForgetAfterDays = values[2];
            MineQTTConfig.roomsMaxSize = values[3];
            MineQTTConfig.limitsMaxRoomsPerPlayer = values[4];
            List<String> problems = MineQTTConfig.sanitize();
            checkWorldDefaults("Out-of-range M4 numbers " + java.util.Arrays.toString(values));
            check(problems.size() == 5, "Each out-of-range M4 number is reported once: " + problems);
            for (String key : List.of("telemetry.intervalSeconds", "telemetry.maxPresenceDevices", "telemetry.forgetAfterDays", "rooms.maxSize",
                    "limits.maxRoomsPerPlayer")) {
                check(problems.stream().anyMatch(problem -> problem.startsWith(key + ": ")), "A problem with " + key + " is reported: " + problems);
            }
        }
        MineQTTConfig.resetToDefaults();
        MineQTTConfig.telemetryPlayers = null;
        MineQTTConfig.blockPlacerPlayersMayConfigure = null;
        check(MineQTTConfig.sanitize().isEmpty() && MineQTTConfig.telemetryPlayers == TelemetryPlayers.PRESENCE
                && MineQTTConfig.blockPlacerPlayersMayConfigure == AutoBoolean.AUTO, "A missing enum takes its default");

        // Wrongly typed values fall back to their defaults, each with a message.
        JsonConfigFormat.Result lenient = read("""
                {"configVersion": 2, "telemetry": {"enabled": "no", "intervalSeconds": 7.5, "players": "everyone", "publishPlayerNames": 0,
                 "allowBroadcast": "yes", "maxPresenceDevices": "many", "forgetAfterDays": [1]},
                 "blockPlacer": {"playersMayConfigure": "sometimes", "consumeItems": "no"}, "blockObserver": {"exportBlockEntityData": 1},
                 "rooms": {"maxSize": "big"}, "limits": {"maxRoomsPerPlayer": 1.5}}""");
        // The two player keys only ever narrow: they fall back to the narrowest setting, every other key to its default.
        check(MineQTTConfig.telemetryPlayers == TelemetryPlayers.OFF && !MineQTTConfig.telemetryPublishPlayerNames,
                "Wrongly typed player keys fall back to OFF and false");
        MineQTTConfig.telemetryPlayers = StaticDefaults.DEFAULT_TELEMETRY_PLAYERS;
        MineQTTConfig.telemetryPublishPlayerNames = StaticDefaults.DEFAULT_TELEMETRY_PUBLISH_PLAYER_NAMES;
        checkWorldDefaults("The other wrongly typed M4 keys");
        for (String key : List.of("telemetry.enabled", "telemetry.intervalSeconds", "telemetry.players", "telemetry.publishPlayerNames",
                "telemetry.allowBroadcast", "telemetry.maxPresenceDevices", "telemetry.forgetAfterDays", "blockPlacer.playersMayConfigure",
                "blockPlacer.consumeItems", "blockObserver.exportBlockEntityData", "rooms.maxSize", "limits.maxRoomsPerPlayer")) {
            check(lenient.messages().stream().anyMatch(message -> message.startsWith(key)), "A problem with " + key + " is reported: " + lenient.messages());
        }
        check(read("{\"configVersion\": 2, \"telemetry\": 3, \"rooms\": []}").messages().containsAll(List.of(
                "telemetry: expected an object, using the defaults of its keys", "rooms: expected an object, using the defaults of its keys")),
                "A new section that is no object is reported");
        checkWorldDefaults("New sections that are no objects");
    }

    /**
     * {@code telemetry.players} and {@code telemetry.publishPlayerNames} (M4 plan D9): absent, the default; present but no
     * valid setting, the narrowest one (OFF, false) with a message, so a typo never publishes more about players. Both
     * loaders use the same decision ({@link ConfigValidation}); NeoForge hands it the value as its file holds it.
     */
    private static void narrowingPlayerKeys() {
        for (Object valid : List.of("off", "Presence", " FULL ", "presence", TelemetryPlayers.FULL)) {
            List<String> messages = new ArrayList<>();
            TelemetryPlayers players = ConfigValidation.telemetryPlayersOrNarrowest(valid, messages);
            check(messages.isEmpty() && players.name().equals(valid.toString().strip().toUpperCase(java.util.Locale.ROOT)),
                    "telemetry.players reads " + valid + " as " + players);
        }
        List<String> absent = new ArrayList<>();
        check(ConfigValidation.telemetryPlayersOrNarrowest(null, absent) == TelemetryPlayers.PRESENCE && absent.isEmpty(),
                "An absent telemetry.players takes the default without a message");
        for (Object invalid : List.of("everyone", "", "OFFF", Boolean.TRUE, 2, 0.5, List.of("FULL"), new Object())) {
            List<String> messages = new ArrayList<>();
            check(ConfigValidation.telemetryPlayersOrNarrowest(invalid, messages) == TelemetryPlayers.OFF && messages.size() == 1
                    && messages.getFirst().startsWith("telemetry.players: "), "An invalid telemetry.players (" + invalid + ") narrows to OFF: " + messages);
        }
        for (Object valid : List.of(true, false, "true", "FALSE", " True ")) {
            List<String> messages = new ArrayList<>();
            boolean names = ConfigValidation.publishPlayerNamesOrNarrowest(valid, messages);
            check(messages.isEmpty() && names == valid.toString().strip().equalsIgnoreCase("true"), "telemetry.publishPlayerNames reads " + valid);
        }
        List<String> absentNames = new ArrayList<>();
        check(ConfigValidation.publishPlayerNamesOrNarrowest(null, absentNames) && absentNames.isEmpty(),
                "An absent telemetry.publishPlayerNames takes the default without a message");
        for (Object invalid : List.of("yes", "", "no", 0, 1, List.of(true), new Object())) {
            List<String> messages = new ArrayList<>();
            check(!ConfigValidation.publishPlayerNamesOrNarrowest(invalid, messages) && messages.size() == 1
                    && messages.getFirst().startsWith("telemetry.publishPlayerNames: "),
                    "An invalid telemetry.publishPlayerNames (" + invalid + ") narrows to false: " + messages);
        }

        // The JSON form: an absent key is the default, an invalid one narrows, and the next save writes the narrow value.
        check(read("{\"configVersion\": 2, \"telemetry\": {}}").messages().isEmpty() && MineQTTConfig.telemetryPlayers == TelemetryPlayers.PRESENCE
                && MineQTTConfig.telemetryPublishPlayerNames, "Absent player keys take their defaults");
        for (String players : List.of("\"everyone\"", "true", "2", "[\"FULL\"]", "{}", "\"\"")) {
            JsonConfigFormat.Result result = read("{\"configVersion\": 2, \"telemetry\": {\"players\": " + players + ", \"publishPlayerNames\": true}}");
            check(MineQTTConfig.telemetryPlayers == TelemetryPlayers.OFF && MineQTTConfig.telemetryPublishPlayerNames
                    && result.messages().stream().anyMatch(message -> message.startsWith("telemetry.players: ")),
                    "telemetry.players " + players + " reads as OFF with a message: " + result.messages());
        }
        for (String names : List.of("0", "1", "\"yes\"", "[true]", "{}")) {
            JsonConfigFormat.Result result = read("{\"configVersion\": 2, \"telemetry\": {\"players\": \"FULL\", \"publishPlayerNames\": " + names + "}}");
            check(!MineQTTConfig.telemetryPublishPlayerNames && MineQTTConfig.telemetryPlayers == TelemetryPlayers.FULL
                    && result.messages().stream().anyMatch(message -> message.startsWith("telemetry.publishPlayerNames: ")),
                    "telemetry.publishPlayerNames " + names + " reads as false with a message: " + result.messages());
        }
        check(read("{\"configVersion\": 2, \"telemetry\": {\"publishPlayerNames\": \"false\"}}").messages().isEmpty()
                && !MineQTTConfig.telemetryPublishPlayerNames, "\"false\" as a string is false, as NeoForge reads it");
        read("{\"configVersion\": 2, \"telemetry\": {\"players\": \"everyone\", \"publishPlayerNames\": \"yes\"}}");
        JsonObject saved = JsonConfigFormat.write().getAsJsonObject("telemetry");
        check(saved.get("players").getAsString().equals("OFF") && !saved.get("publishPlayerNames").getAsBoolean(),
                "The next save writes the narrow values: " + saved);
        MineQTTConfig.resetToDefaults();
    }

    private static void checkWorldDefaults(String what) {
        check(MineQTTConfig.telemetryEnabled, what + ": telemetry is on");
        check(MineQTTConfig.telemetryIntervalSeconds == 10, what + ": telemetry every 10 s at most: " + MineQTTConfig.telemetryIntervalSeconds);
        check(MineQTTConfig.telemetryPlayers == TelemetryPlayers.PRESENCE, what + ": player data up to presence");
        check(MineQTTConfig.telemetryPublishPlayerNames, what + ": no name veto");
        check(!MineQTTConfig.telemetryAllowBroadcast, what + ": broadcast off");
        check(MineQTTConfig.telemetryMaxPresenceDevices == 64, what + ": 64 presence devices: " + MineQTTConfig.telemetryMaxPresenceDevices);
        check(MineQTTConfig.telemetryForgetAfterDays == 90, what + ": players forgotten after 90 days: " + MineQTTConfig.telemetryForgetAfterDays);
        check(MineQTTConfig.blockPlacerPlayersMayConfigure == AutoBoolean.AUTO, what + ": who configures a Block Placer is AUTO");
        check(MineQTTConfig.blockPlacerConsumeItems, what + ": the Block Placer uses items");
        check(!MineQTTConfig.blockObserverExportBlockEntityData, what + ": no block-entity data from the Block Observer");
        check(MineQTTConfig.roomsMaxSize == 128, what + ": rooms of 128 blocks at most: " + MineQTTConfig.roomsMaxSize);
        check(MineQTTConfig.limitsMaxRoomsPerPlayer == 16, what + ": 16 rooms per player: " + MineQTTConfig.limitsMaxRoomsPerPlayer);
    }

    /**
     * The NeoForge config ({@code NeoForgeConfigHandler}, which this source set cannot load) defines, loads and saves
     * exactly the keys of the JSON form, at the same dotted paths: its source is read as text. A key added on one loader
     * only fails here. The values themselves are shared: both read their defaults and bounds from {@link StaticDefaults}.
     */
    private static void neoForgeParity() {
        String path = System.getProperty("mineqtt.neoForgeConfigSource");
        check(path != null, "The task passes the NeoForge config source as mineqtt.neoForgeConfigSource");
        String source;
        try {
            source = Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int start = source.indexOf("static {");
        int end = source.indexOf("SPEC = BUILDER.build();", start);
        check(start >= 0 && end > start, "The NeoForge spec is built in a static block");
        String spec = source.substring(start, end);
        // In source order: a spec field assignment, a section push or pop, and the key its define names.
        Matcher token = Pattern.compile("(\\w+)\\s*=\\s*BUILDER|\\.push\\(\"(\\w+)\"\\)|\\.pop\\(\\)|\\.define\\w*\\(\"(\\w+)\"").matcher(spec);
        List<String> sections = new ArrayList<>();
        Map<String, String> fieldsByKey = new LinkedHashMap<>();
        String field = null;
        while (token.find()) {
            if (token.group(1) != null) {
                field = token.group(1);
            } else if (token.group(2) != null) {
                sections.add(token.group(2));
            } else if (token.group(3) != null) {
                check(field != null, "Every NeoForge define is assigned to a spec field: " + token.group(3));
                String key = sections.isEmpty() ? token.group(3) : String.join(".", sections) + "." + token.group(3);
                check(fieldsByKey.put(key, field) == null, "NeoForge defines " + key + " once");
                field = null;
            } else {
                check(!sections.isEmpty(), "Every NeoForge pop has its push");
                sections.removeLast();
            }
        }
        check(sections.isEmpty(), "Every NeoForge push is popped: " + sections);

        MineQTTConfig.resetToDefaults();
        Set<String> jsonKeys = new TreeSet<>();
        flatten("", JsonConfigFormat.write(), jsonKeys);
        check(new TreeSet<>(fieldsByKey.keySet()).equals(jsonKeys), "NeoForge defines exactly the keys of the JSON form; only NeoForge: "
                + difference(fieldsByKey.keySet(), jsonKeys) + ", only JSON: " + difference(jsonKeys, fieldsByKey.keySet()));
        String load = source.substring(source.indexOf("public void loadConfig()"), source.indexOf("public void saveConfig()"));
        String write = source.substring(source.indexOf("private static void writeValues("));
        // The two player keys only ever narrow (M4 plan D9): NeoForge keeps any present value (isPresent), so it never
        // corrects one to the widest default, and loadConfig judges the raw value with the JSON form's rule.
        Set<String> narrowing = Set.of("TELEMETRY_PLAYERS", "TELEMETRY_PUBLISH_PLAYER_NAMES");
        check(Pattern.compile("\\.defineEnum\\(\"players\", StaticDefaults\\.DEFAULT_TELEMETRY_PLAYERS, EnumGetMethod\\.NAME_IGNORECASE,\\s*NeoForgeConfigHandler::isPresent\\)")
                .matcher(spec).find(), "NeoForge keeps any present telemetry.players for loadConfig to judge");
        check(Pattern.compile("\\.define\\(\"publishPlayerNames\", \\(\\) -> StaticDefaults\\.DEFAULT_TELEMETRY_PUBLISH_PLAYER_NAMES,\\s*NeoForgeConfigHandler::isPresent\\)")
                .matcher(spec).find(), "NeoForge keeps any present telemetry.publishPlayerNames (no BooleanValue, which corrects to true)");
        check(Pattern.compile("private static boolean isPresent\\(Object value\\) \\{\\s*return value != null;\\s*}").matcher(source).find(),
                "isPresent accepts every present value and nothing else");
        check(load.contains("MineQTTConfig.telemetryPlayers = ConfigValidation.telemetryPlayersOrNarrowest(rawTelemetryPlayers(), narrowed);")
                        && Pattern.compile("private static Object rawTelemetryPlayers\\(\\) \\{\\s*try \\{\\s*return TELEMETRY_PLAYERS\\.get\\(\\);").matcher(source).find(),
                "NeoForge narrows telemetry.players with the shared rule");
        check(Pattern.compile("MineQTTConfig\\.telemetryPublishPlayerNames = ConfigValidation\\.publishPlayerNamesOrNarrowest\\(\\s*"
                        + "\\(\\(ModConfigSpec\\.ConfigValue<\\?>\\) TELEMETRY_PUBLISH_PLAYER_NAMES\\)\\.get\\(\\), narrowed\\);").matcher(load).find(),
                "NeoForge narrows telemetry.publishPlayerNames with the shared rule, from the raw value");
        fieldsByKey.forEach((key, specField) -> {
            if (!specField.equals("CONFIG_VERSION") && !narrowing.contains(specField)) {
                check(Pattern.compile("MineQTTConfig\\.\\w+ = (?:List\\.copyOf\\(|ProtocolVersion\\.parse\\()?" + specField + "\\.get\\(\\)").matcher(load).find(),
                        "NeoForge loads " + key + " (" + specField + ")");
            }
            check(write.contains("sink.put(" + specField + ","), "NeoForge saves " + key + " (" + specField + ")");
        });
        for (String key : List.of("telemetry.enabled", "telemetry.intervalSeconds", "telemetry.players", "telemetry.publishPlayerNames",
                "telemetry.allowBroadcast", "telemetry.maxPresenceDevices", "telemetry.forgetAfterDays", "blockPlacer.enabled",
                "blockPlacer.playersMayConfigure", "blockPlacer.consumeItems", "blockObserver.exportBlockEntityData", "rooms.maxSize",
                "limits.maxRoomsPerPlayer")) {
            check(fieldsByKey.containsKey(key), "NeoForge has the M4 key " + key);
        }
    }

    private static void flatten(String prefix, JsonObject object, Set<String> keys) {
        object.entrySet().forEach(entry -> {
            String key = prefix + entry.getKey();
            if (entry.getValue().isJsonObject()) {
                flatten(key + ".", entry.getValue().getAsJsonObject(), keys);
            } else {
                keys.add(key);
            }
        });
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
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

        List<String> notes = ConfigMigration.applyV1(new ConfigMigration.V1("broker.lan:1884", null, null, null, null, null, null));
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

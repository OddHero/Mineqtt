package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.Inbound;
import art.rehra.mineqtt.mqtt.core.InboundHandler;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.mqtt.core.TopicFilters;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How discovery is published (design §4.2, §8.8, §9.3), run by Gradle's verifyDiscoveryPublisher task against a
 * recording MQTT service and a hand-driven clock: exposure and hashes, the rate limit, the v1 discovery migration with
 * its acknowledgements, the v1 status clear, a changed prefix, Home Assistant's birth, the bridge's requests and
 * telemetry, and the ghost cleanup (§4.5).
 */
public final class DiscoveryPublisherCheck {
    private static final String PREFIX = "homeassistant";
    private static int nextX;

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DeviceTypes.register(SmartLamp.TYPE);
        exposureAndHashes();
        groups();
        rateLimit();
        legacyMigration();
        changeDuringMigration();
        legacyWithoutExposure();
        v1Status();
        prefixChange();
        birth();
        bridgeRequestsAndTelemetry();
        ghostVerdicts();
        ghostScan();
        System.out.println("Discovery publisher checks passed.");
    }

    private static void exposureAndHashes() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord lamp = fixture.lamp("kitchen_lamp");
        String topic = config(lamp.objectId());

        fixture.mqtt.state = ConnectionState.reconnecting("check");
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty() && lamp.discoveryHash() == null, "Nothing is released while the broker is unreachable");
        fixture.mqtt.state = ConnectionState.CONNECTED;
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        Outbound first = fixture.mqtt.single();
        check(first.topic().equals(topic) && first.retain() && first.qos() == 1 && !first.refresh(), "A new device is discovered retained, QoS 1");
        check(lamp.discoveryHash() != null && fixture.registry.isDirty(), "Its hash is kept in the registry");

        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "An unchanged device is not published again");

        fixture.publisher.republishAll();
        fixture.publisher.tick();
        List<Outbound> republished = fixture.mqtt.take();
        check(republished.size() == 2 && republished.stream().allMatch(Outbound::refresh),
                "A republish sends the bridge and the device even when unchanged, got " + topics(republished));
        check(republished.getFirst().topic().equals(config(DiscoveryGoldenTest.IDENTITY.bridgeObjectId())), "The bridge comes first");

        lamp.setExpose(false);
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        Outbound cleared = fixture.mqtt.single();
        check(cleared.topic().equals(topic) && cleared.payload().length == 0 && cleared.offline() == OfflinePolicy.KEEP,
                "Expose off clears the retained message and keeps the clear through disconnects");
        check(lamp.discoveryHash() == null, "Nothing is retained any more");
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "A hidden device is cleared only once");

        MineQTTConfig.haExpose = HaExpose.OPT_IN;
        lamp.setExpose(null);
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "OPT_IN does not discover a device nobody exposed");
        lamp.setExpose(true);
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        check(fixture.mqtt.single().payload().length > 0, "OPT_IN discovers an exposed device");

        MineQTTConfig.haExpose = HaExpose.NONE;
        fixture.publisher.configChanged();
        fixture.publisher.tick();
        List<Outbound> none = fixture.mqtt.take();
        check(none.size() == 2 && none.stream().allMatch(message -> message.payload().length == 0),
                "NONE clears the bridge and every discovered device, got " + topics(none));

        MineQTTConfig.haExpose = HaExpose.ALL;
        DeviceRecord unknown = new DeviceRecord(UUID.randomUUID(), Identifier.fromNamespaceAndPath("othermod", "gadget"),
                GlobalPos.of(Level.OVERWORLD, new BlockPos(nextX++, 0, 0)), "Gadget", "gadget");
        fixture.registry.add(unknown);
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        check(fixture.mqtt.take().stream().noneMatch(message -> message.topic().equals(config(unknown.objectId()))),
                "A device of an unknown type is never published");

        fixture.publisher.removed(lamp);
        Outbound removed = fixture.mqtt.single();
        check(removed.topic().equals(topic) && removed.payload().length == 0, "A removed device's discovery is cleared");
    }

    private static void groups() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord member = fixture.lamp("hall_1");
        fixture.publisher.refresh(member);
        fixture.publisher.tick();
        fixture.mqtt.take();
        DeviceGroup group = fixture.registry.createGroup(UUID.randomUUID(), "Hallway");
        fixture.publisher.refresh(group);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "A group without members is not discovered");

        fixture.registry.joinGroup(group, member);
        fixture.publisher.refresh(member);
        fixture.publisher.refresh(group);
        fixture.publisher.tick();
        List<Outbound> joined = fixture.mqtt.take();
        check(joined.size() == 2 && joined.get(0).topic().equals(config(member.objectId())) && joined.get(0).payload().length == 0,
                "A member is hidden by default, got " + topics(joined));
        JsonObject groupConfig = JsonParser.parseString(joined.get(1).text()).getAsJsonObject();
        check(joined.get(1).topic().equals(config(group.objectId()))
                        && groupConfig.getAsJsonObject("cmps").getAsJsonObject("light").get("state_topic").getAsString().equals("mineqtt/world_7f3a/group/hallway"),
                "The group is discovered on its own topics");

        fixture.registry.leaveGroup(member);
        fixture.publisher.refresh(member);
        fixture.publisher.tick();
        check(fixture.mqtt.single().payload().length > 0, "A member that leaves is discovered again");

        // OPT_IN: any player may make a group, but only an exposed member, which a player with mayExpose turned on, or
        // the v1 light of a legacy group puts it into Home Assistant.
        MineQTTConfig.haExpose = HaExpose.OPT_IN;
        DeviceRecord porch = fixture.lamp("porch_1");
        DeviceGroup porchGroup = fixture.registry.createGroup(UUID.randomUUID(), "Porch");
        fixture.registry.joinGroup(porchGroup, porch);
        fixture.publisher.refresh(porchGroup);
        fixture.publisher.tick();
        check(!fixture.publisher.exposed(porchGroup) && fixture.mqtt.take().isEmpty(), "OPT_IN does not discover a group whose members nobody exposed");
        porch.setExpose(true);
        fixture.publisher.refresh(porchGroup);
        fixture.publisher.tick();
        Outbound exposedGroup = fixture.mqtt.single();
        check(fixture.publisher.exposed(porchGroup) && exposedGroup.topic().equals(config(porchGroup.objectId())) && exposedGroup.payload().length > 0,
                "OPT_IN discovers a group with an exposed member");
        porch.setExpose(null);
        fixture.publisher.refresh(porchGroup);
        fixture.publisher.tick();
        check(fixture.mqtt.single().payload().length == 0, "and clears it once no member is exposed any more");
        porchGroup.setLegacyTopic("home/porch");
        check(fixture.publisher.exposed(porchGroup), "A group that replaces a v1 topic stays discovered, as v1's light was");
        MineQTTConfig.haExpose = HaExpose.ALL;
        porchGroup.setLegacyTopic(null);
        check(fixture.publisher.exposed(porchGroup), "ALL discovers every group with members");
    }

    private static void rateLimit() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        for (int i = 0; i < 120; i++) fixture.lamp("lamp_" + i);
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        check(fixture.mqtt.take().size() == DiscoveryPublisher.MAX_PER_SECOND, "At most 50 messages at once");
        fixture.clock.addAndGet(500);
        fixture.publisher.tick();
        check(fixture.mqtt.take().size() == 25, "25 more after half a second");
        check(!fixture.publisher.idle() && fixture.publisher.hasPendingWork(), "The rest still waits, which keeps a paused server pumped");
        fixture.clock.addAndGet(1_000);
        fixture.publisher.tick();
        check(fixture.mqtt.take().size() == 46 && fixture.publisher.idle(), "The bridge and 120 devices are out after 1.5 s");
        check(!fixture.publisher.hasPendingWork(), "Nothing is left for a pump");
    }

    private static void legacyMigration() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord lamp = fixture.lamp("kitchen_lamp");
        lamp.setLegacyUniqueId("home_kitchen");
        String oldTopic = "homeassistant/light/home_kitchen/config";

        fixture.publisher.connected();
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        check(topics(fixture.mqtt.take()).equals(List.of(config(DiscoveryGoldenTest.IDENTITY.bridgeObjectId()))),
                "A device whose v1 discovery is not migrated is left out of the republish");
        check(fixture.publisher.migration().pending() == 1, "One migration is pending");

        fixture.publisher.startMigrations();
        fixture.publisher.tick();
        Outbound migrate = fixture.mqtt.single();
        check(migrate.topic().equals(oldTopic) && migrate.retain() && migrate.text().equals("{\"migrate_discovery\":true}"),
                "Step 1: migrate_discovery on the old topic, retained");
        fixture.clock.addAndGet(LegacyDiscoveryMigration.STEP_MILLIS - 1);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "Step 2 waits two seconds");
        fixture.clock.addAndGet(1);
        fixture.publisher.tick();
        Outbound discovery = fixture.mqtt.single();
        JsonObject migrated = JsonParser.parseString(discovery.text()).getAsJsonObject();
        JsonObject light = migrated.getAsJsonObject("cmps").getAsJsonObject("light");
        check(discovery.topic().equals(config(lamp.objectId())) && light.get("unique_id").getAsString().equals("home_kitchen"),
                "Step 2: the device discovery with the v1 unique_id");
        check(DiscoveryGoldenTest.ids(migrated).equals(List.of(lamp.objectId(), "home_kitchen")),
                "Step 2: Home Assistant reuses v1's device through its identifier, got " + DiscoveryGoldenTest.ids(migrated));
        fixture.clock.addAndGet(LegacyDiscoveryMigration.STEP_MILLIS);
        fixture.publisher.tick();
        Outbound clear = fixture.mqtt.single();
        check(clear.topic().equals(oldTopic) && clear.payload().length == 0 && clear.onAcknowledged() != null, "Step 3: the old topic is cleared");
        check(!fixture.registry.legacyDiscoveryMigrated("home_kitchen"), "Not migrated before the broker acknowledged the clear");
        clear.onAcknowledged().run();
        check(fixture.registry.legacyDiscoveryMigrated("home_kitchen") && fixture.publisher.migration().pending() == 0,
                "Step 4: migrated on the acknowledgement");
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "An unchanged device is not published again after the acknowledgement");

        fixture.publisher.republishAll();
        fixture.publisher.tick();
        Outbound republished = fixture.mqtt.take().stream().filter(message -> message.topic().equals(config(lamp.objectId())))
                .findFirst().orElseThrow(() -> new AssertionError("A migrated device is republished like any other"));
        check(DiscoveryGoldenTest.ids(JsonParser.parseString(republished.text()).getAsJsonObject()).equals(List.of(lamp.objectId(), "home_kitchen")),
                "A migrated device keeps v1's device identifier for good");

        // A connection lost before the acknowledgement: the late acknowledgement must not mark the new attempt.
        DeviceRecord second = fixture.lamp("hall_lamp");
        second.setLegacyUniqueId("home_hall");
        fixture.publisher.refresh(second);
        fixture.publisher.tick();
        Outbound restarted = fixture.mqtt.single();
        check(restarted.topic().equals("homeassistant/light/home_hall/config"), "A device that needs migrating later starts its own sequence");
        fixture.clock.addAndGet(2 * LegacyDiscoveryMigration.STEP_MILLIS);
        fixture.publisher.tick();
        fixture.clock.addAndGet(LegacyDiscoveryMigration.STEP_MILLIS);
        fixture.publisher.tick();
        Outbound staleClear = fixture.mqtt.take().getLast();
        check(staleClear.onAcknowledged() != null, "The clear waits for its acknowledgement");
        fixture.publisher.connected();
        staleClear.onAcknowledged().run();
        check(!fixture.registry.legacyDiscoveryMigrated("home_hall"), "An acknowledgement from an abandoned sequence marks nothing");
        fixture.publisher.startMigrations();
        fixture.publisher.tick();
        check(fixture.mqtt.single().text().contains("migrate_discovery"), "The sequence starts over after the reconnect");

        fixture.mqtt.state = ConnectionState.reconnecting("check");
        fixture.clock.addAndGet(10 * LegacyDiscoveryMigration.STEP_MILLIS);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "Nothing moves while disconnected");

        fixture.publisher.removed(second);
        List<Outbound> removed = fixture.mqtt.take();
        check(topics(removed).equals(List.of(config(second.objectId()), "homeassistant/light/home_hall/config")),
                "A removed device that still had v1 discovery clears both topics, got " + topics(removed));
    }

    private static void changeDuringMigration() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord lamp = fixture.lamp("kitchen_lamp");
        lamp.setLegacyUniqueId("home_kitchen");
        fixture.publisher.connected();
        fixture.publisher.startMigrations();
        fixture.publisher.tick();
        fixture.clock.addAndGet(LegacyDiscoveryMigration.STEP_MILLIS);
        fixture.publisher.tick();
        check(fixture.mqtt.take().size() == 2, "Steps 1 and 2 are out");

        // Renamed after step 2: the sequence holds the change back...
        fixture.registry.rename(lamp, "Pantry", "pantry");
        fixture.publisher.publishNow(lamp);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty(), "A change while the sequence runs waits for it");
        fixture.clock.addAndGet(LegacyDiscoveryMigration.STEP_MILLIS);
        fixture.publisher.tick();
        Outbound clear = fixture.mqtt.single();
        check(clear.payload().length == 0, "Step 3: the old topic is cleared");
        clear.onAcknowledged().run();
        check(fixture.publisher.migration().pending() == 0, "Migrated on the acknowledgement");

        // ...and publishes it once the sequence is done.
        fixture.publisher.tick();
        Outbound renamed = fixture.mqtt.single();
        JsonObject light = JsonParser.parseString(renamed.text()).getAsJsonObject().getAsJsonObject("cmps").getAsJsonObject("light");
        check(renamed.topic().equals(config(lamp.objectId())) && light.get("state_topic").getAsString().endsWith("/pantry"),
                "The change is published after the migration, got " + renamed.text());
    }

    private static void legacyWithoutExposure() {
        Fixture fixture = new Fixture(HaExpose.NONE);
        DeviceRecord lamp = fixture.lamp("cellar_lamp");
        lamp.setLegacyUniqueId("home_cellar");
        fixture.publisher.connected();
        fixture.publisher.startMigrations();
        fixture.publisher.tick();
        Outbound clear = fixture.mqtt.single();
        check(clear.topic().equals("homeassistant/light/home_cellar/config") && clear.payload().length == 0,
                "A device that is not exposed only gets its v1 discovery cleared");
        clear.onAcknowledged().run();
        check(fixture.registry.legacyDiscoveryMigrated("home_cellar"), "and is migrated with that");
    }

    private static void v1Status() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        MineQTTConfig.legacyV1StatusTopic = "minecraft-host/status";
        fixture.publisher.connected();
        Outbound clear = fixture.mqtt.single();
        check(clear.topic().equals("minecraft-host/status") && clear.payload().length == 0 && clear.retain(), "The v1 status is cleared");
        fixture.publisher.connected();
        check(fixture.mqtt.single().topic().equals("minecraft-host/status"), "Until acknowledged it is cleared on every connect");
        clear.onAcknowledged().run();
        check(fixture.registry.v1StatusCleared(), "The acknowledgement is recorded");
        fixture.publisher.connected();
        check(fixture.mqtt.take().isEmpty(), "Then never again");

        Fixture invalid = new Fixture(HaExpose.ALL);
        MineQTTConfig.legacyV1StatusTopic = "minecraft/+/status";
        invalid.publisher.connected();
        check(invalid.mqtt.take().isEmpty(), "An invalid topic is not published");
        MineQTTConfig.legacyV1StatusTopic = "";
    }

    private static void prefixChange() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord lamp = fixture.lamp("kitchen_lamp");
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        fixture.mqtt.take();
        MineQTTConfig.discoveryPrefix = "ha2";
        try {
            fixture.publisher.configChanged();
            List<Outbound> cleared = fixture.mqtt.take();
            check(cleared.size() == 2 && cleared.stream().allMatch(message -> message.topic().startsWith(PREFIX + "/") && message.payload().length == 0),
                    "A new prefix clears everything under the old one, got " + topics(cleared));
            fixture.publisher.tick();
            List<Outbound> moved = fixture.mqtt.take();
            check(topics(moved).contains("ha2/device/" + lamp.objectId() + "/config") && moved.size() == 2,
                    "and publishes it under the new one, got " + topics(moved));
        } finally {
            MineQTTConfig.discoveryPrefix = PREFIX;
        }
    }

    private static void birth() {
        FakeMqtt mqtt = new FakeMqtt();
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger republished = new AtomicInteger();
        BirthWatcher watcher = new BirthWatcher(mqtt, republished::incrementAndGet, clock::get, new Random(3));
        check(watcher.heard().equals(BirthWatcher.Heard.NOTHING), "Nothing is heard before Home Assistant speaks");
        mqtt.deliver("homeassistant/status", "offline");
        watcher.tick();
        check(!watcher.pending(), "offline is no birth");
        check(watcher.heard().equals(new BirthWatcher.Heard(BirthWatcher.Status.WILL, 1_000)), "Its last will is heard: " + watcher.heard());
        mqtt.deliver("homeassistant/status", "online");
        clock.addAndGet(10);
        mqtt.deliver("homeassistant/status", "online");
        check(watcher.pending(), "online schedules a republish");
        check(watcher.heard().equals(new BirthWatcher.Heard(BirthWatcher.Status.BIRTH, 1_010)),
                "The Bridge Console hears the last birth, also one folded into a pending republish: " + watcher.heard());
        clock.addAndGet(BirthWatcher.MAX_JITTER_MILLIS);
        watcher.tick();
        watcher.tick();
        check(republished.get() == 1, "Births while one is pending fold into one republish, got " + republished.get());
        mqtt.deliver("homeassistant/status", "restarting");
        check(watcher.heard().status() == BirthWatcher.Status.BIRTH, "Another word changes nothing it heard");

        MineQTTConfig.haBirthTopic = "ha/status";
        try {
            watcher.configChanged();
            check(mqtt.handlers.keySet().equals(Set.of("ha/status")), "A changed birth topic moves the subscription");
            check(watcher.heard().equals(BirthWatcher.Heard.NOTHING), "and forgets what the old topic said");
        } finally {
            MineQTTConfig.haBirthTopic = "homeassistant/status";
        }
        watcher.stop();
        check(mqtt.handlers.isEmpty(), "Stopping unsubscribes");
    }

    private static void bridgeRequestsAndTelemetry() {
        FakeMqtt mqtt = new FakeMqtt();
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger republished = new AtomicInteger();
        AtomicInteger players = new AtomicInteger(2);
        BridgeDevice bridge = new BridgeDevice(mqtt, () -> DiscoveryGoldenTest.TOPICS, players::get, clock::get, republished::incrementAndGet);
        check(mqtt.handlers.containsKey("mineqtt/world_7f3a/bridge/request/+"), "The bridge takes requests");

        mqtt.deliver("mineqtt/world_7f3a/bridge/request/republish", "");
        check(republished.get() == 0 && mqtt.take().isEmpty(), "An empty request (a retained clear) is ignored");
        mqtt.deliver("mineqtt/world_7f3a/bridge/request/republish", "republish");
        Outbound ok = mqtt.single();
        check(republished.get() == 1 && ok.topic().equals("mineqtt/world_7f3a/bridge/response/republish") && !ok.retain()
                && ok.text().equals("{\"status\":\"ok\"}"), "republish runs and is answered");
        mqtt.deliver("mineqtt/world_7f3a/bridge/request/explode", "now");
        check(mqtt.single().text().contains("\"status\":\"error\""), "An unknown request is answered with an error");

        // Connected, but the connect listeners have not run yet.
        bridge.tick();
        check(mqtt.take().isEmpty(), "No telemetry before the connect publishes it");
        bridge.publishTelemetry();
        Outbound telemetry = mqtt.single();
        check(telemetry.topic().equals("mineqtt/world_7f3a/bridge") && telemetry.retain()
                && telemetry.text().equals("{\"players_online\":2,\"dropped_inbound\":0,\"dropped_outbound\":0}"), "Telemetry: " + telemetry.text());
        players.set(3);
        clock.addAndGet(5_000);
        bridge.tick();
        check(mqtt.take().isEmpty(), "A change waits for the 10 s interval");
        clock.addAndGet(BridgeDevice.TELEMETRY_INTERVAL_MILLIS - 5_000);
        bridge.tick();
        check(mqtt.single().text().contains("\"players_online\":3"), "then it is published");
        clock.addAndGet(BridgeDevice.TELEMETRY_INTERVAL_MILLIS);
        bridge.tick();
        check(mqtt.take().isEmpty(), "Nothing unchanged is published");
        bridge.stop();
        check(mqtt.handlers.isEmpty(), "Stopping unsubscribes");
    }

    /** Design §4.5: which configs the ghost cleanup clears, as the pure decision. */
    private static void ghostVerdicts() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord lamp = fixture.lamp("kitchen_lamp");
        DeviceGroup group = fixture.registry.createGroup(UUID.randomUUID(), "Hallway");
        String bridge = DiscoveryGoldenTest.IDENTITY.bridgeObjectId();
        String ghost = config(DeviceRecord.objectId(UUID.randomUUID()));
        String ours = ghostConfig("dev", "\"" + bridge + "\"", 0);

        check(GhostCleanup.deviceId(PREFIX, config(lamp.objectId())).equals(lamp.id()), "The device id is read back from the object id");
        check(verdict(fixture, ghost, ours, true) == GhostCleanup.Verdict.GHOST, "This bridge's config of an unknown id is a ghost");
        check(verdict(fixture, ghost, ghostConfig("device", "\"" + bridge + "\"", 0), true) == GhostCleanup.Verdict.GHOST,
                "also with the device block spelled out");
        check(verdict(fixture, ghost, ours, false) == GhostCleanup.Verdict.LIVE, "A live message is no ghost");
        check(verdict(fixture, config(lamp.objectId()), ours, true) == GhostCleanup.Verdict.KNOWN, "A device of the registry is kept");
        check(verdict(fixture, config(group.objectId()), ours, true) == GhostCleanup.Verdict.KNOWN, "A group of the registry is kept");
        String otherBridge = "\"" + BridgeDevice.objectId(UUID.randomUUID()) + "\"";
        check(verdict(fixture, ghost, ghostConfig("dev", otherBridge, 0), true) == GhostCleanup.Verdict.OTHER_BRIDGE, "Another bridge's device is kept");
        check(verdict(fixture, ghost, "{\"dev\":{\"name\":\"Lamp\"}}", true) == GhostCleanup.Verdict.OTHER_BRIDGE, "A device without via_device is kept");
        check(verdict(fixture, ghost, ghostConfig("dev", "7", 0), true) == GhostCleanup.Verdict.OTHER_BRIDGE, "A via_device that is no string is kept");
        check(verdict(fixture, ghost, "{\"dev\":\"" + bridge + "\"}", true) == GhostCleanup.Verdict.OTHER_BRIDGE, "A device block that is no object is kept");
        check(verdict(fixture, ghost, "{\"dev\":", true) == GhostCleanup.Verdict.MALFORMED, "Broken JSON is kept");
        check(verdict(fixture, ghost, "[\"" + bridge + "\"]", true) == GhostCleanup.Verdict.MALFORMED, "JSON that is no object is kept");
        String largest = ghostConfig("dev", "\"" + bridge + "\"", GhostCleanup.MAX_CONFIG_BYTES);
        check(largest.length() == GhostCleanup.MAX_CONFIG_BYTES && verdict(fixture, ghost, largest, true) == GhostCleanup.Verdict.GHOST,
                "A config of exactly 16 KiB is judged");
        check(verdict(fixture, ghost, ghostConfig("dev", "\"" + bridge + "\"", GhostCleanup.MAX_CONFIG_BYTES + 1), true) == GhostCleanup.Verdict.TOO_LARGE,
                "One byte more is skipped before parsing");
        String hex = lamp.objectId().substring("mineqtt_".length());
        for (String foreign : List.of(PREFIX + "/device/zigbee_" + hex + "/config", config(bridge), config("mineqtt_" + hex.toUpperCase()),
                config("mineqtt_" + hex.substring(1)), config("mineqtt_" + hex + "0"), config("mineqtt_" + hex.substring(1) + "g"),
                "ha2/device/mineqtt_" + hex + "/config", PREFIX + "/light/mineqtt_" + hex + "/config", PREFIX + "/device/mineqtt_" + hex + "/state")) {
            check(verdict(fixture, foreign, ours, true) == GhostCleanup.Verdict.FOREIGN, "Not a MineQTT device config: " + foreign);
        }
    }

    /** The scan after a connect: its subscription and window, the clears and their rate limit, reconnects and stop. */
    private static void ghostScan() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        FakeMqtt mqtt = fixture.mqtt;
        DeviceRecord lamp = fixture.lamp("kitchen_lamp");
        String bridge = DiscoveryGoldenTest.IDENTITY.bridgeObjectId();
        String ours = ghostConfig("dev", "\"" + bridge + "\"", 0);
        AtomicReference<String> prefix = new AtomicReference<>(PREFIX);
        AtomicBoolean tokens = new AtomicBoolean(true);
        List<String> logged = new ArrayList<>();
        GhostCleanup cleanup = new GhostCleanup(mqtt, prefix::get, bridge, fixture.registry::contains, tokens::get, fixture.clock::get, recording(logged));
        check(!cleanup.pending() && mqtt.handlers.isEmpty(), "Nothing is scanned before a connect");

        cleanup.connected();
        String filter = PREFIX + "/device/+/config";
        SubOptions scan = mqtt.options.get(filter);
        check(mqtt.handlers.keySet().equals(Set.of(filter)) && cleanup.pending(), "A connect subscribes to every device config");
        check(scan.acceptRetained() && !scan.noLocal() && scan.qos() == 0 && scan.maxPayloadBytes() > GhostCleanup.MAX_CONFIG_BYTES,
                "The scan takes retained messages at QoS 0, also its own client id's, and caps the size itself: " + scan);
        String ghost = config(DeviceRecord.objectId(UUID.randomUUID()));
        String otherBridge = config(DeviceRecord.objectId(UUID.randomUUID()));
        mqtt.deliver(ghost, ours, true);
        mqtt.deliver(ghost, ours, true);
        mqtt.deliver(config(lamp.objectId()), ours, true);
        mqtt.deliver(otherBridge, ghostConfig("dev", "\"" + BridgeDevice.objectId(UUID.randomUUID()) + "\"", 0), true);
        mqtt.deliver(config(DeviceRecord.objectId(UUID.randomUUID())), ours, false);
        tokens.set(false);
        cleanup.tick();
        check(mqtt.take().isEmpty() && cleanup.pending(), "A clear waits for the discovery rate limit");
        tokens.set(true);
        cleanup.tick();
        Outbound clear = mqtt.single();
        check(clear.topic().equals(ghost) && clear.retain() && clear.payload().length == 0 && clear.offline() == OfflinePolicy.KEEP,
                "Only the ghost is cleared, once, with an empty retained message: " + clear.topic());
        check(logged.stream().filter(line -> line.contains(ghost)).count() == 1, "The ghost is logged once: " + logged);

        UUID adopted = UUID.randomUUID();
        mqtt.deliver(config(DeviceRecord.objectId(adopted)), ours, true);
        tokens.set(false);
        cleanup.tick();
        fixture.registry.add(new DeviceRecord(adopted, fixture.lampType.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(nextX++, 64, 0)), "Porch", "porch"));
        tokens.set(true);
        cleanup.tick();
        check(mqtt.take().isEmpty(), "A ghost whose id became a device before its clear is left alone");

        fixture.clock.addAndGet(GhostCleanup.WINDOW_MILLIS - 1);
        cleanup.tick();
        check(cleanup.pending() && !mqtt.handlers.isEmpty(), "The scan listens for 5 s");
        fixture.clock.incrementAndGet();
        cleanup.tick();
        check(!cleanup.pending() && mqtt.handlers.isEmpty(), "then it unsubscribes");
        check(logged.getLast().equals("Home Assistant ghost cleanup: 6 discovery configs looked at, 2 ghosts found"), "The scan is summed up: " + logged.getLast());

        cleanup.connected();
        fixture.clock.addAndGet(4_000);
        cleanup.connected();
        fixture.clock.addAndGet(4_000);
        cleanup.tick();
        check(cleanup.pending() && mqtt.handlers.size() == 1, "A connect during a scan restarts its window on the same subscription");
        mqtt.deliver(config(DeviceRecord.objectId(UUID.randomUUID())), ours, true);
        mqtt.state = ConnectionState.reconnecting("connection lost");
        cleanup.tick();
        check(!cleanup.pending() && mqtt.handlers.isEmpty(), "A lost connection ends the scan");
        mqtt.state = ConnectionState.CONNECTED;
        cleanup.tick();
        check(mqtt.take().isEmpty(), "and drops its clears; the next connect finds the ghost again");

        prefix.set("ha2");
        cleanup.connected();
        cleanup.connected();
        check(mqtt.handlers.keySet().equals(Set.of("ha2/device/+/config")), "A connect scans under the prefix of that moment");
        String moved = "ha2/device/" + DeviceRecord.objectId(UUID.randomUUID()) + "/config";
        mqtt.deliver(moved, ours, true);
        cleanup.tick();
        check(mqtt.single().topic().equals(moved), "and judges the topics under it");
        prefix.set(PREFIX);
        cleanup.connected();
        check(mqtt.handlers.keySet().equals(Set.of(filter)), "A changed prefix moves a running scan");

        mqtt.deliver(config(DeviceRecord.objectId(UUID.randomUUID())), ours, true);
        cleanup.stop();
        cleanup.tick();
        check(!cleanup.pending() && mqtt.handlers.isEmpty() && mqtt.take().isEmpty(), "Stopping unsubscribes and clears nothing more");
        cleanup.connected();
        check(mqtt.handlers.isEmpty(), "A stopped cleanup never scans again");
    }

    private static GhostCleanup.Verdict verdict(Fixture fixture, String topic, String payload, boolean retained) {
        Inbound message = new Inbound(topic, payload.getBytes(StandardCharsets.UTF_8), retained, false, null, 0);
        return GhostCleanup.judge(PREFIX, message, DiscoveryGoldenTest.IDENTITY.bridgeObjectId(), fixture.registry::contains);
    }

    /** A device config whose via_device is the JSON {@code via}, padded to {@code size} characters when not 0. */
    private static String ghostConfig(String deviceKey, String via, int size) {
        String config = "{\"" + deviceKey + "\":{\"name\":\"Lamp\",\"via_device\":" + via + "},\"padding\":\"";
        return config + "x".repeat(Math.max(0, size - config.length() - 2)) + "\"}";
    }

    private static MqttLog recording(List<String> lines) {
        return new MqttLog() {
            @Override
            public void debug(String message) {
                lines.add(message);
            }

            @Override
            public void info(String message) {
                lines.add(message);
            }

            @Override
            public void warn(String message, Throwable cause) {
                lines.add(message);
            }

            @Override
            public void error(String message, Throwable cause) {
                lines.add(message);
            }
        };
    }

    private static String config(String objectId) {
        return PREFIX + "/device/" + objectId + "/config";
    }

    private static List<String> topics(List<Outbound> messages) {
        return messages.stream().map(Outbound::topic).toList();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static final class Fixture {
        final FakeMqtt mqtt = new FakeMqtt();
        final AtomicLong clock = new AtomicLong(1_000);
        final DeviceRegistry registry = new DeviceRegistry("world_7f3a", DiscoveryGoldenTest.WORLD);
        final DiscoveryPublisher publisher;
        final DeviceType lampType = SmartLamp.TYPE;

        Fixture(HaExpose expose) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.haExpose = expose;
            publisher = new DiscoveryPublisher(registry, mqtt, () -> DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY, true, clock::get);
        }

        DeviceRecord lamp(String slug) {
            DeviceRecord record = new DeviceRecord(UUID.randomUUID(), lampType.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(nextX++, 64, 0)), slug, slug);
            registry.add(record);
            return record;
        }
    }

    /** Records publishes and routes delivered messages to the matching handlers, like the real service would. */
    private static final class FakeMqtt implements MqttService {
        final List<Outbound> published = new ArrayList<>();
        final Map<String, InboundHandler> handlers = new LinkedHashMap<>();
        // The options of the latest subscription per filter.
        final Map<String, SubOptions> options = new LinkedHashMap<>();
        ConnectionState state = ConnectionState.CONNECTED;
        private long seq;

        List<Outbound> take() {
            List<Outbound> taken = List.copyOf(published);
            published.clear();
            return taken;
        }

        Outbound single() {
            List<Outbound> taken = take();
            check(taken.size() == 1, "Expected one message, got " + topics(taken));
            return taken.getFirst();
        }

        void deliver(String topic, String payload) {
            deliver(topic, payload, false);
        }

        /** A message as the broker sends it; {@code retained}: a stored one, sent on SUBSCRIBE. */
        void deliver(String topic, String payload, boolean retained) {
            Inbound message = new Inbound(topic, payload.getBytes(StandardCharsets.UTF_8), retained, false, null, seq++);
            List.copyOf(handlers.entrySet()).stream().filter(entry -> TopicFilters.matches(entry.getKey(), topic))
                    .forEach(entry -> entry.getValue().handle(message));
        }

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public void publish(Outbound msg) {
            published.add(msg);
        }

        @Override
        public Subscription subscribe(String filter, SubOptions opts, InboundHandler handler) {
            handlers.put(filter, handler);
            options.put(filter, opts);
            return new Subscription() {
                @Override
                public String filter() {
                    return filter;
                }

                @Override
                public void close() {
                    handlers.remove(filter);
                }
            };
        }

        @Override
        public void onConnected(Runnable listener) {
        }

        @Override
        public void onInboundAvailable(Runnable listener) {
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
        }
    }
}

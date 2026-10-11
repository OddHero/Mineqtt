package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.mqtt.core.BrokerSettings;
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
import art.rehra.mineqtt.twin.HaEntityCatalog;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.CatalogParser;
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
 * its acknowledgements, the v1 status clear, a changed prefix, Home Assistant's birth and its listeners, the twin's
 * catalog subscriptions (M3-11), the discovery cleanups of bound and unbound devices (M3-7b), the bridge's requests and
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
        birthListeners();
        catalogSubscriptions();
        cleanupWhileOnline();
        cleanupAfterBirth();
        cleanupProvenByClear();
        unbindCleanup();
        cleanupEdges();
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
        // M3-7b: what proves Home Assistant online for a binding change (MineqttRuntime.haOnline).
        BirthWatcher.Heard birth = watcher.heard();
        check(birth.provesOnline(true, 1_010, false) && birth.provesOnline(true, 500, false), "A birth on the current connection, nothing pending, proves it online");
        check(!birth.provesOnline(false, 1_010, false), "Not while the connection is down: Home Assistant and its broker may be restarting unheard");
        check(!birth.provesOnline(true, 1_011, false) && !birth.provesOnline(true, Long.MAX_VALUE, false), "Not a birth of an earlier connection, nor before the first");
        check(!birth.provesOnline(true, 1_010, true), "Not while the republish it asked for is still due");
        check(!BirthWatcher.Heard.NOTHING.provesOnline(true, 0, false) && !new BirthWatcher.Heard(BirthWatcher.Status.WILL, 2_000).provesOnline(true, 0, false),
                "Nothing heard, or its will, proves nothing");

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

    // M3-11: listeners hear every live birth at once (before the republish's delay), never a retained one or the will.
    private static void birthListeners() {
        FakeMqtt mqtt = new FakeMqtt();
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger republished = new AtomicInteger();
        AtomicInteger heard = new AtomicInteger();
        BirthWatcher watcher = new BirthWatcher(mqtt, republished::incrementAndGet, clock::get, new Random(5));
        watcher.onBirth(heard::incrementAndGet);
        mqtt.deliver("homeassistant/status", "online", true);
        mqtt.deliver("homeassistant/status", "offline");
        check(heard.get() == 0, "A retained birth and the last will are not births");
        mqtt.deliver("homeassistant/status", "online");
        check(heard.get() == 1 && republished.get() == 0 && watcher.pending(), "A live birth is heard at once; the republish waits for its delay");
        mqtt.deliver("homeassistant/status", "online");
        check(heard.get() == 2, "A birth folded into the pending republish is heard too");
        watcher.stop();
    }

    /**
     * M3-11 (D4): the catalog's own SUBSCRIBE 5 s after the first CONNACK, the scan's 10 s after it while no status came,
     * the scan closed by a status and opened again when it is cleared, the ACL asked once per setup, nothing while the
     * twin is off, a reload setting both up again, a reconnect rescheduling nothing, and stop closing everything.
     */
    private static void catalogSubscriptions() {
        FakeMqtt mqtt = new FakeMqtt();
        boolean[] twinOn = {true};
        boolean[] catalogMode = {false};
        boolean[] mayMirror = {true};
        AtomicInteger asked = new AtomicInteger();
        List<String> sunk = new ArrayList<>();
        HaEntityCatalog catalog = new HaEntityCatalog(mqtt, new HaEntityCatalog.Host() {
            @Override
            public String base() {
                return "ha_statestream";
            }

            @Override
            public boolean twinOn() {
                return twinOn[0];
            }

            @Override
            public boolean catalogEnabled() {
                return true;
            }

            @Override
            public boolean catalogMode() {
                return catalogMode[0];
            }

            @Override
            public int maxEntities() {
                return 2;
            }

            @Override
            public boolean mayMirror(String filter) {
                asked.incrementAndGet();
                return mayMirror[0];
            }
        }, message -> sunk.add(message.topic()));
        String catalogFilter = "ha_statestream/_mineqtt/catalog/+";
        String statusTopic = "ha_statestream/_mineqtt/status";
        Set<String> scanFilters = Set.of("ha_statestream/+/+/state", "ha_statestream/+/+/friendly_name");

        catalog.tick(100_000);
        check(mqtt.handlers.isEmpty() && !catalog.pending(), "Nothing before the first CONNACK");
        catalog.connected(1_000);
        catalog.tick(5_999);
        check(mqtt.handlers.isEmpty() && catalog.pending(), "The catalog waits 5 s after the CONNACK, as pending work");
        catalog.tick(6_000);
        check(mqtt.handlers.keySet().equals(Set.of(catalogFilter, statusTopic)) && catalog.catalogOpen(), "Then its own SUBSCRIBE: " + mqtt.handlers.keySet());
        SubOptions options = mqtt.options.get(catalogFilter);
        check(options.acceptRetained() && options.maxPayloadBytes() > CatalogParser.MAX_TOPIC_BYTES,
                "Retained lists are taken, larger than the cap so the parser refuses them visibly");
        // The service drops anything over the broker-wide inbound limit before a handler's own cap is looked at (and on
        // MQTT 5 the broker withholds it): that limit must let the catalog's cap through, or the refusal never happens.
        check(BrokerSettings.DEFAULT_MAX_INBOUND_BYTES >= options.maxPayloadBytes() && options.maxPayloadBytes() == HaEntityCatalog.CATALOG_MAX_BYTES,
                "The broker-wide inbound limit (" + BrokerSettings.DEFAULT_MAX_INBOUND_BYTES + ") lets the catalog's " + options.maxPayloadBytes() + " bytes through");
        mqtt.deliver("ha_statestream/_mineqtt/catalog/light", "[]", true);
        check(sunk.equals(List.of("ha_statestream/_mineqtt/catalog/light")), "Catalog messages go to the documents");
        catalog.tick(10_999);
        check(!catalog.scanOpen(), "The scan waits 10 s");
        catalog.tick(11_000);
        check(catalog.scanOpen() && mqtt.handlers.keySet().containsAll(scanFilters), "No status by then: the scan opens");
        mqtt.deliver("ha_statestream/light/kitchen/state", "on", true);
        mqtt.deliver("ha_statestream/switch/a/state", "on", true);
        mqtt.deliver("ha_statestream/switch/b/state", "on", true);
        check(catalog.scan().size() == 2 && catalog.scan().overflow() == 1, "The scan is capped at twin.catalogMax");
        catalogMode[0] = true;
        catalog.tick(12_000);
        check(!catalog.scanOpen() && catalog.scan().size() == 0 && mqtt.handlers.keySet().equals(Set.of(catalogFilter, statusTopic)),
                "A status closes the scan");
        catalogMode[0] = false;
        catalog.tick(13_000);
        check(catalog.scanOpen(), "A cleared status opens it again");
        catalog.connected(20_000);
        catalog.tick(20_000);
        check(catalog.catalogOpen() && catalog.scanOpen() && mqtt.handlers.size() == 4, "A reconnect reschedules nothing; the subscriptions stay");

        twinOn[0] = false;
        catalog.configChanged();
        check(mqtt.handlers.isEmpty(), "A reload closes both");
        catalog.tick(21_000);
        check(mqtt.handlers.isEmpty() && !catalog.pending(), "...and an off twin subscribes nothing, and has nothing pending");
        twinOn[0] = true;
        mayMirror[0] = false;
        catalog.configChanged();
        int before = asked.get();
        catalog.tick(22_000);
        catalog.tick(23_000);
        check(mqtt.handlers.isEmpty() && asked.get() - before == 4, "A filter the ACL refuses is asked once per setup, never subscribed: " + (asked.get() - before));
        mayMirror[0] = true;
        catalog.configChanged();
        catalog.tick(24_000);
        check(catalog.catalogOpen() && catalog.scanOpen(), "A reload sets both up again at once, past their delays");
        catalog.stop();
        check(mqtt.handlers.isEmpty(), "Stopping closes everything");
    }

    // ---- cleanups (M3-7b) ----

    /**
     * Bound while Home Assistant is online: the reduced message at once, no mark. Home Assistant's clear of the config
     * (once per removed entity) is answered with the reduced message after the last of them, while the watch lasts; an
     * ordinary republish afterwards sends it once.
     */
    private static void cleanupWhileOnline() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        fixture.haOnline.set(true);
        DeviceRecord lamp = discovered(fixture, "porch_lamp");
        String topic = config(lamp.objectId());
        bind(lamp);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        Outbound reduced = fixture.mqtt.single();
        JsonObject expected = DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        check(reduced.topic().equals(topic) && reduced.retain() && reduced.refresh() && json(reduced).equals(expected),
                "Bound while Home Assistant is online: the reduced message at once, as a refresh");
        check(!lamp.discoveryCleanup() && DiscoveryPublisher.hash(expected).equals(lamp.discoveryHash()), "...with no mark, and its hash retained");
        SubOptions watch = fixture.mqtt.options.get(topic);
        check(fixture.mqtt.handlers.containsKey(topic) && watch != null && !watch.acceptRetained() && watch.noLocal(),
                "The device's own config topic is watched, live messages only");

        fixture.mqtt.deliver(topic, reduced.text());
        fixture.mqtt.deliver(topic, "", true);
        check(fixture.after(2 * DiscoveryPublisher.RESTORE_DELAY_MILLIS).isEmpty(), "Its own message and a stored one change nothing");
        fixture.mqtt.deliver(topic, "");
        fixture.clock.addAndGet(DiscoveryPublisher.RESTORE_DELAY_MILLIS / 2);
        fixture.mqtt.deliver(topic, "");
        check(fixture.after(DiscoveryPublisher.RESTORE_DELAY_MILLIS - 1).isEmpty(), "The republish waits for Home Assistant's last clear");
        List<Outbound> restored = fixture.after(1);
        check(restored.size() == 1 && restored.getFirst().refresh() && json(restored.getFirst()).equals(expected),
                "Home Assistant's clear is answered with the reduced message again, a refresh, got " + topics(restored));
        check(fixture.publisher.hasPendingWork(), "A watch is pending work, so a paused server pumps it");

        check(fixture.after(DiscoveryPublisher.WATCH_MILLIS).isEmpty() && !fixture.mqtt.handlers.containsKey(topic) && !fixture.publisher.hasPendingWork(),
                "The watch ends after its time");
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        List<Outbound> again = fixture.mqtt.take();
        check(again.size() == 2 && json(again.get(1)).equals(expected), "An ordinary republish sends the reduced message once: no churn, got " + topics(again));
    }

    /**
     * Bound while Home Assistant is not known to be online: the reduced message and a mark. No clear comes, so the mark
     * survives the watch and a republish of a connect. The republish of Home Assistant's birth sends the full device with
     * its binding sensor, then the removal, which ends the mark; the next birth churns nothing.
     */
    private static void cleanupAfterBirth() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord lamp = discovered(fixture, "hall_lamp");
        String topic = config(lamp.objectId());
        bind(lamp);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        JsonObject reduced = DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        check(json(fixture.mqtt.single()).equals(reduced) && lamp.discoveryCleanup(),
                "Bound while Home Assistant is not known to be online: the reduced message and a mark");
        check(fixture.after(DiscoveryPublisher.WATCH_MILLIS).isEmpty() && !fixture.mqtt.handlers.containsKey(topic) && lamp.discoveryCleanup(),
                "No clear came: the watch ends, the mark stays");

        fixture.publisher.republishAll();
        fixture.publisher.tick();
        List<Outbound> connect = fixture.mqtt.take();
        check(connect.size() == 2 && json(connect.get(1)).equals(reduced) && lamp.discoveryCleanup(),
                "A republish while Home Assistant is not online is the plain one, got " + topics(connect));

        fixture.haOnline.set(true);
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        List<Outbound> birth = fixture.mqtt.take();
        JsonObject discover = DiscoveryPublisher.discoverConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        check(birth.size() == 2 && birth.get(1).topic().equals(topic) && birth.get(1).refresh() && json(birth.get(1)).equals(discover),
                "Home Assistant's birth: the full device with its binding sensor first, got " + topics(birth));
        check(lamp.discoveryCleanup(), "...the mark stays until the removal is out");
        fixture.publisher.refresh(lamp);
        check(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS - 1).isEmpty(), "The removal, and a refresh meanwhile, wait for the components' set-up");
        List<Outbound> removal = fixture.after(1);
        check(removal.size() == 1 && removal.getFirst().refresh() && json(removal.getFirst()).equals(reduced) && !lamp.discoveryCleanup(),
                "Then the reduced message removes them in a run that discovered them, and the mark is done, got " + topics(removal));
        check(DiscoveryPublisher.hash(reduced).equals(lamp.discoveryHash()) && fixture.mqtt.handlers.containsKey(topic), "...its hash retained, its topic watched");
        fixture.mqtt.deliver(topic, "");
        List<Outbound> restored = fixture.after(DiscoveryPublisher.RESTORE_DELAY_MILLIS);
        check(restored.size() == 1 && json(restored.getFirst()).equals(reduced), "Home Assistant's clear after the removal is answered");

        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        List<Outbound> next = fixture.mqtt.take();
        check(next.size() == 2 && json(next.get(1)).equals(reduced) && fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty(),
                "The next birth sends the reduced message once: no churn, got " + topics(next));
    }

    /**
     * Home Assistant's clear proves that it removed the entities, so a mark set while it was not known to be online ends
     * with it. A mark still set when the binding changes while Home Assistant is online runs the whole cleanup at once.
     */
    private static void cleanupProvenByClear() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord lamp = discovered(fixture, "stair_lamp");
        String topic = config(lamp.objectId());
        bind(lamp);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        JsonObject reduced = json(fixture.mqtt.single());
        check(lamp.discoveryCleanup(), "Marked while Home Assistant is not known to be online");
        fixture.mqtt.deliver(topic, "");
        check(!lamp.discoveryCleanup(), "Home Assistant's clear proves it removed the entities: the mark is done");
        check(json(fixture.after(DiscoveryPublisher.RESTORE_DELAY_MILLIS).getFirst()).equals(reduced), "...and the reduced message goes out again");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);

        lamp.setDiscoveryCleanup(true);
        fixture.haOnline.set(true);
        lamp.setTwin(null);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        JsonObject discover = DiscoveryPublisher.discoverConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        check(json(fixture.mqtt.single()).equals(discover), "A marked device changed while Home Assistant is online starts with the full message");
        List<Outbound> removal = fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        JsonObject expectedRemoval = DiscoveryPublisher.removalConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        check(removal.size() == 1 && json(removal.getFirst()).equals(expectedRemoval) && !lamp.discoveryCleanup(), "...then the removal, which ends the mark");
        List<Outbound> steady = fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        check(steady.size() == 1 && steady.getFirst().refresh()
                && json(steady.getFirst()).equals(DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY)),
                "...then the full message alone");
    }

    /**
     * Unbinding: the full message with a stub for the binding sensor, so no orphan stays, then the full message alone; a
     * clear from Home Assistant in between brings the full message sooner. While Home Assistant is not known to be online
     * the record is marked, and its birth runs the three messages.
     */
    private static void unbindCleanup() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        fixture.haOnline.set(true);
        DeviceRecord lamp = discovered(fixture, "attic_lamp");
        String topic = config(lamp.objectId());
        bind(lamp);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        fixture.mqtt.take();

        lamp.setTwin(null);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        JsonObject full = DiscoveryPublisher.deviceConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        JsonObject removal = DiscoveryPublisher.removalConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        check(json(fixture.mqtt.single()).equals(removal) && !lamp.discoveryCleanup(), "Unbound while Home Assistant is online: the full message with the binding stub");
        check(fixture.mqtt.handlers.containsKey(topic), "...watched, since the stub removes the binding sensor");
        check(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS - 1).isEmpty(), "The full message alone waits");
        List<Outbound> steady = fixture.after(1);
        check(steady.size() == 1 && json(steady.getFirst()).equals(full) && DiscoveryPublisher.hash(full).equals(lamp.discoveryHash()),
                "...then follows with the same hash as before the binding");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);

        bind(lamp);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        fixture.mqtt.take();
        lamp.setTwin(null);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        fixture.mqtt.take();
        fixture.mqtt.deliver(topic, "");
        List<Outbound> sooner = fixture.after(DiscoveryPublisher.RESTORE_DELAY_MILLIS);
        check(sooner.size() == 1 && json(sooner.getFirst()).equals(full), "A clear after the stub brings the full message sooner");
        check(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty(), "...and only once");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);

        bind(lamp);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        fixture.mqtt.take();
        fixture.haOnline.set(false);
        lamp.setTwin(null);
        fixture.publisher.bindingChanged(lamp);
        fixture.publisher.tick();
        check(json(fixture.mqtt.single()).equals(removal) && lamp.discoveryCleanup(), "Unbound while Home Assistant is not known online: the stub and a mark");
        check(json(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).getFirst()).equals(full), "...then the full message");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        fixture.haOnline.set(true);
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        List<Outbound> birth = fixture.mqtt.take();
        check(birth.size() == 2 && json(birth.get(1)).equals(DiscoveryPublisher.discoverConfig(SmartLamp.TYPE, lamp, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY)),
                "The birth discovers the binding sensor again");
        check(json(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).getFirst()).equals(removal) && !lamp.discoveryCleanup(), "...removes it in that run");
        check(json(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).getFirst()).equals(full), "...and ends with the full message");
    }

    /**
     * What a cleanup does not touch or survive: a device never discovered or hidden, an own clear during the watch, a
     * removed device, a discovery clear, a rename (the step runs at once), the broker gone (the steps wait) and a v1
     * migration still pending.
     */
    private static void cleanupEdges() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        DeviceRecord fresh = fixture.lamp("fresh_lamp");
        bind(fresh);
        fixture.publisher.bindingChanged(fresh);
        fixture.publisher.tick();
        Outbound first = fixture.mqtt.single();
        check(!first.refresh() && !fresh.discoveryCleanup() && !fixture.mqtt.handlers.containsKey(config(fresh.objectId())),
                "A device Home Assistant never got is discovered plainly: nothing to remove, no mark, no watch");

        fixture.haOnline.set(true);
        DeviceRecord hidden = discovered(fixture, "hidden_lamp");
        bind(hidden);
        fixture.publisher.bindingChanged(hidden);
        fixture.publisher.tick();
        fixture.mqtt.take();
        hidden.setExpose(false);
        fixture.publisher.refresh(hidden);
        fixture.publisher.tick();
        check(fixture.mqtt.single().payload().length == 0 && hidden.discoveryHash() == null, "Hidden during the watch: its config is cleared");
        fixture.mqtt.deliver(config(hidden.objectId()), "");
        check(fixture.after(DiscoveryPublisher.RESTORE_DELAY_MILLIS).isEmpty(), "Its own clear coming back is no reason to publish it");
        hidden.setTwin(null);
        fixture.publisher.bindingChanged(hidden);
        fixture.publisher.tick();
        check(fixture.mqtt.take().isEmpty() && !hidden.discoveryCleanup(), "A hidden device's binding changes nothing in Home Assistant");

        fixture.haOnline.set(false);
        DeviceRecord gone = discovered(fixture, "gone_lamp");
        bind(gone);
        fixture.publisher.bindingChanged(gone);
        fixture.publisher.tick();
        fixture.mqtt.take();
        fixture.haOnline.set(true);
        fixture.publisher.republishAll();
        fixture.publisher.tick();
        fixture.mqtt.take();
        fixture.publisher.removed(gone);
        check(fixture.mqtt.single().payload().length == 0 && !fixture.mqtt.handlers.containsKey(config(gone.objectId())), "A removed device ends its cleanup");
        check(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty(), "...so no step follows");

        DeviceRecord renamed = discovered(fixture, "renamed_lamp");
        renamed.setDiscoveryCleanup(true);
        bind(renamed);
        fixture.publisher.bindingChanged(renamed);
        fixture.publisher.tick();
        fixture.mqtt.take();
        fixture.registry.rename(renamed, "Renamed Lamp", "renamed_lamp_2");
        fixture.publisher.publishNow(renamed);
        Outbound now = fixture.mqtt.single();
        check(json(now).equals(DiscoveryPublisher.removalConfig(SmartLamp.TYPE, renamed, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY))
                && now.text().contains("renamed_lamp_2"), "A rename takes the cleanup's next step at once, on the new topics");

        DeviceRecord waiting = discovered(fixture, "waiting_lamp");
        waiting.setDiscoveryCleanup(true);
        bind(waiting);
        fixture.publisher.bindingChanged(waiting);
        fixture.publisher.tick();
        fixture.mqtt.take();
        fixture.mqtt.state = ConnectionState.reconnecting("check");
        check(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty(), "Without the broker the next step waits");
        fixture.mqtt.state = ConnectionState.CONNECTED;
        check(json(fixture.after(0).getFirst()).equals(DiscoveryPublisher.removalConfig(SmartLamp.TYPE, waiting, DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY)),
                "...and runs once it is back");

        fixture.publisher.clearAll();
        fixture.mqtt.take();
        check(fixture.mqtt.handlers.keySet().stream().noneMatch(filter -> filter.startsWith(PREFIX + "/device/"))
                && fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty(), "A discovery clear ends every cleanup and its watch");

        DeviceRecord legacy = fixture.lamp("legacy_lamp");
        legacy.setLegacyUniqueId("home_legacy");
        legacy.setDiscoveryHash("old");
        bind(legacy);
        fixture.publisher.bindingChanged(legacy);
        fixture.publisher.tick();
        check(!legacy.discoveryCleanup() && !fixture.mqtt.handlers.containsKey(config(legacy.objectId())), "A device whose v1 migration is pending is left to it");
    }

    // A lamp the publisher discovered in full.
    private static DeviceRecord discovered(Fixture fixture, String slug) {
        DeviceRecord lamp = fixture.lamp(slug);
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        fixture.mqtt.take();
        check(lamp.discoveryHash() != null, "Discovered: " + slug);
        return lamp;
    }

    private static void bind(DeviceRecord lamp) {
        lamp.setTwin(Binding.of(SmartLamp.TWIN.sourceEnd("light.m3_lamp")));
    }

    private static JsonObject json(Outbound message) {
        return JsonParser.parseString(message.text()).getAsJsonObject();
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
        final AtomicBoolean haOnline = new AtomicBoolean();
        final DeviceRegistry registry = new DeviceRegistry("world_7f3a", DiscoveryGoldenTest.WORLD);
        final DiscoveryPublisher publisher;
        final DeviceType lampType = SmartLamp.TYPE;

        Fixture(HaExpose expose) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.haExpose = expose;
            publisher = new DiscoveryPublisher(registry, mqtt, () -> DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY, true, clock::get, haOnline::get);
        }

        /** Advances the clock and ticks, returning what was published. */
        List<Outbound> after(long millis) {
            clock.addAndGet(millis);
            publisher.tick();
            return mqtt.take();
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

package art.rehra.mineqtt.ha;

import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.StaticDefaults;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.DeviceView;
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
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.rooms.RoomIndex;
import art.rehra.mineqtt.telemetry.core.NamePolicy;
import art.rehra.mineqtt.telemetry.core.PlayerEventMessage;
import art.rehra.mineqtt.telemetry.core.TelemetryThrottle;
import art.rehra.mineqtt.telemetry.core.WorldReading;
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
import net.minecraft.util.Util;
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
    // Where the ghost checks' bridge keeps its presence states, TopicScheme.playerRoot.
    private static final String PLAYER_ROOT = "mineqtt/world_7f3a/player";
    private static int nextX;

    public static void main(String[] args) {
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
        objectRemovals();
        objectRemovalAfterBirth();
        objectReAdded();
        bridgeRemoval();
        broadcastEntity();
        dynamicOptions();
        rooms();
        bridgeRequestsAndTelemetry();
        ghostVerdicts();
        ghostScan();
        playerGhosts();
        presenceStateGhosts();
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
    // A presence-like object (M4 plan D10): a message this publisher does not build from a record, and its full sensors.
    private static final String PLAYER = "mineqtt_player_0123456789abcdef0123456789abcdef";

    private static JsonObject playerConfig(boolean full) {
        JsonObject components = new JsonObject();
        components.add("presence", JsonParser.parseString("{\"p\":\"binary_sensor\",\"unique_id\":\"" + PLAYER + "_presence\",\"name\":null}"));
        if (full) components.add("health", healthSensor());
        JsonObject config = JsonParser.parseString("{\"dev\":{\"ids\":[\"" + PLAYER + "\"],\"name\":\"Player Steve\"},\"o\":{\"name\":\"MineQTT\"}}").getAsJsonObject();
        config.add("cmps", components);
        return config;
    }

    private static JsonObject healthSensor() {
        return JsonParser.parseString("{\"p\":\"sensor\",\"unique_id\":\"" + PLAYER + "_health\",\"name\":\"Health\"}").getAsJsonObject();
    }

    /**
     * M4 plan §3.4 (M4-1 seam): cleanups are kept by object id, so an object whose message this publisher does not build
     * drops components as a device drops its binding: with Home Assistant online the message with the components as stubs,
     * watched, then the message alone; Home Assistant's clear brings it sooner; an object that is not discovered any more
     * ends its removal without a message; nothing waits once the watch is over.
     */
    private static void objectRemovals() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        fixture.haOnline.set(true);
        AtomicReference<java.util.Optional<JsonObject>> current = new AtomicReference<>(java.util.Optional.of(playerConfig(false)));
        fixture.publisher.componentsRemoved(PLAYER, Map.of("health", healthSensor()), current::get);
        check(fixture.publisher.hasPendingWork(), "A removal is work for the next tick");
        fixture.publisher.tick();
        Outbound removal = fixture.mqtt.single();
        JsonObject expected = playerConfig(false);
        expected.getAsJsonObject("cmps").add("health", JsonParser.parseString("{\"p\":\"sensor\"}"));
        check(removal.topic().equals(config(PLAYER)) && removal.retain() && removal.refresh() && json(removal).equals(expected),
                "The message with the dropped sensor as a stub, retained: " + removal.text());
        check(fixture.mqtt.handlers.containsKey(config(PLAYER)) && fixture.mqtt.options.get(config(PLAYER)).equals(new SubOptions(1, false, true, 64 * 1024, null)),
                "...its config topic watched, as a device's is");
        check(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS - 1).isEmpty(), "The steady message waits");
        List<Outbound> steady = fixture.after(1);
        check(steady.size() == 1 && json(steady.getFirst()).equals(playerConfig(false)) && steady.getFirst().refresh(), "...then the message alone");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        check(!fixture.mqtt.handlers.containsKey(config(PLAYER)) && !fixture.publisher.hasPendingWork(), "The watch closes and nothing waits");

        // Home Assistant's clear after it removed the sensor brings the message back sooner.
        fixture.publisher.componentsRemoved(PLAYER, Map.of("health", healthSensor()), current::get);
        fixture.publisher.tick();
        fixture.mqtt.take();
        fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        fixture.mqtt.take();
        fixture.mqtt.deliver(config(PLAYER), "");
        List<Outbound> restored = fixture.after(DiscoveryPublisher.RESTORE_DELAY_MILLIS);
        check(restored.size() == 1 && json(restored.getFirst()).equals(playerConfig(false)), "Home Assistant's clear republishes the message");
        fixture.mqtt.deliver(config(PLAYER), "", true);
        check(fixture.after(DiscoveryPublisher.RESTORE_DELAY_MILLIS).isEmpty(), "A retained empty message is no clear from Home Assistant");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);

        // Not discovered any more: no message, the removal ends (the owner clears the object).
        fixture.publisher.componentsRemoved(PLAYER, Map.of("health", healthSensor()), current::get);
        current.set(java.util.Optional.empty());
        check(fixture.after(1).isEmpty() && !fixture.publisher.hasPendingWork(), "An object that is not discovered any more ends its removal silently");
        boolean refused = false;
        try {
            fixture.publisher.componentsRemoved(PLAYER, Map.of("health", JsonParser.parseString("{\"name\":\"Health\"}").getAsJsonObject()), current::get);
        } catch (IllegalArgumentException expectedRefusal) {
            refused = true;
        }
        check(refused, "A removed component names its platform, or its stub could not");

        // A device drops components through its binding only, and a group never: their object ids are refused, with or
        // without a binding cleanup running, so no removal races the device's own steps or takes over a later cleanup.
        DeviceRecord lamp = discovered(fixture, "hall_lamp");
        refused = false;
        try {
            fixture.publisher.componentsRemoved(lamp.objectId(), Map.of("x", healthSensor()), () -> java.util.Optional.of(playerConfig(false)));
        } catch (IllegalArgumentException expectedRefusal) {
            refused = true;
        }
        check(refused && !fixture.publisher.hasPendingWork() && fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty(),
                "A device's object id is refused without a binding cleanup too, and nothing runs for it");
        DeviceGroup group = fixture.registry.createGroup(UUID.randomUUID(), "Removal Group");
        refused = false;
        try {
            fixture.publisher.componentsRemoved(group.objectId(), Map.of("x", healthSensor()), () -> java.util.Optional.of(playerConfig(false)));
        } catch (IllegalArgumentException expectedRefusal) {
            refused = true;
        }
        check(refused && !fixture.publisher.hasPendingWork(), "A group's object id is refused");
        bind(lamp);
        fixture.publisher.bindingChanged(lamp);
        refused = false;
        try {
            fixture.publisher.componentsRemoved(lamp.objectId(), Map.of("x", healthSensor()), () -> java.util.Optional.of(playerConfig(false)));
        } catch (IllegalArgumentException expectedRefusal) {
            refused = true;
        }
        check(refused, "A device's object id is refused while its binding cleanup runs");
        fixture.publisher.tick();
        check(fixture.mqtt.single().topic().equals(config(lamp.objectId())), "...and the device's own cleanup runs as before, keyed by its object id");
    }

    /**
     * A removal while Home Assistant is not known to be online is marked: the stubs go out at once (they cost nothing),
     * and Home Assistant's birth runs the full message, the removal and the steady message, which ends the mark. The mark
     * keeps no work pending while it waits.
     */
    private static void objectRemovalAfterBirth() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        AtomicReference<java.util.Optional<JsonObject>> current = new AtomicReference<>(java.util.Optional.of(playerConfig(false)));
        fixture.publisher.componentsRemoved(PLAYER, Map.of("health", healthSensor()), current::get);
        fixture.publisher.tick();
        JsonObject stubbed = json(fixture.mqtt.single());
        check(stubbed.getAsJsonObject("cmps").getAsJsonObject("health").keySet().equals(Set.of("p")), "Offline, the stub goes out at once");
        fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        fixture.mqtt.take();
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        check(!fixture.publisher.hasPendingWork(), "The mark waiting for the birth is no pending work");

        fixture.haOnline.set(true);
        fixture.publisher.republishAll();
        List<Outbound> birth = fixture.after(0);
        List<Outbound> player = birth.stream().filter(message -> message.topic().equals(config(PLAYER))).toList();
        check(player.size() == 1 && json(player.getFirst()).equals(playerConfig(true)), "The birth publishes the message with the sensor in full first: " + topics(birth));
        List<Outbound> removal = fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        check(removal.size() == 1 && json(removal.getFirst()).equals(stubbed), "...then the removal");
        List<Outbound> steady = fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        check(steady.size() == 1 && json(steady.getFirst()).equals(playerConfig(false)), "...then the message alone");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        fixture.publisher.republishAll();
        check(fixture.after(0).stream().noneMatch(message -> message.topic().equals(config(PLAYER))), "The next birth leaves the object to its owner: the mark is done");
    }

    private static JsonObject positionSensor() {
        return JsonParser.parseString("{\"p\":\"sensor\",\"unique_id\":\"" + PLAYER + "_position\",\"name\":\"Position\"}").getAsJsonObject();
    }

    /**
     * A component the owner adds back before a step (full -> presence -> full while Home Assistant was offline) leaves the
     * removal: Home Assistant's birth neither stubs the live component nor replaces it by how it was; with nothing left to
     * remove, the message as it is now ends the removal and its mark. A component still dropped is removed as before.
     */
    private static void objectReAdded() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        AtomicReference<java.util.Optional<JsonObject>> current = new AtomicReference<>(java.util.Optional.of(playerConfig(false)));
        fixture.publisher.componentsRemoved(PLAYER, Map.of("health", healthSensor()), current::get);
        fixture.publisher.tick();
        fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        fixture.mqtt.take();
        // The owner adds the sensor back, renamed, and publishes its message itself.
        JsonObject renamed = playerConfig(true);
        renamed.getAsJsonObject("cmps").getAsJsonObject("health").addProperty("name", "Health Now");
        current.set(java.util.Optional.of(renamed));

        fixture.haOnline.set(true);
        fixture.publisher.republishAll();
        List<Outbound> player = fixture.after(0).stream().filter(message -> message.topic().equals(config(PLAYER))).toList();
        check(player.size() == 1 && json(player.getFirst()).equals(renamed), "The birth publishes the message as it is now, the sensor live as the owner has it: "
                + player.stream().map(Outbound::text).toList());
        check(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty() && fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).isEmpty(),
                "...and no stub follows");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        check(!fixture.publisher.hasPendingWork(), "Nothing waits");
        fixture.publisher.republishAll();
        check(fixture.after(0).stream().noneMatch(message -> message.topic().equals(config(PLAYER))), "The mark is done");

        // Two dropped online, one added back before the removal's step: only the other is stubbed.
        current.set(java.util.Optional.of(playerConfig(false)));
        fixture.publisher.componentsRemoved(PLAYER, Map.of("health", healthSensor(), "position", positionSensor()), current::get);
        current.set(java.util.Optional.of(playerConfig(true)));
        List<Outbound> removal = fixture.after(0);
        JsonObject expected = playerConfig(true);
        expected.getAsJsonObject("cmps").add("position", JsonParser.parseString("{\"p\":\"sensor\"}"));
        check(removal.size() == 1 && json(removal.getFirst()).equals(expected), "Only the component still dropped is stubbed: "
                + removal.stream().map(Outbound::text).toList());
        List<Outbound> steady = fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        check(steady.size() == 1 && json(steady.getFirst()).equals(playerConfig(true)), "...then the message as it is now");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
        check(!fixture.publisher.hasPendingWork(), "...and the removal is done");
    }

    /**
     * The bridge's components (M4 plan D6: the broadcast text, turned off): the bridge's message with the component as a
     * stub, then the bridge's message alone, whose hash is then the bridge's, so a refresh sends nothing; a bridge refresh
     * while a step is due waits for the steps.
     */
    private static void bridgeRemoval() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        fixture.haOnline.set(true);
        fixture.publisher.republishAll();
        fixture.after(0);
        JsonObject bridge = BridgeDevice.discovery(DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        String topic = config(DiscoveryGoldenTest.IDENTITY.bridgeObjectId());
        JsonObject broadcast = JsonParser.parseString("{\"p\":\"text\",\"unique_id\":\"mineqtt_bridge_x_broadcast\",\"name\":\"Broadcast\"}").getAsJsonObject();
        fixture.publisher.bridgeComponentsRemoved(Map.of("broadcast", broadcast));
        fixture.publisher.configChanged();
        List<Outbound> first = fixture.after(0);
        JsonObject stubbed = bridge.deepCopy();
        stubbed.getAsJsonObject("cmps").add("broadcast", JsonParser.parseString("{\"p\":\"text\"}"));
        check(first.size() == 1 && first.getFirst().topic().equals(topic) && json(first.getFirst()).equals(stubbed),
                "The bridge's message with the broadcast stub, and no plain bridge message beside it: " + topics(first));
        List<Outbound> steady = fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        check(steady.size() == 1 && json(steady.getFirst()).equals(bridge), "...then the bridge's message alone");
        fixture.publisher.configChanged();
        check(fixture.after(0).isEmpty(), "Its hash is the bridge's now: a refresh sends nothing");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);
    }

    /**
     * M4 plan D8: the bridge has its broadcast text entity only while {@code telemetry.allowBroadcast} is on. Turned off,
     * Home Assistant removes it; the registry remembers that it may hold the entity until the removal went out while it
     * was online, also over a restart, and a world that never turned it on never stubs it.
     */
    private static void broadcastEntity() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        String topic = config(DiscoveryGoldenTest.IDENTITY.bridgeObjectId());
        JsonObject plain = BridgeDevice.discovery(DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY);
        JsonObject withText = BridgeDevice.discovery(DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY, true);
        JsonObject stubbed = plain.deepCopy();
        stubbed.getAsJsonObject("cmps").add(BridgeDevice.BROADCAST, JsonParser.parseString("{\"p\":\"text\"}"));
        fixture.haOnline.set(true);
        fixture.publisher.republishAll();
        List<Outbound> start = fixture.after(0);
        check(start.size() == 1 && json(start.getFirst()).equals(plain) && !fixture.registry.bridgeBroadcastDiscovered(),
                "Off by default: no broadcast entity, and no stub of one a world never had");

        MineQTTConfig.telemetryAllowBroadcast = true;
        fixture.publisher.configChanged();
        List<Outbound> on = fixture.after(0);
        check(on.size() == 1 && on.getFirst().topic().equals(topic) && json(on.getFirst()).equals(withText),
                "Turned on, the bridge's message gains the broadcast entity: " + topics(on));
        JsonObject text = withText.getAsJsonObject("cmps").getAsJsonObject(BridgeDevice.BROADCAST);
        check(text.get("p").getAsString().equals("text") && text.get("command_topic").getAsString().equals("mineqtt/world_7f3a/bridge/request/broadcast")
                        && !text.get("retain").getAsBoolean() && text.get("max").getAsInt() == 255 && !text.has("state_topic")
                        && text.get("entity_category").getAsString().equals("config"),
                "A config text entity sending to bridge/request/broadcast, never retained, at most 255 characters, optimistic: " + text);
        check(fixture.registry.bridgeBroadcastDiscovered(),
                "The registry remembers at once that Home Assistant may hold it");

        // Turned off while Home Assistant is not known to be online: the stub goes out now, the mark stays.
        MineQTTConfig.telemetryAllowBroadcast = false;
        fixture.haOnline.set(false);
        fixture.publisher.configChanged();
        List<Outbound> off = fixture.after(0);
        check(off.size() == 1 && json(off.getFirst()).equals(stubbed), "Turned off, the bridge's message with the entity as a stub: " + topics(off));
        check(json(fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS).getFirst()).equals(plain), "then the bridge's message alone");
        check(fixture.registry.bridgeBroadcastDiscovered(), "Home Assistant was not known to be online: the registry keeps the mark");
        fixture.after(DiscoveryPublisher.WATCH_MILLIS);

        // A restart forgets the removal in memory; the registry's mark brings it back at Home Assistant's birth.
        DiscoveryPublisher restarted = new DiscoveryPublisher(fixture.registry, fixture.mqtt, () -> DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY,
                true, fixture.clock::get, fixture.haOnline::get);
        fixture.haOnline.set(true);
        restarted.republishAll();
        restarted.tick();
        List<Outbound> birth = fixture.mqtt.take();
        check(birth.size() == 1 && json(birth.getFirst()).equals(stubbed), "After a restart the birth's republish removes the entity again: " + topics(birth));
        check(!fixture.registry.bridgeBroadcastDiscovered(), "issued while Home Assistant is online, which ends the mark");
        fixture.clock.addAndGet(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        restarted.tick();
        check(json(fixture.mqtt.single()).equals(plain), "then the bridge's message alone");
        fixture.clock.addAndGet(DiscoveryPublisher.WATCH_MILLIS);
        restarted.tick();
        restarted.republishAll();
        restarted.tick();
        check(json(fixture.mqtt.single()).equals(plain), "The next republish stubs nothing");
        // /mineqtt discovery clear removes the bridge device in Home Assistant, its broadcast entity with it.
        fixture.registry.setBridgeBroadcastDiscovered(true);
        restarted.clearAll();
        fixture.mqtt.take();
        check(!fixture.registry.bridgeBroadcastDiscovered(), "A discovery clear leaves no mark of the broadcast entity");

        // Turned off while Home Assistant is online: the mark stays until the stub went out, not only until it was
        // scheduled, so a stop (or a crash, or a lost connection) before the next tick removes the entity at the next start.
        MineQTTConfig.telemetryAllowBroadcast = true;
        restarted.configChanged();
        fixture.clock.addAndGet(1_000);
        restarted.tick();
        check(json(fixture.mqtt.single()).equals(withText) && fixture.registry.bridgeBroadcastDiscovered(), "On again: the entity and its mark are back");
        MineQTTConfig.telemetryAllowBroadcast = false;
        restarted.configChanged();
        check(fixture.mqtt.take().isEmpty() && fixture.registry.bridgeBroadcastDiscovered(),
                "Turned off while Home Assistant is online, the stub waits for the next tick and the mark stays until then");
        restarted.stop();
        check(fixture.registry.bridgeBroadcastDiscovered(), "A stop before the stub went out keeps the mark");
        DiscoveryPublisher next = new DiscoveryPublisher(fixture.registry, fixture.mqtt, () -> DiscoveryGoldenTest.TOPICS, DiscoveryGoldenTest.IDENTITY,
                true, fixture.clock::get, fixture.haOnline::get);
        next.republishAll();
        check(fixture.registry.bridgeBroadcastDiscovered(), "The next start schedules the stub again and keeps the mark");
        next.tick();
        List<Outbound> again = fixture.mqtt.take();
        check(again.size() == 1 && json(again.getFirst()).equals(stubbed), "The next start's birth removes the entity: " + topics(again));
        check(!fixture.registry.bridgeBroadcastDiscovered(), "The stub went out while Home Assistant was online: the mark is done");
        next.stop();
    }

    /**
     * Runtime select options (M4 plan §3.2): the message has the options of the moment, so a refresh after they changed
     * publishes it, and an unchanged refresh sends nothing; only types with such a select are checked again.
     */
    private static void dynamicOptions() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        List<String> options = new ArrayList<>(List.of("minecraft:glass"));
        art.rehra.mineqtt.device.Field<String> block = art.rehra.mineqtt.device.Field.text("block", art.rehra.mineqtt.device.Access.READ_WRITE);
        DeviceType placer = DeviceType.builder(Identifier.fromNamespaceAndPath("mineqtt_check", "options_placer")).model("Options Placer")
                .unloaded(art.rehra.mineqtt.device.UnloadedPolicy.UNAVAILABLE).field(block).entity(Ha.selectDynamic("block", block, () -> options).config()).build();
        DeviceTypes.register(placer);
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), placer.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(500, 64, 0)), "Placer", "placer");
        fixture.registry.add(record);
        DeviceRecord lamp = discovered(fixture, "porch_lamp");
        fixture.publisher.refresh(record);
        fixture.publisher.tick();
        check(json(fixture.mqtt.single()).getAsJsonObject("cmps").getAsJsonObject("block").getAsJsonArray("options").size() == 1, "Discovered with one option");
        String hash = record.discoveryHash();
        fixture.publisher.refreshDynamicOptions();
        check(fixture.after(0).isEmpty(), "Unchanged options publish nothing, and the lamp is not checked at all");
        options.add("minecraft:white_wool");
        fixture.publisher.refreshDynamicOptions();
        List<Outbound> published = fixture.after(0);
        check(published.size() == 1 && published.getFirst().topic().equals(config(record.objectId()))
                && json(published.getFirst()).getAsJsonObject("cmps").getAsJsonObject("block").getAsJsonArray("options").size() == 2
                && !hash.equals(record.discoveryHash()), "A new option republishes the placer alone, with a new hash: " + topics(published));
        check(lamp.discoveryHash() != null, "The lamp stays discovered");
        options.clear();
        fixture.publisher.refreshDynamicOptions();
        List<Outbound> emptied = fixture.after(0);
        check(emptied.size() == 1 && json(emptied.getFirst()).getAsJsonObject("cmps").get("block").equals(JsonParser.parseString("{\"p\":\"select\"}")),
                "Options that ran out republish the placer with the select as a stub, so Home Assistant removes it: " + topics(emptied));
    }

    private static DeviceRecord discovered(Fixture fixture, String slug) {
        DeviceRecord lamp = fixture.lamp(slug);
        fixture.publisher.refresh(lamp);
        fixture.publisher.tick();
        fixture.mqtt.take();
        check(lamp.discoveryHash() != null, "Discovered: " + slug);
        return lamp;
    }

    /**
     * M4 plan D11: a room edit checks the devices it touches again ({@link RoomIndex#affected}, as
     * {@code DeviceRuntime#roomChanged} does) and only those whose area changed are published; the area of the message
     * that creates a device in Home Assistant is kept, and only a message that creates it again replaces it. A group names
     * an area while its members agree.
     */
    private static void rooms() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        UUID steve = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
        UUID alex = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
        DeviceRecord kitchenLamp = fixture.lamp("kitchen_lamp");
        kitchenLamp.setOwner(steve);
        DeviceRecord pantryLamp = fixture.lamp("pantry_lamp");
        pantryLamp.setOwner(steve);
        DeviceRecord alexLamp = fixture.lamp("alex_lamp");
        alexLamp.setOwner(alex);
        DeviceRecord outside = fixture.lamp("garden_lamp");
        outside.setOwner(steve);
        List<DeviceRecord> all = List.of(kitchenLamp, pantryLamp, alexLamp, outside);
        all.forEach(fixture.publisher::refresh);
        fixture.publisher.tick();
        check(fixture.mqtt.take().size() == 4, "Four lamps are discovered");
        check(all.stream().allMatch(record -> "".equals(record.discoveryArea())), "A device created in no room is kept with the area none");

        // Steve's kitchen holds his two lamps and Alex's; the garden lamp is outside it.
        BlockPos from = kitchenLamp.pos().pos().offset(0, -1, -2);
        BlockPos to = alexLamp.pos().pos().offset(0, 2, 2);
        Room kitchen = Room.between(UUID.randomUUID(), "Kitchen", Level.OVERWORLD, to, from, steve, false, 1_000);
        fixture.registry.addRoom(kitchen);
        Set<UUID> affected = RoomIndex.affected(fixture.registry.records(), null, kitchen);
        check(affected.equals(Set.of(kitchenLamp.id(), pantryLamp.id())), "A room touches its owner's devices inside it, not Alex's nor the garden's: " + affected);
        String kitchenHash = kitchenLamp.discoveryHash();
        affected.stream().map(fixture.registry::record).forEach(fixture.publisher::refresh);
        fixture.publisher.refresh(alexLamp);
        fixture.publisher.tick();
        List<Outbound> moved = fixture.mqtt.take();
        check(moved.size() == 2 && moved.stream().allMatch(message -> json(message).getAsJsonObject("dev").get("sa").getAsString().equals("Kitchen")),
                "Steve's two lamps are published with the area, Alex's lamp in the box is not touched: " + topics(moved));
        check(!kitchenLamp.discoveryHash().equals(kitchenHash), "The hash follows the area");
        check("".equals(kitchenLamp.discoveryArea()), "Home Assistant created the lamp in no area and keeps that: the record says so still");
        check(fixture.publisher.area(alexLamp) == null && fixture.publisher.area(outside) == null, "Alex's lamp and the garden lamp are in no room");

        // A pantry inside the kitchen wins for the lamp in it; its removal gives the lamp back to the kitchen.
        Room pantry = Room.between(UUID.randomUUID(), "Pantry", Level.OVERWORLD, pantryLamp.pos().pos(), pantryLamp.pos().pos(), steve, false, 2_000);
        fixture.registry.addRoom(pantry);
        RoomIndex.affected(fixture.registry.records(), null, pantry).stream().map(fixture.registry::record).forEach(fixture.publisher::refresh);
        fixture.publisher.tick();
        Outbound pantryMessage = fixture.mqtt.single();
        check(pantryMessage.topic().equals(config(pantryLamp.objectId())) && json(pantryMessage).getAsJsonObject("dev").get("sa").getAsString().equals("Pantry"),
                "The smaller room wins for the lamp in it");
        fixture.registry.removeRoom(pantry.id());
        RoomIndex.affected(fixture.registry.records(), pantry, null).stream().map(fixture.registry::record).forEach(fixture.publisher::refresh);
        fixture.publisher.tick();
        check(json(fixture.mqtt.single()).getAsJsonObject("dev").get("sa").getAsString().equals("Kitchen"), "Without the pantry the lamp is in the kitchen again");

        // A rename publishes the new name; a room for everyone, made by the server, takes Alex's lamp too.
        Room renamed = kitchen.renamed("Cookhouse");
        fixture.registry.replaceRoom(renamed);
        RoomIndex.affected(fixture.registry.records(), kitchen, renamed).stream().map(fixture.registry::record).forEach(fixture.publisher::refresh);
        fixture.publisher.tick();
        check(fixture.mqtt.take().stream().allMatch(message -> json(message).getAsJsonObject("dev").get("sa").getAsString().equals("Cookhouse")),
                "A rename republishes the lamps with the new name");
        Room shared = Room.between(UUID.randomUUID(), "Shared", Level.OVERWORLD, alexLamp.pos().pos(), alexLamp.pos().pos(), Util.NIL_UUID, true, 3_000);
        fixture.registry.addRoom(shared);
        check(RoomIndex.affected(fixture.registry.records(), null, shared).equals(Set.of(alexLamp.id())), "A room for everyone touches Alex's lamp");
        check("Shared".equals(fixture.publisher.area(alexLamp)), "and names it");

        // Hidden, the device is gone from Home Assistant: one clear, never a second (Home Assistant's own clears after it
        // removed the entities are what it warns about). Exposed again, 2026.10 restores the area it had and 2025.2 applies
        // the room's: the first creation's area stays, and the hint says what Home Assistant may show.
        kitchenLamp.setExpose(false);
        fixture.publisher.refresh(kitchenLamp);
        fixture.publisher.tick();
        Outbound cleared = fixture.mqtt.single();
        check(cleared.topic().equals(config(kitchenLamp.objectId())) && cleared.payload().length == 0 && cleared.retain() && "".equals(kitchenLamp.discoveryArea()),
                "One retained clear; the area of the first creation (none) is kept: " + kitchenLamp.discoveryArea());
        fixture.publisher.refresh(kitchenLamp);
        fixture.publisher.republishAll();
        List<Outbound> later = fixture.after(DiscoveryPublisher.CLEANUP_STEP_MILLIS);
        check(later.stream().noneMatch(message -> message.topic().equals(config(kitchenLamp.objectId()))),
                "Neither a refresh nor a republish clears the hidden device again: " + topics(later));
        kitchenLamp.setExpose(null);
        fixture.publisher.refresh(kitchenLamp);
        fixture.publisher.tick();
        check(fixture.mqtt.single().payload().length > 0 && "".equals(kitchenLamp.discoveryArea()),
                "Created again in the Cookhouse, the first creation's area stays: " + kitchenLamp.discoveryArea());
        check(DeviceView.Registration.keptArea(kitchenLamp.discoveryHash() != null, kitchenLamp.discoveryArea(), fixture.publisher.area(kitchenLamp))
                .equals(java.util.Optional.of("")), "so its screen says Home Assistant may have kept it without an area");

        // M4-23: with the catalog's areas, a room names Home Assistant's spelling of a matching area; without, its own name.
        List<String> catalogAreas = new ArrayList<>(List.of("Garden", "COOK HOUSE"));
        fixture.publisher.setAreaNames(() -> catalogAreas);
        check("COOK HOUSE".equals(fixture.publisher.area(pantryLamp)), "The room Cookhouse names the catalog's area COOK HOUSE");
        fixture.publisher.refresh(pantryLamp);
        fixture.publisher.tick();
        check(json(fixture.mqtt.single()).getAsJsonObject("dev").get("sa").getAsString().equals("COOK HOUSE"),
                "A refresh publishes the device with Home Assistant's spelling");
        catalogAreas.clear();
        check("Cookhouse".equals(fixture.publisher.area(pantryLamp)), "Without the catalog the room's own name");
        fixture.publisher.refresh(pantryLamp);
        fixture.publisher.tick();
        fixture.mqtt.take();

        // A group names the area while its members agree.
        DeviceGroup group = fixture.registry.createGroup(UUID.randomUUID(), "Kitchen Lights");
        fixture.registry.joinGroup(group, kitchenLamp);
        fixture.registry.joinGroup(group, pantryLamp);
        check("Cookhouse".equals(fixture.publisher.area(group)), "Both members are in the room: the group names it");
        fixture.registry.joinGroup(group, outside);
        check(fixture.publisher.area(group) == null, "A member outside it: the group names no area");
        check(fixture.publisher.area(fixture.registry.createGroup(UUID.randomUUID(), "Empty")) == null, "An empty group names none");

        // A migrated v1 lamp joins the device Home Assistant kept from v1, which keeps its area: nothing is recorded.
        DeviceRecord legacy = fixture.lamp("legacy_lamp");
        legacy.setLegacyUniqueId("home_legacy");
        fixture.registry.markLegacyDiscoveryMigrated("home_legacy");
        fixture.publisher.refresh(legacy);
        fixture.publisher.tick();
        check(fixture.mqtt.single().payload().length > 0 && legacy.discoveryArea() == null, "A migrated v1 lamp keeps no area of its own");
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
        // Two players online, nobody named, 20 tps at 5 ms a tick, 06:00 on day 0, clear.
        AtomicReference<WorldReading> world = new AtomicReference<>(new WorldReading(2, List.of(), 20, false, 5_000_000, 0, false, false));
        AtomicReference<BridgeDevice.Settings> settings = new AtomicReference<>(new BridgeDevice.Settings(true, 10));
        List<String> shown = new ArrayList<>();
        List<String> logged = new ArrayList<>();
        BridgeDevice bridge = new BridgeDevice(mqtt, () -> DiscoveryGoldenTest.TOPICS, world::get, settings::get, clock::get, republished::incrementAndGet,
                shown::add, recording(logged));
        check(TelemetryThrottle.MIN_INTERVAL_SECONDS == StaticDefaults.MIN_TELEMETRY_INTERVAL_SECONDS
                && TelemetryThrottle.MAX_INTERVAL_SECONDS == StaticDefaults.MAX_TELEMETRY_INTERVAL_SECONDS,
                "The throttle clamps telemetry.intervalSeconds to the config's bounds");
        check(mqtt.handlers.containsKey("mineqtt/world_7f3a/bridge/request/+"), "The bridge takes requests");

        mqtt.deliver("mineqtt/world_7f3a/bridge/request/republish", "");
        check(republished.get() == 0 && mqtt.take().isEmpty(), "An empty request (a retained clear) is ignored");
        mqtt.deliver("mineqtt/world_7f3a/bridge/request/republish", "republish");
        Outbound ok = mqtt.single();
        check(republished.get() == 1 && ok.topic().equals("mineqtt/world_7f3a/bridge/response/republish") && !ok.retain()
                && ok.text().equals("{\"status\":\"ok\"}"), "republish runs and is answered");
        mqtt.deliver("mineqtt/world_7f3a/bridge/request/explode", "now");
        check(mqtt.single().text().contains("\"status\":\"error\""), "An unknown request is answered with an error");
        mqtt.deliver("mineqtt/world_7f3a/bridge/request/republish", "republish", true);
        check(republished.get() == 1 && mqtt.take().isEmpty(), "A retained request is a stale one: ignored, unanswered");
        broadcastRequests(mqtt, clock, settings, shown, logged);

        // Connected, but the connect listeners have not run yet.
        bridge.tick();
        check(mqtt.take().isEmpty(), "No telemetry before the connect publishes it");
        bridge.publishTelemetry();
        Outbound telemetry = mqtt.single();
        check(telemetry.topic().equals("mineqtt/world_7f3a/bridge") && telemetry.retain() && telemetry.text().equals(
                        "{\"players_online\":2,\"players\":[],\"tps\":20.0,\"mspt\":5.0,\"time_of_day\":\"06:00\",\"day\":0,\"is_day\":true,"
                                + "\"weather\":\"clear\",\"dropped_inbound\":0,\"dropped_outbound\":0}"), "Telemetry: " + telemetry.text());
        check(bridge.lastTelemetry().orElseThrow().playersOnline() == 2, "The bridge keeps what it published");

        // A player joins: published at the next sample, but not sooner than 2 s after the last publish.
        world.set(new WorldReading(3, List.of(), 20, false, 5_000_000, 0, false, false));
        clock.addAndGet(1_000);
        bridge.tick();
        check(mqtt.take().isEmpty(), "A change of the players waits for the 2 s floor");
        clock.addAndGet(1_000);
        bridge.tick();
        check(mqtt.single().text().contains("\"players_online\":3"), "then it is published, long before the 10 s interval");

        // Load within the deadbands is not published at all; beyond them only after the interval.
        world.set(new WorldReading(3, List.of(), 20, false, 5_900_000, 0, false, false));
        clock.addAndGet(30_000);
        bridge.tick();
        check(mqtt.take().isEmpty(), "0.9 ms more per tick stays within the MSPT deadband");
        world.set(new WorldReading(3, List.of(), 20, false, 6_000_000, 0, false, false));
        bridge.tick();
        check(mqtt.take().isEmpty(), "The next sample waits a second");
        clock.addAndGet(1_000);
        bridge.tick();
        check(mqtt.single().text().contains("\"mspt\":6.0"), "1 ms more is published once the interval is over");
        world.set(new WorldReading(3, List.of(), 20, false, 7_500_000, 0, false, false));
        clock.addAndGet(9_000);
        bridge.tick();
        check(mqtt.take().isEmpty(), "Another load change waits for the 10 s interval");
        clock.addAndGet(1_000);
        bridge.tick();
        check(mqtt.single().text().contains("\"mspt\":7.5"), "then it is published");

        // telemetry.intervalSeconds is read at every sample and clamped to 5 s.
        settings.set(new BridgeDevice.Settings(true, 1));
        world.set(new WorldReading(3, List.of(), 20, false, 9_000_000, 0, false, false));
        clock.addAndGet(4_000);
        bridge.tick();
        check(mqtt.take().isEmpty(), "An interval of 1 s is clamped to 5 s");
        clock.addAndGet(1_000);
        bridge.tick();
        check(mqtt.single().text().contains("\"mspt\":9.0"), "and publishes after 5 s");

        // Sunset at the next sample's 2 s floor; the time steps on with it.
        world.set(new WorldReading(3, List.of(), 20, false, 9_000_000, 12_000, false, false));
        clock.addAndGet(2_000);
        bridge.tick();
        check(mqtt.single().text().contains("\"time_of_day\":\"18:00\",\"day\":0,\"is_day\":false"), "Night is published within 2 s");

        // telemetry.enabled=false: only the player count and the drop counters, at once.
        settings.set(new BridgeDevice.Settings(false, 10));
        clock.addAndGet(2_000);
        bridge.tick();
        check(mqtt.single().text().equals("{\"players_online\":3,\"dropped_inbound\":0,\"dropped_outbound\":0}"),
                "With telemetry.enabled off only the earlier keys remain");
        world.set(new WorldReading(3, List.of(), 20, true, 50_000_000, 30_000, true, true));
        clock.addAndGet(60_000);
        bridge.tick();
        check(mqtt.take().isEmpty(), "and the world no longer changes it");

        mqtt.state = new ConnectionState(ConnectionState.Status.RECONNECTING, "test");
        world.set(new WorldReading(4, List.of(), 20, false, 5_000_000, 0, false, false));
        clock.addAndGet(60_000);
        bridge.tick();
        check(mqtt.take().isEmpty(), "Nothing is published while the broker is unreachable");
        mqtt.state = ConnectionState.CONNECTED;

        // M4 plan D7: player events on bridge/event, never retained, dropped while offline, within their caps.
        UUID steve = UUID.randomUUID();
        bridge.publishPlayerEvent(PlayerEventMessage.join(steve, "Steve", NamePolicy.NOBODY));
        Outbound join = mqtt.single();
        check(join.topic().equals("mineqtt/world_7f3a/bridge/event") && !join.retain() && join.offline() == OfflinePolicy.DROP
                && join.text().equals("{\"event_type\":\"join\"}"), "A join is an anonymous event on bridge/event: " + join.text());
        bridge.publishPlayerEvent(PlayerEventMessage.leave(steve, "Steve", NamePolicy.NOBODY));
        check(mqtt.single().text().equals("{\"event_type\":\"leave\"}"), "and so is the leave right after it");
        bridge.publishPlayerEvent(PlayerEventMessage.join(steve, "Steve", NamePolicy.NOBODY));
        check(mqtt.take().isEmpty(), "A rejoin within 5 s is not published");
        clock.addAndGet(5_000);
        bridge.publishPlayerEvent(PlayerEventMessage.join(steve, "Steve", NamePolicy.NOBODY));
        check(mqtt.single().text().equals("{\"event_type\":\"join\"}"), "5 s later it is");
        clock.addAndGet(2_000);
        for (int i = 0; i < 12; i++) bridge.publishPlayerEvent(PlayerEventMessage.join(UUID.randomUUID(), "P" + i, NamePolicy.NOBODY));
        check(mqtt.take().size() == 10, "The bridge publishes at most 10 events in a second");
        settings.set(new BridgeDevice.Settings(true, 10));
        clock.addAndGet(60_000);
        bridge.tick();
        check(mqtt.single().text().contains("\"dropped_outbound\":2"), "The 2 events over the cap count in dropped_outbound");
        bridge.stop();
        bridge.publishPlayerEvent(PlayerEventMessage.leave(UUID.randomUUID(), "Late", NamePolicy.NOBODY));
        check(mqtt.take().isEmpty(), "A stopped bridge publishes no event");
        check(mqtt.handlers.isEmpty(), "Stopping unsubscribes");
    }

    /**
     * M4 plan D8: {@code bridge/request/broadcast} answers that it is disabled while {@code telemetry.allowBroadcast} is
     * off; on, it shows the cleaned text once, logs it with its topic, keeps JSON-looking text literal, ignores retained
     * requests and refuses an oversize or empty one and the ones over the rate.
     */
    private static void broadcastRequests(FakeMqtt mqtt, AtomicLong clock, AtomicReference<BridgeDevice.Settings> settings, List<String> shown,
                                          List<String> logged) {
        String request = "mineqtt/world_7f3a/bridge/request/broadcast";
        mqtt.deliver(request, "Hi");
        Outbound disabled = mqtt.single();
        check(disabled.topic().equals("mineqtt/world_7f3a/bridge/response/broadcast") && !disabled.retain()
                && disabled.text().equals("{\"status\":\"error\",\"error\":\"broadcast disabled\"}") && shown.isEmpty(),
                "Off by default: a broadcast request is answered that it is disabled, and nothing is shown: " + disabled.text());

        settings.set(new BridgeDevice.Settings(true, 10, true));
        mqtt.deliver(request, "\u00a74Hi\u202e");
        check(mqtt.single().text().equals("{\"status\":\"ok\"}") && shown.equals(List.of("Hi")),
                "On, the text is shown once without its formatting code and the bidi override: " + shown);
        check(logged.contains("Broadcast text from " + request + ": Hi"), "and logged with its topic: " + logged);
        mqtt.deliver(request, "Again", true);
        check(mqtt.take().isEmpty() && shown.size() == 1, "A retained broadcast request is ignored");

        clock.addAndGet(10_000);
        String json = "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}";
        mqtt.deliver(request, json);
        check(mqtt.single().text().equals("{\"status\":\"ok\"}") && shown.getLast().equals(json), "JSON-looking text is shown as the text it is");
        mqtt.deliver(request, "a".repeat(1025));
        check(mqtt.single().text().equals("{\"status\":\"error\",\"error\":\"message over 1024 bytes\"}") && shown.size() == 2,
                "A request over 1 KiB is refused before it is decoded");
        mqtt.deliver(request, "\u00a7\u00a7 \t\u202e ");
        check(mqtt.single().text().equals("{\"status\":\"error\",\"error\":\"empty message\"}") && shown.size() == 2, "Nothing left shows nothing");

        clock.addAndGet(10_000);
        for (int i = 0; i < 5; i++) mqtt.deliver(request, "Burst " + i);
        List<Outbound> answers = mqtt.take();
        check(answers.size() == 5 && answers.stream().filter(answer -> answer.text().equals("{\"status\":\"ok\"}")).count() == 3
                        && answers.stream().filter(answer -> answer.text().contains("rate limited")).count() == 2
                        && shown.subList(2, shown.size()).equals(List.of("Burst 0", "Burst 1", "Burst 2")),
                "Five in a second show three, the burst; the others are refused: " + shown);
        clock.addAndGet(1_000);
        mqtt.deliver(request, "Later");
        check(mqtt.single().text().equals("{\"status\":\"ok\"}") && shown.getLast().equals("Later"), "A second later one more is shown");
        settings.set(new BridgeDevice.Settings(true, 10));
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
        GhostCleanup cleanup = new GhostCleanup(mqtt, prefix::get, bridge, fixture.registry::contains, player -> false, () -> PLAYER_ROOT, slug -> false,
                tokens::get, fixture.clock::get, recording(logged));
        check(!cleanup.pending() && mqtt.handlers.isEmpty(), "Nothing is scanned before a connect");

        cleanup.connected();
        String filter = PREFIX + "/device/+/config";
        SubOptions scan = mqtt.options.get(filter);
        String states = PLAYER_ROOT + "/+";
        check(mqtt.handlers.keySet().equals(Set.of(filter, states)) && cleanup.pending(),
                "A connect subscribes to every device config and to this bridge's presence states");
        check(scan.equals(mqtt.options.get(states)), "Both with the same options");
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
        check(logged.getLast().equals("Home Assistant ghost cleanup: 6 discovery configs and 0 presence states looked at, 2 ghosts found"),
                "The scan is summed up: " + logged.getLast());

        cleanup.connected();
        fixture.clock.addAndGet(4_000);
        cleanup.connected();
        fixture.clock.addAndGet(4_000);
        cleanup.tick();
        check(cleanup.pending() && mqtt.handlers.size() == 2, "A connect during a scan restarts its window on the same subscriptions");
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
        check(mqtt.handlers.keySet().equals(Set.of("ha2/device/+/config", states)), "A connect scans under the prefix of that moment");
        String moved = "ha2/device/" + DeviceRecord.objectId(UUID.randomUUID()) + "/config";
        mqtt.deliver(moved, ours, true);
        cleanup.tick();
        check(mqtt.single().topic().equals(moved), "and judges the topics under it");
        prefix.set(PREFIX);
        cleanup.connected();
        check(mqtt.handlers.keySet().equals(Set.of(filter, states)), "A changed prefix moves a running scan");

        mqtt.deliver(config(DeviceRecord.objectId(UUID.randomUUID())), ours, true);
        cleanup.stop();
        cleanup.tick();
        check(!cleanup.pending() && mqtt.handlers.isEmpty() && mqtt.take().isEmpty(), "Stopping unsubscribes and clears nothing more");
        cleanup.connected();
        check(mqtt.handlers.isEmpty(), "A stopped cleanup never scans again");
    }

    private static GhostCleanup.Verdict verdict(Fixture fixture, String topic, String payload, boolean retained) {
        Inbound message = new Inbound(topic, payload.getBytes(StandardCharsets.UTF_8), retained, false, null, 0);
        return GhostCleanup.judge(PREFIX, message, DiscoveryGoldenTest.IDENTITY.bridgeObjectId(), fixture.registry::contains, KNOWN_PLAYERS::contains);
    }

    // The presence devices players have in the ghost checks.
    private static final Set<String> KNOWN_PLAYERS = new java.util.HashSet<>();

    /**
     * M4 plan D10: a presence device's config ({@code mineqtt_player_<32hex>}) is judged against the players who have one
     * now, so a consent withdrawn while the broker was away, or a lost registry, leaves no player device behind.
     */
    private static void playerGhosts() {
        Fixture fixture = new Fixture(HaExpose.ALL);
        String bridge = DiscoveryGoldenTest.IDENTITY.bridgeObjectId();
        String ours = ghostConfig("dev", "\"" + bridge + "\"", 0);
        String steve = PresenceDevice.objectId(DiscoveryGoldenTest.WORLD, UUID.randomUUID());
        String alex = PresenceDevice.objectId(DiscoveryGoldenTest.WORLD, UUID.randomUUID());
        KNOWN_PLAYERS.clear();
        KNOWN_PLAYERS.add(steve);
        check(GhostCleanup.playerId(PREFIX, config(steve)).equals(steve) && GhostCleanup.deviceId(PREFIX, config(steve)) == null,
                "A presence config is read as a player's object id, not a device id");
        check(verdict(fixture, config(steve), ours, true) == GhostCleanup.Verdict.KNOWN, "The device of a player who has one is kept");
        check(verdict(fixture, config(alex), ours, true) == GhostCleanup.Verdict.GHOST, "A player device nobody has now is a ghost");
        check(verdict(fixture, config(alex), ours, false) == GhostCleanup.Verdict.LIVE, "A live presence config is no ghost");
        check(verdict(fixture, config(alex), ghostConfig("dev", "\"" + BridgeDevice.objectId(UUID.randomUUID()) + "\"", 0), true)
                == GhostCleanup.Verdict.OTHER_BRIDGE, "Another bridge's player device is kept");
        String hex = alex.substring(PresenceDevice.OBJECT_ID_PREFIX.length());
        for (String foreign : List.of(config("mineqtt_player_" + hex.toUpperCase()), config("mineqtt_player_" + hex.substring(1)),
                config("mineqtt_player_" + hex + "0"), config("mineqtt_players_" + hex))) {
            check(verdict(fixture, foreign, ours, true) == GhostCleanup.Verdict.FOREIGN, "Not a presence config: " + foreign);
        }

        AtomicBoolean tokens = new AtomicBoolean(true);
        List<String> logged = new ArrayList<>();
        GhostCleanup cleanup = new GhostCleanup(fixture.mqtt, () -> PREFIX, bridge, fixture.registry::contains, KNOWN_PLAYERS::contains,
                () -> PLAYER_ROOT, slug -> false, tokens::get, fixture.clock::get, recording(logged));
        cleanup.connected();
        fixture.mqtt.deliver(config(steve), ours, true);
        fixture.mqtt.deliver(config(alex), ours, true);
        String later = PresenceDevice.objectId(DiscoveryGoldenTest.WORLD, UUID.randomUUID());
        fixture.mqtt.deliver(config(later), ours, true);
        KNOWN_PLAYERS.add(later);
        cleanup.tick();
        Outbound clear = fixture.mqtt.single();
        check(clear.topic().equals(config(alex)) && clear.payload().length == 0 && clear.retain(),
                "Only the device nobody has is cleared; one a player got before its clear is left alone: " + clear.topic());
        check(logged.stream().anyMatch(line -> line.contains(config(alex)) && line.contains("no player")), "The clear says why: " + logged);
        cleanup.stop();
        KNOWN_PLAYERS.clear();
    }

    /**
     * A presence device's retained state ({@code <base>/<bridge>/player/<slug>}) holds the player's name in its topic and,
     * under full sharing, their health and position. One whose slug no player holds now (a lost or restored registry, a
     * clear that never reached the broker) is cleared by the same scan, under the player root of the connect.
     */
    private static void presenceStateGhosts() {
        Set<String> slugs = new java.util.HashSet<>(Set.of("player_steve"));
        String steve = PLAYER_ROOT + "/player_steve";
        String alex = PLAYER_ROOT + "/player_alex";
        String full = "{\"presence\":\"ON\",\"health\":20.0,\"x\":1,\"y\":64,\"z\":2}";
        check(GhostCleanup.playerSlug(PLAYER_ROOT, alex).equals("player_alex") && GhostCleanup.playerSlug(PLAYER_ROOT, PLAYER_ROOT + "/") == null
                && GhostCleanup.playerSlug(PLAYER_ROOT, alex + "/set") == null && GhostCleanup.playerSlug(PLAYER_ROOT, "mineqtt/other/player/player_alex") == null,
                "The slug is the one level under this bridge's player root");
        check(stateVerdict(steve, full, true, slugs) == GhostCleanup.Verdict.KNOWN, "The state of a player who has a device is kept");
        check(stateVerdict(alex, full, true, slugs) == GhostCleanup.Verdict.GHOST, "A retained state nobody has now is a ghost");
        check(stateVerdict(alex, "{\"presence\":\"OFF\"}", true, slugs) == GhostCleanup.Verdict.GHOST, "also an OFF one: its topic names the player");
        check(stateVerdict(alex, full, false, slugs) == GhostCleanup.Verdict.LIVE && stateVerdict(alex, "", true, slugs) == GhostCleanup.Verdict.LIVE,
                "A live state and a clear are no ghosts");
        check(stateVerdict("mineqtt/world_7f3a/player_alex", full, true, slugs) == GhostCleanup.Verdict.FOREIGN, "A device topic is not a presence state");

        Fixture fixture = new Fixture(HaExpose.ALL);
        AtomicReference<String> root = new AtomicReference<>(PLAYER_ROOT);
        List<String> logged = new ArrayList<>();
        GhostCleanup cleanup = new GhostCleanup(fixture.mqtt, () -> PREFIX, DiscoveryGoldenTest.IDENTITY.bridgeObjectId(), fixture.registry::contains,
                player -> false, root::get, slugs::contains, () -> true, fixture.clock::get, recording(logged));
        cleanup.connected();
        fixture.mqtt.deliver(steve, full, true);
        fixture.mqtt.deliver(alex, full, true);
        fixture.mqtt.deliver(alex, full, true);
        fixture.mqtt.deliver(PLAYER_ROOT + "/player_bob", full, false);
        String later = PLAYER_ROOT + "/player_carl";
        fixture.mqtt.deliver(later, full, true);
        slugs.add("player_carl");
        cleanup.tick();
        Outbound clear = fixture.mqtt.single();
        check(clear.topic().equals(alex) && clear.payload().length == 0 && clear.retain() && clear.offline() == OfflinePolicy.KEEP,
                "Only the state nobody has is cleared, once; one a player got before its clear is left alone: " + clear.topic());
        check(logged.stream().anyMatch(line -> line.contains(alex) && line.contains("no player")), "The clear says why: " + logged);
        fixture.clock.addAndGet(GhostCleanup.WINDOW_MILLIS);
        cleanup.tick();
        check(logged.getLast().equals("Home Assistant ghost cleanup: 0 discovery configs and 5 presence states looked at, 2 ghosts found"),
                "The scan counts the states, and the ghost that became known before its clear: " + logged.getLast());

        root.set("mineqtt/moved/player");
        cleanup.connected();
        check(fixture.mqtt.handlers.containsKey("mineqtt/moved/player/+") && !fixture.mqtt.handlers.containsKey(PLAYER_ROOT + "/+"),
                "A connect after the topics moved scans the new player root");
        cleanup.connected();
        root.set(PLAYER_ROOT);
        cleanup.connected();
        check(fixture.mqtt.handlers.containsKey(PLAYER_ROOT + "/+") && fixture.mqtt.handlers.size() == 2, "and a moved root moves a running scan");
        cleanup.stop();
        check(fixture.mqtt.handlers.isEmpty(), "Stopping closes both subscriptions");
    }

    private static GhostCleanup.Verdict stateVerdict(String topic, String payload, boolean retained, Set<String> slugs) {
        Inbound message = new Inbound(topic, payload.getBytes(StandardCharsets.UTF_8), retained, false, null, 0);
        return GhostCleanup.judgeState(PLAYER_ROOT, message, slugs::contains);
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

package art.rehra.mineqtt.presence;

import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.ha.DiscoveryPublisher;
import art.rehra.mineqtt.ha.PresenceDevice;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.InboundHandler;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.SubOptions;
import art.rehra.mineqtt.mqtt.core.Subscription;
import art.rehra.mineqtt.runtime.TopicScheme;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The presence devices (M4 plan D10), part of {@code verifyPrivacy}: created for a consenting player online, ON at the
 * join and OFF at the quit, every state as it is now at a republish (the CONNACK rule), removed with empty retained
 * messages on share off, forget, unseen for {@code forgetAfterDays} and when the settings no longer allow presence; slugs
 * unique under {@code player/} and kept across renames; the cap refuses new devices and tells the operators once; a
 * graceful stop sends OFF; discovery only while {@code ha.expose} is not NONE, within the rate limit.
 */
final class PresenceLifecycleCheck {
    private static final UUID WORLD = UUID.fromString("5b0e9a7c-3d2f-41e8-a6c4-b1d09f8e7a63");
    private static final TopicScheme TOPICS = new TopicScheme("mineqtt", "world_7f3a");
    private static final DiscoveryPublisher.Identity IDENTITY = new DiscoveryPublisher.Identity("world", WORLD, "2.0.0");
    private static final long DAY = 20_738;
    private static final String PREFIX = "homeassistant";
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

    private PresenceLifecycleCheck() {
    }

    static void run() {
        lifecycle();
        slugs();
        cap();
        forget();
        settings();
        stopAndCrash();
        full();
        movedTopics();
    }

    /**
     * A new base topic or bridge id: every device's retained state under the old root is cleared, since its topic names
     * the player and a later share off clears only the new one; the republish then publishes it under the new root.
     */
    private static void movedTopics() {
        Fixture fixture = new Fixture();
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        fixture.presence.joined(steve, "Steve");
        fixture.choose(steve, "Steve", Consent.PRESENCE);
        fixture.registry.putPlayer(new PlayerEntry(alex, Consent.PRESENCE, true, "Alex", Optional.of("player_alex"), DAY));
        fixture.presence.tick();
        fixture.mqtt.take();

        fixture.presence.topicsChanged(TOPICS);
        check(fixture.mqtt.take().isEmpty(), "A reconnect under the same root clears nothing");
        TopicScheme moved = new TopicScheme("mineqtt", "moved");
        fixture.topics.set(moved);
        fixture.presence.topicsChanged(TOPICS);
        List<Outbound> cleared = fixture.mqtt.take();
        check(cleared.size() == 2 && cleared.stream().allMatch(message -> message.payload().length == 0 && message.retain() && message.offline() == OfflinePolicy.KEEP)
                        && cleared.stream().map(Outbound::topic).toList().containsAll(List.of(TOPICS.player("player_steve"), TOPICS.player("player_alex"))),
                "Moved topics clear every device's state under the old root, online or not: " + topics(cleared));
        fixture.presence.republish();
        List<Outbound> republished = fixture.mqtt.take();
        check(republished.stream().anyMatch(message -> message.topic().equals(moved.player("player_steve")) && message.text().equals("{\"presence\":\"ON\"}"))
                        && republished.stream().anyMatch(message -> message.topic().equals(moved.player("player_alex")) && message.text().equals("{\"presence\":\"OFF\"}")),
                "and the republish after the connect publishes them under the new root: " + topics(republished));
        fixture.choose(steve, "Steve", Consent.OFF);
        check(fixture.mqtt.take().stream().anyMatch(message -> message.topic().equals(moved.player("player_steve")) && message.payload().length == 0),
                "A later share off clears the state under the new root");
        check(fixture.presence.knowsSlug("player_alex") && !fixture.presence.knowsSlug("player_steve"), "The ghost cleanup knows exactly the slugs players hold");

        // /mineqtt discovery clear: the presence configs go with the bridge's and the devices', the states stay.
        fixture.presence.joined(steve, "Steve");
        fixture.choose(steve, "Steve", Consent.PRESENCE);
        fixture.presence.tick();
        fixture.mqtt.take();
        fixture.choose(alex, "Alex", Consent.PRESENCE);
        fixture.mqtt.take();
        int count = fixture.presence.clearDiscovery();
        List<Outbound> configs = fixture.mqtt.take();
        check(count == 2 && configs.size() == 2 && configs.stream().allMatch(message -> message.payload().length == 0 && message.retain()
                        && message.topic().startsWith(PREFIX + "/device/mineqtt_player_")),
                "discovery clear clears both presence configs and no state: " + topics(configs));
        fixture.presence.tick();
        check(fixture.mqtt.take().isEmpty(), "and a discovery still waiting is not published after it");
        fixture.presence.republish();
        fixture.presence.tick();
        fixture.presence.tick();
        check(fixture.mqtt.take().stream().filter(message -> message.topic().startsWith(PREFIX + "/device/")).count() == 2,
                "The next republish discovers them again");
    }

    /** Join, consent, quit, republish and share off of one player. */
    private static void lifecycle() {
        Fixture fixture = new Fixture();
        UUID steve = UUID.randomUUID();
        String config = config(steve);
        String state = TOPICS.player("player_steve");

        fixture.presence.joined(steve, "Steve");
        fixture.presence.tick();
        check(fixture.mqtt.take().isEmpty() && fixture.registry.player(steve).isEmpty(), "A player who never consented gets no device and leaves no entry");

        fixture.choose(steve, "Steve", Consent.PRESENCE);
        check(fixture.registry.player(steve).orElseThrow().slug().equals(Optional.of("player_steve")), "Consenting while online creates the device player_steve");
        Outbound on = fixture.mqtt.single();
        check(on.topic().equals(state) && on.retain() && on.text().equals("{\"presence\":\"ON\"}"), "Its state is ON at once, retained: " + on);
        fixture.presence.tick();
        Outbound discovery = fixture.mqtt.single();
        check(discovery.topic().equals(config) && discovery.retain() && discovery.qos() == 1, "Its discovery follows within the rate limit: " + discovery.topic());
        JsonObject parsed = JsonParser.parseString(discovery.text()).getAsJsonObject();
        check(parsed.getAsJsonObject("dev").get("name").getAsString().equals("Player Steve")
                && parsed.getAsJsonObject("dev").get("via_device").getAsString().equals(IDENTITY.bridgeObjectId()), "Player Steve under the bridge");
        check(fixture.presence.knows(PresenceDevice.objectId(WORLD, steve)) && !fixture.presence.knows(PresenceDevice.objectId(WORLD, UUID.randomUUID())),
                "The ghost cleanup knows exactly the devices players have");
        check(fixture.presence.slugOf(steve).equals(Optional.of("player_steve")), "The explorer finds the player's own slug");

        fixture.presence.left(steve);
        check(fixture.mqtt.single().text().equals("{\"presence\":\"OFF\"}"), "The quit sends OFF");
        fixture.presence.republish();
        fixture.presence.tick();
        List<Outbound> republished = fixture.mqtt.take();
        check(republished.size() == 2 && republished.stream().allMatch(Outbound::refresh)
                        && republished.stream().anyMatch(message -> message.topic().equals(state) && message.text().equals("{\"presence\":\"OFF\"}"))
                        && republished.stream().anyMatch(message -> message.topic().equals(config)),
                "A republish (each CONNACK) sends the discovery and the state as it is: OFF for a player offline: " + topics(republished));

        fixture.presence.joined(steve, "Steve");
        fixture.presence.tick();
        List<Outbound> rejoined = fixture.mqtt.take();
        check(rejoined.stream().anyMatch(message -> message.topic().equals(state) && message.text().equals("{\"presence\":\"ON\"}")), "A rejoin sends ON");

        fixture.choose(steve, "Steve", Consent.OFF);
        List<Outbound> cleared = fixture.mqtt.take();
        check(cleared.size() == 2 && cleared.stream().allMatch(message -> message.payload().length == 0 && message.retain() && message.offline() == OfflinePolicy.KEEP)
                        && cleared.stream().anyMatch(message -> message.topic().equals(config)) && cleared.stream().anyMatch(message -> message.topic().equals(state)),
                "share off clears discovery and state with empty retained messages that survive a disconnect: " + topics(cleared));
        PlayerEntry off = fixture.registry.player(steve).orElseThrow();
        check(off.consent() == Consent.OFF && off.slug().isEmpty() && !off.personal(), "The slug is gone, the consent kept: " + off);
        check(!fixture.presence.knows(PresenceDevice.objectId(WORLD, steve)), "and the ghost cleanup would clear a config left behind");
        fixture.presence.left(steve);
        check(fixture.mqtt.take().isEmpty(), "A player without a device publishes nothing at the quit");
    }

    /** Slugs are unique under player/, chosen once and kept across a rename; only Home Assistant's name follows. */
    private static void slugs() {
        Fixture fixture = new Fixture();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        fixture.presence.joined(first, "Steve");
        fixture.choose(first, "Steve", Consent.PRESENCE);
        fixture.presence.joined(second, "steve");
        fixture.choose(second, "steve", Consent.FULL);
        check(fixture.registry.player(first).orElseThrow().slug().equals(Optional.of("player_steve"))
                && fixture.registry.player(second).orElseThrow().slug().equals(Optional.of("player_steve_2")), "A second Steve gets player_steve_2");
        fixture.presence.left(first);
        fixture.mqtt.take();
        // The player renamed themselves at Mojang: PlayerPrivacy.joined keeps the new name before the presence join.
        fixture.registry.putPlayer(fixture.registry.player(first).orElseThrow().seen("Stevie", DAY + 1));
        fixture.presence.joined(first, "Stevie");
        fixture.presence.tick();
        List<Outbound> renamed = fixture.mqtt.take();
        check(fixture.registry.player(first).orElseThrow().slug().equals(Optional.of("player_steve")), "A rename keeps the slug");
        Optional<Outbound> discovery = renamed.stream().filter(message -> message.topic().equals(config(first))).findFirst();
        check(discovery.isPresent() && discovery.get().text().contains("\"name\":\"Player Stevie\""), "and Home Assistant's name follows: " + topics(renamed));
    }

    /** Beyond telemetry.maxPresenceDevices nobody new gets a device, nobody loses one, and the operators hear it once. */
    private static void cap() {
        Fixture fixture = new Fixture();
        MineQTTConfig.telemetryMaxPresenceDevices = 1;
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        fixture.presence.joined(first, "First");
        fixture.choose(first, "First", Consent.PRESENCE);
        fixture.presence.joined(second, "Second");
        fixture.choose(second, "Second", Consent.PRESENCE);
        fixture.presence.joined(third, "Third");
        fixture.choose(third, "Third", Consent.PRESENCE);
        check(fixture.registry.player(first).orElseThrow().slug().isPresent() && fixture.registry.player(second).orElseThrow().slug().isEmpty()
                && fixture.registry.player(third).orElseThrow().slug().isEmpty(), "The cap refuses new devices and evicts nobody");
        check(fixture.told.size() == 1 && fixture.told.getFirst().contains("telemetry.maxPresenceDevices"), "The operators are told once: " + fixture.told);
        check(fixture.registry.player(second).orElseThrow().consent() == Consent.PRESENCE, "The refused player's consent still counts for names");
        MineQTTConfig.telemetryMaxPresenceDevices = 64;
        fixture.presence.configChanged();
        check(fixture.registry.player(second).orElseThrow().slug().isPresent() && fixture.registry.player(third).orElseThrow().slug().isPresent(),
                "A raised cap gives the consenting players online their devices");
    }

    /** /mineqtt players forget, and telemetry.forgetAfterDays for players unseen too long. */
    private static void forget() {
        Fixture fixture = new Fixture();
        UUID steve = UUID.randomUUID();
        fixture.presence.joined(steve, "Steve");
        fixture.choose(steve, "Steve", Consent.PRESENCE);
        fixture.mqtt.take();
        PlayerEntry before = fixture.registry.player(steve).orElseThrow();
        PlayerEntry after = before.forgotten();
        fixture.registry.putPlayer(after);
        fixture.presence.forgotten(new PlayerPrivacy.Change(before, after));
        check(fixture.mqtt.take().size() == 2 && fixture.registry.player(steve).orElseThrow().slug().isEmpty(), "forget clears the device");
        check(fixture.registry.player(steve).orElseThrow().consent() == Consent.PRESENCE, "and keeps the consent");
        fixture.presence.tick();
        check(fixture.mqtt.take().isEmpty(), "A forgotten player online gets no device until they join again");
        fixture.presence.left(steve);
        fixture.presence.joined(steve, "Steve");
        check(fixture.registry.player(steve).orElseThrow().slug().isPresent(), "At the next join they get one again");

        // forgetAfterDays: checked at the start and once per game day; 0 never forgets.
        check(!PresenceRuntime.expired(DAY - 90, DAY, 90) && PresenceRuntime.expired(DAY - 91, DAY, 90), "Forgotten after 90 days unseen, not on the 90th");
        check(!PresenceRuntime.expired(DAY - 10_000, DAY, 0) && !PresenceRuntime.expired(0, DAY, 90), "Never with 0 days, nor without a day");
        Fixture later = new Fixture();
        UUID gone = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        later.registry.putPlayer(new PlayerEntry(gone, Consent.PRESENCE, true, "Gone", Optional.of("player_gone"), DAY - 91));
        later.registry.putPlayer(new PlayerEntry(recent, Consent.PRESENCE, true, "Recent", Optional.of("player_recent"), DAY - 3));
        later.presence.start();
        List<Outbound> forgotten = later.mqtt.take();
        check(later.registry.player(gone).orElseThrow().equals(new PlayerEntry(gone, Consent.PRESENCE, true, "", Optional.empty(), 0))
                        && forgotten.stream().anyMatch(message -> message.topic().equals(TOPICS.player("player_gone")) && message.payload().length == 0),
                "At the start a player unseen for 91 days loses device, name and day, and keeps the consent: " + topics(forgotten));
        check(later.registry.player(recent).orElseThrow().slug().isPresent(), "A player seen 3 days ago keeps theirs");
        later.today.set(DAY + 90);
        later.clock.addAndGet(PresenceRuntime.FORGET_CHECK_MILLIS);
        later.presence.tick();
        check(later.registry.player(recent).orElseThrow().slug().isEmpty(), "The game-day check forgets them once they are unseen too long");

        // A session longer than forgetAfterDays: the player is seen until the quit, so the next check keeps them.
        Fixture afk = new Fixture();
        MineQTTConfig.telemetryForgetAfterDays = 1;
        UUID idle = UUID.randomUUID();
        afk.presence.joined(idle, "Idle");
        afk.choose(idle, "Idle", Consent.PRESENCE);
        afk.today.set(DAY + 2);
        afk.clock.addAndGet(PresenceRuntime.FORGET_CHECK_MILLIS);
        afk.presence.tick();
        check(afk.registry.player(idle).orElseThrow().slug().isPresent(), "A player online two days past forgetAfterDays=1 is not forgotten");
        afk.presence.left(idle);
        check(afk.registry.player(idle).orElseThrow().lastSeenDay() == DAY + 2, "The quit marks them seen today");
        afk.clock.addAndGet(PresenceRuntime.FORGET_CHECK_MILLIS);
        afk.presence.tick();
        check(afk.registry.player(idle).orElseThrow().slug().isPresent(), "so the next check after the quit keeps their device");
        afk.today.set(DAY + 4);
        afk.clock.addAndGet(PresenceRuntime.FORGET_CHECK_MILLIS);
        afk.presence.tick();
        check(afk.registry.player(idle).orElseThrow().slug().isEmpty(), "and forgets them once unseen for longer than a day after it");
        UUID withdrawn = UUID.randomUUID();
        afk.registry.putPlayer(new PlayerEntry(withdrawn, Consent.OFF, true, "", Optional.empty(), 0));
        afk.presence.left(withdrawn);
        check(afk.registry.player(withdrawn).orElseThrow().lastSeenDay() == 0, "A quit keeps no day for a player who does not consent");
        MineQTTConfig.resetToDefaults();
    }

    /** telemetry.players=off and publishPlayerNames=false remove every device and keep the consents; ha.expose NONE only the discovery. */
    private static void settings() {
        Fixture fixture = new Fixture();
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        fixture.presence.joined(steve, "Steve");
        fixture.choose(steve, "Steve", Consent.PRESENCE);
        fixture.registry.putPlayer(new PlayerEntry(alex, Consent.FULL, true, "Alex", Optional.of("player_alex"), DAY));
        fixture.presence.tick();
        fixture.mqtt.take();

        MineQTTConfig.telemetryPlayers = TelemetryPlayers.OFF;
        fixture.presence.configChanged();
        List<Outbound> off = fixture.mqtt.take();
        check(off.size() == 4 && off.stream().allMatch(message -> message.payload().length == 0), "telemetry.players=off clears every device: " + topics(off));
        check(fixture.registry.player(steve).orElseThrow().consent() == Consent.PRESENCE && fixture.registry.player(alex).orElseThrow().consent() == Consent.FULL
                && fixture.presence.devices().isEmpty(), "and keeps every consent");
        MineQTTConfig.telemetryPlayers = TelemetryPlayers.PRESENCE;
        fixture.presence.configChanged();
        check(fixture.registry.player(steve).orElseThrow().slug().isPresent() && fixture.registry.player(alex).orElseThrow().slug().isEmpty(),
                "Back on, the consenting player online gets a device again, the offline one at their join");
        fixture.mqtt.take();
        MineQTTConfig.telemetryPublishPlayerNames = false;
        fixture.presence.configChanged();
        check(fixture.presence.devices().isEmpty() && fixture.mqtt.take().size() == 2, "publishPlayerNames=false removes the devices too");
        MineQTTConfig.telemetryPublishPlayerNames = true;
        fixture.presence.configChanged();
        fixture.presence.tick();
        fixture.mqtt.take();

        MineQTTConfig.haExpose = HaExpose.NONE;
        fixture.presence.configChanged();
        List<Outbound> hidden = fixture.mqtt.take();
        check(hidden.stream().filter(message -> message.payload().length == 0).map(Outbound::topic).toList().equals(List.of(config(steve)))
                        && hidden.stream().noneMatch(message -> message.topic().startsWith(PREFIX + "/") && message.payload().length > 0),
                "ha.expose NONE clears the discovery only: " + topics(hidden));
        fixture.presence.republish();
        fixture.presence.tick();
        List<Outbound> stateOnly = fixture.mqtt.take();
        check(stateOnly.size() == 1 && stateOnly.getFirst().topic().equals(TOPICS.player("player_steve")), "and the state is still published: " + topics(stateOnly));
        MineQTTConfig.haExpose = HaExpose.OPT_IN;
        fixture.presence.configChanged();
        fixture.presence.tick();
        check(fixture.mqtt.take().stream().anyMatch(message -> message.topic().equals(config(steve)) && message.payload().length > 0),
                "Under OPT_IN the device is discovered again: the player's consent is the opt-in");

        MineQTTConfig.discoveryPrefix = "ha2";
        fixture.presence.configChanged();
        List<Outbound> cleared = fixture.mqtt.take();
        fixture.presence.tick();
        List<Outbound> moved = fixture.mqtt.take();
        check(cleared.stream().anyMatch(message -> message.topic().equals(config(steve)) && message.payload().length == 0)
                && moved.stream().anyMatch(message -> message.topic().equals("ha2/device/" + PresenceDevice.objectId(WORLD, steve) + "/config")),
                "A new discovery prefix clears the old config and discovers under the new one");

        AtomicBoolean tokens = fixture.tokens;
        fixture.presence.republish();
        tokens.set(false);
        List<Outbound> throttled = new ArrayList<>(fixture.mqtt.take());
        fixture.presence.tick();
        throttled.addAll(fixture.mqtt.take());
        check(throttled.stream().noneMatch(message -> message.topic().startsWith("ha2/")) && fixture.presence.hasPendingWork(),
                "Discovery waits for the shared rate limit");
        tokens.set(true);
        fixture.mqtt.state = ConnectionState.reconnecting("lost");
        fixture.presence.tick();
        check(fixture.mqtt.take().isEmpty() && !fixture.presence.hasPendingWork(), "and for the broker");
        fixture.mqtt.state = ConnectionState.CONNECTED;
        fixture.presence.tick();
        check(fixture.mqtt.take().size() == 1, "then goes out");
    }

    /** A graceful stop sends OFF for the players online; after a crash the restart's republish sends OFF for everyone. */
    private static void stopAndCrash() {
        Fixture fixture = new Fixture();
        UUID steve = UUID.randomUUID();
        fixture.presence.joined(steve, "Steve");
        fixture.choose(steve, "Steve", Consent.PRESENCE);
        fixture.presence.tick();
        fixture.mqtt.take();
        fixture.presence.stop();
        Outbound off = fixture.mqtt.single();
        check(off.topic().equals(TOPICS.player("player_steve")) && off.text().equals("{\"presence\":\"OFF\"}"), "A graceful stop sends OFF for the players online");
        fixture.presence.joined(steve, "Steve");
        fixture.presence.republish();
        check(fixture.mqtt.take().isEmpty(), "A stopped runtime publishes nothing more");

        // kill -9 while Steve was online: the restarted server has nobody online, and its CONNACK republish sends OFF.
        Fixture restarted = new Fixture(fixture.registry);
        restarted.presence.start();
        restarted.presence.republish();
        check(restarted.mqtt.take().stream().anyMatch(message -> message.topic().equals(TOPICS.player("player_steve")) && message.text().equals("{\"presence\":\"OFF\"}")),
                "The first republish after a crash sends OFF for the player who was online");
    }

    /**
     * D10 full: health, dimension and position only with consent full on a full server; the deadbands and the 5 s floor,
     * a dimension change at once, the shared budget; narrowing removes the sensors (again after a restart while Home
     * Assistant was away) and drops the reading from the state; the quit keeps no position.
     */
    private static void full() {
        Fixture fixture = new Fixture();
        MineQTTConfig.telemetryPlayers = TelemetryPlayers.FULL;
        UUID steve = UUID.randomUUID();
        String state = TOPICS.player("player_steve");
        fixture.samples.put(steve, new FullSample(19.7, "minecraft:overworld", 0, 64, 0));
        fixture.presence.joined(steve, "Steve");
        fixture.choose(steve, "Steve", Consent.PRESENCE);
        check(fixture.mqtt.single().text().equals("{\"presence\":\"ON\"}"), "share on publishes presence only, also on a full server");
        fixture.presence.tick();
        Outbound plain = fixture.mqtt.single();
        check(!plain.text().contains("\"health\"") && !fixture.registry.player(steve).orElseThrow().fullDiscovered(), "and discovers no full sensors");
        check(fixture.second().isEmpty(), "Nothing is sampled for a player who shares presence only");

        fixture.choose(steve, "Steve", Consent.FULL);
        Outbound fullState = fixture.mqtt.single();
        check(fullState.topic().equals(state) && fullState.text().equals("{\"presence\":\"ON\",\"health\":19.5,\"dimension\":\"minecraft:overworld\",\"x\":0,\"y\":64,\"z\":0}"),
                "share full publishes health (to 0.5), dimension and block position at once: " + fullState.text());
        fixture.presence.tick();
        Outbound fullDiscovery = fixture.mqtt.single();
        JsonObject components = JsonParser.parseString(fullDiscovery.text()).getAsJsonObject().getAsJsonObject("cmps");
        check(components.keySet().equals(Set.of("presence", "health", "dimension", "position")), "and discovers the three sensors: " + components.keySet());
        check(fixture.registry.player(steve).orElseThrow().fullDiscovered(), "The entry remembers they were discovered");

        fixture.samples.put(steve, new FullSample(19.5, "minecraft:overworld", 3, 64, 0));
        for (int second = 0; second < 6; second++) check(fixture.second().isEmpty(), "Walking 3 blocks publishes nothing");
        fixture.samples.put(steve, new FullSample(19.5, "minecraft:overworld", 5, 64, 0));
        List<Outbound> moved = fixture.second();
        check(moved.size() == 1 && moved.getFirst().text().contains("\"x\":5"), "5 blocks publish one update: " + topics(moved));
        fixture.samples.put(steve, new FullSample(19.5, "minecraft:overworld", 20, 64, 0));
        for (int second = 1; second < 5; second++) check(fixture.second().isEmpty(), "at most one per 5 s, however far they walk");
        check(fixture.second().size() == 1, "then the next");
        fixture.samples.put(steve, new FullSample(19.0, "minecraft:overworld", 20, 64, 0));
        for (int second = 0; second < 6; second++) check(fixture.second().isEmpty(), "Half a heart less is within the health deadband");
        fixture.samples.put(steve, new FullSample(18.5, "minecraft:overworld", 20, 64, 0));
        check(fixture.second().size() == 1, "a whole heart is published");
        fixture.samples.put(steve, new FullSample(18.5, "minecraft:the_nether", 2, 70, 2));
        List<Outbound> nether = fixture.second();
        check(nether.size() == 1 && nether.getFirst().text().contains("\"dimension\":\"minecraft:the_nether\""), "A dimension change is published at once");

        fixture.presence.left(steve);
        check(fixture.mqtt.single().text().equals("{\"presence\":\"OFF\"}"), "The quit's OFF keeps no position");
        check(fixture.second().isEmpty(), "and nothing is sampled for a player offline");
        fixture.presence.joined(steve, "Steve");
        check(fixture.mqtt.take().stream().anyMatch(message -> message.topic().equals(state) && message.text().contains("\"health\":18.5")),
                "A rejoin publishes a fresh reading");
        fixture.presence.tick();
        fixture.mqtt.take();

        // Narrowing by the player's choice, with Home Assistant online: the removal runs now and the mark goes.
        fixture.choose(steve, "Steve", Consent.PRESENCE);
        check(fixture.removals.size() == 1 && fixture.removals.getFirst().getKey().equals(PresenceDevice.objectId(WORLD, steve))
                && fixture.removals.getFirst().getValue().equals(Set.of("health", "dimension", "position")), "share on removes the three sensors: " + fixture.removals);
        check(fixture.mqtt.take().stream().anyMatch(message -> message.topic().equals(state) && message.text().equals("{\"presence\":\"ON\"}")),
                "and the state drops the reading");
        check(!fixture.registry.player(steve).orElseThrow().fullDiscovered(), "Home Assistant was online: the mark is gone");
        check(fixture.second().stream().noneMatch(message -> message.topic().equals(state)), "Nothing is sampled any more");

        // Narrowing while Home Assistant is away: the mark stays, a restart issues the removal again, and a republish
        // while Home Assistant is online issues it once more and ends it.
        fixture.choose(steve, "Steve", Consent.FULL);
        fixture.presence.tick();
        fixture.mqtt.take();
        fixture.removals.clear();
        fixture.haOnline.set(false);
        MineQTTConfig.telemetryPlayers = TelemetryPlayers.PRESENCE;
        fixture.presence.configChanged();
        check(fixture.removals.size() == 1 && fixture.registry.player(steve).orElseThrow().fullDiscovered(),
                "A server narrowed to presence removes the sensors; with Home Assistant away the mark stays");
        Fixture restarted = new Fixture(fixture.registry);
        restarted.haOnline.set(false);
        restarted.presence.start();
        check(restarted.removals.size() == 1 && restarted.registry.player(steve).orElseThrow().fullDiscovered(), "A restart issues the removal again");
        restarted.haOnline.set(true);
        restarted.presence.republish();
        check(restarted.removals.size() == 2 && !restarted.registry.player(steve).orElseThrow().fullDiscovered(),
                "Home Assistant's birth issues it once more, and the mark ends");
        restarted.presence.republish();
        check(restarted.removals.size() == 2, "and never again");

        // The shared budget: 25 players who share everything walk off at once.
        Fixture crowd = new Fixture();
        MineQTTConfig.telemetryPlayers = TelemetryPlayers.FULL;
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            UUID player = UUID.randomUUID();
            players.add(player);
            crowd.samples.put(player, new FullSample(20, "minecraft:overworld", i * 100, 64, 0));
            crowd.presence.joined(player, "P" + i);
            crowd.choose(player, "P" + i, Consent.FULL);
        }
        crowd.mqtt.take();
        crowd.clock.addAndGet(FullSample.MIN_INTERVAL_MILLIS);
        for (UUID player : players) {
            FullSample at = crowd.samples.get(player);
            crowd.samples.put(player, new FullSample(20, at.dimension(), at.x() + 10, 64, 0));
        }
        long first = crowd.second().stream().filter(message -> message.topic().startsWith(TOPICS.root() + "/player/")).count();
        long next = crowd.second().stream().filter(message -> message.topic().startsWith(TOPICS.root() + "/player/")).count();
        check(first == PresenceRuntime.FULL_UPDATES_PER_SECOND && next == 25 - first, "At most 20 updates a second for the bridge, the rest the next second: " + first + ", " + next);
    }

    private static String config(UUID player) {
        return TopicScheme.discoveryConfig(PREFIX, PresenceDevice.objectId(WORLD, player));
    }

    private static List<String> topics(List<Outbound> messages) {
        return messages.stream().map(message -> message.topic() + (message.payload().length == 0 ? " (clear)" : "")).toList();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    /** A presence runtime over a registry and a recording MQTT service, with the default config. */
    private static final class Fixture {
        final DeviceRegistry registry;
        final FakeMqtt mqtt = new FakeMqtt();
        final AtomicLong clock = new AtomicLong(1_000);
        final AtomicLong today = new AtomicLong(DAY);
        final AtomicBoolean tokens = new AtomicBoolean(true);
        final Set<UUID> hosts = new HashSet<>();
        final List<String> told = new ArrayList<>();
        final AtomicBoolean haOnline = new AtomicBoolean(true);
        final AtomicReference<TopicScheme> topics = new AtomicReference<>(TOPICS);
        // Where each player online stands, for full sharing.
        final Map<UUID, FullSample> samples = new HashMap<>();
        // The component removals asked of the discovery: object id and the removed keys.
        final List<Map.Entry<String, Set<String>>> removals = new ArrayList<>();
        final PresenceRuntime presence;

        Fixture() {
            this(new DeviceRegistry("world_7f3a", WORLD));
        }

        Fixture(DeviceRegistry registry) {
            MineQTTConfig.resetToDefaults();
            MineQTTConfig.haExpose = HaExpose.ALL;
            this.registry = registry;
            presence = new PresenceRuntime(registry, mqtt, topics::get, IDENTITY, true, tokens::get,
                    (objectId, removed, current) -> removals.add(Map.entry(objectId, removed.keySet())), haOnline::get, clock::get, today::get,
                    new PresenceRuntime.Game() {
                        @Override
                        public boolean host(UUID player) {
                            return hosts.contains(player);
                        }

                        @Override
                        public Optional<FullSample> sample(UUID player) {
                            return Optional.ofNullable(samples.get(player));
                        }

                        @Override
                        public void tellOps(String text) {
                            told.add(text);
                        }
                    }, SILENT);
        }

        /** The next sampling second, returning what was published. */
        List<Outbound> second() {
            clock.addAndGet(PresenceRuntime.SAMPLE_MILLIS);
            presence.tick();
            return mqtt.take();
        }

        // What PlayerPrivacy.choose does for a player online, without a server.
        void choose(UUID player, String name, Consent consent) {
            PlayerEntry before = registry.player(player).orElseGet(() -> PlayerEntry.of(player));
            PlayerEntry after = PlayerPrivacy.chosen(before, consent, hosts.contains(player), name, today.get());
            registry.putPlayer(after);
            presence.chosen(new PlayerPrivacy.Change(before, after));
        }
    }

    /** Records publishes. */
    private static final class FakeMqtt implements MqttService {
        final List<Outbound> published = new ArrayList<>();
        ConnectionState state = ConnectionState.CONNECTED;

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
            throw new UnsupportedOperationException("The presence devices subscribe to nothing");
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

package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.presence.Consent;
import art.rehra.mineqtt.presence.PlayerEntry;
import art.rehra.mineqtt.presence.PrivacyCommands;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.framework.tabs.CyberdeckExplorerTab;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Consent and presence (M4 plan D9, D10) in a real game, as the non-op player of a dedicated server:
 * <ul>
 *     <li>the join shows the privacy prompt once, with [Share presence] and [Don't share] (the server shares presence,
 *     not full data), and a rejoin does not show it again;</li>
 *     <li>{@code /mineqtt privacy} says nothing names the player;</li>
 *     <li>the command [Share presence] carries makes the player's presence device: a broker probe finds its retained
 *     discovery "Player0" under the bridge and {@code {"presence":"ON"}} on {@code player/<slug>}, and
 *     {@code /mineqtt privacy} lists it;</li>
 *     <li>the Traffic tab shows the player's own {@code player/} topic and hides another player's;</li>
 *     <li>on a server switched to full sharing, share full adds health, dimension and position, walking 6 blocks
 *     publishes a new position, and share on drops them again;</li>
 *     <li>leaving publishes OFF, joining ON again;</li>
 *     <li>{@code share off} clears discovery and state.</li>
 * </ul>
 * Needs a broker; without one the suite is skipped.
 */
public final class PrivacyGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final int CHAT_TICKS = 100;
    private static final int BROKER_TICKS = 200;
    private static final int LISTEN_TIMEOUT_TICKS = 200;
    private static final String SHARE_ON = PrivacyCommands.SHARE_COMMAND + "on";
    private static final String SHARE_OFF = PrivacyCommands.SHARE_COMMAND + "off";

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "privacy", PrivacyGameTest::run);
    }

    private static void run(GameTestSession session) {
        if (session.broker().isEmpty()) {
            session.skip("consent and presence devices", GameTestSession.BROKER_ENV + " is not set");
            return;
        }
        session.teleport(STAND);
        TopicScheme topics = session.onRuntime(MineqttRuntime::topics);

        // The prompt of this suite's join: the newest line with the share buttons.
        session.check(session.onRuntime(runtime -> runtime.registry().player(session.playerId()).map(PlayerEntry::asked).orElse(false)),
                "the join showed the privacy prompt (the player is stored as asked)");
        Optional<ChatLog.Line> prompt = ChatLog.since(0).reversed().stream().filter(line -> line.commands().contains(SHARE_ON)).findFirst();
        session.check(prompt.isPresent() && prompt.get().commands().equals(List.of(SHARE_ON, SHARE_OFF)),
                "the prompt offers [Share presence] and [Don't share], no [Share all] on a presence server (" + prompt + ")");
        prompt.ifPresent(line -> session.check(line.text().contains("[Share presence]") && line.text().contains("[Don't share]"), "with the buttons' labels: " + line.text()));

        int mark = ChatLog.mark();
        session.playerCommand("mineqtt privacy");
        Optional<ChatLog.Line> report = session.awaitChat(mark, line -> line.text().startsWith("Your choice:"), CHAT_TICKS);
        session.check(report.isPresent() && report.get().text().contains("not made, so nothing is shared") && report.get().text().contains("nothing that names you"),
                "/mineqtt privacy says nothing names the player (" + report + ")");

        mark = ChatLog.mark();
        session.reconnect();
        session.teleport(STAND);
        session.context().waitTicks(40);
        session.check(ChatLog.since(mark).stream().noneMatch(line -> line.commands().contains(SHARE_ON)), "a rejoin shows no prompt again (" + ChatLog.since(mark) + ")");

        String configTopic = TopicScheme.discoveryConfig(MineQTTConfig.discoveryPrefix, session.onRuntime(runtime -> runtime.presence().objectId(session.playerId())));
        String intruder = topics.player("player_intruder");
        try (BrokerProbe probe = BrokerProbe.connect(session.broker().get())) {
            // What a click on [Share presence] sends: the command it carries, as the player.
            String command = prompt.map(line -> line.commands().getFirst()).orElse(SHARE_ON);
            session.playerCommand(command.substring(1));
            Optional<JsonObject> config = awaitRetained(session, probe, configTopic, json -> true);
            session.check(config.isPresent() && config.get().getAsJsonObject("dev").get("name").getAsString().equals("Player " + session.playerName())
                            && config.get().getAsJsonObject("dev").get("mdl").getAsString().equals("MineQTT Player"),
                    "share on discovers the presence device \"Player " + session.playerName() + "\" (" + config + ")");
            Optional<String> slug = session.onRuntime(runtime -> runtime.presence().slugOf(session.playerId()));
            session.check(slug.isPresent(), "the player's entry holds the device's slug");
            String stateTopic = topics.player(slug.orElse("player_player0"));
            Optional<JsonObject> on = awaitRetained(session, probe, stateTopic, json -> json.get("presence").getAsString().equals("ON"));
            session.check(on.isPresent(), "its state on " + stateTopic + " is ON (" + on + ")");
            session.check(config.isPresent() && config.get().getAsJsonObject("cmps").getAsJsonObject("presence").get("state_topic").getAsString().equals(stateTopic),
                    "the presence sensor reads that topic");

            mark = ChatLog.mark();
            session.playerCommand("mineqtt privacy");
            Optional<ChatLog.Line> shared = session.awaitChat(mark, line -> line.text().startsWith("Your choice:"), CHAT_TICKS);
            session.check(shared.isPresent() && shared.get().text().contains("share presence") && shared.get().text().contains("your presence device"),
                    "/mineqtt privacy now lists the name and the presence device (" + shared + ")");

            explorer(session, probe, stateTopic, intruder);
            fullSharing(session, probe, configTopic, stateTopic);

            try (BrokerProbe.Listener states = probe.listen(stateTopic)) {
                session.reconnect();
                session.teleport(STAND);
                List<String> seen = new ArrayList<>();
                session.await(() -> {
                    states.nextLive().ifPresent(seen::add);
                    return seen.size() >= 2;
                }, BROKER_TICKS);
                session.check(seen.equals(List.of("{\"presence\":\"OFF\"}", "{\"presence\":\"ON\"}")), "leaving publishes OFF, joining ON (" + seen + ")");
            }

            session.playerCommand("mineqtt privacy share off");
            boolean cleared = session.await(() -> probe.retained(configTopic).isEmpty() && probe.retained(stateTopic).isEmpty(), BROKER_TICKS);
            session.check(cleared, "share off clears the retained discovery and state");
            Optional<PlayerEntry> entry = session.onRuntime(runtime -> runtime.registry().player(session.playerId()));
            session.check(entry.isPresent() && entry.get().consent() == Consent.OFF && entry.get().slug().isEmpty() && entry.get().name().isEmpty(),
                    "the entry keeps the choice and nothing personal (" + entry + ")");
        }
    }

    /**
     * D10 full, on a server switched to {@code telemetry.players=full}: share full adds health, dimension and position to
     * the state and the three sensors to the discovery; walking 6 blocks publishes a new position; share on drops them.
     */
    private static void fullSharing(GameTestSession session, BrokerProbe probe, String configTopic, String stateTopic) {
        TelemetryPlayers before = session.onServer(server -> {
            TelemetryPlayers mode = MineQTTConfig.telemetryPlayers;
            MineQTTConfig.telemetryPlayers = TelemetryPlayers.FULL;
            MineqttRuntime.of(server).presence().configChanged();
            return mode;
        });
        try {
            session.playerCommand("mineqtt privacy share full");
            Optional<JsonObject> full = awaitRetained(session, probe, stateTopic, json -> json.has("health") && json.has("x"));
            session.check(full.isPresent() && full.get().get("dimension").getAsString().equals("minecraft:overworld") && full.get().get("x").getAsInt() == STAND.getX()
                    && full.get().get("y").getAsInt() == STAND.getY(), "share full publishes health, dimension and block position (" + full + ")");
            Optional<JsonObject> sensors = awaitRetained(session, probe, configTopic, json -> json.getAsJsonObject("cmps").keySet().containsAll(List.of("health", "dimension", "position")));
            session.check(sensors.isPresent(), "and discovers the health, dimension and position sensors (" + sensors.map(json -> json.getAsJsonObject("cmps").keySet()) + ")");

            BlockPos away = STAND.offset(6, 0, 0);
            session.teleport(away);
            Optional<JsonObject> moved = awaitRetained(session, probe, stateTopic, json -> json.has("x") && json.get("x").getAsInt() == away.getX());
            session.check(moved.isPresent(), "walking 6 blocks publishes the new position within the 5 s floor (" + moved + ")");
            session.teleport(STAND);

            session.playerCommand("mineqtt privacy share on");
            Optional<JsonObject> narrowed = awaitRetained(session, probe, stateTopic, json -> !json.has("x") && !json.has("health"));
            session.check(narrowed.isPresent() && narrowed.get().get("presence").getAsString().equals("ON"), "share on drops health and position from the state (" + narrowed + ")");
            Optional<JsonObject> plain = awaitRetained(session, probe, configTopic, json -> {
                JsonObject components = json.getAsJsonObject("cmps");
                return List.of("health", "dimension", "position").stream().noneMatch(key -> components.has(key) && components.getAsJsonObject(key).size() > 1);
            });
            session.check(plain.isPresent(), "and the discovery declares the sensors no more (" + plain.map(json -> json.getAsJsonObject("cmps").keySet()) + ")");
        } finally {
            session.onServer(server -> {
                MineQTTConfig.telemetryPlayers = before;
                MineqttRuntime.of(server).presence().configChanged();
                return null;
            });
        }
    }

    /** The Traffic tab shows the player's own presence topic and hides another player's. */
    private static void explorer(GameTestSession session, BrokerProbe probe, String own, String intruder) {
        try {
            // Retained before listening: the explorer gets both on subscribe, and the server judges each.
            probe.publish(intruder, "{\"presence\":\"ON\"}", true);
            session.hold(MineqttItems.Cyberdeck.get());
            session.useHeldItem();
            session.check(session.clickTab(CyberdeckExplorerTab.ID), "the Configurator opens its Traffic tab");
            session.clickButton("Listen: OFF");
            session.awaitClient(mc -> CyberdeckScreen.discoveredTopics().containsKey(own), LISTEN_TIMEOUT_TICKS);
            session.context().waitTicks(20);
            Map<String, String> seen = session.onClient(mc -> new TreeMap<>(CyberdeckScreen.discoveredTopics()));
            session.check(seen.containsKey(own), "the player's own presence topic reaches the explorer (" + seen.keySet() + ")");
            session.check(!seen.containsKey(intruder), "another player's presence topic stays hidden");
            session.screenshot("privacy_traffic");
            session.clickButton("Listen: ON");
            session.closeScreen();
        } finally {
            probe.publish(intruder, "", true);
        }
    }

    /** The retained JSON on a topic once it meets the condition, read every half second; empty after the timeout. */
    private static Optional<JsonObject> awaitRetained(GameTestSession session, BrokerProbe probe, String topic, Predicate<JsonObject> condition) {
        for (int waited = 0; waited <= BROKER_TICKS; waited += 10) {
            Optional<JsonObject> json = probe.retained(topic).filter(text -> !text.isEmpty()).map(text -> JsonParser.parseString(text).getAsJsonObject());
            if (json.isPresent() && condition.test(json.get())) return json;
            session.context().waitTicks(10);
        }
        return Optional.empty();
    }
}

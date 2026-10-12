package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.config.MineQTTConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The bridge's world telemetry and player events (M4 plan D6, D7) in a real game. The retained telemetry counts the
 * joined player but names nobody (the player of a dedicated server has not consented, D9), carries the world keys, and
 * turns night within seconds of {@code /time set}. The player leaves and joins again: the bridge publishes an anonymous
 * leave and join on {@code bridge/event}. A death ({@code /kill}) and an announced advancement of that player publish
 * nothing, since neither is published for a player who has not consented. Then the player runs
 * {@code /mineqtt privacy share on}: the telemetry names them, and a death and an advancement are published with their
 * name, the death by its message key; {@code share off} makes them anonymous again. Needs a broker; without one every
 * step is skipped.
 */
public final class TelemetryGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    // Significant changes go out at the next sample, at most every 2 s; allow for a slow server.
    private static final int TELEMETRY_TICKS = 120;
    private static final int EVENT_TICKS = 100;
    private static final int QUIET_TICKS = 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "telemetry", TelemetryGameTest::run);
    }

    private static void run(GameTestSession session) {
        if (session.broker().isEmpty()) {
            session.skip("the bridge telemetry and player events", "MQTT disabled");
            return;
        }
        session.teleport(STAND);
        String root = MineQTTConfig.baseTopic + "/" + GameTestSession.BRIDGE_ID;
        String telemetryTopic = root + "/bridge";
        try (BrokerProbe probe = BrokerProbe.connect(session.broker().get())) {
            session.consoleCommand("gamerule advance_time false");
            // Night first: the new world starts at day, so only a change back proves the day was published anew.
            session.consoleCommand("time set 13000");
            Optional<JsonObject> night = awaitTelemetry(session, probe, telemetryTopic, telemetry -> telemetry.has("is_day") && !telemetry.get("is_day").getAsBoolean());
            session.check(night.isPresent(), "/time set 13000 turns is_day off within seconds (" + night + ")");
            night.ifPresent(telemetry -> session.check(telemetry.get("time_of_day").getAsString().equals("19:00"),
                    "tick 13000 reads 19:00 (" + telemetry.get("time_of_day") + ")"));

            session.consoleCommand("time set 1000");
            Optional<JsonObject> day = awaitTelemetry(session, probe, telemetryTopic, telemetry -> telemetry.has("is_day") && telemetry.get("is_day").getAsBoolean());
            session.check(day.isPresent(), "/time set 1000 turns is_day on within seconds (" + day + ")");
            day.ifPresent(telemetry -> {
                session.check(telemetry.get("players_online").getAsInt() == 1, "it counts the joined player (" + telemetry + ")");
                session.check(telemetry.getAsJsonArray("players").isEmpty(), "and names nobody, since no player has consented (" + telemetry + ")");
                for (String key : List.of("tps", "mspt", "time_of_day", "day", "weather", "dropped_inbound", "dropped_outbound")) {
                    session.check(telemetry.has(key), "it carries " + key);
                }
                session.check(telemetry.get("time_of_day").getAsString().equals("07:00"), "tick 1000 reads 07:00 (" + telemetry.get("time_of_day") + ")");
            });

            try (BrokerProbe.Listener events = probe.listen(root + "/bridge/event")) {
                // No death or advancement is published for a player who has not consented (D7).
                session.consoleCommand("gamerule immediate_respawn true");
                session.consoleCommand("advancement grant " + session.playerName() + " only minecraft:story/mine_stone");
                session.consoleCommand("kill " + session.playerName());
                session.context().waitTicks(QUIET_TICKS);
                List<JsonObject> quiet = drain(events);
                session.check(quiet.isEmpty(), "a death and an announced advancement of a player who has not consented publish nothing (" + quiet + ")");
                session.awaitServer(server -> session.player(server) != null && session.player(server).isAlive());
                session.teleport(STAND);

                // Leaving and joining again: one anonymous event each.
                session.reconnect();
                List<JsonObject> seen = new ArrayList<>();
                session.await(() -> {
                    seen.addAll(drain(events));
                    return seen.size() >= 2;
                }, EVENT_TICKS);
                session.check(seen.size() == 2 && seen.get(0).toString().equals("{\"event_type\":\"leave\"}")
                        && seen.get(1).toString().equals("{\"event_type\":\"join\"}"), "leaving and joining publish an anonymous leave and join (" + seen + ")");
            }
            Optional<JsonObject> back = awaitTelemetry(session, probe, telemetryTopic, telemetry -> telemetry.get("players_online").getAsInt() == 1);
            session.check(back.isPresent() && back.get().getAsJsonArray("players").isEmpty(), "the telemetry counts the player again, unnamed (" + back + ")");

            // Consent (D9): the player's own command names them, and their deaths and advancements are published.
            session.playerCommand("mineqtt privacy share on");
            Optional<JsonObject> named = awaitTelemetry(session, probe, telemetryTopic, telemetry -> telemetry.getAsJsonArray("players").size() == 1);
            session.check(named.isPresent() && named.get().getAsJsonArray("players").get(0).getAsString().equals(session.playerName()),
                    "after /mineqtt privacy share on the telemetry names the player (" + named + ")");
            try (BrokerProbe.Listener events = probe.listen(root + "/bridge/event")) {
                session.consoleCommand("advancement grant " + session.playerName() + " only minecraft:story/smelt_iron");
                session.consoleCommand("kill " + session.playerName());
                List<JsonObject> seen = new ArrayList<>();
                session.await(() -> {
                    seen.addAll(drain(events));
                    return seen.size() >= 2;
                }, EVENT_TICKS);
                String name = session.playerName();
                session.check(seen.stream().anyMatch(event -> event.toString().equals("{\"event_type\":\"advancement\",\"player\":\"" + name
                        + "\",\"advancement\":\"minecraft:story/smelt_iron\"}")), "a consenting player's announced advancement is published with their name (" + seen + ")");
                session.check(seen.stream().anyMatch(event -> event.get("event_type").getAsString().equals("death") && event.get("player").getAsString().equals(name)
                        && event.get("cause").getAsString().startsWith("death.attack.")), "and their death, by its message key (" + seen + ")");
                session.awaitServer(server -> session.player(server) != null && session.player(server).isAlive());
                session.teleport(STAND);
            }
            session.playerCommand("mineqtt privacy share off");
            Optional<JsonObject> anonymous = awaitTelemetry(session, probe, telemetryTopic, telemetry -> telemetry.getAsJsonArray("players").isEmpty());
            session.check(anonymous.isPresent(), "after share off the telemetry names nobody again (" + anonymous + ")");
        }
    }

    private interface Condition {
        boolean test(JsonObject telemetry);
    }

    /** The retained telemetry once it meets the condition, read every half second; empty after the timeout. */
    private static Optional<JsonObject> awaitTelemetry(GameTestSession session, BrokerProbe probe, String topic, Condition condition) {
        Optional<JsonObject> last = Optional.empty();
        for (int waited = 0; waited <= TELEMETRY_TICKS; waited += 10) {
            last = probe.retained(topic).map(text -> JsonParser.parseString(text).getAsJsonObject());
            if (last.isPresent() && condition.test(last.get())) return last;
            session.context().waitTicks(10);
        }
        return Optional.empty();
    }

    private static List<JsonObject> drain(BrokerProbe.Listener listener) {
        List<JsonObject> messages = new ArrayList<>();
        Optional<String> next;
        while ((next = listener.nextLive()).isPresent()) messages.add(JsonParser.parseString(next.get()).getAsJsonObject());
        return messages;
    }
}

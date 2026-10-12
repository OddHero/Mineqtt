package art.rehra.mineqtt.telemetry;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.TelemetryPlayers;
import art.rehra.mineqtt.ha.BridgeDevice;
import art.rehra.mineqtt.runtime.WorldClock;
import art.rehra.mineqtt.telemetry.core.NamePolicy;
import art.rehra.mineqtt.telemetry.core.WorldReading;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Reads the world for the bridge's telemetry (M4 plan D6): the player count, the names the name policy allows, the
 * tick rate and average tick time, and the overworld's clock and weather. Server thread only.
 */
public final class TelemetrySampler {
    private final MinecraftServer server;
    private final Predicate<UUID> consents;

    /** @param consents whether a player's effective consent lets them be named ({@code PlayerPrivacy.consents}) */
    public TelemetrySampler(MinecraftServer server, Predicate<UUID> consents) {
        this.server = server;
        this.consents = consents;
    }

    /** The telemetry config as the bridge reads it at every sample. */
    public static BridgeDevice.Settings settings() {
        return new BridgeDevice.Settings(MineQTTConfig.telemetryEnabled, MineQTTConfig.telemetryIntervalSeconds, MineQTTConfig.telemetryAllowBroadcast);
    }

    /** One reading of the world. */
    public WorldReading sample() {
        NamePolicy names = namePolicy();
        List<String> named = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (names.mayName(player.getUUID())) named.add(player.getGameProfile().name());
        }
        ServerLevel overworld = server.overworld();
        ServerTickRateManager ticks = server.tickRateManager();
        return new WorldReading(server.getPlayerCount(), named, ticks.tickrate(), ticks.isFrozen(), server.getAverageTickTimeNanos(),
                WorldClock.overworldTicks(overworld), overworld.isRaining(), overworld.isThundering());
    }

    /**
     * The telemetry line of {@code /mineqtt status} and the Bridge Console (M4 plan §1.3) for a reading of the world: how
     * often the telemetry goes out, what it shares about players, and whether Home Assistant may send broadcast text.
     */
    public String statusLine(WorldReading reading) {
        return statusLine(MineQTTConfig.telemetryEnabled, MineQTTConfig.telemetryIntervalSeconds, MineQTTConfig.telemetryPlayers,
                MineQTTConfig.telemetryPublishPlayerNames, reading.players().size(), MineQTTConfig.telemetryAllowBroadcast);
    }

    /**
     * The telemetry line, such as {@code Telemetry: every 10 s · players: presence by consent (3 sharing) · broadcast off}.
     *
     * @param worldKeys          {@code telemetry.enabled}: false publishes the player count and the drop counters only
     * @param publishPlayerNames {@code telemetry.publishPlayerNames}: false shares counts only
     * @param sharing            how many online players the name policy names
     * @param broadcast          {@code telemetry.allowBroadcast}
     */
    public static String statusLine(boolean worldKeys, int intervalSeconds, TelemetryPlayers players, boolean publishPlayerNames, int sharing,
                                    boolean broadcast) {
        String shared = switch (players) {
            case OFF -> "not shared";
            case PRESENCE, FULL -> publishPlayerNames
                    ? players.name().toLowerCase(Locale.ROOT) + " by consent (" + sharing + " sharing)"
                    : "counts only";
        };
        return "Telemetry: " + (worldKeys ? "every " + intervalSeconds + " s" : "player count only") + " · players: " + shared
                + " · broadcast " + (broadcast ? "on" : "off");
    }

    /**
     * Who may be named now (M4 plan D9): the config's veto and upper bound over each player's consent, as the players chose
     * it with {@code /mineqtt privacy share}; the host of an integrated server counts as consenting until they say
     * otherwise.
     */
    public NamePolicy namePolicy() {
        return NamePolicy.of(consents, MineQTTConfig.telemetryPublishPlayerNames, MineQTTConfig.telemetryPlayers != TelemetryPlayers.OFF);
    }
}

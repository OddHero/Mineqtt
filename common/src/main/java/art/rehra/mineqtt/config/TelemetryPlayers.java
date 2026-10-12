package art.rehra.mineqtt.config;

/**
 * Config {@code telemetry.players} (M4 plan D9, D16): the most this server publishes about a player. It is an upper
 * bound only: each player's own consent still decides, and {@code telemetry.publishPlayerNames=false} vetoes names.
 */
public enum TelemetryPlayers {
    /** Counts only: no player name, presence or coordinate leaves the server. */
    OFF,
    /** Names in the telemetry and the player events, and a presence device, for players who consent. */
    PRESENCE,
    /** Also health, dimension and position, for players who consent to full sharing. */
    FULL
}

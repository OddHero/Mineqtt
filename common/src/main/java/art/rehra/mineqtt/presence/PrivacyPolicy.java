package art.rehra.mineqtt.presence;

import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.config.TelemetryPlayers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one rule of M4 plan D9: no player name, presence or coordinate leaves the server without that player's consent,
 * and the server's settings only ever narrow it.
 * <ul>
 *     <li><b>Names</b> (the telemetry's {@code players}, the player events' {@code player}, deaths and advancements): an
 *     effective consent, {@code telemetry.publishPlayerNames} and {@code telemetry.players} other than {@code off}.</li>
 *     <li><b>A presence device</b>: the same, since {@code telemetry.players} is then {@code presence} or
 *     {@code full}.</li>
 *     <li><b>Full data</b> (health, dimension, position): consent {@code full} and {@code telemetry.players=full}.</li>
 * </ul>
 * {@code publishPlayerNames=false} is an admin veto: counts only, no names anywhere, no presence devices. It never
 * names a player who does not consent. Pure apart from {@link #current()}, so {@code verifyPrivacy} runs the matrix.
 *
 * @param mode               {@code telemetry.players}, the server's upper bound
 * @param publishPlayerNames {@code telemetry.publishPlayerNames}
 */
public record PrivacyPolicy(TelemetryPlayers mode, boolean publishPlayerNames) {
    /** How much is shared about one player, or offered by the server. */
    public enum Shared {
        NOTHING,
        /** The name and a presence device. */
        PRESENCE,
        /** Also health, dimension and position. */
        FULL
    }

    /** What the first-join prompt shows a player ({@link #prompt}). */
    public enum Prompt {
        NONE,
        /** {@code [Share presence] [Share all] [Don't share]}; {@code [Share all]} only where the server shares it. */
        CHOICES,
        /** The integrated host, who shares presence until they say otherwise: a note with {@code [Stop sharing]}. */
        HOST_NOTE
    }

    public PrivacyPolicy {
        if (mode == null) throw new IllegalArgumentException("A privacy policy needs telemetry.players");
    }

    /** The policy the loaded config sets. */
    public static PrivacyPolicy current() {
        return new PrivacyPolicy(MineQTTConfig.telemetryPlayers, MineQTTConfig.telemetryPublishPlayerNames);
    }

    /** What the server would share about a player who consents to everything; {@link Shared#NOTHING} means counts only. */
    public Shared offered() {
        if (!publishPlayerNames || mode == TelemetryPlayers.OFF) return Shared.NOTHING;
        return mode == TelemetryPlayers.FULL ? Shared.FULL : Shared.PRESENCE;
    }

    /** What is shared about a player of this effective consent ({@link Consent#effective}). */
    public Shared shared(Consent effective) {
        Shared offered = offered();
        if (offered == Shared.NOTHING || !effective.consents()) return Shared.NOTHING;
        return effective == Consent.FULL && offered == Shared.FULL ? Shared.FULL : Shared.PRESENCE;
    }

    /** Whether a player of this effective consent may be named, and their deaths and advancements published. */
    public boolean mayName(Consent effective) {
        return shared(effective) != Shared.NOTHING;
    }

    /** Whether a player of this effective consent gets a presence device (D10), within the device cap. */
    public boolean presence(Consent effective) {
        return mayName(effective);
    }

    /** Whether a player of this effective consent has health, dimension and position published (D10 {@code full}). */
    public boolean full(Consent effective) {
        return shared(effective) == Shared.FULL;
    }

    /**
     * What a joining player is shown (D9), once per player: nothing once they were asked, nothing while the server could
     * not share anything about them ({@code telemetry.players=off}, {@code publishPlayerNames=false}, no broker, or
     * {@code ha.expose=NONE}), nothing once they chose; the host of an integrated server is told they share presence,
     * everyone else is offered the choices.
     *
     * @param host         whether the player owns this integrated server
     * @param bridgeShares whether MQTT is configured and {@code ha.expose} is not {@code NONE}
     */
    public Prompt prompt(PlayerEntry entry, boolean host, boolean bridgeShares) {
        if (entry.asked() || entry.consent() != Consent.UNSET || offered() == Shared.NOTHING || !bridgeShares) return Prompt.NONE;
        return host ? Prompt.HOST_NOTE : Prompt.CHOICES;
    }

    /**
     * The lines of {@code /mineqtt privacy} (D9): the player's choice, what the server shares at most, exactly what is
     * published about the player now, and what is published about everyone.
     *
     * @param explicit       the player's stored consent
     * @param host           whether the player owns this integrated server
     * @param mqttConfigured whether a broker is configured; without one nothing leaves the server
     * @param presenceDevice the player's presence device as it is published (its Home Assistant name and topic), if any
     */
    public List<String> report(Consent explicit, boolean host, boolean mqttConfigured, Optional<String> presenceDevice) {
        Consent effective = explicit.effective(host);
        List<String> lines = new ArrayList<>();
        lines.add("Your choice: " + switch (explicit) {
            case UNSET -> host ? "not made; as the host of this world you share presence until you say otherwise" : "not made, so nothing is shared";
            case PRESENCE -> "share presence";
            case FULL -> "share all";
            case OFF -> "don't share";
        });
        lines.add("This server shares at most: " + switch (offered()) {
            case NOTHING -> publishPlayerNames ? "nothing about players (telemetry.players is off)" : "nothing about players (player names are turned off)";
            case PRESENCE -> "names and presence";
            case FULL -> "names, presence, health, dimension and position";
        });
        if (!mqttConfigured) {
            lines.add("MQTT is not configured on this server, so nothing is published now.");
        } else {
            Shared shared = shared(effective);
            if (shared == Shared.NOTHING) {
                lines.add("Published about you now: nothing that names you.");
            } else {
                lines.add("Published about you now:");
                lines.add("- your name in the bridge's players online, and on your join and leave events");
                lines.add("- your deaths (the kind of death, never the message) and the advancements announced in chat");
                presenceDevice.ifPresent(device -> lines.add("- your presence device " + device + ": online or offline"));
                if (shared == Shared.FULL && presenceDevice.isPresent()) lines.add("- your health, dimension and block position, at most every 5 s");
                if (explicit == Consent.FULL && offered() == Shared.PRESENCE) lines.add("(You chose to share all; this server shares presence only.)");
            }
        }
        lines.add("Published about everyone, not personal: the number of players online and anonymous join and leave events.");
        return lines;
    }
}

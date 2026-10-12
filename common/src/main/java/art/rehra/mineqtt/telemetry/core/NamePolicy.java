package art.rehra.mineqtt.telemetry.core;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who may be named in the bridge's telemetry ({@code players}) and its player events (M4 plan D9): a player who
 * consents, unless the admin vetoes names ({@code telemetry.publishPlayerNames=false}) or the server shares nothing
 * about players ({@code telemetry.players=off}). The admin settings only ever narrow: they never name a player who
 * does not consent. Counts are not personal and need no consent.
 * <p>
 * The same rule gates the death and advancement events, which carry nothing useful without a name.
 */
public final class NamePolicy {
    /** Names nobody, whatever anyone chose. */
    public static final NamePolicy NOBODY = new NamePolicy(player -> false, false, false);

    private final Predicate<UUID> consents;
    private final boolean publishPlayerNames;
    private final boolean serverSharesPlayers;

    private NamePolicy(Predicate<UUID> consents, boolean publishPlayerNames, boolean serverSharesPlayers) {
        this.consents = consents;
        this.publishPlayerNames = publishPlayerNames;
        this.serverSharesPlayers = serverSharesPlayers;
    }

    /**
     * @param consents            a player's effective consent to be named: their choice ({@code /mineqtt privacy share}), or
     *                            for the host of an integrated server, consent until they say otherwise
     * @param publishPlayerNames  {@code telemetry.publishPlayerNames}: false names nobody
     * @param serverSharesPlayers {@code telemetry.players} is not {@code off}
     */
    public static NamePolicy of(Predicate<UUID> consents, boolean publishPlayerNames, boolean serverSharesPlayers) {
        return new NamePolicy(consents, publishPlayerNames, serverSharesPlayers);
    }

    /** Whether {@code player} may be named, and their death and advancement events published. */
    public boolean mayName(UUID player) {
        return publishPlayerNames && serverSharesPlayers && consents.test(player);
    }
}

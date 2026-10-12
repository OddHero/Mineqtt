package art.rehra.mineqtt.presence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Locale;

/**
 * What a player chose to share about themselves (M4 plan D9). No player name, presence or coordinate leaves the server
 * without it; counts are not personal and need none. The server's {@code telemetry.players} and
 * {@code telemetry.publishPlayerNames} only ever narrow it ({@link PrivacyPolicy}).
 */
public enum Consent {
    /** Not chosen yet: shares nothing, except for the host of an integrated server ({@link #effective}). */
    UNSET,
    /** {@code /mineqtt privacy share on}: the name and a presence device. */
    PRESENCE,
    /** {@code /mineqtt privacy share full}: also health, dimension and position, where the server shares them. */
    FULL,
    /** {@code /mineqtt privacy share off}: nothing, also for the host. */
    OFF;

    /** Stored by its lower-case name; an unknown name makes the entry unreadable, so it is skipped and reported. */
    public static final Codec<Consent> CODEC = Codec.STRING.comapFlatMap(Consent::parse, Consent::key);

    /** The stored and typed name: {@code unset}, {@code presence}, {@code full}, {@code off}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this consent lets the player be named (and get a presence device where the server makes them). */
    public boolean consents() {
        return this == PRESENCE || this == FULL;
    }

    /**
     * The consent that counts (D9): an explicit choice wins; {@link #UNSET} counts as {@link #PRESENCE} only for the
     * owner of an integrated server ({@code MinecraftServer.isSingleplayerOwner}), who owns the world and the Home
     * Assistant it reports to. Every other player, LAN guests included, shares nothing until they say so.
     *
     * @param singleplayerOwner whether the player hosts this integrated server
     */
    public Consent effective(boolean singleplayerOwner) {
        return this == UNSET && singleplayerOwner ? PRESENCE : this;
    }

    private static DataResult<Consent> parse(String key) {
        for (Consent consent : values()) {
            if (consent.key().equals(key)) return DataResult.success(consent);
        }
        return DataResult.error(() -> "Unknown consent: " + key);
    }
}

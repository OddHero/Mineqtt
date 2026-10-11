package art.rehra.mineqtt.twin.core;

import java.util.Locale;
import java.util.Optional;

/**
 * How a bound device's source is doing (M3 plan D6), shown on its state topic's {@code binding} object, in
 * {@code devices info} and {@code twin status}, and to clients as a render hint ({@link Hint}). Pure and Minecraft-free.
 */
public enum TwinHealth {
    /** Nothing known of the source yet, or its retained state was cleared: the device shows what it showed. */
    WAITING,
    /** A live value, or a catalog entry vouched for since Home Assistant's last start. */
    LIVE,
    /** Only retained or older data ("as of 2 days ago (retained)"). */
    RETAINED,
    /** Home Assistant has no value for it: the device holds its last value (or turns off with {@code twin.unavailable off}). */
    UNAVAILABLE,
    /** Home Assistant does not know its state: held like {@link #UNAVAILABLE}. */
    UNKNOWN,
    /** It left the catalog: Home Assistant no longer shares it, so it is not read. */
    NOT_SHARED,
    /** The rules refuse the binding now; it heals when they allow it again. */
    REFUSED,
    /** Its write-back tripped the circuit breaker (D10, M3-9); reading goes on. */
    CONTROL_PAUSED;

    /** What a client draws for a health (D19's visual language); only what changes the drawing. */
    public enum Hint {
        /** Drawn as always: unbound, live, retained or waiting. */
        NONE,
        /** Holds with a desaturated tint and a smoke puff now and then. */
        UNAVAILABLE,
        /** One red particle when it becomes refused. */
        REFUSED,
        /** A red dust particle now and then. */
        PAUSED;

        /** The hint a render update carries ({@link #ordinal()}); an unknown byte reads as {@link #NONE}. */
        public static Hint ofByte(int value) {
            return value >= 0 && value < values().length ? values()[value] : NONE;
        }
    }

    /**
     * The health of a working binding's source, pure (D3, D6): {@link #WAITING} without a document or after a cleared
     * state, {@link #UNAVAILABLE} and {@link #UNKNOWN} for those states, {@link #NOT_SHARED} when the read gate no longer
     * lets the entity through, else its freshness. A refused binding is {@link #REFUSED} whatever its source says.
     *
     * @param active   whether the binding works ({@link Binding#active()})
     * @param document the source's document, if one is known
     * @param readable whether the read gate lets the entity through now ({@link EntityDocuments#readable})
     */
    public static TwinHealth of(boolean active, Optional<EntityDocument> document, boolean readable) {
        if (!active) return REFUSED;
        if (!readable) return NOT_SHARED;
        if (document.isEmpty()) return WAITING;
        EntityDocument known = document.get();
        return switch (known.state()) {
            case StatestreamValue.Unavailable ignored -> UNAVAILABLE;
            case StatestreamValue.Unknown ignored -> UNKNOWN;
            case StatestreamValue.Cleared ignored -> WAITING;
            case StatestreamValue.Text ignored -> known.freshness() == EntityDocument.Freshness.LIVE ? LIVE : RETAINED;
        };
    }

    /** The word the state topic's {@code binding.health} carries: {@code live}, {@code not_shared}, … */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** What clients draw for it. */
    public Hint hint() {
        return switch (this) {
            case WAITING, LIVE, RETAINED -> Hint.NONE;
            case UNAVAILABLE, UNKNOWN, NOT_SHARED -> Hint.UNAVAILABLE;
            case REFUSED -> Hint.REFUSED;
            case CONTROL_PAUSED -> Hint.PAUSED;
        };
    }

    /** Whether the device holds a value it no longer gets: its source has none now. */
    public boolean holding() {
        return hint() == Hint.UNAVAILABLE;
    }
}

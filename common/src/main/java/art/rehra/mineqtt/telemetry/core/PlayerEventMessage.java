package art.rehra.mineqtt.telemetry.core;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A player event on the bridge's {@code bridge/event} topic (M4 plan D7), the payload of its {@code player_event} event
 * entity: {@code {"event_type":"join"}}, with {@code "player"} only for a player the {@link NamePolicy} may name.
 * <ul>
 *     <li>Join and leave are published for every player, anonymous unless the player may be named.</li>
 *     <li>Death and advancement only for a player who may be named (anonymous they are useless and leak a little): a
 *     death carries the damage type's message key ({@code death.attack.<msgId>}), never the rendered message, which
 *     names the killer; an advancement only when Minecraft would announce it in chat, so recipe unlocks and hidden
 *     advancements never reach the topic.</li>
 * </ul>
 * The factories decide all of it, so the Minecraft side only reads the game.
 *
 * @param player whose event it is, for the caps of {@link EventLimiter}; never published
 * @param name   the player's name, present only when the player may be named
 * @param detail the death's message key or the advancement's id
 */
public record PlayerEventMessage(Type type, UUID player, Optional<String> name, Optional<String> detail) {
    public enum Type {
        JOIN(5_000, null),
        LEAVE(5_000, null),
        DEATH(1_000, "cause"),
        ADVANCEMENT(1_000, "advancement");

        private final long minIntervalMillis;
        private final String detailKey;

        Type(long minIntervalMillis, String detailKey) {
            this.minIntervalMillis = minIntervalMillis;
            this.detailKey = detailKey;
        }

        /** The {@code event_type}, one of the entity's {@code event_types}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The shortest time between two events of this type of one player: rejoin spam, death loops. */
        public long minIntervalMillis() {
            return minIntervalMillis;
        }
    }

    /** A player joined: published for everyone, named only when {@code names} allows it. */
    public static PlayerEventMessage join(UUID player, String name, NamePolicy names) {
        return new PlayerEventMessage(Type.JOIN, player, names.mayName(player) ? Optional.of(name) : Optional.empty(), Optional.empty());
    }

    /** A player left: published for everyone, named only when {@code names} allows it. */
    public static PlayerEventMessage leave(UUID player, String name, NamePolicy names) {
        return new PlayerEventMessage(Type.LEAVE, player, names.mayName(player) ? Optional.of(name) : Optional.empty(), Optional.empty());
    }

    /**
     * A player died, for a player who may be named only.
     *
     * @param msgId the damage source's message id ({@code DamageSource.getMsgId()}), e.g. {@code fall} or {@code mob}
     */
    public static Optional<PlayerEventMessage> death(UUID player, String name, String msgId, NamePolicy names) {
        if (!names.mayName(player)) return Optional.empty();
        return Optional.of(new PlayerEventMessage(Type.DEATH, player, Optional.of(name), Optional.of("death.attack." + msgId)));
    }

    /**
     * A player completed an advancement, for a player who may be named and an advancement Minecraft announces only.
     *
     * @param hasDisplay   whether the advancement has a display (recipe unlocks have none)
     * @param announceChat its display's {@code announce_to_chat}
     * @param hidden       its display's {@code hidden}
     */
    public static Optional<PlayerEventMessage> advancement(UUID player, String name, String advancementId, boolean hasDisplay, boolean announceChat,
                                                           boolean hidden, NamePolicy names) {
        if (!announces(hasDisplay, announceChat, hidden) || !names.mayName(player)) return Optional.empty();
        return Optional.of(new PlayerEventMessage(Type.ADVANCEMENT, player, Optional.of(name), Optional.of(advancementId)));
    }

    /** Whether an advancement is one Minecraft announces in chat: it has a display that announces it and is not hidden. */
    public static boolean announces(boolean hasDisplay, boolean announceChat, boolean hidden) {
        return hasDisplay && announceChat && !hidden;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("event_type", type.key());
        name.ifPresent(value -> json.addProperty("player", value));
        if (type.detailKey != null) detail.ifPresent(value -> json.addProperty(type.detailKey, value));
        return json;
    }
}

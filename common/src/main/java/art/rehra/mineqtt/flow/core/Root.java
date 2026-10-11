package art.rehra.mineqtt.flow.core;

/**
 * What a {@link FlowMessage} was caused by at the root of its causal chain (M3 plan D10): its root provenance. The
 * {@link MessageBus} gives every message the root of the extent it was emitted in and carries it through hops, values
 * held back by the rate and drain carry-over, so whatever a chain of wires and same-tick redstone does, its messages
 * still say where it started. A Home Assistant write-back may only be caused by an {@linkplain #inGame in-game} root;
 * the others are incoming state, a broker topic or a restart, and acting on them would echo the outside back to it.
 * <p>
 * Transient: a message's root is never persisted, and neither is a wire's.
 */
public enum Root {
    /** The world, with no other cause current: block tickers, neighbour updates, redstone, a hand on a button. */
    WORLD(true),
    /** A player: a right-click, a screen, a command. */
    PLAYER(true),
    /** A message on a Raw Link: anyone who may publish to that topic. */
    RAW(false),
    /** A device's {@code /set} topics: Home Assistant, or anything else on the broker. */
    HA(false),
    /** Incoming state of the real device a mirrored one follows (M3). */
    TWIN(false),
    /** The server starting, or a device powering on: never a reason to switch a real device. */
    STARTUP(false);

    private final boolean inGame;

    Root(boolean inGame) {
        this.inGame = inGame;
    }

    /**
     * Whether something in the game caused it, a player or the world; the other roots came from outside it (incoming
     * state, the broker) or from a restart.
     */
    public boolean inGame() {
        return inGame;
    }

    /**
     * The root of an extent of {@code root} that starts inside an extent of {@code outer}: {@code root}, unless
     * {@code outer} came from outside the game. Such a root is never cleared by a nested extent, so nothing that incoming
     * state, a broker topic or a restart started becomes a player's or the world's further down the stack; only another
     * outside root may take its place.
     *
     * @param outer the root current when the extent starts; null for none
     */
    public static Root nested(Root outer, Root root) {
        return outer == null || outer.inGame || !root.inGame ? root : outer;
    }
}

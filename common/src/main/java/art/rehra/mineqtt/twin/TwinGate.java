package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.flow.core.Root;

import java.util.Optional;

/**
 * The write-back gate (M3 plan D10): which {@link Root roots} may cause a request to Home Assistant. Only something in
 * the game may, a player or the world ({@link Root#inGame()}); incoming state ({@link Root#TWIN}), a device's
 * {@code /set} topic ({@link Root#HA}), a Raw Link ({@link Root#RAW}) and a restart or chunk load ({@link Root#STARTUP})
 * may not. {@link TwinRequests} asks it before anything else, so no request is ever built for a refused root.
 * <p>
 * Pure and Minecraft-free.
 */
public final class TwinGate {
    private TwinGate() {
    }

    /** Whether a cause of {@code root} may send a request to Home Assistant. */
    public static boolean mayRequest(Root root) {
        return root.inGame();
    }

    /**
     * Why a cause of {@code root} may not send a request, as the log says it once per device; empty for a root that
     * may.
     */
    public static Optional<String> refusal(Root root) {
        return switch (root) {
            case WORLD, PLAYER -> Optional.empty();
            case TWIN -> Optional.of("it would echo incoming state back to Home Assistant");
            case HA -> Optional.of("a device's /set topic may not switch a real device through it");
            case RAW -> Optional.of("a raw topic may not switch a real device");
            case STARTUP -> Optional.of("a restart or a chunk load never switches a real device");
        };
    }
}

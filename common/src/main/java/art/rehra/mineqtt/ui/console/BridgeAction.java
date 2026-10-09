package art.rehra.mineqtt.ui.console;

/**
 * The Bridge Console's actions (M2 plan §4.2 M2-23), the only ones its screen can send: each is an operator command the
 * console runs with the player as its source, so the screen and the command behave alike. They act on the bridge, not on a
 * device, so they are no device's declared actions (§3.8 #1); this closed list is their declaration.
 */
public enum BridgeAction {
    /** {@code /mineqtt reconnect}: closes the broker connection and builds it again from the current config. */
    RECONNECT("reconnect"),
    /** {@code /mineqtt discovery republish}: Home Assistant discovery, availability and state of everything again. */
    REPUBLISH("republish"),
    /** {@code /mineqtt devices prune}: forgets the devices whose block is gone from a loaded chunk. */
    PRUNE("prune");

    private final String key;

    BridgeAction(String key) {
        this.key = key;
    }

    /** Its name in the lang keys ({@code mineqtt.console.action.<key>}). */
    public String key() {
        return key;
    }
}

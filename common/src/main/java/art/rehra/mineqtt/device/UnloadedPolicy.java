package art.rehra.mineqtt.device;

/** What a device does while its chunk is not loaded (design §3.2). */
public enum UnloadedPolicy {
    /**
     * Stays {@code online}: a command is queued in the registry and applied on the next attach, and the published
     * state shows the queued target. Actuators and groups.
     */
    QUEUE,
    /** Publishes {@code offline} on detach and ignores commands until it is loaded again. Sensors. */
    UNAVAILABLE
}

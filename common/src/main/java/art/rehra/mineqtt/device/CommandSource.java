package art.rehra.mineqtt.device;

/** Where a command applied to a device came from. */
public enum CommandSource {
    /** The device's {@code /set} topics: Home Assistant or anything else on the broker. */
    HA,
    /** A command to the device's group, fanned out to its members. */
    GROUP,
    /** A message on the device's Raw Link. */
    RAW,
    /** A local binding from another device (M2). */
    BINDING,
    /** A player in game. */
    PLAYER,
    /** The real device this one mirrors (M3). */
    TWIN
}

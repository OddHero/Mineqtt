package art.rehra.mineqtt.device;

import org.jetbrains.annotations.Nullable;

/**
 * Implemented by device block entities; every method runs on the server thread. The block entity keeps its state in
 * its own NBT, the {@link DeviceLink} keeps its identity.
 */
public interface DeviceHost {
    DeviceType deviceType();

    DeviceLink deviceLink();

    /** Writes every readable field of the current state. */
    void writeState(StateWriter out);

    /**
     * Applies a partial command. The device publishes nothing itself: the runtime publishes the state after an
     * {@link CommandResult.Outcome#APPLIED} result.
     */
    CommandResult applyCommand(CommandReader in, CommandSource source);

    /**
     * The device starts with its server: the first attach in a server run of a device the registry knows, before its
     * queued commands are applied and its state is published. A type with a power-on behaviour (the Redstone Output)
     * applies it here, as its {@link DeviceType#poweredOn} did to the record at server start. A device placed or copied
     * in this run does not start with the server.
     */
    default void powerOn() {
    }

    /**
     * The value a Raw Link output carries now, or null for a device that publishes none. A device publishes it on change;
     * the runtime sends it again when the device gets its first owner, since its publishes were refused until then.
     */
    default @Nullable String rawValue() {
        return null;
    }
}

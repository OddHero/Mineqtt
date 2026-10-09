package art.rehra.mineqtt.device;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

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
     * Whether the device's Raw Link channel resolves into the owner's private subtree (v1 private mode, a private channel
     * since M2-12; {@link DeviceLink#privateMode()}). Such a link stays there whatever the ACL says, so the one-time M1
     * re-resolution never moves it ({@link RawTopics#reResolveM1}), and its channel resolves into the private subtree
     * whenever it is resolved again.
     */
    default boolean isPrivateMode() {
        return deviceLink().privateMode();
    }

    /**
     * The player who changed the device through its screen or a paper moments ago, or null. A refused or moved Raw Link
     * is told to them in chat; without one, to the owner.
     */
    default @Nullable UUID recentEditor() {
        return null;
    }

    /**
     * The device starts with its server: the first attach in a server run of a device the registry knows, before its
     * queued commands are applied and its state is published. A type with a power-on behaviour (the Redstone Output)
     * applies it here, as its {@link DeviceType#poweredOn} did to the record at server start. A device placed or copied
     * in this run does not start with the server.
     */
    default void powerOn() {
    }

    /**
     * The device's Raw Link was taken away: by the device's config or {@code /mineqtt devices raw} (M2-6). An output the
     * link drove turns off, as v1's did when its topic went away.
     */
    default void rawRemoved() {
    }

    /**
     * The value a Raw Link output carries now, read live, or null for a device that publishes none or does not know its
     * value yet (a Redstone Input before it read its signal after the attach). A device publishes it on change; the
     * runtime sends it again when the device gets its first owner, since its publishes were refused until then, and a
     * retaining output after every CONNACK (M2 plan D2). It must never be a value saved before an unload, which a
     * retained publish would store as the current level.
     */
    default @Nullable String rawValue() {
        return null;
    }
}

package art.rehra.mineqtt.device;

/**
 * What a device did with a command.
 *
 * @param detail why a command was rejected; empty otherwise
 */
public record CommandResult(Outcome outcome, String detail) {
    public enum Outcome {
        /** The device changed; its state is published. */
        APPLIED,
        /** Nothing to change, e.g. the device already has that state. */
        UNCHANGED,
        /** The command does not fit the device. */
        REJECTED
    }

    private static final CommandResult APPLIED = new CommandResult(Outcome.APPLIED, "");
    private static final CommandResult UNCHANGED = new CommandResult(Outcome.UNCHANGED, "");

    public static CommandResult applied() {
        return APPLIED;
    }

    public static CommandResult unchanged() {
        return UNCHANGED;
    }

    public static CommandResult rejected(String detail) {
        return new CommandResult(Outcome.REJECTED, detail);
    }
}

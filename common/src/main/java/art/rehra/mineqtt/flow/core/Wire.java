package art.rehra.mineqtt.flow.core;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A wire from an out-port to an in-port (M2 plan §3.2), kept in the device registry. Destroying a device deletes its
 * wires; unloading one never does. Whether a pair of ports may be wired is decided when the wire is made
 * ({@link Mapping#compatible}); the {@link MessageBus} still drops what no longer fits.
 *
 * @param owner   the player who made it; flow-originated MQTT publishes are checked against their ACL
 * @param enabled whether it carries messages
 */
public record Wire(UUID id, Endpoint from, Endpoint to, Mapping mapping, Scope scope, UUID owner, boolean enabled) {
    public Wire {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(owner, "owner");
    }

    /** {@code from → to (mapping)}, for logs and command output. */
    public String describe() {
        return from.describe() + " -> " + to.describe() + " (" + mapping.name().toLowerCase(Locale.ROOT) + ")";
    }
}

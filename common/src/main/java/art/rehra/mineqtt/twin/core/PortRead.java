package art.rehra.mineqtt.twin.core;

import com.google.gson.JsonElement;

import java.util.Objects;
import java.util.Optional;

/**
 * How the twin fills one in-port of a bound device (M3 plan §3.1): the {@link ValuePath} that picks the value out of the
 * source's document and the {@link ValueMap} that turns it into the port's value. Pure and Minecraft-free.
 */
public record PortRead(ValuePath path, ValueMap map) {
    public PortRead {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(map, "map");
    }

    /**
     * The port value for a document, {@code {"state", "attributes": {…}, …}} for a Home Assistant entity
     * ({@link EntityDocument#toJson()}); empty when the path finds nothing or the map refuses what it found, which means
     * "keep the last value".
     */
    public Optional<JsonElement> apply(JsonElement document) {
        return path.select(document).flatMap(value -> map.apply(value, document));
    }
}

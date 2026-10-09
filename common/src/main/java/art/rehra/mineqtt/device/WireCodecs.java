package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Locale;

/**
 * How the registry stores wires (M2 plan §3.2, §3.6). {@code flow.core} stays Minecraft-free, so its codecs live here.
 * Only what is built is read or written: a reserved endpoint ({@link Endpoint#reserved}) or the cable {@link Scope#NETWORK}
 * fails to decode and to encode, so a wire from a later version is dropped with an error instead of running as
 * something it is not.
 */
final class WireCodecs {
    private static final String PORT = "port";
    private static final String GROUP_PORT = "group_port";
    private static final Codec<String> PORT_KEY = Codec.STRING.validate(key -> Endpoint.isPortKey(key)
            ? DataResult.success(key) : DataResult.error(() -> "Invalid port key: " + key));
    private static final MapCodec<Endpoint.PortRef> PORT_REF = RecordCodecBuilder.mapCodec(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("node").forGetter(Endpoint.PortRef::node),
            PORT_KEY.fieldOf("port").forGetter(Endpoint.PortRef::port)
    ).apply(instance, Endpoint.PortRef::new));
    private static final MapCodec<Endpoint.GroupPortRef> GROUP_PORT_REF = RecordCodecBuilder.mapCodec(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("group").forGetter(Endpoint.GroupPortRef::group),
            PORT_KEY.fieldOf("port").forGetter(Endpoint.GroupPortRef::port)
    ).apply(instance, Endpoint.GroupPortRef::new));
    static final Codec<Endpoint> ENDPOINT = Codec.STRING.partialDispatch("type", WireCodecs::type, WireCodecs::endpointCodec);
    private static final Codec<Mapping> MAPPING = Codec.STRING.comapFlatMap(WireCodecs::mapping, mapping -> mapping.name().toLowerCase(Locale.ROOT));
    private static final Codec<Scope> SCOPE = Codec.STRING.flatXmap(WireCodecs::scope,
            scope -> scope == Scope.WIRELESS ? DataResult.success(scope.name().toLowerCase(Locale.ROOT)) : notBuilt("scope " + scope));
    static final Codec<Wire> WIRE = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Wire::id),
            ENDPOINT.fieldOf("from").forGetter(Wire::from),
            ENDPOINT.fieldOf("to").forGetter(Wire::to),
            MAPPING.fieldOf("mapping").forGetter(Wire::mapping),
            SCOPE.fieldOf("scope").forGetter(Wire::scope),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Wire::owner),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Wire::enabled)
    ).apply(instance, Wire::new));

    private WireCodecs() {
    }

    private static DataResult<String> type(Endpoint endpoint) {
        return switch (endpoint) {
            case Endpoint.PortRef _ -> DataResult.success(PORT);
            case Endpoint.GroupPortRef _ -> DataResult.success(GROUP_PORT);
            case Endpoint.TopicRef _, Endpoint.HaEntityRef _ -> notBuilt("endpoint " + endpoint.describe());
        };
    }

    private static DataResult<MapCodec<? extends Endpoint>> endpointCodec(String type) {
        return switch (type) {
            case PORT -> DataResult.success(PORT_REF);
            case GROUP_PORT -> DataResult.success(GROUP_PORT_REF);
            case "topic", "ha_entity" -> notBuilt("endpoint type " + type);
            default -> DataResult.error(() -> "Unknown endpoint type: " + type);
        };
    }

    private static DataResult<Mapping> mapping(String name) {
        for (Mapping mapping : Mapping.values()) {
            if (mapping.name().equalsIgnoreCase(name)) return DataResult.success(mapping);
        }
        return DataResult.error(() -> "Unknown wire mapping: " + name);
    }

    private static DataResult<Scope> scope(String name) {
        if (Scope.WIRELESS.name().equalsIgnoreCase(name)) return DataResult.success(Scope.WIRELESS);
        if (Scope.NETWORK.name().equalsIgnoreCase(name)) return notBuilt("scope " + name);
        return DataResult.error(() -> "Unknown wire scope: " + name);
    }

    private static <T> DataResult<T> notBuilt(String what) {
        return DataResult.error(() -> "The " + what + " is reserved and not supported by this version");
    }
}

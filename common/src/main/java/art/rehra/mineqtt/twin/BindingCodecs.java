package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.ValueMap;
import art.rehra.mineqtt.twin.core.ValuePath;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How a {@link Binding} is stored (M3 plan D14): the {@code twin} key of a device block's {@code mineqtt} compound and
 * the nested {@code twin} compound of its registry record use the same codec. Every key but the ends is optional and left
 * out at its default, so a binding stays small, and a device without a binding stores no {@code twin} key at all.
 * <pre>
 * twin: { source: {entity: "light.kitchen", port: "light"} | {topic: "zigbee2mqtt/porch", path: "state"},
 *         targets: {powered: {entity: "scene.movie", port: "activate"}}, control: true,
 *         invert: true, min: "0", max: "100", decimals: 2, unavailable: "off", path: "attributes.temperature",
 *         preset: {id: "mineqtt:zigbee2mqtt_light", device: "porch", kind: "light"}, refused: "..." }
 * </pre>
 * Numbers of the range are decimal strings, so they come back exactly as stored. A value that breaks a {@link Binding}
 * rule fails to decode, which the callers treat as no binding.
 */
public final class BindingCodecs {
    private static final Codec<String> PORT_KEY = Codec.STRING.validate(key -> Endpoint.isPortKey(key)
            ? DataResult.success(key) : DataResult.error(() -> "Invalid port key: " + key));

    private record StoredEnd(Optional<String> entity, Optional<String> topic, Optional<String> port, Optional<String> path) {
    }

    private static final Codec<StoredEnd> STORED_END = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("entity").forGetter(StoredEnd::entity),
            Codec.STRING.optionalFieldOf("topic").forGetter(StoredEnd::topic),
            Codec.STRING.optionalFieldOf("port").forGetter(StoredEnd::port),
            Codec.STRING.optionalFieldOf("path").forGetter(StoredEnd::path)
    ).apply(instance, StoredEnd::new));

    /** A source or target: a Home Assistant entity's port, or a topic with the path of its value. */
    public static final Codec<Endpoint> END = STORED_END.flatXmap(BindingCodecs::end, BindingCodecs::stored);

    /** The longest range number read, as the Twin tab takes it. */
    private static final int MAX_DECIMAL_LENGTH = 32;

    // A range number within Binding.Settings's digits; written out with toPlainString, which only a bounded number reaches.
    private static final Codec<BigDecimal> DECIMAL = Codec.STRING.comapFlatMap(text -> {
        if (text.length() > MAX_DECIMAL_LENGTH) return DataResult.error(() -> "A range number has at most " + MAX_DECIMAL_LENGTH + " characters");
        try {
            BigDecimal value = new BigDecimal(text);
            return Binding.Settings.fitsRange(value) ? DataResult.success(value)
                    : DataResult.error(() -> "A range number has at most " + Binding.Settings.MAX_INTEGER_DIGITS + " digits before the point and "
                    + Binding.Settings.MAX_FRACTION_DIGITS + " after it: " + text);
        } catch (NumberFormatException e) {
            return DataResult.error(() -> "Not a decimal number: " + text);
        }
    }, BigDecimal::toPlainString);

    private static final Codec<Binding.Unavailable> UNAVAILABLE = Codec.STRING.comapFlatMap(key -> Binding.Unavailable.of(key)
            .map(DataResult::success).orElseGet(() -> DataResult.error(() -> "Not hold or off: " + key)), Binding.Unavailable::key);

    private static final Codec<ValuePath> PATH = Codec.STRING.comapFlatMap(text -> {
        Optional<String> problem = ValuePath.problem(text);
        return problem.isPresent() ? DataResult.error(problem::get) : DataResult.success(ValuePath.parse(text));
    }, ValuePath::toString);

    // The MQTT device preset of a topic source (M3-17), checked by Binding.PresetRef when the binding is built.
    private record StoredPreset(String id, String device, String kind) {
    }

    private static final Codec<StoredPreset> PRESET = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(StoredPreset::id),
            Codec.STRING.fieldOf("device").forGetter(StoredPreset::device),
            Codec.STRING.fieldOf("kind").forGetter(StoredPreset::kind)
    ).apply(instance, StoredPreset::new));

    private record Stored(Optional<Endpoint> source, Map<String, Endpoint> targets, boolean control, boolean invert, Optional<BigDecimal> min,
                          Optional<BigDecimal> max, int decimals, Binding.Unavailable unavailable, Optional<ValuePath> path,
                          Optional<StoredPreset> preset, Optional<String> refused) {
    }

    private static final Codec<Stored> STORED = RecordCodecBuilder.create(instance -> instance.group(
            END.optionalFieldOf("source").forGetter(Stored::source),
            Codec.unboundedMap(PORT_KEY, END).optionalFieldOf("targets", Map.of()).forGetter(Stored::targets),
            Codec.BOOL.optionalFieldOf("control", false).forGetter(Stored::control),
            Codec.BOOL.optionalFieldOf("invert", false).forGetter(Stored::invert),
            DECIMAL.optionalFieldOf("min").forGetter(Stored::min),
            DECIMAL.optionalFieldOf("max").forGetter(Stored::max),
            Codec.intRange(ValueMap.AUTO_DECIMALS, ValueMap.MAX_DECIMALS).optionalFieldOf("decimals", ValueMap.AUTO_DECIMALS).forGetter(Stored::decimals),
            UNAVAILABLE.optionalFieldOf("unavailable", Binding.Unavailable.HOLD).forGetter(Stored::unavailable),
            PATH.optionalFieldOf("path").forGetter(Stored::path),
            PRESET.optionalFieldOf("preset").forGetter(Stored::preset),
            Codec.STRING.optionalFieldOf("refused").forGetter(Stored::refused)
    ).apply(instance, Stored::new));

    /** A whole binding. */
    public static final Codec<Binding> CODEC = STORED.flatXmap(BindingCodecs::binding, binding -> DataResult.success(stored(binding)));

    private BindingCodecs() {
    }

    private static DataResult<Endpoint> end(StoredEnd stored) {
        try {
            if (stored.entity().isPresent() && stored.topic().isEmpty() && stored.path().isEmpty()) {
                return DataResult.success(new Endpoint.HaEntityRef(stored.entity().get(), stored.port().orElse("")));
            }
            if (stored.topic().isPresent() && stored.entity().isEmpty() && stored.port().isEmpty()) {
                String path = stored.path().orElse("");
                Optional<String> problem = ValuePath.problem(path);
                if (problem.isPresent()) return DataResult.error(() -> "Invalid path of topic " + stored.topic().get() + ": " + problem.get());
                return DataResult.success(new Endpoint.TopicRef(stored.topic().get(), ValuePath.parse(path).toString()));
            }
            return DataResult.error(() -> "A binding end is an entity with a port or a topic with a path");
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }

    private static DataResult<StoredEnd> stored(Endpoint end) {
        return switch (end) {
            case Endpoint.HaEntityRef entity -> DataResult.success(new StoredEnd(Optional.of(entity.entityId()), Optional.empty(), Optional.of(entity.port()), Optional.empty()));
            case Endpoint.TopicRef topic -> DataResult.success(new StoredEnd(Optional.empty(), Optional.of(topic.topic()), Optional.empty(),
                    topic.path().isEmpty() ? Optional.empty() : Optional.of(topic.path())));
            default -> DataResult.error(() -> "A binding end is an entity or a topic, not " + end.describe());
        };
    }

    private static DataResult<Binding> binding(Stored stored) {
        try {
            Binding.PresetRef preset = stored.preset().map(ref -> new Binding.PresetRef(ref.id(), ref.device(), ref.kind())).orElse(null);
            Binding.Settings settings = new Binding.Settings(stored.invert(), stored.min().orElse(null), stored.max().orElse(null), stored.decimals(),
                    stored.unavailable(), stored.path().orElse(null), preset);
            return DataResult.success(new Binding(stored.source().orElse(null), new LinkedHashMap<>(stored.targets()), stored.control(), settings,
                    stored.refused().orElse(null)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }

    private static Stored stored(Binding binding) {
        Binding.Settings settings = binding.settings();
        return new Stored(Optional.ofNullable(binding.source()), binding.targets(), binding.control(), settings.invert(), Optional.ofNullable(settings.min()),
                Optional.ofNullable(settings.max()), settings.decimals(), settings.unavailable(), Optional.ofNullable(settings.path()),
                Optional.ofNullable(settings.preset()).map(ref -> new StoredPreset(ref.id(), ref.device(), ref.kind())), Optional.ofNullable(binding.refused()));
    }
}

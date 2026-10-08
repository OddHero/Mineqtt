package art.rehra.mineqtt.device;

import art.rehra.mineqtt.mqtt.core.TopicAcl;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A device's Raw Link: a user topic next to its device topics, for wireless redstone between servers and v1
 * automations (design D2). Saved in the block entity's {@code mineqtt.raw} compound.
 *
 * @param on             the custom on payload that wins over the type's grammar; null when not set
 * @param off            the custom off payload; null when not set
 * @param qos            0 or 1, for publishing
 * @param retain         whether an output publishes retained
 * @param grandfathered  a v1 topic adopted by the migrator, exempt from the raw allowlist while its owner stays. Its
 *                       owner is null while the device has no known owner (v1 recorded no name, or the name is not
 *                       resolved yet); the exemption then holds while the device has no owner, and is bound to the
 *                       owner once the name resolves.
 * @param stateEcho      a migrated v1 input that also publishes the v1 state JSON to {@code <topic>/state} (§6.6)
 * @param disabledReason why the ACL refused the topic when untrusted data was checked; null while usable
 */
public record RawLink(String topic, RawLinkSpec.Direction direction, @Nullable String on, @Nullable String off, int qos,
                      boolean retain, @Nullable TopicAcl.Grandfather grandfathered, boolean stateEcho,
                      @Nullable String disabledReason) {
    /** Appended to the topic of a state echo, as v1 lamps published their state. */
    public static final String ECHO_SUFFIX = "/state";
    private static final Codec<RawLinkSpec.Direction> DIRECTION = Codec.STRING.comapFlatMap(RawLink::direction,
            direction -> direction.name().toLowerCase(Locale.ROOT));
    private static final Codec<TopicAcl.Grandfather> GRANDFATHER = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("topic").forGetter(TopicAcl.Grandfather::topic),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(grandfather -> Optional.ofNullable(grandfather.owner()))
    ).apply(instance, (topic, owner) -> new TopicAcl.Grandfather(topic, owner.orElse(null))));
    public static final Codec<RawLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("topic").forGetter(RawLink::topic),
            DIRECTION.fieldOf("dir").forGetter(RawLink::direction),
            Codec.STRING.optionalFieldOf("on").forGetter(link -> Optional.ofNullable(link.on)),
            Codec.STRING.optionalFieldOf("off").forGetter(link -> Optional.ofNullable(link.off)),
            Codec.intRange(0, 1).optionalFieldOf("qos", 0).forGetter(RawLink::qos),
            Codec.BOOL.optionalFieldOf("retain", false).forGetter(RawLink::retain),
            GRANDFATHER.optionalFieldOf("grandfathered").forGetter(link -> Optional.ofNullable(link.grandfathered)),
            Codec.BOOL.optionalFieldOf("stateEcho", false).forGetter(RawLink::stateEcho),
            Codec.STRING.optionalFieldOf("disabled").forGetter(link -> Optional.ofNullable(link.disabledReason))
    ).apply(instance, (topic, dir, on, off, qos, retain, grandfathered, stateEcho, disabled) -> new RawLink(topic, dir,
            on.orElse(null), off.orElse(null), qos, retain, grandfathered.orElse(null), stateEcho, disabled.orElse(null))));

    public RawLink {
        if (qos < 0 || qos > 1) throw new IllegalArgumentException("Raw Link QoS must be 0 or 1: " + qos);
    }

    /** A link a player set up: default QoS and retain, no custom payloads, no grandfather flag. */
    public static RawLink of(String topic, RawLinkSpec.Direction direction, boolean stateEcho) {
        return new RawLink(topic, direction, null, null, 0, false, null, stateEcho, null);
    }

    public boolean active() {
        return disabledReason == null;
    }

    public ValueCodecs.CustomPayloads customPayloads() {
        return new ValueCodecs.CustomPayloads(on, off);
    }

    /** Where the state echo goes: {@code <topic>/state}. */
    public String echoTopic() {
        return topic + ECHO_SUFFIX;
    }

    /** The grandfather flag of the echo topic: a grandfathered v1 topic also keeps its v1 state topic. */
    public @Nullable TopicAcl.Grandfather echoGrandfather() {
        return grandfathered == null ? null : new TopicAcl.Grandfather(echoTopic(), grandfathered.owner());
    }

    /** The link with other custom payloads; the topic, and with it a grandfather flag, stays. */
    public RawLink withCustomPayloads(ValueCodecs.CustomPayloads custom) {
        return new RawLink(topic, direction, custom.on(), custom.off(), qos, retain, grandfathered, stateEcho, disabledReason);
    }

    public RawLink withoutGrandfather() {
        return new RawLink(topic, direction, on, off, qos, retain, null, stateEcho, disabledReason);
    }

    /** The grandfather flag that waited for its owner, bound to the owner now that the v1 name is resolved. */
    public RawLink withGrandfatherOwner(UUID owner) {
        if (grandfathered == null || grandfathered.owner() != null) return this;
        return new RawLink(topic, direction, on, off, qos, retain, new TopicAcl.Grandfather(grandfathered.topic(), owner), stateEcho, disabledReason);
    }

    /** The link as the ACL judged it: disabled with the reason, or usable again. */
    public RawLink checked(@Nullable String refusal) {
        return new RawLink(topic, direction, on, off, qos, retain, grandfathered, stateEcho, refusal);
    }

    private static DataResult<RawLinkSpec.Direction> direction(String name) {
        for (RawLinkSpec.Direction direction : RawLinkSpec.Direction.values()) {
            if (direction.name().equalsIgnoreCase(name)) return DataResult.success(direction);
        }
        return DataResult.error(() -> "Unknown Raw Link direction: " + name);
    }
}

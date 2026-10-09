package art.rehra.mineqtt.device;

import art.rehra.mineqtt.mqtt.core.TopicAcl;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A device's Raw Link: a user topic next to its device topics, for wireless redstone between servers and v1
 * automations (design D2). Saved in the block entity's {@code mineqtt.raw} compound.
 *
 * @param on             the custom on payload that wins over the type's grammar; null when not set
 * @param off            the custom off payload; null when not set
 * @param qos            0 or 1, for publishing
 * @param retain         whether an output publishes retained: the player's override, or null for the type's default
 *                       ({@link #effectiveRetain}). Links M1 saved and v1 links carry none, so they follow the default.
 *                       A toggling custom payload is never retained, whatever the override says
 * @param grandfathered  a topic exempt from the raw allowlist while its owner stays: a v1 topic adopted by the
 *                       migrator, or a literal of the owner's literal set ({@link RawTopics}). Its owner is null while
 *                       the device has no known owner (v1 recorded no name, or the name is not resolved yet); the
 *                       exemption then holds while the device has no owner, and is bound to the owner once the name
 *                       resolves.
 * @param stateEcho      a migrated v1 input that also publishes the v1 state JSON to {@code <topic>/state} (§6.6)
 * @param disabledReason why the topic may not be used: the ACL refused it when untrusted data was checked, or neither
 *                       the literal nor its shared form could be resolved; null while usable
 * @param resolvedV      the version of the {@link RawTopics} rules that resolved the topic, or that decided once whether a
 *                       grandfathered v1 topic joins its owner's literal set; 0 for a link M1 saved, whose topic is the
 *                       slots' literal as written, and for a v1 topic adopted as it was
 * @param channel        the item or paper literal a derived topic resolves from ({@link RawTopics#resolve}); the topic
 *                       follows it for the device's owner, so it is resolved again when the owner, their literal set or the
 *                       ACL changes. Null for a topic used as it is: a v1 topic the migrator grandfathered, or an M1 link
 *                       not re-resolved yet
 * @param edges          which edges an output publishes: {@link Edges#BOTH}, or only the rising one for a converted v1
 *                       Static Publisher (M2 plan D11); every change of the link keeps it
 */
public record RawLink(String topic, RawLinkSpec.Direction direction, @Nullable String on, @Nullable String off, int qos,
                      @Nullable Boolean retain, @Nullable TopicAcl.Grandfather grandfathered, boolean stateEcho,
                      @Nullable String disabledReason, int resolvedV, @Nullable String channel, Edges edges) {
    /** Appended to the topic of a state echo, as v1 lamps published their state. */
    public static final String ECHO_SUFFIX = "/state";
    private static final Codec<RawLinkSpec.Direction> DIRECTION = Codec.STRING.comapFlatMap(RawLink::direction,
            direction -> direction.name().toLowerCase(Locale.ROOT));
    private static final Codec<Edges> EDGES = Codec.STRING.comapFlatMap(RawLink::edges, edges -> edges.name().toLowerCase(Locale.ROOT));
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
            Codec.BOOL.optionalFieldOf("retain").forGetter(link -> Optional.ofNullable(link.retain)),
            GRANDFATHER.optionalFieldOf("grandfathered").forGetter(link -> Optional.ofNullable(link.grandfathered)),
            Codec.BOOL.optionalFieldOf("stateEcho", false).forGetter(RawLink::stateEcho),
            Codec.STRING.optionalFieldOf("disabled").forGetter(link -> Optional.ofNullable(link.disabledReason)),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("resolvedV", 0).forGetter(RawLink::resolvedV),
            Codec.STRING.optionalFieldOf("channel").forGetter(link -> Optional.ofNullable(link.channel)),
            EDGES.optionalFieldOf("edges", Edges.BOTH).forGetter(RawLink::edges)
    ).apply(instance, (topic, dir, on, off, qos, retain, grandfathered, stateEcho, disabled, resolvedV, channel, edges) -> new RawLink(topic, dir,
            on.orElse(null), off.orElse(null), qos, retain.orElse(null), grandfathered.orElse(null), stateEcho, disabled.orElse(null), resolvedV,
            channel.orElse(null), edges)));

    public RawLink {
        if (qos < 0 || qos > 1) throw new IllegalArgumentException("Raw Link QoS must be 0 or 1: " + qos);
        if (resolvedV < 0) throw new IllegalArgumentException("Raw Link resolution version must not be negative: " + resolvedV);
        Objects.requireNonNull(edges, "edges");
    }

    /** Which edges of a level an output publishes. */
    public enum Edges {
        /** Every edge: the link carries the level, on and off. */
        BOTH,
        /**
         * Only the rising edge, with the on payload: the link carries events, as v1's Static Publisher did (M2 plan D11).
         * It has no level to publish again or to retain.
         */
        RISING;

        /** Whether an edge is published: the rising one always, the falling one only by a link that carries the level. */
        public boolean publishes(boolean rising) {
            return rising || this == BOTH;
        }

        /** Whether the link carries a level, which is published again (after a CONNACK, an owner lookup) and may be retained. */
        public boolean level() {
            return this == BOTH;
        }
    }

    public boolean active() {
        return disabledReason == null;
    }

    /**
     * Whether the current {@link RawTopics} rules resolved the topic, or settled a grandfathered v1 topic; an M1 link is
     * resolved once, at its attach.
     */
    public boolean resolved() {
        return resolvedV >= RawTopics.VERSION;
    }

    /**
     * Whether an output publishes retained: the link's override, else the default of the type's link. Never with a custom
     * payload that toggles ({@link ValueCodecs.CustomPayloads#toggles}), for a link of rising edges only nor for a type
     * whose link carries events ({@link RawLinkSpec#level()}): a stored toggle or event is no level, and every receiver
     * that subscribes or reconnects would act on it again, as it would at every republish.
     */
    public boolean effectiveRetain(RawLinkSpec spec) {
        if (!spec.level() || customPayloads().toggles() || !edges.level()) return false;
        return retain != null ? retain : spec.retainByDefault();
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
        return new RawLink(topic, direction, custom.on(), custom.off(), qos, retain, grandfathered, stateEcho, disabledReason, resolvedV,
                channel, edges);
    }

    /**
     * A link on a topic a player typed (M2-6): used as typed, so it has no channel and is marked as resolved, or the next
     * attach would resolve it again; typing a topic drops a grandfather flag (§8.2). The payloads, QoS, retain override
     * and state echo of the link it replaces stay.
     *
     * @param previous the link until now, null when there was none
     */
    public static RawLink typed(String topic, RawLinkSpec.Direction direction, @Nullable RawLink previous) {
        if (previous == null) return new RawLink(topic, direction, null, null, 0, null, null, false, null, RawTopics.VERSION, null, Edges.BOTH);
        return new RawLink(topic, direction, previous.on, previous.off, previous.qos, previous.retain, null, previous.stateEcho, null,
                RawTopics.VERSION, null, previous.edges);
    }

    /** The link with the player's retain override; the topic stays. */
    public RawLink withRetain(@Nullable Boolean retain) {
        return new RawLink(topic, direction, on, off, qos, retain, grandfathered, stateEcho, disabledReason, resolvedV, channel, edges);
    }

    /** The link publishing with another QoS; the topic stays. */
    public RawLink withQos(int qos) {
        return new RawLink(topic, direction, on, off, qos, retain, grandfathered, stateEcho, disabledReason, resolvedV, channel, edges);
    }

    /**
     * The link of a copy, which is not grandfathered (§8.6): a grandfathered v1 topic becomes the copy's channel, so it is
     * resolved for the copy's owner like an item or paper topic.
     */
    public RawLink withoutGrandfather() {
        String derived = grandfathered != null && channel == null ? topic : channel;
        return new RawLink(topic, direction, on, off, qos, retain, null, stateEcho, disabledReason, resolvedV, derived, edges);
    }

    /** The grandfather flag that waited for its owner, bound to the owner now that the v1 name is resolved. */
    public RawLink withGrandfatherOwner(UUID owner) {
        if (grandfathered == null || grandfathered.owner() != null) return this;
        return new RawLink(topic, direction, on, off, qos, retain, new TopicAcl.Grandfather(grandfathered.topic(), owner), stateEcho,
                disabledReason, resolvedV, channel, edges);
    }

    /** The link as the ACL judged it: disabled with the reason, or usable again. */
    public RawLink checked(@Nullable String refusal) {
        return new RawLink(topic, direction, on, off, qos, retain, grandfathered, stateEcho, refusal, resolvedV, channel, edges);
    }

    /** The link marked as resolved by the current {@link RawTopics} rules, everything else unchanged. */
    public RawLink markResolved() {
        return markResolved(channel);
    }

    /** The link on its topic, marked as resolved by the current {@link RawTopics} rules from the channel. */
    public RawLink markResolved(@Nullable String channel) {
        return new RawLink(topic, direction, on, off, qos, retain, grandfathered, stateEcho, disabledReason, RawTopics.VERSION, channel, edges);
    }

    /**
     * The link on the topic a {@link RawTopics} resolution chose: the channel, the grandfather flag and the refusal are
     * the resolution's, the payloads, QoS, retain and the state echo stay.
     */
    public RawLink resolvedTo(RawTopics.Resolution resolution) {
        return new RawLink(resolution.topic(), direction, on, off, qos, retain, resolution.grandfather(), stateEcho, resolution.refusal(),
                RawTopics.VERSION, resolution.literal(), edges);
    }

    private static DataResult<Edges> edges(String name) {
        for (Edges edges : Edges.values()) {
            if (edges.name().equalsIgnoreCase(name)) return DataResult.success(edges);
        }
        return DataResult.error(() -> "Unknown Raw Link edges: " + name);
    }

    private static DataResult<RawLinkSpec.Direction> direction(String name) {
        for (RawLinkSpec.Direction direction : RawLinkSpec.Direction.values()) {
            if (direction.name().equalsIgnoreCase(name)) return DataResult.success(direction);
        }
        return DataResult.error(() -> "Unknown Raw Link direction: " + name);
    }
}

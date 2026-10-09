package art.rehra.mineqtt.device;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a retaining Raw Link output leaves on the broker when its link changes or its device is destroyed (M2 plan D2).
 * Pure, so the check runs it without a server; {@link DeviceRuntime} judges each step by the ACL of the link it names
 * and publishes it.
 * <ul>
 *     <li>A retaining output that leaves its topic (destroyed, retargeted, its slots emptied) publishes its off payload
 *     retained to the old topic. An empty clear would leave live receivers on other servers stuck on, since raw grammars
 *     ignore empty payloads.</li>
 *     <li>Retain switched off on the same topic, also by a custom payload that became a toggle
 *     ({@link RawLink#effectiveRetain}): an empty retained clear, so no stale level stays stored; live receivers keep
 *     their level.</li>
 *     <li>A link that retains on a topic it did not hold its level on (a new topic, retain switched on, a link usable
 *     again) or whose payloads changed: its current level, retained.</li>
 * </ul>
 * Unloading changes no link, so it publishes nothing: an unloaded chunk does not mean the signal changed.
 */
public final class RawRetain {
    private RawRetain() {
    }

    public enum Kind {
        /** The off payload, retained, to a topic the output left. */
        OFF,
        /** An empty retained payload, which deletes the stored level. */
        CLEAR,
        /** The output's current level, retained. */
        LEVEL
    }

    /**
     * One publish of a plan.
     *
     * @param payload  the payload of {@link Kind#OFF}, empty for {@link Kind#CLEAR}; null for {@link Kind#LEVEL}, whose
     *                 payload is the device's current value
     * @param judgedBy the link whose topic and grandfather flag the ACL judges the publish by: the old link for
     *                 {@code OFF} and {@code CLEAR}, judged before it is replaced, the new one for {@code LEVEL}
     */
    public record Step(Kind kind, String topic, @Nullable String payload, RawLink judgedBy) {
    }

    /**
     * The publishes for an output's link replaced by another.
     *
     * @param before the link until now, null when there was none
     * @param after  the link from now on, null when the device has none any more (destroyed, slots emptied)
     */
    public static List<Step> plan(@Nullable RawLink before, @Nullable RawLink after, RawLinkSpec spec) {
        List<Step> steps = new ArrayList<>();
        if (spec.direction() != RawLinkSpec.Direction.OUT) return steps;
        boolean held = retains(before, spec);
        boolean holds = retains(after, spec);
        boolean sameTopic = before != null && after != null && before.topic().equals(after.topic());
        if (held && !sameTopic) {
            steps.add(new Step(Kind.OFF, before.topic(), spec.offPayload(before.customPayloads()), before));
        } else if (held && after.active() && !holds) {
            steps.add(new Step(Kind.CLEAR, before.topic(), "", before));
        }
        if (holds && (!sameTopic || !held || !Objects.equals(before.customPayloads(), after.customPayloads()))) {
            steps.add(new Step(Kind.LEVEL, after.topic(), null, after));
        }
        return steps;
    }

    /** The publishes for a destroyed device: a retaining output leaves its topic off. */
    public static List<Step> destroyed(@Nullable RawLink link, RawLinkSpec spec) {
        return plan(link, null, spec);
    }

    /** Whether an output's link publishes its level retained: an active output link with effective retain. */
    public static boolean retains(@Nullable RawLink link, RawLinkSpec spec) {
        return link != null && spec.direction() == RawLinkSpec.Direction.OUT && link.direction() == RawLinkSpec.Direction.OUT
                && link.active() && link.effectiveRetain(spec);
    }
}

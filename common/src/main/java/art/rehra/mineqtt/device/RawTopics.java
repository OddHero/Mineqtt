package art.rehra.mineqtt.device;

import art.rehra.mineqtt.mqtt.core.TopicAcl;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Where an item or paper topic goes (M2 plan D1): the same item gives the same topic for the host, a guest and a
 * dedicated server, so classic wireless redstone links up on every server type. Pure: the caller supplies the ACL, the
 * device's owner and the owner's literal set.
 * <ul>
 *     <li>Private mode: {@code <base>/<bridgeId>/raw/players/<owner>/<literal>}.</li>
 *     <li>The literal as written, when it is in the owner's literal set (it then carries a grandfather flag for the
 *     owner) or when the raw allowlist covers it.</li>
 *     <li>Otherwise its shared form {@code mineqtt/shared/<literal>}; when that is refused too, the link is refused with
 *     the reason.</li>
 * </ul>
 * Every result is judged without the operator bypass of the allowlist, which applies only to topics typed explicitly;
 * the hard denies apply to every result. The owner's literal set holds topics only trusted server paths added: v1 topics
 * the migrator grandfathered once their v1 owner name resolved, and M1 topics the one-time
 * {@link #reResolveM1 re-resolution} kept. The link keeps the literal as its {@link RawLink#channel() channel}, and the
 * runtime resolves it again whenever the result could change, so the topic never depends on the order devices attach in.
 */
public final class RawTopics {
    /** The namespace every server shares for derived topics; the default {@code acl.rawAllow} covers it. */
    public static final String SHARED_ROOT = "mineqtt/shared";
    /** The version of these rules, recorded in {@link RawLink#resolvedV()}. */
    public static final int VERSION = 1;

    private RawTopics() {
    }

    /** How a literal was resolved. */
    public enum Kind {
        /** The owner's private subtree. */
        PRIVATE,
        /** The literal as written, from the owner's literal set, with a grandfather flag. */
        OWNER_LITERAL,
        /** The literal as written, which the raw allowlist covers. */
        LITERAL,
        /** The shared form of the literal. */
        SHARED,
        /** Nothing may be used; the topic is the shared form, the refusal says why. */
        REFUSED
    }

    /**
     * A resolved topic.
     *
     * @param literal     the literal it was resolved from, the link's channel
     * @param grandfather the owner's grandfather flag for a literal of their literal set, else null
     * @param refusal     why the topic may not be used, for {@link Kind#REFUSED}; else null
     */
    public record Resolution(String literal, Kind kind, String topic, @Nullable TopicAcl.Grandfather grandfather, @Nullable String refusal) {
        /**
         * A new link on this topic, as the slots set it up: default QoS, the type's default retain, no custom payloads,
         * every edge.
         */
        public RawLink link(RawLinkSpec.Direction direction, boolean stateEcho) {
            return new RawLink(topic, direction, null, null, 0, null, grandfather, stateEcho, refusal, VERSION, literal, RawLink.Edges.BOTH);
        }
    }

    /** What the one-time re-resolution of an M1 link did. */
    public enum Pass {
        /** Nothing: the link is resolved already, or a grandfathered v1 topic that stays as it is. */
        NONE,
        /**
         * Only marked as resolved: a private-mode link, which lives in the owner's subtree whatever the ACL says; its
         * literal becomes the channel when the topic is the owner's private form of one.
         */
        MARKED,
        /** The topic stays; the ACL accepts it for the owner. */
        KEPT,
        /** The topic moved, because the ACL refuses it. */
        REMAPPED
    }

    /**
     * The re-resolved link.
     *
     * @param remember the literal to add to the owner's literal set, or null
     */
    public record ReResolution(Pass pass, RawLink link, @Nullable String remember) {
    }

    /**
     * Resolves a slot topic.
     *
     * @param literal       the channel: v1's slot topic without a private owner prefix, an item's id or a paper text
     * @param privateMode   whether the channel is private: it resolves into the owner's private subtree
     * @param owner         the device's owner; null when it has none (yet)
     * @param ownerLiterals the owner's literal set
     */
    public static Resolution resolve(String literal, boolean privateMode, TopicAcl acl, @Nullable UUID owner, Set<String> ownerLiterals) {
        // Never the operator bypass: the same item gives the same topic for every owner.
        TopicAcl.Subject subject = new TopicAcl.Subject(owner, false, false);
        if (privateMode) {
            if (owner == null) return new Resolution(literal, Kind.REFUSED, literal, null, "private mode needs a known owner");
            String topic = acl.privateTopic(owner, literal);
            TopicAcl.Decision decision = acl.checkPublish(subject, topic);
            return decision.allowed() ? new Resolution(literal, Kind.PRIVATE, topic, null, null)
                    : new Resolution(literal, Kind.REFUSED, topic, null, decision.message());
        }
        if (owner != null && ownerLiterals.contains(literal)) {
            TopicAcl.Grandfather grandfather = new TopicAcl.Grandfather(literal, owner);
            if (acl.checkPublish(subject, literal, grandfather).allowed()) {
                return new Resolution(literal, Kind.OWNER_LITERAL, literal, grandfather, null);
            }
        }
        if (acl.checkPublish(subject, literal).allowed()) return new Resolution(literal, Kind.LITERAL, literal, null, null);
        String shared = shared(literal);
        TopicAcl.Decision decision = acl.checkPublish(subject, shared);
        if (decision.allowed()) return new Resolution(literal, Kind.SHARED, shared, null, null);
        return new Resolution(literal, Kind.REFUSED, shared, null, "neither '" + literal + "' nor its shared form may be used: " + decision.message());
    }

    /**
     * A link judged again for its owner now: at its attach, when its owner or their literal set changed, and when the ACL
     * changed. A link with a channel is resolved from it again, so it moves, is refused or opens again; a topic used as
     * it is (a grandfathered v1 topic, a private-mode M1 link of an unknown literal) stays, and the ACL judges it each
     * time it is used ({@link #problem}).
     *
     * @param privateMode   whether the device's slots are in private mode
     * @param owner         the device's owner; null when it has none
     * @param ownerLiterals the owner's literal set
     */
    public static RawLink recheck(RawLink link, boolean privateMode, TopicAcl acl, @Nullable UUID owner, Set<String> ownerLiterals) {
        if (link.channel() == null) return link;
        return link.resolvedTo(resolve(link.channel(), privateMode, acl, owner, ownerLiterals));
    }

    /**
     * Why a link does nothing now: the refusal it was stored with, or the ACL's refusal of its topic for the owner.
     *
     * @param owner the device's owner as the ACL sees them, the operator bypass included
     * @return empty while the link works
     */
    public static Optional<String> problem(RawLink link, TopicAcl acl, TopicAcl.Subject owner) {
        if (!link.active()) return Optional.of(link.disabledReason());
        TopicAcl.Decision decision = acl.checkPublish(owner, link.topic(), link.grandfathered());
        return decision.allowed() ? Optional.empty() : Optional.of(decision.message());
    }

    /** Whether a link's channel resolved into its shared form: the literal is not usable as written on this server. */
    public static boolean usesSharedForm(RawLink link) {
        return link.channel() != null && link.active() && link.topic().equals(shared(link.channel()));
    }

    /** The shared form of a literal: {@code minecraft/stone} becomes {@code mineqtt/shared/minecraft/stone}. */
    public static String shared(String literal) {
        return SHARED_ROOT + "/" + literal;
    }

    /**
     * The one-time re-resolution of a link M1 saved (no {@link RawLink#resolvedV() resolvedV}, no channel), whose topic
     * is the slots' literal as written; the literal becomes the link's channel. A grandfathered v1 topic stays as it is.
     * A private-mode link is only marked. Any other link of a device this registry knew stays verbatim while the ACL
     * accepts it for the owner, the operator bypass included (an integrated server's operator keeps what M1 allowed
     * them), and its topic joins the owner's literal set, so the owner's new links on the same literal follow it. A link
     * the ACL refuses, and any link of untrusted data (a copy), is resolved like a new one.
     *
     * @param privateMode   whether the device's slots are in private mode
     * @param withBypass    the ACL's decision on the link's topic for the owner, the operator bypass included; null for
     *                      untrusted data, which the operator bypass never keeps
     * @param owner         the device's owner; null when it has none
     * @param ownerLiterals the owner's literal set
     */
    public static ReResolution reResolveM1(RawLink link, boolean privateMode, TopicAcl.@Nullable Decision withBypass, TopicAcl acl,
                                           @Nullable UUID owner, Set<String> ownerLiterals) {
        if (link.resolved() || link.grandfathered() != null || link.channel() != null) return new ReResolution(Pass.NONE, link, null);
        if (privateMode) return new ReResolution(Pass.MARKED, link.markResolved(privateLiteral(link.topic(), acl, owner)), null);
        if (withBypass != null && withBypass.allowed()) {
            return new ReResolution(Pass.KEPT, link.checked(null).markResolved(link.topic()), owner == null ? null : link.topic());
        }
        RawLink moved = link.resolvedTo(resolve(link.topic(), false, acl, owner, ownerLiterals));
        return new ReResolution(moved.topic().equals(link.topic()) ? Pass.KEPT : Pass.REMAPPED, moved, null);
    }

    // The literal whose private form the topic is for the owner (M1 put private-mode slots there); null for another topic.
    private static @Nullable String privateLiteral(String topic, TopicAcl acl, @Nullable UUID owner) {
        if (owner == null) return null;
        String root = acl.privateTopic(owner, "");
        if (topic.equals(root)) return "";
        return topic.startsWith(root + "/") ? topic.substring(root.length() + 1) : null;
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.PortType;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who may change which group, and how (design D1, M2 plan §4.2 M2-17): the General tab's group page and the
 * Configurator's Group mode. Groups have no owner, so a group is changed by whoever may configure every one of its
 * members, as a wire into a group needs ({@link LinkRules}): a player may join their device to a group, rename it or
 * show its members to Home Assistant only when they may configure each member, and a group without members is changed
 * by nobody. Taking their own device out needs CONFIGURE on that device alone. Showing members to Home Assistant also
 * needs the right to expose devices ({@link LinkSetting.Gate#EXPOSE}). A group that replaces a v1 topic follows that
 * topic under the legacy rules, so a device joins it only when its owner may use the topic themselves. Pure: the caller
 * says who may configure what and which topics a device's owner may use.
 */
public final class GroupRules {
    /** The longest group name, as long as a device's. */
    public static final int MAX_NAME_LENGTH = LinkSetting.MAX_NAME_LENGTH;
    private static final PortType NAME_KIND = PortType.text(MAX_NAME_LENGTH);
    // A name on the network: its code points as UTF-8, with room for the server to refuse one too long.
    private static final int MAX_NAME_BYTES = MAX_NAME_LENGTH * 4 + 4;

    private GroupRules() {
    }

    /** A change to a device's group, from its screen ({@code DeviceGroupC2S}) or the Configurator's Group mode. */
    public sealed interface Change {
        StreamCodec<ByteBuf, Change> STREAM_CODEC = ByteBufCodecs.VAR_INT.dispatch(Change::tag, tag -> switch (tag) {
            case 0 -> UUIDUtil.STREAM_CODEC.<Change>map(Join::new, change -> ((Join) change).group());
            case 1 -> StreamCodec.<ByteBuf, Change>unit(new Leave());
            case 2 -> ByteBufCodecs.stringUtf8(MAX_NAME_BYTES).<Change>map(Create::new, change -> ((Create) change).name());
            case 3 -> ByteBufCodecs.stringUtf8(MAX_NAME_BYTES).<Change>map(Rename::new, change -> ((Rename) change).name());
            case 4 -> ByteBufCodecs.BOOL.<Change>map(ExposeMembers::new, change -> ((ExposeMembers) change).expose());
            default -> throw new IllegalArgumentException("Not a group change: " + tag);
        });

        private static int tag(Change change) {
            return switch (change) {
                case Join _ -> 0;
                case Leave _ -> 1;
                case Create _ -> 2;
                case Rename _ -> 3;
                case ExposeMembers _ -> 4;
            };
        }

        /** What a player needs on the device whose group it changes; joining, renaming and showing members need more ({@link GroupRules}). */
        default LinkSetting.Gate gate() {
            return this instanceof ExposeMembers ? LinkSetting.Gate.EXPOSE : LinkSetting.Gate.CONFIGURE;
        }
    }

    /** The device joins this group, leaving its own; it takes over the group's state. */
    public record Join(UUID group) implements Change {
    }

    /** The device leaves its group. */
    public record Leave() implements Change {
    }

    /** A new group of this name, which the device joins, leaving its own; it gives the group its state. */
    public record Create(String name) implements Change {
    }

    /** The device's group gets this name, and the slug and topics it gives. */
    public record Rename(String name) implements Change {
    }

    /** Whether the members of the device's group are discovered by Home Assistant as devices of their own. */
    public record ExposeMembers(boolean expose) implements Change {
    }

    /** A group name as typed: sanitised and stripped, at most {@value #MAX_NAME_LENGTH} characters, not blank. */
    public static DataResult<String> name(String typed) {
        Optional<String> problem = NAME_KIND.problem(new JsonPrimitive(typed));
        if (problem.isPresent()) return DataResult.error(() -> "group name: " + problem.get());
        String name = ValueCodecs.sanitizeText(typed).strip();
        return name.isEmpty() ? DataResult.error(() -> "A group needs a name") : DataResult.success(name);
    }

    /** Whether the grants hold what a change asks of the device whose group it changes. */
    public static boolean permits(Change change, DeviceView.Grants grants) {
        return DeviceConfig.permits(change.gate(), grants);
    }

    /**
     * Whether a player may change a group: it has members, and they may configure every one. A group without members
     * is changed by nobody, so no player decides for the owners of the devices it held or takes over the v1 topic it
     * follows; only the {@code /mineqtt group} commands add to it.
     *
     * @param configures whether the player may configure the device with this id
     */
    public static boolean manages(DeviceGroup group, Predicate<UUID> configures) {
        return !group.members().isEmpty() && group.members().stream().allMatch(configures);
    }

    /**
     * Why device {@code device} of type {@code type} may not join {@code group}, or empty when it may: the type must be
     * groupable and the group's, the device not in it yet, the player must manage the group, and the device's owner must
     * be allowed the v1 topic the group replaces, if any.
     *
     * @param configures   whether the player may configure the device with this id
     * @param topicRefusal why the device's owner may not use this topic, as their own Raw Link on it would be judged;
     *                     empty when they may
     */
    public static Optional<String> joinProblem(DeviceGroup group, Identifier type, boolean groupable, UUID device, Predicate<UUID> configures,
                                               Function<String, Optional<String>> topicRefusal) {
        if (!groupable) return Optional.of(type + " devices cannot be grouped");
        if (group.memberType() != null && !group.memberType().equals(type)) return Optional.of("group " + group.name() + " holds " + group.memberType() + " devices");
        if (group.members().contains(device)) return Optional.of("it already is in group " + group.name());
        if (group.members().isEmpty()) return Optional.of("group " + group.name() + " has no devices");
        if (!manages(group, configures)) return Optional.of("you may not configure every device of group " + group.name());
        if (group.legacyTopic() != null) {
            Optional<String> refusal = topicRefusal.apply(group.legacyTopic());
            if (refusal.isPresent()) return Optional.of("group " + group.name() + " follows the v1 topic " + group.legacyTopic() + ": " + refusal.get());
        }
        return Optional.empty();
    }

    /**
     * The groups a device may join, by name: those of its type, other than its own, whose every member the player may
     * configure and whose v1 topic, if they replace one, the device's owner may use.
     *
     * @param topicRefusal why the device's owner may not use this topic; empty when they may
     */
    public static List<DeviceGroup> joinable(DeviceRegistry registry, DeviceRecord record, Predicate<UUID> configures,
                                             Function<String, Optional<String>> topicRefusal) {
        DeviceType type = DeviceTypes.get(record.type());
        boolean groupable = type != null && type.groupable();
        return registry.groups().stream()
                .filter(group -> joinProblem(group, record.type(), groupable, record.id(), configures, topicRefusal).isEmpty())
                .sorted(Comparator.comparing(DeviceGroup::name, String.CASE_INSENSITIVE_ORDER).thenComparing(DeviceGroup::slug))
                .toList();
    }

    /**
     * Whether a group that lost its last member goes with it, taken out by a player or gone with its block: a group made
     * in the game ends with its last member, so no player keeps groups without devices. A group that replaces a v1 topic
     * stays, as lamps on that topic still join it.
     */
    public static boolean dropsWhenEmptied(DeviceGroup group) {
        return group.members().isEmpty() && group.legacyTopic() == null;
    }
}

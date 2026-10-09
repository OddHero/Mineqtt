package art.rehra.mineqtt.device;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A device's group as its General tab's group page shows it to one player (M2 plan §4.2 M2-17): the group it is in, and
 * the groups it may join. Built per player, as who may change a group depends on them ({@link GroupRules}), and sent
 * apart from the {@link DeviceView} (whose bytes it would crowd out) when the screen opens and whenever a group changes.
 * Names are the groups' names at the time it was built.
 *
 * @param groupable whether the device's type can be grouped at all; a sensor's page has nothing to offer
 * @param own       the group the device is in
 * @param joinable  at most {@value #MAX_JOINABLE} groups the device may join, by name
 * @param total     how many groups it may join, also those beyond {@value #MAX_JOINABLE}
 */
public record DeviceGroups(BlockPos pos, boolean groupable, Optional<Own> own, List<Joinable> joinable, int total) {
    public static final int MAX_JOINABLE = 64;
    /** A group's name; one set by command can be longer than a screen's {@value GroupRules#MAX_NAME_LENGTH} characters, and is cut with an ellipsis. */
    public static final int MAX_NAME_BYTES = 256;
    /** A group's state topic: the bridge's topic root, {@code /group/} and a slug. */
    public static final int MAX_TOPIC_BYTES = 512;

    public DeviceGroups {
        joinable = List.copyOf(joinable);
        if (joinable.size() > MAX_JOINABLE) throw new IllegalArgumentException("A group page offers at most " + MAX_JOINABLE + " groups");
        if (total < joinable.size()) throw new IllegalArgumentException("A device may join at least the groups it is offered");
    }

    /** The groups a device may join: the first {@value #MAX_JOINABLE} of {@code all}, counting every one. */
    public static DeviceGroups of(BlockPos pos, boolean groupable, Optional<Own> own, List<Joinable> all) {
        return new DeviceGroups(pos, groupable, own, all.subList(0, Math.min(all.size(), MAX_JOINABLE)), all.size());
    }

    /** Nothing, as a device that does not run on this server shows. */
    public static DeviceGroups none(BlockPos pos) {
        return new DeviceGroups(pos, false, Optional.empty(), List.of(), 0);
    }

    /**
     * The group a device is in.
     *
     * @param topic         its state topic, which its command topic is under
     * @param members       how many devices it holds, this one included
     * @param exposeMembers whether Home Assistant discovers its members as devices of their own
     * @param manages       whether the player may rename it and show or hide its members: they may configure every member
     */
    public record Own(UUID id, String name, String topic, int members, boolean exposeMembers, boolean manages) {
        public static final StreamCodec<ByteBuf, Own> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Own::id,
                ByteBufCodecs.stringUtf8(MAX_NAME_BYTES), Own::name,
                ByteBufCodecs.stringUtf8(MAX_TOPIC_BYTES), Own::topic,
                ByteBufCodecs.VAR_INT, Own::members,
                ByteBufCodecs.BOOL, Own::exposeMembers,
                ByteBufCodecs.BOOL, Own::manages,
                Own::new);

        /** The group with its name and topic cut to what the stream codec carries. */
        public static Own of(UUID id, String name, String topic, int members, boolean exposeMembers, boolean manages) {
            return new Own(id, DeviceView.cap(name, MAX_NAME_BYTES), DeviceView.cap(topic, MAX_TOPIC_BYTES), members, exposeMembers, manages);
        }
    }

    /** A group a device may join, and how many devices it holds. */
    public record Joinable(UUID id, String name, int members) {
        public static final StreamCodec<ByteBuf, Joinable> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Joinable::id,
                ByteBufCodecs.stringUtf8(MAX_NAME_BYTES), Joinable::name,
                ByteBufCodecs.VAR_INT, Joinable::members,
                Joinable::new);

        /** The group with its name cut to what the stream codec carries. */
        public static Joinable of(UUID id, String name, int members) {
            return new Joinable(id, DeviceView.cap(name, MAX_NAME_BYTES), members);
        }
    }

    public static final StreamCodec<ByteBuf, DeviceGroups> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DeviceGroups::pos,
            ByteBufCodecs.BOOL, DeviceGroups::groupable,
            ByteBufCodecs.optional(Own.STREAM_CODEC), DeviceGroups::own,
            ByteBufCodecs.collection(ArrayList::new, Joinable.STREAM_CODEC, MAX_JOINABLE), DeviceGroups::joinable,
            ByteBufCodecs.VAR_INT, DeviceGroups::total,
            DeviceGroups::new);
}

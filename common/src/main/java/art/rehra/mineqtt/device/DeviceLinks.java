package art.rehra.mineqtt.device;

import art.rehra.mineqtt.flow.core.Mapping;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The links of a device as its screen lists them (M2 plan §3.8 #8, M2-16): every wire that starts or ends at the device
 * or ends at its group, in the order they were made, generically, as the seed of the node editor. The server sends it
 * apart from the {@link DeviceView} (whose bytes it would crowd out) when the screen opens and whenever one of the
 * device's links changes. Names are the devices' and groups' names at the time it was built.
 *
 * @param rows  at most {@value #MAX_ROWS} links
 * @param total how many links the device has, also those beyond {@value #MAX_ROWS}
 */
public record DeviceLinks(BlockPos pos, List<Row> rows, int total) {
    public static final int MAX_ROWS = 64;
    /** A port key's cap: the frozen port id pattern ({@code flow.core.Endpoint#isPortKey}) allows 64 characters. */
    public static final int MAX_PORT_CHARS = 64;
    /** A name and its port, such as {@code Garage Input.powered}; a longer name is cut with an ellipsis. */
    public static final int MAX_LABEL_BYTES = 192;

    /** A wire's mapping on the network, by its ordinal; an unknown one reads as {@link Mapping#FOLLOW}. */
    public static final StreamCodec<ByteBuf, Mapping> MAPPING_CODEC = Row.MAPPING_CODEC;

    public DeviceLinks {
        rows = List.copyOf(rows);
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("A device's links list at most " + MAX_ROWS + " wires");
        if (total < rows.size()) throw new IllegalArgumentException("A device has at least the links it lists");
    }

    /** A device's links: the first {@value #MAX_ROWS} of {@code all}, counting every one. */
    public static DeviceLinks of(BlockPos pos, List<Row> all) {
        return new DeviceLinks(pos, all.subList(0, Math.min(all.size(), MAX_ROWS)), all.size());
    }

    /** No links, as a device that does not run on this server shows. */
    public static DeviceLinks none(BlockPos pos) {
        return new DeviceLinks(pos, List.of(), 0);
    }

    /**
     * One wire seen from the device.
     *
     * @param outgoing whether it starts at the device; otherwise it ends at the device or at the device's group
     * @param port     the device's port it starts or ends at (its group's, for a wire into the group)
     * @param peer     the other end, as a name and a port: {@code Door Output.state}, or a group's name for a wire into a group
     * @param group    the name of the group the wire ends at, the other end's for an outgoing wire, the device's own for an
     *                 incoming one; empty when it ends at a device
     * @param twin     whether it is derived from the device's Home Assistant binding (M3 plan §3.3): listed read-only, only to
     *                 viewers who may configure the device, since its peer names the entity (D13 #7), and gone by unbinding
     */
    public record Row(UUID wire, boolean outgoing, String port, String peer, String group, Mapping mapping, boolean enabled, boolean twin) {
        // Here rather than beside the list's codec, so the row's class needs nothing of the list's to initialise.
        private static final IntFunction<Mapping> MAPPING_BY_ID = ByIdMap.continuous(Enum::ordinal, Mapping.values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        private static final StreamCodec<ByteBuf, Mapping> MAPPING_CODEC = ByteBufCodecs.idMapper(MAPPING_BY_ID, Enum::ordinal);
        public static final StreamCodec<ByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Row::wire,
                ByteBufCodecs.BOOL, Row::outgoing,
                ByteBufCodecs.stringUtf8(MAX_PORT_CHARS), Row::port,
                ByteBufCodecs.stringUtf8(MAX_LABEL_BYTES), Row::peer,
                ByteBufCodecs.stringUtf8(MAX_LABEL_BYTES), Row::group,
                MAPPING_CODEC, Row::mapping,
                ByteBufCodecs.BOOL, Row::enabled,
                ByteBufCodecs.BOOL, Row::twin,
                Row::new);

        /** A row with its texts cut to what the stream codec carries. */
        public static Row of(UUID wire, boolean outgoing, String port, String peer, String group, Mapping mapping, boolean enabled) {
            return new Row(wire, outgoing, DeviceView.cap(port, MAX_PORT_CHARS), DeviceView.cap(peer, MAX_LABEL_BYTES),
                    DeviceView.cap(group, MAX_LABEL_BYTES), mapping, enabled, false);
        }

        /** The wire derived from the device's binding, from the entity's port {@code peer} into the device's {@code port}. */
        public static Row twin(UUID wire, String port, String peer) {
            return new Row(wire, false, DeviceView.cap(port, MAX_PORT_CHARS), DeviceView.cap(peer, MAX_LABEL_BYTES), "", Mapping.FOLLOW, true, true);
        }

        /** A wire derived from one of the device's targets (M3-14): its out-port switches the entity {@code peer} names. */
        public static Row twinTarget(UUID wire, String port, String peer) {
            return new Row(wire, true, DeviceView.cap(port, MAX_PORT_CHARS), DeviceView.cap(peer, MAX_LABEL_BYTES), "", Mapping.FOLLOW, true, true);
        }
    }

    public static final StreamCodec<ByteBuf, DeviceLinks> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DeviceLinks::pos,
            ByteBufCodecs.collection(ArrayList::new, Row.STREAM_CODEC, MAX_ROWS), DeviceLinks::rows,
            ByteBufCodecs.VAR_INT, DeviceLinks::total,
            DeviceLinks::new);
}

package art.rehra.mineqtt.ui.explorer;

import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.Slugs;
import art.rehra.mineqtt.presence.Consent;
import art.rehra.mineqtt.presence.PrivacyPolicy;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One page of the Configurator's Devices tab as the server built it for one player (M4 plan D13): the devices of the
 * search that page holds, grouped by room, how many there are in all, the rooms they are in, and the player's own row
 * ({@link You}). It repeats the search it answers, so the tab drops an answer to a search it has moved on from, as the
 * entity picker does.
 * <p>
 * Its {@link #STREAM_CODEC} writes at most {@value #MAX_BYTES} bytes: a page holds at most {@value #PAGE_SIZE} rows and
 * {@value #MAX_ROOMS} rooms, and every text is cut to its cap ({@link Row#of}, {@link RoomRef#of}, {@link You#of}).
 *
 * @param query the search answered, at most {@value #MAX_QUERY_LENGTH} characters
 * @param room  the room filter answered, a room's name; {@code ""} for every room
 * @param page  the page shown, from 0; the last page when the one asked for is beyond it
 * @param total how many devices match the search, also those on other pages
 * @param scope whose devices the rows are, which also says why there are none
 * @param rooms the rooms the player's devices are in, which the room filter offers and the rows point into, at most
 *              {@value #MAX_ROOMS}
 * @param you   what the server shares about the player, and the choices they have
 */
public record ExplorerPage(String query, String room, int page, List<Row> rows, int total, Scope scope, List<RoomRef> rooms, You you) {
    public static final int PAGE_SIZE = 50;
    public static final int MAX_QUERY_LENGTH = 64;
    public static final int MAX_ROOM_LENGTH = 32;
    public static final int MAX_ROOMS = 64;
    /** At most this many output values per row, each at most {@value #MAX_VALUE_LENGTH} code points. */
    public static final int MAX_VALUES = 3;
    public static final int MAX_VALUE_LENGTH = 32;
    /** At most this many lines of what is published about the player, each at most {@value #MAX_REPORT_BYTES} bytes. */
    public static final int MAX_REPORT_LINES = 12;
    public static final int MAX_REPORT_BYTES = 256;
    /** An operator's row lists at most this many presence devices, each at most {@value #MAX_PRESENCE_BYTES} bytes. */
    public static final int MAX_PRESENCE = 16;
    public static final int MAX_PRESENCE_BYTES = 64;
    public static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_NAME_BYTES = 192;
    private static final int MAX_SLUG_BYTES = Slugs.MAX_LENGTH;
    private static final int MAX_MODEL_BYTES = 64;
    private static final int MAX_DETAIL_BYTES = 128;
    private static final int MAX_DIMENSION_BYTES = 64;
    // A code point takes at most four bytes in UTF-8.
    private static final int MAX_VALUE_BYTES = MAX_VALUE_LENGTH * 4;
    private static final int MAX_QUERY_BYTES = MAX_QUERY_LENGTH * 4;
    private static final int MAX_ROOM_BYTES = MAX_ROOM_LENGTH * 4;

    public static final StreamCodec<ByteBuf, ExplorerPage> STREAM_CODEC = StreamCodec.of(ExplorerPage::write, ExplorerPage::read);

    public ExplorerPage {
        rows = List.copyOf(rows);
        rooms = List.copyOf(rooms);
        if (rows.size() > PAGE_SIZE) throw new IllegalArgumentException("A page holds at most " + PAGE_SIZE + " rows");
        if (rooms.size() > MAX_ROOMS) throw new IllegalArgumentException("A page offers at most " + MAX_ROOMS + " rooms");
        if (page < 0 || total < (long) page * PAGE_SIZE + rows.size()) throw new IllegalArgumentException("A page counts at least the rows before and on it");
        if (query.length() > MAX_QUERY_LENGTH || room.length() > MAX_ROOM_LENGTH) throw new IllegalArgumentException("A search is longer than it may be");
        if (rooms.stream().anyMatch(ref -> ref.name().length() > MAX_ROOM_LENGTH)) throw new IllegalArgumentException("A room name is longer than it may be");
        int roomCount = rooms.size();
        if (rows.stream().anyMatch(row -> row.room() >= roomCount)) throw new IllegalArgumentException("A row points to a room the page does not list");
        if (you == null) throw new IllegalArgumentException("A page carries the player's own row");
    }

    /** How many pages the search fills; at least one, also without devices. */
    public int pages() {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** The room a row is in, if any. */
    public Optional<RoomRef> roomOf(Row row) {
        return row.room() < 0 ? Optional.empty() : Optional.of(rooms.get(row.room()));
    }

    /** The distinct names of the rooms, in the page's order, as the room filter steps through them. */
    public List<String> roomNames() {
        List<String> names = new ArrayList<>();
        for (RoomRef ref : rooms) {
            if (names.stream().noneMatch(ref.name()::equalsIgnoreCase)) names.add(ref.name());
        }
        return names;
    }

    /** Whose devices a page lists (M4 plan D13). */
    public enum Scope {
        /** Every device and group of the bridge: an operator who may build. */
        ALL,
        /** The devices the player owns and may configure. */
        OWN,
        /** None: the player may not build (P2), so they configure nothing and find nothing. */
        NONE
    }

    /** How a device is available, as the Devices tab shows it. */
    public enum Availability {
        /** Loaded, with the broker connected. */
        ONLINE,
        /** Loaded, but the broker is not connected; local wires and loopback still work. */
        NO_BROKER,
        /** Unloaded; commands to it wait until its chunk loads ({@code UnloadedPolicy.QUEUE}). */
        QUEUED,
        /** Unloaded and unavailable; commands to it are refused ({@code UnloadedPolicy.UNAVAILABLE}). */
        UNAVAILABLE,
        /** Its attach, power-on or prune check failed. */
        FAULTED,
        /** Its type is unknown to this MineQTT. */
        UNKNOWN_TYPE
    }

    /** Where a device lies from the player, by the eight points of the compass; {@link #HERE} within a block. */
    public enum Bearing {
        HERE, NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST
    }

    /** What a row stands for: a device, or a group of devices (operators only). */
    public enum Kind {
        DEVICE, GROUP
    }

    /**
     * One device or group.
     *
     * @param model     its type's model, or its type's id when the type is unknown; a group's member type's model
     * @param detail    its fault, or why its Raw Link does nothing; empty when nothing is wrong
     * @param values    up to {@value #MAX_VALUES} of its output values as text
     * @param dimension its dimension, without the {@code minecraft} namespace; empty for a group
     * @param distance  how many blocks away it is; -1 in another dimension than the player's, and for a group
     * @param bearing   where it lies from the player; {@link Bearing#HERE} in another dimension
     * @param usable    whether its type declares a use (a lamp's switch, a timer's trigger) the explorer can run
     * @param room      the index of its room in the page's {@link #rooms}; -1 in no room
     * @param members   how many devices a group has; 0 for a device
     */
    public record Row(UUID id, Kind kind, String name, String slug, String model, Availability availability, String detail, List<String> values,
                      String dimension, int distance, Bearing bearing, boolean usable, int room, int members) {
        public Row {
            values = List.copyOf(values);
            if (values.size() > MAX_VALUES) throw new IllegalArgumentException("A row shows at most " + MAX_VALUES + " values");
            if (distance < -1) throw new IllegalArgumentException("A distance is never negative");
            if (room < -1) throw new IllegalArgumentException("A row's room is an index or -1");
            if (members < 0) throw new IllegalArgumentException("A group has no negative member count");
        }

        /** A device's row with its texts cut to what the stream codec carries. */
        public static Row of(UUID id, String name, String slug, String model, Availability availability, String detail, List<String> values,
                             String dimension, int distance, Bearing bearing, boolean usable, int room) {
            return new Row(id, Kind.DEVICE, DeviceView.cap(name, MAX_NAME_BYTES), DeviceView.cap(slug, MAX_SLUG_BYTES), DeviceView.cap(model, MAX_MODEL_BYTES),
                    availability, DeviceView.cap(detail, MAX_DETAIL_BYTES), capValues(values), DeviceView.cap(dimension, MAX_DIMENSION_BYTES), distance,
                    distance < 0 ? Bearing.HERE : bearing, usable, room, 0);
        }

        /** A group's row with its texts cut to what the stream codec carries; a group lies nowhere, so it has no distance. */
        public static Row group(UUID id, String name, String slug, String model, Availability availability, List<String> values, int room, int members) {
            return new Row(id, Kind.GROUP, DeviceView.cap(name, MAX_NAME_BYTES), DeviceView.cap(slug, MAX_SLUG_BYTES), DeviceView.cap(model, MAX_MODEL_BYTES),
                    availability, "", capValues(values), "", -1, Bearing.HERE, false, room, members);
        }

        private static List<String> capValues(List<String> values) {
            return values.stream().limit(MAX_VALUES).map(value -> DeviceView.cap(value, MAX_VALUE_BYTES)).toList();
        }

        /** Whether it lies in the player's dimension, so its distance and bearing are known. */
        public boolean near() {
            return distance >= 0;
        }
    }

    /**
     * A room the player's devices are in.
     *
     * @param devices how many of the devices the player finds are in it, whatever the search
     */
    public record RoomRef(UUID id, String name, int devices) {
        public RoomRef {
            if (devices < 0) throw new IllegalArgumentException("A room holds no negative number of devices");
        }

        /** A room with its name cut to what the stream codec carries. */
        public static RoomRef of(UUID id, String name, int devices) {
            return new RoomRef(id, cut(name, MAX_ROOM_LENGTH), devices);
        }
    }

    /**
     * The player's own row (M4 plan D9, D13): what they chose, what the server shares about them now and at most, and the
     * lines of {@code /mineqtt privacy}. An operator also sees the presence devices of the players who share them, without
     * any position.
     *
     * @param choice        the player's stored choice
     * @param shared        what is shared about them now
     * @param offered       what the server shares at most about a player who consents to everything
     * @param host          whether they host this integrated server, which shares their presence until they choose
     * @param report        what {@code /mineqtt privacy} tells them, at most {@value #MAX_REPORT_LINES} lines
     * @param presence      for an operator: the presence devices, at most {@value #MAX_PRESENCE}; empty for everyone else
     * @param morePresence  how many presence devices {@code presence} leaves out
     */
    public record You(Consent choice, PrivacyPolicy.Shared shared, PrivacyPolicy.Shared offered, boolean host, List<String> report,
                      List<String> presence, int morePresence) {
        /** The row of a player of whom nothing is known, as a page made without a runtime carries it. */
        public static final You NOBODY = new You(Consent.UNSET, PrivacyPolicy.Shared.NOTHING, PrivacyPolicy.Shared.NOTHING, false, List.of(), List.of(), 0);

        public You {
            report = List.copyOf(report);
            presence = List.copyOf(presence);
            if (report.size() > MAX_REPORT_LINES) throw new IllegalArgumentException("The row reports at most " + MAX_REPORT_LINES + " lines");
            if (presence.size() > MAX_PRESENCE) throw new IllegalArgumentException("The row lists at most " + MAX_PRESENCE + " presence devices");
            if (morePresence < 0) throw new IllegalArgumentException("No negative count of presence devices");
        }

        /** The row with its lines cut to what the stream codec carries, the presence devices beyond the cap counted. */
        public static You of(Consent choice, PrivacyPolicy.Shared shared, PrivacyPolicy.Shared offered, boolean host, List<String> report, List<String> presence) {
            return new You(choice, shared, offered, host, report.stream().limit(MAX_REPORT_LINES).map(line -> DeviceView.cap(line, MAX_REPORT_BYTES)).toList(),
                    presence.stream().limit(MAX_PRESENCE).map(line -> DeviceView.cap(line, MAX_PRESENCE_BYTES)).toList(), Math.max(0, presence.size() - MAX_PRESENCE));
        }
    }

    /**
     * A text cut to at most {@code maxLength} characters, as a search crosses the wire ({@code ExplorerQueryPayload}),
     * without splitting a surrogate pair.
     */
    public static String cut(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end);
    }

    private static void write(ByteBuf buf, ExplorerPage page) {
        int start = buf.writerIndex();
        writeText(buf, page.query, MAX_QUERY_BYTES);
        writeText(buf, page.room, MAX_ROOM_BYTES);
        VarInt.write(buf, page.page);
        VarInt.write(buf, page.total);
        writeEnum(buf, page.scope);
        VarInt.write(buf, page.rows.size());
        for (Row row : page.rows) {
            UUIDUtil.STREAM_CODEC.encode(buf, row.id());
            writeEnum(buf, row.kind());
            writeText(buf, row.name(), MAX_NAME_BYTES);
            writeText(buf, row.slug(), MAX_SLUG_BYTES);
            writeText(buf, row.model(), MAX_MODEL_BYTES);
            writeEnum(buf, row.availability());
            writeText(buf, row.detail(), MAX_DETAIL_BYTES);
            VarInt.write(buf, row.values().size());
            for (String value : row.values()) writeText(buf, value, MAX_VALUE_BYTES);
            writeText(buf, row.dimension(), MAX_DIMENSION_BYTES);
            VarInt.write(buf, row.distance() + 1);
            writeEnum(buf, row.bearing());
            buf.writeBoolean(row.usable());
            VarInt.write(buf, row.room() + 1);
            VarInt.write(buf, row.members());
        }
        VarInt.write(buf, page.rooms.size());
        for (RoomRef ref : page.rooms) {
            UUIDUtil.STREAM_CODEC.encode(buf, ref.id());
            writeText(buf, ref.name(), MAX_ROOM_BYTES);
            VarInt.write(buf, ref.devices());
        }
        You you = page.you;
        writeEnum(buf, you.choice());
        writeEnum(buf, you.shared());
        writeEnum(buf, you.offered());
        buf.writeBoolean(you.host());
        VarInt.write(buf, you.report().size());
        for (String line : you.report()) writeText(buf, line, MAX_REPORT_BYTES);
        VarInt.write(buf, you.presence().size());
        for (String line : you.presence()) writeText(buf, line, MAX_PRESENCE_BYTES);
        VarInt.write(buf, you.morePresence());
        if (buf.writerIndex() - start > MAX_BYTES) throw new EncoderException("An explorer page takes more than " + MAX_BYTES + " bytes");
    }

    private static ExplorerPage read(ByteBuf buf) {
        int start = buf.readerIndex();
        String query = readText(buf, MAX_QUERY_BYTES);
        String room = readText(buf, MAX_ROOM_BYTES);
        int page = readCount(buf);
        int total = readCount(buf);
        Scope scope = readEnum(buf, Scope.values());
        int rowCount = readCount(buf, PAGE_SIZE);
        List<Row> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
            Kind kind = readEnum(buf, Kind.values());
            String name = readText(buf, MAX_NAME_BYTES);
            String slug = readText(buf, MAX_SLUG_BYTES);
            String model = readText(buf, MAX_MODEL_BYTES);
            Availability availability = readEnum(buf, Availability.values());
            String detail = readText(buf, MAX_DETAIL_BYTES);
            int valueCount = readCount(buf, MAX_VALUES);
            List<String> values = new ArrayList<>(valueCount);
            for (int v = 0; v < valueCount; v++) values.add(readText(buf, MAX_VALUE_BYTES));
            String dimension = readText(buf, MAX_DIMENSION_BYTES);
            int distance = readCount(buf) - 1;
            Bearing bearing = readEnum(buf, Bearing.values());
            boolean usable = buf.readBoolean();
            int roomIndex = readCount(buf, MAX_ROOMS) - 1;
            int members = readCount(buf);
            rows.add(new Row(id, kind, name, slug, model, availability, detail, values, dimension, distance, bearing, usable, roomIndex, members));
        }
        int roomCount = readCount(buf, MAX_ROOMS);
        List<RoomRef> rooms = new ArrayList<>(roomCount);
        for (int i = 0; i < roomCount; i++) {
            UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
            String name = readText(buf, MAX_ROOM_BYTES);
            rooms.add(new RoomRef(id, name, readCount(buf)));
        }
        Consent choice = readEnum(buf, Consent.values());
        PrivacyPolicy.Shared shared = readEnum(buf, PrivacyPolicy.Shared.values());
        PrivacyPolicy.Shared offered = readEnum(buf, PrivacyPolicy.Shared.values());
        boolean host = buf.readBoolean();
        int reportCount = readCount(buf, MAX_REPORT_LINES);
        List<String> report = new ArrayList<>(reportCount);
        for (int i = 0; i < reportCount; i++) report.add(readText(buf, MAX_REPORT_BYTES));
        int presenceCount = readCount(buf, MAX_PRESENCE);
        List<String> presence = new ArrayList<>(presenceCount);
        for (int i = 0; i < presenceCount; i++) presence.add(readText(buf, MAX_PRESENCE_BYTES));
        int morePresence = readCount(buf);
        if (buf.readerIndex() - start > MAX_BYTES) throw new DecoderException("An explorer page takes more than " + MAX_BYTES + " bytes");
        try {
            return new ExplorerPage(query, room, page, rows, total, scope, rooms, new You(choice, shared, offered, host, report, presence, morePresence));
        } catch (IllegalArgumentException e) {
            throw new DecoderException(e.getMessage());
        }
    }

    private static void writeText(ByteBuf buf, String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new EncoderException("An explorer page text of " + bytes.length + " bytes exceeds " + maxBytes);
        FriendlyByteBuf.writeByteArray(buf, bytes);
    }

    private static String readText(ByteBuf buf, int maxBytes) {
        return new String(FriendlyByteBuf.readByteArray(buf, maxBytes), StandardCharsets.UTF_8);
    }

    private static int readCount(ByteBuf buf) {
        int count = VarInt.read(buf);
        if (count < 0) throw new DecoderException("An explorer page count is negative");
        return count;
    }

    private static int readCount(ByteBuf buf, int max) {
        int count = readCount(buf);
        if (count > max) throw new DecoderException("An explorer page lists " + count + " entries, at most " + max);
        return count;
    }

    private static void writeEnum(ByteBuf buf, Enum<?> value) {
        VarInt.write(buf, value.ordinal());
    }

    private static <E extends Enum<E>> E readEnum(ByteBuf buf, E[] values) {
        int ordinal = VarInt.read(buf);
        if (ordinal < 0 || ordinal >= values.length) throw new DecoderException("An explorer page has an unknown " + values[0].getDeclaringClass().getSimpleName());
        return values[ordinal];
    }
}

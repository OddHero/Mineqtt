package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.device.Slugs;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.telemetry.core.Weather;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a Bridge Console shows one player (M2 plan §4.2 M2-23): how the bridge runs ({@link Bridge}), the world's
 * telemetry ({@link World}, M4-17), the devices and groups the player may configure (every one for an operator), and
 * whether they may run the console's actions. The server builds it for each viewer ({@link BridgeConsole}), into the open
 * data of the screen and again every second while it is open, so a player never receives a device they may not configure,
 * nor a player's name unless they are an operator.
 * <p>
 * Its {@link #STREAM_CODEC} writes at most {@value #MAX_BYTES} bytes: {@link #of} keeps the first
 * {@value #MAX_DEVICE_ROWS} devices and {@value #MAX_GROUP_ROWS} groups and the rows cut their texts to their caps.
 *
 * @param devices     the first {@value #MAX_DEVICE_ROWS} devices, faulted ones and ones with a problem first
 * @param deviceTotal how many devices the player may configure, also those beyond the rows
 * @param groups      the first {@value #MAX_GROUP_ROWS} groups
 * @param groupTotal  how many groups the player manages, also those beyond the rows
 * @param operator    whether the player may run the console's actions ({@link BridgeAction})
 */
public record ConsoleView(BlockPos pos, Bridge bridge, World world, List<DeviceRow> devices, int deviceTotal, List<GroupRow> groups, int groupTotal,
                          boolean operator) {
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_DEVICE_ROWS = 64;
    public static final int MAX_GROUP_ROWS = 32;
    private static final int MAX_NAME_BYTES = 192;
    private static final int MAX_SLUG_BYTES = Slugs.MAX_LENGTH;
    private static final int MAX_TYPE_BYTES = 64;
    private static final int MAX_DETAIL_BYTES = 256;
    private static final int MAX_WHERE_BYTES = 96;
    private static final int MAX_BRIDGE_TEXT_BYTES = 512;
    private static final int MAX_WORD_BYTES = 32;
    /** At most this many player names, which only an operator's view carries. */
    public static final int MAX_PLAYER_NAMES = 16;
    // A Minecraft name has at most 16 characters of a-z, A-Z, 0-9 and _; a modded server may allow more.
    private static final int MAX_PLAYER_NAME_BYTES = 64;
    private static final int MAX_TELEMETRY_BYTES = 256;

    public static final StreamCodec<ByteBuf, ConsoleView> STREAM_CODEC = StreamCodec.of(ConsoleView::write, ConsoleView::read);

    public ConsoleView {
        devices = List.copyOf(devices);
        groups = List.copyOf(groups);
        if (devices.size() > MAX_DEVICE_ROWS) throw new IllegalArgumentException("A console lists at most " + MAX_DEVICE_ROWS + " devices");
        if (groups.size() > MAX_GROUP_ROWS) throw new IllegalArgumentException("A console lists at most " + MAX_GROUP_ROWS + " groups");
        if (deviceTotal < devices.size() || groupTotal < groups.size()) throw new IllegalArgumentException("A console counts at least the rows it lists");
    }

    /** A console of every visible device and group, keeping the first rows of each and counting all of them. */
    public static ConsoleView of(BlockPos pos, Bridge bridge, World world, List<DeviceRow> devices, List<GroupRow> groups, boolean operator) {
        return new ConsoleView(pos, bridge, world, devices.subList(0, Math.min(devices.size(), MAX_DEVICE_ROWS)), devices.size(),
                groups.subList(0, Math.min(groups.size(), MAX_GROUP_ROWS)), groups.size(), operator);
    }

    /**
     * The devices a player sees, in the console's order: every one for an operator, else those they may configure.
     * Faulted devices come first, then devices of a type unknown to this MineQTT, then devices with a problem, then the
     * rest, each by name, so the cap of the rows never hides what needs attention.
     *
     * @param configures whether the player may configure the device with this id ({@code DeviceRuntime.configurer})
     * @param row        a device's row, built only for the devices the player sees
     */
    public static List<DeviceRow> visibleDevices(Collection<DeviceRecord> records, boolean operator, Predicate<UUID> configures,
                                                 Function<DeviceRecord, DeviceRow> row) {
        List<DeviceRow> rows = new ArrayList<>();
        for (DeviceRecord record : records) {
            if (operator || configures.test(record.id())) rows.add(row.apply(record));
        }
        rows.sort(Comparator.comparingInt(DeviceRow::urgency)
                .thenComparing(device -> device.name().toLowerCase(Locale.ROOT))
                .thenComparing(DeviceRow::slug));
        return rows;
    }

    /**
     * The groups a player sees, by name: every one for an operator, else those they manage ({@link GroupRules#manages}:
     * they may configure every member), as the group pages of a device's screen decide.
     */
    public static List<GroupRow> visibleGroups(Collection<DeviceGroup> groups, boolean operator, Predicate<UUID> configures) {
        return groups.stream().filter(group -> operator || GroupRules.manages(group, configures))
                .map(GroupRow::of)
                .sorted(Comparator.comparing((GroupRow group) -> group.name().toLowerCase(Locale.ROOT)).thenComparing(GroupRow::slug))
                .toList();
    }

    /** Whether Home Assistant was heard from on its birth topic, as the console reads {@code BirthWatcher}'s status. */
    public enum HomeAssistant {
        /** Nothing since the server started, or since the birth topic changed. */
        NOT_SEEN,
        /** Its birth: it (re)started. */
        ONLINE,
        /** Its last will: it went away. */
        OFFLINE
    }

    /** Whether the server ticks while nobody is online (M2 plan D5). */
    public enum ServerState {
        /** A singleplayer or LAN world: it pauses only with the game menu, and is never pumped. */
        INTEGRATED,
        /** A dedicated server with players, or one that has not paused yet. */
        TICKING,
        /** {@code pause-when-empty-seconds} is 0. */
        NEVER_PAUSES,
        /** Empty and paused: MQTT is handled between ticks. */
        PAUSED,
        /** {@code server.keepTickingWhenEmpty} overrides {@code pause-when-empty-seconds}. */
        KEPT_TICKING
    }

    /**
     * How the bridge runs, as {@code /mineqtt status} says it.
     *
     * @param topicRoot             {@code <base>/<bridgeId>}
     * @param connectionDetail      the last disconnect cause while reconnecting, the reason when failed, else empty
     * @param broker                the broker with its credentials masked, "none" without one
     * @param protocol              the configured MQTT version
     * @param homeAssistantAgeMillis how long ago its birth or last will came; -1 when none came
     * @param haExpose              the effective {@code ha.expose}
     * @param discovered            how many devices and groups Home Assistant discovers
     * @param pauseSeconds          the {@code pause-when-empty-seconds} it pauses after (the configured value when kept
     *                              ticking); 0 on an integrated server and when it never pauses
     * @param droppedIn             inbound messages the queue dropped
     * @param droppedOut            outbound messages the outbox dropped
     * @param wires                 every wire of the bridge
     * @param delivered             messages the wires delivered since the server started
     * @param wireDrops             messages the wires dropped since the server started, for any reason
     */
    public record Bridge(String topicRoot, ConnectionState.Status connection, String connectionDetail, String broker, String protocol,
                         HomeAssistant homeAssistant, long homeAssistantAgeMillis, String haExpose, long discovered, ServerState server,
                         int pauseSeconds, long droppedIn, long droppedOut, int queuedIn, int outbox, int wires, long delivered, long wireDrops) {

        /**
         * A bridge with its texts cut to what the stream codec carries. A {@code pauseSeconds} below 0 becomes 0: vanilla
         * takes any {@code pause-when-empty-seconds} of 0 or less as never pausing, and the codec carries no negative count.
         */
        public static Bridge of(String topicRoot, ConnectionState state, String broker, String protocol, HomeAssistant homeAssistant,
                                long homeAssistantAgeMillis, String haExpose, long discovered, ServerState server, int pauseSeconds, long droppedIn,
                                long droppedOut, int queuedIn, int outbox, int wires, long delivered, long wireDrops) {
            return new Bridge(DeviceView.cap(topicRoot, MAX_BRIDGE_TEXT_BYTES), state.status(), DeviceView.cap(state.detail(), MAX_BRIDGE_TEXT_BYTES),
                    DeviceView.cap(broker, MAX_BRIDGE_TEXT_BYTES), DeviceView.cap(protocol, MAX_WORD_BYTES), homeAssistant, homeAssistantAgeMillis,
                    DeviceView.cap(haExpose, MAX_WORD_BYTES), discovered, server, Math.max(0, pauseSeconds), droppedIn, droppedOut, queuedIn, outbox,
                    wires, delivered, wireDrops);
        }
    }

    /**
     * The world as the bridge's telemetry reads it (M4 plan D6, M4-17), as the Bridge tab shows it, whether or not the
     * telemetry is published.
     *
     * @param tps           ticks per second, one decimal: 0 while frozen
     * @param mspt          the average milliseconds per tick, one decimal
     * @param timeOfDay     {@code HH:MM}, in steps of 15 game minutes
     * @param day           whole overworld days
     * @param isDay         06:00 up to 18:00 by the clock
     * @param playersOnline how many players are online, which is not personal
     * @param players       the names of online players the name policy allows (M4 plan D9), at most
     *                      {@value #MAX_PLAYER_NAMES}; only an operator's view carries them, everyone else's none
     * @param named         how many online players the name policy allows, also those beyond {@code players}; 0 in a
     *                      view that carries no names
     * @param telemetry     the telemetry line of {@code /mineqtt status} (M4 plan §1.3)
     */
    public record World(double tps, double mspt, String timeOfDay, long day, boolean isDay, Weather weather, int playersOnline, List<String> players,
                        int named, String telemetry) {
        public World {
            players = List.copyOf(players);
            if (players.size() > MAX_PLAYER_NAMES) throw new IllegalArgumentException("A console names at most " + MAX_PLAYER_NAMES + " players");
            if (named < players.size() || playersOnline < 0 || day < 0) throw new IllegalArgumentException("A console counts at least the players it names");
        }

        /**
         * The world with its texts cut to what the stream codec carries, naming the first {@value #MAX_PLAYER_NAMES}
         * players only for an operator.
         *
         * @param players  the names of online players the name policy allows
         * @param operator whether the view is an operator's: only theirs carries names
         */
        public static World of(double tps, double mspt, String timeOfDay, long day, boolean isDay, Weather weather, int playersOnline,
                               List<String> players, boolean operator, String telemetry) {
            List<String> shown = operator ? players.stream().limit(MAX_PLAYER_NAMES).map(name -> DeviceView.cap(name, MAX_PLAYER_NAME_BYTES)).toList() : List.of();
            return new World(tps, mspt, DeviceView.cap(timeOfDay, MAX_WORD_BYTES), Math.max(0, day), isDay, weather, Math.max(0, playersOnline), shown,
                    operator ? players.size() : 0, DeviceView.cap(telemetry, MAX_TELEMETRY_BYTES));
        }
    }

    /**
     * How a device runs, as the console and the Configurator's Devices tab list it: faulted, of a type unknown to this
     * MineQTT, loaded with the broker connected or not, or unloaded.
     *
     * @param faulted   its attach, power-on or prune check failed
     * @param knownType its type is registered in this MineQTT
     * @param attached  it runs on this server
     * @param connected the broker is connected
     */
    public static State state(boolean faulted, boolean knownType, boolean attached, boolean connected) {
        if (faulted) return State.FAULTED;
        if (!knownType) return State.UNKNOWN_TYPE;
        if (!attached) return State.UNLOADED;
        return connected ? State.ONLINE : State.NO_BROKER;
    }

    /** A device type's id as the console and the Devices tab show it: without the {@code mineqtt} namespace. */
    public static String typeName(Identifier type) {
        return type.getNamespace().equals(MineQTT.MOD_ID) ? type.getPath() : type.toString();
    }

    /** A dimension as the console and the Devices tab name it: without the {@code minecraft} namespace. */
    public static String dimensionName(Identifier dimension) {
        return dimension.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? dimension.getPath() : dimension.toString();
    }

    /** How a device runs, as the console lists it. */
    public enum State {
        /** Loaded, with the broker connected. */
        ONLINE,
        /** Loaded, but the broker is not connected; local wires and loopback still work. */
        NO_BROKER,
        /** Its chunk is not loaded. */
        UNLOADED,
        /** Its attach, power-on or prune check failed; retried with {@code /mineqtt devices retry} or its next chunk load. */
        FAULTED,
        /** Its type is unknown to this MineQTT, so nothing of it is published. */
        UNKNOWN_TYPE
    }

    /**
     * One device.
     *
     * @param type   its type's id without the {@code mineqtt} namespace
     * @param detail its fault, or why its Raw Link does nothing; empty when nothing is wrong
     * @param where  its dimension and position
     */
    public record DeviceRow(String name, String slug, String type, State state, String detail, String where) {

        /** A row with its texts cut to what the stream codec carries. */
        public static DeviceRow of(String name, String slug, String type, State state, String detail, String where) {
            return new DeviceRow(DeviceView.cap(name, MAX_NAME_BYTES), DeviceView.cap(slug, MAX_SLUG_BYTES), DeviceView.cap(type, MAX_TYPE_BYTES),
                    state, DeviceView.cap(detail, MAX_DETAIL_BYTES), DeviceView.cap(where, MAX_WHERE_BYTES));
        }

        /** Whether the console shows it as needing attention: faulted, of an unknown type, or with a problem. */
        public boolean troubled() {
            return urgency() < 3;
        }

        // The console's order: what needs attention first.
        private int urgency() {
            if (state == State.FAULTED) return 0;
            if (state == State.UNKNOWN_TYPE) return 1;
            return detail.isEmpty() ? 3 : 2;
        }
    }

    /**
     * One group.
     *
     * @param memberType its members' type's id without the {@code mineqtt} namespace; empty without members
     * @param discovered whether Home Assistant discovers it
     */
    public record GroupRow(String name, String slug, String memberType, int members, boolean discovered) {

        static GroupRow of(DeviceGroup group) {
            String type = group.memberType() == null ? "" : group.memberType().getPath();
            return new GroupRow(DeviceView.cap(group.name(), MAX_NAME_BYTES), DeviceView.cap(group.slug(), MAX_SLUG_BYTES),
                    DeviceView.cap(type, MAX_TYPE_BYTES), group.members().size(), group.discoveryHash() != null);
        }
    }

    private static void write(ByteBuf buf, ConsoleView view) {
        int start = buf.writerIndex();
        BlockPos.STREAM_CODEC.encode(buf, view.pos);
        Bridge bridge = view.bridge;
        writeText(buf, bridge.topicRoot(), MAX_BRIDGE_TEXT_BYTES);
        writeEnum(buf, bridge.connection());
        writeText(buf, bridge.connectionDetail(), MAX_BRIDGE_TEXT_BYTES);
        writeText(buf, bridge.broker(), MAX_BRIDGE_TEXT_BYTES);
        writeText(buf, bridge.protocol(), MAX_WORD_BYTES);
        writeEnum(buf, bridge.homeAssistant());
        VarLong.write(buf, Math.max(-1, bridge.homeAssistantAgeMillis()) + 1);
        writeText(buf, bridge.haExpose(), MAX_WORD_BYTES);
        writeCount(buf, bridge.discovered());
        writeEnum(buf, bridge.server());
        writeCount(buf, bridge.pauseSeconds());
        writeCount(buf, bridge.droppedIn());
        writeCount(buf, bridge.droppedOut());
        writeCount(buf, bridge.queuedIn());
        writeCount(buf, bridge.outbox());
        writeCount(buf, bridge.wires());
        writeCount(buf, bridge.delivered());
        writeCount(buf, bridge.wireDrops());
        World world = view.world;
        buf.writeDouble(world.tps());
        buf.writeDouble(world.mspt());
        writeText(buf, world.timeOfDay(), MAX_WORD_BYTES);
        writeCount(buf, world.day());
        buf.writeBoolean(world.isDay());
        writeEnum(buf, world.weather());
        writeCount(buf, world.playersOnline());
        VarInt.write(buf, world.players().size());
        for (String name : world.players()) writeText(buf, name, MAX_PLAYER_NAME_BYTES);
        writeCount(buf, world.named());
        writeText(buf, world.telemetry(), MAX_TELEMETRY_BYTES);
        VarInt.write(buf, view.devices.size());
        for (DeviceRow row : view.devices) {
            writeText(buf, row.name(), MAX_NAME_BYTES);
            writeText(buf, row.slug(), MAX_SLUG_BYTES);
            writeText(buf, row.type(), MAX_TYPE_BYTES);
            writeEnum(buf, row.state());
            writeText(buf, row.detail(), MAX_DETAIL_BYTES);
            writeText(buf, row.where(), MAX_WHERE_BYTES);
        }
        writeCount(buf, view.deviceTotal);
        VarInt.write(buf, view.groups.size());
        for (GroupRow row : view.groups) {
            writeText(buf, row.name(), MAX_NAME_BYTES);
            writeText(buf, row.slug(), MAX_SLUG_BYTES);
            writeText(buf, row.memberType(), MAX_TYPE_BYTES);
            writeCount(buf, row.members());
            buf.writeBoolean(row.discovered());
        }
        writeCount(buf, view.groupTotal);
        buf.writeBoolean(view.operator);
        if (buf.writerIndex() - start > MAX_BYTES) throw new EncoderException("A console view takes more than " + MAX_BYTES + " bytes");
    }

    private static ConsoleView read(ByteBuf buf) {
        int start = buf.readerIndex();
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
        Bridge bridge = new Bridge(readText(buf, MAX_BRIDGE_TEXT_BYTES), readEnum(buf, ConnectionState.Status.values()),
                readText(buf, MAX_BRIDGE_TEXT_BYTES), readText(buf, MAX_BRIDGE_TEXT_BYTES), readText(buf, MAX_WORD_BYTES),
                readEnum(buf, HomeAssistant.values()), VarLong.read(buf) - 1, readText(buf, MAX_WORD_BYTES), readCount(buf),
                readEnum(buf, ServerState.values()), readInt(buf), readCount(buf), readCount(buf), readInt(buf), readInt(buf), readInt(buf),
                readCount(buf), readCount(buf));
        double tps = buf.readDouble();
        double mspt = buf.readDouble();
        String timeOfDay = readText(buf, MAX_WORD_BYTES);
        long day = readCount(buf);
        boolean isDay = buf.readBoolean();
        Weather weather = readEnum(buf, Weather.values());
        int playersOnline = readInt(buf);
        int nameCount = readRows(buf, MAX_PLAYER_NAMES);
        List<String> names = new ArrayList<>(nameCount);
        for (int i = 0; i < nameCount; i++) names.add(readText(buf, MAX_PLAYER_NAME_BYTES));
        int named = readInt(buf);
        String telemetry = readText(buf, MAX_TELEMETRY_BYTES);
        int deviceRows = readRows(buf, MAX_DEVICE_ROWS);
        List<DeviceRow> devices = new ArrayList<>(deviceRows);
        for (int i = 0; i < deviceRows; i++) {
            devices.add(new DeviceRow(readText(buf, MAX_NAME_BYTES), readText(buf, MAX_SLUG_BYTES), readText(buf, MAX_TYPE_BYTES),
                    readEnum(buf, State.values()), readText(buf, MAX_DETAIL_BYTES), readText(buf, MAX_WHERE_BYTES)));
        }
        int deviceTotal = readInt(buf);
        int groupRows = readRows(buf, MAX_GROUP_ROWS);
        List<GroupRow> groups = new ArrayList<>(groupRows);
        for (int i = 0; i < groupRows; i++) {
            groups.add(new GroupRow(readText(buf, MAX_NAME_BYTES), readText(buf, MAX_SLUG_BYTES), readText(buf, MAX_TYPE_BYTES),
                    readInt(buf), buf.readBoolean()));
        }
        int groupTotal = readInt(buf);
        boolean operator = buf.readBoolean();
        if (buf.readerIndex() - start > MAX_BYTES) throw new DecoderException("A console view takes more than " + MAX_BYTES + " bytes");
        try {
            World world = new World(tps, mspt, timeOfDay, day, isDay, weather, playersOnline, names, named, telemetry);
            return new ConsoleView(pos, bridge, world, devices, deviceTotal, groups, groupTotal, operator);
        } catch (IllegalArgumentException e) {
            throw new DecoderException(e.getMessage());
        }
    }

    private static void writeText(ByteBuf buf, String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new EncoderException("A console view text of " + bytes.length + " bytes exceeds " + maxBytes);
        FriendlyByteBuf.writeByteArray(buf, bytes);
    }

    private static String readText(ByteBuf buf, int maxBytes) {
        return new String(FriendlyByteBuf.readByteArray(buf, maxBytes), StandardCharsets.UTF_8);
    }

    private static void writeCount(ByteBuf buf, long count) {
        if (count < 0) throw new EncoderException("A console view count is negative: " + count);
        VarLong.write(buf, count);
    }

    private static long readCount(ByteBuf buf) {
        long count = VarLong.read(buf);
        if (count < 0) throw new DecoderException("A console view count is negative");
        return count;
    }

    private static int readInt(ByteBuf buf) {
        long count = readCount(buf);
        if (count > Integer.MAX_VALUE) throw new DecoderException("A console view count is too large");
        return (int) count;
    }

    private static int readRows(ByteBuf buf, int max) {
        int rows = VarInt.read(buf);
        if (rows < 0 || rows > max) throw new DecoderException("A console view lists " + rows + " rows, at most " + max);
        return rows;
    }

    private static void writeEnum(ByteBuf buf, Enum<?> value) {
        VarInt.write(buf, value.ordinal());
    }

    private static <E extends Enum<E>> E readEnum(ByteBuf buf, E[] values) {
        int ordinal = VarInt.read(buf);
        if (ordinal < 0 || ordinal >= values.length) throw new DecoderException("A console view has an unknown " + values[0].getDeclaringClass().getSimpleName());
        return values[ordinal];
    }
}

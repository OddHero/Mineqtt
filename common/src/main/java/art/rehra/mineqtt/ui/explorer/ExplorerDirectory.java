package art.rehra.mineqtt.ui.explorer;

import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.flow.core.Coerce;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.network.MineqttNetworking.ExplorerQueryPayload;
import art.rehra.mineqtt.presence.PlayerEntry;
import art.rehra.mineqtt.presence.PlayerPrivacy;
import art.rehra.mineqtt.presence.PrivacyCommands;
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.rooms.RoomRules;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.console.ConsoleView;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The server side of the Configurator's Devices tab (M4 plan D13): answers each search of a player with the page of the
 * devices they may find, grouped by room. A player finds the devices they own and may configure, an operator who may
 * build every device and group, and a player who may not build (P2) none. A device of another player is never listed,
 * not even a public one or one whose claim trusts the player: its distance and direction would lead anyone to other
 * players' bases. The explorer's actions reach only the devices a player finds ({@link #finds}). Every page also carries
 * the player's own row: what the server shares about them ({@code /mineqtt privacy}). Server thread only; the pure parts
 * are static and checked by {@code verifySecurityGuards}.
 */
public final class ExplorerDirectory {
    /** A player's searches: a burst of {@value #QUERY_BURST}, then {@value #QUERIES_PER_SECOND} per second. */
    public static final int QUERY_BURST = 6;
    public static final double QUERIES_PER_SECOND = 4;
    // Bidirectional overrides and isolates, which would turn a value's text around the rest of the row.
    private static final Pattern BIDI_CONTROLS = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069]");
    // A panel's lines and other text with line breaks show on one line, a space between them.
    private static final Pattern LINE_BREAKS = Pattern.compile("\\R");
    /** The order of the rooms: by name ignoring case, then by id, so two rooms of one name stay apart. */
    public static final Comparator<Room> ROOM_ORDER = Comparator.comparing((Room room) -> RoomRules.key(room.name())).thenComparing(Room::id);
    // Rooms first in their order, then what is in no room.
    private static final Comparator<Optional<Room>> IN_ROOM = Comparator.comparing((Optional<Room> room) -> room.isEmpty())
            .thenComparing(room -> room.orElse(null), Comparator.nullsLast(ROOM_ORDER));
    private static final Comparator<Listed> LISTED_ORDER = Comparator.comparing(Listed::room, IN_ROOM)
            .thenComparing((Listed listed) -> listed.name().toLowerCase(Locale.ROOT)).thenComparing(Listed::slug);

    private ExplorerDirectory() {
    }

    /**
     * {@code ExplorerQueryC2S}, after the guard found the player's explorer open: a search over
     * {@value #QUERIES_PER_SECOND} per second (after a burst of {@value #QUERY_BURST}) is dropped without an answer, which
     * the tab sends again; otherwise the player gets the page they asked for.
     */
    public static void query(ServerPlayer player, CyberdeckMenu menu, ExplorerQueryPayload query) {
        if (!menu.takeExplorerQuery()) return;
        MineqttRuntime.current().filter(runtime -> runtime.server() == player.level().getServer())
                .ifPresent(runtime -> MineqttNetworking.sendExplorerPage(player, page(player, runtime, query.query(), query.room(), query.page())));
    }

    /** The page of a search as a player sees it. */
    public static ExplorerPage page(ServerPlayer player, MineqttRuntime runtime, String query, String room, int page) {
        ExplorerPage.Scope scope = scope(player);
        DeviceRuntime devices = runtime.devices();
        Function<DeviceRecord, Optional<Room>> roomOf = runtime.registry()::roomOf;
        Predicate<DeviceRecord> owns = record -> player.getUUID().equals(record.owner());
        Predicate<UUID> configures = devices.configurer(player);
        Collection<DeviceRecord> records = runtime.registry().records();
        List<ExplorerPage.RoomRef> rooms = rooms(matching(records, scope, owns, configures, "", "", roomOf), roomOf);
        List<Listed> listed = new ArrayList<>();
        for (DeviceRecord record : matching(records, scope, owns, configures, query, room, roomOf)) listed.add(Listed.of(record, roomOf.apply(record)));
        Function<DeviceGroup, Optional<Room>> groupRoom = group -> commonRoom(runtime, group);
        for (DeviceGroup group : matchingGroups(runtime.registry().groups(), scope, query, room, groupRoom)) listed.add(Listed.of(group, groupRoom.apply(group)));
        listed.sort(LISTED_ORDER);
        int shown = clampPage(page, listed.size());
        boolean connected = runtime.mqtt().state().status() == ConnectionState.Status.CONNECTED;
        GlobalPos viewer = GlobalPos.of(player.level().dimension(), player.blockPosition());
        List<ExplorerPage.Row> rows = listed.subList(shown * ExplorerPage.PAGE_SIZE, Math.min(listed.size(), (shown + 1) * ExplorerPage.PAGE_SIZE))
                .stream().map(entry -> entry.record() != null
                        ? row(entry.record(), devices, connected, viewer, roomIndex(rooms, entry.room()))
                        : groupRow(runtime, entry.group(), connected, roomIndex(rooms, entry.room()))).toList();
        return new ExplorerPage(ExplorerPage.cut(query, ExplorerPage.MAX_QUERY_LENGTH), ExplorerPage.cut(room, ExplorerPage.MAX_ROOM_LENGTH), shown, rows,
                listed.size(), scope, rooms, you(player, runtime, scope == ExplorerPage.Scope.ALL));
    }

    /** Whose devices a player finds, from their permissions and whether they may build now. */
    public static ExplorerPage.Scope scope(ServerPlayer player) {
        return scope(AclContext.isOp(player.permissions()), DeviceAccess.mayBuild(player));
    }

    /**
     * Whether a player finds a device: {@link #lists} for their scope, whether they own the device and whether they may
     * configure it now. Every page and every explorer action ({@link ExplorerActions}) asks it afresh, so an action never
     * reaches, and a locate never reveals, a device the page would not list.
     */
    public static boolean finds(ServerPlayer player, DeviceRuntime devices, DeviceRecord record) {
        return lists(scope(player), player.getUUID().equals(record.owner()), () -> devices.configurer(player).test(record.id()));
    }

    // A device or a group as the tab lists it, with the room it is in.
    private record Listed(@Nullable DeviceRecord record, @Nullable DeviceGroup group, Optional<Room> room) {
        static Listed of(DeviceRecord record, Optional<Room> room) {
            return new Listed(record, null, room);
        }

        static Listed of(DeviceGroup group, Optional<Room> room) {
            return new Listed(null, group, room);
        }

        String name() {
            return record != null ? record.name() : Objects.requireNonNull(group).name();
        }

        String slug() {
            return record != null ? record.slug() : Objects.requireNonNull(group).slug();
        }
    }

    // The room of a group: the one all its members are in, as its suggested_area.
    private static Optional<Room> commonRoom(MineqttRuntime runtime, DeviceGroup group) {
        List<DeviceRecord> members = group.members().stream().map(runtime.registry()::record).filter(Objects::nonNull).toList();
        return runtime.registry().roomIndex().commonRoom(members);
    }

    // The index of a room in the page's rooms; -1 in no room or one beyond the page's cap.
    private static int roomIndex(List<ExplorerPage.RoomRef> rooms, Optional<Room> room) {
        if (room.isEmpty()) return -1;
        for (int i = 0; i < rooms.size(); i++) {
            if (rooms.get(i).id().equals(room.get().id())) return i;
        }
        return -1;
    }

    // What the player's own row shows (M4 plan D9): the lines of /mineqtt privacy, and for an operator the presence devices.
    private static ExplorerPage.You you(ServerPlayer player, MineqttRuntime runtime, boolean operator) {
        PlayerPrivacy privacy = runtime.privacy();
        PlayerEntry entry = privacy.entry(player.getUUID());
        boolean host = privacy.host(player.getUUID());
        List<String> presence = operator ? runtime.presence().devices().stream()
                .sorted(Comparator.comparing((PlayerEntry device) -> device.describe().toLowerCase(Locale.ROOT)))
                .map(device -> device.describe() + ": " + (runtime.server().getPlayerList().getPlayer(device.uuid()) != null ? "online" : "offline"))
                .toList() : List.of();
        return ExplorerPage.You.of(entry.consent(), privacy.policy().shared(entry.consent().effective(host)), privacy.policy().offered(), host,
                PrivacyCommands.report(runtime, player), presence);
    }

    // A device as the tab lists it.
    private static ExplorerPage.Row row(DeviceRecord record, DeviceRuntime devices, boolean connected, GlobalPos viewer, int room) {
        DeviceType type = DeviceTypes.get(record.type());
        Optional<DeviceRuntime.Fault> fault = devices.fault(record.pos());
        boolean attached = devices.isAttached(record.id());
        ConsoleView.State state = ConsoleView.state(fault.isPresent(), type != null, attached, connected);
        String detail = switch (state) {
            case FAULTED -> fault.get().text();
            case ONLINE, NO_BROKER -> devices.rawProblem(record.id()).orElse("");
            case UNLOADED, UNKNOWN_TYPE -> "";
        };
        boolean near = viewer.dimension().equals(record.pos().dimension());
        BlockPos from = viewer.pos();
        BlockPos to = record.pos().pos();
        return ExplorerPage.Row.of(record.id(), record.name(), record.slug(), type == null ? ConsoleView.typeName(record.type()) : type.model(),
                availability(state, type == null ? null : type.unloadedPolicy()), detail, values(type, record.lastState()),
                ConsoleView.dimensionName(record.pos().dimension().identifier()), near ? distance(from, to) : -1,
                near ? bearing(to.getX() - from.getX(), to.getZ() - from.getZ()) : ExplorerPage.Bearing.HERE, type != null && type.use().isPresent(), room);
    }

    // A group as an operator's tab lists it: its member type's model, its state's values, and how many members it has.
    private static ExplorerPage.Row groupRow(MineqttRuntime runtime, DeviceGroup group, boolean connected, int room) {
        DeviceType type = group.memberType() == null ? null : DeviceTypes.get(group.memberType());
        String model = type != null ? type.model() : group.memberType() != null ? ConsoleView.typeName(group.memberType()) : "";
        return ExplorerPage.Row.group(group.id(), group.name(), group.slug(), model,
                connected ? ExplorerPage.Availability.ONLINE : ExplorerPage.Availability.NO_BROKER, values(type, group.state()), room, group.members().size());
    }

    /** Whose devices a player finds: an operator who may build every one, a player who may not build none (P2). */
    public static ExplorerPage.Scope scope(boolean op, boolean mayBuild) {
        if (!mayBuild) return ExplorerPage.Scope.NONE;
        return op ? ExplorerPage.Scope.ALL : ExplorerPage.Scope.OWN;
    }

    /**
     * Whether a player finds a device in a scope: in {@link ExplorerPage.Scope#OWN} only one they own and may configure;
     * one they may configure only because it is public or its claim trusts them is not theirs to find.
     *
     * @param configures asked only for a device the player owns
     */
    public static boolean lists(ExplorerPage.Scope scope, boolean owner, BooleanSupplier configures) {
        return switch (scope) {
            case ALL -> true;
            case OWN -> owner && configures.getAsBoolean();
            case NONE -> false;
        };
    }

    /**
     * The devices a search finds, in the tab's order (rooms by name, then the devices in no room; within each by name,
     * then slug): those the scope lists ({@link #lists}) whose name, slug, model or type id contains the query, ignoring
     * case, and, with a room filter, that are in a room of that name ({@link #inRoom}).
     *
     * @param owns       whether the player owns a device
     * @param configures whether the player may configure the device with this id ({@code DeviceRuntime.configurer})
     * @param room       the room filter, a room's name; {@code ""} for every room
     * @param roomOf     the room a device is in ({@code RoomIndex.roomOf})
     */
    public static List<DeviceRecord> matching(Collection<DeviceRecord> records, ExplorerPage.Scope scope, Predicate<DeviceRecord> owns,
                                              Predicate<UUID> configures, String query, String room, Function<DeviceRecord, Optional<Room>> roomOf) {
        String search = normalise(query);
        List<Listed> found = new ArrayList<>();
        for (DeviceRecord record : records) {
            if (!lists(scope, owns.test(record), () -> configures.test(record.id())) || !matches(record, search)) continue;
            Optional<Room> in = roomOf.apply(record);
            if (inRoom(in, room)) found.add(Listed.of(record, in));
        }
        found.sort(LISTED_ORDER);
        return found.stream().map(Listed::record).toList();
    }

    /**
     * The groups a search finds: every group an operator who may build finds ({@link ExplorerPage.Scope#ALL}) whose name or
     * slug contains the query, with a room filter those whose members are all in a room of that name; none in any other
     * scope.
     *
     * @param roomOf the room all of a group's members are in ({@code RoomIndex.commonRoom})
     */
    public static List<DeviceGroup> matchingGroups(Collection<DeviceGroup> groups, ExplorerPage.Scope scope, String query, String room,
                                                   Function<DeviceGroup, Optional<Room>> roomOf) {
        if (scope != ExplorerPage.Scope.ALL) return List.of();
        String search = normalise(query);
        return groups.stream().filter(group -> search.isEmpty() || group.name().toLowerCase(Locale.ROOT).contains(search) || group.slug().contains(search))
                .filter(group -> inRoom(roomOf.apply(group), room)).toList();
    }

    /**
     * Whether something in {@code room} passes the room filter {@code filter}: every room and none pass an empty filter;
     * otherwise only a room whose name, as the page carries it, equals the filter ignoring case. Two rooms of one name (of
     * two owners, for an operator) pass together.
     */
    public static boolean inRoom(Optional<Room> room, String filter) {
        if (filter.isEmpty()) return true;
        return room.isPresent() && RoomRules.key(ExplorerPage.cut(room.get().name(), ExplorerPage.MAX_ROOM_LENGTH)).equals(RoomRules.key(filter));
    }

    /**
     * The rooms the devices a player finds are in, in the tab's order, each with how many of those devices it holds; at
     * most {@value ExplorerPage#MAX_ROOMS}, the rest left out of the filter (their devices show without a room header).
     *
     * @param found the devices the player finds, whatever the search
     */
    public static List<ExplorerPage.RoomRef> rooms(Collection<DeviceRecord> found, Function<DeviceRecord, Optional<Room>> roomOf) {
        Map<Room, Integer> counts = new LinkedHashMap<>();
        for (DeviceRecord record : found) roomOf.apply(record).ifPresent(room -> counts.merge(room, 1, Integer::sum));
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey(ROOM_ORDER)).limit(ExplorerPage.MAX_ROOMS)
                .map(entry -> ExplorerPage.RoomRef.of(entry.getKey().id(), entry.getKey().name(), entry.getValue())).toList();
    }

    // Whether a device's name, slug, model or type id contains the normalised query.
    private static boolean matches(DeviceRecord record, String search) {
        if (search.isEmpty()) return true;
        DeviceType type = DeviceTypes.get(record.type());
        return record.name().toLowerCase(Locale.ROOT).contains(search) || record.slug().contains(search)
                || (type != null && type.model().toLowerCase(Locale.ROOT).contains(search))
                || record.type().toString().contains(search);
    }

    /** A query as it is searched: without control characters and formatting codes, trimmed, lower case, at most 64 characters. */
    public static String normalise(String query) {
        return Coerce.sanitize(query.strip(), ExplorerPage.MAX_QUERY_LENGTH).strip().toLowerCase(Locale.ROOT);
    }

    /** The page shown for the one asked for: from 0 to the last page of {@code total} devices, which is 0 without any. */
    public static int clampPage(int page, int total) {
        int last = total == 0 ? 0 : (total - 1) / ExplorerPage.PAGE_SIZE;
        return Math.max(0, Math.min(page, last));
    }

    /** How a device is available: an unloaded one by its type's policy, queueing commands or refusing them. */
    public static ExplorerPage.Availability availability(ConsoleView.State state, @Nullable UnloadedPolicy policy) {
        return switch (state) {
            case ONLINE -> ExplorerPage.Availability.ONLINE;
            case NO_BROKER -> ExplorerPage.Availability.NO_BROKER;
            case FAULTED -> ExplorerPage.Availability.FAULTED;
            case UNKNOWN_TYPE -> ExplorerPage.Availability.UNKNOWN_TYPE;
            case UNLOADED -> policy == UnloadedPolicy.QUEUE ? ExplorerPage.Availability.QUEUED : ExplorerPage.Availability.UNAVAILABLE;
        };
    }

    /**
     * Up to {@value ExplorerPage#MAX_VALUES} values of a device's state as the tab shows them: its out-ports'
     * ({@link DeviceType#outputs}) in declaration order, without its settings and its location, a lamp's light as its
     * state, each on one line, without
     * control characters, formatting codes and bidirectional controls and cut to {@value ExplorerPage#MAX_VALUE_LENGTH} code
     * points; empty ones are left out. None for a device of an unknown type or without a recorded state.
     */
    public static List<String> values(@Nullable DeviceType type, @Nullable JsonObject state) {
        if (type == null || state == null) return List.of();
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> output : type.outputs(state).entrySet()) {
            if (values.size() == ExplorerPage.MAX_VALUES) break;
            if (output.getKey().equals(CommonFields.LOCATION.key()) || type.setting(output.getKey()).isPresent()) continue;
            String spaced = LINE_BREAKS.matcher(text(output.getValue())).replaceAll(" ");
            String text = Coerce.sanitize(BIDI_CONTROLS.matcher(spaced).replaceAll(""), ExplorerPage.MAX_VALUE_LENGTH).strip();
            if (!text.isEmpty()) values.add(text);
        }
        return values;
    }

    // A value as text: a string as it is, a boolean as on or off, a number without a trailing zero, an object with a state
    // (a lamp's light) as that state, anything else as JSON.
    private static String text(JsonElement value) {
        if (value.isJsonObject() && value.getAsJsonObject().get("state") instanceof JsonPrimitive state) return text(state);
        if (!value.isJsonPrimitive()) return value.toString();
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean() ? "on" : "off";
        if (primitive.isNumber()) {
            try {
                return new BigDecimal(primitive.getAsString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return primitive.getAsString();
            }
        }
        return primitive.getAsString();
    }

    /** How many blocks lie between two positions, rounded; at most {@link Integer#MAX_VALUE}. */
    public static int distance(BlockPos from, BlockPos to) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt(from.distSqr(to))));
    }

    /**
     * Where a device lies by the compass, from the offset to it: north is towards -z and east towards +x, as the debug
     * screen says; {@link ExplorerPage.Bearing#HERE} straight above or below the player.
     */
    public static ExplorerPage.Bearing bearing(int dx, int dz) {
        if (dx == 0 && dz == 0) return ExplorerPage.Bearing.HERE;
        // Clockwise from north, in eighths of a turn centred on each point.
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int sector = (int) Math.floorMod(Math.round(degrees / 45.0), 8L);
        return ExplorerPage.Bearing.values()[1 + sector];
    }
}

package art.rehra.mineqtt.items;

import art.rehra.mineqtt.compat.Particles;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.network.MineqttNetworking.RoomNamePayload;
import art.rehra.mineqtt.network.MineqttNetworking.RoomPromptPayload;
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.rooms.RoomEdits;
import art.rehra.mineqtt.rooms.RoomOffer;
import art.rehra.mineqtt.rooms.RoomRules;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.twin.core.AreaNames;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Configurator's Room mode (M4 plan D11): a click on a block within the Configurator's reach sets corner A, a second
 * the opposite corner B, and once the box passes the room rules a prompt asks for the room's name ({@code RoomNameScreen});
 * a sneak-click within reach of the hand inside a room of the player's (any room, for an operator) picks it, and the prompt renames or removes it.
 * A click into the air cancels. Making, renaming and removing go through {@link RoomEdits}, as the room commands do.
 * <p>
 * Within reach of the hand an ordinary click goes to the block first, as with any item: a door opens, a chest shows its
 * contents, a lever flips, also while a corner A waits, and only a block that does nothing with the click becomes a
 * corner (vanilla then uses the Configurator, {@code CyberdeckItem#use}, which points at that block). Such a block becomes
 * a corner when clicked from beyond the hand's reach. A MineQTT block is always a corner, as every Configurator mode
 * acts on devices, and a sneak-click on any block is always the pick of a room.
 * <p>
 * While a player holds the Configurator in Room mode, every {@value #OUTLINE_INTERVAL_TICKS} ticks the server outlines
 * for them alone, in dust particles, the box they are choosing and the rooms within {@value #OUTLINE_RADIUS} blocks that
 * they own or that are for everyone (an operator sees every room): at most {@value #MAX_OUTLINED_ROOMS} rooms and
 * {@value #MAX_PARTICLES} particles a pass. No payload and no client renderer are involved. Corners and offers are kept
 * per player until they cancel, change the mode or leave. Server thread only.
 */
public final class ConfiguratorRoom {
    public static final int OUTLINE_INTERVAL_TICKS = 10;
    public static final int OUTLINE_RADIUS = 32;
    public static final int MAX_OUTLINED_ROOMS = 4;
    public static final int MAX_PARTICLES = 512;
    /** The outline colours: the box being chosen, the player's own rooms, rooms for everyone, other players' (operators only). */
    public static final int PENDING_COLOR = 0xFFFFFF;
    public static final int OWN_COLOR = 0x00FFFF;
    public static final int FOR_ALL_COLOR = 0x55FF55;
    public static final int OTHER_COLOR = 0x888888;
    // Points along an edge are at most this far apart, and never closer than the particle budget allows.
    private static final double MIN_SPACING = 0.5;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static int nextOffer;

    /** Corner A of the box a player is choosing. */
    private record Corner(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private record Session(@Nullable Corner corner, @Nullable RoomOffer offer) {
    }

    private ConfiguratorRoom() {
    }

    /**
     * A click in Room mode on a block within the Configurator's reach: a corner, or, sneaking, the pick of a room that holds
     * the block. It comes from a MineQTT block's click, a sneaking click on any other block, or the Configurator's use
     * that a block without a use of its own passed on ({@link #clickAtRange}).
     */
    public static void clickBlock(ServerPlayer player, BlockPos pos, boolean sneaking) {
        if (!DeviceAccess.mayBuild(player)) {
            tell(player, Component.translatable("mineqtt.configurator.room.game_mode"));
            return;
        }
        MineqttRuntime runtime = ConfiguratorTarget.runtime(player).orElse(null);
        if (runtime == null) {
            tell(player, Component.translatable("mineqtt.configurator.room.not_running"));
            return;
        }
        if (sneaking) {
            pick(player, runtime, pos);
        } else {
            corner(player, runtime, pos);
        }
    }

    /**
     * The Configurator's use in Room mode ({@code CyberdeckItem#use}): after a click on a block within the hand's reach
     * that did nothing with it, or a right-click beyond that reach. The block the player points at within the
     * Configurator's range is a corner; pointing at nothing cancels. A sneaking right-click steps the mode instead, as in
     * every mode, so a room is picked only within reach.
     */
    public static void clickAtRange(ServerPlayer player) {
        HitResult hit = player.pick(C2SGuard.CONFIGURATOR_RANGE, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            clickBlock(player, blockHit.getBlockPos(), false);
        } else {
            clickAir(player);
        }
    }

    /** A click into the air in Room mode: cancels the box being chosen or the open prompt, or says how the mode works. */
    public static void clickAir(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        tell(player, Component.translatable(session == null ? "mineqtt.configurator.room.hint" : "mineqtt.configurator.room.cancelled"));
    }

    // Corner A, or B: a box that passes the room rules is offered for a name; one that does not keeps A for another B.
    private static void corner(ServerPlayer player, MineqttRuntime runtime, BlockPos pos) {
        Session session = SESSIONS.get(player.getUUID());
        Corner corner = session == null ? null : session.corner();
        ResourceKey<Level> dimension = player.level().dimension();
        if (corner == null || !corner.dimension().equals(dimension)) {
            SESSIONS.put(player.getUUID(), new Session(new Corner(dimension, pos.immutable()), null));
            tell(player, Component.translatable("mineqtt.configurator.room.corner", pos.getX(), pos.getY(), pos.getZ()));
            return;
        }
        RoomEdits.Actor actor = RoomEdits.Actor.of(player);
        Optional<String> problem = RoomEdits.createProblem(runtime.registry(), actor, dimension, corner.pos(), pos, false);
        if (problem.isPresent()) {
            tell(player, Component.translatable("mineqtt.configurator.room.refused", problem.get()));
            return;
        }
        RoomOffer offer = RoomOffer.create(++nextOffer, dimension, corner.pos(), pos, Util.getMillis());
        SESSIONS.put(player.getUUID(), new Session(null, offer));
        MineqttNetworking.sendRoomPrompt(player, new RoomPromptPayload(offer.id(), offer.sizeX(), offer.sizeY(), offer.sizeZ(), "",
                suggestions(runtime.registry(), actor, areaSuggestions(player, runtime)), actor.roomsAdmin()));
    }

    // The smallest room holding the block that the player may change is offered to rename or remove.
    private static void pick(ServerPlayer player, MineqttRuntime runtime, BlockPos pos) {
        RoomEdits.Actor actor = RoomEdits.Actor.of(player);
        Optional<Room> room = runtime.registry().roomIndex().at(GlobalPos.of(player.level().dimension(), pos)).stream()
                .filter(candidate -> RoomRules.mayEdit(candidate, actor.id(), actor.op())).findFirst();
        if (room.isEmpty()) {
            tell(player, Component.translatable("mineqtt.configurator.room.none_here"));
            return;
        }
        RoomOffer offer = RoomOffer.edit(++nextOffer, room.get(), Util.getMillis());
        SESSIONS.put(player.getUUID(), new Session(null, offer));
        MineqttNetworking.sendRoomPrompt(player, new RoomPromptPayload(offer.id(), offer.sizeX(), offer.sizeY(), offer.sizeZ(), room.get().name(),
                suggestions(runtime.registry(), actor, areaSuggestions(player, runtime)), false));
    }

    /** {@link #suggestions(DeviceRegistry, RoomEdits.Actor, List)} without Home Assistant area names. */
    public static List<String> suggestions(DeviceRegistry registry, RoomEdits.Actor actor) {
        return suggestions(registry, actor, List.of());
    }

    /**
     * Names the prompt offers: the names of the rooms the player sees but does not own (rooms for everyone, and for an
     * operator every room), which they may reuse, as their own names are taken among their rooms, distinct ignoring case
     * and sorted; then the Home Assistant area names they may be shown ({@link #areaSuggestions}) that are valid room names
     * and name neither one of those rooms nor one of their own, as Home Assistant compares area names (M4 plan D11). At most
     * {@link RoomPromptPayload#MAX_SUGGESTIONS} in all, the area names kept.
     */
    public static List<String> suggestions(DeviceRegistry registry, RoomEdits.Actor actor, List<String> areas) {
        Map<String, String> names = new LinkedHashMap<>();
        registry.rooms().stream()
                .filter(room -> RoomRules.visibleTo(room, actor.id(), actor.op()) && !room.owner().equals(actor.id()))
                .map(Room::name).sorted(String.CASE_INSENSITIVE_ORDER).forEach(name -> names.putIfAbsent(RoomRules.key(name), name));
        List<Room> own = registry.rooms().stream().filter(room -> room.owner().equals(actor.id())).toList();
        own.forEach(room -> names.remove(RoomRules.key(room.name())));
        Set<String> taken = new HashSet<>();
        names.values().forEach(name -> taken.add(AreaNames.key(name)));
        own.forEach(room -> taken.add(AreaNames.key(room.name())));
        // Only an area name that is a valid room name can be taken as one.
        List<String> areaNames = areas.stream().map(RoomRules::name).filter(area -> RoomRules.nameProblem(area).isEmpty())
                .filter(area -> taken.add(AreaNames.key(area))).limit(AreaNames.MAX_SUGGESTIONS).toList();
        List<String> suggestions = new ArrayList<>(names.values().stream().limit(RoomPromptPayload.MAX_SUGGESTIONS - areaNames.size()).toList());
        suggestions.addAll(areaNames);
        return List.copyOf(suggestions);
    }

    /**
     * The Home Assistant area names a player's prompt may suggest (M4 plan D11): those of the catalog entries the twin's
     * read gate lets through, for a player who may bind devices to Home Assistant ({@code mineqtt:twin.bind} or an
     * operator; everyone on an integrated server), at most {@value AreaNames#MAX_SUGGESTIONS}; none for anyone else.
     */
    public static List<String> areaSuggestions(boolean mayBindTwin, List<String> readableAreas) {
        return mayBindTwin ? readableAreas.stream().limit(AreaNames.MAX_SUGGESTIONS).toList() : List.of();
    }

    private static List<String> areaSuggestions(ServerPlayer player, MineqttRuntime runtime) {
        boolean mayBind = AclContext.mayBindTwin(player.permissions(), runtime.server().isDedicatedServer());
        return areaSuggestions(mayBind, mayBind ? runtime.twin().readableAreas() : List.of());
    }

    /**
     * {@code RoomNameC2S}: the player answered the prompt. The {@link C2SGuard} lets it through only while it answers the
     * player's open offer, before it lapsed, they hold a Configurator in Room mode, are within its reach of the box, may
     * build there and, for a picked room, may change it. The offer is used up either way; the answer then goes through
     * {@link RoomEdits}, which judges the name, the rules and the build rights again.
     */
    public static void named(ServerPlayer player, RoomNamePayload payload) {
        MineqttRuntime runtime = ConfiguratorTarget.runtime(player).orElse(null);
        if (runtime == null) return;
        Session session = SESSIONS.get(player.getUUID());
        RoomOffer offer = session == null || session.offer() == null || !session.offer().answers(payload.offer(), Util.getMillis()) ? null : session.offer();
        RoomEdits.Actor actor = RoomEdits.Actor.of(player);
        Room room = offer == null || !offer.edits() ? null : runtime.registry().room(offer.room());
        if (!C2SGuard.requireOffer(player, payload, ConfiguratorMode.ROOM, offer != null, offer == null ? null : offer.dimension(),
                offer == null ? null : new AABB(offer.min().getX(), offer.min().getY(), offer.min().getZ(), offer.max().getX() + 1, offer.max().getY() + 1,
                        offer.max().getZ() + 1),
                () -> !offer.edits() || room == null || RoomRules.mayEdit(room, actor.id(), actor.op()))) {
            return;
        }
        SESSIONS.remove(player.getUUID());
        Optional<String> problem = offer.answerProblem(payload.op(), payload.forAll(), actor.roomsAdmin());
        if (problem.isPresent()) {
            tell(player, Component.translatable("mineqtt.configurator.room.refused", problem.get()));
            return;
        }
        if (offer.edits() && room == null) {
            tell(player, Component.translatable("mineqtt.configurator.room.gone"));
            return;
        }
        RoomEdits.Result result = switch (payload.op()) {
            case CREATE -> RoomEdits.create(runtime, actor, payload.name(), offer.dimension(), offer.min(), offer.max(), payload.forAll());
            case RENAME -> RoomEdits.rename(runtime, actor, room, payload.name());
            case REMOVE -> RoomEdits.remove(runtime, actor, room);
        };
        tell(player, Component.literal(result.message()));
    }

    /**
     * {@code SERVER_POST}: every {@value #OUTLINE_INTERVAL_TICKS} ticks, the outlines of each player who holds the
     * Configurator in Room mode and may build.
     */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % OUTLINE_INTERVAL_TICKS != 0) return;
        MineqttRuntime runtime = MineqttRuntime.current().filter(running -> running.server() == server).orElse(null);
        if (runtime == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || !DeviceAccess.mayBuild(player)) continue;
            if (CyberdeckDataUtil.mode(CyberdeckMenu.heldConfigurator(player)) != ConfiguratorMode.ROOM || !C2SGuard.holdsConfigurator(player)) continue;
            outline(player, runtime.registry());
        }
    }

    /** One outline box: its corners, inclusive, and its colour. */
    public record Box(BlockPos min, BlockPos max, int color) {
    }

    /**
     * The boxes a player's outline shows now: the box they are choosing (corner A to the block they point at, or A alone),
     * then the rooms around them that they own or that are for everyone, every room for an operator, nearest first.
     */
    private static List<Box> boxes(ServerPlayer player, DeviceRegistry registry) {
        List<Box> boxes = new ArrayList<>();
        Session session = SESSIONS.get(player.getUUID());
        Corner corner = session == null ? null : session.corner();
        if (corner != null && corner.dimension().equals(player.level().dimension())) {
            HitResult hit = player.pick(C2SGuard.CONFIGURATOR_RANGE, 1.0F, false);
            BlockPos other = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit.getBlockPos() : corner.pos();
            boxes.add(new Box(BlockPos.min(corner.pos(), other), BlockPos.max(corner.pos(), other), PENDING_COLOR));
        }
        RoomEdits.Actor actor = RoomEdits.Actor.of(player);
        registry.roomIndex().near(player.level().dimension(), player.blockPosition(), OUTLINE_RADIUS).stream()
                .filter(room -> RoomRules.visibleTo(room, actor.id(), actor.op()))
                .limit(MAX_OUTLINED_ROOMS)
                .forEach(room -> boxes.add(new Box(room.min(), room.max(), color(room, actor.id()))));
        return boxes;
    }

    /** A room's outline colour for a viewer: their own, for everyone, or another player's (which only operators see). */
    public static int color(Room room, UUID viewer) {
        if (room.owner().equals(viewer)) return OWN_COLOR;
        return room.forAll() ? FOR_ALL_COLOR : OTHER_COLOR;
    }

    private static void outline(ServerPlayer player, DeviceRegistry registry) {
        List<Box> boxes = boxes(player, registry);
        int budget = MAX_PARTICLES;
        ServerLevel level = (ServerLevel) player.level(); // a ServerPlayer's level is typed so from 1.21.6 on
        for (int i = 0; i < boxes.size() && budget > 0; i++) {
            Box box = boxes.get(i);
            List<double[]> points = edgePoints(box, budget / (boxes.size() - i));
            DustParticleOptions dust = Particles.dust(box.color(), 1.0F);
            for (double[] point : points) {
                Particles.sendTo(level, player, dust, true, false, point[0], point[1], point[2], 1, 0, 0, 0, 0);
            }
            budget -= points.size();
        }
    }

    /**
     * Points along the twelve edges of a box's outer faces, spaced evenly and at least {@value #MIN_SPACING} blocks apart,
     * at most {@code budget} of them; every corner of the box is one.
     */
    public static List<double[]> edgePoints(Box box, int budget) {
        List<double[]> points = new ArrayList<>();
        if (budget < 8) return points;
        double x0 = box.min().getX(), y0 = box.min().getY(), z0 = box.min().getZ();
        double x1 = box.max().getX() + 1, y1 = box.max().getY() + 1, z1 = box.max().getZ() + 1;
        double perimeter = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        // Each edge gets its segments, and the eight corners come once.
        double spacing = Math.max(MIN_SPACING, perimeter / Math.max(1, budget - 8));
        for (double[] corner : new double[][]{{x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1}}) {
            points.add(corner);
        }
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) edge(points, x0, y, z, x1, y, z, spacing);
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) edge(points, x, y0, z, x, y1, z, spacing);
        }
        for (double x : new double[]{x0, x1}) {
            for (double y : new double[]{y0, y1}) edge(points, x, y, z0, x, y, z1, spacing);
        }
        return points.size() <= budget ? points : points.subList(0, budget);
    }

    // The points strictly between an edge's two ends, at most spacing apart.
    private static void edge(List<double[]> points, double ax, double ay, double az, double bx, double by, double bz, double spacing) {
        double length = Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az);
        int segments = (int) Math.ceil(length / spacing);
        for (int i = 1; i < segments; i++) {
            double t = (double) i / segments;
            points.add(new double[]{ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t});
        }
    }

    /** The player changed the Configurator's mode or left: their corner and prompt are forgotten. */
    public static void forget(UUID player) {
        SESSIONS.remove(player);
    }

    /** The server stops. */
    public static void clear() {
        SESSIONS.clear();
    }

    private static void tell(ServerPlayer player, Component message) {
        ConfiguratorTarget.tell(player, message);
    }
}

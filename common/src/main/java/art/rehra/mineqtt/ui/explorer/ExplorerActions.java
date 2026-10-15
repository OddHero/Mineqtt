package art.rehra.mineqtt.ui.explorer;

import art.rehra.mineqtt.compat.Particles;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.network.MineqttNetworking.ExplorerActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.PrivacyChoicePayload;
import art.rehra.mineqtt.presence.PrivacyCommands;
import art.rehra.mineqtt.rooms.Room;
import art.rehra.mineqtt.rooms.RoomRules;
import art.rehra.mineqtt.rooms.RoomSwitch;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.console.ConsoleView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The actions of the Configurator's Devices tab (M4 plan D13) and its You row (D9), on the server. Each action reaches
 * only a device the player finds ({@link ExplorerDirectory#finds}), asked afresh, so it never reaches or reveals a device
 * the tab would not list:
 * <ul>
 *     <li><b>Use</b> runs the type's declared use ({@link DeviceType#use()}) from anywhere through
 *     {@link DeviceRuntime#explorerUse}, as a player's command: a loaded device where the level lets the player interact
 *     with it applies it, an unloaded one that queues commands queues it, an unloaded one that is unavailable refuses it
 *     with the reason.</li>
 *     <li><b>Locate</b> shows only the asking player a column of particles at the device for {@value #MARK_TICKS} ticks
 *     when it lies in their dimension within {@value #LOCATE_RANGE} blocks; otherwise the action bar says how far and in
 *     which direction, or in which dimension.</li>
 *     <li><b>Open</b> opens the device's screen within the Configurator's range ({@link C2SGuard#CONFIGURATOR_RANGE}), under
 *     the rules of opening it from afar; beyond, the action bar says how far away it is.</li>
 *     <li><b>All on</b> and <b>All off</b> of a room the player sees switch the devices of it they find
 *     ({@link RoomSwitch}, {@code DeviceRuntime#roomSwitch}), as {@code /mineqtt rooms switch} does.</li>
 * </ul>
 * The actions and privacy choices of one open explorer share its pace ({@link CyberdeckMenu#takeExplorerAction}). Server
 * thread only; the decisions are pure static functions checked by {@code verifySecurityGuards}.
 */
public final class ExplorerActions {
    /** The actions of one open explorer: a burst of {@value #ACTION_BURST}, then {@value #ACTIONS_PER_SECOND} per second. */
    public static final int ACTION_BURST = 4;
    public static final double ACTIONS_PER_SECOND = 2;
    /** Locate marks a device at most this many blocks away. */
    public static final int LOCATE_RANGE = 128;
    /** How long a mark shows, and how often its particles are sent again. */
    public static final int MARK_TICKS = 100;
    public static final int MARK_INTERVAL = 10;
    /** The mark's column: this many blocks high above the device, a particle every half block. */
    public static final int COLUMN_HEIGHT = 8;
    // At most one mark per player: a new locate moves it.
    private static final Map<UUID, Mark> MARKS = new HashMap<>();

    private ExplorerActions() {
    }

    /**
     * What an {@code ExplorerActionC2S} asks: of a device ({@link #USE}, {@link #LOCATE}, {@link #OPEN}), or of a room,
     * whose id it then names ({@link #ROOM_ON}, {@link #ROOM_OFF}: All on and All off, {@link RoomSwitch}).
     */
    public enum Kind {
        USE, LOCATE, OPEN, ROOM_ON, ROOM_OFF;

        /** Whether it names a room rather than a device. */
        public boolean room() {
            return this == ROOM_ON || this == ROOM_OFF;
        }
    }

    /** What a locate shows the player ({@link #located}). */
    public enum Located {
        /** A column of particles at the device, and how far it is. */
        MARKED,
        /** In their dimension but beyond {@value #LOCATE_RANGE} blocks: how far and in which direction. */
        FAR,
        /** In another dimension: which one. */
        ELSEWHERE
    }

    /** What an open does ({@link #opens}). */
    public enum Opens {
        OPEN,
        /** Not loaded, so it has no screen to open. */
        UNLOADED,
        /** In another dimension. */
        ELSEWHERE,
        /** Beyond the Configurator's range. */
        FAR,
        /** Within range, but the level does not let the player interact there (spawn protection, the world border). */
        PROTECTED
    }

    // A device marked for a player until a server tick.
    private record Mark(GlobalPos pos, int until) {
    }

    /** {@code ExplorerActionC2S}, after the guard found the player's explorer open. */
    public static void act(ServerPlayer player, CyberdeckMenu menu, ExplorerActionPayload payload) {
        if (!menu.takeExplorerAction()) {
            player.sendSystemMessage(Component.translatable("mineqtt.explorer.action.rate"), true);
            return;
        }
        Optional<MineqttRuntime> running = MineqttRuntime.current().filter(runtime -> runtime.server() == player.level().getServer());
        if (running.isEmpty()) return;
        DeviceRuntime devices = running.get().devices();
        if (ExplorerDirectory.scope(player) == ExplorerPage.Scope.NONE) {
            player.sendSystemMessage(Component.translatable("mineqtt.game_mode.uses_only"), true);
            return;
        }
        if (payload.kind().room()) {
            switchRoom(player, running.get(), payload.target(), payload.kind() == Kind.ROOM_ON);
            return;
        }
        DeviceRecord record = running.get().registry().record(payload.target());
        if (record == null || !ExplorerDirectory.finds(player, devices, record)) {
            player.sendSystemMessage(Component.translatable("mineqtt.explorer.action.unknown"), true);
            return;
        }
        switch (payload.kind()) {
            case USE -> use(player, devices, record);
            case LOCATE -> locate(player, record);
            case OPEN -> open(player, devices, record);
            case ROOM_ON, ROOM_OFF -> throw new IllegalStateException("A room action names a room");
        }
    }

    // All on or All off of a room the player sees (their own, one for everyone, every room for an operator): the devices
    // of it they find, through the path of /mineqtt rooms switch.
    private static void switchRoom(ServerPlayer player, MineqttRuntime runtime, UUID roomId, boolean on) {
        Room room = runtime.registry().room(roomId);
        if (room == null || !RoomRules.visibleTo(room, player.getUUID(), AclContext.isOp(player.permissions()))) {
            player.sendSystemMessage(Component.translatable("mineqtt.explorer.room.unknown"), true);
            return;
        }
        DeviceRuntime devices = runtime.devices();
        RoomSwitch.Result result = devices.roomSwitch(room, on, player, record -> ExplorerDirectory.finds(player, devices, record));
        player.sendSystemMessage(Component.literal(result.describe(room.name(), on)), true);
    }

    /**
     * {@code PrivacyChoiceC2S}, after the guard found the player's explorer open: the choice of the You row, through the
     * code path of {@code /mineqtt privacy share} ({@link PrivacyCommands#share}), whose lines the player reads in the
     * chat as after the command.
     */
    public static void choose(ServerPlayer player, CyberdeckMenu menu, PrivacyChoicePayload payload) {
        if (!menu.takeExplorerAction()) {
            player.sendSystemMessage(Component.translatable("mineqtt.explorer.action.rate"), true);
            return;
        }
        MineqttRuntime.current().filter(runtime -> runtime.server() == player.level().getServer()).ifPresent(runtime ->
                player.sendSystemMessage(Component.literal(String.join("\n", PrivacyCommands.share(runtime, player, payload.choice())))));
    }

    /**
     * Why the explorer's Use of a device the player finds is refused, or empty when it goes ahead (M4 plan D13).
     *
     * @param usable      whether the device's type declares a use
     * @param attached    whether the device is loaded
     * @param policy      what its type does with a command while unloaded
     * @param mayInteract whether the level lets the player interact at the loaded device; not asked of an unloaded one
     */
    public static Optional<String> useProblem(boolean usable, boolean attached, UnloadedPolicy policy, boolean mayInteract) {
        if (!usable) return Optional.of("mineqtt.explorer.use.none");
        if (attached) return mayInteract ? Optional.empty() : Optional.of("mineqtt.explorer.use.protected");
        return policy == UnloadedPolicy.QUEUE ? Optional.empty() : Optional.of("mineqtt.explorer.use.unavailable");
    }

    /** What a locate of a device the player finds shows them. */
    public static Located located(boolean sameDimension, int distance) {
        if (!sameDimension) return Located.ELSEWHERE;
        return distance <= LOCATE_RANGE ? Located.MARKED : Located.FAR;
    }

    /**
     * What an open of a device the player finds does: the rule of {@link C2SGuard#reaches} for a player who holds the
     * Configurator and may build (the explorer is open, so they do), with each refusal told apart.
     *
     * @param distanceSqr the squared distance from the player's eyes to the device's block, as the interaction range
     *                    measures it
     * @param mayInteract whether the level lets the player interact with the block; asked only within range
     */
    public static Opens opens(boolean attached, boolean sameLevel, double distanceSqr, BooleanSupplier mayInteract) {
        if (!attached) return Opens.UNLOADED;
        if (!sameLevel) return Opens.ELSEWHERE;
        if (distanceSqr >= C2SGuard.CONFIGURATOR_RANGE * C2SGuard.CONFIGURATOR_RANGE) return Opens.FAR;
        return mayInteract.getAsBoolean() ? Opens.OPEN : Opens.PROTECTED;
    }

    private static void use(ServerPlayer player, DeviceRuntime devices, DeviceRecord record) {
        DeviceType type = DeviceTypes.get(record.type());
        BlockEntity blockEntity = devices.attachedBlockEntity(record.id());
        boolean mayInteract = blockEntity == null || blockEntity.getLevel() == null || blockEntity.getLevel().mayInteract(player, blockEntity.getBlockPos());
        Optional<String> problem = useProblem(type != null && type.use().isPresent(), blockEntity != null,
                type == null ? UnloadedPolicy.UNAVAILABLE : type.unloadedPolicy(), mayInteract);
        if (problem.isPresent()) {
            player.sendSystemMessage(Component.translatable(problem.get(), record.name(), type == null ? ConsoleView.typeName(record.type()) : type.model()), true);
            return;
        }
        DeviceRuntime.Configured result = devices.explorerUse(record, player);
        String answer = switch (result.result()) {
            case APPLIED -> "mineqtt.explorer.use.applied";
            case QUEUED -> "mineqtt.explorer.use.queued";
            case UNCHANGED -> "mineqtt.explorer.use.unchanged";
            case REFUSED -> "mineqtt.device.refused";
        };
        player.sendSystemMessage(result.result() == DeviceRuntime.Configured.Result.REFUSED
                ? Component.translatable(answer, result.detail()) : Component.translatable(answer, record.name()), true);
    }

    private static void locate(ServerPlayer player, DeviceRecord record) {
        GlobalPos pos = record.pos();
        boolean sameDimension = player.level().dimension().equals(pos.dimension());
        int distance = sameDimension ? ExplorerDirectory.distance(player.blockPosition(), pos.pos()) : -1;
        Located located = located(sameDimension, distance);
        switch (located) {
            case MARKED -> {
                MARKS.put(player.getUUID(), new Mark(pos, player.level().getServer().getTickCount() + MARK_TICKS));
                mark(player, pos.pos());
                player.sendSystemMessage(Component.translatable("mineqtt.explorer.locate.marked", record.name(), distance, bearing(player, pos.pos())), true);
            }
            case FAR -> player.sendSystemMessage(Component.translatable("mineqtt.explorer.locate.far", record.name(), distance, bearing(player, pos.pos())), true);
            case ELSEWHERE -> player.sendSystemMessage(Component.translatable("mineqtt.explorer.locate.elsewhere", record.name(),
                    ConsoleView.dimensionName(pos.dimension().identifier())), true);
        }
    }

    private static Component bearing(ServerPlayer player, BlockPos to) {
        ExplorerPage.Bearing bearing = ExplorerDirectory.bearing(to.getX() - player.blockPosition().getX(), to.getZ() - player.blockPosition().getZ());
        return Component.translatable("mineqtt.explorer.bearing." + bearing.name().toLowerCase(Locale.ROOT));
    }

    private static void open(ServerPlayer player, DeviceRuntime devices, DeviceRecord record) {
        BlockEntity blockEntity = devices.attachedBlockEntity(record.id());
        ServerLevel level = blockEntity != null && blockEntity.getLevel() instanceof ServerLevel serverLevel ? serverLevel : null;
        boolean sameLevel = level != null && level == player.level();
        double distanceSqr = blockEntity == null ? Double.MAX_VALUE : new AABB(blockEntity.getBlockPos()).distanceToSqr(player.getEyePosition());
        Opens opens = opens(blockEntity instanceof DeviceBlockEntity, sameLevel, distanceSqr, () -> level.mayInteract(player, blockEntity.getBlockPos()));
        switch (opens) {
            case OPEN -> {
                DeviceBlockEntity device = (DeviceBlockEntity) blockEntity;
                if (DeviceAccess.check(player, device, MineqttPermission.USE)) {
                    device.openScreen(player);
                } else {
                    player.sendSystemMessage(Component.translatable("mineqtt.explorer.action.unknown"), true);
                }
            }
            case UNLOADED -> player.sendSystemMessage(Component.translatable("mineqtt.explorer.open.unloaded", record.name()), true);
            case ELSEWHERE -> player.sendSystemMessage(Component.translatable("mineqtt.explorer.locate.elsewhere", record.name(),
                    ConsoleView.dimensionName(record.pos().dimension().identifier())), true);
            case FAR -> player.sendSystemMessage(Component.translatable("mineqtt.explorer.open.far", record.name(), (int) Math.round(Math.sqrt(distanceSqr)),
                    (int) C2SGuard.CONFIGURATOR_RANGE), true);
            case PROTECTED -> player.sendSystemMessage(Component.translatable("mineqtt.configurator.protected", record.name()), true);
        }
    }

    // The column of particles at a device, sent to the player alone; the long-distance flag shows it beyond 32 blocks.
    private static void mark(ServerPlayer player, BlockPos pos) {
        ServerLevel level = (ServerLevel) player.level(); // a ServerPlayer's level is typed so from 1.21.6 on
        for (int step = 0; step <= COLUMN_HEIGHT * 2; step++) {
            Particles.sendTo(level, player, ParticleTypes.END_ROD, true, true, pos.getX() + 0.5, pos.getY() + 1.0 + step * 0.5, pos.getZ() + 0.5,
                    1, 0, 0, 0, 0);
        }
    }

    /**
     * Sends each mark's particles again every {@value #MARK_INTERVAL} ticks until it lapses; a mark ends early when its
     * player left or changed dimension.
     */
    public static void tick(MinecraftServer server) {
        if (MARKS.isEmpty()) return;
        int now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Mark>> it = MARKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Mark> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Mark mark = entry.getValue();
            if (player == null || now >= mark.until() || !player.level().dimension().equals(mark.pos().dimension())) {
                it.remove();
            } else if ((mark.until() - now) % MARK_INTERVAL == 0) {
                mark(player, mark.pos().pos());
            }
        }
    }

    /** Whether a player has a mark showing, for checks and tests. */
    public static boolean marked(UUID player, @Nullable GlobalPos pos) {
        Mark mark = MARKS.get(player);
        return mark != null && (pos == null || mark.pos().equals(pos));
    }

    /** The player left; their mark ends. */
    public static void forget(UUID player) {
        MARKS.remove(player);
    }

    /** The server stops. */
    public static void clear() {
        MARKS.clear();
    }
}

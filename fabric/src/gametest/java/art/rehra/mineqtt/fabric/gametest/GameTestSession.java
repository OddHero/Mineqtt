package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.Interaction;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.ha.BridgeDevice;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.ui.CyberdeckScreen;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * One client game test session: an in-process dedicated server that the real client joins as a non-op player, plus the
 * helpers the suites drive it with (place, open, click, read chat, screenshots).
 * <p>
 * With {@value #BROKER_ENV} set to a {@code tcp://} or {@code ws://} URI, the server's MineQTT connects to that broker
 * under the bridge id {@link #BRIDGE_ID}; when the session ends, the retained topics of that bridge, the world's Home
 * Assistant discovery topics and the Raw Link topics a suite named ({@link #clearAtClose}) are cleared.
 * Without it MQTT stays disabled, and the suites report what they cannot check as skipped.
 * <p>
 * {@value #SUITES_ENV} selects suites by the names they report; the others return at once, without a server.
 */
final class GameTestSession implements AutoCloseable {
    static final String BROKER_ENV = "MINEQTT_E2E_BROKER";
    static final String BRIDGE_ENV = "MINEQTT_GAMETEST_BRIDGE";
    static final String SUITES_ENV = "MINEQTT_GAMETEST_SUITES";
    static final String DEFAULT_BRIDGE_ID = "gametest";
    /**
     * The bridge id of this run: {@value #BRIDGE_ENV}, or {@value #DEFAULT_BRIDGE_ID}. Runs that share a broker each take
     * their own, and the suites fold it into the topics they publish to outside the bridge, so no run reads or clears
     * another run's topics.
     */
    static final String BRIDGE_ID = env(BRIDGE_ENV).orElse(DEFAULT_BRIDGE_ID);
    /** The suites {@value #SUITES_ENV} names; empty runs every suite. */
    private static final Set<String> SELECTED_SUITES = env(SUITES_ENV)
            .map(names -> Arrays.stream(names.split(",")).map(String::strip).filter(name -> !name.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new)))
            .map(Collections::unmodifiableSet)
            .orElse(Set.of());
    /** The surface blocks of the flat test world; players stand one block higher. */
    static final int GROUND_Y = -61;
    private static final int CONNECT_TIMEOUT_TICKS = 30 * 20;
    private static final int TAB_SWITCH_TICKS = 20;

    private final ClientGameTestContext context;
    private final SuiteReport report;
    private final String savedBroker;
    private final String savedBridgeId;
    private final List<String> savedRawAllow;
    private @Nullable BrokerConfig broker;
    private @Nullable TestDedicatedServerContext server;
    private @Nullable TestDedicatedServerConnection connection;
    private String playerName;
    private UUID playerId;
    // Raw Link topics outside the bridge that the suites' devices published retained levels to.
    private final Set<String> rawTopics = new LinkedHashSet<>();

    private GameTestSession(ClientGameTestContext context, SuiteReport report) {
        this.context = context;
        this.report = report;
        this.savedBroker = MineQTTConfig.broker;
        this.savedBridgeId = MineQTTConfig.bridgeId;
        this.savedRawAllow = MineQTTConfig.aclRawAllow;
    }

    /** Runs one suite in its own session and server; the client ends on the title screen, as the runner requires. */
    static void run(ClientGameTestContext context, String suite, Consumer<GameTestSession> body) {
        SuiteReport report = new SuiteReport(suite);
        if (!SELECTED_SUITES.isEmpty() && !SELECTED_SUITES.contains(suite)) {
            report.info("not run: " + SUITES_ENV + " selects " + SELECTED_SUITES);
            return;
        }
        try {
            try (GameTestSession session = new GameTestSession(context, report)) {
                session.start();
                body.accept(session);
            }
        } catch (RuntimeException | Error e) {
            report.abort(e);
            throw e;
        }
        report.finish();
    }

    private void start() {
        ChatLog.install();
        // The explorer keeps its topics for the whole client JVM; each suite sees only what reached its own world.
        context.runOnClient(mc -> CyberdeckScreen.clearDiscoveredTopics());
        // The in-process server shares this JVM's configuration, which the client loaded at start; MineQTT reads it
        // when the server starts.
        // MineQTT would replace an unusable bridge id with one per world, which the cleanup would then miss.
        if (!ConfigValidation.isValidBridgeId(BRIDGE_ID)) {
            throw new IllegalStateException(BRIDGE_ENV + " '" + BRIDGE_ID + "' must be 1-64 of a-z, 0-9, _ and -");
        }
        ConfigValidation.bridgeNamespaceError(BRIDGE_ID, MineQTTConfig.baseTopic).ifPresent(error -> {
            throw new IllegalStateException(BRIDGE_ENV + ": " + error);
        });
        MineQTTConfig.broker = env(BROKER_ENV).orElse("");
        MineQTTConfig.bridgeId = BRIDGE_ID;
        try {
            broker = MineQTTConfig.brokerConfig("mineqtt_" + BRIDGE_ID).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(BROKER_ENV + " is not a valid broker URI: " + e.getMessage(), e);
        }
        if (broker != null && broker.tls()) {
            throw new IllegalStateException(BROKER_ENV + " must be a tcp:// or ws:// listener, the test sets no CA file");
        }
        report.info(broker == null ? "MQTT disabled (" + BROKER_ENV + " is not set)" : "broker " + broker.redacted());

        server = context.worldBuilder().createServer();
        connection = server.connect();
        connection.waitForChunksRender();
        playerName = onClient(mc -> mc.player.getGameProfile().name());
        playerId = onClient(mc -> mc.player.getUUID());
        if (broker != null) {
            server.waitFor(s -> MineqttRuntime.of(s).mqtt().state().isConnected(), CONNECT_TIMEOUT_TICKS);
        }
        report.check(!onServer(s -> AclContext.of(player(s)).op()), "the joining player " + playerName + " is not an operator");
    }

    @Override
    public void close() {
        List<String> discoveryTopics = List.of();
        try {
            // Read while the server runs: each fresh world publishes its discovery under its own world UUID.
            if (broker != null && server != null) discoveryTopics = onRuntime(GameTestSession::discoveryTopics);
        } finally {
            stop();
        }
        if (broker != null && server != null) {
            // After the server stopped, so its final offline states are among the cleared topics.
            try (BrokerProbe probe = BrokerProbe.connect(broker)) {
                Set<String> cleared = probe.clearRetained(List.of(MineQTTConfig.baseTopic + "/" + BRIDGE_ID + "/#"));
                report.info("cleared " + cleared.size() + " retained topics of bridge " + BRIDGE_ID);
                cleared = probe.clearRetained(discoveryTopics);
                report.info("cleared " + cleared.size() + " retained Home Assistant discovery topics of the test world");
                if (!rawTopics.isEmpty()) {
                    cleared = probe.clearRetained(rawTopics);
                    report.info("cleared " + cleared.size() + " retained Raw Link levels of the suite");
                }
            }
        }
    }

    /**
     * A Raw Link topic a device of the suite publishes retained levels to (wireless redstone, M2 plan D2), cleared when the
     * session ends, so no later run or other client finds the level.
     */
    void clearAtClose(String topic) {
        rawTopics.add(topic);
    }

    private void stop() {
        try {
            if (connection != null) connection.close();
        } finally {
            try {
                if (server != null) server.close();
            } finally {
                MineQTTConfig.broker = savedBroker;
                MineQTTConfig.bridgeId = savedBridgeId;
                MineQTTConfig.aclRawAllow = savedRawAllow;
            }
        }
    }

    /** An environment variable, stripped; empty when it is unset or blank. */
    private static Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).map(String::strip).filter(value -> !value.isEmpty());
    }

    /** Every discovery topic this world may have published: the bridge's and one per device and group. */
    private static List<String> discoveryTopics(MineqttRuntime runtime) {
        DeviceRegistry registry = runtime.registry();
        List<String> objectIds = new ArrayList<>();
        objectIds.add(BridgeDevice.objectId(registry.worldUuid()));
        registry.records().forEach(record -> objectIds.add(record.objectId()));
        registry.groups().forEach(group -> objectIds.add(group.objectId()));
        return objectIds.stream().map(objectId -> TopicScheme.discoveryConfig(MineQTTConfig.discoveryPrefix, objectId)).toList();
    }

    // ===== State =====

    ClientGameTestContext context() {
        return context;
    }

    /** The test broker, or empty when MQTT is disabled for this run. */
    Optional<BrokerConfig> broker() {
        return Optional.ofNullable(broker);
    }

    String playerName() {
        return playerName;
    }

    UUID playerId() {
        return playerId;
    }

    void check(boolean condition, String what) {
        report.check(condition, what);
    }

    void skip(String what, String reason) {
        report.skip(what, reason);
    }

    void info(String text) {
        report.info(text);
    }

    /** Runs on the server thread and hands the result to the test thread. */
    <T> T onServer(Function<MinecraftServer, T> function) {
        return server.computeOnServer(function::apply);
    }

    <T> T onRuntime(Function<MineqttRuntime, T> function) {
        return onServer(s -> function.apply(MineqttRuntime.of(s)));
    }

    /** Reads the device at {@code pos} on the server thread; {@code null} when there is no device there. */
    <T> @Nullable T device(BlockPos pos, Function<DeviceLink, T> read) {
        return onServer(s -> {
            BlockEntity blockEntity = s.overworld().getBlockEntity(pos);
            return blockEntity instanceof DeviceHost host ? read.apply(host.deviceLink()) : null;
        });
    }

    /** Runs on the client thread and hands the result to the test thread. */
    <T> T onClient(Function<Minecraft, T> function) {
        return context.computeOnClient(function::apply);
    }

    /** The joined player on the server side; call it on the server thread. */
    ServerPlayer player(MinecraftServer minecraftServer) {
        return minecraftServer.getPlayerList().getPlayer(playerId);
    }

    // ===== Commands and chat =====

    /** Runs a command as the server console; the player stays a non-op. */
    void consoleCommand(String command) {
        server.runCommand(command);
    }

    /** Sends a command as the player, as if typed into the chat. */
    void playerCommand(String command) {
        context.runOnClient(mc -> mc.player.connection.sendCommand(command));
        connection.waitForServerboundPackets();
    }

    /** The first message after {@code mark} (see {@link ChatLog#mark()}) that matches, or empty after the timeout. */
    Optional<ChatLog.Line> awaitChat(int mark, Predicate<ChatLog.Line> matches, int timeoutTicks) {
        awaitClient(mc -> ChatLog.since(mark).stream().anyMatch(matches), timeoutTicks);
        return ChatLog.since(mark).stream().filter(matches).findFirst();
    }

    // ===== Waiting =====

    /** Waits until the condition holds on the client thread; returns false after the timeout instead of failing. */
    boolean awaitClient(Predicate<Minecraft> condition, int timeoutTicks) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            if (onClient(condition::test)) return true;
            context.waitTick();
        }
        return onClient(condition::test);
    }

    /**
     * Waits until the condition holds, asked on the test thread once a tick, so it may read either side through
     * {@link #onServer} and {@link #onClient}; returns false after the timeout instead of failing.
     */
    boolean await(BooleanSupplier condition, int timeoutTicks) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            if (condition.getAsBoolean()) return true;
            context.waitTick();
        }
        return condition.getAsBoolean();
    }

    /** Waits until the condition holds on the server thread; fails the suite after the default timeout. */
    void awaitServer(Predicate<MinecraftServer> condition) {
        server.waitFor(condition);
    }

    // ===== Player and world =====

    void teleport(BlockPos feet) {
        consoleCommand("tp " + playerName + " " + (feet.getX() + 0.5) + " " + feet.getY() + " " + (feet.getZ() + 0.5) + " 0 0");
        context.waitFor(mc -> mc.player.blockPosition().equals(feet));
    }

    /** Replaces the item in the player's main hand and waits until the client has it. */
    void hold(Item item) {
        consoleCommand("item replace entity " + playerName + " weapon.mainhand with " + BuiltInRegistries.ITEM.getKey(item));
        context.waitFor(mc -> mc.player.getMainHandItem().is(item));
    }

    /** Aims at the centre of one face of the full block at {@code pos} and waits until the crosshair is on that face. */
    void aim(BlockPos pos, Direction face) {
        context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos).relative(face, 0.5)));
        context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos) && hit.getDirection() == face);
    }

    /** Aims at the middle of the block at {@code pos}, whatever its shape, and waits until the crosshair is on it. */
    void aim(BlockPos pos) {
        context.runOnClient(mc -> {
            VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
            Vec3 middle = shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(Vec3.atLowerCornerOf(pos));
            mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, middle);
        });
        context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos));
    }

    /**
     * Aims at the middle of the block at {@code pos} beyond the player's reach, where the client's crosshair finds no
     * block, and waits until the server's view of the player points at it within the Configurator's range.
     */
    void aimFar(BlockPos pos) {
        context.runOnClient(mc -> {
            VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
            Vec3 middle = shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(Vec3.atLowerCornerOf(pos));
            mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, middle);
        });
        server.waitFor(s -> player(s).pick(C2SGuard.CONFIGURATOR_RANGE, 1.0F, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos));
    }

    /** Looks straight up into the open sky, so the use key uses the held item instead of a block. */
    void aimAtSky() {
        context.getInput().lookAt(0, -90);
        context.waitFor(mc -> mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS);
    }

    void pressUse() {
        context.getInput().pressKey(options -> options.keyUse);
    }

    /** Gives the player {@code block} and places it onto the ground block {@code ground}; see {@link #placeHeld}. */
    BlockPos place(Block block, BlockPos ground) {
        hold(block.asItem());
        return placeHeld(block, ground);
    }

    /** Places the held {@code block} onto the ground block {@code ground} with the use key, and waits until both sides have it. */
    BlockPos placeHeld(Block block, BlockPos ground) {
        BlockPos pos = ground.above();
        aim(ground, Direction.UP);
        pressUse();
        server.waitFor(s -> s.overworld().getBlockState(pos).is(block));
        context.waitFor(mc -> mc.level.getBlockState(pos).is(block));
        return pos;
    }

    // ===== Screens =====

    /**
     * Opens the screen of the block at {@code pos} with the gesture that opens it for what the player holds
     * ({@link Interaction#decide}): the use key, or sneak + use where a plain right-click does something else (the
     * lamp toggles, a renamed paper fills a channel). A block in hand would be placed by a sneaking player, so the main
     * hand is emptied first then.
     */
    TabbedMqttScreen openBlock(BlockPos pos) {
        aim(pos);
        if (outcome(pos, false) == Interaction.Outcome.SCREEN) {
            pressUse();
        } else {
            if (outcome(pos, true) != Interaction.Outcome.SCREEN) hold(Items.AIR);
            sneakUse(pos);
        }
        context.waitForScreen(TabbedMqttScreen.class);
        return onClient(GameTestSession::openScreen);
    }

    /** What a right-click on the block at {@code pos} with the held item does, as the mod decides it. */
    Interaction.Outcome outcome(BlockPos pos, boolean sneaking) {
        return onClient(mc -> Interaction.decide(sneaking, Interaction.Held.of(mc.player, InteractionHand.MAIN_HAND),
                mc.level.getBlockEntity(pos) instanceof DeviceHost host ? host.deviceType() : null));
    }

    /**
     * Presses the use key while sneaking: the shift key is held until the server sees the player sneak, and released
     * after the click.
     */
    void sneakUse() {
        sneakUse(null);
    }

    /**
     * {@link #sneakUse()} on the block at {@code pos}, aimed at again once the player sneaks: sneaking lowers the eyes,
     * which can take the crosshair off a small block (the Wall Switch's plate) and onto the wall behind it.
     */
    void sneakUse(@Nullable BlockPos pos) {
        context.getInput().holdKey(options -> options.keyShift);
        try {
            server.waitFor(s -> player(s).isShiftKeyDown());
            if (pos != null) aim(pos);
            pressUse();
        } finally {
            context.getInput().releaseKey(options -> options.keyShift);
        }
        server.waitFor(s -> !player(s).isShiftKeyDown());
    }

    /** Uses the held item into the sky and waits for its screen. */
    TabbedMqttScreen useHeldItem() {
        aimAtSky();
        pressUse();
        context.waitForScreen(TabbedMqttScreen.class);
        return onClient(GameTestSession::openScreen);
    }

    /** The tab ids of the open screen, in tab-bar order. */
    List<String> tabIds() {
        return onClient(mc -> openScreen(mc).getMenu().tabs.stream().map(MqttTab::id).toList());
    }

    /** Clicks a tab of the open screen with the mouse; returns whether it became the active tab. */
    boolean clickTab(String tabId) {
        double[] cursor = onClient(mc -> {
            TabbedMqttScreen screen = openScreen(mc);
            int index = screen.getMenu().tabs.stream().map(MqttTab::id).toList().indexOf(tabId);
            if (index < 0) throw new AssertionError("No tab " + tabId + " on " + screen.getTitle().getString());
            // The tab bar sits on top of the panel, whose height depends on the menu (with or without the inventory).
            int tabW = TabbedMqttScreen.tabWidth(screen.getMenu().tabs.size());
            double guiX = screen.getLeftPos() + index * (tabW + TabbedMqttScreen.TAB_SPACING) + tabW / 2.0;
            double guiY = screen.getTopPos() - TabbedMqttScreen.TAB_H + TabbedMqttScreen.TAB_OVERLAP + TabbedMqttScreen.TAB_H / 2.0;
            Window window = mc.getWindow();
            return new double[]{guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
                    guiY * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        return awaitClient(mc -> tabId.equals(openScreen(mc).getMenu().getActiveTabId()), TAB_SWITCH_TICKS);
    }

    /**
     * Clicks the first widget of the open screen, a MineQTT screen or any other (the Link mode picker), that matches with
     * the left mouse button, as a player would: at its middle, so a text field gets the focus and a button is pressed only
     * when it is active.
     */
    void clickWidget(Predicate<AbstractWidget> which, String what) {
        double[] cursor = onClient(mc -> {
            Screen screen = mc.gui.screen();
            if (screen == null) throw new AssertionError("No screen is open to click " + what + " on");
            AbstractWidget widget = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .filter(w -> w.visible && which.test(w)).findFirst()
                    .orElseThrow(() -> new AssertionError("No widget " + what + " on " + screen.getTitle().getString()));
            Window window = mc.getWindow();
            return new double[]{(widget.getX() + widget.getWidth() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                    (widget.getY() + widget.getHeight() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    /**
     * Turns the mouse wheel by {@code notches} (positive scrolls up) with the cursor at the middle of the first visible
     * widget of the open screen that matches, as a player scrolls a list next to its buttons.
     */
    void scrollAt(Predicate<AbstractWidget> which, String what, double notches) {
        double[] cursor = onClient(mc -> {
            Screen screen = mc.gui.screen();
            if (screen == null) throw new AssertionError("No screen is open to scroll at " + what);
            AbstractWidget widget = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .filter(w -> w.visible && which.test(w)).findFirst()
                    .orElseThrow(() -> new AssertionError("No widget " + what + " on " + screen.getTitle().getString()));
            Window window = mc.getWindow();
            return new double[]{(widget.getX() + widget.getWidth() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth(),
                    (widget.getY() + widget.getHeight() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.getInput().scroll(notches);
        context.waitTick();
    }

    /**
     * Clicks into the first text field of the open screen that matches, erases its text with End and Backspace (Ctrl+A
     * does not reach a field from a client test, M2-8) and types {@code text}; checks the field took it.
     */
    void typeInto(Predicate<AbstractWidget> which, String what, String text) {
        clickWidget(which, what);
        context.getInput().pressKey(InputConstants.KEY_END);
        for (int i = fieldText(which).length(); i > 0; i--) {
            context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        }
        context.getInput().typeChars(text);
        context.waitTick();
        check(text.equals(fieldText(which)), what + " takes the typed text " + text + " (" + fieldText(which) + ")");
    }

    /** The text of the first text field of the open screen that matches. */
    String fieldText(Predicate<AbstractWidget> which) {
        return onClient(mc -> openScreen(mc).children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(which).findFirst().map(EditBox::getValue).orElseThrow(() -> new AssertionError("No such text field")));
    }

    /**
     * Drags the first slider of the open screen that matches with the real mouse: presses at the first of the
     * {@code fractions} of its track, moves to each further one a tick apart, and releases at the last; one fraction is
     * a click. A vanilla slider's track runs from 4 px inside its left edge to 4 px inside its right one.
     *
     * @param step run on the test thread after each move, e.g. to watch the server follow
     */
    void dragSlider(Predicate<AbstractWidget> which, String what, List<Double> fractions, Runnable step) {
        double[] track = sliderTrack(which, what);
        context.getInput().setCursorPos(track[0] + fractions.getFirst() * track[1], track[2]);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
        step.run();
        for (double fraction : fractions.subList(1, fractions.size())) {
            context.getInput().setCursorPos(track[0] + fraction * track[1], track[2]);
            context.waitTick();
            step.run();
        }
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    /**
     * Drags the first slider of the open screen that matches through the {@code fractions} of its track within one
     * tick, as a fast mouse at a high frame rate does: pressed at the first, it moves to each further one in a frame of
     * its own, which the mouse handler passes on as a drag, and is released at the last.
     */
    void flingSlider(Predicate<AbstractWidget> which, String what, List<Double> fractions) {
        double[] track = sliderTrack(which, what);
        context.getInput().setCursorPos(track[0] + fractions.getFirst() * track[1], track[2]);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        for (double fraction : fractions.subList(1, fractions.size())) {
            context.getInput().setCursorPos(track[0] + fraction * track[1], track[2]);
            // What the client does with the mouse's movement once a frame.
            context.runOnClient(mc -> mc.mouseHandler.handleAccumulatedMovement());
        }
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    // The window coordinates of a slider's track: its left end, its length and its middle's height.
    private double[] sliderTrack(Predicate<AbstractWidget> which, String what) {
        return onClient(mc -> {
            TabbedMqttScreen screen = openScreen(mc);
            AbstractWidget slider = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .filter(w -> w.visible && which.test(w)).findFirst()
                    .orElseThrow(() -> new AssertionError("No slider " + what + " on " + screen.getTitle().getString()));
            Window window = mc.getWindow();
            double scaleX = window.getScreenWidth() / (double) window.getGuiScaledWidth();
            double scaleY = window.getScreenHeight() / (double) window.getGuiScaledHeight();
            return new double[]{(slider.getX() + 4) * scaleX, (slider.getWidth() - 8) * scaleX, (slider.getY() + slider.getHeight() / 2.0) * scaleY};
        });
    }

    /** The device status line the server sent with the open screen, or later while it was open. */
    @Nullable String screenStatus() {
        return onClient(mc -> openScreen(mc).getMenu().deviceStatus());
    }

    /** Why the open device's Raw Link does nothing, as the server last sent it; null while it works. */
    @Nullable String screenRawProblem() {
        return onClient(mc -> openScreen(mc).getMenu().rawProblem());
    }

    /** The device view the server last sent the open screen: with its open data (M2-7), then on each change (M2-6). */
    Optional<DeviceView> screenView() {
        return onClient(mc -> openScreen(mc).getMenu().deviceView());
    }

    /** The note the open screen's tab shows at the right of its title row, such as "Read only", as text. */
    Optional<String> titleNote() {
        return onClient(mc -> openScreen(mc).titleNote().map(Component::getString));
    }

    /**
     * Waits until the open screen's title row shows {@code note} at its right, or no note when it is empty: a tab takes a
     * new view when it draws it.
     */
    boolean awaitTitleNote(Optional<String> note, int timeoutTicks) {
        return awaitClient(mc -> openScreen(mc).titleNote().map(Component::getString).equals(note), timeoutTicks);
    }

    /**
     * Sends a payload from the client, as a screen does, and waits until the server received it; its handler then runs
     * on the server thread.
     */
    void send(CustomPacketPayload payload) {
        context.runOnClient(mc -> NetworkManager.sendToServer(payload));
        connection.waitForServerboundPackets();
    }

    /** Presses the button labelled {@code label} on the open screen. */
    void clickButton(String label) {
        context.clickScreenButton(label);
    }

    /** Closes the open screen with the Escape key, which also closes the menu on the server. */
    void closeScreen() {
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(null);
        server.waitFor(s -> player(s).containerMenu == player(s).inventoryMenu);
    }

    /**
     * Takes a screenshot into {@code screenshots/} of the run directory, with the toasts dismissed and the mouse parked in
     * the corner, so neither covers the screen.
     */
    Path screenshot(String name) {
        context.runOnClient(mc -> mc.gui.toastManager().clear());
        context.getInput().setCursorPos(0, 0);
        context.waitTick();
        return context.takeScreenshot(name);
    }

    /** The open MineQTT screen; fails when none is open. */
    static TabbedMqttScreen openScreen(Minecraft mc) {
        if (mc.gui.screen() instanceof TabbedMqttScreen screen) return screen;
        throw new AssertionError("No MineQTT screen is open: " + mc.gui.screen());
    }
}

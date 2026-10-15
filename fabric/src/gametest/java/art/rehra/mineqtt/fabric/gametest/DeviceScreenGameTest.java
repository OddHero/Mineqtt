package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.DeviceTabs;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.device.GeneralTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The generated device screen's General tab from a real client (M2 plan §4.2 M2-8). The non-op player places a Redstone
 * Output; its screen opens on General with the device's name, slug, state topic and group from its view. Typing a name
 * previews the slug and topic and sends nothing; Enter renames the device. Copy puts the topic on the clipboard. Both
 * sides reopen on the tab the player last had open. The Expose button is read-only without {@code mayExpose}; once the
 * player is an operator it steps the exposure through on (Home Assistant discovers the device, and a rename through the
 * field changes its name there), off and default. A name longer than the field shows cut and is no pending rename. A
 * player whose claim lets them use but not configure a device (a stand-in for a claim mod) sees the tab read-only.
 */
public final class DeviceScreenGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos OTHERS = new BlockPos(-2, GameTestSession.GROUND_Y + 1, 0);
    private static final UUID OTHER_OWNER = UUID.fromString("6d2f3e4a-5b6c-4d7e-8f90-a1b2c3d4e5f6");
    private static final int ANSWER_TICKS = 40;
    // Ample for a dropped payload to have done something if it had not been dropped.
    private static final int DROP_TICKS = 20;
    // Reads of a retained discovery config, each up to a second, before a broker check gives up.
    private static final int BROKER_ATTEMPTS = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "device-screen", DeviceScreenGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        BlockPos output = session.place(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(output) instanceof DeviceHost host && host.deviceLink().attached());
        TabbedMqttScreen screen = session.openBlock(output);

        // The generated screen: a device menu, General first and shown first.
        session.check(screen.getMenu() instanceof DeviceMenu, "a device opens its generated screen");
        session.check(session.tabIds().getFirst().equals(GeneralTab.ID) && GeneralTab.ID.equals(session.onClient(mc -> screen(mc).getMenu().getActiveTabId())),
                "the screen opens on its General tab, the first of " + session.tabIds());
        DeviceView view = session.screenView().orElseThrow();
        String root = MineQTTConfig.baseTopic + "/" + GameTestSession.BRIDGE_ID;
        session.check(view.registration().topicRoot().equals(root) && view.registration().group().isEmpty() && !view.registration().discovered(),
                "the view names the bridge's topic root, no group, and Home Assistant does not discover the device under OPT_IN (" + view.registration() + ")");
        session.check(view.name().equals(nameField(session)), "the name field shows the device's name (" + view.name() + ")");
        session.check(!exposeActive(session) && exposeLabel(session).equals("Expose: default"),
                "the Expose button shows default and is read-only for a player without mayExpose");
        session.check(session.awaitTitleNote(Optional.empty(), ANSWER_TICKS), "the owner, who may configure the device, sees no note in the title row ("
                + session.titleNote() + ")");
        session.screenshot("device_screen_general");

        // Typing previews the slug and the topic and sends nothing; Enter renames.
        typeName(session, "Garage Door");
        session.context().waitTicks(DROP_TICKS);
        DeviceView typing = session.screenView().orElseThrow();
        session.check(typing.name().equals(view.name()) && view.name().equals(session.device(output, link -> link.name())),
                "typing a name renames nothing yet");
        session.check(DeviceTabs.slugPreview(nameField(session), typing).equals(Optional.of("garage_door")), "the typed name previews the slug garage_door");
        session.screenshot("device_screen_general_typing");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(awaitView(session, v -> v.name().equals("Garage Door") && v.slug().equals("garage_door")), "Enter renames the device to Garage Door");
        session.check("Garage Door".equals(session.device(output, link -> link.name())), "the server has the new name");
        session.check("Garage Door".equals(nameField(session)), "the field shows the name the server answered with");
        String title = session.onClient(mc -> ((DeviceMenu) screen(mc).getMenu()).title().getString());
        session.check(title.equals("Garage Door"), "the screen's title follows the rename at once (" + title + ")");

        // Copy puts the state topic on the clipboard; the player's clipboard is restored afterwards. The tab takes a new view
        // when it draws it, which the test's ticks do not always include, so it waits until Save shows the rename is no
        // longer pending.
        session.check(session.awaitClient(mc -> !saveActive(mc), ANSWER_TICKS), "the tab shows the rename, Save is inactive again");
        String clipboard = session.onClient(mc -> mc.keyboardHandler.getClipboard());
        try {
            session.clickWidget(widget -> widget instanceof Button && widget.getMessage().getString().equals("Copy"), "Copy");
            String copied = session.onClient(mc -> mc.keyboardHandler.getClipboard());
            session.check(copied.equals(root + "/garage_door"), "Copy puts the state topic on the clipboard (" + copied + ")");
        } finally {
            session.context().runOnClient(mc -> mc.keyboardHandler.setClipboard(clipboard));
        }
        session.closeScreen();

        rememberedTab(session, output);
        expose(session, output);
        longName(session, output);
        readOnly(session);
    }

    /**
     * The device remembers the tab the player had open, in its own data since it has no container (M2-12), and both
     * sides reopen on it.
     */
    private static void rememberedTab(GameTestSession session, BlockPos output) {
        session.openBlock(output);
        session.check(session.clickTab(DiagnosticsTab.ID), "the Diagnostics tab opens");
        session.closeScreen();
        session.check(DiagnosticsTab.ID.equals(session.device(output, DeviceLink::lastTab)), "the device keeps the tab");
        session.openBlock(output);
        String serverTab = session.onServer(s -> session.player(s).containerMenu instanceof TabbedMqttMenu menu ? menu.getActiveTabId() : null);
        String clientTab = session.onClient(mc -> screen(mc).getMenu().getActiveTabId());
        session.check(DiagnosticsTab.ID.equals(clientTab) && DiagnosticsTab.ID.equals(serverTab),
                "the screen reopens on Diagnostics on both sides (client " + clientTab + ", server " + serverTab + ")");
        session.check(session.clickTab(GeneralTab.ID), "the General tab opens again");
        session.closeScreen();
    }

    /**
     * A name longer than the field, which only a command or a copy gives, shows cut to the field's length: the cut is
     * no rename to save, and a later rename still reaches the field.
     */
    private static void longName(GameTestSession session, BlockPos output) {
        int max = LinkSetting.MAX_NAME_LENGTH;
        String longName = "Garage Door " + "x".repeat(max);
        session.consoleCommand("mineqtt devices rename " + session.device(output, link -> link.slug()) + " " + longName);
        session.awaitServer(s -> longName.equals(session.device(output, link -> link.name())));
        session.openBlock(output);
        session.context().waitTick();
        session.check(longName.substring(0, max).equals(nameField(session)), "the field shows the start of a name longer than it (" + nameField(session) + ")");
        session.check(!saveActive(session), "the cut name is no rename to save");
        session.screenshot("device_screen_long_name");

        String renamed = "Side Door " + "y".repeat(max);
        session.consoleCommand("mineqtt devices rename " + session.device(output, link -> link.slug()) + " " + renamed);
        session.check(awaitView(session, v -> v.name().equals(renamed)) && session.awaitClient(mc -> renamed.substring(0, max).equals(nameField(mc)), ANSWER_TICKS),
                "a later rename by command reaches the field (" + nameField(session) + ")");
        session.check(!saveActive(session), "nor is the new cut a rename to save");
        session.closeScreen();
    }

    /**
     * An operator (op level 2, like {@code mineqtt:ha.expose}) steps the exposure: on, and Home Assistant discovers the
     * device and gets the name a rename through the field gives it; off, and its discovery is cleared; default, which
     * follows OPT_IN.
     */
    private static void expose(GameTestSession session, BlockPos output) {
        session.consoleCommand("op " + session.playerName());
        session.awaitServer(s -> AclContext.mayExpose(session.player(s).permissions()));
        try {
            // What the player may change comes with the screen's open data.
            session.openBlock(output);
            session.check(session.screenView().map(view -> view.grants().expose()).orElse(false) && exposeActive(session),
                    "an operator's view grants exposing and the Expose button is active");
            String discovery = session.onRuntime(runtime -> TopicScheme.discoveryConfig(MineQTTConfig.discoveryPrefix,
                    runtime.registry().record(session.device(output, link -> link.id())).objectId()));

            clickExpose(session);
            session.check(awaitView(session, v -> v.expose().equals(Optional.of(true)) && v.registration().discovered()),
                    "Expose on: the device is exposed and Home Assistant discovers it");
            session.check(awaitExposeLabel(session, "Expose: on"), "the button shows on");
            brokerCheck(session, discovery, payload -> payload.filter(json -> deviceName(json).equals("Garage Door")).isPresent(),
                    "the device's discovery config is retained with its name Garage Door");

            typeName(session, "Garage Opener");
            session.context().getInput().pressKey(InputConstants.KEY_RETURN);
            session.check(awaitView(session, v -> v.name().equals("Garage Opener") && v.slug().equals("garage_opener")), "the exposed device is renamed through the field");
            brokerCheck(session, discovery, payload -> payload.filter(json -> deviceName(json).equals("Garage Opener")).isPresent(),
                    "Home Assistant's device name follows the rename");
            session.screenshot("device_screen_general_exposed");

            clickExpose(session);
            session.check(awaitView(session, v -> v.expose().equals(Optional.of(false)) && !v.registration().discovered()),
                    "Expose off: the device is hidden");
            brokerCheck(session, discovery, Optional::isEmpty, "its discovery config is cleared");
            // The button steps on from what it shows.
            session.check(awaitExposeLabel(session, "Expose: off"), "the button shows off");
            clickExpose(session);
            session.check(awaitView(session, v -> v.expose().isEmpty() && !v.registration().discovered()),
                    "Expose default: the device follows ha.expose, OPT_IN on a dedicated server");
            session.check("default".equals(session.device(output, link -> link.expose() == null ? "default" : link.expose().toString())),
                    "the server's device follows ha.expose again");
            session.closeScreen();
        } finally {
            session.consoleCommand("deop " + session.playerName());
            session.awaitServer(s -> !AclContext.isOp(session.player(s).permissions()));
        }
    }

    /**
     * A player whose claim trusts them to use a device but not to configure it, as a claim mod's container permission
     * would, sees the General tab read-only: the field takes no typing, there is no Save button, Expose is inactive, and
     * the title row says Read only.
     * Without a claim mod in the test, a permission manager that answers like one stands in for it.
     */
    private static void readOnly(GameTestSession session) {
        PermissionManager installed = MineQTT.permissionManager;
        MineQTT.permissionManager = new PermissionManager() {
            @Override
            public boolean canInteract(ServerPlayer player, BlockPos pos, MineqttPermission permission) {
                return permission == MineqttPermission.USE;
            }
        };
        try {
            session.consoleCommand("setblock " + OTHERS.getX() + " " + OTHERS.getY() + " " + OTHERS.getZ() + " mineqtt:rgb_led_block{mineqtt:{v:2,id:"
                    + intArray(UUID.randomUUID()) + ",type:\"mineqtt:smart_lamp\",name:\"Porch Lamp\",owner:" + intArray(OTHER_OWNER)
                    + ",ownerName:\"Someone\",access:\"claim\"}}");
            session.awaitServer(s -> s.overworld().getBlockEntity(OTHERS) instanceof DeviceHost host && host.deviceLink().attached());
            session.openBlock(OTHERS);
            DeviceView view = session.screenView().orElseThrow();
            session.check(view.grants().equals(new DeviceView.Grants(true, false, false, false)), "the player may use the lamp but not configure it (" + view.grants() + ")");
            session.check(!saveVisible(session) && !exposeActive(session), "General has no Save button and an inactive Expose button");
            session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "the title row says Read only (" + session.titleNote() + ")");
            session.clickWidget(EditBox.class::isInstance, "the name field");
            session.context().getInput().typeChars("x");
            session.context().waitTick();
            session.check(nameField(session).equals(view.name()), "the name field takes no typing (" + nameField(session) + ")");
            session.screenshot("device_screen_general_read_only");

            // The guard drops a rename the screen would not send: the player may not configure the lamp.
            session.send(new DeviceConfigPayload(OTHERS, "name", StringTag.valueOf("Stolen Lamp")));
            session.context().waitTicks(DROP_TICKS);
            session.check(view.name().equals(session.device(OTHERS, link -> link.name())), "a rename sent anyway changes nothing");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
    }

    // Clicks into the name field, erases its text with End and Backspace, and types the name.
    private static void typeName(GameTestSession session, String name) {
        session.clickWidget(EditBox.class::isInstance, "the name field");
        session.context().getInput().pressKey(InputConstants.KEY_END);
        for (int i = nameField(session).length(); i > 0; i--) {
            session.context().getInput().pressKey(InputConstants.KEY_BACKSPACE);
        }
        session.check(nameField(session).isEmpty(), "End and Backspace erase the name field");
        session.context().getInput().typeChars(name);
        session.context().waitTick();
        session.check(name.equals(nameField(session)), "the name field takes the typed name " + name);
    }

    private static void clickExpose(GameTestSession session) {
        session.clickWidget(DeviceScreenGameTest::isExpose, "Expose");
    }

    // Reads the retained discovery config until it matches; checks the last read.
    private static void brokerCheck(GameTestSession session, String topic, Predicate<Optional<String>> matches, String what) {
        Optional<BrokerConfig> broker = session.broker();
        if (broker.isEmpty()) {
            session.skip(what, "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            return;
        }
        try (BrokerProbe probe = BrokerProbe.connect(broker.get())) {
            Optional<String> payload = probe.retained(topic);
            for (int attempt = 1; attempt < BROKER_ATTEMPTS && !matches.test(payload); attempt++) {
                payload = probe.retained(topic);
            }
            session.check(matches.test(payload), what + " (" + topic + ": " + payload.orElse("nothing retained") + ")");
        }
    }

    private static String deviceName(String discovery) {
        return JsonParser.parseString(discovery).getAsJsonObject().getAsJsonObject("dev").get("name").getAsString();
    }

    private static boolean awaitView(GameTestSession session, Predicate<DeviceView> matches) {
        return session.awaitClient(mc -> {
            DeviceView view = screen(mc).getMenu().deviceView().orElse(null);
            return view != null && matches.test(view);
        }, ANSWER_TICKS);
    }

    private static String nameField(GameTestSession session) {
        return session.onClient(DeviceScreenGameTest::nameField);
    }

    private static String nameField(Minecraft mc) {
        return widget(mc, EditBox.class::isInstance).map(field -> ((EditBox) field).getValue()).orElseThrow();
    }

    private static boolean saveActive(GameTestSession session) {
        return session.onClient(DeviceScreenGameTest::saveActive);
    }

    private static boolean saveActive(Minecraft mc) {
        return widget(mc, w -> w instanceof Button && w.getMessage().getString().equals("Save")).orElseThrow().active;
    }

    // The tab draws a new view's label in its next frame.
    private static boolean awaitExposeLabel(GameTestSession session, String label) {
        return session.awaitClient(mc -> widget(mc, DeviceScreenGameTest::isExpose).orElseThrow().getMessage().getString().equals(label), ANSWER_TICKS);
    }

    private static boolean exposeActive(GameTestSession session) {
        return session.onClient(mc -> widget(mc, DeviceScreenGameTest::isExpose).orElseThrow().active);
    }

    private static String exposeLabel(GameTestSession session) {
        return session.onClient(mc -> widget(mc, DeviceScreenGameTest::isExpose).orElseThrow().getMessage().getString());
    }

    private static boolean saveVisible(GameTestSession session) {
        return session.onClient(mc -> widget(mc, w -> w instanceof Button && w.getMessage().getString().equals("Save")).orElseThrow().visible);
    }

    private static boolean isExpose(AbstractWidget widget) {
        return widget instanceof Button && widget.getMessage().getString().startsWith("Expose:");
    }

    private static Optional<AbstractWidget> widget(Minecraft mc, Predicate<AbstractWidget> which) {
        return screen(mc).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(which).findFirst();
    }

    private static TabbedMqttScreen screen(Minecraft mc) {
        if (Screens.current() instanceof TabbedMqttScreen screen) return screen;
        throw new AssertionError("No MineQTT screen is open: " + Screens.current());
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

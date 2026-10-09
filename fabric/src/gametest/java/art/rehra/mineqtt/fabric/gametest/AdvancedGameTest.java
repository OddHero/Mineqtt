package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.integrations.PermissionManager;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.ui.device.AccessTab;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.device.DeviceMenu;
import art.rehra.mineqtt.ui.device.DiagnosticsTab;
import art.rehra.mineqtt.ui.device.GeneralTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The generated device screen's Advanced tab from a real client (M2 plan §4.2 M2-10). The non-op owner gives a Redstone
 * Input a channel through the text field and sees the topic it resolves to; switches its retain off (the broker's stored
 * level is cleared) and on again, sets QoS 1 and both custom payloads; types a topic the server's allowlist refuses and
 * sees the reason on the tab, with the link unchanged, until a command changes the link; then types an allowed topic,
 * which is used as typed and reopens in Topic mode. A renamed paper used on a Redstone Output and on the input fills both
 * channels, not consumed, with the answer in the action bar, and the output then follows the input. A
 * player whose claim lets them use but not configure another owner's output is refused the paper and sees the tab
 * read-only.
 */
public final class AdvancedGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos INPUT_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos OUTPUT_GROUND = new BlockPos(-2, GameTestSession.GROUND_Y, 0);
    private static final BlockPos OTHERS = new BlockPos(0, GameTestSession.GROUND_Y + 1, -2);
    private static final UUID OTHER_OWNER = UUID.fromString("8f4a5b6c-7d8e-4f90-a1b2-c3d4e5f6a7b8");
    private static final String CHANNEL = "advanced_gametest";
    private static final String MOVED = "advanced_gametest_moved";
    private static final String TYPED = "mineqtt/shared/advanced_gametest_typed";
    private static final String REFUSED = "zigbee2mqtt/advanced_gametest/set";
    private static final String PAPER = "advanced_gametest_paper";
    private static final int ANSWER_TICKS = 40;
    // Ample for a dropped change to have done something if it had not been dropped.
    private static final int DROP_TICKS = 20;
    // Reads of a retained message, each up to a second, before a broker check gives up.
    private static final int BROKER_ATTEMPTS = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "advanced", AdvancedGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        // The input publishes its level retained on each topic it uses (M2 plan D2).
        session.clearAtClose(RawTopics.shared(CHANNEL));
        session.clearAtClose(RawTopics.shared(MOVED));
        session.clearAtClose(TYPED);
        session.clearAtClose(RawTopics.shared(PAPER));
        BlockPos input = session.place(MineqttBlocks.PUBLISHER_BLOCK.get(), INPUT_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(input) instanceof DeviceHost host && host.deviceLink().attached());
        channel(session, input);
        retainAndPayloads(session, input);
        privateChannel(session, input);
        typedTopic(session, input);
        paper(session, input);
        readOnly(session);
    }

    /** An empty Advanced tab, then a channel typed into the field: the server resolves it into the shared namespace. */
    private static void channel(GameTestSession session, BlockPos input) {
        session.openBlock(input);
        session.check(session.tabIds().equals(List.of(GeneralTab.ID, AdvancedTab.ID, AccessTab.ID, DiagnosticsTab.ID)),
                "the input's screen has General, Advanced, Access and Diagnostics, no Controls (it has no in-port) and no slot tabs (M2-12): "
                        + session.tabIds());
        session.check(session.clickTab(AdvancedTab.ID), "the Advanced tab opens");
        session.check(label(session, isButton("Channel")).equals("Channel") && field(session, linkField()).isEmpty(),
                "without a Raw Link the editor shows an empty channel");
        session.check(label(session, isButton("Retain:")).equals("Retain: ON (default)") && !active(session, isButton("Retain:"))
                        && !active(session, onField()), "retain shows the input's default, on, and like the payloads waits for a link");
        session.screenshot("advanced_input_empty");

        type(session, linkField(), CHANNEL);
        session.context().waitTicks(DROP_TICKS);
        session.check(session.device(input, link -> link.raw()) == null, "typing a channel links nothing yet");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        String topic = RawTopics.shared(CHANNEL);
        session.check(awaitView(session, view -> view.raw().filter(raw -> raw.topic().equals(topic) && raw.channel().equals(Optional.of(CHANNEL))).isPresent()),
                "Enter sets the channel, which resolves to " + topic);
        RawLink link = session.device(input, DeviceLink::raw);
        session.check(link != null && link.active() && CHANNEL.equals(link.channel()) && topic.equals(link.topic()), "the server's link has the channel (" + link + ")");
        session.check(session.awaitClient(mc -> field(mc, linkField()).equals(CHANNEL) && widget(mc, isButton("Retain:")).orElseThrow().active
                && widget(mc, onField()).orElseThrow().active, ANSWER_TICKS), "the field shows the channel and the link's settings open up");
        session.check(session.awaitTitleNote(Optional.empty(), ANSWER_TICKS), "the owner sees no note in the title row (" + session.titleNote() + ")");
        session.screenshot("advanced_input_channel");
    }

    /** Retain off and on again, QoS 1, and both custom payloads, one with Enter and one with Save. */
    private static void retainAndPayloads(GameTestSession session, BlockPos input) {
        String topic = RawTopics.shared(CHANNEL);
        brokerCheck(session, topic, payload -> payload.equals(Optional.of("false")), "the input keeps its level retained on " + topic);
        session.clickWidget(isButton("Retain:"), "the retain switch");
        session.check(awaitView(session, view -> view.raw().flatMap(DeviceView.Raw::retain).equals(Optional.of(false)))
                        && session.awaitClient(mc -> label(mc, isButton("Retain:")).equals("Retain: OFF"), ANSWER_TICKS),
                "the switch turns retain off, an override shown without (default)");
        brokerCheck(session, topic, Optional::isEmpty, "retain off clears the stored level");
        session.clickWidget(isButton("Retain:"), "the retain switch");
        session.check(awaitView(session, view -> view.raw().flatMap(DeviceView.Raw::retain).equals(Optional.of(true)))
                && session.awaitClient(mc -> label(mc, isButton("Retain:")).equals("Retain: ON"), ANSWER_TICKS), "and on again");
        brokerCheck(session, topic, payload -> payload.equals(Optional.of("false")), "retain on stores the level again");

        session.dragSlider(isSlider("QoS:"), "the QoS slider", List.of(1.0), () -> {
        });
        session.check(awaitView(session, view -> view.raw().map(DeviceView.Raw::qos).orElse(0) == 1)
                && session.awaitClient(mc -> label(mc, isSlider("QoS:")).equals("QoS: 1"), ANSWER_TICKS), "the slider sets QoS 1");

        type(session, onField(), "OPEN");
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(awaitView(session, view -> view.raw().flatMap(DeviceView.Raw::on).equals(Optional.of("OPEN"))), "Enter in the On field sets the on payload");
        type(session, offField(), "SHUT");
        session.check(active(session, isButton("Save")), "an edited payload can be saved");
        session.clickWidget(isButton("Save"), "Save");
        session.check(awaitView(session, view -> view.raw().flatMap(DeviceView.Raw::off).equals(Optional.of("SHUT"))), "Save sets the off payload");
        RawLink link = session.device(input, DeviceLink::raw);
        session.check(link != null && "OPEN".equals(link.on()) && "SHUT".equals(link.off()) && link.qos() == 1 && Boolean.TRUE.equals(link.retain()),
                "the server's link has the payloads, QoS 1 and its retain override (" + link + ")");
        session.check(session.awaitClient(mc -> !label(mc, isButton("Save")).isEmpty() && !widget(mc, isButton("Save")).orElseThrow().active, ANSWER_TICKS),
                "nothing is left to save");
        session.screenshot("advanced_input_payloads");
    }

    /**
     * Private mode (M2-12, v1's private mode): the same channel saved in Private mode resolves into the owner's private
     * subtree, and reopens in Private mode; saved in Channel mode again it moves back to the shared namespace.
     */
    private static void privateChannel(GameTestSession session, BlockPos input) {
        session.clickWidget(isButton("Channel"), "the mode button");
        session.check(session.awaitClient(mc -> label(mc, isButton("Private")).equals("Private") && field(mc, linkField()).equals(CHANNEL)
                && widget(mc, isButton("Save")).orElseThrow().active, ANSWER_TICKS), "Private mode shows the channel, which can be saved as a private one");
        session.clickWidget(isButton("Save"), "Save");
        String privateTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/raw/players/" + session.playerId() + "/" + CHANNEL;
        session.clearAtClose(privateTopic);
        session.check(awaitView(session, view -> view.privateMode() && view.raw().filter(raw -> raw.topic().equals(privateTopic)
                && raw.channel().equals(Optional.of(CHANNEL))).isPresent()), "the channel resolves into the owner's private subtree " + privateTopic);
        session.check(Boolean.TRUE.equals(session.device(input, DeviceLink::privateMode)), "the server's link is private");
        brokerCheck(session, privateTopic, payload -> payload.equals(Optional.of("SHUT")), "the input publishes its level there, in its off payload");
        session.screenshot("advanced_input_private");
        session.closeScreen();
        session.openBlock(input);
        session.check(session.clickTab(AdvancedTab.ID) && label(session, isButton("Private")).equals("Private") && field(session, linkField()).equals(CHANNEL),
                "a private channel reopens in Private mode");

        session.clickWidget(isButton("Private"), "the mode button");
        session.clickWidget(isButton("Topic"), "the mode button");
        session.check(label(session, isButton("Channel")).equals("Channel") && active(session, isButton("Save")), "back in Channel mode the channel can be saved again");
        session.clickWidget(isButton("Save"), "Save");
        session.check(awaitView(session, view -> !view.privateMode() && view.raw().filter(raw -> raw.topic().equals(RawTopics.shared(CHANNEL))).isPresent()),
                "saved in Channel mode it is shared again");
    }

    /**
     * Topic mode: an allowlist refuses the first topic, which the tab explains while the link stays; the second is used
     * as typed, keeps the link's settings and reopens in Topic mode.
     */
    private static void typedTopic(GameTestSession session, BlockPos input) {
        session.clickWidget(isButton("Channel"), "the mode button");
        session.clickWidget(isButton("Private"), "the mode button");
        session.check(session.awaitClient(mc -> label(mc, isButton("Topic")).equals("Topic"), ANSWER_TICKS)
                && field(session, linkField()).equals(RawTopics.shared(CHANNEL)), "Topic mode shows the topic the channel resolved to");
        int mark = ChatLog.mark();
        type(session, linkField(), REFUSED);
        session.clickWidget(isButton("Save"), "Save");
        session.check(session.awaitClient(mc -> refusal(mc).filter(refusal -> refusal.key().equals("raw.topic") && refusal.reason().contains("allowlist")).isPresent(),
                ANSWER_TICKS), "the tab gets why the typed topic is refused: " + session.onClient(AdvancedGameTest::refusal));
        session.check(session.awaitChat(mark, line -> !line.actionBar() && line.text().startsWith("Not changed:"), ANSWER_TICKS).isPresent(),
                "the refusal is told in chat as well");
        RawLink unchanged = session.device(input, DeviceLink::raw);
        session.check(unchanged != null && CHANNEL.equals(unchanged.channel()), "a refused topic leaves the link as it was (" + unchanged + ")");
        session.check(field(session, linkField()).equals(REFUSED), "the field keeps the refused topic to correct");
        session.screenshot("advanced_input_refused");

        // The link changes by another path: the refusal was about the old link and goes; the typed text stays.
        String slug = session.device(input, DeviceLink::slug);
        session.consoleCommand("mineqtt devices raw " + slug + " channel " + MOVED);
        session.check(session.awaitClient(mc -> screen(mc).getMenu().deviceView().flatMap(DeviceView::raw).flatMap(DeviceView.Raw::channel).equals(Optional.of(MOVED))
                && refusal(mc).isEmpty(), ANSWER_TICKS), "a link changed by a command ends the refusal: " + session.onClient(AdvancedGameTest::refusal));
        session.check(field(session, linkField()).equals(REFUSED), "the field still holds the typed topic");
        session.consoleCommand("mineqtt devices raw " + slug + " channel " + CHANNEL);
        session.check(awaitView(session, view -> view.raw().flatMap(DeviceView.Raw::channel).equals(Optional.of(CHANNEL))), "the command takes the channel back");
        session.clickWidget(isButton("Save"), "Save");
        session.check(session.awaitClient(mc -> refusal(mc).filter(refusal -> refusal.key().equals("raw.topic")).isPresent(), ANSWER_TICKS),
                "saving the topic again is refused again");
        session.context().waitTicks(DROP_TICKS);
        session.check(session.onClient(AdvancedGameTest::refusal).isPresent(), "the view that follows a refusal, with the same link, keeps it");

        type(session, linkField(), TYPED);
        session.context().getInput().pressKey(InputConstants.KEY_RETURN);
        session.check(awaitView(session, view -> view.raw().filter(raw -> raw.topic().equals(TYPED) && raw.channel().isEmpty()).isPresent()),
                "an allowed topic is used as typed, without a channel");
        session.check(session.onClient(AdvancedGameTest::refusal).isEmpty(), "a new change hides the old refusal");
        RawLink typed = session.device(input, DeviceLink::raw);
        session.check(typed != null && typed.channel() == null && TYPED.equals(typed.topic()) && "OPEN".equals(typed.on()) && typed.qos() == 1,
                "the server's link has the typed topic and kept its payloads and QoS (" + typed + ")");
        session.screenshot("advanced_input_typed");
        session.closeScreen();
        session.openBlock(input);
        session.check(session.clickTab(AdvancedTab.ID) && label(session, isButton("Topic")).equals("Topic") && field(session, linkField()).equals(TYPED),
                "a typed topic reopens in Topic mode");
        session.closeScreen();
    }

    /**
     * A renamed paper used on the output and on the input fills both channels without opening a screen or using up the
     * paper, and the action bar answers; used again it changes nothing. The output then follows the input.
     */
    private static void paper(GameTestSession session, BlockPos input) {
        BlockPos output = session.place(MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), OUTPUT_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(output) instanceof DeviceHost host && host.deviceLink().attached());
        holdPaper(session);
        int mark = ChatLog.mark();
        session.aim(output);
        session.pressUse();
        String topic = RawTopics.shared(PAPER);
        session.check(session.await(() -> PAPER.equals(session.device(output, link -> link.raw() == null ? null : link.raw().channel())), ANSWER_TICKS),
                "the paper's name becomes the output's channel");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Channel from the paper:") && line.text().contains(topic), ANSWER_TICKS).isPresent(),
                "the action bar says where the channel resolved (messages: " + ChatLog.since(mark) + ")");
        session.check(session.onClient(mc -> mc.gui.screen() == null), "the paper opens no screen");
        session.check(session.onServer(s -> session.player(s).getMainHandItem().is(Items.PAPER) && session.player(s).getMainHandItem().getCount() == 1),
                "the paper is not consumed");

        int again = ChatLog.mark();
        session.pressUse();
        session.check(session.awaitChat(again, line -> line.actionBar() && line.text().startsWith("Channel unchanged:"), ANSWER_TICKS).isPresent(),
                "the same paper again changes nothing, and says so (messages: " + ChatLog.since(again) + ")");

        // The output reads plain levels; the input's custom payloads from before would be words it does not know.
        String slug = session.device(input, DeviceLink::slug);
        session.consoleCommand("mineqtt devices raw " + slug + " on \"\"");
        session.consoleCommand("mineqtt devices raw " + slug + " off \"\"");
        session.check(session.await(() -> Boolean.TRUE.equals(session.device(input, link -> link.raw().on() == null && link.raw().off() == null)), ANSWER_TICKS),
                "the command twin clears the input's payloads");
        session.aim(input);
        session.pressUse();
        session.check(session.await(() -> PAPER.equals(session.device(input, link -> link.raw() == null ? null : link.raw().channel())), ANSWER_TICKS),
                "the paper fills the input's channel as well, replacing its typed topic");
        session.consoleCommand("setblock " + (input.getX() + 1) + " " + input.getY() + " " + input.getZ() + " minecraft:redstone_block");
        session.check(session.await(() -> power(session, output) > 0, ANSWER_TICKS), "the output follows the input on the paper's channel");
        session.consoleCommand("setblock " + (input.getX() + 1) + " " + input.getY() + " " + input.getZ() + " minecraft:air");
        session.check(session.await(() -> power(session, output) == 0, ANSWER_TICKS), "and turns off with it");
    }

    /**
     * A player whose claim trusts them to use another owner's output but not to configure it, as a claim mod's container
     * permission would: the paper is refused in the action bar, and the Advanced tab is read-only and says so in the title
     * row. Without a claim mod in the test, a permission manager that answers like one stands in for it.
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
            session.consoleCommand("setblock " + OTHERS.getX() + " " + OTHERS.getY() + " " + OTHERS.getZ() + " mineqtt:redstone_emitter_block{mineqtt:{v:2,id:"
                    + intArray(UUID.randomUUID()) + ",type:\"" + RedstoneOutput.TYPE.id() + "\",name:\"Gate\",owner:" + intArray(OTHER_OWNER)
                    + ",ownerName:\"Someone\",access:\"claim\"}}");
            session.awaitServer(s -> s.overworld().getBlockEntity(OTHERS) instanceof DeviceHost host && host.deviceLink().attached());
            holdPaper(session);
            int mark = ChatLog.mark();
            session.aim(OTHERS);
            session.pressUse();
            session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Not changed:") && line.text().contains("configure"), ANSWER_TICKS).isPresent(),
                    "the paper is refused in the action bar (messages: " + ChatLog.since(mark) + ")");
            session.context().waitTicks(DROP_TICKS);
            session.check(session.device(OTHERS, link -> link.raw()) == null, "and links nothing");

            session.hold(Items.AIR);
            session.openBlock(OTHERS);
            session.check(session.clickTab(AdvancedTab.ID), "the Advanced tab opens");
            DeviceView view = session.screenView().orElseThrow();
            session.check(view.grants().equals(new DeviceView.Grants(true, false, false, false)), "the player may use the output but not configure it (" + view.grants() + ")");
            session.check(!active(session, isButton("Channel")) && !active(session, linkField()) && !visible(session, isButton("Save"))
                    && !active(session, onField()), "the mode button, the fields and Save are read-only");
            session.check(session.awaitTitleNote(Optional.of("Read only"), ANSWER_TICKS), "the title row says Read only (" + session.titleNote() + ")");
            session.screenshot("advanced_read_only");
            session.closeScreen();
        } finally {
            MineQTT.permissionManager = installed;
        }
    }

    // A renamed paper in the main hand, from the server, once the client has it too.
    private static void holdPaper(GameTestSession session) {
        session.onServer(s -> {
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_NAME, Component.literal(PAPER));
            session.player(s).setItemInHand(InteractionHand.MAIN_HAND, paper);
            return null;
        });
        session.awaitClient(mc -> mc.player.getMainHandItem().is(Items.PAPER) && mc.player.getMainHandItem().getCustomName() != null, ANSWER_TICKS);
    }

    private static void type(GameTestSession session, Predicate<AbstractWidget> which, String text) {
        session.typeInto(which, "the field", text);
    }

    // Reads the retained message until it matches; checks the last read.
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

    private static int power(GameTestSession session, BlockPos output) {
        return session.onServer(s -> s.overworld().getBlockState(output).getValue(RedstoneEmitterBlock.POWER));
    }

    private static boolean awaitView(GameTestSession session, Predicate<DeviceView> matches) {
        return session.awaitClient(mc -> {
            DeviceView view = screen(mc).getMenu().deviceView().orElse(null);
            return view != null && matches.test(view);
        }, ANSWER_TICKS);
    }

    private static Optional<DeviceMenu.Refusal> refusal(Minecraft mc) {
        return screen(mc).getMenu() instanceof DeviceMenu menu ? menu.refusal() : Optional.empty();
    }

    private static Predicate<AbstractWidget> linkField() {
        return isField("Raw Link");
    }

    private static Predicate<AbstractWidget> onField() {
        return isField("On");
    }

    private static Predicate<AbstractWidget> offField() {
        return isField("Off");
    }

    private static Predicate<AbstractWidget> isField(String name) {
        return widget -> widget instanceof EditBox && widget.getMessage().getString().equals(name);
    }

    private static Predicate<AbstractWidget> isButton(String prefix) {
        return widget -> widget instanceof Button && widget.getMessage().getString().startsWith(prefix);
    }

    private static Predicate<AbstractWidget> isSlider(String prefix) {
        return widget -> widget instanceof AbstractSliderButton && widget.getMessage().getString().startsWith(prefix);
    }

    private static String field(GameTestSession session, Predicate<AbstractWidget> which) {
        return session.onClient(mc -> field(mc, which));
    }

    private static String field(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).map(field -> ((EditBox) field).getValue()).orElseThrow();
    }

    private static String label(GameTestSession session, Predicate<AbstractWidget> which) {
        return session.onClient(mc -> label(mc, which));
    }

    private static String label(Minecraft mc, Predicate<AbstractWidget> which) {
        return widget(mc, which).map(widget -> widget.getMessage().getString()).orElse("");
    }

    private static boolean active(GameTestSession session, Predicate<AbstractWidget> which) {
        return session.onClient(mc -> widget(mc, which).orElseThrow().active);
    }

    private static boolean visible(GameTestSession session, Predicate<AbstractWidget> which) {
        return session.onClient(mc -> widget(mc, which).orElseThrow().visible);
    }

    private static Optional<AbstractWidget> widget(Minecraft mc, Predicate<AbstractWidget> which) {
        return screen(mc).children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(which).findFirst();
    }

    private static TabbedMqttScreen screen(Minecraft mc) {
        if (mc.gui.screen() instanceof TabbedMqttScreen screen) return screen;
        throw new AssertionError("No MineQTT screen is open: " + mc.gui.screen());
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

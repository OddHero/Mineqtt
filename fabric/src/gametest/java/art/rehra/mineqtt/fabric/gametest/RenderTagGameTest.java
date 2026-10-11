package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RedstoneEmitterBlock;
import art.rehra.mineqtt.blocks.RedstonePublisherBlock;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.client.RgbLedBlockColor;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.RenderTag;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.device.types.PresenceSensor.Presence;
import art.rehra.mineqtt.device.types.RedstoneOutput.Output;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The render-only update tag (M2-7) from a real client: the non-op player places the four devices. Their update tags
 * carry the render compound alone, so the client's block entities know no owner or Raw Link, while the server's
 * have both; the lamp still re-tints from red to blue at the same brightness, which changes no block state; and each
 * device's screen shows its values from the view in its open data at once, its Raw Link topic as the server resolved
 * it, also after a private channel moved it, and the output's new ON payload once only its payload changed. A later
 * render update reads nothing but the render compound, the client's block entities never hold legacy items (M2-12), and
 * no client block entity ever awaits a v1 adoption.
 */
public final class RenderTagGameTest implements FabricClientGameTest {
    // The devices stand in a row three blocks apart, so the input's redstone block touches nothing else; the player
    // stands this far north of the one they place or open.
    private static final int STAND_OFF = 3;
    private static final BlockPos LAMP_GROUND = new BlockPos(-4, GameTestSession.GROUND_Y, 3);
    private static final BlockPos OUTPUT_GROUND = new BlockPos(-1, GameTestSession.GROUND_Y, 3);
    private static final BlockPos INPUT_GROUND = new BlockPos(2, GameTestSession.GROUND_Y, 3);
    private static final BlockPos SENSOR_GROUND = new BlockPos(5, GameTestSession.GROUND_Y, 3);
    private static final String LAMP_CHANNEL = "render_tag_" + GameTestSession.BRIDGE_ID + "_lamp";
    private static final String OUTPUT_CHANNEL = "render_tag_" + GameTestSession.BRIDGE_ID + "_output";
    private static final int ANSWER_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "render-tag", RenderTagGameTest::run);
    }

    private static void run(GameTestSession session) {
        BlockPos lamp = placeDevice(session, MineqttBlocks.RGB_LED_BLOCK.get(), LAMP_GROUND);
        BlockPos output = placeDevice(session, MineqttBlocks.REDSTONE_EMITTER_BLOCK.get(), OUTPUT_GROUND);
        BlockPos input = placeDevice(session, MineqttBlocks.PUBLISHER_BLOCK.get(), INPUT_GROUND);
        BlockPos sensor = placeDevice(session, MineqttBlocks.MOTION_SENSOR_BLOCK.get(), SENSOR_GROUND);
        String lampSlug = session.device(lamp, DeviceLink::slug);
        String outputSlug = session.device(output, DeviceLink::slug);
        String sensorSlug = session.device(sensor, DeviceLink::slug);

        // The server's lamp and output get a Raw Link, the output an ON payload.
        session.consoleCommand("mineqtt devices raw " + lampSlug + " channel " + LAMP_CHANNEL);
        session.consoleCommand("mineqtt devices raw " + outputSlug + " channel " + OUTPUT_CHANNEL);
        session.consoleCommand("mineqtt devices raw " + outputSlug + " on OPEN");
        session.awaitServer(s -> s.overworld().getBlockEntity(output) instanceof DeviceHost host && host.deviceLink().raw() != null
                && "OPEN".equals(host.deviceLink().raw().on()));
        session.consoleCommand("mineqtt devices set " + outputSlug + " {\"power\":7}");
        session.consoleCommand("setblock " + (input.getX() + 1) + " " + input.getY() + " " + input.getZ() + " minecraft:redstone_block");
        session.consoleCommand("mineqtt devices set " + sensorSlug + " {\"range\":3}");

        // Red, then blue at the same brightness: the light level, and so the block state, stays; only the render update
        // re-tints the lamp.
        standBy(session, lamp);
        session.aim(lamp);
        session.consoleCommand("mineqtt devices set " + lampSlug + " {\"state\":\"ON\",\"brightness\":128,\"color\":{\"r\":255,\"g\":0,\"b\":0}}");
        session.check(session.awaitClient(mc -> tint(mc, lamp) == expectedTint(0xFF0000), ANSWER_TICKS),
                "the lamp is tinted red on the client (" + Integer.toHexString(session.onClient(mc -> tint(mc, lamp))) + ")");
        int lightLevel = session.onClient(mc -> mc.level.getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL));
        session.consoleCommand("mineqtt devices set " + lampSlug + " {\"color\":{\"r\":0,\"g\":0,\"b\":255}}");
        session.check(session.awaitClient(mc -> tint(mc, lamp) == expectedTint(0x0000FF), ANSWER_TICKS),
                "the lamp re-tints blue on the client (" + Integer.toHexString(session.onClient(mc -> tint(mc, lamp))) + ")");
        session.check(session.onClient(mc -> mc.level.getBlockState(lamp).getValue(RgbLedBlock.LIGHT_LEVEL)) == lightLevel,
                "at the same light level " + lightLevel + ", so no block state carried the colour");
        session.screenshot("render_tag_lamp_blue");
        session.check(session.awaitClient(mc -> mc.level.getBlockState(output).getValue(RedstoneEmitterBlock.POWER) == 7
                && mc.level.getBlockState(input).getValue(RedstonePublisherBlock.POWERED), ANSWER_TICKS), "the output emits 7 and the input is powered");

        for (BlockPos pos : List.of(lamp, output, input, sensor)) updateTag(session, pos);
        session.check(session.device(lamp, link -> link.raw() != null) && session.device(output, link -> link.raw() != null),
                "the server's lamp and output have a Raw Link");
        for (BlockPos pos : List.of(lamp, output, input, sensor)) {
            session.check(session.onClient(mc -> noOwnerOrLink(mc, pos)), "the client's block entity at " + pos.toShortString() + " has no owner or Raw Link");
        }

        // Each screen shows its values from the view in its open data, at once.
        LightState light = shown(session, lamp, "lamp", view -> SmartLamp.LIGHT.parse(view.stateJson()).orElseThrow());
        session.check(light.on() && light.brightness() == 128 && light.color().packed() == 0x0000FF, "the lamp's screen shows it on, blue at 128: " + light);
        DeviceView lampView = session.screenView().orElseThrow();
        session.check(lampView.raw().map(DeviceView.Raw::topic).filter(RawTopics.shared(LAMP_CHANNEL)::equals).isPresent() && !lampView.privateMode()
                        && lampView.ownerName().equals(session.playerName()),
                "its view has the topic the server resolved, the private mode and the owner (" + lampView.raw() + ")");
        // A private channel moves the topic into the owner's subtree (M2-12); the screen gets the new view.
        session.send(new DeviceConfigPayload(lamp, LinkSetting.RAW_PRIVATE.key(), StringTag.valueOf(LAMP_CHANNEL)));
        session.check(awaitView(session, view -> view.privateMode() && view.raw().map(DeviceView.Raw::topic).filter(topic -> topic.contains("/raw/players/")).isPresent()),
                "a private channel moves the lamp's topic into the owner's subtree (" + session.screenView().flatMap(DeviceView::raw).map(DeviceView.Raw::topic) + ")");
        session.check(session.clickTab(AdvancedTab.ID), "the lamp's Advanced tab opens");
        session.screenshot("render_tag_lamp_private");
        session.send(new DeviceConfigPayload(lamp, LinkSetting.RAW_CHANNEL.key(), StringTag.valueOf(LAMP_CHANNEL)));
        session.check(awaitView(session, view -> !view.privateMode() && view.raw().map(DeviceView.Raw::topic).filter(RawTopics.shared(LAMP_CHANNEL)::equals).isPresent()),
                "the same channel, no longer private, brings the shared topic back");
        session.closeScreen();
        session.check(session.onClient(mc -> noOwnerOrLink(mc, lamp)), "the lamp's client block entity still has no owner or Raw Link");

        Output shownOutput = shown(session, output, "output", view -> Output.of(view.stateJson()));
        DeviceView outputView = session.screenView().orElseThrow();
        session.check(shownOutput.power() == 7 && outputView.raw().flatMap(DeviceView.Raw::on).filter("OPEN"::equals).isPresent(),
                "the output's screen shows power 7 and its ON payload (" + shownOutput + ", " + outputView.raw() + ")");
        // Only the payload changes: the topic stays, the open screen gets the new payload.
        session.consoleCommand("mineqtt devices raw " + outputSlug + " on HIGH");
        session.check(awaitView(session, view -> view.raw().flatMap(DeviceView.Raw::on).filter("HIGH"::equals).isPresent()),
                "a new ON payload reaches the open screen (" + session.screenView().flatMap(DeviceView::raw) + ")");
        session.closeScreen();

        boolean powered = shown(session, input, "input", view -> view.stateJson().get("powered").getAsBoolean());
        session.check(powered, "the input's screen shows it powered");
        session.closeScreen();

        Presence presence = shown(session, sensor, "presence_sensor", view -> Presence.of(view.stateJson()));
        session.check(presence.range() == 3, "the sensor's screen shows range 3: " + presence);
        session.closeScreen();

        // A forced render update of every device reads only the render compound. The lamp's later re-tint is the mark that
        // the client has handled the updates sent a tick before it.
        List<BlockPos> devices = List.of(lamp, output, input, sensor);
        session.onServer(s -> {
            devices.forEach(pos -> ((DeviceBlockEntity) s.overworld().getBlockEntity(pos)).markUpdated());
            return null;
        });
        session.context().waitTick();
        session.consoleCommand("mineqtt devices set " + lampSlug + " {\"color\":{\"r\":0,\"g\":255,\"b\":0}}");
        session.check(session.awaitClient(mc -> tint(mc, lamp) == expectedTint(0x00FF00), ANSWER_TICKS), "the lamp re-tints green on the client");
        for (BlockPos pos : devices) {
            session.check(session.onClient(mc -> noOwnerOrLink(mc, pos)),
                    "a render update leaves the client's block entity at " + pos.toShortString() + " without owner, Raw Link or legacy items");
        }
    }

    private static BlockPos placeDevice(GameTestSession session, Block block, BlockPos ground) {
        standBy(session, ground);
        BlockPos pos = session.place(block, ground);
        session.awaitServer(s -> s.overworld().getBlockEntity(pos) instanceof DeviceHost host && host.deviceLink().attached());
        return pos;
    }

    /** The server's update tag of the device holds the render compound alone; the lamp's holds its colour, brightness and on. */
    private static void updateTag(GameTestSession session, BlockPos pos) {
        CompoundTag tag = session.onServer(s -> s.overworld().getBlockEntity(pos).getUpdateTag(s.registryAccess()));
        session.check(tag.keySet().equals(Set.of(RenderTag.KEY)), "the update tag at " + pos.toShortString() + " is render-only: " + tag);
        boolean lamp = session.onServer(s -> s.overworld().getBlockState(pos).is(MineqttBlocks.RGB_LED_BLOCK.get()));
        Set<String> expected = lamp ? Set.of("rgb", "brightness", "on") : Set.of();
        session.check(tag.getCompoundOrEmpty(RenderTag.KEY).keySet().equals(expected), "it renders " + expected + ": " + tag);
    }

    /**
     * Opens the device's screen, reads a value from the view that came with it, without waiting for a later one, and
     * photographs every tab.
     */
    private static <T> T shown(GameTestSession session, BlockPos pos, String name, Function<DeviceView, T> read) {
        standBy(session, pos);
        session.openBlock(pos);
        Optional<DeviceView> view = session.screenView();
        session.check(view.isPresent(), "the " + name + "'s screen opens with its view");
        T value = read.apply(view.orElseThrow());
        for (String tab : session.tabIds()) {
            session.check(session.clickTab(tab), name + " shows its " + tab + " tab");
            session.screenshot("render_tag_" + name + "_" + tab);
        }
        return value;
    }

    // Within reach of the block at pos, north of it. A player who stands there already is not teleported again: the
    // client would not wait for that teleport, which could arrive after the next aim and turn the player away again.
    private static void standBy(GameTestSession session, BlockPos pos) {
        BlockPos feet = new BlockPos(pos.getX(), GameTestSession.GROUND_Y + 1, pos.getZ() - STAND_OFF);
        if (!session.onClient(mc -> mc.player.blockPosition().equals(feet))) session.teleport(feet);
    }

    private static boolean awaitView(GameTestSession session, Predicate<DeviceView> matches) {
        return session.awaitClient(mc -> mc.gui.screen() instanceof TabbedMqttScreen screen && screen.getMenu().deviceView().filter(matches).isPresent(), ANSWER_TICKS);
    }

    // Whether the client's block entity at pos carries no owner, device identity, Raw Link or legacy items. A device that
    // read a render tag as its data would also run the v1 migrator on it and await an adoption.
    private static boolean noOwnerOrLink(Minecraft mc, BlockPos pos) {
        if (!(mc.level.getBlockEntity(pos) instanceof DeviceBlockEntity device) || device instanceof Container) return false;
        DeviceLink link = device.deviceLink();
        return link.owner() == null && link.ownerName().isEmpty() && link.id() == null && link.raw() == null && link.legacyItems().isEmpty()
                && !link.awaitingAdoption();
    }

    // The tint the lamp's block colour gives on the client, as the renderer asks for it.
    private static int tint(Minecraft mc, BlockPos pos) {
        return new RgbLedBlockColor().colorInWorld(mc.level.getBlockState(pos), mc.level, pos);
    }

    // The tint of a lamp that is on at brightness 128 in this colour: each channel scaled by its light level of 15.
    private static int expectedTint(int rgb) {
        LightState light = new LightState(true, 128, Rgb.ofPacked(rgb), null);
        float factor = SmartLamp.lightLevel(light) / 15.0f;
        int r = Math.round(light.color().r() * factor);
        int g = Math.round(light.color().g() * factor);
        int b = Math.round(light.color().b() * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

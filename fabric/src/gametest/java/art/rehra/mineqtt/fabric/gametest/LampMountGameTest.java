package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.blocks.RgbLedBlock;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.DeviceRecord;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Smart Lamp mounts (P3). Block states as 0.6.0 saved them, without {@code face}, decode as floor lamps with their
 * facing and light level. The non-op player places lamps by hand on the top of a block (a floor lamp), on the four sides
 * of a floating stone block (wall lamps pointing away from it) and under a block (a ceiling lamp); each state's outline
 * reaches its support and holds the bulb, and each lamp lights its block at 15 when switched on. The top of a bottom
 * slab holds no lamp; a click on the side of short grass replaces it with a floor lamp. A neighbour that is not the support changes nothing; when the support goes away, its lamps pop
 * off, drop as items and leave the registry. Lamps as 0.6.0 placed them without a support check, one stacked on another
 * and one floating, stay while the lamp below switches or a side neighbour comes and goes, and pop off when the block on
 * their support side goes away. Screenshots {@code lamp_mount_*}.
 */
public final class LampMountGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos START = new BlockPos(0, Y, 0);
    // A stone block floating at head height, with a wall lamp on each side.
    private static final BlockPos WALL = new BlockPos(0, Y + 1, 6);
    // A stone block three blocks up, with a ceiling lamp below it.
    private static final BlockPos CEILING = new BlockPos(8, Y + 3, 6);
    private static final BlockPos FLOOR_GROUND = new BlockPos(-8, GameTestSession.GROUND_Y, 6);
    private static final BlockPos SLAB = new BlockPos(-8, Y, 2);
    private static final BlockPos GRASS = new BlockPos(8, Y, 2);
    // A lamp on the ground with a second lamp standing on it, as 0.6.0 let a player stack them.
    private static final BlockPos STACK_LOWER = new BlockPos(8, Y, -4);
    private static final BlockPos STACK_UPPER = STACK_LOWER.above();
    // A floor lamp one block above the ground, as 0.6.0 placed a lamp clicked against a wall.
    private static final BlockPos FLOATING = new BlockPos(-8, Y + 1, -4);
    private static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    // The lamp's model reaches 14 of 16 pixels out from its support.
    private static final double REACH = 14.0 / 16.0;
    private static final double EPSILON = 1.0E-6;
    private static final int ANSWER_TICKS = 60;
    private static final int SETTLE_TICKS = 10;
    // The server resends an unconfirmed teleport after 20 ticks, with its rotation, which would undo an aim (M2-21).
    private static final int TELEPORT_TICKS = 25;
    private static final int EQUIP_TICKS = 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "lamp_mount", LampMountGameTest::run);
    }

    private static void run(GameTestSession session) {
        legacyStates(session);
        setblock(session, WALL, "minecraft:stone");
        setblock(session, CEILING, "minecraft:stone");
        setblock(session, SLAB, "minecraft:smooth_stone_slab[type=bottom]");

        BlockPos floor = placeOn(session, FLOOR_GROUND, Direction.UP, FLOOR_GROUND.above().south(3));
        mounted(session, floor, AttachFace.FLOOR, Direction.UP);
        for (Direction side : SIDES) {
            BlockPos lamp = placeOn(session, WALL, side, WALL.relative(side, 3).below());
            mounted(session, lamp, AttachFace.WALL, side);
        }
        BlockPos ceiling = placeOn(session, CEILING, Direction.DOWN, CEILING.below(3).north(2));
        mounted(session, ceiling, AttachFace.CEILING, Direction.DOWN);
        notOnSlab(session);
        onGrass(session);

        List<BlockPos> lamps = List.of(floor, WALL.north(), WALL.east(), WALL.south(), WALL.west(), ceiling);
        for (BlockPos lamp : lamps) {
            session.consoleCommand("mineqtt devices set " + slug(session, lamp) + " {\"state\":\"ON\",\"color\":{\"r\":255,\"g\":180,\"b\":60}}");
        }
        for (BlockPos lamp : lamps) {
            session.check(session.await(() -> session.onServer(s -> s.overworld().getBrightness(LightLayer.BLOCK, lamp)) == 15
                    && session.onClient(mc -> mc.level.getBrightness(LightLayer.BLOCK, lamp)) == 15, ANSWER_TICKS),
                    "the lamp at " + lamp.toShortString() + " lights its block at 15 on both sides");
        }
        screenshots(session);
        supportGone(session, floor, ceiling);
        legacyStack(session);
        legacyFloating(session);
    }

    /** The palette entries a 0.6.0 world stored (from its region file) decode as floor lamps, facing and light kept. */
    private static void legacyStates(GameTestSession session) {
        Map<String, String> saved = Map.of(
                "{Name:\"mineqtt:rgb_led_block\",Properties:{facing:\"north\",light_level:\"0\"}}", "north/0",
                "{Name:\"mineqtt:rgb_led_block\",Properties:{facing:\"north\",light_level:\"15\"}}", "north/15",
                "{Name:\"mineqtt:rgb_led_block\",Properties:{facing:\"east\",light_level:\"0\"}}", "east/0",
                "{Name:\"mineqtt:rgb_led_block\",Properties:{facing:\"up\",light_level:\"0\"}}", "up/0");
        saved.forEach((entry, expected) -> {
            BlockState state = session.onServer(s -> {
                try {
                    return BlockState.CODEC.parse(NbtOps.INSTANCE, TagParser.parseCompoundFully(entry)).getOrThrow();
                } catch (CommandSyntaxException e) {
                    throw new IllegalStateException(e);
                }
            });
            session.check(state.is(MineqttBlocks.RGB_LED_BLOCK.get()) && state.getValue(RgbLedBlock.FACE) == AttachFace.FLOOR
                    && (state.getValue(RgbLedBlock.FACING).getSerializedName() + "/" + state.getValue(RgbLedBlock.LIGHT_LEVEL)).equals(expected)
                    && RgbLedBlock.pointing(state) == Direction.UP,
                    "the 0.6.0 state " + entry + " loads as a floor lamp, " + expected + ": " + state);
        });
    }

    /**
     * Stands at {@code stand}, places a lamp from the hand on the {@code face} of {@code support}, and waits until it is
     * there on both sides and attached.
     */
    private static BlockPos placeOn(GameTestSession session, BlockPos support, Direction face, BlockPos stand) {
        session.teleport(stand);
        session.context().waitTicks(TELEPORT_TICKS);
        session.hold(MineqttBlocks.RGB_LED_BLOCK.get().asItem());
        BlockPos lamp = support.relative(face);
        session.aim(support, face);
        session.pressUse();
        session.awaitServer(s -> s.overworld().getBlockEntity(lamp) instanceof DeviceHost host && host.deviceLink().attached());
        session.context().waitFor(mc -> mc.level.getBlockState(lamp).is(MineqttBlocks.RGB_LED_BLOCK.get()));
        return lamp;
    }

    /** The placed lamp's state, outline and bulb on the server and the client. */
    private static void mounted(GameTestSession session, BlockPos lamp, AttachFace face, Direction pointing) {
        BlockState state = session.onServer(s -> s.overworld().getBlockState(lamp));
        BlockState client = session.onClient(mc -> mc.level.getBlockState(lamp));
        String where = face.getSerializedName() + " lamp at " + lamp.toShortString();
        session.check(state.getValue(RgbLedBlock.FACE) == face && RgbLedBlock.pointing(state) == pointing
                && (face != AttachFace.WALL || state.getValue(RgbLedBlock.FACING) == pointing), "the " + where + " points " + pointing + ": " + state);
        session.check(client.equals(state), "the client has the same state");
        session.check(session.playerName().equals(session.device(lamp, DeviceLink::ownerName)), "the " + where + " is the player's lamp");
        AABB bounds = session.onServer(s -> state.getShape(s.overworld(), lamp).bounds());
        session.check(reachesSupport(bounds, pointing), "its outline sits on its support and reaches 14 pixels out: " + bounds);
        session.check(bounds.equals(session.onServer(s -> state.getCollisionShape(s.overworld(), lamp).bounds())),
                "its collision shape is its outline");
        Vec3 bulb = RgbLedBlock.bulbCentre(state, lamp).subtract(Vec3.atLowerCornerOf(lamp));
        session.check(bounds.contains(bulb) && bulb.distanceTo(Vec3.atCenterOf(BlockPos.ZERO).relative(pointing, 0.1)) < EPSILON,
                "its particles rise from the bulb, inside the outline: " + bulb);
    }

    /** The outline touches the support's face and ends 14 pixels away from it, 6 pixels wide across. */
    private static boolean reachesSupport(AABB bounds, Direction pointing) {
        Direction.Axis axis = pointing.getAxis();
        double near = pointing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? bounds.min(axis) : 1 - bounds.max(axis);
        double width = 6.0 / 16.0;
        return Math.abs(near) < EPSILON && Math.abs(bounds.max(axis) - bounds.min(axis) - REACH) < EPSILON
                && Math.abs(bounds.getXsize() * bounds.getYsize() * bounds.getZsize() - width * width * REACH) < EPSILON;
    }

    /** The top of a bottom slab cannot hold the socket's middle: nothing is placed and the lamp stays in hand. */
    private static void notOnSlab(GameTestSession session) {
        session.teleport(SLAB.south(2));
        session.context().waitTicks(TELEPORT_TICKS);
        session.hold(MineqttBlocks.RGB_LED_BLOCK.get().asItem());
        session.aim(SLAB);
        session.check(session.onClient(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getDirection() == Direction.UP),
                "the crosshair is on the slab's top");
        session.pressUse();
        session.context().waitTicks(SETTLE_TICKS);
        session.check(session.onServer(s -> s.overworld().getBlockState(SLAB).is(Blocks.SMOOTH_STONE_SLAB)
                        && s.overworld().getBlockState(SLAB.above()).isAir() && session.player(s).getMainHandItem().is(MineqttBlocks.RGB_LED_BLOCK.get().asItem())),
                "no lamp stands on the top of a bottom slab, and it stays in hand");
    }

    /**
     * The side of short grass on the ground: the clicked face is the grass outline's, nothing stands behind it, and the
     * lamp replaces the grass standing on the ground, the support the player looks down to.
     */
    private static void onGrass(GameTestSession session) {
        setblock(session, GRASS, "minecraft:short_grass");
        session.teleport(GRASS.north(3));
        session.context().waitTicks(TELEPORT_TICKS);
        session.hold(MineqttBlocks.RGB_LED_BLOCK.get().asItem());
        session.aim(GRASS, Direction.NORTH);
        session.pressUse();
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(GRASS)).is(MineqttBlocks.RGB_LED_BLOCK.get()), ANSWER_TICKS),
                "a click on the side of short grass places a lamp in its place");
        BlockState state = session.onServer(s -> s.overworld().getBlockState(GRASS));
        session.check(state.getValue(RgbLedBlock.FACE) == AttachFace.FLOOR, "it stands on the ground: " + state);
    }

    private static void screenshots(GameTestSession session) {
        session.hold(Items.AIR);
        // The north and west wall lamps from the north-west, the ceiling lamp beyond them.
        look(session, WALL.north(3).west(3).below(), WALL);
        session.screenshot("lamp_mount_walls_north_west");
        // The south and east wall lamps from the south-east, the floor lamp beyond them.
        look(session, WALL.south(3).east(3).below(), WALL);
        session.screenshot("lamp_mount_walls_south_east");
        look(session, CEILING.below(3).north(2).west(), CEILING.below());
        session.screenshot("lamp_mount_ceiling");
        look(session, FLOOR_GROUND.above().north(2).east(), FLOOR_GROUND.above());
        session.screenshot("lamp_mount_floor");
        // The item in the hand is the floor model, as before.
        session.hold(MineqttBlocks.RGB_LED_BLOCK.get().asItem());
        // Until the hand has raised the item and its name has faded from above the hotbar.
        session.context().waitTicks(EQUIP_TICKS);
        session.screenshot("lamp_mount_in_hand");
        session.hold(Items.AIR);
    }

    private static void look(GameTestSession session, BlockPos stand, BlockPos at) {
        session.teleport(stand);
        session.context().waitTicks(TELEPORT_TICKS);
        session.context().runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(at)));
        session.context().waitTicks(SETTLE_TICKS);
    }

    private static void supportGone(GameTestSession session, BlockPos floor, BlockPos ceiling) {
        Item lampItem = MineqttBlocks.RGB_LED_BLOCK.get().asItem();
        // A neighbour of the north wall lamp that is not its support comes and goes.
        BlockPos beside = WALL.north().above();
        setblock(session, beside, "minecraft:stone");
        setblock(session, beside, "minecraft:air");
        session.context().waitTicks(SETTLE_TICKS);
        session.check(session.onServer(s -> s.overworld().getBlockState(WALL.north()).is(MineqttBlocks.RGB_LED_BLOCK.get())),
                "a neighbour that is not its support changes nothing");

        int before = droppedNear(session, WALL, lampItem);
        setblock(session, WALL, "minecraft:air");
        for (Direction side : SIDES) {
            BlockPos lamp = WALL.relative(side);
            gone(session, lamp, "the " + side.getSerializedName() + " wall lamp");
        }
        session.check(session.await(() -> droppedNear(session, WALL, lampItem) - before == 4, ANSWER_TICKS),
                "the four wall lamps dropped as items: " + (droppedNear(session, WALL, lampItem) - before));

        setblock(session, CEILING, "minecraft:air");
        gone(session, ceiling, "the ceiling lamp");
        session.check(session.await(() -> droppedNear(session, ceiling, lampItem) == 1, ANSWER_TICKS), "the ceiling lamp dropped as an item");

        setblock(session, FLOOR_GROUND, "minecraft:air");
        gone(session, floor, "the floor lamp");
        session.check(session.await(() -> droppedNear(session, floor, lampItem) == 1, ANSWER_TICKS), "the floor lamp dropped as an item");
    }

    /**
     * A lamp standing on another lamp has no support under its socket (the lower lamp's top is 2 pixels short). Switching
     * the lower lamp changes its light level, which updates the upper lamp from its support side; the upper lamp stays a
     * device. A side neighbour changes nothing either. When the lower lamp goes away, the upper one pops off.
     */
    private static void legacyStack(GameTestSession session) {
        Item lampItem = MineqttBlocks.RGB_LED_BLOCK.get().asItem();
        legacyLamp(session, STACK_LOWER);
        legacyLamp(session, STACK_UPPER);
        session.consoleCommand("mineqtt devices set " + slug(session, STACK_LOWER) + " {\"state\":\"ON\"}");
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(STACK_LOWER).getValue(RgbLedBlock.LIGHT_LEVEL)) == 15,
                ANSWER_TICKS), "the lower lamp of the stack switches on");
        session.consoleCommand("mineqtt devices set " + slug(session, STACK_LOWER) + " {\"state\":\"OFF\"}");
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(STACK_LOWER).getValue(RgbLedBlock.LIGHT_LEVEL)) == 0,
                ANSWER_TICKS), "and off again");
        sideNeighbour(session, STACK_UPPER);
        stays(session, STACK_UPPER, "the lamp standing on a lamp stays while the lamp below switches and a side neighbour comes and goes");

        int before = droppedNear(session, STACK_UPPER, lampItem);
        setblock(session, STACK_LOWER, "minecraft:air");
        gone(session, STACK_UPPER, "the lamp that stood on a lamp");
        session.check(session.await(() -> droppedNear(session, STACK_UPPER, lampItem) - before == 1, ANSWER_TICKS),
                "the lamp that stood on a lamp dropped as an item: " + (droppedNear(session, STACK_UPPER, lampItem) - before));
    }

    /**
     * A floor lamp floating above the ground stays while a side neighbour comes and goes and while a block below holds it;
     * when that block goes away, the lamp pops off.
     */
    private static void legacyFloating(GameTestSession session) {
        Item lampItem = MineqttBlocks.RGB_LED_BLOCK.get().asItem();
        legacyLamp(session, FLOATING);
        sideNeighbour(session, FLOATING);
        stays(session, FLOATING, "the floating lamp stays while a side neighbour comes and goes");
        setblock(session, FLOATING.below(), "minecraft:stone");
        session.context().waitTicks(SETTLE_TICKS);
        stays(session, FLOATING, "a block set below the floating lamp holds it");

        int before = droppedNear(session, FLOATING, lampItem);
        setblock(session, FLOATING.below(), "minecraft:air");
        gone(session, FLOATING, "the lamp that floated");
        session.check(session.await(() -> droppedNear(session, FLOATING, lampItem) - before == 1, ANSWER_TICKS),
                "the lamp that floated dropped as an item: " + (droppedNear(session, FLOATING, lampItem) - before));
    }

    /** A floor lamp set by command, which skips the survival check as 0.6.0's placement did, once it is a device. */
    private static void legacyLamp(GameTestSession session, BlockPos lamp) {
        setblock(session, lamp, "mineqtt:rgb_led_block[face=floor]");
        session.awaitServer(s -> s.overworld().getBlockEntity(lamp) instanceof DeviceHost host && host.deviceLink().attached());
    }

    /** A stone block comes and goes on the east side of {@code lamp}. */
    private static void sideNeighbour(GameTestSession session, BlockPos lamp) {
        setblock(session, lamp.east(), "minecraft:stone");
        setblock(session, lamp.east(), "minecraft:air");
        session.context().waitTicks(SETTLE_TICKS);
    }

    /** The lamp is still there on both sides and still a device. */
    private static void stays(GameTestSession session, BlockPos lamp, String what) {
        session.check(session.onServer(s -> s.overworld().getBlockState(lamp).is(MineqttBlocks.RGB_LED_BLOCK.get()))
                && session.onClient(mc -> mc.level.getBlockState(lamp).is(MineqttBlocks.RGB_LED_BLOCK.get()))
                && session.onRuntime(runtime -> runtime.registry().records().stream().anyMatch(record -> record.pos().pos().equals(lamp))), what);
    }

    /** The lamp popped off on both sides and left the registry. */
    private static void gone(GameTestSession session, BlockPos lamp, String what) {
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(lamp).isAir())
                && session.onClient(mc -> mc.level.getBlockState(lamp).isAir()), ANSWER_TICKS), what + " pops off when its support goes away");
        session.check(session.await(() -> session.onRuntime(runtime -> runtime.registry().records().stream()
                .noneMatch(record -> record.pos().pos().equals(lamp))), ANSWER_TICKS), what + " left the registry");
    }

    // Item entities of this item within three blocks of pos, by count: a ceiling lamp's drop falls to the ground.
    private static int droppedNear(GameTestSession session, BlockPos pos, Item item) {
        return session.onServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3), entity -> entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum());
    }

    private static String slug(GameTestSession session, BlockPos pos) {
        return session.onRuntime(runtime -> runtime.registry().records().stream().filter(record -> record.pos().pos().equals(pos))
                .map(DeviceRecord::slug).findFirst().orElseThrow(() -> new AssertionError("No device at " + pos)));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }
}

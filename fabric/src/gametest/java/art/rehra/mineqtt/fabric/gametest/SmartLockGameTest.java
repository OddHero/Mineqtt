package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.SmartLockBlock;
import art.rehra.mineqtt.blocks.entities.SmartLockBlockEntity;
import art.rehra.mineqtt.compat.ContainerLocks;
import art.rehra.mineqtt.compat.Screens;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.SmartLock;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * The Smart Lock. The player (no operator) puts it onto an oak door with its item: the door does not swing, the plate
 * sits on the door's face aimed at it, the player owns it, it is private and locked. The owner opens and closes the
 * locked door by hand. A command and an action unlock and lock it; locking closes the door the owner left open. A
 * redstone block opens the locked door, which the lock closes again the next tick and counts as an attempt by
 * redstone; unlocked, the signal opens it. A double door whose plate nobody owns refuses the player at the door
 * without a plate, with vanilla's "is locked" message, and stays shut; claimed, the player passes. On a chest the item
 * puts the plate on the chest; the owner opens the locked chest, which carries vanilla's lock, gone once unlocked. A
 * chest whose plate nobody owns refuses the player with vanilla's message and counts an attempt by hand; as an
 * operator the player passes, unless smartLock.opsBypass is off; with public access everybody passes. The item on
 * stone or on a target that already has a lock places nothing. A stranger cannot break a plate. Breaking the door pops
 * its plate, which drops its item, and the device goes. With a broker: Home Assistant's payload_unlock and payload_lock
 * on /set unlock and lock the door's lock, the retained state follows, and a refused hand publishes the attempt event
 * with how and on what, never who.
 */
public final class SmartLockGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    // An oak door facing north: its panel is on the south side, where the player stands.
    private static final BlockPos DOOR = new BlockPos(0, Y, 18);
    private static final BlockPos DOOR_PLATE = DOOR.south();
    private static final BlockPos DOOR_STAND = DOOR.south(2);
    private static final BlockPos DOOR_POWER = DOOR.west();
    // A double door: the left door hinged left, the right one hinged right; the plate is on the right door.
    private static final BlockPos LEFT_DOOR = new BlockPos(4, Y, 18);
    private static final BlockPos RIGHT_DOOR = LEFT_DOOR.east();
    private static final BlockPos PAIR_PLATE = RIGHT_DOOR.south();
    private static final BlockPos PAIR_STAND = LEFT_DOOR.south(2);
    private static final BlockPos CHEST = new BlockPos(8, Y, 18);
    private static final BlockPos CHEST_PLATE = CHEST.south();
    private static final BlockPos CHEST_STAND = CHEST.south(2);
    private static final BlockPos OTHER_CHEST = new BlockPos(12, Y, 18);
    private static final BlockPos OTHER_PLATE = OTHER_CHEST.south();
    private static final BlockPos OTHER_STAND = OTHER_CHEST.south(2);
    private static final BlockPos STONE = new BlockPos(16, Y, 18);
    private static final int ANSWER_TICKS = 80;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "smart_lock", SmartLockGameTest::run);
    }

    private static void run(GameTestSession session) {
        boolean opsBypass = session.onServer(s -> MineQTTConfig.smartLockOpsBypass);
        try {
            setblock(session, DOOR, "minecraft:oak_door[facing=north,half=lower,hinge=left]");
            setblock(session, DOOR.above(), "minecraft:oak_door[facing=north,half=upper,hinge=left]");
            setblock(session, LEFT_DOOR, "minecraft:oak_door[facing=north,half=lower,hinge=left]");
            setblock(session, LEFT_DOOR.above(), "minecraft:oak_door[facing=north,half=upper,hinge=left]");
            setblock(session, RIGHT_DOOR, "minecraft:oak_door[facing=north,half=lower,hinge=right]");
            setblock(session, RIGHT_DOOR.above(), "minecraft:oak_door[facing=north,half=upper,hinge=right]");
            setblock(session, CHEST, "minecraft:chest[facing=south]");
            setblock(session, OTHER_CHEST, "minecraft:chest[facing=south]");
            setblock(session, STONE, "minecraft:stone");
            String door = placeOnDoor(session);
            ownerUsesDoor(session);
            commands(session, door);
            redstone(session, door);
            doublePair(session);
            String chest = chest(session);
            String other = strangerChest(session);
            refusals(session);
            Optional<BrokerConfig> broker = session.broker();
            if (broker.isEmpty()) {
                session.skip("Home Assistant's lock payloads and the attempt event on the broker", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            } else {
                published(session, door, other, broker.get());
            }
            protection(session);
            breakTarget(session, chest);
        } finally {
            session.onServer(s -> {
                MineQTTConfig.smartLockOpsBypass = opsBypass;
                return null;
            });
            session.consoleCommand("deop " + session.playerName());
        }
    }

    /** The item on the door's face: the door stays shut, the plate sits there aimed at it, owned by the player, private and locked. */
    private static String placeOnDoor(GameTestSession session) {
        session.teleport(DOOR_STAND);
        session.context().waitTicks(20);
        session.hold(MineqttItems.SmartLock.get());
        session.aim(DOOR, Direction.SOUTH);
        session.pressUse();
        session.awaitServer(s -> s.overworld().getBlockEntity(DOOR_PLATE) instanceof DeviceHost host && host.deviceLink().attached());
        BlockState plate = session.onServer(s -> s.overworld().getBlockState(DOOR_PLATE));
        session.check(plate.getBlock() instanceof SmartLockBlock && plate.getValue(SmartLockBlock.FACE) == AttachFace.WALL
                        && plate.getValue(SmartLockBlock.FACING) == Direction.SOUTH && SmartLockBlock.support(DOOR_PLATE, plate).equals(DOOR),
                "the plate sits on the door's face, aimed at the door (" + plate + ")");
        session.check(!open(session, DOOR), "using the item on the door does not swing it");
        session.check(session.playerId().equals(session.device(DOOR_PLATE, DeviceLink::owner)) && session.device(DOOR_PLATE, DeviceLink::access) == AccessMode.PRIVATE,
                "the player owns it and it is private");
        JsonObject state = state(session, DOOR_PLATE);
        session.check(state.get("locked").getAsBoolean() && state.get("state").getAsString().equals("locked") && state.get("target").getAsString().equals("minecraft:oak_door")
                && state.get("status").getAsString().equals("ok") && !state.get("paired").getAsBoolean(), "it is locked and locks the oak door (" + state + ")");
        session.context().waitFor(mc -> mc.level.getBlockState(DOOR_PLATE).getBlock() instanceof SmartLockBlock && mc.level.getBlockState(DOOR_PLATE).getValue(SmartLockBlock.LOCKED));
        session.aim(DOOR_PLATE);
        session.screenshot("smart_lock_door");
        return session.device(DOOR_PLATE, DeviceLink::slug);
    }

    /** The owner opens and closes the locked door by hand; the lock reads open while it stands open. */
    private static void ownerUsesDoor(GameTestSession session) {
        session.hold(Items.AIR);
        session.aim(DOOR.above(), Direction.SOUTH);
        session.pressUse();
        session.check(session.await(() -> open(session, DOOR), ANSWER_TICKS), "the owner opens the locked door");
        session.check(session.await(() -> state(session, DOOR_PLATE).get("state").getAsString().equals("open"), ANSWER_TICKS)
                && state(session, DOOR_PLATE).get("locked").getAsBoolean(), "it stays locked and reads open (" + state(session, DOOR_PLATE) + ")");
        session.context().waitTicks(10);
        session.check(open(session, DOOR), "the lock does not close a door its owner opened");
        session.context().waitFor(mc -> mc.level.getBlockState(DOOR).getValue(DoorBlock.OPEN));
        // The open door's panel stands on its hinge side now: the player clicks into the door's space.
        session.aim(DOOR.above());
        session.pressUse();
        session.check(session.await(() -> !open(session, DOOR), ANSWER_TICKS), "and closes it");
        session.check(session.await(() -> state(session, DOOR_PLATE).get("state").getAsString().equals("locked"), ANSWER_TICKS), "it reads locked again");
    }

    /** A command unlocks, an action locks; locking closes the door left open; the toggle action and the state follow. */
    private static void commands(GameTestSession session, String slug) {
        session.consoleCommand("mineqtt devices set " + slug + " {\"locked\":false}");
        session.check(session.await(() -> !locked(session, DOOR_PLATE) && state(session, DOOR_PLATE).get("state").getAsString().equals("unlocked"), ANSWER_TICKS),
                "a command unlocks it (" + state(session, DOOR_PLATE) + ")");
        session.context().waitFor(mc -> !mc.level.getBlockState(DOOR_PLATE).getValue(SmartLockBlock.LOCKED));
        session.aim(DOOR.above(), Direction.SOUTH);
        session.pressUse();
        session.check(session.await(() -> open(session, DOOR), ANSWER_TICKS), "the unlocked door opens");
        session.consoleCommand("mineqtt devices action " + slug + " lock");
        session.check(session.await(() -> locked(session, DOOR_PLATE) && !open(session, DOOR), ANSWER_TICKS), "the lock action locks it and closes the door");
        session.consoleCommand("mineqtt devices action " + slug + " toggle");
        session.check(session.await(() -> !locked(session, DOOR_PLATE), ANSWER_TICKS), "toggle unlocks");
        session.consoleCommand("mineqtt devices action " + slug + " toggle");
        session.check(session.await(() -> locked(session, DOOR_PLATE), ANSWER_TICKS), "and locks again");
    }

    /** Redstone opening the locked door is undone the next tick and counted; unlocked, the signal opens it. */
    private static void redstone(GameTestSession session, String slug) {
        int before = attempts(session, DOOR_PLATE);
        setblock(session, DOOR_POWER, "minecraft:redstone_block");
        session.check(session.await(() -> attempts(session, DOOR_PLATE) == before + 1, ANSWER_TICKS) && !open(session, DOOR),
                "a redstone block cannot open the locked door: it is closed again and counted (" + state(session, DOOR_PLATE) + ")");
        session.context().waitTicks(10);
        session.check(!open(session, DOOR) && attempts(session, DOOR_PLATE) == before + 1, "it stays shut while the signal holds");
        session.consoleCommand("mineqtt devices action " + slug + " unlock");
        session.check(session.await(() -> !locked(session, DOOR_PLATE) && open(session, DOOR), ANSWER_TICKS), "unlocked, the signal that still holds opens it");
        setblock(session, DOOR_POWER, "minecraft:air");
        session.check(session.await(() -> !open(session, DOOR), ANSWER_TICKS), "and its end closes it");
        session.consoleCommand("mineqtt devices action " + slug + " lock");
        session.check(session.await(() -> locked(session, DOOR_PLATE), ANSWER_TICKS), "locked again");
    }

    /**
     * A double door locks as a pair: with a plate nobody owns on the right door the player is refused at the left one,
     * with vanilla's message, and it stays shut; claimed, the player opens it.
     */
    private static void doublePair(GameTestSession session) {
        setblock(session, PAIR_PLATE, "mineqtt:smart_lock[face=wall,facing=south]");
        session.awaitServer(s -> s.overworld().getBlockEntity(PAIR_PLATE) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.await(() -> state(session, PAIR_PLATE).get("paired").getAsBoolean(), ANSWER_TICKS), "the plate on the right door knows the pair");
        session.teleport(PAIR_STAND);
        session.context().waitTicks(10);
        session.context().waitFor(mc -> mc.level.getBlockState(PAIR_PLATE).getBlock() instanceof SmartLockBlock);
        int before = attempts(session, PAIR_PLATE);
        int mark = ChatLog.mark();
        session.aim(LEFT_DOOR.above(), Direction.SOUTH);
        session.pressUse();
        Optional<ChatLog.Line> line = session.awaitChat(mark, l -> l.text().contains("is locked"), ANSWER_TICKS);
        session.check(line.filter(ChatLog.Line::actionBar).isPresent(), "the player is refused at the left door with vanilla's message (" + line + ")");
        session.check(session.await(() -> attempts(session, PAIR_PLATE) == before + 1, ANSWER_TICKS), "it counts an attempt");
        session.context().waitTicks(5);
        session.check(!open(session, LEFT_DOOR) && !open(session, RIGHT_DOOR), "both doors stay shut");
        session.check(session.onClient(mc -> !mc.level.getBlockState(LEFT_DOOR).getValue(DoorBlock.OPEN)), "and the player sees the door shut");
        session.check(session.onServer(s -> {
            var blockEntity = s.overworld().getBlockEntity(PAIR_PLATE);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the plate");
        session.aim(LEFT_DOOR.above(), Direction.SOUTH);
        session.pressUse();
        session.check(session.await(() -> open(session, LEFT_DOOR), ANSWER_TICKS), "its owner opens the left door of the pair");
    }

    /** On a chest: the plate on the chest; the owner opens the locked chest, which carries vanilla's lock until unlocked. */
    private static String chest(GameTestSession session) {
        session.teleport(CHEST_STAND);
        session.context().waitTicks(10);
        session.hold(MineqttItems.SmartLock.get());
        session.aim(CHEST, Direction.SOUTH);
        session.pressUse();
        session.awaitServer(s -> s.overworld().getBlockEntity(CHEST_PLATE) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onClient(mc -> !(Screens.current() instanceof AbstractContainerScreen<?>)), "using the item on the chest does not open it");
        String slug = session.device(CHEST_PLATE, DeviceLink::slug);
        session.check(session.await(() -> vanillaLocked(session, CHEST), ANSWER_TICKS), "the locked chest carries vanilla's lock");
        session.hold(Items.AIR);
        // The plate now covers the middle of the chest's face: the player clicks its top.
        session.aim(CHEST, Direction.UP);
        session.pressUse();
        session.check(session.awaitClient(mc -> Screens.current() instanceof AbstractContainerScreen<?>, ANSWER_TICKS), "its owner opens the locked chest");
        session.check(vanillaLocked(session, CHEST), "and its lock is back at once");
        session.closeScreen();
        session.consoleCommand("mineqtt devices action " + slug + " unlock");
        session.check(session.await(() -> !vanillaLocked(session, CHEST), ANSWER_TICKS), "unlocked, vanilla's lock comes off");
        session.consoleCommand("mineqtt devices action " + slug + " lock");
        session.check(session.await(() -> vanillaLocked(session, CHEST), ANSWER_TICKS), "locked, it is back");
        return slug;
    }

    /**
     * A chest whose plate nobody owns refuses the player with vanilla's message and counts an attempt by hand; an operator
     * passes unless smartLock.opsBypass is off; public access lets everybody in.
     */
    private static String strangerChest(GameTestSession session) {
        setblock(session, OTHER_PLATE, "mineqtt:smart_lock[face=wall,facing=south]");
        session.awaitServer(s -> s.overworld().getBlockEntity(OTHER_PLATE) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.await(() -> vanillaLocked(session, OTHER_CHEST), ANSWER_TICKS), "a lock set by command locks its chest");
        String slug = session.device(OTHER_PLATE, DeviceLink::slug);
        session.teleport(OTHER_STAND);
        session.context().waitTicks(10);
        session.context().waitFor(mc -> mc.level.getBlockState(OTHER_PLATE).getBlock() instanceof SmartLockBlock);
        int before = attempts(session, OTHER_PLATE);
        int mark = ChatLog.mark();
        session.aim(OTHER_CHEST, Direction.UP);
        session.pressUse();
        Optional<ChatLog.Line> line = session.awaitChat(mark, l -> l.text().equals("Chest is locked!"), ANSWER_TICKS);
        session.check(line.filter(ChatLog.Line::actionBar).isPresent(), "vanilla refuses the stranger: Chest is locked! (" + ChatLog.since(mark) + ")");
        session.check(session.onClient(mc -> !(Screens.current() instanceof AbstractContainerScreen<?>)), "no chest screen opens");
        session.check(session.await(() -> attempts(session, OTHER_PLATE) == before + 1, ANSWER_TICKS), "an attempt is counted");

        session.consoleCommand("op " + session.playerName());
        session.context().waitTicks(5);
        session.aim(OTHER_CHEST, Direction.UP);
        session.pressUse();
        session.check(session.awaitClient(mc -> Screens.current() instanceof AbstractContainerScreen<?>, ANSWER_TICKS), "an operator passes by default");
        session.closeScreen();
        session.onServer(s -> {
            MineQTTConfig.smartLockOpsBypass = false;
            return null;
        });
        int refused = ChatLog.mark();
        session.aim(OTHER_CHEST, Direction.UP);
        session.pressUse();
        session.check(session.awaitChat(refused, l -> l.text().equals("Chest is locked!"), ANSWER_TICKS).isPresent()
                && session.onClient(mc -> !(Screens.current() instanceof AbstractContainerScreen<?>)), "with smartLock.opsBypass off the operator is refused");
        session.onServer(s -> {
            MineQTTConfig.smartLockOpsBypass = true;
            return null;
        });
        session.consoleCommand("deop " + session.playerName());
        session.consoleCommand("mineqtt devices access " + slug + " public");
        session.check(session.await(() -> session.device(OTHER_PLATE, DeviceLink::access) == AccessMode.PUBLIC, ANSWER_TICKS), "the lock is made public");
        session.context().waitTicks(5);
        session.aim(OTHER_CHEST, Direction.UP);
        session.pressUse();
        session.check(session.awaitClient(mc -> Screens.current() instanceof AbstractContainerScreen<?>, ANSWER_TICKS), "a public lock lets the player in");
        session.closeScreen();
        session.consoleCommand("mineqtt devices access " + slug + " private");
        session.check(session.await(() -> session.device(OTHER_PLATE, DeviceLink::access) == AccessMode.PRIVATE, ANSWER_TICKS), "and private again");
        return slug;
    }

    /** The item on stone, and on a chest that already has a lock, places nothing. */
    private static void refusals(GameTestSession session) {
        session.teleport(STONE.south(2));
        session.context().waitTicks(10);
        session.hold(MineqttItems.SmartLock.get());
        int mark = ChatLog.mark();
        session.aim(STONE, Direction.SOUTH);
        session.pressUse();
        session.check(session.awaitChat(mark, l -> l.text().startsWith("Use the Smart Lock on"), ANSWER_TICKS).isPresent(), "the item on stone is refused with a hint");
        session.context().waitTicks(5);
        session.check(session.onServer(s -> s.overworld().getBlockState(STONE.south()).isAir()), "and places nothing");
        session.teleport(OTHER_STAND);
        session.context().waitTicks(10);
        int again = ChatLog.mark();
        session.aim(OTHER_CHEST, Direction.UP);
        session.pressUse();
        session.check(session.awaitChat(again, l -> l.text().equals("This already has a Smart Lock"), ANSWER_TICKS).isPresent()
                && session.onServer(s -> s.overworld().getBlockState(OTHER_CHEST.above()).isAir()), "a second lock on a locked chest is refused");
        session.hold(Items.AIR);
    }

    /** With a broker: Home Assistant's lock payloads on /set, the retained state, and the attempt event. */
    private static void published(GameTestSession session, String door, String other, BrokerConfig broker) {
        String doorTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/" + door;
        String otherTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/" + other;
        try (BrokerProbe probe = BrokerProbe.connect(broker)) {
            probe.publish(doorTopic + "/set", "{\"locked\":false}", false);
            session.check(session.await(() -> !locked(session, DOOR_PLATE), ANSWER_TICKS), "payload_unlock on /set unlocks the door's lock");
            session.context().waitTicks(20);
            Optional<JsonObject> retained = probe.retained(doorTopic).map(text -> JsonParser.parseString(text).getAsJsonObject());
            session.check(retained.filter(json -> json.get("state").getAsString().equals("unlocked") && !json.get("locked").getAsBoolean()).isPresent(),
                    "the retained state reads unlocked (" + retained + ")");
            probe.publish(doorTopic + "/set", "{\"locked\":true}", false);
            session.check(session.await(() -> locked(session, DOOR_PLATE), ANSWER_TICKS), "payload_lock locks it again");
        }
        // One probe per listener: two subscriptions on one probe share a stream.
        try (BrokerProbe probe = BrokerProbe.connect(broker); BrokerProbe.Listener events = probe.listen(otherTopic + "/action")) {
            session.context().waitTicks(30);
            session.teleport(OTHER_STAND);
            session.context().waitTicks(10);
            session.hold(Items.AIR);
            session.aim(OTHER_CHEST, Direction.UP);
            session.pressUse();
            Optional<String> event = Optional.empty();
            for (int tick = 0; tick < ANSWER_TICKS && event.isEmpty(); tick++) {
                session.context().waitTick();
                event = events.nextLive();
            }
            Optional<JsonObject> json = event.map(text -> JsonParser.parseString(text).getAsJsonObject());
            session.check(json.filter(e -> e.get("event_type").getAsString().equals(SmartLock.ATTEMPT) && e.get("method").getAsString().equals("hand")
                    && e.get("target").getAsString().equals("container") && e.keySet().size() == 3).isPresent(), "a refused hand publishes the attempt (" + event + ")");
            session.check(event.filter(text -> !text.contains(session.playerName()) && !text.contains(session.playerId().toString())).isPresent(),
                    "the attempt names nobody");
        }
    }

    /** A stranger cannot break a plate; the plate stays. */
    private static void protection(GameTestSession session) {
        session.teleport(OTHER_STAND);
        session.context().waitTicks(10);
        int mark = ChatLog.mark();
        boolean destroyed = session.onServer(s -> session.player(s).gameMode.destroyBlock(OTHER_PLATE));
        session.check(!destroyed && session.onServer(s -> s.overworld().getBlockState(OTHER_PLATE).getBlock() instanceof SmartLockBlock),
                "a player who may not configure the plate cannot break it");
        session.check(session.awaitChat(mark, l -> l.text().startsWith("Only its owner"), ANSWER_TICKS).isPresent(), "and is told so");
    }

    /** Breaking the locked chest pops its plate, which drops its item; the device goes. */
    private static void breakTarget(GameTestSession session, String slug) {
        session.consoleCommand("setblock " + CHEST.getX() + " " + CHEST.getY() + " " + CHEST.getZ() + " minecraft:air destroy");
        session.check(session.await(() -> session.onServer(s -> s.overworld().getBlockState(CHEST_PLATE).isAir()), ANSWER_TICKS), "breaking the chest pops its plate");
        session.check(session.await(() -> session.onServer(s -> !s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(CHEST_PLATE).inflate(2),
                item -> item.getItem().is(MineqttItems.SmartLock.get())).isEmpty()), ANSWER_TICKS), "the plate drops its item");
        session.check(session.await(() -> session.onRuntime(runtime -> runtime.registry().records().stream().noneMatch(record -> record.slug().equals(slug))), ANSWER_TICKS),
                "the device goes with it");
    }

    private static boolean open(GameTestSession session, BlockPos door) {
        return session.onServer(s -> s.overworld().getBlockState(door).getValue(DoorBlock.OPEN));
    }

    private static boolean locked(GameTestSession session, BlockPos plate) {
        return session.onServer(s -> s.overworld().getBlockEntity(plate) instanceof SmartLockBlockEntity lock && lock.locked());
    }

    private static int attempts(GameTestSession session, BlockPos plate) {
        return session.onServer(s -> s.overworld().getBlockEntity(plate) instanceof SmartLockBlockEntity lock ? lock.attempts() : -1);
    }

    private static boolean vanillaLocked(GameTestSession session, BlockPos chest) {
        return session.onServer(s -> s.overworld().getBlockEntity(chest) instanceof BaseContainerBlockEntity container && ContainerLocks.locked(container));
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static JsonObject state(GameTestSession session, BlockPos pos) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(pos) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }
}

package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.nbt.CompoundTag;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Owners are matched by UUID (M2 plan D7). The joining player plays an alt account that carries the name a main account
 * had before it was renamed: a timer the main account owns by the UUID 0.6.0 and 0.7.0 saved, but whose saved owner name
 * is the alt's name, does not make the alt its owner when it is adopted as a device (M4 plan D4). A private device of the
 * main account stays closed to the alt. A v1 timer or lamp that only names its owner gets the owner's UUID when it attaches
 * while the player of exactly that name is online, and that player owns it. A v1 owner name that differs from the
 * player's only in case is another offline account, so neither the timer nor the lamp is bound to the player.
 */
public final class OwnerUuidGameTest implements FabricClientGameTest {
    /** The main account, renamed since it placed the blocks under the name the joining player has now. */
    private static final UUID MAIN = UUID.fromString("7b1d2c3e-4f50-4a61-8b72-9c83d4e5f601");
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos TIMER_MAIN = new BlockPos(2, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos TIMER_V1 = new BlockPos(2, GameTestSession.GROUND_Y + 1, 2);
    private static final BlockPos DEVICE_MAIN = new BlockPos(-2, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos DEVICE_V1 = new BlockPos(-2, GameTestSession.GROUND_Y + 1, 2);
    private static final BlockPos TIMER_OTHER_CASE = new BlockPos(4, GameTestSession.GROUND_Y + 1, 2);
    private static final BlockPos DEVICE_OTHER_CASE = new BlockPos(-4, GameTestSession.GROUND_Y + 1, 2);
    private static final int NO_SCREEN_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "owner-uuid", OwnerUuidGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        boolean onlineMode = session.onServer(MinecraftServer::usesAuthentication);
        session.check(!onlineMode, "the test server does not authenticate players (offline names)");
        session.check(UUIDUtil.createOfflinePlayerUUID(session.playerName()).equals(session.playerId()),
                "the player carries the UUID offline mode derives from their name");
        String name = session.playerName();

        // A 0.7.0 timer the main account placed under the name the alt has now: its saved owner UUID makes the device the main account's.
        setblock(session, TIMER_MAIN, "mineqtt:interval_timer", "{ownerName:\"" + name + "\",ownerUuid:" + intArray(MAIN) + ",intervalValue:20L}");
        session.awaitServer(s -> s.overworld().getBlockEntity(TIMER_MAIN) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(MAIN.equals(session.device(TIMER_MAIN, link -> link.owner())), "the saved owner UUID becomes the timer device's owner");
        session.check(!session.onServer(s -> DeviceAccess.isOwner(session.player(s), s.overworld().getBlockEntity(TIMER_MAIN))),
                "the alt carrying the old owner name does not own the main account's timer");
        // Without a claim mod a device of the default access stays open to everyone, so the screen still opens.
        TabbedMqttScreen screen = session.openBlock(TIMER_MAIN);
        String timerStatus = screen.getMenu().deviceStatus();
        session.check(timerStatus != null && !timerStatus.contains("not verified"), "a known owner needs no verification (" + timerStatus + ")");
        session.closeScreen();

        // A v1 timer that only names its owner: its attach binds the player of that name.
        setblock(session, TIMER_V1, "mineqtt:interval_timer", "{ownerName:\"" + name + "\",intervalValue:20L}");
        session.awaitServer(s -> s.overworld().getBlockEntity(TIMER_V1) instanceof DeviceHost host && host.deviceLink().owner() != null);
        session.check(session.playerId().equals(session.device(TIMER_V1, link -> link.owner())), "the v1 owner name resolves to the player's UUID at the attach");
        session.check(session.onServer(s -> DeviceAccess.isOwner(session.player(s), s.overworld().getBlockEntity(TIMER_V1))), "the player owns the v1 timer by UUID");
        CompoundTag saved = session.onServer(s -> s.overworld().getBlockEntity(TIMER_V1).saveWithoutMetadata(s.registryAccess()));
        session.check(saved.getCompound("mineqtt").map(data -> data.contains("owner")).orElse(false) && !saved.contains("intervalValue"),
                "the owner UUID is saved in the device's compound, the v1 keys are gone (" + saved + ")");

        // A private device of the main account: the alt may neither use nor configure it.
        setblock(session, DEVICE_MAIN, "mineqtt:rgb_led_block", "{mineqtt:{v:2,id:" + intArray(UUID.randomUUID())
                + ",type:\"mineqtt:smart_lamp\",owner:" + intArray(MAIN) + ",ownerName:\"" + name + "\",access:\"private\"}}");
        session.awaitServer(s -> s.overworld().getBlockEntity(DEVICE_MAIN) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            BlockEntity lamp = s.overworld().getBlockEntity(DEVICE_MAIN);
            return !DeviceAccess.isOwner(session.player(s), lamp) && !DeviceAccess.check(session.player(s), lamp, MineqttPermission.USE)
                    && !DeviceAccess.check(session.player(s), lamp, MineqttPermission.CONFIGURE);
        }), "the alt may neither use nor configure the main account's private lamp");
        session.aim(DEVICE_MAIN);
        // Sneak + right-click asks for the screen; a plain right-click would ask to switch the lamp.
        session.sneakUse();
        session.context().waitTicks(NO_SCREEN_TICKS);
        session.check(session.onClient(mc -> !(mc.gui.screen() instanceof TabbedMqttScreen)), "the private lamp's screen does not open for the alt");

        // A v1 lamp that only names its owner: its attach binds the player of that name.
        setblock(session, DEVICE_V1, "mineqtt:rgb_led_block", "{ownerName:\"" + name + "\"}");
        session.awaitServer(s -> s.overworld().getBlockEntity(DEVICE_V1) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.playerId().equals(session.device(DEVICE_V1, link -> link.owner())), "the v1 lamp's owner name resolves to the player");
        screen = session.openBlock(DEVICE_V1);
        String status = screen.getMenu().deviceStatus();
        session.check(status != null && !status.contains("not verified"), "the v1 lamp's owner is verified (" + status + ")");
        session.check(screen.getMenu().configurable(), "the player configures their v1 lamp");
        session.screenshot("owner_uuid_v1_lamp");
        session.closeScreen();

        // v1 blocks of the account whose name differs from the player's only in case: offline mode tells them apart.
        String otherCase = otherCase(name);
        setblock(session, TIMER_OTHER_CASE, "mineqtt:interval_timer", "{ownerName:\"" + otherCase + "\",intervalValue:20L}");
        setblock(session, DEVICE_OTHER_CASE, "mineqtt:rgb_led_block", "{ownerName:\"" + otherCase + "\"}");
        session.awaitServer(s -> s.overworld().getBlockEntity(TIMER_OTHER_CASE) instanceof DeviceHost timer && timer.deviceLink().attached()
                && s.overworld().getBlockEntity(DEVICE_OTHER_CASE) instanceof DeviceHost host && host.deviceLink().attached());
        // The owner lookups run on the next server tick; give them time to bind someone they must not.
        session.context().waitTicks(NO_SCREEN_TICKS);
        session.check(session.onServer(s -> !DeviceAccess.isOwner(session.player(s), s.overworld().getBlockEntity(TIMER_OTHER_CASE)))
                        && session.device(TIMER_OTHER_CASE, link -> link.owner()) == null,
                "a v1 timer of " + otherCase + " is not bound to " + name);
        session.check(session.onServer(s -> !DeviceAccess.isOwner(session.player(s), s.overworld().getBlockEntity(DEVICE_OTHER_CASE)))
                && session.device(DEVICE_OTHER_CASE, link -> link.owner()) == null, "a v1 lamp of " + otherCase + " is not bound to " + name);
    }

    /** The name with its case flipped: another offline account, whose derived UUID differs. */
    private static String otherCase(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(name) ? name.toUpperCase(Locale.ROOT) : lower;
    }

    /** Sets the block with its block entity data as the console, and waits until the client has the block too. */
    private static void setblock(GameTestSession session, BlockPos pos, String block, String data) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block + data);
        session.context().waitFor(mc -> !mc.level.getBlockState(pos).isAir());
    }

    /** A UUID as an SNBT int array, the way block entity data stores it. */
    private static String intArray(UUID id) {
        return Arrays.toString(UUIDUtil.uuidToIntArray(id)).replace("[", "[I;").replace(" ", "");
    }
}

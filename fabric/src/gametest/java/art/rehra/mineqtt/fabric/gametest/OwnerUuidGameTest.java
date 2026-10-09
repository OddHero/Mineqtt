package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
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
 * had before it was renamed: blocks that main account owns by UUID, but whose saved owner name is the alt's name, do not
 * make the alt their owner. A private device of the main account stays closed to the alt. A v1 block that only names its
 * owner gets the owner's UUID when it loads while the player of exactly that name is online, and that player owns the
 * block and its v1 device twin. A v1 owner name that differs from the player's only in case is another offline account,
 * so neither its block nor its device is bound to the player, not even by their click.
 */
public final class OwnerUuidGameTest implements FabricClientGameTest {
    /** The main account, renamed since it placed the blocks under the name the joining player has now. */
    private static final UUID MAIN = UUID.fromString("7b1d2c3e-4f50-4a61-8b72-9c83d4e5f601");
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos LEGACY_MAIN = new BlockPos(2, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos LEGACY_V1 = new BlockPos(2, GameTestSession.GROUND_Y + 1, 2);
    private static final BlockPos DEVICE_MAIN = new BlockPos(-2, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos DEVICE_V1 = new BlockPos(-2, GameTestSession.GROUND_Y + 1, 2);
    private static final BlockPos LEGACY_OTHER_CASE = new BlockPos(4, GameTestSession.GROUND_Y + 1, 2);
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

        // A legacy block the main account placed under the name the alt has now.
        setblock(session, LEGACY_MAIN, "mineqtt:interval_timer", "{ownerName:\"" + name + "\",ownerUuid:" + intArray(MAIN) + "}");
        session.awaitServer(s -> s.overworld().getBlockEntity(LEGACY_MAIN) instanceof BaseMqttBlockEntity);
        session.check(MAIN.equals(session.onServer(s -> legacy(s, LEGACY_MAIN).getOwnerUuid())), "a saved owner UUID is kept");
        session.check(!session.onServer(s -> DeviceAccess.isOwner(session.player(s), legacy(s, LEGACY_MAIN))),
                "the alt carrying the old owner name does not own the main account's legacy block");
        // Without a claim mod a legacy block stays open to everyone, as in v1, so the screen still opens.
        TabbedMqttScreen screen = session.openBlock(LEGACY_MAIN);
        session.check(screen.getMenu().deviceStatus() == null, "a resolved owner needs no status line (" + screen.getMenu().deviceStatus() + ")");
        session.closeScreen();

        // A v1 legacy block that only names its owner: the name gives the UUID at load.
        setblock(session, LEGACY_V1, "mineqtt:interval_timer", "{ownerName:\"" + name + "\"}");
        session.awaitServer(s -> s.overworld().getBlockEntity(LEGACY_V1) instanceof BaseMqttBlockEntity block && block.getOwnerUuid() != null);
        session.check(session.playerId().equals(session.onServer(s -> legacy(s, LEGACY_V1).getOwnerUuid())),
                "the v1 owner name resolves to the player's UUID after the load");
        session.check(session.onServer(s -> DeviceAccess.isOwner(session.player(s), legacy(s, LEGACY_V1))), "the player owns the v1 block by UUID");
        String saved = session.onServer(s -> legacy(s, LEGACY_V1).saveWithoutMetadata(s.registryAccess()).toString());
        session.check(saved.contains("ownerUuid"), "the owner UUID is saved with the block (" + saved + ")");

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
        setblock(session, LEGACY_OTHER_CASE, "mineqtt:interval_timer", "{ownerName:\"" + otherCase + "\"}");
        setblock(session, DEVICE_OTHER_CASE, "mineqtt:rgb_led_block", "{ownerName:\"" + otherCase + "\"}");
        session.awaitServer(s -> s.overworld().getBlockEntity(LEGACY_OTHER_CASE) instanceof BaseMqttBlockEntity
                && s.overworld().getBlockEntity(DEVICE_OTHER_CASE) instanceof DeviceHost host && host.deviceLink().attached());
        // The owner lookups run on the next server tick; give them time to bind someone they must not.
        session.context().waitTicks(NO_SCREEN_TICKS);
        session.check(session.onServer(s -> !DeviceAccess.isOwner(session.player(s), legacy(s, LEGACY_OTHER_CASE))
                        && legacy(s, LEGACY_OTHER_CASE).getOwnerUuid() == null),
                "a v1 block of " + otherCase + " is not bound to " + name + ", not even by their click");
        session.check(session.onServer(s -> !DeviceAccess.isOwner(session.player(s), s.overworld().getBlockEntity(DEVICE_OTHER_CASE)))
                && session.device(DEVICE_OTHER_CASE, link -> link.owner()) == null, "a v1 lamp of " + otherCase + " is not bound to " + name);
    }

    /** The name with its case flipped: another offline account, whose derived UUID differs. */
    private static String otherCase(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(name) ? name.toUpperCase(Locale.ROOT) : lower;
    }

    private static BaseMqttBlockEntity legacy(MinecraftServer server, BlockPos pos) {
        return (BaseMqttBlockEntity) server.overworld().getBlockEntity(pos);
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

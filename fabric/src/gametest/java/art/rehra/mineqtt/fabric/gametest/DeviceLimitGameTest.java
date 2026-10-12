package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.MineqttBlocks;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.items.DeviceBlockItem;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.legacy.LegacyConverterBlock;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceClaimPayload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code limits.maxDevicesPerPlayer} (M2-26): with a limit of 1, a non-op survival player places one Smart Lamp; the
 * second is not placed on either side, the lamp stays in their hand and the action bar says why. A Bridge Console, which
 * is no device, is still placed, a Static Publisher placed before it converted (it would become a Redstone Input) is
 * refused, and an ownerless lamp set by the console is not theirs to claim from its screen. An operator places another
 * lamp and claims the ownerless one, and a player whose lamps are broken may place again. Two placements in one tick
 * count both. Every MineQTT block that is a device has a {@link DeviceBlockItem}, and no other block has one.
 */
public final class DeviceLimitGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos STAND = new BlockPos(0, Y, 0);
    private static final BlockPos FIRST_GROUND = new BlockPos(1, Y - 1, 2);
    private static final BlockPos SECOND_GROUND = new BlockPos(-1, Y - 1, 2);
    private static final BlockPos THIRD_GROUND = new BlockPos(0, Y - 1, 3);
    private static final BlockPos UNOWNED = new BlockPos(0, Y, -2);
    private static final String REFUSAL = "Not placed: this server allows one device per player, and you own 1";
    private static final String CLAIM_REFUSAL = "this server allows one device per player, and you own 1";
    private static final int ANSWER_TICKS = 60;
    private static final int TELEPORT_RESEND_TICKS = 25;
    // Long enough for the server's block updates to undo the client's predicted placement.
    private static final int SETTLE_TICKS = 10;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "device-limit", DeviceLimitGameTest::run);
    }

    private static void run(GameTestSession session) {
        List<String> mismatched = session.onServer(DeviceLimitGameTest::itemMismatches);
        session.check(mismatched.isEmpty(), "every block that is a device, and only those, has a DeviceBlockItem (mismatched: " + mismatched + ")");

        int saved = session.onServer(s -> MineQTTConfig.limitsMaxDevicesPerPlayer);
        setLimit(session, 1);
        try {
            limited(session);
        } finally {
            setLimit(session, saved);
        }
    }

    private static void limited(GameTestSession session) {
        session.consoleCommand("gamemode survival " + session.playerName());
        session.teleport(STAND);
        // The server sends a teleport again after 20 ticks until it sees the confirmation, which would undo an early aim.
        session.context().waitTicks(TELEPORT_RESEND_TICKS);
        holdLamps(session, 3);

        BlockPos first = session.placeHeld(MineqttBlocks.RGB_LED_BLOCK.get(), FIRST_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(first) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(owned(session) == 1, "the first lamp is placed and the player owns one device");

        int mark = ChatLog.mark();
        BlockPos second = SECOND_GROUND.above();
        session.aim(SECOND_GROUND, Direction.UP);
        session.pressUse();
        Optional<ChatLog.Line> refusal = session.awaitChat(mark, line -> line.actionBar() && line.text().startsWith("Not placed"), ANSWER_TICKS);
        session.screenshot("device_limit_refused");
        session.check(refusal.map(line -> line.text().equals(REFUSAL)).orElse(false), "the action bar says why the second lamp is not placed (" + refusal + ")");
        session.context().waitTicks(SETTLE_TICKS);
        session.check(session.onServer(s -> s.overworld().getBlockState(second).isAir()), "the server placed no second lamp");
        session.check(session.awaitClient(mc -> mc.level.getBlockState(second).isAir(), ANSWER_TICKS), "the client's predicted lamp is gone again");
        session.check(session.onServer(s -> session.player(s).getMainHandItem().getCount()) == 2, "the server kept the lamp in the player's hand");
        session.check(session.awaitClient(mc -> mc.player.getMainHandItem().getCount() == 2, ANSWER_TICKS),
                "the client shows the lamp still in hand (" + session.onClient(mc -> mc.player.getMainHandItem().getCount()) + ")");
        session.check(owned(session) == 1, "the player still owns one device");

        // place waits until both sides have the block, so a refusal would fail the suite here.
        BlockPos console = session.place(MineqttBlocks.TERMINAL_BLOCK.get(), SECOND_GROUND);
        session.check(session.onServer(s -> s.overworld().getBlockState(console).is(MineqttBlocks.TERMINAL_BLOCK.get())),
                "a Bridge Console, which is no device, is placed at the limit");
        session.consoleCommand("setblock " + console.getX() + " " + console.getY() + " " + console.getZ() + " minecraft:air");

        mark = ChatLog.mark();
        Use converter = session.onServer(s -> useOn(session.player(s), new ItemStack(MineqttItems.StaticPublisherBlock.get()), THIRD_GROUND));
        session.check(!converter.result().consumesAction() && converter.placed() == null && converter.count() == 1,
                "a Static Publisher, which would become a Redstone Input, is not placed and kept (" + converter + ")");
        session.check(session.awaitChat(mark, line -> line.actionBar() && line.text().equals(REFUSAL), ANSWER_TICKS).isPresent(),
                "the action bar says why for the converter too");

        session.consoleCommand("setblock " + UNOWNED.getX() + " " + UNOWNED.getY() + " " + UNOWNED.getZ() + " mineqtt:rgb_led_block");
        session.awaitServer(s -> s.overworld().getBlockEntity(UNOWNED) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.device(UNOWNED, link -> link.owner() == null), "a lamp set by the console has no owner");
        mark = ChatLog.mark();
        claimUnowned(session);
        Optional<ChatLog.Line> claimRefusal = session.awaitChat(mark, line -> !line.actionBar() && line.text().startsWith("Not changed"), ANSWER_TICKS);
        session.check(claimRefusal.map(line -> line.text().endsWith(": " + CLAIM_REFUSAL)).orElse(false),
                "the player at the limit may not claim the ownerless lamp, and is told why (" + claimRefusal + ")");
        session.check(session.device(UNOWNED, link -> link.owner() == null) && owned(session) == 1, "the lamp stays ownerless and the player owns one device");

        session.consoleCommand("op " + session.playerName());
        holdLamps(session, 2);
        BlockPos byOperator = session.placeHeld(MineqttBlocks.RGB_LED_BLOCK.get(), SECOND_GROUND);
        session.awaitServer(s -> s.overworld().getBlockEntity(byOperator) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(owned(session) == 2, "an operator places a lamp beyond the limit, and owns two devices");
        claimUnowned(session);
        session.check(session.await(() -> session.playerId().equals(session.device(UNOWNED, link -> link.owner())), ANSWER_TICKS) && owned(session) == 3,
                "an operator claims the ownerless lamp beyond the limit, and owns three devices");
        session.consoleCommand("deop " + session.playerName());

        for (BlockPos lamp : List.of(first, byOperator, UNOWNED)) {
            session.consoleCommand("setblock " + lamp.getX() + " " + lamp.getY() + " " + lamp.getZ() + " minecraft:air destroy");
        }
        session.check(session.await(() -> owned(session) == 0, ANSWER_TICKS), "with their lamps broken the player owns no device");
        session.consoleCommand("kill @e[type=item]");

        // Both placements run in one server task: the first lamp waits for its attach, and still counts.
        List<Use> sameTick = session.onServer(s -> {
            ServerPlayer player = session.player(s);
            ItemStack lamps = new ItemStack(MineqttItems.RgbLedBlock.get(), 2);
            return List.of(useOn(player, lamps, FIRST_GROUND), useOn(player, lamps, SECOND_GROUND));
        });
        session.check(sameTick.get(0).placed() != null && sameTick.get(1).placed() == null && sameTick.get(1).count() == 1,
                "of two placements in one tick only the first is placed (" + sameTick + ")");
        session.check(session.await(() -> owned(session) == 1, ANSWER_TICKS), "the player owns one device again");
    }

    /** The blocks whose item does not match what they are: a device without a DeviceBlockItem, or the other way round. */
    private static List<String> itemMismatches(MinecraftServer server) {
        List<String> mismatched = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            // A converter's item asks its successor's, which is checked as a block of its own.
            if (!id.getNamespace().equals(MineQTT.MOD_ID) || block instanceof LegacyConverterBlock) continue;
            BlockEntity blockEntity = block instanceof EntityBlock entityBlock ? entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState()) : null;
            Item item = block.asItem();
            if ((blockEntity instanceof DeviceHost) != (item instanceof DeviceBlockItem)) mismatched.add(id.toString());
        }
        return mismatched;
    }

    /** The player uses {@code stack} on top of {@code ground} on the server thread, as the use key would. */
    private static Use useOn(ServerPlayer player, ItemStack stack, BlockPos ground) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground).relative(Direction.UP, 0.5), Direction.UP, ground, false);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        BlockPos pos = ground.above();
        BlockState placed = player.level().getBlockState(pos);
        return new Use(result, placed.isAir() ? null : BuiltInRegistries.BLOCK.getKey(placed.getBlock()).toString(), stack.getCount());
    }

    /** The player opens the ownerless lamp's screen and claims it there, as the Access tab's claim button does. */
    private static void claimUnowned(GameTestSession session) {
        session.openBlock(UNOWNED);
        session.send(new DeviceClaimPayload(UNOWNED));
        session.closeScreen();
    }

    private static void holdLamps(GameTestSession session, int count) {
        session.consoleCommand("item replace entity " + session.playerName() + " weapon.mainhand with mineqtt:rgb_led_block " + count);
        session.context().waitFor(mc -> mc.player.getMainHandItem().is(MineqttItems.RgbLedBlock.get()) && mc.player.getMainHandItem().getCount() == count);
    }

    private static int owned(GameTestSession session) {
        return session.onRuntime(runtime -> runtime.devices().ownedDevices(session.playerId()));
    }

    private static void setLimit(GameTestSession session, int limit) {
        session.onServer(s -> MineQTTConfig.limitsMaxDevicesPerPlayer = limit);
    }

    /** What a use did: the interaction result, the block now above the ground (null for air), the stack's count after. */
    private record Use(InteractionResult result, @Nullable String placed, int count) {
    }
}

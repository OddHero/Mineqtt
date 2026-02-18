package art.rehra.mineqtt.commands;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class MineQTTCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> mineqtt = Commands.literal("mineqtt")
                .requires(source -> source.hasPermission(2));

        LiteralArgumentBuilder<CommandSourceStack> setgoal = Commands.literal("setgoal")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> {
                            BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
                            updateGoal(context.getSource(), pos.getX(), pos.getY(), pos.getZ());
                            return 1;
                        }))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            int x = IntegerArgumentType.getInteger(context, "x");
                                            int y = IntegerArgumentType.getInteger(context, "y");
                                            int z = IntegerArgumentType.getInteger(context, "z");
                                            updateGoal(context.getSource(), x, y, z);
                                            return 1;
                                        }))));

        LiteralArgumentBuilder<CommandSourceStack> enablegoal = Commands.literal("enablegoal")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> {
                            boolean enabled = BoolArgumentType.getBool(context, "enabled");
                            updateGoalEnabled(context.getSource(), enabled);
                            return 1;
                        }));

        mineqtt.then(setgoal);
        mineqtt.then(enablegoal);
        dispatcher.register(mineqtt);
    }

    private static void updateGoal(CommandSourceStack source, int x, int y, int z) {
        MineQTTConfig.goalX = x;
        MineQTTConfig.goalY = y;
        MineQTTConfig.goalZ = z;

        if (MineQTT.getConfigHandler() != null) {
            MineQTT.getConfigHandler().saveConfig();
        }

        source.sendSuccess(() -> Component.literal(String.format("MineQTT goal set to: %d, %d, %d", x, y, z)), true);
    }

    private static void updateGoalEnabled(CommandSourceStack source, boolean enabled) {
        MineQTTConfig.zombieGoalEnabled = enabled;

        if (MineQTT.getConfigHandler() != null) {
            MineQTT.getConfigHandler().saveConfig();
        }

        source.sendSuccess(() -> Component.literal("MineQTT zombie goal " + (enabled ? "enabled" : "disabled")), true);
    }
}

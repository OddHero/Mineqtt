package art.rehra.mineqtt.commands;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AclContext;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public class MineQTTCommands {
    // Long states are cut in chat; the broker has the full JSON.
    private static final int MAX_SHOWN_JSON = 256;

    private static final SuggestionProvider<CommandSourceStack> DEVICE_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().records().stream()
                    .map(DeviceRecord::slug).toList()).orElse(List.of()), builder);
    private static final SuggestionProvider<CommandSourceStack> GROUP_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().groups().stream()
                    .map(DeviceGroup::slug).toList()).orElse(List.of()), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> mineqtt = Commands.literal("mineqtt")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

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

        // /mineqtt publish <topic> <payload...>: op level 3, full topic ACL (the hard deny applies to every level).
        LiteralArgumentBuilder<CommandSourceStack> publish = Commands.literal("publish")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("topic", StringArgumentType.string())
                        .then(Commands.argument("payload", StringArgumentType.greedyString())
                                .executes(context -> withRuntime(context, runtime -> publish(context, runtime)))));

        LiteralArgumentBuilder<CommandSourceStack> status = Commands.literal("status")
                .executes(context -> withRuntime(context, runtime -> status(context.getSource(), runtime)));

        LiteralArgumentBuilder<CommandSourceStack> reconnect = Commands.literal("reconnect")
                .executes(context -> withRuntime(context, runtime -> {
                    runtime.reconnect();
                    context.getSource().sendSuccess(() -> Component.literal("MQTT connection rebuilt: " + runtime.mqtt().state()), true);
                    return 1;
                }));

        LiteralArgumentBuilder<CommandSourceStack> devices = Commands.literal("devices")
                .then(Commands.literal("list")
                        .executes(context -> withRuntime(context, runtime -> listDevices(context.getSource(), runtime))))
                .then(Commands.literal("info")
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .executes(context -> withDevice(context, (runtime, record) -> deviceInfo(context.getSource(), runtime, record)))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> withDevice(context, (runtime, record) -> {
                                            String oldSlug = record.slug();
                                            runtime.devices().rename(record, StringArgumentType.getString(context, "name"));
                                            context.getSource().sendSuccess(() -> Component.literal("Renamed " + oldSlug + " to " + record.name()
                                                    + " (" + runtime.topics().device(record.slug()) + ")"), true);
                                            return 1;
                                        })))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .executes(context -> withDevice(context, (runtime, record) -> {
                                    if (!runtime.devices().remove(record)) {
                                        context.getSource().sendFailure(Component.literal(record.slug() + " is loaded at " + describe(record)
                                                + "; break the block to remove it"));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Forgot " + record.slug() + " and cleared its retained topics. "
                                            + "If its block still exists, it registers again when its chunk loads."), true);
                                    return 1;
                                }))))
                .then(Commands.literal("prune")
                        .executes(context -> withRuntime(context, runtime -> {
                            int pruned = runtime.devices().pruneLoaded();
                            context.getSource().sendSuccess(() -> Component.literal("Pruned " + pruned + " devices whose block is gone"), true);
                            return pruned;
                        })));

        LiteralArgumentBuilder<CommandSourceStack> group = Commands.literal("group")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> withRuntime(context, runtime -> {
                                    DeviceGroup created = runtime.devices().createGroup(StringArgumentType.getString(context, "name"));
                                    context.getSource().sendSuccess(() -> Component.literal("Created group " + created.name() + " ("
                                            + runtime.topics().group(created.slug()) + ")"), true);
                                    return 1;
                                }))))
                .then(Commands.literal("add")
                        .then(Commands.argument("group", StringArgumentType.word()).suggests(GROUP_SLUGS)
                                .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                        .executes(context -> withGroupAndDevice(context, (runtime, target, record) -> {
                                            boolean inherits = !target.state().isEmpty();
                                            Optional<String> refusal = runtime.devices().join(target, record);
                                            if (refusal.isPresent()) {
                                                context.getSource().sendFailure(Component.literal(record.slug() + " cannot join " + target.slug() + ": " + refusal.get()));
                                                return 0;
                                            }
                                            context.getSource().sendSuccess(() -> Component.literal(record.slug() + " joined " + target.slug()
                                                    + (inherits ? " and took over its state" : "; its state is now the group's")), true);
                                            return 1;
                                        })))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("group", StringArgumentType.word()).suggests(GROUP_SLUGS)
                                .executes(context -> withGroup(context, (runtime, target) -> {
                                    runtime.devices().deleteGroup(target);
                                    context.getSource().sendSuccess(() -> Component.literal("Deleted group " + target.slug()
                                            + "; its members are devices of their own again"), true);
                                    return 1;
                                }))
                                .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                        .executes(context -> withGroupAndDevice(context, (runtime, target, record) -> {
                                            if (!target.id().equals(record.group())) {
                                                context.getSource().sendFailure(Component.literal(record.slug() + " is not in group " + target.slug()));
                                                return 0;
                                            }
                                            runtime.devices().leave(record);
                                            context.getSource().sendSuccess(() -> Component.literal(record.slug() + " left " + target.slug()), true);
                                            return 1;
                                        })))))
                .then(Commands.literal("list")
                        .executes(context -> withRuntime(context, runtime -> listGroups(context.getSource(), runtime))));

        LiteralArgumentBuilder<CommandSourceStack> discovery = Commands.literal("discovery")
                .then(Commands.literal("republish")
                        .executes(context -> withRuntime(context, runtime -> {
                            runtime.republish();
                            context.getSource().sendSuccess(() -> Component.literal("Republishing Home Assistant discovery for the bridge, "
                                    + runtime.registry().records().size() + " devices and " + runtime.registry().groups().size()
                                    + " groups; availability and state follow"), true);
                            return 1;
                        })))
                .then(Commands.literal("clear")
                        .executes(context -> withRuntime(context, runtime -> clearRetained(context.getSource(), runtime, null)))
                        .then(Commands.literal("--bridge")
                                .then(Commands.argument("old", StringArgumentType.word())
                                        .executes(context -> withRuntime(context, runtime ->
                                                clearRetained(context.getSource(), runtime, StringArgumentType.getString(context, "old")))))));

        mineqtt.then(setgoal);
        mineqtt.then(enablegoal);
        mineqtt.then(publish);
        mineqtt.then(status);
        mineqtt.then(reconnect);
        mineqtt.then(devices);
        mineqtt.then(group);
        mineqtt.then(discovery);
        dispatcher.register(mineqtt);
    }

    private static int publish(CommandContext<CommandSourceStack> context, MineqttRuntime runtime) {
        CommandSourceStack source = context.getSource();
        String topic = StringArgumentType.getString(context, "topic");
        String payload = StringArgumentType.getString(context, "payload");
        TopicAcl.Decision decision = runtime.acl().checkPublish(AclContext.of(source), topic);
        if (!decision.allowed()) {
            source.sendFailure(Component.literal("Not published to " + topic + ": " + decision.message()));
            return 0;
        }
        Entity entity = source.getEntity();
        UUID origin = entity == null ? null : entity.getUUID();
        runtime.mqtt().publish(Outbound.raw(topic, ValueCodecs.utf8(payload), 0, false, origin));
        // Blocks on this server receive it either way; the broker only while connected.
        String delivery = runtime.mqtt().state().isConnected() ? "Published to: " : "Not connected to the broker, delivered on this server only: ";
        source.sendSuccess(() -> Component.literal(delivery + topic), false);
        return 1;
    }

    private static int status(CommandSourceStack source, MineqttRuntime runtime) {
        ConnectionState state = runtime.mqtt().state();
        MqttService.Stats stats = runtime.mqtt().stats();
        DeviceRuntime devices = runtime.devices();
        DeviceRegistry registry = runtime.registry();
        StringBuilder text = new StringBuilder("MineQTT bridge ").append(runtime.topics().bridgeId())
                .append(runtime.bridgeIdGenerated() ? " (generated)" : " (from config)")
                .append(" under ").append(runtime.topics().root())
                .append("\nConnection: ").append(state)
                .append("\nBroker: ").append(runtime.broker())
                .append("\nProtocol: MQTT ").append(runtime.protocol().configValue())
                .append("\nDevices: ").append(registry.records().size()).append(" registered, ").append(devices.attachedCount()).append(" loaded")
                .append("; groups: ").append(registry.groups().size())
                .append("\nHome Assistant: ").append(MineQTTConfig.haExpose(runtime.server().isDedicatedServer()))
                .append(" under ").append(MineQTTConfig.discoveryPrefix).append(", ").append(runtime.discovery().discovered())
                .append(" devices and groups discovered, ").append(runtime.discovery().migration().pending()).append(" v1 discoveries to migrate")
                .append("\nDropped: ").append(stats.droppedInbound()).append(" inbound, ").append(stats.droppedOutbound()).append(" outbound")
                .append("; queued: ").append(stats.queuedInbound()).append(" inbound, ").append(stats.outboxSize()).append(" in the outbox");
        if (runtime.protocol() == ProtocolVersion.MQTT_3_1_1) {
            text.append("\nMQTT 3.1.1 cannot announce a maximum packet size: set message_size_limit on the broker to keep huge messages away");
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return state.isConnected() ? 1 : 0;
    }

    private static int listDevices(CommandSourceStack source, MineqttRuntime runtime) {
        Collection<DeviceRecord> records = runtime.registry().records();
        if (records.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No devices registered"), false);
            return 0;
        }
        StringBuilder text = new StringBuilder(records.size() + " devices:");
        for (DeviceRecord record : records) {
            text.append("\n").append(record.slug()).append(" - ").append(record.name()).append(" (").append(record.type()).append(") ")
                    .append(runtime.devices().isAttached(record.id()) ? "loaded" : "unloaded").append(" at ").append(describe(record));
            DeviceGroup group = record.group() == null ? null : runtime.registry().group(record.group());
            if (group != null) text.append(", group ").append(group.slug());
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return records.size();
    }

    private static int deviceInfo(CommandSourceStack source, MineqttRuntime runtime, DeviceRecord record) {
        DeviceType type = DeviceTypes.get(record.type());
        DeviceGroup group = record.group() == null ? null : runtime.registry().group(record.group());
        String text = record.name() + " (" + record.slug() + ")"
                + "\nId: " + record.id() + " (" + record.objectId() + ")"
                + "\nType: " + record.type() + (type == null ? " - unknown to this MineQTT, not published" : ", " + type.unloadedPolicy().name().toLowerCase(Locale.ROOT) + " while unloaded")
                + "\nPosition: " + describe(record) + (runtime.devices().isAttached(record.id()) ? ", loaded" : ", unloaded")
                + "\nOwner: " + (record.owner() == null ? "unknown" : record.owner())
                + "\nExpose: " + (record.expose() == null ? "default (" + MineQTTConfig.haExpose(runtime.server().isDedicatedServer()) + ")" : record.expose())
                + (record.discoveryHash() != null ? ", discovered as " + record.objectId() : ", not discovered")
                + (record.legacyUniqueId() == null ? "" : "\nv1 unique_id: " + record.legacyUniqueId()
                        + (runtime.registry().legacyDiscoveryMigrated(record.legacyUniqueId()) ? " (migrated)" : " (migration pending)"))
                + "\nGroup: " + (group == null ? "none" : group.slug())
                + runtime.devices().status(record.id()).map(status -> "\nStatus: " + status).orElse("")
                + "\nState topic: " + runtime.topics().device(record.slug())
                + "\nLast state: " + shorten(record.lastState() == null ? "none" : record.lastState().toString())
                + (record.pending() == null ? "" : "\nQueued: " + shorten(record.pending().toString()));
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int listGroups(CommandSourceStack source, MineqttRuntime runtime) {
        Collection<DeviceGroup> groups = runtime.registry().groups();
        if (groups.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No groups"), false);
            return 0;
        }
        StringBuilder text = new StringBuilder(groups.size() + " groups:");
        for (DeviceGroup group : groups) {
            List<String> members = group.members().stream()
                    .map(member -> Optional.ofNullable(runtime.registry().record(member)).map(DeviceRecord::slug).orElse(member.toString()))
                    .toList();
            text.append("\n").append(group.slug()).append(" - ").append(group.name())
                    .append(group.memberType() == null ? "" : " (" + group.memberType() + ")")
                    .append(group.legacyTopic() == null ? "" : " replacing v1 topic " + group.legacyTopic())
                    .append(": ").append(members.isEmpty() ? "no members" : String.join(", ", members));
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return groups.size();
    }

    private static int clearRetained(CommandSourceStack source, MineqttRuntime runtime, String oldBridge) {
        if (oldBridge != null && (oldBridge.isEmpty() || !ConfigValidation.isValidBridgeId(oldBridge))) {
            source.sendFailure(Component.literal("Not a bridge id: " + oldBridge));
            return 0;
        }
        if (oldBridge != null && oldBridge.equals(runtime.topics().bridgeId())) {
            source.sendFailure(Component.literal(oldBridge + " is this server's bridge id; give the old one"));
            return 0;
        }
        int cleared = runtime.devices().clearRetained(oldBridge);
        String scope = oldBridge == null ? "" : " and the topics left under " + runtime.topics().base() + "/" + oldBridge;
        source.sendSuccess(() -> Component.literal("Cleared " + cleared + " retained topics: Home Assistant discovery" + scope
                + ". Use /mineqtt discovery republish to publish this server's devices again."), true);
        return cleared;
    }

    private static Optional<MineqttRuntime> running(CommandSourceStack source) {
        return MineqttRuntime.current().filter(runtime -> runtime.server() == source.getServer());
    }

    private static int withRuntime(CommandContext<CommandSourceStack> context, Function<MineqttRuntime, Integer> action) {
        Optional<MineqttRuntime> runtime = running(context.getSource());
        if (runtime.isEmpty()) {
            context.getSource().sendFailure(Component.literal("MineQTT is not running on this server"));
            return 0;
        }
        return action.apply(runtime.get());
    }

    private static int withDevice(CommandContext<CommandSourceStack> context, DeviceAction action) {
        return withRuntime(context, runtime -> {
            String slug = StringArgumentType.getString(context, "device");
            DeviceRecord record = runtime.registry().bySlug(slug);
            if (record == null) {
                context.getSource().sendFailure(Component.literal("No device " + slug));
                return 0;
            }
            return action.run(runtime, record);
        });
    }

    private static int withGroup(CommandContext<CommandSourceStack> context, GroupAction action) {
        return withRuntime(context, runtime -> {
            String slug = StringArgumentType.getString(context, "group");
            DeviceGroup group = runtime.registry().groupBySlug(slug);
            if (group == null) {
                context.getSource().sendFailure(Component.literal("No group " + slug));
                return 0;
            }
            return action.run(runtime, group);
        });
    }

    private static int withGroupAndDevice(CommandContext<CommandSourceStack> context, GroupDeviceAction action) {
        return withGroup(context, (runtime, group) -> withDevice(context, (sameRuntime, record) -> action.run(runtime, group, record)));
    }

    @FunctionalInterface
    private interface DeviceAction {
        int run(MineqttRuntime runtime, DeviceRecord record);
    }

    @FunctionalInterface
    private interface GroupAction {
        int run(MineqttRuntime runtime, DeviceGroup group);
    }

    @FunctionalInterface
    private interface GroupDeviceAction {
        int run(MineqttRuntime runtime, DeviceGroup group, DeviceRecord record);
    }

    private static String describe(DeviceRecord record) {
        BlockPos pos = record.pos().pos();
        return record.pos().dimension().identifier() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String shorten(String json) {
        return json.length() <= MAX_SHOWN_JSON ? json : json.substring(0, MAX_SHOWN_JSON) + "...";
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

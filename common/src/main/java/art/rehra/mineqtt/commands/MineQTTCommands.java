package art.rehra.mineqtt.commands;

import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.HaExpose;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.Action;
import art.rehra.mineqtt.device.DefaultWiring;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.device.Setting;
import art.rehra.mineqtt.device.TwinRules;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.mqtt.core.MqttService;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.security.AccessMode;
import art.rehra.mineqtt.security.AclContext;
import art.rehra.mineqtt.security.DeviceAccess;
import art.rehra.mineqtt.twin.BindingPresets;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.twin.TwinRequests;
import art.rehra.mineqtt.twin.TwinRuntime;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.CatalogParser;
import art.rehra.mineqtt.twin.core.EntityDocument;
import art.rehra.mineqtt.twin.core.EntityDocuments;
import art.rehra.mineqtt.twin.core.EntitySearch;
import art.rehra.mineqtt.twin.core.HaTime;
import art.rehra.mineqtt.twin.core.StatestreamValue;
import art.rehra.mineqtt.twin.core.TwinHealth;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

public class MineQTTCommands {
    private static final int MAX_SHOWN_FAULTS = 5;
    // At most this many rows of /mineqtt twin entities, as the picker shows (M3 plan D4), and suggested entity ids.
    private static final int MAX_ENTITY_ROWS = 40;
    private static final int MAX_SUGGESTED_ENTITIES = 200;

    private static final SuggestionProvider<CommandSourceStack> DEVICE_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().records().stream()
                    .map(DeviceRecord::slug).toList()).orElse(List.of()), builder);
    // Players may expose only devices they may configure.
    private static final SuggestionProvider<CommandSourceStack> EXPOSABLE_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().records().stream()
                    .filter(record -> mayConfigure(context.getSource(), record)).map(DeviceRecord::slug).toList()).orElse(List.of()), builder);
    private static final SuggestionProvider<CommandSourceStack> FAULTED_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().records().stream()
                    .filter(record -> runtime.devices().fault(record.pos()).isPresent()).map(DeviceRecord::slug).toList()).orElse(List.of()), builder);
    // Devices whose type mirrors Home Assistant (M3 plan D19).
    private static final SuggestionProvider<CommandSourceStack> TWIN_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().records().stream()
                    .filter(record -> type(record).flatMap(DeviceType::twin).isPresent()).map(DeviceRecord::slug).toList())
                    .orElse(List.of()), builder);
    // The targets of the device named before them (M3-14): twin.target, twin.left, twin.right.
    private static final SuggestionProvider<CommandSourceStack> TARGET_NAMES = (context, builder) ->
            SharedSuggestionProvider.suggest(namedType(context).flatMap(DeviceType::twin).map(twin -> List.copyOf(twin.targets().keySet())).orElse(List.of()), builder);
    // The entities the twin knows: those bound devices mirror, and those the catalog (or the scan) shares (M3-11).
    private static final SuggestionProvider<CommandSourceStack> TWIN_ENTITIES = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> Stream.concat(runtime.devices().attachedBindings().stream()
                            .flatMap(binding -> binding.sourceEntity().stream()),
                    runtime.twin().entities("", List.of(), Set.of(), MAX_SUGGESTED_ENTITIES).stream().map(EntitySearch.Candidate::entityId))
                    .distinct().toList()).orElse(List.of()), builder);
    // The MQTT device presets the datapacks loaded (M3-17).
    private static final SuggestionProvider<CommandSourceStack> PRESET_IDS = (context, builder) ->
            SharedSuggestionProvider.suggest(BindingPresets.all().stream().map(BindingPreset::id).toList(), builder);
    private static final SuggestionProvider<CommandSourceStack> RAW_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().records().stream()
                    .filter(record -> type(record).flatMap(DeviceType::rawLink).isPresent()).map(DeviceRecord::slug).toList()).orElse(List.of()), builder);
    // The setting keys and actions of the device named before them; settings include its writable fields (M2-9).
    private static final SuggestionProvider<CommandSourceStack> SETTING_KEYS = (context, builder) ->
            SharedSuggestionProvider.suggest(namedType(context).map(type -> Stream.of(type.settings().stream().map(Setting::key),
                    type.ports().stream().filter(port -> port.direction() == Port.Direction.IN && port.origin() == Port.Origin.FIELD).map(Port::key),
                    type.linkSettings().stream().map(LinkSetting::key)).flatMap(keys -> keys).toList()).orElse(List.of()), builder);
    private static final SuggestionProvider<CommandSourceStack> ACTION_KEYS = (context, builder) ->
            SharedSuggestionProvider.suggest(namedType(context).map(type -> type.actions().stream().map(Action::key).toList()).orElse(List.of()), builder);
    private static final SuggestionProvider<CommandSourceStack> GROUP_SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().groups().stream()
                    .map(DeviceGroup::slug).toList()).orElse(List.of()), builder);
    // How /mineqtt wires names a group, before its slug: group is a reserved device slug, so it never names a device.
    private static final String GROUP_PREFIX = "group.";
    private static final SuggestionProvider<CommandSourceStack> NODE_NAMES = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> Stream.concat(
                    runtime.registry().records().stream().map(DeviceRecord::slug),
                    runtime.registry().groups().stream().map(group -> GROUP_PREFIX + group.slug())).toList()).orElse(List.of()), builder);
    // A wire starts at a device's out-port and ends at an in-port of a device or of a group's member type.
    private static final SuggestionProvider<CommandSourceStack> OUT_PORTS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().records().stream()
                    .flatMap(record -> portNames(record.slug(), type(record), Port.Direction.OUT)).toList()).orElse(List.of()), builder);
    private static final SuggestionProvider<CommandSourceStack> IN_PORTS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> Stream.concat(
                    runtime.registry().records().stream().flatMap(record -> portNames(record.slug(), type(record), Port.Direction.IN)),
                    runtime.registry().groups().stream().flatMap(group -> portNames(GROUP_PREFIX + group.slug(),
                            Optional.ofNullable(group.memberType()).map(DeviceTypes::get), Port.Direction.IN))).toList()).orElse(List.of()), builder);
    private static final SuggestionProvider<CommandSourceStack> MAPPINGS = (context, builder) ->
            SharedSuggestionProvider.suggest(Stream.of(Mapping.values()).map(MineQTTCommands::mappingName).toList(), builder);
    private static final SuggestionProvider<CommandSourceStack> WIRE_IDS = (context, builder) ->
            SharedSuggestionProvider.suggest(running(context.getSource()).map(runtime -> runtime.registry().wires().stream()
                    .map(wire -> wire.id().toString()).toList()).orElse(List.of()), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Ops use every subcommand. A holder of mineqtt:ha.expose reaches /mineqtt only for devices expose (M2 plan D6),
        // so every other subcommand needs op level 2 itself.
        LiteralArgumentBuilder<CommandSourceStack> mineqtt = Commands.literal("mineqtt")
                .requires(source -> AclContext.mayExpose(source.permissions()));

        // /mineqtt publish <topic> <payload...>: op level 3, full topic ACL (the hard deny applies to every level).
        LiteralArgumentBuilder<CommandSourceStack> publish = Commands.literal("publish")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("topic", StringArgumentType.string())
                        .then(Commands.argument("payload", StringArgumentType.greedyString())
                                .executes(context -> withRuntime(context, runtime -> publish(context, runtime)))));

        LiteralArgumentBuilder<CommandSourceStack> status = Commands.literal("status")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> withRuntime(context, runtime -> status(context.getSource(), runtime)));

        LiteralArgumentBuilder<CommandSourceStack> reconnect = Commands.literal("reconnect")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> withRuntime(context, runtime -> reconnect(context.getSource(), runtime)));

        LiteralArgumentBuilder<CommandSourceStack> devices = Commands.literal("devices")
                .then(Commands.literal("list")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> withRuntime(context, runtime -> listDevices(context.getSource(), runtime))))
                .then(Commands.literal("info")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .executes(context -> withDevice(context, (runtime, record) -> deviceInfo(context.getSource(), runtime, record)))))
                .then(Commands.literal("rename")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> withDevice(context, (runtime, record) -> {
                                            String name = name(context);
                                            if (name.isEmpty()) {
                                                context.getSource().sendFailure(Component.literal("A device needs a name"));
                                                return 0;
                                            }
                                            String oldSlug = record.slug();
                                            runtime.devices().rename(record, name);
                                            context.getSource().sendSuccess(() -> Component.literal("Renamed " + oldSlug + " to " + record.name()
                                                    + " (" + runtime.topics().device(record.slug()) + ")"), true);
                                            return 1;
                                        })))))
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(context -> withDevice(context, (runtime, record) ->
                                                set(context.getSource(), runtime, record, StringArgumentType.getString(context, "command")))))))
                .then(Commands.literal("setting")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .then(Commands.argument("key", StringArgumentType.word()).suggests(SETTING_KEYS)
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> withDevice(context, (runtime, record) -> {
                                                    String key = StringArgumentType.getString(context, "key");
                                                    return configure(context.getSource(), runtime, record, key,
                                                            settingValue(record, key, StringArgumentType.getString(context, "value")));
                                                }))))))
                .then(Commands.literal("raw")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(RAW_SLUGS)
                                .then(Commands.literal("channel")
                                        .then(Commands.argument("channel", StringArgumentType.greedyString())
                                                .executes(context -> rawText(context, LinkSetting.RAW_CHANNEL, "channel"))))
                                .then(Commands.literal("private")
                                        .then(Commands.argument("channel", StringArgumentType.greedyString())
                                                .executes(context -> rawText(context, LinkSetting.RAW_PRIVATE, "channel"))))
                                .then(Commands.literal("topic")
                                        .then(Commands.argument("topic", StringArgumentType.greedyString())
                                                .executes(context -> rawText(context, LinkSetting.RAW_TOPIC, "topic"))))
                                .then(Commands.literal("none")
                                        .executes(context -> withDevice(context, (runtime, record) ->
                                                configure(context.getSource(), runtime, record, LinkSetting.RAW_TOPIC.key(), new JsonPrimitive("")))))
                                .then(Commands.literal("retain")
                                        .then(Commands.literal("on").executes(context -> rawValue(context, LinkSetting.RAW_RETAIN, new JsonPrimitive(true))))
                                        .then(Commands.literal("off").executes(context -> rawValue(context, LinkSetting.RAW_RETAIN, new JsonPrimitive(false)))))
                                .then(Commands.literal("qos")
                                        .then(Commands.literal("0").executes(context -> rawValue(context, LinkSetting.RAW_QOS, new JsonPrimitive(0))))
                                        .then(Commands.literal("1").executes(context -> rawValue(context, LinkSetting.RAW_QOS, new JsonPrimitive(1)))))
                                .then(Commands.literal("on")
                                        .then(Commands.argument("payload", StringArgumentType.greedyString())
                                                .executes(context -> rawText(context, LinkSetting.RAW_ON, "payload"))))
                                .then(Commands.literal("off")
                                        .then(Commands.argument("payload", StringArgumentType.greedyString())
                                                .executes(context -> rawText(context, LinkSetting.RAW_OFF, "payload"))))))
                // The twin's source: an entity, or none to unbind (M3 plan D19); the command twin of twin.entity. resume
                // ends a pause of the circuit breaker (D10), the twin of the Twin tab's Resume.
                .then(Commands.literal("bind")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(TWIN_SLUGS)
                                .then(Commands.literal("none")
                                        .executes(context -> withDevice(context, (runtime, record) ->
                                                configure(context.getSource(), runtime, record, LinkSetting.TWIN_ENTITY.key(), new JsonPrimitive("")))))
                                .then(Commands.literal("resume")
                                        .executes(context -> withDevice(context, (runtime, record) ->
                                                reply(context.getSource(), runtime.devices().resumeTwin(record, editor(context.getSource()))))))
                                // An MQTT device through a preset (M3-17), the command twin of twin.device.
                                .then(Commands.literal("preset")
                                        .then(Commands.argument("preset", IdentifierArgument.id()).suggests(PRESET_IDS)
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(context -> withDevice(context, (runtime, record) -> configure(context.getSource(), runtime, record,
                                                                LinkSetting.TWIN_DEVICE.key(), new JsonPrimitive(IdentifierArgument.getId(context, "preset") + " "
                                                                        + StringArgumentType.getString(context, "name"))))))))
                                // A target: the entity an out-port switches (M3-14), the command twin of twin.<target>.
                                .then(Commands.literal("target")
                                        .then(Commands.argument("target", StringArgumentType.word()).suggests(TARGET_NAMES)
                                                .then(Commands.literal("none")
                                                        .executes(context -> withDevice(context, (runtime, record) ->
                                                                bindTarget(context.getSource(), runtime, record, StringArgumentType.getString(context, "target"), ""))))
                                                .then(Commands.argument("entity", StringArgumentType.word())
                                                        .executes(context -> withDevice(context, (runtime, record) -> bindTarget(context.getSource(), runtime, record,
                                                                StringArgumentType.getString(context, "target"), StringArgumentType.getString(context, "entity")))))))
                                .then(Commands.argument("entity", StringArgumentType.word()).suggests(TWIN_ENTITIES)
                                        .executes(context -> withDevice(context, (runtime, record) -> configure(context.getSource(), runtime, record,
                                                LinkSetting.TWIN_ENTITY.key(), new JsonPrimitive(StringArgumentType.getString(context, "entity"))))))))
                .then(Commands.literal("action")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .then(Commands.argument("action", StringArgumentType.word()).suggests(ACTION_KEYS)
                                        .executes(context -> action(context, ""))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> action(context, StringArgumentType.getString(context, "value")))))))
                .then(Commands.literal("access")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .then(accessMode(AccessMode.PRIVATE))
                                .then(accessMode(AccessMode.CLAIM))
                                .then(accessMode(AccessMode.PUBLIC))))
                .then(Commands.literal("claim")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .executes(context -> withDevice(context, (runtime, record) ->
                                        reply(context.getSource(), runtime.devices().claim(record, editor(context.getSource())))))))
                .then(Commands.literal("expose")
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(EXPOSABLE_SLUGS)
                                .then(Commands.literal("on")
                                        .executes(context -> withDevice(context, (runtime, record) -> expose(context.getSource(), runtime, record, true))))
                                .then(Commands.literal("off")
                                        .executes(context -> withDevice(context, (runtime, record) -> expose(context.getSource(), runtime, record, false))))
                                .then(Commands.literal("default")
                                        .executes(context -> withDevice(context, (runtime, record) -> expose(context.getSource(), runtime, record, null))))))
                .then(Commands.literal("remove")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .executes(context -> withDevice(context, (runtime, record) -> {
                                    if (!runtime.devices().remove(record)) {
                                        context.getSource().sendFailure(Component.literal(record.slug() + " is loaded at " + describe(record)
                                                + "; break the block to remove it"));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Forgot " + record.slug()
                                            + " and cleared its retained device topics; a level retained on its raw link stays. "
                                            + "If its block still exists, it registers again when its chunk loads."), true);
                                    return 1;
                                }))))
                .then(Commands.literal("eject")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(DEVICE_SLUGS)
                                .executes(context -> withDevice(context, (runtime, record) ->
                                        reply(context.getSource(), runtime.devices().eject(record))))))
                .then(Commands.literal("retry")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("device", StringArgumentType.word()).suggests(FAULTED_SLUGS)
                                .executes(context -> withDevice(context, (runtime, record) -> retry(context.getSource(), runtime, record.pos()))))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> withRuntime(context, runtime -> retry(context.getSource(), runtime,
                                        GlobalPos.of(context.getSource().getLevel().dimension(), BlockPosArgument.getBlockPos(context, "pos")))))))
                .then(Commands.literal("prune")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> withRuntime(context, runtime -> prune(context.getSource(), runtime))));

        LiteralArgumentBuilder<CommandSourceStack> group = Commands.literal("group")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> withRuntime(context, runtime -> {
                                    String name = name(context);
                                    if (name.isEmpty()) {
                                        context.getSource().sendFailure(Component.literal("A group needs a name"));
                                        return 0;
                                    }
                                    DeviceGroup created = runtime.devices().createGroup(name);
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
                .then(Commands.literal("rename")
                        .then(Commands.argument("group", StringArgumentType.word()).suggests(GROUP_SLUGS)
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> withGroup(context, (runtime, target) -> renameGroup(context, runtime, target))))))
                .then(Commands.literal("expose-members")
                        .then(Commands.argument("group", StringArgumentType.word()).suggests(GROUP_SLUGS)
                                .then(Commands.literal("on").executes(context -> withGroup(context, (runtime, target) ->
                                        exposeMembers(context.getSource(), runtime, target, true))))
                                .then(Commands.literal("off").executes(context -> withGroup(context, (runtime, target) ->
                                        exposeMembers(context.getSource(), runtime, target, false))))))
                .then(Commands.literal("list")
                        .executes(context -> withRuntime(context, runtime -> listGroups(context.getSource(), runtime))));

        // /mineqtt wires (M2 plan D9), the twin of the Configurator's Link mode: a port is <device>.<port> or
        // group.<group>.<port>, and a wire without a mapping gets the one DefaultWiring gives its ports.
        LiteralArgumentBuilder<CommandSourceStack> wires = Commands.literal("wires")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list")
                        .executes(context -> withRuntime(context, runtime -> listWires(context.getSource(), runtime, null)))
                        .then(Commands.argument("node", StringArgumentType.word()).suggests(NODE_NAMES)
                                .executes(context -> withRuntime(context, runtime ->
                                        listWires(context.getSource(), runtime, StringArgumentType.getString(context, "node"))))))
                .then(Commands.literal("add")
                        .then(Commands.argument("from", StringArgumentType.word()).suggests(OUT_PORTS)
                                .then(Commands.argument("to", StringArgumentType.word()).suggests(IN_PORTS)
                                        .executes(context -> withRuntime(context, runtime -> addWire(context, runtime, null)))
                                        .then(Commands.argument("mapping", StringArgumentType.word()).suggests(MAPPINGS)
                                                .executes(context -> withRuntime(context, runtime ->
                                                        addWire(context, runtime, StringArgumentType.getString(context, "mapping"))))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(WIRE_IDS)
                                .executes(context -> withRuntime(context, runtime ->
                                        removeWire(context.getSource(), runtime, StringArgumentType.getString(context, "id"))))));

        LiteralArgumentBuilder<CommandSourceStack> discovery = Commands.literal("discovery")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("republish")
                        .executes(context -> withRuntime(context, runtime -> republish(context.getSource(), runtime))))
                .then(Commands.literal("clear")
                        .executes(context -> withRuntime(context, runtime -> clearRetained(context.getSource(), runtime, null)))
                        .then(Commands.literal("--bridge")
                                .then(Commands.argument("old", StringArgumentType.word())
                                        .executes(context -> withRuntime(context, runtime ->
                                                clearRetained(context.getSource(), runtime, StringArgumentType.getString(context, "old")))))));

        // The Home Assistant twin's read side (M3 plan D19): what it reads, and one entity as it knows it.
        LiteralArgumentBuilder<CommandSourceStack> twin = Commands.literal("twin")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status")
                        .executes(context -> withRuntime(context, runtime -> twinStatus(context.getSource(), runtime))))
                .then(Commands.literal("show")
                        .then(Commands.argument("entity", StringArgumentType.word()).suggests(TWIN_ENTITIES)
                                .executes(context -> withRuntime(context, runtime ->
                                        twinShow(context.getSource(), runtime, StringArgumentType.getString(context, "entity"))))))
                // Whether the control blueprint answers (M3 plan D12), and a fresh entity catalog (D3).
                .then(Commands.literal("ping")
                        .executes(context -> withRuntime(context, runtime -> twinPing(context.getSource(), runtime))))
                .then(Commands.literal("refresh")
                        .executes(context -> withRuntime(context, runtime -> twinRefresh(context.getSource(), runtime))))
                // The entities Home Assistant shares (M3 plan D4), as the picker ranks them.
                .then(Commands.literal("entities")
                        .executes(context -> withRuntime(context, runtime -> twinEntities(context.getSource(), runtime, "")))
                        .then(Commands.argument("query", StringArgumentType.greedyString())
                                .executes(context -> withRuntime(context, runtime ->
                                        twinEntities(context.getSource(), runtime, StringArgumentType.getString(context, "query"))))));

        mineqtt.then(publish);
        mineqtt.then(status);
        mineqtt.then(twin);
        mineqtt.then(reconnect);
        mineqtt.then(devices);
        mineqtt.then(group);
        mineqtt.then(wires);
        mineqtt.then(discovery);
        dispatcher.register(mineqtt);
    }

    /**
     * {@code /mineqtt reconnect}, also the Bridge Console's Reconnect, which runs it with the player as the source: the
     * reply goes to them and, as for any command, to the other operators and the log.
     */
    public static int reconnect(CommandSourceStack source, MineqttRuntime runtime) {
        runtime.reconnect();
        source.sendSuccess(() -> Component.literal("MQTT connection rebuilt: " + runtime.mqtt().state()), true);
        return 1;
    }

    /** {@code /mineqtt discovery republish}, also the Bridge Console's Republish ({@link #reconnect}). */
    public static int republish(CommandSourceStack source, MineqttRuntime runtime) {
        runtime.republish();
        source.sendSuccess(() -> Component.literal("Republishing Home Assistant discovery for the bridge, "
                + runtime.registry().records().size() + " devices and " + runtime.registry().groups().size()
                + " groups; availability and state follow"), true);
        return 1;
    }

    /** {@code /mineqtt devices prune}, also the Bridge Console's Prune ({@link #reconnect}). */
    public static int prune(CommandSourceStack source, MineqttRuntime runtime) {
        int pruned = runtime.devices().pruneLoaded();
        source.sendSuccess(() -> Component.literal("Pruned " + pruned + " devices whose block is gone"), true);
        return pruned;
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
        runtime.mqtt().publish(Outbound.raw(topic, ValueCodecs.utf8(payload), 0, origin));
        // Blocks on this server receive it either way; the broker only while connected.
        String delivery = runtime.mqtt().state().isConnected() ? "Published to: " : "Not connected to the broker, delivered on this server only: ";
        source.sendSuccess(() -> Component.literal(delivery + topic), false);
        return 1;
    }

    /**
     * {@code /mineqtt devices expose <device> on|off|default} (M2 plan D6), the twin of the Expose button: an op may set
     * any device, a holder of {@code mineqtt:ha.expose} one they may configure. The reply names the effect, which
     * {@code ha.expose} NONE, a type unknown to this MineQTT and a group hiding its members override.
     *
     * @param expose null to follow {@code ha.expose} again
     */
    private static int expose(CommandSourceStack source, MineqttRuntime runtime, DeviceRecord record, @Nullable Boolean expose) {
        if (!mayConfigure(source, record)) {
            source.sendFailure(Component.literal("You may not configure " + record.slug()));
            return 0;
        }
        runtime.devices().setExpose(record, expose);
        HaExpose policy = MineQTTConfig.haExpose(runtime.server().isDedicatedServer());
        DeviceGroup group = record.group() == null ? null : runtime.registry().group(record.group());
        String setting = expose == null ? "follows ha.expose (" + policy + ")" : expose ? "is exposed" : "is hidden";
        String effect = runtime.discovery().exposed(record) ? "Home Assistant discovers it"
                : "Home Assistant does not discover it" + (policy == HaExpose.NONE ? ": ha.expose is NONE"
                : DeviceTypes.get(record.type()) == null ? ": its type is unknown to this MineQTT"
                : group != null && !group.exposeMembers() ? ": group " + group.slug() + " hides its members" : "");
        source.sendSuccess(() -> Component.literal(record.slug() + " " + setting + "; " + effect), true);
        return 1;
    }

    /**
     * {@code /mineqtt devices set <device> <json>}, the twin of the device's {@code /set} topic: the JSON object goes
     * through {@link DeviceRuntime#set} as a player's command, so a loaded device applies it, an unloaded actuator queues
     * it and an unloaded sensor ignores it, as for Home Assistant. The reply says which.
     */
    private static int set(CommandSourceStack source, MineqttRuntime runtime, DeviceRecord record, String text) {
        JsonObject command;
        try {
            JsonElement json = JsonParser.parseString(text);
            if (!json.isJsonObject()) throw new JsonParseException("not an object");
            command = json.getAsJsonObject();
        } catch (JsonParseException e) {
            source.sendFailure(Component.literal("A command is a JSON object like the one on " + runtime.topics().device(record.slug())
                    + "/set, e.g. {\"state\":\"ON\"}"));
            return 0;
        }
        return reply(source, runtime.devices().set(record, command, source.getPlayer()));
    }

    /**
     * {@code /mineqtt devices setting} and {@code raw} (M2-6), the twins of the screen's {@code DeviceConfigC2S}: the same
     * validation and setters, with an operator's rights. A topic typed here is judged for the operator, their bypass of
     * the allowlist included, and for the device's owner.
     */
    private static int configure(CommandSourceStack source, MineqttRuntime runtime, DeviceRecord record, String key, JsonElement value) {
        return reply(source, runtime.devices().configure(record, key, value, editor(source)));
    }

    /**
     * {@code /mineqtt devices bind <device> target <target> <entity>|none} (M3 plan D19, M3-14): {@code devices setting
     * <device> twin.<target> <entity>} in short. The target is named as its setting names it ({@code target},
     * {@code left}) or by its out-port ({@code powered}).
     */
    private static int bindTarget(CommandSourceStack source, MineqttRuntime runtime, DeviceRecord record, String target, String entity) {
        Optional<TwinAdapter> adapter = type(record).flatMap(DeviceType::twin);
        Optional<String> name = adapter.flatMap(twin -> twin.targets().containsKey(target) ? Optional.of(target)
                : twin.targets().entrySet().stream().filter(entry -> entry.getValue().equals(target)).map(Map.Entry::getKey).findFirst());
        if (name.isEmpty()) {
            String known = adapter.map(twin -> String.join(", ", twin.targets().keySet())).filter(names -> !names.isEmpty()).orElse(null);
            source.sendFailure(Component.literal(record.slug() + " has no target " + target + (known == null ? "" : " (its targets: " + known + ")")));
            return 0;
        }
        return configure(source, runtime, record, LinkSetting.TWIN_PREFIX + name.get(), new JsonPrimitive(entity));
    }

    /**
     * {@code /mineqtt devices access <device> private|claim|public} (M2-11), the twin of the Access tab's mode button:
     * {@code devices setting <device> access <mode>} in short. The device must be loaded, as its mode is kept in its block.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> accessMode(AccessMode mode) {
        return Commands.literal(mode.getSerializedName()).executes(context -> withDevice(context, (runtime, record) ->
                configure(context.getSource(), runtime, record, LinkSetting.ACCESS.key(), new JsonPrimitive(mode.getSerializedName()))));
    }

    // devices raw <device> channel|topic|on|off <text>: the text without one pair of quotes around it, so "" is empty.
    private static int rawText(CommandContext<CommandSourceStack> context, LinkSetting<String> setting, String argument) {
        String text = ValueCodecs.unquote(StringArgumentType.getString(context, argument).strip());
        return rawValue(context, setting, new JsonPrimitive(text));
    }

    private static int rawValue(CommandContext<CommandSourceStack> context, LinkSetting<?> setting, JsonElement value) {
        return withDevice(context, (runtime, record) -> configure(context.getSource(), runtime, record, setting.key(), value));
    }

    /** {@code /mineqtt devices action <device> <action> [value]} (M2-6), the twin of the screen's {@code DeviceActionC2S}. */
    private static int action(CommandContext<CommandSourceStack> context, String value) {
        return withDevice(context, (runtime, record) -> reply(context.getSource(),
                runtime.devices().action(record, StringArgumentType.getString(context, "action"), value, editor(context.getSource()))));
    }

    // A setting's value as typed: text for a text key, else read like a /set/<field> payload.
    private static JsonElement settingValue(DeviceRecord record, String key, String text) {
        return type(record).map(type -> DeviceConfig.commandValue(type, key, text)).orElseGet(() -> new JsonPrimitive(text));
    }

    // The command twins act with an operator's rights; the ACL judges a typed topic for the source.
    private static DeviceRuntime.Editor editor(CommandSourceStack source) {
        return new DeviceRuntime.Editor(DeviceView.Grants.ALL, AclContext.of(source), source.getPlayer(),
                TwinRules.Rights.of(source.permissions(), source.getServer().isDedicatedServer())
                        .withTopics(TwinRules.topics(source.getServer(), AclContext.of(source))));
    }

    private static int reply(CommandSourceStack source, DeviceRuntime.Configured result) {
        switch (result.result()) {
            case REFUSED -> {
                source.sendFailure(Component.literal(result.detail()));
                return 0;
            }
            case UNCHANGED -> source.sendSuccess(() -> Component.literal(result.detail()), false);
            case APPLIED, QUEUED -> source.sendSuccess(() -> Component.literal(result.detail()), true);
        }
        return 1;
    }

    /**
     * {@code /mineqtt devices retry <device|x y z>} (M2 plan D7): queues a faulted device again, so its attach runs on the
     * next tick. A device that is not faulted is left alone. The position form also reaches a device that faulted before
     * it was registered.
     */
    private static int retry(CommandSourceStack source, MineqttRuntime runtime, GlobalPos pos) {
        DeviceRecord record = runtime.registry().at(pos);
        String device = record != null ? record.slug() : "the device at " + describe(pos);
        Optional<DeviceRuntime.Fault> fault = runtime.devices().fault(pos);
        return switch (runtime.devices().retry(pos)) {
            case NOT_FAULTED -> {
                String answer = record != null ? record.slug() + " is not faulted" : "Nothing at " + describe(pos) + " is faulted";
                source.sendSuccess(() -> Component.literal(answer), false);
                yield 0;
            }
            case NOT_LOADED -> {
                source.sendFailure(Component.literal("The chunk of " + device + " is not loaded; it is retried when the chunk loads ("
                        + fault.orElseThrow().text() + ")"));
                yield 0;
            }
            case GONE -> {
                source.sendSuccess(() -> Component.literal("No device is left at " + describe(pos) + "; its fault is cleared"), true);
                yield 1;
            }
            case QUEUED -> {
                source.sendSuccess(() -> Component.literal("Retrying " + device + " on the next tick; /mineqtt status shows whether it is still faulted"), true);
                yield 1;
            }
        };
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
                .append(faults(runtime))
                .append("\nWires: ").append(registry.wires().size()).append("; messages ").append(devices.flowCounters().summary())
                .append("\nHome Assistant: ").append(MineQTTConfig.haExpose(runtime.server().isDedicatedServer()))
                .append(" under ").append(MineQTTConfig.discoveryPrefix).append(", ").append(runtime.discovery().discovered())
                .append(" devices and groups discovered, ").append(runtime.discovery().migration().pending()).append(" v1 discoveries to migrate")
                .append("\nTwin: ").append(MineQTTConfig.twinSummary(runtime.server().isDedicatedServer()))
                .append("\nDropped: ").append(stats.droppedInbound()).append(" inbound, ").append(stats.droppedOutbound()).append(" outbound")
                .append("; queued: ").append(stats.queuedInbound()).append(" inbound, ").append(stats.outboxSize()).append(" in the outbox");
        if (runtime.server().isDedicatedServer()) {
            int pauseSeconds = runtime.pauseWhenEmptySeconds();
            text.append("\nServer: ");
            if (runtime.pausedWhileEmpty()) {
                text.append("paused (empty): MQTT handled between ticks; repeaters, pistons and light wait until it resumes");
            } else {
                text.append(pauseSeconds > 0 ? "ticking" : "ticking, never pauses");
            }
            if (runtime.pauseOverridden()) {
                text.append(" (server.keepTickingWhenEmpty overrides pause-when-empty-seconds=").append(runtime.configuredPauseWhenEmptySeconds()).append(")");
            } else {
                text.append(" (pause-when-empty-seconds=").append(pauseSeconds).append(")");
            }
        }
        if (runtime.protocol() == ProtocolVersion.MQTT_3_1_1) {
            text.append("\nMQTT 3.1.1 cannot announce a maximum packet size: set max_packet_size (Mosquitto 2) or message_size_limit"
                    + " (Mosquitto 1.x) on the broker to keep huge messages away");
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
                    .append(runtime.devices().isAttached(record.id()) ? "loaded" : runtime.devices().fault(record.pos()).isPresent() ? "faulted" : "unloaded")
                    .append(" at ").append(describe(record));
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
                + (type == null ? "" : "\nPorts in: " + ports(type, Port.Direction.IN) + "\nPorts out: " + ports(type, Port.Direction.OUT))
                + "\nPosition: " + describe(record) + (runtime.devices().isAttached(record.id()) ? ", loaded" : ", unloaded")
                + runtime.devices().fault(record.pos()).map(fault -> "\nFaulted: " + fault.text() + "; /mineqtt devices retry " + record.slug()).orElse("")
                + "\nOwner: " + (record.owner() == null ? "unknown" : record.owner()) + ", access " + record.access().getSerializedName()
                + "\nExpose: " + (record.expose() == null ? "default (" + MineQTTConfig.haExpose(runtime.server().isDedicatedServer()) + ")" : record.expose())
                + (record.discoveryHash() != null ? ", discovered as " + record.objectId() : ", not discovered")
                + (record.discoveryCleanup() ? " (Home Assistant removes its old entities at its next start)" : "")
                + (record.legacyUniqueId() == null ? "" : "\nv1 unique_id: " + record.legacyUniqueId()
                        + (runtime.registry().legacyDiscoveryMigrated(record.legacyUniqueId()) ? " (migrated)" : " (migration pending)"))
                + "\nGroup: " + (group == null ? "none" : group.slug())
                + (runtime.devices().legacyItems(record.id()) > 0
                ? "\nLegacy items: " + runtime.devices().legacyItems(record.id()) + " from its old slots; /mineqtt devices eject " + record.slug() : "")
                + runtime.devices().status(record.id()).map(status -> "\nStatus: " + status).orElse("")
                + twinInfo(runtime, record)
                + "\nState topic: " + runtime.topics().device(record.slug())
                + "\nMessages: " + runtime.devices().trafficSummary(record.id())
                + "\nLast state: " + DeviceRuntime.shown(record.lastState() == null ? "none" : record.lastState().toString())
                + (record.pending() == null ? "" : "\nQueued: " + DeviceRuntime.shown(record.pending().toString()));
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /**
     * {@code devices info}'s twin line (M3 plan D6): what a bound device mirrors, how its source is doing and since when;
     * an unloaded device reads nothing (D17).
     */
    private static String twinInfo(MineqttRuntime runtime, DeviceRecord record) {
        Binding binding = record.twin();
        if (binding == null) return "";
        if (!binding.bound()) return targetInfo(runtime, record, binding);
        StringBuilder text = new StringBuilder("\nTwin: mirrors ").append(binding.describeSource());
        binding.sourceTopic().ifPresent(topic -> text.append(" on ").append(topic));
        Optional<TwinHealth> health = runtime.devices().twinHealth(record.id());
        if (health.isEmpty()) {
            text.append(binding.active() ? ", reads nothing while unloaded" : ", refused: " + binding.refused());
            return text.toString();
        }
        text.append(", ").append(health.get().key());
        if (!binding.active()) text.append(": ").append(binding.refused());
        if (runtime.devices().twinPaused(record.id())) text.append(" (resume with /mineqtt devices bind ").append(record.slug()).append(" resume)");
        if (runtime.twinRequests().pending(record.id())) text.append(binding.preset().isPresent() ? ", a request waits for the device" : ", a request waits for Home Assistant");
        runtime.twinRequests().answer(record.id()).ifPresent(answer -> text.append(", the control blueprint last answered ").append(answer.status()));
        rateInfo(runtime, record, text);
        binding.sourceEntity().flatMap(runtime.twin()::document).or(() -> runtime.twin().presetDocument(binding)).ifPresent(document -> text.append(" (")
                .append(document.state().known() ? "state " + DeviceRuntime.shown(document.state().wire()) : document.state() instanceof StatestreamValue.Cleared ? "cleared" : document.state().wire())
                .append(document.lastUpdated() == null ? "" : ", as of " + HaTime.format(document.lastUpdated())).append(")"));
        return text.append(targetInfo(runtime, record, binding)).toString();
    }

    /**
     * The targets part of {@code devices info}'s twin lines (M3-14): what each out-port switches and what the twin knows of
     * that entity, and for a device with targets only whether its binding is refused, paused or waiting.
     */
    private static String targetInfo(MineqttRuntime runtime, DeviceRecord record, Binding binding) {
        if (binding.targets().isEmpty()) return "";
        StringBuilder text = new StringBuilder("\nTwin targets: ");
        text.append(String.join("; ", binding.targets().entrySet().stream().map(target -> {
            String entity = Binding.describe(target.getValue());
            HaCalls.Mirror mirror = target.getValue() instanceof Endpoint.HaEntityRef end ? runtime.twin().mirror(end.entityId()) : HaCalls.Mirror.NONE;
            return target.getKey() + " switches " + entity + (mirror.freshness() == HaCalls.Mirror.Freshness.NONE ? ""
                    : " (" + mirror.freshness().name().toLowerCase(Locale.ROOT) + ", state " + DeviceRuntime.shown(mirror.state()) + ")");
        }).toList()));
        if (binding.bound()) return text.toString();
        if (!binding.active()) text.append("; refused: ").append(binding.refused());
        // A target whose entity left the catalog is not switched until Home Assistant labels it again (wave 5 finding 3).
        if (runtime.devices().twinHealth(record.id()).orElse(null) == TwinHealth.NOT_SHARED) {
            text.append("; not_shared: ").append(TwinRules.unsharedTarget(binding, runtime.twin()::readable).orElse("an entity"))
                    .append(" is no longer shared by Home Assistant, so presses for it are not sent");
        }
        if (runtime.devices().twinPaused(record.id())) text.append(" (paused; resume with /mineqtt devices bind ").append(record.slug()).append(" resume)");
        if (runtime.twinRequests().pending(record.id())) text.append(", a request waits for Home Assistant");
        runtime.twinRequests().answer(record.id()).ifPresent(answer -> text.append(", the control blueprint last answered ").append(answer.status()));
        rateInfo(runtime, record, text);
        return text.toString();
    }

    // The device's requests the rates refused (wave 5 finding 1): how many, and the last one with its reason and age.
    private static void rateInfo(MineqttRuntime runtime, DeviceRecord record, StringBuilder text) {
        runtime.devices().twinRateRefusals(record.id()).ifPresent(rate -> text.append("; ").append(rate.refused())
                .append(rate.refused() == 1 ? " request" : " requests").append(" refused by the rate, the last for ").append(rate.target())
                .append(" ").append((Util.getMillis() - rate.at()) / 1000).append(" s ago (").append(rate.reason()).append(")"));
    }

    /** {@code /mineqtt twin status} (M3 plan D19): the twin's config line, what it subscribes and folds, and the bound devices' health. */
    private static int twinStatus(CommandSourceStack source, MineqttRuntime runtime) {
        TwinRuntime.Status status = runtime.twin().status(runtime.devices().attachedBindings());
        EntityDocuments.Counters counters = status.counters();
        StringBuilder text = new StringBuilder("Twin: ").append(MineQTTConfig.twinSummary(runtime.server().isDedicatedServer()))
                .append("\nMirroring: ").append(status.devices()).append(" loaded devices, ").append(status.targetDevices())
                .append(" with targets, ").append(status.entities()).append(" entities, ")
                .append(status.subscriptions()).append(" statestream subscriptions");
        if (status.refusedLeaves() > 0) text.append(", ").append(status.refusedLeaves()).append(" refused by the ACL");
        text.append("\nKnown: ").append(status.documents()).append(" entity documents; applied ").append(status.applied()).append(" times");
        text.append("\nMQTT devices: ").append(status.topicDevices()).append(" loaded devices on ").append(status.topics()).append(" topics");
        if (status.refusedTopics() > 0) text.append(", ").append(status.refusedTopics()).append(" refused by the ACL");
        text.append("; ").append(BindingPresets.all().size()).append(" presets loaded");
        if (!BindingPresets.problems().isEmpty()) text.append(", ").append(BindingPresets.problems().size()).append(" skipped (see the server log)");
        TwinRuntime.CatalogStatus catalog = status.catalog();
        if (catalog.status() != null) {
            text.append("\nCatalog: ").append(catalog.entities()).append(" entities shared, generated ")
                    .append(catalog.status().generated() == null ? "at an unknown time" : HaTime.format(catalog.status().generated()));
            if (catalog.stale()) text.append(" - older than 30 min: is the MineQTT catalog automation on?");
        } else {
            text.append("\nCatalog: none").append(catalog.open() ? " received" : MineQTTConfig.twinCatalog ? " yet" : " (twin.catalog is off)")
                    .append(catalog.scanOpen() ? "; the statestream scan lists " + catalog.scanned() + " entities" : "")
                    .append("; install the MineQTT catalog blueprint for a complete list and label control");
        }
        if (catalog.scanOverflow() > 0) text.append("; ").append(catalog.scanOverflow()).append(" entities over twin.catalogMax left out");
        if (!catalog.refusals().isEmpty()) {
            text.append("\nCatalog lists refused: ").append(String.join(", ", catalog.refusals().entrySet().stream()
                    .map(entry -> entry.getKey() + " (" + (entry.getValue() == CatalogParser.Refusal.TOO_LARGE ? "larger than 64 KiB" : "not a JSON list") + ")")
                    .toList()));
        }
        if (!catalog.drifting().isEmpty()) {
            text.append("\nNo live updates: ").append(String.join(", ", catalog.drifting())).append(" - add them to the mqtt_statestream include");
        }
        text.append("\nHealth: ").append(status.health().isEmpty() ? "no bound devices loaded"
                : String.join(", ", status.health().entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey().key()).toList()));
        TwinRequests.Status requests = runtime.twinRequests().status();
        text.append("\nRequests: ").append(requests.sent()).append(" sent, ").append(requests.held()).append(" held for the rate, ")
                .append(requests.skipped()).append(" not needed; refused: ").append(requests.refused().isEmpty() ? "none"
                        : String.join(", ", requests.refused().entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey().name().toLowerCase(Locale.ROOT)).toList()));
        if (!requests.roots().isEmpty()) {
            text.append(" (roots: ").append(String.join(", ", requests.roots().entrySet().stream()
                    .map(entry -> entry.getValue() + " " + entry.getKey().name().toLowerCase(Locale.ROOT)).toList())).append(")");
        }
        text.append("\nWaiting: ").append(requests.pending()).append(" for Home Assistant, ").append(requests.waiting()).append(" for the rate; ")
                .append(requests.paused()).append(requests.paused() == 1 ? " device" : " devices").append(" paused by the breaker");
        text.append("\nAnswers: ").append(requests.answers().isEmpty() ? "none" : String.join(", ", requests.answers().entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey()).toList()));
        if (requests.unmatched() > 0 || requests.droppedAnswers() > 0) {
            text.append("; ").append(requests.unmatched()).append(" for no waiting request, ").append(requests.droppedAnswers()).append(" dropped (retained, oversized or malformed)");
        }
        text.append("\nIgnored: ").append(counters.ignoredTopics()).append(" topics, ").append(counters.oversize()).append(" oversize, ")
                .append(counters.entityOverflow()).append(" over the entity limit, ").append(counters.attributeOverflow()).append(" over the attribute limit, ")
                .append(counters.badTimestamps()).append(" bad timestamps");
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return status.devices();
    }

    /**
     * {@code /mineqtt twin show <entity>} (M3 plan D19): one entity as the twin knows it, and the devices that mirror it.
     * Only an entity {@code twin.allowedEntities} lets through, which nobody bypasses (D13).
     */
    private static int twinShow(CommandSourceStack source, MineqttRuntime runtime, String entity) {
        if (!Endpoint.ENTITY_ID.matcher(entity).matches()) {
            source.sendFailure(Component.literal("'" + entity + "' is not a Home Assistant entity id, like light.kitchen"));
            return 0;
        }
        if (!MineQTTConfig.twinAllowedEntities(runtime.server().isDedicatedServer()).matches(entity)) {
            source.sendFailure(Component.literal(entity + " is not in twin.allowedEntities"));
            return 0;
        }
        List<String> devices = runtime.devices().mirroring(entity).stream().map(DeviceRecord::slug).toList();
        Optional<EntityDocument> document = runtime.twin().document(entity);
        StringBuilder text = new StringBuilder(entity).append(devices.isEmpty() ? ": no loaded device mirrors it" : ": mirrored by " + String.join(", ", devices));
        if (document.isEmpty()) {
            text.append("\nNothing known yet").append(devices.isEmpty() ? " (only mirrored entities are read)" : "; is it in the mqtt_statestream include?");
        } else {
            EntityDocument known = document.get();
            text.append("\nState: ").append(known.state() instanceof StatestreamValue.Cleared ? "cleared" : DeviceRuntime.shown(known.state().wire()))
                    .append(", ").append(known.freshness().name().toLowerCase(Locale.ROOT)).append(" from ").append(known.origin().name().toLowerCase(Locale.ROOT))
                    .append(runtime.twin().readable(entity) ? "" : ", not shared (the catalog does not list it)")
                    .append("\nLast updated: ").append(known.lastUpdated() == null ? "unknown" : HaTime.format(known.lastUpdated()))
                    .append("\nAttributes: ").append(known.attributes().isEmpty() ? "none" : DeviceRuntime.shown(attributes(known).toString()));
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return document.isPresent() ? 1 : 0;
    }

    /**
     * {@code /mineqtt twin ping} (M3 plan D12): asks the control blueprint to answer; the answer, or that none came within
     * 5 s, reaches the source later.
     */
    private static int twinPing(CommandSourceStack source, MineqttRuntime runtime) {
        // The answer comes later, so it is told to the operators and the log as well: an RCON source is gone by then.
        Optional<String> refusal = runtime.twinRequests().ping(answer -> source.sendSuccess(() -> Component.literal("Control blueprint: " + answer), true));
        if (refusal.isPresent()) {
            source.sendFailure(Component.literal("No ping: " + refusal.get()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Pinged the control blueprint on " + runtime.topics().haRequest() + "; waiting for its answer"), false);
        return 1;
    }

    /**
     * {@code /mineqtt twin entities [query]} (M3 plan D4): the entities the read gate lets through, from the catalog (or the
     * statestream scan without one), best match first, at most {@value #MAX_ENTITY_ROWS}. Mirrored entities Home
     * Assistant changes without statestream publishing them carry the drift warning.
     */
    private static int twinEntities(CommandSourceStack source, MineqttRuntime runtime, String query) {
        if (MineQTTConfig.twinOffReason(runtime.server().isDedicatedServer()).isPresent()) {
            source.sendFailure(Component.literal("The twin is " + MineQTTConfig.twinSummary(runtime.server().isDedicatedServer())));
            return 0;
        }
        List<EntitySearch.Candidate> found = runtime.twin().entities(query, List.of(), Set.of(), MAX_ENTITY_ROWS);
        TwinRuntime.CatalogStatus catalog = runtime.twin().status(List.of()).catalog();
        StringBuilder text = new StringBuilder(catalog.status() != null ? "From the catalog (" + catalog.entities() + " entities)"
                : catalog.scanOpen() ? "No catalog; from the statestream scan (" + catalog.scanned() + " entities)" : "No catalog yet");
        if (found.isEmpty()) {
            text.append(query.isBlank() ? "\nNo Home Assistant entities yet. In Home Assistant, enable mqtt_statestream (base "
                    + MineQTTConfig.twinStatestreamBase + "), import the MineQTT catalog blueprint and label entities 'MineQTT read'."
                    : "\nNothing matches '" + DeviceRuntime.shown(query) + "'");
        }
        for (EntitySearch.Candidate candidate : found) {
            text.append("\n").append(candidate.entityId());
            if (!candidate.name().equals(candidate.entityId())) text.append(" - ").append(DeviceRuntime.shown(candidate.name()));
            if (!candidate.area().isEmpty()) text.append(" (").append(DeviceRuntime.shown(candidate.area())).append(")");
            text.append(": ").append(candidate.state() instanceof StatestreamValue.Cleared ? "cleared" : DeviceRuntime.shown(candidate.state().wire()));
            if (candidate.control()) text.append(", control");
            if (runtime.twin().drifting(candidate.entityId())) text.append(", no live updates: add it to the mqtt_statestream include");
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return found.size();
    }

    /** {@code /mineqtt twin refresh} (M3 plan D3): asks the catalog blueprint to publish the entity catalog anew. */
    private static int twinRefresh(CommandSourceStack source, MineqttRuntime runtime) {
        if (!runtime.twinRequests().sync("command")) {
            source.sendFailure(Component.literal("Not sent: the bridge is offline"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Asked Home Assistant for a fresh entity catalog on " + runtime.topics().haSync()), true);
        return 1;
    }

    private static JsonObject attributes(EntityDocument document) {
        JsonObject attributes = new JsonObject();
        document.attributes().forEach(attributes::add);
        return attributes;
    }

    // A type's ports of one direction, as `devices info` lists them: "power (level), mode (enum level|switch)".
    private static String ports(DeviceType type, Port.Direction direction) {
        List<String> ports = type.ports().stream().filter(port -> port.direction() == direction).map(Port::describe).toList();
        return ports.isEmpty() ? "none" : String.join(", ", ports);
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
                    .append(group.exposeMembers() ? ", members shown to Home Assistant" : "")
                    .append(": ").append(members.isEmpty() ? "no members" : String.join(", ", members));
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return groups.size();
    }

    /**
     * {@code /mineqtt group rename <group> <name>} (M2-17), the twin of the group page's rename: the slug and the topics
     * follow the name, the unique_ids stay. One pair of quotes around the name is dropped (M2 plan D6).
     */
    private static int renameGroup(CommandContext<CommandSourceStack> context, MineqttRuntime runtime, DeviceGroup group) {
        String name = name(context);
        if (name.isEmpty()) {
            context.getSource().sendFailure(Component.literal("A group needs a name"));
            return 0;
        }
        if (name.equals(group.name())) {
            context.getSource().sendFailure(Component.literal("Group " + group.slug() + " already is called " + name));
            return 0;
        }
        String oldSlug = group.slug();
        runtime.devices().renameGroup(group, name);
        context.getSource().sendSuccess(() -> Component.literal("Renamed group " + oldSlug + " to " + group.name() + " ("
                + runtime.topics().group(group.slug()) + ")"), true);
        return 1;
    }

    /**
     * {@code /mineqtt group expose-members <group> on|off} (M2-17), the twin of the group page's members button: whether
     * Home Assistant discovers the members as devices of their own beside the group, as their own exposure allows.
     */
    private static int exposeMembers(CommandSourceStack source, MineqttRuntime runtime, DeviceGroup group, boolean expose) {
        String shown = expose ? "shows its members to Home Assistant" : "hides its members from Home Assistant";
        if (group.exposeMembers() == expose) {
            source.sendFailure(Component.literal("Group " + group.slug() + " already " + shown));
            return 0;
        }
        runtime.devices().setExposeMembers(group, expose);
        source.sendSuccess(() -> Component.literal("Group " + group.slug() + " " + shown), true);
        return 1;
    }

    /** {@code /mineqtt wires list [device|group.<group>]}: every wire, or those with an end at one device or group. */
    private static int listWires(CommandSourceStack source, MineqttRuntime runtime, @Nullable String node) {
        Collection<Wire> wires;
        if (node == null) {
            wires = runtime.registry().wires();
        } else {
            Optional<UUID> id = node.startsWith(GROUP_PREFIX)
                    ? Optional.ofNullable(runtime.registry().groupBySlug(node.substring(GROUP_PREFIX.length()))).map(DeviceGroup::id)
                    : Optional.ofNullable(runtime.registry().bySlug(node)).map(DeviceRecord::id);
            if (id.isEmpty()) {
                source.sendFailure(Component.literal("No device or group " + node));
                return 0;
            }
            wires = runtime.registry().wiresOf(id.get());
        }
        if (wires.isEmpty()) {
            source.sendSuccess(() -> Component.literal(node == null ? "No wires" : "No wires at " + node), false);
            return 0;
        }
        StringBuilder text = new StringBuilder(wires.size() + (wires.size() == 1 ? " wire:" : " wires:"));
        for (Wire wire : wires) {
            text.append("\n").append(runtime.devices().describe(wire)).append(" [").append(wire.id()).append("]");
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return wires.size();
    }

    /**
     * {@code /mineqtt wires add <from> <to> [mapping]} (M2 plan D9), the twin of the Configurator's Link mode: a wire from
     * a device's out-port to an in-port of a device or group. Without a mapping it gets {@link DefaultWiring#mapping}'s. A
     * player owns the wires they make, which count against {@code limits.maxWiresPerPlayer} as Link mode's do; the
     * console's and command blocks' belong to the server ({@link Util#NIL_UUID}) and count for nobody.
     */
    private static int addWire(CommandContext<CommandSourceStack> context, MineqttRuntime runtime, @Nullable String mappingName) {
        CommandSourceStack source = context.getSource();
        Optional<Endpoint> from = endpoint(source, runtime, StringArgumentType.getString(context, "from"));
        Optional<Endpoint> to = from.isEmpty() ? Optional.empty() : endpoint(source, runtime, StringArgumentType.getString(context, "to"));
        if (to.isEmpty()) return 0;
        Mapping mapping;
        if (mappingName != null) {
            Optional<Mapping> named = Stream.of(Mapping.values()).filter(candidate -> mappingName(candidate).equals(mappingName)).findFirst();
            if (named.isEmpty()) {
                source.sendFailure(Component.literal("No mapping " + mappingName + "; one of "
                        + String.join(", ", Stream.of(Mapping.values()).map(MineQTTCommands::mappingName).toList())));
                return 0;
            }
            mapping = named.get();
        } else {
            Optional<Port> out = runtime.devices().port(from.get(), Port.Direction.OUT);
            Optional<Port> in = runtime.devices().port(to.get(), Port.Direction.IN);
            mapping = out.isPresent() && in.isPresent() ? DefaultWiring.mapping(out.get(), in.get()) : Mapping.FOLLOW;
        }
        ServerPlayer player = source.getPlayer();
        Wire wire = new Wire(UUID.randomUUID(), from.get(), to.get(), mapping, Scope.WIRELESS, player == null ? Util.NIL_UUID : player.getUUID(), true);
        Optional<String> refusal = runtime.devices().addWire(wire, player == null ? device -> true : runtime.devices().configurer(player));
        if (refusal.isPresent()) {
            source.sendFailure(Component.literal("Not wired " + runtime.devices().describe(from.get()) + " to "
                    + runtime.devices().describe(to.get()) + ": " + refusal.get()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Wired " + runtime.devices().describe(wire) + " [" + wire.id() + "]"), true);
        return 1;
    }

    /** {@code /mineqtt wires remove <id>}: the id as {@code wires list} shows it, or enough of its start to name one wire. */
    private static int removeWire(CommandSourceStack source, MineqttRuntime runtime, String id) {
        String prefix = id.toLowerCase(Locale.ROOT);
        List<Wire> matches = runtime.registry().wires().stream().filter(wire -> wire.id().toString().startsWith(prefix)).toList();
        if (matches.size() != 1) {
            source.sendFailure(Component.literal(matches.isEmpty() ? "No wire " + id : id + " is the start of " + matches.size() + " wire ids; give more of it"));
            return 0;
        }
        String described = runtime.devices().describe(matches.getFirst());
        runtime.devices().removeWire(matches.getFirst().id());
        source.sendSuccess(() -> Component.literal("Removed wire " + described), true);
        return 1;
    }

    // <device>.<port> or group.<group>.<port>; the port follows the last dot. A failure is told to the source.
    private static Optional<Endpoint> endpoint(CommandSourceStack source, MineqttRuntime runtime, String text) {
        int dot = text.lastIndexOf('.');
        String node = dot < 0 ? text : text.substring(0, dot);
        String port = dot < 0 ? "" : text.substring(dot + 1);
        if (!Endpoint.isPortKey(port)) {
            source.sendFailure(Component.literal("Name a port as <device>.<port> or " + GROUP_PREFIX + "<group>.<port>, e.g. garage_input.powered: " + text));
            return Optional.empty();
        }
        if (node.startsWith(GROUP_PREFIX)) {
            DeviceGroup group = runtime.registry().groupBySlug(node.substring(GROUP_PREFIX.length()));
            if (group == null) source.sendFailure(Component.literal("No group " + node.substring(GROUP_PREFIX.length())));
            return Optional.ofNullable(group).map(found -> Endpoint.groupPort(found.id(), port));
        }
        DeviceRecord record = runtime.registry().bySlug(node);
        if (record == null) source.sendFailure(Component.literal("No device " + node));
        return Optional.ofNullable(record).map(found -> Endpoint.port(found.id(), port));
    }

    // A node's ports of one direction as /mineqtt wires names them: <node>.<port>.
    private static Stream<String> portNames(String node, Optional<DeviceType> type, Port.Direction direction) {
        return type.stream().flatMap(found -> found.ports().stream()).filter(port -> port.direction() == direction).map(port -> node + "." + port.key());
    }

    private static String mappingName(Mapping mapping) {
        return mapping.name().toLowerCase(Locale.ROOT);
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

    // Ops, the console and command blocks may configure every device; any other player what DeviceAccess allows.
    private static boolean mayConfigure(CommandSourceStack source, DeviceRecord record) {
        ServerPlayer player = source.getPlayer();
        return AclContext.isOp(source.permissions()) || player != null && DeviceAccess.check(player, record, MineqttPermission.CONFIGURE);
    }

    // The name argument without the quotes a player typed around it (M2 plan D6), sanitized; empty when nothing is left.
    private static String name(CommandContext<CommandSourceStack> context) {
        return ValueCodecs.sanitizeText(ValueCodecs.unquote(StringArgumentType.getString(context, "name").strip())).strip();
    }

    private static Optional<MineqttRuntime> running(CommandSourceStack source) {
        return MineqttRuntime.current().filter(runtime -> runtime.server() == source.getServer());
    }

    private static Optional<DeviceType> type(DeviceRecord record) {
        return Optional.ofNullable(DeviceTypes.get(record.type()));
    }

    // The type of the device a command names in its device argument, for the suggestions of the arguments after it.
    private static Optional<DeviceType> namedType(CommandContext<CommandSourceStack> context) {
        String slug = StringArgumentType.getString(context, "device");
        return running(context.getSource()).map(runtime -> runtime.registry().bySlug(slug)).flatMap(MineQTTCommands::type);
    }

    private static int withRuntime(CommandContext<CommandSourceStack> context, Function<MineqttRuntime, Integer> action) {
        Optional<MineqttRuntime> runtime = running(context.getSource());
        if (runtime.isEmpty()) {
            context.getSource().sendFailure(Component.literal("MineQTT is not running on this server"));
            return 0;
        }
        try {
            return action.apply(runtime.get());
        } finally {
            // A command run while the server is paused (RCON) may leave work for the next tick.
            runtime.get().workQueued();
        }
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

    // The faulted devices for status: how many, and the first few with why.
    private static String faults(MineqttRuntime runtime) {
        Map<GlobalPos, DeviceRuntime.Fault> faults = runtime.devices().faults();
        if (faults.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\nFaulted: ").append(faults.size()).append(", offline until /mineqtt devices retry or their chunk loads again");
        faults.entrySet().stream().limit(MAX_SHOWN_FAULTS)
                .forEach(fault -> text.append("\n  ").append(deviceAt(runtime, fault.getKey())).append(": ").append(fault.getValue().text()));
        if (faults.size() > MAX_SHOWN_FAULTS) text.append("\n  and ").append(faults.size() - MAX_SHOWN_FAULTS).append(" more");
        return text.toString();
    }

    // The slug of the device registered at a position, else the position.
    private static String deviceAt(MineqttRuntime runtime, GlobalPos pos) {
        DeviceRecord record = runtime.registry().at(pos);
        return record != null ? record.slug() : describe(pos);
    }

    private static String describe(DeviceRecord record) {
        return describe(record.pos());
    }

    private static String describe(GlobalPos globalPos) {
        BlockPos pos = globalPos.pos();
        return globalPos.dimension().identifier() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}

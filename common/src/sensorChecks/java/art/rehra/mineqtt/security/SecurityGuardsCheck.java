package art.rehra.mineqtt.security;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.commands.MineQTTCommands;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceClipboard;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceGroup;
import art.rehra.mineqtt.device.DeviceGroups;
import art.rehra.mineqtt.device.DeviceLinks;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRegistry;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.DeviceTypes;
import art.rehra.mineqtt.device.DeviceView;
import art.rehra.mineqtt.device.GroupRules;
import art.rehra.mineqtt.device.LinkRules;
import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.RawLinkSpec;
import art.rehra.mineqtt.device.RawTopics;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.device.MirrorNoLoopCheck;
import art.rehra.mineqtt.device.TwinRules;
import art.rehra.mineqtt.device.TwinTestTypes;
import art.rehra.mineqtt.twin.BindingCodecs;
import art.rehra.mineqtt.twin.BindingPresets;
import art.rehra.mineqtt.twin.core.PresetCodec;
import art.rehra.mineqtt.twin.TwinAdapter;
import art.rehra.mineqtt.twin.TwinRequests;
import art.rehra.mineqtt.twin.core.Call;
import art.rehra.mineqtt.twin.core.HaCalls;
import art.rehra.mineqtt.flow.core.Root;
import art.rehra.mineqtt.mqtt.core.MqttLog;
import art.rehra.mineqtt.mqtt.core.OfflinePolicy;
import art.rehra.mineqtt.mqtt.core.Outbound;
import art.rehra.mineqtt.runtime.TopicScheme;
import art.rehra.mineqtt.twin.core.Binding;
import art.rehra.mineqtt.twin.core.ValuePath;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.RedstoneInput;
import art.rehra.mineqtt.device.types.RedstoneOutput;
import art.rehra.mineqtt.device.types.SmartLamp;
import art.rehra.mineqtt.device.types.SmartPlug;
import art.rehra.mineqtt.device.types.WallSwitch;
import art.rehra.mineqtt.flow.core.Endpoint;
import art.rehra.mineqtt.flow.core.Mapping;
import art.rehra.mineqtt.flow.core.Scope;
import art.rehra.mineqtt.flow.core.Wire;
import art.rehra.mineqtt.integrations.MineqttPermission;
import art.rehra.mineqtt.mqtt.core.TopicAcl;
import art.rehra.mineqtt.device.DeviceTraffic;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceClaimPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceConfigPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceRefusedPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceEjectPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceGroupPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceGroupsPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceRetryPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceStatusPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceLinksPayload;
import art.rehra.mineqtt.network.MineqttNetworking.DeviceUnlinkPayload;
import art.rehra.mineqtt.network.MineqttNetworking.GroupNamePayload;
import art.rehra.mineqtt.network.MineqttNetworking.GroupPromptPayload;
import art.rehra.mineqtt.network.MineqttNetworking.LinkChoicePayload;
import art.rehra.mineqtt.network.MineqttNetworking.LinkPickerPayload;
import art.rehra.mineqtt.network.MineqttNetworking.BridgeActionPayload;
import art.rehra.mineqtt.network.MineqttNetworking.ConsoleViewPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinResultsPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinSearchPayload;
import art.rehra.mineqtt.network.MineqttNetworking.TwinViewPayload;
import art.rehra.mineqtt.device.TwinView;
import art.rehra.mineqtt.twin.core.TwinHealth;
import art.rehra.mineqtt.ui.device.TwinPicker;
import art.rehra.mineqtt.mqtt.core.ConnectionState;
import art.rehra.mineqtt.ui.console.BridgeAction;
import art.rehra.mineqtt.ui.console.ConsoleView;
import art.rehra.mineqtt.security.C2SGuard.Facts;
import art.rehra.mineqtt.security.C2SGuard.Rejection;
import art.rehra.mineqtt.twin.core.EntityGlobs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The pure parts of the security layer, run by Gradle's verifySecurityGuards task: the token bucket and per-player
 * rate limits, the order of the {@link C2SGuard} checks, the field caps, the {@link DeviceAccess} decision table, who may
 * change the access mode and claim a device, what the Diagnostics tab shows of a device's traffic to whom, the
 * owner match by UUID and the {@code ownerUuid} key of blocks that are no devices, who may expose devices and reach which {@code /mineqtt} subcommand,
 * what the device config and action payloads and their command twins accept ({@link DeviceConfig}), typed Raw Link topics
 * and the wire bounds of the device payloads, the inventory of payloads (the retired blocks' are gone), who may make which wire with the Configurator's Link mode and how many,
 * who may open the Bridge Console and run its actions and what it lists to whom, the {@link NoticeGate} that tells a refusal once, the length cap of the device status the server sends to clients,
 * and that automation reaches no configuration slot. The game is bootstrapped for the vanilla permission, command, item
 * and block entity classes; no server is started.
 */
public final class SecurityGuardsCheck {
    private static final long SECOND = 1_000_000_000L;

    static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DeviceTypes.registerBuiltIn();
        tokenBucket();
        rateLimit();
        guardOrder();
        configuratorReach();
        fieldCaps();
        accessTable();
        accessChanges();
        traffic();
        legacyOwner();
        legacyOwnerKey();
        exposeRights();
        twinPermissions();
        deviceConfig();
        typedTopic();
        devicePayloads();
        payloadInventory();
        linkMode();
        groupMode();
        pasteRechecks();
        twinBindings();
        presetBindings();
        twinRequests();
        twinPicker();
        presetSearch();
        pickerAnswersAndRates();
        catalogTopics();
        bridgeConsole();
        noticeGate();
        statusCap();
        automationFaces();
        System.out.println("Security guard checks passed.");
    }

    /**
     * M3-11 (D3, D13 #5): the catalog lives under the statestream base, so no player, op or admin can forge it from
     * Minecraft (publishing there is hard-denied), only admins may listen to it, and the server itself mirrors it (the
     * catalog's and the scan's own subscriptions). An admin's broad allowlist changes none of it.
     */
    private static void catalogTopics() {
        UUID steve = UUID.fromString("6a1c0f4e-0b6e-4a43-9d76-2f4f6f0e5a11");
        List<String> everything = List.of("#");
        for (TopicAcl acl : List.of(new TopicAcl(new TopicAcl.Rules("mineqtt", "world", "homeassistant", "ha_statestream", everything, true, true)),
                new TopicAcl(new TopicAcl.Rules("mineqtt", "world", "homeassistant", "ha_statestream", TopicAcl.defaultRawAllow("mineqtt", "world"), false, false)))) {
            for (TopicAcl.Subject subject : List.of(new TopicAcl.Subject(steve, false, false), new TopicAcl.Subject(steve, true, false),
                    new TopicAcl.Subject(steve, true, true), TopicAcl.Subject.CONSOLE)) {
                for (String topic : List.of("ha_statestream/_mineqtt/catalog/light", "ha_statestream/_mineqtt/status", "ha_statestream/light/kitchen/state")) {
                    check(acl.checkPublish(subject, topic) == TopicAcl.Decision.DENY_STATESTREAM, "Nobody publishes " + topic + " from Minecraft: " + subject);
                }
            }
            check(acl.checkListen(new TopicAcl.Subject(steve, true, false), "ha_statestream/_mineqtt/#") == TopicAcl.Decision.DENY_STATESTREAM_LISTEN,
                    "Only admins listen to the catalog");
            for (String filter : List.of("ha_statestream/_mineqtt/catalog/+", "ha_statestream/_mineqtt/status", "ha_statestream/+/+/state",
                    "ha_statestream/+/+/friendly_name")) {
                check(acl.checkMirror(TopicAcl.Subject.CONSOLE, filter).allowed(), "The server mirrors " + filter);
            }
            check(acl.checkLegacy("ha_statestream/_mineqtt/status", false) != TopicAcl.Decision.ALLOWED, "A v1 block never listens to the catalog");
        }
    }

    /**
     * M3-13 (D4, D13, D19): the Twin tab's payloads and the picker's search. The view, the search and the answer are
     * bounded on the wire (query 64, rows 40, names 64, areas 32, values 32); a view for a player who may not configure
     * the device names no entity; Resume is a press needing CONFIGURE and never a stored value; a search answers only a
     * binder, and while the twin is off only why, without asking the twin; only readable entities in
     * {@code twin.allowedEntities} and, while the catalog decides, in the catalog are ever candidates; a domain chip never
     * widens a search; a search flood beyond the burst is dropped.
     */
    private static void twinPicker() {
        BlockPos pos = new BlockPos(3, -60, 4);
        // The search: the query has at most 64 characters on the wire.
        TwinSearchPayload search = new TwinSearchPayload(pos, "twin.entity", "kitchen", "light");
        RegistryFriendlyByteBuf searchBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinSearchPayload.CODEC.encode(searchBuf, search);
        check(TwinSearchPayload.CODEC.decode(searchBuf).equals(search), "A search crosses the wire");
        check(fails(() -> TwinSearchPayload.CODEC.encode(new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY),
                new TwinSearchPayload(pos, "twin.entity", "q".repeat(65), ""))), "A query over 64 characters does not encode");
        RegistryFriendlyByteBuf longQuery = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        BlockPos.STREAM_CODEC.encode(longQuery, pos);
        net.minecraft.network.VarInt.write(longQuery, TwinPicker.SearchKind.ENTITY.ordinal());
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(longQuery, "twin.entity");
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(longQuery, "q".repeat(500));
        check(fails(() -> TwinSearchPayload.CODEC.decode(longQuery)), "A forged query over 64 characters is not read");

        // The answer: at most 40 rows, each bounded.
        List<TwinPicker.Row> rows = new ArrayList<>();
        for (int i = 0; i < TwinPicker.MAX_ROWS; i++) {
            rows.add(new TwinPicker.Row("light.l" + i, "Lamp " + i, "Kitchen", "on", java.util.OptionalInt.of(0xFF8800), i % 2 == 0, i % 3 == 0, false));
        }
        TwinResultsPayload results = new TwinResultsPayload(pos, TwinPicker.SearchKind.ENTITY, "twin.entity", "lamp", "", rows, TwinPicker.Notice.NONE, "");
        RegistryFriendlyByteBuf resultsBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinResultsPayload.CODEC.encode(resultsBuf, results);
        check(TwinResultsPayload.CODEC.decode(resultsBuf).equals(results), "An answer of 40 rows crosses the wire");
        List<TwinPicker.Row> tooMany = new ArrayList<>(rows);
        tooMany.add(rows.getFirst());
        check(fails(() -> new TwinResultsPayload(pos, TwinPicker.SearchKind.ENTITY, "twin.entity", "", "", tooMany, TwinPicker.Notice.NONE, "")), "An answer of 41 rows is not made");
        RegistryFriendlyByteBuf forged = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        BlockPos.STREAM_CODEC.encode(forged, pos);
        net.minecraft.network.VarInt.write(forged, TwinPicker.SearchKind.ENTITY.ordinal());
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forged, "twin.entity");
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forged, "");
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forged, "");
        net.minecraft.network.VarInt.write(forged, 41);
        check(fails(() -> TwinResultsPayload.CODEC.decode(forged)), "A forged answer of 41 rows is not read");
        TwinPicker.Row long_ = new TwinPicker.Row("light.x", "n".repeat(500), "a".repeat(500), "v".repeat(500), java.util.OptionalInt.empty(), false, false, false);
        check(long_.name().length() == TwinPicker.MAX_NAME_LENGTH && long_.area().length() == TwinPicker.MAX_AREA_LENGTH
                && long_.value().length() == TwinPicker.MAX_VALUE_LENGTH, "A row's name, area and value are cut to 64, 32 and 32");
        check(fails(() -> new TwinPicker.Row("light.x", "", "", "", java.util.OptionalInt.of(0x1000000), false, false, false)), "A row's colour is 24 bits");
        TwinResultsPayload capped = new TwinResultsPayload(pos, TwinPicker.SearchKind.ENTITY, "k".repeat(100), "q".repeat(100), "c".repeat(100), List.of(), TwinPicker.Notice.OFF, "d".repeat(1000));
        check(capped.key().length() == 64 && capped.query().length() == TwinPicker.MAX_QUERY_LENGTH && capped.domain().length() == TwinPicker.MAX_DOMAIN_LENGTH && capped.detail().length() == TwinView.MAX_REASON_LENGTH,
                "An answer's key, query and detail are cut so it always encodes");

        // The view: entities only for a player who may configure the device.
        TwinView.Entity kitchen = new TwinView.Entity("light.kitchen", "Kitchen Light", "Kitchen");
        TwinView full = new TwinView(pos, true, Optional.of(kitchen), Optional.empty(), true, TwinView.Settings.DEFAULT, Map.of(), Optional.of(TwinHealth.LIVE), "", false,
                false, Optional.of("not_labelled"), "", true, true, true, 2, Optional.of("twin.entity"), 0, "");
        RegistryFriendlyByteBuf viewBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinViewPayload.CODEC.encode(viewBuf, new TwinViewPayload(full));
        check(TwinViewPayload.CODEC.decode(viewBuf).view().equals(full), "A view crosses the wire");
        check(fails(() -> new TwinView(pos, false, Optional.of(kitchen), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(), Optional.empty(), "", false, false,
                Optional.empty(), "", false, false, false, 0, Optional.empty(), 0, "")), "A view without details names no source");
        check(fails(() -> new TwinView(pos, false, Optional.of(TwinView.Entity.HIDDEN), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of("left", kitchen),
                Optional.empty(), "", false, false, Optional.empty(), "", false, false, false, 0, Optional.empty(), 0, "")), "nor a target");
        check(fails(() -> new TwinView(pos, false, Optional.of(TwinView.Entity.HIDDEN), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(), Optional.empty(),
                "light.kitchen is not in twin.allowedEntities", false, false, Optional.empty(), "", false, false, false, 0, Optional.empty(), 0, "")), "nor a refusal");
        check(fails(() -> new TwinView(pos, false, Optional.of(TwinView.Entity.HIDDEN), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(), Optional.empty(), "", false,
                false, Optional.of("accepted"), "", false, false, false, 0, Optional.empty(), 0, "")), "nor the control blueprint's answer");
        TwinView hidden = new TwinView(pos, false, Optional.of(TwinView.Entity.HIDDEN), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(), Optional.of(TwinHealth.LIVE),
                "", false, false, Optional.empty(), "", false, false, false, 0, Optional.empty(), 0, "");
        check(hidden.bound() && hidden.source().orElseThrow().id().isEmpty(), "A view without details says it is bound, not to what");
        TwinView.Entity longEntity = new TwinView.Entity("light." + "x".repeat(300), "n".repeat(100), "a".repeat(100));
        check(longEntity.id().length() == Binding.MAX_REFERENCE_LENGTH && longEntity.name().length() == TwinView.MAX_NAME_LENGTH
                && longEntity.area().length() == TwinView.MAX_AREA_LENGTH, "A view's entity texts are cut to their caps");

        // Resume: a press that needs CONFIGURE, the command twin of devices bind <device> resume; never pasted.
        check(LinkSetting.TWIN_RESUME.gate() == LinkSetting.Gate.CONFIGURE && DeviceConfig.permission(SmartLamp.TYPE, "twin.resume") == MineqttPermission.CONFIGURE,
                "Resume needs CONFIGURE at the guard and the gate");
        check(DeviceConfig.parse(SmartLamp.TYPE, "twin.resume", new JsonPrimitive("PRESS"), new DeviceView.Grants(false, true, false, false)).result()
                .filter(DeviceConfig.TwinResume.class::isInstance).isPresent(), "A press resumes");
        refused(SmartLamp.TYPE, "twin.resume", new JsonPrimitive("PRESS"), new DeviceView.Grants(true, false, false, false), "Resume without CONFIGURE");
        refused(SmartLamp.TYPE, "twin.resume", new JsonPrimitive(true), DeviceView.Grants.ALL, "Resume as a value instead of a press");
        refused(DisplayPanel.TYPE, "twin.resume", new JsonPrimitive("PRESS"), DeviceView.Grants.ALL, "Resume on a read-only panel, which never pauses");
        Binding bound = Binding.of(SmartLamp.TWIN.sourceEnd("light.kitchen")).withControl(true);
        DeviceView lampView = new DeviceView(pos, SmartLamp.TYPE.id(), "Lamp", "lamp", "", AccessMode.CLAIM, Optional.empty(), DeviceView.Registration.NONE,
                DeviceView.Grants.ALL, "{}", Optional.empty(), false, "", Optional.empty(), new DeviceView.Ownership(DeviceView.Owner.NOBODY, false),
                DeviceView.Diagnostics.notRunning(null), 0, false);
        check(DeviceClipboard.of(SmartLamp.TYPE, lampView, bound).entries().stream().noneMatch(entry -> entry.key().equals("twin.resume")),
                "Copy-Paste never copies Resume");

        // A search answers only a binder; while the twin is off only why; neither asks the twin.
        boolean[] asked = {false};
        java.util.function.Supplier<List<art.rehra.mineqtt.twin.core.EntitySearch.Candidate>> spy = () -> {
            asked[0] = true;
            return List.of();
        };
        TwinPicker.Answer noBind = TwinPicker.answer(false, Optional.empty(), spy, () -> true, id -> Optional.empty(), null, "");
        check(noBind.rows().isEmpty() && noBind.notice() == TwinPicker.Notice.NO_BIND && !asked[0], "A player without mineqtt:twin.bind gets no rows, and the twin is not asked");
        TwinPicker.Answer off = TwinPicker.answer(true, Optional.of("set twin.allowedEntities"), spy, () -> true, id -> Optional.empty(), null, "");
        check(off.rows().isEmpty() && off.notice() == TwinPicker.Notice.OFF && off.detail().equals("set twin.allowedEntities") && !asked[0],
                "While the twin is off the answer says why, and the twin is not asked");
        check(TwinPicker.answer(true, Optional.empty(), spy, () -> false, id -> Optional.empty(), null, "").notice() == TwinPicker.Notice.EMPTY,
                "No entity at all: D4's empty-catalog help");
        check(TwinPicker.answer(true, Optional.empty(), spy, () -> true, id -> Optional.empty(), null, "").notice() == TwinPicker.Notice.NO_MATCH,
                "Entities, but none matching");

        // Only readable, allowed entities are candidates; in catalog mode only the catalog's.
        art.rehra.mineqtt.twin.core.EntityDocuments store = new art.rehra.mineqtt.twin.core.EntityDocuments("ha_statestream",
                art.rehra.mineqtt.twin.core.EntityDocuments.Limits.DEFAULT);
        art.rehra.mineqtt.twin.core.EntityScan scan = new art.rehra.mineqtt.twin.core.EntityScan("ha_statestream", 100);
        for (String entity : List.of("light/kitchen", "light/hall", "person/steve", "sensor/temperature")) {
            scan.accept("ha_statestream/" + entity + "/state", "on".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        java.util.function.Function<EntityGlobs, List<String>> candidates = allowed -> art.rehra.mineqtt.twin.core.EntitySearch.readable(
                art.rehra.mineqtt.twin.core.EntitySearch.candidates(store, scan, List.of()).values(), store, allowed).stream()
                .map(art.rehra.mineqtt.twin.core.EntitySearch.Candidate::entityId).sorted().toList();
        check(candidates.apply(EntityGlobs.ALL).equals(List.of("light.hall", "light.kitchen", "sensor.temperature")),
                "Without a catalog the scan's entities are candidates, never a private domain: " + candidates.apply(EntityGlobs.ALL));
        check(candidates.apply(EntityGlobs.of(List.of("light.k*"))).equals(List.of("light.kitchen")), "Only entities twin.allowedEntities matches");
        check(candidates.apply(EntityGlobs.NONE).isEmpty(), "An empty twin.allowedEntities lists nothing");
        store.accept("ha_statestream/_mineqtt/catalog/light", ("[{\"id\":\"light.kitchen\",\"name\":\"Kitchen\",\"area\":\"Kitchen\",\"control\":true,\"state\":\"on\"},"
                + "{\"id\":\"light.porch\",\"name\":\"Porch\",\"state\":\"off\"}]").getBytes(java.nio.charset.StandardCharsets.UTF_8), true, 1);
        store.accept("ha_statestream/_mineqtt/status", "{\"v\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8), true, 2);
        check(candidates.apply(EntityGlobs.ALL).equals(List.of("light.kitchen", "light.porch")),
                "While the catalog decides only its entries are candidates, not what the scan saw: " + candidates.apply(EntityGlobs.ALL));
        check(art.rehra.mineqtt.twin.core.EntitySearch.candidates(store, scan, List.of()).get("light.kitchen").control()
                        && !art.rehra.mineqtt.twin.core.EntitySearch.candidates(store, scan, List.of()).get("light.porch").control(),
                "A candidate carries the catalog's control label");

        // M3-14's control filter: a target lists only what Minecraft may control, and in catalog mode what HA labelled for it.
        List<art.rehra.mineqtt.twin.core.EntitySearch.Candidate> pool = List.of(candidate("light.kitchen", true), candidate("light.porch", false),
                candidate("scene.movie", true), candidate("lock.front", true), candidate("binary_sensor.door", true), candidate("person.steve", true));
        List<String> allowlist = List.of("light", "switch", "input_boolean", "fan", "cover", "scene", "script", "button", "input_button");
        java.util.function.BiFunction<Boolean, String, List<String>> targetRows = (catalogMode, query) -> TwinPicker.answer(true, Optional.empty(),
                () -> pool, () -> true, id -> Optional.empty(), new TwinPicker.ControlFilter(catalogMode, allowlist), query).rows().stream()
                .map(TwinPicker.Row::entityId).toList();
        check(targetRows.apply(true, "").equals(List.of("light.kitchen", "scene.movie")),
                "In catalog mode a target lists only control-labelled entities of domains Minecraft controls and the allowlist allows: " + targetRows.apply(true, ""));
        check(targetRows.apply(false, "").equals(List.of("light.kitchen", "light.porch", "scene.movie")),
                "Without a catalog the labels are unknown: every controllable, allowed domain (no lock outside the allowlist, no sensor, no person)");
        TwinPicker.Answer unlabelled = TwinPicker.answer(true, Optional.empty(), () -> List.of(candidate("light.porch", false)), () -> true,
                id -> Optional.empty(), new TwinPicker.ControlFilter(true, allowlist), "");
        check(unlabelled.rows().isEmpty() && unlabelled.notice() == TwinPicker.Notice.NO_CONTROL, "Nothing labelled for control: the answer says to label it");
        check(TwinPicker.answer(true, Optional.empty(), () -> List.of(candidate("light.porch", false)), () -> true, id -> Optional.empty(),
                new TwinPicker.ControlFilter(true, allowlist), "porch").notice() == TwinPicker.Notice.NO_MATCH, "A query that finds nothing controllable: no match");
        check(TwinPicker.answer(true, Optional.empty(), () -> pool, () -> true, id -> Optional.empty(), null, "").rows().size() == pool.size(),
                "A source's search is not filtered for control");

        // A flood beyond the burst is dropped; the rate refills.
        long[] now = {7 * SECOND};
        TokenBucket searches = new TokenBucket(TwinPicker.SEARCH_BURST, TwinPicker.SEARCHES_PER_SECOND, () -> now[0]);
        for (int i = 0; i < TwinPicker.SEARCH_BURST; i++) check(searches.tryTake(), "Search " + i + " of a burst passes");
        check(!searches.tryTake(), "A search beyond the burst is dropped");
        now[0] += SECOND / 4;
        check(searches.tryTake() && !searches.tryTake(), "A quarter second later one more passes (4 a second)");
        check(TwinPicker.DEBOUNCE_MILLIS == 250 && 1000 / TwinPicker.DEBOUNCE_MILLIS <= TwinPicker.SEARCHES_PER_SECOND,
                "The picker's debounce keeps a typing player within the rate");
        check(TwinPicker.keys(SmartLamp.TWIN).equals(List.of("twin.entity")) && TwinPicker.keys(DisplayPanel.TWIN).equals(List.of("twin.entity"))
                        && TwinPicker.keys(WallSwitch.TWIN).equals(List.of("twin.left", "twin.right")), "The picker chooses a mirror's source, a switch's targets");
    }

    /**
     * Wave 7 (M3-17's Twin tab source): the preset search reuses the picker's payloads with a kind field, so the payload
     * count stays 32. It crosses the wire and an unknown kind is not read; the answer repeats its kind. Only a binder gets
     * presets, and the Home Assistant twin being off does not hide them (M3-17 decision 2); only presets of a kind the type
     * mirrors are listed, a kind chip never widens the list, a query narrows it, and an entity key is no preset search. A
     * view names an MQTT device only for a viewer who may configure the device, and has one source at most.
     */
    private static void presetSearch() {
        BlockPos pos = new BlockPos(3, -60, 4);
        TwinSearchPayload search = new TwinSearchPayload(pos, TwinPicker.SearchKind.PRESET, TwinPicker.PRESET_KEY, "zigbee", "light");
        RegistryFriendlyByteBuf searchBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinSearchPayload.CODEC.encode(searchBuf, search);
        check(TwinSearchPayload.CODEC.decode(searchBuf).equals(search), "A preset search crosses the wire");
        check(new TwinSearchPayload(pos, "twin.entity", "", "").kind() == TwinPicker.SearchKind.ENTITY, "The picker's search is an entity search");
        RegistryFriendlyByteBuf forgedKind = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        BlockPos.STREAM_CODEC.encode(forgedKind, pos);
        net.minecraft.network.VarInt.write(forgedKind, 7);
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forgedKind, "twin.device");
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forgedKind, "");
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forgedKind, "");
        check(fails(() -> TwinSearchPayload.CODEC.decode(forgedKind)), "A search of an unknown kind is not read");
        TwinResultsPayload answer = new TwinResultsPayload(pos, TwinPicker.SearchKind.PRESET, TwinPicker.PRESET_KEY, "", "",
                List.of(new TwinPicker.Row("mineqtt:zigbee2mqtt_light", "zigbee2mqtt light", "", "light", java.util.OptionalInt.empty(), true, false, false)),
                TwinPicker.Notice.NONE, "");
        RegistryFriendlyByteBuf answerBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinResultsPayload.CODEC.encode(answerBuf, answer);
        check(TwinResultsPayload.CODEC.decode(answerBuf).equals(answer), "A preset answer crosses the wire with its kind");

        check(TwinPicker.searchable(SmartLamp.TWIN, TwinPicker.SearchKind.PRESET, "twin.device")
                        && !TwinPicker.searchable(SmartLamp.TWIN, TwinPicker.SearchKind.PRESET, "twin.entity")
                        && !TwinPicker.searchable(SmartLamp.TWIN, TwinPicker.SearchKind.ENTITY, "twin.device")
                        && !TwinPicker.searchable(WallSwitch.TWIN, TwinPicker.SearchKind.PRESET, "twin.device")
                        && TwinPicker.searchable(WallSwitch.TWIN, TwinPicker.SearchKind.ENTITY, "twin.left"),
                "A preset search is for twin.device of a type with a source; an entity search for the picker's keys");
        java.util.Collection<art.rehra.mineqtt.twin.core.BindingPreset> builtIns = MirrorNoLoopCheck.builtInPresets().presets().values();
        TwinPicker.Answer noBind = TwinPicker.presets(false, SmartLamp.TWIN.sourceDomains(), builtIns, "", "");
        check(noBind.rows().isEmpty() && noBind.notice() == TwinPicker.Notice.NO_BIND, "A player without mineqtt:twin.bind gets no presets, only why");
        List<String> lamp = TwinPicker.presets(true, SmartLamp.TWIN.sourceDomains(), builtIns, "", "").rows().stream().map(TwinPicker.Row::entityId).toList();
        check(lamp.equals(List.of("mineqtt:esphome_light", "mineqtt:zigbee2mqtt_light", "mineqtt:generic_switch", "mineqtt:shelly_gen2_switch",
                        "mineqtt:tasmota_power", "mineqtt:zigbee2mqtt_switch", "mineqtt:zigbee2mqtt_contact")),
                "A lamp lists the light presets first, then the switches, then the contact, each kind by name; no sensor preset: " + lamp);
        List<String> panel = TwinPicker.presets(true, DisplayPanel.TWIN.sourceDomains(), builtIns, "", "").rows().stream().map(TwinPicker.Row::entityId).toList();
        check(panel.containsAll(List.of("mineqtt:zigbee2mqtt_sensor", "mineqtt:generic_topic", "mineqtt:zigbee2mqtt_contact")), "A panel lists the sensors: " + panel);
        check(TwinPicker.presets(true, SmartLamp.TWIN.sourceDomains(), builtIns, "", "switch").rows().stream().allMatch(row -> row.value().equals("switch")),
                "A kind chip lists that kind only");
        check(TwinPicker.presets(true, SmartLamp.TWIN.sourceDomains(), builtIns, "", "sensor").rows().size() == lamp.size(),
                "A kind chip the type does not mirror never widens the list to it");
        check(TwinPicker.presets(true, SmartLamp.TWIN.sourceDomains(), builtIns, "ZIGBEE", "").rows().stream().map(TwinPicker.Row::entityId).toList()
                .equals(List.of("mineqtt:zigbee2mqtt_light", "mineqtt:zigbee2mqtt_switch", "mineqtt:zigbee2mqtt_contact")),
                "A query narrows the presets by id or name, case-blind");
        check(TwinPicker.presets(true, SmartLamp.TWIN.sourceDomains(), builtIns, "nothing like it", "").notice() == TwinPicker.Notice.NO_MATCH,
                "A query that matches no preset: no match");
        check(TwinPicker.presets(true, SmartLamp.TWIN.sourceDomains(), List.of(), "", "").notice() == TwinPicker.Notice.EMPTY, "No preset loaded: empty");
        TwinPicker.Row bulb = TwinPicker.presets(true, SmartLamp.TWIN.sourceDomains(), builtIns, "zigbee2mqtt light", "").rows().getFirst();
        check(bulb.control() && bulb.value().equals("light") && bulb.area().isEmpty() && bulb.name().equals("zigbee2mqtt light"),
                "A preset row carries its name, its kind and whether it sends commands: " + bulb);

        TwinView.Device porch = new TwinView.Device("mineqtt:zigbee2mqtt_light", "zigbee2mqtt light", "Porch", "zigbee2mqtt/Porch", "light");
        TwinView devices = new TwinView(pos, true, Optional.empty(), Optional.of(porch), true, TwinView.Settings.DEFAULT, Map.of(), Optional.of(TwinHealth.LIVE),
                "", false, false, Optional.empty(), "set twin.allowedEntities", true, true, false, 0, Optional.empty(), 0, "");
        RegistryFriendlyByteBuf viewBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinViewPayload.CODEC.encode(viewBuf, new TwinViewPayload(devices));
        check(TwinViewPayload.CODEC.decode(viewBuf).view().equals(devices) && devices.bound() && devices.boundToDevice(),
                "A view of an MQTT device crosses the wire and reads as bound, also while the Home Assistant twin is off");
        check(fails(() -> new TwinView(pos, false, Optional.empty(), Optional.of(porch), false, TwinView.Settings.DEFAULT, Map.of(), Optional.empty(), "",
                false, false, Optional.empty(), "", false, false, false, 0, Optional.empty(), 0, "")), "A view without details names no MQTT device");
        TwinView hidden = new TwinView(pos, false, Optional.empty(), Optional.of(TwinView.Device.HIDDEN), false, TwinView.Settings.DEFAULT, Map.of(),
                Optional.of(TwinHealth.LIVE), "", false, false, Optional.empty(), "", false, false, false, 0, Optional.empty(), 0, "");
        check(hidden.bound() && hidden.boundToDevice() && hidden.device().orElseThrow().topic().isEmpty(),
                "A view without details says it follows an MQTT device, not which");
        check(fails(() -> new TwinView(pos, true, Optional.of(new TwinView.Entity("light.kitchen", "", "")), Optional.of(porch), false,
                TwinView.Settings.DEFAULT, Map.of(), Optional.empty(), "", false, false, Optional.empty(), "", true, true, false, 0, Optional.empty(), 0, "")),
                "A view has one source: an entity or an MQTT device");
        TwinView.Device long_ = new TwinView.Device("p".repeat(300), "n".repeat(300), "d".repeat(300), "t".repeat(500), "k".repeat(100));
        check(long_.preset().length() == TwinView.MAX_PRESET_LENGTH && long_.name().length() == TwinView.MAX_NAME_LENGTH
                        && long_.device().length() == TwinView.MAX_DEVICE_LENGTH && long_.topic().length() == TwinView.MAX_TOPIC_LENGTH
                        && long_.kind().length() == TwinView.MAX_KIND_LENGTH, "An MQTT device's texts are cut to their caps");
    }

    /**
     * Wave 7 (the wave 6 findings): the picker's answer repeats the domain chip it answers, bounded on the wire, so the
     * picker drops an answer to a chip or a query it has left; an unanswered search is sent again a few times, within the
     * rate. The Twin tab's view counts the requests the rates refused for every viewer, and names the last one's target
     * only for a viewer who may configure the device.
     */
    private static void pickerAnswersAndRates() {
        BlockPos pos = new BlockPos(3, -60, 4);
        TwinResultsPayload lights = new TwinResultsPayload(pos, TwinPicker.SearchKind.ENTITY, "twin.entity", "m3", "light", List.of(), TwinPicker.Notice.NO_MATCH, "");
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinResultsPayload.CODEC.encode(buf, lights);
        check(TwinResultsPayload.CODEC.decode(buf).equals(lights), "An answer crosses the wire with the domain it answers");
        RegistryFriendlyByteBuf forged = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        BlockPos.STREAM_CODEC.encode(forged, pos);
        net.minecraft.network.VarInt.write(forged, TwinPicker.SearchKind.ENTITY.ordinal());
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forged, "twin.entity");
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forged, "");
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(forged, "d".repeat(500));
        check(fails(() -> TwinResultsPayload.CODEC.decode(forged)), "A forged answer's domain over 64 characters is not read");
        check(TwinPicker.answers("twin.entity", "m3", "light", "twin.entity", "m3", "light"), "The answer to the search sent last is shown");
        check(!TwinPicker.answers("twin.entity", "m3", "switch", "twin.entity", "m3", "light"), "An answer to a chip the player left is dropped");
        check(!TwinPicker.answers("twin.entity", "m3", "", "twin.entity", "m", ""), "An answer to an older query is dropped");
        check(!TwinPicker.answers("twin.left", "m3", "", "twin.entity", "m3", ""), "An answer for another key is dropped");
        check(!TwinPicker.resendDue(false, 0, TwinPicker.RESEND_MILLIS - 1) && TwinPicker.resendDue(false, 0, TwinPicker.RESEND_MILLIS)
                        && !TwinPicker.resendDue(true, 0, 10 * TwinPicker.RESEND_MILLIS) && !TwinPicker.resendDue(false, TwinPicker.MAX_RESENDS, 10 * TwinPicker.RESEND_MILLIS),
                "An unanswered search goes again after a second, a few times at most; an answered one never");
        check(1000.0 / TwinPicker.RESEND_MILLIS <= TwinPicker.SEARCHES_PER_SECOND, "Resending keeps the picker within the search rate");

        TwinView rated = new TwinView(pos, true, Optional.of(new TwinView.Entity("scene.movie", "", "")), Optional.empty(), false, TwinView.Settings.DEFAULT,
                Map.of(), Optional.of(TwinHealth.LIVE), "", false, false, Optional.empty(), "", true, true, false, 0, Optional.empty(), 3,
                "scene.movie: Wall sends at most 4 requests a second");
        RegistryFriendlyByteBuf viewBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TwinViewPayload.CODEC.encode(viewBuf, new TwinViewPayload(rated));
        check(TwinViewPayload.CODEC.decode(viewBuf).view().equals(rated), "A view with refused requests crosses the wire");
        TwinView counted = new TwinView(pos, false, Optional.of(TwinView.Entity.HIDDEN), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(),
                Optional.of(TwinHealth.LIVE), "", false, false, Optional.empty(), "", false, false, false, 0, Optional.empty(), 3, "");
        check(counted.rateRefused() == 3, "A viewer without details sees how many requests the rates refused");
        check(fails(() -> new TwinView(pos, false, Optional.of(TwinView.Entity.HIDDEN), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(),
                Optional.empty(), "", false, false, Optional.empty(), "", false, false, false, 0, Optional.empty(), 3, "scene.movie: …")),
                "...but not what the last one was for");
        check(fails(() -> new TwinView(pos, true, Optional.empty(), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(), Optional.empty(), "", false,
                false, Optional.empty(), "", true, true, false, 0, Optional.empty(), -1, "")), "A negative count is refused");
        check(new TwinView(pos, true, Optional.empty(), Optional.empty(), false, TwinView.Settings.DEFAULT, Map.of(), Optional.empty(), "", false, false,
                Optional.empty(), "", true, true, false, 0, Optional.empty(), 1, "x".repeat(1000)).rateLast().length() == TwinView.MAX_REASON_LENGTH,
                "The last refusal's text is cut to its cap");
    }

    private static art.rehra.mineqtt.twin.core.EntitySearch.Candidate candidate(String id, boolean control) {
        return new art.rehra.mineqtt.twin.core.EntitySearch.Candidate(id, id, "", art.rehra.mineqtt.twin.core.StatestreamValue.of("on"), control);
    }

    private static void tokenBucket() {
        long[] now = {5 * SECOND};
        TokenBucket bucket = new TokenBucket(20, 20, () -> now[0]);
        for (int i = 0; i < 20; i++) check(bucket.tryTake(), "A full bucket allows a burst of its capacity, token " + i);
        check(!bucket.tryTake(), "The 21st action within the same instant is refused");
        now[0] += SECOND / 20;
        check(bucket.tryTake(), "One token is back after 1/20 s");
        check(!bucket.tryTake(), "And only one");
        now[0] += 10 * SECOND;
        for (int i = 0; i < 20; i++) check(bucket.tryTake(), "A long pause refills to the capacity, token " + i);
        check(!bucket.tryTake(), "Never beyond the capacity");
        now[0] -= SECOND;
        check(!bucket.tryTake(), "A clock stepping back refills nothing");
        now[0] += SECOND + SECOND / 20;
        check(bucket.tryTake(), "Nor does it drain the bucket");

        long[] slow = {0};
        TokenBucket publishes = new TokenBucket(2, 2, () -> slow[0]);
        check(publishes.tryTake() && publishes.tryTake() && !publishes.tryTake(), "2 per second: a burst of two");
        slow[0] += SECOND / 4;
        check(!publishes.tryTake(), "Half a token after 1/4 s is not enough");
        slow[0] += SECOND / 4;
        check(publishes.tryTake(), "A whole token after 1/2 s");

        rejects(() -> new TokenBucket(0, 1, () -> 0), "a capacity of zero");
        rejects(() -> new TokenBucket(1, 0, () -> 0), "a refill rate of zero");
        rejects(() -> new TokenBucket(1, Double.NaN, () -> 0), "a NaN refill rate");
    }

    private static void rateLimit() {
        long[] now = {0};
        RateLimit limit = new RateLimit(2, 2, () -> now[0]);
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        check(limit.tryTake(steve) && limit.tryTake(steve) && !limit.tryTake(steve), "Steve uses up his bucket");
        check(limit.tryTake(alex), "Alex has his own bucket");
        limit.forget(steve);
        check(limit.tryTake(steve), "A player who left and came back starts with a full bucket");
        limit.clear();
        check(limit.tryTake(alex) && limit.tryTake(alex), "Clearing refills everyone");
    }

    private static void guardOrder() {
        List<String> asked = new ArrayList<>();
        check(C2SGuard.decide(facts(asked, -1)).isEmpty() && asked.size() == 8, "Every fact holds: accepted after asking all eight");
        Rejection[] expected = Rejection.values();
        for (int failing = 0; failing < expected.length; failing++) {
            asked.clear();
            Optional<Rejection> rejection = C2SGuard.decide(facts(asked, failing));
            check(rejection.equals(Optional.of(expected[failing])), "Failing fact " + failing + " is " + expected[failing] + ", got " + rejection);
            check(asked.size() == failing + 1, "Nothing after the failing fact " + expected[failing] + " is asked: " + asked);
        }
        check(asked.getFirst().equals("tokenTaken"), "The rate limit comes first, so a flood of bad payloads is cut off too");
        check(Rejection.values()[1] == Rejection.SPECTATOR, "A spectator's payload is dropped before any lookup: a spectator only looks");
    }

    /**
     * The guard's range variant (M2 plan §4.2 M2-13, design §8.5): with a Configurator in hand a player reaches a block
     * up to 16 blocks away in the same dimension, measured like the block interaction range, where the level lets them
     * interact; without one, or beyond it, in another dimension or where the level protects the block, they do not.
     */
    private static void configuratorReach() {
        double slack = 1.0;
        Vec3 eye = new Vec3(0.5, 1.62, 0.5);
        double at10 = new AABB(new BlockPos(0, 12, 0)).distanceToSqr(eye);
        double at16 = new AABB(new BlockPos(16, 1, 0)).distanceToSqr(eye);
        double at17 = new AABB(new BlockPos(17, 1, 0)).distanceToSqr(eye);
        double at18 = new AABB(new BlockPos(18, 1, 0)).distanceToSqr(eye);
        check(Math.abs(Math.sqrt(at10) - 10.38) < 0.01 && Math.abs(Math.sqrt(at16) - 15.5) < 0.01, "The distances are measured to the block's cube: "
                + Math.sqrt(at10) + ", " + Math.sqrt(at16));
        check(C2SGuard.CONFIGURATOR_RANGE == 16.0, "The Configurator reaches 16 blocks");
        BooleanSupplier free = () -> true;
        check(C2SGuard.reaches(true, true, false, free, at10, slack), "Within reach a player needs no Configurator");
        check(!C2SGuard.reaches(true, false, false, free, at10, slack), "Beyond reach without a Configurator nothing is reached");
        check(C2SGuard.reaches(true, false, true, free, at10, slack), "A ceiling lamp 10 blocks up is reached with a Configurator");
        check(C2SGuard.reaches(true, false, true, free, at16, slack) && C2SGuard.reaches(true, false, true, free, at17, slack),
                "A block 16 blocks away is reached, and with the slack one more");
        check(!C2SGuard.reaches(true, false, true, free, at18, slack), "Beyond the range and its slack nothing is reached");
        check(C2SGuard.reaches(true, false, true, free, at18, 4.0), "A screen's larger slack keeps it open a little further");
        check(!C2SGuard.reaches(false, true, true, free, at10, slack) && !C2SGuard.reaches(false, true, false, free, 0, slack),
                "Another dimension is never reached, whatever its coordinates");
        List<String> asked = new ArrayList<>();
        BooleanSupplier protectedHere = () -> {
            asked.add("mayInteract");
            return false;
        };
        check(!C2SGuard.reaches(true, false, true, protectedHere, at10, slack) && asked.size() == 1,
                "From afar a block the level protects (spawn protection, the world border) is not reached, as vanilla refuses a click on it");
        asked.clear();
        check(C2SGuard.reaches(true, true, true, protectedHere, at10, slack) && asked.isEmpty(),
                "Within reach the level is not asked: vanilla asked it before the click that opened the screen");
    }

    private static void fieldCaps() {
        String prefix = "mineqtt/shared/";
        check(C2SGuard.fitsTopic(prefix + "x".repeat(256 - prefix.length())), "A 256-byte topic fits");
        check(!C2SGuard.fitsTopic(prefix + "x".repeat(257 - prefix.length())), "A 257-byte topic does not");
        check(!C2SGuard.fitsTopic("é".repeat(129)), "Topics count UTF-8 bytes: 129 two-byte characters are 258 bytes");
        check(C2SGuard.fitsPayload("x".repeat(1024)), "A 1 KiB payload fits");
        check(!C2SGuard.fitsPayload("x".repeat(1025)), "1025 bytes do not");
        check(!C2SGuard.fitsPayload("€".repeat(342)), "Payloads count UTF-8 bytes: 342 three-byte characters are 1026 bytes");
    }

    private static void accessTable() {
        for (MineqttPermission need : MineqttPermission.values()) {
            for (AccessMode mode : AccessMode.values()) {
                check(DeviceAccess.permits(need, mode, true, false, never()), "An op may " + need + " a " + mode + " device without a claim check");
                check(DeviceAccess.permits(need, mode, false, true, never()), "The owner may " + need + " a " + mode + " device without a claim check");
            }
            check(!DeviceAccess.permits(need, AccessMode.PRIVATE, false, false, never()), "Nobody else may " + need + " a private device");
            check(DeviceAccess.permits(need, AccessMode.CLAIM, false, false, () -> true), "A claim-trusted player may " + need + " a claim device");
            check(!DeviceAccess.permits(need, AccessMode.CLAIM, false, false, () -> false), "An untrusted player may not " + need + " a claim device");
        }
        check(DeviceAccess.permits(MineqttPermission.USE, AccessMode.PUBLIC, false, false, never()), "Anyone may use a public device");
        check(DeviceAccess.permits(MineqttPermission.CONFIGURE, AccessMode.PUBLIC, false, false, () -> true), "A claim-trusted player configures a public device");
        check(!DeviceAccess.permits(MineqttPermission.CONFIGURE, AccessMode.PUBLIC, false, false, () -> false), "An untrusted player may not configure a public device");
        // M2-16: the claim API reads the player's level, so from another dimension a claim trusts nobody and is not asked.
        check(DeviceAccess.claimTrust(true, () -> true).getAsBoolean() && !DeviceAccess.claimTrust(true, () -> false).getAsBoolean(),
                "In the device's dimension the claim decides");
        for (MineqttPermission need : MineqttPermission.values()) {
            check(!DeviceAccess.permits(need, AccessMode.CLAIM, false, false, DeviceAccess.claimTrust(false, never())),
                    "From another dimension nobody else may " + need + " a claim device, whatever the claim there says");
            for (AccessMode mode : AccessMode.values()) {
                check(DeviceAccess.permits(need, mode, true, false, DeviceAccess.claimTrust(false, never()))
                        && DeviceAccess.permits(need, mode, false, true, DeviceAccess.claimTrust(false, never())),
                        "An op and the owner may " + need + " a " + mode + " device from another dimension");
            }
        }
        check(!DeviceAccess.permits(MineqttPermission.CONFIGURE, AccessMode.PUBLIC, false, false, DeviceAccess.claimTrust(false, never())),
                "Nor configure a public device from another dimension");
        check(DeviceAccess.permits(MineqttPermission.USE, AccessMode.PUBLIC, false, false, DeviceAccess.claimTrust(false, never())),
                "Anyone may still use a public device from another dimension");
        check(AccessMode.DEFAULT == AccessMode.CLAIM, "Devices follow the claim by default");
        mayNotBuildTable();
    }

    /**
     * P2: a player who may not build (adventure mode) only uses MineQTT blocks. They configure nothing, an operator and
     * the owner neither, without the claim being asked; whether they use a device stays the access mode's decision. A
     * player who may build is decided as before.
     */
    private static void mayNotBuildTable() {
        for (AccessMode mode : AccessMode.values()) {
            check(!DeviceAccess.permits(MineqttPermission.CONFIGURE, mode, true, false, false, never()), "An op who may not build may not configure a " + mode + " device");
            check(!DeviceAccess.permits(MineqttPermission.CONFIGURE, mode, false, true, false, never()), "Nor may its owner who may not build");
            check(!DeviceAccess.permits(MineqttPermission.CONFIGURE, mode, false, false, false, never()), "Nor anyone else, whatever the claim says");
            check(DeviceAccess.permits(MineqttPermission.USE, mode, true, false, false, never()) && DeviceAccess.permits(MineqttPermission.USE, mode, false, true, false, never()),
                    "An op and the owner who may not build still use a " + mode + " device");
        }
        check(DeviceAccess.permits(MineqttPermission.USE, AccessMode.PUBLIC, false, false, false, never()), "Anyone who may not build still uses a public device");
        check(DeviceAccess.permits(MineqttPermission.USE, AccessMode.CLAIM, false, false, false, () -> true)
                && !DeviceAccess.permits(MineqttPermission.USE, AccessMode.CLAIM, false, false, false, () -> false), "The claim decides who uses a claim device in adventure mode");
        check(!DeviceAccess.permits(MineqttPermission.USE, AccessMode.PRIVATE, false, false, false, never()), "Nobody else uses a private device in adventure mode");
        for (MineqttPermission need : MineqttPermission.values()) {
            for (AccessMode mode : AccessMode.values()) {
                for (int who = 0; who < 8; who++) {
                    boolean op = (who & 1) != 0;
                    boolean owner = (who & 2) != 0;
                    boolean trust = (who & 4) != 0;
                    check(DeviceAccess.permits(need, mode, op, owner, true, () -> trust) == DeviceAccess.permits(need, mode, op, owner, () -> trust),
                            "A player who may build is decided by the access mode alone (" + need + ", " + mode + ", op " + op + ", owner " + owner + ", trusted " + trust + ")");
                }
            }
        }
        check(DeviceAccess.consolePermits(MineqttPermission.USE, true, false, never()), "An op in adventure mode opens a console");
        check(!DeviceAccess.consolePermits(MineqttPermission.CONFIGURE, true, false, never()), "but runs none of its actions");
    }

    /**
     * M2-11, the mode-change matrix: for every operator, owner, access mode, claim trust and exposure right, the grants
     * are {@link DeviceAccess#permits}' USE and CONFIGURE, the exposure on top of CONFIGURE, and the access mode only for
     * the owner or an operator, which the config validator then holds every mode change to: a player the claim trusts
     * with everything still may not set a device private, and no mode locks out its owner. Claim trust is not asked of
     * the owner or an operator. A device without an owner is claimable by a player who may configure it, one with an
     * owner (or a v1 owner name still to resolve) by nobody.
     */
    private static void accessChanges() {
        for (boolean mayBuild : new boolean[]{true, false}) {
            for (boolean op : new boolean[]{true, false}) {
                for (boolean owner : new boolean[]{true, false}) {
                    for (AccessMode mode : AccessMode.values()) {
                        for (int trust = 0; trust < 4; trust++) {
                            boolean useTrust = (trust & 1) != 0;
                            boolean configureTrust = (trust & 2) != 0;
                            for (boolean mayExpose : new boolean[]{true, false}) {
                                accessChange(mayBuild, op, owner, mode, useTrust, configureTrust, mayExpose);
                            }
                        }
                    }
                }
            }
        }
        // The plan's client check, as a table row: the trusted non-owner may not set private, the owner may.
        DeviceView.Grants trusted = DeviceAccess.grants(false, false, true, AccessMode.CLAIM, need -> true, false);
        check(trusted.configure() && DeviceConfig.parse(RedstoneOutput.TYPE, "access", new JsonPrimitive("private"), trusted).isError(),
                "A claim-trusted player who configures the device may not set it private");
        DeviceView.Grants owner = DeviceAccess.grants(false, true, true, AccessMode.PRIVATE, need -> false, false);
        check(owner.use() && owner.configure() && owner.access(), "The owner of a private device keeps every right but exposing");
        // P2: in adventure mode the owner, an operator who may expose devices, and a player the claim trusts only use it.
        for (AccessMode mode : AccessMode.values()) {
            DeviceView.Grants adventureOwner = DeviceAccess.grants(false, true, false, mode, need -> false, true);
            DeviceView.Grants adventureOp = DeviceAccess.grants(true, false, false, mode, need -> false, true);
            DeviceView.Grants adventureTrusted = DeviceAccess.grants(false, false, false, mode, need -> true, true);
            DeviceView.Grants usesOnly = new DeviceView.Grants(true, false, false, false);
            check(adventureOwner.equals(usesOnly) && adventureOp.equals(usesOnly), "In adventure mode the owner and an op only use a " + mode + " device");
            check(adventureTrusted.equals(new DeviceView.Grants(mode != AccessMode.PRIVATE, false, false, false)),
                    "and a player the claim trusts uses a " + mode + " device as its access mode says, configuring nothing");
        }
        UUID steve = UUID.fromString("6a1c0f4e-0b6e-4a43-9d76-2f4f6f0e5a11");
        for (boolean onlineMode : new boolean[]{true, false}) {
            check(DeviceAccess.owner(steve, "Steve", onlineMode) == DeviceView.Owner.KNOWN, "A UUID is a known owner");
            check(DeviceAccess.owner(null, "", onlineMode) == DeviceView.Owner.NOBODY, "No UUID and no name is nobody");
        }
        check(DeviceAccess.owner(null, "Steve", true) == DeviceView.Owner.NOT_VERIFIED, "An online-mode server has not verified an unresolved v1 name");
        check(DeviceAccess.owner(null, "Steve", false) == DeviceView.Owner.KNOWN, "Without authentication the name stands for its derived UUID");
    }

    // One row of the matrix: a player who may not build gets USE at most (P2).
    private static void accessChange(boolean mayBuild, boolean op, boolean owner, AccessMode mode, boolean useTrust, boolean configureTrust, boolean mayExpose) {
        String who = (op ? "an op" : "a non-op") + (owner ? " owner" : " non-owner") + " of a " + mode + " device (may build: " + mayBuild
                + ", trusted to use: " + useTrust + ", to configure: " + configureTrust + ", may expose: " + mayExpose + ")";
        DeviceView.Grants grants = DeviceAccess.grants(op, owner, mayBuild, mode, need -> {
            check(!op && !owner, "The claim is not asked for " + who);
            check(mayBuild || need == MineqttPermission.USE, "Nor about configuring for " + who);
            return need == MineqttPermission.USE ? useTrust : configureTrust;
        }, mayExpose);
        boolean use = DeviceAccess.permits(MineqttPermission.USE, mode, op, owner, () -> useTrust);
        boolean configure = mayBuild && DeviceAccess.permits(MineqttPermission.CONFIGURE, mode, op, owner, () -> configureTrust);
        boolean access = mayBuild && (op || owner);
        check(grants.equals(new DeviceView.Grants(use, configure, configure && mayExpose, access)), who + " gets " + grants);
        for (AccessMode target : AccessMode.values()) {
            boolean taken = DeviceConfig.parse(RedstoneOutput.TYPE, "access", new JsonPrimitive(target.getSerializedName()), grants).isSuccess();
            check(taken == access, who + (taken ? " may" : " may not") + " set it " + target);
        }
        for (DeviceView.Owner owned : DeviceView.Owner.values()) {
            check(DeviceAccess.mayClaim(grants, owned) == (owned == DeviceView.Owner.NOBODY && configure),
                    who + (owned == DeviceView.Owner.NOBODY ? " claims an ownerless one exactly when configuring" : " claims no owned one"));
        }
    }

    /**
     * M2-11: what a device's Diagnostics tab is told of its traffic. The last message each way replaces the one before (a
     * ring of one), its payload kept only as a start of at most {@value DeviceView#MAX_SHOWN_PAYLOAD_BYTES} bytes without
     * control characters and formatting codes, and shown only to a viewer who may configure the device: everyone else
     * gets topic, size and age. The counts add up. A topic is kept without control characters and formatting codes too.
     */
    private static void traffic() {
        DeviceTraffic traffic = new DeviceTraffic();
        DeviceView.Diagnostics none = traffic.diagnostics(null, DeviceView.Availability.NOT_RUNNING, "", true, 0, 0, 0);
        check(none.lastIn().isEmpty() && none.lastOut().isEmpty() && none.received() == 0 && none.id().isEmpty(), "A device that sent nothing shows nothing");
        traffic.received("mineqtt/w/lamp/set", "{\"state\":\"OFF\"}".getBytes(StandardCharsets.UTF_8), 1_000);
        traffic.received("mineqtt/w/lamp/set", "{\"state\":\"ON\"}\u0007§cx".getBytes(StandardCharsets.UTF_8), 2_000);
        traffic.dropped();
        byte[] large = ("{\"x\":\"" + "é".repeat(8_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        traffic.sent("mineqtt/w/lamp", large, 2_500);
        UUID id = UUID.fromString("3f9c0000-0000-4000-8000-000000000003");
        DeviceView.Diagnostics shown = traffic.diagnostics(id, DeviceView.Availability.ONLINE, "", true, 5_000, 4, 2);
        DeviceView.Traffic in = shown.lastIn().orElseThrow();
        check(in.payload().equals(Optional.of("{\"state\":\"ON\"}x")) && in.bytes() == 19 && in.ageMillis() == 3_000 && in.topic().equals("mineqtt/w/lamp/set"),
                "The last message in replaces the one before, its payload without control characters or formatting codes: " + in);
        DeviceView.Traffic out = shown.lastOut().orElseThrow();
        check(out.bytes() == large.length && out.payload().orElseThrow().getBytes(StandardCharsets.UTF_8).length <= DeviceView.MAX_SHOWN_PAYLOAD_BYTES
                && out.ageMillis() == 2_500, "A large payload keeps its whole size but shows only its start: " + out.bytes());
        check(shown.id().equals(Optional.of(id)) && shown.received() == 2 && shown.dropped() == 1 && shown.sent() == 1
                && shown.bridgeDroppedIn() == 4 && shown.bridgeDroppedOut() == 2, "The counts add up: " + shown);
        DeviceView.Diagnostics redacted = traffic.diagnostics(id, DeviceView.Availability.ONLINE, "", false, 5_000, 4, 2);
        check(redacted.lastIn().orElseThrow().payload().isEmpty() && redacted.lastOut().orElseThrow().payload().isEmpty()
                        && redacted.lastIn().orElseThrow().bytes() == 19 && redacted.lastIn().orElseThrow().topic().equals("mineqtt/w/lamp/set"),
                "A viewer who may not configure the device sees topic, size and age, never a payload");
        check(traffic.summary().equals("2 in (1 dropped), 1 out"), "devices info sums it up: " + traffic.summary());
        check(traffic.diagnostics(id, DeviceView.Availability.ONLINE, "", true, 500, 0, 0).lastIn().orElseThrow().ageMillis() == 0,
                "A clock that went back gives no negative age");
        traffic.received("mineqtt/w/lamp/set/§kx\u0007", "1".getBytes(StandardCharsets.UTF_8), 6_000);
        String topic = traffic.diagnostics(id, DeviceView.Availability.ONLINE, "", false, 6_000, 0, 0).lastIn().orElseThrow().topic();
        check(topic.equals("mineqtt/w/lamp/set/x"), "A topic the publisher chose shows without control characters or formatting codes: " + topic);
    }

    /**
     * M2 plan D7: a block's owner is matched by UUID only. A v1 owner name stands for the UUID derived from it on a server
     * without authentication, where every player carries that UUID, and for nobody on an online-mode server until it is
     * resolved; a player who merely carries the owner's (old) name is never the owner. Only an online player of exactly
     * that name binds it, never one whose name differs in case.
     */
    private static void legacyOwner() {
        UUID steve = UUID.fromString("6a1c0f4e-0b6e-4a43-9d76-2f4f6f0e5a11");
        UUID impostor = UUID.fromString("0e5d8a3b-6c7f-4b29-8a1e-3c9d2b4f7e60");
        UUID offlineSteve = UUIDUtil.createOfflinePlayerUUID("Steve");
        check(offlineSteve.equals(UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8))),
                "The offline UUID is vanilla's derivation from the name: " + offlineSteve);

        // Resolved UUID: the UUID decides, in either mode.
        for (boolean onlineMode : new boolean[]{true, false}) {
            check(DeviceAccess.legacyOwnerMatches(steve, "Steve", onlineMode, steve), "The resolved owner matches (online mode " + onlineMode + ")");
            check(!DeviceAccess.legacyOwnerMatches(steve, "Steve", onlineMode, impostor), "Another player does not (online mode " + onlineMode + ")");
        }

        // Online mode, unresolved: nobody is the owner until the name is resolved.
        check(!DeviceAccess.legacyOwnerMatches(null, "Steve", true, steve), "An unresolved name matches nobody on an online-mode server");
        check(!DeviceAccess.legacyOwnerMatches(null, "Steve", true, offlineSteve), "Not even the UUID derived from it");
        check(AclContext.offlineOwner("Steve", true) == null, "An online-mode server derives no owner from a name");

        // Offline mode, unresolved: the name's derived UUID, which is what the player called that carries there.
        check(DeviceAccess.legacyOwnerMatches(null, "Steve", false, offlineSteve), "Offline mode: the player of that name matches before the lookup");
        check(!DeviceAccess.legacyOwnerMatches(null, "Steve", false, UUIDUtil.createOfflinePlayerUUID("steve")), "Names are told apart by case, as vanilla's offline UUIDs are");
        check(!DeviceAccess.legacyOwnerMatches(null, "Steve", false, steve), "An online UUID does not match a derived owner");
        check(offlineSteve.equals(AclContext.offlineOwner("Steve", false)), "The offline-mode lookup gives the derived UUID");

        // No owner at all: nobody matches.
        for (boolean onlineMode : new boolean[]{true, false}) {
            check(!DeviceAccess.legacyOwnerMatches(null, "", onlineMode, offlineSteve), "A block without an owner has none (online mode " + onlineMode + ")");
            check(AclContext.offlineOwner("", onlineMode) == null, "No name derives no owner (online mode " + onlineMode + ")");
        }

        // Renamed account: Steve (resolved) is now called Alex; an alt now called Steve is not the owner.
        UUID alt = UUIDUtil.createOfflinePlayerUUID("Steve");
        for (boolean onlineMode : new boolean[]{true, false}) {
            check(DeviceAccess.legacyOwnerMatches(steve, "Steve", onlineMode, steve), "The renamed owner still matches by UUID (online mode " + onlineMode + ")");
            check(!DeviceAccess.legacyOwnerMatches(steve, "Steve", onlineMode, alt), "An alt named after the old name does not (online mode " + onlineMode + ")");
        }

        // The online player who binds a name: exactly that name, case included; the UUID they carry, also a forwarded one.
        NameAndId lowerSteve = new NameAndId(UUIDUtil.createOfflinePlayerUUID("steve"), "steve");
        check(AclContext.onlineOwner("Steve", List.of(lowerSteve)) == null, "A player whose name differs in case does not bind the owner name");
        check(offlineSteve.equals(AclContext.onlineOwner("Steve", List.of(lowerSteve, new NameAndId(offlineSteve, "Steve")))),
                "The player of exactly that name does, also with a differently cased one online before them");
        check(steve.equals(AclContext.onlineOwner("Steve", List.of(new NameAndId(steve, "Steve")))),
                "Their UUID is taken as they carry it, also one a proxy forwards to an offline-mode server");
        check(AclContext.onlineOwner("Steve", List.of()) == null, "Nobody online binds nobody");
        check(AclContext.onlineOwner("", List.of(new NameAndId(steve, ""))) == null, "No owner name binds nobody");
    }

    /**
     * The {@code ownerUuid} key of a block that is no device: v1 data has none and loads without an owner UUID, a placing
     * player's UUID is saved and loaded, a resolved name fills it once and only for the name it was looked up for, and a
     * block nobody placed has neither.
     */
    private static void legacyOwnerKey() {
        UUID steve = UUID.fromString("6a1c0f4e-0b6e-4a43-9d76-2f4f6f0e5a11");
        CompoundTag v1 = new CompoundTag();
        v1.putString("ownerName", "Steve");
        Slots legacy = load(v1);
        check(legacy.getOwnerUuid() == null && legacy.getOwnerName().equals("Steve"), "v1 data loads with its owner name and no owner UUID");
        check(!save(legacy).contains("ownerUuid"), "And saves no owner UUID until the name is resolved");

        check(!legacy.ownerResolved("Alex", steve) && legacy.getOwnerUuid() == null, "A lookup for another name is ignored");
        check(legacy.ownerResolved("Steve", steve) && steve.equals(legacy.getOwnerUuid()), "The resolved name fills the owner UUID");
        check(!legacy.ownerResolved("Steve", UUID.randomUUID()) && steve.equals(legacy.getOwnerUuid()), "Once only");
        CompoundTag saved = save(legacy);
        check(saved.getIntArray("ownerUuid").map(UUIDUtil::uuidFromIntArray).filter(steve::equals).isPresent(),
                "The owner UUID is saved as an int array: " + saved);
        check(saved.getStringOr("ownerName", "").equals("Steve"), "Next to the owner name v1 reads");
        check(steve.equals(load(saved).getOwnerUuid()), "And loads again");

        Slots placed = new Slots();
        placed.setOwner(steve, "Steve");
        check(steve.equals(load(save(placed)).getOwnerUuid()), "A placing player's UUID round-trips");
        placed.setOwner(null, "");
        CompoundTag nobody = save(placed);
        check(!nobody.contains("ownerUuid") && nobody.getStringOr("ownerName", "x").isEmpty(), "A block nobody placed has no owner: " + nobody);
    }

    private static CompoundTag save(Slots block) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        block.saveAdditional(output);
        return output.buildResult();
    }

    private static Slots load(CompoundTag tag) {
        Slots block = new Slots();
        block.loadAdditional(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag));
        return block;
    }

    /**
     * M3 plan D13: {@code mineqtt:twin.bind} and {@code mineqtt:twin.control} are everyone's on integrated servers and
     * need op level 2 or the permission on dedicated servers; {@code twin.allowedEntities} binds operators and the
     * console too, so its dedicated default keeps the twin off for everyone.
     */
    private static void twinPermissions() {
        for (boolean op : new boolean[]{true, false}) {
            for (boolean granted : new boolean[]{true, false}) {
                check(AclContext.twinPermitted(false, op, granted), "Integrated: everyone (op " + op + ", granted " + granted + ")");
                check(AclContext.twinPermitted(true, op, granted) == (op || granted), "Dedicated: ops and holders only (op " + op + ", granted " + granted + ")");
            }
        }
        PermissionSet binder = permission -> permission.equals(AclContext.TWIN_BIND);
        PermissionSet controller = permission -> permission.equals(AclContext.TWIN_CONTROL);
        for (PermissionSet anyone : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.MODERATOR, binder, controller)) {
            check(AclContext.mayBindTwin(anyone, false) && AclContext.mayControlTwin(anyone, false), "Everyone binds and controls on integrated servers");
        }
        for (PermissionSet op : List.of(LevelBasedPermissionSet.GAMEMASTER, LevelBasedPermissionSet.ADMIN, LevelBasedPermissionSet.OWNER)) {
            check(AclContext.mayBindTwin(op, true) && AclContext.mayControlTwin(op, true), "Ops bind and control on dedicated servers");
        }
        check(!AclContext.mayBindTwin(PermissionSet.NO_PERMISSIONS, true) && !AclContext.mayControlTwin(PermissionSet.NO_PERMISSIONS, true)
                && !AclContext.mayBindTwin(LevelBasedPermissionSet.MODERATOR, true) && !AclContext.mayControlTwin(LevelBasedPermissionSet.MODERATOR, true),
                "Players below op level 2 neither bind nor control on dedicated servers");
        check(AclContext.mayBindTwin(binder, true) && !AclContext.mayControlTwin(binder, true), "mineqtt:twin.bind binds, but does not turn control on");
        check(AclContext.mayControlTwin(controller, true) && !AclContext.mayBindTwin(controller, true), "mineqtt:twin.control is the other right");
        PermissionSet admin = permission -> permission.equals(AclContext.ADMIN) || permission.equals(AclContext.HA_EXPOSE);
        check(!AclContext.mayBindTwin(admin, true) && !AclContext.mayControlTwin(admin, true), "mineqtt:admin and mineqtt:ha.expose grant neither");
        check(AclContext.TWIN_BIND.toString().contains("mineqtt:twin.bind") && AclContext.TWIN_CONTROL.toString().contains("mineqtt:twin.control"),
                "The permission ids: " + AclContext.TWIN_BIND + ", " + AclContext.TWIN_CONTROL);

        // The allowed-entities gate takes no subject: nobody bypasses it.
        try {
            Class<?>[] parameters = AclContext.class.getMethod("twinBindRefusal", boolean.class, EntityGlobs.class, String.class).getParameterTypes();
            check(Arrays.stream(parameters).noneMatch(type -> type == TopicAcl.Subject.class || PermissionSet.class.isAssignableFrom(type)),
                    "The entity gate sees only whether the binder may bind at all");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("AclContext.twinBindRefusal(boolean, EntityGlobs, String) is the entity gate", e);
        }
        check(AclContext.twinBindRefusal(true, EntityGlobs.NONE, "light.kitchen").orElse("").contains("set twin.allowedEntities"),
                "An empty list refuses a binder who may bind");
        check(AclContext.twinBindRefusal(false, EntityGlobs.ALL, "light.kitchen").orElse("").contains("mineqtt:twin.bind"),
                "Without the permission the permission is named");
        check(AclContext.twinBindRefusal(true, EntityGlobs.of(List.of("light.*")), "lock.front_door").orElse("").equals("lock.front_door is not in twin.allowedEntities"),
                "An entity outside the globs is refused");
        check(AclContext.twinBindRefusal(true, EntityGlobs.of(List.of("light.*")), "light.porch").isEmpty(), "One inside is allowed");

        // The share gate of an edit (M3-11, M3-14): every NEW source or target must be shared while the catalog answers.
        Function<String, Optional<String>> shared = entity -> entity.equals("light.shared") || entity.equals("switch.shared")
                ? Optional.empty() : Optional.of(entity + " is not shared");
        Binding source = Binding.of(RedstoneOutput.TWIN.sourceEnd("light.shared"));
        Binding unsharedTarget = source.withTarget("powered", TwinAdapter.targetEnd("switch.hidden"));
        check(TwinRules.shareRefusal(null, source, shared).isEmpty() && TwinRules.shareRefusal(null, Binding.of(RedstoneOutput.TWIN.sourceEnd("light.hidden")), shared)
                .orElse("").equals("light.hidden is not shared"), "A new source must be shared");
        check(TwinRules.shareRefusal(source, unsharedTarget, shared).orElse("").equals("switch.hidden is not shared")
                        && TwinRules.shareRefusal(source, source.withTarget("powered", TwinAdapter.targetEnd("switch.shared")), shared).isEmpty(),
                "A new target must be shared too (the integration fix that reversed M3-11 decision 5 for targets)");
        check(TwinRules.shareRefusal(unsharedTarget, unsharedTarget.withControl(true), shared).isEmpty()
                        && TwinRules.shareRefusal(Binding.of(RedstoneOutput.TWIN.sourceEnd("light.hidden")),
                        Binding.of(RedstoneOutput.TWIN.sourceEnd("light.hidden")).withSettings(Binding.Settings.DEFAULT.withInvert(true)), shared).isEmpty()
                        && TwinRules.shareRefusal(unsharedTarget, null, shared).isEmpty(),
                "Entities already bound stay editable after they left the catalog, and unbinding is never refused");

        MineQTTConfig.resetToDefaults();
        for (PermissionSet op : List.of(LevelBasedPermissionSet.GAMEMASTER, LevelBasedPermissionSet.OWNER)) {
            check(AclContext.twinBindRefusal(op, true, "light.kitchen").orElse("").contains("set twin.allowedEntities"),
                    "On a dedicated server with the default AUTO, an op cannot bind either");
        }
        check(AclContext.twinBindRefusal(PermissionSet.NO_PERMISSIONS, false, "lock.front_door").isEmpty(), "AUTO on an integrated server allows every entity");
        MineQTTConfig.twinAllowedEntities = List.of("light.*");
        check(AclContext.twinBindRefusal(LevelBasedPermissionSet.OWNER, true, "lock.front_door").orElse("").contains("not in twin.allowedEntities"),
                "The console's permission level does not widen the list");
        check(AclContext.twinBindRefusal(LevelBasedPermissionSet.GAMEMASTER, true, "light.porch").isEmpty(), "An op binds a listed entity");
        check(AclContext.twinBindRefusal(PermissionSet.NO_PERMISSIONS, true, "light.porch").orElse("").contains("mineqtt:twin.bind"),
                "A player without the permission does not");
        check(AclContext.twinBindRefusal(binder, true, "light.porch").isEmpty(), "A holder of mineqtt:twin.bind does");
        MineQTTConfig.twinStatestreamBase = "";
        check(AclContext.twinBindRefusal(LevelBasedPermissionSet.OWNER, true, "light.porch").orElse("").contains("set twin.statestreamBase"),
                "Without a statestream base the twin is off for everyone");
        MineQTTConfig.resetToDefaults();
    }

    /**
     * M2 plan D6: exposing a device needs op level 2 or {@code mineqtt:ha.expose}, and that permission opens
     * {@code /mineqtt devices expose} only, never another subcommand.
     */
    private static void exposeRights() {
        PermissionSet exposer = permission -> permission.equals(AclContext.HA_EXPOSE);
        check(AclContext.mayExpose(LevelBasedPermissionSet.GAMEMASTER) && AclContext.mayExpose(LevelBasedPermissionSet.OWNER), "Ops may expose");
        check(AclContext.mayExpose(exposer), "A holder of mineqtt:ha.expose may expose");
        check(!AclContext.mayExpose(PermissionSet.NO_PERMISSIONS) && !AclContext.mayExpose(LevelBasedPermissionSet.MODERATOR), "Nobody else may");
        check(!AclContext.mayExpose(permission -> permission.equals(AclContext.ADMIN)), "mineqtt:admin alone grants no exposure");

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        MineQTTCommands.register(dispatcher);
        CommandSourceStack op = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
        CommandSourceStack holder = Commands.createCompilationContext(exposer);
        List<CommandSourceStack> outsiders = List.of(Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS),
                Commands.createCompilationContext(LevelBasedPermissionSet.MODERATOR));
        for (String state : List.of("on", "off", "default")) {
            String command = "mineqtt devices expose lamp " + state;
            check(parses(dispatcher, command, op) && parses(dispatcher, command, holder), "An op and a holder may run " + command);
            outsiders.forEach(source -> check(!parses(dispatcher, command, source), "Nobody else may run " + command));
        }
        check(!parses(dispatcher, "mineqtt devices expose lamp maybe", op), "Only on, off and default");
        check(!parses(dispatcher, "mineqtt devices access lamp open", op), "Only private, claim and public");
        for (String command : List.of("mineqtt status", "mineqtt reconnect", "mineqtt devices list", "mineqtt devices info lamp",
                "mineqtt devices rename lamp \"Living Room\"", "mineqtt devices remove lamp", "mineqtt devices prune",
                "mineqtt devices retry lamp", "mineqtt devices retry 12 -60 3", "mineqtt devices eject lamp",
                "mineqtt devices setting lamp mode switch", "mineqtt devices setting lamp name \"Hall Lamp\"",
                "mineqtt devices raw lamp channel garage", "mineqtt devices raw lamp private garage", "mineqtt devices raw lamp topic zigbee2mqtt/hall/set",
                "mineqtt devices raw lamp none",
                "mineqtt devices raw lamp retain off", "mineqtt devices raw lamp qos 1", "mineqtt devices raw lamp on OPEN", "mineqtt devices raw lamp off \"\"",
                "mineqtt devices action lamp state", "mineqtt devices action lamp state TOGGLE",
                "mineqtt devices access lamp private", "mineqtt devices access lamp claim", "mineqtt devices access lamp public", "mineqtt devices claim lamp",
                "mineqtt group create \"Hall\"", "mineqtt group list", "mineqtt group rename hall \"Hall Lights\"",
                "mineqtt group expose-members hall on", "mineqtt group expose-members hall off", "mineqtt discovery republish",
                "mineqtt wires list", "mineqtt wires list lamp", "mineqtt wires list group.hall", "mineqtt wires add input.powered lamp.state",
                "mineqtt wires add input.pulse group.hall.state pulse_toggle", "mineqtt wires remove 3f9c0000")) {
            check(parses(dispatcher, command, op), "An op may run " + command);
            check(!parses(dispatcher, command, holder), "mineqtt:ha.expose does not open " + command);
        }
        check(!parses(dispatcher, "mineqtt publish t x", op) && parses(dispatcher, "mineqtt publish t x",
                Commands.createCompilationContext(LevelBasedPermissionSet.ADMIN)), "publish needs op level 3");
        // A subcommand added later without its own op requirement would open to every holder of mineqtt:ha.expose.
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("mineqtt");
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            check(child.canUse(holder) == child.getName().equals("devices"), "mineqtt:ha.expose reaches /mineqtt " + child.getName() + ": " + child.canUse(holder));
        }
        for (CommandNode<CommandSourceStack> child : root.getChild("devices").getChildren()) {
            check(child.canUse(holder) == child.getName().equals("expose"), "mineqtt:ha.expose reaches devices " + child.getName() + ": " + child.canUse(holder));
        }
    }

    // Whether the whole command parses to something the source may run; nothing is executed.
    private static boolean parses(CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
        return !parse.getReader().canRead() && parse.getExceptions().isEmpty() && parse.getContext().getCommand() != null;
    }

    /**
     * M2-6: what {@code DeviceConfigC2S}, {@code DeviceActionC2S} and their command twins accept. Only keys the type
     * declares, values within their kind and bounds (a name of 64 characters, topics of 256 bytes, payloads of 1 KiB),
     * a setting only with CONFIGURE, exposure and access only with their own right, an action only when declared and
     * with USE; settings reach the device as its command.
     */
    private static void deviceConfig() {
        DeviceView.Grants user = new DeviceView.Grants(true, false, false, false);
        DeviceView.Grants configurer = new DeviceView.Grants(true, true, false, false);
        DeviceView.Grants nobody = DeviceView.Grants.NONE;
        DeviceView.Grants all = DeviceView.Grants.ALL;

        refused(RedstoneOutput.TYPE, "power_level", new JsonPrimitive(3), all, "an unknown key");
        refused(RedstoneOutput.TYPE, "location", new JsonPrimitive("x"), all, "a read-only field");
        refused(RedstoneOutput.TYPE, "last_level", new JsonPrimitive(3), all, "a read-only field");
        refused(RedstoneOutput.TYPE, "state", new JsonPrimitive("ON"), all, "an action, which goes as an action");
        DeviceConfig.Change mode = accepted(RedstoneOutput.TYPE, "mode", new JsonPrimitive("SWITCH"), configurer);
        check(mode instanceof DeviceConfig.Command command && command.command().toString().equals("{\"mode\":\"switch\"}"),
                "A setting reaches the device as its command, in the field's encoding: " + mode);
        refused(RedstoneOutput.TYPE, "mode", new JsonPrimitive("switch"), user, "a setting without CONFIGURE");
        refused(RedstoneOutput.TYPE, "mode", new JsonPrimitive("bogus"), all, "an unknown enum name");
        accepted(PresenceSensor.TYPE, "range", new JsonPrimitive(8), configurer);
        for (int range : new int[]{0, 9, -1}) refused(PresenceSensor.TYPE, "range", new JsonPrimitive(range), all, "range " + range);
        refused(PresenceSensor.TYPE, "range", new JsonPrimitive(2.5), all, "a fraction of a whole-number setting");
        refused(PresenceSensor.TYPE, "clear_delay", new JsonPrimitive(3601), all, "a clear delay over an hour");
        refused(PresenceSensor.TYPE, "detect", new JsonPrimitive(true), all, "garbage for an enum");
        check(DeviceConfig.parse(PresenceSensor.TYPE, "range", IntTag.valueOf(5), configurer).isSuccess(), "A client's NBT int is read");
        check(DeviceConfig.parse(RedstoneOutput.TYPE, "mode", StringTag.valueOf("level"), configurer).isSuccess(), "A client's NBT string is read");

        // M2-9: a command to a writable field that is no setting needs USE, not CONFIGURE, and is as strict as a setting.
        check(accepted(RedstoneOutput.TYPE, "power", new JsonPrimitive(7), user).equals(new DeviceConfig.Command(JsonParser.parseString("{\"power\":7}").getAsJsonObject())),
                "A user sets the output's level, as the command {\"power\":7}");
        refused(RedstoneOutput.TYPE, "power", new JsonPrimitive(7), new DeviceView.Grants(false, true, true, true), "a command without USE");
        for (int power : new int[]{16, -1}) refused(RedstoneOutput.TYPE, "power", new JsonPrimitive(power), all, "level " + power);
        refused(RedstoneOutput.TYPE, "power", new JsonPrimitive(2.5), all, "a fraction of a level");
        check(DeviceConfig.parse(RedstoneOutput.TYPE, "power", IntTag.valueOf(15), user).isSuccess(), "A client's NBT int sets the level");
        JsonObject light = JsonParser.parseString("{\"state\":\"ON\",\"brightness\":60}").getAsJsonObject();
        check(accepted(SmartLamp.TYPE, "light", light, user).equals(new DeviceConfig.Command(light)),
                "A light command reaches the lamp as sent, its keys at the top level and nothing filled in");
        CompoundTag color = new CompoundTag();
        color.putInt("r", 255);
        color.putInt("g", 0);
        color.putInt("b", 0);
        CompoundTag lightTag = new CompoundTag();
        lightTag.putString("state", "ON");
        lightTag.put("color", color);
        check(DeviceConfig.parse(SmartLamp.TYPE, "light", lightTag, user).getOrThrow()
                        .equals(new DeviceConfig.Command(JsonParser.parseString("{\"state\":\"ON\",\"color\":{\"r\":255,\"g\":0,\"b\":0}}").getAsJsonObject())),
                "A client's NBT compound is a light command");
        refused(SmartLamp.TYPE, "light", new JsonPrimitive("ON"), all, "a light that is no object");
        refused(SmartLamp.TYPE, "light", JsonParser.parseString("{\"brightness\":300}"), all, "a brightness over 255");
        refused(SmartLamp.TYPE, "light", JsonParser.parseString("{\"color_temp\":300}"), all, "a light key the picker does not send");
        refused(SmartLamp.TYPE, "light", JsonParser.parseString("{}"), all, "an empty light command");
        // M2-25: the command twin may set an effect and fade to the new light, within bounds.
        JsonObject fade = JsonParser.parseString("{\"state\":\"ON\",\"effect\":\"candle\",\"transition\":1.5}").getAsJsonObject();
        check(accepted(SmartLamp.TYPE, "light", fade, user).equals(new DeviceConfig.Command(fade)), "An effect and a transition reach the lamp as sent");
        refused(SmartLamp.TYPE, "light", JsonParser.parseString("{\"effect\":\"disco\"}"), all, "an unknown effect");
        refused(SmartLamp.TYPE, "light", JsonParser.parseString("{\"state\":\"ON\",\"transition\":3601}"), all, "a transition over an hour");
        refused(SmartLamp.TYPE, "light", light, nobody, "a light command without USE");
        check(DeviceConfig.permission(RedstoneOutput.TYPE, "power") == MineqttPermission.USE && DeviceConfig.permission(SmartLamp.TYPE, "light") == MineqttPermission.USE,
                "The guard lets a command to a writable field through with USE");
        check(DeviceConfig.permission(RedstoneOutput.TYPE, "mode") == MineqttPermission.CONFIGURE && DeviceConfig.permission(SmartLamp.TYPE, "name") == MineqttPermission.CONFIGURE
                        && DeviceConfig.permission(RedstoneOutput.TYPE, "state") == MineqttPermission.CONFIGURE
                        && DeviceConfig.permission(RedstoneOutput.TYPE, "bogus") == MineqttPermission.CONFIGURE,
                "The guard still asks CONFIGURE for settings, link settings, actions sent as settings and unknown keys");
        check(DeviceConfig.commandValue(RedstoneOutput.TYPE, "power", "7").equals(new JsonPrimitive(new BigDecimal(7))), "A twin reads a level as a number");
        check(DeviceConfig.commandValue(SmartLamp.TYPE, "light", "{\"state\":\"OFF\"}").equals(JsonParser.parseString("{\"state\":\"OFF\"}")),
                "A twin reads a light command as JSON");

        // Link settings: the name, its bounds and gates.
        check(accepted(SmartLamp.TYPE, "name", new JsonPrimitive("n".repeat(64)), configurer) instanceof DeviceConfig.Rename, "A name of 64 characters");
        refused(SmartLamp.TYPE, "name", new JsonPrimitive("n".repeat(65)), all, "a name of 65 characters");
        refused(SmartLamp.TYPE, "name", new JsonPrimitive("§r  "), all, "a name that is blank once sanitised");
        refused(SmartLamp.TYPE, "name", new JsonPrimitive("Hall"), user, "a rename without CONFIGURE");
        refused(SmartLamp.TYPE, "expose", new JsonPrimitive("on"), configurer, "exposing without mineqtt:ha.expose");
        check(accepted(SmartLamp.TYPE, "expose", new JsonPrimitive("default"), new DeviceView.Grants(true, true, true, false))
                .equals(new DeviceConfig.Expose(null)), "default follows ha.expose");
        refused(SmartLamp.TYPE, "access", new JsonPrimitive("private"), configurer, "an access change by someone who is neither the owner nor an op");
        check(accepted(SmartLamp.TYPE, "access", new JsonPrimitive("PUBLIC"), new DeviceView.Grants(false, false, false, true))
                .equals(new DeviceConfig.Access(AccessMode.PUBLIC)), "The owner or an op sets the access mode");
        refused(SmartLamp.TYPE, "access", new JsonPrimitive("everyone"), all, "an unknown access mode");

        // The Raw Link: which keys a type has, topic and payload bounds.
        String prefix = "mineqtt/shared/";
        check(accepted(RedstoneInput.TYPE, "raw.topic", new JsonPrimitive(prefix + "x".repeat(256 - prefix.length())), configurer)
                instanceof DeviceConfig.RawTopic, "A typed topic of 256 bytes");
        refused(RedstoneInput.TYPE, "raw.topic", new JsonPrimitive(prefix + "x".repeat(257 - prefix.length())), all, "a typed topic of 257 bytes");
        refused(RedstoneInput.TYPE, "raw.channel", new JsonPrimitive("é".repeat(129)), all, "a channel of 258 UTF-8 bytes");
        refused(RedstoneInput.TYPE, "raw.topic", new JsonPrimitive("home/+/door"), all, "a wildcard topic");
        check(accepted(RedstoneInput.TYPE, "raw.topic", new JsonPrimitive(""), configurer).equals(new DeviceConfig.RawTopic("")), "Empty removes the link");
        check(accepted(RedstoneInput.TYPE, "raw.on", new JsonPrimitive("x".repeat(1024)), configurer) instanceof DeviceConfig.RawPayload,
                "A payload of 1 KiB");
        refused(RedstoneInput.TYPE, "raw.off", new JsonPrimitive("€".repeat(342)), all, "a payload of 1026 UTF-8 bytes");
        check(accepted(RedstoneInput.TYPE, "raw.off", new JsonPrimitive(""), configurer).equals(new DeviceConfig.RawPayload(false, null)),
                "An empty payload removes it");
        check(DeviceConfig.parse(RedstoneInput.TYPE, "raw.retain", ByteTag.valueOf(false), configurer).getOrThrow().equals(new DeviceConfig.RawRetain(false)),
                "A client's NBT boolean is read");
        refused(RedstoneInput.TYPE, "raw.qos", new JsonPrimitive(2), all, "QoS 2");
        refused(RedstoneInput.TYPE, "raw.retain", new JsonPrimitive(true), user, "a Raw Link edit without CONFIGURE");
        refused(SmartLamp.TYPE, "raw.retain", new JsonPrimitive(true), all, "retain on an input's link");
        refused(SmartLamp.TYPE, "raw.qos", new JsonPrimitive(1), all, "QoS on an input's link");
        refused(PresenceSensor.TYPE, "raw.on", new JsonPrimitive("1"), all, "custom payloads on a link that has none");

        // Command twins read typed text by the key's kind.
        check(DeviceConfig.commandValue(SmartLamp.TYPE, "name", " \"Living Room\" ").equals(new JsonPrimitive("Living Room")), "A name loses one pair of quotes");
        check(DeviceConfig.commandValue(PresenceSensor.TYPE, "range", "5").equals(new JsonPrimitive(new BigDecimal(5))), "A number is read as one");
        check(DeviceConfig.commandValue(RedstoneInput.TYPE, "raw.on", "\"\"").equals(new JsonPrimitive("")), "\"\" is the empty text");

        // Actions: declared ones only, with USE.
        check(DeviceConfig.action(RedstoneOutput.TYPE, "state", "on", user).getOrThrow().toString().equals("{\"state\":\"ON\"}"), "A user switches the output on");
        check(DeviceConfig.action(SmartLamp.TYPE, "state", "toggle", user).getOrThrow().toString().equals("{\"state\":\"TOGGLE\"}"), "And toggles the lamp");
        check(DeviceConfig.action(RedstoneOutput.TYPE, "state", "on", nobody).isError(), "Nobody without USE performs an action");
        check(DeviceConfig.action(RedstoneOutput.TYPE, "state", "dim", user).isError(), "A value that does not fit the action is refused");
        check(DeviceConfig.action(RedstoneOutput.TYPE, "power", "7", all).isError(), "A field that is no declared action is refused");
        check(DeviceConfig.action(PresenceSensor.TYPE, "state", "on", all).isError(), "A type without the action refuses it");
        check(DeviceConfig.action(RedstoneOutput.TYPE, "state", "x".repeat(1025), all).isError(), "An action value over 1 KiB is refused");
    }

    /**
     * M2-6: a typed Raw Link topic is used as typed when the ACL allows it for the player who typed it, an operator's
     * bypass of the allowlist included, and for the device's owner; it stores no channel, so no attach resolves it again.
     */
    private static void typedTopic() {
        UUID steve = UUID.fromString("6a1c0f4e-0b6e-4a43-9d76-2f4f6f0e5a11");
        UUID alex = UUID.fromString("0e5d8a3b-6c7f-4b29-8a1e-3c9d2b4f7e60");
        List<String> allow = TopicAcl.defaultRawAllow("mineqtt", "world");
        TopicAcl integrated = new TopicAcl(new TopicAcl.Rules("mineqtt", "world", "homeassistant", null, allow, true, false));
        TopicAcl dedicated = new TopicAcl(new TopicAcl.Rules("mineqtt", "world", "homeassistant", null, allow, false, false));
        TopicAcl.Subject op = new TopicAcl.Subject(steve, true, false);
        TopicAcl.Subject guest = new TopicAcl.Subject(alex, false, false);
        String zigbee = "zigbee2mqtt/hall/set";

        check(DeviceConfig.typedTopicProblem(integrated, op, op, zigbee).isEmpty(), "An integrated server's op types a topic outside the allowlist for their device");
        check(DeviceConfig.typedTopicProblem(integrated, guest, op, zigbee).equals(Optional.of(TopicAcl.Decision.NOT_ALLOWLISTED.message())),
                "A guest may not point the host's device at it");
        check(DeviceConfig.typedTopicProblem(integrated, op, guest, zigbee).orElse("").contains("owner"),
                "Nor an op a guest's device, which publishes for the guest");
        check(DeviceConfig.typedTopicProblem(dedicated, op, op, zigbee).isPresent(), "A dedicated server's op has no bypass");
        check(DeviceConfig.typedTopicProblem(integrated, op, op, "homeassistant/light/x/set").isPresent(), "The hard deny holds for ops");
        check(DeviceConfig.typedTopicProblem(dedicated, guest, guest, "mineqtt/shared/garage").isEmpty(), "Anyone may type an allowlisted topic");
        check(DeviceConfig.typedTopicProblem(dedicated, guest, AclContext.NOBODY, "mineqtt/shared/garage").isEmpty(), "Also for a device without an owner");
        TopicAcl.Subject console = TopicAcl.Subject.CONSOLE;
        check(DeviceConfig.typedTopicProblem(integrated, console, op, zigbee).isEmpty(), "The console types for an op's device like an op");

        // The editor is judged as they typed it, the owner as the op list sees them: on an integrated server the host is an op.
        Function<NameAndId, PermissionSet> opList = profile -> profile.id().equals(steve) ? LevelBasedPermissionSet.GAMEMASTER
                : PermissionSet.NO_PERMISSIONS;
        DeviceRuntime.Editor guestEditor = new DeviceRuntime.Editor(DeviceView.Grants.ALL, guest, null);
        DeviceRuntime.Editor hostEditor = new DeviceRuntime.Editor(DeviceView.Grants.ALL, op, null);
        check(DeviceConfig.typedTopicProblem(integrated, guestEditor, steve, "Steve", opList, zigbee)
                        .equals(Optional.of(TopicAcl.Decision.NOT_ALLOWLISTED.message())),
                "A guest with CONFIGURE on the host's device may not use the host's bypass");
        check(DeviceConfig.typedTopicProblem(integrated, hostEditor, alex, "Alex", opList, zigbee).orElse("").contains("owner"),
                "The host may not point a guest's device at it");
        check(DeviceConfig.typedTopicProblem(integrated, hostEditor, steve, "Steve", opList, zigbee).isEmpty(),
                "The host types it for their own device");
        check(DeviceConfig.typedTopicProblem(integrated, hostEditor, null, "", opList, zigbee).orElse("").contains("owner"),
                "Nor for a device without an owner");

        RawLink grandfathered = new RawLink("home/garage", RawLinkSpec.Direction.OUT, "OPEN", "SHUT", 1, false,
                new TopicAcl.Grandfather("home/garage", steve), true, null, 0, null, RawLink.Edges.BOTH);
        check(DeviceConfig.typedTopicProblem(dedicated, op, op, grandfathered.topic()).isPresent(), "Typing a grandfathered topic again drops its exemption");
        RawLink typed = RawLink.typed(zigbee, RawLinkSpec.Direction.OUT, grandfathered);
        check(typed.channel() == null && typed.grandfathered() == null && typed.resolved() && typed.active(), "A typed topic has no channel, no grandfather flag and is resolved: " + typed);
        check("OPEN".equals(typed.on()) && "SHUT".equals(typed.off()) && typed.qos() == 1 && Boolean.FALSE.equals(typed.retain()) && typed.stateEcho(),
                "It keeps the payloads, QoS, retain override and state echo it replaces");
        check(RawTopics.reResolveM1(typed, false, null, dedicated, steve, Set.of()).pass() == RawTopics.Pass.NONE
                        && RawTopics.recheck(typed, false, dedicated, steve, Set.of(zigbee)).equals(typed),
                "No attach resolves a typed topic again, not even from the owner's literal set");
        RawLink fresh = RawLink.typed("mineqtt/shared/garage", RawLinkSpec.Direction.IN, null);
        check(fresh.on() == null && fresh.qos() == 0 && fresh.retain() == null && !fresh.stateEcho() && fresh.resolved(), "A first typed topic has the defaults");
        check(grandfathered.withRetain(true).grandfathered() != null && grandfathered.withQos(0).topic().equals("home/garage"),
                "Retain and QoS change on the link as it is");
    }

    /**
     * M2-20 to M2-22: every payload the client and server exchange, each a record in {@link MineqttNetworking} whose type
     * has one of its declared ids, and no other. The retired blocks' payloads are gone with their screens, and so is the
     * Light Remote's command, which published a client's JSON to its topic, so a client cannot reach a block entity
     * through them any more.
     */
    private static void payloadInventory() {
        Set<Identifier> declared = new HashSet<>();
        Set<Identifier> payloads = new HashSet<>();
        try {
            for (Field field : MineqttNetworking.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == Identifier.class) declared.add((Identifier) field.get(null));
            }
            for (Class<?> nested : MineqttNetworking.class.getDeclaredClasses()) {
                if (!CustomPacketPayload.class.isAssignableFrom(nested)) continue;
                Identifier id = ((CustomPacketPayload.Type<?>) nested.getField("TYPE").get(null)).id();
                check(payloads.add(id), "Two payloads share the id " + id);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("The payloads of MineqttNetworking could not be read", e);
        }
        check(payloads.equals(declared), "Every declared id has its payload and every payload a declared id: " + declared + " / " + payloads);
        // M3-13: TwinViewS2C, TwinSearchC2S and TwinResultsS2C; M3-17's preset search reuses the last two with a kind (wave 7).
        check(payloads.size() == 32, "32 payloads, " + payloads.size() + " found: a new or retired one updates this count");
        for (String retired : List.of("set_static_message", "test_static_publish", "set_topic_relay_source", "set_topic_relay_target", "test_topic_relay",
                "set_dynamic_data_type", "test_dynamic_publish", "light_remote_command")) {
            check(!payloads.contains(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, retired)), "The retired payload " + retired + " is gone");
        }
    }

    /** M2-6: the device payloads are bounded on the wire; the setting value by an NBT quota. */
    private static void devicePayloads() {
        BlockPos pos = new BlockPos(3, -60, 4);
        DeviceConfigPayload config = new DeviceConfigPayload(pos, "raw.on", StringTag.valueOf("x".repeat(1024)));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceConfigPayload.CODEC.encode(buf, config);
        check(DeviceConfigPayload.CODEC.decode(buf).equals(config), "A setting of a 1 KiB payload crosses the wire");
        RegistryFriendlyByteBuf large = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceConfigPayload.CODEC.encode(large, new DeviceConfigPayload(pos, "raw.on", StringTag.valueOf("x".repeat(5000))));
        check(fails(() -> DeviceConfigPayload.CODEC.decode(large)), "A value over the NBT quota is not read");
        DeviceActionPayload action = new DeviceActionPayload(pos, "state", "TOGGLE");
        RegistryFriendlyByteBuf actionBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceActionPayload.CODEC.encode(actionBuf, action);
        check(DeviceActionPayload.CODEC.decode(actionBuf).equals(action), "An action crosses the wire");
        // M2-9: the Controls tab's level and light commands.
        CompoundTag light = new CompoundTag();
        light.putString("state", "ON");
        light.putInt("brightness", 200);
        for (DeviceConfigPayload command : List.of(new DeviceConfigPayload(pos, "power", IntTag.valueOf(7)), new DeviceConfigPayload(pos, "light", light))) {
            RegistryFriendlyByteBuf commandBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            DeviceConfigPayload.CODEC.encode(commandBuf, command);
            check(DeviceConfigPayload.CODEC.decode(commandBuf).equals(command), "A command to " + command.key() + " crosses the wire");
        }
        check(fails(() -> DeviceActionPayload.CODEC.encode(new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY),
                new DeviceActionPayload(pos, "s".repeat(65), ""))), "An action key over 64 characters does not encode");
        // M2-10: a refusal goes back to the screen it came from, cut so it always encodes.
        DeviceRefusedPayload refused = DeviceRefusedPayload.of(pos, "k".repeat(100), "r".repeat(10_000));
        check(refused.key().length() == DeviceConfigPayload.MAX_KEY_CHARS && refused.reason().length() == DeviceStatusPayload.MAX_STATUS_CHARS,
                "A refusal's key and reason are cut to their caps");
        DeviceRefusedPayload topic = DeviceRefusedPayload.of(pos, "raw.topic", "garage_door may not use the topic zigbee2mqtt/x/set: not in the raw topic allowlist");
        for (DeviceRefusedPayload payload : List.of(refused, topic)) {
            RegistryFriendlyByteBuf refusedBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            DeviceRefusedPayload.CODEC.encode(refusedBuf, payload);
            check(DeviceRefusedPayload.CODEC.decode(refusedBuf).equals(payload), "A refusal crosses the wire unchanged");
        }
        check(topic.key().equals("raw.topic") && topic.reason().endsWith("allowlist"), "A short refusal is sent whole");
        // M2-11: a claim and a retry name only the device.
        DeviceClaimPayload claim = new DeviceClaimPayload(pos);
        RegistryFriendlyByteBuf claimBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceClaimPayload.CODEC.encode(claimBuf, claim);
        check(DeviceClaimPayload.CODEC.decode(claimBuf).equals(claim) && claimBuf.readableBytes() == 0, "A claim crosses the wire");
        DeviceRetryPayload retry = new DeviceRetryPayload(pos);
        RegistryFriendlyByteBuf retryBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceRetryPayload.CODEC.encode(retryBuf, retry);
        check(DeviceRetryPayload.CODEC.decode(retryBuf).equals(retry) && retryBuf.readableBytes() == 0, "A retry crosses the wire");
        // M2-12: so does an eject.
        DeviceEjectPayload eject = new DeviceEjectPayload(pos);
        RegistryFriendlyByteBuf ejectBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceEjectPayload.CODEC.encode(ejectBuf, eject);
        check(DeviceEjectPayload.CODEC.decode(ejectBuf).equals(eject) && ejectBuf.readableBytes() == 0, "An eject crosses the wire");
    }

    /**
     * The Configurator's Link mode (M2 plan §4.2 M2-16): a pick passes the guard only while it answers the player's open
     * offer, with a Configurator in Link mode and CONFIGURE on both devices; a wire needs CONFIGURE on both ends, on every
     * member of a group end, and stays within {@code limits.maxWiresPerPlayer}, which the console's wires do not count
     * against; the link payloads cross the wire within their caps.
     */
    private static void linkMode() {
        BooleanSupplier yes = () -> true;
        BooleanSupplier no = () -> false;
        check(C2SGuard.decide(C2SGuard.offerFacts(yes, yes, true, yes, yes, yes, yes, yes, yes)).isEmpty(),
                "A pick that answers the offer, in Link mode, at a device in reach the player may configure on both ends passes");
        check(C2SGuard.decide(C2SGuard.offerFacts(yes, yes, false, yes, yes, yes, yes, yes, yes)).equals(Optional.of(Rejection.NO_MENU)),
                "A pick that answers no open offer is dropped");
        check(C2SGuard.decide(C2SGuard.offerFacts(yes, yes, true, no, yes, yes, yes, yes, yes)).equals(Optional.of(Rejection.MENU_INVALID)),
                "A pick without a Configurator in Link mode is dropped");
        check(C2SGuard.decide(C2SGuard.offerFacts(yes, yes, true, yes, yes, no, yes, yes, yes)).equals(Optional.of(Rejection.OUT_OF_REACH)),
                "A pick beyond the Configurator's reach of the device is dropped");
        check(C2SGuard.decide(C2SGuard.offerFacts(yes, yes, true, yes, yes, yes, no, yes, yes)).equals(Optional.of(Rejection.WRONG_BLOCK_ENTITY)),
                "A pick for a device that is no longer there is dropped");
        check(C2SGuard.decide(C2SGuard.offerFacts(yes, yes, true, yes, yes, yes, yes, no, yes)).equals(Optional.of(Rejection.DENIED))
                && C2SGuard.decide(C2SGuard.offerFacts(yes, yes, true, yes, yes, yes, yes, yes, no)).equals(Optional.of(Rejection.DENIED)),
                "A pick needs CONFIGURE on the device clicked first and on the one clicked second");
        check(C2SGuard.decide(C2SGuard.offerFacts(yes, no, true, yes, yes, yes, yes, yes, yes)).equals(Optional.of(Rejection.SPECTATOR)),
                "A spectator's pick is dropped");

        DeviceRegistry registry = new DeviceRegistry("links_0001", UUID.randomUUID());
        DeviceRecord input = linkDevice(registry, RedstoneInput.TYPE, "input", 0);
        DeviceRecord output = linkDevice(registry, RedstoneOutput.TYPE, "output", 1);
        DeviceRecord lamp1 = linkDevice(registry, SmartLamp.TYPE, "lamp_1", 2);
        DeviceRecord lamp2 = linkDevice(registry, SmartLamp.TYPE, "lamp_2", 3);
        DeviceGroup lamps = registry.createGroup(UUID.randomUUID(), "Lamps");
        registry.joinGroup(lamps, lamp1);
        registry.joinGroup(lamps, lamp2);
        UUID player = UUID.randomUUID();
        Endpoint powered = Endpoint.port(input.id(), "powered");
        Endpoint signal = Endpoint.port(input.id(), "signal");
        Set<UUID> configurable = new HashSet<>(Set.of(input.id(), output.id(), lamp1.id(), lamp2.id()));
        Wire toOutput = linkWire(player, powered, Endpoint.port(output.id(), "state"));
        check(LinkRules.allowed(registry, toOutput, configurable::contains, 256).isEmpty(), "A player who may configure both ends may wire them");
        configurable.remove(output.id());
        check(LinkRules.allowed(registry, toOutput, configurable::contains, 256).orElse("").contains("may not configure output"),
                "Not without CONFIGURE on the target");
        configurable.add(output.id());
        configurable.remove(input.id());
        check(LinkRules.allowed(registry, toOutput, configurable::contains, 256).orElse("").contains("may not configure input"),
                "Nor without CONFIGURE on the source");
        configurable.add(input.id());
        configurable.remove(lamp2.id());
        Wire toLamps = linkWire(player, powered, Endpoint.groupPort(lamps.id(), "state"));
        check(LinkRules.allowed(registry, toLamps, configurable::contains, 256).orElse("").contains("may not configure lamp_2"),
                "A wire into a group needs CONFIGURE on every member");
        configurable.add(lamp2.id());
        check(LinkRules.allowed(registry, toLamps, configurable::contains, 256).isEmpty(), "With every member it may be made");
        check(LinkRules.allowed(registry, linkWire(player, powered, Endpoint.port(input.id(), "powered")), configurable::contains, 256).isPresent(),
                "The registry's rules still apply: no self-wire");

        // Removing a link from a device's list: a wire at the device goes with CONFIGURE on it, a wire into its group only
        // with CONFIGURE on the source or on every member.
        Set<UUID> onlyLamp1 = Set.of(lamp1.id());
        check(LinkRules.mayRemove(registry, toOutput, output.id(), Set.of(output.id())::contains)
                        && LinkRules.mayRemove(registry, toOutput, input.id(), Set.of(input.id())::contains),
                "Either end of a wire may cut it, whoever made it");
        check(!LinkRules.mayRemove(registry, toLamps, lamp1.id(), onlyLamp1::contains),
                "One member's configurer may not cut a wire into the group from the other members");
        check(LinkRules.mayRemove(registry, toLamps, lamp1.id(), Set.of(lamp1.id(), lamp2.id())::contains),
                "With CONFIGURE on every member it may");
        check(LinkRules.mayRemove(registry, toLamps, lamp1.id(), Set.of(lamp1.id(), input.id())::contains),
                "So may the source's configurer");
        check(!LinkRules.mayRemove(registry, toOutput, lamp1.id(), configurable::contains), "A wire that does not touch the device is not its to remove");

        // The cap: the player's own wires count, the console's and other players' do not; 0 means no limit.
        registry.addWire(toOutput);
        registry.addWire(linkWire(player, signal, Endpoint.port(output.id(), "power")));
        registry.addWire(linkWire(Util.NIL_UUID, Endpoint.port(output.id(), "state"), Endpoint.port(lamp1.id(), "state")));
        registry.addWire(linkWire(Util.NIL_UUID, Endpoint.port(output.id(), "state"), Endpoint.port(lamp2.id(), "state")));
        registry.addWire(linkWire(Util.NIL_UUID, Endpoint.port(input.id(), "pulse"), Endpoint.port(output.id(), "state")));
        registry.addWire(linkWire(UUID.randomUUID(), Endpoint.port(lamp1.id(), "light_level"), Endpoint.port(output.id(), "power")));
        check(LinkRules.owned(registry, player) == 2, "The player owns two wires: " + LinkRules.owned(registry, player));
        Wire third = linkWire(player, powered, Endpoint.port(lamp2.id(), "state"));
        check(LinkRules.allowed(registry, third, configurable::contains, 3).isEmpty(), "A third wire fits a cap of three, whatever the console made");
        check(LinkRules.allowed(registry, third, configurable::contains, 2).orElse("").contains("limits.maxWiresPerPlayer"),
                "A cap of two refuses the third wire and names the limit");
        check(LinkRules.allowed(registry, third, configurable::contains, 0).isEmpty(), "A cap of 0 is no limit");
        Wire console = linkWire(Util.NIL_UUID, powered, Endpoint.port(lamp2.id(), "state"));
        check(LinkRules.allowed(registry, console, device -> {
            throw new AssertionError("The console's wire asks nobody's permission");
        }, 1).isEmpty(), "The console's wire is trusted and counts against no cap");

        // The payloads: a device's links, a removal, the picker and a pick.
        BlockPos pos = new BlockPos(3, -60, 4);
        List<DeviceLinks.Row> rows = new ArrayList<>();
        for (int i = 0; i < DeviceLinks.MAX_ROWS + 6; i++) {
            rows.add(DeviceLinks.Row.of(UUID.randomUUID(), i % 2 == 0, "p".repeat(80), "Ä".repeat(300) + ".state", i % 3 == 0 ? "G".repeat(300) : "",
                    Mapping.values()[i % Mapping.values().length], i % 5 != 0));
        }
        DeviceLinks links = DeviceLinks.of(pos, rows);
        check(links.rows().size() == DeviceLinks.MAX_ROWS && links.total() == rows.size(), "A device's links list the first 64 and count all");
        check(links.rows().getFirst().peer().getBytes(StandardCharsets.UTF_8).length <= DeviceLinks.MAX_LABEL_BYTES
                && links.rows().getFirst().port().length() <= DeviceLinks.MAX_PORT_CHARS, "A link's texts are cut to their caps");
        RegistryFriendlyByteBuf linksBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceLinksPayload.CODEC.encode(linksBuf, new DeviceLinksPayload(links));
        check(DeviceLinksPayload.CODEC.decode(linksBuf).links().equals(links) && linksBuf.readableBytes() == 0, "The largest links list crosses the wire unchanged");
        DeviceUnlinkPayload unlink = new DeviceUnlinkPayload(pos, UUID.randomUUID());
        RegistryFriendlyByteBuf unlinkBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceUnlinkPayload.CODEC.encode(unlinkBuf, unlink);
        check(DeviceUnlinkPayload.CODEC.decode(unlinkBuf).equals(unlink) && unlinkBuf.readableBytes() == 0, "A removal crosses the wire");
        List<LinkPickerPayload.Choice> choices = new ArrayList<>();
        for (int i = 0; i < LinkPickerPayload.MAX_CHOICES; i++) {
            choices.add(LinkPickerPayload.Choice.of("f".repeat(70), "enum " + "x|".repeat(100), "t".repeat(70), "number", i % 2 == 0 ? "" : "Ö".repeat(300),
                    List.of(Mapping.values()), i == 0, i == 1));
        }
        LinkPickerPayload picker = new LinkPickerPayload(42, "Ä".repeat(500), "Garage Door", choices);
        RegistryFriendlyByteBuf pickerBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        LinkPickerPayload.CODEC.encode(pickerBuf, picker);
        check(LinkPickerPayload.CODEC.decode(pickerBuf).equals(picker) && pickerBuf.readableBytes() == 0, "The largest picker crosses the wire unchanged");
        check(fails(() -> LinkPickerPayload.CODEC.encode(new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY),
                new LinkPickerPayload(1, "a", "b", Collections.nCopies(LinkPickerPayload.MAX_CHOICES + 1, choices.getFirst())))),
                "A picker with more choices than its cap does not encode");
        LinkChoicePayload choice = new LinkChoicePayload(42, 3, Mapping.INVERT, false);
        RegistryFriendlyByteBuf choiceBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        LinkChoicePayload.CODEC.encode(choiceBuf, choice);
        check(LinkChoicePayload.CODEC.decode(choiceBuf).equals(choice) && choiceBuf.readableBytes() == 0, "A pick crosses the wire");
    }

    /**
     * The group page and the Configurator's Group mode (M2 plan §4.2 M2-17): groups have no owner, so joining one needs
     * CONFIGURE on every member and nobody joins one without members, a group that replaces a v1 topic takes a device
     * only when its owner may use that topic, a group holds one groupable type, its name is bounded like a device's, an
     * emptied group goes unless it replaces a v1 topic, and the group payloads cross the wire within their caps.
     */
    private static void groupMode() {
        DeviceRegistry registry = new DeviceRegistry("groups_0001", UUID.randomUUID());
        DeviceRecord input = linkDevice(registry, RedstoneInput.TYPE, "input", 0);
        DeviceRecord output = linkDevice(registry, RedstoneOutput.TYPE, "output", 1);
        DeviceRecord kitchen = linkDevice(registry, SmartLamp.TYPE, "kitchen", 2);
        DeviceRecord hall = linkDevice(registry, SmartLamp.TYPE, "hall", 3);
        DeviceRecord porch = linkDevice(registry, SmartLamp.TYPE, "porch", 4);
        DeviceRecord cellar = linkDevice(registry, SmartLamp.TYPE, "cellar", 5);
        DeviceGroup lamps = registry.createGroup(UUID.randomUUID(), "Lamps");
        registry.joinGroup(lamps, kitchen);
        registry.joinGroup(lamps, hall);
        DeviceGroup attic = registry.createGroup(UUID.randomUUID(), "attic");
        DeviceGroup outputs = registry.createGroup(UUID.randomUUID(), "Outputs");
        registry.joinGroup(outputs, output);
        DeviceGroup porchLights = registry.createGroup(UUID.randomUUID(), "Porch Lights");
        porchLights.setLegacyTopic("home/porch");
        registry.joinGroup(porchLights, cellar);
        Set<UUID> configurable = new HashSet<>(Set.of(kitchen.id(), hall.id(), porch.id(), cellar.id(), output.id(), input.id()));
        // Whether the joining device's owner may use a topic, as the runtime asks the ACL for them.
        Function<String, Optional<String>> allowed = topic -> Optional.empty();
        Function<String, Optional<String>> refused = topic -> Optional.of("the raw allowlist does not cover it");

        check(GroupRules.joinProblem(lamps, porch.type(), true, porch.id(), configurable::contains, refused).isEmpty(),
                "A player who may configure the lamp and every member joins it to the group; it follows no v1 topic");
        configurable.remove(hall.id());
        check(GroupRules.joinProblem(lamps, porch.type(), true, porch.id(), configurable::contains, allowed).orElse("").contains("every device"),
                "Not without CONFIGURE on every member: groups have no owner");
        check(!GroupRules.manages(lamps, configurable::contains), "Nor may they rename it");
        configurable.add(hall.id());
        check(GroupRules.joinProblem(attic, porch.type(), true, porch.id(), device -> true, allowed).orElse("").contains("no devices")
                        && !GroupRules.manages(attic, device -> true),
                "Nobody joins or renames a group without members, not even a player who may configure every device");
        check(GroupRules.joinProblem(porchLights, porch.type(), true, porch.id(), configurable::contains, allowed).isEmpty(),
                "A lamp joins a group that replaces a v1 topic when its owner may use the topic");
        check(GroupRules.joinProblem(porchLights, porch.type(), true, porch.id(), configurable::contains, refused).orElse("").contains("v1 topic home/porch"),
                "Not when the ACL refuses the topic to its owner: the group follows it under the legacy rules");
        check(GroupRules.joinProblem(lamps, hall.type(), true, hall.id(), configurable::contains, allowed).orElse("").contains("already"),
                "A member joins its group no second time");
        check(GroupRules.joinProblem(outputs, porch.type(), true, porch.id(), configurable::contains, allowed).orElse("").contains("holds"),
                "A group holds devices of one type");
        check(GroupRules.joinProblem(attic, input.type(), false, input.id(), configurable::contains, allowed).orElse("").contains("cannot be grouped"),
                "A sensor or an input joins no group");
        check(GroupRules.joinable(registry, porch, configurable::contains, allowed).stream().map(DeviceGroup::name).toList().equals(List.of("Lamps", "Porch Lights")),
                "A lamp may join the groups of lamps, by name, not the empty one: " + GroupRules.joinable(registry, porch, configurable::contains, allowed));
        check(GroupRules.joinable(registry, porch, configurable::contains, refused).stream().map(DeviceGroup::name).toList().equals(List.of("Lamps")),
                "Nor one whose v1 topic its owner may not use");
        check(GroupRules.joinable(registry, kitchen, configurable::contains, refused).isEmpty(), "Nor the group it is in");
        check(GroupRules.joinable(registry, porch, Set.of(porch.id())::contains, allowed).isEmpty(),
                "Nor a group whose members the player may not configure");
        check(GroupRules.joinable(registry, input, configurable::contains, allowed).isEmpty(), "An input may join nothing");

        String longest = "ä".repeat(GroupRules.MAX_NAME_LENGTH);
        check(GroupRules.name("  Kitchen Lights ").result().equals(Optional.of("Kitchen Lights")) && GroupRules.name(longest).isSuccess(),
                "A group name is stripped and may be as long as a device's");
        check(GroupRules.name(longest + "x").isError() && GroupRules.name("   ").isError() && GroupRules.name("§c").isError(),
                "A longer, blank or only formatting name is refused");

        registry.leaveGroup(output);
        check(GroupRules.dropsWhenEmptied(outputs), "A group a player emptied goes");
        DeviceGroup legacy = registry.createGroup(UUID.randomUUID(), "legacy");
        legacy.setLegacyTopic("home/lights");
        check(!GroupRules.dropsWhenEmptied(legacy) && !GroupRules.dropsWhenEmptied(lamps),
                "A group that replaces a v1 topic stays, and so does one with members");

        BlockPos pos = new BlockPos(3, -60, 4);
        for (GroupRules.Change change : List.of(new GroupRules.Join(lamps.id()), new GroupRules.Leave(), new GroupRules.Create(longest),
                new GroupRules.Rename("Hall Lights"), new GroupRules.ExposeMembers(true), new GroupRules.ExposeMembers(false))) {
            DeviceGroupPayload payload = new DeviceGroupPayload(pos, change);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            DeviceGroupPayload.CODEC.encode(buf, payload);
            check(DeviceGroupPayload.CODEC.decode(buf).equals(payload) && buf.readableBytes() == 0, "The group change " + change + " crosses the wire");
        }
        check(fails(() -> DeviceGroupPayload.CODEC.encode(new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY),
                new DeviceGroupPayload(pos, new GroupRules.Rename("x".repeat(1000))))), "A name far beyond the cap does not encode");
        List<DeviceGroups.Joinable> joinable = new ArrayList<>();
        for (int i = 0; i < DeviceGroups.MAX_JOINABLE + 5; i++) joinable.add(DeviceGroups.Joinable.of(UUID.randomUUID(), "Ö".repeat(300), i));
        DeviceGroups page = DeviceGroups.of(pos, true, Optional.of(DeviceGroups.Own.of(UUID.randomUUID(), "Ä".repeat(300), "t/".repeat(400), 3, true, false)),
                joinable);
        check(page.joinable().size() == DeviceGroups.MAX_JOINABLE && page.total() == joinable.size()
                        && page.own().orElseThrow().name().getBytes(StandardCharsets.UTF_8).length <= DeviceGroups.MAX_NAME_BYTES
                        && page.own().orElseThrow().topic().getBytes(StandardCharsets.UTF_8).length <= DeviceGroups.MAX_TOPIC_BYTES,
                "A group page offers the first 64 groups, counts all, and cuts its texts to their caps");
        for (DeviceGroups groups : List.of(page, DeviceGroups.none(pos))) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            DeviceGroupsPayload.CODEC.encode(buf, new DeviceGroupsPayload(groups));
            check(DeviceGroupsPayload.CODEC.decode(buf).groups().equals(groups) && buf.readableBytes() == 0, "A group page crosses the wire unchanged");
        }
        GroupPromptPayload prompt = new GroupPromptPayload(7, "Ä".repeat(500), "Hall Lamp");
        RegistryFriendlyByteBuf promptBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        GroupPromptPayload.CODEC.encode(promptBuf, prompt);
        check(GroupPromptPayload.CODEC.decode(promptBuf).equals(prompt) && promptBuf.readableBytes() == 0, "A name prompt crosses the wire, its names cut");
        GroupNamePayload named = new GroupNamePayload(7, longest);
        RegistryFriendlyByteBuf namedBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        GroupNamePayload.CODEC.encode(namedBuf, named);
        check(GroupNamePayload.CODEC.decode(namedBuf).equals(named) && namedBuf.readableBytes() == 0, "A group name crosses the wire");
    }

    /**
     * The Configurator's Copy-Paste mode (M2 plan §4.2 M2-17): what it copies is what a player sends from the device's
     * screen, in an order that sets up the Raw Link before its settings and changes the access mode last, so a paste
     * re-checks every one for the paster: a player who may only configure gets the Raw Link and the settings but not the
     * exposure or the access mode, one who may only use gets nothing, and a typed topic is judged for the paster.
     */
    private static void pasteRechecks() {
        DeviceView.Raw channel = new DeviceView.Raw("mineqtt/shared/garage", Optional.of("garage"), RawLinkSpec.Direction.IN, 0, Optional.empty(), false,
                Optional.of("OPEN"), Optional.empty(), Optional.empty(), RawLink.Edges.BOTH);
        JsonObject state = new JsonObject();
        state.addProperty("state", "ON");
        state.addProperty("power", 15);
        state.addProperty("mode", "switch");
        state.addProperty("power_on_behaviour", "off");
        DeviceClipboard output = DeviceClipboard.of(RedstoneOutput.TYPE, clipboardView(RedstoneOutput.TYPE, state, Optional.of(channel), false,
                Optional.of(true), AccessMode.PUBLIC));
        check(output.type().equals(RedstoneOutput.TYPE.id()) && output.source().equals("Garage Output"), "It remembers the type and the name it copied");
        check(output.entries().stream().map(DeviceClipboard.Entry::key).toList().equals(List.of("raw.channel", "raw.on", "raw.off", "mode", "power_on_behaviour",
                "expose", "access")), "An output copies its channel and payloads, its settings, its exposure and its access mode, in that order: " + output.entries());
        check(output.entries().stream().noneMatch(entry -> entry.key().equals("name") || entry.key().equals("state") || entry.key().equals("power")),
                "Never its name or its state");
        check(output.entries().get(2).value().getAsString().isEmpty(), "A payload it lacks is copied as none");
        DeviceClipboard same = DeviceClipboard.of(RedstoneOutput.TYPE, clipboardView(RedstoneOutput.TYPE, state.deepCopy(), Optional.of(channel), false,
                Optional.of(true), AccessMode.PUBLIC));
        check(output.entries().stream().allMatch(same.entries()::contains),
                "A target that has every setting already holds every entry, so a paste sends it nothing (and refuses nothing)");

        DeviceView.Grants configureOnly = new DeviceView.Grants(true, true, false, false);
        DeviceView.Grants useOnly = new DeviceView.Grants(true, false, false, false);
        for (DeviceClipboard.Entry entry : output.entries()) {
            boolean exposure = entry.key().equals("expose");
            boolean access = entry.key().equals("access");
            check(DeviceConfig.parse(RedstoneOutput.TYPE, entry.key(), entry.value(), DeviceView.Grants.ALL).isSuccess(), "An op pastes " + entry);
            check(DeviceConfig.parse(RedstoneOutput.TYPE, entry.key(), entry.value(), configureOnly).isSuccess() == (!exposure && !access),
                    "A player who may configure but not expose or change access pastes " + entry + " exactly when it is neither");
            check(DeviceConfig.parse(RedstoneOutput.TYPE, entry.key(), entry.value(), useOnly).isError(), "A player who may only use pastes nothing: " + entry);
        }
        check(output.entries().stream().filter(DeviceClipboard.Entry::link).count() == 1 && output.entries().getFirst().link()
                && output.entries().stream().filter(DeviceClipboard.Entry::raw).count() == 3, "The link comes first, before the rest of the Raw Link");

        DeviceView.Raw typed = new DeviceView.Raw("zigbee2mqtt/hall/set", Optional.empty(), RawLinkSpec.Direction.OUT, 1, Optional.of(false), false,
                Optional.empty(), Optional.empty(), Optional.empty(), RawLink.Edges.BOTH);
        DeviceClipboard input = DeviceClipboard.of(RedstoneInput.TYPE, clipboardView(RedstoneInput.TYPE, new JsonObject(), Optional.of(typed), false,
                Optional.empty(), AccessMode.CLAIM));
        check(input.entries().stream().map(DeviceClipboard.Entry::key).toList().equals(List.of("raw.topic", "raw.retain", "raw.qos", "raw.on", "raw.off",
                "expose", "access")), "An input copies its typed topic, retain override and QoS: " + input.entries());
        check(!input.entries().get(1).value().getAsBoolean(), "Its override as it is");
        DeviceView.Raw byDefault = new DeviceView.Raw("zigbee2mqtt/hall/set", Optional.empty(), RawLinkSpec.Direction.OUT, 1, Optional.empty(), true,
                Optional.empty(), Optional.empty(), Optional.empty(), RawLink.Edges.BOTH);
        DeviceClipboard defaulted = DeviceClipboard.of(RedstoneInput.TYPE, clipboardView(RedstoneInput.TYPE, new JsonObject(), Optional.of(byDefault), false,
                Optional.empty(), AccessMode.CLAIM));
        check(defaulted.entries().get(1).key().equals("raw.retain") && defaulted.entries().get(1).value().getAsBoolean(),
                "Without an override it copies its type's default (an input retains), so a target's own override does not survive: " + defaulted.entries());
        TopicAcl dedicated = new TopicAcl(new TopicAcl.Rules("mineqtt", "world", "homeassistant", null, TopicAcl.defaultRawAllow("mineqtt", "world"), false, false));
        TopicAcl.Subject paster = new TopicAcl.Subject(UUID.randomUUID(), false, false);
        check(DeviceConfig.typedTopicProblem(dedicated, paster, paster, input.entries().getFirst().value().getAsString()).isPresent(),
                "A copied typed topic is judged for the paster as if they typed it, and refused outside the allowlist");
        check(DeviceClipboard.of(RedstoneInput.TYPE, clipboardView(RedstoneInput.TYPE, new JsonObject(), Optional.of(channel), true, Optional.empty(),
                AccessMode.CLAIM)).entries().getFirst().key().equals("raw.private"), "A private channel is copied as one, resolved for the target's owner");
        DeviceClipboard lamp = DeviceClipboard.of(SmartLamp.TYPE, clipboardView(SmartLamp.TYPE, new JsonObject(), Optional.empty(), false, Optional.of(false),
                AccessMode.PRIVATE));
        check(lamp.entries().stream().map(DeviceClipboard.Entry::key).toList().equals(List.of("expose", "access")),
                "A lamp without a Raw Link copies no link, so a paste leaves the target's alone");
    }

    /**
     * M3 plan D11, D12 (M3-9): the write-back's wire format. A request carries exactly {@code v, id, action, target,
     * data, origin}; its target is one entity of the action's own domain; its data is the call's whitelisted keys only
     * (never a target key or a code, which {@link Call} cannot even hold); its origin names the device by object id and
     * sanitised name, and the player only when one caused it. Requests are QoS 1, never retained, dropped while offline.
     * Answers are read only live, at most 1 KiB, with a string id of at most 16 characters that names a waiting request;
     * anything else is dropped or ignored, and none of it reaches a device.
     */
    private static void twinRequests() {
        UUID device = UUID.randomUUID();
        TwinRequests.Origin origin = new TwinRequests.Origin(device, DeviceRecord.objectId(device), "Porch §cLamp\n" + "x".repeat(200),
                new TwinRequests.Requester(UUID.randomUUID(), "Steve"));
        JsonObject data = new JsonObject();
        data.addProperty("brightness", 128);
        JsonObject request = TwinRequests.request("0123456789ab", new Call("light.turn_on", data), "light.kitchen", origin);
        check(request.keySet().equals(Set.of("v", "id", "action", "target", "data", "origin")) && request.get("v").getAsInt() == TwinRequests.VERSION,
                "A request has exactly its six keys: " + request);
        check(request.getAsJsonObject("target").keySet().equals(Set.of("entity_id")) && request.getAsJsonObject("target").get("entity_id").getAsString().equals("light.kitchen"),
                "Its target is one entity id");
        check(request.getAsJsonObject("data").toString().equals("{\"brightness\":128}"), "Its data is the call's own");
        JsonObject from = request.getAsJsonObject("origin");
        check(from.get("device").getAsString().equals(DeviceRecord.objectId(device)) && from.get("player").getAsString().equals("Steve")
                && from.get("name").getAsString().length() <= TwinRequests.MAX_NAME_LENGTH && !from.get("name").getAsString().contains("§")
                && !from.get("name").getAsString().contains("\n"), "Its origin names the device by object id and capped, sanitised name: " + from);
        check(!TwinRequests.request("0123456789ab", new Call("light.turn_off"), "light.kitchen",
                new TwinRequests.Origin(device, DeviceRecord.objectId(device), "Porch Lamp", null)).getAsJsonObject("origin").has("player"),
                "A request the world caused names no player");
        for (String key : HaCalls.BLOCKED_DATA_KEYS) {
            JsonObject smuggled = new JsonObject();
            smuggled.addProperty(key, "light.other");
            check(fails(() -> new Call("light.turn_on", smuggled)), "No call carries " + key);
        }
        check(fails(() -> new Call("light.turn_on").target("switch.porch")) && fails(() -> new Call("light.turn_on").target("light.a,light.b")),
                "A request never targets another domain or more than one entity");
        check(fails(() -> new Call("homeassistant.turn_on")) && fails(() -> new Call("lock.open")), "No cross-domain call, no lock.open");
        check(!TwinRequests.RESPONSES.acceptRetained() && TwinRequests.RESPONSES.maxPayloadBytes() == 1024, "Answers are subscribed live only, at most 1 KiB");

        List<TwinRequests.Notice> notices = new ArrayList<>();
        List<Outbound> published = new ArrayList<>();
        long[] now = {0};
        TwinRequests requests = new TwinRequests(new TwinRequests.Transport() {
            @Override
            public boolean connected() {
                return true;
            }

            @Override
            public void publish(Outbound message) {
                published.add(message);
            }
        }, () -> new TopicScheme("mineqtt", "b"), () -> List.of("light"), () -> 20, () -> now[0], new java.util.Random(1), new MqttLog() {
            @Override
            public void debug(String message) {
            }

            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message, Throwable cause) {
            }

            @Override
            public void error(String message, Throwable cause) {
            }
        });
        requests.setListener(new TwinRequests.Listener() {
            @Override
            public void pendingChanged(UUID changed, boolean pending) {
            }

            @Override
            public void pausedChanged(UUID changed, boolean paused) {
            }

            @Override
            public void notice(TwinRequests.Notice notice) {
                notices.add(notice);
            }
        });
        TwinRequests.Sent first = (TwinRequests.Sent) requests.send(origin, "light.kitchen", new Call("light.turn_on"), Root.PLAYER);
        check(first.id().matches("[0-9a-f]{" + TwinRequests.ID_LENGTH + "}") && published.getFirst().topic().equals("mineqtt/b/bridge/ha_request"),
                "A request gets an id of 12 hex digits and goes to bridge/ha_request: " + first);
        Outbound sent = published.getFirst();
        check(sent.qos() == 1 && !sent.retain() && sent.offline() == OfflinePolicy.DROP,
                "The request TwinRequests publishes is QoS 1, never retained, never sent late");
        String refusal = "{\"id\":\"" + first.id() + "\",\"status\":\"not_labelled\"}";
        requests.response(refusal.getBytes(StandardCharsets.UTF_8), true);
        requests.response(("{\"id\":\"" + first.id() + "x".repeat(16) + "\",\"status\":\"not_labelled\"}").getBytes(StandardCharsets.UTF_8), false);
        requests.response(("{\"id\":" + 5 + ",\"status\":\"not_labelled\"}").getBytes(StandardCharsets.UTF_8), false);
        requests.response(("{\"id\":\"" + first.id() + "\",\"status\":\"not_labelled\",\"pad\":\"" + "x".repeat(1024) + "\"}").getBytes(StandardCharsets.UTF_8), false);
        check(notices.isEmpty() && requests.pending(device), "Retained, overlong-id, non-string-id and oversized answers are dropped");
        requests.response(refusal.getBytes(StandardCharsets.UTF_8), false);
        check(notices.size() == 1 && !requests.pending(device), "A live answer naming the request is matched");
        requests.response(refusal.getBytes(StandardCharsets.UTF_8), false);
        check(notices.size() == 1 && requests.status().unmatched() == 1, "...once: a repeat names no waiting request");
        check(published.size() == 1, "No answer publishes anything");
    }

    /**
     * M3 plan D13, D19 (M3-7): the twin binding keys and who may change them. A key exists only on a type with a twin
     * adapter, needs CONFIGURE (never USE), and the runtime composes the twin gate on top: {@code mineqtt:twin.bind} and
     * {@code twin.allowedEntities} for an entity, which nobody bypasses, {@code mineqtt:twin.control} to turn control on,
     * the binding judged for the device's owner too. A bound device joins no group; a placed copy loses control its
     * placer may not turn on and stays refused until its owner may bind it; a paste sends the binding keys in an order
     * that needs the source first, each judged for the paster.
     */
    private static void twinBindings() {
        DeviceType type = TwinTestTypes.TYPE;
        List<String> twinKeys = List.of("twin.entity", "twin.device", "twin.preset", "twin.invert", "twin.min", "twin.max", "twin.unavailable", "twin.control",
                "twin.target", "twin.resume");
        check(type.linkSettings().stream().map(LinkSetting::key).filter(key -> key.startsWith("twin.")).toList().equals(twinKeys),
                "A twin type has the binding keys its adapter uses, the source first and its targets last: " + type.linkSettings());
        // M3-8/M3-9: the Smart Lamp mirrors and forwards; M3-12: the Redstone Output and the Smart Plug too. The sensors
        // sense the Minecraft world and get no twin (D5).
        // M3-14: the Redstone Input and the Wall Switch switch entities from their targets and mirror nothing.
        // M3-15: the Display Panel mirrors read-only.
        Map<DeviceType, List<String>> mirroring = Map.of(
                DisplayPanel.TYPE, List.of("twin.entity", "twin.device", "twin.preset", "twin.path", "twin.min", "twin.max", "twin.decimals", "twin.unavailable"),
                // M3-13: Resume (the breaker's) for every type that requests: control or targets.
                SmartLamp.TYPE, List.of("twin.entity", "twin.device", "twin.preset", "twin.unavailable", "twin.control", "twin.resume"),
                RedstoneOutput.TYPE, List.of("twin.entity", "twin.device", "twin.preset", "twin.path", "twin.invert", "twin.min", "twin.max", "twin.unavailable",
                        "twin.control", "twin.resume"),
                SmartPlug.TYPE, List.of("twin.entity", "twin.device", "twin.preset", "twin.unavailable", "twin.control", "twin.resume"),
                RedstoneInput.TYPE, List.of("twin.target", "twin.resume"),
                WallSwitch.TYPE, List.of("twin.left", "twin.right", "twin.resume"));
        for (DeviceType shipped : DeviceTypes.all()) {
            List<String> expected = mirroring.getOrDefault(shipped, List.of());
            check(shipped.twin().isPresent() == !expected.isEmpty()
                            && shipped.linkSettings().stream().map(LinkSetting::key).filter(key -> key.startsWith("twin.")).toList().equals(expected),
                    "A shipped type has the binding keys its adapter uses, in paste order: " + shipped + " " + shipped.linkSettings());
            if (expected.isEmpty()) continue;
            TwinAdapter shippedTwin = shipped.twin().orElseThrow();
            if (!shippedTwin.hasSource()) {
                check(!shippedTwin.controllable() && !shippedTwin.targets().isEmpty() && shippedTwin.mirroredKeys().isEmpty()
                                && expected.stream().filter(key -> !key.equals("twin.resume"))
                                .allMatch(key -> shipped.linkSetting(key).orElseThrow().gate() == LinkSetting.Gate.TWIN),
                        shipped + " has targets only, each needing mineqtt:twin.bind on top of CONFIGURE");
                continue;
            }
            if (!shippedTwin.controllable()) {
                // M3-15: the Display Panel only reads its source.
                check(shipped.linkSetting("twin.control").isEmpty() && shippedTwin.targets().isEmpty()
                                && shipped.linkSetting("twin.entity").orElseThrow().gate() == LinkSetting.Gate.TWIN,
                        shipped + " is read-only: its source needs mineqtt:twin.bind on top of CONFIGURE, and it has no control and no targets");
                continue;
            }
            check(shipped.linkSetting("twin.control").orElseThrow().gate() == LinkSetting.Gate.TWIN_CONTROL
                            && shipped.linkSetting("twin.entity").orElseThrow().gate() == LinkSetting.Gate.TWIN,
                    shipped + ": control needs mineqtt:twin.control and the source mineqtt:twin.bind on top of CONFIGURE");
            check(shippedTwin.controllable() && shippedTwin.targets().isEmpty(), shipped + " forwards to its source but switches no targets");
        }
        for (String key : List.of("twin.target")) {
            check(DeviceConfig.permission(RedstoneInput.TYPE, key) == MineqttPermission.CONFIGURE, key + " of the input needs CONFIGURE at the C2S guard");
            refused(RedstoneInput.TYPE, key, new JsonPrimitive("scene.movie"), new DeviceView.Grants(true, false, false, false), "an input target without CONFIGURE");
        }
        refused(WallSwitch.TYPE, "twin.entity", new JsonPrimitive("light.kitchen"), DeviceView.Grants.ALL, "a source on a type with targets only");
        refused(WallSwitch.TYPE, "twin.left", new JsonPrimitive("Scene.Movie"), DeviceView.Grants.ALL, "a target that is no entity id");
        check(RedstoneOutput.TYPE.linkSetting("twin.path").orElseThrow().gate() == LinkSetting.Gate.TWIN
                        && RedstoneOutput.TYPE.linkSetting("twin.invert").orElseThrow().gate() == LinkSetting.Gate.CONFIGURE,
                "An output's path chooses what is read (mineqtt:twin.bind); its other rows only shape it (CONFIGURE)");
        check(DisplayPanel.TYPE.linkSettings().stream().map(LinkSetting::key).filter(key -> key.startsWith("twin.")).toList()
                        .equals(List.of("twin.entity", "twin.device", "twin.preset", "twin.path", "twin.min", "twin.max", "twin.decimals", "twin.unavailable")),
                "The panel has its source, path, range, decimals and what happens while it is unavailable, never control: " + DisplayPanel.TYPE.linkSettings());
        check(!DisplayPanel.TWIN.controllable() && DisplayPanel.TWIN.targets().isEmpty() && DisplayPanel.TYPE.linkSetting("twin.path").orElseThrow().gate() == LinkSetting.Gate.TWIN,
                "The panel is read-only towards its source and switches nothing; twin.path needs the twin gate as it picks what is read");
        refused(DisplayPanel.TYPE, "twin.control", new JsonPrimitive(true), DeviceView.Grants.ALL, "control on a read-only panel");
        // The command twin types an enum's names as text, also the ones that look like numbers (found on the M3-15 lane).
        check(DeviceConfig.commandValue(DisplayPanel.TYPE, "twin.decimals", "0").equals(new JsonPrimitive("0"))
                        && DeviceConfig.parse(DisplayPanel.TYPE, "twin.decimals", DeviceConfig.commandValue(DisplayPanel.TYPE, "twin.decimals", "2"), DeviceView.Grants.ALL).isSuccess()
                        && DeviceConfig.commandValue(DisplayPanel.TYPE, "size", " \"large\" ").equals(new JsonPrimitive("large")),
                "/mineqtt devices setting <panel> twin.decimals 0 types the name 0, not a number");
        Map<String, String> panelRows = Map.of("twin.path", "attributes.current_temperature", "twin.min", "-10", "twin.max", "35.5", "twin.decimals", "1");
        panelRows.forEach((key, value) -> {
            refused(DisplayPanel.TYPE, key, new JsonPrimitive(value), new DeviceView.Grants(true, false, false, false), key + " without CONFIGURE");
            check(DeviceConfig.parse(DisplayPanel.TYPE, key, new JsonPrimitive(value), new DeviceView.Grants(false, true, false, false)).isSuccess(),
                    "CONFIGURE passes the pure gate of the panel's " + key);
        });
        check(SmartLamp.TYPE.linkSettings().stream().map(LinkSetting::key).filter(key -> key.startsWith("twin.")).toList()
                        .equals(List.of("twin.entity", "twin.device", "twin.preset", "twin.unavailable", "twin.control", "twin.resume")),
                "The lamp has its source, what happens while it is unavailable and control (M3-9), no rows, and Resume (M3-13): "
                        + SmartLamp.TYPE.linkSettings());
        check(SmartLamp.TYPE.linkSetting("twin.control").orElseThrow().gate() == LinkSetting.Gate.TWIN_CONTROL,
                "The lamp's control key needs mineqtt:twin.control on top of CONFIGURE");
        check(SmartLamp.TWIN.controllable() && SmartLamp.TWIN.targets().isEmpty(), "The lamp forwards to its source but switches no targets");
        check(LinkSetting.isKey("twin.anything") && LinkSetting.TWIN_RESERVED_NAMES.contains("entity"), "Every twin.* key is reserved for bindings");
        DeviceView.Grants useOnly = new DeviceView.Grants(true, false, false, false);
        DeviceView.Grants configure = new DeviceView.Grants(false, true, false, false);
        Map<String, JsonElement> values = Map.of("twin.entity", new JsonPrimitive("switch.porch"), "twin.invert", new JsonPrimitive(true),
                "twin.min", new JsonPrimitive("12.5"), "twin.max", new JsonPrimitive(""), "twin.unavailable", new JsonPrimitive("off"),
                "twin.control", new JsonPrimitive(true), "twin.target", new JsonPrimitive("scene.movie"), "twin.resume", new JsonPrimitive("PRESS"),
                "twin.device", new JsonPrimitive("mineqtt:zigbee2mqtt_switch Porch plug"), "twin.preset", new JsonPrimitive("mineqtt:tasmota_power"));
        for (String key : twinKeys) {
            check(DeviceConfig.permission(type, key) == MineqttPermission.CONFIGURE, key + " needs CONFIGURE at the C2S guard");
            refused(type, key, values.get(key), useOnly, "a twin key without CONFIGURE");
            refused(type, key, values.get(key), DeviceView.Grants.NONE, "a twin key without any grant");
            check(DeviceConfig.parse(type, key, values.get(key), configure).isSuccess(), "CONFIGURE passes the pure gate of " + key);
        }
        refused(PresenceSensor.TYPE, "twin.entity", new JsonPrimitive("switch.porch"), DeviceView.Grants.ALL, "a binding on a type without an adapter");
        refused(type, "twin.entity", new JsonPrimitive("Light.Kitchen"), DeviceView.Grants.ALL, "an entity id that is not lowercase");
        refused(type, "twin.entity", new JsonPrimitive("light.kitchen; rm"), DeviceView.Grants.ALL, "a malformed entity id");
        refused(type, "twin.entity", new JsonPrimitive("x".repeat(300) + ".y"), DeviceView.Grants.ALL, "an entity id over 255 characters");
        refused(type, "twin.min", new JsonPrimitive("1e400"), DeviceView.Grants.ALL, "a number with an exponent");
        refused(type, "twin.min", new JsonPrimitive("NaN"), DeviceView.Grants.ALL, "NaN");
        refused(type, "twin.unavailable", new JsonPrimitive("explode"), DeviceView.Grants.ALL, "an unknown unavailable mode");
        refused(type, "twin.decimals", new JsonPrimitive("2"), DeviceView.Grants.ALL, "a row the type does not use");
        // A stored binding comes from block entity data (an item any creative player can make, /data, a structure), not
        // through the parser above: its range numbers are bounded as well, so a huge exponent fails to decode, which drops
        // the binding, instead of being written out in full at the next save (wave 4 review).
        for (String huge : List.of("1e2000000000", "1e-2000000000", "1e70000", "1234567890123456", "-1E+15", "0.0000001", "9".repeat(40))) {
            check(BindingCodecs.CODEC.parse(NbtOps.INSTANCE, storedBinding(huge)).isError(),
                    "A stored twin.min of " + (huge.length() > 20 ? huge.substring(0, 20) + "…" : huge) + " fails to decode");
        }
        Binding largest = BindingCodecs.CODEC.parse(NbtOps.INSTANCE, storedBinding("-123456789012345.123456")).getOrThrow();
        check(largest.settings().min().toPlainString().equals("-123456789012345.123456"), "The largest number the tab takes is stored as it is");
        Binding exponent = BindingCodecs.CODEC.parse(NbtOps.INSTANCE, storedBinding("1E+2")).getOrThrow();
        check(BindingCodecs.CODEC.encodeStart(NbtOps.INSTANCE, exponent).getOrThrow() instanceof CompoundTag written
                        && written.getStringOr("min", "").equals("100"), "A small exponent is written out plainly");
        check(fails(() -> Binding.Settings.DEFAULT.withMin(new BigDecimal("1e2000000000")))
                        && fails(() -> Binding.Settings.DEFAULT.withMax(new BigDecimal("1e-2000000000"))),
                "No binding holds a range number with more digits than the tab takes, however it was made");

        TwinRules.Rights all = new TwinRules.Rights(true, true, EntityGlobs.ALL, Optional.empty());
        TwinRules.Rights noBind = new TwinRules.Rights(false, true, EntityGlobs.ALL, Optional.empty());
        TwinRules.Rights noControl = new TwinRules.Rights(true, false, EntityGlobs.ALL, Optional.empty());
        TwinRules.Rights switchesOnly = new TwinRules.Rights(true, true, EntityGlobs.of(List.of("switch.*", "scene.*")), Optional.empty());
        TwinRules.Rights opOnEmptyList = new TwinRules.Rights(true, true, EntityGlobs.NONE, Optional.empty());
        TwinRules.Rights off = new TwinRules.Rights(true, true, EntityGlobs.ALL, Optional.of("no statestream base: set twin.statestreamBase"));
        TwinAdapter adapter = TwinTestTypes.ADAPTER;
        DeviceConfig.Twin bind = new DeviceConfig.TwinSource("switch.porch");
        TwinRules.Edited bound = TwinRules.edit(adapter, null, bind, all, all, false);
        check(bound.refusal() == null && bound.binding().sourceEntity().orElse("").equals("switch.porch") && !bound.binding().control() && bound.binding().active(),
                "A binder with the rights binds, control off: " + bound);
        check(bound.binding().source().equals(new Endpoint.HaEntityRef("switch.porch", "state")), "The source end is the domain's first out-port");
        check(TwinRules.edit(adapter, null, bind, noBind, all, false).refusal().contains("mineqtt:twin.bind"), "Binding needs mineqtt:twin.bind");
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinSource("input_boolean.flag"), switchesOnly, all, false).refusal().contains("twin.allowedEntities"),
                "An entity outside twin.allowedEntities is refused");
        check(TwinRules.edit(adapter, null, bind, opOnEmptyList, all, false).refusal().contains("twin is off"),
                "An empty list refuses everyone, operators and the console included (no subject is asked)");
        check(TwinRules.edit(adapter, null, bind, off, all, false).refusal().contains("twin.statestreamBase"), "A twin without a base refuses with the key to set");
        check(TwinRules.edit(adapter, null, bind, all, noBind, false).refusal().startsWith("not for the device's owner"),
                "An editor never binds for an owner who may not bind");
        check(TwinRules.edit(adapter, null, bind, all, all, true).refusal().contains("group"), "A device in a group is not bound (D6)");
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinSource("light.kitchen"), all, all, false).refusal().contains("cannot mirror light"),
                "A domain the type does not mirror is refused");
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinSource("person.steve"), all, all, false).refusal().contains("cannot be read"),
                "A privacy domain is never bound (D2)");
        Binding binding = bound.binding();
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinControl(true), noControl, all, false).refusal().contains("mineqtt:twin.control"),
                "Turning control on needs mineqtt:twin.control");
        Binding controlled = TwinRules.edit(adapter, binding, new DeviceConfig.TwinControl(true), all, all, false).binding();
        check(controlled.control(), "...which turns it on");
        check(!TwinRules.edit(adapter, controlled, new DeviceConfig.TwinControl(false), TwinRules.Rights.NONE, all, false).binding().control(),
                "Turning it off needs no twin right");
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinControl(true), all, all, false).refusal() != null, "No control without a source");
        check(!TwinRules.edit(adapter, controlled, new DeviceConfig.TwinSource("input_boolean.flag"), all, all, false).binding().control(),
                "A new source starts without control");
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinInvert(true), all, all, false).refusal() != null, "A row needs a source first");
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinInvert(true), TwinRules.Rights.NONE, all, false).binding().settings().invert(),
                "A row that only shapes the source needs CONFIGURE alone");
        Binding scene = TwinRules.edit(adapter, binding, new DeviceConfig.TwinTarget("target", "scene.movie"), all, all, false).binding();
        check(scene.targets().equals(Map.of(TwinTestTypes.PRESSED, new Endpoint.HaEntityRef("scene.movie", "activate"))), "A target attaches to its out-port: " + scene);
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinTarget("target", "lock.front"), all, all, false).refusal() != null,
                "A target domain the type does not switch is refused");
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinTarget("target", "scene.movie"), noBind, all, false).refusal().contains("mineqtt:twin.bind"),
                "A target needs mineqtt:twin.bind too");
        check(TwinRules.edit(adapter, scene, new DeviceConfig.TwinSource(""), TwinRules.Rights.NONE, all, false).binding().equals(
                new Binding(null, scene.targets(), false, scene.settings(), null)), "Unbinding the source keeps the targets");
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinSource(""), TwinRules.Rights.NONE, all, false).binding() == null, "...and without any, unbinds");
        check(TwinRules.pathProblem("lock", ValuePath.parse("attributes.changed_by")).isPresent()
                        && TwinRules.pathProblem("sensor", ValuePath.parse("attributes.unit_of_measurement")).isEmpty()
                        && TwinRules.pathProblem("switch", ValuePath.parse("last_changed")).isEmpty()
                        && TwinRules.pathProblem("switch", ValuePath.parse("attributes")).isPresent(),
                "A path reads the state, a timestamp or a whitelisted attribute only (M3-4 decision 8)");

        // Judged again for the owner: refused, kept, healed.
        Binding refused = TwinRules.recheck(adapter, controlled, noBind);
        check(!refused.active() && refused.bound() && refused.control() && !refused.controls(), "A binding its owner may no longer have is refused, kept bound");
        check(TwinRules.recheck(adapter, refused, noBind) == refused, "Judged the same, it is the same binding");
        check(TwinRules.recheck(adapter, refused, all).equals(controlled), "It heals when the rules allow it again");
        check(TwinRules.edit(adapter, refused, bind, all, all, false).binding().equals(controlled), "Binding the same entity anew heals it too");
        check(!TwinRules.placed(adapter, controlled, noControl).control() && TwinRules.placed(adapter, controlled, noControl).active(),
                "A placed copy loses control its placer may not turn on (D13 #6)");
        check(!TwinRules.placed(adapter, controlled, TwinRules.Rights.NONE).active(), "...and is refused for a placer who may not bind it");
        check(TwinRules.placed(adapter, controlled, all).equals(controlled), "...and stays as it is for one who may");

        // Groups.
        DeviceRegistry registry = new DeviceRegistry("world_7f3a", UUID.randomUUID());
        DeviceRecord memberRecord = new DeviceRecord(UUID.randomUUID(), type.id(), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), "Hall", "hall");
        registry.add(memberRecord);
        DeviceGroup lamps = registry.createGroup(UUID.randomUUID(), "Lamps");
        registry.joinGroup(lamps, memberRecord);
        UUID device = UUID.randomUUID();
        check(GroupRules.joinProblem(lamps, type.id(), true, device, controlled, id -> true, topic -> Optional.empty()).orElse("").contains("switch.porch"),
                "A bound device joins no group (D6)");
        check(GroupRules.joinProblem(lamps, type.id(), true, device, scene.withSource(null), id -> true, topic -> Optional.empty()).isEmpty(),
                "Targets alone do not keep it out");
        check(TwinRules.groupProblem(controlled).isPresent() && TwinRules.groupProblem(null).isEmpty(), "Only a bound device is kept out of groups");

        // Copy-paste.
        JsonObject state = new JsonObject();
        state.addProperty("power", 15);
        state.addProperty("fade", 3);
        Binding copied = scene.withSettings(scene.settings().withInvert(true).withMin(new BigDecimal("12.5")));
        DeviceClipboard clipboard = DeviceClipboard.of(type, clipboardView(type, state, Optional.empty(), false, Optional.empty(), AccessMode.CLAIM), copied);
        check(clipboard.entries().stream().map(DeviceClipboard.Entry::key).toList().equals(List.of("fade", "twin.entity", "twin.invert", "twin.min", "twin.max",
                "twin.unavailable", "twin.control", "twin.target", "expose", "access")), "The binding is copied source first, control after the rows: " + clipboard.entries());
        check(clipboard.entries().stream().filter(DeviceClipboard.Entry::twinSource).count() == 1
                && clipboard.entries().stream().filter(DeviceClipboard.Entry::twinShape).count() == 5, "A paste knows which keys need the source");
        for (DeviceClipboard.Entry entry : clipboard.entries()) {
            if (!entry.key().startsWith("twin.")) continue;
            check(DeviceConfig.parse(type, entry.key(), entry.value(), configure).isSuccess(), "A copied binding key parses for a configurer: " + entry);
            check(DeviceConfig.parse(type, entry.key(), entry.value(), useOnly).isError(), "...and not for a user: " + entry);
        }
        check(DeviceClipboard.of(type, clipboardView(type, state, Optional.empty(), false, Optional.empty(), AccessMode.CLAIM)).entries().stream()
                .noneMatch(entry -> entry.key().startsWith("twin.")), "Without the binding (a copier who may not configure), none is copied");
    }

    /**
     * M3-17: a source that is an MQTT device through a preset. twin.device needs mineqtt:twin.bind but not the Home
     * Assistant twin's entity list, a loaded preset of a kind the type mirrors, a device name that makes valid topics and
     * a state topic the ACL allows the binder and the owner; turning control on needs mineqtt:twin.control and a command
     * topic the ACL allows them (acl.rawAllow); twin.preset switches the preset of the same device. A stored binding
     * follows the loaded preset (a moved topic, a new kind without control), is refused while its preset is gone and
     * heals when it is back; a placed copy is judged for the placer's topics. The binding round-trips through the codec,
     * and a copy pastes it as one twin.device key.
     */
    private static void presetBindings() {
        BindingPresets.replace(MirrorNoLoopCheck.builtInPresets());
        TwinAdapter adapter = TwinTestTypes.ADAPTER;
        TwinRules.Topics open = topics(Optional.empty(), Optional.empty());
        TwinRules.Topics noCommands = topics(Optional.empty(), Optional.of("not in the raw topic allowlist (acl.rawAllow)"));
        TwinRules.Topics noSources = topics(Optional.of("the Home Assistant statestream namespace is mirrored through Home Assistant bindings only"), Optional.empty());
        TwinRules.Rights all = new TwinRules.Rights(true, true, EntityGlobs.NONE, Optional.of("no allowed entities: set twin.allowedEntities"), open);
        TwinRules.Rights noBind = new TwinRules.Rights(false, true, EntityGlobs.ALL, Optional.empty(), open);
        TwinRules.Rights noControl = new TwinRules.Rights(true, false, EntityGlobs.ALL, Optional.empty(), open);
        DeviceConfig.Twin bind = new DeviceConfig.TwinDevice("mineqtt:zigbee2mqtt_switch", "Porch plug");
        TwinRules.Edited bound = TwinRules.edit(adapter, null, bind, all, all, false);
        check(bound.refusal() == null && bound.binding().source().equals(new Endpoint.TopicRef("zigbee2mqtt/Porch plug", ""))
                        && bound.binding().preset().orElseThrow().equals(new Binding.PresetRef("mineqtt:zigbee2mqtt_switch", "Porch plug", "switch"))
                        && !bound.binding().control() && bound.binding().active(),
                "A preset binds with twin.bind alone, while the Home Assistant twin is off: " + bound);
        check(DeviceConfig.parse(TwinTestTypes.TYPE, "twin.device", new JsonPrimitive("mineqtt:zigbee2mqtt_switch Porch plug"), DeviceView.Grants.ALL).isSuccess()
                && DeviceConfig.parse(TwinTestTypes.TYPE, "twin.device", new JsonPrimitive(""), DeviceView.Grants.ALL).isSuccess(), "twin.device parses; empty unbinds");
        refused(TwinTestTypes.TYPE, "twin.device", new JsonPrimitive("mineqtt:zigbee2mqtt_switch"), DeviceView.Grants.ALL, "a preset without a device name");
        refused(TwinTestTypes.TYPE, "twin.device", new JsonPrimitive("Not A Preset porch"), DeviceView.Grants.ALL, "a preset id that is none");
        refused(TwinTestTypes.TYPE, "twin.preset", new JsonPrimitive("mineqtt zigbee"), DeviceView.Grants.ALL, "a twin.preset that is no id");
        check(TwinRules.edit(adapter, null, bind, noBind, all, false).refusal().contains("mineqtt:twin.bind"), "A preset needs mineqtt:twin.bind");
        check(TwinRules.edit(adapter, null, bind, all, noBind, false).refusal().startsWith("not for the device's owner"), "...for the owner too");
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinDevice("mineqtt:nothing", "x"), all, all, false).refusal().contains("no MQTT device preset"),
                "A preset that is not loaded is refused");
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinDevice("mineqtt:zigbee2mqtt_light", "x"), all, all, false).refusal().contains("cannot mirror a light"),
                "A preset of a kind the type does not mirror is refused");
        for (String name : List.of("#", "a/+", "/x", "$SYS")) {
            check(TwinRules.edit(adapter, null, new DeviceConfig.TwinDevice("mineqtt:zigbee2mqtt_switch", name), all, all, false).refusal() != null,
                    "The device name '" + name + "' is refused before the ACL is asked");
        }
        TwinRules.Rights statestream = all.withTopics(noSources);
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinDevice("mineqtt:generic_switch", "ha_statestream/lock/front/state"), statestream, all, false).refusal()
                .contains("statestream"), "A state topic the ACL refuses the binder is refused");
        check(TwinRules.edit(adapter, null, bind, all, statestream, false).refusal().startsWith("not for the device's owner"), "...and one it refuses the owner");
        check(TwinRules.edit(adapter, null, bind, all, all, true).refusal().contains("group"), "A device in a group binds no preset (D6)");
        Binding binding = bound.binding();
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinControl(true), noControl, all, false).refusal().contains("mineqtt:twin.control"),
                "Control needs mineqtt:twin.control");
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinControl(true), all.withTopics(noCommands), all, false).refusal().contains("acl.rawAllow"),
                "Control needs a command topic the ACL allows the binder, named so: " + TwinRules.edit(adapter, binding, new DeviceConfig.TwinControl(true),
                        all.withTopics(noCommands), all, false).refusal());
        check(TwinRules.edit(adapter, binding, new DeviceConfig.TwinControl(true), all, all.withTopics(noCommands), false).refusal().startsWith("not for the device's owner"),
                "...and the owner");
        Binding controlled = TwinRules.edit(adapter, binding, new DeviceConfig.TwinControl(true), all, all, false).binding();
        check(controlled.controls(), "With both, control is on");
        Binding readOnly = TwinRules.edit(adapter, null, new DeviceConfig.TwinDevice("mineqtt:generic_switch", "home/plug"), all, all, false).binding();
        check(TwinRules.edit(adapter, readOnly, new DeviceConfig.TwinControl(true), all, all, false).refusal().contains("read-only"), "A read-only preset takes no control");
        Binding tasmota = TwinRules.edit(adapter, controlled, new DeviceConfig.TwinPreset("mineqtt:tasmota_power"), all, all, false).binding();
        check(tasmota.sourceTopic().orElseThrow().equals("stat/Porch plug/POWER") && tasmota.preset().orElseThrow().id().equals("mineqtt:tasmota_power") && !tasmota.control(),
                "twin.preset switches the preset of the same device, control off: " + tasmota);
        check(TwinRules.edit(adapter, null, new DeviceConfig.TwinPreset("mineqtt:tasmota_power"), all, all, false).refusal().contains("twin.device"),
                "twin.preset alone names no device");
        TwinRules.Rights everything = new TwinRules.Rights(true, true, EntityGlobs.ALL, Optional.empty(), open);
        Binding entity = TwinRules.edit(adapter, controlled, new DeviceConfig.TwinSource("switch.porch"), everything, everything, false).binding();
        check(entity.sourceEntity().orElse("").equals("switch.porch") && entity.preset().isEmpty() && entity.settings().preset() == null,
                "twin.entity replaces the preset source and keeps no preset: " + entity);
        check(TwinRules.edit(adapter, controlled, new DeviceConfig.TwinDevice("", ""), all, all, false).binding() == null, "An empty twin.device unbinds");
        check(controlled.equals(TwinRules.edit(adapter, controlled, bind, noBind, all, false).binding()),
                "Naming the same device again changes nothing, also for an editor who could not bind it now");

        // A stored binding is judged for its owner against the loaded presets.
        check(TwinRules.recheck(adapter, controlled, all) == controlled, "A working preset binding stays as it is");
        check(TwinRules.recheck(adapter, controlled, all.withTopics(TwinRules.Topics.NONE)).refused() != null, "Without an ACL to ask, it is refused");
        BindingPresets.replace(PresetCodec.Loaded.EMPTY);
        Binding gone = TwinRules.recheck(adapter, controlled, all);
        check(gone.refused() != null && gone.refused().contains("no MQTT device preset"), "A preset that went refuses its binding: " + gone.refused());
        BindingPresets.replace(MirrorNoLoopCheck.builtInPresets());
        check(TwinRules.recheck(adapter, gone, all).active(), "...which heals when the preset is back");
        Map<String, String> moved = new java.util.HashMap<>();
        moved.put("mineqtt:zigbee2mqtt_switch", "{\"kind\":\"switch\",\"state_topic\":\"z2m/{device}\",\"on\":\"ON\",\"off\":\"OFF\","
                + "\"command_topic\":\"z2m/{device}/set\",\"commands\":{\"turn_on\":{\"state\":\"ON\"}}}");
        BindingPresets.replace(PresetCodec.loadAll(moved));
        Binding followed = TwinRules.recheck(adapter, controlled, all);
        check(followed.sourceTopic().orElseThrow().equals("z2m/Porch plug") && followed.controls(), "A datapack that moved the topic moves the binding, control kept: " + followed);
        moved.put("mineqtt:zigbee2mqtt_switch", "{\"kind\":\"input_boolean\",\"state_topic\":\"z2m/{device}\"}");
        BindingPresets.replace(PresetCodec.loadAll(moved));
        Binding rekinded = TwinRules.recheck(adapter, controlled, all);
        check(rekinded.preset().orElseThrow().kind().equals("input_boolean") && !rekinded.control() && rekinded.active(), "A new kind keeps no control: " + rekinded);
        BindingPresets.replace(MirrorNoLoopCheck.builtInPresets());
        Binding placed = TwinRules.placed(adapter, controlled, new TwinRules.Rights(true, false, EntityGlobs.NONE, Optional.empty()));
        check(placed.refused() != null && !placed.control(), "A placed copy is judged for its placer: no topics without an ACL, no control without the permission");

        // Stored and copied.
        CompoundTag stored = (CompoundTag) BindingCodecs.CODEC.encodeStart(NbtOps.INSTANCE, controlled).getOrThrow();
        check(stored.getCompound("preset").isPresent() && stored.getCompound("source").orElseThrow().getStringOr("topic", "").equals("zigbee2mqtt/Porch plug"),
                "The preset is stored with the binding: " + stored);
        check(BindingCodecs.CODEC.parse(NbtOps.INSTANCE, stored).getOrThrow().equals(controlled), "...and read back equal");
        CompoundTag forged = stored.copy();
        forged.getCompound("preset").orElseThrow().putString("kind", "person");
        check(BindingCodecs.CODEC.parse(NbtOps.INSTANCE, forged).isError(), "A stored preset of a private kind fails to decode");
        check(!((CompoundTag) BindingCodecs.CODEC.encodeStart(NbtOps.INSTANCE, entity).getOrThrow()).contains("preset"), "An entity binding stores no preset");
        JsonObject state = new JsonObject();
        state.addProperty("fade", 3);
        DeviceClipboard copy = DeviceClipboard.of(TwinTestTypes.TYPE, clipboardView(TwinTestTypes.TYPE, state, Optional.empty(), false, Optional.empty(), AccessMode.CLAIM),
                controlled);
        check(copy.entries().stream().filter(entry -> entry.key().equals("twin.device")).findFirst().orElseThrow().value()
                        .equals(new JsonPrimitive("mineqtt:zigbee2mqtt_switch Porch plug"))
                        && copy.entries().stream().noneMatch(entry -> entry.key().equals("twin.entity"))
                        && copy.entries().stream().filter(DeviceClipboard.Entry::twinSource).count() == 1,
                "A copy names the preset device as its one source key: " + copy.entries());
        BindingPresets.replace(PresetCodec.Loaded.EMPTY);
    }

    // The ACL's preset-topic judgement for a check: a fixed answer for state topics and for command topics.
    private static TwinRules.Topics topics(Optional<String> source, Optional<String> command) {
        return new TwinRules.Topics() {
            @Override
            public Optional<String> source(String topic) {
                return source.map(reason -> topic + ": " + reason);
            }

            @Override
            public Optional<String> command(String topic) {
                return command.map(reason -> topic + ": " + reason);
            }
        };
    }

    private static DeviceView clipboardView(DeviceType type, JsonObject state, Optional<DeviceView.Raw> raw, boolean privateMode, Optional<Boolean> expose,
                                            AccessMode access) {
        UUID id = UUID.randomUUID();
        return new DeviceView(BlockPos.ZERO, type.id(), "Garage Output", "garage_output", "Steve", access, expose,
                new DeviceView.Registration("mineqtt/world", false, Optional.empty()), DeviceView.Grants.ALL, state.toString(), raw, privateMode,
                "", Optional.empty(), new DeviceView.Ownership(DeviceView.Owner.KNOWN, false), DeviceView.Diagnostics.notRunning(id), 0, false);
    }

    /**
     * The Bridge Console (M2 plan §4.2 M2-23): anyone the claim trusts opens it, only an operator runs its actions, whatever
     * the claim says; it lists a player only the devices they may configure and the groups whose every member they may,
     * an operator everything, and builds no row of a device it does not list; what needs attention comes first; its view
     * and its action payload cross the wire within their caps, and an unknown action is not read as another.
     */
    private static void bridgeConsole() {
        check(DeviceAccess.consolePermits(MineqttPermission.USE, true, true, never()), "An operator opens a console without a claim check");
        check(DeviceAccess.consolePermits(MineqttPermission.USE, false, true, () -> true), "A player the claim trusts opens a console");
        check(!DeviceAccess.consolePermits(MineqttPermission.USE, false, true, () -> false), "One it does not trust does not");
        check(DeviceAccess.consolePermits(MineqttPermission.CONFIGURE, true, true, never()), "An operator runs the console's actions");
        check(!DeviceAccess.consolePermits(MineqttPermission.CONFIGURE, false, true, never()),
                "Nobody else does, and the claim is not even asked: the actions act on the whole bridge");

        DeviceRegistry registry = new DeviceRegistry("console_0001", UUID.randomUUID());
        DeviceRecord porch = linkDevice(registry, SmartLamp.TYPE, "porch", 0);
        DeviceRecord kitchen = linkDevice(registry, SmartLamp.TYPE, "kitchen", 1);
        DeviceRecord garage = linkDevice(registry, RedstoneOutput.TYPE, "garage", 2);
        DeviceRecord stranger = linkDevice(registry, RedstoneOutput.TYPE, "stranger", 3);
        DeviceRecord broken = linkDevice(registry, RedstoneInput.TYPE, "broken", 4);
        DeviceRecord alien = new DeviceRecord(UUID.randomUUID(), Identifier.fromNamespaceAndPath("othermod", "gadget"),
                GlobalPos.of(Level.OVERWORLD, new BlockPos(5, -60, 0)), "alien", "alien");
        registry.add(alien);
        DeviceGroup lamps = registry.createGroup(UUID.randomUUID(), "Lamps");
        registry.joinGroup(lamps, porch);
        registry.joinGroup(lamps, kitchen);
        DeviceGroup outputs = registry.createGroup(UUID.randomUUID(), "Outputs");
        registry.joinGroup(outputs, garage);
        registry.joinGroup(outputs, stranger);
        registry.createGroup(UUID.randomUUID(), "Empty");
        Set<UUID> mine = Set.of(porch.id(), kitchen.id(), garage.id(), broken.id());
        List<UUID> built = new ArrayList<>();
        Function<DeviceRecord, ConsoleView.DeviceRow> row = record -> {
            built.add(record.id());
            ConsoleView.State state = record == broken ? ConsoleView.State.FAULTED : record == alien ? ConsoleView.State.UNKNOWN_TYPE
                    : record == kitchen ? ConsoleView.State.UNLOADED : ConsoleView.State.ONLINE;
            String detail = record == broken ? "attach failed: boom" : record == garage ? "not in the raw topic allowlist" : "";
            return ConsoleView.DeviceRow.of(record.name(), record.slug(), record.type().getPath(), state, detail, "overworld 0 -60 0");
        };

        List<ConsoleView.DeviceRow> own = ConsoleView.visibleDevices(registry.records(), false, mine::contains, row);
        check(own.stream().map(ConsoleView.DeviceRow::slug).toList().equals(List.of("broken", "garage", "kitchen", "porch")),
                "A player sees only the devices they may configure, faulted first, then with a problem, then by name: " + own);
        check(Set.copyOf(built).equals(mine), "No row is built of a device they may not configure");
        List<ConsoleView.DeviceRow> all = ConsoleView.visibleDevices(registry.records(), true, device -> false, row);
        check(all.stream().map(ConsoleView.DeviceRow::slug).toList().equals(List.of("broken", "alien", "garage", "kitchen", "porch", "stranger")),
                "An operator sees every device, one of an unknown type after the faulted ones: " + all);
        check(all.get(0).troubled() && all.get(1).troubled() && all.get(2).troubled() && !all.get(3).troubled(), "What needs attention is marked");
        List<ConsoleView.GroupRow> ownGroups = ConsoleView.visibleGroups(registry.groups(), false, mine::contains);
        check(ownGroups.stream().map(ConsoleView.GroupRow::slug).toList().equals(List.of("lamps")),
                "A player sees the groups whose every member they may configure, not one with another's device nor an empty one: " + ownGroups);
        List<ConsoleView.GroupRow> allGroups = ConsoleView.visibleGroups(registry.groups(), true, device -> false);
        check(allGroups.stream().map(ConsoleView.GroupRow::slug).toList().equals(List.of("empty", "lamps", "outputs")),
                "An operator sees every group, by name: " + allGroups);
        check(allGroups.get(1).members() == 2 && allGroups.get(1).memberType().equals(SmartLamp.TYPE.id().getPath()) && allGroups.get(0).memberType().isEmpty(),
                "A group row names its member count and type");

        ConsoleView.Bridge bridge = ConsoleView.Bridge.of("mineqtt/console_0001", ConnectionState.reconnecting("broker gone"), "tcp://localhost:1883 (MQTT 5, user ***)",
                "5", ConsoleView.HomeAssistant.ONLINE, 12_000, "OPT_IN", 3, ConsoleView.ServerState.PAUSED, 60, 2, 5, 1, 4, 7, 120, 3);
        BlockPos pos = new BlockPos(1, -60, 2);
        ConsoleView view = ConsoleView.of(pos, bridge, own, ownGroups, false);
        check(roundTrip(view).equals(view), "A player's console view crosses the wire unchanged");
        ConsoleView none = ConsoleView.of(pos, ConsoleView.Bridge.of("", ConnectionState.DISABLED, "none", "5", ConsoleView.HomeAssistant.NOT_SEEN, -1,
                "NONE", 0, ConsoleView.ServerState.INTEGRATED, 0, 0, 0, 0, 0, 0, 0, 0), List.of(), List.of(), true);
        check(roundTrip(none).equals(none) && none.bridge().homeAssistantAgeMillis() == -1, "So does an empty one, with no birth heard");
        // Vanilla takes a negative pause-when-empty-seconds as never pausing; the view still has to cross the wire.
        ConsoleView neverPauses = ConsoleView.of(pos, ConsoleView.Bridge.of("", ConnectionState.DISABLED, "none", "5", ConsoleView.HomeAssistant.NOT_SEEN, -1,
                "NONE", 0, ConsoleView.ServerState.NEVER_PAUSES, -1, 0, 0, 0, 0, 0, 0, 0), List.of(), List.of(), true);
        check(neverPauses.bridge().pauseSeconds() == 0 && roundTrip(neverPauses).equals(neverPauses),
                "A server that never pauses because pause-when-empty-seconds is negative shows 0 s, and its view crosses the wire");

        String text = "€".repeat(400);
        List<ConsoleView.DeviceRow> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(ConsoleView.DeviceRow.of(text, "s".repeat(80), text, ConsoleView.State.FAULTED, text, text));
        DeviceGroup wide = registry.createGroup(UUID.randomUUID(), "x".repeat(60));
        List<ConsoleView.GroupRow> groups = new ArrayList<>();
        for (int i = 0; i < 50; i++) groups.add(ConsoleView.visibleGroups(List.of(wide), true, device -> true).getFirst());
        ConsoleView.Bridge longest = ConsoleView.Bridge.of(text, ConnectionState.failed(text), text, text, ConsoleView.HomeAssistant.OFFLINE, Long.MAX_VALUE / 2,
                text, Long.MAX_VALUE / 2, ConsoleView.ServerState.KEPT_TICKING, Integer.MAX_VALUE, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2);
        ConsoleView largest = ConsoleView.of(pos, longest, many, groups, true);
        check(largest.devices().size() == ConsoleView.MAX_DEVICE_ROWS && largest.deviceTotal() == 100
                        && largest.groups().size() == ConsoleView.MAX_GROUP_ROWS && largest.groupTotal() == 50,
                "A console keeps the first rows and counts them all");
        RegistryFriendlyByteBuf largeBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        ConsoleViewPayload.CODEC.encode(largeBuf, new ConsoleViewPayload(largest));
        check(largeBuf.readableBytes() <= ConsoleView.MAX_BYTES, "The largest console view fits its cap: " + largeBuf.readableBytes() + " bytes");
        check(ConsoleViewPayload.CODEC.decode(largeBuf).view().equals(largest), "and crosses the wire unchanged");
        rejects(() -> new ConsoleView(pos, bridge, many, 100, List.of(), 0, false), "a console view of more rows than it may carry");

        for (BridgeAction action : BridgeAction.values()) {
            BridgeActionPayload payload = new BridgeActionPayload(pos, action);
            RegistryFriendlyByteBuf actionBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            BridgeActionPayload.CODEC.encode(actionBuf, payload);
            check(BridgeActionPayload.CODEC.decode(actionBuf).equals(payload) && actionBuf.readableBytes() == 0, "The " + action.key() + " action crosses the wire");
        }
        RegistryFriendlyByteBuf unknown = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        unknown.writeBlockPos(pos);
        unknown.writeVarInt(BridgeAction.values().length);
        check(fails(() -> BridgeActionPayload.CODEC.decode(unknown)), "An action the console does not declare is not read");
    }

    private static ConsoleView roundTrip(ConsoleView view) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        ConsoleViewPayload.CODEC.encode(buf, new ConsoleViewPayload(view));
        ConsoleView read = ConsoleViewPayload.CODEC.decode(buf).view();
        check(buf.readableBytes() == 0, "A console view is read to its end");
        return read;
    }

    private static DeviceRecord linkDevice(DeviceRegistry registry, DeviceType type, String slug, int x) {
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), type.id(), GlobalPos.of(Level.OVERWORLD, new BlockPos(x, -60, 0)), slug, slug);
        registry.add(record);
        return record;
    }

    private static Wire linkWire(UUID owner, Endpoint from, Endpoint to) {
        return new Wire(UUID.randomUUID(), from, to, Mapping.FOLLOW, Scope.WIRELESS, owner, true);
    }

    private static DeviceConfig.Change accepted(DeviceType type, String key, JsonElement value, DeviceView.Grants grants) {
        DataResult<DeviceConfig.Change> result = DeviceConfig.parse(type, key, value, grants);
        check(result.isSuccess(), type + " accepts " + key + " = " + value + ": " + result);
        return result.getOrThrow();
    }

    private static void refused(DeviceType type, String key, JsonElement value, DeviceView.Grants grants, String what) {
        check(DeviceConfig.parse(type, key, value, grants).isError(), type + " refuses " + what + " (" + key + " = " + value + ")");
    }

    // A stored binding to switch.porch whose twin.min is `min`, as block entity data would carry it.
    private static CompoundTag storedBinding(String min) {
        CompoundTag source = new CompoundTag();
        source.putString("entity", "switch.porch");
        source.putString("port", "state");
        CompoundTag stored = new CompoundTag();
        stored.put("source", source);
        stored.putString("min", min);
        return stored;
    }

    private static boolean fails(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return true;
        }
        return false;
    }

    private static void noticeGate() {
        NoticeGate<UUID> gate = new NoticeGate<>();
        UUID lamp = UUID.randomUUID();
        UUID plug = UUID.randomUUID();
        check(gate.update(lamp, "not in the raw topic allowlist"), "A new refusal is told");
        check(!gate.update(lamp, "not in the raw topic allowlist"), "The same refusal is not told twice");
        check(gate.update(lamp, "the Home Assistant discovery prefix is reserved"), "Another reason is told");
        check(gate.update(plug, "not in the raw topic allowlist"), "Each device has its own record");
        check(!gate.update(lamp, null) && gate.problem(lamp) == null, "A link that works again has nothing to tell");
        check(gate.update(lamp, "the Home Assistant discovery prefix is reserved"), "A refusal that returns is told again");
        gate.forget(plug);
        check(gate.problem(plug) == null && gate.update(plug, "not in the raw topic allowlist"), "A forgotten device starts afresh");
        gate.clear();
        check(gate.problem(lamp) == null && gate.problem(plug) == null, "Clearing forgets every record");
    }

    /** The server cuts the status line and the problem, so a status update always encodes and never splits a character. */
    private static void statusCap() {
        int max = DeviceStatusPayload.MAX_STATUS_CHARS;
        BlockPos pos = new BlockPos(1, -60, 2);
        DeviceStatusPayload capped = DeviceStatusPayload.of(pos, "s".repeat(10_000), "p".repeat(max + 1));
        check(capped.status().length() == max && capped.status().endsWith("…"), "A long status is cut to " + max + " characters");
        check(capped.problem().orElseThrow().length() == max, "So is a long problem");
        String fits = "f".repeat(max);
        check(DeviceStatusPayload.cap(fits).equals(fits), "A status of exactly " + max + " characters stays whole");
        check(DeviceStatusPayload.of(pos, "ok", null).problem().isEmpty(), "No problem is sent as none");
        String emoji = "e".repeat(max - 2) + "😀" + "e".repeat(8);
        String cut = DeviceStatusPayload.cap(emoji);
        check(cut.length() <= max && !Character.isHighSurrogate(cut.charAt(cut.length() - 2)), "The cut never ends inside a surrogate pair: " + cut.length());

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        DeviceStatusPayload.CODEC.encode(buf, capped);
        check(DeviceStatusPayload.CODEC.decode(buf).equals(capped), "A capped status encodes and decodes unchanged");
        boolean refused = false;
        try {
            DeviceStatusPayload.CODEC.encode(new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY),
                    new DeviceStatusPayload(pos, "s".repeat(max + 1), Optional.empty()));
        } catch (RuntimeException expected) {
            refused = true;
        }
        check(refused, "An uncut status longer than the cap does not encode");
    }

    /**
     * Hoppers, hopper minecarts, droppers and sided item wrappers reach no configuration slot from any side (M2 plan
     * D7), while the screen still fills them. The insert runs vanilla's own hopper and dropper routine; the extraction
     * of hoppers and wrappers asks the same two face methods.
     */
    private static void automationFaces() {
        // An item stack needs its item's default components, which a server binds when it loads its data packs.
        Items.PAPER.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        Slots slots = new Slots();
        List<Direction> sides = new ArrayList<>(Arrays.asList(Direction.values()));
        sides.add(null);
        ItemStack paper = new ItemStack(Items.PAPER);
        for (Direction side : sides) {
            if (side != null) check(slots.getSlotsForFace(side).length == 0, "No slot faces " + side);
            for (int slot = 0; slot < slots.getContainerSize(); slot++) {
                check(!slots.canPlaceItemThroughFace(slot, paper, side), "Slot " + slot + " takes nothing through " + side);
                if (side != null) check(!slots.canTakeItemThroughFace(slot, paper, side), "Slot " + slot + " gives nothing through " + side);
            }
            ItemStack left = HopperBlockEntity.addItem(null, slots, new ItemStack(Items.PAPER, 3), side);
            check(left.is(Items.PAPER) && left.getCount() == 3 && slots.isEmpty(), "A hopper or dropper facing " + side + " inserts nothing");
        }

        check(slots.canPlaceItem(0, paper), "The screen's slots still take items");
        slots.setItem(0, paper.copy());
        check(slots.getItem(0).is(Items.PAPER), "A slot the screen fills keeps its item");
        ItemStack left = HopperBlockEntity.addItem(null, slots, new ItemStack(Items.PAPER), Direction.DOWN);
        check(left.getCount() == 1 && slots.getItem(0).getCount() == 1, "A hopper does not top up a filled slot");
    }

    /** Facts that all hold except the one at {@code failing} (-1: none), recording which were asked. */
    private static Facts facts(List<String> asked, int failing) {
        String[] names = {"tokenTaken", "acting", "menuOpen", "menuValid", "loaded", "inReach", "blockEntityMatches", "permitted"};
        BooleanSupplier[] suppliers = new BooleanSupplier[names.length];
        for (int i = 0; i < names.length; i++) {
            int index = i;
            suppliers[i] = () -> {
                asked.add(names[index]);
                return index != failing;
            };
        }
        return new Facts(suppliers[0], suppliers[1], suppliers[2], suppliers[3], suppliers[4], suppliers[5], suppliers[6], suppliers[7]);
    }

    private static BooleanSupplier never() {
        return () -> {
            throw new AssertionError("The claim must not be asked");
        };
    }

    private static void rejects(Runnable build, String description) {
        try {
            build.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Accepted " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    /** A MineQTT block entity's slots, over a vanilla block entity type: a MineQTT type exists only under a mod loader. */
    private static final class Slots extends BaseMqttBlockEntity {
        Slots() {
            super(BlockEntityTypes.DAYLIGHT_DETECTOR, BlockPos.ZERO, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        }

        @Override
        protected String getDefaultTopic() {
            return "";
        }

        @Override
        protected Component getDefaultName() {
            return Component.empty();
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
        }
    }
}

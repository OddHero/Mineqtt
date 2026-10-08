package art.rehra.mineqtt.config;

import art.rehra.mineqtt.mqtt.core.ProtocolVersion;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The JSON form of the config (Fabric's {@code mineqtt.json}). Dotted v2 keys such as {@code tls.caFile} are nested
 * objects. Reading is per key: a value of the wrong type falls back to its default with a message instead of
 * discarding the whole file.
 */
public final class JsonConfigFormat {
    /** Keys only a v1 file has. A file with none of them and no configVersion is a hand-written v2 file. */
    private static final List<String> V1_ONLY_KEYS = List.of("brokerUrl", "clientId", "autoReconnect", "connectionTimeout", "keepAlive", "statusTopic");

    private JsonConfigFormat() {
    }

    /**
     * @param migrated the file was v1 and has to be written back as v2
     * @param messages problems and migration notes for the log; never the password or the username
     */
    public record Result(boolean migrated, List<String> messages) {
    }

    /**
     * Fills {@link MineQTTConfig} from a parsed config file, migrating a v1 file, then sanitizes it. A file without
     * configVersion is v1 only when it has a v1-only key; otherwise it is read as v2.
     */
    public static Result read(JsonObject root) {
        Reader in = new Reader();
        boolean migrated = !root.has("configVersion") && V1_ONLY_KEYS.stream().anyMatch(root::has);
        if (migrated) {
            in.messages.addAll(ConfigMigration.applyV1(readV1(root, in)));
        } else {
            readV2(root, in);
        }
        in.messages.addAll(MineQTTConfig.sanitize());
        return new Result(migrated, in.messages);
    }

    /** {@link MineQTTConfig} as a v2 config file. */
    public static JsonObject write() {
        JsonObject root = new JsonObject();
        root.addProperty("configVersion", StaticDefaults.CONFIG_VERSION);
        root.addProperty("broker", MineQTTConfig.broker);
        root.addProperty("protocol", MineQTTConfig.protocol.configValue());
        root.addProperty("keepAliveSec", MineQTTConfig.keepAliveSec);
        root.addProperty("username", MineQTTConfig.username);
        root.addProperty("password", MineQTTConfig.password);
        JsonObject tls = new JsonObject();
        tls.addProperty("caFile", MineQTTConfig.tlsCaFile);
        tls.addProperty("insecureSkipVerify", MineQTTConfig.tlsInsecureSkipVerify);
        root.add("tls", tls);
        root.addProperty("bridgeId", MineQTTConfig.bridgeId);
        root.addProperty("baseTopic", MineQTTConfig.baseTopic);
        root.addProperty("discoveryPrefix", MineQTTConfig.discoveryPrefix);
        root.addProperty("haBirthTopic", MineQTTConfig.haBirthTopic);
        JsonObject ha = new JsonObject();
        ha.addProperty("expose", MineQTTConfig.haExpose.name());
        root.add("ha", ha);
        JsonObject acl = new JsonObject();
        JsonArray rawAllow = new JsonArray();
        MineQTTConfig.aclRawAllow.forEach(rawAllow::add);
        acl.add("rawAllow", rawAllow);
        acl.addProperty("opsBypassAllowlist", MineQTTConfig.aclOpsBypassAllowlist.name());
        acl.addProperty("explorerBrokerWide", MineQTTConfig.aclExplorerBrokerWide);
        root.add("acl", acl);
        JsonObject blockPlacer = new JsonObject();
        blockPlacer.addProperty("enabled", MineQTTConfig.blockPlacerEnabled);
        root.add("blockPlacer", blockPlacer);
        JsonObject limits = new JsonObject();
        limits.addProperty("maxDevicesPerPlayer", MineQTTConfig.limitsMaxDevicesPerPlayer);
        root.add("limits", limits);
        JsonObject legacy = new JsonObject();
        legacy.addProperty("v1StatusTopic", MineQTTConfig.legacyV1StatusTopic);
        root.add("legacy", legacy);
        root.addProperty("allowItemNetherPortalTeleport", MineQTTConfig.allowItemNetherPortalTeleport);
        root.addProperty("goalX", MineQTTConfig.goalX);
        root.addProperty("goalY", MineQTTConfig.goalY);
        root.addProperty("goalZ", MineQTTConfig.goalZ);
        root.addProperty("zombieGoalEnabled", MineQTTConfig.zombieGoalEnabled);
        return root;
    }

    private static ConfigMigration.V1 readV1(JsonObject root, Reader in) {
        return new ConfigMigration.V1(in.string(root, "brokerUrl", null), in.string(root, "clientId", null),
                in.secret(root, "username", null), in.secret(root, "password", null), in.integer(root, "keepAlive", null),
                in.string(root, "baseTopic", null), in.string(root, "statusTopic", null),
                in.bool(root, "allowItemNetherPortalTeleport", null), in.integer(root, "goalX", null),
                in.integer(root, "goalY", null), in.integer(root, "goalZ", null), in.bool(root, "zombieGoalEnabled", null));
    }

    private static void readV2(JsonObject root, Reader in) {
        MineQTTConfig.resetToDefaults();
        Integer version = in.integer(root, "configVersion", StaticDefaults.CONFIG_VERSION);
        if (version > StaticDefaults.CONFIG_VERSION) {
            in.messages.add("configVersion: " + version + " was written by a newer MineQTT; keys this version does not know are dropped on the next save");
        }
        MineQTTConfig.broker = in.string(root, "broker", StaticDefaults.DEFAULT_BROKER);
        MineQTTConfig.protocol = in.protocol(root, "protocol");
        MineQTTConfig.keepAliveSec = in.integer(root, "keepAliveSec", StaticDefaults.DEFAULT_KEEP_ALIVE_SEC);
        MineQTTConfig.username = in.secret(root, "username", StaticDefaults.DEFAULT_USERNAME);
        MineQTTConfig.password = in.secret(root, "password", StaticDefaults.DEFAULT_PASSWORD);
        JsonObject tls = in.section(root, "tls");
        MineQTTConfig.tlsCaFile = in.string(tls, "tls.caFile", StaticDefaults.DEFAULT_TLS_CA_FILE);
        MineQTTConfig.tlsInsecureSkipVerify = in.bool(tls, "tls.insecureSkipVerify", StaticDefaults.DEFAULT_TLS_INSECURE_SKIP_VERIFY);
        MineQTTConfig.bridgeId = in.string(root, "bridgeId", StaticDefaults.DEFAULT_BRIDGE_ID);
        MineQTTConfig.baseTopic = in.string(root, "baseTopic", StaticDefaults.DEFAULT_BASE_TOPIC);
        MineQTTConfig.discoveryPrefix = in.string(root, "discoveryPrefix", StaticDefaults.DEFAULT_DISCOVERY_PREFIX);
        MineQTTConfig.haBirthTopic = in.string(root, "haBirthTopic", StaticDefaults.DEFAULT_HA_BIRTH_TOPIC);
        JsonObject ha = in.section(root, "ha");
        MineQTTConfig.haExpose = in.enumValue(ha, "ha.expose", HaExpose.class, StaticDefaults.DEFAULT_HA_EXPOSE);
        JsonObject acl = in.section(root, "acl");
        MineQTTConfig.aclRawAllow = in.stringList(acl, "acl.rawAllow", StaticDefaults.DEFAULT_ACL_RAW_ALLOW);
        MineQTTConfig.aclOpsBypassAllowlist = in.autoBoolean(acl, "acl.opsBypassAllowlist", StaticDefaults.DEFAULT_ACL_OPS_BYPASS_ALLOWLIST);
        MineQTTConfig.aclExplorerBrokerWide = in.bool(acl, "acl.explorerBrokerWide", StaticDefaults.DEFAULT_ACL_EXPLORER_BROKER_WIDE);
        JsonObject blockPlacer = in.section(root, "blockPlacer");
        MineQTTConfig.blockPlacerEnabled = in.bool(blockPlacer, "blockPlacer.enabled", StaticDefaults.DEFAULT_BLOCK_PLACER_ENABLED);
        JsonObject limits = in.section(root, "limits");
        MineQTTConfig.limitsMaxDevicesPerPlayer = in.integer(limits, "limits.maxDevicesPerPlayer", StaticDefaults.DEFAULT_LIMITS_MAX_DEVICES_PER_PLAYER);
        JsonObject legacy = in.section(root, "legacy");
        MineQTTConfig.legacyV1StatusTopic = in.string(legacy, "legacy.v1StatusTopic", StaticDefaults.DEFAULT_LEGACY_V1_STATUS_TOPIC);
        MineQTTConfig.allowItemNetherPortalTeleport = in.bool(root, "allowItemNetherPortalTeleport", StaticDefaults.DEFAULT_ALLOW_ITEM_NETHER_PORTAL_TELEPORT);
        MineQTTConfig.goalX = in.integer(root, "goalX", StaticDefaults.DEFAULT_GOAL_X);
        MineQTTConfig.goalY = in.integer(root, "goalY", StaticDefaults.DEFAULT_GOAL_Y);
        MineQTTConfig.goalZ = in.integer(root, "goalZ", StaticDefaults.DEFAULT_GOAL_Z);
        MineQTTConfig.zombieGoalEnabled = in.bool(root, "zombieGoalEnabled", StaticDefaults.DEFAULT_ZOMBIE_GOAL_ENABLED);
    }

    /**
     * Typed lookups that report a wrong type and fall back. A key is the dotted path for messages; its last segment is
     * the member name inside the given object. A missing key silently takes the fallback.
     */
    private static final class Reader {
        final List<String> messages = new ArrayList<>();

        JsonObject section(JsonObject parent, String key) {
            JsonElement value = parent.get(key);
            if (value == null || value.isJsonNull()) return new JsonObject();
            if (value.isJsonObject()) return value.getAsJsonObject();
            messages.add(key + ": expected an object, using the defaults of its keys");
            return new JsonObject();
        }

        String string(JsonObject parent, String key, String fallback) {
            JsonPrimitive value = primitive(parent, key);
            if (value == null) return fallback;
            if (value.isString()) return value.getAsString();
            messages.add(key + ": expected a string, " + fallingBackTo(fallback == null ? null : "'" + fallback + "'"));
            return fallback;
        }

        /** Like {@link #string}, but the message never repeats a value. */
        String secret(JsonObject parent, String key, String fallback) {
            JsonPrimitive value = primitive(parent, key);
            if (value == null) return fallback;
            if (value.isString()) return value.getAsString();
            messages.add(key + ": expected a string, the value is ignored");
            return fallback;
        }

        Integer integer(JsonObject parent, String key, Integer fallback) {
            JsonPrimitive value = primitive(parent, key);
            if (value == null) return fallback;
            if (value.isNumber()) {
                double number = value.getAsDouble();
                if (number == Math.rint(number) && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return (int) number;
            }
            messages.add(key + ": expected a whole number, " + fallingBackTo(fallback));
            return fallback;
        }

        Boolean bool(JsonObject parent, String key, Boolean fallback) {
            JsonPrimitive value = primitive(parent, key);
            if (value == null) return fallback;
            if (value.isBoolean()) return value.getAsBoolean();
            messages.add(key + ": expected true or false, " + fallingBackTo(fallback));
            return fallback;
        }

        ProtocolVersion protocol(JsonObject parent, String key) {
            JsonPrimitive value = primitive(parent, key);
            if (value == null) return StaticDefaults.DEFAULT_PROTOCOL;
            try {
                return ProtocolVersion.parse(value.getAsString());
            } catch (IllegalArgumentException e) {
                messages.add(key + ": " + e.getMessage() + ", using " + StaticDefaults.DEFAULT_PROTOCOL.configValue());
                return StaticDefaults.DEFAULT_PROTOCOL;
            }
        }

        <E extends Enum<E>> E enumValue(JsonObject parent, String key, Class<E> type, E fallback) {
            JsonPrimitive value = primitive(parent, key);
            if (value == null) return fallback;
            if (value.isString()) {
                for (E constant : type.getEnumConstants()) {
                    if (constant.name().equals(value.getAsString().strip().toUpperCase(Locale.ROOT))) return constant;
                }
            }
            messages.add(key + ": expected one of " + List.of(type.getEnumConstants()) + ", " + fallingBackTo(fallback));
            return fallback;
        }

        /** {@link AutoBoolean} also accepts a plain JSON boolean. */
        AutoBoolean autoBoolean(JsonObject parent, String key, AutoBoolean fallback) {
            JsonPrimitive value = primitive(parent, key);
            if (value != null && value.isBoolean()) return value.getAsBoolean() ? AutoBoolean.TRUE : AutoBoolean.FALSE;
            return enumValue(parent, key, AutoBoolean.class, fallback);
        }

        List<String> stringList(JsonObject parent, String key, List<String> fallback) {
            JsonElement value = parent.get(member(key));
            if (value == null || value.isJsonNull()) return fallback;
            if (value.isJsonArray()) {
                List<String> list = new ArrayList<>();
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        list.add(element.getAsString());
                    } else {
                        messages.add(key + ": ignoring " + element + ", entries must be strings");
                    }
                }
                return List.copyOf(list);
            }
            messages.add(key + ": expected a list of strings, using " + fallback);
            return fallback;
        }

        private JsonPrimitive primitive(JsonObject parent, String key) {
            JsonElement value = parent.get(member(key));
            if (value == null || value.isJsonNull()) return null;
            if (value.isJsonPrimitive()) return value.getAsJsonPrimitive();
            messages.add(key + ": expected a single value, not an object or a list; the value is ignored");
            return null;
        }

        private static String fallingBackTo(Object fallback) {
            return fallback == null ? "the value is ignored" : "using " + fallback;
        }

        private static String member(String key) {
            return key.substring(key.lastIndexOf('.') + 1);
        }
    }
}

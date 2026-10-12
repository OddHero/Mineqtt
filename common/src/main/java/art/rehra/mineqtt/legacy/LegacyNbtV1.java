package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.device.RawLink;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.types.BlockObserver;
import art.rehra.mineqtt.device.types.ClimateSensor;
import art.rehra.mineqtt.device.types.PresenceSensor;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.device.timer.core.TimerKind;
import art.rehra.mineqtt.device.timer.core.TimerState;
import com.mojang.serialization.Codec;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The block entity data MineQTT 1 wrote, read by pure functions (design §9.1): the migrator turns it into a
 * {@link Snapshot} on load, and the registry side effects happen when the device attaches. Also the v1 topic rules of
 * the item slots, which the blocks that are no devices keep.
 */
public final class LegacyNbtV1 {
    /** The tab v1 and M1 remembered, beside the device data. */
    public static final String LAST_TAB = "lastTabId";
    /** v1's private mode, which M1 kept beside its device data too. */
    public static final String PRIVATE_MODE = "privateMode";
    /** The first of a motion sensor's spawn-egg filter slots, as v1's filter tab showed them and M1 read them. */
    public static final int FIRST_EGG_SLOT = 2;
    public static final int EGG_SLOTS = 5;
    // v1 kept 0-15 per colour channel; 15 * 17 = 255.
    private static final int CHANNEL_TO_8_BIT = 17;
    private static final int V1_SLOTS = 4;
    private static final String ITEMS = "Items";
    private static final String TOPIC = "topic";
    private static final String OWNER_NAME = "ownerName";
    private static final String OWNER_UUID = "ownerUuid";
    /** The name an anvil gave a v1 block, which its container block entity kept. */
    private static final String CUSTOM_NAME = "CustomName";
    private static final String TEMPERATURE = "Temperature";
    private static final String HUMIDITY = "Humidity";
    private static final String UPDATE_INTERVAL_TICKS = "UpdateIntervalTicks";
    private static final String LAST_PUBLISH_TIME = "LastPublishTime";
    /** The message of a v1 Static Publisher, which it published on every rising edge; {@code true} when empty. */
    private static final String STATIC_MESSAGE = "staticMessage";
    /** A v1 Block Sensor's data mode. */
    private static final String SENSOR_DATA_MODE = "BlockSensorDataMode";
    // v1 sensors published every 200 ticks unless set otherwise.
    private static final int V1_SENSOR_INTERVAL_TICKS = 200;
    // The v1 timers' own keys (M4 plan D4), each kind's first key telling it apart.
    private static final String INTERVAL_VALUE = "intervalValue";
    private static final String UNIT_INDEX = "unitIndex";
    private static final String TICK_COUNTER = "tickCounter";
    private static final String TRIGGER_MODE_INDEX = "triggerModeIndex";
    private static final String CUSTOM_TIME = "customTime";
    private static final String LAST_TRIGGER_DAY = "lastTriggerDay";
    private static final String ALARM_TIME = "alarmTime";
    private static final String LAST_ALARM_DAY = "lastAlarmDay";
    private static final String CRON_EXPRESSION = "cronExpression";
    private static final String LAST_TRIGGER_TICK = "lastTriggerTick";
    private static final String LAST_TRIGGER_TIME = "lastTriggerTime";
    // A v1 Daylight Timer without a custom time had noon; a day had 24000 ticks.
    private static final int V1_CUSTOM_TIME = 6000;
    private static final int V1_DAY_TICKS = 24_000;

    /**
     * What every v1 device block entity had.
     *
     * @param combinedTopic the topic v1 used, private owner prefix included; empty when no base item was set
     * @param ownerUuid     the UUID the owner name was resolved to, which only a block that stayed a legacy block after
     *                      M2 F11 saved (the Smart Plug until M2-18); null when unknown
     * @param custom        the custom on/off payloads of slots 2 and 3, as v1 read them; a Static Publisher's message as
     *                      the on payload instead
     * @param name          the name an anvil gave the block ({@code CustomName}), which the device takes; empty when none
     * @param edges         which edges its topic got: {@link RawLink.Edges#RISING} for a Static Publisher converted into a
     *                      Redstone Input (M2 plan D11), which published its message on the rising edge only, else
     *                      {@link RawLink.Edges#BOTH}
     */
    public record Snapshot(String combinedTopic, String ownerName, @Nullable UUID ownerUuid, boolean privateMode, ValueCodecs.CustomPayloads custom,
                           String name, RawLink.Edges edges) {
    }

    private LegacyNbtV1() {
    }

    /**
     * Reads the keys every v1 device wrote: {@code Items}, {@code ownerName}, {@code privateMode} and the
     * {@code CustomName} an anvil gave it, and the {@code ownerUuid} of a legacy block saved since M2 F11. A converted
     * Static Publisher's {@code staticMessage} is its on payload, published on rising edges only; its third slot, which
     * no screen showed, never fed the message, so its item is no payload.
     */
    public static Snapshot read(ValueInput input) {
        NonNullList<ItemStack> items = NonNullList.withSize(V1_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        String ownerName = input.getStringOr(OWNER_NAME, "");
        boolean privateMode = input.getBooleanOr(PRIVATE_MODE, false);
        Optional<String> staticMessage = input.getString(STATIC_MESSAGE);
        ValueCodecs.CustomPayloads custom = staticMessage.map(LegacyNbtV1::staticPayloads).orElseGet(() -> customPayloads(items.get(2), items.get(3)));
        return new Snapshot(combinedTopic(items.get(0), items.get(1), privateMode, ownerName), ownerName,
                input.read(OWNER_UUID, UUIDUtil.CODEC).orElse(null), privateMode, custom, customName(input),
                staticMessage.isPresent() ? RawLink.Edges.RISING : RawLink.Edges.BOTH);
    }

    /**
     * A Static Publisher's message as payloads: the on payload, none for an empty message, as v1 then published
     * {@code true}, which is a Redstone Input's on payload without a custom one. It published nothing when switched off.
     */
    private static ValueCodecs.CustomPayloads staticPayloads(String message) {
        return new ValueCodecs.CustomPayloads(message.isEmpty() ? null : message, null);
    }

    // The plain text of the anvil name; empty without one or for an unreadable one.
    private static String customName(ValueInput input) {
        Component name = BlockEntity.parseCustomNameSafe(input, CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    /**
     * Every item of the slots ({@code Items}) a v1 or M1 device kept beside its data, each with its slot; a motion sensor
     * had nine.
     */
    public static List<ItemStackWithSlot> items(ValueInput input) {
        return input.listOrEmpty(ITEMS, ItemStackWithSlot.CODEC).stream().filter(item -> !item.stack().isEmpty()).toList();
    }

    /**
     * Writes the keys every v1 device wrote back as they were read, for a device whose v1 data is not adopted yet: its
     * slots, owner name and UUID, private mode, anvil name, topic and last tab, and a converted Static Publisher's
     * message. Saved this way, it is still the same v1 data when it loads again.
     */
    public static void writeDevice(ValueOutput output, Snapshot v1, List<ItemStackWithSlot> items, String lastTab) {
        output.putString(TOPIC, v1.combinedTopic());
        output.putString(LAST_TAB, lastTab);
        output.putString(OWNER_NAME, v1.ownerName());
        output.storeNullable(OWNER_UUID, UUIDUtil.CODEC, v1.ownerUuid());
        output.putBoolean(PRIVATE_MODE, v1.privateMode());
        if (!v1.name().isEmpty()) output.store(CUSTOM_NAME, ComponentSerialization.CODEC, Component.literal(v1.name()));
        if (v1.edges() == RawLink.Edges.RISING) output.putString(STATIC_MESSAGE, v1.custom().on() == null ? "" : v1.custom().on());
        if (items.isEmpty()) return;
        ValueOutput.TypedOutputList<ItemStackWithSlot> list = output.list(ITEMS, ItemStackWithSlot.CODEC);
        items.forEach(list::add);
    }

    /**
     * The mobs a v1 or M1 motion sensor counted: the entity type of every spawn egg in its filter slots (2 to 6), once
     * each, in slot order. Other items filtered nothing, and without an egg it counted every mob (empty). An egg of the
     * player, whom only a hand-made egg names, is left out as {@link PresenceSensor#parseFilter} refuses it.
     */
    public static List<Identifier> eggFilter(List<ItemStackWithSlot> items) {
        List<Identifier> filter = new ArrayList<>();
        for (ItemStackWithSlot item : items) {
            if (item.slot() < FIRST_EGG_SLOT || item.slot() >= FIRST_EGG_SLOT + EGG_SLOTS) continue;
            if (!(item.stack().getItem() instanceof SpawnEggItem)) continue;
            EntityType<?> type = SpawnEggItem.getType(item.stack());
            if (type == null || type == EntityTypes.PLAYER) continue;
            Identifier id = EntityType.getKey(type);
            if (!filter.contains(id)) filter.add(id);
        }
        return List.copyOf(filter);
    }

    /** v1's custom on/off payloads: the topic of the item in slot 2 and in slot 3, null for an empty slot. */
    public static ValueCodecs.CustomPayloads customPayloads(ItemStack on, ItemStack off) {
        return new ValueCodecs.CustomPayloads(on.isEmpty() ? null : itemTopic(on), off.isEmpty() ? null : itemTopic(off));
    }

    /**
     * Whether the data holds the own key of v1's subscribing blocks, {@code currentSubscribedTopic}, which a redstone
     * emitter and a smart plug wrote and v2 data never has. v1 publishers wrote no key of their own.
     */
    public static boolean hasSubscribedKeys(ValueInput input) {
        return input.getString("currentSubscribedTopic").isPresent();
    }

    /**
     * Writes the key of v1's subscribing blocks, a redstone emitter's, a smart plug's and an RGB LED's, for a block
     * entity whose v1 data is not adopted yet: saved this way, it is still v1 data when it loads again, also from an item.
     */
    public static void writeSubscribed(ValueOutput output, String topic) {
        output.putString("currentSubscribedTopic", topic);
    }

    /**
     * Whether the data holds a v1 motion sensor's own keys ({@code MotionDetected}, {@code LastMotionTime},
     * {@code LastCount}), which v2 data never has at its top level.
     */
    public static boolean hasMotionKeys(ValueInput input) {
        return input.getInt("LastCount").isPresent() || input.getLong("LastMotionTime").isPresent();
    }

    /** A v1 motion sensor: it counted mobs; {@code LastCount} was -1 before its first scan. */
    public static PresenceSensor.Presence motionState(ValueInput input) {
        return PresenceSensor.Presence.ofV1(input.getIntOr("LastCount", -1), input.getByteOr("MotionDetected", (byte) 0) != 0);
    }

    /**
     * Writes a motion sensor's own v1 keys, for a block entity whose v1 data is not adopted yet: saved this way, it is
     * still v1 data when it loads again. v1's {@code LastMotionTime} was wall-clock time that v2 never reads; it is
     * written as 0.
     */
    public static void writeMotion(ValueOutput output, PresenceSensor.Presence presence) {
        output.putByte("MotionDetected", (byte) (presence.occupancy() ? 1 : 0));
        output.putLong("LastMotionTime", 0L);
        output.putInt("LastCount", presence.count());
    }

    /**
     * Whether the data holds a v1 Block Sensor's own key ({@code BlockSensorDataMode}), which 0.7.0 and every v1 sensor
     * wrote and v2 data never has at its top level.
     */
    public static boolean hasSensorKeys(ValueInput input) {
        return input.getString(SENSOR_DATA_MODE).isPresent();
    }

    /** A v1 Block Sensor (M4 plan D12): its data mode is the Block Observer's payload; {@code full_data} without one. */
    public static BlockObserver.Observation sensorState(ValueInput input) {
        return BlockObserver.Observation.ofV1(BlockObserver.Payload.ofV1(input.getStringOr(SENSOR_DATA_MODE, "")));
    }

    /**
     * Writes a v1 Block Sensor's own key, for a block entity whose v1 data is not adopted yet: saved this way, it is still
     * v1 data when it loads again.
     */
    public static void writeSensor(ValueOutput output, BlockObserver.Observation observation) {
        output.putString(SENSOR_DATA_MODE, observation.payload().v1Name());
    }

    /**
     * Whether the data holds a v1 temperature or humidity sensor's own keys ({@code Temperature}, {@code Humidity},
     * {@code UpdateIntervalTicks}), which v2 data never has at its top level.
     */
    public static boolean hasClimateKeys(ValueInput input) {
        return input.read(TEMPERATURE, Codec.DOUBLE).isPresent() || input.read(HUMIDITY, Codec.DOUBLE).isPresent() || input.getInt(UPDATE_INTERVAL_TICKS).isPresent();
    }

    /**
     * A v1 temperature or humidity sensor ({@link ClimateSensor.Climate#ofV1}): a humidity sensor is told by its
     * {@code Humidity} without a {@code Temperature}, and keeps publishing its humidity.
     */
    public static ClimateSensor.Climate climateState(ValueInput input) {
        return ClimateSensor.Climate.ofV1(input.getIntOr(UPDATE_INTERVAL_TICKS, V1_SENSOR_INTERVAL_TICKS), input.read(TEMPERATURE, Codec.DOUBLE).orElse(null),
                input.read(HUMIDITY, Codec.DOUBLE).orElse(null), input.getLongOr(LAST_PUBLISH_TIME, 0L));
    }

    /**
     * Writes a v1 temperature or humidity sensor's own keys, for a block entity whose v1 data is not adopted yet: saved
     * this way, it is still the same sensor when it loads again, a humidity sensor's {@code Humidity} without a
     * {@code Temperature}.
     */
    public static void writeClimate(ValueOutput output, ClimateSensor.Climate climate) {
        if (climate.rawValue() == ClimateSensor.RawValue.HUMIDITY) {
            output.putDouble(HUMIDITY, climate.reading().humidity());
        } else {
            output.putDouble(TEMPERATURE, climate.reading().temperature());
        }
        output.putLong(LAST_PUBLISH_TIME, climate.lastReading());
        output.putInt(UPDATE_INTERVAL_TICKS, climate.intervalTicks());
    }

    /**
     * A v1 timer's settings and counters, from the keys of its kind (M4 plan D4).
     *
     * @param intervalValue the interval as v1 saved it, which {@link Timers.Settings} clamps; for the log
     */
    public record Timer(Timers.Settings settings, TimerState counters, long intervalValue) {
    }

    /**
     * Whether the data holds a v1 timer's own keys, each kind told by its first: an interval timer's
     * {@code intervalValue}, a Daylight Timer's {@code triggerModeIndex}, an alarm clock's {@code alarmTime}, a cron
     * timer's {@code cronExpression}. v2 data never has them at its top level.
     */
    public static boolean hasTimerKeys(ValueInput input) {
        return input.getLong(INTERVAL_VALUE).isPresent() || input.getInt(TRIGGER_MODE_INDEX).isPresent() || input.getInt(ALARM_TIME).isPresent()
                || input.getString(CRON_EXPRESSION).isPresent();
    }

    /**
     * A v1 timer of {@code kind} (D4): its settings and counters under their v1 meaning, with v1's defaults for a missing
     * key. An alarm clock without an alarm ({@code alarmTime} -1) is disabled and set to 06:00; v1's
     * {@code lastTriggerTime} starts the pulse of a timer saved while powered.
     */
    public static Timer timerState(ValueInput input, TimerKind kind) {
        long lastTriggerTime = input.getLongOr(LAST_TRIGGER_TIME, 0L);
        Timers.Settings defaults = Timers.Settings.DEFAULT;
        return switch (kind) {
            case INTERVAL -> {
                long value = input.getLongOr(INTERVAL_VALUE, 20L);
                int interval = (int) Math.clamp(value, 1, Timers.MAX_INTERVAL);
                yield new Timer(defaults.withInterval(interval, Timers.Unit.ofV1(input.getIntOr(UNIT_INDEX, 0))),
                        new TimerState(input.getLongOr(TICK_COUNTER, 0L), -1, -1, lastTriggerTime), value);
            }
            case GAME_TIME -> new Timer(defaults.withTriggerAt(Timers.TriggerAt.ofV1(input.getIntOr(TRIGGER_MODE_INDEX, 0)))
                    .withTimeTicks(input.getIntOr(CUSTOM_TIME, V1_CUSTOM_TIME)),
                    new TimerState(0, input.getLongOr(LAST_TRIGGER_DAY, -1L), -1, lastTriggerTime), defaults.interval());
            case ALARM -> {
                int alarm = input.getIntOr(ALARM_TIME, -1);
                boolean set = alarm >= 0 && alarm < V1_DAY_TICKS;
                yield new Timer(defaults.withEnabled(set).withTimeTicks(set ? alarm : 0),
                        new TimerState(0, input.getLongOr(LAST_ALARM_DAY, -1L), -1, lastTriggerTime), defaults.interval());
            }
            case CRON -> new Timer(defaults.withCron(input.getStringOr(CRON_EXPRESSION, "")),
                    new TimerState(0, -1, input.getLongOr(LAST_TRIGGER_TICK, -1L), lastTriggerTime), defaults.interval());
        };
    }

    /**
     * Writes a timer's own v1 keys, for a block entity whose v1 data is not adopted yet: saved this way, it is still the
     * same v1 timer when it loads again. A disabled alarm clock is one without an alarm.
     */
    public static void writeTimer(ValueOutput output, TimerKind kind, Timers.Settings settings, TimerState counters) {
        switch (kind) {
            case INTERVAL -> {
                output.putLong(INTERVAL_VALUE, settings.interval());
                output.putInt(UNIT_INDEX, settings.unit().ordinal());
                output.putLong(TICK_COUNTER, counters.tickCounter());
            }
            case GAME_TIME -> {
                output.putInt(TRIGGER_MODE_INDEX, settings.triggerAt().ordinal());
                output.putInt(CUSTOM_TIME, settings.timeTicks());
                output.putLong(LAST_TRIGGER_DAY, counters.lastTriggerDay());
            }
            case ALARM -> {
                output.putInt(ALARM_TIME, settings.enabled() ? settings.timeTicks() : -1);
                output.putLong(LAST_ALARM_DAY, counters.lastTriggerDay());
            }
            case CRON -> {
                output.putString(CRON_EXPRESSION, settings.cron());
                output.putLong(LAST_TRIGGER_TICK, counters.lastTriggerTick());
            }
        }
        if (kind != TimerKind.CRON) output.putLong(LAST_TRIGGER_TIME, counters.lastTriggerTime());
    }

    /**
     * The colour state of a v1 RGB LED: {@code TargetRed/TargetGreen/TargetBlue} (0–15, ×17 to 8 bit),
     * {@code Brightness} (0–255) and {@code Lit}. v1's plain colour payloads set only the displayed
     * {@code Red/Green/Blue}; a lamp without target colour takes those instead.
     */
    public static ValueCodecs.LightState lampState(ValueInput input) {
        int red = input.getIntOr("TargetRed", 0);
        int green = input.getIntOr("TargetGreen", 0);
        int blue = input.getIntOr("TargetBlue", 0);
        if (red == 0 && green == 0 && blue == 0) {
            red = input.getIntOr("Red", 0);
            green = input.getIntOr("Green", 0);
            blue = input.getIntOr("Blue", 0);
        }
        return new ValueCodecs.LightState(input.getByteOr("Lit", (byte) 0) != 0, input.getIntOr("Brightness", 255),
                new ValueCodecs.Rgb(to8Bit(red), to8Bit(green), to8Bit(blue)), null);
    }

    /** Whether the data holds a v1 RGB LED's colour keys, which v2 data never has at its top level. */
    public static boolean hasLampKeys(ValueInput input) {
        return input.getInt("TargetRed").isPresent() || input.getInt("Red").isPresent();
    }

    /**
     * Writes a lamp's colour state with the v1 keys, for a block entity whose v1 data is not adopted yet: saved this
     * way, it is still v1 data when it loads again.
     */
    public static void writeLamp(ValueOutput output, ValueCodecs.LightState state) {
        int red = toChannel(state.color().r());
        int green = toChannel(state.color().g());
        int blue = toChannel(state.color().b());
        output.putInt("TargetRed", red);
        output.putInt("TargetGreen", green);
        output.putInt("TargetBlue", blue);
        output.putInt("Brightness", state.brightness());
        output.putByte("Lit", (byte) (state.on() ? 1 : 0));
        float factor = state.brightness() / 255.0f;
        output.putInt("Red", Math.round(red * factor));
        output.putInt("Green", Math.round(green * factor));
        output.putInt("Blue", Math.round(blue * factor));
    }

    /**
     * v1's topic of an item: a renamed paper's name, otherwise the item id with {@code :} as {@code /};
     * {@code minecraft/default} for an empty stack.
     */
    public static String itemTopic(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft/default";
        if (stack.getItem() == Items.PAPER) return stack.getHoverName().getString();
        return stack.getItem().toString().replace(":", "/");
    }

    /**
     * v1's combined topic: base (slot 0) and sub (slot 1) joined with {@code /}, empty without a base item; in private
     * mode the owner's name is put in front, and stands alone without a base item.
     */
    public static String combinedTopic(ItemStack base, ItemStack sub, boolean privateMode, String ownerName) {
        String basePath = base.isEmpty() ? "" : itemTopic(base);
        String subPath = sub.isEmpty() ? "" : itemTopic(sub);
        String combined = basePath.isEmpty() ? "" : subPath.isEmpty() ? basePath : basePath + "/" + subPath;
        if (privateMode && !ownerName.isEmpty()) return combined.isEmpty() ? ownerName : ownerName + "/" + combined;
        return combined;
    }

    /** The unique_id v1 gave a lamp's Home Assistant light: its topic with every other character as {@code _}. */
    public static String legacyUniqueId(String topic) {
        return topic.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * The name of a device or group made from a v1 topic: its last level, title-cased ({@code home/kitchen_lamp}
     * becomes "Kitchen Lamp"); empty when the topic has no letters or digits.
     */
    public static String topicName(String topic) {
        String[] levels = topic.split("/");
        String last = "";
        for (int i = levels.length - 1; i >= 0 && last.isEmpty(); i--) last = levels[i].strip();
        StringBuilder name = new StringBuilder();
        for (String word : ValueCodecs.sanitizeText(last).split("[\\s_-]+")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.appendCodePoint(Character.toTitleCase(word.codePointAt(0))).append(word.substring(Character.charCount(word.codePointAt(0))));
        }
        return name.chars().anyMatch(Character::isLetterOrDigit) ? name.toString() : "";
    }

    /** A v1 colour channel (0–15) as 8 bit. */
    public static int to8Bit(int channel) {
        return Math.clamp(channel, 0, 15) * CHANNEL_TO_8_BIT;
    }

    private static int toChannel(int value) {
        return Math.round(value / (float) CHANNEL_TO_8_BIT);
    }
}

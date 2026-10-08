package art.rehra.mineqtt.legacy;

import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.types.PresenceSensor;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The block entity data MineQTT 1 wrote, read by pure functions (design §9.1): the migrator turns it into a
 * {@link Snapshot} on load, and the registry side effects happen when the device attaches. Also the v1 topic rules
 * the item slots keep in M1, where they edit the Raw Link.
 */
public final class LegacyNbtV1 {
    // v1 kept 0-15 per colour channel; 15 * 17 = 255.
    private static final int CHANNEL_TO_8_BIT = 17;
    private static final int V1_SLOTS = 4;

    /**
     * What every v1 device block entity had.
     *
     * @param combinedTopic the topic v1 used, private owner prefix included; empty when no base item was set
     * @param custom        the custom on/off payloads of slots 2 and 3, as v1 read them
     */
    public record Snapshot(String combinedTopic, String ownerName, boolean privateMode, ValueCodecs.CustomPayloads custom) {
    }

    private LegacyNbtV1() {
    }

    /** Reads the keys every v1 device wrote: {@code Items}, {@code ownerName} and {@code privateMode}. */
    public static Snapshot read(ValueInput input) {
        NonNullList<ItemStack> items = NonNullList.withSize(V1_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        String ownerName = input.getStringOr("ownerName", "");
        boolean privateMode = input.getBooleanOr("privateMode", false);
        return new Snapshot(combinedTopic(items.get(0), items.get(1), privateMode, ownerName), ownerName, privateMode,
                customPayloads(items.get(2), items.get(3)));
    }

    /** v1's custom on/off payloads: the topic of the item in slot 2 and in slot 3, null for an empty slot. */
    public static ValueCodecs.CustomPayloads customPayloads(ItemStack on, ItemStack off) {
        return new ValueCodecs.CustomPayloads(on.isEmpty() ? null : itemTopic(on), off.isEmpty() ? null : itemTopic(off));
    }

    /**
     * Whether the data holds a v1 redstone emitter's own key, {@code currentSubscribedTopic}, which v2 data never has.
     * v1 publishers wrote no key of their own.
     */
    public static boolean hasEmitterKeys(ValueInput input) {
        return input.getString("currentSubscribedTopic").isPresent();
    }

    /**
     * Writes a redstone emitter's own v1 key, for a block entity whose v1 data is not adopted yet: saved this way, it is
     * still v1 data when it loads again, also from an item.
     */
    public static void writeEmitter(ValueOutput output, String topic) {
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

package art.rehra.mineqtt.device.types;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.device.Access;
import art.rehra.mineqtt.device.CommonFields;
import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Field;
import art.rehra.mineqtt.device.PlacerRules;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.UnloadedPolicy;
import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.ha.Ha;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Container Observer ({@code container_observer}): placed against a container (a chest, a barrel, a furnace, a
 * shulker box, a chest minecart standing there), it reports what the container holds. Home Assistant gets the fill as
 * the device's primary sensor, with the item counts by item id as its attributes, the number of items and of used slots,
 * and whether the container is empty or full. Every entity stays the same whatever the container holds, so its discovery
 * never changes with the contents.
 * <p>
 * It reads every slot of the container, as a comparator does, and counts by item id alone: names, enchantments, book
 * text or any other part of a stack never leave the server. It reads a container only where its owner may open it
 * ({@link #mayRead}, the Block Placer's rule for the container behind it): across a claim line it reports nothing but
 * {@link Status#CLAIMED}. It never loads the chunk of the container. It looks every {@value #SAMPLE_TICKS} ticks and
 * publishes a changed state at most once a second.
 */
public final class ContainerObserver {
    /** How often it looks at the container, in game ticks: twice a second, as the Block Observer samples block data. */
    public static final int SAMPLE_TICKS = 10;
    /**
     * The most item ids its {@link #ITEMS} lists, the most numerous first. A double chest holds 54 stacks, so this
     * covers any vanilla container; a larger modded one lists its 64 most numerous items, and {@link #DISTINCT} tells
     * how many there are.
     */
    public static final int MAX_ITEMS = 64;
    /**
     * The most the item ids of {@link #ITEMS} take as UTF-8, so a pack with very long ids still leaves the state well
     * within what a device may publish ({@code DeviceView.MAX_STATE_BYTES}).
     */
    public static final int MAX_ITEMS_BYTES = 8 * 1024;
    // What an entry of the items object takes besides its id, at most: quotes, colon, comma and a ten-digit count.
    private static final int ENTRY_OVERHEAD = 14;

    /** What it reports. */
    public enum Status implements StringRepresentable {
        /** It has not looked yet since it loaded; the default, so a state from before the first look reads unknown. */
        WAITING,
        /** It read the container. */
        OK,
        /** Nothing it can read is in front of it. */
        NO_CONTAINER,
        /** Its owner may not open the container there, so it reports nothing of it ({@link #mayRead}). */
        CLAIMED;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The id of the container in front, e.g. {@code minecraft:chest} or {@code minecraft:chest_minecart}; empty for none. */
    public static final Field<String> CONTAINER = Field.text("container", Access.READ);
    /** The items it holds, item id to count, sorted by id; at most {@value #MAX_ITEMS} ids. The fill sensor's attributes. */
    public static final Field<Map<String, Integer>> ITEMS = Field.json("items", Codec.unboundedMap(Codec.STRING, Codec.INT), Map.of(), Access.READ);
    /** How many items it holds. */
    public static final Field<Integer> TOTAL = Field.intRange("total", 0, Integer.MAX_VALUE, Access.READ);
    /** How many different items it holds; more than {@link #ITEMS} lists when there are over {@value #MAX_ITEMS}. */
    public static final Field<Integer> DISTINCT = Field.intRange("distinct", 0, Integer.MAX_VALUE, Access.READ);
    public static final Field<Integer> SLOTS_USED = Field.intRange("slots_used", 0, Integer.MAX_VALUE, Access.READ);
    public static final Field<Integer> SLOTS_TOTAL = Field.intRange("slots_total", 0, Integer.MAX_VALUE, Access.READ);
    /** How full it is, as a comparator measures it: each slot counts as its stack's share of a full stack. */
    public static final Field<Double> FILL = Field.decimal("fill", 0, 100, 1, 0, "%", Access.READ);
    public static final Field<Boolean> EMPTY = Field.bool("empty", Access.READ);
    /** Whether every slot holds a full stack, so nothing more fits. */
    public static final Field<Boolean> FULL = Field.bool("full", Access.READ);
    public static final Field<Status> STATUS = Field.enumOf("status", Status.class, Access.READ);

    public static final DeviceType TYPE = DeviceType.builder(Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "container_observer"))
            .model("Container Observer").unloaded(UnloadedPolicy.UNAVAILABLE).minInterval(Duration.ofSeconds(1))
            .field(CONTAINER).field(ITEMS).field(TOTAL).field(DISTINCT).field(SLOTS_USED).field(SLOTS_TOTAL).field(FILL).field(EMPTY).field(FULL)
            .field(STATUS).field(CommonFields.LOCATION)
            // The readings mean something only while it reads a container: unknown in Home Assistant otherwise.
            .entity(Ha.sensor("fill", FILL).name(null).unit("%").stateClass("measurement").valueTemplate(whenRead("value_json.fill")).jsonAttributes(ITEMS))
            .entity(Ha.sensor("total", TOTAL).name("Items").stateClass("measurement").valueTemplate(whenRead("value_json.total")))
            .entity(Ha.sensor("slots_used", SLOTS_USED).stateClass("measurement").valueTemplate(whenRead("value_json.slots_used")))
            .entity(Ha.binarySensor("empty", EMPTY).valueTemplate(whenRead("('ON' if value_json.empty else 'OFF')")))
            .entity(Ha.binarySensor("full", FULL).valueTemplate(whenRead("('ON' if value_json.full else 'OFF')")))
            .entity(Ha.sensor("status", STATUS).diagnostic())
            .entity(Ha.sensor("container", CONTAINER).diagnostic().valueTemplate("{{ value_json.container if value_json.container else None }}"))
            .entity(Ha.sensor("location", CommonFields.LOCATION).name("Location").diagnostic().disabledByDefault())
            .build();

    private ContainerObserver() {
    }

    // A value template that gives the expression while the observer reads its container, else None (unknown).
    private static String whenRead(String expression) {
        return "{{ " + expression + " if value_json.status == 'ok' else None }}";
    }

    /**
     * Whether it may read the container in front (the Block Placer's rule for the container behind it,
     * {@link PlacerRules#claimAllows}): without a claim mod always; with one, when the observer and the container share
     * one claim, or its owner is online and may open containers there. An offline or unknown owner cannot be asked, so a
     * container across a claim line stays unread while they are away.
     *
     * @param ownerMayOpen whether the owner is online and may open the container; false for an offline owner or none
     */
    public static boolean mayRead(boolean claimMod, boolean sameClaim, boolean ownerMayOpen) {
        return PlacerRules.claimAllows(claimMod, sameClaim, ownerMayOpen);
    }

    /**
     * Reads every slot of a container: the items by id (the most numerous {@value #MAX_ITEMS}, within
     * {@value #MAX_ITEMS_BYTES} bytes of ids), the totals, and the fill as a comparator measures it (each stack's share
     * of what its slot holds at most).
     *
     * @param containerId its id, as {@link #CONTAINER} reports it
     */
    public static Scan scan(Container container, String containerId) {
        Map<String, Long> counts = new HashMap<>();
        int size = Math.max(0, container.getContainerSize());
        int used = 0;
        int fullSlots = 0;
        double fraction = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            used++;
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
            int max = Math.max(1, Math.min(container.getMaxStackSize(), stack.getMaxStackSize()));
            fraction += Math.min(1.0, stack.getCount() / (double) max);
            if (stack.getCount() >= max) fullSlots++;
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new Scan(Status.OK, containerId, listed(counts), clampInt(total), counts.size(), used, size, fillPercent(size == 0 ? 0 : fraction / size),
                total == 0, size > 0 && fullSlots == size);
    }

    /**
     * The items {@link #ITEMS} lists of all counted: the most numerous first (equal counts by id), at most
     * {@value #MAX_ITEMS}, while their ids fit {@value #MAX_ITEMS_BYTES} bytes; sorted by id, so the same contents always
     * give the same state.
     */
    public static Map<String, Integer> listed(Map<String, Long> counts) {
        List<Map.Entry<String, Long>> ranked = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .toList();
        TreeMap<String, Integer> listed = new TreeMap<>();
        int bytes = 0;
        for (Map.Entry<String, Long> entry : ranked) {
            if (listed.size() == MAX_ITEMS) break;
            bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length + ENTRY_OVERHEAD;
            if (bytes > MAX_ITEMS_BYTES) break;
            listed.put(entry.getKey(), clampInt(entry.getValue()));
        }
        return Collections.unmodifiableMap(listed);
    }

    /**
     * A fill fraction as the percentage {@link #FILL} reports, to one decimal: 0 only when empty and 100 only when full,
     * so a single item in a large chest still shows 0.1 and a nearly full one 99.9.
     */
    public static double fillPercent(double fraction) {
        if (!(fraction > 0)) return 0;
        if (fraction >= 1) return 100;
        double percent = Math.round(fraction * 1000) / 10.0;
        return Math.clamp(percent, 0.1, 99.9);
    }

    private static int clampInt(long value) {
        return (int) Math.clamp(value, 0, Integer.MAX_VALUE);
    }

    /**
     * What the observer saw at its last look.
     *
     * @param container the id of the container; empty when there is none
     * @param items     the items {@link #listed} lists
     * @param distinct  how many different items there are, also those {@code items} leaves out
     * @param fill      the percentage {@link #fillPercent} gives
     */
    public record Scan(Status status, String container, Map<String, Integer> items, int total, int distinct, int slotsUsed, int slotsTotal,
                       double fill, boolean empty, boolean full) {
        /** Before the first look since it loaded. */
        public static final Scan WAITING = idle(Status.WAITING, "");
        /** Nothing readable in front. */
        public static final Scan NO_CONTAINER = idle(Status.NO_CONTAINER, "");

        public Scan {
            container = ValueCodecs.sanitizeText(container);
            items = Collections.unmodifiableMap(new TreeMap<>(items));
        }

        /**
         * A container its owner may not open: its id (the block is in plain sight) and nothing of what it holds.
         */
        public static Scan claimed(String container) {
            return idle(Status.CLAIMED, container);
        }

        private static Scan idle(Status status, String container) {
            return new Scan(status, container, Map.of(), 0, 0, 0, 0, 0, false, false);
        }

        /** Writes every readable field but the location. */
        public StateWriter write(StateWriter out) {
            return out.put(CONTAINER, container).put(ITEMS, items).put(TOTAL, total).put(DISTINCT, distinct).put(SLOTS_USED, slotsUsed)
                    .put(SLOTS_TOTAL, slotsTotal).put(FILL, fill).put(EMPTY, empty).put(FULL, full).put(STATUS, status);
        }
    }
}

package art.rehra.mineqtt.device;

import art.rehra.mineqtt.CheckItems;
import art.rehra.mineqtt.device.types.ContainerObserver;
import art.rehra.mineqtt.device.types.ContainerObserver.Scan;
import art.rehra.mineqtt.device.types.ContainerObserver.Status;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Container Observer, run by Gradle's verifyContainerObserver task: what a look at a container reports (the items by
 * id only, the totals, the fill as a comparator measures it, empty and full), the cap on the items it lists and the bytes
 * they take, the same state for the same contents, and the privacy rule: a container its owner may not open is reported
 * by its id alone.
 */
public final class ContainerObserverCheck {
    private static final String CHEST = "minecraft:chest";

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Every item a stack of 64, as the common components give it; a slot's own maximum is the container's here.
        CheckItems.bindCommonComponents(BuiltInRegistries.ITEM);
        emptyChest();
        contents();
        fullAndNearlyFull();
        smallSlots();
        fillPercent();
        sameContentsSameState();
        cap();
        byteBudget();
        privacy();
        idleStates();
        System.out.println("Container Observer checks passed.");
    }

    private static void emptyChest() {
        Scan scan = ContainerObserver.scan(new SimpleContainer(27), CHEST);
        check(scan.status() == Status.OK && scan.container().equals(CHEST) && scan.items().isEmpty() && scan.total() == 0 && scan.distinct() == 0
                        && scan.slotsUsed() == 0 && scan.slotsTotal() == 27 && scan.fill() == 0 && scan.empty() && !scan.full(),
                "An empty chest: read, nothing in it, 27 slots, empty and not full: " + scan);
        JsonObject state = state(scan);
        check(state.get("status").getAsString().equals("ok") && state.getAsJsonObject("items").isEmpty() && state.get("empty").getAsBoolean(),
                "Its state says so: " + state);
    }

    /**
     * Stacks of one item add up across slots; each item counts by id only, so a renamed stack's name never reaches the
     * state; the fill is each stack's share of a full stack.
     */
    private static void contents() {
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.STONE, 64));
        chest.setItem(5, new ItemStack(Items.STONE, 10));
        chest.setItem(7, new ItemStack(Items.DIAMOND, 3));
        chest.setItem(9, new ItemStack(Items.OAK_LOG, 8));
        ItemStack secret = new ItemStack(Items.PAPER, 2);
        secret.set(DataComponents.CUSTOM_NAME, Component.literal("Secret base at 120 64 -300"));
        chest.setItem(26, secret);
        Scan scan = ContainerObserver.scan(chest, CHEST);
        check(scan.items().equals(Map.of("minecraft:stone", 74, "minecraft:diamond", 3, "minecraft:oak_log", 8, "minecraft:paper", 2)),
                "Counted by item id, stacks of one item added up: " + scan.items());
        check(List.copyOf(scan.items().keySet()).equals(List.of("minecraft:diamond", "minecraft:oak_log", "minecraft:paper", "minecraft:stone")),
                "The items are sorted by id: " + scan.items().keySet());
        check(scan.total() == 87 && scan.distinct() == 4 && scan.slotsUsed() == 5 && scan.slotsTotal() == 27 && !scan.empty() && !scan.full(),
                "87 items of 4 kinds in 5 of 27 slots: " + scan);
        // Stone 64/64 and 10/64, diamonds 3/64, logs 8/64, paper 2/64.
        double fraction = (1 + 10 / 64.0 + 3 / 64.0 + 8 / 64.0 + 2 / 64.0) / 27;
        check(scan.fill() == Math.round(fraction * 1000) / 10.0, "The fill is the comparator's measure, to one decimal: " + scan.fill() + " for " + fraction);
        String json = state(scan).toString();
        check(!json.contains("Secret") && !json.contains("custom_name") && !json.contains("120 64"), "A stack's name never reaches the state: " + json);
    }

    private static void fullAndNearlyFull() {
        SimpleContainer hopper = new SimpleContainer(5);
        for (int slot = 0; slot < 5; slot++) hopper.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        Scan full = ContainerObserver.scan(hopper, "minecraft:hopper");
        check(full.full() && full.fill() == 100 && !full.empty() && full.total() == 320 && full.distinct() == 1, "Every slot holds a full stack: full, 100 %: " + full);
        hopper.setItem(4, new ItemStack(Items.COBBLESTONE, 63));
        Scan nearly = ContainerObserver.scan(hopper, "minecraft:hopper");
        check(!nearly.full() && nearly.fill() == 99.7, "One item short: not full, 99.7 %: " + nearly);
        SimpleContainer chest = new SimpleContainer(54);
        for (int slot = 0; slot < 54; slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(53, new ItemStack(Items.COBBLESTONE, 63));
        check(ContainerObserver.scan(chest, CHEST).fill() == 99.9, "Nearly full never reads 100: " + ContainerObserver.scan(chest, CHEST).fill());
        SimpleContainer single = new SimpleContainer(54);
        single.setItem(30, new ItemStack(Items.COBBLESTONE));
        Scan one = ContainerObserver.scan(single, CHEST);
        check(one.fill() == 0.1 && !one.empty() && one.total() == 1, "A single item never reads 0: " + one);
        Scan none = ContainerObserver.scan(new SimpleContainer(0), "minecraft:crafter");
        check(none.slotsTotal() == 0 && none.fill() == 0 && none.empty() && !none.full(), "A container without slots is empty, never full: " + none);
    }

    /**
     * A container whose slots hold one item each (a jukebox, a brewing stand's bottle slots) is full with one per slot: a
     * stack's share is of the smaller of its own maximum and the container's.
     */
    private static void smallSlots() {
        SimpleContainer single = new SimpleContainer(2) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
        single.setItem(0, new ItemStack(Items.MUSIC_DISC_CAT));
        single.setItem(1, new ItemStack(Items.STONE));
        Scan scan = ContainerObserver.scan(single, "minecraft:jukebox");
        check(scan.full() && scan.fill() == 100, "One item fills a slot of one: " + scan);
    }

    private static void fillPercent() {
        check(ContainerObserver.fillPercent(0) == 0 && ContainerObserver.fillPercent(-1) == 0 && ContainerObserver.fillPercent(Double.NaN) == 0,
                "Nothing, or nonsense, is 0");
        check(ContainerObserver.fillPercent(1) == 100 && ContainerObserver.fillPercent(1.5) == 100, "Full is 100");
        check(ContainerObserver.fillPercent(0.0001) == 0.1 && ContainerObserver.fillPercent(0.9999) == 99.9 && ContainerObserver.fillPercent(0.4567) == 45.7,
                "In between: rounded to one decimal, never 0 or 100");
    }

    /** The same contents in other slots give the same state, byte for byte, so a rearranged chest publishes nothing. */
    private static void sameContentsSameState() {
        SimpleContainer a = new SimpleContainer(27);
        SimpleContainer b = new SimpleContainer(27);
        List<Item> items = List.of(Items.STONE, Items.DIRT, Items.OAK_LOG, Items.IRON_INGOT, Items.TORCH);
        for (int i = 0; i < items.size(); i++) {
            a.setItem(i, new ItemStack(items.get(i), i + 1));
            b.setItem(26 - i * 3, new ItemStack(items.get(i), i + 1));
        }
        Scan first = ContainerObserver.scan(a, CHEST);
        Scan second = ContainerObserver.scan(b, CHEST);
        check(first.equals(second) && state(first).toString().equals(state(second).toString()), "Rearranged, the same state: " + state(first));
    }

    /** At most 64 item ids, the most numerous; equal counts by id; how many there are is still told. */
    private static void cap() {
        Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < 100; i++) counts.put(String.format("mod:item_%03d", i), (long) i);
        Map<String, Integer> listed = ContainerObserver.listed(counts);
        check(listed.size() == ContainerObserver.MAX_ITEMS && listed.containsKey("mod:item_099") && listed.containsKey("mod:item_036")
                && !listed.containsKey("mod:item_035"), "The 64 most numerous are listed: " + listed.keySet());
        counts.put("mod:a_tie", 36L);
        Map<String, Integer> tie = ContainerObserver.listed(counts);
        check(tie.containsKey("mod:a_tie") && !tie.containsKey("mod:item_036"), "Equal counts: the lower id first");
        check(ContainerObserver.listed(Map.of("mod:huge", 5_000_000_000L)).get("mod:huge") == Integer.MAX_VALUE, "A count beyond an int is capped");

        SimpleContainer big = new SimpleContainer(120);
        int distinct = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || distinct == 120) continue;
            big.setItem(distinct++, new ItemStack(item));
        }
        Scan scan = ContainerObserver.scan(big, "examplemod:crate");
        check(scan.items().size() == ContainerObserver.MAX_ITEMS && scan.distinct() == 120 && scan.total() == 120 && scan.slotsUsed() == 120,
                "A large modded container lists 64 ids and tells there are 120: " + scan.items().size() + " of " + scan.distinct());
    }

    /** Long ids stop the list at its byte budget, so the largest state still fits a device's state. */
    private static void byteBudget() {
        Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < 200; i++) counts.put("examplemod:" + "x".repeat(240) + String.format("%03d", i), (long) Integer.MAX_VALUE);
        Map<String, Integer> listed = ContainerObserver.listed(counts);
        int bytes = listed.keySet().stream().mapToInt(id -> id.getBytes(StandardCharsets.UTF_8).length + 14).sum();
        check(!listed.isEmpty() && listed.size() < ContainerObserver.MAX_ITEMS && bytes <= ContainerObserver.MAX_ITEMS_BYTES,
                "Long ids stop at the byte budget: " + listed.size() + " ids, " + bytes + " bytes");
        Scan worst = new Scan(Status.OK, "examplemod:" + "c".repeat(300), listed, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                99.9, false, false);
        int stateBytes = state(worst).toString().getBytes(StandardCharsets.UTF_8).length;
        // The location takes well under 1 KiB besides.
        check(stateBytes + 1024 <= DeviceView.MAX_STATE_BYTES, "The largest state fits what a device may publish: " + stateBytes + " bytes");
    }

    /**
     * The Block Placer's claim rule: without a claim mod it always reads; with one, only in its own claim or for an owner
     * online who may open the container there. Refused, it reports the container's id and nothing it holds.
     */
    private static void privacy() {
        check(ContainerObserver.mayRead(false, false, false), "Without a claim mod it reads, as everywhere in MineQTT");
        check(ContainerObserver.mayRead(true, true, false), "In its own claim it reads, with its owner offline too");
        check(ContainerObserver.mayRead(true, false, true), "Across a claim line only for an owner online who may open the container");
        check(!ContainerObserver.mayRead(true, false, false), "...and never for an owner offline, unknown or not allowed");
        for (boolean claimMod : List.of(true, false)) {
            for (boolean sameClaim : List.of(true, false)) {
                for (boolean mayOpen : List.of(true, false)) {
                    check(ContainerObserver.mayRead(claimMod, sameClaim, mayOpen) == PlacerRules.claimAllows(claimMod, sameClaim, mayOpen),
                            "The same rule as the Block Placer's container behind it");
                }
            }
        }
        Scan claimed = Scan.claimed(CHEST);
        JsonObject state = state(claimed);
        check(state.get("status").getAsString().equals("claimed") && state.get("container").getAsString().equals(CHEST) && state.getAsJsonObject("items").isEmpty()
                        && state.get("total").getAsInt() == 0 && state.get("distinct").getAsInt() == 0 && state.get("slots_used").getAsInt() == 0
                        && state.get("slots_total").getAsInt() == 0 && state.get("fill").getAsDouble() == 0 && !state.get("empty").getAsBoolean()
                        && !state.get("full").getAsBoolean(), "A refused container: its id and nothing of what it holds: " + state);
    }

    private static void idleStates() {
        JsonObject waiting = state(Scan.WAITING);
        JsonObject none = state(Scan.NO_CONTAINER);
        check(waiting.get("status").getAsString().equals("waiting") && waiting.get("container").getAsString().isEmpty() && waiting.getAsJsonObject("items").isEmpty(),
                "Before its first look: waiting, nothing read: " + waiting);
        check(none.get("status").getAsString().equals("no_container") && none.get("container").getAsString().isEmpty() && !none.get("empty").getAsBoolean(),
                "Nothing in front: no container, and not empty, which Home Assistant shows as unknown: " + none);
        check(ContainerObserver.TYPE.fields().stream().map(Field::key).toList().equals(List.of("container", "items", "total", "distinct",
                "slots_used", "slots_total", "fill", "empty", "full", "status", "location")), "Its fields, in order");
    }

    private static JsonObject state(Scan scan) {
        return scan.write(new StateWriter()).json();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

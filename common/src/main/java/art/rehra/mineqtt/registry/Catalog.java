package art.rehra.mineqtt.registry;

import art.rehra.mineqtt.MineQTT;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Every MineQTT block and item (M2 plan D8, design §7): whether survival players can craft it, whether the creative tab
 * shows it, and which retiring blocks convert into a successor or are decommissioned. The order is the creative tab's.
 * <p>
 * A plain table of ids that references no {@code RegistrySupplier}, so the checks read it under a bare bootstrap without
 * running the mod's registration. {@code MineQTTTabs} resolves the ids at runtime; {@code verifyCatalog} checks that every
 * entry has its assets, loot table, lang keys and recipes, and that no recipe makes a retiring block.
 */
public final class Catalog {
    /** A block entry is a block with its block item; an item entry is a plain item. */
    public enum Kind {
        BLOCK,
        ITEM
    }

    public enum Status {
        /** Survival players craft it: the Network Chip from vanilla items, everything else behind the chip. */
        CRAFTABLE,
        /**
         * Retiring into a successor (design §9.5): it has no recipe of its own, and only a shapeless 1:1 recipe may use
         * it, which turns it into its successor.
         */
        CONVERTER,
        /**
         * Retiring for good (design §9.5): no recipe makes or uses it, the creative tab does not show it, and breaking it
         * gives the Network Chip back.
         */
        DECOMMISSIONED,
        /**
         * Built but not released yet (M3 plan D18): no recipe makes or uses it, the creative tab does not show it, and
         * breaking it drops it. A block whose client side is missing never reaches survival or creative players; operators
         * can still place it. The Display Panel was one until its renderer landed (M3-15, M3-16); such an entry is
         * {@code new Entry(id, Kind.BLOCK, Status.UNRELEASED, false, null)}.
         */
        UNRELEASED
    }

    /**
     * @param creative  whether the creative tab shows it
     * @param successor the entry a converter turns into ({@code LegacyConverterBlock}); null for every other entry, and
     *                  for a converter whose conversion is not built yet
     */
    public record Entry(Identifier id, Kind kind, Status status, boolean creative, @Nullable Identifier successor) {
        public Entry {
            if (successor != null && status != Status.CONVERTER) throw new IllegalArgumentException(id + " has a successor but is no converter");
        }

        public boolean craftable() {
            return status == Status.CRAFTABLE;
        }

        public boolean converter() {
            return status == Status.CONVERTER;
        }

        public boolean decommissioned() {
            return status == Status.DECOMMISSIONED;
        }

        public boolean unreleased() {
            return status == Status.UNRELEASED;
        }
    }

    public static final List<Entry> ENTRIES = List.of(
            // Redstone I/O
            block("redstone_emitter_block", Status.CRAFTABLE),
            block("redstone_publisher_block", Status.CRAFTABLE),
            block("smart_plug", Status.CRAFTABLE),
            // Lighting
            block("rgb_led_block", Status.CRAFTABLE),
            // Sensors
            block("motion_sensor_block", Status.CRAFTABLE),
            block("temperature_sensor_block", Status.CRAFTABLE),
            block("block_sensor_block", Status.CRAFTABLE),
            block("container_observer", Status.CRAFTABLE),
            block("entity_observer", Status.CRAFTABLE),
            // Controls and displays
            block("light_remote_block", Status.CRAFTABLE),
            block("display_panel", Status.CRAFTABLE),
            // Automation
            block("alarm_clock", Status.CRAFTABLE),
            block("game_time_timer", Status.CRAFTABLE),
            block("cron_timer", Status.CRAFTABLE),
            block("interval_timer", Status.CRAFTABLE),
            block("block_emitter_block", Status.CRAFTABLE),
            // Tooling
            item("cyberdeck", Status.CRAFTABLE),
            block("terminal_block", Status.CRAFTABLE),
            // Component
            item("network_chip", Status.CRAFTABLE),
            // Retiring blocks, all hidden: the converters (M2-19, M2-20) and the decommissioned blocks (M2-21).
            converter("humidity_sensor_block", "temperature_sensor_block"),
            converter("static_publisher", "redstone_publisher_block"),
            decommissioned("dynamic_publisher"),
            decommissioned("topic_relay"));

    private Catalog() {
    }

    public static Optional<Entry> find(Identifier id) {
        return ENTRIES.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    private static Entry block(String path, Status status) {
        return new Entry(id(path), Kind.BLOCK, status, true, null);
    }

    private static Entry item(String path, Status status) {
        return new Entry(id(path), Kind.ITEM, status, true, null);
    }

    /** A converted block: hidden from the creative tab, it turns into {@code successor} when placed, carried or crafted. */
    private static Entry converter(String path, String successor) {
        return new Entry(id(path), Kind.BLOCK, Status.CONVERTER, false, id(successor));
    }

    /** A decommissioned block: hidden from the creative tab, inert, and broken for its parts ({@code DecommissionedBlock}). */
    private static Entry decommissioned(String path) {
        return new Entry(id(path), Kind.BLOCK, Status.DECOMMISSIONED, false, null);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, path);
    }
}

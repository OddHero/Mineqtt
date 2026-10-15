package art.rehra.mineqtt;

import net.minecraft.world.item.Item;
//? if >=26.1 {
import net.minecraft.core.component.DataComponents;
//?}

/** Item setup the bootstrapped checks share. */
public final class CheckItems {
    private CheckItems() {
    }

    /**
     * Gives the items their default components, which a server binds when it loads its data packs; the common ones are
     * enough for the items the checks use. Before 26.1 an item carries its components from its construction on.
     */
    public static void bindCommonComponents(Iterable<Item> items) {
        //? if >=26.1 {
        for (Item item : items) item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        //?}
    }

    public static void bindCommonComponents(Item... items) {
        bindCommonComponents(java.util.List.of(items));
    }
}

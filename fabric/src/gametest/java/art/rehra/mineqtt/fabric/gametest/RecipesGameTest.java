package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.items.MineqttItems;
import art.rehra.mineqtt.registry.Catalog;
import art.rehra.mineqtt.tabs.MineQTTTabs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Every Catalog entry is registered, and the creative tab shows exactly the creative entries in Catalog order (M2 plan
 * D8). Every recipe file under {@code data/mineqtt/recipe/} loads, the legacy {@code recipes/} folder ships nothing, and
 * every crafting recipe matches its own ingredients and no other recipe, vanilla or MineQTT. Then, in survival: a fresh
 * player knows no MineQTT recipe, and an ender pearl unlocks the Network Chip recipe and no other; the player crafts
 * four chips at a crafting table through the recipe book, which unlocks the Redstone Input, and crafts one the same way.
 */
public final class RecipesGameTest implements FabricClientGameTest {
    private static final BlockPos STAND = new BlockPos(0, GameTestSession.GROUND_Y + 1, 0);
    private static final BlockPos TABLE = new BlockPos(0, GameTestSession.GROUND_Y + 1, 2);
    private static final Identifier CHIP_RECIPE = mineqtt("network_chip");
    private static final Identifier INPUT_RECIPE = mineqtt("redstone_publisher_block");
    private static final int UNLOCK_TICKS = 5 * 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "recipes", RecipesGameTest::run);
    }

    private static void run(GameTestSession session) {
        registered(session);
        creativeTab(session);
        recipesLoad(session);
        recipesMatch(session);
        survivalCrafting(session);
    }

    private static void registered(GameTestSession session) {
        for (Catalog.Entry entry : Catalog.ENTRIES) {
            session.check(BuiltInRegistries.ITEM.containsKey(entry.id()), "Catalog item " + entry.id() + " is registered");
            if (entry.kind() == Catalog.Kind.BLOCK) {
                session.check(BuiltInRegistries.BLOCK.containsKey(entry.id()), "Catalog block " + entry.id() + " is registered");
            }
        }
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals(MineQTT.MOD_ID)) session.check(Catalog.find(id).isPresent(), "registered item " + id + " is in the Catalog");
        }
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getNamespace().equals(MineQTT.MOD_ID)) {
                session.check(Catalog.find(id).filter(entry -> entry.kind() == Catalog.Kind.BLOCK).isPresent(), "registered block " + id + " is a Catalog block");
            }
        }
    }

    private static void creativeTab(GameTestSession session) {
        List<Identifier> shown = session.onClient(mc -> {
            CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
            return MineQTTTabs.MINEQTT_TAB.get().getDisplayItems().stream().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())).toList();
        });
        List<Identifier> creative = Catalog.ENTRIES.stream().filter(Catalog.Entry::creative).map(Catalog.Entry::id).toList();
        session.check(shown.equals(creative), "the MineQTT tab shows the creative Catalog entries in order (" + shown + ")");
    }

    private static void recipesLoad(GameTestSession session) {
        Set<Identifier> files = session.onServer(s -> s.getResourceManager()
                .listResources("recipe", id -> id.getNamespace().equals(MineQTT.MOD_ID) && id.getPath().endsWith(".json")).keySet().stream()
                .map(id -> mineqtt(id.getPath().substring("recipe/".length(), id.getPath().length() - ".json".length())))
                .collect(Collectors.toCollection(TreeSet::new)));
        Set<Identifier> loaded = session.onServer(s -> modRecipes(s).stream().map(holder -> holder.id().identifier())
                .collect(Collectors.toCollection(TreeSet::new)));
        session.check(!files.isEmpty() && files.equals(loaded), "every recipe file loads (" + files.size() + " files, " + loaded.size() + " recipes)");
        session.check(session.onServer(s -> s.getResourceManager().listResources("recipes", id -> id.getNamespace().equals(MineQTT.MOD_ID)).isEmpty()),
                "no legacy recipes/ file ships");
        for (Identifier recipe : loaded) {
            session.check(session.onServer(s -> s.getAdvancements().get(mineqtt("recipes/" + recipe.getPath())) != null),
                    "recipe " + recipe + " has its unlock advancement");
        }
    }

    /** Each crafting recipe, fed the first item of each of its ingredients, is the recipe the crafting grid picks. */
    private static void recipesMatch(GameTestSession session) {
        List<String> results = session.onServer(s -> {
            List<String> lines = new ArrayList<>();
            for (RecipeHolder<?> holder : modRecipes(s)) {
                CraftingInput input = holder.value() instanceof CraftingRecipe crafting ? input(crafting) : null;
                if (input == null) {
                    lines.add("FAIL " + holder.id().identifier() + " is no shaped or shapeless crafting recipe");
                    continue;
                }
                Optional<RecipeHolder<CraftingRecipe>> match = s.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, s.overworld());
                Optional<Identifier> picked = match.map(picks -> picks.id().identifier());
                Identifier made = match.map(picks -> BuiltInRegistries.ITEM.getKey(picks.value().assemble(input).getItem())).orElse(null);
                boolean ok = picked.equals(Optional.of(holder.id().identifier())) && made != null && Catalog.find(made).filter(Catalog.Entry::craftable).isPresent();
                lines.add((ok ? "PASS " : "FAIL ") + holder.id().identifier() + " is picked for its own ingredients (" + picked.orElse(null) + ") and makes " + made);
            }
            return lines;
        });
        results.forEach(line -> session.check(line.startsWith("PASS "), line.substring(5)));
    }

    private static void survivalCrafting(GameTestSession session) {
        session.teleport(STAND);
        String player = session.playerName();
        session.consoleCommand("gamemode survival " + player);
        session.consoleCommand("clear " + player);
        session.consoleCommand("setblock " + TABLE.getX() + " " + TABLE.getY() + " " + TABLE.getZ() + " minecraft:crafting_table");
        session.context().waitFor(mc -> !mc.level.getBlockState(TABLE).isAir());
        List<Identifier> known = knownModRecipes(session);
        session.check(known.isEmpty(), "a fresh player knows no MineQTT recipe (" + known + ")");

        session.consoleCommand("give " + player + " minecraft:ender_pearl");
        session.check(awaitKnows(session, CHIP_RECIPE), "an ender pearl unlocks the Network Chip recipe");
        known = knownModRecipes(session);
        session.check(known.equals(List.of(CHIP_RECIPE)), "the ender pearl unlocks no other MineQTT recipe (" + known + ")");

        session.consoleCommand("give " + player + " minecraft:copper_ingot 4");
        session.consoleCommand("give " + player + " minecraft:redstone 2");
        session.consoleCommand("give " + player + " minecraft:quartz 2");
        session.aim(TABLE);
        session.pressUse();
        session.context().waitForScreen(CraftingScreen.class);
        craft(session, CHIP_RECIPE, MineqttItems.NetworkChip.get(), 4);
        session.check(awaitKnows(session, INPUT_RECIPE), "obtaining the Network Chip unlocks the Redstone Input recipe");

        session.consoleCommand("give " + player + " minecraft:smooth_stone 5");
        session.consoleCommand("give " + player + " minecraft:observer");
        session.consoleCommand("give " + player + " minecraft:redstone 2");
        craft(session, INPUT_RECIPE, MineqttItems.RedstonePublisherBlock.get(), 1);
        session.check(session.onServer(s -> session.player(s).getInventory().countItem(MineqttItems.NetworkChip.get()) == 3),
                "the Redstone Input took one of the four chips");
        session.closeScreen();
    }

    /**
     * Fills the open crafting grid as a click in the recipe book does (the server refuses recipes the player does not know),
     * photographs it, and shift-clicks the result into the inventory.
     */
    private static void craft(GameTestSession session, Identifier recipe, Item result, int count) {
        RecipeDisplayId display = session.onServer(s -> {
            List<RecipeDisplayEntry> entries = new ArrayList<>();
            s.getRecipeManager().listDisplaysForRecipe(ResourceKey.create(Registries.RECIPE, recipe), entries::add);
            return entries.getFirst().id();
        });
        int before = session.onServer(s -> session.player(s).getInventory().countItem(result));
        session.context().runOnClient(mc -> mc.gameMode.handlePlaceRecipe(mc.player.containerMenu.containerId, display, false));
        session.awaitServer(s -> session.player(s).containerMenu instanceof CraftingMenu menu && menu.getSlot(0).getItem().is(result));
        session.context().waitFor(mc -> mc.player.containerMenu.getSlot(0).getItem().is(result));
        session.screenshot("recipes_" + recipe.getPath());
        session.context().runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, 0, 0, ContainerInput.QUICK_MOVE, mc.player));
        session.awaitServer(s -> session.player(s).getInventory().countItem(result) >= before + count);
        session.check(session.onServer(s -> session.player(s).getInventory().countItem(result)) == before + count,
                "the crafting table made " + count + " " + BuiltInRegistries.ITEM.getKey(result) + " from recipe " + recipe);
    }

    /** The grid a player fills for the recipe, from the first item of each ingredient; null for other recipe kinds. */
    private static @Nullable CraftingInput input(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            List<ItemStack> grid = shaped.getIngredients().stream().map(ingredient -> ingredient.map(RecipesGameTest::firstItem).orElse(ItemStack.EMPTY)).toList();
            return CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<ItemStack> items = shapeless.placementInfo().ingredients().stream().map(RecipesGameTest::firstItem).toList();
            return CraftingInput.of(items.size(), 1, items);
        }
        return null;
    }

    private static ItemStack firstItem(Ingredient ingredient) {
        return ingredient.items().findFirst().map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static List<RecipeHolder<?>> modRecipes(MinecraftServer server) {
        return server.getRecipeManager().getRecipes().stream().filter(holder -> holder.id().identifier().getNamespace().equals(MineQTT.MOD_ID)).toList();
    }

    /** Waits a few seconds for the unlock advancement to give the player the recipe; returns whether it did. */
    private static boolean awaitKnows(GameTestSession session, Identifier recipe) {
        for (int tick = 0; tick < UNLOCK_TICKS && !knows(session, recipe); tick++) {
            session.context().waitTick();
        }
        return knows(session, recipe);
    }

    /** The MineQTT recipes the player knows. */
    private static List<Identifier> knownModRecipes(GameTestSession session) {
        return session.onServer(s -> modRecipes(s).stream().map(holder -> holder.id().identifier()).filter(recipe -> knows(session, s, recipe)).toList());
    }

    private static boolean knows(GameTestSession session, Identifier recipe) {
        return session.onServer(s -> knows(session, s, recipe));
    }

    private static boolean knows(GameTestSession session, MinecraftServer server, Identifier recipe) {
        return session.player(server).getRecipeBook().contains(ResourceKey.create(Registries.RECIPE, recipe));
    }

    private static Identifier mineqtt(String path) {
        return Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, path);
    }
}

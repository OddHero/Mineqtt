package art.rehra.mineqtt.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Catalog and the data behind it (M2 plan D8), run by Gradle's verifyCatalog task on the processed resources folder
 * it passes as the only argument: the data as the jar of the Minecraft version carries it. processResources rebuilds that
 * folder from scratch, so a file deleted from the sources cannot survive in it.
 * <ul>
 *     <li>Every entry has its item model definition, the models and textures they reach, and its lang keys; a block also
 *     its blockstate and a loot table that parses and drops only vanilla items and Catalog entries; a decommissioned
 *     block's drops the Network Chip alone, and the creative tab does not show it. Every blockstate, item model
 *     definition, loot table and block or item name in the lang file belongs to an entry.</li>
 *     <li>Every recipe parses with the vanilla codec after its {@code mineqtt:} ids are replaced by {@code minecraft:stone}
 *     (mod items are not registered under a bare bootstrap), names only vanilla items that exist and Catalog entries, and
 *     makes a craftable entry. Every craftable entry has a recipe; no recipe makes a converter, a decommissioned or an
 *     unreleased block, a converter is used only by a shapeless 1:1 recipe, and a decommissioned or an unreleased block by
 *     none; an unreleased block is hidden from the creative tab and drops itself. A converter with a successor
 *     is hidden from the creative tab and has exactly one shapeless 1:1 recipe, into its craftable successor.</li>
 *     <li>Every recipe is unlocked by an advancement that parses and whose criteria name only vanilla items, Catalog
 *     entries and MineQTT recipes that exist; a recipe that takes the Network Chip unlocks when the player obtains the
 *     chip, so every requirement group of its advancement holds once the player has a chip.</li>
 *     <li>The legacy {@code data/mineqtt/recipes/} folder, which 26.2 never loads, is gone.</li>
 * </ul>
 * Every problem is collected, so one run lists them all.
 */
public final class CatalogCheck {
    private static final String NAMESPACE = "mineqtt";
    private static final String CHIP = "mineqtt:network_chip";
    private static final String STAND_IN = "minecraft:stone";
    private static final Pattern NAME_KEY = Pattern.compile("(block|item)\\.mineqtt\\.[a-z0-9_]+");

    private static final List<String> problems = new ArrayList<>();
    private static Path resources;
    private static RegistryOps<JsonElement> ops;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: CatalogCheck <resources folder>");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        resources = Path.of(args[0]);
        ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        table();
        assets();
        Map<String, JsonObject> recipes = recipes();
        advancements(recipes);
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " catalog problem(s):\n  " + String.join("\n  ", problems));
        }
        System.out.println("Catalog checks passed: " + Catalog.ENTRIES.size() + " entries, " + recipes.size() + " recipes.");
    }

    /** Each entry once, in the mineqtt namespace; decommissioned and unreleased blocks are hidden from the creative tab. */
    private static void table() {
        Set<Identifier> seen = new HashSet<>();
        for (Catalog.Entry entry : Catalog.ENTRIES) {
            expect(seen.add(entry.id()), "Catalog lists " + entry.id() + " twice");
            expect(entry.id().getNamespace().equals(NAMESPACE), "Catalog entry " + entry.id() + " is not in the mineqtt namespace");
            expect(!entry.decommissioned() || !entry.creative(), "The decommissioned " + entry.id() + " is still in the creative tab");
            expect(!entry.unreleased() || !entry.creative() && entry.kind() == Catalog.Kind.BLOCK,
                    "The unreleased " + entry.id() + " must be a block hidden from the creative tab");
        }
        expect(Catalog.find(Identifier.parse(CHIP)).filter(Catalog.Entry::craftable).isPresent(), "The Network Chip is a craftable entry");
    }

    // ===== Assets, loot tables and lang =====

    private static void assets() throws IOException {
        Path assets = resources.resolve("assets").resolve(NAMESPACE);
        JsonObject lang = read(assets.resolve("lang/en_us.json")).getAsJsonObject();
        for (Catalog.Entry entry : Catalog.ENTRIES) {
            String path = entry.id().getPath();
            Path itemModel = assets.resolve("items/" + path + ".json");
            if (expect(Files.isRegularFile(itemModel), entry.id() + " has no item model definition items/" + path + ".json")) {
                models(read(itemModel), "items/" + path + ".json");
            }
            expect(lang.has("item.mineqtt." + path), entry.id() + " has no lang key item.mineqtt." + path);
            if (entry.kind() == Catalog.Kind.BLOCK) {
                Path blockstate = assets.resolve("blockstates/" + path + ".json");
                if (expect(Files.isRegularFile(blockstate), entry.id() + " has no blockstate blockstates/" + path + ".json")) {
                    models(read(blockstate), "blockstates/" + path + ".json");
                }
                Path lootTable = resources.resolve("data/mineqtt/loot_table/blocks/" + path + ".json");
                if (expect(Files.isRegularFile(lootTable), entry.id() + " has no loot table loot_table/blocks/" + path + ".json")) {
                    JsonElement table = read(lootTable);
                    List<String> drops = new ArrayList<>();
                    lootItems(table, drops::add);
                    drops.forEach(item -> itemExists(item, "The loot table of " + entry.id()));
                    parse(LootTable.DIRECT_CODEC, table, "loot table of " + entry.id());
                    // A decommissioned block gives its part back, not itself, which could only be placed again.
                    if (entry.decommissioned()) {
                        expect(drops.equals(List.of(CHIP)), "The decommissioned " + entry.id() + " must drop the Network Chip alone, drops " + drops);
                    }
                    // An unreleased block, which no recipe makes, gives itself back to whoever placed it.
                    if (entry.unreleased()) {
                        expect(drops.equals(List.of(entry.id().toString())), "The unreleased " + entry.id() + " must drop itself alone, drops " + drops);
                    }
                }
                expect(lang.has("block.mineqtt." + path), entry.id() + " has no lang key block.mineqtt." + path);
            }
        }
        // Nothing outside the Catalog: a block or item that exists only as data would never be checked.
        everyFileIsAnEntry(assets.resolve("blockstates"), Catalog.Kind.BLOCK, "blockstate");
        everyFileIsAnEntry(assets.resolve("items"), null, "item model definition");
        everyFileIsAnEntry(resources.resolve("data/mineqtt/loot_table/blocks"), Catalog.Kind.BLOCK, "block loot table");
        for (String key : lang.keySet()) {
            if (!NAME_KEY.matcher(key).matches()) continue;
            Catalog.Kind kind = key.startsWith("block.") ? Catalog.Kind.BLOCK : null;
            String path = key.substring(key.lastIndexOf('.') + 1);
            expect(isEntry(path, kind), "Lang key " + key + " names no " + (kind == null ? "" : "block ") + "Catalog entry");
        }
    }

    private static void everyFileIsAnEntry(Path folder, Catalog.Kind kind, String what) throws IOException {
        for (Path file : jsonFiles(folder)) {
            String path = baseName(file);
            expect(isEntry(path, kind), "The " + what + " " + file.getFileName() + " belongs to no " + (kind == null ? "" : "block ") + "Catalog entry");
        }
    }

    /** Whether {@code mineqtt:<path>} is an entry, of {@code kind} unless it is null. */
    private static boolean isEntry(String path, Catalog.Kind kind) {
        return Catalog.find(Identifier.fromNamespaceAndPath(NAMESPACE, path)).filter(entry -> kind == null || entry.kind() == kind).isPresent();
    }

    /** Every model a blockstate or item model definition names exists, and so does every model and texture it reaches. */
    private static void models(JsonElement definition, String source) throws IOException {
        List<String> models = new ArrayList<>();
        collectStrings(definition, "model", models::add);
        expect(!models.isEmpty(), source + " names no model");
        for (String model : models) {
            model(model, source, new HashSet<>());
        }
    }

    private static void model(String model, String source, Set<String> visited) throws IOException {
        Identifier id = Identifier.parse(model);
        // builtin/generated and the like are made by the model loader, not read from a file.
        if (id.getPath().startsWith("builtin/") || !visited.add(model)) return;
        String file = "assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json";
        JsonElement json = resource(file);
        if (!expect(json != null, source + " reaches the missing model " + model)) return;
        JsonObject object = json.getAsJsonObject();
        if (object.has("parent")) model(object.get("parent").getAsString(), model, visited);
        if (object.has("textures")) {
            for (Map.Entry<String, JsonElement> texture : object.getAsJsonObject("textures").entrySet()) {
                String value = texture.getValue().getAsString();
                if (value.startsWith("#")) continue;
                Identifier textureId = Identifier.parse(value);
                String png = "assets/" + textureId.getNamespace() + "/textures/" + textureId.getPath() + ".png";
                expect(exists(png), "Model " + model + " uses the missing texture " + value);
            }
        }
    }

    // ===== Recipes =====

    /** Checks every recipe and returns them by recipe id. */
    private static Map<String, JsonObject> recipes() throws IOException {
        expect(Files.notExists(resources.resolve("data/mineqtt/recipes")),
                "The legacy data/mineqtt/recipes/ folder is back; 26.2 loads recipes only from data/mineqtt/recipe/");
        Map<String, JsonObject> recipes = new TreeMap<>();
        Set<Identifier> made = new HashSet<>();
        // What each converter's 1:1 recipes make.
        Map<Identifier, List<Identifier>> conversions = new HashMap<>();
        for (Path file : jsonFiles(resources.resolve("data/mineqtt/recipe"))) {
            String id = NAMESPACE + ":" + baseName(file);
            JsonObject recipe = read(file).getAsJsonObject();
            recipes.put(id, recipe);
            parse(Recipe.CODEC, recipe, "recipe " + id);

            String type = recipe.has("type") ? recipe.get("type").getAsString() : "";
            List<String> ingredients = ingredients(recipe);
            for (String ingredient : ingredients) {
                ingredient(id, type, ingredients, ingredient);
            }
            JsonObject result = recipe.getAsJsonObject("result");
            if (!expect(result != null && result.has("id"), "Recipe " + id + " has no result id")) continue;
            Identifier resultId = Identifier.parse(result.get("id").getAsString());
            Catalog.Entry entry = Catalog.find(resultId).orElse(null);
            if (expect(entry != null, "Recipe " + id + " makes " + resultId + ", which is no Catalog entry")) {
                expect(entry.craftable(), "Recipe " + id + " makes " + resultId + ", which is " + entry.status() + ", not craftable");
                made.add(resultId);
            }
            if (ingredients.size() == 1) {
                Catalog.find(Identifier.tryParse(ingredients.getFirst())).filter(Catalog.Entry::converter)
                        .ifPresent(converter -> conversions.computeIfAbsent(converter.id(), key -> new ArrayList<>()).add(resultId));
            }
        }
        for (Catalog.Entry entry : Catalog.ENTRIES) {
            if (entry.craftable()) expect(made.contains(entry.id()), "Craftable " + entry.id() + " has no recipe");
            if (entry.successor() == null) continue;
            // A converted block leaves the creative tab; items in containers, which no inventory tick reaches, convert
            // through one 1:1 recipe into the successor.
            expect(!entry.creative(), "The converter " + entry.id() + " is still in the creative tab");
            expect(Catalog.find(entry.successor()).filter(Catalog.Entry::craftable).isPresent(),
                    "The converter " + entry.id() + " converts into " + entry.successor() + ", which is no craftable entry");
            expect(conversions.getOrDefault(entry.id(), List.of()).equals(List.of(entry.successor())),
                    "The converter " + entry.id() + " needs exactly one shapeless 1:1 recipe into " + entry.successor() + ", has "
                            + conversions.getOrDefault(entry.id(), List.of()));
        }
        return recipes;
    }

    private static void ingredient(String recipe, String type, List<String> ingredients, String ingredient) {
        if (!itemExists(ingredient, "Recipe " + recipe)) return;
        Catalog.find(Identifier.parse(ingredient)).ifPresent(entry -> {
            expect(!entry.decommissioned(), "Recipe " + recipe + " uses the decommissioned " + entry.id());
            expect(!entry.unreleased(), "Recipe " + recipe + " uses the unreleased " + entry.id());
            if (entry.converter()) {
                expect(type.equals("minecraft:crafting_shapeless") && ingredients.size() == 1,
                        "Recipe " + recipe + " uses the converter " + entry.id() + " other than as the one ingredient of a shapeless recipe");
            }
        });
    }

    /**
     * Whether an item id that {@code user} names is a vanilla item or a Catalog entry. The codec parse cannot tell, since
     * it sees every {@code mineqtt:} id as stone.
     */
    private static boolean itemExists(String item, String user) {
        if (!expect(!item.startsWith("#"), user + " uses the tag " + item
                + "; tags are not loaded under a bare bootstrap, so this check could not prove it exists")) {
            return false;
        }
        Identifier id = Identifier.parse(item);
        if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return expect(BuiltInRegistries.ITEM.containsKey(id), user + " uses " + id + ", which is no item");
        }
        return expect(Catalog.find(id).isPresent(), user + " uses " + id + ", which is no Catalog entry");
    }

    /** Every ingredient id a crafting, cooking or stonecutting recipe names, in order. */
    private static List<String> ingredients(JsonObject recipe) {
        List<String> ids = new ArrayList<>();
        Consumer<JsonElement> add = ingredient -> {
            if (ingredient.isJsonArray()) {
                ingredient.getAsJsonArray().forEach(alternative -> ids.add(ingredientId(alternative)));
            } else {
                ids.add(ingredientId(ingredient));
            }
        };
        if (recipe.has("key")) recipe.getAsJsonObject("key").entrySet().forEach(key -> add.accept(key.getValue()));
        if (recipe.has("ingredients")) recipe.getAsJsonArray("ingredients").forEach(add);
        if (recipe.has("ingredient")) add.accept(recipe.get("ingredient"));
        return ids;
    }

    // An ingredient by its id ("minecraft:clock", "#minecraft:planks"), or before 1.21.2 as {"item": ...} or {"tag": ...}.
    private static String ingredientId(JsonElement ingredient) {
        if (!ingredient.isJsonObject()) return ingredient.getAsString();
        JsonObject object = ingredient.getAsJsonObject();
        return object.has("tag") ? "#" + object.get("tag").getAsString() : object.get("item").getAsString();
    }

    // ===== Recipe advancements =====

    private static void advancements(Map<String, JsonObject> recipes) throws IOException {
        Map<String, List<JsonObject>> unlockedBy = new LinkedHashMap<>();
        for (Path file : jsonFiles(resources.resolve("data/mineqtt/advancement/recipes"))) {
            String id = NAMESPACE + ":recipes/" + baseName(file);
            JsonObject advancement = read(file).getAsJsonObject();
            JsonObject criteria = advancement.getAsJsonObject("criteria");
            collectStrings(criteria, "items", item -> itemExists(item, "Advancement " + id));
            Consumer<String> knownRecipe = recipe -> expect(!recipe.startsWith(NAMESPACE + ":") || recipes.containsKey(recipe),
                    "Advancement " + id + " waits for the unknown recipe " + recipe);
            // recipe_unlocked names its recipe in "recipe", from 26.3 on in "recipes".
            collectStrings(criteria, "recipe", knownRecipe);
            collectStrings(criteria, "recipes", knownRecipe);
            //? if >=26.3 {
            /*// 26.3 resolves the recipes of recipe_unlocked in the recipe registry, which only a loaded world has; the rest of
            // the advancement still parses.
            parse(Advancement.CODEC, withoutRecipeUnlocked(advancement), "advancement " + id);
            *///?} else {
            parse(Advancement.CODEC, advancement, "advancement " + id);
            //?}
            JsonObject rewards = advancement.getAsJsonObject("rewards");
            JsonArray rewarded = rewards == null ? null : rewards.getAsJsonArray("recipes");
            if (!expect(rewarded != null && !rewarded.isEmpty(), "Advancement " + id + " unlocks no recipe")) continue;
            for (JsonElement recipe : rewarded) {
                expect(recipes.containsKey(recipe.getAsString()), "Advancement " + id + " unlocks the unknown recipe " + recipe.getAsString());
                unlockedBy.computeIfAbsent(recipe.getAsString(), key -> new ArrayList<>()).add(advancement);
            }
        }
        recipes.forEach((id, recipe) -> {
            List<JsonObject> advancements = unlockedBy.getOrDefault(id, List.of());
            if (!expect(!advancements.isEmpty(), "No advancement unlocks recipe " + id)) return;
            if (ingredients(recipe).contains(CHIP)) {
                expect(advancements.stream().anyMatch(CatalogCheck::completesOnChip), "Recipe " + id + " takes the Network Chip but does not unlock on obtaining it");
            }
        });
    }

    // The advancement without its recipe_unlocked criteria, also in the requirements.
    private static JsonObject withoutRecipeUnlocked(JsonObject advancement) {
        JsonObject copy = advancement.deepCopy();
        JsonObject criteria = copy.getAsJsonObject("criteria");
        List<String> removed = new ArrayList<>();
        for (String name : List.copyOf(criteria.keySet())) {
            if (criteria.getAsJsonObject(name).get("trigger").getAsString().equals("minecraft:recipe_unlocked")) {
                criteria.remove(name);
                removed.add(name);
            }
        }
        if (copy.has("requirements")) {
            JsonArray groups = new JsonArray();
            for (JsonElement group : copy.getAsJsonArray("requirements")) {
                JsonArray names = new JsonArray();
                group.getAsJsonArray().forEach(name -> {
                    if (!removed.contains(name.getAsString())) names.add(name);
                });
                if (!names.isEmpty()) groups.add(names);
            }
            copy.add("requirements", groups);
        }
        return copy;
    }

    /**
     * Whether obtaining the Network Chip alone completes the advancement: each requirement group, all of which must hold,
     * names a criterion that holding the chip fires, which is enough for that group. Without requirements every criterion
     * is a group of its own, as the codec reads it.
     */
    private static boolean completesOnChip(JsonObject advancement) {
        JsonObject criteria = advancement.getAsJsonObject("criteria");
        List<List<String>> groups = new ArrayList<>();
        if (advancement.has("requirements")) {
            for (JsonElement group : advancement.getAsJsonArray("requirements")) {
                List<String> names = new ArrayList<>();
                group.getAsJsonArray().forEach(name -> names.add(name.getAsString()));
                groups.add(names);
            }
        } else {
            criteria.keySet().forEach(name -> groups.add(List.of(name)));
        }
        return !groups.isEmpty() && groups.stream().allMatch(group -> group.stream()
                .anyMatch(name -> criteria.has(name) && firesOnChip(criteria.getAsJsonObject(name))));
    }

    /** Whether the criterion is an inventory_changed one whose every item predicate accepts the Network Chip. */
    private static boolean firesOnChip(JsonObject criterion) {
        if (!criterion.get("trigger").getAsString().equals("minecraft:inventory_changed")) return false;
        JsonObject conditions = criterion.getAsJsonObject("conditions");
        JsonArray predicates = conditions == null ? null : conditions.getAsJsonArray("items");
        if (predicates == null || predicates.isEmpty()) return false;
        for (JsonElement predicate : predicates) {
            List<String> items = new ArrayList<>();
            collectStrings(predicate, "items", items::add);
            if (!items.contains(CHIP)) return false;
        }
        return true;
    }

    // ===== Helpers =====

    /** Decodes {@code json} with every {@code mineqtt:} id replaced by a vanilla item, since mod items are not registered here. */
    private static <T> void parse(Codec<T> codec, JsonElement json, String what) {
        DataResult<T> result = codec.parse(ops, substitute(json));
        result.error().ifPresent(error -> problems.add("The " + what + " does not parse: " + error.message()));
    }

    private static JsonElement substitute(JsonElement json) {
        if (json.isJsonObject()) {
            JsonObject copy = new JsonObject();
            json.getAsJsonObject().entrySet().forEach(entry -> copy.add(entry.getKey(), substitute(entry.getValue())));
            return copy;
        }
        if (json.isJsonArray()) {
            JsonArray copy = new JsonArray();
            json.getAsJsonArray().forEach(element -> copy.add(substitute(element)));
            return copy;
        }
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString() && json.getAsString().startsWith(NAMESPACE + ":")) {
            return new JsonPrimitive(STAND_IN);
        }
        return json;
    }

    /** Hands the id of every item entry, also one nested in an alternatives, group or sequence entry, to {@code sink}. */
    private static void lootItems(JsonElement json, Consumer<String> sink) {
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(element -> lootItems(element, sink));
        } else if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("minecraft:item") && object.has("name")) {
                sink.accept(object.get("name").getAsString());
            }
            object.entrySet().forEach(entry -> lootItems(entry.getValue(), sink));
        }
    }

    /** Hands every string under a key named {@code name}, alone or in an array, anywhere in {@code json} to {@code sink}. */
    private static void collectStrings(JsonElement json, String name, Consumer<String> sink) {
        if (json == null) return;
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(element -> collectStrings(element, name, sink));
        } else if (json.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (entry.getKey().equals(name) && value.isJsonPrimitive()) {
                    sink.accept(value.getAsString());
                } else if (entry.getKey().equals(name) && value.isJsonArray()) {
                    value.getAsJsonArray().forEach(element -> {
                        if (element.isJsonPrimitive()) sink.accept(element.getAsString());
                        else collectStrings(element, name, sink);
                    });
                } else {
                    collectStrings(value, name, sink);
                }
            }
        }
    }

    /**
     * A resource of this mod from the source folder, or a vanilla one from the Minecraft jar on the classpath; null when
     * it does not exist.
     */
    private static JsonElement resource(String path) throws IOException {
        if (path.startsWith("assets/" + NAMESPACE + "/")) {
            Path file = resources.resolve(path);
            return Files.isRegularFile(file) ? read(file) : null;
        }
        try (InputStream in = CatalogCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        }
    }

    private static boolean exists(String path) {
        if (path.startsWith("assets/" + NAMESPACE + "/")) return Files.isRegularFile(resources.resolve(path));
        return CatalogCheck.class.getClassLoader().getResource(path) != null;
    }

    private static JsonElement read(Path file) throws IOException {
        // Some older assets (the Cyberdeck's model files) start with a byte order mark.
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return JsonParser.parseString(text.startsWith("﻿") ? text.substring(1) : text);
    }

    private static List<Path> jsonFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            problems.add("Missing folder " + resources.relativize(folder));
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }

    private static boolean expect(boolean condition, String problem) {
        if (!condition) problems.add(problem);
        return condition;
    }
}

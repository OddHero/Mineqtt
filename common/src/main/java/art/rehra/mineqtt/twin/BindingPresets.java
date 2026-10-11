package art.rehra.mineqtt.twin;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.twin.core.BindingPreset;
import art.rehra.mineqtt.twin.core.PresetCodec;
import dev.architectury.registry.ReloadListenerRegistry;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The MQTT device presets of the running server (M3 plan M3-17): read from every datapack's
 * {@code data/<namespace>/mineqtt/binding_presets/*.json} at each data reload (the server's start and {@code /reload}),
 * MineQTT's built-ins among them ({@code data/mineqtt/mineqtt/binding_presets/}). A file that breaks a rule is skipped
 * with a warning naming it ({@link PresetCodec}); the others load. After a reload the running server judges every
 * loaded binding again, so a preset that went refuses its bindings and one that came back heals them. The presets are
 * replaced as a whole, so readers on any thread see one reload's set.
 */
public final class BindingPresets {
    /** Where the presets are in a datapack, below {@code data/<namespace>/}. */
    public static final String DIRECTORY = MineQTT.MOD_ID + "/binding_presets";
    private static final Identifier LISTENER = Identifier.fromNamespaceAndPath(MineQTT.MOD_ID, "binding_presets");

    private static volatile PresetCodec.Loaded loaded = PresetCodec.Loaded.EMPTY;

    private BindingPresets() {
    }

    /** Registers the data reload listener; once, from {@code MineQTT.init}. */
    public static void register() {
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new Listener(), LISTENER);
    }

    /** The preset of {@code id}, if one is loaded. */
    public static Optional<BindingPreset> get(String id) {
        return Optional.ofNullable(loaded.presets().get(id));
    }

    /** Every loaded preset, in id order. */
    public static Collection<BindingPreset> all() {
        return loaded.presets().values();
    }

    /** The files the last reload skipped, and why. */
    public static List<String> problems() {
        return loaded.problems();
    }

    /**
     * Makes {@code next} the loaded presets and logs what was skipped; the running server, if any, judges its bindings
     * again on its own thread. Also what the checks use to load presets without a datapack.
     */
    public static void replace(PresetCodec.Loaded next) {
        loaded = Objects.requireNonNull(next, "next");
        next.problems().forEach(problem -> MineQTT.LOGGER.warn("MineQTT {}", problem));
        MineQTT.LOGGER.info("MineQTT loaded {} MQTT device presets{}", next.presets().size(),
                next.problems().isEmpty() ? "" : " (" + next.problems().size() + " skipped)");
        MineqttRuntime.current().ifPresent(runtime -> runtime.server().execute(runtime::presetsChanged));
    }

    /** Reads the preset files off the reload's worker thread, then swaps them in on the apply step. */
    private static final class Listener extends SimplePreparableReloadListener<PresetCodec.Loaded> {
        @Override
        protected PresetCodec.Loaded prepare(ResourceManager manager, ProfilerFiller profiler) {
            FileToIdConverter files = FileToIdConverter.json(DIRECTORY);
            Map<String, String> texts = new LinkedHashMap<>();
            Map<String, String> unreadable = new LinkedHashMap<>();
            for (Map.Entry<Identifier, Resource> file : files.listMatchingResources(manager).entrySet()) {
                String id = files.fileToId(file.getKey()).toString();
                try (InputStream in = file.getValue().open()) {
                    byte[] bytes = in.readNBytes(PresetCodec.MAX_FILE_BYTES + 1);
                    texts.put(id, new String(bytes, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    unreadable.put(id, e.getMessage());
                }
            }
            PresetCodec.Loaded parsed = PresetCodec.loadAll(texts);
            if (unreadable.isEmpty()) return parsed;
            List<String> problems = new ArrayList<>(parsed.problems());
            unreadable.forEach((id, reason) -> problems.add("binding preset " + id + " skipped: it could not be read (" + reason + ")"));
            return new PresetCodec.Loaded(parsed.presets(), problems);
        }

        @Override
        protected void apply(PresetCodec.Loaded next, ResourceManager manager, ProfilerFiller profiler) {
            replace(next);
        }

        @Override
        public String getName() {
            return LISTENER.toString();
        }
    }
}

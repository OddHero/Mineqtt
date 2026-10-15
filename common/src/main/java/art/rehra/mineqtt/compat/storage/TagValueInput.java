package art.rehra.mineqtt.compat.storage;

//? if <1.21.6 {
/*import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

// Minecraft's TagValueInput of 1.21.6 for the versions before: a ValueInput over a CompoundTag. Values are read with
// codecs, so a number of another numeric type reads as vanilla's getters read it, and a value that does not parse is absent.
public final class TagValueInput implements ValueInput {
    private final CompoundTag tag;
    private final @Nullable HolderLookup.Provider registries;
    private final DynamicOps<Tag> ops;

    private TagValueInput(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        this.tag = tag;
        this.registries = registries;
        this.ops = registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    public static ValueInput create(ProblemReporter problems, @Nullable HolderLookup.Provider registries, CompoundTag tag) {
        return new TagValueInput(tag, registries);
    }

    // The input a block entity's loadAdditional reads, over the tag it was handed.
    public static ValueInput wrap(CompoundTag tag, HolderLookup.Provider registries) {
        return new TagValueInput(tag, registries);
    }

    // The tag the input reads, for Minecraft's methods that take one before 1.21.6.
    public CompoundTag tag() {
        return tag;
    }

    @Override
    public <T> Optional<T> read(String key, Codec<T> codec) {
        Tag value = tag.get(key);
        return value == null ? Optional.empty() : codec.parse(ops, value).result();
    }

    @Override
    public <T> Optional<T> read(MapCodec<T> codec) {
        return codec.codec().parse(ops, tag).result();
    }

    @Override
    public Optional<ValueInput> child(String key) {
        return tag.get(key) instanceof CompoundTag child ? Optional.of(new TagValueInput(child, registries)) : Optional.empty();
    }

    @Override
    public ValueInput childOrEmpty(String key) {
        return child(key).orElseGet(() -> new TagValueInput(new CompoundTag(), registries));
    }

    @Override
    public Optional<ValueInputList> childrenList(String key) {
        if (!(tag.get(key) instanceof ListTag list)) return Optional.empty();
        List<ValueInput> children = new ArrayList<>();
        for (Tag element : list) {
            if (element instanceof CompoundTag child) children.add(new TagValueInput(child, registries));
        }
        return Optional.of(new Children(children));
    }

    @Override
    public ValueInputList childrenListOrEmpty(String key) {
        return childrenList(key).orElseGet(() -> new Children(List.of()));
    }

    @Override
    public <T> Optional<TypedInputList<T>> list(String key, Codec<T> codec) {
        if (!(tag.get(key) instanceof ListTag list)) return Optional.empty();
        List<T> values = new ArrayList<>();
        for (Tag element : list) codec.parse(ops, element).result().ifPresent(values::add);
        return Optional.of(new Typed<>(values));
    }

    @Override
    public <T> TypedInputList<T> listOrEmpty(String key, Codec<T> codec) {
        return list(key, codec).orElseGet(() -> new Typed<>(List.of()));
    }

    @Override
    public boolean getBooleanOr(String key, boolean fallback) {
        return read(key, Codec.BOOL).orElse(fallback);
    }

    @Override
    public byte getByteOr(String key, byte fallback) {
        return read(key, Codec.BYTE).orElse(fallback);
    }

    @Override
    public int getShortOr(String key, short fallback) {
        return read(key, Codec.SHORT).orElse(fallback);
    }

    @Override
    public Optional<Integer> getInt(String key) {
        return read(key, Codec.INT);
    }

    @Override
    public int getIntOr(String key, int fallback) {
        return read(key, Codec.INT).orElse(fallback);
    }

    @Override
    public long getLongOr(String key, long fallback) {
        return read(key, Codec.LONG).orElse(fallback);
    }

    @Override
    public Optional<Long> getLong(String key) {
        return read(key, Codec.LONG);
    }

    @Override
    public float getFloatOr(String key, float fallback) {
        return read(key, Codec.FLOAT).orElse(fallback);
    }

    @Override
    public double getDoubleOr(String key, double fallback) {
        return read(key, Codec.DOUBLE).orElse(fallback);
    }

    @Override
    public Optional<String> getString(String key) {
        return read(key, Codec.STRING);
    }

    @Override
    public String getStringOr(String key, String fallback) {
        return read(key, Codec.STRING).orElse(fallback);
    }

    @Override
    public Optional<int[]> getIntArray(String key) {
        return tag.get(key) instanceof IntArrayTag array ? Optional.of(array.getAsIntArray()) : Optional.empty();
    }

    @Override
    public HolderLookup.Provider lookup() {
        return registries;
    }

    private record Typed<T>(List<T> values) implements TypedInputList<T> {
        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public Stream<T> stream() {
            return values.stream();
        }

        @Override
        public Iterator<T> iterator() {
            return values.iterator();
        }
    }

    private record Children(List<ValueInput> values) implements ValueInputList {
        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public Stream<ValueInput> stream() {
            return values.stream();
        }

        @Override
        public Iterator<ValueInput> iterator() {
            return values.iterator();
        }
    }
}
*///?}

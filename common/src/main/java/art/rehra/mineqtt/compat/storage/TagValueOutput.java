package art.rehra.mineqtt.compat.storage;

//? if <1.21.6 {
/*import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import org.jetbrains.annotations.Nullable;

// Minecraft's TagValueOutput of 1.21.6 for the versions before: a ValueOutput into a CompoundTag. A value its codec cannot
// encode is left out.
public final class TagValueOutput implements ValueOutput {
    private final CompoundTag tag;
    private final DynamicOps<Tag> ops;

    private TagValueOutput(CompoundTag tag, DynamicOps<Tag> ops) {
        this.tag = tag;
        this.ops = ops;
    }

    public static TagValueOutput createWithContext(ProblemReporter problems, HolderLookup.Provider registries) {
        return new TagValueOutput(new CompoundTag(), RegistryOps.create(NbtOps.INSTANCE, registries));
    }

    public static TagValueOutput createWithoutContext(ProblemReporter problems) {
        return new TagValueOutput(new CompoundTag(), NbtOps.INSTANCE);
    }

    // The output a block entity's saveAdditional writes, into the tag it was handed.
    public static ValueOutput wrap(CompoundTag tag, HolderLookup.Provider registries) {
        return new TagValueOutput(tag, RegistryOps.create(NbtOps.INSTANCE, registries));
    }

    @Override
    public <T> void store(String key, Codec<T> codec, T value) {
        codec.encodeStart(ops, value).result().ifPresent(encoded -> tag.put(key, encoded));
    }

    @Override
    public <T> void storeNullable(String key, Codec<T> codec, @Nullable T value) {
        if (value != null) store(key, codec, value);
    }

    @Override
    public <T> void store(MapCodec<T> codec, T value) {
        codec.codec().encodeStart(ops, value).result().ifPresent(encoded -> {
            if (encoded instanceof CompoundTag fields) tag.merge(fields);
        });
    }

    @Override
    public void putBoolean(String key, boolean value) {
        tag.putBoolean(key, value);
    }

    @Override
    public void putByte(String key, byte value) {
        tag.putByte(key, value);
    }

    @Override
    public void putShort(String key, short value) {
        tag.putShort(key, value);
    }

    @Override
    public void putInt(String key, int value) {
        tag.putInt(key, value);
    }

    @Override
    public void putLong(String key, long value) {
        tag.putLong(key, value);
    }

    @Override
    public void putFloat(String key, float value) {
        tag.putFloat(key, value);
    }

    @Override
    public void putDouble(String key, double value) {
        tag.putDouble(key, value);
    }

    @Override
    public void putString(String key, String value) {
        tag.putString(key, value);
    }

    @Override
    public void putIntArray(String key, int[] value) {
        tag.putIntArray(key, value);
    }

    @Override
    public ValueOutput child(String key) {
        CompoundTag child = new CompoundTag();
        tag.put(key, child);
        return new TagValueOutput(child, ops);
    }

    @Override
    public ValueOutputList childrenList(String key) {
        ListTag list = new ListTag();
        tag.put(key, list);
        return new ValueOutputList() {
            @Override
            public ValueOutput addChild() {
                CompoundTag child = new CompoundTag();
                list.add(child);
                return new TagValueOutput(child, ops);
            }

            @Override
            public void discardLast() {
                list.remove(list.size() - 1);
            }

            @Override
            public boolean isEmpty() {
                return list.isEmpty();
            }
        };
    }

    @Override
    public <T> TypedOutputList<T> list(String key, Codec<T> codec) {
        ListTag list = new ListTag();
        tag.put(key, list);
        return new TypedOutputList<>() {
            @Override
            public void add(T value) {
                codec.encodeStart(ops, value).result().ifPresent(list::add);
            }

            @Override
            public boolean isEmpty() {
                return list.isEmpty();
            }
        };
    }

    @Override
    public void discard(String key) {
        tag.remove(key);
    }

    @Override
    public boolean isEmpty() {
        return tag.isEmpty();
    }

    public CompoundTag buildResult() {
        return tag;
    }
}
*///?}

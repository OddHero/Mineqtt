package art.rehra.mineqtt.compat.storage;

//? if <1.21.6 {
/*import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;

import java.util.Optional;
import java.util.stream.Stream;

// Minecraft's ValueInput of 1.21.6 for the versions before, read from a CompoundTag (TagValueInput). Stonecutter swaps the
// imports of net.minecraft.world.level.storage.ValueInput for this interface.
public interface ValueInput {
    <T> Optional<T> read(String key, Codec<T> codec);

    <T> Optional<T> read(MapCodec<T> codec);

    Optional<ValueInput> child(String key);

    ValueInput childOrEmpty(String key);

    Optional<ValueInputList> childrenList(String key);

    ValueInputList childrenListOrEmpty(String key);

    <T> Optional<TypedInputList<T>> list(String key, Codec<T> codec);

    <T> TypedInputList<T> listOrEmpty(String key, Codec<T> codec);

    boolean getBooleanOr(String key, boolean fallback);

    byte getByteOr(String key, byte fallback);

    int getShortOr(String key, short fallback);

    Optional<Integer> getInt(String key);

    int getIntOr(String key, int fallback);

    long getLongOr(String key, long fallback);

    Optional<Long> getLong(String key);

    float getFloatOr(String key, float fallback);

    double getDoubleOr(String key, double fallback);

    Optional<String> getString(String key);

    String getStringOr(String key, String fallback);

    Optional<int[]> getIntArray(String key);

    HolderLookup.Provider lookup();

    interface TypedInputList<T> extends Iterable<T> {
        boolean isEmpty();

        Stream<T> stream();
    }

    interface ValueInputList extends Iterable<ValueInput> {
        boolean isEmpty();

        Stream<ValueInput> stream();
    }
}
*///?}

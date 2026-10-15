package art.rehra.mineqtt.compat.storage;

//? if <1.21.6 {
/*import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import org.jetbrains.annotations.Nullable;

// Minecraft's ValueOutput of 1.21.6 for the versions before, written into a CompoundTag (TagValueOutput). Stonecutter swaps
// the imports of net.minecraft.world.level.storage.ValueOutput for this interface.
public interface ValueOutput {
    <T> void store(String key, Codec<T> codec, T value);

    <T> void storeNullable(String key, Codec<T> codec, @Nullable T value);

    <T> void store(MapCodec<T> codec, T value);

    void putBoolean(String key, boolean value);

    void putByte(String key, byte value);

    void putShort(String key, short value);

    void putInt(String key, int value);

    void putLong(String key, long value);

    void putFloat(String key, float value);

    void putDouble(String key, double value);

    void putString(String key, String value);

    void putIntArray(String key, int[] value);

    ValueOutput child(String key);

    ValueOutputList childrenList(String key);

    <T> TypedOutputList<T> list(String key, Codec<T> codec);

    void discard(String key);

    boolean isEmpty();

    interface TypedOutputList<T> {
        void add(T value);

        boolean isEmpty();
    }

    interface ValueOutputList {
        ValueOutput addChild();

        void discardLast();

        boolean isEmpty();
    }
}
*///?}

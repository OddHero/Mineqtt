package art.rehra.mineqtt.compat;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import org.jetbrains.annotations.Nullable;
//? if <1.21.5 {
/*import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
*///?}

import java.util.Optional;
import java.util.Set;

/**
 * The NBT getters, codec access and parsing as 1.21.5 has them (an absent value is empty, not 0 or ""), as static methods:
 * from 1.21.5 on they call Minecraft's, before they are written out here. A value of another type reads as absent, as from
 * 1.21.5 on; a number reads as any numeric type.
 */
public final class NbtTags {

    private NbtTags() {
    }

    // ===== CompoundTag =====

    public static Set<String> keySet(CompoundTag tag) {
        //? if >=1.21.5 {
        return tag.keySet();
        //?} else {
        /*return tag.getAllKeys();
        *///?}
    }

    public static Optional<Byte> getByte(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getByte(key);
        //?} else {
        /*return tag.get(key) instanceof NumericTag number ? Optional.of(number.getAsByte()) : Optional.empty();
        *///?}
    }

    public static byte getByteOr(CompoundTag tag, String key, byte fallback) {
        return getByte(tag, key).orElse(fallback);
    }

    public static Optional<Short> getShort(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getShort(key);
        //?} else {
        /*return tag.get(key) instanceof NumericTag number ? Optional.of(number.getAsShort()) : Optional.empty();
        *///?}
    }

    public static short getShortOr(CompoundTag tag, String key, short fallback) {
        return getShort(tag, key).orElse(fallback);
    }

    public static Optional<Integer> getInt(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getInt(key);
        //?} else {
        /*return tag.get(key) instanceof NumericTag number ? Optional.of(number.getAsInt()) : Optional.empty();
        *///?}
    }

    public static int getIntOr(CompoundTag tag, String key, int fallback) {
        return getInt(tag, key).orElse(fallback);
    }

    public static Optional<Long> getLong(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getLong(key);
        //?} else {
        /*return tag.get(key) instanceof NumericTag number ? Optional.of(number.getAsLong()) : Optional.empty();
        *///?}
    }

    public static long getLongOr(CompoundTag tag, String key, long fallback) {
        return getLong(tag, key).orElse(fallback);
    }

    public static Optional<Float> getFloat(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getFloat(key);
        //?} else {
        /*return tag.get(key) instanceof NumericTag number ? Optional.of(number.getAsFloat()) : Optional.empty();
        *///?}
    }

    public static float getFloatOr(CompoundTag tag, String key, float fallback) {
        return getFloat(tag, key).orElse(fallback);
    }

    public static Optional<Double> getDouble(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getDouble(key);
        //?} else {
        /*return tag.get(key) instanceof NumericTag number ? Optional.of(number.getAsDouble()) : Optional.empty();
        *///?}
    }

    public static double getDoubleOr(CompoundTag tag, String key, double fallback) {
        return getDouble(tag, key).orElse(fallback);
    }

    public static Optional<Boolean> getBoolean(CompoundTag tag, String key) {
        return getByte(tag, key).map(value -> value != 0);
    }

    public static boolean getBooleanOr(CompoundTag tag, String key, boolean fallback) {
        return getBoolean(tag, key).orElse(fallback);
    }

    public static Optional<String> getString(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getString(key);
        //?} else {
        /*return tag.get(key) instanceof StringTag text ? Optional.of(text.getAsString()) : Optional.empty();
        *///?}
    }

    public static String getStringOr(CompoundTag tag, String key, String fallback) {
        return getString(tag, key).orElse(fallback);
    }

    public static Optional<byte[]> getByteArray(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getByteArray(key);
        //?} else {
        /*return tag.get(key) instanceof ByteArrayTag array ? Optional.of(array.getAsByteArray()) : Optional.empty();
        *///?}
    }

    public static Optional<int[]> getIntArray(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getIntArray(key);
        //?} else {
        /*return tag.get(key) instanceof IntArrayTag array ? Optional.of(array.getAsIntArray()) : Optional.empty();
        *///?}
    }

    public static Optional<long[]> getLongArray(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getLongArray(key);
        //?} else {
        /*return tag.get(key) instanceof LongArrayTag array ? Optional.of(array.getAsLongArray()) : Optional.empty();
        *///?}
    }

    public static Optional<CompoundTag> getCompound(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag compound ? Optional.of(compound) : Optional.empty();
    }

    public static CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        return getCompound(tag, key).orElseGet(CompoundTag::new);
    }

    public static Optional<ListTag> getList(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag list ? Optional.of(list) : Optional.empty();
    }

    public static ListTag getListOrEmpty(CompoundTag tag, String key) {
        return getList(tag, key).orElseGet(ListTag::new);
    }

    public static <T> Optional<T> read(CompoundTag tag, String key, Codec<T> codec) {
        return read(tag, key, codec, NbtOps.INSTANCE);
    }

    public static <T> Optional<T> read(CompoundTag tag, String key, Codec<T> codec, DynamicOps<Tag> ops) {
        //? if >=1.21.5 {
        return tag.read(key, codec, ops);
        //?} else {
        /*Tag value = tag.get(key);
        return value == null ? Optional.empty() : codec.parse(ops, value).result();
        *///?}
    }

    public static <T> Optional<T> read(CompoundTag tag, MapCodec<T> codec) {
        //? if >=1.21.5 {
        return tag.read(codec);
        //?} else {
        /*return codec.codec().parse(NbtOps.INSTANCE, tag).result();
        *///?}
    }

    public static <T> void store(CompoundTag tag, String key, Codec<T> codec, T value) {
        store(tag, key, codec, NbtOps.INSTANCE, value);
    }

    public static <T> void store(CompoundTag tag, String key, Codec<T> codec, DynamicOps<Tag> ops, T value) {
        //? if >=1.21.5 {
        tag.store(key, codec, ops, value);
        //?} else {
        /*tag.put(key, codec.encodeStart(ops, value).getOrThrow());
        *///?}
    }

    public static <T> void storeNullable(CompoundTag tag, String key, Codec<T> codec, @Nullable T value) {
        if (value != null) store(tag, key, codec, value);
    }

    public static <T> void store(CompoundTag tag, MapCodec<T> codec, T value) {
        //? if >=1.21.5 {
        tag.store(codec, value);
        //?} else {
        /*tag.merge((CompoundTag) codec.codec().encodeStart(NbtOps.INSTANCE, value).getOrThrow());
        *///?}
    }

    // ===== ListTag =====

    public static Optional<CompoundTag> getCompound(ListTag list, int index) {
        return element(list, index) instanceof CompoundTag compound ? Optional.of(compound) : Optional.empty();
    }

    public static CompoundTag getCompoundOrEmpty(ListTag list, int index) {
        return getCompound(list, index).orElseGet(CompoundTag::new);
    }

    public static Optional<ListTag> getList(ListTag list, int index) {
        return element(list, index) instanceof ListTag child ? Optional.of(child) : Optional.empty();
    }

    public static ListTag getListOrEmpty(ListTag list, int index) {
        return getList(list, index).orElseGet(ListTag::new);
    }

    public static Optional<String> getString(ListTag list, int index) {
        Tag element = element(list, index);
        return element == null ? Optional.empty() : asString(element);
    }

    public static String getStringOr(ListTag list, int index, String fallback) {
        return getString(list, index).orElse(fallback);
    }

    public static Optional<Integer> getInt(ListTag list, int index) {
        Tag element = element(list, index);
        return element == null ? Optional.empty() : asInt(element);
    }

    public static int getIntOr(ListTag list, int index, int fallback) {
        return getInt(list, index).orElse(fallback);
    }

    private static @Nullable Tag element(ListTag list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    // ===== Tag =====

    public static Optional<String> asString(Tag tag) {
        //? if >=1.21.5 {
        return tag.asString();
        //?} else {
        /*return tag instanceof StringTag text ? Optional.of(text.getAsString()) : Optional.empty();
        *///?}
    }

    public static Optional<Integer> asInt(Tag tag) {
        //? if >=1.21.5 {
        return tag.asInt();
        //?} else {
        /*return tag instanceof NumericTag number ? Optional.of(number.getAsInt()) : Optional.empty();
        *///?}
    }

    public static Optional<Long> asLong(Tag tag) {
        //? if >=1.21.5 {
        return tag.asLong();
        //?} else {
        /*return tag instanceof NumericTag number ? Optional.of(number.getAsLong()) : Optional.empty();
        *///?}
    }

    public static Optional<Boolean> asBoolean(Tag tag) {
        //? if >=1.21.5 {
        return tag.asBoolean();
        //?} else {
        /*return tag instanceof NumericTag number ? Optional.of(number.getAsByte() != 0) : Optional.empty();
        *///?}
    }

    public static Optional<CompoundTag> asCompound(Tag tag) {
        return tag instanceof CompoundTag compound ? Optional.of(compound) : Optional.empty();
    }

    public static Optional<ListTag> asList(Tag tag) {
        return tag instanceof ListTag list ? Optional.of(list) : Optional.empty();
    }

    // ===== TagParser =====

    /** SNBT text as a compound; it must be one compound and nothing else. */
    public static CompoundTag parseCompoundFully(String text) throws CommandSyntaxException {
        //? if >=1.21.5 {
        return TagParser.parseCompoundFully(text);
        //?} else {
        /*return TagParser.parseTag(text);
        *///?}
    }
}

package art.rehra.mineqtt.compat;

//? if <1.21.6 {
/*import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;

// Minecraft's ItemStackWithSlot of 1.21.6 for the versions before, in the same form as a container's saved slots.
// Stonecutter swaps the imports of net.minecraft.world.ItemStackWithSlot for this record.
public record ItemStackWithSlot(int slot, ItemStack stack) {
    public static final Codec<ItemStackWithSlot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ExtraCodecs.UNSIGNED_BYTE.fieldOf("Slot").orElse(0).forGetter(ItemStackWithSlot::slot),
            MapCodec.assumeMapUnsafe(ItemStack.CODEC).forGetter(ItemStackWithSlot::stack)
    ).apply(instance, ItemStackWithSlot::new));

    public boolean isValidInContainer(int size) {
        return slot >= 0 && slot < size;
    }
}
*///?}

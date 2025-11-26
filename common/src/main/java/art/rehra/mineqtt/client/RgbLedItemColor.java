package art.rehra.mineqtt.client;

import net.minecraft.world.item.ItemStack;

public class RgbLedItemColor {

    public static int getColor(ItemStack stack, int tintIndex) {
        // Items default to white/off state
        return 0xFFFFFF;
    }
}


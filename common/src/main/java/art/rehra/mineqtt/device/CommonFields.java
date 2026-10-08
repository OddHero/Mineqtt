package art.rehra.mineqtt.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;

/** Fields several device types share. */
public final class CommonFields {
    /** Where the device is: {@code {"dimension":"minecraft:overworld","pos":[120,64,-35]}}. */
    public static final Field<GlobalPos> LOCATION = new Field<>("location", GlobalPos.CODEC, GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), Access.READ, false);

    private CommonFields() {
    }
}

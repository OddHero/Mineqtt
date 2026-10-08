package art.rehra.mineqtt.security;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** Who besides the owner and the operators may use and configure a device (design §8.7). */
public enum AccessMode implements StringRepresentable {
    /** The owner only. */
    PRIVATE("private"),
    /** Players the claim at the device trusts; without a claim mod, everyone. */
    CLAIM("claim"),
    /** Anyone may use it; configuring is left to the players the claim trusts. */
    PUBLIC("public");

    public static final Codec<AccessMode> CODEC = StringRepresentable.fromEnum(AccessMode::values);
    public static final AccessMode DEFAULT = CLAIM;

    private final String name;

    AccessMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}

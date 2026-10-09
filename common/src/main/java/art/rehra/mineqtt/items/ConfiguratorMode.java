package art.rehra.mineqtt.items;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

import java.util.function.IntFunction;

/**
 * What the Configurator does to a device it is used on (design §7.4), kept on the item; sneak + right-click on air
 * steps to the next mode. Configure opens a device's screen, also from afar; Link wires one device to another
 * ({@link ConfiguratorLink}); Group puts devices into one group ({@link ConfiguratorGroup}); Copy-Paste copies one
 * device's settings onto others of its type ({@link ConfiguratorCopy}). Room and Bind follow in later milestones.
 */
public enum ConfiguratorMode implements StringRepresentable {
    CONFIGURE("configure"),
    LINK("link"),
    GROUP("group"),
    COPY_PASTE("copy_paste");

    public static final Codec<ConfiguratorMode> CODEC = StringRepresentable.fromEnum(ConfiguratorMode::values);
    private static final IntFunction<ConfiguratorMode> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, ConfiguratorMode> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    private final String name;

    ConfiguratorMode(String name) {
        this.name = name;
    }

    /** The mode after this one, the first after the last. */
    public ConfiguratorMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Component displayName() {
        return Component.translatable("mineqtt.configurator.mode." + name);
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}

package art.rehra.mineqtt.compat;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

/**
 * Stand-ins for {@code Block.simpleCodec} and {@code Block.propertiesCodec}, which 26.3 removed with the block codecs.
 * The mod's blocks keep their codecs for the versions before; from 26.3 on nothing reads them, so these never run.
 */
public final class BlockCodecs {

    private BlockCodecs() {
    }

    public static <B extends Block> MapCodec<B> simpleCodec(Function<BlockBehaviour.Properties, B> factory) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(BlockCodecs.<B>propertiesCodec()).apply(instance, factory));
    }

    public static <B extends Block> RecordCodecBuilder<B, BlockBehaviour.Properties> propertiesCodec() {
        return MapCodec.<BlockBehaviour.Properties>unit(() -> {
            throw new UnsupportedOperationException("Blocks have no codec from Minecraft 26.3 on");
        }).forGetter(block -> {
            throw new UnsupportedOperationException("Blocks have no codec from Minecraft 26.3 on");
        });
    }
}

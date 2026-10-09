package art.rehra.mineqtt.registry;

import art.rehra.mineqtt.MineQTT;
import art.rehra.mineqtt.items.ConfiguratorMode;
import com.mojang.serialization.Codec;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.function.UnaryOperator;

public class MineqttDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES = DeferredRegister.create(MineQTT.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /** The Configurator's mode; absent means {@link ConfiguratorMode#CONFIGURE}. */
    public static final RegistrySupplier<DataComponentType<ConfiguratorMode>> CONFIGURATOR_MODE = register("configurator_mode", builder -> builder.persistent(ConfiguratorMode.CODEC).networkSynchronized(ConfiguratorMode.STREAM_CODEC));

    /**
     * The Cyberdeck's explorer state of v1 and M1, kept on the item. Deprecated since M2-13 (design §7.2): the explorer
     * keeps it on the server instead, and the Configurator strips these from itself in {@code inventoryTick}. They stay
     * registered so items that still carry them load.
     */
    @Deprecated
    public static final RegistrySupplier<DataComponentType<Boolean>> LISTENING = register("listening", builder -> builder.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    /** @deprecated see {@link #LISTENING} */
    @Deprecated
    public static final RegistrySupplier<DataComponentType<Boolean>> PRIVATE_MODE = register("private_mode", builder -> builder.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    /** @deprecated see {@link #LISTENING}; the items it holds are given back when it is stripped */
    @Deprecated
    public static final RegistrySupplier<DataComponentType<ItemContainerContents>> INVENTORY = register("inventory", builder -> builder.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC));

    private static <T> RegistrySupplier<DataComponentType<T>> register(String name, UnaryOperator<DataComponentType.Builder<T>> builder) {
        return DATA_COMPONENT_TYPES.register(name, () -> builder.apply(DataComponentType.builder()).build());
    }

    public static void init() {
        DATA_COMPONENT_TYPES.register();
    }
}

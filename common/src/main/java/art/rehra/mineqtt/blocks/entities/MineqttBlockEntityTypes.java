package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.MineQTT;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

public class MineqttBlockEntityTypes {

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(MineQTT.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static RegistrySupplier<BlockEntityType<PublisherBlockEntity>> PUBLISHER_BLOCK;
    public static RegistrySupplier<BlockEntityType<SubscriberBlockEntity>> SUBSCRIBER_BLOCK;

    public static void writeRegister(){
        BLOCK_ENTITIES.register();
    }

    public static <T extends BlockEntityType<?>> RegistrySupplier<T> registerBlockEntity(String name, Supplier<T> blockEntity) {
        return BLOCK_ENTITIES.register(ResourceLocation.fromNamespaceAndPath(MineQTT.MOD_ID, name), blockEntity);
    }
}

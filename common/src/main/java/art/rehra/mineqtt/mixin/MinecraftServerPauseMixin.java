package art.rehra.mineqtt.mixin;

import art.rehra.mineqtt.config.MineQTTConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * {@code server.keepTickingWhenEmpty} (M2 plan D5): an empty dedicated server never pauses, so repeaters, pistons and
 * light keep running with nobody online. {@code tickServer} counts {@code emptyTicks} up to
 * {@code pause-when-empty-seconds * 20} before it pauses; restarting that count every tick keeps it at 1. The setting is
 * read every tick, so a config reload applies at the next one, and turning it off starts a full countdown that ends in
 * vanilla's "pausing" line and autosave. {@code pauseWhenEmptySeconds()} keeps reporting {@code server.properties}, to
 * the JSON-RPC management API too.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPauseMixin {
    // An empty server pauses from 1.21.2 on; before, the mixin is left out of mineqtt.mixins.json and has nothing to do.
    //? if >=1.21.2 {

    @Shadow
    private int emptyTicks;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void mineqtt$keepTickingWhenEmpty(BooleanSupplier haveTime, CallbackInfo ci) {
        if (MineQTTConfig.serverKeepTickingWhenEmpty && (Object) this instanceof DedicatedServer) emptyTicks = 0;
    }
    //?}
}

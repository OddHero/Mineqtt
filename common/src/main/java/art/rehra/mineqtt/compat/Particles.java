package art.rehra.mineqtt.compat;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Particles sent to one player: 1.21.4 split the long-distance flag into override-limiter and always-show. */
public final class Particles {

    private Particles() {
    }

    public static <T extends ParticleOptions> void sendTo(ServerLevel level, ServerPlayer player, T particle, boolean overrideLimiter,
                                                          boolean alwaysShow, double x, double y, double z, int count,
                                                          double dx, double dy, double dz, double speed) {
        //? if >=1.21.4 {
        level.sendParticles(player, particle, overrideLimiter, alwaysShow, x, y, z, count, dx, dy, dz, speed);
        //?} else {
        /*level.sendParticles(player, particle, overrideLimiter, x, y, z, count, dx, dy, dz, speed);
        *///?}
    }

    /** Dust of a packed RGB colour; before 1.21.2 the colour is a vector. */
    public static DustParticleOptions dust(int rgb, float scale) {
        //? if >=1.21.2 {
        return new DustParticleOptions(rgb, scale);
        //?} else {
        /*return new DustParticleOptions(new org.joml.Vector3f(((rgb >> 16) & 0xFF) / 255F, ((rgb >> 8) & 0xFF) / 255F, (rgb & 0xFF) / 255F), scale);
        *///?}
    }
}

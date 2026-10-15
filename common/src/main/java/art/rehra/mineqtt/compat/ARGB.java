package art.rehra.mineqtt.compat;

//? if <1.21.2 {
/*// The ARGB helpers of 1.21.2's net.minecraft.util.ARGB that the mod uses, for 1.21.1: Stonecutter swaps the import.
public final class ARGB {

    private ARGB() {
    }

    public static int opaque(int color) {
        return color | 0xFF000000;
    }

    public static int white(float alpha) {
        return (Math.round(Math.clamp(alpha, 0f, 1f) * 255f) << 24) | 0xFFFFFF;
    }

    public static int scaleRGB(int color, float scale) {
        int r = Math.clamp((int) (((color >> 16) & 0xFF) * scale), 0, 255);
        int g = Math.clamp((int) (((color >> 8) & 0xFF) * scale), 0, 255);
        int b = Math.clamp((int) ((color & 0xFF) * scale), 0, 255);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
*///?}

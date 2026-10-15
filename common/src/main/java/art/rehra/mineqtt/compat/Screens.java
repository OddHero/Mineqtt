package art.rehra.mineqtt.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/** The client's current screen: Minecraft holds it from 26.2 on in its Gui, before in itself. Client only. */
public final class Screens {

    private Screens() {
    }

    public static @Nullable Screen current() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen();
        //?} else {
        /*return Minecraft.getInstance().screen;
        *///?}
    }

    /** Opens the screen, or closes the current one for {@code null}. */
    public static void open(@Nullable Screen screen) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setScreen(screen);
        //?} else {
        /*Minecraft.getInstance().setScreen(screen);
        *///?}
    }
}

package art.rehra.mineqtt.compat;

import net.minecraft.client.gui.components.events.GuiEventListener;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
//?}

/**
 * Hands a key press or a typed character on to a vanilla widget. The mod's views take them as numbers; Minecraft's widgets
 * take KeyEvent and CharacterEvent records from 1.21.9 on, the numbers before.
 */
public final class Inputs {

    private Inputs() {
    }

    public static boolean keyPressed(GuiEventListener widget, int keyCode, int scanCode, int modifiers) {
        //? if >=1.21.9 {
        return widget.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
        //?} else {
        /*return widget.keyPressed(keyCode, scanCode, modifiers);
        *///?}
    }

    public static boolean charTyped(GuiEventListener widget, char c, int modifiers) {
        //? if >=26.1 {
        return widget.charTyped(new CharacterEvent(c));
        //?} else if >=1.21.9 {
        /*return widget.charTyped(new CharacterEvent(c, modifiers));
        *///?} else {
        /*return widget.charTyped(c, modifiers);
        *///?}
    }
}

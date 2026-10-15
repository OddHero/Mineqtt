package art.rehra.mineqtt.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Shows an {@link EditBox} value as asterisks, for the broker password in both loaders' config screens. */
public final class PasswordMask {
    // An EditBox takes a TextFormatter from 1.21.9 on, a BiFunction before.
    //? if >=1.21.9 {
    public static final EditBox.TextFormatter FORMATTER =
    //?} else {
    /*public static final java.util.function.BiFunction<String, Integer, FormattedCharSequence> FORMATTER =
    *///?}
            (text, firstCharIndex) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY);

    private PasswordMask() {
    }
}

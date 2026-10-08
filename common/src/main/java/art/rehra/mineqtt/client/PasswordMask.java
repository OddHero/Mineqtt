package art.rehra.mineqtt.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Shows an {@link EditBox} value as asterisks, for the broker password in both loaders' config screens. */
public final class PasswordMask {
    public static final EditBox.TextFormatter FORMATTER =
            (text, firstCharIndex) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY);

    private PasswordMask() {
    }
}

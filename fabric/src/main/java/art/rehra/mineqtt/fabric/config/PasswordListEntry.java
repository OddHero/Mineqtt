package art.rehra.mineqtt.fabric.config;

import art.rehra.mineqtt.client.PasswordMask;
import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A Cloth Config string entry that shows its value as asterisks. Cloth Config has no password field of its own. */
public class PasswordListEntry extends StringListEntry {
    @SuppressWarnings("deprecation") // The constructor StringFieldBuilder uses; Cloth Config offers no builder hook for a formatter.
    public PasswordListEntry(Component fieldName, String value, Component resetButtonKey, Supplier<String> defaultValue,
                             Consumer<String> saveConsumer, Supplier<Optional<Component[]>> tooltipSupplier) {
        super(fieldName, value, resetButtonKey, defaultValue, saveConsumer, tooltipSupplier, false);
        textFieldWidget.addFormatter(PasswordMask.FORMATTER);
    }

    /** The narrator would read the edit box value aloud, so only the reset button is narrated. */
    @Override
    public List<? extends NarratableEntry> narratables() {
        return List.of(resetButton);
    }
}

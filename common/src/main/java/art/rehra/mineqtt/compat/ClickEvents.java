package art.rehra.mineqtt.compat;

import net.minecraft.network.chat.ClickEvent;
import org.jetbrains.annotations.Nullable;

/** Click events became one record per action in 1.21.5. */
public final class ClickEvents {

    private ClickEvents() {
    }

    /** The command a run_command click event runs, or null for any other event. */
    public static @Nullable String runCommand(@Nullable ClickEvent event) {
        //? if >=1.21.5 {
        return event instanceof ClickEvent.RunCommand run ? run.command() : null;
        //?} else {
        /*return event != null && event.getAction() == ClickEvent.Action.RUN_COMMAND ? event.getValue() : null;
        *///?}
    }
}

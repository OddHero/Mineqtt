package art.rehra.mineqtt.fabric.gametest;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every system message the client receives, chat and action bar, in order. Fabric events cannot be unregistered. */
final class ChatLog {
    /**
     * @param actionBar the message was shown in the action bar (the overlay) instead of the chat
     * @param commands  the commands its clickable parts run, in order: what a click on each would send
     */
    record Line(String text, boolean actionBar, List<String> commands) {
    }

    private static final List<Line> LINES = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private ChatLog() {
    }

    static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> LINES.add(new Line(message.getString(), overlay, commands(message))));
        }
    }

    /** The position of the next message, for {@link #since(int)}. */
    static int mark() {
        return LINES.size();
    }

    // The run_command click events of a message, each once per run of parts that carries it.
    private static List<String> commands(Component message) {
        List<String> commands = new ArrayList<>();
        message.visit((style, text) -> {
            String run = art.rehra.mineqtt.compat.ClickEvents.runCommand(style.getClickEvent());
            if (run != null && (commands.isEmpty() || !commands.getLast().equals(run))) {
                commands.add(run);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return List.copyOf(commands);
    }

    static List<Line> since(int mark) {
        synchronized (LINES) {
            return List.copyOf(LINES.subList(Math.min(mark, LINES.size()), LINES.size()));
        }
    }
}

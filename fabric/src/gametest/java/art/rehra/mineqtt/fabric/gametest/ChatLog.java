package art.rehra.mineqtt.fabric.gametest;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every system message the client receives, chat and action bar, in order. Fabric events cannot be unregistered. */
final class ChatLog {
    /** @param actionBar the message was shown in the action bar (the overlay) instead of the chat */
    record Line(String text, boolean actionBar) {
    }

    private static final List<Line> LINES = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private ChatLog() {
    }

    static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> LINES.add(new Line(message.getString(), overlay)));
        }
    }

    /** The position of the next message, for {@link #since(int)}. */
    static int mark() {
        return LINES.size();
    }

    static List<Line> since(int mark) {
        synchronized (LINES) {
            return List.copyOf(LINES.subList(Math.min(mark, LINES.size()), LINES.size()));
        }
    }
}

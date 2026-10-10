import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Minecraft RCON client for use inside the lane server container ({@code lane-rcon <command...>}), the RCON path
 * of a lane without published host ports (a remote lane). The same protocol as e2e/tools/rcon.py: log in with
 * RCON_PASSWORD on 127.0.0.1:25575 (RCON_PORT), send one command, print the reply.
 */
public final class Rcon {
    private Rcon() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: lane-rcon <command...>");
            System.exit(2);
        }
        String password = System.getenv().getOrDefault("RCON_PASSWORD", "mineqtt-lane");
        int port = Integer.parseInt(System.getenv().getOrDefault("RCON_PORT", "25575"));
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.write(packet(1, 3, password));
            if (receive(in).id == -1) {
                System.err.println("auth failed");
                System.exit(1);
            }
            out.write(packet(2, 2, String.join(" ", args)));
            System.out.println(receive(in).body);
        }
    }

    private record Reply(int id, String body) {
    }

    private static byte[] packet(int id, int type, String body) {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(14 + text.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + text.length).putInt(id).putInt(type).put(text).put((byte) 0).put((byte) 0);
        return buffer.array();
    }

    private static Reply receive(DataInputStream in) throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] data = new byte[length];
        in.readFully(data);
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        buffer.getInt();
        return new Reply(id, new String(data, 8, Math.max(0, length - 10), StandardCharsets.UTF_8));
    }
}

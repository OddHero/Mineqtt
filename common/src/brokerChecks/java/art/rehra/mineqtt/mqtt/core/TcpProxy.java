package art.rehra.mineqtt.mqtt.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback TCP relay in front of a broker, so a check can cut the connection the way a network failure does: both
 * sockets close without an MQTT DISCONNECT, which makes the broker fire the client's will.
 */
final class TcpProxy implements AutoCloseable {
    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile boolean open = true;
    private volatile boolean closed;

    TcpProxy(String targetHost, int targetPort) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        Thread.ofVirtual().name("tcp-proxy-accept").start(this::acceptLoop);
    }

    /** The loopback address the proxy listens on. */
    String host() {
        return server.getInetAddress().getHostAddress();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Connections accepted and relayed so far; refused attempts while cut are not counted. */
    int accepted() {
        return accepted.get();
    }

    /** Drops every relayed connection and refuses new ones until {@link #restore()}. */
    void cut() {
        open = false;
        for (Socket socket : sockets) {
            closeQuietly(socket);
        }
        sockets.clear();
    }

    void restore() {
        open = true;
    }

    @Override
    public void close() {
        closed = true;
        cut();
        closeQuietly(server);
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return;
            }
            if (!open) {
                closeQuietly(client);
                continue;
            }
            Socket upstream = new Socket();
            try {
                upstream.connect(new InetSocketAddress(targetHost, targetPort), 5_000);
            } catch (IOException e) {
                closeQuietly(client);
                closeQuietly(upstream);
                continue;
            }
            accepted.incrementAndGet();
            sockets.add(client);
            sockets.add(upstream);
            Thread.ofVirtual().name("tcp-proxy-up").start(() -> pump(client, upstream));
            Thread.ofVirtual().name("tcp-proxy-down").start(() -> pump(upstream, client));
        }
    }

    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int read; (read = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Either side went away; the pair is torn down below.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
            sockets.remove(from);
            sockets.remove(to);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing is best effort.
        }
    }
}

package art.rehra.mineqtt.mqtt.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * A complete, validated broker connection: the parsed broker URI plus {@link BrokerSettings}. {@code wsPath} is null
 * for plain MQTT transports; {@code username}, {@code password} and {@code caFile} may be null.
 * {@link #toString()} is {@link #redacted()}.
 */
public record BrokerConfig(String host, int port, boolean tls, String wsPath, Path caFile,
                           boolean insecureSkipVerify, String clientId, String username, char[] password,
                           int keepAliveSec, ProtocolVersion protocol, int maxInboundBytes) {
    public static final String DEFAULT_WS_PATH = "/mqtt";

    private enum Scheme {
        TCP(1883, false, false), SSL(8883, true, false), WS(80, false, true), WSS(443, true, true);

        final int defaultPort;
        final boolean tls;
        final boolean webSocket;

        Scheme(int defaultPort, boolean tls, boolean webSocket) {
            this.defaultPort = defaultPort;
            this.tls = tls;
            this.webSocket = webSocket;
        }

        static Scheme of(String name) {
            return switch (name) {
                case "tcp", "mqtt" -> TCP;
                case "ssl", "mqtts", "tls" -> SSL;
                case "ws" -> WS;
                case "wss" -> WSS;
                default -> throw new IllegalArgumentException("Unsupported broker URI scheme '" + name
                        + "', expected tcp, mqtt, ssl, mqtts, ws or wss");
            };
        }
    }

    public BrokerConfig {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Broker host must not be blank");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Broker port must be 1-65535: " + port);
        if (wsPath != null && !wsPath.startsWith("/")) throw new IllegalArgumentException("WebSocket path must start with '/': " + wsPath);
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("MQTT client id must not be blank");
        if (keepAliveSec < 0 || keepAliveSec > 65_535) throw new IllegalArgumentException("Keep alive must be 0-65535 seconds: " + keepAliveSec);
        if (protocol == null) throw new IllegalArgumentException("MQTT protocol must be set");
        if (maxInboundBytes <= 0) throw new IllegalArgumentException("Inbound payload limit must be positive: " + maxInboundBytes);
    }

    /**
     * Parses a broker URI. Accepted forms: {@code host}, {@code host:port}, {@code tcp://} and {@code mqtt://}
     * (default port 1883), {@code ssl://}, {@code mqtts://} and {@code tls://} (8883, TLS), {@code ws://} (80, path
     * default {@value #DEFAULT_WS_PATH}), {@code wss://} (443, {@value #DEFAULT_WS_PATH}, TLS). IPv6 literals go in
     * brackets ({@code [::1]:1883}); a bare literal without a port is accepted too. Credentials in the URI are
     * rejected, they belong in the username and password settings.
     *
     * @return empty when the URI is blank, which means MQTT is disabled
     * @throws IllegalArgumentException with a message fit for the server log when the URI is malformed
     */
    public static Optional<BrokerConfig> parse(String uri, BrokerSettings rest) {
        if (uri == null || uri.isBlank()) return Optional.empty();
        String text = uri.strip();
        int schemeEnd = text.indexOf("://");
        Scheme scheme = schemeEnd < 0 ? Scheme.TCP : Scheme.of(text.substring(0, schemeEnd).toLowerCase(Locale.ROOT));
        String remainder = schemeEnd < 0 ? text : text.substring(schemeEnd + 3);
        // Checked first and anywhere (a password may contain '/'), so no later error message echoes credentials.
        if (remainder.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Credentials in the broker URI are not supported, set username and password instead");
        }
        if (remainder.indexOf('?') >= 0 || remainder.indexOf('#') >= 0) {
            // Not echoed either: some WebSocket brokers take an access token in the query.
            throw new IllegalArgumentException("Broker URI must not contain a query or fragment");
        }

        int pathStart = remainder.indexOf('/');
        String authority = pathStart < 0 ? remainder : remainder.substring(0, pathStart);
        String path = pathStart < 0 ? "" : remainder.substring(pathStart);

        String host;
        String portText = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("Unclosed IPv6 bracket in broker URI: " + uri);
            host = authority.substring(1, close);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) throw new IllegalArgumentException("Unexpected text after the IPv6 address in broker URI: " + uri);
                portText = after.substring(1);
            }
            if (!isIpv6Literal(host)) throw new IllegalArgumentException("Invalid IPv6 address in broker URI: " + uri);
        } else {
            int firstColon = authority.indexOf(':');
            if (firstColon >= 0 && firstColon != authority.lastIndexOf(':')) {
                // Several colons and no brackets can only be a bare IPv6 literal, which leaves no room for a port.
                host = authority;
                if (!isIpv6Literal(host)) throw new IllegalArgumentException("Invalid IPv6 address in broker URI: " + uri);
            } else {
                host = firstColon < 0 ? authority : authority.substring(0, firstColon);
                portText = firstColon < 0 ? null : authority.substring(firstColon + 1);
                if (!isHostName(host)) throw new IllegalArgumentException("Invalid broker host in URI: " + uri);
            }
        }
        if (host.isEmpty()) throw new IllegalArgumentException("Broker URI has no host: " + uri);
        int port = portText == null ? scheme.defaultPort : parsePort(portText, uri);

        String wsPath = null;
        if (scheme.webSocket) {
            wsPath = path.isEmpty() ? DEFAULT_WS_PATH : path;
        } else if (!path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("Only ws:// and wss:// broker URIs may have a path: " + uri);
        }
        return Optional.of(new BrokerConfig(host, port, scheme.tls, wsPath, rest.caFile(), rest.insecureSkipVerify(),
                rest.clientId(), rest.username(), rest.password(), rest.keepAliveSec(), rest.protocol(), rest.maxInboundBytes()));
    }

    public boolean webSocket() {
        return wsPath != null;
    }

    /** The broker URI in canonical form, e.g. {@code ssl://broker.lan:8883} or {@code ws://[::1]:9001/mqtt}. */
    public String uri() {
        String scheme = webSocket() ? (tls ? "wss" : "ws") : (tls ? "ssl" : "tcp");
        String hostPart = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return scheme + "://" + hostPart + ":" + port + (webSocket() ? wsPath : "");
    }

    /** The connection for logs and {@code /mineqtt status}: URI, protocol and TLS mode, with the user masked. */
    public String redacted() {
        StringBuilder text = new StringBuilder(uri()).append(" (MQTT ").append(protocol.configValue());
        if (username != null) text.append(", user ***");
        if (tls && insecureSkipVerify) text.append(", TLS verification OFF");
        else if (tls && caFile != null) text.append(", CA ").append(caFile.getFileName());
        return text.append(')').toString();
    }

    @Override
    public String toString() {
        return redacted();
    }

    /** The password as the UTF-8 bytes MQTT sends, or null. */
    byte[] passwordUtf8() {
        if (password == null) return null;
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static int parsePort(String text, String uri) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65_535) return port;
        } catch (NumberFormatException ignored) {
            // Reported below together with the out-of-range case.
        }
        throw new IllegalArgumentException("Invalid broker port '" + text + "' in URI: " + uri);
    }

    private static boolean isHostName(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            if (!allowed) return false;
        }
        return true;
    }

    private static boolean isIpv6Literal(String host) {
        if (host.indexOf(':') < 0) return false;
        int zone = host.indexOf('%');
        String address = zone < 0 ? host : host.substring(0, zone);
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            boolean allowed = Character.digit(c, 16) >= 0 || c == ':' || c == '.';
            if (!allowed) return false;
        }
        return zone < 0 || isHostName(host.substring(zone + 1)) && zone + 1 < host.length();
    }
}

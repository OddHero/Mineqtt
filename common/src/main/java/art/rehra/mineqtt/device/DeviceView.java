package art.rehra.mineqtt.device;

import art.rehra.mineqtt.security.AccessMode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * What a device screen shows of one device: an immutable snapshot of its identity, access, exposure, Raw Link, status
 * line and state, what the bridge knows of it ({@link Registration}), who owns it ({@link Ownership}), how it runs and
 * what it sent and received ({@link Diagnostics}), and what the viewing player may change. The server builds it into the
 * open data of the screen (M2-7) and sends it again whenever the device changed (M2-6); the client resolves the type
 * from its id ({@link DeviceTypes}) and derives tabs and widgets from the declaration, so the view carries values, never
 * the declaration. It carries the owner's name but not their UUID, and no grandfather flag.
 * <p>
 * Its {@link #STREAM_CODEC} writes at most {@value #MAX_BYTES} bytes: {@link #of} cuts every text to its cap and a
 * state over {@value #MAX_STATE_BYTES} bytes does not encode.
 *
 * @param expose       its own Home Assistant exposure; empty follows {@code ha.expose}
 * @param registration what the bridge knows of it
 * @param state        the device's state JSON, as it publishes it
 * @param privateMode  whether its Raw Link's channel resolves into the owner's private subtree (a private channel)
 * @param ownership    whether it has an owner, and what decides claim trust on this server
 * @param diagnostics  its id, availability and traffic, the payloads only for a player who may configure it
 * @param legacyItems  how many items its old configuration slots held and it still keeps (M2-12)
 */
public record DeviceView(BlockPos pos, Identifier type, String name, String slug, String ownerName, AccessMode access,
                         Optional<Boolean> expose, Registration registration, Grants grants, String state, Optional<Raw> raw,
                         boolean privateMode, String status, Optional<String> problem, Ownership ownership, Diagnostics diagnostics,
                         int legacyItems) {
    public static final int MAX_BYTES = 32 * 1024;
    public static final int MAX_STATE_BYTES = 16 * 1024;
    /** The most of a message's payload the Diagnostics tab shows; its size is shown in full. */
    public static final int MAX_SHOWN_PAYLOAD_BYTES = 256;
    private static final int MAX_ID_BYTES = 256;
    private static final int MAX_NAME_BYTES = 1024;
    private static final int MAX_SLUG_BYTES = Slugs.MAX_LENGTH;
    private static final int MAX_OWNER_BYTES = 256;
    private static final int MAX_STATUS_BYTES = 1536;
    private static final int MAX_TOPIC_BYTES = 1024;
    private static final int MAX_PAYLOAD_BYTES = LinkSetting.MAX_PAYLOAD_BYTES;
    // The Diagnostics tab's texts stay short, so the largest view still fits MAX_BYTES: a fault's text, a message's topic.
    private static final int MAX_DETAIL_BYTES = 1024;
    private static final int MAX_TRAFFIC_TOPIC_BYTES = LinkSetting.MAX_TOPIC_BYTES;
    private static final String ELLIPSIS = "…";

    public static final StreamCodec<ByteBuf, DeviceView> STREAM_CODEC = StreamCodec.of(DeviceView::write, DeviceView::read);

    /**
     * What the viewing player may change, as the server judged it when it built the view; the server judges every
     * change again.
     *
     * @param use       USE: commands and actions
     * @param configure CONFIGURE: settings, name and Raw Link
     * @param expose    CONFIGURE and {@code AclContext.mayExpose}: the Home Assistant exposure
     * @param access    the owner or an operator: the access mode
     */
    public record Grants(boolean use, boolean configure, boolean expose, boolean access) {
        public static final Grants NONE = new Grants(false, false, false, false);
        /** An operator's command twin ({@code /mineqtt devices setting}, op level 2): everything. */
        public static final Grants ALL = new Grants(true, true, true, true);
    }

    /**
     * What the bridge knows of a registered device, which its block does not: where its topics live, whether Home
     * Assistant discovers it now and its group.
     *
     * @param topicRoot  {@code <base>/<bridgeId>}, under which the device's state topic is its slug
     *                   ({@code TopicScheme#device}); empty while the device is not registered
     * @param discovered whether Home Assistant discovers it now: its exposure, {@code ha.expose} and its group decide
     * @param group      the name of its group, if it is in one
     */
    public record Registration(String topicRoot, boolean discovered, Optional<String> group) {
        /** A device that does not run on this server (not attached yet, faulted, or MineQTT not started). */
        public static final Registration NONE = new Registration("", false, Optional.empty());

        /** The device's state topic for a slug, {@code <topicRoot>/<slug>}; empty while it is not registered. */
        public Optional<String> topic(String slug) {
            return topicRoot.isEmpty() ? Optional.empty() : Optional.of(topicRoot + "/" + slug);
        }
    }

    /**
     * The device's Raw Link as its editor shows it.
     *
     * @param channel  the item or paper literal the topic resolves from; empty for a topic used as typed
     * @param retain   the link's own retain override; empty follows the type
     * @param retains  whether it publishes retained now (the override, the type's default, never for a toggle)
     * @param disabled why the topic may not be used; empty while usable
     * @param edges    which edges an output publishes: every one, or the rising one only (a converted Static Publisher)
     */
    public record Raw(String topic, Optional<String> channel, RawLinkSpec.Direction direction, int qos, Optional<Boolean> retain,
                      boolean retains, Optional<String> on, Optional<String> off, Optional<String> disabled, RawLink.Edges edges) {
        static Raw of(RawLink link, RawLinkSpec spec) {
            return new Raw(cap(link.topic(), MAX_TOPIC_BYTES), Optional.ofNullable(link.channel()).map(channel -> cap(channel, MAX_TOPIC_BYTES)),
                    link.direction(), link.qos(), Optional.ofNullable(link.retain()),
                    spec.direction() == RawLinkSpec.Direction.OUT && link.effectiveRetain(spec),
                    Optional.ofNullable(link.on()).map(on -> cap(on, MAX_PAYLOAD_BYTES)), Optional.ofNullable(link.off()).map(off -> cap(off, MAX_PAYLOAD_BYTES)),
                    Optional.ofNullable(link.disabledReason()).map(reason -> cap(reason, MAX_STATUS_BYTES)), link.edges());
        }
    }

    /**
     * Who owns the device, as the Access tab shows it (M2-11).
     *
     * @param owner    whether it has an owner and whether the server knows them by UUID
     * @param claimMod whether a claim mod (Flan) decides who the claim at the device trusts; without one, claim access
     *                 trusts everyone
     */
    public record Ownership(Owner owner, boolean claimMod) {
    }

    /** Whether a device has an owner. */
    public enum Owner {
        /** Nobody: a player who may configure the device may claim it. */
        NOBODY,
        /** A player the server knows by UUID, or, without authentication, by the UUID their name stands for. */
        KNOWN,
        /** A v1 owner name an online-mode server has not resolved yet: until then only the access mode counts (M2 plan D7). */
        NOT_VERIFIED
    }

    /**
     * How a device runs on this server and what it sent and received (M2-11), for the Diagnostics tab. The counts and
     * the last messages are kept in memory since the server started.
     *
     * @param id               the device's UUID; empty until its first attach gives it one
     * @param detail           the fault while {@link Availability#FAULTED}, the broker connection's state while
     *                         {@link Availability#DISCONNECTED}, else empty
     * @param lastIn           the last MQTT message that reached it: a command on its topics, its group's, or its Raw Link's
     * @param lastOut          the last message it published: state, event, Raw Link level or state echo
     * @param received         MQTT messages that reached it
     * @param dropped          of those, the ones it ignored: not a command, refused by the device, or nobody took them
     * @param sent             messages it published
     * @param bridgeDroppedIn  messages the bridge dropped before they reached any device (a full queue, a payload too large)
     * @param bridgeDroppedOut messages the bridge dropped on their way to the broker
     */
    public record Diagnostics(Optional<UUID> id, Availability availability, String detail, Optional<Traffic> lastIn, Optional<Traffic> lastOut,
                              long received, long dropped, long sent, long bridgeDroppedIn, long bridgeDroppedOut) {
        /** A device that does not run on this server, as far as anything is known of it. */
        public static Diagnostics notRunning(@Nullable UUID id) {
            return new Diagnostics(Optional.ofNullable(id), Availability.NOT_RUNNING, "", Optional.empty(), Optional.empty(), 0, 0, 0, 0, 0);
        }
    }

    /** Whether a device takes part in MQTT now. */
    public enum Availability {
        /** Attached and the broker connected: Home Assistant sees it {@code online}. */
        ONLINE,
        /** Attached, but the broker is not connected; links on this server still work. */
        DISCONNECTED,
        /** Its attach threw; it stays offline until a retry or its next chunk load (M2 plan D7). */
        FAULTED,
        /** Not attached: MineQTT does not run here, or the device was not taken up yet. */
        NOT_RUNNING
    }

    /**
     * One message as the Diagnostics tab shows it.
     *
     * @param bytes     the payload's whole size
     * @param ageMillis how long before the view was built it went in or out
     * @param payload   its start as text, at most {@value #MAX_SHOWN_PAYLOAD_BYTES} bytes; empty for a viewer who may not
     *                  configure the device, who sees topic, size and age only
     */
    public record Traffic(String topic, int bytes, long ageMillis, Optional<String> payload) {
    }

    public DeviceView {
        if (!parse(state).isJsonObject()) throw new IllegalArgumentException("A device view's state is a JSON object: " + state);
        if (legacyItems < 0) throw new IllegalArgumentException("A device view has a negative item count");
    }

    /**
     * The view of a device: its link's identity, access, exposure, Raw Link and legacy items, with every text cut to its
     * cap.
     *
     * @param registration what the bridge knows of it; {@link Registration#NONE} while it does not run here
     * @param privateMode  whether the device is in private mode ({@link DeviceHost#isPrivateMode()})
     * @param state        the state it publishes; at most {@value #MAX_STATE_BYTES} bytes as JSON
     * @param problem      why its Raw Link does nothing, or null while it works
     * @param diagnostics  already redacted for the viewer ({@link DeviceTraffic#diagnostics})
     * @throws IllegalArgumentException when the state is larger, which no declared type's is
     */
    public static DeviceView of(BlockPos pos, DeviceLink link, Registration registration, boolean privateMode, JsonObject state, String status,
                                @Nullable String problem, Grants grants, Ownership ownership, Diagnostics diagnostics) {
        String json = state.toString();
        if (utf8(json).length > MAX_STATE_BYTES) throw new IllegalArgumentException("The state of " + link.slug() + " exceeds " + MAX_STATE_BYTES + " bytes");
        DeviceType type = link.type();
        Optional<Raw> raw = Optional.ofNullable(link.raw()).flatMap(rawLink -> type.rawLink().map(spec -> Raw.of(rawLink, spec)));
        Registration capped = new Registration(cap(registration.topicRoot(), MAX_TOPIC_BYTES), registration.discovered(),
                registration.group().map(group -> cap(group, MAX_NAME_BYTES)));
        Diagnostics cappedDiagnostics = new Diagnostics(diagnostics.id(), diagnostics.availability(), cap(diagnostics.detail(), MAX_DETAIL_BYTES),
                diagnostics.lastIn().map(DeviceView::cap), diagnostics.lastOut().map(DeviceView::cap), diagnostics.received(), diagnostics.dropped(),
                diagnostics.sent(), diagnostics.bridgeDroppedIn(), diagnostics.bridgeDroppedOut());
        return new DeviceView(pos, type.id(), cap(link.name(), MAX_NAME_BYTES), cap(link.slug(), MAX_SLUG_BYTES), cap(link.ownerName(), MAX_OWNER_BYTES),
                link.access(), Optional.ofNullable(link.expose()), capped, grants, json, raw, privateMode, cap(status, MAX_STATUS_BYTES),
                Optional.ofNullable(problem).map(text -> cap(text, MAX_STATUS_BYTES)), ownership, cappedDiagnostics,
                link.legacyItems().stream().mapToInt(item -> item.stack().getCount()).sum());
    }

    private static Traffic cap(Traffic traffic) {
        return new Traffic(cap(traffic.topic(), MAX_TRAFFIC_TOPIC_BYTES), traffic.bytes(), traffic.ageMillis(),
                traffic.payload().map(payload -> cap(payload, MAX_SHOWN_PAYLOAD_BYTES)));
    }

    /** The state as a JSON object, a new copy each time. */
    public JsonObject stateJson() {
        return parse(state).getAsJsonObject();
    }

    /**
     * {@code text}, or as much of its start as fits {@code maxBytes} of UTF-8 with an ellipsis; never splits a
     * surrogate pair.
     */
    public static String cap(String text, int maxBytes) {
        if (utf8(text).length <= maxBytes) return text;
        int budget = maxBytes - utf8(ELLIPSIS).length;
        int end = 0;
        int bytes = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = utf8(Character.toString(codePoint)).length;
            if (bytes + size > budget) break;
            bytes += size;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    private static void write(ByteBuf buf, DeviceView view) {
        int start = buf.writerIndex();
        BlockPos.STREAM_CODEC.encode(buf, view.pos);
        writeText(buf, view.type.toString(), MAX_ID_BYTES);
        writeText(buf, view.name, MAX_NAME_BYTES);
        writeText(buf, view.slug, MAX_SLUG_BYTES);
        writeText(buf, view.ownerName, MAX_OWNER_BYTES);
        VarInt.write(buf, view.access.ordinal());
        writeOptionalBoolean(buf, view.expose);
        writeText(buf, view.registration.topicRoot(), MAX_TOPIC_BYTES);
        buf.writeBoolean(view.registration.discovered());
        writeOptionalText(buf, view.registration.group(), MAX_NAME_BYTES);
        buf.writeBoolean(view.grants.use());
        buf.writeBoolean(view.grants.configure());
        buf.writeBoolean(view.grants.expose());
        buf.writeBoolean(view.grants.access());
        writeText(buf, view.state, MAX_STATE_BYTES);
        buf.writeBoolean(view.raw.isPresent());
        view.raw.ifPresent(raw -> {
            writeText(buf, raw.topic(), MAX_TOPIC_BYTES);
            writeOptionalText(buf, raw.channel(), MAX_TOPIC_BYTES);
            VarInt.write(buf, raw.direction().ordinal());
            VarInt.write(buf, raw.qos());
            writeOptionalBoolean(buf, raw.retain());
            buf.writeBoolean(raw.retains());
            writeOptionalText(buf, raw.on(), MAX_PAYLOAD_BYTES);
            writeOptionalText(buf, raw.off(), MAX_PAYLOAD_BYTES);
            writeOptionalText(buf, raw.disabled(), MAX_STATUS_BYTES);
            VarInt.write(buf, raw.edges().ordinal());
        });
        buf.writeBoolean(view.privateMode);
        writeText(buf, view.status, MAX_STATUS_BYTES);
        writeOptionalText(buf, view.problem, MAX_STATUS_BYTES);
        VarInt.write(buf, view.ownership.owner().ordinal());
        buf.writeBoolean(view.ownership.claimMod());
        Diagnostics diagnostics = view.diagnostics;
        buf.writeBoolean(diagnostics.id().isPresent());
        diagnostics.id().ifPresent(id -> {
            buf.writeLong(id.getMostSignificantBits());
            buf.writeLong(id.getLeastSignificantBits());
        });
        VarInt.write(buf, diagnostics.availability().ordinal());
        writeText(buf, diagnostics.detail(), MAX_DETAIL_BYTES);
        writeTraffic(buf, diagnostics.lastIn());
        writeTraffic(buf, diagnostics.lastOut());
        for (long count : new long[]{diagnostics.received(), diagnostics.dropped(), diagnostics.sent(), diagnostics.bridgeDroppedIn(), diagnostics.bridgeDroppedOut()}) {
            if (count < 0) throw new EncoderException("A device view has a negative count");
            VarLong.write(buf, count);
        }
        VarInt.write(buf, view.legacyItems);
        if (buf.writerIndex() - start > MAX_BYTES) throw new EncoderException("A device view takes more than " + MAX_BYTES + " bytes");
    }

    private static void writeTraffic(ByteBuf buf, Optional<Traffic> traffic) {
        buf.writeBoolean(traffic.isPresent());
        traffic.ifPresent(message -> {
            if (message.bytes() < 0 || message.ageMillis() < 0) throw new EncoderException("A device view has a message of negative size or age");
            writeText(buf, message.topic(), MAX_TRAFFIC_TOPIC_BYTES);
            VarInt.write(buf, message.bytes());
            VarLong.write(buf, message.ageMillis());
            writeOptionalText(buf, message.payload(), MAX_SHOWN_PAYLOAD_BYTES);
        });
    }

    private static Optional<Traffic> readTraffic(ByteBuf buf) {
        if (!buf.readBoolean()) return Optional.empty();
        String topic = readText(buf, MAX_TRAFFIC_TOPIC_BYTES);
        int bytes = VarInt.read(buf);
        long age = VarLong.read(buf);
        if (bytes < 0 || age < 0) throw new DecoderException("A device view has a message of negative size or age");
        return Optional.of(new Traffic(topic, bytes, age, readOptionalText(buf, MAX_SHOWN_PAYLOAD_BYTES)));
    }

    private static long readCount(ByteBuf buf) {
        long count = VarLong.read(buf);
        if (count < 0) throw new DecoderException("A device view has a negative count");
        return count;
    }

    private static DeviceView read(ByteBuf buf) {
        int start = buf.readerIndex();
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
        Identifier type = Identifier.tryParse(readText(buf, MAX_ID_BYTES));
        if (type == null) throw new DecoderException("A device view names no type");
        String name = readText(buf, MAX_NAME_BYTES);
        String slug = readText(buf, MAX_SLUG_BYTES);
        String ownerName = readText(buf, MAX_OWNER_BYTES);
        AccessMode access = readEnum(buf, AccessMode.values());
        Optional<Boolean> expose = readOptionalBoolean(buf);
        Registration registration = new Registration(readText(buf, MAX_TOPIC_BYTES), buf.readBoolean(), readOptionalText(buf, MAX_NAME_BYTES));
        Grants grants = new Grants(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        String state = readText(buf, MAX_STATE_BYTES);
        Optional<Raw> raw = Optional.empty();
        if (buf.readBoolean()) {
            String topic = readText(buf, MAX_TOPIC_BYTES);
            Optional<String> channel = readOptionalText(buf, MAX_TOPIC_BYTES);
            RawLinkSpec.Direction direction = readEnum(buf, RawLinkSpec.Direction.values());
            int qos = VarInt.read(buf);
            if (qos < 0 || qos > 1) throw new DecoderException("A device view's Raw Link has QoS " + qos);
            raw = Optional.of(new Raw(topic, channel, direction, qos, readOptionalBoolean(buf), buf.readBoolean(),
                    readOptionalText(buf, MAX_PAYLOAD_BYTES), readOptionalText(buf, MAX_PAYLOAD_BYTES), readOptionalText(buf, MAX_STATUS_BYTES),
                    readEnum(buf, RawLink.Edges.values())));
        }
        boolean privateMode = buf.readBoolean();
        String status = readText(buf, MAX_STATUS_BYTES);
        Optional<String> problem = readOptionalText(buf, MAX_STATUS_BYTES);
        Ownership ownership = new Ownership(readEnum(buf, Owner.values()), buf.readBoolean());
        Optional<UUID> id = buf.readBoolean() ? Optional.of(new UUID(buf.readLong(), buf.readLong())) : Optional.empty();
        Availability availability = readEnum(buf, Availability.values());
        String detail = readText(buf, MAX_DETAIL_BYTES);
        Optional<Traffic> lastIn = readTraffic(buf);
        Optional<Traffic> lastOut = readTraffic(buf);
        Diagnostics diagnostics = new Diagnostics(id, availability, detail, lastIn, lastOut, readCount(buf), readCount(buf), readCount(buf),
                readCount(buf), readCount(buf));
        int legacyItems = VarInt.read(buf);
        if (buf.readerIndex() - start > MAX_BYTES) throw new DecoderException("A device view takes more than " + MAX_BYTES + " bytes");
        try {
            return new DeviceView(pos, type, name, slug, ownerName, access, expose, registration, grants, state, raw, privateMode, status, problem,
                    ownership, diagnostics, legacyItems);
        } catch (IllegalArgumentException e) {
            throw new DecoderException(e.getMessage());
        }
    }

    private static void writeText(ByteBuf buf, String text, int maxBytes) {
        byte[] bytes = utf8(text);
        if (bytes.length > maxBytes) throw new EncoderException("A device view text of " + bytes.length + " bytes exceeds " + maxBytes);
        FriendlyByteBuf.writeByteArray(buf, bytes);
    }

    private static String readText(ByteBuf buf, int maxBytes) {
        return new String(FriendlyByteBuf.readByteArray(buf, maxBytes), StandardCharsets.UTF_8);
    }

    private static void writeOptionalText(ByteBuf buf, Optional<String> text, int maxBytes) {
        buf.writeBoolean(text.isPresent());
        text.ifPresent(value -> writeText(buf, value, maxBytes));
    }

    private static Optional<String> readOptionalText(ByteBuf buf, int maxBytes) {
        return buf.readBoolean() ? Optional.of(readText(buf, maxBytes)) : Optional.empty();
    }

    // 0 for none, 1 for false, 2 for true.
    private static void writeOptionalBoolean(ByteBuf buf, Optional<Boolean> value) {
        buf.writeByte(value.map(flag -> flag ? 2 : 1).orElse(0));
    }

    private static Optional<Boolean> readOptionalBoolean(ByteBuf buf) {
        return switch (buf.readByte()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(false);
            case 2 -> Optional.of(true);
            default -> throw new DecoderException("A device view has an unreadable flag");
        };
    }

    private static <E extends Enum<E>> E readEnum(ByteBuf buf, E[] values) {
        int ordinal = VarInt.read(buf);
        if (ordinal < 0 || ordinal >= values.length) throw new DecoderException("A device view has an unknown " + values[0].getDeclaringClass().getSimpleName());
        return values[ordinal];
    }

    private static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("A device view's state is no JSON: " + e.getMessage());
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

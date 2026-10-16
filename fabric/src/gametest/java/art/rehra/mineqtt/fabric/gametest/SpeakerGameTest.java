package art.rehra.mineqtt.fabric.gametest;

import art.rehra.mineqtt.blocks.entities.SpeakerBlockEntity;
import art.rehra.mineqtt.config.MineQTTConfig;
import art.rehra.mineqtt.device.DeviceHost;
import art.rehra.mineqtt.device.DeviceLink;
import art.rehra.mineqtt.device.StateWriter;
import art.rehra.mineqtt.device.types.Speaker;
import art.rehra.mineqtt.mqtt.core.BrokerConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * The Speaker. Set by command, it is off with the defaults (chime, volume 100, duration 30 s, range 16). A siren
 * command as Home Assistant sends it ({@code {"state":"ON","tone":"alarm","duration":2,"volume_level":0.5}}) sounds the
 * alarm at half volume, its sounds counted on the server, and turns it off after its two seconds; OFF stops a run of
 * the setting's length at once; at volume 0 it sounds without a sound. A redstone block beside it starts a run, and
 * taking it away does not stop it. A Redstone Input wired to its {@code state} switches it on and off with its signal.
 * Announcements: refused while {@code speaker.allowAnnouncements} is off (nothing on the player's screen, the reason in
 * {@code last_result}); allowed, a text with formatting codes, a line break and a control character reaches the player
 * nearby in the action bar as {@code [<name>] <clean text>}, in the chat with {@code speaker.announceInChat}, and not at
 * all once the player stands beyond the range; a third announcement at once is rate limited. With a broker: the siren
 * JSON and a notify payload ({@code 42}) on the device's /set topics work as from the command, and the retained state
 * shows the siren on.
 */
public final class SpeakerGameTest implements FabricClientGameTest {
    private static final int Y = GameTestSession.GROUND_Y + 1;
    private static final BlockPos SPEAKER = new BlockPos(4, Y, 14);
    private static final BlockPos STAND = SPEAKER.west(3);
    private static final BlockPos POWER = SPEAKER.east();
    private static final BlockPos INPUT = SPEAKER.south(4);
    private static final BlockPos INPUT_POWER = INPUT.south();
    // Beyond a range of 8 blocks, within the server's chunks.
    private static final BlockPos FAR = SPEAKER.west(12);
    private static final int ANSWER_TICKS = 80;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSession.run(context, "speaker", SpeakerGameTest::run);
    }

    private static void run(GameTestSession session) {
        session.teleport(STAND);
        session.context().waitTicks(25);
        boolean allowed = session.onServer(s -> MineQTTConfig.speakerAllowAnnouncements);
        boolean inChat = session.onServer(s -> MineQTTConfig.speakerAnnounceInChat);
        try {
            String slug = speaker(session);
            siren(session, slug);
            redstone(session, slug);
            wire(session, slug);
            announcements(session, slug);
            Optional<BrokerConfig> broker = session.broker();
            if (broker.isEmpty()) {
                session.skip("the siren and the notify entity's payloads on the broker", "no broker (" + GameTestSession.BROKER_ENV + " is not set)");
            } else {
                published(session, slug, broker.get());
            }
        } finally {
            session.onServer(s -> {
                MineQTTConfig.speakerAllowAnnouncements = allowed;
                MineQTTConfig.speakerAnnounceInChat = inChat;
                return null;
            });
        }
    }

    /** Set by command: a Speaker device, off, with the defaults. */
    private static String speaker(GameTestSession session) {
        setblock(session, SPEAKER, "mineqtt:speaker");
        session.awaitServer(s -> s.overworld().getBlockEntity(SPEAKER) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            var blockEntity = s.overworld().getBlockEntity(SPEAKER);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the speaker");
        session.check(Speaker.TYPE.equals(session.device(SPEAKER, DeviceLink::type)), "a placed Speaker block is a Speaker device");
        JsonObject state = state(session);
        session.check(state.get("state").getAsString().equals("OFF") && state.get("tone").getAsString().equals("chime") && state.get("volume").getAsInt() == 100
                        && state.get("duration").getAsInt() == 30 && state.get("range").getAsInt() == 16 && state.get("duration_left").getAsInt() == 0,
                "it is off; the defaults are chime, volume 100, 30 s and range 16 (" + state + ")");
        return session.device(SPEAKER, DeviceLink::slug);
    }

    /**
     * Home Assistant's siren command: the alarm at half volume for two seconds, then off by itself; OFF stops a run at
     * once; volume 0 sounds without a sound.
     */
    private static void siren(GameTestSession session, String slug) {
        int before = sounds(session);
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"tone\":\"alarm\",\"duration\":2,\"volume_level\":0.5}");
        session.check(session.await(() -> on(session), ANSWER_TICKS), "the siren command turns it on (" + state(session) + ")");
        JsonObject state = state(session);
        session.check(state.get("tone").getAsString().equals("alarm") && state.get("volume").getAsInt() == 50 && state.get("duration").getAsInt() == 30
                        && state.get("duration_left").getAsInt() >= 1 && state.get("duration_left").getAsInt() <= 2,
                "the alarm at volume 50 for two seconds; the duration setting stays (" + state + ")");
        session.check(session.await(() -> sounds(session) >= before + 3, ANSWER_TICKS), "the server plays the alarm (" + sounds(session) + " sounds)");
        session.aim(SPEAKER);
        session.screenshot("speaker_alarm");
        session.check(session.await(() -> !on(session), ANSWER_TICKS), "the siren stops when its two seconds are over (" + state(session) + ")");
        int afterRun = sounds(session);
        // Two seconds of the alarm are eight sounds at most, four a second.
        session.check(afterRun - before <= 2 * 4 + 1, "at most four sounds a second (" + (afterRun - before) + " in the run)");
        session.check(state(session).get("duration_left").getAsInt() == 0, "no time is left");
        session.context().waitTicks(20);
        session.check(sounds(session) == afterRun, "off, it plays nothing");

        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\"}");
        session.check(session.await(() -> on(session) && state(session).get("duration_left").getAsInt() > 20, ANSWER_TICKS),
                "on without a duration, it sounds for the setting's 30 s (" + state(session) + ")");
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"OFF\"}");
        session.check(session.await(() -> !on(session), ANSWER_TICKS), "OFF stops it at once (" + state(session) + ")");

        session.consoleCommand("mineqtt devices setting " + slug + " volume 0");
        session.check(session.await(() -> state(session).get("volume").getAsInt() == 0, ANSWER_TICKS), "volume 0 is set");
        int silent = sounds(session);
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"ON\",\"duration\":1}");
        session.check(session.await(() -> on(session), ANSWER_TICKS), "it turns on at volume 0");
        session.check(session.await(() -> !on(session), ANSWER_TICKS) && sounds(session) == silent, "and sounds without a sound for its second");
        session.consoleCommand("mineqtt devices setting " + slug + " volume 100");
        session.consoleCommand("mineqtt devices setting " + slug + " tone beep");
        session.check(session.await(() -> state(session).get("volume").getAsInt() == 100 && state(session).get("tone").getAsString().equals("beep"), ANSWER_TICKS),
                "volume 100 and the beep are set");
    }

    /** A redstone block beside it starts a run; taking the block away leaves it sounding. */
    private static void redstone(GameTestSession session, String slug) {
        setblock(session, POWER, "minecraft:redstone_block");
        session.check(session.await(() -> on(session), ANSWER_TICKS), "a rising redstone signal turns it on (" + state(session) + ")");
        setblock(session, POWER, "minecraft:air");
        session.context().waitTicks(10);
        session.check(on(session), "the signal falling leaves it sounding");
        session.consoleCommand("mineqtt devices set " + slug + " {\"state\":\"OFF\"}");
        session.check(session.await(() -> !on(session), ANSWER_TICKS), "OFF stops it");
    }

    /** A Redstone Input wired to its state switches it on with its signal and off without it. */
    private static void wire(GameTestSession session, String slug) {
        setblock(session, INPUT, "mineqtt:redstone_publisher_block");
        session.awaitServer(s -> s.overworld().getBlockEntity(INPUT) instanceof DeviceHost host && host.deviceLink().attached());
        session.check(session.onServer(s -> {
            var blockEntity = s.overworld().getBlockEntity(INPUT);
            return ((DeviceHost) blockEntity).deviceLink().claim(blockEntity, session.playerId(), session.playerName());
        }), "the player claims the Redstone Input");
        String input = session.device(INPUT, DeviceLink::slug);
        session.consoleCommand("mineqtt wires add " + input + ".powered " + slug + ".state");
        session.check(session.onRuntime(runtime -> runtime.registry().wires().size()) > 0, "the wire is made");
        setblock(session, INPUT_POWER, "minecraft:redstone_block");
        session.check(session.await(() -> on(session), ANSWER_TICKS), "the input's signal turns the siren on through the wire (" + state(session) + ")");
        setblock(session, INPUT_POWER, "minecraft:air");
        session.check(session.await(() -> !on(session), ANSWER_TICKS), "and its end turns it off (" + state(session) + ")");
        session.consoleCommand("setblock " + INPUT.getX() + " " + INPUT.getY() + " " + INPUT.getZ() + " minecraft:air");
    }

    /**
     * Refused while the server's key is off; allowed, cleaned plain text reaches the player in range in the action bar, in
     * the chat with announceInChat, and nobody beyond the range; a third at once is rate limited.
     */
    private static void announcements(GameTestSession session, String slug) {
        String name = session.device(SPEAKER, DeviceLink::name);
        setConfig(session, false, false);
        int mark = ChatLog.mark();
        session.consoleCommand("mineqtt devices action " + slug + " announce Washer done");
        session.check(session.await(() -> state(session).get("last_result").getAsString().equals(Speaker.REFUSED_DISABLED), ANSWER_TICKS),
                "while announcements are off it is refused (" + state(session) + ")");
        session.context().waitTicks(10);
        session.check(ChatLog.since(mark).stream().noneMatch(line -> line.text().contains("Washer done")), "and the player sees nothing");

        setConfig(session, true, false);
        // Formatting codes, a line break and a bell character, as JSON escapes the /set command reads.
        int shown = ChatLog.mark();
        session.consoleCommand("mineqtt devices set " + slug + " {\"announce\":\"\\u00a7cWasher\\n  done\\u0007 \\u00a7l!\"}");
        Optional<ChatLog.Line> line = session.awaitChat(shown, l -> l.text().contains("Washer"), ANSWER_TICKS);
        session.check(line.filter(l -> l.actionBar() && l.text().equals("[" + name + "] Washer done !") && l.commands().isEmpty()).isPresent(),
                "the player nearby sees the clean text behind the speaker's name in the action bar, nothing to click (" + line + ")");
        session.check(state(session).get("last_announcement").getAsString().equals("Washer done !") && state(session).get("last_result").getAsString().equals(Speaker.ANNOUNCED),
                "the state shows the last announcement (" + state(session) + ")");
        int limited = ChatLog.mark();
        session.consoleCommand("mineqtt devices action " + slug + " announce Second");
        session.consoleCommand("mineqtt devices action " + slug + " announce Third");
        session.check(session.await(() -> state(session).get("last_result").getAsString().equals(Speaker.REFUSED_RATE), ANSWER_TICKS),
                "a third announcement at once is rate limited (" + state(session) + ")");
        session.check(session.awaitChat(limited, l -> l.text().endsWith("Second"), ANSWER_TICKS).isPresent()
                && ChatLog.since(limited).stream().noneMatch(l -> l.text().contains("Third")), "the second is shown, the third is not");

        session.context().waitTicks(Speaker.ANNOUNCEMENT_SECONDS * 20 * 2 + 5);
        setConfig(session, true, true);
        int chat = ChatLog.mark();
        session.consoleCommand("mineqtt devices action " + slug + " announce In the chat");
        Optional<ChatLog.Line> chatLine = session.awaitChat(chat, l -> l.text().endsWith("In the chat"), ANSWER_TICKS);
        session.check(chatLine.filter(l -> !l.actionBar()).isPresent(), "with announceInChat it is a chat line (" + chatLine + ")");

        session.consoleCommand("mineqtt devices setting " + slug + " range 8");
        session.check(session.await(() -> state(session).get("range").getAsInt() == 8, ANSWER_TICKS), "range 8 is set");
        session.teleport(FAR);
        session.context().waitTicks(25);
        session.context().waitTicks(Speaker.ANNOUNCEMENT_SECONDS * 20 + 5);
        int far = ChatLog.mark();
        session.consoleCommand("mineqtt devices action " + slug + " announce Too far");
        session.check(session.await(() -> state(session).get("last_announcement").getAsString().equals("Too far"), ANSWER_TICKS), "it is announced");
        session.context().waitTicks(10);
        session.check(ChatLog.since(far).stream().noneMatch(l -> l.text().contains("Too far")), "a player 12 blocks away hears nothing within range 8");
        session.teleport(STAND);
        session.context().waitTicks(25);
        session.consoleCommand("mineqtt devices setting " + slug + " range 16");
        setConfig(session, true, false);
    }

    /** With a broker: Home Assistant's siren JSON on /set and a notify payload on /set/announce. */
    private static void published(GameTestSession session, String slug, BrokerConfig broker) {
        String stateTopic = "mineqtt/" + GameTestSession.BRIDGE_ID + "/" + slug;
        session.context().waitTicks(Speaker.ANNOUNCEMENT_SECONDS * 20 * 2 + 5);
        try (BrokerProbe probe = BrokerProbe.connect(broker)) {
            probe.publish(stateTopic + "/set", "{\"state\":\"ON\",\"tone\":\"doorbell\",\"duration\":3,\"volume_level\":0.8}", false);
            session.check(session.await(() -> on(session) && state(session).get("tone").getAsString().equals("doorbell") && state(session).get("volume").getAsInt() == 80,
                    ANSWER_TICKS), "Home Assistant's siren JSON on /set turns it on with its tone and volume (" + state(session) + ")");
            session.context().waitTicks(30);
            Optional<JsonObject> retained = probe.retained(stateTopic).map(text -> JsonParser.parseString(text).getAsJsonObject());
            session.check(retained.filter(json -> json.get("state").getAsString().equals("ON") && json.get("tone").getAsString().equals("doorbell")).isPresent(),
                    "the retained state shows the siren on (" + retained + ")");
            int mark = ChatLog.mark();
            probe.publish(stateTopic + "/set/announce", "42", false);
            session.check(session.awaitChat(mark, l -> l.text().endsWith("] 42"), ANSWER_TICKS).isPresent(),
                    "a notify payload that looks like a number is shown as its text (" + ChatLog.since(mark) + ")");
            probe.publish(stateTopic + "/set", "{\"state\":\"OFF\"}", false);
            session.check(session.await(() -> !on(session), ANSWER_TICKS), "OFF on /set stops it");
        }
    }

    private static void setConfig(GameTestSession session, boolean allow, boolean chat) {
        session.onServer(s -> {
            MineQTTConfig.speakerAllowAnnouncements = allow;
            MineQTTConfig.speakerAnnounceInChat = chat;
            return null;
        });
    }

    private static boolean on(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockEntity(SPEAKER) instanceof SpeakerBlockEntity speaker && speaker.sounding())
                && state(session).get("state").getAsString().equals("ON");
    }

    private static int sounds(GameTestSession session) {
        return session.onServer(s -> s.overworld().getBlockEntity(SPEAKER) instanceof SpeakerBlockEntity speaker ? speaker.soundsPlayed() : -1);
    }

    private static void setblock(GameTestSession session, BlockPos pos, String block) {
        session.consoleCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
    }

    private static JsonObject state(GameTestSession session) {
        return session.onServer(s -> {
            StateWriter state = new StateWriter();
            if (s.overworld().getBlockEntity(SPEAKER) instanceof DeviceHost host) host.writeState(state);
            return state.json();
        });
    }}

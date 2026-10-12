package art.rehra.mineqtt.ui.device;

import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.device.Port;
import art.rehra.mineqtt.device.timer.core.CronTimerSchedule;
import art.rehra.mineqtt.device.timer.core.NextFire;
import art.rehra.mineqtt.device.types.Timers;
import art.rehra.mineqtt.ui.framework.MqttTab;
import art.rehra.mineqtt.util.CronSchedule;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The Cron Timer's Schedule tab (M4 plan D5), after Controls: the one custom tab of a generated screen. Its seven fields
 * (tick, second, minute, hour, day, month, weekday) are edited one box each and checked one by one; presets fill them;
 * Set sends the joined expression as the {@code cron} setting, through the same {@code DeviceConfigPayload} as the
 * Controls tab's text row, so the server judges it as {@code /mineqtt devices setting <cron timer> cron ...}; and the
 * next three fire times of the edited expression show before it is sent, in the server's zone. Server-safe; the pure
 * rules are here, {@code CronScheduleTabView} draws it. No slots.
 */
public class CronScheduleTab extends MqttTab {

    public static final String ID = "schedule";
    /** The fields of an expression in its order, tick to weekday; also the last part of their lang keys. */
    public static final List<String> FIELDS = List.of("tick", "second", "minute", "hour", "day", "month", "weekday");
    /** How many next fire times the tab shows. */
    public static final int PREVIEW = 3;
    // What a field reads as while the others are being checked: any value.
    private static final String ANY = "*";

    /** A ready schedule: its lang key and its expression. */
    public record Preset(String key, String expression) {
    }

    /** The presets, in the order the tab's preset button steps through them. */
    public static final List<Preset> PRESETS = List.of(
            new Preset("every_5_seconds", "0 */5 * * * * *"),
            new Preset("every_minute", "0 0 * * * * *"),
            new Preset("every_hour", "0 0 0 * * * *"),
            new Preset("daily", "0 0 0 6 * * *"),
            new Preset("weekdays", "0 0 30 7 * * 1-5"),
            new Preset("weekends", "0 0 0 9 * * 0,6"));

    private final DeviceTabs.Control control;

    public CronScheduleTab(DeviceType type) {
        super(ID);
        if (!appliesTo(type)) throw new IllegalArgumentException(type.id() + " has no cron expression");
        this.control = DeviceTabs.Control.of(type.port(Port.Direction.IN, Timers.CRON.key()).orElseThrow());
    }

    /** Whether a type's screen has the tab: a type with the Cron Timer's {@code cron} setting. */
    public static boolean appliesTo(DeviceType type) {
        return type.setting(Timers.CRON.key()).map(setting -> setting.field().equals(Timers.CRON)).orElse(false);
    }

    /** The {@code cron} setting's control, which sends what the tab sets. */
    public DeviceTabs.Control control() {
        return control;
    }

    /**
     * An expression's fields, one per box: its seven tokens, or empty boxes for an empty expression; the tokens of an
     * expression of another length fill the first boxes, the rest stay empty.
     */
    public static List<String> split(String expression) {
        List<String> tokens = new ArrayList<>(Collections.nCopies(FIELDS.size(), ""));
        String text = expression == null ? "" : expression.strip();
        if (text.isEmpty()) return tokens;
        String[] parts = text.split("\\s+");
        for (int i = 0; i < Math.min(parts.length, tokens.size()); i++) tokens.set(i, parts[i]);
        return tokens;
    }

    /** The expression of the boxes: their tokens joined by spaces; empty when every box is. */
    public static String join(List<String> tokens) {
        List<String> stripped = tokens.stream().map(String::strip).toList();
        return stripped.stream().allMatch(String::isEmpty) ? "" : String.join(" ", stripped);
    }

    /**
     * Why the token of field {@code index} does not parse, or empty when it does: each box is checked alone, with every
     * other field any value.
     */
    public static Optional<String> fieldProblem(int index, String token) {
        String text = token == null ? "" : token.strip();
        if (text.isEmpty()) return Optional.of("empty");
        if (text.chars().anyMatch(Character::isWhitespace)) return Optional.of("one value, range, list or step only");
        List<String> probe = new ArrayList<>(Collections.nCopies(FIELDS.size(), ANY));
        probe.set(index, text);
        return CronTimerSchedule.problem(String.join(" ", probe));
    }

    /**
     * The next {@code count} fire times of an expression after {@code now} in {@code zone}, to the second; fewer when it
     * matches less often within 400 days, none for an expression that does not parse.
     */
    public static List<ZonedDateTime> nextFires(String expression, ZoneId zone, Instant now, int count) {
        if (CronTimerSchedule.problem(expression).isPresent() || expression.isBlank()) return List.of();
        CronSchedule schedule = CronSchedule.parse(expression.strip());
        List<ZonedDateTime> fires = new ArrayList<>();
        LocalDateTime from = now.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
        while (fires.size() < count) {
            LocalDateTime next = CronSchedule.findNextTrigger(schedule, from, zone);
            if (next == null) break;
            fires.add(next.truncatedTo(ChronoUnit.SECONDS).atZone(zone));
            from = next.truncatedTo(ChronoUnit.SECONDS);
        }
        return List.copyOf(fires);
    }

    /**
     * The zone the server reads a cron expression in, as far as the client can tell: the offset of the timer's next fire,
     * else the client's own zone.
     */
    public static ZoneId zoneOf(String nextFire) {
        return NextFire.parse(nextFire).<ZoneId>map(time -> time.getOffset()).orElse(ZoneId.systemDefault());
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Items.CLOCK);
    }

    @Override
    public Component title() {
        return Component.translatable("mineqtt.tab.schedule");
    }
}

package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Draws the {@link ConsoleDevicesTab}: the devices the player may configure (all for an operator), what needs attention
 * first, each with how it runs at the right, and its slug, type, place and fault or problem on hover.
 */
public class ConsoleDevicesTabView extends ConsoleListView<ConsoleView.DeviceRow> {

    public ConsoleDevicesTabView(TabbedMqttScreen screen) {
        super(screen);
    }

    @Override
    protected List<ConsoleView.DeviceRow> rows(ConsoleView view) {
        return view.devices();
    }

    @Override
    protected Component header(ConsoleView view) {
        long troubled = view.devices().stream().filter(ConsoleView.DeviceRow::troubled).count();
        String scope = view.operator() ? "all" : "own";
        Component count = view.deviceTotal() > view.devices().size()
                ? Component.translatable("mineqtt.console.devices.header.capped." + scope, view.deviceTotal(), view.devices().size())
                : Component.translatable(counted("mineqtt.console.devices.header." + scope, view.deviceTotal()), view.deviceTotal());
        return troubled == 0 ? count : Component.translatable(counted("mineqtt.console.devices.header.troubled", troubled), count, troubled);
    }

    @Override
    protected int headerColor(ConsoleView view) {
        return view.devices().stream().anyMatch(ConsoleView.DeviceRow::troubled) ? WARNING_COLOR : LABEL_COLOR;
    }

    @Override
    protected Component empty(ConsoleView view) {
        return Component.translatable(view.operator() ? "mineqtt.console.devices.empty.all" : "mineqtt.console.devices.empty.own");
    }

    @Override
    protected Component name(ConsoleView.DeviceRow row) {
        return Component.literal(row.name());
    }

    @Override
    protected Component word(ConsoleView.DeviceRow row) {
        boolean problem = !row.detail().isEmpty() && (row.state() == ConsoleView.State.ONLINE || row.state() == ConsoleView.State.NO_BROKER);
        return Component.translatable(problem ? "mineqtt.console.state.problem" : "mineqtt.console.state." + state(row));
    }

    @Override
    protected int wordColor(ConsoleView.DeviceRow row) {
        return switch (row.state()) {
            case ONLINE -> row.detail().isEmpty() ? ONLINE_COLOR : WARNING_COLOR;
            case NO_BROKER -> WARNING_COLOR;
            case UNLOADED -> MUTED_COLOR;
            case FAULTED, UNKNOWN_TYPE -> REFUSED_COLOR;
        };
    }

    @Override
    protected Component details(ConsoleView.DeviceRow row) {
        Component details = Component.translatable("mineqtt.console.devices.row.tooltip", row.name(), row.slug(), row.type(), row.where(),
                Component.translatable("mineqtt.console.state." + state(row)));
        return row.detail().isEmpty() ? details : Component.translatable("mineqtt.console.devices.row.detail", details, row.detail());
    }

    private static String state(ConsoleView.DeviceRow row) {
        return row.state().name().toLowerCase(Locale.ROOT);
    }
}

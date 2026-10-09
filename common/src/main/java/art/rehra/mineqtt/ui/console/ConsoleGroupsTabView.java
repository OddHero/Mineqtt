package art.rehra.mineqtt.ui.console;

import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Draws the {@link ConsoleGroupsTab}: the groups the player manages (all for an operator), each with its member count at
 * the right, and its slug, member type and Home Assistant discovery on hover.
 */
public class ConsoleGroupsTabView extends ConsoleListView<ConsoleView.GroupRow> {

    public ConsoleGroupsTabView(TabbedMqttScreen screen) {
        super(screen);
    }

    @Override
    protected List<ConsoleView.GroupRow> rows(ConsoleView view) {
        return view.groups();
    }

    @Override
    protected Component header(ConsoleView view) {
        String scope = view.operator() ? "all" : "own";
        return view.groupTotal() > view.groups().size()
                ? Component.translatable("mineqtt.console.groups.header.capped." + scope, view.groupTotal(), view.groups().size())
                : Component.translatable(counted("mineqtt.console.groups.header." + scope, view.groupTotal()), view.groupTotal());
    }

    @Override
    protected int headerColor(ConsoleView view) {
        return LABEL_COLOR;
    }

    @Override
    protected Component empty(ConsoleView view) {
        return Component.translatable(view.operator() ? "mineqtt.console.groups.empty.all" : "mineqtt.console.groups.empty.own");
    }

    @Override
    protected Component name(ConsoleView.GroupRow row) {
        return Component.literal(row.name());
    }

    @Override
    protected Component word(ConsoleView.GroupRow row) {
        return Component.translatable(counted("mineqtt.console.groups.members", row.members()), row.members());
    }

    @Override
    protected int wordColor(ConsoleView.GroupRow row) {
        return row.members() == 0 ? MUTED_COLOR : VALUE_COLOR;
    }

    @Override
    protected Component details(ConsoleView.GroupRow row) {
        Component type = row.memberType().isEmpty() ? Component.translatable("mineqtt.console.groups.no_type") : Component.literal(row.memberType());
        return Component.translatable(row.discovered() ? "mineqtt.console.groups.row.tooltip.discovered" : "mineqtt.console.groups.row.tooltip",
                row.name(), row.slug(), type);
    }
}

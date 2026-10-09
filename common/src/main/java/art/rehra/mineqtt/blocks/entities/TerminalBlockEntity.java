package art.rehra.mineqtt.blocks.entities;

import art.rehra.mineqtt.ui.console.BridgeConsole;
import art.rehra.mineqtt.ui.framework.TabbedScreenHost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The block entity of the Bridge Console ({@code terminal_block}, M2 plan §4.2 M2-23). The console shows the bridge, not
 * itself: it keeps only the tab its screen last showed. It is no device and no container, has no owner and sends clients
 * nothing. A console placed before it had a block entity gets one at its first lookup, so nothing is migrated.
 */
public class TerminalBlockEntity extends BlockEntity implements TabbedScreenHost {
    private String lastTabId = "";

    public TerminalBlockEntity(BlockPos pos, BlockState blockState) {
        super(MineqttBlockEntityTypes.TERMINAL_BLOCK.get(), pos, blockState);
    }

    @Override
    public void openScreen(ServerPlayer player) {
        BridgeConsole.open(player, this);
    }

    @Override
    public String getLastTabId() {
        return lastTabId;
    }

    @Override
    public void setLastTabId(String tabId) {
        if (tabId == null || tabId.equals(lastTabId)) return;
        lastTabId = tabId;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!lastTabId.isEmpty()) output.putString("lastTabId", lastTabId);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        lastTabId = input.getString("lastTabId").orElse("");
    }
}

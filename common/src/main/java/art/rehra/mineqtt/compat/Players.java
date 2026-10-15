package art.rehra.mineqtt.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Player accessors that changed between versions. */
public final class Players {

    private Players() {
    }

    public static GameType gameMode(ServerPlayer player) {
        //? if >=1.21.5 {
        return player.gameMode();
        //?} else {
        /*return player.gameMode.getGameModeForPlayer();
        *///?}
    }
}

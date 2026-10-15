package art.rehra.mineqtt.compat;

//? if <1.21.9 {
/*import com.mojang.authlib.GameProfile;

import java.util.UUID;

// Minecraft's NameAndId of 1.21.9 for the versions before, where a game profile stands for a player: Stonecutter swaps
// the imports of net.minecraft.server.players.NameAndId for this class.
public record NameAndId(UUID id, String name) {
    public NameAndId(GameProfile profile) {
        this(profile.getId(), profile.getName());
    }

    public GameProfile toProfile() {
        return new GameProfile(id, name);
    }
}
*///?}

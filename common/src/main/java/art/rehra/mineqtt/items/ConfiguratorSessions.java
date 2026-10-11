package art.rehra.mineqtt.items;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The Configurator's modes besides Configure (Link, Group, Copy-Paste, Bind), as one: a click on a device or into the
 * air in a mode goes to that mode, and what a player began in any mode is forgotten when they change the mode or leave,
 * and when the server stops. Bind keeps nothing between clicks. Server thread only.
 */
public final class ConfiguratorSessions {
    private ConfiguratorSessions() {
    }

    /** A click in {@code mode} on a device the player may use, within reach or from afar. */
    public static void click(ConfiguratorMode mode, ServerPlayer player, DeviceBlockEntity device) {
        switch (mode) {
            case LINK -> ConfiguratorLink.click(player, device);
            case GROUP -> ConfiguratorGroup.click(player, device);
            case COPY_PASTE -> ConfiguratorCopy.click(player, device);
            case BIND -> ConfiguratorBind.click(player, device);
            case CONFIGURE -> throw new IllegalArgumentException("Configure mode opens screens");
        }
    }

    /** A click in {@code mode} into the air, or on a block that is no device: stops what the player began, or says how the mode works. */
    public static void clickAir(ConfiguratorMode mode, ServerPlayer player) {
        switch (mode) {
            case LINK -> ConfiguratorLink.clickAir(player);
            case GROUP -> ConfiguratorGroup.clickAir(player);
            case COPY_PASTE -> ConfiguratorCopy.clickAir(player);
            case BIND -> ConfiguratorBind.clickAir(player);
            case CONFIGURE -> throw new IllegalArgumentException("Configure mode opens screens");
        }
    }

    /** What a player is told who clicks a MineQTT block that is no device in {@code mode}: it cannot take part. */
    public static Component notDevice(ConfiguratorMode mode, Component block) {
        return Component.translatable("mineqtt.configurator." + mode.getSerializedName() + ".not_device", block);
    }

    /** The player changed the Configurator's mode or left: what they began in any mode is forgotten. */
    public static void forget(UUID player) {
        ConfiguratorLink.forget(player);
        ConfiguratorGroup.forget(player);
        ConfiguratorCopy.forget(player);
    }

    /** The server stops. */
    public static void clear() {
        ConfiguratorLink.clear();
        ConfiguratorGroup.clear();
        ConfiguratorCopy.clear();
    }
}

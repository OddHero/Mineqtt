package art.rehra.mineqtt.items;

import art.rehra.mineqtt.blocks.entities.DeviceBlockEntity;
import art.rehra.mineqtt.device.DeviceConfig;
import art.rehra.mineqtt.device.DeviceRecord;
import art.rehra.mineqtt.device.DeviceRuntime;
import art.rehra.mineqtt.device.LinkSetting;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * A device a Configurator mode step was used on (Link, Group, Copy-Paste): one that runs on the player's server and that
 * they may configure, as every step needs. Server thread only.
 */
record ConfiguratorTarget(MineqttRuntime runtime, DeviceRecord record) {

    /**
     * The device clicked, when it runs and the player may configure it; otherwise empty, and the player was told why in
     * the action bar.
     */
    static Optional<ConfiguratorTarget> of(ServerPlayer player, DeviceBlockEntity device) {
        MineqttRuntime runtime = runtime(player).orElse(null);
        UUID id = device.deviceLink().id();
        DeviceRecord record = runtime == null || id == null ? null : runtime.registry().record(id);
        if (record == null || !device.deviceLink().attached()) {
            Component name = record == null || record.name().isBlank() ? device.getBlockState().getBlock().getName() : Component.literal(record.name());
            tell(player, Component.translatable("mineqtt.configurator.not_running", name));
            return Optional.empty();
        }
        if (!runtime.devices().configurer(player).test(id)) {
            tell(player, Component.translatable("mineqtt.device.refused", DeviceConfig.refusal(LinkSetting.Gate.CONFIGURE)));
            return Optional.empty();
        }
        return Optional.of(new ConfiguratorTarget(runtime, record));
    }

    /** The MineQTT runtime of the player's server, if it runs. */
    static Optional<MineqttRuntime> runtime(ServerPlayer player) {
        return MineqttRuntime.current().filter(running -> running.server() == player.level().getServer());
    }

    DeviceRuntime devices() {
        return runtime.devices();
    }

    /** Tells the player in the action bar, where every Configurator step answers. */
    static void tell(ServerPlayer player, Component message) {
        player.sendSystemMessage(message, true);
    }
}

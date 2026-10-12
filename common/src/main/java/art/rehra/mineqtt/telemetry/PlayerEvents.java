package art.rehra.mineqtt.telemetry;

import art.rehra.mineqtt.presence.PrivacyCommands;
import art.rehra.mineqtt.presence.PrivacyPolicy;
import art.rehra.mineqtt.runtime.MineqttRuntime;
import art.rehra.mineqtt.telemetry.core.NamePolicy;
import art.rehra.mineqtt.telemetry.core.PlayerEventMessage;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;

/**
 * The game side of the bridge's player events (M4 plan D7): joins, leaves, deaths and advancements of players, handed
 * to the running runtime's bridge. {@link PlayerEventMessage} decides what is published and with which name, from the
 * telemetry's {@link NamePolicy}, which follows each player's consent (D9): deaths and advancements only of players who
 * consent. A joining player is first shown the privacy prompt, once, when the server could share anything about them,
 * and the presence devices (D10) follow joins and quits.
 * Nothing is published while MineQTT is not running on that server.
 */
public final class PlayerEvents {
    private PlayerEvents() {
    }

    /** Registers the listeners, once from {@code MineQTT.init}. */
    public static void register() {
        PlayerEvent.PLAYER_JOIN.register(player -> runtime(player).ifPresent(runtime -> {
            PrivacyPolicy.Prompt prompt = runtime.privacy().joined(player);
            PrivacyCommands.prompt(prompt, runtime.privacy().policy()).ifPresent(player::sendSystemMessage);
            runtime.presence().joined(player.getUUID(), name(player));
            runtime.bridge().publishPlayerEvent(PlayerEventMessage.join(player.getUUID(), name(player), runtime.telemetry().namePolicy()));
        }));
        PlayerEvent.PLAYER_QUIT.register(player -> runtime(player).ifPresent(runtime -> {
            runtime.presence().left(player.getUUID());
            runtime.bridge().publishPlayerEvent(PlayerEventMessage.leave(player.getUUID(), name(player), runtime.telemetry().namePolicy()));
        }));
        EntityEvent.LIVING_DEATH.register(PlayerEvents::died);
        PlayerEvent.PLAYER_ADVANCEMENT.register(PlayerEvents::advanced);
    }

    // Never cancels a death: the event only listens.
    private static EventResult died(LivingEntity entity, DamageSource source) {
        if (entity instanceof ServerPlayer player) {
            runtime(player).ifPresent(runtime -> PlayerEventMessage.death(player.getUUID(), name(player), source.getMsgId(),
                    runtime.telemetry().namePolicy()).ifPresent(runtime.bridge()::publishPlayerEvent));
        }
        return EventResult.pass();
    }

    private static void advanced(ServerPlayer player, AdvancementHolder advancement) {
        Optional<DisplayInfo> display = advancement.value().display();
        runtime(player).ifPresent(runtime -> PlayerEventMessage.advancement(player.getUUID(), name(player), advancement.id().toString(),
                display.isPresent(), display.map(DisplayInfo::shouldAnnounceChat).orElse(false), display.map(DisplayInfo::isHidden).orElse(false),
                runtime.telemetry().namePolicy()).ifPresent(runtime.bridge()::publishPlayerEvent));
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    private static Optional<MineqttRuntime> runtime(ServerPlayer player) {
        return MineqttRuntime.current().filter(runtime -> runtime.server() == player.level().getServer());
    }
}

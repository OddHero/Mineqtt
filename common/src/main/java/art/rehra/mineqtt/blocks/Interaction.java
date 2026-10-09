package art.rehra.mineqtt.blocks;

import art.rehra.mineqtt.device.DeviceType;
import art.rehra.mineqtt.items.ConfiguratorMode;
import art.rehra.mineqtt.items.CyberdeckDataUtil;
import art.rehra.mineqtt.items.CyberdeckItem;
import art.rehra.mineqtt.security.C2SGuard;
import art.rehra.mineqtt.ui.CyberdeckMenu;
import art.rehra.mineqtt.ui.device.AdvancedTab;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * What a right-click on a MineQTT block does (design §7.4, M2 plan §4.2 M2-13): a plain right-click uses a device that
 * has a natural use (the lamp toggles) and opens the screen of every other block; sneak + right-click configures, so it
 * opens the screen; a renamed paper fills a device's Raw Link channel and a named name tag renames a device, neither
 * used up; the Configurator configures. A sneaking player holding a block places it against the block, as vanilla lets
 * them place against a chest. In Link, Group and Copy-Paste mode the Configurator's plain click is a step of that mode
 * (M2-16, M2-17), and sneaking it opens the screen as in Configure mode. {@link #decide} is the pure table;
 * {@link BaseMqttBlock#click} carries it out, except for a spectator, who only looks, as into a chest: their click
 * opens the screen, where they change nothing.
 */
public final class Interaction {

    /** What a click does. */
    public enum Outcome {
        /** Nothing here: the game goes on as without MineQTT (a sneaking player places the held block). */
        PASS,
        /** The device's {@link DeviceType#use() use} action. */
        USE,
        /** The block's screen opens. */
        SCREEN,
        /** The renamed paper's name becomes the device's Raw Link channel. */
        PAPER,
        /** The name tag's name becomes the device's name. */
        RENAME,
        /** A step of the Configurator's Link mode ({@code ConfiguratorLink}). */
        LINK,
        /** A step of the Configurator's Group mode ({@code ConfiguratorGroup}). */
        GROUP,
        /** A step of the Configurator's Copy-Paste mode ({@code ConfiguratorCopy}). */
        COPY
    }

    /** What the clicking hand holds, as far as a click on a MineQTT block cares. */
    public enum Held {
        /** Nothing, or an item without a meaning here. */
        OTHER,
        /** An item that places a block. */
        PLACEABLE,
        /** A paper with a name ({@link AdvancedTab#paperChannel}). */
        NAMED_PAPER,
        /** A name tag with a name. */
        NAMED_TAG,
        /** The Configurator (the {@code cyberdeck} item) in Configure mode. */
        CONFIGURATOR,
        /** The Configurator in Link mode. */
        CONFIGURATOR_LINK,
        /** The Configurator in Group mode. */
        CONFIGURATOR_GROUP,
        /** The Configurator in Copy-Paste mode. */
        CONFIGURATOR_COPY;

        public static Held of(ItemStack stack) {
            if (stack.getItem() instanceof CyberdeckItem) return of(CyberdeckDataUtil.mode(stack));
            if (AdvancedTab.paperChannel(stack).isPresent()) return NAMED_PAPER;
            if (nameTag(stack).isPresent()) return NAMED_TAG;
            if (stack.getItem() instanceof BlockItem) return PLACEABLE;
            return OTHER;
        }

        /** The Configurator in a mode. */
        public static Held of(ConfiguratorMode mode) {
            return switch (mode) {
                case CONFIGURE -> CONFIGURATOR;
                case LINK -> CONFIGURATOR_LINK;
                case GROUP -> CONFIGURATOR_GROUP;
                case COPY_PASTE -> CONFIGURATOR_COPY;
            };
        }

        /**
         * What the clicking hand holds; a hand without anything of meaning here counts the Configurator in the other
         * hand, so a Configurator in the off hand configures within reach as it does from afar ({@code CyberdeckItem#use}).
         */
        public static Held of(Player player, InteractionHand hand) {
            Held held = of(player.getItemInHand(hand));
            return held == OTHER && C2SGuard.holdsConfigurator(player) ? of(CyberdeckMenu.heldConfigurator(player)) : held;
        }
    }

    private Interaction() {
    }

    /**
     * @param sneaking whether the player sneaks
     * @param held     what the clicking hand holds
     * @param type     the device's type, or null for a block that is no device
     */
    public static Outcome decide(boolean sneaking, Held held, @Nullable DeviceType type) {
        // The Configurator configures whatever it is used on: a step of its mode, or the screen while sneaking.
        Outcome configurator = switch (held) {
            case CONFIGURATOR -> Outcome.SCREEN;
            case CONFIGURATOR_LINK -> Outcome.LINK;
            case CONFIGURATOR_GROUP -> Outcome.GROUP;
            case CONFIGURATOR_COPY -> Outcome.COPY;
            default -> null;
        };
        if (configurator != null) return sneaking ? Outcome.SCREEN : configurator;
        if (sneaking) return held == Held.PLACEABLE ? Outcome.PASS : Outcome.SCREEN;
        if (type == null) return Outcome.SCREEN;
        if (held == Held.NAMED_PAPER && type.rawLink().isPresent()) return Outcome.PAPER;
        if (held == Held.NAMED_TAG) return Outcome.RENAME;
        return type.use().isPresent() ? Outcome.USE : Outcome.SCREEN;
    }

    /** The name a name tag gives, stripped; empty for any other item and for a name tag without a name. */
    public static Optional<String> nameTag(ItemStack stack) {
        if (!stack.is(Items.NAME_TAG)) return Optional.empty();
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        String text = name == null ? "" : name.getString().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}

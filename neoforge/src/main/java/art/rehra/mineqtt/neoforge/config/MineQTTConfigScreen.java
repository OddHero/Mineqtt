package art.rehra.mineqtt.neoforge.config;

import art.rehra.mineqtt.client.PasswordMask;
import art.rehra.mineqtt.config.ConfigValidation;
import art.rehra.mineqtt.config.MineQTTConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * NeoForge's generated config screen for {@code mineqtt.toml}, with the password masked, the broker URI checked while it
 * is typed, and the keys MineQTT maintains itself hidden.
 */
public final class MineQTTConfigScreen {
    private static final int MAX_PASSWORD_LENGTH = 256;

    private MineQTTConfigScreen() {
    }

    public static Screen createConfigScreen(ModContainer container, Screen parent) {
        return new ConfigurationScreen(container, parent, TopSection::new);
    }

    /** The top level of the file. Nested sections use NeoForge's own section screen, so the overrides stay up here. */
    private static final class TopSection extends ConfigurationScreen.ConfigurationSectionScreen {
        TopSection(ConfigurationScreen parent, ModConfig.Type type, ModConfig modConfig, Component title) {
            super(parent, type, modConfig, title, (context, key, original) -> key.equals("configVersion") ? null : original);
        }

        @Override
        protected Element createSection(String key, UnmodifiableConfig subconfig, UnmodifiableConfig subsection) {
            return key.equals("legacy") ? null : super.createSection(key, subconfig, subsection);
        }

        @Override
        protected Element createStringValue(String key, Predicate<String> tester, Supplier<String> source, Consumer<String> target) {
            return switch (key) {
                // The spec accepts any broker so a typo in the file is kept and reported; here it must parse.
                case "broker" -> super.createStringValue(key, ConfigValidation::isValidBroker, source, target);
                case "password" -> createPasswordValue(key, source, target);
                default -> super.createStringValue(key, tester, source, target);
            };
        }

        private Element createPasswordValue(String key, Supplier<String> source, Consumer<String> target) {
            EditBox box = new PasswordEditBox(font, getTranslationComponent(key));
            box.setMaxLength(MAX_PASSWORD_LENGTH);
            box.setValue(source.get());
            box.setTooltip(Tooltip.create(getTooltipComponent(key, null)));
            // The environment variable wins, so editing the stored password would have no effect.
            box.setEditable(!MineQTTConfig.passwordFromEnvironment());
            box.setResponder(newValue -> {
                if (!newValue.equals(source.get())) {
                    undoManager.add(value -> {
                        target.accept(value);
                        onChanged(key);
                    }, newValue, value -> {
                        target.accept(value);
                        onChanged(key);
                    }, source.get());
                }
            });
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), box);
        }
    }

    /** Shows asterisks and keeps the value out of the narration. */
    private static final class PasswordEditBox extends EditBox {
        PasswordEditBox(Font font, Component message) {
            super(font, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, message);
            addFormatter(PasswordMask.FORMATTER);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.translatable("gui.narrate.editBox", getMessage(), "");
        }
    }
}

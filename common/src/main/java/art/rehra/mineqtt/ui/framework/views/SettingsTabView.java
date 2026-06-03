package art.rehra.mineqtt.ui.framework.views;

import art.rehra.mineqtt.blocks.entities.BaseMqttBlockEntity;
import art.rehra.mineqtt.network.MineqttNetworking;
import art.rehra.mineqtt.ui.framework.GuiText;
import art.rehra.mineqtt.ui.framework.MqttTabView;
import art.rehra.mineqtt.ui.framework.TabbedMqttMenu;
import art.rehra.mineqtt.ui.framework.TabbedMqttScreen;
import com.mojang.authlib.properties.PropertyMap;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.Optional;

/**
 * Renders topic configuration info above the two frequency slots, and exposes
 * a "Private" toggle that prefixes the block's topic with the owner's username.
 *
 * <p>The toggle is rendered as a 16x16 slot-sized icon: when ON, it shows the
 * owner's player head; when OFF, it shows a flat grey square. The button is
 * shown for both block-backed menus and the cyberdeck.</p>
 */
public class SettingsTabView implements MqttTabView {

    /**
     * Inner usable width of the GUI panel for label/text rendering.
     */
    private static final int CONTENT_W = TabbedMqttMenu.GUI_WIDTH - 16; // 8px padding each side
    /**
     * Slot-sized button (matches the 16x16 inner area of vanilla slot widgets).
     */
    private static final int BTN_SIZE = 16;

    private final TabbedMqttScreen screen;
    private PrivateModeButton privateBtn;

    public SettingsTabView(TabbedMqttScreen screen) {
        this.screen = screen;
    }

    private static ItemStack cyberdeckStack(TabbedMqttMenu menu) {
        ItemStack main = menu.player.getMainHandItem();
        if (main.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem) return main;
        ItemStack off = menu.player.getOffhandItem();
        if (off.getItem() instanceof art.rehra.mineqtt.items.CyberdeckItem) return off;
        return ItemStack.EMPTY;
    }

    @Override
    public void init(TabbedMqttScreen screen, int guiLeft, int guiTop) {
        TabbedMqttMenu menu = screen.getMenu();
        // Position the head button to the left of the two frequency slots, aligned
        // with the slot row (slots sit at y=36..52 in the panel).
        int x = guiLeft + 32;
        int y = guiTop + 36;

        privateBtn = new PrivateModeButton(x, y, () -> currentPrivate(menu), () -> currentOwner(menu), this::togglePrivate);
    }

    private boolean currentPrivate(TabbedMqttMenu menu) {
        if (menu.blockPos != null) {
            var be = menu.player.level().getBlockEntity(menu.blockPos);
            return be instanceof BaseMqttBlockEntity m && m.isPrivateMode();
        }
        return art.rehra.mineqtt.items.CyberdeckDataUtil.isPrivate(cyberdeckStack(menu));
    }

    private String currentOwner(TabbedMqttMenu menu) {
        if (menu.blockPos != null) {
            var be = menu.player.level().getBlockEntity(menu.blockPos);
            if (be instanceof BaseMqttBlockEntity m && !m.getOwnerName().isEmpty()) return m.getOwnerName();
        }
        // Fallback to the viewing player (used for cyberdeck and pre-feature blocks).
        return menu.player.getGameProfile().getName();
    }

    private void togglePrivate() {
        TabbedMqttMenu menu = screen.getMenu();
        if (menu.blockPos != null) {
            var be = menu.player.level().getBlockEntity(menu.blockPos);
            if (!(be instanceof BaseMqttBlockEntity m)) return;
            boolean newState = !m.isPrivateMode();
            // Optimistic client-side update so the topic preview reflects the new state immediately.
            m.setPrivateMode(newState);
            NetworkManager.sendToServer(new MineqttNetworking.SetPrivateModePayload(Optional.of(menu.blockPos), newState));
        } else {
            // Cyberdeck: persist on the held cyberdeck item via a DataComponent.
            ItemStack stack = cyberdeckStack(menu);
            boolean newState = !art.rehra.mineqtt.items.CyberdeckDataUtil.isPrivate(stack);
            // Optimistic client-side update so the preview reflects immediately.
            art.rehra.mineqtt.items.CyberdeckDataUtil.setPrivate(stack, newState);
            NetworkManager.sendToServer(new MineqttNetworking.SetPrivateModePayload(Optional.empty(), newState));
        }
    }

    @Override
    public List<? extends GuiEventListener> widgets() {
        return privateBtn != null ? List.of(privateBtn) : List.of();
    }

    @Override
    public void renderContent(GuiGraphics g, int guiLeft, int guiTop, int mouseX, int mouseY, float partialTick) {
        TabbedMqttMenu menu = screen.getMenu();
        String topic;
        boolean enabled;

        if (menu.blockPos != null) {
            var be = menu.player.level().getBlockEntity(menu.blockPos);
            if (!(be instanceof BaseMqttBlockEntity mqtt)) return;
            topic = mqtt.getCombinedTopic();
            enabled = mqtt.isEnabled();
        } else {
            // Cyberdeck (item-based): build the topic from the slots, with optional client-side private prefix.
            var baseStack = menu.container.getItem(0);
            var subStack = menu.container.getItem(1);
            String base = baseStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(baseStack);
            String sub = subStack.isEmpty() ? "" : BaseMqttBlockEntity.parseItemStackTopic(subStack);
            String combined = sub.isEmpty() ? base : base + "/" + sub;
            if (art.rehra.mineqtt.items.CyberdeckDataUtil.isPrivate(cyberdeckStack(menu))) {
                String owner = menu.player.getGameProfile().getName();
                combined = combined.isEmpty() ? owner : owner + "/" + combined;
                enabled = true;
            } else {
                enabled = !baseStack.isEmpty();
            }
            topic = combined;
        }

        int x = guiLeft + 8;
        // Slot labels — centered above each 16px slot (slots at y=36..52).
        GuiText.drawCentered(g, "Base", guiLeft + 61, guiTop + 26, 24, 0xFF555555);
        GuiText.drawCentered(g, "Sub", guiLeft + 97, guiTop + 26, 24, 0xFF555555);

        // Topic info — rendered BELOW the slots so it never overlaps them.
        int topicY = guiTop + 58;

        if (enabled && !topic.isEmpty()) {
            GuiText.drawTruncated(g, "Topic:", x, topicY, CONTENT_W, 0xFF555555);
            GuiText.drawAutoScaled(g, topic, x + 4, topicY + 10, CONTENT_W - 4, 30, 0xFF0088FF, mouseX, mouseY);
        } else {
            GuiText.drawTruncated(g, "No topic configured", x, topicY, CONTENT_W, 0xFF666666, mouseX, mouseY);
            GuiText.drawTruncated(g, "Place item in left slot to enable", x, topicY + 10, CONTENT_W, 0xFF888888, mouseX, mouseY);
        }
    }

    /**
     * Small 16x16 toggle that renders the owner's player head when ON and a flat
     * grey square when OFF. Hover tooltip explains the toggle and shows the owner.
     */
    private static final class PrivateModeButton extends AbstractButton {
        private final java.util.function.BooleanSupplier isOn;
        private final java.util.function.Supplier<String> ownerName;
        private final Runnable onToggle;
        private ItemStack cachedHead = ItemStack.EMPTY;
        private String cachedOwner = "";

        PrivateModeButton(int x, int y, java.util.function.BooleanSupplier isOn,
                          java.util.function.Supplier<String> ownerName, Runnable onToggle) {
            super(x, y, BTN_SIZE, BTN_SIZE, Component.translatable("mineqtt.tab.settings.private.tooltip"));
            this.isOn = isOn;
            this.ownerName = ownerName;
            this.onToggle = onToggle;
        }

        private ItemStack headFor(String name) {
            if (!name.equals(cachedOwner) || cachedHead.isEmpty()) {
                ItemStack head = new ItemStack(Items.PLAYER_HEAD);
                head.set(DataComponents.PROFILE,
                        new ResolvableProfile(Optional.of(name), Optional.empty(), new PropertyMap()));
                cachedHead = head;
                cachedOwner = name;
            }
            return cachedHead;
        }

        @Override
        public void onPress() {
            onToggle.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean on = isOn.getAsBoolean();
            // Background: subtle dark slot-like frame regardless of state, for visual consistency.
            g.fill(getX() - 1, getY() - 1, getX() + width + 1, getY() + height + 1, 0xFF373737);
            if (on) {
                String name = ownerName.get();
                if (name == null || name.isEmpty()) {
                    g.fill(getX(), getY(), getX() + width, getY() + height, 0xFFAAAAAA);
                } else {
                    g.renderItem(headFor(name), getX(), getY());
                }
            } else {
                // Flat grey when disabled.
                g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF808080);
            }
            // Hover highlight.
            if (isHovered()) {
                g.fill(getX(), getY(), getX() + width, getY() + height, 0x33FFFFFF);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}

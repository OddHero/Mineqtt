package art.rehra.mineqtt.ui.framework;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Reusable text rendering helpers for the MQTT GUI framework.
 *
 * <p>All helpers respect a {@code maxWidth} so labels can never overflow their
 * area. Text that does not fit is truncated with an ellipsis ("...") unless a
 * wrapping or auto-scaling variant is used.</p>
 *
 * <p>Tooltip-aware overloads accept the current mouse position and will register
 * a tooltip showing the full untruncated text whenever the rendered text was
 * actually truncated (or auto-scaled below 1.0) and the mouse hovers over its
 * bounds.</p>
 */
public final class GuiText {

    public static final String ELLIPSIS = "...";

    /**
     * Minimum scale used by auto-scaling helpers before falling back to truncation.
     */
    public static final float MIN_AUTO_SCALE = 0.5f;

    private GuiText() {
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    // ===== Truncated, left-aligned =====

    public static void drawTruncated(GuiGraphics g, String text, int x, int y, int maxWidth, int color) {
        drawTruncated(g, text, x, y, maxWidth, color, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static void drawTruncated(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
        drawTruncated(g, text.getString(), x, y, maxWidth, color, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /**
     * Tooltip-aware overload — shows the full text when hovered if truncation occurred.
     */
    public static void drawTruncated(GuiGraphics g, String text, int x, int y, int maxWidth, int color, int mouseX, int mouseY) {
        Font f = font();
        String s = truncate(f, text, maxWidth);
        g.drawString(f, s, x, y, color, false);
        if (!s.equals(text)) {
            int drawnW = Math.min(maxWidth, f.width(s));
            maybeTooltip(g, text, x, y, drawnW, f.lineHeight, mouseX, mouseY);
        }
    }

    public static void drawTruncated(GuiGraphics g, Component text, int x, int y, int maxWidth, int color, int mouseX, int mouseY) {
        drawTruncated(g, text.getString(), x, y, maxWidth, color, mouseX, mouseY);
    }

    // ===== Truncated, centered =====

    public static void drawCentered(GuiGraphics g, String text, int x, int y, int width, int color) {
        drawCentered(g, text, x, y, width, color, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static void drawCentered(GuiGraphics g, Component text, int x, int y, int width, int color) {
        drawCentered(g, text.getString(), x, y, width, color, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static void drawCentered(GuiGraphics g, String text, int x, int y, int width, int color, int mouseX, int mouseY) {
        Font f = font();
        String s = truncate(f, text, width);
        int tw = f.width(s);
        int drawX = x + (width - tw) / 2;
        g.drawString(f, s, drawX, y, color, false);
        if (!s.equals(text)) {
            maybeTooltip(g, text, drawX, y, tw, f.lineHeight, mouseX, mouseY);
        }
    }

    public static void drawCentered(GuiGraphics g, Component text, int x, int y, int width, int color, int mouseX, int mouseY) {
        drawCentered(g, text.getString(), x, y, width, color, mouseX, mouseY);
    }

    // ===== Truncated, right-aligned =====

    public static void drawRight(GuiGraphics g, String text, int rightX, int y, int maxWidth, int color) {
        drawRight(g, text, rightX, y, maxWidth, color, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static void drawRight(GuiGraphics g, String text, int rightX, int y, int maxWidth, int color, int mouseX, int mouseY) {
        Font f = font();
        String s = truncate(f, text, maxWidth);
        int tw = f.width(s);
        int drawX = rightX - tw;
        g.drawString(f, s, drawX, y, color, false);
        if (!s.equals(text)) {
            maybeTooltip(g, text, drawX, y, tw, f.lineHeight, mouseX, mouseY);
        }
    }

    // ===== Wrapped =====

    public static int drawWrapped(GuiGraphics g, String text, int x, int y, int maxWidth, int color) {
        Font f = font();
        List<FormattedCharSequence> lines = f.split(Component.literal(text), maxWidth);
        int lineH = f.lineHeight;
        for (FormattedCharSequence line : lines) {
            g.drawString(f, line, x, y, color, false);
            y += lineH;
        }
        return y;
    }

    // ===== Auto-scaled textbox =====

    /**
     * Renders {@code text} so that it always fits inside a {@code maxWidth × maxHeight}
     * box. The text is rendered at scale 1.0 if possible; otherwise the font is shrunk
     * down (uniformly) until it fits, or until {@link #MIN_AUTO_SCALE} is reached, in
     * which case the text is truncated with an ellipsis.
     *
     * <p>The text is left-aligned and vertically centered within the box.</p>
     */
    public static void drawAutoScaled(GuiGraphics g, String text, int x, int y, int maxWidth, int maxHeight, int color) {
        drawAutoScaled(g, text, x, y, maxWidth, maxHeight, color, Alignment.LEFT, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static void drawAutoScaled(GuiGraphics g, String text, int x, int y, int maxWidth, int maxHeight, int color, int mouseX, int mouseY) {
        drawAutoScaled(g, text, x, y, maxWidth, maxHeight, color, Alignment.LEFT, mouseX, mouseY);
    }

    public static void drawAutoScaled(GuiGraphics g, String text, int x, int y, int maxWidth, int maxHeight, int color,
                                      Alignment align, int mouseX, int mouseY) {
        if (text == null || text.isEmpty() || maxWidth <= 0 || maxHeight <= 0) return;
        Font f = font();
        int textW = f.width(text);
        int textH = f.lineHeight;

        float scaleW = textW <= 0 ? 1f : (float) maxWidth / (float) textW;
        float scaleH = (float) maxHeight / (float) textH;
        float scale = Math.min(1f, Math.min(scaleW, scaleH));
        boolean truncated = false;
        String rendered = text;

        if (scale < MIN_AUTO_SCALE) {
            // Even the smallest acceptable scale can't fit — clamp scale and truncate.
            scale = MIN_AUTO_SCALE;
            int effectiveMaxW = (int) Math.floor(maxWidth / scale);
            rendered = truncate(f, text, effectiveMaxW);
            truncated = !rendered.equals(text);
            textW = f.width(rendered);
        }

        int scaledW = (int) Math.ceil(textW * scale);
        int scaledH = (int) Math.ceil(textH * scale);
        int drawX;
        switch (align) {
            case CENTER -> drawX = x + (maxWidth - scaledW) / 2;
            case RIGHT -> drawX = x + (maxWidth - scaledW);
            default -> drawX = x;
        }
        int drawY = y + (maxHeight - scaledH) / 2;

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(drawX, drawY);
        pose.scale(scale, scale);
        g.drawString(f, rendered, 0, 0, color, false);
        pose.popMatrix();

        if (truncated) {
            maybeTooltip(g, text, drawX, drawY, scaledW, scaledH, mouseX, mouseY);
        }
    }

    public static String truncate(Font f, String text, int maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (f.width(text) <= maxWidth) return text;
        int ellipsisW = f.width(ELLIPSIS);
        if (ellipsisW >= maxWidth) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                String candidate = out.toString() + text.charAt(i);
                if (f.width(candidate) > maxWidth) break;
                out.append(text.charAt(i));
            }
            return out.toString();
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = out.toString() + text.charAt(i) + ELLIPSIS;
            if (f.width(candidate) > maxWidth) break;
            out.append(text.charAt(i));
        }
        return out + ELLIPSIS;
    }

    // ===== Helpers =====

    private static void maybeTooltip(GuiGraphics g, String fullText, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (mouseX == Integer.MIN_VALUE || mouseY == Integer.MIN_VALUE) return;
        if (fullText == null || fullText.isEmpty()) return;
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return;
        g.setTooltipForNextFrame(font(), Component.literal(fullText), mouseX, mouseY);
    }

    public enum Alignment {LEFT, CENTER, RIGHT}
}

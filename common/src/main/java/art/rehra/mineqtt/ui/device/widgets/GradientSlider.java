package art.rehra.mineqtt.ui.device.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.util.function.DoubleToIntFunction;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * An {@link IntSlider} whose track shows what it picks: a colour for every position, e.g. the hues of a light. It draws
 * no text; its label belongs beside it. A dimmed slider shows a value the device does not use now (a colour while the
 * light shows a colour temperature): its handle is faded until the player picks with it.
 */
public class GradientSlider extends IntSlider {

    private static final Identifier HANDLE = Identifier.withDefaultNamespace("widget/slider_handle");
    private static final Identifier HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted");
    private static final int HANDLE_W = 8;
    // The track is narrower than the handle, so the handle stands out over light and dark colours alike.
    private static final int TRACK_INSET = 2;
    private static final int BORDER = 0xFF000000;
    private static final int FOCUS_BORDER = 0xFFFFFFFF;
    private static final int INACTIVE_SHADE = 0xA0C6C6C6;

    private final DoubleToIntFunction colorAt;
    private boolean dimmed;

    /**
     * @param colorAt the colour of the track at a position from 0 to 1, as ARGB
     * @param label   what narration reads for a number
     */
    public GradientSlider(int x, int y, int width, int height, int min, int max, int step, int number, DoubleToIntFunction colorAt,
                          IntFunction<Component> label, IntConsumer changed) {
        super(x, y, width, height, min, max, step, number, label, changed);
        this.colorAt = colorAt;
    }

    public void setDimmed(boolean dimmed) {
        this.dimmed = dimmed;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int left = getX() + HANDLE_W / 2;
        int top = getY() + TRACK_INSET;
        int width = getWidth() - HANDLE_W;
        int bottom = getY() + getHeight() - TRACK_INSET;
        g.fill(left - 1, top - 1, left + width + 1, bottom + 1, isFocused() ? FOCUS_BORDER : BORDER);
        for (int column = 0; column < width; column++) {
            g.fill(left + column, top, left + column + 1, bottom, colorAt.applyAsInt(width == 1 ? 0 : column / (double) (width - 1)));
        }
        if (!active) g.fill(left, top, left + width, bottom, INACTIVE_SHADE);
        float handleAlpha = dimmed && !held() ? 0.45f : 1f;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, isHoveredOrFocused() && active ? HANDLE_HIGHLIGHTED : HANDLE,
                getX() + (int) (value * (getWidth() - HANDLE_W)), getY(), HANDLE_W, getHeight(), ARGB.white(handleAlpha * alpha));
        handleCursor(g);
    }
}

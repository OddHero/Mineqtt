package art.rehra.mineqtt.ui.device.widgets;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A vanilla slider over whole numbers from {@code min} to {@code max} in steps of {@code step}. It reports a new number
 * while the player drags or steps it with the arrow keys (after Enter or Space, as vanilla sliders), and takes the
 * device's value from a view without reporting it. While the player holds it, a view does not move it.
 */
public class IntSlider extends AbstractSliderButton {

    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<Component> label;
    private final IntConsumer changed;
    private int number;
    private boolean held;

    /**
     * @param label   the text the slider shows for a number
     * @param changed told each new number the player picks
     */
    public IntSlider(int x, int y, int width, int height, int min, int max, int step, int number, IntFunction<Component> label, IntConsumer changed) {
        super(x, y, width, height, Component.empty(), 0);
        if (min > max || step <= 0) throw new IllegalArgumentException("Empty slider " + min + ".." + max + " by " + step);
        this.min = min;
        this.max = max;
        this.step = step;
        this.label = label;
        this.changed = changed;
        show(number);
    }

    public int number() {
        return number;
    }

    /** Whether the player is dragging it. */
    public boolean held() {
        return held;
    }

    /** Shows a number the player did not pick, e.g. the device's from a new view. */
    public void show(int number) {
        this.number = snap(number);
        this.value = fraction(this.number);
        updateMessage();
    }

    /** The mouse button went up somewhere; the screen does not always pass the release on to the widget. */
    public void release() {
        if (!held) return;
        held = false;
        this.value = fraction(number);
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(number));
    }

    @Override
    protected void applyValue() {
        int next = snap((int) Math.round(min + value * (max - min)));
        if (next == number) return;
        number = next;
        changed.accept(next);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        held = true;
        super.onClick(event, doubleClick);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        release();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (canChangeValue && (event.isLeft() || event.isRight())) {
            // One step per key, not the vanilla pixel.
            setValue(fraction(snap(number + (event.isLeft() ? -step : step))));
            return true;
        }
        return super.keyPressed(event);
    }

    private int snap(int number) {
        int clamped = Math.clamp(number, min, max);
        return Math.min(max, min + Math.round((clamped - min) / (float) step) * step);
    }

    private double fraction(int number) {
        return max == min ? 0 : (number - min) / (double) (max - min);
    }
}

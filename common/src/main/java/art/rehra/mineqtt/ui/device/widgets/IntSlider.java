package art.rehra.mineqtt.ui.device.widgets;

import net.minecraft.client.gui.components.AbstractSliderButton;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
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
    //? if >=1.21.9 {
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        held = true;
        super.onClick(event, doubleClick);
    }
    //?} else {
    /*public void onClick(double mouseX, double mouseY) {
        held = true;
        super.onClick(mouseX, mouseY);
    }
    *///?}

    @Override
    //? if >=1.21.9 {
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
    //?} else {
    /*public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
    *///?}
        release();
    }

    @Override
    //? if >=1.21.9 {
    public boolean keyPressed(KeyEvent event) {
        boolean left = event.isLeft(), right = event.isRight();
    //?} else {
    /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean left = keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT, right = keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT;
    *///?}
        // Before 1.21.11 the slider's own state and setter are private: a focused slider always takes the keys.
        //? if >=1.21.11 {
        if (canChangeValue && (left || right)) {
            // One step per key, not the vanilla pixel.
            setValue(fraction(snap(number + (left ? -step : step))));
            return true;
        }
        //?} else {
        /*if (left || right) {
            // One step per key, not the vanilla pixel.
            double next = fraction(snap(number + (left ? -step : step)));
            if (next != value) {
                value = next;
                applyValue();
                updateMessage();
            }
            return true;
        }
        *///?}
        //? if >=1.21.9 {
        return super.keyPressed(event);
        //?} else {
        /*return super.keyPressed(keyCode, scanCode, modifiers);
        *///?}
    }

    private int snap(int number) {
        int clamped = Math.clamp(number, min, max);
        return Math.min(max, min + Math.round((clamped - min) / (float) step) * step);
    }

    private double fraction(int number) {
        return max == min ? 0 : (number - min) / (double) (max - min);
    }
}

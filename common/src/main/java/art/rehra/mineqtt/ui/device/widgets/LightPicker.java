package art.rehra.mineqtt.ui.device.widgets;

import art.rehra.mineqtt.device.ValueCodecs;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.ui.device.LightControl;
import art.rehra.mineqtt.ui.device.LightControl.HueSaturation;
import art.rehra.mineqtt.ui.framework.GuiText;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The light picker of the Controls tab ({@code DeviceTabs.Widget.LIGHT}): four rows of brightness, hue, saturation and
 * colour temperature, each a track that shows what it picks, with its label to the left and its value to the right.
 * Hue and saturation pick a colour ({@code rgb} mode), the temperature a white ({@code color_temp} mode); the sliders of
 * the mode the light is not in are faded. Every pick goes out as a light command ({@link LightControl}).
 */
public class LightPicker {
    private static final int GAP = 4;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int MUTED_COLOR = 0xFF808080;
    // A hue read back from the lamp's 8-bit colour may be off by this much from the one picked; the slider keeps its own.
    private static final int HUE_SLACK = 2;
    private static final int MID_KELVIN = (ValueCodecs.MIN_KELVIN + ValueCodecs.MAX_KELVIN) / 2;

    private final int x;
    private final int width;
    private final int labelW;
    private final int valueW;
    private final GradientSlider brightness;
    private final GradientSlider hue;
    private final GradientSlider saturation;
    private final GradientSlider kelvin;
    private boolean kelvinMode;

    /**
     * @param top   the top of the first row
     * @param pitch from one row's top to the next
     * @param send  takes each light command the player picks
     */
    public LightPicker(int x, int top, int width, int rowHeight, int pitch, Consumer<JsonObject> send) {
        this.x = x;
        this.width = width;
        Font font = GuiText.font();
        this.labelW = Stream.of("brightness", "hue", "saturation", "temperature").mapToInt(key -> font.width(label(key))).max().orElse(0);
        this.valueW = Stream.of(percent(100), degrees(LightControl.MAX_HUE), kelvinText(ValueCodecs.MAX_KELVIN)).mapToInt(font::width).max().orElse(0);
        int trackX = x + labelW + GAP;
        int trackW = width - labelW - valueW - 2 * GAP;
        brightness = new GradientSlider(trackX, top, trackW, rowHeight, LightControl.MIN_BRIGHTNESS, LightControl.MAX_BRIGHTNESS, 1,
                LightControl.MAX_BRIGHTNESS, t -> scaled(color(), t), value -> label("brightness"), value -> send.accept(LightControl.brightness(value)));
        hue = new GradientSlider(trackX, top + pitch, trackW, rowHeight, 0, LightControl.MAX_HUE, 1, 0,
                t -> argb(new HueSaturation((int) Math.round(t * LightControl.MAX_HUE), LightControl.MAX_SATURATION).rgb()),
                value -> label("hue"), value -> pickColor(send));
        saturation = new GradientSlider(trackX, top + 2 * pitch, trackW, rowHeight, 0, LightControl.MAX_SATURATION, 1, 0,
                t -> argb(new HueSaturation(this.hue.number(), (int) Math.round(t * LightControl.MAX_SATURATION)).rgb()),
                value -> label("saturation"), value -> pickColor(send));
        kelvin = new GradientSlider(trackX, top + 3 * pitch, trackW, rowHeight, ValueCodecs.MIN_KELVIN, ValueCodecs.MAX_KELVIN, LightControl.KELVIN_STEP,
                MID_KELVIN, t -> argb(ValueCodecs.kelvinToRgb((int) Math.round(ValueCodecs.MIN_KELVIN + t * (ValueCodecs.MAX_KELVIN - ValueCodecs.MIN_KELVIN)))),
                value -> label("temperature"), value -> {
            kelvinMode = true;
            send.accept(LightControl.kelvin(value));
        });
    }

    public List<GradientSlider> widgets() {
        return List.of(brightness, hue, saturation, kelvin);
    }

    /** Whether the player is dragging one of its sliders. */
    public boolean held() {
        return widgets().stream().anyMatch(IntSlider::held);
    }

    public void release() {
        widgets().forEach(IntSlider::release);
    }

    /** Shows the device's light; a hue the lamp's colour cannot tell (a grey) or only roughly tells stays as picked. */
    public void show(LightState light) {
        kelvinMode = light.kelvin() != null;
        brightness.show(LightControl.brightness(light));
        if (light.kelvin() != null) kelvin.show(light.kelvin());
        HueSaturation shown = HueSaturation.of(light.color());
        if (shown.saturation() > 0 && Math.abs(shown.hue() - hue.number()) > HUE_SLACK) hue.show(shown.hue());
        saturation.show(shown.saturation());
    }

    /** Whether the player may pick; a picker they may not use is read-only and says why on hover. */
    public void setEditable(boolean editable, @Nullable Tooltip why) {
        for (GradientSlider slider : widgets()) {
            slider.active = editable;
            slider.setTooltip(why);
        }
    }

    /** Draws the labels and values beside the tracks and fades the sliders of the mode the light is not in. */
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        hue.setDimmed(kelvinMode);
        saturation.setDimmed(kelvinMode);
        kelvin.setDimmed(!kelvinMode);
        row(g, brightness, "brightness", percent(LightControl.percent(brightness.number())), false, mouseX, mouseY);
        row(g, hue, "hue", degrees(hue.number()), kelvinMode, mouseX, mouseY);
        row(g, saturation, "saturation", percent(saturation.number()), kelvinMode, mouseX, mouseY);
        row(g, kelvin, "temperature", kelvinText(kelvin.number()), !kelvinMode, mouseX, mouseY);
    }

    private void row(GuiGraphicsExtractor g, GradientSlider slider, String key, String value, boolean faded, int mouseX, int mouseY) {
        int textY = slider.getY() + (slider.getHeight() - GuiText.font().lineHeight) / 2 + 1;
        int color = faded ? MUTED_COLOR : LABEL_COLOR;
        GuiText.drawTruncated(g, label(key), x, textY, labelW, color, mouseX, mouseY);
        GuiText.drawRight(g, value, x + width, textY, valueW, color, mouseX, mouseY);
    }

    private void pickColor(Consumer<JsonObject> send) {
        kelvinMode = false;
        send.accept(LightControl.color(new HueSaturation(hue.number(), saturation.number())));
    }

    // The colour the light shows at full brightness: the picked colour, or the white of the picked temperature.
    private Rgb color() {
        return kelvinMode ? ValueCodecs.kelvinToRgb(kelvin.number()) : new HueSaturation(hue.number(), saturation.number()).rgb();
    }

    private static int scaled(Rgb color, double t) {
        return argb(new Rgb((int) Math.round(color.r() * t), (int) Math.round(color.g() * t), (int) Math.round(color.b() * t)));
    }

    private static int argb(Rgb color) {
        return 0xFF000000 | color.packed();
    }

    private static Component label(String key) {
        return Component.translatable("mineqtt.device.control.light." + key);
    }

    private static String percent(int percent) {
        return Component.translatable("mineqtt.device.control.percent", percent).getString();
    }

    private static String degrees(int degrees) {
        return Component.translatable("mineqtt.device.control.degrees", degrees).getString();
    }

    private static String kelvinText(int kelvin) {
        return Component.translatable("mineqtt.device.control.kelvin", kelvin).getString();
    }
}

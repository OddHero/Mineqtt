package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.ValueCodecs.LightEffect;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.twin.core.DisplayText;
import art.rehra.mineqtt.twin.core.TwinHealth;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.storage.ValueInput;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * A device's update tag (M2 plan D12, design §6.9): the clients in range get only what they draw, in one {@value #KEY}
 * compound, and never the owner, the topics, the Raw Link or the slots. A device's screens read the menu's open data
 * and its {@link DeviceView}s instead. Loading tells the three kinds of data apart ({@link #dispatch}), so a client
 * never takes an update packet for v1 data, and never runs the v1 migrator on one.
 */
public final class RenderTag {
    public static final String KEY = "mineqtt_render";
    private static final String RGB = "rgb";
    private static final String BRIGHTNESS = "brightness";
    private static final String ON = "on";
    private static final String EFFECT = "effect";
    private static final String TWIN = "twin";
    private static final String TWIN_PENDING = "twin_pending";
    private static final String PANEL_TITLE = "title";
    private static final String PANEL_LINES = "lines";
    private static final String PANEL_LAYOUT = "layout";
    private static final String PANEL_SIZE = "size";
    private static final String PANEL_AT = "at";
    private static final String PANEL_COLOR = "color";
    private static final String PANEL_GLOW = "glow";
    private static final String PANEL_WAITING = "waiting";

    /** What a device block entity is loading. */
    public enum Data {
        /** A {@code mineqtt} compound: v2 data, which {@link DeviceLink#load} reads. */
        DEVICE,
        /** Only a {@value #KEY} compound: a client's render update, which replaces nothing but what it carries. */
        RENDER,
        /** Neither: v1 data, which the attach adopts. */
        V1
    }

    private RenderTag() {
    }

    /**
     * The dispatch table: a {@code mineqtt} compound is v2 data whatever else the tag holds, a render compound alone is
     * a render update, and anything else is v1 data.
     *
     * @param device whether the tag has a {@code mineqtt} compound
     * @param render whether it has a {@value #KEY} compound
     */
    public static Data dispatch(boolean device, boolean render) {
        if (device) return Data.DEVICE;
        return render ? Data.RENDER : Data.V1;
    }

    public static Data dispatch(ValueInput input) {
        return dispatch(input.child(DeviceLink.KEY).isPresent(), input.child(KEY).isPresent());
    }

    /** The update tag of a device: its render data, and nothing else; empty render data for a device that draws none. */
    public static CompoundTag updateTag(CompoundTag render) {
        return updateTag(render, TwinHealth.Hint.NONE);
    }

    /**
     * The update tag of a device with its twin hint (M3 plan D6, D19): one {@value #TWIN} byte while its source's health
     * changes the drawing, so an unbound device's tag stays as it was. Never the entity or its name (D13 #7).
     */
    public static CompoundTag updateTag(CompoundTag render, TwinHealth.Hint hint) {
        return updateTag(render, hint, false);
    }

    /**
     * The update tag of a device with its twin hint and, while a request of it waits for Home Assistant (M3 plan D6), one
     * {@value #TWIN_PENDING} byte; neither while there is nothing to draw, so an unbound device's tag stays as it was.
     */
    public static CompoundTag updateTag(CompoundTag render, TwinHealth.Hint hint, boolean pending) {
        CompoundTag tag = new CompoundTag();
        CompoundTag data = render.copy();
        if (hint != TwinHealth.Hint.NONE) data.putByte(TWIN, (byte) hint.ordinal());
        if (pending) data.putByte(TWIN_PENDING, (byte) 1);
        tag.put(KEY, data);
        return tag;
    }

    /** Whether a render update says a request of the device waits for Home Assistant. */
    public static boolean twinPending(ValueInput input) {
        return input.childOrEmpty(KEY).getByteOr(TWIN_PENDING, (byte) 0) != 0;
    }

    /** The twin hint a render update carries; {@link TwinHealth.Hint#NONE} without one. */
    public static TwinHealth.Hint twinHint(ValueInput input) {
        return TwinHealth.Hint.ofByte(input.childOrEmpty(KEY).getByteOr(TWIN, (byte) 0));
    }

    /**
     * What a client draws of a Smart Lamp: its colour, brightness and whether it is on, which give the tint and the
     * particles, and its effect, which the client animates, only while it has one. A colour temperature is sent as the
     * colour it shows.
     */
    public static CompoundTag lamp(LightState light) {
        CompoundTag render = new CompoundTag();
        render.putInt(RGB, light.color().packed());
        render.putInt(BRIGHTNESS, light.brightness());
        render.putBoolean(ON, light.on());
        if (light.effect() != LightEffect.NONE) render.putString(EFFECT, light.effect().getSerializedName());
        return render;
    }

    /**
     * What a client draws of a Display Panel (M3 plan D18): its finished lines and the size they were laid out in, or
     * the point in time it shows with its size setting; its title while it mirrors an entity; colour, glow, and whether it
     * waits for its source. About 300 bytes at most; never the entity (D13 #7).
     */
    public static CompoundTag panel(DisplayPanel.Render render) {
        CompoundTag tag = new CompoundTag();
        if (render.title() != null) tag.putString(PANEL_TITLE, render.title());
        if (render.at().isPresent()) {
            tag.putLong(PANEL_AT, render.at().getAsLong());
            tag.putByte(PANEL_SIZE, (byte) render.size().ordinal());
        } else {
            ListTag lines = new ListTag();
            render.lines().forEach(line -> lines.add(StringTag.valueOf(line)));
            tag.put(PANEL_LINES, lines);
            tag.putByte(PANEL_LAYOUT, (byte) render.layout().ordinal());
        }
        tag.putByte(PANEL_COLOR, (byte) render.color().getId());
        tag.putBoolean(PANEL_GLOW, render.glow());
        if (render.waiting()) tag.putBoolean(PANEL_WAITING, true);
        return tag;
    }

    /**
     * The panel a render update carries, bounded as the server bounds it: at most {@value DisplayText#MAX_LINES} lines of
     * {@value DisplayText#MAX_COLUMNS} characters and a title of as many; unknown sizes and colours read as the defaults.
     */
    public static DisplayPanel.Render panel(ValueInput input) {
        ValueInput render = input.childOrEmpty(KEY);
        String title = render.getString(PANEL_TITLE).map(text -> bounded(text, DisplayText.MAX_COLUMNS)).orElse(null);
        List<String> lines = new ArrayList<>();
        render.list(PANEL_LINES, Codec.STRING).ifPresent(list -> list.stream().limit(DisplayText.MAX_LINES - (title == null ? 0 : 1))
                .forEach(line -> lines.add(bounded(line, DisplayText.MAX_COLUMNS))));
        DisplayText.Size[] layouts = DisplayText.Size.values();
        int layout = render.getByteOr(PANEL_LAYOUT, (byte) DisplayText.Size.LARGE.ordinal());
        DisplayText.Size shownLayout = layout > 0 && layout < layouts.length ? layouts[layout] : DisplayText.Size.LARGE;
        DisplayPanel.Size[] sizes = DisplayPanel.Size.values();
        int size = render.getByteOr(PANEL_SIZE, (byte) 0);
        OptionalLong at = render.getLong(PANEL_AT).map(OptionalLong::of).orElse(OptionalLong.empty());
        return new DisplayPanel.Render(title, lines, shownLayout, size >= 0 && size < sizes.length ? sizes[size] : DisplayPanel.Size.AUTO,
                DyeColor.byId(render.getByteOr(PANEL_COLOR, (byte) DisplayPanel.DEFAULT_COLOR.getId())), render.getBooleanOr(PANEL_GLOW, true), at,
                render.getBooleanOr(PANEL_WAITING, false));
    }

    // Text cut to `columns` code points.
    private static String bounded(String text, int columns) {
        return text.codePointCount(0, text.length()) <= columns ? text : text.substring(0, text.offsetByCodePoints(0, columns));
    }

    /** The light a lamp's render update carries; what is missing keeps {@link LightState#DEFAULT}'s value. */
    public static LightState lamp(ValueInput input) {
        ValueInput render = input.childOrEmpty(KEY);
        LightState fallback = LightState.DEFAULT;
        return new LightState(render.getBooleanOr(ON, fallback.on()), render.getIntOr(BRIGHTNESS, fallback.brightness()),
                Rgb.ofPacked(render.getIntOr(RGB, fallback.color().packed())), null,
                render.getString(EFFECT).flatMap(LightEffect::byName).orElse(fallback.effect()));
    }
}

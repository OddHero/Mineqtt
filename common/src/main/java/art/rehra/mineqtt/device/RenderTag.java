package art.rehra.mineqtt.device;

import art.rehra.mineqtt.device.ValueCodecs.LightEffect;
import art.rehra.mineqtt.device.ValueCodecs.LightState;
import art.rehra.mineqtt.device.ValueCodecs.Rgb;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;

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
        CompoundTag tag = new CompoundTag();
        tag.put(KEY, render.copy());
        return tag;
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

    /** The light a lamp's render update carries; what is missing keeps {@link LightState#DEFAULT}'s value. */
    public static LightState lamp(ValueInput input) {
        ValueInput render = input.childOrEmpty(KEY);
        LightState fallback = LightState.DEFAULT;
        return new LightState(render.getBooleanOr(ON, fallback.on()), render.getIntOr(BRIGHTNESS, fallback.brightness()),
                Rgb.ofPacked(render.getIntOr(RGB, fallback.color().packed())), null,
                render.getString(EFFECT).flatMap(LightEffect::byName).orElse(fallback.effect()));
    }
}

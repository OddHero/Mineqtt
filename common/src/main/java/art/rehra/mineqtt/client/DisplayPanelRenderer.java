package art.rehra.mineqtt.client;

import art.rehra.mineqtt.blocks.DisplayPanelBlock;
import art.rehra.mineqtt.blocks.entities.DisplayPanelBlockEntity;
import art.rehra.mineqtt.device.types.DisplayPanel;
import art.rehra.mineqtt.twin.core.DisplayText;
import art.rehra.mineqtt.twin.core.TwinHealth;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
//? if >=1.21.9 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
//? if >=1.21.9 {
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?}
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;

/**
 * Draws a Display Panel's text on its screen (M3 plan D18), with the verified 26.2 renderer API (plan Appendix C): the
 * render state is extracted from the block entity each frame and submitted as text quads through
 * {@link SubmitNodeCollector#submitText}, as the sign renderer submits its lines.
 * <ul>
 *     <li>The server lays the text out; the renderer fits it to the screen: one scale for the value, the largest at which
 *     its line slots (2, 3 or 4 by size) fill the screen's height and its widest line its width; a mirrored panel's
 *     title small above it.</li>
 *     <li>Full bright while the panel glows, else in the block's light, in the panel's dye colour.</li>
 *     <li>A point in time is worded on the client against its own clock each frame ({@link DisplayPanel.TimeShown}), so
 *     "in 2 h 14 min" counts down without a server update.</li>
 *     <li>Twin states (D19): "Waiting for HA…" while a mirrored source has no value yet, "Unavailable" in grey while it
 *     has none, a small "⟳" while a request waits.</li>
 * </ul>
 * Client only; registered from both platform client inits. The view distance is the default one, as for signs.
 */
// The renderer extracts a render state and submits it from 1.21.9 on; before, it draws in render, from the same state.
//? if >=1.21.9 {
public class DisplayPanelRenderer implements BlockEntityRenderer<DisplayPanelBlockEntity, DisplayPanelRenderState> {
//?} else {
/*public class DisplayPanelRenderer implements BlockEntityRenderer<DisplayPanelBlockEntity> {
*///?}
    /** The side of the square text area on the screen, in blocks: the screen's 12 pixels less half a pixel each side. */
    public static final float TEXT_AREA = 11f / 16f;
    // How far in front of the plate's face the text floats, against z-fighting (with the polygon offset).
    private static final float Z_OFFSET = 0.002f;
    // The gap under a title, as a share of its height.
    private static final float TITLE_GAP = 0.3f;
    private static final int GREY = 0xFF8A8A8A;
    private static final int LIGHT_GREY = 0xFFB4B4B4;
    private static final int PENDING_WHITE = 0xFFE8E8E8;
    private static final String PENDING_GLYPH = "⟳";

    private final Font font;

    public DisplayPanelRenderer(BlockEntityRendererProvider.Context context) {
        //? if >=1.21.9 {
        this.font = context.font();
        //?} else {
        /*this.font = context.getFont();
        *///?}
    }

    //? if >=1.21.9 {
    @Override
    public DisplayPanelRenderState createRenderState() {
        return new DisplayPanelRenderState();
    }

    @Override
    public void extractRenderState(DisplayPanelBlockEntity panel, DisplayPanelRenderState state, float partialTick, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(panel, state, partialTick, cameraPosition, breakProgress);
        extractText(panel, state, System.currentTimeMillis());
    }

    @Override
    public void submit(DisplayPanelRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        // submitText(pose, x, y, text, shadow, mode, light, colour, background, outline): the order AbstractSignRenderer uses.
        draw(state, pose, (at, x, text, light, color) ->
                collector.submitText(at, x, 0, text, false, Font.DisplayMode.POLYGON_OFFSET, light, color, 0, 0));
    }
    //?} else if >=1.21.5 {
    /*@Override
    public void render(DisplayPanelBlockEntity panel, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay,
                       net.minecraft.world.phys.Vec3 cameraPosition) {
        render(panel, pose, buffers, light);
    }
    *///?} else {
    /*@Override
    public void render(DisplayPanelBlockEntity panel, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        render(panel, pose, buffers, light);
    }
    *///?}

    //? if <1.21.9 {
    /*private void render(DisplayPanelBlockEntity panel, PoseStack pose, MultiBufferSource buffers, int light) {
        DisplayPanelRenderState state = new DisplayPanelRenderState();
        state.lightCoords = light;
        extractText(panel, state, System.currentTimeMillis());
        draw(state, pose, (at, x, text, textLight, color) ->
                font.drawInBatch(text, x, 0, color, false, at.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, textLight));
    }
    *///?}

    // Where draw puts its text: at the pose, x font units from the line's left, in that light and colour.
    private interface TextSink {
        void text(PoseStack pose, float x, FormattedCharSequence text, int light, int color);
    }

    /**
     * What the panel shows now, without the font: facing, title, lines, layout, colours and light (the block's light
     * must be extracted first). The client game test reads it as the renderer does.
     *
     * @param now the client's clock, which a point in time is worded against
     */
    public static void extractText(DisplayPanelBlockEntity panel, DisplayPanelRenderState state, long now) {
        BlockState blockState = panel.getBlockState();
        state.facing = blockState.hasProperty(DisplayPanelBlock.FACING) ? blockState.getValue(DisplayPanelBlock.FACING) : Direction.NORTH;
        DisplayPanel.Render render = panel.shown();
        int dye = ARGB.opaque(render.color().getTextColor());
        state.color = dye;
        state.titleColor = ARGB.opaque(ARGB.scaleRGB(dye, 0.7f));
        state.textLight = render.glow() ? LightCoordsUtil.FULL_BRIGHT : state.lightCoords;
        state.pending = panel.twinPending();
        state.title = render.title();
        if (render.waiting()) {
            shown(state, Component.translatable("mineqtt.display_panel.waiting").getString(), DisplayText.Size.AUTO);
            state.color = LIGHT_GREY;
        } else if (panel.twinHint() == TwinHealth.Hint.UNAVAILABLE) {
            shown(state, Component.translatable("mineqtt.display_panel.unavailable").getString(), DisplayText.Size.AUTO);
            state.color = GREY;
        } else if (render.at().isPresent()) {
            shown(state, time(DisplayPanel.TimeShown.of(render.at().getAsLong(), now, ZoneId.systemDefault())), render.size().layout());
        } else {
            state.lines = render.lines();
            state.layout = render.layout();
        }
    }

    // Lays out text the client words itself, under the title when there is one, as the server lays out a message.
    private static void shown(DisplayPanelRenderState state, String text, DisplayText.Size size) {
        DisplayText.Layout layout = state.title == null ? DisplayText.layout(text, size) : DisplayText.titled(state.title, text, size);
        state.title = layout.title();
        state.lines = layout.lines();
        state.layout = layout.size();
    }

    /** A point in time as the client words it: "now", "in 2 h 14 min", "5 min ago", "07:30", "10-12 07:30". */
    static String time(DisplayPanel.TimeShown shown) {
        return switch (shown.kind()) {
            case NOW -> Component.translatable("mineqtt.display_panel.time.now").getString();
            case IN -> Component.translatable("mineqtt.display_panel.time.in", duration(shown)).getString();
            case AGO -> Component.translatable("mineqtt.display_panel.time.ago", duration(shown)).getString();
            case CLOCK -> shown.clock();
            case DATE -> shown.date();
        };
    }

    private static String duration(DisplayPanel.TimeShown shown) {
        if (shown.hours() == 0) return Component.translatable("mineqtt.display_panel.time.minutes", shown.minutes()).getString();
        if (shown.minutes() == 0) return Component.translatable("mineqtt.display_panel.time.hours", shown.hours()).getString();
        return Component.translatable("mineqtt.display_panel.time.hours_minutes", shown.hours(), shown.minutes()).getString();
    }

    private void draw(DisplayPanelRenderState state, PoseStack pose, TextSink sink) {
        if (state.lines.isEmpty() && state.title == null && !state.pending) return;
        pose.pushPose();
        // Into the screen's plane: the block's centre, turned so that +z is where the screen faces, onto the plate's face.
        pose.translate(0.5f, 0.5f, 0.5f);
        //? if >=26.3 {
        /*pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(-state.facing.toYRot())));
        *///?} else {
        pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
        //?}
        pose.translate(0f, 0f, -(0.5f - DisplayPanelBlock.THICKNESS / 16f) + Z_OFFSET);

        float lineHeight = font.lineHeight;
        float titleScale = 0;
        float titleHeight = 0;
        if (state.title != null) {
            titleScale = Math.min(TEXT_AREA / (DisplayText.Size.SMALL.lines() * lineHeight), TEXT_AREA / Math.max(1, font.width(state.title)));
            titleHeight = lineHeight * titleScale;
        }
        float gap = state.title == null ? 0 : titleHeight * TITLE_GAP;
        int slots = state.title == null ? state.layout.lines() : Math.min(state.layout.lines(), DisplayText.TITLED_VALUE_LINES);
        int widest = 1;
        for (String line : state.lines) widest = Math.max(widest, font.width(line));
        float valueScale = Math.min((TEXT_AREA - titleHeight - gap) / (Math.max(1, slots) * lineHeight), TEXT_AREA / widest);
        float total = titleHeight + gap + state.lines.size() * lineHeight * valueScale;
        float top = total / 2;
        if (state.title != null) {
            line(pose, sink, state.title, top, titleScale, state.titleColor, state.textLight);
            top -= titleHeight + gap;
        }
        for (String line : state.lines) {
            line(pose, sink, line, top, valueScale, state.color, state.textLight);
            top -= lineHeight * valueScale;
        }
        if (state.pending) {
            float scale = TEXT_AREA / (DisplayText.Size.SMALL.lines() * lineHeight) * 0.6f;
            pose.pushPose();
            pose.translate(TEXT_AREA / 2 - font.width(PENDING_GLYPH) * scale, TEXT_AREA / 2, 0f);
            pose.scale(scale, -scale, scale);
            sink.text(pose, 0, Component.literal(PENDING_GLYPH).getVisualOrderText(), LightCoordsUtil.FULL_BRIGHT, PENDING_WHITE);
            pose.popPose();
        }
        pose.popPose();
    }

    // One line centred on the screen with its top edge at `top` (blocks, up from the screen's middle), `scale` blocks per
    // font unit.
    private void line(PoseStack pose, TextSink sink, String text, float top, float scale, int color, int light) {
        FormattedCharSequence sequence = Component.literal(text).getVisualOrderText();
        pose.pushPose();
        pose.translate(0f, top, 0f);
        pose.scale(scale, -scale, scale);
        sink.text(pose, -font.width(sequence) / 2f, sequence, light, color);
        pose.popPose();
    }
}

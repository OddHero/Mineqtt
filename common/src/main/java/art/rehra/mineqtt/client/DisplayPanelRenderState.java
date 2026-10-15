package art.rehra.mineqtt.client;

import art.rehra.mineqtt.twin.core.DisplayText;
//? if >=1.21.9 {
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
//?}
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What {@link DisplayPanelRenderer} draws of one Display Panel in a frame, extracted from its block entity on the client
 * (the 26.2 render-state split): the finished lines, how large they are drawn and in which colours and light.
 */
//? if >=1.21.9 {
public class DisplayPanelRenderState extends BlockEntityRenderState {
//?} else {
/*public class DisplayPanelRenderState {
    // The block's light, as the renderer is given it (a BlockEntityRenderState holds it from 1.21.9 on).
    public int lightCoords;
*///?}
    /** Where the panel's screen faces. */
    public Direction facing = Direction.NORTH;
    /** The small line over a mirrored panel's value; null for none. */
    public @Nullable String title;
    /** The lines of the value, at most {@value DisplayText#MAX_LINES}. */
    public List<String> lines = List.of();
    /** The size the lines are laid out in: how many line slots the screen has for them. */
    public DisplayText.Size layout = DisplayText.Size.LARGE;
    /** The colour of the value, opaque ARGB. */
    public int color;
    /** The colour of the title, opaque ARGB. */
    public int titleColor;
    /** The light the text is drawn with: full bright while the panel glows, else the block's. */
    public int textLight;
    /** Whether a request of the panel waits for Home Assistant: a small glyph in the corner (M3 plan D19). */
    public boolean pending;
}

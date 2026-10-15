package art.rehra.mineqtt.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The GUI's pose: a 2D matrix stack from 1.21.6 on, the 3D PoseStack before. */
public final class GuiPose {

    private GuiPose() {
    }

    public static void push(GuiGraphicsExtractor g) {
        //? if >=1.21.6 {
        g.pose().pushMatrix();
        //?} else {
        /*g.pose().pushPose();
        *///?}
    }

    public static void pop(GuiGraphicsExtractor g) {
        //? if >=1.21.6 {
        g.pose().popMatrix();
        //?} else {
        /*g.pose().popPose();
        *///?}
    }

    public static void translate(GuiGraphicsExtractor g, float x, float y) {
        //? if >=1.21.6 {
        g.pose().translate(x, y);
        //?} else {
        /*g.pose().translate(x, y, 0);
        *///?}
    }

    public static void scale(GuiGraphicsExtractor g, float scale) {
        //? if >=1.21.6 {
        g.pose().scale(scale, scale);
        //?} else {
        /*g.pose().scale(scale, scale, 1);
        *///?}
    }
}

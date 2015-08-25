package v55v551n.Mineqtt.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public class GuiBlockSender extends GuiScreen {

    @Override
    public void initGui(){
        buttonList.clear();
        buttonList.add(new GuiButton(0,10,10,"Test"));
        super.initGui();
    }

    public GuiBlockSender(){

    }

    @Override
    public void drawScreen(int x, int y, float ticks){
        this.drawGradientRect(0, 0, this.width, this.height, -2072689236, -304253680);
        this.drawHorizontalLine(0, this.width, y, -0xFFFFFF);
        this.drawVerticalLine(x,this.height,0,-0xFFFFFF);
        this.drawGradientRect(0,0,x,y,-0x000000,-0xFFFFFF);

    }
}

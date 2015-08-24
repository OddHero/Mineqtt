package v55v551n.Mineqtt.handler;

import v55v551n.Mineqtt.client.gui.GuiBlockSender;
import v55v551n.Mineqtt.reference.GUIs;
import v55v551n.Mineqtt.utility.LogHelper;
import cpw.mods.fml.common.network.IGuiHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

public class GuiHandler implements IGuiHandler{
    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {

        return null;
    }

    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if(ID == 0){
            return new GuiBlockSender();
        }

        return null;
    }
}

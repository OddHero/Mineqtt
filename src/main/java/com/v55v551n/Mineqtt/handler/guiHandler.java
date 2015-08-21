package com.v55v551n.Mineqtt.handler;

import com.v55v551n.Mineqtt.client.gui.GuiBlockSender;
import com.v55v551n.Mineqtt.reference.GUIs;
import com.v55v551n.Mineqtt.utility.LogHelper;
import cpw.mods.fml.common.network.IGuiHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

public class guiHandler implements IGuiHandler{
    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {

        return null;
    }

    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        LogHelper.info("Creating gui: "+GUIs.SENDING_GUI.ordinal());
        if(ID == 0){
            return new GuiBlockSender();
        }

        return null;
    }
}

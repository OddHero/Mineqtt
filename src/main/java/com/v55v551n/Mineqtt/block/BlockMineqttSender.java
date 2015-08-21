package com.v55v551n.Mineqtt.block;

import com.v55v551n.Mineqtt.creativetab.CreativeTabMineqtt;
import com.v55v551n.Mineqtt.reference.GUIs;
import com.v55v551n.Mineqtt.utility.LogHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

public class BlockMineqttSender extends BlockMineqtt {

    public BlockMineqttSender(){

        super();
        this.setBlockName("mqttsenderblock");
    }

    public boolean onBlockActivated(World world,int x, int y, int z, EntityPlayer player, int side, float hitX, float hitY, float hitZ){
        if(world.isRemote){
            LogHelper.info("Open gui: " + GUIs.SENDING_GUI.ordinal());
            player.openGui(player, 0,world,x,y,z);
        }

        return true;
    }
}

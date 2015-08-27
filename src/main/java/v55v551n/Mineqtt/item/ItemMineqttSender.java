package v55v551n.Mineqtt.item;

import v55v551n.Mineqtt.Mineqtt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

public class ItemMineqttSender extends ItemMineqtt {
    public ItemMineqttSender(){
        super();
        this.setUnlocalizedName("mqttsender");
        this.setMaxStackSize(1);
    }

    public boolean onItemUse(ItemStack itemStack, EntityPlayer entityPlayer, World world, int x, int y, int z, int side, float faceX, float faceY, float faceZ)
    {
        String message;
        message = world.getBlock(x,y,z).getLocalizedName();
        if(!world.isRemote){
            Mineqtt.mqttThread.sendMessage("test",message);
            return true;
        }else{
            return false;
        }
    }
}

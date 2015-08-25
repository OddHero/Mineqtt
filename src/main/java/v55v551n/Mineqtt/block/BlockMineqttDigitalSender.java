package v55v551n.Mineqtt.block;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.DamageSource;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import v55v551n.Mineqtt.Mineqtt;
import v55v551n.Mineqtt.utility.LogHelper;

public class BlockMineqttDigitalSender extends BlockMineqtt {

    private int strength;


    public BlockMineqttDigitalSender(){
        super();
        this.setBlockName("mqttdigitalsender");
    }

    @Override
    public void onEntityCollidedWithBlock(World p_149670_1_, int p_149670_2_, int p_149670_3_, int p_149670_4_, Entity entity) {

    }



    public boolean canConnectRedstone(IBlockAccess world, int x, int y, int z, int side)
    {
        return false;
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbour) {

        if (!world.isRemote) {
            if (world.getBlockPowerInput(x, y, z) != strength) {
                strength = world.getBlockPowerInput(x, y, z);
                Mineqtt.sendHandler.sendMessage("digital/" + x + "/" + y + "/" + z, "true");
                LogHelper.info(strength);
            }
        }
    }
}

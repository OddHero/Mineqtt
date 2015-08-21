package com.v55v551n.Mineqtt.init;

import com.v55v551n.Mineqtt.block.BlockMineqtt;
import com.v55v551n.Mineqtt.block.BlockMineqttSender;
import cpw.mods.fml.common.registry.GameRegistry;

public class ModBlocks {

    public static final BlockMineqtt MineqttSenderBlock = new BlockMineqttSender();

    public static void init(){
        GameRegistry.registerBlock(MineqttSenderBlock,"MineqttSenderBlock");
    }

}
